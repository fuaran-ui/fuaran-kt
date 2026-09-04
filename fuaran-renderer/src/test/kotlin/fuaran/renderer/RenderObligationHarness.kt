// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.Audio
import fuaran.ui.Video
import java.io.File

/**
 * Executable render-obligation conformance (WIRE_FORMAT.md 13) — **this surface's adoption**.
 *
 * Decode conformance here is corpus-driven and strong. Render obligations were prose: 3.6.2-3.6.6
 * state, in sentences, that an accessible name is always emitted, that `autoplay` never appears
 * without `muted`, that an audio transport has no autoplay pathway at all, that a refused source
 * emits no affordance. A surface can decode every fixture in the corpus and silently fail every one
 * of those — none is a missing discriminator arm, so neither the decode harness nor the `else`-free
 * dispatch spine reaches them.
 *
 * So the artefact carries them now, and this gate asserts FROM the artefact rather than from a hand
 * list beside it. Three consequences, which are the whole point:
 *
 *  * The ENUMERATION is the corpus artefact's. A newly declared obligation on a kind this surface
 *    renders arrives here as a claim with no checker and turns the gate RED — not as a paragraph a
 *    future reader may re-read.
 *  * NOT CHECKED IS NOT PASSED. Every claim this surface does not assert is printed by name with
 *    the section that states it, and fails the gate unless it carries a **declared exemption**.
 *    Silence is never an answer.
 *  * The go-red property is PROVEN, not asserted: [statusOf] is exercised against a claim no
 *    checker covers and must report it unchecked — the shape a new obligation takes on the day it
 *    lands — and the artefact path is overridable so a perturbed scratch copy can be run against
 *    without touching the shared corpus.
 *
 * **THIS SURFACE IS A RENDER PROJECTION, AND DECLARED EXEMPTIONS ARE THE POINT.** It carries no
 * playback engine and no network image loader, so several claims are vacuous here rather than met.
 * A declared exemption naming the structural fact is a CONFORMANT answer; an obligation silently
 * absent from the registry is not, and the difference between the two is the whole reason the
 * artefact enumerates rather than describes. What each exemption says is recorded in
 * [DECLARED_EXEMPTIONS], one full sentence each; the honest floor those sentences describe is the
 * decision this repo's `CLAUDE.md` records for the media and image arms, not a gap left open.
 *
 * **The gate is SPLIT across two legs, and the split is what makes it re-checkable.** The same
 * split, for the same reason, as the accessibility and trend-sentiment projections beside it: a
 * claim about EMITTED OUTPUT on a Compose surface can only be observed inside a composition, and
 * the Robolectric leg that provides one needs an Android SDK — so a gate typed entirely in Compose
 * vocabulary would run on the CI box and nowhere else, and a decision testable on one platform is a
 * decision nobody re-checks. Therefore:
 *
 *  * the reader, the reporting surface, the registries, the exemptions and every gate check over
 *    them live HERE, in the plain-JVM leg, and run on whatever machine the next change is made
 *    from;
 *  * the checkers that must observe a composition live in `RenderObligationTest`, are named in
 *    [COMPOSE_CHECKER_KEYS] so this leg's [statusOf] can see them, and that declaration is itself
 *    guarded there — a key declared here with no test beside it fails that leg, so the declaration
 *    cannot drift into a claim nobody runs.
 */

/** A minimal check collector — the shape the sibling harnesses use, kept local so this leg depends
 *  on nothing another leg establishes. */
internal class ObligationChecks {
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
}

// --------------------------------------------------------------------------- //
// The plain-JVM checkers
// --------------------------------------------------------------------------- //

/**
 * `Media/no-autoplay-pathway` — the STRUCTURAL half, and on this surface the strongest form the
 * claim can take.
 *
 * 3.6.6's rule is that the `Audio` variant has no autoplay pathway *at all*. Here that is not a
 * render decision to be checked in output but a property of the TYPE: the case declares no slot, so
 * an `{"$type":"Audio","autoplay":true}` document lands the value nowhere and no render arm below
 * can reach one. Asserting it reflectively is what makes the property survive someone "tidying" the
 * two cases into one record with a `mediaType` string — the exact refactor the sum type exists to
 * forbid. The emitted-output half (an audio tile stating no autoplay) rides the Compose leg.
 *
 * The probe is verified before it is trusted: [Video] MUST carry an autoplay field, so a reflective
 * search that can no longer find such a field anywhere fails here rather than reporting a vacuous
 * green on [Audio].
 */
private fun checkNoAutoplayPathwayStructurally() {
    val autoplayish = { name: String -> name.contains("autoplay", ignoreCase = true) }

    // Verify the probe. Without this leg a rename of the slot — or a reflection API that stopped
    // reporting the field — would make the assertion below unfalsifiable and permanently green.
    if (Video::class.java.declaredFields.none { autoplayish(it.name) }) {
        error(
            "the reflective probe found no autoplay field on Video either, so its silence about Audio " +
                "proves nothing — the slot was renamed, or this probe no longer sees fields",
        )
    }

    if (Audio::class.java.declaredFields.any { autoplayish(it.name) }) {
        error("the Audio case grew an autoplay slot — 3.6.6's strongest rule is no longer structural")
    }
    // `muted` rides `autoplay`, so a muted slot on the variant with no autoplay would be the same
    // defect arriving under the other name.
    if (Audio::class.java.declaredFields.any { it.name.contains("muted", ignoreCase = true) }) {
        error("the Audio case grew a muted slot — there is no autoplay here for muting to ride")
    }
}

/**
 * The claims asserted in this plain-JVM leg, keyed by the artefact's WIRE tokens because the
 * enumeration they are matched against comes from the artefact.
 */
internal val PLAIN_JVM_CHECKERS: Map<String, () -> Unit> =
    linkedMapOf(
        "Media/no-autoplay-pathway" to ::checkNoAutoplayPathwayStructurally,
    )

/**
 * The claims asserted in the Compose leg (`RenderObligationTest`), declared here so this leg's
 * [statusOf] reports the union rather than under-reporting what the surface actually checks.
 *
 * This is a DECLARATION, and a declaration that nothing verified would be exactly the loophole this
 * mechanism removes — so `RenderObligationTest` asserts that its own checker registry equals this
 * set. A key added here with no test beside it fails that leg; a test added there and not named
 * here is an orphan and fails the orphan check below.
 */
internal val COMPOSE_CHECKER_KEYS: Set<String> =
    linkedSetOf(
        // The one obligation that binds a surface rendering no playback: `label` is required on the
        // wire because a transport is never decorative, and it lands on the tile as the accessible
        // name, on both variants, always.
        "Media/accessible-name-always",
        // The emitted-output half of the structural claim above: an audio tile states no autoplay.
        "Media/no-autoplay-pathway",
        // 3.6.6 text tracks (Phase 1110). These three are ASSERTED rather than exempted, and the
        // distinction is worth stating because the media arm's other two claims are not: those are
        // about ATTRIBUTES a player emits, where these three are about what the track MENU says —
        // which order it is in, which entry carries the default claim, and where the transcript
        // sits relative to the transport. All three are ordinary logic over the decoded list, so a
        // surface with no playback engine can and does get them exactly right or exactly wrong.
        "Media/authored-child-order",
        "Media/single-default-per-kind",
        "Media/transcript-disclosure-named",
        // 3.6.8 (Phase 1111) — the one embed claim that binds a surface mounting no browsing
        // context, on `Media/accessible-name-always`'s argument one kind over.
        "Embed/accessible-name-always",
        // 3.6.12 (Phase 1120) — the kind's only declared claim, and one this floor honours in
        // full: a row states its OWN label rather than letting a name be computed from a branch.
        "Tree/accessible-name-always",
        // 25.4, the UNCARDED path — see the note on the exemptions below.
        "Custom/unregistered-custom-labelled",
    )

// --------------------------------------------------------------------------- //
// The declared exemptions
// --------------------------------------------------------------------------- //

/**
 * Obligations this surface declares it does NOT check, each with a reason.
 *
 * NON-EMPTY is the correct state here, and it is the honest one. This is a render projection over
 * the reference core with no playback engine and no network image loader: the media arm is a
 * labelled transport tile and the image arm a labelled placeholder box, both real arms of the
 * exhaustive floor and neither a player. Several claims are therefore VACUOUS here — there is no
 * attribute for them to be true or false of — and a checker asserting the absence of output this
 * surface never produces would be a green that guards nothing.
 *
 * Each reason names the structural fact rather than saying "not applicable", because the reason is
 * what a reader of the run has to judge, and because the forward-coupling rules in this repo's
 * `CLAUDE.md` bind a future real player or loader arm to discharge these for real in the same
 * change that adds it. An exemption is a statement about THIS floor, never a permanent excuse.
 */
internal val DECLARED_EXEMPTIONS: Map<String, String> =
    linkedMapOf(
        "Media/autoplay-muted-pairing" to
            "this floor carries no playback engine, so nothing here ever starts playing and neither an " +
                "autoplay nor a muted attribute is ever emitted — the claim is vacuous on this surface, and " +
                "the transport tile STATES the declaration (as the inseparable token `autoplay+muted`, pinned " +
                "by a supporting test) rather than acting on it; a real player arm must emit the pair or " +
                "neither, per the forward-coupling rule in this repo's CLAUDE.md",
        "Media/refused-source-dropped" to
            "neither a `poster` nor a text-track source is ever fetched on this floor and no destination is " +
                "emitted anywhere in the render — the tile records only THAT a poster was declared, and a " +
                "track row states its kind, language and label and never its URL — so there is no emitted " +
                "source for the URL-scheme and egress floor to have dropped; a real player arm must route " +
                "`src`, `poster` and every track through FuaranUrlPolicy and DROP a refused poster or track " +
                "rather than substitute one",
        "Embed/sandbox-always-exactly-declared" to
            "this floor mounts no browsing context, so there is no `sandbox` attribute for the claim to be " +
                "true or false of, and the token split it turns on — three relaxations riding `sandbox` while " +
                "AllowFullscreen rides `allow` — is HTML attribute vocabulary this surface does not emit at " +
                "all; what the tile does instead is STATE the granted set in the vocabulary's declaration " +
                "order and say plainly that an empty list grants nothing, both pinned by a supporting test, " +
                "and a real frame arm must emit the attribute unconditionally and empty in the same change " +
                "that adds it",
        "Embed/refused-embed-source-omitted" to
            "no source is emitted or fetched here, because no frame is mounted — the tile never shows the " +
                "destination at all, pinned by a supporting test — so there is no attribute whose omission " +
                "could distinguish a conformant surface from a broken one; the 19.1 class itself IS " +
                "implemented, as the `Embed.sanitizedSrc` accessor a consumer must consult, and separately " +
                "the C-ABI session this surface renders through opens under a deny-non-local-egress policy " +
                "exposing no knob, so a remote source is already refused on that path",
        "Image/alt-always-emitted" to
            "this floor has no network image loader, so the arm renders a labelled placeholder box rather " +
                "than an image element and there is no alternative-text attribute to emit always; the " +
                "placeholder's visible caption additionally substitutes the generic word `image` for an empty " +
                "alt, which collapses the decorative-versus-unnamed distinction this claim turns on, so it " +
                "cannot honestly be reported asserted here",
        "Image/anchor-affordance-on-expandable" to
            "this floor emits no navigable anchor for an image at all — there is no image element and no " +
                "loader behind one — so a declared `expandable` reaches no affordance for a checker to " +
                "assert; a real loader arm owes a working link to the full-size asset, routed through " +
                "FuaranUrlPolicy, in the same change that adds it",
        "Image/refused-src-no-affordance" to
            "no expansion affordance is emitted on this floor under any `src`, refused or permitted, so " +
                "there is no affordance whose absence under a refusal could distinguish a conformant surface " +
                "from a broken one — the claim only becomes checkable when the affordance exists",
        "Image/figure-caption-outside-link" to
            "this floor renders neither an expansion anchor nor a figure-and-caption structure — the " +
                "placeholder box carries the resolved alt text alone — so there is no nesting order between a " +
                "caption and a link target for a checker to pin",
        "Image/srcset-ascending-by-width" to
            "no responsive candidate is emitted on this floor, because there is no image element to carry " +
                "one; the decoder deliberately preserves the AUTHORED order of `srcSet` (ordering is a " +
                "renderer's presentation rule, recorded in this repo's CLAUDE.md), and this renderer emits no " +
                "candidate list to order",
    )

/** The canonical default reason, used for any (kind, claim) pair neither registry covers. */
internal const val NO_CHECKER_REASON =
    "no checker registered in RenderObligationHarness.kt and no declared exemption — add one, or " +
        "declare why this host cannot check it"

/**
 * This surface's answer for one declared obligation.
 *
 * There is no `NotRendered` branch: the dispatch spine is an `else`-free exhaustive `when` over the
 * sealed `NodeKind`, so this surface renders every kind the wire declares and every declared
 * obligation is one it owes.
 */
internal fun statusOf(kind: String, claimId: String): ObligationOutcome {
    val key = "$kind/$claimId"
    if (key in PLAIN_JVM_CHECKERS || key in COMPOSE_CHECKER_KEYS) return ObligationOutcome.Asserted
    DECLARED_EXEMPTIONS[key]?.let { return ObligationOutcome.Unchecked(it) }
    return ObligationOutcome.Unchecked(NO_CHECKER_REASON)
}

// --------------------------------------------------------------------------- //
// The gate
// --------------------------------------------------------------------------- //

/** How many checks [renderObligationFailures] runs — reported so a leg that shrank is visible. */
var renderObligationChecksRun = 0
    private set

/**
 * Every assertion, returning the failures rather than throwing, so both gates run the identical set
 * without a second copy of the expectations. Empty means green.
 *
 * [report] is filled with the full projection so the caller can PRINT every unasserted line before
 * the gate decides — not checked is not passed, and an exempted claim must be visible in the run
 * rather than inferable from its absence.
 */
fun renderObligationFailures(
    artifact: File,
    report: MutableList<ObligationReport> = mutableListOf(),
): List<String> {
    val c = ObligationChecks()
    val manifest = parseRenderFidelityManifest(artifact.readText())
    report.clear()
    report.addAll(reportObligations(manifest, ::statusOf))

    // ── The gate ──────────────────────────────────────────────────────────────
    c.check("everyDeclaredObligationIsAssertedOrDeclaredExempt") {
        // A suite reading the wrong file, or a stale artefact, enumerates nothing and passes while
        // checking nothing. Refuse that before judging anything else.
        if (report.isEmpty()) {
            error(
                "the manifest declares no obligations at all — either the artefact is stale or this gate is " +
                    "reading the wrong file (${artifact.absolutePath}), and either way it is asserting nothing",
            )
        }
        val undeclared =
            unassertedObligations(report)
                .filter { "${it.kind}/${it.claimId}" !in DECLARED_EXEMPTIONS }
                .map { "${it.kind}/${it.claimId} [${it.section}]" }
        if (undeclared.isNotEmpty()) {
            error(
                "a render obligation this surface owes has no checker: assert it, or add a declared " +
                    "exemption saying why this surface cannot — $undeclared",
            )
        }
    }

    // ── The go-red proof ──────────────────────────────────────────────────────
    c.check("anObligationWithNoCheckerIsReportedUNCHECKED") {
        // The shape a NEWLY-DECLARED obligation takes on the day it lands: a (kind, claim) pair
        // neither registry covers. Without this probe the gate above could be green because the
        // classification never reports anything — the completeness check that cannot fail.
        val outcome = statusOf("Markdown", "accessible-name-always")
        val unchecked =
            outcome as? ObligationOutcome.Unchecked
                ?: error("an unregistered (kind, claim) must be reported UNCHECKED, got $outcome")
        if (!unchecked.reason.contains("no checker registered")) {
            error("the reason must be one a reader can act on, got: ${unchecked.reason}")
        }
        // …and the gate's own filter must classify it as unasserted, which is what turns it red.
        val probe =
            ObligationReport("Markdown", "accessible-name-always", "", "probe", outcome)
        if (unassertedObligations(listOf(probe)).size != 1) {
            error("unassertedObligations did not classify an unchecked line as unasserted")
        }
    }

    // ── The vocabulary seam ───────────────────────────────────────────────────
    c.check("everyDeclaredClaimResolvesAgainstTheClosedVocabulary") {
        // A row naming a claim the vocabulary omits is unresolvable: a surface keying its registry
        // off the vocabulary could never report it, and a surface must never accept a claim it
        // cannot name.
        val vocabulary = manifest.obligationVocabulary.map { it.id }.toSet()
        if (vocabulary.isEmpty()) error("the artefact carries no obligation vocabulary")
        val unresolvable =
            allObligations(manifest)
                .filter { (_, o) -> o.id !in vocabulary }
                .map { (kind, o) -> "$kind/${o.id}" }
        if (unresolvable.isNotEmpty()) {
            error("a kind declares an obligation the closed vocabulary does not carry: $unresolvable")
        }
    }

    c.check("everyDeclaredClaimCarriesASectionAndAStatement") {
        // An obligation with no section is an assertion about a surface's habits rather than about
        // the specification, and is not admissible.
        for ((kind, o) in allObligations(manifest)) {
            if (!o.section.contains("WIRE_FORMAT.md")) error("$kind/${o.id}: no spec section (${o.section})")
            if (o.statement.isEmpty()) error("$kind/${o.id}: no normative statement")
        }
    }

    // ── The registries are not themselves a second source of truth ────────────
    c.check("noCheckerOrExemptionNamesAnObligationTheManifestDoesNotDeclare") {
        // A checker for a claim no row declares is a stale assertion: it passes forever and guards a
        // contract that has moved. A stale EXEMPTION is the same defect pointing the other way — it
        // excuses a claim nobody owes, and, worse, it would keep silencing the gate if the claim
        // ever came back under the same id. Both are checked; the worked example checks only the
        // first because its exemption map is empty, which is not the case this surface is in.
        val declared = allObligations(manifest).map { (kind, o) -> "$kind/${o.id}" }.toSet()
        val orphans =
            (PLAIN_JVM_CHECKERS.keys + COMPOSE_CHECKER_KEYS + DECLARED_EXEMPTIONS.keys)
                .filter { it !in declared }
                .sorted()
        if (orphans.isNotEmpty()) {
            error(
                "a checker or exemption names an obligation no manifest row declares — either the row was " +
                    "removed or the entry was never declared: $orphans",
            )
        }
    }

    c.check("noObligationIsBothAssertedAndExempted") {
        // A key in both registries is a contradiction the gate would otherwise resolve silently in
        // favour of "asserted", hiding an exemption someone wrote down deliberately.
        val both =
            (PLAIN_JVM_CHECKERS.keys + COMPOSE_CHECKER_KEYS)
                .filter { it in DECLARED_EXEMPTIONS }
                .sorted()
        if (both.isNotEmpty()) error("asserted AND declared exempt — the two say opposite things: $both")
    }

    c.check("everyDeclaredExemptionCarriesAReasonNotALabel") {
        // "not applicable" is not a reason; the structural fact is. A one-word exemption is how a
        // gap re-enters wearing the shape of a decision.
        for ((key, reason) in DECLARED_EXEMPTIONS) {
            if (reason.length < 80) error("$key: the exemption reason is too short to name a structural fact")
            if (reason.trim().equals("not applicable", ignoreCase = true)) error("$key: 'not applicable' is a label")
        }
    }

    // ── The checkers themselves, run BY NAME ──────────────────────────────────
    //
    // So a failing obligation names the claim it broke rather than surfacing as one opaque red.
    for ((key, checker) in PLAIN_JVM_CHECKERS) c.check("owes $key", checker)

    renderObligationChecksRun = c.passed + c.failures.size
    return c.failures
}

fun main() {
    println("== render-obligation conformance (WIRE_FORMAT.md 13) ==")
    val artifact = locateRenderFidelityArtifact()
    if (artifact == null) {
        // A standalone clone of this repo alone legitimately has no corpus, and the skip is honest
        // there. The dishonest case — a cross-host checkout whose corpus has moved, where a skip
        // would mean this gate certified NOTHING while reporting success — is discriminated and
        // failed loudly by the `:fuaran-ui` decode harness later in the same run, naming every path
        // it tried. One copy of that discrimination is enough; two would drift.
        println(
            "SKIP: render-fidelity.json not found (set $RENDER_FIDELITY_ENV or FUARAN_CORPUS). Nothing to certify.",
        )
        return
    }
    println("Artefact: ${artifact.absolutePath}")

    val report = mutableListOf<ObligationReport>()
    val failures = renderObligationFailures(artifact, report)

    // NOT CHECKED IS NOT PASSED. Everything this surface did not assert is printed by name and
    // section BEFORE the gate decides, so an exempted claim is visible in the run.
    val unmet = unassertedObligations(report)
    println()
    println("Obligations declared: ${report.size} — asserted ${report.size - unmet.size}, not asserted ${unmet.size}")
    for (line in unmet) println("  render obligation not asserted: ${describeObligationReport(line)}")

    println()
    if (failures.isEmpty()) {
        println(
            "PASS: $renderObligationChecksRun checks green — every declared obligation is asserted or " +
                "declared exempt with a reason, and the go-red probe reports an unregistered claim UNCHECKED.",
        )
    } else {
        println("FAIL: ${failures.size} obligation check(s) failed")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
