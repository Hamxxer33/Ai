package io.kestrel.engine.research

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Exact checking of the arithmetic a model writes ("1865 - 1776 = 89", "10,000 × 1.05^20 ≈ 26,533").
 * Small models retrieve the right numbers and then subtract wrongly; this catches it
 * deterministically.
 */
object Arithmetic {
    data class Check(val expression: String, val claimed: Double, val actual: Double) {
        // "1939–1945 = 6 years": a span written earlier-later is read as a duration
        val ok: Boolean get() = close(actual, claimed) || (actual < 0 && SPAN.matches(expression.trim()) && close(-actual, claimed))
        private fun close(a: Double, b: Double) = abs(a - b) <= max(0.5, abs(a) * 0.005)
    }

    private val SPAN = Regex("\\d[\\d,]*\\s*[-−–]\\s*\\d[\\d,]*")

    private val NUM = "\\d[\\d,]*(?:\\.\\d+)?"
    // an expression made of numbers, operators and parentheses, followed by = or ≈ and a number
    private val EQUATION = Regex(
        "((?:\\(\\s*)*$NUM(?:\\s*[-+*/×÷x^−–]\\s*(?:\\(\\s*)*$NUM(?:\\s*\\))*)+)\\s*(?:=|≈|is about|equals)\\s*\\$?\\s*($NUM)",
    )

    fun checks(text: String): List<Check> = EQUATION.findAll(text).mapNotNull { m ->
        val expr = m.groupValues[1]
        val claimed = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
        val actual = runCatching { evaluate(expr) }.getOrNull() ?: return@mapNotNull null
        if (actual.isNaN() || actual.isInfinite()) null else Check(expr.trim(), claimed, actual)
    }.toList()

    /** Evaluates + - * / ^ with parentheses; "x", "×", "÷", "−", "–" accepted; commas in numbers ignored. */
    fun evaluate(expression: String): Double {
        val s = expression.replace(",", "").replace('×', '*').replace('x', '*').replace('÷', '/')
            .replace('−', '-').replace('–', '-').replace(" ", "")
        var pos = 0
        fun peek() = if (pos < s.length) s[pos] else '\u0000'
        lateinit var expr: () -> Double
        fun primary(): Double {
            if (peek() == '(') { pos++; val v = expr(); if (peek() == ')') pos++; return v }
            if (peek() == '-') { pos++; return -primary() }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            require(pos > start) { "number expected at $start in $s" }
            return s.substring(start, pos).toDouble()
        }
        fun power(): Double {
            val b = primary()
            return if (peek() == '^') { pos++; b.pow(power()) } else b
        }
        fun term(): Double {
            var v = power()
            while (peek() == '*' || peek() == '/') {
                val op = s[pos++]
                val r = power()
                v = if (op == '*') v * r else v / r
            }
            return v
        }
        expr = {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[pos++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            v
        }
        val v = expr()
        require(pos == s.length) { "trailing input in $s" }
        return v
    }

    fun format(v: Double): String =
        if (abs(v - Math.rint(v)) < 1e-9) "%,d".format(Math.rint(v).toLong()) else "%,.2f".format(v)
}
