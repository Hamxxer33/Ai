package io.kestrel.research

import android.app.Application
import io.kestrel.engine.llm.LlamaNative
import io.kestrel.engine.research.ResearchEngine
import io.kestrel.engine.retrieval.HybridRetriever
import io.kestrel.engine.retrieval.QueryEncoder
import io.kestrel.engine.store.KnowledgePack
import io.kestrel.research.data.AndroidModelProvider
import io.kestrel.research.data.AndroidSqlDb
import io.kestrel.research.data.DeviceInfo
import io.kestrel.research.data.ModelCatalog
import io.kestrel.research.data.Settings
import io.kestrel.research.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class KestrelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Process-wide objects. The engine is rebuilt when the library (models/packs) changes. */
class AppContainer(val app: Application) {
    val settings = Settings(app)
    val device = DeviceInfo(app)
    val storage = Storage(app, device).also { it.ensureDirs() }
    val catalog = ModelCatalog(storage, settings)
    val runtime = AndroidModelProvider(catalog, settings, device)

    private val lock = Mutex()
    private var engine: ResearchEngine? = null
    private var packs: List<KnowledgePack> = emptyList()
    var packErrors: List<String> = emptyList()
        private set

    val nativeReady: Result<Unit> by lazy {
        runCatching { LlamaNative.ensureLoaded(app.applicationInfo.nativeLibraryDir) }
    }

    fun openPacks(): List<KnowledgePack> = packs

    suspend fun engine(): ResearchEngine = lock.withLock {
        engine?.let { return@withLock it }
        nativeReady.getOrThrow()
        val opened = withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            val ps = storage.packDirs().mapNotNull { pd ->
                if (pd.manifest == null) { errors += "${pd.dir.name}: ${pd.error}"; return@mapNotNull null }
                runCatching { KnowledgePack.open(pd.dir) { AndroidSqlDb.openReadOnly(it) } }
                    .onFailure { errors += "${pd.dir.name}: ${it.message}" }.getOrNull()
            }
            packErrors = errors
            ps
        }
        packs = opened
        val encoders = HashMap<String, QueryEncoder>()
        if (settings.useVectors) {
            for (p in opened) {
                val spec = p.manifest.embedding ?: continue
                if (p.vectors == null) continue
                val emb = runCatching { runtime.embedder(spec.file) }.getOrNull() ?: continue
                encoders[p.id] = QueryEncoder(emb, spec.query_prefix, spec.dim, spec.transform)
            }
        }
        ResearchEngine(HybridRetriever(opened, encoders), runtime).also { engine = it }
    }

    /** Drop the engine so the next question re-scans models and packs. */
    suspend fun invalidate() = lock.withLock {
        engine = null
        packs.forEach { runCatching { it.close() } }
        packs = emptyList()
        runtime.unloadAll()
    }
}
