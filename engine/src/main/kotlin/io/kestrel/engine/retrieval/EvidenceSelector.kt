package io.kestrel.engine.retrieval

import io.kestrel.engine.text.Sentences
import io.kestrel.engine.text.Text
import kotlin.math.max

/** One numbered source handed to a model: the selected sentences of one retrieved chunk. */
data class Evidence(
    val n: Int,
    val retrieved: Retrieved,
    val sentences: List<String>,
    val score: Double,
) {
    val title get() = retrieved.chunk.title
    val section get() = retrieved.chunk.section
    val text: String get() = sentences.joinToString(" ")

    fun render(): String = buildString {
        append('[').append(n).append("] ").append(title)
        if (section.isNotBlank()) append(" — ").append(section)
        append('\n').append(text)
    }
}

data class EvidenceBudget(val maxTokens: Int, val maxSources: Int = 8, val minPerSource: Int = 1, val neighbours: Boolean = true)

/**
 * Sentence-level evidence compression. A retrieved passage usually has one or two sentences that
 * matter; the model reads those (plus a neighbour for context) instead of the whole passage,
 * which is what keeps phone prefill time down.
 */
object EvidenceSelector {
    private val ANAPHOR = Regex("^(he|she|it|they|this|these|that|those|its|his|her|their|the latter|the former|such|however|in addition|also)\\b", RegexOption.IGNORE_CASE)
    private val DATEISH = Regex("\\b(1[0-9]{3}|20[0-9]{2})\\b|\\d")

    private data class Cand(val src: Int, val idx: Int, val text: String, var score: Double)

    fun select(
        question: String,
        retrieved: List<Retrieved>,
        budget: EvidenceBudget,
        extraTerms: List<String> = emptyList(),
        startAt: Int = 1,
    ): List<Evidence> {
        if (retrieved.isEmpty()) return emptyList()
        val feats = QueryAnalyzer.analyze(question)
        val qStems = (feats.stems + extraTerms.flatMap { Text.stems(it) }).toSet()
        val entStems = (feats.entities + extraTerms).flatMap { Text.stems(it) }.toSet()
        val sources = retrieved.take(budget.maxSources * 2)
        val maxChunk = sources.maxOf { it.score }.coerceAtLeast(1e-9)

        val sentencesBySrc = sources.map { Sentences.split(it.chunk.text) }
        val cands = mutableListOf<Cand>()
        val wantsDate = Regex("^when\\b|\\b(what|which) (year|date|century|decade)\\b|\\bin what year\\b", RegexOption.IGNORE_CASE).containsMatchIn(question)
        val definitional = feats.type == QuestionType.LOOKUP || feats.type == QuestionType.AMBIGUOUS || feats.type == QuestionType.EXPLANATION
        sources.forEachIndexed { si, r ->
            val sents = sentencesBySrc[si]
            // a sentence in an article implicitly talks about the article's subject
            val titleStems = Text.stems(r.chunk.title).toSet()
            sents.forEachIndexed { i, s ->
                val own = Text.stems(s).toSet()
                if (own.isEmpty()) return@forEachIndexed
                val st = own + titleStems
                val overlap = if (qStems.isEmpty()) 0.0 else qStems.count { it in st }.toDouble() / qStems.size
                val ownOverlap = if (qStems.isEmpty()) 0.0 else qStems.count { it in own }.toDouble() / qStems.size
                val ent = if (entStems.isEmpty()) 0.0 else entStems.count { it in st }.toDouble() / entStems.size
                val num = if ((feats.wantsNumber || wantsDate) && DATEISH.containsMatchIn(s)) 0.15 else 0.0
                val lead = if (r.chunk.ord == 0 && i == 0) (if (definitional) 0.12 else 0.04) else 0.0
                val len = s.length
                val lenPenalty = if (len < 40) 0.1 else if (len > 600) 0.1 else 0.0
                val score = 0.4 * overlap + 0.15 * ownOverlap + 0.15 * ent + num + lead + 0.25 * (r.score / maxChunk) - lenPenalty
                cands += Cand(si, i, s, score)
            }
        }
        // choose sentences: first guarantee the best sentence of each top source, then fill by score
        val chosen = HashMap<Int, MutableSet<Int>>()
        var used = 0
        fun cost(s: String) = Text.estimateTokens(s) + 2
        fun take(c: Cand): Boolean {
            val set = chosen.getOrPut(c.src) { mutableSetOf() }
            if (c.idx in set) return true
            var extra = cost(c.text)
            val needPrev = budget.neighbours && c.idx > 0 && ANAPHOR.containsMatchIn(c.text) && (c.idx - 1) !in set
            if (needPrev) extra += cost(sentencesBySrc[c.src][c.idx - 1])
            if (used + extra > budget.maxTokens) return false
            set += c.idx
            if (needPrev) set += c.idx - 1
            used += extra
            return true
        }
        val bySrc = cands.groupBy { it.src }
        val srcOrder = sources.indices.take(budget.maxSources)
        for (si in srcOrder) {
            bySrc[si]?.sortedByDescending { it.score }?.take(budget.minPerSource)?.forEach { take(it) }
        }
        val allowed = srcOrder.toSet()
        for (c in cands.filter { it.src in allowed }.sortedByDescending { it.score }) {
            if (c.score < 0.12) break
            if (!take(c) && used > budget.maxTokens * 0.95) break
        }
        // assemble: sources ordered by their best selected sentence (most relevant first, which
        // small models attend to most), sentences in document order
        val out = mutableListOf<Evidence>()
        var n = startAt
        val bestOf = srcOrder.associateWith { si ->
            val idx = chosen[si] ?: emptySet<Int>()
            cands.filter { it.src == si && it.idx in idx }.maxOfOrNull { it.score } ?: Double.NEGATIVE_INFINITY
        }
        for (si in srcOrder.sortedByDescending { bestOf[it] }) {
            val idx = chosen[si]?.sorted() ?: continue
            if (idx.isEmpty()) continue
            val sents = sentencesBySrc[si]
            val parts = mutableListOf<String>()
            var prev = -2
            for (i in idx) {
                if (prev >= 0 && i != prev + 1) parts += "…"
                parts += sents[i]
                prev = i
            }
            val best = cands.filter { it.src == si && it.idx in idx }.maxOfOrNull { it.score } ?: 0.0
            out += Evidence(n++, sources[si], parts, max(best, sources[si].score))
        }
        return out
    }

    fun render(evidence: List<Evidence>): String = evidence.joinToString("\n\n") { it.render() }
}
