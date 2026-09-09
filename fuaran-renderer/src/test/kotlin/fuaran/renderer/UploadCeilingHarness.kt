// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.FileUpload
import fuaran.ui.FuaranDecodeException
import fuaran.ui.decodeNode

/**
 * Phase 1548 — `FileUpload.maxBytes` / `.maxFiles` (WIRE_FORMAT.md 3.6.23), both legs.
 *
 * **The decode leg lives here rather than beside the decoder**, and the placement is deliberate:
 * the plain-JVM gate compiles a named set of `main()`-driven harnesses and CI runs
 * `:fuaran-ui:corpusCheck` plus `:fuaran-renderer:testDebugUnitTest`, so this module's test source
 * set is the one home from which both a Windows box and the CI runner execute the same assertions.
 * The shared corpus already carries the four refusals and the two accept vectors and the corpus
 * harness runs them; what is here is the CORRECTED TWIN beside each refusal. A reject vector on its
 * own proves only that SOMETHING was refused — a decoder refusing every `maxBytes` outright would
 * satisfy all four and be wrong in the more expensive direction, since it would reject documents
 * every other host accepts — so the twin is what makes the pair say where the boundary is.
 *
 * **The render leg** is the supporting evidence the declared exemption for
 * `FileUpload/ceiling-recorded-never-enforced` names in `RenderObligationHarness.kt`. An exemption
 * asserting only "this floor emits no attribute bag" would be true and useless; what makes it
 * honest is that the floor follows the same obligation's reasoning — record THAT a ceiling was
 * declared, never its value — and that these checks fail if it stops doing so.
 *
 * A plain `main`-driven runner, matching the sibling harnesses: the repo builds with a bare
 * `kotlinc` and no artefact resolution, so the exit code is the gate. [uploadCeilingFailures] is
 * exposed separately so the Gradle/Robolectric leg runs the identical set without duplicating it.
 */

/** A minimal check collector — the shape the sibling harnesses use, kept local so this leg depends
 *  on nothing another leg establishes. */
internal class CeilingChecks {
    var passed = 0
    val failures = mutableListOf<String>()

    fun check(name: String, body: () -> Unit) {
        try {
            body()
            passed++
        } catch (e: Throwable) {
            failures.add("$name — ${e::class.simpleName}: ${e.message}")
        }
    }

    fun eq(what: String, expected: Any?, actual: Any?) {
        if (expected != actual) error("$what — expected $expected, got $actual")
    }
}

/** How many checks [uploadCeilingFailures] runs — reported so a leg that shrank is visible. */
var uploadCeilingChecksRun = 0
    private set

/** One upload document with an arbitrary ceiling clause spliced in. */
private fun uploadJson(clause: String, multiple: Boolean = false): String {
    val accept = if (multiple) "[\"image/*\"]" else "[\"application/pdf\"]"
    return "{\"id\":\"up\",\"kind\":{\"\$type\":\"FileUpload\",\"accept\":" + accept +
        ",\"label\":\"Upload\"," + clause + "\"multiple\":" + multiple +
        ",\"onSelect\":\"<closure>\"}}"
}

private fun upload(clause: String, multiple: Boolean = false): FileUpload =
    decodeNode(uploadJson(clause, multiple)).kind as FileUpload

/** Assert one document is refused as `WRONG_TYPE` at exactly [path]. */
private fun refuses(clause: String, path: String, multiple: Boolean = false) {
    try {
        decodeNode(uploadJson(clause, multiple))
    } catch (e: FuaranDecodeException) {
        if (e.code != FuaranDecodeException.WRONG_TYPE) error("$clause — expected WRONG_TYPE, got ${e.code}")
        if (e.path != path) error("$clause — expected path $path, got ${e.path}")
        return
    }
    error("$clause — decode ACCEPTED a malformed ceiling")
}

/** Every assertion, returning the failures rather than throwing, so both gates run the identical
 *  set. Empty means green. */
fun uploadCeilingFailures(): List<String> {
    val c = CeilingChecks()

    // ── Decode: absence is the pre-1548 control, exactly ──────────────────────

    c.check("anUploadDeclaringNeitherCeilingCarriesNeither") {
        // The polarity `nodes/upload-1.json` pins: every document written before this revision is
        // byte-identical and means what it always meant.
        val k = upload("")
        c.eq("maxBytes", null, k.maxBytes)
        c.eq("maxFiles", null, k.maxFiles)
    }

    // ── Decode: the accept vectors' shapes, carried apart ─────────────────────

    c.check("eachCeilingDecodesWithoutImplyingTheOther") {
        val bytes = upload("\"maxBytes\":5242880,")
        c.eq("maxBytes", 5242880, bytes.maxBytes)
        c.eq("maxFiles must not be conjured", null, bytes.maxFiles)

        val files = upload("\"maxFiles\":3,", multiple = true)
        c.eq("maxFiles", 3, files.maxFiles)
        c.eq("maxBytes must not be conjured", null, files.maxBytes)
    }

    c.check("theLargestDeclarableCeilingIsTheSlotWidth") {
        // 7.1 — every typed integer slot is a signed 32-bit integer, so this is the largest ceiling
        // expressible, and one above it is a refusal rather than a number silently wrapped into one
        // the author did not write.
        c.eq("2^31-1", 2147483647, upload("\"maxBytes\":2147483647,").maxBytes)
        refuses("\"maxBytes\":2147483648,", "\$.kind.maxBytes")
    }

    // ── Decode: the four refusals, each beside its corrected twin ─────────────

    c.check("aByteCeilingWrittenAsAStringIsRefusedAndItsTwinDecodes") {
        // `reject-upload-maxbytes-nonint`. A host that read the digits out of the string would
        // accept a bound no other host could.
        refuses("\"maxBytes\":\"5242880\",", "\$.kind.maxBytes")
        c.eq("twin", 5242880, upload("\"maxBytes\":5242880,").maxBytes)
    }

    c.check("aZeroByteCeilingIsRefusedAndItsTwinDecodes") {
        // `reject-upload-maxbytes-nonpositive`. A ceiling of zero is not a small ceiling; it is an
        // upload that can accept no file at all.
        refuses("\"maxBytes\":0,", "\$.kind.maxBytes")
        refuses("\"maxBytes\":-1,", "\$.kind.maxBytes")
        c.eq("twin", 1, upload("\"maxBytes\":1,").maxBytes)
    }

    c.check("aFractionalCountCeilingIsRefusedAndItsTwinDecodes") {
        // `reject-upload-maxfiles-nonint`. 7.1 retired truncation at every typed integer slot; a
        // host reading `3` would enforce a ceiling the document does not state, and the reader
        // would find out by losing a file.
        refuses("\"maxFiles\":3.5,", "\$.kind.maxFiles", multiple = true)
        c.eq("twin", 3, upload("\"maxFiles\":3,", multiple = true).maxFiles)
        // …and `3.0` is the same integer spelled with a fraction-free decimal point, which 7.1
        // accepts. Asserted because it is the boundary the refusal above could otherwise be read as
        // forbidding.
        c.eq("3.0 is the integer 3", 3, upload("\"maxFiles\":3.0,", multiple = true).maxFiles)
    }

    c.check("aZeroCountCeilingIsRefusedAndItsTwinDecodes") {
        // `reject-upload-maxfiles-nonpositive`. A multiple upload admitting zero files is a control
        // with no reachable selection.
        refuses("\"maxFiles\":0,", "\$.kind.maxFiles", multiple = true)
        c.eq("twin", 1, upload("\"maxFiles\":1,", multiple = true).maxFiles)
    }

    c.check("aCountCeilingBesideASingleFileUploadIsInertRatherThanRefused") {
        // 3.6.23 — a single-file upload admits one file by construction, so a `maxFiles` beside
        // `"multiple":false` does nothing. Deliberately NOT refused: the bytes describe a control
        // every host renders identically with or without the member, so there is nothing for a
        // decoder to be right about, and a host refusing it would reject documents every other host
        // accepts.
        val k = upload("\"maxFiles\":3,", multiple = false)
        c.eq("carried", 3, k.maxFiles)
        c.eq("still single-file", false, k.multiple)
    }

    // ── Render: the markers record the declaration and withhold the number ────

    c.check("anUndeclaredCeilingEarnsNoMarker") {
        // 3.6.23 obligation 4, second half. The marker line is ABSENT rather than empty, which is
        // what keeps the unbounded control visually identical to its pre-1548 self.
        val m = uploadCeilingMarkers(upload(""))
        c.eq("byte", false, m.byteCeilingDeclared)
        c.eq("count", false, m.fileCountCeilingDeclared)
        c.eq("isEmpty", true, m.isEmpty)
        c.eq("summary", null, m.summary)
    }

    c.check("eachCeilingIsMarkedOnItsOwn") {
        c.eq(
            "bytes alone",
            "per-file byte ceiling declared",
            uploadCeilingMarkers(upload("\"maxBytes\":5242880,")).summary,
        )
        c.eq(
            "count alone",
            "file-count ceiling declared",
            uploadCeilingMarkers(upload("\"maxFiles\":3,", multiple = true)).summary,
        )
        c.eq(
            "both, in the specification's own order",
            "per-file byte ceiling declared · file-count ceiling declared",
            uploadCeilingMarkers(upload("\"maxBytes\":5242880,\"maxFiles\":3,", multiple = true)).summary,
        )
    }

    c.check("noMarkerEverCarriesTheCeilingsValue") {
        // THE LOAD-BEARING ONE. A tier that cannot act on the number must not publish it, because
        // publishing it invites the reader to believe an enforcement that is not there — and this
        // arm enforces nothing at all, since it opens no picker. Asserted over the RENDERED TEXT
        // rather than over the booleans it is built from: the booleans structurally cannot carry a
        // number, so a check over them could not fail. This one can, the moment someone
        // "helpfully" interpolates the ceiling into the caption.
        val summary =
            uploadCeilingMarkers(upload("\"maxBytes\":5242881,\"maxFiles\":7,", multiple = true)).summary ?: ""
        if (summary.any { it.isDigit() }) {
            error(
                "a marker on this tier records THAT a ceiling was declared and never its value " +
                    "(WIRE_FORMAT.md 3.6.23 obligation 4) — got: $summary",
            )
        }
    }

    c.check("thisFloorClaimsNoEnforcement") {
        // The claim is DECLINED, and declined out loud: `enforced` is a field rather than an
        // omission so a reader can tell a decision from an oversight.
        for (clause in listOf("", "\"maxBytes\":1,", "\"maxFiles\":1,")) {
            c.eq("enforced($clause)", false, uploadCeilingMarkers(upload(clause, multiple = true)).enforced)
        }
    }

    c.check("theNodeStillCarriesBothValues") {
        // The values are WITHHELD FROM THE RENDER, not dropped from the model. An embedding app
        // that adds a real picker reads them off the node, so the projection must not be mistaken
        // for the place the ceilings live.
        val k = upload("\"maxBytes\":5242880,\"maxFiles\":3,", multiple = true)
        c.eq("maxBytes", 5242880, k.maxBytes)
        c.eq("maxFiles", 3, k.maxFiles)
    }

    uploadCeilingChecksRun = c.passed + c.failures.size
    return c.failures
}

fun main() {
    println("== upload ceilings :: decode floor + value-free markers (platform-neutral) ==")
    val failures = uploadCeilingFailures()
    if (failures.isEmpty()) {
        println(
            "PASS: $uploadCeilingChecksRun upload-ceiling checks green — the positivity floor holds " +
                "at both members and no marker carries a value.",
        )
    } else {
        println("FAIL: ${failures.size} upload-ceiling check(s) failed")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
