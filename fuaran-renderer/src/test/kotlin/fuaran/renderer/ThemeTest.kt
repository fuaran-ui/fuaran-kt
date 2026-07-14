// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import fuaran.ui.BadgeVariant
import fuaran.ui.Emphasis
import fuaran.ui.StyleWeight
import fuaran.ui.ToneVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Phase 545 Material tone-bridge gate. Two properties:
 *
 *  1. **Pure tokens** (spacing / emphasis / heading typography) are scheme-independent and map
 *     monotonically over the density / emphasis vocabulary — asserted directly, no composition.
 *  2. **Tone colours differ between light and dark schemes.** The same [ToneVariant] token, resolved
 *     under [FuaranTheme] in light vs dark, yields a different container colour for every tone — the
 *     acceptance criterion "tone-mapped rendering differs visibly and correctly between light and
 *     dark". Captured from a real headless composition under Robolectric.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTest {
    @Test
    fun spacingTokensAreMonotonicOverDensity() {
        assertTrue(FuaranSpacing.pad(StyleWeight.Compact).value < FuaranSpacing.pad(StyleWeight.Standard).value)
        assertTrue(FuaranSpacing.pad(StyleWeight.Standard).value < FuaranSpacing.pad(StyleWeight.Spacious).value)
        assertTrue(FuaranSpacing.gap(StyleWeight.Compact).value < FuaranSpacing.gap(StyleWeight.Spacious).value)
    }

    @Test
    fun emphasisTypographyScalesWithLoudness() {
        assertTrue(emphasisScale(Emphasis.Quiet) < emphasisScale(Emphasis.Normal))
        assertTrue(emphasisScale(Emphasis.Normal) < emphasisScale(Emphasis.Loud))
        assertEquals(1.0f, emphasisScale(Emphasis.Normal), 0.0001f)
    }

    @Test
    fun everyToneDiffersBetweenLightAndDarkSchemes() {
        val light = captureTones(darkTheme = false)
        val dark = captureTones(darkTheme = true)

        assertEquals(ToneVariant.entries.size, light.size)
        for (tv in ToneVariant.entries) {
            assertNotEquals(
                "tone $tv container must differ between light and dark schemes",
                light.getValue(tv),
                dark.getValue(tv),
            )
        }
    }

    @Test
    fun badgeVariantsMapOntoDistinctTones() {
        val swatches = mutableMapOf<BadgeVariant, Color>()
        composeCapture { swatches[BadgeVariant.Brand] = badge(BadgeVariant.Brand).container }
        composeCapture { swatches[BadgeVariant.Critical] = badge(BadgeVariant.Critical).container }
        composeCapture { swatches[BadgeVariant.Success] = badge(BadgeVariant.Success).container }
        assertNotEquals(swatches[BadgeVariant.Brand], swatches[BadgeVariant.Critical])
        assertNotEquals(swatches[BadgeVariant.Success], swatches[BadgeVariant.Critical])
    }

    // --- helpers ------------------------------------------------------------ //

    private fun captureTones(darkTheme: Boolean): Map<ToneVariant, Color> {
        val out = linkedMapOf<ToneVariant, Color>()
        runComposeUiTest {
            setContent {
                FuaranTheme(darkTheme = darkTheme) {
                    for (tv in ToneVariant.entries) out[tv] = tone(tv).container
                }
            }
            waitForIdle()
        }
        return out
    }

    private fun composeCapture(block: @Composable () -> Unit) {
        runComposeUiTest {
            setContent { FuaranTheme(darkTheme = false) { block() } }
            waitForIdle()
        }
    }
}
