package io.kestrel.engine.text

import java.text.Normalizer

/** Text utilities shared by retrieval, evidence selection and verification. */
object Text {
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NUMBER = Regex("(?<![\\p{L}\\d])(\\d{1,3}(?:[,  ]\\d{3})+|\\d+)(?:\\.(\\d+))?")

    /** Lowercase, strip diacritics (matches FTS5 `unicode61 remove_diacritics 2` closely enough). */
    fun fold(s: String): String =
        DIACRITICS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

    /** Word tokens, folded. Possessive/contracted suffixes are dropped ("einstein's" -> "einstein"). */
    fun words(s: String): List<String> = WORD.findAll(fold(s)).map { m ->
        val w = m.value
        val q = w.indexOfAny(charArrayOf('\'', '’'))
        if (q > 0) w.substring(0, q) else w
    }.filter { it.isNotEmpty() }.toList()

    /** Content words: no stopwords, no single letters (numbers kept). */
    fun contentWords(s: String): List<String> =
        words(s).filter { (it.length > 1 || it[0].isDigit()) && it !in Stopwords.ALL }

    /** Porter stems of content words. */
    fun stems(s: String): List<String> = contentWords(s).map { Porter.stem(it) }

    /** Normal form for title/alias lookup: folded words joined by single spaces. */
    fun normTitle(s: String): String = words(s.replace('_', ' ')).joinToString(" ")

    /** Numbers mentioned in the text, normalised ("1,234.50" -> "1234.5"; years stay as-is). */
    fun numbers(s: String): Set<String> = NUMBER.findAll(s).map { m ->
        val int = m.groupValues[1].replace(",", "").replace(" ", "").replace(" ", "").trimStart('0').ifEmpty { "0" }
        val frac = m.groupValues[2].trimEnd('0')
        if (frac.isEmpty()) int else "$int.$frac"
    }.toSet()

    /** Rough token estimate for budgeting (English prose ~ 4 chars/token for modern tokenizers). */
    fun estimateTokens(s: String): Int = (s.length + 3) / 4

    /** Capitalised word sequences that look like names ("Marie Curie", "Treaty of Versailles"). */
    fun capitalisedSpans(s: String): List<String> {
        val out = mutableListOf<String>()
        val toks = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’.-]*").findAll(s).toList()
        var i = 0
        while (i < toks.size) {
            val t = toks[i].value
            val first = i == 0 || s.substring(0, toks[i].range.first).trimEnd().endsWith(".") ||
                s.substring(0, toks[i].range.first).trimEnd().endsWith("?")
            if (t[0].isUpperCase() && !(first && fold(t) in Stopwords.ALL)) {
                val start = i
                var end = i
                var j = i + 1
                while (j < toks.size) {
                    val w = toks[j].value
                    val gap = s.substring(toks[j - 1].range.last + 1, toks[j].range.first)
                    if (gap.any { it == ',' || it == '?' || it == ';' || it == ':' || it == '(' || it == ')' }) break
                    if (w[0].isUpperCase() || w[0].isDigit()) { end = j; j++ }
                    else if (fold(w) in CONNECTORS && j + 1 < toks.size && toks[j + 1].value[0].isUpperCase()) { j++ }
                    else break
                }
                out += s.substring(toks[start].range.first, toks[end].range.last + 1).trimEnd('.', '\'', '’')
                i = end + 1
            } else i++
        }
        return out.filter { it.length > 1 }
    }

    private val CONNECTORS = setOf("of", "the", "de", "da", "von", "van", "der", "and", "for", "on", "in", "del", "la", "le", "du")
}

/** Sentence splitter tuned for encyclopedic English (abbreviations, initials, decimals). */
object Sentences {
    private val ABBREV = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "ft", "vs", "etc", "e.g", "i.e", "cf", "al",
        "no", "vol", "pp", "p", "fig", "approx", "ca", "c", "inc", "ltd", "co", "corp", "gen", "col", "lt",
        "sgt", "capt", "gov", "sen", "rep", "rev", "u.s", "u.k", "u.n", "jan", "feb", "mar", "apr", "jun",
        "jul", "aug", "sep", "sept", "oct", "nov", "dec", "est", "b", "d", "r", "op", "ed", "eds",
    )

    /** Abbreviations that often end a sentence ("... in the U.S. He was ..."). */
    private val TERMINAL_ABBREV = setOf("u.s", "u.k", "u.n", "etc", "inc", "ltd", "co", "corp", "jr", "sr")
    private val STARTERS = setOf("he", "she", "it", "they", "the", "this", "that", "these", "in", "a", "an", "we", "his", "her", "its", "their", "there", "after", "during", "however")

    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (ch == '\n') {
                if (i > start) out += text.substring(start, i)
                start = i + 1
            } else if (ch == '.' || ch == '!' || ch == '?') {
                var j = i + 1
                while (j < n && (text[j] == '"' || text[j] == '\'' || text[j] == ')' || text[j] == '’' || text[j] == '”')) j++
                val atBoundary = j >= n || (text[j] == ' ' && j + 1 < n && (text[j + 1].isUpperCase() || text[j + 1] == '"' || text[j + 1] == '(' || text[j + 1].isDigit()))
                if (atBoundary && ch == '.' && (!endsWithAbbrev(text, start, i) || terminalAbbrevBeforeStarter(text, start, i, j))) {
                    out += text.substring(start, j)
                    start = j
                } else if (atBoundary && ch != '.') {
                    out += text.substring(start, j)
                    start = j
                }
            }
            i++
        }
        if (start < n) out += text.substring(start)
        return out.map { it.trim() }.filter { it.length > 1 }
    }

    private fun terminalAbbrevBeforeStarter(text: String, start: Int, dot: Int, j: Int): Boolean {
        var k = dot - 1
        while (k >= start && (text[k].isLetter() || text[k] == '.')) k--
        val word = text.substring(k + 1, dot).lowercase()
        if (word !in TERMINAL_ABBREV) return false
        val next = text.substring(j).trimStart().takeWhile { it.isLetter() }.lowercase()
        return next in STARTERS
    }

    private fun endsWithAbbrev(text: String, start: Int, dot: Int): Boolean {
        var k = dot - 1
        while (k >= start && (text[k].isLetter() || text[k] == '.')) k--
        val word = text.substring(k + 1, dot).lowercase()
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isLetter()) return true // initials: "J. R. R. Tolkien"
        return word in ABBREV
    }
}
