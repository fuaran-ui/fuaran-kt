// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import fuaran.ui.JsonNumber
import fuaran.ui.LiteralText
import fuaran.ui.Metric
import fuaran.ui.Node
import fuaran.ui.StaticBinding
import fuaran.ui.ToneVariant
import fuaran.ui.TrendPolarity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trend-sentiment projection — **the half only this gate can answer**.
 *
 * The composition rule itself (sign x polarity), the glyph vocabulary and the drop case live in
 * `TrendSentimentHarness` and run in the plain-JVM gate against the platform-neutral result type,
 * for the reason the accessibility split records: a decision testable on one platform is a decision
 * nobody re-checks.
 *
 * What stays here is what genuinely needs a Compose host — that the glyph and its announcement
 * REACH the semantics tree, and that the numeric text is untouched by the reading. The one
 * delegating test re-runs the neutral set so this gate still fails on a rule regression rather than
 * only on a rendering one; the checks are shared, not duplicated.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrendSentimentTest {
    /** [trend] is the WIRE spelling, kept as the literal string the tree carries — `JsonNumber`
     *  holds its raw lexeme, and the trend text is that lexeme verbatim, which is what makes the
     *  "numeric text is unchanged" assertion below an exact match rather than a formatting guess. */
    private fun metric(polarity: TrendPolarity, trend: String, tone: ToneVariant = ToneVariant.Default) =
        Node(
            id = "m",
            kind =
                Metric(
                    label = LiteralText("Revenue"),
                    value = StaticBinding(JsonNumber("42.0")),
                    tone = tone,
                    trend = StaticBinding(JsonNumber(trend)),
                    trendPolarity = polarity,
                ),
        )

    // ── The neutral rule set, re-run here ────────────────────────────────────

    @Test
    fun theCompositionRuleHoldsUnderThisGateToo() {
        assertEquals(emptyList<String>(), trendSentimentFailures())
    }

    // ── The render — the sentiment genuinely reaches the tree ────────────────

    @Test
    fun aFallingTrendUnderAnInvertedPolarityIsAnnouncedAsImproving() =
        runComposeUiTest {
            // The corpus fixture's own pair: -7.34 under LowerIsBetter on a Warning tile. The tile
            // says the reading stands badly; the polarity says the quantity is improving. One
            // `tone` slot could never have said both, which is the whole argument for the field.
            setContent { FuaranNode(metric(TrendPolarity.LowerIsBetter, "-7.34", ToneVariant.Warning)) }
            onNodeWithContentDescription("improving").assertExists()
        }

    @Test
    fun theSameNumberAnnouncesOppositelyWithoutTheDeclaration() =
        runComposeUiTest {
            setContent { FuaranNode(metric(TrendPolarity.HigherIsBetter, "-7.34")) }
            onNodeWithContentDescription("regressing").assertExists()
        }

    @Test
    fun theNumericTextIsUnchangedByTheReading() =
        runComposeUiTest {
            // Clause 3: polarity changes how the number READS, never what it SAYS. The cheap trick
            // — flip the sign so up is always good — would make this assertion fail, and it is a
            // false statement about the world rather than a rendering choice.
            setContent { FuaranNode(metric(TrendPolarity.LowerIsBetter, "-7.34", ToneVariant.Warning)) }
            onNodeWithText("-7.34").assertIsDisplayed()
        }

    @Test
    fun aZeroTrendAnnouncesUnchanged() =
        runComposeUiTest {
            setContent { FuaranNode(metric(TrendPolarity.HigherIsBetter, "0")) }
            onNodeWithContentDescription("unchanged").assertExists()
        }
}
