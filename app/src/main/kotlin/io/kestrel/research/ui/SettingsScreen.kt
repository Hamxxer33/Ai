package io.kestrel.research.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kestrel.engine.llm.systemInfo
import io.kestrel.research.AppContainer
import io.kestrel.research.BuildConfig
import io.kestrel.research.ui.theme.Signal
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val s = container.settings
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Offline guarantee") {
            val noNet = container.device.networkImpossible()
            Text(
                if (noNet) "This app has no INTERNET permission: Android does not let it open any network connection."
                else "WARNING: this build holds the INTERNET permission.",
                fontSize = 13.sp, color = if (noNet) Signal.ok else Signal.bad,
            )
            Text("Airplane mode: ${if (container.device.airplaneMode()) "on" else "off"}", fontSize = 13.sp)
            Text("Requested permissions:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            container.device.requestedPermissions().forEach { Text("• ${it.removePrefix("android.permission.")}", fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            Text("No Google Play Services, no analytics, no downloader. Models and packs are read from local storage only.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Runtime") {
            var threads by remember { mutableFloatStateOf(s.threads.toFloat()) }
            Text("CPU threads: ${if (threads.toInt() == 0) "auto (${container.device.bigCores()} big cores)" else threads.toInt().toString()}", fontSize = 13.sp)
            Slider(value = threads, onValueChange = { threads = it }, valueRange = 0f..8f, steps = 7, onValueChangeFinished = {
                s.threads = threads.toInt(); scope.launch { container.invalidate() }
            })
            var budget by remember { mutableFloatStateOf(s.memoryBudgetMb.toFloat()) }
            Text("Model memory budget: ${if (budget.toInt() == 0) "auto" else "${budget.toInt()} MB"}", fontSize = 13.sp)
            Slider(value = budget, onValueChange = { budget = (it / 250).toInt() * 250f }, valueRange = 0f..9000f, onValueChangeFinished = { s.memoryBudgetMb = budget.toInt() })
            ToggleRow("Stream MoE experts from storage (deep model)", s.streamExperts) { s.streamExperts = it; scope.launch { container.invalidate() } }
            ToggleRow("Pin inference threads to big cores (${container.device.bigCoreIds().joinToString(",")})", s.pinBigCores) { s.pinBigCores = it; scope.launch { container.invalidate() } }
            ToggleRow("High-priority inference threads", s.highPriority) { s.highPriority = it; scope.launch { container.invalidate() } }
            ToggleRow("Dense vector retrieval (when a pack has vectors)", s.useVectors) { s.useVectors = it; scope.launch { container.invalidate() } }
        }

        SectionCard("About") {
            Text("Kestrel ${BuildConfig.VERSION_NAME} · ${container.device.describe()}", fontSize = 12.sp)
            val info = remember { container.nativeReady.fold({ runCatching { systemInfo() }.getOrDefault("") }, { "native library failed: ${it.message}" }) }
            SelectionContainer { Text(info, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Open source (Apache-2.0). Inference: llama.cpp (MIT). Knowledge: see each pack's licence.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = v, onCheckedChange = { v = it; onChange(it) })
    }
}
