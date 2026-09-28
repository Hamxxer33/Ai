package io.kestrel.research.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.kestrel.engine.research.Exchange
import io.kestrel.engine.research.ResearchAnswer
import io.kestrel.engine.research.ResearchEvent
import io.kestrel.engine.research.ResearchMode
import io.kestrel.engine.research.StepStatus
import io.kestrel.engine.research.VerificationReport
import io.kestrel.engine.retrieval.Evidence
import io.kestrel.research.KestrelApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StepUi(val id: String, val label: String, val status: StepStatus, val detail: String, val ms: Long)

data class TurnUi(
    val id: Long,
    val question: String,
    val mode: ResearchMode,
    val steps: List<StepUi> = emptyList(),
    val answer: String = "",
    val sources: List<Evidence> = emptyList(),
    val verification: VerificationReport? = null,
    val result: ResearchAnswer? = null,
    val running: Boolean = true,
    val error: String? = null,
    val memoryAnswer: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
)

class ResearchViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as KestrelApp).container
    private val _turns = MutableStateFlow<List<TurnUi>>(emptyList())
    val turns: StateFlow<List<TurnUi>> = _turns
    private val _mode = MutableStateFlow(c.settings.mode)
    val mode: StateFlow<ResearchMode> = _mode
    val runtime = c.runtime.status
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    fun setMode(m: ResearchMode) {
        _mode.value = m
        c.settings.mode = m
    }

    private fun update(id: Long, f: (TurnUi) -> TurnUi) = _turns.update { list -> list.map { if (it.id == id) f(it) else it } }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || busy) return
        val id = System.nanoTime()
        val mode = _mode.value
        val history = _turns.value.filter { it.result != null && !it.result.abstained }.takeLast(2)
            .map { Exchange(it.result!!.standaloneQuestion, it.answer) }
        _turns.update { it + TurnUi(id, q, mode) }
        job = viewModelScope.launch(Dispatchers.Default) {
            val buffer = StringBuilder()
            val ticker = launch {
                while (isActive) {
                    delay(120)
                    val text = synchronized(buffer) { buffer.toString() }
                    update(id) { if (it.answer.length != text.length) it.copy(answer = text) else it }
                }
            }
            try {
                val engine = c.engine()
                val result = engine.research(q, mode, history) { ev ->
                    when (ev) {
                        is ResearchEvent.Step -> update(id) { t ->
                            val s = StepUi(ev.id, ev.label, ev.status, ev.detail, ev.ms)
                            val steps = if (t.steps.any { it.id == ev.id }) t.steps.map { if (it.id == ev.id) s else it } else t.steps + s
                            t.copy(steps = steps)
                        }
                        is ResearchEvent.AnswerDelta -> synchronized(buffer) { buffer.append(ev.text) }
                        is ResearchEvent.SourcesReady -> update(id) { it.copy(sources = ev.evidence) }
                        is ResearchEvent.Verified -> update(id) { it.copy(verification = ev.report) }
                    }
                }
                ticker.cancel()
                update(id) {
                    it.copy(answer = result.text, sources = result.evidence, verification = result.verification, result = result, running = false)
                }
            } catch (e: CancellationException) {
                ticker.cancel()
                update(id) { it.copy(running = false, error = "Stopped", answer = synchronized(buffer) { buffer.toString() }) }
            } catch (e: Throwable) {
                ticker.cancel()
                update(id) { it.copy(running = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Explicit, labelled fallback: the model's own memory, without sources. */
    fun memoryAnswer(turnId: Long) {
        if (busy) return
        val t = _turns.value.firstOrNull { it.id == turnId } ?: return
        job = viewModelScope.launch(Dispatchers.Default) {
            val sb = StringBuilder()
            update(turnId) { it.copy(memoryAnswer = "", running = true) }
            try {
                c.engine().answerFromMemory(t.result?.standaloneQuestion ?: t.question) { d ->
                    sb.append(d)
                    update(turnId) { it.copy(memoryAnswer = sb.toString()) }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                update(turnId) { it.copy(error = e.message) }
            } finally {
                update(turnId) { it.copy(running = false) }
            }
        }
    }

    fun clear() {
        if (!busy) _turns.value = emptyList()
    }
}
