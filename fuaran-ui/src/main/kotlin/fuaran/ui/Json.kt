// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/**
 * A minimal, dependency-free JSON reader for the Fuaran UI wire format.
 *
 * The workspace "hand-write the hard part" stance applies: the Kotlin standard
 * library carries no JSON parser, and pulling in `kotlinx.serialization` would add a
 * classpath dependency for no gain on the render-projection path (this decoder never
 * re-encodes, so byte-exact canonical output is a non-goal here). A small recursive
 * reader keeps `fuaran-ui` a plain-JVM library buildable with a bare `kotlinc`, with
 * no artefact resolution.
 *
 * Values are modelled as an immutable [JsonValue] tree. Numbers retain their raw
 * lexeme so a float slot can parse it faithfully; the reader itself never rounds.
 */
sealed interface JsonValue

data class JsonObject(val members: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = members[key]
}

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

/** A JSON number, keeping the source lexeme so no precision is lost before a typed slot reads it. */
data class JsonNumber(val raw: String) : JsonValue {
    /**
     * The lexeme as a `Double`. Total for any value the reader produced: [Json]'s number scanner
     * admits exactly the RFC 8259 grammar, and `String.toDouble` accepts every lexeme in it —
     * rounding to the nearest representable value, and to an infinity for an exponent past the
     * `Double` range, which is what a JSON reader is meant to do rather than an error.
     */
    fun toDouble(): Double = raw.toDouble()

    /**
     * The lexeme as an `Int`, or `null` when it does not denote one **exactly**.
     *
     * The `toDouble().toInt()` pair this replaced had to go because the JVM's `Double`-to-`Int`
     * narrowing SATURATES: `3000000000` came back as `Int.MAX_VALUE` and `1.5` came back as `1`.
     * Both silently — an integer slot received a number that is not the one the document carried,
     * and nothing downstream could tell. The conformance corpus pins an integer slot as having no
     * non-numeric form at all (`reject-binding-int-*`); quietly reshaping one that IS numeric but
     * out of range is the same defect one step further in.
     *
     * `BigDecimal` rather than a hand-rolled parse, because the question "does this lexeme denote
     * an exact integer" must be answered for `1.0` and `1e3` (which do) as well as for `1.5` and
     * `3e9` (which do not — the second only because it leaves `Int`), and a decimal type answers it
     * with no rounding step in the middle. It is `java.math`, so `fuaran-ui` stays dependency-free.
     */
    fun toIntOrNull(): Int? = runCatching { java.math.BigDecimal(raw).intValueExact() }.getOrNull()

    /** The lexeme as a `Long`, or `null` when it does not denote one exactly. See [toIntOrNull]. */
    fun toLongOrNull(): Long? =
        runCatching { java.math.BigDecimal(raw).longValueExact() }.getOrNull()
}

data class JsonBool(val value: Boolean) : JsonValue

data object JsonNull : JsonValue

/** Thrown when the input is not syntactically valid JSON. Maps to the `INVALID_JSON` decode code. */
class JsonSyntaxException(message: String) : Exception(message)

/**
 * Thrown when a [WireLimits] bound the READER owns is breached — syntactic nesting depth,
 * string length, or array/object width. Maps to the `LIMIT_EXCEEDED` decode code.
 *
 * A distinct type, not a flag on [JsonSyntaxException], because only the reader knows
 * which of the two happened and the format explicitly forbids collapsing them: the input
 * is well-formed and merely too large to walk, so reporting it as malformed is an
 * actively wrong diagnosis. The type IS the distinction, so a `catch` site cannot lose it
 * by forgetting to read a flag.
 */
class JsonLimitException(message: String) : Exception(message)

/**
 * Serialise a [JsonValue] back to compact JSON text. The render-projection path never re-encodes a
 * *node* (there is no canonical Kotlin encoder — that is the Rust core's job), but the interaction /
 * write-back path (Phase 545) must marshal a wire-parsed payload [JsonValue] (a `SetState` action's
 * value, a form-field edit) back into the JSON string the session's `set_state` channel takes. This
 * is that minimal, dependency-free writer — RFC 8259 string escaping, source number lexemes preserved.
 */
fun JsonValue.encode(): String {
    val sb = StringBuilder()
    encodeInto(sb)
    return sb.toString()
}

private fun JsonValue.encodeInto(sb: StringBuilder) {
    when (this) {
        is JsonString -> encodeJsonString(value, sb)
        is JsonNumber -> sb.append(raw)
        is JsonBool -> sb.append(if (value) "true" else "false")
        JsonNull -> sb.append("null")
        is JsonArray -> {
            sb.append('[')
            items.forEachIndexed { i, item ->
                if (i > 0) sb.append(',')
                item.encodeInto(sb)
            }
            sb.append(']')
        }
        is JsonObject -> {
            sb.append('{')
            var first = true
            for ((k, v) in members) {
                if (!first) sb.append(',')
                first = false
                encodeJsonString(k, sb)
                sb.append(':')
                v.encodeInto(sb)
            }
            sb.append('}')
        }
    }
}

private fun encodeJsonString(s: String, sb: StringBuilder) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else ->
                if (c < ' ') {
                    sb.append("\\u")
                    sb.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
        }
    }
    sb.append('"')
}

/** A single-pass recursive-descent JSON reader (RFC 8259). */
object Json {
    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) {
            throw JsonSyntaxException("trailing content at offset ${reader.pos}")
        }
        return value
    }

    private class Reader(val src: String) {
        var pos = 0

        /**
         * Current SYNTACTIC nesting depth (`WireLimits.MAX_JSON_DEPTH`). Incremented on
         * the way DOWN — before the recursion that would breach the bound — so nothing
         * has been allocated when it fires, and never by measuring the value that was
         * built. This is a field on the reader rather than shared state precisely
         * because a `Reader` is per-parse: two concurrent parses cannot see each other's
         * count without any locking or thread-locals.
         */
        var depth = 0

        fun atEnd(): Boolean = pos >= src.length

        /** Enters one composite level, refusing the level that would breach the bound. */
        fun enterComposite() {
            depth++
            if (depth > WireLimits.MAX_JSON_DEPTH) {
                throw JsonLimitException(
                    "JSON nesting deeper than the wire limit MAX_JSON_DEPTH = ${WireLimits.MAX_JSON_DEPTH}; " +
                        "expected a document nesting no more than ${WireLimits.MAX_JSON_DEPTH} levels deep",
                )
            }
        }

        fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun readValue(): JsonValue {
            if (atEnd()) throw JsonSyntaxException("unexpected end of input")
            return when (val c = src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonString(readString())
                't', 'f' -> readBool()
                'n' -> readNull()
                else ->
                    if (c == '-' || c in '0'..'9') readNumber()
                    else throw JsonSyntaxException("unexpected character '$c' at offset $pos")
            }
        }

        private fun readObject(): JsonObject {
            expect('{')
            // BEFORE the empty-composite arm below, deliberately: every `{` counts,
            // empty or not. Testing after it leaves the innermost level of a `{{{…}}}`
            // payload unmeasured — the exact off-by-one that made the host family
            // disagree by one level at the syntactic boundary.
            enterComposite()
            try {
                val members = LinkedHashMap<String, JsonValue>()
                skipWhitespace()
                if (peek() == '}') {
                    pos++
                    return JsonObject(members)
                }
                while (true) {
                    skipWhitespace()
                    if (peek() != '"') throw JsonSyntaxException("expected object key at offset $pos")
                    val key = readString()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    members[key] = readValue()
                    if (members.size > WireLimits.MAX_ARRAY_LENGTH) {
                        throw JsonLimitException(
                            "an object has more members than the wire limit MAX_ARRAY_LENGTH = " +
                                "${WireLimits.MAX_ARRAY_LENGTH}; expected objects of no more than " +
                                "${WireLimits.MAX_ARRAY_LENGTH} members",
                        )
                    }
                    skipWhitespace()
                    when (val c = peek()) {
                        ',' -> pos++
                        '}' -> {
                            pos++
                            return JsonObject(members)
                        }
                        else -> throw JsonSyntaxException("expected ',' or '}' but saw '$c' at offset $pos")
                    }
                }
            } finally {
                depth--
            }
        }

        private fun readArray(): JsonArray {
            expect('[')
            // See the note in readObject: before the empty arm, every `[` counts.
            enterComposite()
            try {
                val items = ArrayList<JsonValue>()
                skipWhitespace()
                if (peek() == ']') {
                    pos++
                    return JsonArray(items)
                }
                while (true) {
                    skipWhitespace()
                    items.add(readValue())
                    if (items.size > WireLimits.MAX_ARRAY_LENGTH) {
                        throw JsonLimitException(
                            "an array is longer than the wire limit MAX_ARRAY_LENGTH = " +
                                "${WireLimits.MAX_ARRAY_LENGTH}; expected arrays of no more than " +
                                "${WireLimits.MAX_ARRAY_LENGTH} elements",
                        )
                    }
                    skipWhitespace()
                    when (val c = peek()) {
                        ',' -> pos++
                        ']' -> {
                            pos++
                            return JsonArray(items)
                        }
                        else -> throw JsonSyntaxException("expected ',' or ']' but saw '$c' at offset $pos")
                    }
                }
            } finally {
                depth--
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                // Inside the accumulation loop rather than after it: a hostile 100 MB
                // literal is then refused partway through, rather than being built in
                // full and measured afterwards. `> MAX` and not `>=`, so a string of
                // exactly MAX_STRING_LENGTH characters is accepted — the limit is
                // inclusive, and a bound one character too tight would refuse a document
                // every other host must accept.
                if (sb.length > WireLimits.MAX_STRING_LENGTH) {
                    throw JsonLimitException(
                        "a string is longer than the wire limit MAX_STRING_LENGTH = " +
                            "${WireLimits.MAX_STRING_LENGTH}; expected strings of no more than " +
                            "${WireLimits.MAX_STRING_LENGTH} characters",
                    )
                }
                if (atEnd()) throw JsonSyntaxException("unterminated string")
                when (val c = src[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonSyntaxException("unterminated escape")
                        when (val e = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw JsonSyntaxException("truncated \\u escape")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonSyntaxException("bad \\u escape '$hex'")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonSyntaxException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        /**
         * The RFC 8259 number grammar, exactly:
         *
         * ```text
         * number = '-'? int frac? exp?
         * int    = '0' | [1-9] [0-9]*
         * frac   = '.' [0-9]+
         * exp    = ('e' | 'E') ('+' | '-')? [0-9]+
         * ```
         *
         * Each of the three parts REQUIRES its digits. The scanner this replaced looped
         * `while (digit) pos++` at every part, which accepts a part with no digits at all: `1e`
         * and `1e+` were read as numbers, and the lexeme was carried to a typed slot that threw
         * `NumberFormatException` — an untyped, unpathed failure escaping a decoder whose whole
         * contract is that it refuses in one shape. `.5`, `1.` and `01` slipped through the same
         * way. A malformed number is refused HERE, as [JsonSyntaxException], which the decoder
         * already maps to the canonical `INVALID_JSON` code.
         *
         * Refusing the leading zero costs nothing extra: after `0` the integer part simply ends,
         * so `01` leaves `1` unread and the enclosing value's own expectation (a comma, a brace,
         * end of input) fails on it.
         */
        private fun readNumber(): JsonNumber {
            val start = pos
            if (peek() == '-') pos++
            if (atEnd() || src[pos] !in '0'..'9') {
                throw JsonSyntaxException("a number needs at least one integer digit at offset $start")
            }
            if (src[pos] == '0') {
                pos++
            } else {
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            if (!atEnd() && src[pos] == '.') {
                pos++
                if (atEnd() || src[pos] !in '0'..'9') {
                    throw JsonSyntaxException("a fraction needs at least one digit at offset $pos")
                }
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (!atEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                if (atEnd() || src[pos] !in '0'..'9') {
                    throw JsonSyntaxException("an exponent needs at least one digit at offset $pos")
                }
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            return JsonNumber(src.substring(start, pos))
        }

        private fun readBool(): JsonBool =
            when {
                src.startsWith("true", pos) -> {
                    pos += 4
                    JsonBool(true)
                }
                src.startsWith("false", pos) -> {
                    pos += 5
                    JsonBool(false)
                }
                else -> throw JsonSyntaxException("invalid literal at offset $pos")
            }

        private fun readNull(): JsonValue =
            if (src.startsWith("null", pos)) {
                pos += 4
                JsonNull
            } else {
                throw JsonSyntaxException("invalid literal at offset $pos")
            }

        private fun peek(): Char {
            if (atEnd()) throw JsonSyntaxException("unexpected end of input")
            return src[pos]
        }

        private fun expect(c: Char) {
            if (atEnd() || src[pos] != c) throw JsonSyntaxException("expected '$c' at offset $pos")
            pos++
        }
    }
}
