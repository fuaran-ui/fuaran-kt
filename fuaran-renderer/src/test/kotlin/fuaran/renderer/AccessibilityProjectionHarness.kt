// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.Accessibility
import fuaran.ui.JsonBool
import fuaran.ui.JsonString
import fuaran.ui.LiteralText
import fuaran.ui.Markdown
import fuaran.ui.Node
import fuaran.ui.NodeKind
import fuaran.ui.QueryBinding
import fuaran.ui.StateBinding
import fuaran.ui.StaticBinding
import fuaran.ui.TextSource
import fuaran.ui.decodeNode

/**
 * The accessibility projection's MAPPING DECISIONS, asserted in the plain-JVM gate.
 *
 * These assertions used to live in the `:fuaran-renderer` Robolectric source set, because the
 * projection's result was typed in Compose vocabulary and could not be constructed off an
 * Android-SDK machine. They therefore ran on CI and nowhere else, which made the drop set — the
 * whole content of the "dropped, never silently" policy — a decision nobody could re-check on the
 * machine they were changing it from. The projection is split now (`AccessibilityProjection.kt`
 * carries no Compose import), so the decisions are assertable wherever the repo's ordinary gate
 * runs and the Robolectric leg keeps only what it alone can answer: that the emitted semantics
 * genuinely reach the tree.
 *
 * A plain `main`-driven runner rather than JUnit, matching the corpus harness: the repo builds with
 * a bare `kotlinc` and no artefact resolution, so the exit code is the gate. [a11yMappingFailures]
 * is exposed separately so the Gradle/Robolectric leg can run the same checks without duplicating
 * them.
 */

/** A minimal check collector — the same shape the corpus harness uses. */
internal class A11yChecks {
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

private fun text(s: String) = StaticBinding(JsonString(s))

private fun flag(b: Boolean) = StaticBinding(JsonBool(b))

/** How many checks [a11yMappingFailures] runs — reported so a leg that shrank is visible. */
var a11yMappingChecksRun = 0
    private set

/**
 * Every mapping assertion, returning the failures rather than throwing, so both gates can run the
 * identical set. Empty means green.
 */
fun a11yMappingFailures(): List<String> {
    val ctx = BindingContext.Empty
    val c = A11yChecks()

    // ── The absent trait ─────────────────────────────────────────────────────

    c.check("noTraitProjectsNothing") {
        val p = accessibilityProjection(null, ctx)
        if (!p.isEmpty) error("an absent trait must project nothing")
        if (p.unmapped.isNotEmpty()) error("an absent trait drops nothing: ${p.unmapped}")
    }

    // ── label ────────────────────────────────────────────────────────────────

    c.check("labelResolvesThroughTheBinding") {
        val p = accessibilityProjection(Accessibility(label = text("Save changes")), ctx)
        c.eq("label", "Save changes", p.label)
        if (p.isEmpty) error("a projected label is not an empty projection")
    }

    c.check("labelResolvingEmptyIsDropped") {
        // Mirrors the reference projection's empty filter — and the stake is higher here than
        // parity: an empty `contentDescription` would ERASE the node's natural name.
        val p = accessibilityProjection(Accessibility(label = text("")), ctx)
        c.eq("label", null, p.label)
        if (!p.isEmpty) error("an empty resolved label must project nothing")
    }

    c.check("anUnresolvableLabelBindingIsDroppedNotRenderedAsAPlaceholder") {
        // A host-owned binding has no wire-surviving value at the render floor, so it resolves
        // empty — and an empty label is dropped, never emitted as "".
        val p = accessibilityProjection(Accessibility(label = QueryBinding("orders")), ctx)
        c.eq("label", null, p.label)
    }

    // ── role ─────────────────────────────────────────────────────────────────

    c.check("theMappedRoleTokensCarryTheirSemantics") {
        val button = accessibilityProjection(Accessibility(role = "button"), ctx)
        c.eq("button role", SemanticRole.Button, button.role)
        c.eq("button drops", emptyList<A11ySlot>(), button.unmapped)

        // `tab` maps here and NOT on the sibling native surface, which is the concrete half of
        // "the two surfaces have different drop sets by design". Asserting it by value is what
        // keeps that a decision rather than an accident of which arm someone wrote first.
        val tab = accessibilityProjection(Accessibility(role = "tab"), ctx)
        c.eq("tab role", SemanticRole.Tab, tab.role)
        c.eq("tab drops", emptyList<A11ySlot>(), tab.unmapped)

        val heading = accessibilityProjection(Accessibility(role = "heading"), ctx)
        if (!heading.heading) error("`heading` must set the heading flag")
        c.eq("heading role", null, heading.role)
        c.eq("heading drops", emptyList<A11ySlot>(), heading.unmapped)
    }

    c.check("everyOtherWireRoleIsReportedUnmappedNotApproximated") {
        // The decision this pins: a role with no Compose semantics that MEANS what it means is
        // dropped rather than approximated. `link` is the one that most invites a substitute —
        // Role.Button would announce a link as a button, which is a mis-statement, not a partial.
        for (token in listOf(
            "link", "dialog", "alert", "status", "banner", "navigation", "main", "form", "region",
            "progressbar", "tablist", "tabpanel",
        )) {
            val p = accessibilityProjection(Accessibility(role = token), ctx)
            c.eq("role $token should carry no semantics role", null, p.role)
            if (p.heading) error("role $token is not a heading")
            c.eq("role $token should report the drop", listOf(A11ySlot.Role), p.unmapped)
            if (!p.isEmpty) error("role $token alone should project nothing")
        }
    }

    c.check("aCustomRoleIsUnmappedByDefinition") {
        val p = accessibilityProjection(Accessibility(role = "treegrid"), ctx)
        c.eq("custom role drops", listOf(A11ySlot.Role), p.unmapped)
    }

    c.check("theRoleTokenIsMatchedExactlyNotCaseFolded") {
        // The wire's tokens are lowercase ARIA roles and the reference emits the token the author
        // declared. Accepting "Button" here would honour a spelling no HTML tier honours.
        val p = accessibilityProjection(Accessibility(role = "Button"), ctx)
        c.eq("case-folded role", null, p.role)
        c.eq("case-folded role drops", listOf(A11ySlot.Role), p.unmapped)
    }

    // ── labelledBy / describedBy — the drop set ──────────────────────────────

    c.check("labelledByAndDescribedByAreReportedUnmapped") {
        val p =
            accessibilityProjection(
                Accessibility(labelledBy = "heading-1", describedBy = "help-1"),
                ctx,
            )
        c.eq("drops", listOf(A11ySlot.LabelledBy, A11ySlot.DescribedBy), p.unmapped)
        // Reported, and projecting nothing — the composable is emitted untouched.
        if (!p.isEmpty) error("an id-referenced pair projects nothing")
    }

    c.check("theDropSetIsReportedInWireSlotOrder") {
        // Order is part of the contract: the drop set is meant to be READ, and a set that reorders
        // itself per input is one nobody can assert against.
        val p =
            accessibilityProjection(
                Accessibility(labelledBy = "h", describedBy = "d", role = "banner"),
                ctx,
            )
        c.eq(
            "wire-slot order",
            listOf(A11ySlot.LabelledBy, A11ySlot.DescribedBy, A11ySlot.Role),
            p.unmapped,
        )
    }

    // ── liveRegion — the slot this platform maps EXACTLY ─────────────────────

    c.check("politeAndAssertiveKeepTheirDistinction") {
        // This surface carries the politeness distinction the wire declares, so nothing is lost
        // here — unlike the sibling native surface, whose declarative analogue cannot express it.
        // The two answer to the reference `aria-live`, not to each other.
        c.eq(
            "polite",
            LiveRegionKind.Polite,
            accessibilityProjection(Accessibility(liveRegion = "polite"), ctx).liveRegion,
        )
        c.eq(
            "assertive",
            LiveRegionKind.Assertive,
            accessibilityProjection(Accessibility(liveRegion = "assertive"), ctx).liveRegion,
        )
    }

    c.check("offProjectsNothingAndIsNotADrop") {
        // `off` asserts "do not announce", which IS the platform default — so the faithful
        // projection is the absence of a live region, not a reported drop.
        val p = accessibilityProjection(Accessibility(liveRegion = "off"), ctx)
        c.eq("off liveRegion", null, p.liveRegion)
        c.eq("off drops", emptyList<A11ySlot>(), p.unmapped)
        if (!p.isEmpty) error("`off` alone projects nothing")
    }

    c.check("anUnrecognisedLiveRegionTokenIsADropNotAGuess") {
        val p = accessibilityProjection(Accessibility(liveRegion = "urgent"), ctx)
        c.eq("unknown liveRegion", null, p.liveRegion)
        c.eq("unknown liveRegion drops", listOf(A11ySlot.LiveRegion), p.unmapped)
    }

    // ── hidden — the aria-hidden analogue ────────────────────────────────────

    c.check("hiddenTrueProjectsTheHiddenFlag") {
        // The author's intent is to REMOVE the subtree from the accessibility tree, and that intent
        // must survive the crossing to a native surface with the same force it has on the web.
        val p = accessibilityProjection(Accessibility(hidden = flag(true)), ctx)
        if (!p.hidden) error("a resolved-true hidden must project")
        if (p.isEmpty) error("a hidden projection is not empty")
    }

    c.check("hiddenFalseProjectsNothing") {
        // Mirrors the reference: only a resolved-true `hidden` emits. Hiding on `false` would
        // invert the author's assertion.
        val p = accessibilityProjection(Accessibility(hidden = flag(false)), ctx)
        if (p.hidden) error("a resolved-false hidden must not project")
        if (!p.isEmpty) error("a false hidden alone projects nothing")
    }

    c.check("hiddenResolvesThroughSeededState") {
        val seeded = BindingContext(state = mapOf("decorative" to JsonBool(true)))
        val p =
            accessibilityProjection(
                Accessibility(hidden = StateBinding("decorative", JsonBool(false))),
                seeded,
            )
        if (!p.hidden) error("seeded state must win over the declared default")
    }

    // ── The whole trait at once ──────────────────────────────────────────────

    c.check("aFullyPopulatedTraitProjectsTheMappableHalfAndReportsTheRest") {
        val p =
            accessibilityProjection(
                Accessibility(
                    label = text("Open the report"),
                    labelledBy = "heading-1",
                    describedBy = "help-1",
                    role = "button",
                    liveRegion = "polite",
                    hidden = flag(true),
                ),
                ctx,
            )
        c.eq("label", "Open the report", p.label)
        c.eq("role", SemanticRole.Button, p.role)
        c.eq("liveRegion", LiveRegionKind.Polite, p.liveRegion)
        if (!p.hidden) error("hidden must project")
        c.eq("drops", listOf(A11ySlot.LabelledBy, A11ySlot.DescribedBy), p.unmapped)
    }

    c.check("theProjectionNeverThrowsOnAnUnmappableTrait") {
        // The policy as a property rather than a sentence: a render surface does not REFUSE a tree
        // the wire declares valid. Every slot combination projects some (possibly empty) result and
        // reports what it could not carry — a surface that rejected one would fork the vocabulary
        // by platform.
        val p =
            accessibilityProjection(
                Accessibility(labelledBy = "a", describedBy = "b", role = "tablist"),
                ctx,
            )
        if (!p.isEmpty) error("an unmappable-only trait projects nothing")
        c.eq("drop count", 3, p.unmapped.size)
    }


    // ── The tooltip trait (WIRE_FORMAT.md 3.1, Phase 1112) ───────────────────
    //
    // Asserted HERE, in the platform-neutral leg, for the reason the drop set already records: a
    // decision testable only on a machine carrying the Android SDK is a decision nobody re-checks.
    // Note these use the NODE overload — the hint sits on the envelope beside `accessibility`, not
    // inside it, so the trait overload structurally cannot see it.

    fun node(kind: NodeKind = Markdown(LiteralText("Body")), tooltip: TextSource? = null, a11y: Accessibility? = null) =
        Node(id = "n", kind = kind, accessibility = a11y, tooltip = tooltip)

    c.check("aTooltipIsReportedDroppedRatherThanProjected") {
        // §3.1: the hint is a DESCRIPTION and MUST NOT be projected as a NAME. Compose has exactly
        // one announcement channel for a node's own text, and writing to it makes the string the
        // NAME — so the one available projection is the one the specification forbids in terms.
        // Dropped, then, and reported, which is this surface's whole policy for an unmappable slot.
        val p = accessibilityProjection(node(tooltip = LiteralText("Updated nightly.")), ctx)
        c.eq("drops", listOf(A11ySlot.Tooltip), p.unmapped)
        if (!p.isEmpty) error("a hint must project NO semantics — projecting it would make it the name")
        if (p.label != null) error("the hint reached the accessible NAME, which §3.1 forbids in terms")
    }

    c.check("aTooltipAndAnAccessibilityLabelAreDIFFERENTSlots") {
        // The case §3.1 is written about: an icon-only control needs both, saying different things.
        // The label must survive as the name AND the hint must be reported dropped — a surface that
        // conflated them would leave such a control with two competing names and no description.
        val p =
            accessibilityProjection(
                node(
                    tooltip = LiteralText("Exports the rows currently shown, not the whole table."),
                    a11y = Accessibility(label = text("Download CSV"), describedBy = "note"),
                ),
                ctx,
            )
        c.eq("label", "Download CSV", p.label)
        // BOTH description-shaped slots drop, and in this order: the trait's own first, then the
        // envelope's. That the two drop together is the honest statement — this platform has no
        // description channel at all, not merely no `aria-describedby`.
        c.eq("drops", listOf(A11ySlot.DescribedBy, A11ySlot.Tooltip), p.unmapped)
    }

    c.check("anEmptyOrWhitespaceHintIsNotEVENReportedAsDropped") {
        // §3.1 obligation 5: a host emits NOTHING AT ALL when the hint resolves to empty or
        // whitespace — "advertising a description that is not there is worse than silence". So the
        // condition is on the RESOLVED value, and there was nothing to carry, which is a different
        // fact from a hint this platform could not carry.
        for (hint in listOf("", "   ", "\t\n")) {
            val p = accessibilityProjection(node(tooltip = LiteralText(hint)), ctx)
            if (p.unmapped.isNotEmpty()) error("a hint resolving to '${hint.trim()}' has nothing to drop: ${p.unmapped}")
        }
    }

    c.check("aNodeWithNoHintReportsNoTooltipDrop") {
        // The go-red half of the three above: without it, a projection that reported `Tooltip`
        // unconditionally would pass every one of them.
        val p = accessibilityProjection(node(), ctx)
        if (A11ySlot.Tooltip in p.unmapped) error("a node with no hint must not report one dropped")
    }

    c.check("theHintSTILLDecodesAndIsReachableOnTheModel") {
        // Dropped by the RENDER floor is not dropped by the surface. The trait decodes and reaches
        // `Node.tooltip`, so an embedding app that has a description channel of its own can project
        // it — which is what makes the drop a statement about this floor rather than about the
        // Kotlin surface.
        val n = decodeNode("{\"id\":\"t\",\"kind\":{\"\$type\":\"Markdown\",\"text\":\"Body\"},\"tooltip\":\"Updated nightly.\"}")
        c.eq("tooltip", LiteralText("Updated nightly."), n.tooltip)
    }

    a11yMappingChecksRun = c.passed + c.failures.size
    return c.failures
}

fun main() {
    println("== accessibility projection :: mapping decisions (platform-neutral) ==")
    val failures = a11yMappingFailures()
    if (failures.isEmpty()) {
        println("PASS: $a11yMappingChecksRun mapping checks green — the decisions and the drop set hold.")
    } else {
        println("FAIL: ${failures.size} mapping check(s) failed")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
