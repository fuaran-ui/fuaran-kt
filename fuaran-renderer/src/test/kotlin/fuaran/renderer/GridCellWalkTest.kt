// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import fuaran.ui.CurrencyValueFormat
import fuaran.ui.JsonArray
import fuaran.ui.JsonNull
import fuaran.ui.JsonNumber
import fuaran.ui.JsonObject
import fuaran.ui.JsonString
import fuaran.ui.NoValueFormat
import fuaran.ui.NumberValueFormat
import fuaran.ui.PercentValueFormat
import fuaran.ui.ResolvedRows
import fuaran.ui.ToneVariant
import fuaran.ui.decodeNode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 753 — the grid cell walk.
 *
 * Two tiers, deliberately. The **lowering** is pure logic and is asserted directly, because that
 * is where the load-bearing semantics live — above all the tone lookup's unmapped fallback, the
 * case a per-surface copy of a lookup-with-fallback gets wrong and a parity test misses. The
 * **render** is then proved under Robolectric: the rows genuinely reach the screen, and the three
 * outcomes are visibly different rather than merely differently-typed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GridCellWalkTest {
    private fun row(vararg pairs: Pair<String, fuaran.ui.JsonValue>) = JsonObject(pairs.toMap())

    private val shipmentTones =
        mapOf("On time" to ToneVariant.Success, "Delayed" to ToneVariant.Warning, "Cancelled" to ToneVariant.Critical)

    // ── The lowering ─────────────────────────────────────────────────────────

    @Test
    fun aMappedValueTakesItsDeclaredTone() {
        for ((value, want) in listOf("On time" to ToneVariant.Success, "Delayed" to ToneVariant.Warning)) {
            val (label, tone) = tonedPillOf(row("status" to JsonString(value)), "status", shipmentTones, ToneVariant.Subdued)
            assertEquals(value, label)
            assertEquals(want, tone)
        }
    }

    @Test
    fun anUnmappedValueTakesTheDefaultTone() {
        // The case that matters: the map does not mention "Unknown", so the pill takes
        // `defaultTone` — NOT the identity, and not the first map entry.
        val (label, tone) = tonedPillOf(row("status" to JsonString("Unknown")), "status", shipmentTones, ToneVariant.Subdued)
        assertEquals("Unknown", label)
        assertEquals(ToneVariant.Subdued, tone)
    }

    @Test
    fun anAbsentDefaultFallsBackToTheIdentityTone() {
        val (_, tone) = tonedPillOf(row("status" to JsonString("Unknown")), "status", shipmentTones, ToneVariant.Default)
        assertEquals(ToneVariant.Default, tone)
    }

    @Test
    fun aRowMissingTheNamedFieldLowersToAnEmptyDefaultPill() {
        // Never a crash and never a dropped cell.
        val (label, tone) = tonedPillOf(row("other" to JsonString("x")), "status", shipmentTones, ToneVariant.Subdued)
        assertEquals("", label)
        assertEquals(ToneVariant.Subdued, tone)
    }

    @Test
    fun theToneMapKeysOnTheRawDatumNotAFormattedOne() {
        // The number keys by its source lexeme, so an author's map entry matches.
        val (label, tone) = tonedPillOf(row("level" to JsonNumber("3")), "level", mapOf("3" to ToneVariant.Critical), ToneVariant.Default)
        assertEquals("3", label)
        assertEquals(ToneVariant.Critical, tone)
    }

    @Test
    fun projectionCoversTheScalarShapesAndRefusesStructuralOnes() {
        val r =
            row(
                "s" to JsonString("text"),
                "n" to JsonNumber("42"),
                "z" to JsonNull,
                "arr" to JsonArray(listOf(JsonString("a"))),
                "obj" to JsonObject(mapOf("k" to JsonString("v"))),
            )
        assertEquals("text", projectRowFieldString(r, "s"))
        assertEquals("42", projectRowFieldString(r, "n"))
        // Structural and null values have no cell text — never a joined rendering.
        for (key in listOf("z", "arr", "obj", "absent")) {
            assertEquals(key, "", projectRowFieldString(r, key))
        }
        assertEquals("", projectRowFieldString(JsonArray(emptyList()), "any"))
    }

    @Test
    fun formatsApplyToNumericTextAndLeaveOtherTextAlone() {
        assertEquals("GBP 1234.50", formatCellValue("1234.5", CurrencyValueFormat("GBP")))
        assertEquals("7%", formatCellValue("0.07", PercentValueFormat(0)))
        assertEquals("3.14", formatCellValue("3.14159", NumberValueFormat(2)))
        // A currency format over a string cell must not mangle it.
        assertEquals("Delayed", formatCellValue("Delayed", CurrencyValueFormat("GBP")))
        assertEquals("x", formatCellValue("x", NoValueFormat))
    }

    @Test
    fun anAbsentRowsEntryReadsAsNotResolvedNotEmpty() {
        // The distinction the seam exists to preserve: nothing seeded means "not yet".
        assertEquals(ResolvedRows.NotResolved, BindingContext.Empty.rowsFor("grid"))
        assertEquals(
            ResolvedRows.Rows(emptyList()),
            BindingContext(rows = mapOf("grid" to ResolvedRows.Rows(emptyList()))).rowsFor("grid"),
        )
    }

    // ── The render ───────────────────────────────────────────────────────────

    /** A one-column TonedPill grid; the rows are seeded, as a host seeds them. */
    private val tonedGrid =
        """
        {"id":"shipments","kind":{"${'$'}type":"DataGrid","columns":[{"field":"status","kind":{"${'$'}type":"TonedPill","default":"Subdued","field":"status","map":{"Cancelled":"Critical","Delayed":"Warning","On time":"Success"}},"label":"Status"}],"rowKeyField":"status","source":{"${'$'}type":"Query","dependsOn":[],"name":"rows"}}}
        """.trimIndent()

    private fun seeded(outcome: ResolvedRows) = BindingContext(rows = mapOf("shipments" to outcome))

    private fun rowsOf(vararg statuses: String) =
        ResolvedRows.Rows(statuses.map { JsonObject(mapOf("status" to JsonString(it))) })

    @Test
    fun aDataBoundGridRendersItsRowsRatherThanThePlaceholder() =
        runComposeUiTest {
            setContent {
                FuaranTheme(darkTheme = false) {
                    FuaranNode(decodeNode(tonedGrid), seeded(rowsOf("On time", "Delayed", "Unknown")))
                }
            }
            waitForIdle()
            // Every row's label reaches the screen — including the UNMAPPED one, which must
            // render rather than being skipped for having no tone entry.
            onNodeWithText("On time").assertIsDisplayed()
            onNodeWithText("Delayed").assertIsDisplayed()
            onNodeWithText("Unknown").assertIsDisplayed()
        }

    @Test
    fun theThreeOutcomesRenderDistinguishably() {
        // Resolved-but-empty is an empty state; unresolved is loading; no-row-source is a
        // caller mistake. Collapsing the middle one would show "no data" for "not yet".
        val cases =
            listOf(
                ResolvedRows.Rows(emptyList()) to "No rows",
                ResolvedRows.NotResolved to "Loading…",
                ResolvedRows.NoRowSource to "(no row source)",
            )
        for ((outcome, expected) in cases) {
            runComposeUiTest {
                setContent { FuaranTheme(darkTheme = false) { FuaranNode(decodeNode(tonedGrid), seeded(outcome)) } }
                waitForIdle()
                onNodeWithText(expected).assertIsDisplayed()
            }
        }
    }

    @Test
    fun aGridWithNoSeededRowsShowsTheLoadingSurfaceNotAnEmptyTable() =
        runComposeUiTest {
            setContent { FuaranTheme(darkTheme = false) { FuaranNode(decodeNode(tonedGrid), BindingContext.Empty) } }
            waitForIdle()
            onNodeWithText("Loading…").assertIsDisplayed()
        }
}
