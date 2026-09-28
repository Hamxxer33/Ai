@file:OptIn(ExperimentalMaterial3Api::class)

package io.kestrel.research.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kestrel.engine.research.ResearchMode
import io.kestrel.engine.research.StepStatus
import io.kestrel.engine.research.Verdict
import io.kestrel.engine.retrieval.Evidence
import io.kestrel.research.AppContainer
import io.kestrel.research.ui.theme.Signal

@Composable
fun ResearchScreen(vm: ResearchViewModel, container: AppContainer) {
    val turns by vm.turns.collectAsState()
    val mode by vm.mode.collectAsState()
    val runtime by vm.runtime.collectAsState()
    var input by remember { mutableStateOf("") }
    var openSource by remember { mutableStateOf<Evidence?>(null) }
    val listState = rememberLazyListState()
    val running = turns.lastOrNull()?.running == true

    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val modes = listOf(ResearchMode.QUICK to "Quick", ResearchMode.AUTO to "Research", ResearchMode.DEEP to "Deep")
            modes.forEachIndexed { i, (m, label) ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { vm.setMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(label) }
            }
        }
        if (runtime.loading != null || runtime.error != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (runtime.loading != null) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading ${runtime.loading}…", fontSize = 12.sp)
                } else Text(runtime.error ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        Box(Modifier.weight(1f)) {
            if (turns.isEmpty()) EmptyState(container) else LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(turns, key = { it.id }) { t -> TurnCard(t, onCite = { n -> openSource = t.sources.firstOrNull { it.n == n } }, onMemory = { vm.memoryAnswer(t.id) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask a research question…") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!running) { vm.ask(input); input = "" } }),
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                FilledIconButton(onClick = { vm.stop() }) { Icon(Icons.Filled.Close, "Stop") }
            } else {
                FilledIconButton(onClick = { vm.ask(input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.Filled.Send, "Ask") }
            }
        }
    }

    openSource?.let { ev ->
        AlertDialog(
            onDismissRequest = { openSource = null },
            confirmButton = { TextButton(onClick = { openSource = null }) { Text("Close") } },
            title = { Text("[${ev.n}] ${ev.title}", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ev.section.isNotBlank()) Text(ev.section, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Used as evidence:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(ev.text, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text("Full passage (${ev.retrieved.chunk.packId}, chunk ${ev.retrieved.chunk.id}):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(ev.retrieved.chunk.text, style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
}

@Composable
private fun EmptyState(container: AppContainer) {
    val models = remember { container.catalog.entries() }
    val packs = remember { container.storage.packDirs() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline research", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Answers come only from the knowledge packs and models stored on this phone. Every claim is cited and checked against its source.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard("On this phone") {
            Text("${models.count { it.role != null }} model(s): " + models.filter { it.role != null }.joinToString { "${it.role!!.name.lowercase()} ${it.name}" }.ifEmpty { "none" }, fontSize = 13.sp)
            Text("${packs.size} knowledge pack(s): " + packs.joinToString { it.manifest?.name ?: it.dir.name }.ifEmpty { "none" }, fontSize = 13.sp)
            if (models.isEmpty() || packs.isEmpty()) {
                Text("Copy files into:\n${container.storage.appDir.absolutePath}/models and /packs\n(see Library for details)", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        SectionCard("Modes") {
            Text("Quick — fast model, short answer.\nResearch — the router picks models and steps per question.\nDeep — decomposition, strongest model, full claim verification.", fontSize = 13.sp)
        }
    }
}

@Composable
private fun TurnCard(t: TurnUi, onCite: (Int) -> Unit, onMemory: () -> Unit) {
    var showSteps by remember(t.id) { mutableStateOf(true) }
    var showSources by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(t.running) { if (!t.running) showSteps = false }
    SectionCard {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t.question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(t.mode.name.lowercase())
                t.result?.let { Pill(it.type.name.lowercase()) }
                t.result?.answerModel?.let { Pill(it) }
                if (t.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            if (t.result != null && t.result.standaloneQuestion != t.question) {
                Text("Interpreted as: ${t.result.standaloneQuestion}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // research steps
            Row(Modifier.fillMaxWidth().clickable { showSteps = !showSteps }, verticalAlignment = Alignment.CenterVertically) {
                Text("Research steps (${t.steps.size})", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                Icon(if (showSteps) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary)
            }
            if (showSteps) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (s in t.steps) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp)) {
                            Dot(
                                when (s.status) {
                                    StepStatus.DONE -> Signal.ok
                                    StepStatus.RUNNING -> MaterialTheme.colorScheme.primary
                                    StepStatus.FAILED -> Signal.bad
                                    StepStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(s.label + if (s.ms > 0) "  ·  ${fmtMs(s.ms)}" else "", fontSize = 13.sp)
                            if (s.detail.isNotBlank()) Text(s.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                        }
                    }
                }
            }
            HorizontalDivider()
            if (t.answer.isNotBlank()) MarkdownText(t.answer, onCite)
            else if (t.running) Text("Researching…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            t.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            // verification
            t.verification?.takeIf { it.claims.isNotEmpty() }?.let { v ->
                val rate = v.supportRate
                val color = if (rate >= 0.8) Signal.ok else if (rate >= 0.5) Signal.warn else Signal.bad
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(color); Spacer(Modifier.width(6.dp))
                    Text("Grounding: ${v.summary()}", fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
                }
                val flagged = v.claims.filter { it.verdict == Verdict.UNSUPPORTED || it.verdict == Verdict.UNCITED }
                if (flagged.isNotEmpty()) Column {
                    Text("Not supported by the cited sources:", fontSize = 12.sp, color = Signal.bad)
                    flagged.take(4).forEach { Text("• " + it.sentence.take(160), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (t.result?.abstained == true) {
                OutlinedButton(onClick = onMemory, enabled = !t.running) { Text("Answer from model memory (unverified)") }
            }
            t.memoryAnswer?.let {
                SectionCard("Unverified — from model memory, no sources") { Text(it.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium) }
            }
            // sources
            if (t.sources.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().clickable { showSources = !showSources }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Sources (${t.sources.size})", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Icon(if (showSources) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.primary)
                }
                if (showSources) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in t.sources) {
                        Column(Modifier.fillMaxWidth().clickable { onCite(s.n) }) {
                            Text("[${s.n}] ${s.title}" + if (s.section.isNotBlank()) " — ${s.section}" else "", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(s.text.take(220), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                        }
                    }
                }
            }
            // metrics
            t.result?.let { r ->
                val ans = r.llmCalls.lastOrNull { it.step == "answer" }?.stats
                val parts = buildList {
                    add("total ${fmtMs(r.totalMs)}")
                    add("search ${fmtMs(r.retrievalMs)}")
                    ans?.let {
                        add("first word ${fmtMs(it.ttft_ms.toLong())}")
                        add("%.1f tok/s".format(it.decodeTokensPerSecond))
                        add("${it.prompt_tokens} prompt tok")
                    }
                    add("${r.llmCalls.size} model calls")
                }
                Text(parts.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
