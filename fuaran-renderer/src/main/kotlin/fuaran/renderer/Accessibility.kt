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

/**
 * The accessibility projection — **the Compose half, and only the Compose half**.
 *
 * The decisions live in `AccessibilityProjection.kt`, which carries no Compose import and is
 * therefore assertable in the plain-JVM gate. What remains here is application: turning the
 * platform-neutral [SemanticRole] / [LiveRegionKind] / label / hidden result into
 * `androidx.compose.ui.semantics` calls. This file cannot be loaded off an Android classpath, and
 * that is exactly why it is kept as thin as it is — everything in it is a translation with no
 * decision left in it, so the half that only CI can run is the half that has nothing to get wrong
 * beyond a swapped enum arm, and the headless Compose leg proves the emitted semantics reach the
 * tree.
 */

/**
 * Map the neutral role onto Compose's. Exhaustive by construction — a new [SemanticRole] case is a
 * build error here until its arm lands, which is what stops the decision half from growing a case
 * the application half silently ignores.
 */
internal fun composeRole(role: SemanticRole): Role =
    when (role) {
        SemanticRole.Button -> Role.Button
        SemanticRole.Tab -> Role.Tab
    }

/** Map the neutral live-region mode onto Compose's. Exhaustive, for the same reason. */
internal fun composeLiveRegion(mode: LiveRegionKind): LiveRegionMode =
    when (mode) {
        LiveRegionKind.Polite -> LiveRegionMode.Polite
        LiveRegionKind.Assertive -> LiveRegionMode.Assertive
    }

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
            if (semanticRole != null) role = composeRole(semanticRole)
            if (projection.heading) heading()
            if (live != null) liveRegion = composeLiveRegion(live)
            if (projection.hidden) hideFromAccessibility()
        }
    }
