// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
// `onAllNodes` / `setContent` are MEMBERS of the `ComposeUiTest` receiver, not top-level
// extensions like `hasText` above — importing them is an unresolved reference.
import fuaran.ui.decodeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The upload ceilings (WIRE_FORMAT.md 3.6.23, Phase 1548) — **the half only this gate can answer**.
 *
 * The decode floor, the marker projection and the value-withholding rule live in
 * `UploadCeilingHarness` and run in the plain-JVM gate, for the reason the accessibility and
 * trend-sentiment splits already record: a decision testable on one platform is a decision nobody
 * re-checks, and the local toolchain this repo builds with cannot drive Gradle at all.
 *
 * What stays here is what genuinely needs a composition: that the marker line REACHES the tree when
 * a ceiling is declared, that it does not when none is, and — the one that matters — that what
 * reaches the tree carries no number. A harness assertion over the projected string cannot see an
 * arm that ignores the projection and interpolates `k.maxBytes` into a caption of its own; this
 * can.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadCeilingTest {
    private fun upload(clause: String, multiple: Boolean = false) =
        decodeNode(
            "{\"id\":\"up\",\"kind\":{\"\$type\":\"FileUpload\",\"accept\":[\"application/pdf\"]," +
                "\"label\":\"Attach a scan\"," + clause + "\"multiple\":" + multiple +
                ",\"onSelect\":\"<closure>\"}}",
        )

    // ── The neutral set, re-run here ─────────────────────────────────────────

    @Test
    fun theDecodeFloorAndTheMarkerRulesHoldUnderThisGateToo() {
        assertEquals(emptyList<String>(), uploadCeilingFailures())
    }

    // ── The render — the markers genuinely reach, and carry no value ─────────

    @Test
    fun aDeclaredCeilingReachesTheTreeAsAValueFreeMarker() =
        runComposeUiTest {
            setContent { FuaranNode(upload("\"maxBytes\":5242880,")) }
            assertTrue(
                "the control's own label must survive the marker line",
                onAllNodes(hasText("Attach a scan", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                "a declared byte ceiling is recorded as read",
                onAllNodes(hasText("per-file byte ceiling declared", substring = true))
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
            )
        }

    @Test
    fun theCeilingsNumberNeverReachesTheTree() =
        // 3.6.23 obligation 4 — this tier can enforce neither ceiling, so a marker holding the
        // number would claim an enforcement that is not there. Digits chosen to be unmistakable in
        // a substring search and impossible to arrive at by rounding.
        runComposeUiTest {
            setContent { FuaranNode(upload("\"maxBytes\":5242881,\"maxFiles\":7,", multiple = true)) }
            for (spelling in listOf("5242881", "5,242,881", "5.0 MB", " 7 ")) {
                assertTrue(
                    "the ceiling reached the render as a value: $spelling",
                    onAllNodes(hasText(spelling, substring = true)).fetchSemanticsNodes().isEmpty(),
                )
            }
        }

    @Test
    fun anUploadDeclaringNoCeilingRendersAsItAlwaysDid() =
        // The marker line is ABSENT, not empty: every pre-1548 upload document looks exactly as it
        // did before the revision.
        runComposeUiTest {
            setContent { FuaranNode(upload("")) }
            assertTrue(
                onAllNodes(hasText("Attach a scan", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            )
            assertFalse(
                "an undeclared ceiling earns no marker at all",
                onAllNodes(hasText("ceiling declared", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            )
        }
}
