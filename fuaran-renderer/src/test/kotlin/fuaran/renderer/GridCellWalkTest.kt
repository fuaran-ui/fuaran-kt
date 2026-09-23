// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
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

    // ── Phase 1855 — the interactive-row marker reaches the rows (3.6.24) ────
    //
    // WHICH rows are marked is asserted in the plain-JVM obligation gate, over
    // `gridRowInteractivity`. What only a composition can answer is that the grid arm APPLIES that
    // projection: a marked row carries a click action in its semantics and an unmarked one does
    // not. Counted over the unmerged tree, so a marker cannot hide inside a merged parent.

    private fun clickActionsIn(node: String, ctx: BindingContext): Int {
        var count = -1
        runComposeUiTest {
            setContent { FuaranTheme(darkTheme = false) { FuaranNode(decodeNode(node), ctx) } }
            waitForIdle()
            count = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size
        }
        return count
    }

    @Test
    fun theInteractiveRowMarkerReachesExactlyTheRowsTheProjectionMarks() {
        val declaring = tonedGrid.replace("\"rowKeyField\"", "\"onRowClick\":\"<closure>\",\"rowKeyField\"")
        check(declaring != tonedGrid) { "the substitution declared nothing" }
        val threeRows = seeded(rowsOf("On time", "Delayed", "Unknown"))

        // Rule 1, both halves: every bound row where the grid declares a row action, none where it
        // does not. The silent grid is the probe's control — were the cells themselves clickable,
        // it would count them and the declaring count below would be wrong for the same reason.
        assertEquals("a grid declaring no row action carries no marker", 0, clickActionsIn(tonedGrid, threeRows))
        assertEquals("every bound row of a declaring grid is marked", 3, clickActionsIn(declaring, threeRows))

        // Rule 3: no row on screen, so no marker, however the grid declares.
        assertEquals(0, clickActionsIn(declaring, seeded(ResolvedRows.NotResolved)))

        // Rule 2: a staticRows grid marks no row whatever it declares.
        val staticDeclaring =
            """
            {"id":"table-clickable","kind":{"${'$'}type":"DataGrid","columns":[],"onRowClick":"<closure>","source":{"${'$'}type":"Static","value":[]},"staticRows":{"headers":["Term","Definition"],"rows":[["MVU","Model-View-Update"],["DSL","Domain-specific language"]]}}}
            """.trimIndent()
        assertEquals("a staticRows grid is never marked", 0, clickActionsIn(staticDeclaring, BindingContext.Empty))
    }
}
