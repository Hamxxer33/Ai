package io.kestrel.research.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.kestrel.engine.bench.BenchQuestion
import io.kestrel.engine.bench.BenchResult
import io.kestrel.engine.bench.BenchmarkRunner
import io.kestrel.engine.llm.ChatMessage
import io.kestrel.engine.llm.GenerationParams
import io.kestrel.engine.llm.LlamaModel
import io.kestrel.engine.llm.ModelRole
import io.kestrel.research.KestrelApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BenchState(
    val running: Boolean = false,
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val results: List<BenchResult> = emptyList(),
    val runtimeLines: List<String> = emptyList(),
    val outFile: String? = null,
    val error: String? = null,
)

class BenchmarkViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as KestrelApp).container
    private val _state = MutableStateFlow(BenchState())
    val state: StateFlow<BenchState> = _state
    private var job: Job? = null

    val questions: List<BenchQuestion> by lazy {
        val external = File(c.storage.benchDir(), "questions.jsonl")
        val text = if (external.exists()) external.readText()
        else app.assets.open("benchmark/questions.jsonl").bufferedReader().use { it.readText() }
        BenchmarkRunner.loadQuestions(text)
    }

    fun runResearch(filter: (BenchQuestion) -> Boolean, label: String) {
        if (job?.isActive == true) return
        val qs = questions.filter(filter)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(c.storage.benchDir(), "results-$stamp.jsonl")
        _state.value = BenchState(running = true, label = label, total = qs.size, outFile = out.absolutePath)
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val runner = BenchmarkRunner(c.engine())
                for (q in qs) {
                    val r = runner.run(q)
                    out.appendText(runner.encode(r) + "\n")
                    _state.update { it.copy(done = it.done + 1, results = it.results + r) }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    /** Prefill/decode speed of each assigned model on this phone (same prompt, 2 runs). */
    fun runRuntime() {
        if (job?.isActive == true) return
        _state.value = BenchState(running = true, label = "runtime")
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                c.invalidate()
                val filler = (1..45).joinToString(" ") { "Sentence number $it describes a river, a mountain and a city in some detail." }
                for ((role, file) in c.catalog.byRole()) {
                    if (role == ModelRole.EMBED) continue
                    val line = StringBuilder("${role.name.lowercase()} ${file.name}: ")
                    val t0 = System.currentTimeMillis()
                    val m = c.runtime.get(role) as LlamaModel
                    line.append("load ${fmtMs(System.currentTimeMillis() - t0)}; ")
                    for (run in 1..2) {
                        m.clearCache()
                        val r = m.chat(listOf(ChatMessage.user("Summarise in one sentence: $filler")), GenerationParams(maxTokens = 64, reusePrefix = false))
                        if (run == 2) line.append(
                            "prefill ${r.stats.prompt_tokens} tok @ %.1f tok/s, decode %.1f tok/s, TTFT %s".format(
                                r.stats.prefillTokensPerSecond, r.stats.decodeTokensPerSecond, fmtMs(r.stats.ttft_ms.toLong()),
                            ),
                        )
                    }
                    val mem = c.device.memory()
                    line.append("; app RSS ${mem.appRssMb} MB, avail ${mem.availMb} MB")
                    _state.update { it.copy(runtimeLines = it.runtimeLines + line.toString()) }
                }
                val out = File(c.storage.benchDir(), "runtime-${System.currentTimeMillis()}.txt")
                out.writeText(c.device.describe() + "\n" + _state.value.runtimeLines.joinToString("\n") + "\n")
                _state.update { it.copy(outFile = out.absolutePath) }
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun stop() { job?.cancel() }
}

@Composable
fun BenchmarkScreen(vm: BenchmarkViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val view = LocalView.current
    DisposableEffect(s.running) {
        view.keepScreenOn = s.running
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Runtime speed (this phone)") {
            Text("Loads each assigned model and measures prompt reading and generation speed.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.runRuntime() }, enabled = !s.running) { Text("Run runtime benchmark") }
            s.runtimeLines.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
        SectionCard("Research benchmark (${vm.questions.size} questions)") {
            Text("Runs the full pipeline on each question and writes every answer, source, timing and memory figure to a JSONL file for scoring on a computer (tools/score_bench.py).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.runResearch({ true }, "all") }, enabled = !s.running) { Text("Run all") }
                OutlinedButton(onClick = {
                    val picks = vm.questions.groupBy { it.category }.values.flatMap { it.take(2) }.map { it.id }.toSet()
                    vm.runResearch({ it.id in picks }, "sample")
                }, enabled = !s.running) { Text("2 per category") }
                if (s.running) OutlinedButton(onClick = { vm.stop() }) { Text("Stop") }
            }
            if (s.total > 0) {
                LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                Text("${s.done}/${s.total} (${s.label})", fontSize = 12.sp)
            }
            if (s.results.isNotEmpty()) {
                val rs = s.results
                val matched = rs.mapNotNull { it.answer_match }
                val facts = rs.mapNotNull { it.key_fact_recall }
                val abst = rs.mapNotNull { it.abstain_correct }
                val lat = rs.map { it.total_ms }.sorted()
                val ttft = rs.mapNotNull { it.answer_ttft_ms }.sorted()
                Text(
                    buildString {
                        append("answer match: ${matched.count { it }}/${matched.size}\n")
                        append("key-fact recall: %.2f\n".format(facts.average().takeIf { !it.isNaN() } ?: 0.0))
                        append("abstention correct: ${abst.count { it }}/${abst.size}\n")
                        append("claims supported: ${rs.sumOf { it.claims_supported }}/${rs.sumOf { it.claims_checkable }}\n")
                        append("median latency: ${fmtMs(lat[lat.size / 2])}\n")
                        if (ttft.isNotEmpty()) append("median time to first word: ${fmtMs(ttft[ttft.size / 2].toLong())}\n")
                        append("peak RSS: ${rs.mapNotNull { it.peak_rss_mb }.maxOrNull() ?: 0} MB")
                    },
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        s.outFile?.let {
            SectionCard("Output") {
                SelectionContainer { Text("adb pull $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
