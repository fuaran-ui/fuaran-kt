// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

import java.io.File

/**
 * Decoder robustness fuzz - this surface's leg of the cross-host family (Phase 1023, extended to
 * this host by Phase 1540 task H-30).
 *
 * The threat model's load-bearing claim is that decoding is TOTAL: a malformed or hostile input
 * yields a structured, typed error, never an escaping runtime exception and never a hang. Until a
 * fuzz leg exists on a host, that claim rests there on a CURATED reject corpus - inputs an author
 * chose, which is evidence about the author's imagination rather than about the decoder. The
 * corpus leg beside this one asserts the cases somebody thought of; this one asserts the property.
 *
 * It is pointed at what Phase 1540 changed. The reader's number scanner previously looped
 * `while (digit) pos++` at each of the three parts of the grammar, so a part with NO digits was
 * read as a number and the lexeme was carried to a typed slot that threw `NumberFormatException` -
 * an untyped, unpathed failure escaping a decoder whose entire contract is that it refuses in one
 * shape. `1e`, `1e+`, `.5`, `1.` and `01` all took that path, and `3000000000` reached an integer
 * slot through a saturating `toDouble().toInt()`. Those exact lexemes are a named generation
 * family below, so the CLASS stays closed rather than the five instances.
 *
 * ## The invariants, per input
 *
 *  1. **Totality** - the entry point returns a value, or throws one of the TYPED refusals it is
 *     contracted to raise. Anything else is a counterexample: a `NumberFormatException`, a
 *     `StackOverflowError`, an `IndexOutOfBoundsException`, a `NullPointerException`, a
 *     `ClassCastException`, or any other throwable. `Throwable` is caught rather than `Exception`
 *     precisely because two of the most likely defects here (`StackOverflowError` from an
 *     unbounded recursive descent, `OutOfMemoryError` from an unbounded accumulation) are `Error`s,
 *     and a harness catching only `Exception` would miss the two failures it most exists to find.
 *  2. **Termination** - it returns inside a per-input wall-clock budget.
 *  3. **Fixed point** - an accepted input's canonical form re-decodes and re-encodes to itself.
 *     Available for ONE of the two entry points, and the reason is structural rather than an
 *     omission: this surface is a render projection with **no canonical node encoder** - that is
 *     the Rust reference core's job, and the absence is the charter, not a gap. So `decodeNode`
 *     carries invariants 1 and 2 only, stated here rather than left to be inferred from a missing
 *     assertion. The READER half does have an encoder (`JsonValue.encode`, the write-back path's
 *     marshaller), so `Json.parse` carries all three and the fixed point is genuinely exercised.
 *  4. **Bounded work** - the Rust reference host measures allocated bytes per input against a
 *     budget, through a counting global allocator. **That does not port to the JVM exactly, and it
 *     is not faked here.** The nearest instrument is HotSpot's
 *     `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, which is neither portable across
 *     JVMs nor measuring the same thing: it counts TLAB allocation on the calling thread including
 *     whatever the JIT and the interpreter allocated on its behalf, so the same decode reports
 *     different figures interpreted and compiled, and a budget over it would be tuned to a warmup
 *     state rather than to the decoder. A number that moves for reasons having nothing to do with
 *     the code under test is worse than no number, because it gets raised in a hurry by whoever it
 *     blocks. The bound this host DOES carry is the one the wire specification states - the
 *     [WireLimits] resource limits, asserted directly by the corpus leg beside this one - plus the
 *     time budget in invariant 2, which a runaway allocation reaches anyway once the collector
 *     starts thrashing.
 *
 * ## Determinism
 *
 * SplitMix64, hand-rolled: replayability is the whole point of the seed, and there is no seedable
 * generator in the Kotlin standard library specified to produce the same stream across JVM
 * versions. One notable difference from the Rust host's generator: a Kotlin `String` is a sequence
 * of UTF-16 code units with no well-formedness guarantee, so slicing it anywhere is total, and a
 * cut through a surrogate pair yields a LONE SURROGATE. That is a case the Rust host structurally
 * cannot be handed through its public decode surface, and it arrives here for free from the
 * ordinary span mutators - though the escape family below aims at it deliberately as well.
 *
 * ## Running it
 *
 * ```text
 * java -cp "build/fuaran-kt.jar;build/classes" fuaran.ui.DecoderFuzzTestKt
 *
 * FUARAN_FUZZ_SEED=<n>          replay a specific stream (default 1023)
 * FUARAN_FUZZ_ITERATIONS=<n>    inputs per entry point (default 50000 bounded, 250000 long)
 * FUARAN_FUZZ_LONG=1            the long profile: larger payloads, more pathological inputs
 * FUARAN_CORPUS=<dir>           the shared fixture corpus, used as the seed pool
 * ```
 *
 * A counterexample is reported with its MINIMISED input and the escaping throwable's class. The
 * policy is the family's: fix the decoder, then land the input as a permanent reject fixture in the
 * shared corpus, so every conformant host inherits the case rather than only this one.
 */

// --------------------------------------------------------------------------- //
// Deterministic PRNG
// --------------------------------------------------------------------------- //

private val GOLDEN: Long = 0x9E3779B97F4A7C15uL.toLong()

private class Rng(seed: Long) {
    private var s: Long = if (seed == 0L) GOLDEN else seed

    fun nextLong(): Long {
        s += GOLDEN
        var z = s
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /** Uniform in `[0, n)`; `0` for a non-positive `n` so no caller has to guard. */
    fun next(n: Int): Int = if (n <= 1) 0 else ((nextLong() ushr 1) % n.toLong()).toInt()

    /** Uniform in `[lo, hi]`, inclusive. */
    fun range(lo: Int, hi: Int): Int = if (hi <= lo) lo else lo + next(hi - lo + 1)

    fun boolean(): Boolean = (nextLong() and 1L) == 1L

    fun <T> pick(xs: List<T>): T = xs[next(xs.size)]
}

// --------------------------------------------------------------------------- //
// Seeds + vocabulary
// --------------------------------------------------------------------------- //

/**
 * Built-in seeds, so the harness is self-sufficient: the go-red self-tests must not depend on the
 * shared corpus being checked out beside this repo in order to prove that the harness can fail.
 */
private val BUILTIN_SEEDS: List<String> =
    listOf(
        "{\"id\":\"heading-1\",\"kind\":{\"\$type\":\"Heading\",\"level\":2,\"text\":\"Channel\"," +
            "\"variant\":\"Standard\"}}",
        "{\"id\":\"markdown-1\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"Updated hourly.\"}}",
        "{\"id\":\"b\",\"kind\":{\"\$type\":\"Box\",\"children\":[],\"layout\":{\"\$type\":\"Flex\"," +
            "\"direction\":\"Vertical\",\"wrap\":false},\"role\":\"Group\"}}",
        // An INT slot and a FLOAT slot in the seed pool, so the number families have somewhere
        // typed to land after a mutation rather than only the bare-document positions.
        "{\"id\":\"n1\",\"kind\":{\"\$type\":\"Tabs\",\"activeIndex\":{\"\$type\":\"Static\",\"value\":0}," +
            "\"children\":[{\"id\":\"m1\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"x\"}}]}}",
        "{\"id\":\"m\",\"kind\":{\"\$type\":\"Metric\",\"label\":\"Revenue\"," +
            "\"value\":{\"\$type\":\"Static\",\"value\":42.0}}}",
        "{}",
        "[]",
        "null",
        "",
        "0",
        "\"x\"",
    )

/** A node the decoder must accept - the floor under every go-red assertion below. */
private const val WELL_FORMED_NODE =
    "{\"id\":\"markdown-1\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"Updated hourly.\"}}"

/**
 * Corpus candidates, and the cross-host discrimination beside them, are a deliberate SECOND COPY of
 * the ones in `CorpusDecodeTest`. Kotlin top-level `private` is file-scoped, so sharing them would
 * mean widening that file's declarations - an edit to a file this change has no other reason to
 * touch, for a nine-line list. The duplication is cheap and visible; the coupling would not be.
 */
private val FUZZ_CORPUS_CANDIDATES =
    listOf(
        "../wire-format-fixtures",
        "../../wire-format-fixtures",
        "wire-format-fixtures",
        "fuaran-kt/../wire-format-fixtures",
    )

private val FUZZ_SIBLING_HOST_NAMES =
    listOf("fuaran-dotnet", "fuaran", "fuaran-ts", "fuaran-py", "fuaran-go", "fuaran-rs", "fuaran-swift")

private fun locateFuzzCorpus(): File? {
    System.getenv("FUARAN_CORPUS")?.let {
        val f = File(it)
        if (File(f, "manifest.json").isFile) return f
    }
    for (c in FUZZ_CORPUS_CANDIDATES) {
        val f = File(c)
        if (File(f, "manifest.json").isFile) return f
    }
    return null
}

private fun fuzzCrossHostSibling(): Pair<String, File>? {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        for (name in FUZZ_SIBLING_HOST_NAMES) {
            if (File(dir, name).isDirectory) return name to dir
        }
        dir = dir.parentFile
    }
    return null
}

/**
 * Every corpus payload this harness can find, as raw text. READ-ONLY by construction: the fuzz
 * never writes into the corpus. A REJECT fixture is the most productive seed there is, since it
 * already sits one edit away from the refusal boundary the fuzz is probing.
 *
 * `ops/` is EXCLUDED, and not as a filter over a family: there is no `TreeOp` decoder on this
 * surface at all - the core owns apply, and a render projection never sees an op - so an op
 * document is simply not a node, every mutation of one lands in the same shallow envelope refusal,
 * and including them would displace mutations that reach a typed slot with mutations that cannot.
 */
private fun loadFuzzSeeds(corpus: File?): List<String> {
    val seeds = BUILTIN_SEEDS.toMutableList()
    if (corpus == null) return seeds
    for (family in listOf("nodes", "lenient", "reject")) {
        val files = File(corpus, family).listFiles() ?: continue
        for (f in files.sortedBy { it.name }) {
            if (!f.isFile || !f.name.endsWith(".json") || f.name.endsWith(".expected.json")) continue
            runCatching { seeds.add(f.readText()) }
        }
    }
    return seeds
}

private val FALLBACK_VOCAB =
    listOf("Box", "Heading", "Markdown", "Metric", "Badge", "Form", "Button", "DataGrid", "Chart", "Custom")

/**
 * The wire vocabulary the near-miss generators aim just beside, read from the corpus MANIFEST so a
 * newly-admitted kind is fuzzed the day it lands rather than whenever someone remembers to extend a
 * literal list here. Parsed with this host's own reader, which is the only one it has.
 */
private fun loadFuzzVocabulary(corpus: File?): List<String> {
    if (corpus != null) {
        val names =
            runCatching {
                val root = Json.parse(File(corpus, "manifest.json").readText()) as JsonObject
                (root["kinds"] as? JsonArray)?.items.orEmpty().mapNotNull { (it as? JsonString)?.value }
            }.getOrDefault(emptyList())
        if (names.isNotEmpty()) return names
    }
    return FALLBACK_VOCAB
}

// --------------------------------------------------------------------------- //
// Alphabets
// --------------------------------------------------------------------------- //

/**
 * One UTF-16 string from raw code units.
 *
 * Every non-ASCII entry in the alphabets below is built through this rather than written as the
 * character itself, so **this source file is pure ASCII on disk**. That is not style. A raw NUL in
 * a tracked file makes git classify it as binary, at which point the file is excluded from EOL
 * normalisation and from every textual diff for good; and a lone surrogate cannot be written as a
 * literal at all in some editors while silently becoming U+FFFD in others, which would replace the
 * exact case it was there to test with the replacement character standing in for it.
 *
 * Deliberately NOT `String(intArray, offset, count)`, which takes CODE POINTS: these are CODE
 * UNITS, so an unpaired surrogate stays unpaired instead of being normalised away.
 */
private fun cu(vararg units: Int): String = String(CharArray(units.size) { Char(units[it]) })

private val HOSTILE_CHARS =
    listOf(
        "{", "}", "[", "]", "\"", ":", ",", "\\", "/", "-", "+", ".", "e", "E", "0", "9",
        "n", "t", "f", " ", "\t", "\n", "\r",
        cu(0x00), cu(0x1B), cu(0x7F),
        cu(0xFEFF), cu(0x2028), cu(0xFFFD), cu(0x00E9), cu(0x4E2D),
        // Lone surrogates. A Kotlin String may hold one, so this host CAN be handed one through
        // its public decode surface - the case the Rust host's `&str` type removes rather than
        // declines to test.
        cu(0xD800), cu(0xDC00), cu(0xDBFF), cu(0xDFFF),
    )

/**
 * The JSON-ESCAPE entries are TEXT (a backslash, `u`, four hex digits), not the code points they
 * denote - a decoder's unescape path is what they are aimed at, so writing them as the characters
 * would test the wrong thing.
 */
private val HOSTILE_TOKENS =
    listOf(
        "null", "true", "false", "{}", "[]", "\"\"", "-0", "1e999", "-1e999", "1E-999",
        "NaN", "Infinity", "-Infinity", "0x10", "00", "01", "1.2.3", "+1", ".5", "5.", "1e", "1e+", "1.",
        "\\u0000", "\\uD800", "\\uDFFF", "\\uFFFF", "\\x41", "\\", "\\\"", "\\u", "\\u00", "\\uZZZZ",
        "\"\$type\":\"\"", "\"\$type\":null", "\"id\":\"\"", "\"id\":null", "\"id\":[]",
        "\"kind\":\"Heading\"", "\"children\":\"x\"", "\"level\":1e", "\"value\":3000000000",
        ",", ":", "[", "]", "{", "}", "\"", "'", "/*", "*/", "//", "\r\n",
        cu(0x00), cu(0xFEFF),
    )

/** REAL wire keys, so a generated near-miss reaches the typed decoders rather than bouncing off the envelope. */
private val WIRE_KEYS =
    listOf(
        "id", "kind", "\$type", "children", "layout", "role", "text", "level", "variant", "source",
        "value", "label", "fields", "items", "columns", "rows", "onSubmit", "onClick", "required",
        "binding", "style", "props", "state", "path", "node", "index", "target", "name", "format",
        "unit", "min", "max", "options", "spec", "accessibility", "tooltip", "activeIndex",
        "activeStep", "srcSet", "tracks", "permissions", "__proto__", "constructor", "", " ",
    )

private val SCALAR_LITERALS =
    listOf(
        "0", "-1", "1e308", "-1e308", "1e999", "3.141592653589793", "true", "false", "null",
        "\"\"", "\"x\"", "\"Standard\"", "\"Group\"", "9007199254740993", "-0.0", "3000000000", "1e",
    )

/**
 * The NUMBER GRAMMAR edge cases. Each is raw JSON text destined for a numeric position, and the
 * first five are the exact lexemes the pre-1540 scanner admitted and carried to a typed slot that
 * threw `NumberFormatException`. `1e3`, `1.0`, `2`, `-7` and `0` are in the list deliberately: a
 * family that only ever generates REFUSALS never proves the reader still accepts what it must, and
 * `1e3` / `1.0` are the two shapes an exact-integer test gets wrong in the other direction.
 *
 * U+0663 ARABIC-INDIC DIGIT THREE is in the list for a reason worth naming: `Character.isDigit`
 * accepts it and the JSON grammar does not, so it is exactly what a scanner written with the
 * platform's digit predicate rather than an explicit `'0'..'9'` range lets through.
 */
private val NUMBER_LEXEMES =
    listOf(
        "1e", "1e+", "1e-", ".5", "1.", "01", "-", "+1", "00", "0.", ".", "--1", "1.2.3", "0x10",
        "1e+e", "1.e3", "1_000", cu(0x0663),
        "NaN", "Infinity", "-Infinity", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"nan\"", "\"1\"",
        "1e400", "-1e400", "1e999999", "1e" + "9".repeat(300), "1e-" + "9".repeat(300),
        "1" + "0".repeat(400), "-1" + "0".repeat(400),
        "3000000000", "-3000000000", "2147483648", "-2147483649", "9007199254740993",
        "1.5", "0.1", "1e3", "1.0", "2", "-7", "0", "-0",
    )

/**
 * String ESCAPE payloads, dropped into a string slot verbatim. The surrogate families are the
 * point: a lone leading surrogate, a lone trailing one, a mismatched pair, a well-formed pair, and
 * the truncated escapes that make an unescape path read past its own input.
 */
private val ESCAPE_PAYLOADS =
    listOf(
        "\\u0041", "\\u0000", "\\uFFFF", "\\uFFFE", "\\uFEFF", "\\u2028", "\\u2029",
        "\\uD800", "\\uDC00", "\\uDBFF", "\\uDFFF",
        "\\uD800\\uDC00", "\\uD800\\uD800", "\\uDC00\\uD800", "\\uD800x", "\\uD800\\n",
        "\\u", "\\u0", "\\u00", "\\u004", "\\uZZZZ", "\\u 041", "\\u+041", "\\uD80",
        "\\x41", "\\a", "\\0", "\\", "\\\\", "\\\"", "\\/", "\\b\\f\\n\\r\\t",
        "\n", "\t",
        // RAW code units rather than escapes - the other door into the same code paths. The last
        // entry is a well-formed astral character (U+10000), so the set covers both halves.
        cu(0xD800), cu(0xDFFF), cu(0x00), cu(0x7F), cu(0xFFFD), cu(0xD800, 0xDC00),
        "a".repeat(2000),
    )

// --------------------------------------------------------------------------- //
// Near miss + mutators
// --------------------------------------------------------------------------- //

/**
 * A near-miss of a real vocabulary word: the class of input a model emitter actually produces, and
 * the class a curated reject corpus is worst at covering, because a human writing fixtures reaches
 * for obvious garbage.
 */
private fun nearMiss(rng: Rng, word: String): String {
    if (word.isEmpty()) return "x"
    return when (rng.next(8)) {
        0 -> word.lowercase()
        1 -> word.uppercase()
        2 -> word + "s"
        3 -> word.substring(0, word.length - 1)
        4 -> "$word "
        5 -> " $word"
        6 -> {
            val i = rng.next(word.length)
            word.substring(0, i) + word.substring(i + 1)
        }
        else -> {
            val i = rng.next(word.length + 1)
            word.substring(0, i) + rng.pick(HOSTILE_CHARS) + word.substring(i)
        }
    }
}

/**
 * Each mutator corrupts a payload, and each is NAMED so a reported counterexample records WHICH
 * transformation produced it: a find whose provenance is only "the fuzzer did something" is
 * markedly harder to act on.
 *
 * No char-boundary machinery, unlike the Rust host's set. A Kotlin `String` is indexed in UTF-16
 * code units and `substring` is total at every index, so a cut through a surrogate pair produces a
 * lone surrogate rather than an exception - which is a case worth generating, not one to avoid.
 */
private val MUTATOR_NAMES =
    listOf(
        "flip-char", "delete-span", "insert-token", "duplicate-span", "truncate", "transpose",
        "repeat-structural", "retype-value", "near-miss-type", "delete-key", "duplicate-key",
        "escape-injection", "number-swap", "prefix-junk", "suffix-junk",
    )

private class FuzzConfig(val name: String, val maxPayloadChars: Int, val heavyEveryN: Int)

private val BOUNDED_CONFIG = FuzzConfig("bounded", 32 * 1024, 120)
private val LONG_CONFIG = FuzzConfig("long", 2 * 1024 * 1024, 25)

private fun truncateTo(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)

private fun nearMissType(rng: Rng, vocab: List<String>, s: String): String {
    val marker = "\"\$type\":\""
    val positions = mutableListOf<Int>()
    var at = s.indexOf(marker)
    while (at >= 0) {
        positions.add(at)
        at = s.indexOf(marker, at + 1)
    }
    if (positions.isEmpty()) {
        // No discriminator to corrupt - APPEND one rather than returning the input untouched. A
        // silently no-op mutator quietly shrinks the effective iteration count and nothing reports
        // that it did.
        return s + "{\"\$type\":\"" + nearMiss(rng, rng.pick(vocab)) + "\"}"
    }
    val start = rng.pick(positions) + marker.length
    val close = s.indexOf('"', start)
    if (close < 0) return s
    val replacement =
        if (rng.boolean()) nearMiss(rng, s.substring(start, close)) else nearMiss(rng, rng.pick(vocab))
    return s.substring(0, start) + replacement + s.substring(close)
}

/** Delete a whole `"key":value` pair, cutting from the key's opening quote to just past the next comma. */
private fun deleteKey(rng: Rng, s: String): String {
    val positions = mutableListOf<Int>()
    var at = s.indexOf("\":")
    while (at >= 0) {
        positions.add(at)
        at = s.indexOf("\":", at + 1)
    }
    if (positions.isEmpty()) return s
    val colon = rng.pick(positions)
    var closeQuote = colon
    while (closeQuote > 0 && s[closeQuote] != '"') closeQuote--
    var openQuote = if (closeQuote > 0) closeQuote - 1 else 0
    while (openQuote > 0 && s[openQuote] != '"') openQuote--
    val comma = s.indexOf(',', colon)
    val cutTo = if (comma >= 0) comma + 1 else minOf(s.length, colon + 8)
    return s.substring(0, openQuote) + s.substring(cutTo)
}

/** Replace a number-shaped run with a hostile lexeme, so the number family reaches a typed slot. */
private fun numberSwap(rng: Rng, s: String): String {
    val starts = mutableListOf<Int>()
    for (i in s.indices) {
        val c = s[i]
        if ((c in '0'..'9' || c == '-') && (i == 0 || s[i - 1] == ':' || s[i - 1] == '[' || s[i - 1] == ',')) {
            starts.add(i)
        }
    }
    if (starts.isEmpty()) return s
    val start = rng.pick(starts)
    var end = start
    while (end < s.length && (s[end] in '0'..'9' || s[end] in "-+.eE")) end++
    return s.substring(0, start) + rng.pick(NUMBER_LEXEMES) + s.substring(end)
}

private fun mutateOnce(rng: Rng, vocab: List<String>, cfg: FuzzConfig, s: String): Pair<String, String> {
    val name = rng.pick(MUTATOR_NAMES)
    val n = s.length
    val result =
        when {
            name == "flip-char" && n > 0 -> {
                val i = rng.next(n)
                s.substring(0, i) + rng.pick(HOSTILE_CHARS) + s.substring(i + 1)
            }
            name == "delete-span" && n > 1 -> {
                val i = rng.next(n)
                val to = minOf(n, i + rng.range(1, 8))
                s.substring(0, i) + s.substring(to)
            }
            name == "insert-token" -> {
                val i = rng.next(n + 1)
                s.substring(0, i) + rng.pick(HOSTILE_TOKENS) + s.substring(i)
            }
            name == "duplicate-span" && n > 1 -> {
                val i = rng.next(n)
                val to = minOf(n, i + rng.range(1, 64))
                val at = rng.next(n + 1)
                s.substring(0, at) + s.substring(i, to) + s.substring(at)
            }
            name == "truncate" && n > 1 -> s.substring(0, rng.next(n))
            name == "transpose" && n > 2 -> {
                val i = rng.next(n - 1)
                s.substring(0, i) + s[i + 1] + s[i] + s.substring(i + 2)
            }
            name == "repeat-structural" -> {
                val ch = rng.pick(listOf("[", "{", "\"", "]", "}", ",", "\\"))
                val count = minOf(rng.range(2, 4096), maxOf(2, cfg.maxPayloadChars / 4))
                val at = rng.next(n + 1)
                s.substring(0, at) + ch.repeat(count) + s.substring(at)
            }
            name == "retype-value" && n > 0 -> {
                val i = rng.next(n)
                val to = minOf(n, i + rng.range(1, 12))
                s.substring(0, i) + rng.pick(SCALAR_LITERALS) + s.substring(to)
            }
            name == "near-miss-type" -> nearMissType(rng, vocab, s)
            name == "delete-key" -> deleteKey(rng, s)
            name == "duplicate-key" && n > 4 -> {
                // A duplicated key is a real emitter defect and a classic cross-host parser
                // divergence (first-wins vs last-wins vs refuse). Fuzzing it for escaping
                // throwables is in scope here; asserting which behaviour is correct is not.
                val i = s.indexOf('"')
                val j = s.indexOf(',')
                if (i >= 0 && j > i) s.substring(0, j + 1) + s.substring(i, j) + "," + s.substring(j + 1) else s
            }
            name == "escape-injection" && n > 0 -> {
                val i = rng.next(n)
                s.substring(0, i) + rng.pick(ESCAPE_PAYLOADS) + s.substring(i)
            }
            name == "number-swap" -> numberSwap(rng, s)
            name == "prefix-junk" -> {
                val junk = StringBuilder()
                repeat(rng.range(1, 16)) { junk.append(rng.pick(HOSTILE_CHARS)) }
                junk.toString() + s
            }
            name == "suffix-junk" -> {
                val junk = StringBuilder()
                repeat(rng.range(1, 16)) { junk.append(rng.pick(HOSTILE_CHARS)) }
                s + junk.toString()
            }
            else -> s + rng.pick(HOSTILE_CHARS)
        }
    return name to truncateTo(result, cfg.maxPayloadChars)
}

// --------------------------------------------------------------------------- //
// Structure-aware generation
// --------------------------------------------------------------------------- //

private fun genValue(rng: Rng, depth: Int, out: StringBuilder, vocab: List<String>, cfg: FuzzConfig) {
    if (out.length > cfg.maxPayloadChars) {
        out.append('0')
        return
    }
    if (depth == 0) {
        out.append(rng.pick(SCALAR_LITERALS))
        return
    }
    when (rng.next(12)) {
        0, 1, 2, 3 -> out.append(rng.pick(SCALAR_LITERALS))
        4, 5, 6, 7 -> {
            out.append('{')
            val n = rng.range(0, 5)
            for (i in 0 until n) {
                if (i > 0) out.append(',')
                out.append('"').append(rng.pick(WIRE_KEYS)).append("\":")
                genValue(rng, depth - 1, out, vocab, cfg)
            }
            out.append('}')
        }
        8, 9, 10 -> {
            out.append('[')
            val n = rng.range(0, 5)
            for (i in 0 until n) {
                if (i > 0) out.append(',')
                genValue(rng, depth - 1, out, vocab, cfg)
            }
            out.append(']')
        }
        else -> {
            // A plausible node shell around a wrong interior: the shape that gets furthest into
            // the typed decoders before it fails, and so the one most likely to reach code a
            // shallow syntax reject never does.
            out.append("{\"id\":\"g\",\"kind\":{\"\$type\":\"")
            out.append(nearMiss(rng, rng.pick(vocab)))
            out.append("\",\"").append(rng.pick(WIRE_KEYS)).append("\":")
            genValue(rng, depth - 1, out, vocab, cfg)
            out.append("}}")
        }
    }
}

/** A hostile NUMBER lexeme in a typed numeric position - the H-30 family, stated as a generator. */
private fun genNumberGrammar(rng: Rng): String {
    val lex = rng.pick(NUMBER_LEXEMES)
    return when (rng.next(7)) {
        // An INT slot reached through a Static envelope - the corpus's own reject-binding-int shape.
        0 ->
            "{\"id\":\"n1\",\"kind\":{\"\$type\":\"Tabs\",\"activeIndex\":{\"\$type\":\"Static\",\"value\":$lex}," +
                "\"children\":[{\"id\":\"m1\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"x\"}}]}}"
        // A bare INT slot on the node kind itself, with no envelope between reader and slot.
        1 -> "{\"id\":\"a\",\"kind\":{\"\$type\":\"Heading\",\"level\":$lex,\"text\":\"x\",\"variant\":\"Standard\"}}"
        // A FLOAT slot, which legitimately accepts the three sentinel STRINGS an int slot must not.
        2 ->
            "{\"id\":\"m\",\"kind\":{\"\$type\":\"Metric\",\"label\":\"L\"," +
                "\"value\":{\"\$type\":\"Static\",\"value\":$lex}}}"
        3 -> lex
        4 -> "[$lex]"
        5 -> "{\"id\":$lex,\"kind\":{\"\$type\":\"Markdown\",\"text\":\"x\"}}"
        else -> "{\"a\":$lex,\"b\":[$lex,$lex],\"c\":{\"d\":$lex}}"
    }
}

/** An escape payload in a string position - the unescape path's own family. */
private fun genEscape(rng: Rng): String {
    val p = rng.pick(ESCAPE_PAYLOADS)
    return when (rng.next(5)) {
        0 -> "{\"id\":\"a\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"$p\"}}"
        1 -> "{\"id\":\"$p\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"x\"}}"
        2 -> "\"$p\""
        3 -> "{\"$p\":\"$p\"}"
        else -> "{\"id\":\"a\",\"kind\":{\"\$type\":\"$p\"}}"
    }
}

/**
 * Depth, width and string length taken past the [WireLimits] bounds. Every payload is assembled as
 * TEXT: building one as a nested value would blow the harness's own stack while CONSTRUCTING the
 * input, which proves nothing about the decoder.
 */
private fun genPathological(rng: Rng, cfg: FuzzConfig): String {
    val cap = cfg.maxPayloadChars
    return when (rng.next(9)) {
        0 -> {
            val n = minOf(cap / 2, rng.range(64, 200_000))
            "[".repeat(n) + "]".repeat(n)
        }
        1 -> {
            val n = minOf(cap / 6, rng.range(64, 100_000))
            "{\"a\":".repeat(n) + "1" + "}".repeat(n)
        }
        // Unterminated as well as over-deep: the depth guard must fire on the way DOWN, before
        // truncation is ever reached.
        2 -> "[".repeat(minOf(cap / 2, rng.range(64, 200_000)))
        3 -> {
            // Deep NODE nesting rather than deep JSON - crosses MAX_NODE_DEPTH while staying far
            // inside MAX_JSON_DEPTH, so it is genuinely the node guard answering.
            var acc = "{\"id\":\"leaf\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"x\"}}"
            val tail =
                "],\"layout\":{\"\$type\":\"Flex\",\"direction\":\"Vertical\",\"wrap\":false},\"role\":\"Group\"}}"
            for (i in 1..rng.range(2, 400)) {
                if (acc.length >= cap) break
                acc = "{\"id\":\"n$i\",\"kind\":{\"\$type\":\"Box\",\"children\":[$acc$tail"
            }
            acc
        }
        4 -> {
            val n = minOf(cap / 2, rng.range(1000, 200_000))
            "{\"id\":\"a\",\"kind\":[" + "1,".repeat(n) + "1]}"
        }
        5 -> {
            val n = minOf(cap, rng.range(1000, 1_200_000))
            "{\"id\":\"a\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"" + "x".repeat(n) + "\"}}"
        }
        6 -> {
            // Escape-heavy: nearly every character an escape, so the unescape path does the work
            // rather than the structural walk. Half of them lone surrogates.
            val n = minOf(cap / 8, rng.range(500, 100_000))
            "{\"id\":\"a\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"" +
                (if (rng.boolean()) "\\uD800" else "\\u0041").repeat(n) + "\"}}"
        }
        7 -> {
            val n = minOf(cap / 8, rng.range(500, 50_000))
            (0 until n).joinToString(",", "{", "}") { "\"k$it\":1" }
        }
        else -> {
            // A long run of number lexemes, so the scanner is the hot path.
            val n = minOf(cap / 8, rng.range(500, 50_000))
            "[" + (0 until n).joinToString(",") { "1e30" } + "]"
        }
    }
}

private class Generated(val payload: String, val origin: String, val family: String)

/**
 * Deterministic in `(seed, iteration, cfg)` - the replay contract. Every branch draws from the same
 * [Rng], so ADDING a family renumbers the stream; that is why a reported find carries its payload
 * too and replay is the backstop rather than the primary record.
 */
private fun generate(
    rng: Rng,
    seeds: List<String>,
    vocab: List<String>,
    cfg: FuzzConfig,
    iteration: Int,
): Generated {
    if (iteration % cfg.heavyEveryN == 0) {
        return Generated(genPathological(rng, cfg), "pathological", "pathological")
    }
    when (rng.next(14)) {
        0, 1 -> {
            val out = StringBuilder()
            genValue(rng, rng.range(1, 6), out, vocab, cfg)
            return Generated(out.toString(), "structured-generation", "structured")
        }
        2 -> {
            val out = StringBuilder()
            repeat(rng.range(0, 200)) { out.append(rng.pick(HOSTILE_CHARS)) }
            return Generated(out.toString(), "raw-junk", "raw-junk")
        }
        3 -> {
            // Crossover: prefix of one seed, suffix of another. Produces half-valid documents no
            // single-seed mutation reaches.
            val a = rng.pick(seeds)
            val c = rng.pick(seeds)
            val joined = a.substring(0, rng.next(a.length + 1)) + c.substring(rng.next(c.length + 1))
            return Generated(truncateTo(joined, cfg.maxPayloadChars), "crossover", "crossover")
        }
        4, 5 -> return Generated(genNumberGrammar(rng), "number-grammar", "number-grammar")
        6 -> return Generated(genEscape(rng), "string-escape", "string-escape")
        else -> Unit
    }
    var acc = rng.pick(seeds)
    val names = mutableListOf<String>()
    repeat(rng.range(1, 4)) {
        val (name, next) = mutateOnce(rng, vocab, cfg, acc)
        acc = next
        names.add(name)
    }
    return Generated(acc, "mutation:" + names.joinToString("+"), "mutation")
}

// --------------------------------------------------------------------------- //
// Subjects, verdicts, and the invariant check
// --------------------------------------------------------------------------- //

/**
 * One decode entry point, or a deliberately-broken stand-in.
 *
 * [decode] is typed `(String) -> Any?` on purpose: it is what lets the go-red self-tests below
 * drive the IDENTICAL machinery with a mutant that throws an `IllegalStateException`. A fuzz
 * harness nobody has ever seen fail is decoration.
 */
private class Subject(
    val name: String,
    /** True when the throwable is one of the TYPED refusals this entry point is contracted to raise. */
    val typed: (Throwable) -> Boolean,
    val decode: (String) -> Any?,
    /**
     * Re-serialise an accepted value, or `null` when this entry point has NO encoder - in which
     * case invariant 3 does not apply and is not asserted. See the header: the absence of a
     * canonical node encoder on this surface is the charter, not a gap.
     */
    val canonicalise: ((Any?) -> String)? = null,
)

private fun refusalCode(t: Throwable): String =
    when (t) {
        is FuaranDecodeException -> t.code
        is JsonSyntaxException -> "reader:INVALID_JSON"
        is JsonLimitException -> "reader:LIMIT_EXCEEDED"
        else -> t::class.java.simpleName
    }

private fun describeThrowable(t: Throwable): String {
    val where = t.stackTrace.firstOrNull { it.className.startsWith("fuaran.ui.") }
    return "${t::class.java.name}: ${t.message}" + if (where != null) " at $where" else ""
}

private sealed interface Outcome

private class Refused(val code: String) : Outcome

private class Escaped(val t: Throwable) : Outcome

private class Accepted(val canonical: String?, val redecodedCode: String?, val second: String?) : Outcome

private fun runSubject(subject: Subject, input: String): Outcome {
    val value =
        try {
            subject.decode(input)
        } catch (t: Throwable) {
            return if (subject.typed(t)) Refused(refusalCode(t)) else Escaped(t)
        }
    val canon = subject.canonicalise ?: return Accepted(null, null, null)
    val first = canon(value)
    val again =
        try {
            subject.decode(first)
        } catch (t: Throwable) {
            return Accepted(first, if (subject.typed(t)) refusalCode(t) else t::class.java.name, null)
        }
    return Accepted(first, null, canon(again))
}

private class Measured(val kind: String, val detail: String, val elapsedMs: Double)

/**
 * Run one input through one subject and judge it against every invariant that applies to it.
 *
 * Every throwable is caught HERE and nowhere else, which is what makes "nothing escapes but the
 * typed error" a measured property rather than a hope. `refused` and `clean` are both PASSES - a
 * fuzz harness that treated refusal as failure would be asserting the opposite of the claim under
 * test.
 */
private fun check(subject: Subject, softTimeMs: Double, input: String): Measured {
    val started = System.nanoTime()
    val outcome =
        try {
            runSubject(subject, input)
        } catch (t: Throwable) {
            // An encoder that throws is a totality breach too, so it lands in the same bucket.
            Escaped(t)
        }
    val elapsed = (System.nanoTime() - started) / 1_000_000.0

    if (outcome is Escaped) return Measured("escaped-throwable", describeThrowable(outcome.t), elapsed)
    // Order matters: an input that both ran long AND broke another invariant is reported as the
    // time breach, because that is the one an operator has to act on first.
    if (elapsed > softTimeMs) {
        return Measured("timed-out", "the decoder returned only after " + "%.0f".format(elapsed) + " ms", elapsed)
    }
    if (outcome is Refused) return Measured("refused", outcome.code, elapsed)
    val a = outcome as Accepted
    if (a.redecodedCode != null) {
        return Measured("canonical-refused", "the decoder's own output re-decodes as ${a.redecodedCode}", elapsed)
    }
    if (a.canonical == null) return Measured("clean", "", elapsed)
    if (a.second == null) return Measured("canonical-refused", "the decoder produced no re-decodable output", elapsed)
    return if (a.second == a.canonical) {
        Measured("clean", "", elapsed)
    } else {
        Measured(
            "fixed-point-broken",
            "first canonical form ${a.canonical.length} chars, second ${a.second.length}",
            elapsed,
        )
    }
}

// --------------------------------------------------------------------------- //
// Minimiser
// --------------------------------------------------------------------------- //

/**
 * Delete-and-retry: cut a chunk, keep the cut when the verdict KIND is unchanged, otherwise move
 * on; halve the chunk when a pass reduced nothing. Bounded by an attempt budget AND a wall clock,
 * because the inputs worth minimising are exactly the ones that are slow to classify.
 */
private fun minimise(classify: (String) -> String, target: String, input: String): String {
    val started = System.nanoTime()
    fun outOfTime() = (System.nanoTime() - started) / 1_000_000_000.0 > 10.0
    var best = input
    var granularity = 2
    var budget = 400
    while (budget > 0 && !outOfTime()) {
        val chunk = maxOf(1, best.length / granularity)
        var reduced = false
        var i = 0
        while (i < best.length && budget > 0 && !outOfTime()) {
            val take = minOf(chunk, best.length - i)
            val candidate = best.substring(0, i) + best.substring(i + take)
            budget--
            if (candidate.isNotEmpty() && classify(candidate) == target) {
                best = candidate
                reduced = true
            } else {
                i += take
            }
        }
        if (reduced) {
            granularity = maxOf(2, granularity / 2)
        } else if (chunk > 1) {
            granularity *= 2
        } else {
            break
        }
    }
    return best
}

/** A copy-pasteable rendering: every control char, backslash, quote and non-ASCII code unit escaped. */
private fun quoteForReport(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code in 0x20..0x7e -> sb.append(c)
            else -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
        }
    }
    sb.append('"')
    return sb.toString()
}

// --------------------------------------------------------------------------- //
// The run
// --------------------------------------------------------------------------- //

private class Counterexample(
    val subject: String,
    val iteration: Int,
    val origin: String,
    val kind: String,
    val detail: String,
    val payload: String,
    val minimised: String,
)

private fun describeCounterexample(c: Counterexample, seed: Long, configName: String): String =
    buildString {
        appendLine("  subject: ${c.subject}")
        appendLine("  seed: $seed, iteration: ${c.iteration}, config: $configName")
        appendLine("  origin: ${c.origin}")
        appendLine("  verdict: ${c.kind} - ${c.detail}")
        appendLine("  length: ${c.payload.length} chars (minimised to ${c.minimised.length})")
        appendLine("  minimised input: ${quoteForReport(c.minimised)}")
        if (c.minimised != c.payload) {
            val head = if (c.payload.length > 400) c.payload.substring(0, 400) else c.payload
            appendLine("  original input (first ${head.length}): ${quoteForReport(head)}")
        }
        appendLine(
            "  Counterexample policy: fix the decoder, then land the input as a permanent reject " +
                "fixture in the shared corpus, so every conformant host inherits the case rather " +
                "than only this one.",
        )
    }

private class RunStats {
    var iterations = 0
    var inputs = 0
    var seedCount = 0
    val rejectCodes = sortedMapOf<String, Int>()
    val families = sortedSetOf<String>()
    val acceptedBySubject = sortedMapOf<String, Int>()
    var maxDecodeMs = 0.0
    var elapsedSeconds = 0.0
    val counterexamples = mutableListOf<Counterexample>()
}

/**
 * `subjects` is a parameter precisely so the go-red self-tests drive the IDENTICAL machinery with
 * broken stand-ins.
 */
private fun run(
    subjects: List<Subject>,
    softTimeMs: Double,
    cfg: FuzzConfig,
    seed: Long,
    iterations: Int,
    seeds: List<String>,
    vocab: List<String>,
    minimiseFinds: Boolean,
): RunStats {
    val rng = Rng(seed)
    val started = System.nanoTime()
    val stats = RunStats()
    stats.seedCount = seeds.size
    for (s in subjects) stats.acceptedBySubject[s.name] = 0

    for (i in 1..iterations) {
        val g = generate(rng, seeds, vocab, cfg, i)
        stats.families.add(g.family)
        for (subject in subjects) {
            val m = check(subject, softTimeMs, g.payload)
            stats.inputs++
            if (m.elapsedMs > stats.maxDecodeMs) stats.maxDecodeMs = m.elapsedMs
            when (m.kind) {
                "refused" -> stats.rejectCodes[m.detail] = (stats.rejectCodes[m.detail] ?: 0) + 1
                "clean" -> stats.acceptedBySubject[subject.name] = (stats.acceptedBySubject[subject.name] ?: 0) + 1
                else -> {
                    val minimised =
                        if (minimiseFinds) {
                            minimise({ candidate -> check(subject, softTimeMs, candidate).kind }, m.kind, g.payload)
                        } else {
                            g.payload
                        }
                    stats.counterexamples.add(
                        Counterexample(subject.name, i, g.origin, m.kind, m.detail, g.payload, minimised),
                    )
                }
            }
        }
        stats.iterations = i
    }
    stats.elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
    return stats
}

/**
 * The one-line human summary, printed on every run, pass or fail: a harness whose output is only
 * visible when it fails cannot be checked for having quietly stopped generating anything.
 */
private fun summarise(stats: RunStats, seed: Long, cfg: FuzzConfig): String {
    val perIteration = if (stats.iterations == 0) 0 else stats.inputs / stats.iterations
    val accepted = stats.acceptedBySubject.entries.joinToString(" ") { "${it.key}=${it.value}" }
    val codes = stats.rejectCodes.entries.joinToString(" ") { "${it.key}=${it.value}" }
    return "seed=$seed config=${cfg.name} ${stats.inputs} inputs (${stats.iterations} iterations x " +
        "$perIteration entry points) in " + "%.1f".format(stats.elapsedSeconds) + " s; " +
        "seeds=${stats.seedCount}; families=${stats.families.size} [${stats.families.joinToString(",")}]; " +
        "accepted [$accepted]; refused [$codes]; counterexamples=${stats.counterexamples.size}; " +
        "max decode " + "%.0f".format(stats.maxDecodeMs) + " ms"
}

// --------------------------------------------------------------------------- //
// The entry points under test
// --------------------------------------------------------------------------- //

/**
 * `decodeNode` is contracted to raise `FuaranDecodeException` and NOTHING else - it catches the
 * reader's two typed failures itself and maps them onto the canonical `INVALID_JSON` /
 * `LIMIT_EXCEEDED` codes. So the reader's own exception types are NOT in this subject's typed set:
 * one escaping here would be a real find, and exactly the shape the pre-1540 number-grammar defect
 * took one step further on (a `NumberFormatException` from a typed slot).
 */
private fun nodeSubject() =
    Subject(
        name = "decodeNode",
        typed = { it is FuaranDecodeException },
        decode = { decodeNode(it) },
        canonicalise = null,
    )

/**
 * The READER, fuzzed as its own entry point rather than only through the decoder above. It is the
 * surface Phase 1540 hardened, it is public API, and it is the one half of this host that HAS an
 * encoder - so it is where invariant 3 is actually exercised.
 */
private fun readerSubject() =
    Subject(
        name = "Json.parse",
        typed = { it is JsonSyntaxException || it is JsonLimitException },
        decode = { Json.parse(it) },
        canonicalise = { (it as JsonValue).encode() },
    )

private fun realSubjects() = listOf(nodeSubject(), readerSubject())

// --------------------------------------------------------------------------- //
// The gate's constants
// --------------------------------------------------------------------------- //

private const val DEFAULT_SEED = 1023L

/**
 * The per-input wall-clock budget. Generous deliberately: this runs on a cold JVM, so the first few
 * hundred inputs are interpreted before the JIT has seen the reader at all, and a budget tuned to
 * steady-state throughput would go red on the warmup rather than on the decoder. A flaky budget is
 * worse than a loose one - it gets raised in a hurry by whoever it blocks, and nobody records why.
 * What it is FOR is unbounded work, which is orders of magnitude away from this figure.
 */
private const val SOFT_TIME_BUDGET_MS = 5_000.0

// --------------------------------------------------------------------------- //
// Go-red: the harness fails when the decoder is broken
// --------------------------------------------------------------------------- //

private class SelfTest {
    var passed = 0
    val failures = mutableListOf<String>()

    fun check(name: String, body: () -> Unit) {
        try {
            body()
            passed++
            println("  $name: OK")
        } catch (t: Throwable) {
            failures.add("$name - ${t::class.java.simpleName}: ${t.message}")
            println("  $name: FAIL - ${t.message}")
        }
    }
}

/** Fires only on inputs whose length is divisible by `n` - PARTIAL by design; see the inverse pin. */
private fun everyNth(n: Int, name: String, broken: (String) -> Any?): Subject =
    Subject(
        name = name,
        typed = { it is JsonSyntaxException },
        decode = { input ->
            if (input.length % n == 0) broken(input) else throw JsonSyntaxException("deliberate refusal")
        },
    )

private fun goRedSelfTests(seeds: List<String>, vocab: List<String>, seed: Long): SelfTest {
    val t = SelfTest()

    // The slow mutant is measured against a DELIBERATELY TIGHT budget rather than the shipped one.
    // Sleeping past the real budget would cost seconds per firing - the sort of cost that gets a
    // go-red test deleted rather than fixed. What is under test is the harness's ability to see a
    // decode that returned past ITS budget, and that is exactly as true at 5 ms.
    val tight = 5.0
    var flip = 0

    val mutants: List<Pair<Subject, Double>> =
        listOf(
            // The one the phase asks for by name: an arbitrary runtime exception escaping a
            // decoder whose contract is that it refuses in one typed shape.
            everyNth(3, "mutant:throws-IllegalState") {
                throw IllegalStateException("deliberate: the decoder let an untyped exception escape")
            } to SOFT_TIME_BUDGET_MS,
            // An ERROR rather than an Exception. `StackOverflowError` from an unbounded recursive
            // descent is the single most likely real defect in a hand-rolled reader, and a harness
            // catching only `Exception` would be blind to it while passing every test above.
            everyNth(5, "mutant:throws-StackOverflowError") {
                throw StackOverflowError("deliberate: unbounded recursion")
            } to SOFT_TIME_BUDGET_MS,
            everyNth(7, "mutant:slow") {
                Thread.sleep(25)
                JsonNull
            } to tight,
            Subject(
                name = "mutant:canonical-refused",
                typed = { it is JsonSyntaxException },
                decode = { input ->
                    if (input.length % 11 == 0) JsonNull else throw JsonSyntaxException("deliberate refusal")
                },
                // Accepts, then emits something its own reader cannot read back.
                canonicalise = { "{" },
            ) to SOFT_TIME_BUDGET_MS,
            Subject(
                name = "mutant:fixed-point-broken",
                typed = { it is JsonSyntaxException },
                decode = { input ->
                    if (input.length % 13 == 0) JsonNull else throw JsonSyntaxException("deliberate refusal")
                },
                // Two canonical forms for one value: the shape a non-idempotent encoder takes.
                canonicalise = {
                    flip++
                    if (flip % 2 == 1) "{\"a\":1}" else "{\"a\":2}"
                },
            ) to SOFT_TIME_BUDGET_MS,
        )

    for ((mutant, budget) in mutants) {
        t.check("go-red/${mutant.name}") {
            val stats = run(listOf(mutant), budget, BOUNDED_CONFIG, seed, 200, seeds, vocab, false)
            if (stats.counterexamples.isEmpty()) {
                error("${mutant.name} produced no counterexample - the harness cannot see this defect class")
            }
            // The inverse pin, in the same place as the claim it qualifies. Without it a mutant
            // that broke EVERY input would satisfy the assertion above while proving only that the
            // harness reports what it is handed.
            if (stats.counterexamples.size >= stats.inputs) {
                error("${mutant.name} broke EVERY input - it proves nothing about the harness's discrimination")
            }
        }
    }

    t.check("go-red/a-well-formed-node-is-clean") {
        // The floor under everything above: the machinery must call a GOOD input good. A harness
        // that reported every input as a counterexample would pass every go-red test in this file.
        val m = check(nodeSubject(), SOFT_TIME_BUDGET_MS, WELL_FORMED_NODE)
        if (m.kind != "clean") error("a well-formed node was judged '${m.kind}' (${m.detail})")
    }
    t.check("go-red/a-well-formed-document-round-trips") {
        val m = check(readerSubject(), SOFT_TIME_BUDGET_MS, WELL_FORMED_NODE)
        if (m.kind != "clean") error("a well-formed document was judged '${m.kind}' (${m.detail})")
    }
    t.check("go-red/a-malformed-document-is-a-refusal-not-a-find") {
        val m = check(nodeSubject(), SOFT_TIME_BUDGET_MS, "{\"id\":\"x\",")
        if (m.kind != "refused") error("a syntax error was judged '${m.kind}', not a refusal")
        if (m.detail != FuaranDecodeException.INVALID_JSON) error("expected INVALID_JSON, got ${m.detail}")
    }
    t.check("go-red/the-minimiser-shrinks-and-preserves-the-verdict") {
        // A minimiser that returned its input unchanged would make every reported find unreadable
        // while every other assertion here still passed.
        val padded = " ".repeat(500) + "@" + " ".repeat(500)
        val classify: (String) -> String = { if (it.contains("@")) "target" else "other" }
        val got = minimise(classify, "target", padded)
        if (classify(got) != "target") error("the minimiser lost the verdict it was preserving")
        if (got.length >= padded.length) error("the minimiser reduced nothing (${got.length} of ${padded.length})")
    }
    t.check("go-red/the-generator-is-deterministic-in-its-seed") {
        // Replay is the whole point of the seed, and nothing else in this file would notice it
        // breaking: a non-deterministic stream still passes every invariant.
        fun stream(s: Long): List<String> {
            val rng = Rng(s)
            return (1..50).map { generate(rng, seeds, vocab, BOUNDED_CONFIG, it).payload }
        }
        if (stream(seed) != stream(seed)) error("two runs at the same seed generated different streams")
        if (stream(seed) == stream(seed + 1)) {
            error("two DIFFERENT seeds generated the same stream - the seed is not read")
        }
    }

    return t
}

// --------------------------------------------------------------------------- //
// The targeted corpus assertion - the reject-binding-int vectors
// --------------------------------------------------------------------------- //

/**
 * The `reject-binding-int-*` vectors, asserted DIRECTLY rather than only through the fuzz.
 *
 * They are the corpus's statement of what Phase 1540 changed: WIRE_FORMAT 7 gives an integer slot
 * no non-numeric form at all, so a JSON boolean and a non-finite sentinel string (which a FLOAT
 * slot legitimately accepts) must both be refused - with the code and the `$`-rooted path the
 * fixture declares, not merely with some refusal. A fuzz run cannot make that claim: it asserts
 * that nothing escapes untyped, which a decoder saturating `3000000000` to `Int.MAX_VALUE` would
 * satisfy perfectly while returning a number the document does not contain.
 *
 * Read from the fixture and the manifest rather than restated here, so the expectation cannot drift
 * from the corpus.
 */
private fun assertRejectBindingIntVectors(corpus: File, runner: SelfTest) {
    val manifest = Json.parse(File(corpus, "manifest.json").readText()) as JsonObject
    val fixtures = (manifest["fixtures"] as JsonArray).items.map { it as JsonObject }
    val targeted = fixtures.filter { ((it["id"] as? JsonString)?.value ?: "").startsWith("reject-binding-int-") }
    if (targeted.isEmpty()) {
        runner.check("reject-binding-int/enumerated") {
            error("the manifest enumerated NO reject-binding-int-* vectors - this assertion checked nothing")
        }
        return
    }
    for (fx in targeted) {
        val id = (fx["id"] as JsonString).value
        runner.check("reject-binding-int/$id") {
            val json = File(corpus, (fx["inputFile"] as JsonString).value).readText()
            val expectedCode = (fx["expectedErrorCode"] as? JsonString)?.value ?: error("no expectedErrorCode")
            val expectedPath = (fx["expectedPath"] as? JsonString)?.value ?: "$"
            val e =
                try {
                    decodeNode(json)
                    error(
                        "decode ACCEPTED an integer slot the corpus pins as refused " +
                            "(expected $expectedCode at $expectedPath)",
                    )
                } catch (e: FuaranDecodeException) {
                    e
                }
            if (e.code != expectedCode) {
                error("wrong code - expected $expectedCode, got ${e.code} at ${e.path}: ${e.detail}")
            }
            if (!e.path.startsWith(expectedPath)) error("wrong path - expected prefix $expectedPath, got ${e.path}")
        }
    }
    // The CLASS, not only the stored instances: an out-of-range integer is refused rather than
    // saturated, and a fractional one rather than truncated. Neither has a corpus fixture, and both
    // were live defects before Phase 1540.
    for ((lexeme, why) in
        listOf(
            "3000000000" to "past Int.MAX_VALUE - the saturating narrowing this replaced returned 2147483647",
            "-3000000000" to "past Int.MIN_VALUE",
            "1.5" to "fractional - the narrowing this replaced returned 1",
        )
    ) {
        runner.check("reject-binding-int/out-of-range-$lexeme") {
            val json =
                "{\"id\":\"n1\",\"kind\":{\"\$type\":\"Tabs\",\"activeIndex\":{\"\$type\":\"Static\"," +
                    "\"value\":$lexeme},\"children\":[{\"id\":\"m1\",\"kind\":{\"\$type\":\"Markdown\"," +
                    "\"text\":\"x\"}}]}}"
            val e =
                try {
                    decodeNode(json)
                    error("an integer slot ACCEPTED '$lexeme' ($why)")
                } catch (e: FuaranDecodeException) {
                    e
                }
            if (e.code != FuaranDecodeException.WRONG_TYPE) error("expected WRONG_TYPE, got ${e.code}")
            if (e.path != "\$.kind.activeIndex") error("expected \$.kind.activeIndex, got ${e.path}")
        }
    }
}

// --------------------------------------------------------------------------- //
// The gate
// --------------------------------------------------------------------------- //

private fun envLong(name: String, fallback: Long): Long {
    val raw = System.getenv(name) ?: return fallback
    if (raw.isBlank()) return fallback
    return raw.trim().toLongOrNull() ?: error("$name: '$raw' is not an integer")
}

private fun envInt(name: String, fallback: Int): Int {
    val raw = System.getenv(name) ?: return fallback
    if (raw.isBlank()) return fallback
    val n = raw.trim().toIntOrNull() ?: error("$name: '$raw' is not an integer")
    if (n <= 0) error("$name: '$raw' is not a positive integer")
    return n
}

fun main() {
    val corpus = locateFuzzCorpus()
    if (corpus == null) {
        // A missing corpus has two very different meanings, and collapsing them into one clean
        // SKIP would be a vacuous green on a checkout that plainly HAS a corpus. The same
        // discrimination the corpus harness makes, for the same reason.
        val sibling = fuzzCrossHostSibling()
        if (sibling != null) {
            val (name, at) = sibling
            println(
                "FAIL: cross-host checkout detected ($name/ is present under ${at.path}) but the " +
                    "wire-format-fixtures corpus is at none of the paths tried.",
            )
            println("  FUARAN_CORPUS=${System.getenv("FUARAN_CORPUS") ?: "<unset>"}")
            for (c in FUZZ_CORPUS_CANDIDATES) println("  tried: ${File(c).absolutePath}")
            kotlin.system.exitProcess(1)
        }
    }

    val seeds = loadFuzzSeeds(corpus)
    val vocab = loadFuzzVocabulary(corpus)
    val long = System.getenv("FUARAN_FUZZ_LONG") == "1"
    val cfg = if (long) LONG_CONFIG else BOUNDED_CONFIG
    val seed = envLong("FUARAN_FUZZ_SEED", DEFAULT_SEED)
    val iterations = envInt("FUARAN_FUZZ_ITERATIONS", if (long) 250_000 else 50_000)

    println("Corpus: ${corpus?.absolutePath ?: "<absent - built-in seeds only>"}")
    println("Seeds: ${seeds.size} (${BUILTIN_SEEDS.size} built in); vocabulary: ${vocab.size} kinds")

    // ORDER: the harness proves itself, then the targeted vectors, then the fuzz. A broken harness
    // reported after a long clean-looking run is a result nobody re-reads.
    println("")
    println("-- go-red self-tests (the harness must be able to FAIL) --")
    val self = goRedSelfTests(seeds, vocab, seed)

    println("")
    if (corpus != null) {
        println("-- the corpus reject-binding-int vectors, asserted directly --")
        assertRejectBindingIntVectors(corpus, self)
    } else {
        println("NOT CHECKED: the reject-binding-int vectors need the corpus; it is absent here.")
    }

    if (self.failures.isNotEmpty()) {
        println("")
        println("FAIL: ${self.failures.size} of ${self.failures.size + self.passed} harness/vector checks failed:")
        for (f in self.failures) println("  - $f")
        kotlin.system.exitProcess(1)
    }
    println("  ${self.passed} harness/vector checks passed.")

    println("")
    println("-- the refusal contract over generated hostile input --")
    val subjects = realSubjects()
    val stats = run(subjects, SOFT_TIME_BUDGET_MS, cfg, seed, iterations, seeds, vocab, true)
    println("  [decoder-fuzz] ${summarise(stats, seed, cfg)}")

    if (stats.counterexamples.isNotEmpty()) {
        println("")
        println(
            "FAIL: ${stats.counterexamples.size} counterexample(s) - the decoder's refusal contract " +
                "does not hold over generated hostile input.",
        )
        println("")
        for (c in stats.counterexamples.take(5)) println(describeCounterexample(c, seed, cfg.name))
        kotlin.system.exitProcess(1)
    }

    // A run that generated nothing would report zero counterexamples and look identical to a clean
    // one. Pin the work actually done.
    val problems = mutableListOf<String>()
    if (stats.iterations != iterations) problems.add("ran ${stats.iterations} iterations, expected $iterations")
    if (stats.inputs != iterations * subjects.size) {
        problems.add("judged ${stats.inputs} inputs, expected ${iterations * subjects.size}")
    }
    if (stats.rejectCodes.isEmpty()) problems.add("no generated input was REFUSED - the stream is not hostile")
    // Acceptance is pinned per SUBJECT that carries the fixed-point invariant: a stream that only
    // ever refuses never exercises it. `decodeNode` has no encoder and so no fixed point, so its
    // acceptance count is REPORTED in the summary rather than asserted here - asserting it would be
    // pinning a coverage figure the invariant set does not rest on.
    for (s in subjects.filter { it.canonicalise != null }) {
        if ((stats.acceptedBySubject[s.name] ?: 0) == 0) {
            problems.add("no input was ACCEPTED by ${s.name} - the fixed-point invariant was never exercised")
        }
    }
    if (stats.families.size < 6) problems.add("only ${stats.families.size} generation families produced input")
    if (problems.isNotEmpty()) {
        println("")
        println("FAIL: the run did not do the work it claims:")
        for (p in problems) println("  - $p")
        kotlin.system.exitProcess(1)
    }

    println("")
    println(
        "PASS: seed=$seed iterations=$iterations subjects=${subjects.size} " +
            "families=${stats.families.size} counterexamples=0",
    )
}
