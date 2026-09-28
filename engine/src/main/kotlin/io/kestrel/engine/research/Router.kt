package io.kestrel.engine.research

import io.kestrel.engine.llm.ModelRole
import io.kestrel.engine.retrieval.QueryFeatures
import io.kestrel.engine.retrieval.QuestionType

enum class ResearchMode { QUICK, AUTO, DEEP }

/** How one question will be researched: which models, how many steps, how much to read. */
data class Route(
    val answerRole: ModelRole,
    val plan: Boolean,
    val hops: Boolean,
    val llmVerify: Boolean,
    val evidenceTokens: Int,
    val maxSources: Int,
    val answerWords: Int,
    val answerTokens: Int,
    val retrieveK: Int,
    val reason: String,
)

/**
 * Picks the cheapest route expected to answer the question well. The rules encode the design in
 * docs/ARCHITECTURE.md: easy questions never touch the large model; hard ones get decomposition,
 * per-hop extraction on the fast model and a short synthesis prompt on the strongest model.
 */
object Router {
    fun route(f: QueryFeatures, mode: ResearchMode, available: Set<ModelRole>): Route {
        fun pick(vararg prefs: ModelRole): ModelRole = prefs.firstOrNull { it in available }
            ?: available.firstOrNull { it != ModelRole.EMBED } ?: ModelRole.FAST
        val hard = f.type in setOf(QuestionType.MULTIHOP, QuestionType.SYNTHESIS, QuestionType.COMPARISON)
        return when (mode) {
            ResearchMode.QUICK -> Route(
                answerRole = pick(ModelRole.FAST, ModelRole.STRONG),
                plan = false, hops = false, llmVerify = false,
                evidenceTokens = 500, maxSources = 5, answerWords = 90, answerTokens = 200, retrieveK = 8,
                reason = "quick mode: fast model, compact evidence",
            )
            ResearchMode.DEEP -> Route(
                answerRole = pick(ModelRole.DEEP, ModelRole.STRONG, ModelRole.FAST),
                plan = true, hops = f.type == QuestionType.MULTIHOP || f.confidence < 0.7 || hard,
                llmVerify = true,
                evidenceTokens = if (ModelRole.DEEP in available) 900 else 1300,
                maxSources = 8, answerWords = 300, answerTokens = 600, retrieveK = 14,
                reason = "deep mode: decomposition, strongest model, full verification",
            )
            ResearchMode.AUTO -> when (f.type) {
                // a low-confidence "lookup" is just an unrecognised question: long ones go to the strong model
                QuestionType.LOOKUP -> if (f.confidence < 0.5 && f.words.size >= 6) Route(
                    answerRole = pick(ModelRole.STRONG, ModelRole.FAST),
                    plan = false, hops = false, llmVerify = false,
                    evidenceTokens = 900, maxSources = 6, answerWords = 180, answerTokens = 380, retrieveK = 10,
                    reason = "unrecognised question: strong model",
                ) else Route(
                    answerRole = pick(ModelRole.FAST, ModelRole.STRONG),
                    plan = false, hops = false, llmVerify = false,
                    evidenceTokens = 550, maxSources = 5, answerWords = 90, answerTokens = 200, retrieveK = 8,
                    reason = "lookup: fast model on compact evidence",
                )
                QuestionType.AMBIGUOUS -> Route(
                    answerRole = pick(ModelRole.FAST, ModelRole.STRONG),
                    plan = false, hops = false, llmVerify = false,
                    evidenceTokens = 700, maxSources = 6, answerWords = 140, answerTokens = 280, retrieveK = 10,
                    reason = "ambiguous: cover the main readings found",
                )
                QuestionType.EXPLANATION, QuestionType.NUMERIC -> Route(
                    answerRole = pick(ModelRole.STRONG, ModelRole.FAST),
                    plan = f.confidence < 0.6, hops = false, llmVerify = f.type == QuestionType.NUMERIC,
                    evidenceTokens = 1000, maxSources = 6, answerWords = 180, answerTokens = 380, retrieveK = 10,
                    reason = "${f.type.name.lowercase()}: strong model",
                )
                QuestionType.COMPARISON, QuestionType.SYNTHESIS -> Route(
                    answerRole = pick(ModelRole.STRONG, ModelRole.DEEP, ModelRole.FAST),
                    plan = true, hops = false, llmVerify = true,
                    evidenceTokens = 1100, maxSources = 8, answerWords = 220, answerTokens = 440, retrieveK = 12,
                    reason = "${f.type.name.lowercase()}: per-aspect retrieval, strong model",
                )
                QuestionType.MULTIHOP -> Route(
                    answerRole = pick(ModelRole.STRONG, ModelRole.DEEP, ModelRole.FAST),
                    plan = true, hops = true, llmVerify = true,
                    evidenceTokens = 900, maxSources = 7, answerWords = 160, answerTokens = 340, retrieveK = 10,
                    reason = "multi-hop: decomposition with per-hop extraction",
                )
            }
        }
    }
}
