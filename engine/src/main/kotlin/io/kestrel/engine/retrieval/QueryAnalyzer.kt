package io.kestrel.engine.retrieval

import io.kestrel.engine.text.Porter
import io.kestrel.engine.text.Stopwords
import io.kestrel.engine.text.Text

enum class QuestionType { LOOKUP, EXPLANATION, COMPARISON, MULTIHOP, SYNTHESIS, NUMERIC, AMBIGUOUS }

data class QueryFeatures(
    val text: String,
    val type: QuestionType,
    /** Confidence of the rule-based type (0..1); low values ask the planner model to decide. */
    val confidence: Double,
    val words: List<String>,
    val stems: List<String>,
    /** Named spans (capitalised sequences, quoted strings). */
    val entities: List<String>,
    /** For comparisons: the things being compared, when they could be read off the question. */
    val comparands: List<String>,
    val numbers: Set<String>,
    val wantsNumber: Boolean,
    val wantsList: Boolean,
)

/** Rule-based question analysis. Cheap and deterministic; the planner model refines it when needed. */
object QueryAnalyzer {
    private val COMPARE = Regex(
        "\\b(compare|comparison|compared|versus|vs\\.?|difference between|differences between|differ|contrast|" +
            "similarities|similar to|better than|worse than|pros and cons|advantages and disadvantages)\\b|" +
            "\\bwhich (is|was|are|were|has|had) (older|larger|bigger|smaller|longer|shorter|taller|higher|lower|" +
            "faster|slower|heavier|lighter|more|less|earlier|later|closer|farther|further|deeper|hotter|colder|denser)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val EXPLAIN = Regex(
        "^(why|how (does|do|did|is|are|can|could|would)|explain|what (causes|caused|makes|made|happens|happened)|" +
            "what is the (mechanism|reason|cause|role|purpose|function|significance))\\b|\\b(mechanism|work\\?|works\\?)",
        RegexOption.IGNORE_CASE,
    )
    // questions that need arithmetic, not just a number looked up
    private val NUMERIC = Regex(
        "\\b(how old was|how old were|how many (years|days|months|times|hours|minutes|km|kilometres|kilometers|miles) " +
            "(passed|elapsed|between|separate|did|does|do|would|will|after|before|longer|more|older)|how long did|how long would|" +
            "how long will|how much (older|longer|larger|bigger|more|less|heavier|faster)|calculate|compute|" +
            "times (larger|bigger|more|smaller|heavier|longer|faster)|how many times|ratio|percentage of|what percent|" +
            "if you (invest|travel|drive|walk|save)|compounded|per (year|hour|second) for|roughly how (far|many|much)|" +
            "(difference|gap) (in|between) (years|age|height|size))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val QUANTITY = Regex(
        "\\b(how (many|much|old|long|far|tall|big|high|deep|fast|heavy|large)|what (year|date|percentage|proportion)|" +
            "when (did|was|were|is)|in what year|population|area|distance|height|length|melting point|boiling point)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SYNTH = Regex(
        "^(describe|outline|trace|summari[sz]e|give an overview|give a timeline|discuss)\\b|" +
            "\\b(overview|summari[sz]e|history of|evolution of|impact of|impacts of|effects? of|consequences of|" +
            "timeline|phases of|stages of|main events|major events|career of|life of|life and work|contributions|" +
            "role of|influence of|significance of|causes and|main (causes|reasons|factors|arguments|theories|types)|" +
            "what are the (main|key|major)|state of|debate|arguments for|arguments against|survey|trends?)\\b",
        RegexOption.IGNORE_CASE,
    )
    // relation chains: "the X of the Y that/who ...", "the country where the inventor of Z was born"
    private val MULTIHOP = Regex(
        "\\b(the \\w+ (of|in|by|at|for) the \\w+[^?]{0,60}\\b(that|which|who|whose|where|when)\\b)|" +
            "\\b(born in the same|same (year|city|country|place) as|who (directed|wrote|founded|invented|discovered|" +
            "composed|painted|designed|built|led) the [^?]{3,60}(that|which|who|whose|when|where)|" +
            "(was|were) (born|founded|built|released) (in|during) the (year|decade|reign|presidency) (of|when|that))\\b",
        RegexOption.IGNORE_CASE,
    )
    // "the author of The Little Prince", "the capital of the country where ...", "the element named after ..."
    private val RELATION_CHAIN = Regex(
        "\\b(the |a |an )?(author|writer|director|producer|founder|inventor|creator|composer|painter|architect|designer|" +
            "discoverer|leader|president|king|queen|monarch|emperor|ruler|wife|husband|spouse|father|mother|son|daughter|" +
            "capital|currency|language|population|birthplace|home ?town|headquarters|successor|predecessor|owner|" +
            "manufacturer|developer|publisher|singer|star|coach|captain|mayor|governor|builder)s? of (the |a |an )?" +
            "([A-Z0-9\"“]|country|city|state|film|movie|book|novel|band|company|element|planet|song|album|team|person|" +
            "scientist|writer|author|composer|empire|dynasty|war|battle|treaty)",
    )
    private val PREDICATE = Regex(
        "\\b(born|die|died|located|founded|built|invented|discovered|written|wrote|directed|married|educated|stud(y|ied)|" +
            "attend(ed)?|lived?|flows?|named|w[io]n|completed|released|elected|buried|situated|use[sd]?|speak|spoken|study)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val NAMED_AFTER = Regex("\\b(named after|named for|where (the|a) .+ (is|was) (located|born|built|founded))\\b", RegexOption.IGNORE_CASE)

    private val LOOKUP = Regex(
        "^(who (is|was|were|are)|what (is|was|are|were) (the )?\\w+( \\w+)?\\??$|when (did|was|were|is)|where (is|was|are|were|did)|" +
            "which (year|country|city|person)|what year|define|definition of)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val LIST = Regex("\\b(list|name (all|the|some)|what are (the|some)|which (countries|people|elements|cities)|examples of)\\b", RegexOption.IGNORE_CASE)
    private val QUOTED = Regex("[\"“]([^\"”]{2,80})[\"”]")
    private val SPLIT_COMPARANDS = Regex("\\s+(?:vs\\.?|versus|and|or|with|to)\\s+", RegexOption.IGNORE_CASE)

    fun analyze(question: String): QueryFeatures {
        val q = question.trim()
        val words = Text.contentWords(q)
        val stems = words.map { Porter.stem(it) }
        val quoted = QUOTED.findAll(q).map { it.groupValues[1] }.toList()
        val caps = Text.capitalisedSpans(q).filterNot { Text.fold(it) in Stopwords.QUESTION }
        val entities = (quoted + caps).distinctBy { Text.fold(it) }
        val numbers = Text.numbers(q)

        val scores = linkedMapOf(
            QuestionType.COMPARISON to (if (COMPARE.containsMatchIn(q)) 0.9 else 0.0),
            QuestionType.MULTIHOP to (if (MULTIHOP.containsMatchIn(q)) 0.75 else 0.0),
            QuestionType.SYNTHESIS to (if (SYNTH.containsMatchIn(q)) 0.7 else 0.0),
            QuestionType.EXPLANATION to (if (EXPLAIN.containsMatchIn(q)) 0.75 else 0.0),
            QuestionType.NUMERIC to (if (NUMERIC.containsMatchIn(q)) 0.65 else 0.0),
            QuestionType.LOOKUP to (if (LOOKUP.containsMatchIn(q)) 0.6 else 0.0),
        )
        // multiple entities joined by "and"/"vs" without a comparison verb are often comparisons
        if (scores[QuestionType.COMPARISON] == 0.0 && entities.size >= 2 && Regex("\\b(and|or)\\b").containsMatchIn(q) &&
            Regex("^(which|who)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)
        ) scores[QuestionType.COMPARISON] = 0.6
        // a property of an entity that is itself described by a relation ("born" + "the author of X")
        val chain = RELATION_CHAIN.find(q)
        val outerPredicate = chain != null && PREDICATE.containsMatchIn(q.removeRange(chain.range))
        val genericWithClause = chain != null && Regex("\\b(country|city|state|film|book|company|person|scientist|place)\\s+(where|that|which|who|whose|in which)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)
        if (outerPredicate || genericWithClause || NAMED_AFTER.containsMatchIn(q)) {
            scores[QuestionType.MULTIHOP] = maxOf(scores[QuestionType.MULTIHOP]!!, 0.8)
        }
        // long questions with several clauses lean multi-hop
        val clauses = Regex("\\b(that|which|who|whose|where|when)\\b", RegexOption.IGNORE_CASE).findAll(q).count()
        if (clauses >= 2 && scores[QuestionType.MULTIHOP]!! < 0.6) scores[QuestionType.MULTIHOP] = 0.6

        var (type, conf) = scores.maxByOrNull { it.value }!!.toPair()
        val isQuestion = q.endsWith("?") || Regex("^(who|what|when|where|which|why|how|is|are|was|were|do|does|did|can|name|list)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)
        if (conf == 0.0) {
            val bare = words.size <= 2 && !isQuestion
            type = if (bare) QuestionType.AMBIGUOUS else QuestionType.LOOKUP
            conf = if (bare) 0.5 else 0.4
        }
        // bare topic ("Mercury", "Georgia") is ambiguous rather than a lookup
        if (words.size <= 2 && !q.contains('?') && entities.size <= 1 && type == QuestionType.LOOKUP) {
            type = QuestionType.AMBIGUOUS
            conf = 0.5
        }

        val comparands = if (type == QuestionType.COMPARISON) extractComparands(q, entities) else emptyList()
        return QueryFeatures(
            text = q, type = type, confidence = conf, words = words, stems = stems, entities = entities,
            comparands = comparands, numbers = numbers,
            wantsNumber = scores[QuestionType.NUMERIC]!! > 0 || QUANTITY.containsMatchIn(q),
            wantsList = LIST.containsMatchIn(q),
        )
    }

    private fun extractComparands(q: String, entities: List<String>): List<String> {
        if (entities.size >= 2) return entities.take(4)
        val m = Regex("(?:between|compare|comparing)\\s+(.+?)(?:\\?|$|\\s+(?:in terms of|regarding|for|on)\\s)", RegexOption.IGNORE_CASE).find(q)
            ?: Regex("^(?:is|are|was|were)?\\s*(.+?)\\s+(?:vs\\.?|versus)\\s+(.+?)(?:\\?|$)", RegexOption.IGNORE_CASE).find(q)
        val span = when {
            m == null -> return entities
            m.groupValues.size > 2 && m.groupValues[2].isNotEmpty() -> "${m.groupValues[1]} vs ${m.groupValues[2]}"
            else -> m.groupValues[1]
        }
        return span.split(SPLIT_COMPARANDS, 0)
            .map { it.trim().trimEnd('?', '.').removePrefix("the ").removePrefix("a ").trim() }
            .filter { it.isNotEmpty() && Text.contentWords(it).isNotEmpty() }
            .take(4)
    }
}

/** Builds safe FTS5 MATCH expressions. Every term is quoted, so user text can never be FTS syntax. */
object FtsQuery {
    private fun quote(t: String) = "\"" + t.replace("\"", "") + "\""

    fun phrase(text: String): String? {
        val w = Text.words(text).filter { it.isNotEmpty() }
        return if (w.isEmpty()) null else quote(w.joinToString(" "))
    }

    /** OR of terms. */
    fun any(terms: Collection<String>): String = terms.distinct().joinToString(" OR ") { quote(it) }

    /** AND of terms. */
    fun all(terms: Collection<String>): String = terms.distinct().joinToString(" AND ") { quote(it) }
}
