package io.kestrel.engine.research

import io.kestrel.engine.retrieval.QuestionType

/**
 * Prompts. System prompts are constant per model so llama.cpp can reuse their KV cache across
 * questions; everything that varies goes into the user message.
 */
object Prompts {
    val ANSWER_SYSTEM = """
You are Kestrel, an offline research assistant. You answer using ONLY the numbered sources in the user's message.
Rules:
1. Put the source numbers after every factual sentence, like [1] or [2][3].
2. Copy names, dates and numbers exactly as the sources give them.
3. If the sources do not contain what is needed, say what is missing. Never fill gaps from memory and never invent sources.
4. If sources disagree, say so and cite each side.
5. Start with a direct answer, then the supporting details. No preamble.
""".trim()

    fun answerUser(question: String, sources: String, notes: List<String>, type: QuestionType, maxWords: Int): String = buildString {
        append("Sources:\n").append(sources).append("\n\n")
        if (notes.isNotEmpty()) {
            append("Findings from earlier research steps:\n")
            notes.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("Question: ").append(question).append("\n\n")
        append("Instructions: ").append(formatHint(type)).append(" Use at most ").append(maxWords).append(" words. Cite sources as [n].")
    }

    private fun formatHint(type: QuestionType): String = when (type) {
        QuestionType.LOOKUP -> "Answer in one to three sentences."
        QuestionType.EXPLANATION -> "Explain the mechanism or reasons step by step, in plain language."
        QuestionType.COMPARISON -> "Compare point by point (a short list or a compact table), then give a one-sentence conclusion."
        QuestionType.MULTIHOP -> "Connect the findings step by step, showing how each fact leads to the next, then state the final answer."
        QuestionType.SYNTHESIS -> "Synthesise the sources into a structured overview with a few short sections or bullets."
        QuestionType.NUMERIC -> "Quote the numbers you use from the sources, show the calculation line by line, then give the result."
        QuestionType.AMBIGUOUS -> "If the question could mean several things, briefly cover the main meanings found in the sources."
    }

    // ------------------------------------------------------------------ planner

    val PLAN_SYSTEM = """
You plan research for questions answered from an offline encyclopedia. Reply with JSON only.
Fields:
- "type": lookup, explanation, comparison, multihop, synthesis, numeric or ambiguous.
- "subquestions": 1-4 simple questions, each answerable from one encyclopedia article, in the order they must be answered. Write #1, #2 for the answer of an earlier subquestion.
- "queries": 2-5 short searches (article titles or key terms).

Question: Who was the US president when the Eiffel Tower was completed?
{"type":"multihop","subquestions":["When was the Eiffel Tower completed?","Who was President of the United States in #1?"],"queries":["Eiffel Tower","List of presidents of the United States"]}

Question: How do nuclear fission and fusion differ as energy sources?
{"type":"comparison","subquestions":["How does nuclear fission produce energy?","How does nuclear fusion produce energy?"],"queries":["Nuclear fission","Nuclear fusion","Fusion power","Nuclear power"]}

Question: Why did the Bronze Age collapse happen?
{"type":"synthesis","subquestions":["What was the Late Bronze Age collapse?","What causes have been proposed for the Late Bronze Age collapse?"],"queries":["Late Bronze Age collapse","Sea Peoples"]}
""".trim()

    fun planUser(question: String) = "Question: $question"

    /** GBNF for the plan JSON; keeps small models on-format. */
    val PLAN_GRAMMAR = """
root ::= "{" ws "\"type\":" ws type "," ws "\"subquestions\":" ws strs "," ws "\"queries\":" ws strs ws "}"
type ::= "\"lookup\"" | "\"explanation\"" | "\"comparison\"" | "\"multihop\"" | "\"synthesis\"" | "\"numeric\"" | "\"ambiguous\""
strs ::= "[" ws ( str ( ws "," ws str ){0,4} )? ws "]"
str ::= "\"" chr{1,160} "\""
chr ::= [^"\\\n] | "\\" ["\\/nt]
ws ::= [ \n]{0,2}
""".trim()

    // ------------------------------------------------------------------ hop extraction

    val HOP_SYSTEM = """
You answer one research sub-question using only the numbered sources.
Reply with the short answer (a name, date, number or one sentence) followed by the source numbers, like: Christopher Nolan [2]
If the sources do not answer it, reply exactly: NOT FOUND
""".trim()

    fun hopUser(question: String, sources: String) = "Sources:\n$sources\n\nQuestion: $question"

    // ------------------------------------------------------------------ follow-up rewrite

    val REWRITE_SYSTEM = """
Rewrite the user's follow-up question as one standalone question, using the previous exchange to resolve words like "he", "it", "there" or "what about". Reply with the rewritten question only.
""".trim()

    fun rewriteUser(prevQ: String, prevA: String, followUp: String) =
        "Previous question: $prevQ\nPrevious answer: ${prevA.take(600)}\n\nFollow-up: $followUp"

    // ------------------------------------------------------------------ verification

    val VERIFY_SYSTEM = """
You check claims against sources. For each numbered claim, output S if the sources state or directly imply it, or U if they do not. Output one letter per claim, in order, with nothing else.
""".trim()

    fun verifyUser(sources: String, claims: List<String>) = buildString {
        append("Sources:\n").append(sources).append("\n\nClaims:\n")
        claims.forEachIndexed { i, c -> append(i + 1).append(". ").append(c).append('\n') }
        append("\nVerdicts:")
    }

    fun verifyGrammar(n: Int) = "root ::= " + List(n) { "v" }.joinToString(" ") + "\nv ::= \"S\" | \"U\""

    // ------------------------------------------------------------------ memory-only (explicitly requested)

    val MEMORY_SYSTEM = """
You are an offline assistant answering from your own training, without sources. Be brief and say when you are unsure. Do not invent specific numbers, dates or quotations you are not sure of.
""".trim()
}
