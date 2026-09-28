package io.kestrel.engine.llm

import java.io.File

/** Receives generated UTF-8 bytes as they are produced. Return false to stop generation. */
fun interface ByteSink {
    fun onBytes(bytes: ByteArray): Boolean
}

/**
 * JNI surface of `native/src/kestrel_jni.cpp`. All text crosses the boundary as UTF-8 bytes.
 * Handles are opaque native pointers; the Kotlin wrappers in [LlamaModel] own their lifetime.
 */
object LlamaNative {
    const val FLAG_MMAP = 1
    const val FLAG_MLOCK = 2
    const val FLAG_STREAM_EXPERTS = 4
    const val FLAG_NO_REPACK = 8

    @Volatile
    private var ready = false

    /**
     * Loads the native library and initialises the ggml backends.
     * @param nativeLibDir Android: `applicationInfo.nativeLibraryDir` (where the per-CPU ggml
     *   backend variants live). Desktop: directory holding `libkestrel_jni` (or null to use
     *   `-Dkestrel.native.dir` / `java.library.path`).
     */
    @Synchronized
    fun ensureLoaded(nativeLibDir: String? = null, verbose: Boolean = false) {
        if (ready) return
        val isAndroid = System.getProperty("java.vm.vendor")?.contains("Android", ignoreCase = true) == true ||
            System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true
        if (isAndroid) {
            System.loadLibrary("kestrel_jni")
        } else {
            val dir = nativeLibDir ?: System.getProperty("kestrel.native.dir")
            val f = dir?.let { File(it, System.mapLibraryName("kestrel_jni")) }
            if (f != null && f.exists()) System.load(f.absolutePath) else System.loadLibrary("kestrel_jni")
        }
        backendInit(nativeLibDir?.toByteArray(), verbose)
        ready = true
    }

    @JvmStatic external fun backendInit(libDir: ByteArray?, verbose: Boolean)
    @JvmStatic external fun systemInfo(): ByteArray
    @JvmStatic external fun modelLoad(path: ByteArray, flags: Int, nGpuLayers: Int): Long
    @JvmStatic external fun modelFree(model: Long)
    @JvmStatic external fun modelInfo(model: Long): ByteArray
    @JvmStatic external fun applyChatTemplate(model: Long, messagesJson: ByteArray, addGenerationPrompt: Boolean, enableThinking: Boolean): ByteArray
    @JvmStatic external fun tokenize(model: Long, text: ByteArray, addSpecial: Boolean, parseSpecial: Boolean): IntArray
    @JvmStatic external fun contextCreate(
        model: Long, nCtx: Int, nBatch: Int, nUbatch: Int, nThreads: Int, nThreadsBatch: Int,
        embeddings: Boolean, pooling: Int, flashAttn: Int, nSeqMax: Int,
    ): Long
    @JvmStatic external fun contextFree(ctx: Long)
    @JvmStatic external fun cancel(ctx: Long)
    @JvmStatic external fun kvClear(ctx: Long)
    @JvmStatic external fun generate(ctx: Long, prompt: ByteArray, paramsJson: ByteArray, sink: ByteSink?): ByteArray
    @JvmStatic external fun embed(ctx: Long, texts: Array<ByteArray>, normalize: Boolean): Array<FloatArray>
}
