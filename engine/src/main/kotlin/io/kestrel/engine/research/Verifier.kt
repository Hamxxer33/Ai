package io.kestrel.engine.research

import io.kestrel.engine.retrieval.Evidence
import io.kestrel.engine.text.Sentences
import io.kestrel.engine.text.Text

enum class Verdict { SUPPORTED, WEAK, UNSUPPORTED, UNCITED, COMPUTED, NOT_A_CLAIM }

data class ClaimCheck(
    val sentence: String,
    val citations: List<Int>,
    /** Share of the claim's content words found in its cited sources (0..1). */
    val support: Double,
    /** Every number in the claim appears in the cited sources (or the question). */
    val numbersOk: Boolean,
    val invalidCitations: List<Int>,
    val verdict: Verdict,
    /** Result of the model check, when one ran: true = supported. */
    val llmSupported: Boolean? = null,
)

data class VerificationReport(val claims: List<ClaimCheck>) {
    val checkable get() = claims.filter { it.verdict != Verdict.NOT_A_CLAIM && it.verdict != Verdict.COMPUTED }
    val supported get() = checkable.count { it.verdict == Verdict.SUPPORTED }
    val unsupported get() = checkable.count { it.verdict == Verdict.UNSUPPORTED || it.verdict == Verdict.UNCITED }
    val supportRate: Double get() = if (checkable.isEmpty()) 1.0 else supported.toDouble() / checkable.size
    val citationErrors get() = claims.sumOf { it.invalidCitations.size }
    fun summary() = "${supported}/${checkable.size} claims supported" +
        (if (unsupported > 0) ", $unsupported unsupported" else "") +
        (if (citationErrors > 0) ", $citationErrors invalid citations" else "")
}

/**
 * Claim-level grounding check. Deterministic first (content-word overlap with the cited
 * sentences, exact number matching); a model check is run only for the claims that fail it.
 */
object Verifier {
    private val CITE = Regex("\\[(\\d{1,2}(?:\\s*[,–-]\\s*\\d{1,2})*)]")
    private val CALC = Regex("[=×÷*/+]|\\b(times|divided|multiplied|minus|plus|approximately|about|roughly|total|sum|difference|ratio|per cent|percent)\\b", RegexOption.IGNORE_CASE)
    private val HEADING = Regex("^(#+\\s|\\*\\*[^*]+\\*\\*:?$|[-|: ]+$)")
    // statements about the sources themselves ("the sources do not say ...") are not factual claims
    private val META = Regex("\\b(the )?(provided |available |offline |cited )?(sources?|text|passages?|documents?|library)\\b.{0,60}\\b(do(es)? not|don't|doesn't|not|no|lack|omit|fail|missing|mention)|" +
        "\\b(not (stated|mentioned|specified|given|provided)|no information|cannot (be )?determined|is missing)\\b", RegexOption.IGNORE_CASE)

    fun citations(s: String): List<Int> = CITE.findAll(s).flatMap { m ->
        m.groupValues[1].split(Regex("\\s*,\\s*")).flatMap { part ->
            val r = part.split(Regex("\\s*[–-]\\s*"))
            if (r.size == 2) (r[0].toInt()..r[1].toInt()).toList() else listOf(r[0].trim().toInt())
        }
    }.toList()

    /** Splits an answer into claim units: sentences, list items and table rows. */
    fun claims(answer: String): List<String> = answer.lines()
        .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").trim() }
        .filter { it.isNotEmpty() }
        .flatMap { line -> if (line.startsWith("|")) listOf(line) else Sentences.split(line) }
        .filter { it.isNotBlank() }

    fun check(answer: String, evidence: List<Evidence>, question: String, numericQuestion: Boolean): VerificationReport {
        val byN = evidence.associateBy { it.n }
        val qNumbers = Text.numbers(question)
        val allEvidenceText = evidence.joinToString(" ") { it.title + " " + it.text }
        val out = claims(answer).map { raw ->
            val cites = citations(raw)
            val body = CITE.replace(raw, " ").replace("**", "").trim()
            val stems = Text.stems(body)
            val invalid = cites.filter { it !in byN }
            if (stems.size < 2 || HEADING.containsMatchIn(raw) || META.containsMatchIn(body)) {
                return@map ClaimCheck(raw, cites, 0.0, true, invalid, Verdict.NOT_A_CLAIM)
            }
            val cited = cites.mapNotNull { byN[it] }
            val refText = if (cited.isEmpty()) allEvidenceText else cited.joinToString(" ") { it.title + " " + it.text }
            val refStems = Text.stems(refText).toSet()
            val support = stems.count { it in refStems }.toDouble() / stems.size
            val nums = Text.numbers(body) - qNumbers
            val refNums = Text.numbers(refText)
            val numbersOk = nums.all { n -> n in refNums || refNums.any { it.startsWith("$n.") || n.startsWith("$it.") } }
            val verdict = when {
                numericQuestion && !numbersOk && CALC.containsMatchIn(body) -> Verdict.COMPUTED
                cited.isEmpty() && cites.isEmpty() -> if (support >= 0.8 && numbersOk) Verdict.WEAK else Verdict.UNCITED
                !numbersOk -> Verdict.UNSUPPORTED
                support >= 0.6 -> Verdict.SUPPORTED
                support >= 0.35 -> Verdict.WEAK
                else -> Verdict.UNSUPPORTED
            }
            ClaimCheck(raw, cites, support, numbersOk, invalid, verdict)
        }
        return VerificationReport(out)
    }

    /** Applies model verdicts (in order) to the claims that were sent for checking. */
    fun applyModelVerdicts(report: VerificationReport, checked: List<Int>, verdicts: String): VerificationReport {
        val claims = report.claims.toMutableList()
        checked.forEachIndexed { k, idx ->
            val v = verdicts.getOrNull(k) ?: return@forEachIndexed
            val c = claims[idx]
            val ok = v == 'S'
            val newVerdict = when {
                ok && c.numbersOk && c.verdict != Verdict.UNCITED -> Verdict.SUPPORTED
                ok -> c.verdict
                else -> Verdict.UNSUPPORTED
            }
            claims[idx] = c.copy(verdict = newVerdict, llmSupported = ok)
        }
        return VerificationReport(claims)
    }
}
