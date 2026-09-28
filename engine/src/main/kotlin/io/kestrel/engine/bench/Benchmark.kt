package io.kestrel.engine.bench

import io.kestrel.engine.research.ResearchAnswer
import io.kestrel.engine.research.ResearchEngine
import io.kestrel.engine.research.ResearchMode
import io.kestrel.engine.text.Text
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BenchQuestion(
    val id: String,
    val category: String,
    val question: String,
    /** Short gold answer; matched leniently (normalised containment). */
    val answer: String = "",
    /** Alternative acceptable answers. */
    val accept: List<String> = emptyList(),
    /** Facts a complete answer should state; each is matched leniently. */
    val key_facts: List<String> = emptyList(),
    /** The corpus should not support an answer; the correct behaviour is to say so. */
    val expect_abstain: Boolean = false,
    val mode: String = "auto",
    val notes: String = "",
)

@Serializable
data class LlmCallRecord(
    val step: String, val model: String, val prompt_tokens: Int, val prefilled_tokens: Int, val reused_tokens: Int,
    val generated_tokens: Int, val prefill_ms: Double, val decode_ms: Double, val ttft_ms: Double,
    val prefill_tps: Double, val decode_tps: Double,
)

@Serializable
data class SourceRecord(val n: Int, val pack: String, val title: String, val section: String, val chunk_id: Long, val text: String)

@Serializable
data class BenchResult(
    val id: String,
    val category: String,
    val question: String,
    val standalone_question: String,
    val mode: String,
    val detected_type: String,
    val route: String,
    val answer_model: String?,
    val models_used: List<String>,
    val answer: String,
    val abstained: Boolean,
    val sources: List<SourceRecord>,
    val linked_articles: List<String>,
    val hops: List<String>,
    // automatic scores
    val answer_match: Boolean?,
    val key_fact_recall: Double?,
    val abstain_correct: Boolean?,
    val claims_checkable: Int,
    val claims_supported: Int,
    val claims_unsupported: Int,
    val citation_errors: Int,
    val verification_ran_llm: Boolean,
    // performance
    val total_ms: Long,
    val retrieval_ms: Long,
    val answer_ttft_ms: Double?,
    val answer_decode_tps: Double?,
    val answer_prefill_tps: Double?,
    val llm_calls: List<LlmCallRecord>,
    val rss_mb: Long?,
    val peak_rss_mb: Long?,
    val steps: List<String>,
    val error: String? = null,
)

object Scoring {
    private fun norm(s: String) = Text.words(s).joinToString(" ")

    /** Lenient match: all content words of the target (numbers exact) appear in the answer. */
    fun matches(answer: String, target: String): Boolean {
        if (target.isBlank()) return false
        val a = " " + norm(answer) + " "
        val t = norm(target)
        if (t.isNotEmpty() && a.contains(" $t ")) return true
        val words = Text.contentWords(target).ifEmpty { Text.words(target) }
        val aw = Text.words(answer).toSet()
        val aStems = Text.stems(answer).toSet()
        val nums = Text.numbers(target)
        val aNums = Text.numbers(answer)
        if (!nums.all { it in aNums }) return false
        return words.all { w -> w in aw || io.kestrel.engine.text.Porter.stem(w) in aStems }
    }

    private val ABSTAIN = Regex(
        "(does not contain enough information|do(es)? not (say|mention|contain|provide|state)|not (found|mentioned|covered) in the (offline )?(sources|library)|" +
            "no information|cannot (be )?(determined|answered)|insufficient|not enough information|unable to (find|answer))",
        RegexOption.IGNORE_CASE,
    )

    fun looksAbstained(a: ResearchAnswer) = a.abstained || ABSTAIN.containsMatchIn(a.text.take(400))
}

object MemoryProbe {
    /** (current RSS, peak RSS) in MB from /proc/self/status (Linux and Android). */
    fun rss(): Pair<Long?, Long?> = runCatching {
        var cur: Long? = null
        var peak: Long? = null
        File("/proc/self/status").forEachLine { line ->
            if (line.startsWith("VmRSS:")) cur = line.filter { it.isDigit() }.toLong() / 1024
            if (line.startsWith("VmHWM:")) peak = line.filter { it.isDigit() }.toLong() / 1024
        }
        cur to peak
    }.getOrDefault(null to null)
}

class BenchmarkRunner(private val engine: ResearchEngine) {
    private val json = Json { encodeDefaults = true }

    suspend fun run(q: BenchQuestion, defaultMode: ResearchMode? = null): BenchResult {
        val mode = defaultMode ?: when (q.mode.lowercase()) {
            "quick" -> ResearchMode.QUICK
            "deep" -> ResearchMode.DEEP
            else -> ResearchMode.AUTO
        }
        return try {
            val a = engine.research(q.question, mode)
            toResult(q, a)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val (rss, peak) = MemoryProbe.rss()
            BenchResult(
                q.id, q.category, q.question, q.question, mode.name, "", "", null, emptyList(), "", false, emptyList(),
                emptyList(), emptyList(), null, null, null, 0, 0, 0, 0, false, 0, 0, null, null, null, emptyList(), rss, peak,
                emptyList(), error = e.toString(),
            )
        }
    }

    fun toResult(q: BenchQuestion, a: ResearchAnswer): BenchResult {
        val (rss, peak) = MemoryProbe.rss()
        val answerCall = a.llmCalls.lastOrNull { it.step == "answer" }
        val targets = listOf(q.answer).filter { it.isNotBlank() } + q.accept
        val abstained = Scoring.looksAbstained(a)
        return BenchResult(
            id = q.id, category = q.category, question = q.question, standalone_question = a.standaloneQuestion,
            mode = a.mode.name, detected_type = a.type.name, route = a.route.reason, answer_model = a.answerModel,
            models_used = a.llmCalls.map { it.model }.distinct(), answer = a.text, abstained = abstained,
            sources = a.evidence.map { SourceRecord(it.n, it.retrieved.chunk.packId, it.title, it.section, it.retrieved.chunk.id, it.text) },
            linked_articles = a.linkedArticles,
            hops = a.hops.map { "${it.question} => ${it.answer ?: "NOT FOUND"}" },
            answer_match = if (targets.isEmpty() || q.expect_abstain) null else targets.any { Scoring.matches(a.text, it) },
            key_fact_recall = if (q.key_facts.isEmpty()) null else q.key_facts.count { Scoring.matches(a.text, it) }.toDouble() / q.key_facts.size,
            abstain_correct = if (q.expect_abstain) abstained else !abstained,
            claims_checkable = a.verification.checkable.size,
            claims_supported = a.verification.supported,
            claims_unsupported = a.verification.unsupported,
            citation_errors = a.verification.citationErrors,
            verification_ran_llm = a.llmCalls.any { it.step == "verify" },
            total_ms = a.totalMs, retrieval_ms = a.retrievalMs,
            answer_ttft_ms = answerCall?.stats?.ttft_ms,
            answer_decode_tps = answerCall?.stats?.decodeTokensPerSecond,
            answer_prefill_tps = answerCall?.stats?.prefillTokensPerSecond,
            llm_calls = a.llmCalls.map {
                LlmCallRecord(
                    it.step, it.model, it.stats.prompt_tokens, it.stats.prefilled_tokens, it.stats.reused_tokens,
                    it.stats.generated_tokens, it.stats.prefill_ms, it.stats.decode_ms, it.stats.ttft_ms,
                    it.stats.prefillTokensPerSecond, it.stats.decodeTokensPerSecond,
                )
            },
            rss_mb = rss, peak_rss_mb = peak,
            steps = a.steps.map { "${it.id} [${it.status}] ${it.ms}ms ${it.detail}" },
        )
    }

    fun encode(r: BenchResult): String = json.encodeToString(BenchResult.serializer(), r)

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true }
        fun loadQuestions(text: String): List<BenchQuestion> = text.lines().filter { it.isNotBlank() && !it.startsWith("//") }
            .map { lenient.decodeFromString(BenchQuestion.serializer(), it) }
    }
}
