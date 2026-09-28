package io.kestrel.engine.retrieval

import io.kestrel.engine.llm.EmbeddingModel
import io.kestrel.engine.store.Chunk
import io.kestrel.engine.store.KnowledgePack
import io.kestrel.engine.text.Porter
import io.kestrel.engine.text.Text
import io.kestrel.engine.vector.VectorIndex
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class ChunkKey(val pack: String, val id: Long)

data class Retrieved(
    val chunk: Chunk,
    val score: Double,
    /** Per-signal contributions, for the research trace and for tuning. */
    val signals: Map<String, Double>,
) {
    val key get() = ChunkKey(chunk.packId, chunk.id)
}

data class RetrievalRequest(
    val question: String,
    /** Extra keyword queries (from the planner or a previous hop). */
    val queries: List<String> = emptyList(),
    /** Entities that must be linked to articles when possible. */
    val entities: List<String> = emptyList(),
    val k: Int = 10,
    val maxPerDoc: Int = 3,
    val useDense: Boolean = true,
)

data class RetrievalResult(
    val items: List<Retrieved>,
    val linkedDocs: List<String>,
    val timings: Map<String, Long>,
    /** Best raw evidence of relevance (0..1) — used by the sufficiency gate. */
    val confidence: Double,
)

/** Embedding-based sentence scorer for evidence selection (query and passages in the same space). */
class EmbeddingSentenceScorer(
    private val model: EmbeddingModel,
    private val queryPrefix: String = "",
    private val docPrefix: String = "",
    override val maxSentences: Int = 48,
) : SentenceScorer {
    override fun similarities(question: String, sentences: List<String>): List<Float> {
        val vs = model.embed(listOf(queryPrefix + question) + sentences.map { docPrefix + it })
        val q = vs[0]
        return vs.drop(1).map { v -> var s = 0f; for (i in q.indices) s += q[i] * v[i]; s }
    }
}

/** An embedding model plus the prefix/dimension/transform conventions of the pack that indexed with it. */
class QueryEncoder(
    private val model: EmbeddingModel,
    private val prefix: String,
    private val dim: Int,
    private val transform: String = "none",
) {
    fun encode(text: String): FloatArray {
        var v = model.embed(listOf(prefix + text)).first()
        if (transform == "layernorm") {
            val mean = v.average().toFloat()
            var varSum = 0.0
            for (x in v) varSum += (x - mean) * (x - mean)
            val sd = Math.sqrt(varSum / v.size).toFloat() + 1e-5f
            v = FloatArray(v.size) { (v[it] - mean) / sd }
        }
        return VectorIndex.normalize(if (v.size > dim) v.copyOf(dim) else v.copyOf())
    }
}

/**
 * Hybrid retrieval over one or more knowledge packs:
 * BM25 (AND of rare terms, OR of all terms, planner queries) + title/alias entity linking +
 * dense ANN, fused with weighted reciprocal-rank fusion, then reranked with cheap features.
 */
class HybridRetriever(
    private val packs: List<KnowledgePack>,
    private val encoders: Map<String, QueryEncoder> = emptyMap(),
) {
    private val rrfK = 60.0

    fun retrieve(req: RetrievalRequest): RetrievalResult {
        val timings = linkedMapOf<String, Long>()
        val feats = QueryAnalyzer.analyze(req.question)
        val lists = mutableListOf<Pair<Double, List<ChunkKey>>>() // (weight, ranked keys)
        val signalRanks = HashMap<ChunkKey, MutableMap<String, Double>>()
        fun addList(name: String, weight: Double, keys: List<ChunkKey>) {
            if (keys.isEmpty()) return
            lists += weight to keys
            keys.forEachIndexed { r, k ->
                val m = signalRanks.getOrPut(k) { HashMap() }
                m[name] = max(m[name] ?: 0.0, weight / (rrfK + r + 1))
            }
        }
        val linked = mutableListOf<String>()
        val linkedDocKeys = HashSet<Pair<String, Long>>()

        for (pack in packs) {
            // ---- lexical
            var t0 = System.nanoTime()
            val words = (Text.contentWords(req.question) + req.entities.flatMap { Text.contentWords(it) }).distinct()
            val df = pack.termDf(words.map { Porter.stem(it) })
            val total = max(1L, pack.totalChunks).toDouble()
            fun idf(w: String): Double {
                val d = df[Porter.stem(w)] ?: 0L
                return ln((total - d + 0.5) / (d + 0.5) + 1.0)
            }
            // very common words make OR queries slow and add nothing; keep them only if little else is left
            val informative = words.filter { (df[Porter.stem(it)] ?: 0L) < total * 0.03 }.ifEmpty { words }
            val rare = informative.sortedByDescending { idf(it) }
            if (rare.size >= 2) {
                val andTerms = rare.take(min(4, rare.size))
                var hits = pack.search(FtsQuery.all(andTerms), 60)
                if (hits.size < 5 && andTerms.size > 2) hits = pack.search(FtsQuery.all(andTerms.take(2)), 60)
                addList("bm25_and", 1.0, hits.map { ChunkKey(pack.id, it.chunkId) })
            }
            if (informative.isNotEmpty()) {
                addList("bm25_or", 0.8, pack.search(FtsQuery.any(informative.take(12)), 80).map { ChunkKey(pack.id, it.chunkId) })
            }
            for (ent in (req.entities + feats.entities).distinctBy { Text.fold(it) }.take(6)) {
                val ph = FtsQuery.phrase(ent) ?: continue
                // the entity together with the question's other rare terms ("president of the
                // united states" AND 1889) is far more precise than either alone
                val entWords = Text.words(ent).toSet()
                val rest = rare.filter { it !in entWords }.take(3)
                if (rest.isNotEmpty()) {
                    addList("bm25_ent_and", 1.0, pack.search("$ph AND (${FtsQuery.any(rest)})", 30).map { ChunkKey(pack.id, it.chunkId) })
                }
                addList("bm25_phrase", 0.5, pack.search(ph, 20).map { ChunkKey(pack.id, it.chunkId) })
            }
            for ((i, q) in req.queries.withIndex()) {
                val qw = Text.contentWords(q).distinct()
                if (qw.isEmpty()) continue
                val expr = if (qw.size >= 2) "(${FtsQuery.all(qw.take(4))}) OR (${FtsQuery.any(qw.take(10))})" else FtsQuery.any(qw)
                addList("bm25_q$i", 0.7, pack.search(expr, 40).map { ChunkKey(pack.id, it.chunkId) })
            }
            timings["bm25_${pack.id}"] = (System.nanoTime() - t0) / 1_000_000

            // ---- entity linking via titles and aliases
            t0 = System.nanoTime()
            val entityDocs = linkEntities(pack, req.question, req.entities + feats.entities)
            for ((docId, title, strength) in entityDocs) {
                val doc = pack.doc(docId) ?: continue
                linked += title
                linkedDocKeys += pack.id to docId
                val range = doc.firstChunk until doc.firstChunk + doc.nChunks
                val lead = listOf(ChunkKey(pack.id, doc.firstChunk))
                addList("title_lead", 1.1 * strength, lead)
                val inner = if (informative.isNotEmpty()) pack.search(FtsQuery.any(informative.take(10)), 4, range) else emptyList()
                addList("title_inner", 1.0 * strength, inner.map { ChunkKey(pack.id, it.chunkId) })
            }
            timings["titles_${pack.id}"] = (System.nanoTime() - t0) / 1_000_000

            // ---- dense
            val enc = encoders[pack.id]
            if (req.useDense && enc != null && pack.vectors != null) {
                t0 = System.nanoTime()
                val qv = enc.encode(req.question)
                timings["embed_${pack.id}"] = (System.nanoTime() - t0) / 1_000_000
                t0 = System.nanoTime()
                val res = pack.vectors.search(qv, 60)
                addList("dense", 1.0, res.map { ChunkKey(pack.id, it.chunkId) })
                for (q in req.queries.take(2)) {
                    val r2 = pack.vectors.search(enc.encode(q), 30)
                    addList("dense_q", 0.6, r2.map { ChunkKey(pack.id, it.chunkId) })
                }
                timings["ann_${pack.id}"] = (System.nanoTime() - t0) / 1_000_000
            }
        }

        // ---- fuse
        val fused = HashMap<ChunkKey, Double>()
        for ((_, keys) in lists) for (k in keys) fused[k] = 0.0
        for ((k, sig) in signalRanks) fused[k] = sig.values.sum()
        val pool = fused.entries.sortedByDescending { it.value }.take(max(40, req.k * 4)).map { it.key }

        // ---- fetch and rerank
        val t0 = System.nanoTime()
        val byPack = pool.groupBy { it.pack }
        val chunks = HashMap<ChunkKey, Chunk>()
        for ((pid, keys) in byPack) {
            val pack = packs.first { it.id == pid }
            for (c in pack.chunks(keys.map { it.id })) chunks[ChunkKey(pid, c.id)] = c
        }
        val qStems = feats.stems.toSet() + req.entities.flatMap { Text.stems(it) }
        // adjacent content-word pairs of the question ("capit austral", "walk moon"): a chunk that
        // keeps them together (at most one word apart) is usually about exactly this
        val qPairs = feats.stems.zipWithNext().filter { it.first != it.second }.toSet()
        val idfW = qStems.associateWith { s -> 1.0 + (if (s.length > 6) 0.3 else 0.0) }
        val maxFused = fused.values.maxOrNull() ?: 1.0
        val scored = pool.mapNotNull { key ->
            val c = chunks[key] ?: return@mapNotNull null
            val seq = Text.stems(c.title + ". " + c.text)
            val cStems = seq.toSet() + Text.stems(c.section)
            val prox = if (qPairs.isEmpty()) 0.0 else {
                val found = HashSet<Pair<String, String>>()
                for (i in seq.indices) for (d in 1..2) if (i + d < seq.size) {
                    val p = seq[i] to seq[i + d]
                    if (p in qPairs) found += p
                    val r = seq[i + d] to seq[i]
                    if (r in qPairs) found += r
                }
                found.size.toDouble() / qPairs.size
            }
            val titleStems = Text.stems(c.title).toSet()
            val cov = if (qStems.isEmpty()) 0.0 else qStems.sumOf { if (it in cStems) idfW[it]!! else 0.0 } / qStems.sumOf { idfW[it]!! }
            val titleOverlap = if (titleStems.isEmpty()) 0.0 else titleStems.count { it in qStems }.toDouble() / titleStems.size
            val entity = if ((key.pack to c.docId) in linkedDocKeys) 1.0 else 0.0
            val lead = if (c.ord == 0) 1.0 else 0.0
            val prior = packs.first { it.id == key.pack }.doc(c.docId)?.prior ?: 0.0
            val rrf = (fused[key] ?: 0.0) / maxFused
            val score = 0.30 * rrf + 0.30 * cov + 0.14 * prox + 0.10 * titleOverlap + 0.08 * entity + 0.04 * lead + 0.04 * prior
            val sig = (signalRanks[key] ?: emptyMap()) + mapOf(
                "rrf" to rrf, "coverage" to cov, "proximity" to prox, "title_overlap" to titleOverlap, "entity" to entity, "prior" to prior,
            )
            Retrieved(c, score, sig)
        }.sortedByDescending { it.score }

        // ---- diversity: at most maxPerDoc chunks from one article
        val perDoc = HashMap<Pair<String, Long>, Int>()
        val out = mutableListOf<Retrieved>()
        for (r in scored) {
            val d = r.chunk.packId to r.chunk.docId
            val n = perDoc[d] ?: 0
            if (n >= req.maxPerDoc) continue
            perDoc[d] = n + 1
            out += r
            if (out.size >= req.k) break
        }
        timings["rerank"] = (System.nanoTime() - t0) / 1_000_000
        val confidence = out.firstOrNull()?.signals?.get("coverage") ?: 0.0
        return RetrievalResult(out, linked.distinct(), timings, confidence)
    }

    private data class Linked(val docId: Long, val title: String, val strength: Double)

    /**
     * Entity linking: every 1-6 word n-gram of the question (and each given entity) is looked up in
     * the title/alias table. Longer and capitalised matches win; single common words need a capital.
     */
    private fun linkEntities(pack: KnowledgePack, question: String, entities: List<String>): List<Linked> {
        val words = Text.words(question)
        val raw = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?").findAll(question).map { it.value }.toList()
        val grams = LinkedHashMap<String, Double>()
        val lowercaseSingles = HashSet<String>()
        val namedGrams = HashSet<String>()
        for (n in 6 downTo 1) {
            for (i in 0..words.size - n) {
                val g = words.subList(i, i + n)
                if (g.first() in io.kestrel.engine.text.Stopwords.ALL || g.last() in io.kestrel.engine.text.Stopwords.ALL) continue
                val capitalised = (i until i + n).all { it < raw.size && (raw[it].first().isUpperCase() || raw[it].first().isDigit() || words[it] in CONNECT) }
                if (n == 1 && !capitalised && g[0].length < 3) continue
                val strength = if (n == 1 && !capitalised) 0.3 else min(1.0, 0.35 + 0.2 * n + if (capitalised) 0.3 else 0.0)
                val key = g.joinToString(" ")
                grams.merge(key, strength) { a, b -> max(a, b) }
                if (n == 1 && !capitalised) lowercaseSingles += key
                if (capitalised) namedGrams += key
            }
        }
        for (e in entities) grams.merge(Text.normTitle(e), 1.0) { a, b -> max(a, b) }
        if (grams.isEmpty()) return emptyList()
        // lowercase single words ("ice", "tides") only stand in for the topic when the question
        // names no capitalised entity; otherwise they are generic ("country", "author")
        val hasNamed = namedGrams.isNotEmpty() || entities.isNotEmpty()
        if (hasNamed) lowercaseSingles.forEach { grams.remove(it) }
        val hits = pack.lookupTitles(grams.keys)
        val result = mutableListOf<Linked>()
        val covered = mutableListOf<String>()
        for ((g, strength) in grams.entries.sortedByDescending { it.key.split(' ').size * 10 + it.value }) {
            // a lowercase common word ("ice", "tides") links only to an article with exactly that title
            val h = (hits[g] ?: continue).let { hs -> if (g in lowercaseSingles) hs.filter { it.kind == 0 } else hs }
            if (h.isEmpty()) continue
            // skip n-grams that are inside a longer matched n-gram
            if (covered.any { it.contains(g) && it != g }) continue
            covered += g
            val docs = pack.docs(h.map { it.docId })
            val best = h.sortedWith(compareBy<io.kestrel.engine.store.TitleHit> { it.kind }.thenByDescending { docs[it.docId]?.prior ?: 0.0 })
            // an exact title beats aliases; ambiguous aliases contribute their top two readings
            val take = if (best.first().kind == 0) 1 else 2
            for (t in best.take(take)) result += Linked(t.docId, t.title, strength * if (t.kind == 2) 0.7 else 1.0)
        }
        return result.distinctBy { it.docId }.take(6)
    }

    companion object {
        private val CONNECT = setOf("of", "the", "and", "de", "von", "van", "da", "in", "on", "for")
    }
}
