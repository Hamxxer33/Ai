package io.kestrel.research.data

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/** Facts about the phone that the UI shows and the runtime uses (RAM, cores, offline state). */
class DeviceInfo(private val context: Context) {
    data class Memory(val totalMb: Long, val availMb: Long, val lowMemory: Boolean, val appRssMb: Long, val appPeakMb: Long)

    fun memory(): Memory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val (rss, peak) = io.kestrel.engine.bench.MemoryProbe.rss()
        return Memory(mi.totalMem / 1_048_576, mi.availMem / 1_048_576, mi.lowMemory, rss ?: 0, peak ?: 0)
    }

    /** Max frequency (kHz) of each CPU core. */
    fun coreMaxFreqs(): List<Long> = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
        runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrDefault(0L)
    }

    /** Cores at >= 70% of the fastest core's frequency: the "big" cores worth computing on. */
    fun bigCores(): Int {
        val f = coreMaxFreqs().filter { it > 0 }
        if (f.isEmpty()) return (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        val top = f.max()
        return f.count { it >= top * 0.7 }.coerceIn(2, 8)
    }

    /** Indices of the big cores, for pinning compute threads. */
    fun bigCoreIds(): IntArray {
        val f = coreMaxFreqs()
        val top = f.maxOrNull() ?: 0L
        if (top <= 0) return IntArray(0)
        return f.indices.filter { f[it] >= top * 0.7 }.toIntArray()
    }

    fun airplaneMode(): Boolean = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    /** True when this package cannot open network sockets at all (INTERNET not granted). */
    fun networkImpossible(): Boolean =
        context.packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName) != PackageManager.PERMISSION_GRANTED

    fun requestedPermissions(): List<String> = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        pi.requestedPermissions?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun describe(): String = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.SOC_MODEL}), Android ${Build.VERSION.RELEASE}"
}
