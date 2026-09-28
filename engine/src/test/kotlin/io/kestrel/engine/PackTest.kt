package io.kestrel.engine

import io.kestrel.engine.retrieval.EvidenceBudget
import io.kestrel.engine.retrieval.EvidenceSelector
import io.kestrel.engine.retrieval.HybridRetriever
import io.kestrel.engine.retrieval.RetrievalRequest
import io.kestrel.engine.store.JdbcSqlDb
import io.kestrel.engine.store.KnowledgePack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds a tiny pack with the same schema as tools/build_pack.py and exercises retrieval. */
class PackTest {
    private val docs = listOf(
        "Eiffel Tower" to listOf(
            "The Eiffel Tower is a wrought-iron lattice tower on the Champ de Mars in Paris, France. It is named after the engineer Gustave Eiffel, whose company designed and built the tower.",
            "Construction began in January 1887 and the tower was completed on 31 March 1889 as the entrance arch to the 1889 World's Fair.",
        ),
        "Benjamin Harrison" to listOf(
            "Benjamin Harrison was an American politician and lawyer who served as the 23rd president of the United States from 1889 to 1893.",
        ),
        "Nuclear fusion" to listOf(
            "Nuclear fusion is a reaction in which two or more atomic nuclei, usually deuterium and tritium, combine to form one or more different atomic nuclei.",
        ),
        "Nuclear fission" to listOf(
            "Nuclear fission is a reaction in which the nucleus of an atom splits into two or more smaller nuclei. Uranium-235 is used in reactors.",
        ),
    )

    private fun build(dir: File) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "corpus.sqlite")}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE meta(key TEXT PRIMARY KEY, value BLOB)")
                st.executeUpdate("CREATE TABLE docs(id INTEGER PRIMARY KEY, title TEXT, first_chunk INTEGER, n_chunks INTEGER, prior REAL, words INTEGER, url TEXT)")
                st.executeUpdate("CREATE TABLE chunks(id INTEGER PRIMARY KEY, doc_id INTEGER, ord INTEGER, section TEXT, text BLOB)")
                st.executeUpdate("CREATE TABLE titles(norm TEXT, doc_id INTEGER, kind INTEGER)")
                st.executeUpdate("CREATE VIRTUAL TABLE chunks_fts USING fts5(title, section, body, content='', tokenize='porter unicode61 remove_diacritics 2')")
                st.executeUpdate("CREATE TABLE term_df(term TEXT PRIMARY KEY, df INTEGER)")
            }
            var cid = 0L
            docs.forEachIndexed { i, (title, chunks) ->
                val did = i + 1L
                c.prepareStatement("INSERT INTO docs VALUES (?,?,?,?,0.5,100,NULL)").use { it.setLong(1, did); it.setString(2, title); it.setLong(3, cid + 1); it.setInt(4, chunks.size); it.execute() }
                c.prepareStatement("INSERT INTO titles VALUES (?,?,0)").use { it.setString(1, io.kestrel.engine.text.Text.normTitle(title)); it.setLong(2, did); it.execute() }
                chunks.forEachIndexed { o, t ->
                    cid++
                    c.prepareStatement("INSERT INTO chunks VALUES (?,?,?,'',?)").use { it.setLong(1, cid); it.setLong(2, did); it.setInt(3, o); it.setBytes(4, t.toByteArray()); it.execute() }
                    c.prepareStatement("INSERT INTO chunks_fts(rowid, title, section, body) VALUES (?,?,'',?)").use { it.setLong(1, cid); it.setString(2, title); it.setString(3, t); it.execute() }
                }
            }
        }
        File(dir, "manifest.json").writeText("""{"id":"test","name":"Test","chunks":5,"docs":4}""")
    }

    @Test
    fun hybridRetrievalFindsBothHops() {
        val dir = createTempDirectory("pack").toFile()
        build(dir)
        KnowledgePack.open(dir) { JdbcSqlDb.openReadOnly(it) }.use { pack ->
            val r = HybridRetriever(listOf(pack)).retrieve(RetrievalRequest("When was the Eiffel Tower completed?", k = 3))
            assertEquals("Eiffel Tower", r.items.first().chunk.title)
            assertTrue("Eiffel Tower" in r.linkedDocs)
            val ev = EvidenceSelector.select("When was the Eiffel Tower completed?", r.items, EvidenceBudget(200, 3))
            assertTrue(ev.first().text.contains("1889"), ev.toString())
            val f = HybridRetriever(listOf(pack)).retrieve(RetrievalRequest("Compare nuclear fission and nuclear fusion", k = 4))
            val titles = f.items.map { it.chunk.title }.toSet()
            assertTrue("Nuclear fission" in titles && "Nuclear fusion" in titles, titles.toString())
        }
    }

    @Test
    fun vectorIndexRoundTrip() {
        // write a 3-vector, 16-dim index in the KVEC0001 layout and search it
        val dim = 16
        val vecs = listOf(FloatArray(dim) { if (it == 0) 1f else 0f }, FloatArray(dim) { if (it == 1) 1f else 0f }, FloatArray(dim) { if (it < 2) 0.7071f else 0f })
        val ids = listOf(10, 20, 30)
        val f = File.createTempFile("idx", ".kvec")
        val n = vecs.size
        val size = 32 + dim * 4 + 2 * 8 + n * 4 + n * (dim / 8) + n * 4 + n * dim
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("KVEC0001".toByteArray()); bb.putInt(dim); bb.putInt(1); bb.putLong(n.toLong()); bb.putInt(0); bb.putInt(0)
        for (d in 0 until dim) bb.putFloat(if (d < 2) 0.7071f else 0f)
        bb.putLong(0); bb.putLong(n.toLong())
        ids.forEach { bb.putInt(it) }
        for (v in vecs) for (b in 0 until dim / 8) {
            var byte = 0
            for (k in 0 until 8) if (v[b * 8 + k] > 0) byte = byte or (1 shl (7 - k))
            bb.put(byte.toByte())
        }
        for (v in vecs) bb.putFloat(v.maxOf { kotlin.math.abs(it) } / 127f)
        for (v in vecs) { val s = v.maxOf { kotlin.math.abs(it) } / 127f; v.forEach { bb.put(Math.round(it / s).toByte()) } }
        f.writeBytes(bb.array())
        io.kestrel.engine.vector.VectorIndex.open(f).use { idx ->
            val r = idx.search(FloatArray(dim) { if (it == 1) 1f else 0f }, 2, nprobe = 1, rerank = 3)
            assertEquals(20L, r[0].chunkId)
            assertEquals(30L, r[1].chunkId)
        }
    }
}
