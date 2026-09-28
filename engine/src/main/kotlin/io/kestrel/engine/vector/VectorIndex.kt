package io.kestrel.engine.vector

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.PriorityQueue

/**
 * Memory-mapped IVF index with 1-bit codes for candidate generation and int8 vectors for
 * rescoring. Written by tools/build_vectors.py. Little-endian layout:
 *
 *   magic   "KVEC0001"                      8 bytes
 *   u32 dim, u32 nlist, u64 n, u32 flags, u32 reserved
 *   f32 centroids[nlist][dim]               (L2-normalised)
 *   u64 offsets[nlist + 1]                  vector positions, vectors are grouped by list
 *   u32 ids[n]                              chunk id of each position
 *   u8  codes[n][dim / 8]                   sign bits, MSB first
 *   f32 scales[n]                           int8 dequantisation scale per vector
 *   i8  vecs[n][dim]
 *
 * Only the pages actually touched by a query are read from flash.
 */
class VectorIndex private constructor(private val raf: RandomAccessFile, val dim: Int, val nlist: Int, val n: Long) : Closeable {
    private val ch = raf.channel
    private val codeBytes = dim / 8
    private val centroids: FloatArray
    private val offsets: LongArray
    private val idsOff: Long
    private val codesOff: Long
    private val scalesOff: Long
    private val vecsOff: Long
    private val maps = HashMap<Long, MappedByteBuffer>()

    init {
        var pos = 32L
        val cBuf = map(pos, nlist.toLong() * dim * 4)
        centroids = FloatArray(nlist * dim)
        cBuf.asFloatBuffer().get(centroids)
        pos += nlist.toLong() * dim * 4
        val oBuf = map(pos, (nlist + 1).toLong() * 8)
        offsets = LongArray(nlist + 1)
        oBuf.asLongBuffer().get(offsets)
        pos += (nlist + 1).toLong() * 8
        idsOff = pos
        pos += n * 4
        codesOff = pos
        pos += n * codeBytes
        scalesOff = pos
        pos += n * 4
        vecsOff = pos
    }

    private fun map(off: Long, len: Long): ByteBuffer =
        ch.map(FileChannel.MapMode.READ_ONLY, off, len).order(ByteOrder.LITTLE_ENDIAN)

    // Large regions are mapped in 1 GiB windows (a MappedByteBuffer is limited to 2 GiB).
    private val window = 1L shl 30

    private fun windowFor(abs: Long): Pair<ByteBuffer, Int> {
        val base = abs / window * window
        val buf = synchronized(maps) {
            maps.getOrPut(base) {
                val len = minOf(window + (1 shl 20), raf.length() - base)
                ch.map(FileChannel.MapMode.READ_ONLY, base, len)
            }
        }
        return buf.duplicate().order(ByteOrder.LITTLE_ENDIAN) to (abs - base).toInt()
    }

    private fun readBytes(abs: Long, dst: ByteArray, len: Int = dst.size) {
        val (b, o) = windowFor(abs)
        b.position(o)
        b.get(dst, 0, len)
    }

    private fun readInt(abs: Long): Int { val (b, o) = windowFor(abs); return b.getInt(o) }
    private fun readFloat(abs: Long): Float { val (b, o) = windowFor(abs); return b.getFloat(o) }

    data class Result(val chunkId: Long, val score: Float)

    /**
     * @param query L2-normalised query vector of length [dim]
     * @param nprobe lists to scan
     * @param rerank candidates kept after Hamming filtering, rescored with int8 dot products
     */
    fun search(query: FloatArray, k: Int, nprobe: Int = 24, rerank: Int = 400): List<Result> {
        require(query.size >= dim) { "query dim ${query.size} < index dim $dim" }
        val q = if (query.size == dim) query else normalize(query.copyOf(dim))
        // 1. closest lists
        val listScores = FloatArray(nlist)
        for (l in 0 until nlist) {
            var s = 0f
            val base = l * dim
            for (d in 0 until dim) s += centroids[base + d] * q[d]
            listScores[l] = s
        }
        val probe = (0 until nlist).sortedByDescending { listScores[it] }.take(nprobe.coerceAtMost(nlist))
        // 2. hamming filter
        val qCode = LongArray((dim + 63) / 64)
        for (d in 0 until dim) if (q[d] > 0) qCode[d / 64] = qCode[d / 64] or (1L shl (63 - d % 64))
        val heap = PriorityQueue<LongArray>(rerank + 1, compareByDescending { it[0] }) // max-heap on distance
        val block = 8192 // vectors per read; block * codeBytes stays well under the 1 MiB window overlap
        val codes = ByteArray(block * codeBytes)
        val words = LongArray(qCode.size)
        for (l in probe) {
            var p = offsets[l]
            val end = offsets[l + 1]
            while (p < end) {
                val cnt = minOf(block.toLong(), end - p).toInt()
                readBytes(codesOff + p * codeBytes, codes, cnt * codeBytes)
                for (i in 0 until cnt) {
                    java.util.Arrays.fill(words, 0L)
                    val o = i * codeBytes
                    for (b in 0 until codeBytes) {
                        words[b ushr 3] = words[b ushr 3] or ((codes[o + b].toLong() and 0xFF) shl (56 - 8 * (b and 7)))
                    }
                    var dist = 0
                    for (w in words.indices) dist += java.lang.Long.bitCount(words[w] xor qCode[w])
                    if (heap.size < rerank) heap.add(longArrayOf(dist.toLong(), p + i))
                    else if (dist < heap.peek()[0]) { heap.poll(); heap.add(longArrayOf(dist.toLong(), p + i)) }
                }
                p += cnt
            }
        }
        // 3. int8 rescoring
        val v = ByteArray(dim)
        val scored = heap.map { e ->
            val p = e[1]
            readBytes(vecsOff + p * dim, v)
            var s = 0f
            for (d in 0 until dim) s += v[d] * q[d]
            val score = s * readFloat(scalesOff + p * 4)
            Result(readInt(idsOff + p * 4).toLong() and 0xFFFFFFFFL, score)
        }
        return scored.sortedByDescending { it.score }.take(k)
    }

    override fun close() {
        synchronized(maps) { maps.clear() }
        raf.close()
    }

    companion object {
        fun open(file: File): VectorIndex {
            val raf = RandomAccessFile(file, "r")
            val head = ByteArray(32)
            raf.readFully(head)
            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val magic = String(head, 0, 8, Charsets.US_ASCII)
            require(magic == "KVEC0001") { "not a kvec file: $file" }
            bb.position(8)
            val dim = bb.int
            val nlist = bb.int
            val n = bb.long
            require(dim % 8 == 0) { "dim must be a multiple of 8" }
            return VectorIndex(raf, dim, nlist, n)
        }

        fun normalize(v: FloatArray): FloatArray {
            var ss = 0.0
            for (x in v) ss += x * x
            val inv = if (ss > 0) (1.0 / Math.sqrt(ss)).toFloat() else 0f
            for (i in v.indices) v[i] *= inv
            return v
        }
    }
}
