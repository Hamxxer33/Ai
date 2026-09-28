package io.kestrel.engine.text

object Stopwords {
    /** English function words plus question scaffolding ("explain", "tell me about"). */
    val ALL: Set<String> = """
        a about above after again against all almost also although always am among an and another any are aren
        around as at be became because become been before being below between both but by can cannot could
        couldn did didn do does doesn doing don down during each either else enough especially etc even ever
        every few for from further had hadn has hasn have haven having he her here hers herself him himself his
        how however i if in into is isn it its itself just least less like ll made make many may me might more
        most mostly much must my myself neither no nor not now of off often on once one only onto or other
        others otherwise our ours ourselves out over own per perhaps quite rather re really s same shall she
        should shouldn since so some such t than that the their theirs them themselves then there therefore
        these they this those though through thus to too toward towards under until up upon us ve very via
        was wasn we were weren what whatever when whenever where whereas whether which while who whoever whom
        whose why will with within without won would wouldn yet you your yours yourself yourselves
        tell explain describe give list please know want find show briefly detail details overview summary
        summarize summarise compare comparison versus vs difference differences similar similarity
        similarities main key important according used use using called known
    """.trim().split(Regex("\\s+")).toSet()

    /** Words that frame a question but carry no retrievable content. Kept separate for classification. */
    val QUESTION = setOf("who", "what", "when", "where", "why", "how", "which", "whom", "whose")
}
