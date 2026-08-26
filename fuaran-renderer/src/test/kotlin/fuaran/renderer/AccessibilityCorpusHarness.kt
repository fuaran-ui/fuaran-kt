// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.decodeNode
import java.io.File

/**
 * The accessibility projection, driven by the SHARED CORPUS.
 *
 * The mapping harness next door asserts the decisions against traits built here, so it measures
 * this surface against this surface's own idea of the wire trait. The shared corpus's accessibility
 * family is the oracle every surface answers to: all six slots, both role classes (a named
 * lower-case `region` and a deliberately-cased custom `doc-pageFooter`), both binding forms (Static
 * and State), all three `liveRegion` tokens, and the trait on both an ordinary wrapper kind and the
 * kinds whose body carries the semantics.
 *
 * This is a RENDER-COVERAGE leg, not a byte-parity one: there is no canonical encoder here, so what
 * is certified is that the decoded trait projects to the declared value AND to the declared DROP
 * SET. **The drop set is asserted exactly rather than as a superset** — a slot that becomes mappable
 * must move between the two lists and turn this red, which is what makes the drop a decision rather
 * than an omission.
 *
 * The sibling native surface runs the same shape over the same fixtures and its expectations
 * DIFFER, deliberately: it maps `link` and cannot map `tab`; this one maps `tab` and cannot map
 * `link`, and it keeps the polite/assertive distinction the sibling loses. Neither is the other's
 * parity target — both answer to the reference `aria-*` projection.
 *
 * The decode half of this family lives in the `:fuaran-ui` corpus harness, which asserts what
 * landed in the trait's six slots. This leg starts where that one stops: what the PROJECTION makes
 * of the decoded trait, which is where the mapping decisions live.
 */
private val A11Y_FAMILY =
    listOf(
        "a11y-wrapper-all-slots",
        "a11y-wrapper-state-bound",
        "a11y-alert-assertive",
        "a11y-link-labelled",
        "a11y-button-named",
        "a11y-image-decorative",
    )

private val CORPUS_CANDIDATES =
    listOf(
        "../wire-format-fixtures",
        "../../wire-format-fixtures",
        "wire-format-fixtures",
        "fuaran-kt/../wire-format-fixtures",
    )

/**
 * Locate the corpus. `FUARAN_CORPUS` is what the repo's own driver sets; `fuaran.corpus` is what
 * the Gradle unit-test task sets, so the same leg runs under either gate without a second copy of
 * the expectations.
 */
internal fun locateA11yCorpus(): File? {
    val declared = listOfNotNull(System.getenv("FUARAN_CORPUS"), System.getProperty("fuaran.corpus"))
    for (d in declared) {
        val f = File(d)
        if (File(f, "manifest.json").isFile) return f
    }
    for (c in CORPUS_CANDIDATES) {
        val f = File(c)
        if (File(f, "manifest.json").isFile) return f
    }
    return null
}

/**
 * Run the corpus-driven projection leg, returning the failures. Empty means green; a null corpus is
 * reported by the caller rather than silently read as success.
 */
fun a11yCorpusFailures(corpus: File): List<String> {
    val ctx = BindingContext.Empty
    val c = A11yChecks()

    fun project(id: String): AccessibilityProjection {
        val file = File(corpus, "nodes/$id.json")
        if (!file.isFile) error("fixture $id is absent from the corpus at ${corpus.absolutePath}")
        return accessibilityProjection(decodeNode(file.readText()), ctx)
    }

    // ── The ordinary wrapper kind, all six slots at once ─────────────────────

    c.check("a11y-wrapper-all-slots") {
        val p = project("a11y-wrapper-all-slots")
        c.eq("label", "Channel performance summary", p.label)
        // `region` is a landmark role with no Compose semantics that means what it means, so it
        // drops rather than being approximated.
        c.eq("role", null, p.role)
        if (p.heading) error("`region` is not a heading")
        // `polite` maps EXACTLY here — the politeness survives, which is the half the sibling
        // surface loses.
        c.eq("liveRegion", LiveRegionKind.Polite, p.liveRegion)
        // `hidden` is an explicit Static FALSE on the wire — distinct from omitted, and it must
        // not hide the node.
        if (p.hidden) error("an explicit Static false must not hide the node")
        c.eq("drops, exactly", listOf(A11ySlot.LabelledBy, A11ySlot.DescribedBy, A11ySlot.Role), p.unmapped)
    }

    // ── The State forms ──────────────────────────────────────────────────────

    c.check("a11y-wrapper-state-bound") {
        val p = project("a11y-wrapper-state-bound")
        // An unwritten `State` resolves to its declared `defaultValue` — the same law the HTML
        // tiers apply, so the accessible name is not lost at the render floor just because no host
        // state was seeded.
        c.eq("label", "Site footer", p.label)
        // `doc-pageFooter` is a CUSTOM role whose meaning this platform cannot know. It drops —
        // and the fact that its CASE survived decode is what the corpus fixture exists to pin, so
        // the drop must be reported rather than the token quietly matching a folded arm.
        c.eq("role", null, p.role)
        c.eq("drops, exactly", listOf(A11ySlot.Role), p.unmapped)
        // `off` maps EXACTLY — it asserts "do not announce", which is the platform default, so its
        // faithful projection is the ABSENCE of a live region rather than a drop. Asserting both
        // halves is what keeps that distinction live: the drop list above is the other half.
        c.eq("liveRegion", null, p.liveRegion)
        if (p.hidden) error("the State default is false")
    }

    // ── The announcement pair ────────────────────────────────────────────────

    c.check("a11y-alert-assertive") {
        val p = project("a11y-alert-assertive")
        c.eq("label", null, p.label)
        // `assertive` and `polite` project DIFFERENTLY here — compare with the wrapper fixture
        // above. That difference is the evidence for the exact mapping this surface claims, and it
        // is exactly what the sibling surface's identical projections evidence the loss of.
        c.eq("liveRegion", LiveRegionKind.Assertive, p.liveRegion)
        c.eq("drops, exactly", listOf(A11ySlot.Role), p.unmapped)
    }

    // ── The kinds whose body carries the semantics ───────────────────────────

    c.check("a11y-link-labelled") {
        val p = project("a11y-link-labelled")
        c.eq("label", "Read the 2026 annual report (PDF)", p.label)
        c.eq("role", null, p.role)
        c.eq("liveRegion", null, p.liveRegion)
        c.eq("no role slot, so nothing to drop", emptyList<A11ySlot>(), p.unmapped)
    }

    c.check("a11y-button-named") {
        val p = project("a11y-button-named")
        c.eq("label", "Refresh revenue figures", p.label)
        c.eq("role", SemanticRole.Button, p.role)
        c.eq("drops, exactly", emptyList<A11ySlot>(), p.unmapped)
    }

    c.check("a11y-image-decorative") {
        val p = project("a11y-image-decorative")
        // The slot whose absence went unnoticed on two hosts for weeks: `hidden` Static TRUE on
        // the empty-alt decorative shape.
        if (!p.hidden) error("a decorative image must leave the accessibility tree")
        c.eq("label", null, p.label)
        c.eq("drops, exactly", emptyList<A11ySlot>(), p.unmapped)
    }

    // ── The family itself ────────────────────────────────────────────────────

    c.check("every-trait-bearing-fixture-projects-something-or-reports-why-not") {
        // A leg that silently enumerated nothing would be a gate that checked nothing. Every
        // fixture in the family must either project a value or report a drop — a trait that did
        // neither would have decoded and vanished, which is the exact defect the drop set exists
        // to make impossible.
        for (id in A11Y_FAMILY) {
            val p = project(id)
            if (p.isEmpty && p.unmapped.isEmpty()) {
                error("$id: the trait decoded and projected nothing, reporting nothing")
            }
        }
    }

    // The enumeration guard, one level below the per-fixture checks: a family that shrank because
    // a fixture was renamed would otherwise pass by checking fewer things.
    c.check("the-family-enumerates-every-fixture") {
        val present = A11Y_FAMILY.filter { File(corpus, "nodes/$it.json").isFile }
        if (present.size != A11Y_FAMILY.size) {
            error(
                "the accessibility family enumerated ${present.size} of ${A11Y_FAMILY.size} fixtures — " +
                    "a leg that quietly checked a subset reads as covered while being untested",
            )
        }
    }

    a11yCorpusChecksRun = c.passed + c.failures.size
    return c.failures
}

/** How many checks [a11yCorpusFailures] runs — reported so a leg that shrank is visible. */
var a11yCorpusChecksRun = 0
    private set

fun main() {
    println("== accessibility projection :: corpus-driven (value AND drop set, asserted exactly) ==")
    val corpus = locateA11yCorpus()
    if (corpus == null) {
        // A standalone clone of this repo alone legitimately has no corpus, and the skip is honest
        // there. The dishonest case — a cross-host checkout whose corpus has moved, where a skip
        // would mean this gate certified NOTHING while reporting success — is discriminated and
        // failed loudly by the `:fuaran-ui` decode harness later in the same run, naming every
        // path it tried. One copy of that discrimination is enough; two would drift.
        println("SKIP: wire-format-fixtures corpus not found (set FUARAN_CORPUS). Nothing to certify.")
        return
    }
    println("Corpus: ${corpus.absolutePath}")
    val failures = a11yCorpusFailures(corpus)
    if (failures.isEmpty()) {
        println(
            "PASS: $a11yCorpusChecksRun checks green — ${A11Y_FAMILY.size} trait-bearing fixtures " +
                "projected to their declared value and drop set, asserted exactly.",
        )
    } else {
        println("FAIL: ${failures.size} projection check(s) failed")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
