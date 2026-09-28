package io.kestrel.research.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kestrel.engine.llm.ModelRole
import io.kestrel.research.AppContainer
import kotlinx.coroutines.launch

private const val STORAGE_LIMIT = 50L * 1_000_000_000L

@Composable
fun LibraryScreen(container: AppContainer) {
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val models = remember(refresh) { container.catalog.entries() }
    val packs = remember(refresh) { container.storage.packDirs() }
    val total = remember(refresh) { container.storage.totalBytes() }
    val mem = remember(refresh) { container.device.memory() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Storage") {
            Text("${fmtBytes(total)} of the 50 GB budget (APK + models + packs)", fontSize = 13.sp)
            LinearProgressIndicator(progress = { (total.toFloat() / STORAGE_LIMIT).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("RAM: ${mem.totalMb / 1024.0} GB total, ${mem.availMb} MB available · app ${mem.appRssMb} MB (peak ${mem.appPeakMb} MB)".replace(Regex("(\\d+\\.\\d)\\d+"), "$1"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { container.invalidate(); refresh++ } }) { Text("Rescan") }
                if (!container.device.hasAllFilesAccess()) OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                }) { Text("Allow /sdcard/Kestrel") }
            }
        }

        SectionCard("Models (${models.size})") {
            if (models.isEmpty()) Text("No .gguf files found.", fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
            for (m in models) {
                var open by remember(m.name) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${fmtBytes(m.sizeBytes)} · ${m.file.parentFile?.parent ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        TextButton(onClick = { open = true }) { Text(m.role?.name?.lowercase() ?: "unused") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            (ModelRole.entries.map { it as ModelRole? } + listOf(null)).forEach { r ->
                                DropdownMenuItem(text = { Text(r?.name?.lowercase() ?: "unused") }, onClick = {
                                    container.settings.setRole(m.name, r)
                                    open = false
                                    scope.launch { container.invalidate(); refresh++ }
                                })
                            }
                        }
                    }
                }
            }
            Text("Roles: fast = planning, hops, checks, quick answers · strong = research answers · deep = hard synthesis (streamed MoE) · embed = query vectors", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Knowledge packs (${packs.size})") {
            if (packs.isEmpty()) Text("No packs found.", fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
            for (p in packs) {
                val m = p.manifest
                Column {
                    Text(m?.name ?: p.dir.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (m != null) {
                        Text(
                            "%,d articles · %,d passages · %s · vectors: %s".format(m.docs, m.chunks, fmtBytes(p.bytes), m.embedding?.let { "${it.model} ${it.dim}d" } ?: "none"),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Licence: ${m.license}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text(p.error ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            container.packErrors.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
        }

        SectionCard("Adding files (no network involved)") {
            SelectionContainer {
                Text(
                    "From a computer over USB:\n" +
                        "adb push model.gguf ${container.storage.appDir.absolutePath}/models/\n" +
                        "adb push packs/enwiki ${container.storage.appDir.absolutePath}/packs/\n\n" +
                        "Or copy to /sdcard/Kestrel/models and /sdcard/Kestrel/packs with any file manager after allowing all-files access.\n" +
                        "Then tap Rescan.",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
            }
        }
    }
}
