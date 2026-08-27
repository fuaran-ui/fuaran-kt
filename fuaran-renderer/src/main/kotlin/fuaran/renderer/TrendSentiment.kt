// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.TrendPolarity

/**
 * The trend-sentiment projection — **the platform-neutral half**.
 *
 * `Metric` carries two slots that both look like judgements about a number, and WIRE_FORMAT.md
 * 3.6.1 exists because they are not the same judgement. `tone` says how the reading STANDS and
 * colours the TILE; `trendPolarity` says which way the quantity IMPROVES and reaches the TREND
 * element alone. Sentiment is `sign(trend) x polarity`, where `HigherIsBetter` is `+1` and
 * `LowerIsBetter` is `-1`: a positive product is an improvement, a negative product a regression,
 * a zero trend neither.
 *
 * **The numeric text is UNCHANGED by the reading.** A falling -7.34% prints -7.34% under either
 * declaration; polarity changes how the number READS, never what it SAYS. The cheap trick — let the
 * emitter flip the sign so up is always good — is refused by the specification, because a -7.34%
 * error rate printed as +7.34% is a false statement about the world.
 *
 * **Nothing here writes back to `tone`.** A surface that inferred "improving implies the tile is
 * Success" would re-create in the render the exact conflation the wire slot exists to remove, and
 * would override an emitter's deliberate `Critical` on a metric improving from a bad place. This
 * file carries no import of `ToneVariant` and no path to one, by construction rather than by
 * discipline.
 *
 * **Why this file carries no Compose import, and why that is the point.** The same reasoning as
 * [AccessibilityProjection] next door: the DECISIONS are the load-bearing part — which product
 * reads as which sentiment, which glyph carries it, what an unresolvable trend does — and typed in
 * Compose vocabulary they could only be asserted on a machine carrying the Android SDK, which is to
 * say on the CI box and nowhere else. A decision that can only be tested on one platform is a
 * decision nobody re-checks. The thin application onto a Compose colour and a semantics property
 * lives in `FuaranRenderer.kt`, inside the only half that genuinely needs Compose.
 *
 * **The structural intent transfers from the reference renderers; their CSS constraint does not.**
 * Those tiers emit `fuaran-metric-trend-{improving,regressing,unchanged}` class modifiers plus a
 * glyph carrying an `aria-label`. Compose has no class vocabulary, so what crosses is the PAIR — a
 * sentiment and a non-colour channel for it — projected into this platform's own idiom.
 */
enum class TrendSentiment(
    /**
     * The spoken sentiment. These are the reference tiers' own sentiment names, so the
     * `contentDescription` a Compose surface announces is the word the HTML tiers put in
     * `aria-label` for the same node. That is the parity claim this type makes, and the only one:
     * the visual treatment is this platform's.
     */
    val label: String,
    /**
     * The non-colour channel. U+25B2 BLACK UP-POINTING TRIANGLE, U+25BC BLACK DOWN-POINTING
     * TRIANGLE, U+2192 RIGHTWARDS ARROW — named in prose so a mojibake in this file is a diff a
     * reviewer can catch rather than a rendered byte nobody pinned.
     *
     * Sentiment carried by colour ALONE fails WCAG 1.4.1, and 3.6.1 makes discharging that
     * obligation non-optional while leaving HOW to the surface. The glyph tracks SENTIMENT, not the
     * number's direction: under an inverted polarity the triangle deliberately disagrees with the
     * sign, and that disagreement is the visible evidence the declaration was honoured.
     */
    val glyph: String,
) {
    Improving("improving", "▲"),
    Regressing("regressing", "▼"),
    Unchanged("unchanged", "→"),
}

/**
 * `sentiment = sign(trend) x polarity`.
 *
 * Total in both arguments and dependent on nothing else — no second binding, no cross-node
 * coordination, no state. `-0.0 == 0.0` in IEEE 754 comparison, so a negative zero reads
 * [TrendSentiment.Unchanged] rather than smuggling in a direction the number does not have; a NaN
 * satisfies neither comparison and also reads unchanged, which is the honest answer for a quantity
 * that did not move anywhere expressible.
 */
fun trendSentiment(polarity: TrendPolarity, trend: Double): TrendSentiment {
    val direction =
        when (polarity) {
            TrendPolarity.HigherIsBetter -> 1.0
            TrendPolarity.LowerIsBetter -> -1.0
        }
    val sentiment = trend * direction
    return when {
        sentiment > 0.0 -> TrendSentiment.Improving
        sentiment < 0.0 -> TrendSentiment.Regressing
        else -> TrendSentiment.Unchanged
    }
}

/**
 * The projection actually applied by the render arm: a resolved trend STRING read as a number, or
 * `null` when it is not one.
 *
 * `null` is this surface's counterpart of the reference renderers' unresolved branch, which emits
 * the trend element with no sentiment class and no glyph. This surface resolves a binding to its
 * display string and reads a number back out — the idiom `resolveFloat` already uses throughout
 * this renderer — so a trend that resolves to an em-dash, an i18n placeholder, or a formatted
 * string carrying a unit yields no sentiment. Asserting nothing is the correct outcome there: a
 * sentiment invented from an unparsed string would be a claim about a number nobody has.
 */
fun trendSentiment(polarity: TrendPolarity, resolvedTrend: String): TrendSentiment? =
    resolvedTrend.trim().toDoubleOrNull()?.let { trendSentiment(polarity, it) }
