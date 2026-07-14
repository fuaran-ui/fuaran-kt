// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The render-coverage sink for the Phase 544 conformance gate (the analogue of Phase 542's
 * decode-coverage report).
 *
 * As [FuaranNode] dispatches, it records the discriminator of every [fuaran.ui.NodeKind] it
 * renders; the (structurally unreachable, see below) [FallbackPlaceholder] records a *fallback
 * hit*. The render leg asserts [fallbacks] is empty across the whole corpus — every fixture
 * renders through a real arm of the floor.
 *
 * Note the dispatch spine in [FuaranNode] is an **`else`-free exhaustive `when`** over the sealed
 * `NodeKind`, so an unrendered kind is a *compile* error, not a runtime fallback — the Kotlin
 * analogue of the Rust host's `match` with no `_ =>`. [FallbackPlaceholder] therefore exists as the
 * *defined* visible placeholder a host may deliberately route a not-yet-implemented kind through
 * (never a silent skip); the coverage sink makes any such use loud.
 */
class RenderCoverage {
    /** discriminator -> number of fixtures rendered through that kind's arm. */
    val kinds: MutableMap<String, Int> = sortedMapOf()

    /** discriminators that fell to [FallbackPlaceholder] — must be empty for the gate to pass. */
    val fallbacks: MutableSet<String> = linkedSetOf()

    fun count(discriminator: String) {
        kinds[discriminator] = (kinds[discriminator] ?: 0) + 1
    }

    fun fallback(discriminator: String) {
        fallbacks += discriminator
    }
}

/** The ambient coverage sink; `null` in production (no tracking), a recorder under the render gate. */
val LocalRenderCoverage = staticCompositionLocalOf<RenderCoverage?> { null }
