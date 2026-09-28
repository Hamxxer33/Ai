package io.kestrel.engine.research

import io.kestrel.engine.llm.ChatMessage
import io.kestrel.engine.llm.GenerationParams
import io.kestrel.engine.llm.GenerationStats
import io.kestrel.engine.llm.LanguageModel
import io.kestrel.engine.llm.ModelRole
import io.kestrel.engine.retrieval.Evidence
import io.kestrel.engine.retrieval.EvidenceBudget
import io.kestrel.engine.retrieval.EvidenceSelector
import io.kestrel.engine.retrieval.HybridRetriever
import io.kestrel.engine.retrieval.QueryAnalyzer
import io.kestrel.engine.retrieval.QueryFeatures
import io.kestrel.engine.retrieval.QuestionType
import io.kestrel.engine.retrieval.Retrieved
import io.kestrel.engine.retrieval.RetrievalRequest
import io.kestrel.engine.text.Text
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/** Supplies models by role, loading/unloading them within the device's memory budget. */
interface ModelProvider {
    val available: Set<ModelRole>
    suspend fun get(role: ModelRole): LanguageModel?
}

enum class StepStatus { RUNNING, DONE, SKIPPED, FAILED }

sealed interface ResearchEvent {
    data class Step(val id: String, val label: String, val status: StepStatus, val detail: String = "", val ms: Long = 0) : ResearchEvent
    data class AnswerDelta(val text: String) : ResearchEvent
    data class SourcesReady(val evidence: List<Evidence>) : ResearchEvent
    data class Verified(val report: VerificationReport) : ResearchEvent
}

data class Exchange(val question: String, val answer: String)

data class Hop(val question: String, val answer: String?, val sources: List<Evidence>, val ms: Long)

data class LlmCall(val step: String, val model: String, val stats: GenerationStats)

data class StepRecord(val id: String, val label: String, val status: StepStatus, val detail: String, val ms: Long)

data class ResearchAnswer(
    val question: String,
    val standaloneQuestion: String,
    val type: QuestionType,
    val mode: ResearchMode,
    val route: Route,
    val text: String,
    val evidence: List<Evidence>,
    val verification: VerificationReport,
    val hops: List<Hop>,
    val abstained: Boolean,
    val steps: List<StepRecord>,
    val llmCalls: List<LlmCall>,
    val retrievalMs: Long,
    val totalMs: Long,
    val answerModel: String?,
    val linkedArticles: List<String>,
)

data class EngineConfig(
    /** Below this lexical coverage (and without a linked article) the question is treated as not covered. */
    val minConfidence: Double = 0.34,
    val hopEvidenceTokens: Int = 420,
    val verifyMaxClaims: Int = 12,
)

/**
 * The research pipeline: rewrite → analyse → route → plan → hops → retrieve → select evidence →
 * sufficiency gate → synthesise → verify. See docs/ARCHITECTURE.md §5.
 */
class ResearchEngine(
    private val retriever: HybridRetriever,
    private val models: ModelProvider,
    private val config: EngineConfig = EngineConfig(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun research(
        question: String,
        mode: ResearchMode = ResearchMode.AUTO,
        history: List<Exchange> = emptyList(),
        listener: (ResearchEvent) -> Unit = {},
    ): ResearchAnswer {
        val t0 = System.currentTimeMillis()
        val steps = mutableListOf<StepRecord>()
        val calls = mutableListOf<LlmCall>()
        var retrievalMs = 0L

        suspend fun <T> step(id: String, label: String, block: suspend () -> Pair<T, String>): T {
            listener(ResearchEvent.Step(id, label, StepStatus.RUNNING))
            val s = System.currentTimeMillis()
            try {
                val (v, detail) = block()
                val ms = System.currentTimeMillis() - s
                steps += StepRecord(id, label, StepStatus.DONE, detail, ms)
                listener(ResearchEvent.Step(id, label, StepStatus.DONE, detail, ms))
                return v
            } catch (e: Throwable) {
                val ms = System.currentTimeMillis() - s
                steps += StepRecord(id, label, StepStatus.FAILED, e.message ?: e.toString(), ms)
                listener(ResearchEvent.Step(id, label, StepStatus.FAILED, e.message ?: "", ms))
                throw e
            }
        }

        // ---- 0. follow-up rewrite
        var q = question.trim()
        if (history.isNotEmpty() && looksLikeFollowUp(q)) {
            val fast = models.get(ModelRole.FAST) ?: models.get(ModelRole.STRONG)
            if (fast != null) {
                q = step("rewrite", "Resolve follow-up") {
                    val prev = history.last()
                    val r = fast.chat(
                        listOf(ChatMessage.system(Prompts.REWRITE_SYSTEM), ChatMessage.user(Prompts.rewriteUser(prev.question, prev.answer, q))),
                        GenerationParams(maxTokens = 60, stop = listOf("\n")),
                    )
                    calls += LlmCall("rewrite", r.modelId, r.stats)
                    val rewritten = r.text.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"') ?: q
                    (if (rewritten.length in 4..400) rewritten else q) to rewritten
                }
            }
        }

        // ---- 1. analyse + route
        var feats = QueryAnalyzer.analyze(q)
        var route = Router.route(feats, mode, models.available)
        steps += StepRecord("analyse", "Classify question", StepStatus.DONE, "${feats.type.name.lowercase()} (${"%.2f".format(feats.confidence)}), ${route.reason}", 0)
        listener(ResearchEvent.Step("analyse", "Classify question", StepStatus.DONE, "${feats.type.name.lowercase()} · ${route.reason}"))

        // ---- 2. plan
        var plan = Plan(feats.type, emptyList(), emptyList())
        if (route.plan) {
            val fast = models.get(ModelRole.FAST) ?: models.get(ModelRole.STRONG)
            if (fast != null) {
                plan = step("plan", "Plan research") {
                    val p = runCatching { planWith(fast, q, calls) }.getOrNull() ?: fallbackPlan(feats)
                    p to "${p.type.name.lowercase()}: ${p.subquestions.size} sub-questions, ${p.queries.size} searches"
                }
                if (plan.type != feats.type && feats.confidence < 0.7) {
                    feats = feats.copy(type = plan.type)
                    route = Router.route(feats, mode, models.available)
                }
            } else plan = fallbackPlan(feats)
        } else if (feats.type == QuestionType.COMPARISON) {
            plan = fallbackPlan(feats)
        }
        coroutineContext.ensureActive()

        // ---- 3. hops (multi-hop: sequential extraction; comparison/synthesis: per-aspect retrieval)
        val hops = mutableListOf<Hop>()
        val pool = LinkedHashMap<Pair<String, Long>, Retrieved>()
        val linked = mutableListOf<String>()
        val hopAnswers = mutableListOf<String>()
        val subqs = plan.subquestions.take(4)
        if (subqs.isNotEmpty() && (route.hops || feats.type in setOf(QuestionType.COMPARISON, QuestionType.SYNTHESIS))) {
            val extract = route.hops
            val fast = if (extract) models.get(ModelRole.FAST) ?: models.get(ModelRole.STRONG) else null
            for ((i, raw) in subqs.withIndex()) {
                coroutineContext.ensureActive()
                val sq = substitute(raw, hopAnswers)
                val hop = step("hop${i + 1}", "Research: $sq") {
                    val hs = System.currentTimeMillis()
                    val rs = System.currentTimeMillis()
                    val res = retriever.retrieve(RetrievalRequest(sq, queries = plan.queries.take(2), entities = hopAnswers.takeLast(1), k = 6, maxPerDoc = 2))
                    retrievalMs += System.currentTimeMillis() - rs
                    linked += res.linkedDocs
                    res.items.forEach { pool.putIfAbsent(it.chunk.packId to it.chunk.id, it) }
                    var answer: String? = null
                    val ev = EvidenceSelector.select(sq, res.items, EvidenceBudget(config.hopEvidenceTokens, maxSources = 4))
                    if (fast != null && ev.isNotEmpty()) {
                        val r = fast.chat(
                            listOf(ChatMessage.system(Prompts.HOP_SYSTEM), ChatMessage.user(Prompts.hopUser(sq, EvidenceSelector.render(ev)))),
                            GenerationParams(maxTokens = 64, stop = listOf("\n\n")),
                        )
                        calls += LlmCall("hop${i + 1}", r.modelId, r.stats)
                        val a = r.text.trim().lines().firstOrNull { it.isNotBlank() }?.trim()
                        answer = if (a == null || a.uppercase().startsWith("NOT FOUND")) null else a
                    }
                    val h = Hop(sq, answer, ev, System.currentTimeMillis() - hs)
                    h to (answer ?: if (extract) "not found" else "${ev.size} sources")
                }
                hops += hop
                if (hop.answer != null) hopAnswers += stripCitations(hop.answer) else if (extract) hopAnswers += ""
            }
        }

        // ---- 4. main retrieval
        val main = step("retrieve", "Search offline library") {
            val rs = System.currentTimeMillis()
            val extraEntities = hopAnswers.filter { it.isNotBlank() && it.length < 80 } + feats.comparands
            val res = retriever.retrieve(
                RetrievalRequest(q, queries = plan.queries.take(4), entities = extraEntities, k = route.retrieveK, maxPerDoc = if (feats.type == QuestionType.LOOKUP) 3 else 2),
            )
            retrievalMs += System.currentTimeMillis() - rs
            linked += res.linkedDocs
            val timing = res.timings.entries.joinToString(", ") { "${it.key} ${it.value}ms" }
            res to "${res.items.size} passages; linked: ${res.linkedDocs.take(4).joinToString()}; $timing"
        }
        // merge: main results first, then hop results that add new articles
        val merged = LinkedHashMap<Pair<String, Long>, Retrieved>()
        main.items.forEach { merged[it.chunk.packId to it.chunk.id] = it }
        // interleave hop evidence so each hop's best source survives the budget
        hops.forEach { h -> h.sources.firstOrNull()?.retrieved?.let { merged.putIfAbsent(it.chunk.packId to it.chunk.id, it) } }
        pool.values.sortedByDescending { it.score }.forEach { merged.putIfAbsent(it.chunk.packId to it.chunk.id, it) }

        // ---- 5. evidence
        val evidence = step("evidence", "Select evidence") {
            val ranked = merged.values.toList()
            val ev = EvidenceSelector.select(
                q, ranked, EvidenceBudget(route.evidenceTokens, maxSources = route.maxSources),
                extraTerms = hopAnswers.filter { it.isNotBlank() } + feats.comparands,
            )
            ev to "${ev.size} sources, ~${ev.sumOf { Text.estimateTokens(it.render()) }} tokens"
        }
        listener(ResearchEvent.SourcesReady(evidence))

        // ---- 6. sufficiency gate
        val hopFound = hops.any { it.answer != null }
        val insufficient = evidence.isEmpty() || (main.confidence < config.minConfidence && main.linkedDocs.isEmpty() && !hopFound)
        if (insufficient) {
            val text = insufficientAnswer(q, feats, main.items.map { it.chunk.title }.distinct().take(5))
            listener(ResearchEvent.AnswerDelta(text))
            return ResearchAnswer(
                question, q, feats.type, mode, route, text, evidence, VerificationReport(emptyList()), hops, true,
                steps, calls, retrievalMs, System.currentTimeMillis() - t0, null, linked.distinct(),
            )
        }

        // ---- 7. synthesis
        val model = models.get(route.answerRole)
            ?: models.get(ModelRole.STRONG) ?: models.get(ModelRole.FAST)
            ?: error("no language model available")
        val notes = hops.filter { it.answer != null }.map { h ->
            val titles = h.sources.map { it.title }.distinct().take(2).joinToString("; ")
            "${h.question} → ${stripCitations(h.answer!!)} (from: $titles)"
        }
        val answer = step("answer", "Write answer (${model.id})") {
            val sb = StringBuilder()
            val r = model.chat(
                listOf(
                    ChatMessage.system(Prompts.ANSWER_SYSTEM),
                    ChatMessage.user(Prompts.answerUser(q, EvidenceSelector.render(evidence), notes, feats.type, route.answerWords)),
                ),
                GenerationParams(maxTokens = route.answerTokens, temperature = 0.2f, topP = 0.9f, repeatPenalty = 1.05f),
                thinking = false,
            ) { delta -> sb.append(delta); listener(ResearchEvent.AnswerDelta(delta)) }
            calls += LlmCall("answer", r.modelId, r.stats)
            r.text to "${r.stats.prompt_tokens} prompt tok (${r.stats.reused_tokens} cached), ${r.stats.generated_tokens} gen, " +
                "TTFT ${"%.1f".format(r.stats.ttft_ms / 1000)}s, ${"%.1f".format(r.stats.decodeTokensPerSecond)} tok/s"
        }

        // ---- 8. verification
        var report = Verifier.check(answer, evidence, q, feats.type == QuestionType.NUMERIC)
        if (route.llmVerify) {
            val weak = report.claims.withIndex()
                .filter { it.value.verdict == Verdict.WEAK || it.value.verdict == Verdict.UNSUPPORTED || it.value.verdict == Verdict.UNCITED }
                .take(config.verifyMaxClaims)
            val fast = if (weak.isNotEmpty()) models.get(ModelRole.FAST) ?: models.get(ModelRole.STRONG) else null
            if (fast != null) {
                report = step("verify", "Verify claims") {
                    val cited = weak.flatMap { it.value.citations }.toSet()
                    val ev = if (cited.isEmpty()) evidence else evidence.filter { it.n in cited }.ifEmpty { evidence }
                    val r = fast.chat(
                        listOf(ChatMessage.system(Prompts.VERIFY_SYSTEM), ChatMessage.user(Prompts.verifyUser(EvidenceSelector.render(ev), weak.map { it.value.sentence }))),
                        GenerationParams(maxTokens = weak.size + 2, grammar = Prompts.verifyGrammar(weak.size)),
                    )
                    calls += LlmCall("verify", r.modelId, r.stats)
                    val updated = Verifier.applyModelVerdicts(report, weak.map { it.index }, r.text.trim())
                    updated to updated.summary()
                }
            }
        }
        steps += StepRecord("check", "Grounding check", StepStatus.DONE, report.summary(), 0)
        listener(ResearchEvent.Verified(report))

        return ResearchAnswer(
            question, q, feats.type, mode, route, answer, evidence, report, hops, false, steps, calls,
            retrievalMs, System.currentTimeMillis() - t0, model.id, linked.distinct(),
        )
    }

    /** Answer from the model's own memory, clearly labelled. Only on explicit user request. */
    suspend fun answerFromMemory(question: String, onDelta: (String) -> Unit): String {
        val m = models.get(ModelRole.STRONG) ?: models.get(ModelRole.FAST) ?: error("no model")
        val r = m.chat(
            listOf(ChatMessage.system(Prompts.MEMORY_SYSTEM), ChatMessage.user(question)),
            GenerationParams(maxTokens = 400, temperature = 0.3f),
        ) { onDelta(it) }
        return r.text
    }

    // ------------------------------------------------------------------ helpers

    data class Plan(val type: QuestionType, val subquestions: List<String>, val queries: List<String>)

    private suspend fun planWith(model: LanguageModel, q: String, calls: MutableList<LlmCall>): Plan {
        val r = model.chat(
            listOf(ChatMessage.system(Prompts.PLAN_SYSTEM), ChatMessage.user(Prompts.planUser(q))),
            GenerationParams(maxTokens = 220, grammar = Prompts.PLAN_GRAMMAR),
        )
        calls += LlmCall("plan", r.modelId, r.stats)
        val obj = json.parseToJsonElement(r.text.substring(r.text.indexOf('{'))).jsonObject
        val type = when (obj["type"]?.jsonPrimitive?.content) {
            "lookup" -> QuestionType.LOOKUP
            "explanation" -> QuestionType.EXPLANATION
            "comparison" -> QuestionType.COMPARISON
            "multihop" -> QuestionType.MULTIHOP
            "synthesis" -> QuestionType.SYNTHESIS
            "numeric" -> QuestionType.NUMERIC
            else -> QuestionType.AMBIGUOUS
        }
        val subs = obj["subquestions"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }?.filter { it.length > 3 } ?: emptyList()
        val qs = obj["queries"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return Plan(type, subs, qs)
    }

    private fun fallbackPlan(f: QueryFeatures): Plan = when (f.type) {
        QuestionType.COMPARISON -> Plan(f.type, f.comparands.map { "What is $it?" }, f.comparands)
        else -> Plan(f.type, emptyList(), f.entities)
    }

    private fun stripCitations(s: String) = s.replace(Regex("\\s*\\[[\\d,\\s–-]+]"), "").trim()

    private fun substitute(q: String, answers: List<String>): String {
        var s = q
        answers.forEachIndexed { i, a -> if (a.isNotBlank()) s = s.replace("#${i + 1}", a) }
        return s.replace(Regex("#\\d"), "it")
    }

    private fun looksLikeFollowUp(q: String): Boolean {
        val w = Text.words(q)
        if (w.size <= 3) return true
        return Regex("^(and|also|what about|how about|why|so|then|but|compared)\\b|\\b(he|she|it|they|him|her|them|his|its|their|there|that|this|those|these)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(q) && QueryAnalyzer.analyze(q).entities.isEmpty()
    }

    private fun insufficientAnswer(q: String, f: QueryFeatures, nearest: List<String>): String = buildString {
        append("The offline library does not contain enough information to answer this reliably, so I won't guess.")
        append("\n\nSearched for: ").append((f.entities.ifEmpty { f.words.take(6) }).joinToString(", "))
        if (nearest.isNotEmpty()) append("\nClosest articles found: ").append(nearest.joinToString(", "))
        append("\n\nYou can rephrase the question, or ask for an unverified answer from the model's memory.")
    }
}
