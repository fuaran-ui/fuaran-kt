// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.DataGrid
import fuaran.ui.ResolvedRows

/**
 * The interactive-row marker — which rendered grid rows say "this row can be activated"
 * (WIRE_FORMAT.md 3.6.24, Phase 1855).
 *
 * `onRowClick` is a closure-bearing slot, so it reaches this surface as one fact — the grid DECLARES
 * a row action ([DataGrid.rowActionDeclared]) — and that fact is the whole of what decides a row's
 * marker. The reference tier's marker is the `fuaran-grid-row-interactive` class; a surface with no
 * class vocabulary uses whatever it uses to say a row can be activated, which on Compose is a click
 * action in the row's semantics. The three rules, in the section's own order:
 *
 *  1. A BOUND row is marked if and only if the grid declares a row action — every row, not the first.
 *  2. A `staticRows` grid marks NO row, whatever it declares: its cells are text sources rather than
 *     the row values an action is applied to, so a marker there would promise a click no tier can
 *     deliver. The declaration is still REPORTED ([GridRowInteractivity.rowActionDeclared]), because
 *     rule 2 is precisely the divergence between what the document declared and what a row carries.
 *  3. A bound leg with no rows on screen — unresolved, or no row source — has no row to mark, so the
 *     list is EMPTY rather than all-`false`: a `false` would assert a row that does not exist.
 *
 * **What the marker does NOT claim** (3.6.24, stated there): that a click reaches an action. The
 * closure never crosses the wire, so on this surface the marked row's click action is a declaration
 * with no handler behind it — it reports itself unhandled — exactly the posture the section gives a
 * no-script tier's marker. What it guarantees is the other direction: no row ever advertises an
 * activation the document did not declare.
 *
 * **Why this file carries no Compose import.** The same reasoning as [uploadCeilingMarkers] and the
 * accessibility projection beside it: which rows are marked is ordinary logic over the decoded model,
 * and typed in Compose vocabulary it would be provable only on a box carrying the Android SDK. The
 * grid arm applies [markers] one-for-one to the rows it draws.
 */
data class GridRowInteractivity(
    /** One entry per rendered row, in render order; `true` where that row carries the marker. */
    val markers: List<Boolean>,
    /** The document declared a row action — reported even where rule 2 withholds every marker. */
    val rowActionDeclared: Boolean,
) {
    /** No rendered row carries the marker (vacuously true where no row is rendered). */
    val marksNoRow: Boolean
        get() = markers.none { it }
}

/**
 * Project a grid and the rows its bound leg resolved to into the per-row markers this surface emits.
 * [rows] is ignored for a `staticRows` grid, whose rows are the node's own.
 */
fun gridRowInteractivity(grid: DataGrid, rows: ResolvedRows): GridRowInteractivity {
    val static = grid.staticRows
    val markers =
        when {
            // Rule 2 — the static mode honours no row action in any tier.
            static != null -> List(static.rows.size) { false }
            // Rule 1 — every bound row, iff declared.
            rows is ResolvedRows.Rows -> List(rows.rows.size) { grid.rowActionDeclared }
            // Rule 3 — no row on screen, so nothing to mark.
            else -> emptyList()
        }
    return GridRowInteractivity(markers = markers, rowActionDeclared = grid.rowActionDeclared)
}
