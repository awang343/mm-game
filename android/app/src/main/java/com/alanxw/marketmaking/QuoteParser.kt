package com.alanxw.marketmaking

/**
 * Deterministic parser for spoken market-making quotes.
 * Direct port of python/quote_parser.py.
 *
 * Pipeline: word-numbers → digits, drop non-vocab tokens, match patterns.
 */
object QuoteParser {

    private val ONES = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "oh" to 0,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val SCALES = setOf("hundred", "thousand", "million")
    private val NUMBER_WORDS = ONES.keys + TEENS.keys + TENS.keys + SCALES

    private val TRADER_VOCAB = setOf("at", "to", "by", "bid", "for", "up", "offered", "offer")

    private val TOKEN_RE = Regex("""\d+(?:,\d{3})*(?:\.\d+)?|[a-z]+|[,;:.!?]""")
    private val VOCAB_TOKEN_RE = Regex("""\d+(?:\.\d+)?|[a-z]+""")

    private val PAT_SIZE_UP = Regex(
        """(\d+(?:\.\d+)?)\s+(?:bid\s+)?(?:at|to|by|offered)\s+(\d+(?:\.\d+)?)\s+(?:for\s+)?(\d+)\s+up\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PAT_FULL = Regex(
        """(\d+(?:\.\d+)?)\s+bid\s+for\s+(\d+)\s+(\d+)\s+(?:at|offered)\s+(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    data class Quote(val bid: Double, val bidSize: Int, val ask: Double, val askSize: Int)

    fun parse(text: String?): Quote? {
        if (text.isNullOrBlank()) return null
        val filtered = filterToVocab(normalizeNumbers(text))

        PAT_SIZE_UP.find(filtered)?.let { m ->
            val bid = m.groupValues[1].toDouble()
            val ask = m.groupValues[2].toDouble()
            val size = m.groupValues[3].toInt()
            if (ask > bid) return Quote(bid, size, ask, size)
        }
        PAT_FULL.find(filtered)?.let { m ->
            val bid = m.groupValues[1].toDouble()
            val bidSize = m.groupValues[2].toInt()
            val askSize = m.groupValues[3].toInt()
            val ask = m.groupValues[4].toDouble()
            if (ask > bid) return Quote(bid, bidSize, ask, askSize)
        }
        return null
    }

    fun normalizeNumbers(text: String): String {
        val lowered = text.lowercase().replace("-", " ")
        val tokens = TOKEN_RE.findAll(lowered).map { it.value }.toList()

        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            when {
                tok in NUMBER_WORDS -> {
                    val seq = mutableListOf<String>()
                    var j = i
                    while (j < tokens.size && (tokens[j] in NUMBER_WORDS || tokens[j] == "and")) {
                        if (tokens[j] != "and") seq.add(tokens[j])
                        j++
                    }
                    val num = wordsToNum(seq)
                    if (num != null) out.add(num.toString())
                    else out.addAll(tokens.subList(i, j))
                    i = j
                }
                tok[0].isDigit() -> {
                    out.add(tok.replace(",", ""))
                    i++
                }
                else -> {
                    out.add(tok)
                    i++
                }
            }
        }

        // join with spaces, no space before punctuation
        val sb = StringBuilder()
        for (p in out) {
            if (p in setOf(",", ";", ":", ".", "!", "?")) {
                sb.append(p)
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(p)
            }
        }
        return sb.toString()
    }

    fun filterToVocab(normalized: String): String {
        val kept = mutableListOf<String>()
        for (m in VOCAB_TOKEN_RE.findAll(normalized.lowercase())) {
            val tok = m.value
            if (tok in TRADER_VOCAB || tok[0].isDigit()) kept.add(tok)
        }
        return kept.joinToString(" ")
    }

    private fun wordsToNum(tokens: List<String>): Int? {
        if (tokens.isEmpty()) return null
        // Digit-by-digit: "one nine six five" → 1965
        if (tokens.size >= 3 && tokens.all { it in ONES }) {
            return tokens.joinToString("") { ONES[it].toString() }.toInt()
        }
        yearForm(tokens)?.let { return it }
        return standardCardinal(tokens)
    }

    private fun smallCardinal(tokens: List<String>): Int? {
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) {
            val t = tokens[0]
            return ONES[t] ?: TEENS[t] ?: TENS[t]
        }
        if (tokens.size == 2) {
            if (tokens[0] in TENS && tokens[1] in ONES) {
                return TENS[tokens[0]]!! + ONES[tokens[1]]!!
            }
            if (tokens[0] == "oh" && tokens[1] in ONES) return ONES[tokens[1]]
        }
        return null
    }

    private fun yearForm(tokens: List<String>): Int? {
        if (tokens.isEmpty()) return null
        val century = when {
            tokens[0] in TEENS -> TEENS[tokens[0]]!! * 100
            tokens[0] in TENS && tokens.size >= 2 -> TENS[tokens[0]]!! * 100
            else -> return null
        }
        val rest = smallCardinal(tokens.drop(1)) ?: return null
        if (rest >= 100) return null
        return century + rest
    }

    private fun standardCardinal(tokens: List<String>): Int? {
        var total = 0
        var current = 0
        for (t in tokens) {
            when {
                t in ONES -> current += ONES[t]!!
                t in TEENS -> current += TEENS[t]!!
                t in TENS -> current += TENS[t]!!
                t == "hundred" -> current = (if (current == 0) 1 else current) * 100
                t == "thousand" -> {
                    total += (if (current == 0) 1 else current) * 1000
                    current = 0
                }
                t == "million" -> {
                    total += (if (current == 0) 1 else current) * 1_000_000
                    current = 0
                }
                else -> return null
            }
        }
        return total + current
    }

    fun shortcircuitAction(text: String): String? {
        val t = text.trim().lowercase().trimEnd('.', '?', '!', ',')
        return when (t) {
            in REPEAT_PHRASES -> "repeat"
            in OUT_PHRASES -> "out"
            in QUIT_PHRASES -> "quit"
            else -> null
        }
    }

    private val REPEAT_PHRASES = setOf(
        "repeat", "repeat please", "please repeat",
        "again", "again please", "one more time", "say it again", "say that again",
        "can you repeat", "can you repeat please", "can you say it again",
        "what was the question", "what's the question", "whats the question",
        "i didn't hear", "i didnt hear", "i didn't catch that",
    )
    private val OUT_PHRASES = setOf("out", "i'm out", "im out", "clear", "skip", "skip this", "pass", "next")
    private val QUIT_PHRASES = setOf("quit", "exit", "stop", "i'm done", "im done", "done")
}
