package io.kestrel.research.data

import io.kestrel.engine.llm.EmbeddingModel
import io.kestrel.engine.llm.LanguageModel
import io.kestrel.engine.llm.LlamaEmbedder
import io.kestrel.engine.llm.LlamaModel
import io.kestrel.engine.llm.LoadOptions
import io.kestrel.engine.llm.ModelRole
import io.kestrel.engine.research.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ModelEntry(val file: File, val role: ModelRole?, val autoRole: ModelRole?) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/** GGUF files on the phone and the role each one plays. */
class ModelCatalog(private val storage: Storage, private val settings: Settings) {
    fun entries(): List<ModelEntry> = storage.modelFiles().map { f ->
        val auto = guessRole(f.name)
        val role = if (settings.roleDisabled(f.name)) null else settings.roleOverride(f.name) ?: auto
        ModelEntry(f, role, auto)
    }

    /** The first file assigned to each role. */
    fun byRole(): Map<ModelRole, File> {
        val out = LinkedHashMap<ModelRole, File>()
        for (e in entries()) if (e.role != null && e.role !in out) out[e.role] = e.file
        return out
    }

    companion object {
        /** Filename heuristics; the user can override any assignment in Library. */
        fun guessRole(name: String): ModelRole? {
            val n = name.lowercase()
            return when {
                "mmproj" in n -> null
                Regex("embed|bge-|e5-|minilm|arctic-embed|gte-").containsMatchIn(n) -> ModelRole.EMBED
                "rerank" in n -> null
                Regex("(\\d{2,3})b-a\\d+(\\.\\d)?b|a3b|a4b|a2b|20b|24b|26b|27b|30b|35b").containsMatchIn(n) -> ModelRole.DEEP
                Regex("e2b|0\\.[5-9]b|1b|1\\.[0-9]b|2b|lfm2\\.5-1").containsMatchIn(n) -> ModelRole.FAST
                Regex("e4b|3b|4b|7b|8b|9b").containsMatchIn(n) -> ModelRole.STRONG
                else -> ModelRole.STRONG
            }
        }
    }
}

data class RuntimeStatus(
    val loaded: List<String> = emptyList(),
    val loading: String? = null,
    val lastLoadMs: Long = 0,
    val threads: Int = 0,
    val error: String? = null,
)

/**
 * Loads models by role, within a memory budget. The deep (streamed MoE) and strong tiers never
 * stay resident together; the fast tier is kept when possible because the pipeline calls it for
 * planning, per-hop extraction and verification.
 */
class AndroidModelProvider(
    private val catalog: ModelCatalog,
    private val settings: Settings,
    private val device: DeviceInfo,
) : ModelProvider {
    private val mutex = Mutex()
    private val loaded = LinkedHashMap<String, Pair<ModelRole, LlamaModel>>() // path -> model
    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status
    private var embedder: Pair<String, LlamaEmbedder>? = null

    val threads: Int get() = settings.threads.takeIf { it > 0 } ?: device.bigCores()

    override val available: Set<ModelRole> get() = catalog.byRole().keys - ModelRole.EMBED

    override suspend fun get(role: ModelRole): LanguageModel? = mutex.withLock {
        val file = catalog.byRole()[role] ?: return@withLock null
        loaded[file.absolutePath]?.let { return@withLock it.second }
        // memory policy: deep and strong are mutually exclusive
        val conflicting = when (role) {
            ModelRole.DEEP -> setOf(ModelRole.STRONG, ModelRole.DEEP)
            ModelRole.STRONG -> setOf(ModelRole.DEEP, ModelRole.STRONG)
            else -> setOf(ModelRole.FAST)
        }
        loaded.entries.filter { it.value.first in conflicting }.map { it.key }.forEach { unloadLocked(it) }
        val budget = budgetMb()
        val need = estimateMb(file, role)
        while (loaded.isNotEmpty() && residentMb() + need > budget) {
            unloadLocked(loaded.keys.first())
        }
        _status.value = _status.value.copy(loading = file.name, error = null)
        val t0 = System.currentTimeMillis()
        val model = try {
            withContext(Dispatchers.IO) {
                LlamaModel.load(
                    file.nameWithoutExtension, file.absolutePath,
                    LoadOptions(
                        contextSize = when (role) { ModelRole.FAST -> 4096; ModelRole.STRONG -> 6144; else -> 4096 },
                        batchSize = 512,
                        threads = threads,
                        streamExperts = role == ModelRole.DEEP && settings.streamExperts,
                    ),
                )
            }
        } catch (t: Throwable) {
            _status.value = _status.value.copy(loading = null, error = "Failed to load ${file.name}: ${t.message}")
            throw t
        }
        loaded[file.absolutePath] = role to model
        _status.value = RuntimeStatus(
            loaded = loaded.values.map { "${it.first.name.lowercase()}: ${it.second.id}" },
            loading = null, lastLoadMs = System.currentTimeMillis() - t0, threads = threads,
        )
        model
    }

    /** Query embedder for dense retrieval; loaded once, small. */
    suspend fun embedder(preferredFile: String?): EmbeddingModel? = mutex.withLock {
        val files = catalog.entries().filter { it.role == ModelRole.EMBED }.map { it.file }
        val f = files.firstOrNull { preferredFile != null && it.name.equals(preferredFile, ignoreCase = true) } ?: files.firstOrNull()
            ?: return@withLock null
        embedder?.let { if (it.first == f.absolutePath) return@withLock it.second else it.second.close() }
        val e = withContext(Dispatchers.IO) { LlamaEmbedder.load(f.nameWithoutExtension, f.absolutePath, threads.coerceAtMost(4), 512, 4) }
        embedder = f.absolutePath to e
        e
    }

    suspend fun unloadAll() = mutex.withLock {
        loaded.keys.toList().forEach { unloadLocked(it) }
        embedder?.second?.close()
        embedder = null
        _status.value = RuntimeStatus(threads = threads)
    }

    private fun unloadLocked(path: String) {
        loaded.remove(path)?.second?.close()
        _status.value = _status.value.copy(loaded = loaded.values.map { "${it.first.name.lowercase()}: ${it.second.id}" })
    }

    private fun residentMb(): Long = loaded.values.sumOf { (role, m) -> estimateMb(File(""), role, m.info.size_bytes) }

    private fun estimateMb(file: File, role: ModelRole, size: Long = file.length()): Long {
        val mb = size / 1_048_576
        // a streamed MoE keeps only non-expert weights plus the page-cache working set resident
        return if (role == ModelRole.DEEP && settings.streamExperts) (mb * 0.45).toLong() else mb + 400
    }

    private fun budgetMb(): Long {
        val user = settings.memoryBudgetMb
        if (user > 0) return user.toLong()
        val total = device.memory().totalMb
        return (total * 0.62).toLong().coerceIn(2500, 8000)
    }
}
