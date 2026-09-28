package io.kestrel.engine

import io.kestrel.engine.research.Arithmetic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArithmeticTest {
    @Test
    fun evaluatesExpressions() {
        assertEquals(89.0, Arithmetic.evaluate("1865 - 1776"))
        assertEquals(26532.98, Arithmetic.evaluate("10,000 × 1.05^20"), 0.01)
        assertEquals(14.0, Arithmetic.evaluate("2 * (3 + 4)"))
    }

    @Test
    fun findsWrongResults() {
        val c = Arithmetic.checks("The war lasted 1453 − 1337 = 116 years, and 1865 - 1776 = 88 years.")
        assertEquals(2, c.size)
        assertTrue(c[0].ok)
        assertFalse(c[1].ok)
        assertEquals(89.0, c[1].actual)
    }

    @Test
    fun ignoresPlainDates() {
        assertTrue(Arithmetic.checks("He was born in 1879 and published in 1905.").isEmpty())
        assertTrue(Arithmetic.checks("From 1939-1945 the war raged.").isEmpty())
        assertTrue(Arithmetic.checks("The war lasted 1939–1945 = 6 years.").all { it.ok })
    }
}
