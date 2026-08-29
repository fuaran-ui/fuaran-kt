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

/** The `Static` payload of a `Binding<string>` slot, or null when it is any other form. */
private fun staticString(b: Binding?): String? = ((b as? StaticBinding)?.value as? JsonString)?.value

/** The `Static` payload of a `Binding<bool>` slot, or null when it is any other form. */
private fun staticBool(b: Binding?): Boolean? = ((b as? StaticBinding)?.value as? JsonBool)?.value

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

private val CORPUS_CANDIDATES =
    listOf(
        "../wire-format-fixtures",
        "../../wire-format-fixtures",
        "wire-format-fixtures",
        "fuaran-kt/../wire-format-fixtures",
    )

/**
 * Sibling hosts whose presence proves this is a CROSS-HOST checkout — the shape the
 * conformance gate is built from — rather than a standalone clone of this repo alone.
 * Excludes this host.
 */
private val SIBLING_HOST_NAMES =
    listOf("fuaran-dotnet", "fuaran", "fuaran-ts", "fuaran-py", "fuaran-go", "fuaran-rs", "fuaran-swift")

private fun locateCorpus(): File? {
    System.getenv("FUARAN_CORPUS")?.let {
        val f = File(it)
        if (File(f, "manifest.json").isFile) return f
    }
    for (c in CORPUS_CANDIDATES) {
        val f = File(c)
        if (File(f, "manifest.json").isFile) return f
    }
    return null
}

/**
 * Walks up from the working directory looking for a sibling host. A hit means the corpus
 * is absent from a checkout that plainly HAS one — it moved, was renamed, or the harness's
 * candidate list went stale — and the right answer is to fail, not to skip.
 */
private fun crossHostSibling(): Pair<String, File>? {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        for (name in SIBLING_HOST_NAMES) {
            val candidate = File(dir, name)
            if (candidate.isDirectory) return name to dir
        }
        dir = dir.parentFile
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
        // A missing corpus has two very different meanings, and collapsing them into one
        // clean `SKIP` + exit 0 is a vacuous green: on a standalone clone the skip is
        // honest, but on a cross-host checkout it means the conformance gate silently
        // certified NOTHING while reporting success. Discriminate, and fail loudly in
        // the second case — naming every path that was tried, so the fix is to correct
        // the candidate list rather than to let the harness keep skipping.
        val sibling = crossHostSibling()
        if (sibling != null) {
            val (name, at) = sibling
            println(
                "FAIL: cross-host checkout detected ($name/ is present under ${at.path}) but the " +
                    "wire-format-fixtures corpus is at none of the paths tried — this gate certified NOTHING.",
            )
            println("  FUARAN_CORPUS=${System.getenv("FUARAN_CORPUS") ?: "<unset>"}")
            for (c in CORPUS_CANDIDATES) println("  tried: ${File(c).absolutePath}")
            println(
                "  If the corpus moved or was renamed, add the new location to CORPUS_CANDIDATES " +
                    "rather than letting the harness skip.",
            )
            kotlin.system.exitProcess(1)
        }
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
    // The §21 RESOURCE LIMITS — the host-local half
    // ----------------------------------------------------------------------- //
    //
    // The corpus pins three of the five bounds (node depth past the limit, node depth AT
    // the limit, and the syntactic boundary from both sides). The two LINEAR limits stay
    // host-local by the corpus's own decision: committing a megabyte of "aaaa…" to a
    // shared repository to assert one integer comparison is a poor trade, and unlike the
    // depth bounds they are not a recursion hazard. So they are asserted here, generated
    // from a rule rather than stored — as every ported host does.
    //
    // The boundary cases matter more than the breaches. A guard one level too TIGHT
    // refuses a document every host must accept, and a refusal-only family passes
    // throughout that defect.
    // Built from the inside out, the same rule the corpus's stored depth fixtures follow:
    // n levels of `Box`, the innermost carrying no children. One tree level is three JSON
    // levels here (the node object, its `children` array, the child object), which is why
    // the node-axis cases below stay well under the syntactic bound — otherwise a
    // node-depth assertion could be satisfied by the syntactic guard firing first, and
    // would pass while the node guard did nothing.
    fun nestedNodes(n: Int): String {
        // A `Box` needs `role` AND `layout` before it is a valid node — omit them and the
        // decoder refuses on shape at the innermost level, which measures nothing about
        // depth while looking exactly like a depth failure.
        val tail = "],\"layout\":{\"\$type\":\"Flex\",\"direction\":\"Vertical\",\"wrap\":false},\"role\":\"Group\"}}"
        var inner = ""
        for (i in n - 1 downTo 0) {
            inner = "{\"id\":\"n$i\",\"kind\":{\"\$type\":\"Box\",\"children\":[$inner$tail"
        }
        return inner
    }

    fun limitCodeOf(json: String): String =
        try {
            decodeNode(json)
            "ACCEPTED"
        } catch (e: FuaranDecodeException) {
            e.code
        }

    runner.check("limits/node-depth-at-the-limit-decodes") {
        // Rule 1, and the half hosts actually fail: two of the codec hosts aborted the
        // process on exactly this document.
        decodeNode(nestedNodes(WireLimits.MAX_NODE_DEPTH))
    }
    runner.check("limits/node-depth-one-past-the-limit-is-refused") {
        val got = limitCodeOf(nestedNodes(WireLimits.MAX_NODE_DEPTH + 1))
        if (got != FuaranDecodeException.LIMIT_EXCEEDED) error("expected LIMIT_EXCEEDED, got $got")
    }
    runner.check("limits/a-deep-tree-is-a-limit-breach-not-invalid-json") {
        // The distinction the format is explicit about: the input is well-formed and
        // merely too large to walk, so INVALID_JSON is an actively wrong diagnosis.
        // All three stay under the SYNTACTIC bound (3 JSON levels per tree level), so
        // each one is genuinely the node guard answering.
        for (n in listOf(25, 40, 80)) {
            val got = limitCodeOf(nestedNodes(n))
            if (got != FuaranDecodeException.LIMIT_EXCEEDED) error("depth $n: expected LIMIT_EXCEEDED, got $got")
        }
    }
    runner.check("limits/bare-nesting-at-the-syntactic-limit-fails-on-SHAPE") {
        // Exactly MAX_JSON_DEPTH levels: not a valid node, so it must fail — but on
        // shape, NOT as a limit breach. A guard one level too tight answers
        // LIMIT_EXCEEDED here, which is the off-by-one that made the host family
        // disagree at this boundary.
        val doc = "[".repeat(WireLimits.MAX_JSON_DEPTH) + "]".repeat(WireLimits.MAX_JSON_DEPTH)
        val got = limitCodeOf(doc)
        if (got != FuaranDecodeException.WRONG_TYPE) error("expected WRONG_TYPE (a shape failure), got $got")
    }
    runner.check("limits/bare-nesting-one-past-the-syntactic-limit-is-refused") {
        val n = WireLimits.MAX_JSON_DEPTH + 1
        val got = limitCodeOf("[".repeat(n) + "]".repeat(n))
        if (got != FuaranDecodeException.LIMIT_EXCEEDED) error("expected LIMIT_EXCEEDED, got $got")
    }
    runner.check("limits/genuinely-malformed-input-is-still-INVALID_JSON") {
        // The other direction: adding the limit codes must not turn a syntax error into
        // a limit report.
        val got = limitCodeOf("{\"id\":\"x\",")
        if (got != FuaranDecodeException.INVALID_JSON) error("expected INVALID_JSON, got $got")
    }
    runner.check("limits/a-string-at-the-limit-is-accepted-and-one-past-it-is-refused") {
        val atMax = "\"" + "a".repeat(WireLimits.MAX_STRING_LENGTH) + "\""
        // A bare string is not a node, so the ACCEPTED case still fails — on shape.
        val at = limitCodeOf(atMax)
        if (at != FuaranDecodeException.WRONG_TYPE) error("a string at the limit must pass the reader; got $at")
        val past = limitCodeOf("\"" + "a".repeat(WireLimits.MAX_STRING_LENGTH + 1) + "\"")
        if (past != FuaranDecodeException.LIMIT_EXCEEDED) error("expected LIMIT_EXCEEDED, got $past")
    }
    runner.check("limits/an-over-long-array-is-refused") {
        val doc = "[" + "1,".repeat(WireLimits.MAX_ARRAY_LENGTH) + "1]"
        val got = limitCodeOf(doc)
        if (got != FuaranDecodeException.LIMIT_EXCEEDED) error("expected LIMIT_EXCEEDED, got $got")
    }
    runner.check("limits/a-refused-decode-does-not-poison-the-next") {
        // The counters are decremented in `finally` precisely so a refusal leaves no
        // residue. Without that, each refused decode would tighten the budget until a
        // valid tree was refused too — and the corpus, which decodes hundreds of trees in
        // one process, would fail somewhere far from the cause.
        repeat(50) { limitCodeOf(nestedNodes(WireLimits.MAX_NODE_DEPTH + 5)) }
        decodeNode(nestedNodes(WireLimits.MAX_NODE_DEPTH))
    }
    runner.check("limits/concurrent-decodes-do-not-share-counters") {
        // decodeNode is public library API, so concurrent decode is expected usage. If
        // the counters were shared state rather than thread-locals this asserts on the
        // RESULT rather than on the absence of a race report — a mis-bounded decode shows
        // up as a valid tree refused, which is the damage that matters.
        val ok = nestedNodes(WireLimits.MAX_NODE_DEPTH)
        val over = nestedNodes(WireLimits.MAX_NODE_DEPTH + 1)
        val problems = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads =
            (0 until 4).map { t ->
                Thread {
                    repeat(40) {
                        if (limitCodeOf(ok) != "ACCEPTED") problems.add("thread $t: a tree at the limit was refused")
                        if (limitCodeOf(over) != FuaranDecodeException.LIMIT_EXCEEDED) {
                            problems.add("thread $t: a tree past the limit was not refused")
                        }
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        if (problems.isNotEmpty()) error(problems.first())
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

    // ----------------------------------------------------------------------- //
    // The SANITIZATION family (WIRE_FORMAT 19 + 22) - semantic invariants
    // ----------------------------------------------------------------------- //
    //
    // Unlike every other family here this one is NOT byte-parity: the markup a host emits
    // around a URL differs legitimately between an F# React renderer, a Go static-HTML
    // emitter and this native projection, so comparing bytes would pin accidents. The family
    // states invariants instead, and each case carries the URL parser's own verdict
    // (`off-origin` / `same-origin` / `scheme-refused`) so a "must reject this" claim is
    // backed by what a real parser does rather than by a reading of the specification.
    //
    // ONE group applies to this surface, and the reasons the others do not are recorded
    // rather than left to inference:
    //
    //   * `url-floor` (19) - APPLICABLE and asserted. `Link.href`, `Image.src` and
    //     `Navigate.route` all reach the embedding app, which may hand them to an Intent or
    //     a browser, so the scheme floor is this surface's real exposure.
    //   * `markdown-body`, `text-source` (22) - NOT APPLICABLE. There is no markup emission
    //     and no HTML-parsing text path anywhere in this projection: text reaches a Compose
    //     Text node as CONTENT, so there is no markup for a payload to break out of. That is
    //     a structural property of rendering into native views, not a gap.
    //   * `extra-attributes` (22) - NOT APPLICABLE. The ExtraAttributes seam does not exist
    //     on a decoded tree here, and every attribute this renderer sets is
    //     renderer-controlled. (The same declaration `fuaran-go` makes, for the same reason.)
    //
    // The CLAIMED-GROUPS GUARD below is the load-bearing part. Without it a group added to
    // the corpus later would read as covered while being silently untested - the exact shape
    // 22.2 refuses - so a group this leg neither runs nor names as not-applicable FAILS.
    val notApplicableGroups =
        mapOf(
            "markdown-body" to "no markup emission and no HTML-parsing text path - text renders as content",
            "text-source" to "no markup emission and no HTML-parsing text path - text renders as content",
            "extra-attributes" to "the ExtraAttributes seam does not exist on a decoded tree here",
        )

    val sanitizationManifest = File(File(corpus, "sanitization"), "manifest.json")
    if (!sanitizationManifest.isFile) {
        println("FAIL: sanitization/manifest.json absent - the render-time floor family did not run")
        kotlin.system.exitProcess(1)
    }
    val sanRoot = Json.parse(sanitizationManifest.readText()) as JsonObject
    val sanGroups = (sanRoot["groups"] as JsonArray).items.map { it as JsonObject }
    var urlFloorCases = 0
    for (g in sanGroups) {
        val gid = (g["id"] as JsonString).value
        if (gid == "url-floor") {
            val cases = (g["cases"] as JsonArray).items.map { it as JsonObject }
            urlFloorCases = cases.size
            for (c in cases) {
                val id = (c["id"] as JsonString).value
                val input = (c["input"] as JsonString).value
                val invariant = (c["invariant"] as JsonString).value
                val expected = (c["expected"] as? JsonString)?.value
                runner.check("sanitization/url-floor/$id") {
                    val got = FuaranUrlPolicy.sanitize(input)
                    when (invariant) {
                        "reject" ->
                            if (got != null) {
                                error("the floor ACCEPTED '$input' as '$got' (reason: ${(c["reason"] as? JsonString)?.value})")
                            }
                        "accept" -> {
                            if (got == null) error("the floor REJECTED '$input', which resolves same-origin")
                            // The emitted form is the 19 rule-1 normalised one, which is NOT
                            // always the input: an accepted URL carrying an interior tab loses
                            // it, because that is what a parser would have read anyway.
                            if (expected != null && got != expected) {
                                error("expected the normalised form '$expected', got '$got'")
                            }
                        }
                        else -> error("unknown invariant '$invariant'")
                    }
                }
            }
            continue
        }
        val reason = notApplicableGroups[gid]
        if (reason == null) {
            println(
                "FAIL: sanitization group '$gid' is neither run nor declared not-applicable - " +
                    "it would read as covered while being untested",
            )
            kotlin.system.exitProcess(1)
        }
        // Logged, not silent: a reader sees WHY the group did not execute rather than
        // inferring it from an absence.
        println("sanitization/$gid: NOT APPLICABLE - $reason")
    }
    if (urlFloorCases == 0) {
        println("FAIL: the sanitization url-floor group enumerated ZERO cases")
        kotlin.system.exitProcess(1)
    }
    println("sanitization/url-floor: $urlFloorCases cases asserted against the URL floor")

    // ----------------------------------------------------------------------- //
    // The ACCESSIBILITY-TRAIT leg (WIRE_FORMAT 3.1)
    // ----------------------------------------------------------------------- //
    //
    // The node-round-trip family above proves each fixture DECODES; it says nothing
    // about what landed in the trait's six slots, because this surface has no
    // canonical encoder to compare bytes against. So the accessibility family gets
    // its own value assertions: the slot each fixture populated, the binding form it
    // carried, and — the two that a fold bug and a loose reader each got wrong once
    // — a custom role's CASE and a `liveRegion` token's membership of the closed set.
    //
    // This is the DECODE half of the trait's certification. The PROJECTION half — the
    // mapping onto semantics properties, and the drop set — runs beside it in the same
    // gate, over the same fixtures, in `AccessibilityCorpusHarness`: the projection's
    // result type is platform-neutral, so it no longer needs a machine carrying the
    // Android SDK. Only the reachability of the emitted Compose semantics does, and that
    // is all `AccessibilityProjectionTest` keeps.
    val a11yFixtures =
        listOf(
            "a11y-wrapper-all-slots",
            "a11y-wrapper-state-bound",
            "a11y-alert-assertive",
            "a11y-link-labelled",
            "a11y-button-named",
            "a11y-image-decorative",
        )
    var a11yChecked = 0
    for (id in a11yFixtures) {
        val file = File(corpus, "nodes/$id.json")
        if (!file.isFile) continue
        a11yChecked++
        runner.check("accessibility/$id") {
            val a = decodeNode(file.readText()).accessibility ?: error("the trait decoded to nothing")
            when (id) {
                "a11y-wrapper-all-slots" -> {
                    if (staticString(a.label) != "Channel performance summary") error("label: ${a.label}")
                    if (a.labelledBy != "a11y-wrapper-heading") error("labelledBy: ${a.labelledBy}")
                    if (a.describedBy != "a11y-wrapper-note") error("describedBy: ${a.describedBy}")
                    if (a.role != "region") error("role: ${a.role}")
                    if (a.liveRegion != "polite") error("liveRegion: ${a.liveRegion}")
                    // An explicit Static FALSE — distinct on the wire from omitted.
                    if (staticBool(a.hidden) != false) error("hidden: ${a.hidden}")
                }
                "a11y-wrapper-state-bound" -> {
                    // The State form carries its own declared default; the surface
                    // keeps the binding rather than resolving it here.
                    val label = a.label as? StateBinding ?: error("label is not State-bound: ${a.label}")
                    if (label.key != "footerLabel") error("label key: ${label.key}")
                    if ((label.defaultValue as? JsonString)?.value != "Site footer") {
                        error("label default: ${label.defaultValue}")
                    }
                    // The CASE survives decode — the exact spelling a fold bug rewrote.
                    if (a.role != "doc-pageFooter") error("the custom role's case was folded: ${a.role}")
                    if (a.liveRegion != "off") error("liveRegion: ${a.liveRegion}")
                    if (a.hidden !is StateBinding) error("hidden is not State-bound: ${a.hidden}")
                }
                "a11y-alert-assertive" -> {
                    if (a.role != "alert") error("role: ${a.role}")
                    if (a.liveRegion != "assertive") error("liveRegion: ${a.liveRegion}")
                    if (a.label != null) error("no label slot was authored: ${a.label}")
                }
                "a11y-link-labelled" -> {
                    if (staticString(a.label) != "Read the 2026 annual report (PDF)") error("label: ${a.label}")
                    if (a.role != null) error("no role slot was authored: ${a.role}")
                }
                "a11y-button-named" -> {
                    if (staticString(a.label) != "Refresh revenue figures") error("label: ${a.label}")
                    if (a.role != "button") error("role: ${a.role}")
                }
                "a11y-image-decorative" -> {
                    // The slot whose absence went unnoticed on two hosts for weeks.
                    if (staticBool(a.hidden) != true) error("hidden: ${a.hidden}")
                    if (a.label != null) error("no label slot was authored: ${a.label}")
                }
            }
        }
    }
    if (a11yChecked != a11yFixtures.size) {
        println(
            "FAIL: the accessibility family enumerated $a11yChecked of ${a11yFixtures.size} fixtures - " +
                "a leg that quietly checked a subset reads as covered while being untested",
        )
        kotlin.system.exitProcess(1)
    }
    println("accessibility: $a11yChecked trait-bearing fixtures asserted slot by slot")

    // ----------------------------------------------------------------------- //
    // The TREND-POLARITY leg (WIRE_FORMAT 3.6.1)
    // ----------------------------------------------------------------------- //
    //
    // `nodes/metric-inverted-polarity.json` proves the POSITIVE case through the
    // node-round-trip family above — but only that it DECODES, since this surface has
    // no canonical encoder to compare bytes against. So the slot gets its own value
    // assertion, plus the three things a fixture structurally cannot prove: the
    // DEFAULT when the key is absent (a fixture can only pin a document that exists),
    // the REFUSAL of the spelling the specification reserved (a corpus reject fixture
    // for it would assert the reservation is closed, which is the claim it withholds),
    // and that an INERT declaration survives.
    //
    // The RENDER half of the same rule — sentiment = sign x polarity, the glyph and the
    // spoken label — runs beside it in the same gate, in `TrendSentimentHarness`.
    run {
        val fixture = File(corpus, "nodes/metric-inverted-polarity.json")
        if (fixture.isFile) {
            runner.check("trendPolarity/metric-inverted-polarity") {
                val k = decodeNode(fixture.readText()).kind as? Metric ?: error("not a Metric")
                if (k.trendPolarity != TrendPolarity.LowerIsBetter) error("polarity: ${k.trendPolarity}")
                if (k.trend == null) error("the fixture's trend slot decoded to nothing")
                // The pair one `tone` slot could never express, and the whole argument for the
                // field: the tile says the reading stands badly, the polarity says the quantity is
                // improving. Nothing derives either from the other.
                if (k.tone != ToneVariant.Warning) error("tone was rewritten by the polarity: ${k.tone}")
            }
        }
        val base =
            "{\"id\":\"m\",\"kind\":{\"\$type\":\"Metric\",\"label\":\"Revenue\"," +
                "\"value\":{\"\$type\":\"Static\",\"value\":42.0}"

        runner.check("trendPolarity/absentIsHigherIsBetter") {
            // A DEFAULT, not a third state — which is why the model slot is total rather than
            // nullable, and why this asserts a value rather than an absence.
            val k = decodeNode("$base}}").kind as? Metric ?: error("not a Metric")
            if (k.trendPolarity != TrendPolarity.HigherIsBetter) error("polarity: ${k.trendPolarity}")
        }

        runner.check("trendPolarity/inertDeclarationSurvives") {
            // Clause 4: a polarity with no `trend` is legal and says nothing. It is KEPT rather
            // than dropped — dropping it would silently rewrite the author's document because this
            // surface judged the declaration pointless.
            val k = decodeNode("$base,\"trendPolarity\":\"LowerIsBetter\"}}").kind as? Metric ?: error("not a Metric")
            if (k.trendPolarity != TrendPolarity.LowerIsBetter) error("polarity: ${k.trendPolarity}")
            if (k.trend != null) error("a trend appeared from nowhere: ${k.trend}")
        }

        runner.check("trendPolarity/reservedNeutralRefusedByDefaultDeny") {
            // The whole point of modelling the reserved case as ABSENCE FROM THE CASE SET rather
            // than as a case the decoder refuses: default-deny does the work, and the expected-list
            // diagnostic cannot advertise a spelling the format does not accept.
            val e =
                runCatching { decodeNode("$base,\"trendPolarity\":\"Neutral\"}}") }.exceptionOrNull()
                    as? FuaranDecodeException ?: error("'Neutral' was ACCEPTED, or threw the wrong type")
            if (e.code != FuaranDecodeException.UNKNOWN_DU_CASE) error("code: ${e.code}")
            if (e.path != "$.kind.trendPolarity") error("path: ${e.path}")
            val msg = e.message ?: error("no message")
            if (!msg.contains("HigherIsBetter, LowerIsBetter")) error("expected-list drifted: $msg")
            // Scoped to the expected-list HALF deliberately: the message also echoes the rejected
            // spelling as the got-value, so a bare contains("Neutral") reads red on a CORRECT
            // refusal. The sibling Swift surface's first run of this assertion did exactly that.
            val expectedList = msg.substringAfter("expected one of ", "")
            if (expectedList.isEmpty()) error("no expected-list in: $msg")
            if (expectedList.contains("Neutral")) error("the reserved case leaked into the expected list")
        }

        runner.check("trendPolarity/noAliasArmIsRegistered") {
            // Neither the boolean spelling 3.6.1 refuses nor a direction word is aliased. Accepting
            // either would silently decide a question the wire deliberately left to a declaration.
            for (spelling in listOf("Inverted", "Descending", "lowerIsBetter")) {
                val threw = runCatching { decodeNode("$base,\"trendPolarity\":\"$spelling\"}}") }.isFailure
                if (!threw) error("'$spelling' must not be accepted")
            }
        }
    }

    // ----------------------------------------------------------------------- //
    // The MEDIA-VOCABULARY leg (WIRE_FORMAT 3.6.2 - 3.6.6)
    // ----------------------------------------------------------------------- //
    //
    // The families above prove each media fixture DECODES and each media reject vector is
    // REFUSED with the canonical code and path. They cannot prove what landed in the slots,
    // because this surface has no canonical encoder to compare bytes against — and three of
    // this change-set's rules are about ABSENCE, which no stored fixture can pin at all:
    //
    //   * absent `srcSet` MEANS the empty list (the missing-list-field class). A decoder
    //     answering null/None here has produced a value its own encoder cannot round-trip,
    //     and it is the single most likely cross-host divergence in the slot.
    //   * absent `fit`/`aspectRatio`/`loading` restore the identity defaults, so a document
    //     written before they existed decodes to today's behaviour.
    //   * absent `controls` means TRUE — the inverted polarity — while absent `loop` and
    //     absent `expandable` mean false. One reader getting the inversion backwards silently
    //     takes the transport away from every keyboard user.
    //
    // And one rule is about a slot that does not exist: `Audio` carries NO autoplay pathway.
    // That is stronger than a default of false, so it is asserted as unrepresentability —
    // an `autoplay` beside an `Audio` discriminator has nowhere to land and is tolerated as
    // the unknown key it is, never absorbed into a flag.
    run {
        fun imageFixture(id: String): Image? =
            File(corpus, "nodes/$id.json").takeIf { it.isFile }?.let { decodeNode(it.readText()).kind as? Image }

        fun mediaOf(kindJson: String): Media =
            decodeNode("{\"id\":\"m\",\"kind\":{\"\$type\":\"Media\",\"label\":\"Commentary\"," +
                "\"src\":{\"\$type\":\"Static\",\"value\":\"/a.mp3\"},\"kind\":$kindJson}}").kind as Media

        runner.check("media/image-presentation-tokens-decode") {
            val k = imageFixture("image-presentation-1") ?: error("fixture absent")
            if (k.fit != ImageFit.Cover) error("fit: ${k.fit}")
            if (k.aspectRatio != ImageAspect.SixteenNine) error("aspectRatio: ${k.aspectRatio}")
            if (k.loading != ImageLoading.Lazy) error("loading: ${k.loading}")
        }
        runner.check("media/image-presentation-absent-restores-the-identity") {
            // The claim `nodes/image-1.json` is the PROOF of rather than a restatement of: the
            // pre-phase document decodes to today's behaviour on all three axes.
            val k = imageFixture("image-1") ?: error("fixture absent")
            if (k.fit != ImageFit.Natural) error("fit: ${k.fit}")
            if (k.aspectRatio != ImageAspect.Natural) error("aspectRatio: ${k.aspectRatio}")
            if (k.loading != ImageLoading.Eager) error("loading: ${k.loading}")
            if (k.caption != null) error("a caption appeared from nowhere: ${k.caption}")
            if (k.expandable) error("expandable must default to false")
        }
        runner.check("media/image-srcset-absent-is-the-EMPTY-LIST-not-null") {
            val k = imageFixture("image-1") ?: error("fixture absent")
            if (k.srcSet != emptyList<SrcSetEntry>()) error("absent srcSet must decode to the empty list; got ${k.srcSet}")
        }
        runner.check("media/image-srcset-preserves-the-AUTHORED-order") {
            // The fixture is authored DESCENDING by width precisely so a re-sorting host fails it.
            // Presentation order (ascending) is a renderer's business; the wire is ordered data.
            val k = imageFixture("image-srcset-1") ?: error("fixture absent")
            val widths = k.srcSet.map { it.width }
            if (widths != listOf(1600, 800, 400)) error("authored order was not preserved: $widths")
            if (staticString(k.srcSet[0].src) != "/harbour-1600.jpg") error("entry 0 src: ${k.srcSet[0].src}")
        }
        runner.check("media/image-caption-is-a-full-TextSource") {
            // The rule a second host is most likely to break, because a caption reads like a
            // string: every case of the DU rides the slot, arg bag included.
            val literal = imageFixture("image-caption-1") ?: error("fixture absent")
            if (literal.caption != LiteralText("The harbour at dawn, 1908. Oil on canvas.")) {
                error("literal caption: ${literal.caption}")
            }
            val i18n = imageFixture("image-caption-i18n-1") ?: error("fixture absent")
            val c = i18n.caption as? I18nText ?: error("an I18n caption narrowed to a string: ${i18n.caption}")
            if (c.key != "gallery.caption.harbour") error("i18n key: ${c.key}")
            if (c.args == null) error("the arg bag was dropped")
        }
        runner.check("media/image-expandable-composes-with-the-other-five-slots") {
            val k = imageFixture("image-expandable-figure-1") ?: error("fixture absent")
            if (!k.expandable) error("expandable was not read")
            if (k.aspectRatio != ImageAspect.FourThree || k.fit != ImageFit.Cover) error("presentation slots: $k")
            if (k.caption == null) error("caption was dropped")
            if (k.srcSet.map { it.width } != listOf(400, 800)) error("srcSet: ${k.srcSet}")
        }
        runner.check("media/media-shared-bool-defaults-including-the-INVERTED-one") {
            val k = decodeNode(File(corpus, "nodes/media-video-1.json").readText()).kind as Media
            // `controls` is omitted at TRUE — the second such slot in the vocabulary. A reader that
            // took the ordinary polarity here removes the transport from every keyboard user, and
            // the document that says so is the one that omits the key.
            if (!k.controls) error("absent controls must mean TRUE")
            if (k.loop) error("absent loop must mean false")
            if (k.kind != Video()) error("the minimum Video payload is the bare discriminator; got ${k.kind}")
        }
        runner.check("media/media-video-autoplay-and-poster-live-in-the-CASE") {
            val autoplay = decodeNode(File(corpus, "nodes/media-video-autoplay-1.json").readText()).kind as Media
            val v = autoplay.kind as? Video ?: error("not a Video: ${autoplay.kind}")
            if (!v.autoplay) error("autoplay was not read")
            if (autoplay.controls) error("an explicit controls:false was not read")
            if (!autoplay.loop) error("loop was not read")
            val poster = decodeNode(File(corpus, "nodes/media-video-poster-1.json").readText()).kind as Media
            val pv = poster.kind as? Video ?: error("not a Video")
            if (staticString(pv.poster) != "/walkthrough-poster.jpg") error("poster: ${pv.poster}")
        }
        runner.check("media/AUDIO-HAS-NO-AUTOPLAY-PATHWAY") {
            // The load-bearing assertion of the whole section, and the reason the variant is a sum
            // rather than a flag beside a `mediaType` string. `Audio` declares no slot, so an
            // `autoplay` beside it is an ordinary unknown key: tolerated by rule 2, landing
            // nowhere. A host modelling this as `Media(autoplay: Bool, kind: String)` would decode
            // the same document into a page that begins making sound unbidden.
            val plain = mediaOf("{\"\$type\":\"Audio\"}")
            if (plain.kind != Audio) error("kind: ${plain.kind}")
            val withAutoplay = mediaOf("{\"\$type\":\"Audio\",\"autoplay\":true}")
            if (withAutoplay.kind != Audio) error("an autoplay declaration reached the Audio case: ${withAutoplay.kind}")
            // Stated at the type level too, so the claim is not merely about these two documents:
            // there is no member to read, on any Audio value this decoder can produce.
            if (Audio::class.java.declaredFields.any { it.name.contains("autoplay", ignoreCase = true) }) {
                error("the Audio case grew an autoplay slot — 3.6.6's strongest rule is no longer structural")
            }
        }
        runner.check("media/mediaKind-is-\$type-discriminated-so-the-refusal-carries-it") {
            // 6: the discriminated position reports at `.\$type`, not at the bare slot — which is
            // what distinguishes this refusal from `aspectRatio`'s one line above it.
            val e =
                runCatching { mediaOf("{\"\$type\":\"Stream\"}") }.exceptionOrNull() as? FuaranDecodeException
                    ?: error("'Stream' was ACCEPTED, or threw the wrong type")
            if (e.code != FuaranDecodeException.UNKNOWN_DU_CASE) error("code: ${e.code}")
            if (e.path != "\$.kind.kind.\$type") error("path: ${e.path}")
        }
    }

    // ----------------------------------------------------------------------- //
    // The BARE STATE TRANSFORM SOURCE leg (WIRE_FORMAT 16)
    // ----------------------------------------------------------------------- //
    //
    // `{"$type":"State","key":k}` in a Transform's `source` slot carries no payload member.
    // `unwrapTransformSource` refused it: there was nothing to unwrap to, so the transform
    // "had no data". That was correct while nothing else could fill the slot; under 24.4 a
    // SIBLING reader's declaration fills it, so the refusal was rejecting the most direct
    // spelling of "I read this key and carry no data of my own" - the one FUARAN106's remedy
    // text tells an author to write.
    //
    // No stored fixture pins the bare spelling: the corpus is a shared gate and keeps the
    // `"defaultValue": []` spelling deliberately, so respelling it there would redden a host
    // that has not adopted this. The assertion therefore rides here, like the three
    // absence rules in the media leg above and for the same reason.
    run {
        fun badge(source: String): Node =
            decodeNode(
                "{\"id\":\"member-count\",\"kind\":{\"\$type\":\"Badge\",\"label\":" +
                    "{\"\$type\":\"Bound\",\"binding\":{\"\$type\":\"Transform\",\"pipeline\":[" +
                    "{\"\$type\":\"groupBy\",\"aggs\":[{\"fn\":\"count\",\"name\":\"n\",\"of\":\"team\"}],\"keys\":[]}]," +
                    "\"source\":$source}},\"variant\":\"Info\"}}",
            )

        fun transformSource(n: Node): JsonValue {
            val label = (n.kind as? Badge)?.label ?: error("not a Badge: ${n.kind}")
            val binding = (label as? BoundText)?.binding ?: error("label is not a bound binding: $label")
            return (binding as? TransformBinding)?.source ?: error("not a Transform: $binding")
        }

        runner.check("state-source/the-BARE-State-wrapper-is-a-live-source-not-a-refusal") {
            // Held RAW and returned UNCHANGED — this surface validates the envelope rather than
            // rewriting it — so the assertion is that the bare wrapper survives verbatim, which
            // is also what would let a canonical re-encode round-trip if this tier ever gained one.
            val src = transformSource(badge("{\"\$type\":\"State\",\"key\":\"members\"}"))
            val o = src as? JsonObject ?: error("the source was rewritten to ${src::class.simpleName}")
            if ((o["\$type"] as? JsonString)?.value != "State") error("the envelope was not preserved: $o")
            if ((o["key"] as? JsonString)?.value != "members") error("the live slot's key was lost: $o")
            if (o["defaultValue"] != null) error("a defaultValue appeared from nowhere: $o")
        }
        runner.check("state-source/the-two-data-less-spellings-are-ONE-dialect") {
            // The empty-array spelling said the same thing while the bare one was refused. Both
            // must now reach the slot, and neither may be normalised into the other — each stays
            // the bytes it arrived as.
            val empty = transformSource(badge("{\"\$type\":\"State\",\"defaultValue\":[],\"key\":\"members\"}"))
            val o = empty as? JsonObject ?: error("the source was rewritten to ${empty::class.simpleName}")
            if (o["defaultValue"] !is JsonArray) error("the empty array was normalised away: $o")
        }
        runner.check("state-source/the-widening-is-SCOPED-to-the-State-envelope") {
            // The go-red half. A check that only ever passes cannot tell a decoder that ACCEPTS
            // the bare State wrapper from one that stopped validating envelopes at all. `Static`
            // and `Bound` name no live slot, so nothing can seed them and an empty one genuinely
            // has no data — both must still refuse, at their own path.
            for (source in listOf("{\"\$type\":\"Static\"}", "{\"\$type\":\"Bound\"}")) {
                val e =
                    runCatching { badge(source) }.exceptionOrNull() as? FuaranDecodeException
                        ?: error("$source was ACCEPTED — the widening is not scoped to the State wrapper")
                if (e.code != FuaranDecodeException.WRONG_TYPE) error("$source code: ${e.code}")
                if (!e.path.startsWith("\$")) error("$source path is not \$-rooted: ${e.path}")
            }
        }
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
