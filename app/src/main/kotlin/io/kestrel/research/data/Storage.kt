package io.kestrel.research.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import io.kestrel.engine.llm.ModelRole
import io.kestrel.engine.research.ResearchMode
import io.kestrel.engine.store.KnowledgePack
import io.kestrel.engine.store.PackManifest
import java.io.File

/**
 * Where models and knowledge packs live. Nothing is ever downloaded: files are copied to the
 * phone (adb push, USB file transfer) before use. Two locations are scanned:
 *  1. /sdcard/Android/data/io.kestrel.research/files/{models,packs}  (no permission needed)
 *  2. /sdcard/Kestrel/{models,packs}                                 (needs "All files access")
 */
class Storage(private val context: Context, private val device: DeviceInfo) {
    val appDir: File get() = context.getExternalFilesDir(null) ?: context.filesDir
    val sharedDir: File get() = File(Environment.getExternalStorageDirectory(), "Kestrel")

    fun roots(): List<File> = buildList {
        add(appDir)
        if (device.hasAllFilesAccess()) add(sharedDir)
    }.distinctBy { it.absolutePath }

    fun ensureDirs() {
        File(appDir, "models").mkdirs()
        File(appDir, "packs").mkdirs()
        File(appDir, "bench").mkdirs()
    }

    fun modelFiles(): List<File> = roots().flatMap { r ->
        File(r, "models").listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }?.toList() ?: emptyList()
    }.sortedBy { it.name.lowercase() }

    data class PackDir(val dir: File, val manifest: PackManifest?, val error: String?, val bytes: Long)

    fun packDirs(): List<PackDir> = roots().flatMap { r ->
        File(r, "packs").listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    }.map { d ->
        val bytes = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        runCatching { PackDir(d, KnowledgePack.readManifest(d), null, bytes) }
            .getOrElse { PackDir(d, null, it.message ?: "invalid manifest", bytes) }
    }

    fun benchDir(): File = File(appDir, "bench").also { it.mkdirs() }

    /** Bytes used by everything the app depends on (APK + models + packs). */
    fun totalBytes(): Long {
        val apk = runCatching { File(context.applicationInfo.sourceDir).length() }.getOrDefault(0L)
        return apk + modelFiles().sumOf { it.length() } + packDirs().sumOf { it.bytes }
    }
}

/** User settings (SharedPreferences). */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("kestrel", Context.MODE_PRIVATE)

    var threads: Int
        get() = p.getInt("threads", 0)
        set(v) = p.edit().putInt("threads", v).apply()

    var mode: ResearchMode
        get() = runCatching { ResearchMode.valueOf(p.getString("mode", "AUTO")!!) }.getOrDefault(ResearchMode.AUTO)
        set(v) = p.edit().putString("mode", v.name).apply()

    var streamExperts: Boolean
        get() = p.getBoolean("stream_experts", true)
        set(v) = p.edit().putBoolean("stream_experts", v).apply()

    var useVectors: Boolean
        get() = p.getBoolean("use_vectors", true)
        set(v) = p.edit().putBoolean("use_vectors", v).apply()

    var highPriority: Boolean
        get() = p.getBoolean("high_priority", true)
        set(v) = p.edit().putBoolean("high_priority", v).apply()

    var pinBigCores: Boolean
        get() = p.getBoolean("pin_big_cores", true)
        set(v) = p.edit().putBoolean("pin_big_cores", v).apply()

    var memoryBudgetMb: Int
        get() = p.getInt("memory_budget_mb", 0)
        set(v) = p.edit().putInt("memory_budget_mb", v).apply()

    fun roleOverride(file: String): ModelRole? = p.getString("role:$file", null)?.let { runCatching { ModelRole.valueOf(it) }.getOrNull() }
    fun roleDisabled(file: String): Boolean = p.getString("role:$file", null) == "NONE"
    fun setRole(file: String, role: ModelRole?) = p.edit().putString("role:$file", role?.name ?: "NONE").apply()
}
