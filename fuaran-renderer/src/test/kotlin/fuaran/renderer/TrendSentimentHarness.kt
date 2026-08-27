// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.TrendPolarity

/**
 * The trend-sentiment projection's DECISIONS, asserted in the plain-JVM gate.
 *
 * Same split, and the same reason, as the accessibility projection beside it: these are the
 * load-bearing choices — which product reads as which sentiment, which glyph carries it, what an
 * unresolvable trend does — and typed in Compose vocabulary they would run on the CI box and
 * nowhere else. `TrendSentiment.kt` carries no Compose import precisely so they can be re-checked
 * on the machine anyone changes them from; the Robolectric leg keeps only what it alone can answer,
 * that the glyph and its announcement genuinely reach the semantics tree.
 *
 * A plain `main`-driven runner rather than JUnit, matching the corpus and accessibility harnesses:
 * the repo builds with a bare `kotlinc` and no artefact resolution, so the exit code is the gate.
 * [trendSentimentFailures] is exposed separately so the Gradle/Robolectric leg can run the
 * identical set without duplicating it.
 */

/** A minimal check collector — the same shape the sibling harnesses use, kept local so this leg
 *  depends on nothing another leg establishes. */
internal class SentimentChecks {
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

/** How many checks [trendSentimentFailures] runs — reported so a leg that shrank is visible. */
var trendSentimentChecksRun = 0
    private set

/** Every assertion, returning the failures rather than throwing, so both gates run the identical
 *  set. Empty means green. */
fun trendSentimentFailures(): List<String> {
    val c = SentimentChecks()

    c.check("sentimentIsSignTimesPolarity") {
        // The whole table. Note the two diagonals: the SAME number reads oppositely under the two
        // declarations, and that is the entire content of the slot.
        c.eq("+ under HigherIsBetter", TrendSentiment.Improving, trendSentiment(TrendPolarity.HigherIsBetter, 7.34))
        c.eq("- under HigherIsBetter", TrendSentiment.Regressing, trendSentiment(TrendPolarity.HigherIsBetter, -7.34))
        c.eq("+ under LowerIsBetter", TrendSentiment.Regressing, trendSentiment(TrendPolarity.LowerIsBetter, 7.34))
        c.eq("- under LowerIsBetter", TrendSentiment.Improving, trendSentiment(TrendPolarity.LowerIsBetter, -7.34))
        c.eq("zero, HigherIsBetter", TrendSentiment.Unchanged, trendSentiment(TrendPolarity.HigherIsBetter, 0.0))
        c.eq("zero, LowerIsBetter", TrendSentiment.Unchanged, trendSentiment(TrendPolarity.LowerIsBetter, 0.0))
    }

    c.check("theCorpusPairImprovesOnAWarningTile") {
        // `nodes/metric-inverted-polarity.json` read as the renderer reads it: a falling -7.34% on
        // a "tone":"Warning" tile is an IMPROVEMENT. That pair on one node is the case a single
        // `tone` slot could never express, and it is why the field exists. Nothing reachable from
        // `TrendSentiment.kt` can touch the tile's tone.
        c.eq("inverted fall", TrendSentiment.Improving, trendSentiment(TrendPolarity.LowerIsBetter, -7.34))
    }

    c.check("zeroAndNaNReadUnchanged") {
        // -0.0 == 0.0 in IEEE 754 comparison, so a negative zero must not smuggle in a direction
        // the number does not have; a NaN satisfies neither comparison.
        c.eq("negative zero", TrendSentiment.Unchanged, trendSentiment(TrendPolarity.HigherIsBetter, -0.0))
        c.eq("negative zero, inverted", TrendSentiment.Unchanged, trendSentiment(TrendPolarity.LowerIsBetter, -0.0))
        c.eq("NaN", TrendSentiment.Unchanged, trendSentiment(TrendPolarity.HigherIsBetter, Double.NaN))
    }

    c.check("glyphAndSpokenLabelPerSentiment") {
        // The non-colour channel. Colour alone fails WCAG 1.4.1, and 3.6.1 makes discharging that
        // obligation non-optional while leaving HOW to the surface.
        c.eq("improving glyph", "▲", TrendSentiment.Improving.glyph)
        c.eq("regressing glyph", "▼", TrendSentiment.Regressing.glyph)
        c.eq("unchanged glyph", "→", TrendSentiment.Unchanged.glyph)
        // The glyph for a FALLING number under an inverted polarity is the UP triangle. If this
        // ever reads the down triangle, the declaration stopped being honoured — the glyph tracks
        // SENTIMENT, not the number's direction, and that disagreement is the visible evidence.
        c.eq("inverted fall glyph", "▲", trendSentiment(TrendPolarity.LowerIsBetter, -7.34).glyph)
    }

    c.check("spokenLabelsMatchTheReferenceVocabulary") {
        // These are the reference tiers' own sentiment names, so a Compose surface announces the
        // word the HTML tiers put in `aria-label` for the same node. That is the parity claim this
        // type makes, and the only one.
        c.eq(
            "labels",
            listOf("improving", "regressing", "unchanged"),
            TrendSentiment.entries.map { it.label },
        )
    }

    c.check("unparseableResolvedTrendYieldsNoSentiment") {
        // The surface's counterpart of the reference renderers' unresolved branch, which emits an
        // unclassed trend element with no glyph. Inventing a sentiment from an unparsed string
        // would be a claim about a number nobody has.
        for (raw in listOf("—", "[i18n:trend]", "-7.34%", "")) {
            if (trendSentiment(TrendPolarity.HigherIsBetter, raw) != null) {
                error("'$raw' must project no sentiment")
            }
        }
    }

    c.check("parseableResolvedTrendProjects") {
        c.eq("inverted", TrendSentiment.Improving, trendSentiment(TrendPolarity.LowerIsBetter, " -7.34 "))
        c.eq("plain", TrendSentiment.Regressing, trendSentiment(TrendPolarity.HigherIsBetter, "-7.34"))
        c.eq("zero", TrendSentiment.Unchanged, trendSentiment(TrendPolarity.HigherIsBetter, "0"))
    }

    trendSentimentChecksRun = c.passed + c.failures.size
    return c.failures
}

fun main() {
    println("== trend sentiment projection :: composition rule (platform-neutral) ==")
    val failures = trendSentimentFailures()
    if (failures.isEmpty()) {
        println("PASS: $trendSentimentChecksRun sentiment checks green — sign x polarity, the glyphs and the drop case hold.")
    } else {
        println("FAIL: ${failures.size} sentiment check(s) failed")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
