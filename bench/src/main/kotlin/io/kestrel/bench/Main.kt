package io.kestrel.bench

import io.kestrel.engine.bench.BenchmarkRunner
import io.kestrel.engine.llm.ChatMessage
import io.kestrel.engine.llm.GenerationParams
import io.kestrel.engine.llm.LanguageModel
import io.kestrel.engine.llm.LlamaEmbedder
import io.kestrel.engine.llm.LlamaModel
import io.kestrel.engine.llm.LoadOptions
import io.kestrel.engine.llm.ModelRole
import io.kestrel.engine.llm.systemInfo
import io.kestrel.engine.research.ModelProvider
import io.kestrel.engine.research.ResearchEngine
import io.kestrel.engine.research.ResearchEvent
import io.kestrel.engine.research.ResearchMode
import io.kestrel.engine.research.StepStatus
import io.kestrel.engine.retrieval.EvidenceBudget
import io.kestrel.engine.retrieval.EvidenceSelector
import io.kestrel.engine.retrieval.HybridRetriever
import io.kestrel.engine.retrieval.QueryAnalyzer
import io.kestrel.engine.retrieval.QueryEncoder
import io.kestrel.engine.retrieval.RetrievalRequest
import io.kestrel.engine.store.JdbcSqlDb
import io.kestrel.engine.store.KnowledgePack
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Desktop CLI: runs the exact engine and native bridge the app uses, for development and for
 * reproducing the benchmark on a PC.
 *
 *   kestrel-cli ask      --pack DIR [--pack DIR] --fast GGUF [--strong GGUF] [--deep GGUF] [--embed GGUF] [--mode auto|quick|deep] "question"
 *   kestrel-cli retrieve --pack DIR [--embed GGUF] "question"
 *   kestrel-cli bench    --pack DIR ... --questions FILE --out FILE [--limit N] [--ids a,b] [--mode auto]
 *   kestrel-cli speed    --model GGUF [--threads N]
 *   kestrel-cli info
 */
fun main(argv: Array<String>) {
    if (argv.isEmpty()) usage()
    val cmd = argv[0]
    val opts = Opts.parse(argv.drop(1))
    when (cmd) {
        "info" -> { io.kestrel.engine.llm.LlamaNative.ensureLoaded(); println(systemInfo()) }
        "retrieve" -> retrieve(opts)
        "ask" -> ask(opts)
        "bench" -> bench(opts)
        "speed" -> speed(opts)
        else -> usage()
    }
    exitProcess(0)
}

private fun usage(): Nothing {
    System.err.println("usage: kestrel-cli ask|retrieve|bench|speed|info [options] (see Main.kt)")
    exitProcess(2)
}

class Opts(val multi: Map<String, List<String>>, val positional: List<String>) {
    fun get(k: String) = multi[k]?.lastOrNull()
    fun all(k: String) = multi[k] ?: emptyList()
    fun int(k: String, d: Int) = get(k)?.toInt() ?: d

    companion object {
        fun parse(a: List<String>): Opts {
            val m = LinkedHashMap<String, MutableList<String>>()
            val pos = mutableListOf<String>()
            var i = 0
            while (i < a.size) {
                val s = a[i]
                if (s.startsWith("--")) {
                    val k = s.removePrefix("--")
                    val v = if (i + 1 < a.size && !a[i + 1].startsWith("--")) a[++i] else "true"
                    m.getOrPut(k) { mutableListOf() }.add(v)
                } else pos += s
                i++
            }
            return Opts(m, pos)
        }
    }
}

private fun threads(o: Opts) = o.int("threads", Runtime.getRuntime().availableProcessors().coerceAtMost(8))

private fun openPacks(o: Opts): List<KnowledgePack> = o.all("pack").map { KnowledgePack.open(File(it)) { p -> JdbcSqlDb.openReadOnly(p) } }

private fun encoders(o: Opts, packs: List<KnowledgePack>): Map<String, QueryEncoder> {
    val path = o.get("embed") ?: return emptyMap()
    val emb = LlamaEmbedder.load("embed", path, threads(o))
    return packs.mapNotNull { p ->
        val spec = p.manifest.embedding ?: return@mapNotNull null
        if (p.vectors == null) null else p.id to QueryEncoder(emb, spec.query_prefix, spec.dim, spec.transform)
    }.toMap()
}

class DesktopModels(private val o: Opts) : ModelProvider {
    private val paths = mapOf(
        ModelRole.FAST to o.get("fast"),
        ModelRole.STRONG to o.get("strong"),
        ModelRole.DEEP to o.get("deep"),
    ).filterValues { it != null }.mapValues { it.value!! }
    private val loaded = HashMap<ModelRole, LanguageModel>()
    private val byPath = HashMap<String, LanguageModel>()
    override val available: Set<ModelRole> get() = paths.keys

    override suspend fun get(role: ModelRole): LanguageModel? {
        loaded[role]?.let { return it }
        val p = paths[role] ?: return null
        val m = byPath.getOrPut(p) {
            val t0 = System.currentTimeMillis()
            val lm = LlamaModel.load(
                File(p).nameWithoutExtension, p,
                LoadOptions(contextSize = o.int("ctx", 4096), threads = threads(o), streamExperts = role == ModelRole.DEEP && o.get("stream-experts") == "true"),
            )
            System.err.println("[load] ${lm.id} in ${System.currentTimeMillis() - t0} ms (${lm.info.desc})")
            lm
        }
        loaded[role] = m
        return m
    }
}

private fun retrieve(o: Opts) {
    val q = o.positional.joinToString(" ")
    val packs = openPacks(o)
    val r = HybridRetriever(packs, encoders(o, packs))
    val f = QueryAnalyzer.analyze(q)
    println("type=${f.type} conf=${f.confidence} entities=${f.entities} comparands=${f.comparands} stems=${f.stems}")
    val t0 = System.currentTimeMillis()
    val res = r.retrieve(RetrievalRequest(q, k = o.int("k", 10)))
    println("retrieval ${System.currentTimeMillis() - t0} ms ${res.timings} linked=${res.linkedDocs} conf=${"%.2f".format(res.confidence)}")
    res.items.forEachIndexed { i, it ->
        println("%2d %.3f %s — %s [%s]".format(i + 1, it.score, it.chunk.title, it.chunk.section, it.signals.entries.joinToString { e -> "${e.key}=${"%.3f".format(e.value)}" }))
        println("    " + it.chunk.text.take(220).replace("\n", " "))
    }
    val ev = EvidenceSelector.select(q, res.items, EvidenceBudget(o.int("budget", 900), 8))
    println("\n--- evidence (${ev.sumOf { io.kestrel.engine.text.Text.estimateTokens(it.render()) }} tok est) ---")
    println(EvidenceSelector.render(ev))
}

private fun buildEngine(o: Opts): Pair<ResearchEngine, List<KnowledgePack>> {
    val packs = openPacks(o)
    val retriever = HybridRetriever(packs, encoders(o, packs))
    return ResearchEngine(retriever, DesktopModels(o)) to packs
}

private fun mode(o: Opts) = when (o.get("mode")) { "quick" -> ResearchMode.QUICK; "deep" -> ResearchMode.DEEP; else -> ResearchMode.AUTO }

private fun ask(o: Opts) = runBlocking {
    val q = o.positional.joinToString(" ")
    val (engine, _) = buildEngine(o)
    var streaming = false
    val a = engine.research(q, mode(o)) { ev ->
        when (ev) {
            is ResearchEvent.Step -> if (ev.status != StepStatus.RUNNING) {
                if (streaming) { println(); streaming = false }
                System.err.println("  · ${ev.label}: ${ev.status} ${ev.ms}ms ${ev.detail}")
            }
            is ResearchEvent.AnswerDelta -> { streaming = true; print(ev.text); System.out.flush() }
            is ResearchEvent.SourcesReady -> Unit
            is ResearchEvent.Verified -> Unit
        }
    }
    println("\n\n--- sources ---")
    a.evidence.forEach { println("[${it.n}] ${it.title}${if (it.section.isNotBlank()) " — " + it.section else ""}") }
    println("--- verification: ${a.verification.summary()}")
    a.verification.claims.filter { it.verdict.name != "NOT_A_CLAIM" }.forEach {
        println("  ${it.verdict.name.padEnd(11)} ${"%.2f".format(it.support)} ${it.sentence.take(110)}")
    }
    println("--- total ${a.totalMs} ms, retrieval ${a.retrievalMs} ms; calls: " +
        a.llmCalls.joinToString { "${it.step}/${it.model}: ${it.stats.prompt_tokens}p(${it.stats.reused_tokens}c)+${it.stats.generated_tokens}g ${"%.0f".format(it.stats.total_ms)}ms" })
}

private fun bench(o: Opts) = runBlocking {
    val (engine, _) = buildEngine(o)
    val runner = BenchmarkRunner(engine)
    var qs = BenchmarkRunner.loadQuestions(File(o.get("questions") ?: error("--questions")).readText())
    o.get("ids")?.let { ids -> val set = ids.split(",").toSet(); qs = qs.filter { it.id in set } }
    o.get("category")?.let { c -> qs = qs.filter { it.category == c } }
    qs = qs.take(o.int("limit", qs.size))
    val out = File(o.get("out") ?: "bench-results.jsonl")
    val done = if (out.exists() && o.get("resume") == "true") out.readLines().mapNotNull { Regex("\"id\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.toSet() else {
        out.writeText(""); emptySet()
    }
    val forced = o.get("mode")?.let { mode(o) }
    for ((i, q) in qs.withIndex()) {
        if (q.id in done) continue
        val r = runner.run(q, forced)
        out.appendText(runner.encode(r) + "\n")
        System.err.println("[${i + 1}/${qs.size}] ${q.id} ${r.total_ms}ms match=${r.answer_match} facts=${r.key_fact_recall} abstain_ok=${r.abstain_correct} supported=${r.claims_supported}/${r.claims_checkable} ${r.error ?: ""}")
    }
}

private fun speed(o: Opts) = runBlocking {
    val path = o.get("model") ?: error("--model")
    val t0 = System.currentTimeMillis()
    val m = LlamaModel.load("speed", path, LoadOptions(contextSize = 4096, threads = threads(o)))
    println("load ${System.currentTimeMillis() - t0} ms  ${m.info}")
    val filler = (1..60).joinToString(" ") { "The quick brown fox jumps over the lazy dog number $it." }
    for (run in 1..2) {
        m.clearCache()
        val r = m.chat(listOf(ChatMessage.user("Summarise in one sentence: $filler")), GenerationParams(maxTokens = 64, reusePrefix = false))
        println("run $run: prompt ${r.stats.prompt_tokens} tok @ ${"%.1f".format(r.stats.prefillTokensPerSecond)} tok/s; gen ${r.stats.generated_tokens} @ ${"%.1f".format(r.stats.decodeTokensPerSecond)} tok/s; ttft ${"%.0f".format(r.stats.ttft_ms)} ms")
    }
}
