package io.kestrel.engine

import io.kestrel.engine.retrieval.FtsQuery
import io.kestrel.engine.retrieval.QueryAnalyzer
import io.kestrel.engine.retrieval.QuestionType
import io.kestrel.engine.text.Porter
import io.kestrel.engine.text.Sentences
import io.kestrel.engine.text.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTest {
    @Test
    fun porterMatchesReferenceStems() {
        // Reference outputs of Martin Porter's algorithm (the one FTS5's porter tokenizer implements).
        val cases = mapOf(
            "caresses" to "caress", "ponies" to "poni", "cats" to "cat", "feed" to "feed", "agreed" to "agre",
            "plastered" to "plaster", "motoring" to "motor", "sing" to "sing", "conflated" to "conflat",
            "hopping" to "hop", "falling" to "fall", "filing" to "file", "happy" to "happi",
            "relational" to "relat", "conditional" to "condit", "rational" to "ration", "digitizer" to "digit",
            "generalization" to "gener", "electricity" to "electr", "adjustment" to "adjust",
            "generate" to "gener", "running" to "run", "relativity" to "rel", "universities" to "univers",
        )
        for ((w, s) in cases) assertEquals(s, Porter.stem(w), "stem($w)")
    }

    @Test
    fun sentencesHandleAbbreviationsAndInitials() {
        val s = Sentences.split("J. R. R. Tolkien wrote it in the U.S. and the U.K. He was born in 1892. It sold 150 million copies! Dr. Smith agreed.")
        assertEquals(4, s.size, s.toString())
        assertTrue(s[0].startsWith("J. R. R. Tolkien"))
    }

    @Test
    fun numbersAreNormalised() {
        assertEquals(setOf("21196", "1889", "3.5"), Text.numbers("21,196 km in 1889 and 3.50 m"))
    }

    @Test
    fun ftsQueriesAreAlwaysQuoted() {
        assertEquals("\"not\" OR \"near\" OR \"and\"", FtsQuery.any(listOf("not", "near", "and")))
        assertEquals("\"marie curie\"", FtsQuery.phrase("Marie Curie's"))
    }

    @Test
    fun classifierRecognisesQuestionTypes() {
        assertEquals(QuestionType.COMPARISON, QueryAnalyzer.analyze("Compare nuclear fission and nuclear fusion").type)
        assertEquals(QuestionType.EXPLANATION, QueryAnalyzer.analyze("Why does ice float on water?").type)
        assertEquals(QuestionType.NUMERIC, QueryAnalyzer.analyze("How old was Albert Einstein when he published special relativity?").type)
        assertEquals(QuestionType.NUMERIC, QueryAnalyzer.analyze("How many years passed between 1776 and 1865?").type)
        assertEquals(QuestionType.LOOKUP, QueryAnalyzer.analyze("In what year did the Chernobyl disaster occur?").type)
        assertEquals(QuestionType.SYNTHESIS, QueryAnalyzer.analyze("Give an overview of the history of the Internet").type)
        assertEquals(QuestionType.MULTIHOP, QueryAnalyzer.analyze("Who directed the film that won Best Picture in 1998?").type)
        assertEquals(QuestionType.MULTIHOP, QueryAnalyzer.analyze("In which country was the author of The Little Prince born?").type)
        assertEquals(QuestionType.MULTIHOP, QueryAnalyzer.analyze("What is the capital of the country where Angkor Wat is located?").type)
        assertEquals(QuestionType.LOOKUP, QueryAnalyzer.analyze("What is the capital of Australia?").type)
        val c = QueryAnalyzer.analyze("What is the difference between a virus and a bacterium?")
        assertEquals(QuestionType.COMPARISON, c.type)
        assertTrue(c.comparands.size >= 2, c.comparands.toString())
    }

    @Test
    fun entitiesAreCapitalisedSpans() {
        val e = QueryAnalyzer.analyze("Who was the President of the United States when the Eiffel Tower was completed?").entities
        assertTrue(e.any { it.contains("Eiffel Tower") }, e.toString())
        assertTrue(e.any { it.contains("United States") }, e.toString())
    }
}
