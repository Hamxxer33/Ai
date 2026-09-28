package io.kestrel.research.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kestrel.research.data.DeviceInfo
import io.kestrel.research.ui.theme.Signal

@Composable
fun Dot(color: Color) {
    Spacer(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant, textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 12.sp, color = textColor)
    }
}

/** The always-visible offline status: facts, not claims. */
@Composable
fun OfflineBadge(device: DeviceInfo) {
    val noNet = device.networkImpossible()
    val airplane = device.airplaneMode()
    val color = if (noNet) Signal.ok else Signal.warn
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(6.dp))
            Text(
                (if (noNet) "Offline" else "Network permitted!") + if (airplane) " · airplane" else "",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
            )
        }
    }
}

@Composable
fun SectionCard(title: String? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private val CITE = Regex("\\[(\\d{1,2}(?:\\s*[,–-]\\s*\\d{1,2})*)]")
private val BOLD = Regex("\\*\\*(.+?)\\*\\*")

/** Inline markdown (bold) plus clickable [n] citations. */
fun inline(text: String, citeColor: Color, onCite: (Int) -> Unit): AnnotatedString = buildAnnotatedString {
    var i = 0
    val tokens = (CITE.findAll(text).map { it.range to "c" } + BOLD.findAll(text).map { it.range to "b" })
        .sortedBy { it.first.first }.toList()
    for ((range, kind) in tokens) {
        if (range.first < i) continue
        append(text.substring(i, range.first))
        val raw = text.substring(range)
        if (kind == "b") {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(raw.removePrefix("**").removeSuffix("**")) }
        } else {
            val nums = raw.trim('[', ']').split(Regex("\\s*[,–-]\\s*")).mapNotNull { it.trim().toIntOrNull() }
            nums.forEachIndexed { k, n ->
                withLink(
                    LinkAnnotation.Clickable(
                        "cite$n",
                        TextLinkStyles(SpanStyle(color = citeColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)),
                    ) { onCite(n) },
                ) { append(if (k == 0) "[$n" else ",$n") }
            }
            withStyle(SpanStyle(color = citeColor, fontSize = 12.sp)) { append("]") }
        }
        i = range.last + 1
    }
    if (i < text.length) append(text.substring(i))
}

/** Minimal markdown: headings, bullets, numbered lists, tables (monospace), paragraphs. */
@Composable
fun MarkdownText(text: String, onCite: (Int) -> Unit) {
    val citeColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lines = text.lines()
        var table = mutableListOf<String>()
        fun flushTable(): List<String> { val t = table; table = mutableListOf(); return t }
        val blocks = mutableListOf<Pair<String, String>>()
        for (l in lines) {
            val s = l.trimEnd()
            if (s.trimStart().startsWith("|")) { table.add(s.trim()); continue }
            if (table.isNotEmpty()) blocks += "table" to flushTable().joinToString("\n")
            when {
                s.isBlank() -> Unit
                s.startsWith("#") -> blocks += "h" to s.trimStart('#', ' ')
                Regex("^\\s*[-*•]\\s+").containsMatchIn(s) -> blocks += "li" to s.replaceFirst(Regex("^\\s*[-*•]\\s+"), "")
                Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(s) -> blocks += "ol" to s.trim()
                else -> blocks += "p" to s.trim()
            }
        }
        if (table.isNotEmpty()) blocks += "table" to flushTable().joinToString("\n")
        for ((kind, body) in blocks) {
            when (kind) {
                "h" -> Text(inline(body, citeColor, onCite), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                "li" -> Row { Text("•  "); Text(inline(body, citeColor, onCite), style = MaterialTheme.typography.bodyMedium) }
                "ol" -> Text(inline(body, citeColor, onCite), style = MaterialTheme.typography.bodyMedium)
                "table" -> Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        inline(body.lines().filterNot { Regex("^\\|?\\s*:?-{2,}").containsMatchIn(it) }.joinToString("\n"), citeColor, onCite),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                }
                else -> Text(inline(body, citeColor, onCite), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

fun fmtMs(ms: Long): String = if (ms < 1000) "${ms} ms" else "%.1f s".format(ms / 1000.0)
fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.0f MB".format(b / 1048576.0)
    else -> "%.0f KB".format(b / 1024.0)
}
