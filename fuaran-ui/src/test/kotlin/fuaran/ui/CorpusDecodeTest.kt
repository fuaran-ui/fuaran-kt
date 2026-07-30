// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

import java.io.File

/**
 * The corpus render-coverage harness for Phase 542.
 *
 * The bar: every **node round-trip** fixture in the shared `wire-format-fixtures/`
 * corpus decodes into the sealed model with **zero fallback-arm hits** (an unmodelled
 * `$type` throws [FuaranDecodeException]; there is no catch-all producing a generic
 * node). Coverage is reported per [NodeKind] discriminator. The harness locates the
 * corpus via `manifest.json` — the authoritative fixture enumeration — and **skips
 * cleanly** when the corpus is absent, so `fuaran-ui` stays standalone-testable.
 *
 * This is a plain-JVM `main`-driven runner rather than a Gradle/JUnit launch: the repo
 * builds with a bare `kotlinc`, no artefact resolution, and the exit code is the gate.
 */

private class Runner {
    var passed = 0
    var failed = 0
    val failures = mutableListOf<String>()

    fun check(name: String, body: () -> Unit) {
        try {
            body()
            passed++
        } catch (e: Throwable) {
            failed++
            failures.add("$name — ${e::class.simpleName}: ${e.message}")
        }
    }
}

private fun locateCorpus(): File? {
    System.getenv("FUARAN_CORPUS")?.let {
        val f = File(it)
        if (File(f, "manifest.json").isFile) return f
    }
    val candidates =
        listOf(
            "../wire-format-fixtures",
            "../../wire-format-fixtures",
            "wire-format-fixtures",
            "fuaran-kt/../wire-format-fixtures",
        )
    for (c in candidates) {
        val f = File(c)
        if (File(f, "manifest.json").isFile) return f
    }
    return null
}

private fun manifestNodeFixtures(manifestJson: String): List<Pair<String, String>> {
    val root = Json.parse(manifestJson) as JsonObject
    val fixtures = (root["fixtures"] as JsonArray).items
    val out = mutableListOf<Pair<String, String>>()
    for (f in fixtures) {
        val o = f as JsonObject
        val kind = (o["kind"] as? JsonString)?.value ?: continue
        if (kind != "node-round-trip") continue
        val id = (o["id"] as? JsonString)?.value ?: "?"
        val input = (o["inputFile"] as? JsonString)?.value ?: continue
        out.add(id to input)
    }
    return out
}

fun main() {
    val corpus = locateCorpus()
    if (corpus == null) {
        println("SKIP: wire-format-fixtures corpus not found (set FUARAN_CORPUS or run from the repo). Nothing to certify.")
        return
    }
    println("Corpus: ${corpus.absolutePath}")
    val manifest = File(corpus, "manifest.json").readText()
    val nodeFixtures = manifestNodeFixtures(manifest)
    val runner = Runner()
    val coverage = sortedMapOf<String, Int>()

    for ((id, input) in nodeFixtures) {
        runner.check("node-round-trip/$id") {
            val json = File(corpus, input).readText()
            val node = decodeNode(json)
            // Touch the exhaustive dispatch spine so every decoded kind is classified with no `else`.
            val category = node.kind.category()
            val disc = node.kind.discriminator()
            coverage[disc] = (coverage[disc] ?: 0) + 1
            if (category !in NodeCategory.entries) error("uncategorised kind $disc")
        }
    }

    // Default-deny proof: an unmodelled node kind must raise WRONG_NODE_KIND, never a fallback node.
    runner.check("default-deny/unknown-node-kind-throws") {
        val ok =
            try {
                decodeNode("{\"id\":\"x\",\"kind\":{\"\$type\":\"NotAKind\"}}")
                false
            } catch (e: FuaranDecodeException) {
                e.code == FuaranDecodeException.WRONG_NODE_KIND
            }
        if (!ok) error("expected WRONG_NODE_KIND for an unmodelled node kind")
    }
    // Default-deny proof: an unmodelled nested DU case must raise UNKNOWN_DU_CASE.
    runner.check("default-deny/unknown-binding-case-throws") {
        val ok =
            try {
                decodeNode("{\"id\":\"x\",\"kind\":{\"\$type\":\"Sparkline\",\"source\":{\"\$type\":\"Nope\"}}}")
                false
            } catch (e: FuaranDecodeException) {
                e.code == FuaranDecodeException.UNKNOWN_DU_CASE
            }
        if (!ok) error("expected UNKNOWN_DU_CASE for an unmodelled binding case")
    }
    // Empty-id proof.
    runner.check("default-deny/empty-node-id-throws") {
        val ok =
            try {
                decodeNode("{\"id\":\"\",\"kind\":{\"\$type\":\"Markdown\",\"text\":{\"\$type\":\"Literal\",\"text\":\"x\"}}}")
                false
            } catch (e: FuaranDecodeException) {
                e.code == FuaranDecodeException.EMPTY_NODE_ID
            }
        if (!ok) error("expected EMPTY_NODE_ID for an empty id")
    }

    // Phase 745 — the DateRange lenient spellings. This harness runs the
    // node-round-trip family ONLY, so the corpus's two `lenient-daterange-*`
    // fixtures are never reached here: without these checks those shapes stay
    // certified on the Swift and Python hosts and never on this one. All three
    // spellings must normalise to the SAME canonical bare `{from, to}` pair.
    fun dateRangeForm(valueJson: String): String =
        "{\"id\":\"f\",\"kind\":{\"\$type\":\"Form\",\"fields\":[{\"id\":\"stay\",\"kind\":{\"\$type\":\"DateRange\"," +
            "\"value\":$valueJson,\"variant\":\"Date\"},\"label\":\"Stay\",\"required\":false}]," +
            "\"onSubmit\":{\"\$type\":\"Dispatch\"},\"submitLabel\":\"Book\"}}"

    fun decodedPair(valueJson: String): Pair<String, String> {
        val node = decodeNode(dateRangeForm(valueJson))
        val field = (node.kind as Form).fields.single()
        val binding = (field.kind as DateRangeField).value
        val pair = (binding as StaticBinding).value as JsonObject
        return (pair["from"] as JsonString).value to (pair["to"] as JsonString).value
    }

    val canonicalPair = "2026-03-01" to "2026-03-08"
    runner.check("lenient/daterange-canonical-bare-object") {
        val got = decodedPair("{\"from\":\"2026-03-01\",\"to\":\"2026-03-08\"}")
        if (got != canonicalPair) error("expected $canonicalPair, got $got")
    }
    runner.check("lenient/daterange-bare-array") {
        val got = decodedPair("[\"2026-03-01\",\"2026-03-08\"]")
        if (got != canonicalPair) error("bare [from, to] array must normalise to the canonical pair; got $got")
    }
    runner.check("lenient/daterange-static-envelope") {
        val got = decodedPair("{\"\$type\":\"Static\",\"value\":{\"from\":\"2026-03-01\",\"to\":\"2026-03-08\"}}")
        if (got != canonicalPair) error("Static-enveloped pair must normalise to the canonical pair; got $got")
    }
    // The ordered-pair rule (WIRE_FORMAT: ordinal compare, no date parsing).
    runner.check("default-deny/daterange-unordered-throws") {
        val ok =
            try {
                decodedPair("{\"from\":\"2026-03-08\",\"to\":\"2026-03-01\"}")
                false
            } catch (e: FuaranDecodeException) {
                e.code == FuaranDecodeException.WRONG_TYPE
            }
        if (!ok) error("expected WRONG_TYPE for a literal pair whose from sorts after its to")
    }

    // Phase 750 — the TonedPill cell, the first cell kind this projection carries a
    // PAYLOAD for. The node-round-trip family above proves the canonical fixture
    // decodes without a fallback hit, but a coverage walk asks "did it decode", not
    // "did it decode CORRECTLY" — and the corpus's three `lenient-tonedpill-*` fixtures
    // plus its reject live in families this harness does not run, so without these
    // checks those shapes stay certified on the other hosts and never on this one.
    // (The Phase 745 DateRange block above is the same argument, same shape.)
    fun tonedPillColumn(kindJson: String): String =
        "{\"id\":\"g1\",\"kind\":{\"\$type\":\"DataGrid\",\"columns\":[{\"field\":\"status\"," +
            "\"kind\":$kindJson,\"label\":\"Status\"}]," +
            "\"source\":{\"\$type\":\"Static\",\"value\":\"<opaque>\"}}}"

    fun decodedCell(kindJson: String): CellKind =
        ((decodeNode(tonedPillColumn(kindJson)).kind) as DataGrid).columns.single().kind

    val warningOnDelayed = TonedPillCell("status", mapOf("Delayed" to ToneVariant.Warning))
    // All three tone-map field names, INCLUDING `tones` — the corpus fixture exercises
    // `toneMap` only, so a host that wired just the one it was shown is non-conformant
    // in a way no fixture would catch.
    for (alias in listOf("map", "toneMap", "tones")) {
        runner.check("lenient/tonedpill-$alias-alias") {
            val got = decodedCell("{\"\$type\":\"TonedPill\",\"field\":\"status\",\"$alias\":{\"Delayed\":\"Warning\"}}")
            if (got != warningOnDelayed) error("`$alias` must normalise to the canonical map; got $got")
        }
    }
    runner.check("lenient/tonedpill-pill-tag") {
        // The §16 coercion: a `Pill` tag CARRYING a tone map is the declarative case.
        val got = decodedCell("{\"\$type\":\"Pill\",\"field\":\"status\",\"map\":{\"Delayed\":\"Warning\"}}")
        if (got != warningOnDelayed) error("a Pill-tagged tone map must coerce to TonedPill; got $got")
    }
    runner.check("lenient/tonedpill-closure-pill-untouched") {
        // The other half of that coercion: it keys off the tone map, so an ordinary
        // closure `Pill` — which can never carry one — is left alone.
        val got = decodedCell("{\"\$type\":\"Pill\",\"labelFn\":\"<closure>\",\"toneFn\":\"<closure>\"}")
        if (got != PillCell) error("a closure Pill must stay PillCell; got $got")
    }
    runner.check("lenient/tonedpill-tone-aliases-inside-the-map") {
        val got =
            decodedCell(
                "{\"\$type\":\"TonedPill\",\"field\":\"s\",\"map\":{\"a\":\"Danger\",\"b\":\"Positive\",\"c\":\"Neutral\"}}",
            )
        val want =
            TonedPillCell(
                "s",
                mapOf("a" to ToneVariant.Critical, "b" to ToneVariant.Success, "c" to ToneVariant.Default),
            )
        if (got != want) error("the 3.6 tone aliases must apply inside the map; got $got")
    }
    runner.check("lenient/tonedpill-default-restores-the-identity") {
        // Absent, and an aliased `Neutral` (which normalises to Default) — both restore
        // the identity tone the wire omits.
        for (given in listOf("", "\"default\":\"Neutral\",")) {
            val got = decodedCell("{\"\$type\":\"TonedPill\",$given\"field\":\"s\",\"map\":{\"a\":\"Info\"}}")
            val want = TonedPillCell("s", mapOf("a" to ToneVariant.Info))
            if (got != want) error("`$given` must restore the identity default; got $got")
        }
        val kept = decodedCell("{\"\$type\":\"TonedPill\",\"default\":\"Subdued\",\"field\":\"s\",\"map\":{\"a\":\"Info\"}}")
        if (kept != TonedPillCell("s", mapOf("a" to ToneVariant.Info), ToneVariant.Subdued)) {
            error("a real default must survive; got $kept")
        }
    }
    runner.check("default-deny/tonedpill-unknown-tone-is-didactic") {
        val e =
            try {
                decodedCell("{\"\$type\":\"TonedPill\",\"field\":\"status\",\"map\":{\"Delayed\":\"Urgent\"}}")
                error("expected a refusal for a tone-map value outside ToneVariant")
            } catch (e: FuaranDecodeException) {
                e
            }
        if (e.code != FuaranDecodeException.UNKNOWN_DU_CASE) error("expected UNKNOWN_DU_CASE, got ${e.code}")
        // The offending KEY, not merely the map — "one of your tones is wrong" is not
        // an actionable report when the map has nine entries.
        if (e.path != "\$.kind.columns[0].kind.map.Delayed") error("expected the offending key's path, got ${e.path}")
        val message = e.message ?: ""
        if (!message.contains("Delayed") || !message.contains("Urgent")) {
            error("the message must name the offending key and value; got $message")
        }
        // All seven legal names, so the author can fix it from the message alone.
        for (tone in ToneVariant.entries) {
            if (!message.contains(tone.name)) error("the message must teach ${tone.name}; got $message")
        }
    }
    runner.check("default-deny/tonedpill-requires-field-and-map") {
        for ((kindJson, wantPath) in
            listOf(
                "{\"\$type\":\"TonedPill\",\"map\":{\"a\":\"Info\"}}" to "\$.kind.columns[0].kind.field",
                "{\"\$type\":\"TonedPill\",\"field\":\"s\"}" to "\$.kind.columns[0].kind.map",
            )
        ) {
            val e =
                try {
                    decodedCell(kindJson)
                    error("expected MISSING_FIELD for $kindJson")
                } catch (e: FuaranDecodeException) {
                    e
                }
            if (e.code != FuaranDecodeException.MISSING_FIELD) error("expected MISSING_FIELD, got ${e.code}")
            if (e.path != wantPath) error("expected $wantPath, got ${e.path}")
        }
    }

    println()
    println("Per-NodeKind coverage (${coverage.size} distinct kinds across ${nodeFixtures.size} node fixtures):")
    for ((disc, n) in coverage) println("  %-16s %d".format(disc, n))

    println()
    if (runner.failed == 0) {
        println("PASS: ${runner.passed} checks green (all ${nodeFixtures.size} node fixtures decoded with zero fallback-arm hits)")
    } else {
        println("FAIL: ${runner.failed} of ${runner.passed + runner.failed} checks failed")
        runner.failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
