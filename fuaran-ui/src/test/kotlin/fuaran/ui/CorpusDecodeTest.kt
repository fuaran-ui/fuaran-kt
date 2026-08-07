// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

import java.io.File

/**
 * The corpus conformance harness.
 *
 * Three families, all hard-failing, plus the URL-floor checks:
 *
 *  * **node-round-trip** — every fixture decodes into the sealed model with **zero
 *    fallback-arm hits** (an unmodelled `$type` throws [FuaranDecodeException]; there is no
 *    catch-all producing a generic node). Coverage is reported per [NodeKind] discriminator.
 *  * **lenient-accept** — every 16 / 3.6 shorthand, field alias, enum alias and shape
 *    coercion. Being *stricter* than the language is an availability defect, not a safe
 *    default, and a model's first guess is exactly the spelling these fixtures pin.
 *  * **reject** — every malformed fixture must fail with the canonical code and a `$`-rooted
 *    path prefix. A decode that SUCCEEDS is the hard failure.
 *
 * The harness locates the corpus via `manifest.json` — the authoritative fixture enumeration
 * — and **skips cleanly** when the corpus is absent, so `fuaran-ui` stays standalone-testable.
 * When it does find one, a family that enumerates zero fixtures fails the run: a leg that
 * quietly checked nothing is the failure shape worth catching explicitly.
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

private data class Fixture(
    val id: String,
    val kind: String,
    val decoder: String,
    val inputFile: String,
    val expectedErrorCode: String?,
    val expectedPath: String?,
)

private fun manifestFixtures(manifestJson: String): List<Fixture> {
    val root = Json.parse(manifestJson) as JsonObject
    val fixtures = (root["fixtures"] as JsonArray).items
    val out = mutableListOf<Fixture>()
    for (f in fixtures) {
        val o = f as JsonObject
        out.add(
            Fixture(
                id = (o["id"] as? JsonString)?.value ?: "?",
                kind = (o["kind"] as? JsonString)?.value ?: continue,
                decoder = (o["decoder"] as? JsonString)?.value ?: continue,
                inputFile = (o["inputFile"] as? JsonString)?.value ?: continue,
                expectedErrorCode = (o["expectedErrorCode"] as? JsonString)?.value,
                expectedPath = (o["expectedPath"] as? JsonString)?.value,
            ),
        )
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
    val all = manifestFixtures(manifest)
    val nodeFixtures = all.filter { it.decoder == "node" && it.kind == "node-round-trip" }
    val runner = Runner()
    val coverage = sortedMapOf<String, Int>()

    for (fx in nodeFixtures) {
        runner.check("node-round-trip/${fx.id}") {
            val json = File(corpus, fx.inputFile).readText()
            val node = decodeNode(json)
            // Touch the exhaustive dispatch spine so every decoded kind is classified with no `else`.
            val category = node.kind.category()
            val disc = node.kind.discriminator()
            coverage[disc] = (coverage[disc] ?: 0) + 1
            if (category !in NodeCategory.entries) error("uncategorised kind $disc")
        }
    }

    // ----------------------------------------------------------------------- //
    // The LENIENT-ACCEPT leg (WIRE_FORMAT 16 + 3.6)
    // ----------------------------------------------------------------------- //
    //
    // This family exists because a host that skips it "can pass certification while
    // diverging, which is precisely what this family exists to prevent" (WIRE_FORMAT). This
    // harness ran the node-round-trip family ALONE, so every field alias, enum alias and
    // shape coercion in the corpus went unchecked here while being certified on every other
    // host — and a model's first guess is precisely the spelling these fixtures pin. Being
    // stricter than the language is not a safe default; it is an availability defect that
    // presents to the user as the surface rejecting a tree the language accepts.
    val lenientFixtures = all.filter { it.decoder == "node" && it.kind == "lenient-accept" }
    for (fx in lenientFixtures) {
        runner.check("lenient-accept/${fx.id}") {
            val json = File(corpus, fx.inputFile).readText()
            val node = decodeNode(json)
            val disc = node.kind.discriminator()
            coverage[disc] = (coverage[disc] ?: 0) + 1
        }
    }

    // ----------------------------------------------------------------------- //
    // The REJECT leg — the negative half of the decode contract
    // ----------------------------------------------------------------------- //
    //
    // A decoder that accepts every valid tree AND accepts malformed ones is not lenient: it
    // hands the embedding app typed slots that do not mean what their types say, and nothing
    // in the render path would ever notice.
    //
    // Path matching is by PREFIX, mirroring the reference host's own reject leg: a
    // discriminator refusal legitimately reports at `<path>.$type` where the corpus records
    // `<path>`, so equality would fail a correct message.
    //
    // TWO DOCUMENTED EXCLUSIONS, neither a filter over the family — both a decoder that does
    // not exist on this surface:
    //
    //   * `decoder == "op"` fixtures. There is no `TreeOp` decoder here at all; the core owns
    //     apply and mutation, and a render projection never sees an op.
    //   * the `envelope-reject` family (a separate manifest kind). It asserts FOREIGN_PROFILE
    //     — versioning-envelope negotiation, a codec-host obligation this decode-only surface
    //     does not carry and does not model.
    val rejectFixtures = all.filter { it.decoder == "node" && it.kind == "reject" }
    for (fx in rejectFixtures) {
        runner.check("reject/${fx.id}") {
            val json = File(corpus, fx.inputFile).readText()
            val expectedCode = fx.expectedErrorCode ?: ""
            val expectedPath = fx.expectedPath ?: "$"
            val e =
                try {
                    decodeNode(json)
                    error("decode ACCEPTED a malformed input (expected $expectedCode at $expectedPath)")
                } catch (e: FuaranDecodeException) {
                    e
                }
            if (e.code != expectedCode) {
                error("wrong code — expected $expectedCode, got ${e.code} at ${e.path}: ${e.detail}")
            }
            if (!e.path.startsWith(expectedPath)) {
                error("wrong path — expected prefix $expectedPath, got ${e.path}")
            }
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

    // Phase 745 — the DateRange lenient spellings. Written when this harness ran the
    // node-round-trip family ONLY and the corpus's `lenient-daterange-*` fixtures were
    // therefore unreachable here. The lenient leg above now runs them, so these are no
    // longer the only coverage — they are kept because they assert the stronger property
    // the corpus leg does not: that all three spellings normalise to the SAME canonical
    // bare `{from, to}` pair, not merely that each decodes.
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
    // "did it decode CORRECTLY". The lenient and reject legs above now run the corpus's
    // `lenient-tonedpill-*` fixtures and its reject, so these are no longer the only
    // coverage — they are kept for the same reason as the DateRange block above: they
    // assert the decoded VALUE, which a coverage walk never does.
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

    // ----------------------------------------------------------------------- //
    // The URL safety floor
    // ----------------------------------------------------------------------- //
    //
    // Each of these is a real evasion rather than a synthetic permutation, and each is a case
    // an embedding app would otherwise have to rediscover for itself.
    for (ok in listOf("https://example.org/a?b=1#c", "http://example.org", "mailto:a@example.org", "tel:+441234567890")) {
        runner.check("url/allow-$ok") {
            if (FuaranUrlPolicy.sanitize(ok) != ok) error("$ok must pass the allowlist")
        }
    }
    for (ok in listOf("", "/settings", "#section", "?q=1")) {
        runner.check("url/allow-relative-'$ok'") {
            if (FuaranUrlPolicy.sanitize(ok) != ok) error("'$ok' is relative and must pass")
        }
    }
    for (bad in
        listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "java\tscript:alert(1)", // whitespace-split scheme — what a startsWith check misses
            "  javascript:alert(1)  ",
            "vbscript:x",
            "data:text/html,<script>x</script>",
            "file:///etc/passwd",
            "intent://evil#Intent;scheme=http;end",
            "//evil.example/x", // protocol-relative
            "\\\\evil.example\\x", // backslash form — normalises to '//' in several parsers
            "/\\evil.example",
            "myapp://open?id=1", // deny by default
        )
    ) {
        runner.check("url/refuse-$bad") {
            if (FuaranUrlPolicy.sanitize(bad) != null) error("$bad must be refused")
        }
    }
    runner.check("url/classify-names-the-scheme") {
        val r = FuaranUrlPolicy.classify("javascript:x")
        if (r !is SanitizedUrl.Rejected || !r.reason.contains("javascript")) {
            error("the refusal reason must name the scheme; got $r")
        }
    }
    runner.check("url/sanitized-href-on-a-literal-link") {
        val json =
            "{\"id\":\"l1\",\"kind\":{\"\$type\":\"Link\",\"href\":{\"\$type\":\"Static\"," +
                "\"value\":\"javascript:alert(1)\"},\"label\":\"Go\",\"download\":false}}"
        val link = decodeNode(json).kind as Link
        if (link.sanitizedHref !is SanitizedUrl.Rejected) error("a javascript: href must be rejected")
        // The raw value stays reachable — the floor is an accessor, not a decode-time filter,
        // so the projection remains a faithful view of the wire.
        if (link.href.literalString != "javascript:alert(1)") error("the raw href must survive decode")
    }
    runner.check("url/sanitized-href-is-dynamic-for-a-state-binding") {
        val json =
            "{\"id\":\"l2\",\"kind\":{\"\$type\":\"Link\",\"href\":{\"\$type\":\"State\",\"key\":\"dest\"}," +
                "\"label\":\"Go\",\"download\":false}}"
        val link = decodeNode(json).kind as Link
        if (link.sanitizedHref != SanitizedUrl.Dynamic) error("a State-bound href is not knowable at decode time")
        if (link.sanitizedHref.openable != null) error("a dynamic href must not be openable")
    }
    runner.check("url/sanitized-navigate-route") {
        if ((NavigateAction("/dashboard") as Action).sanitizedNavigateRoute != SanitizedUrl.Allowed("/dashboard")) {
            error("a relative route must be allowed")
        }
        if ((NavigateAction("javascript:x") as Action).sanitizedNavigateRoute !is SanitizedUrl.Rejected) {
            error("a javascript: route must be rejected")
        }
        if ((DispatchAction as Action).sanitizedNavigateRoute != null) error("only Navigate carries a route")
    }

    val decoded = nodeFixtures.size + lenientFixtures.size
    println()
    println("Per-NodeKind coverage (${coverage.size} distinct kinds across $decoded decoded fixtures):")
    for ((disc, n) in coverage) println("  %-16s %d".format(disc, n))

    println()
    println(
        "Families run: node-round-trip=${nodeFixtures.size} lenient-accept=${lenientFixtures.size} " +
            "reject=${rejectFixtures.size}",
    )
    // A leg that silently found no fixtures is a gate that checked nothing — the exact
    // failure shape the CI workflow's SKIP guard exists to catch, one level down.
    if (nodeFixtures.isEmpty() || lenientFixtures.isEmpty() || rejectFixtures.isEmpty()) {
        println("FAIL: a corpus family enumerated ZERO fixtures — the manifest or the filter is wrong")
        kotlin.system.exitProcess(1)
    }

    println()
    if (runner.failed == 0) {
        println("PASS: ${runner.passed} checks green ($decoded fixtures decoded with zero fallback-arm hits; ${rejectFixtures.size} rejects refused with the canonical code + path)")
    } else {
        println("FAIL: ${runner.failed} of ${runner.passed + runner.failed} checks failed")
        runner.failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
