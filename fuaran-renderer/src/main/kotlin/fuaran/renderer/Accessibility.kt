// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import fuaran.ui.Accessibility
import fuaran.ui.Node

/**
 * The accessibility projection — a node's [Accessibility] trait rendered as Compose `semantics {}`.
 *
 * The HTML render tiers of the Fuaran UI wire format project the trait's six slots into `aria-*`
 * attributes (`label` → `aria-label`, `labelledBy` → `aria-labelledby`, `describedBy` →
 * `aria-describedby`, `role` → `role`, `liveRegion` → `aria-live`, `hidden` → `aria-hidden`). A
 * Compose surface has no attribute bag, so the projection is a mapping onto **semantics
 * properties**, and the two vocabularies do not correspond one-for-one. The mapping — and, just as
 * importantly, the slots that have no native equivalent — is decided and enumerated here; see
 * `CLAUDE.md`, "Accessibility projection", for the decision record and its rationale.
 *
 * **An unmappable slot is dropped, never refused — and never silently.** A render surface does not
 * reject a tree the wire declares valid; what it owes instead is an account of what it could not
 * carry, which is [AccessibilityProjection.unmapped].
 *
 * **Placement.** The reference host decides which element carries the projection
 * (`../fuaran-dotnet/docs/DECISIONS.md`, D4: the node's semantic element rather than its wrapper
 * `<div>`). A Compose surface has no wrapper: the kind arm IS the node's composable, so the
 * projection is applied at the single dispatch site in [FuaranNode] and nowhere else.
 *
 * **Pure, and asserted directly.** [accessibilityProjection] is ordinary logic over the decoded
 * model — the same two-tier shape as the grid-cell lowering: the mapping decisions (which role
 * tokens carry a semantics role, which slots are dropped, what an empty resolved label does) are
 * asserted on the value, and the reachability of the emitted semantics is then proved under the
 * headless Compose harness.
 */
data class AccessibilityProjection(
    /**
     * The resolved accessible name, or null when the slot is absent or resolves empty. An empty
     * resolved label is DROPPED rather than applied, mirroring the reference projection — an empty
     * `contentDescription` would erase the node's natural name instead of leaving it alone.
     */
    val label: String? = null,
    /** The mapped semantics role, or null when the token has none here. */
    val role: Role? = null,
    /** `role: "heading"` — a semantics FUNCTION in Compose (`heading()`), not a [Role] value. */
    val heading: Boolean = false,
    /** The live-region mode. `off` maps to null: absence IS "do not announce" here. */
    val liveRegion: LiveRegionMode? = null,
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

/** The six slots of the wire trait, named so a dropped one can be reported rather than vanish. */
enum class A11ySlot { Label, LabelledBy, DescribedBy, Role, LiveRegion, Hidden }

/**
 * The role token → semantics map. Keyed on the wire's own lowercase ARIA tokens, matched EXACTLY:
 * the reference projection emits the token the author declared, and a case-folding match here would
 * accept a spelling no HTML tier honours.
 *
 * `button` and `tab` have genuine [Role] values; `heading` is a semantics function rather than a
 * role, so it rides its own flag. Every other token — the landmark roles (`banner` / `navigation` /
 * `main` / `form` / `region`), `tablist` / `tabpanel`, `dialog`, `alert`, `status`, `progressbar`,
 * `link`, and any custom role whose meaning the host cannot know — has no Compose semantics that
 * MEANS what it means, and is reported unmapped rather than approximated.
 *
 * `link` is the notable absence and is deliberate: Compose has no link role, and the nearest
 * substitute ([Role.Button]) would announce a link as a button.
 */
internal fun roleSemanticsOf(token: String): Pair<Role?, Boolean>? =
    when (token) {
        "button" -> Role.Button to false
        "tab" -> Role.Tab to false
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
 * `liveRegion`, by contrast, maps EXACTLY: Compose's [LiveRegionMode] carries the polite/assertive
 * distinction the wire declares, so this surface loses nothing there.
 */
fun accessibilityProjection(a11y: Accessibility?, ctx: BindingContext): AccessibilityProjection {
    if (a11y == null) return AccessibilityProjection.None

    val unmapped = mutableListOf<A11ySlot>()

    val label = a11y.label?.let { ctx.resolve(it) }?.ifEmpty { null }

    if (a11y.labelledBy != null) unmapped.add(A11ySlot.LabelledBy)
    if (a11y.describedBy != null) unmapped.add(A11ySlot.DescribedBy)

    var role: Role? = null
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
    var live: LiveRegionMode? = null
    when (a11y.liveRegion) {
        null, "off" -> Unit
        "polite" -> live = LiveRegionMode.Polite
        "assertive" -> live = LiveRegionMode.Assertive
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

/** Project a node's trait — the shape the dispatch spine calls. */
fun accessibilityProjection(node: Node, ctx: BindingContext): AccessibilityProjection =
    accessibilityProjection(node.accessibility, ctx)

/**
 * Apply a projection to a modifier chain.
 *
 * An empty projection returns the receiver untouched, so a node with no trait (which is every node
 * in the shared corpus today) reaches exactly the composable it reached before this projection
 * existed.
 *
 * `mergeDescendants` is set **only when a label is projected**, and that is the load-bearing detail:
 * `aria-label` on the semantic element means "this node's accessible name is X", and without
 * merging, a `contentDescription` on a node with descendant text yields two announced elements
 * instead of one renamed element. Where no label is projected there is nothing to merge FOR, and
 * merging anyway would flatten a subtree the author never asked to collapse.
 */
fun Modifier.fuaranAccessibility(projection: AccessibilityProjection): Modifier =
    if (projection.isEmpty) {
        this
    } else {
        this.semantics(mergeDescendants = projection.label != null) {
            // Bound outside the property assignments: the compiler cannot prove a data-class
            // property is unchanged across the receiver boundary, so the smart cast is unavailable
            // there (the same reason `RenderIcon` binds `k.label` first).
            val described = projection.label
            val semanticRole = projection.role
            val live = projection.liveRegion
            if (described != null) contentDescription = described
            if (semanticRole != null) role = semanticRole
            if (projection.heading) heading()
            if (live != null) liveRegion = live
            if (projection.hidden) hideFromAccessibility()
        }
    }
