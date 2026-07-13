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
    fun toDouble(): Double = raw.toDouble()
    fun toInt(): Int = toDouble().toInt()
    fun toLong(): Long = toDouble().toLong()
}

data class JsonBool(val value: Boolean) : JsonValue

data object JsonNull : JsonValue

/** Thrown when the input is not syntactically valid JSON. Maps to the `INVALID_JSON` decode code. */
class JsonSyntaxException(message: String) : Exception(message)

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

        fun atEnd(): Boolean = pos >= src.length

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
        }

        private fun readArray(): JsonArray {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                items.add(readValue())
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
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
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

        private fun readNumber(): JsonNumber {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && src[pos] in '0'..'9') pos++
            if (!atEnd() && src[pos] == '.') {
                pos++
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (!atEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            val lexeme = src.substring(start, pos)
            if (lexeme.isEmpty() || lexeme == "-") throw JsonSyntaxException("malformed number at offset $start")
            return JsonNumber(lexeme)
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
