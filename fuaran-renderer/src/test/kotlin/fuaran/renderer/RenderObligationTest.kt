// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
// `onAllNodes` / `waitForIdle` / `setContent` are MEMBERS of the `ComposeUiTest` receiver, not
// top-level extensions like `hasText` above — importing them is an unresolved reference.
import fuaran.ui.Audio
import fuaran.ui.Binding
import fuaran.ui.Custom
import fuaran.ui.JsonObject
import fuaran.ui.JsonString
import fuaran.ui.LiteralText
import fuaran.ui.Media
import fuaran.ui.MediaKind
import fuaran.ui.Node
import fuaran.ui.StaticBinding
import fuaran.ui.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Render-obligation conformance (WIRE_FORMAT.md 13) — **the half only this gate can answer**.
 *
 * The reader, the reporting surface, the checker and exemption registries, and every gate check
 * over them live in `RenderObligationHarness` and run in the plain-JVM gate, for the reason the
 * accessibility and trend-sentiment splits already record: a decision testable on one platform is a
 * decision nobody re-checks, and the local toolchain this repo builds with cannot drive Gradle at
 * all.
 *
 * What stays HERE is what genuinely needs a composition — the claims that are about EMITTED OUTPUT
 * rather than about the type system. A checker that inspected the decoded tree instead would be
 * re-stating the model; the obligations are claims about what a reader receives.
 *
 * Two tests below are not obligation checkers and are deliberately not registered as such:
 * [theTransportTileStatesAutoplayOnlyAsTheInseparablePair] and
 * [theTransportTileNamesADeclaredPosterAndNeverItsDestination] pin the honest FLOOR that two of
 * this surface's declared exemptions describe. They are why those exemptions can be read as a
 * decision rather than as a gap: the tile states what the document declared without acting on it,
 * and an edit that let it emit a bare `autoplay`, or a poster's destination, goes red here even
 * though neither obligation is claimed.
 *
 * Existence is asserted through [onAllNodes] rather than the single-node finders throughout. The
 * media tile merges its descendants into one semantics node and the placeholders do not, so the
 * NUMBER of nodes a matcher hits is an implementation detail of the arm; what the obligations claim
 * is that the output carries the text, not how many nodes carry it. A finder that also failed on
 * multiplicity would make these assertions brittle in a direction none of them is about.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderObligationTest {
    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun media(
        label: String,
        kind: MediaKind,
        src: Binding = StaticBinding(JsonString("/asset")),
    ) = Node(id = "m", kind = Media(label = LiteralText(label), src = src, kind = kind))

    /** The unregistered custom node. This surface ships no custom-renderer registry seam and no
     *  contract-card reader, so every `Custom` node takes the unregistered, uncarded path. */
    private fun custom() =
        Node(
            id = "cust",
            kind =
                Custom(
                    moduleId = "analytics",
                    componentId = "sparkline",
                    props = JsonObject(mapOf("series" to JsonString("{\"points\":[1,2,3]}"))),
                ),
        )

    // ── The checkers ─────────────────────────────────────────────────────────
    //
    // One per (kind, claim) this surface asserts in emitted output.

    /**
     * `Media/accessible-name-always` — the one 3.6.6 obligation that binds a surface rendering no
     * playback, and the one this floor genuinely honours.
     *
     * `label` is required on the wire precisely because a transport is never decorative, and it
     * lands on the tile as `contentDescription`, unconditionally. Both variants are asserted,
     * because the label is mandatory for the KIND and not for one arm of it: a surface emitting it
     * only on video passes a video-only test.
     */
    private fun checkAccessibleNameAlways() {
        runComposeUiTest {
            setContent { MaterialTheme { FuaranNode(media("Studio walkthrough", Video())) } }
            waitForIdle()
            assertTrue(
                "a video transport emits the resolved label as its accessible name",
                onAllNodes(hasContentDescription("Studio walkthrough")).fetchSemanticsNodes().isNotEmpty(),
            )
        }
        runComposeUiTest {
            setContent { MaterialTheme { FuaranNode(media("Curator commentary", Audio)) } }
            waitForIdle()
            assertTrue(
                "an audio transport emits it too — the label is mandatory for the KIND, not for one arm",
                onAllNodes(hasContentDescription("Curator commentary")).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    /**
     * `Media/no-autoplay-pathway` — the EMITTED half.
     *
     * The structural half is asserted reflectively in the plain-JVM leg, which is the stronger form
     * on this surface: the `Audio` case declares no slot, so no render arm can reach one. This leg
     * closes the other direction — that nothing in the audio tile's own output states an autoplay
     * or a muting, which is what a surface that grew a synthetic pathway beside the type would
     * produce.
     */
    private fun checkNoAutoplayPathwayInOutput() =
        runComposeUiTest {
            setContent { MaterialTheme { FuaranNode(media("Curator commentary", Audio)) } }
            waitForIdle()
            assertTrue(
                "an audio transport must state no autoplay — the case declares no such slot",
                onAllNodes(hasText("autoplay", substring = true, ignoreCase = true))
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            assertTrue(
                "an audio transport has no autoplay, so it has nothing to mute",
                onAllNodes(hasText("muted", substring = true, ignoreCase = true))
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
        }

    /**
     * `Custom/unregistered-custom-labelled` (25.4) — the UNCARDED path, which on this surface is
     * the whole of the path.
     *
     * The claim is conditional on a contract card for the node's identity being AVAILABLE. This
     * surface holds no card reader, so no card is ever available and the identity-only placeholder
     * is the conformant answer rather than a degradation: it names the component and invents
     * nothing. The carded branches — the summary, the declared prop rows, the machine-readable
     * verdict marker, the withheld description under a contradicted content hash — are out of scope
     * here because there is nothing to read a card from, and **this surface does not thereby claim
     * 25 adoption**, which is a separate bar with its own roster row.
     *
     * What is asserted is the half that IS this surface's answer: the identity reaches the reader,
     * and no prop value and no guess at the component's appearance goes with it.
     */
    private fun checkUnregisteredCustomLabelled() =
        runComposeUiTest {
            setContent { MaterialTheme { FuaranNode(custom()) } }
            waitForIdle()
            assertTrue(
                "the placeholder says what it is",
                onAllNodes(hasText("Custom", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(
                "…and names the component identity, which is the whole of what this surface can honestly say",
                onAllNodes(hasText("analytics/sparkline", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            )
            // Never a prop VALUE: this surface was not asked to interpret the node's props, and a
            // placeholder that leaked them would be showing an opaque payload as if it were content.
            assertTrue(
                "no prop value reaches the placeholder",
                onAllNodes(hasText("points", substring = true)).fetchSemanticsNodes().isEmpty(),
            )
            assertTrue(
                "no prop name either — with no card there is no declared schema to show, so showing one would be a guess",
                onAllNodes(hasText("series", substring = true)).fetchSemanticsNodes().isEmpty(),
            )
        }

    /**
     * The registry: which (kind, claim) pairs this leg asserts. Keyed by the artefact's WIRE tokens,
     * because the enumeration it is matched against comes from the artefact.
     */
    private val composeCheckers: Map<String, () -> Unit> =
        linkedMapOf(
            "Media/accessible-name-always" to ::checkAccessibleNameAlways,
            "Media/no-autoplay-pathway" to ::checkNoAutoplayPathwayInOutput,
            "Custom/unregistered-custom-labelled" to ::checkUnregisteredCustomLabelled,
        )

    // ── The declaration guard ────────────────────────────────────────────────

    @Test
    fun theComposeRegistryIsExactlyWhatTheNeutralGateDeclaresItToBe() {
        // `COMPOSE_CHECKER_KEYS` is what makes the plain-JVM gate report these three claims as
        // ASSERTED without being able to run them. That is a declaration, and a declaration nothing
        // verifies is the loophole this whole mechanism removes — so it is verified here, where the
        // checkers actually live. A key declared there with no checker beside it fails this test;
        // a checker here that nothing declares fails the neutral leg's orphan check.
        assertEquals(
            "the Compose-tier declaration in RenderObligationHarness.kt has drifted from the checkers here",
            COMPOSE_CHECKER_KEYS,
            composeCheckers.keys,
        )
    }

    // ── The neutral gate, re-run here ────────────────────────────────────────

    @Test
    fun theObligationGateHoldsUnderThisGateToo() {
        val artifact = locateRenderFidelityArtifact()
        if (artifact == null) {
            // Honest on a standalone clone with no corpus; impossible in CI, which asserts the
            // corpus is present before running either gate precisely so a skip cannot read as a
            // pass. Printed rather than swallowed.
            println("SKIP: render-fidelity.json not found (set FUARAN_CORPUS). Nothing to certify.")
            return
        }
        val report = mutableListOf<ObligationReport>()
        val failures = renderObligationFailures(artifact, report)
        for (line in unassertedObligations(report)) {
            println("  render obligation not asserted: ${describeObligationReport(line)}")
        }
        assertEquals(emptyList<String>(), failures)
    }

    // ── The checkers, run BY NAME ────────────────────────────────────────────
    //
    // So a failing obligation names the claim it broke rather than surfacing as one opaque red.

    @Test
    fun owesMediaAccessibleNameAlways() = composeCheckers.getValue("Media/accessible-name-always")()

    @Test
    fun owesMediaNoAutoplayPathway() = composeCheckers.getValue("Media/no-autoplay-pathway")()

    @Test
    fun owesCustomUnregisteredCustomLabelled() =
        composeCheckers.getValue("Custom/unregistered-custom-labelled")()

    // ── The floor two declared exemptions describe ───────────────────────────

    @Test
    fun theTransportTileStatesAutoplayOnlyAsTheInseparablePair() =
        runComposeUiTest {
            // `Media/autoplay-muted-pairing` is declared EXEMPT here — nothing plays, so no autoplay
            // and no muted attribute is ever emitted and the claim is vacuous. What the tile does do
            // is STATE the declaration, and it states it as one token. An edit that split the token,
            // or emitted a bare `autoplay`, would be the first step of exactly the divergence the
            // obligation guards against on a surface that does play.
            setContent { MaterialTheme { FuaranNode(media("Ambient loop", Video(autoplay = true))) } }
            waitForIdle()
            val paired =
                onAllNodes(hasText("autoplay+muted", substring = true, ignoreCase = true))
                    .fetchSemanticsNodes()
            val anyAutoplay =
                onAllNodes(hasText("autoplay", substring = true, ignoreCase = true))
                    .fetchSemanticsNodes()
            assertTrue("the declaration is stated at all", paired.isNotEmpty())
            // Counted rather than merely present: every place the tile names autoplay must be a
            // place it names the pair, whatever the merged-semantics shape happens to be.
            assertEquals(
                "autoplay is never stated apart from its pair",
                paired.size,
                anyAutoplay.size,
            )
        }

    @Test
    fun theTransportTileNamesADeclaredPosterAndNeverItsDestination() =
        runComposeUiTest {
            // `Media/refused-source-dropped` is declared EXEMPT for the structural reason this test
            // pins: no destination is emitted at all, so there is nothing for the egress floor to
            // have dropped. A destination reaching the output is the change that makes the exemption
            // false, and it fails here on the day it lands rather than at the next reading of a
            // paragraph. The URL below is safe by the scheme floor and entirely undeclared — the
            // input the "refused" obligations are about.
            setContent {
                MaterialTheme {
                    FuaranNode(
                        media(
                            "Studio walkthrough",
                            Video(poster = StaticBinding(JsonString("https://collector.example/poster.jpg"))),
                        ),
                    )
                }
            }
            waitForIdle()
            assertTrue(
                "the tile records THAT a poster was declared",
                onAllNodes(hasText("poster", substring = true, ignoreCase = true))
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
            )
            assertTrue(
                "…and never where it points — no destination is emitted for the egress floor to have to refuse",
                onAllNodes(hasText("collector.example", substring = true)).fetchSemanticsNodes().isEmpty(),
            )
        }
}
