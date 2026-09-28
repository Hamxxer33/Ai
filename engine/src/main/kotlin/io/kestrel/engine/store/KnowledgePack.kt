package io.kestrel.engine.store

import io.kestrel.engine.vector.VectorIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.util.zip.Inflater

@Serializable
data class EmbeddingSpec(
    val model: String,
    /** GGUF file name, looked up in the models directory. */
    val file: String? = null,
    /** Dimensions stored in the index (Matryoshka truncation of the model's output). */
    val dim: Int,
    val full_dim: Int = dim,
    val query_prefix: String = "",
    val doc_prefix: String = "",
    val vectors: String = "vectors.kvec",
    /** Matryoshka recipe applied before truncation: "none" or "layernorm" (nomic-embed v1.5). */
    val transform: String = "none",
    val scope: String = "",
    val count: Long = 0,
)

@Serializable
data class PackFile(val bytes: Long = 0, val sha256: String = "")

@Serializable
data class PackManifest(
    val format: String = "kestrel-pack/1",
    val id: String,
    val name: String,
    val version: String = "1",
    val license: String = "",
    val source: String = "",
    val created: String = "",
    val language: String = "en",
    val docs: Long = 0,
    val chunks: Long = 0,
    val text_codec: String = "plain",
    /** Display-only link pattern for a source ({title}); never fetched. */
    val url_template: String = "",
    val embedding: EmbeddingSpec? = null,
    val files: Map<String, PackFile> = emptyMap(),
)

data class Doc(val id: Long, val title: String, val firstChunk: Long, val nChunks: Int, val prior: Double)

data class Chunk(
    val id: Long,
    val docId: Long,
    val ord: Int,
    val title: String,
    val section: String,
    val text: String,
    val packId: String,
)

data class Hit(val chunkId: Long, val score: Double)

data class TitleHit(val docId: Long, val kind: Int, val title: String)

/**
 * A knowledge pack on disk: manifest.json + corpus.sqlite (+ optional vectors.kvec).
 *
 * Schema (see tools/build_pack.py):
 *   meta(key TEXT PRIMARY KEY, value BLOB)
 *   docs(id INTEGER PRIMARY KEY, title TEXT, first_chunk INTEGER, n_chunks INTEGER, prior REAL)
 *   chunks(id INTEGER PRIMARY KEY, doc_id INTEGER, ord INTEGER, section TEXT, text BLOB)
 *   chunks_fts  FTS5(title, section, body) contentless, rowid = chunks.id, tokenize porter unicode61
 *   titles(norm TEXT, doc_id INTEGER, kind INTEGER)      kind 0 title, 1 redirect, 2 alias
 *   term_df(term TEXT PRIMARY KEY, df INTEGER)          stemmed term -> chunk frequency
 */
class KnowledgePack(
    val dir: File,
    val manifest: PackManifest,
    private val db: SqlDb,
    val vectors: VectorIndex?,
) : Closeable {
    val id: String get() = manifest.id
    private val zdict: ByteArray? = if (manifest.text_codec == "zlib-dict-v1") {
        db.query("SELECT value FROM meta WHERE key = 'zdict'") { it.blob(0) }.firstOrNull()
    } else null
    private val docCache = object : LinkedHashMap<Long, Doc>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Doc>?) = size > 20_000
    }
    val totalChunks: Long by lazy {
        if (manifest.chunks > 0) manifest.chunks else db.query("SELECT max(id) FROM chunks") { it.long(0) }.firstOrNull() ?: 0
    }

    fun decodeText(raw: ByteArray?): String {
        if (raw == null) return ""
        if (zdict == null) return String(raw, Charsets.UTF_8)
        val inf = Inflater()
        try {
            inf.setInput(raw)
            val out = java.io.ByteArrayOutputStream(raw.size * 4)
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsDictionary()) inf.setDictionary(zdict)
                    else if (inf.needsInput()) break
                }
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            inf.end()
        }
    }

    /** BM25 over chunks. [ftsQuery] must be a valid FTS5 MATCH expression. */
    fun search(ftsQuery: String, limit: Int, rowRange: LongRange? = null): List<Hit> {
        if (ftsQuery.isBlank()) return emptyList()
        val sql = buildString {
            append("SELECT rowid, bm25(chunks_fts, 6.0, 2.0, 1.0) FROM chunks_fts WHERE chunks_fts MATCH ?")
            if (rowRange != null) append(" AND rowid BETWEEN ? AND ?")
            append(" ORDER BY 2 LIMIT ?")
        }
        val args = mutableListOf<Any?>(ftsQuery)
        if (rowRange != null) { args += rowRange.first; args += rowRange.last }
        args += limit
        return runCatching { db.query(sql, args) { Hit(it.long(0), -it.double(1)) } }.getOrElse { emptyList() }
    }

    fun chunks(ids: Collection<Long>): List<Chunk> {
        if (ids.isEmpty()) return emptyList()
        val out = HashMap<Long, Chunk>()
        for (part in ids.distinct().chunked(400)) {
            val q = part.joinToString(",") { "?" }
            db.query(
                "SELECT c.id, c.doc_id, c.ord, d.title, c.section, c.text FROM chunks c JOIN docs d ON d.id = c.doc_id WHERE c.id IN ($q)",
                part,
            ) {
                Chunk(it.long(0), it.long(1), it.long(2).toInt(), it.string(3) ?: "", it.string(4) ?: "", decodeText(it.blob(5)), id)
            }.forEach { out[it.id] = it }
        }
        return ids.mapNotNull { out[it] }
    }

    fun doc(docId: Long): Doc? = synchronized(docCache) { docCache[docId] } ?: db.query(
        "SELECT id, title, first_chunk, n_chunks, prior FROM docs WHERE id = ?", listOf(docId),
    ) { Doc(it.long(0), it.string(1) ?: "", it.long(2), it.long(3).toInt(), it.double(4)) }.firstOrNull()?.also {
        synchronized(docCache) { docCache[docId] = it }
    }

    fun docs(ids: Collection<Long>): Map<Long, Doc> {
        val out = HashMap<Long, Doc>()
        val missing = ArrayList<Long>()
        synchronized(docCache) { for (i in ids) docCache[i]?.let { out[i] = it } ?: missing.add(i) }
        for (part in missing.distinct().chunked(400)) {
            val q = part.joinToString(",") { "?" }
            db.query("SELECT id, title, first_chunk, n_chunks, prior FROM docs WHERE id IN ($q)", part) {
                Doc(it.long(0), it.string(1) ?: "", it.long(2), it.long(3).toInt(), it.double(4))
            }.forEach { out[it.id] = it; synchronized(docCache) { docCache[it.id] = it } }
        }
        return out
    }

    fun docIdOfChunk(chunkId: Long): Long? =
        db.query("SELECT doc_id FROM chunks WHERE id = ?", listOf(chunkId)) { it.long(0) }.firstOrNull()

    /** Exact lookups of normalised titles/aliases. */
    fun lookupTitles(norms: Collection<String>): Map<String, List<TitleHit>> {
        if (norms.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<TitleHit>>()
        for (part in norms.distinct().chunked(300)) {
            val q = part.joinToString(",") { "?" }
            db.query(
                "SELECT t.norm, t.doc_id, t.kind, d.title FROM titles t JOIN docs d ON d.id = t.doc_id WHERE t.norm IN ($q)",
                part,
            ) { Triple(it.string(0) ?: "", TitleHit(it.long(1), it.long(2).toInt(), it.string(3) ?: ""), 0) }
                .forEach { out.getOrPut(it.first) { mutableListOf() }.add(it.second) }
        }
        return out
    }

    /** Document frequency (in chunks) of stemmed terms; unknown terms map to 0. */
    fun termDf(stems: Collection<String>): Map<String, Long> {
        if (stems.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        for (part in stems.distinct().chunked(300)) {
            val q = part.joinToString(",") { "?" }
            runCatching {
                db.query("SELECT term, df FROM term_df WHERE term IN ($q)", part) { it.string(0)!! to it.long(1) }
            }.getOrDefault(emptyList()).forEach { out[it.first] = it.second }
        }
        return out
    }

    fun interrupt() = db.interrupt()

    override fun close() {
        db.close()
        vectors?.close()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun readManifest(dir: File): PackManifest =
            json.decodeFromString(PackManifest.serializer(), File(dir, "manifest.json").readText())

        fun open(dir: File, openDb: (String) -> SqlDb): KnowledgePack {
            val m = readManifest(dir)
            val db = openDb(File(dir, "corpus.sqlite").absolutePath)
            val vec = m.embedding?.let { e -> File(dir, e.vectors).takeIf { it.exists() }?.let { VectorIndex.open(it) } }
            return KnowledgePack(dir, m, db, vec)
        }
    }
}
