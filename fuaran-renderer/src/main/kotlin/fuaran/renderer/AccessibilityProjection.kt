// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.Accessibility
import fuaran.ui.Node

/**
 * The accessibility projection — **the platform-neutral half**.
 *
 * The HTML render tiers of the Fuaran UI wire format project the trait's six slots into `aria-*`
 * attributes (`label` → `aria-label`, `labelledBy` → `aria-labelledby`, `describedBy` →
 * `aria-describedby`, `role` → `role`, `liveRegion` → `aria-live`, `hidden` → `aria-hidden`). A
 * Compose surface has no attribute bag, so the projection is a mapping onto **semantics
 * properties**, and the two vocabularies do not correspond one-for-one. The mapping — and, just as
 * importantly, the slots that have no native equivalent — is decided here; see `CLAUDE.md`,
 * "Accessibility projection", for the decision record and its rationale.
 *
 * **Why this file carries no Compose import, and why that is the point.** The decisions live here:
 * which role tokens carry a semantics role, what an empty resolved label does, and above all the
 * DROP SET. Typed in Compose vocabulary they could only be asserted on a machine carrying the
 * Android SDK, which is to say on the CI box and nowhere else — and a decision that can only be
 * tested on one platform is a decision nobody re-checks. So the result type names the platform's
 * concepts in its own enums ([SemanticRole], [LiveRegionKind]) and the thin mapping onto
 * `androidx.compose.ui.semantics` lives next door in `Accessibility.kt`, inside the only half that
 * genuinely needs Compose. The sibling native surface took the same decision for the same reason.
 *
 * **An unmappable slot is dropped, never refused — and never silently.** A render surface does not
 * reject a tree the wire declares valid; what it owes instead is an account of what it could not
 * carry, which is [AccessibilityProjection.unmapped].
 *
 * **Placement.** The reference host decides which element carries the projection
 * (`../fuaran-dotnet/docs/DECISIONS.md`, D4: the node's semantic element rather than its wrapper
 * `<div>`). A Compose surface has no wrapper: the kind arm IS the node's composable, so the
 * projection is applied at the single dispatch site in `FuaranNode` and nowhere else.
 */
data class AccessibilityProjection(
    /**
     * The resolved accessible name, or null when the slot is absent or resolves empty. An empty
     * resolved label is DROPPED rather than applied, mirroring the reference projection — an empty
     * `contentDescription` would erase the node's natural name instead of leaving it alone.
     */
    val label: String? = null,
    /** The mapped semantics role, or null when the token has none here. */
    val role: SemanticRole? = null,
    /** `role: "heading"` — a semantics FUNCTION in Compose (`heading()`), not a role VALUE. */
    val heading: Boolean = false,
    /** The live-region mode. `off` maps to null: absence IS "do not announce" here. */
    val liveRegion: LiveRegionKind? = null,
    /** Whether the node is hidden from the accessibility tree. */
    val hidden: Boolean = false,
    /**
     * Slots the author populated that this platform cannot express, in wire order. Carried rather
     * than discarded so the drop set is assertable — the whole point of the policy in `CLAUDE.md`:
     * dropped, never silently.
     */
    val unmapped: List<A11ySlot> = emptyList(),
) {
    /**
     * Nothing to apply. Note [unmapped] is deliberately NOT consulted: a trait carrying only
     * unmappable slots projects no semantics, so the composable is emitted exactly as it was before
     * this projection existed — the drop is reported, not rendered.
     */
    val isEmpty: Boolean
        get() = label == null && role == null && !heading && liveRegion == null && !hidden

    companion object {
        val None = AccessibilityProjection()
    }
}

/**
 * The slots of a node's ANNOUNCEMENT surface, named so a dropped one can be reported rather than
 * vanish: the six of the `accessibility` trait, plus the §3.1 [Tooltip] trait that sits beside it
 * on the envelope and asks the same question of this platform.
 */
enum class A11ySlot {
    Label,
    LabelledBy,
    DescribedBy,
    Role,
    LiveRegion,
    Hidden,

    /**
     * `Node.tooltip` (WIRE_FORMAT.md 3.1, Phase 1112) — reported dropped for the identical
     * structural reason [DescribedBy] is, and reported only through the NODE overload of
     * [accessibilityProjection], since the trait does not live on the `accessibility` record.
     *
     * §3.1 states the hint is a DESCRIPTION and that a host MUST NOT project it as a NAME.
     * Compose's semantics vocabulary has exactly one announcement channel for a node's own text —
     * `contentDescription` — and setting it makes the string the node's accessible NAME. So the
     * one available projection is the one the specification forbids in terms: an icon-only control
     * needs both slots saying different things, and a surface that conflated them would leave such
     * a control with two competing names and no description. `stateDescription` is not a second
     * channel either — it announces a control's STATE, so a hint routed there is announced as
     * though it were the control's current value.
     *
     * Dropped, then, and never silently — the policy this drop set exists to keep honest. The
     * trait still DECODES and reaches [fuaran.ui.Node.tooltip], so an embedding app that has a
     * description channel of its own can project it; what this surface reports is that the render
     * floor does not.
     */
    Tooltip,
}

/**
 * A semantics role this surface can carry, named platform-neutrally so the mapping decision stays
 * assertable off an Android box. Mapped to `androidx.compose.ui.semantics.Role` by `composeRole`
 * in `Accessibility.kt`.
 *
 * The enum is deliberately SMALL — it enumerates what maps, not what exists. A wire role token
 * absent from it is reported unmapped, and adding a case here is the act of deciding that the
 * platform can express one more thing.
 */
enum class SemanticRole {
    /** `role: "button"` → `Role.Button`. */
    Button,

    /** `role: "tab"` → `Role.Tab`. */
    Tab,
}

/**
 * A live-region mode, named platform-neutrally for the same reason as [SemanticRole]. Mapped to
 * `androidx.compose.ui.semantics.LiveRegionMode` by `composeLiveRegion` in `Accessibility.kt`.
 *
 * Both wire tokens that announce have a case, which is what makes this an EXACT mapping rather than
 * a partial one: the politeness distinction the wire declares survives the crossing here, unlike on
 * the sibling declarative surface where it is genuinely lost.
 */
enum class LiveRegionKind {
    /** `liveRegion: "polite"` → `LiveRegionMode.Polite`. */
    Polite,

    /** `liveRegion: "assertive"` → `LiveRegionMode.Assertive`. */
    Assertive,
}

/**
 * The role token → semantics map. Keyed on the wire's own lowercase ARIA tokens, matched EXACTLY:
 * the reference projection emits the token the author declared, and a case-folding match here would
 * accept a spelling no HTML tier honours.
 *
 * `button` and `tab` have genuine [SemanticRole] values; `heading` is a semantics function rather
 * than a role, so it rides its own flag. Every other token — the landmark roles (`banner` /
 * `navigation` / `main` / `form` / `region`), `tablist` / `tabpanel`, `dialog`, `alert`, `status`,
 * `progressbar`, `link`, and any custom role whose meaning the host cannot know — has no Compose
 * semantics that MEANS what it means, and is reported unmapped rather than approximated.
 *
 * `link` is the notable absence and is deliberate: Compose has no link role, and the nearest
 * substitute (`Role.Button`) would announce a link as a button. The sibling native surface maps
 * `link` genuinely and cannot map `tab`, which this one can — the two have DIFFERENT drop sets by
 * design, and neither is the other's parity target.
 */
internal fun roleSemanticsOf(token: String): Pair<SemanticRole?, Boolean>? =
    when (token) {
        "button" -> SemanticRole.Button to false
        "tab" -> SemanticRole.Tab to false
        "heading" -> null to true
        else -> null
    }

/**
 * Project a node's [Accessibility] trait, resolving its bindings through the render context.
 *
 * **Fidelity limits, stated rather than assumed away.** `labelledBy` / `describedBy` carry another
 * node's id, and Compose has no id-referenced labelling or description — an accessible name is a
 * value on the node, not a pointer to another one — so both are dropped. Inlining the referenced
 * node's text as this node's name would be a different assertion from `aria-labelledby`, and would
 * need a tree walk from a projection that does not have the tree.
 *
 * `liveRegion`, by contrast, maps EXACTLY: [LiveRegionKind] carries the polite/assertive
 * distinction the wire declares, so this surface loses nothing there.
 */
fun accessibilityProjection(a11y: Accessibility?, ctx: BindingContext): AccessibilityProjection {
    if (a11y == null) return AccessibilityProjection.None

    val unmapped = mutableListOf<A11ySlot>()

    val label = a11y.label?.let { ctx.resolve(it) }?.ifEmpty { null }

    if (a11y.labelledBy != null) unmapped.add(A11ySlot.LabelledBy)
    if (a11y.describedBy != null) unmapped.add(A11ySlot.DescribedBy)

    var role: SemanticRole? = null
    var heading = false
    val roleToken = a11y.role
    if (roleToken != null) {
        val mapped = roleSemanticsOf(roleToken)
        if (mapped == null) {
            unmapped.add(A11ySlot.Role)
        } else {
            role = mapped.first
            heading = mapped.second
        }
    }

    // `off` asserts "do not announce", which IS the platform default — so its faithful projection is
    // the absence of a live region, not a reported drop. An unrecognised token is a drop.
    var live: LiveRegionKind? = null
    when (a11y.liveRegion) {
        null, "off" -> Unit
        "polite" -> live = LiveRegionKind.Polite
        "assertive" -> live = LiveRegionKind.Assertive
        else -> unmapped.add(A11ySlot.LiveRegion)
    }

    return AccessibilityProjection(
        label = label,
        role = role,
        heading = heading,
        liveRegion = live,
        hidden = ctx.resolveBool(a11y.hidden),
        unmapped = unmapped.toList(),
    )
}

/**
 * Project a node's announcement surface — the shape the dispatch spine calls.
 *
 * This overload covers the ENVELOPE, so it sees one slot the trait overload cannot: the §3.1
 * `tooltip`. It is reported as a drop rather than projected, and [A11ySlot.Tooltip] carries the
 * structural reason. §3.1 obligation 5 decides the condition: a hint that resolves to empty or
 * whitespace must produce nothing at all — "advertising a description that is not there is worse
 * than silence" — so a whitespace-only hint is not even reported as dropped, because there was
 * nothing to carry.
 */
fun accessibilityProjection(node: Node, ctx: BindingContext): AccessibilityProjection {
    val base = accessibilityProjection(node.accessibility, ctx)
    val hint = node.tooltip?.let { ctx.resolveText(it) }
    return if (hint.isNullOrBlank()) base else base.copy(unmapped = base.unmapped + A11ySlot.Tooltip)
}
