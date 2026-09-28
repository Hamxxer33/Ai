package io.kestrel.engine.text

/**
 * The original Porter (1980) stemming algorithm, the one SQLite FTS5's `porter` tokenizer uses,
 * so that terms stemmed here match the terms in the FTS index (e.g. for df lookups).
 */
object Porter {
    fun stem(word: String): String {
        if (word.length <= 2 || !word.all { it in 'a'..'z' }) return word
        val s = Stemmer(word)
        s.run()
        return s.result()
    }

    private class Stemmer(w: String) {
        private var b = w.toCharArray()
        private var k = b.size - 1 // end of current word
        private var j = 0 // general offset

        fun result() = String(b, 0, k + 1)

        private fun cons(i: Int): Boolean = when (b[i]) {
            'a', 'e', 'i', 'o', 'u' -> false
            'y' -> if (i == 0) true else !cons(i - 1)
            else -> true
        }

        /** Number of VC sequences between 0 and j. */
        private fun m(): Int {
            var n = 0
            var i = 0
            while (true) {
                if (i > j) return n
                if (!cons(i)) break
                i++
            }
            i++
            while (true) {
                while (true) {
                    if (i > j) return n
                    if (cons(i)) break
                    i++
                }
                i++
                n++
                while (true) {
                    if (i > j) return n
                    if (!cons(i)) break
                    i++
                }
                i++
            }
        }

        private fun vowelInStem(): Boolean = (0..j).any { !cons(it) }

        private fun doublec(i: Int): Boolean = i >= 1 && b[i] == b[i - 1] && cons(i)

        private fun cvc(i: Int): Boolean {
            if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) return false
            val ch = b[i]
            return !(ch == 'w' || ch == 'x' || ch == 'y')
        }

        private fun ends(s: String): Boolean {
            val l = s.length
            val o = k - l + 1
            if (o < 0) return false
            for (i in 0 until l) if (b[o + i] != s[i]) return false
            j = k - l
            return true
        }

        private fun setto(s: String) {
            val l = s.length
            val o = j + 1
            if (o + l > b.size) b = b.copyOf(o + l)
            for (i in 0 until l) b[o + i] = s[i]
            k = j + l
        }

        private fun r(s: String) {
            if (m() > 0) setto(s)
        }

        private fun step1ab() {
            if (b[k] == 's') {
                if (ends("sses")) k -= 2
                else if (ends("ies")) setto("i")
                else if (k >= 1 && b[k - 1] != 's') k--
            }
            if (ends("eed")) {
                if (m() > 0) k--
            } else if ((ends("ed") || ends("ing")) && vowelInStem()) {
                k = j
                if (ends("at")) setto("ate")
                else if (ends("bl")) setto("ble")
                else if (ends("iz")) setto("ize")
                else if (doublec(k)) {
                    k--
                    val ch = b[k]
                    if (ch == 'l' || ch == 's' || ch == 'z') k++
                } else if (m() == 1 && cvc(k)) setto("e")
            }
        }

        private fun step1c() {
            if (ends("y") && vowelInStem()) b[k] = 'i'
        }

        private fun step2() {
            if (k == 0) return
            when (b[k - 1]) {
                'a' -> { if (ends("ational")) r("ate") else if (ends("tional")) r("tion") }
                'c' -> { if (ends("enci")) r("ence") else if (ends("anci")) r("ance") }
                'e' -> { if (ends("izer")) r("ize") }
                'l' -> {
                    if (ends("bli")) r("ble") else if (ends("alli")) r("al") else if (ends("entli")) r("ent")
                    else if (ends("eli")) r("e") else if (ends("ousli")) r("ous")
                }
                'o' -> { if (ends("ization")) r("ize") else if (ends("ation")) r("ate") else if (ends("ator")) r("ate") }
                's' -> {
                    if (ends("alism")) r("al") else if (ends("iveness")) r("ive") else if (ends("fulness")) r("ful")
                    else if (ends("ousness")) r("ous")
                }
                't' -> { if (ends("aliti")) r("al") else if (ends("iviti")) r("ive") else if (ends("biliti")) r("ble") }
                'g' -> { if (ends("logi")) r("log") }
            }
        }

        private fun step3() {
            when (b[k]) {
                'e' -> { if (ends("icate")) r("ic") else if (ends("ative")) r("") else if (ends("alize")) r("al") }
                'i' -> { if (ends("iciti")) r("ic") }
                'l' -> { if (ends("ical")) r("ic") else if (ends("ful")) r("") }
                's' -> { if (ends("ness")) r("") }
            }
        }

        private fun step4() {
            if (k == 0) return
            val matched = when (b[k - 1]) {
                'a' -> ends("al")
                'c' -> ends("ance") || ends("ence")
                'e' -> ends("er")
                'i' -> ends("ic")
                'l' -> ends("able") || ends("ible")
                'n' -> ends("ant") || ends("ement") || ends("ment") || ends("ent")
                'o' -> (ends("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) || ends("ou")
                's' -> ends("ism")
                't' -> ends("ate") || ends("iti")
                'u' -> ends("ous")
                'v' -> ends("ive")
                'z' -> ends("ize")
                else -> false
            }
            if (matched && m() > 1) k = j
        }

        private fun step5() {
            j = k
            if (b[k] == 'e') {
                val a = m()
                if (a > 1 || (a == 1 && !cvc(k - 1))) k--
            }
            if (b[k] == 'l' && doublec(k) && m() > 1) k--
        }

        fun run() {
            if (k > 1) {
                step1ab()
                if (k > 0) {
                    step1c()
                    step2()
                    step3()
                    step4()
                    step5()
                }
            }
        }
    }
}
