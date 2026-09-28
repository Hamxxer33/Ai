package io.kestrel.engine

import io.kestrel.engine.research.Verdict
import io.kestrel.engine.research.Verifier
import io.kestrel.engine.retrieval.Evidence
import io.kestrel.engine.retrieval.Retrieved
import io.kestrel.engine.store.Chunk
import kotlin.test.Test
import kotlin.test.assertEquals

class VerifierTest {
    private fun ev(n: Int, title: String, text: String) =
        Evidence(n, Retrieved(Chunk(n.toLong(), n.toLong(), 0, title, "", text, "t"), 1.0, emptyMap()), listOf(text), 1.0)

    private val evidence = listOf(
        ev(1, "Eiffel Tower", "The Eiffel Tower was completed in March 1889 for the World's Fair in Paris."),
        ev(2, "Benjamin Harrison", "Benjamin Harrison served as the 23rd president of the United States from 1889 to 1893."),
    )

    @Test
    fun citationsParseRangesAndLists() {
        assertEquals(listOf(1, 2, 3, 5), Verifier.citations("x [1-3] y [5]"))
        assertEquals(listOf(2, 4), Verifier.citations("x [2, 4]"))
    }

    @Test
    fun supportedClaimsPassAndWrongNumbersFail() {
        val r = Verifier.check(
            "The Eiffel Tower was completed in 1889 [1]. Benjamin Harrison was president from 1889 to 1893 [2]. " +
                "The tower was completed in 1887 [1]. It was painted by Picasso in Madrid [2].",
            evidence, "Who was president when the Eiffel Tower was completed?", false,
        )
        val v = r.claims.map { it.verdict }
        assertEquals(Verdict.SUPPORTED, v[0])
        assertEquals(Verdict.SUPPORTED, v[1])
        assertEquals(Verdict.UNSUPPORTED, v[2]) // wrong year
        assertEquals(Verdict.UNSUPPORTED, v[3]) // unsupported content
    }

    @Test
    fun invalidCitationsAreCounted() {
        val r = Verifier.check("The tower was completed in 1889 [7].", evidence, "q", false)
        assertEquals(1, r.citationErrors)
    }
}
