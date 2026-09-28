package io.kestrel.engine.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun system(c: String) = ChatMessage("system", c)
        fun user(c: String) = ChatMessage("user", c)
        fun assistant(c: String) = ChatMessage("assistant", c)
    }
}

data class GenerationParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.0f,
    val stop: List<String> = emptyList(),
    /** GBNF grammar constraining the output (JSON plans, verdict lists). */
    val grammar: String? = null,
    val reusePrefix: Boolean = true,
    val seed: Int = 42,
    /** Formatted prompt prefix to snapshot (set by [LlamaModel.chat] from the system turn). */
    val snapshotPrefix: String? = null,
) {
    fun toJson(): String = buildJsonObject {
        put("max_tokens", maxTokens)
        put("temperature", temperature)
        put("top_p", topP)
        put("top_k", topK)
        put("min_p", minP)
        put("repeat_penalty", repeatPenalty)
        put("seed", seed)
        put("reuse_prefix", reusePrefix)
        put("stop", JsonArray(stop.map { JsonPrimitive(it) }))
        grammar?.let { put("grammar", it) }
        snapshotPrefix?.let { put("snapshot_prefix", it) }
    }.toString()
}

@Serializable
data class GenerationStats(
    val prompt_tokens: Int = 0,
    val prefilled_tokens: Int = 0,
    val reused_tokens: Int = 0,
    val generated_tokens: Int = 0,
    val prefill_ms: Double = 0.0,
    val decode_ms: Double = 0.0,
    val ttft_ms: Double = 0.0,
    val total_ms: Double = 0.0,
    val stop_reason: String = "",
    val restored_tokens: Int = 0,
) {
    val prefillTokensPerSecond: Double get() = if (prefill_ms > 0) prefilled_tokens * 1000.0 / prefill_ms else 0.0
    val decodeTokensPerSecond: Double get() = if (decode_ms > 0) generated_tokens * 1000.0 / decode_ms else 0.0
}

@Serializable
private data class RawResult(
    val text: String = "",
    val prompt_tokens: Int = 0,
    val prefilled_tokens: Int = 0,
    val reused_tokens: Int = 0,
    val generated_tokens: Int = 0,
    val prefill_ms: Double = 0.0,
    val decode_ms: Double = 0.0,
    val ttft_ms: Double = 0.0,
    val total_ms: Double = 0.0,
    val stop_reason: String = "",
    val restored_tokens: Int = 0,
)

data class GenerationResult(val text: String, val stats: GenerationStats, val modelId: String)

@Serializable
data class ModelInfo(
    val desc: String = "",
    val arch: String = "",
    val name: String = "",
    val n_params: Long = 0,
    val size_bytes: Long = 0,
    val n_ctx_train: Int = 0,
    val n_embd: Int = 0,
    val n_layer: Int = 0,
    val n_vocab: Int = 0,
    val has_encoder: Boolean = false,
    val is_recurrent: Boolean = false,
    val has_chat_template: Boolean = false,
    val supports_thinking: Boolean = false,
    val load_ms: Double = 0.0,
)

/** A text generation model, as the research pipeline sees it. */
interface LanguageModel : Closeable {
    val id: String
    val info: ModelInfo

    /** Chat completion. [onText] receives visible text (reasoning blocks removed) as it streams. */
    suspend fun chat(
        messages: List<ChatMessage>,
        params: GenerationParams = GenerationParams(),
        thinking: Boolean = false,
        onText: ((String) -> Unit)? = null,
    ): GenerationResult

    fun countTokens(text: String): Int
}

/** A sentence embedding model. Vectors are L2-normalised. */
interface EmbeddingModel : Closeable {
    val id: String
    val dim: Int
    fun embed(texts: List<String>): List<FloatArray>
}

enum class ModelRole { FAST, STRONG, DEEP, EMBED }

data class LoadOptions(
    val contextSize: Int = 4096,
    val batchSize: Int = 512,
    val threads: Int = 4,
    val threadsBatch: Int = threads,
    val mmap: Boolean = true,
    val mlock: Boolean = false,
    /** Keep routed-expert tensors file-backed (streamed from flash by the page cache). */
    val streamExperts: Boolean = false,
    val repack: Boolean = true,
    val flashAttention: Int = -1,
    val gpuLayers: Int = 0,
)

private val json = Json { ignoreUnknownKeys = true }

/** llama.cpp-backed chat model. One context, one generation at a time. */
class LlamaModel private constructor(
    override val id: String,
    private val model: Long,
    private val ctx: Long,
    override val info: ModelInfo,
    private val dispatcher: CoroutineDispatcher,
    private val executor: java.util.concurrent.ExecutorService,
) : LanguageModel {

    override suspend fun chat(
        messages: List<ChatMessage>,
        params: GenerationParams,
        thinking: Boolean,
        onText: ((String) -> Unit)?,
    ): GenerationResult {
        val prompt = formatChat(messages, thinking)
        val sys = messages.firstOrNull()?.takeIf { it.role == "system" }
        val prefix = if (sys == null || params.snapshotPrefix != null) params.snapshotPrefix else systemPrefix(sys, thinking)
        return complete(prompt, params.copy(snapshotPrefix = prefix?.takeIf { prompt.startsWith(it) }), onText)
    }

    private val prefixCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The part of any prompt that starts with this system message and does not depend on the user
     * turn: the longest common prefix of two renderings with different user text, cut back to a
     * line break so that it tokenizes the same way inside the full prompt.
     */
    private fun systemPrefix(sys: ChatMessage, thinking: Boolean): String? = prefixCache.getOrPut(sys.content + "|" + thinking) {
        val a = formatChat(listOf(sys, ChatMessage.user("alpha")), thinking)
        val b = formatChat(listOf(sys, ChatMessage.user("omega")), thinking)
        var n = 0
        while (n < a.length && n < b.length && a[n] == b[n]) n++
        val cut = a.lastIndexOf('\n', n - 1)
        if (cut > sys.content.length / 2) a.substring(0, cut + 1) else ""
    }.ifEmpty { null }

    fun formatChat(messages: List<ChatMessage>, thinking: Boolean): String {
        val arr = buildJsonArray {
            for (m in messages) add(buildJsonObject { put("role", m.role); put("content", m.content) })
        }.toString()
        return String(LlamaNative.applyChatTemplate(model, arr.toByteArray(), true, thinking), Charsets.UTF_8)
    }

    /** Raw completion of an already formatted prompt. */
    suspend fun complete(prompt: String, params: GenerationParams, onText: ((String) -> Unit)?): GenerationResult = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) LlamaNative.cancel(ctx)
            }
        }
        val filter = ReasoningFilter()
        try {
            val raw = withContext(dispatcher) {
                val sink = if (onText == null) null else ByteSink { bytes ->
                    val visible = filter.accept(String(bytes, Charsets.UTF_8))
                    if (visible.isNotEmpty()) onText(visible)
                    true
                }
                LlamaNative.generate(ctx, prompt.toByteArray(), params.toJson().toByteArray(), sink)
            }
            val r = json.decodeFromString(RawResult.serializer(), String(raw, Charsets.UTF_8))
            val stats = GenerationStats(
                r.prompt_tokens, r.prefilled_tokens, r.reused_tokens, r.generated_tokens,
                r.prefill_ms, r.decode_ms, r.ttft_ms, r.total_ms, r.stop_reason, r.restored_tokens,
            )
            GenerationResult(ReasoningFilter.strip(r.text), stats, id)
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    override fun countTokens(text: String): Int = LlamaNative.tokenize(model, text.toByteArray(), false, true).size

    fun clearCache() = LlamaNative.kvClear(ctx)

    override fun close() {
        LlamaNative.cancel(ctx)
        executor.submit {
            LlamaNative.contextFree(ctx)
            LlamaNative.modelFree(model)
        }.get()
        executor.shutdown()
    }

    companion object {
        fun load(id: String, path: String, opts: LoadOptions): LlamaModel {
            LlamaNative.ensureLoaded()
            var flags = 0
            if (opts.mmap) flags = flags or LlamaNative.FLAG_MMAP
            if (opts.mlock) flags = flags or LlamaNative.FLAG_MLOCK
            if (opts.streamExperts) flags = flags or LlamaNative.FLAG_STREAM_EXPERTS
            if (!opts.repack) flags = flags or LlamaNative.FLAG_NO_REPACK
            val m = LlamaNative.modelLoad(path.toByteArray(), flags, opts.gpuLayers)
            val info = json.decodeFromString(ModelInfo.serializer(), String(LlamaNative.modelInfo(m), Charsets.UTF_8))
            val ctx = try {
                LlamaNative.contextCreate(
                    m, opts.contextSize, opts.batchSize, opts.batchSize, opts.threads, opts.threadsBatch,
                    false, -1, opts.flashAttention, 1,
                )
            } catch (t: Throwable) {
                LlamaNative.modelFree(m)
                throw t
            }
            val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "llm-$id").apply { isDaemon = true } }
            return LlamaModel(id, m, ctx, info, ex.asCoroutineDispatcher(), ex)
        }
    }
}

/** llama.cpp-backed embedding model (mean/CLS pooling as declared by the GGUF). */
class LlamaEmbedder private constructor(
    override val id: String,
    private val model: Long,
    private val ctx: Long,
    override val dim: Int,
    val info: ModelInfo,
) : EmbeddingModel {
    private val lock = Any()

    override fun embed(texts: List<String>): List<FloatArray> = synchronized(lock) {
        if (texts.isEmpty()) return emptyList()
        LlamaNative.embed(ctx, texts.map { it.toByteArray() }.toTypedArray(), true).toList()
    }

    override fun close() = synchronized(lock) {
        LlamaNative.contextFree(ctx)
        LlamaNative.modelFree(model)
    }

    companion object {
        fun load(id: String, path: String, threads: Int, maxTokens: Int = 512, parallel: Int = 8): LlamaEmbedder {
            LlamaNative.ensureLoaded()
            val m = LlamaNative.modelLoad(path.toByteArray(), LlamaNative.FLAG_MMAP, 0)
            val info = json.decodeFromString(ModelInfo.serializer(), String(LlamaNative.modelInfo(m), Charsets.UTF_8))
            val batch = maxTokens * parallel
            val ctx = LlamaNative.contextCreate(m, batch, batch, batch, threads, threads, true, -1, -1, parallel)
            return LlamaEmbedder(id, m, ctx, info.n_embd, info)
        }
    }
}

/**
 * Removes reasoning blocks (`<think>…</think>`) from streamed text so the UI shows only the
 * answer. Text after an unterminated opening tag is held back.
 */
class ReasoningFilter {
    private val buf = StringBuilder()
    private var inside = false

    fun accept(chunk: String): String {
        buf.append(chunk)
        val out = StringBuilder()
        while (true) {
            if (inside) {
                val end = buf.indexOf(CLOSE)
                if (end < 0) return out.toString()
                buf.delete(0, end + CLOSE.length)
                inside = false
            } else {
                val start = buf.indexOf(OPEN)
                if (start < 0) {
                    // hold back a possible partial "<think>" at the end
                    val keep = (1 until OPEN.length).lastOrNull { buf.endsWith(OPEN.substring(0, it)) } ?: 0
                    out.append(buf, 0, buf.length - keep)
                    buf.delete(0, buf.length - keep)
                    return out.toString()
                }
                out.append(buf, 0, start)
                buf.delete(0, start + OPEN.length)
                inside = true
            }
        }
    }

    companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
        private val BLOCK = Regex("<think>[\\s\\S]*?(</think>|$)")

        fun strip(text: String): String {
            var t = text
            // a template may open the block in the prompt, so the output can start mid-block
            val close = t.indexOf(CLOSE)
            val open = t.indexOf(OPEN)
            if (close >= 0 && (open < 0 || close < open)) t = t.substring(close + CLOSE.length)
            return BLOCK.replace(t, "").trim()
        }
    }
}

fun systemInfo(): String = String(LlamaNative.systemInfo(), Charsets.UTF_8)
