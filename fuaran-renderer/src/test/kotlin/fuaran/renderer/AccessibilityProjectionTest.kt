// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import fuaran.ui.Accessibility
import fuaran.ui.JsonBool
import fuaran.ui.JsonString
import fuaran.ui.LiteralText
import fuaran.ui.Markdown
import fuaran.ui.Node
import fuaran.ui.StaticBinding
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The accessibility projection — **the half that only this gate can answer**.
 *
 * The mapping decisions (which role tokens carry a semantics role, what an empty resolved label
 * does, and above all the DROP SET) moved to `AccessibilityProjectionHarness` and
 * `AccessibilityCorpusHarness`, which run in the plain-JVM gate against the platform-neutral
 * result type. They used to live here, and that was the defect: the projection was typed in
 * Compose vocabulary, so the whole content of the "dropped, never silently" policy could only be
 * re-checked on a machine carrying the Android SDK — which is to say on CI, and not on the machine
 * anyone changes the mapping from.
 *
 * What stays is what genuinely needs a Compose host: that the projected semantics REACH the
 * semantics tree, that a hidden node keeps its pixels and loses its announcement, and that an
 * unmappable-only trait leaves the render untouched. The one delegating test below re-runs the
 * neutral mapping set here too, so this gate still fails on a mapping regression rather than
 * merely on a rendering one — the checks are shared, not duplicated.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityProjectionTest {
    private fun text(s: String) = StaticBinding(JsonString(s))

    private fun flag(b: Boolean) = StaticBinding(JsonBool(b))

    private fun node(a11y: Accessibility?, body: String = "content") =
        Node(id = "n", kind = Markdown(LiteralText(body)), accessibility = a11y)

    // ── The neutral mapping set, re-run here ─────────────────────────────────

    @Test
    fun theMappingDecisionsAndTheDropSetHoldUnderThisGateToo() {
        // Delegating rather than restating: one set of expectations, two gates. A second copy here
        // is how the two would come to disagree, and the disagreement would be invisible until
        // someone ran both.
        assertEquals(emptyList<String>(), a11yMappingFailures())
    }

    // ── The render — the semantics genuinely reach the tree ──────────────────

    @Test
    fun aLabelledNodeIsAnnouncedByItsAuthoredNameNotItsContent() =
        runComposeUiTest {
            setContent { FuaranNode(node(Accessibility(label = text("Quarterly revenue")))) }
            onNodeWithContentDescription("Quarterly revenue").assertExists()
        }

    @Test
    fun aRoleReachesTheSemanticsTree() =
        runComposeUiTest {
            setContent { FuaranNode(node(Accessibility(role = "button"))) }
            onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertExists()
        }

    @Test
    fun theOtherMappedRoleReachesTheSemanticsTree() =
        runComposeUiTest {
            // `tab` is the token this surface maps and the sibling native surface does not, so it
            // is worth proving it arrives rather than merely that it projects.
            setContent { FuaranNode(node(Accessibility(role = "tab"))) }
            onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertExists()
        }

    @Test
    fun aLiveRegionReachesTheSemanticsTree() =
        runComposeUiTest {
            setContent { FuaranNode(node(Accessibility(liveRegion = "assertive"))) }
            onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            ).assertExists()
        }

    @Test
    fun thePolitenessDistinctionSurvivesTheCrossing() =
        runComposeUiTest {
            // The neutral half asserts `polite` and `assertive` project to different values; this
            // asserts the difference survives the Compose mapping rather than collapsing in the
            // arm that translates it.
            setContent { FuaranNode(node(Accessibility(liveRegion = "polite"))) }
            onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            ).assertExists()
        }

    @Test
    fun aHeadingRoleReachesTheSemanticsTree() =
        runComposeUiTest {
            setContent { FuaranNode(node(Accessibility(role = "heading"))) }
            onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        }

    @Test
    fun aHiddenNodeLeavesTheAccessibilityTreeWhileStillRendering() =
        runComposeUiTest {
            // The whole point of `aria-hidden`, held natively: the pixels stay, the announcement
            // goes. A projection that dropped the node from the layout, or one that left it
            // announced, would each fail a different half of the author's intent.
            setContent {
                FuaranNode(node(Accessibility(hidden = flag(true)), body = "decorative flourish"))
            }
            onNodeWithText("decorative flourish").assertIsDisplayed()
            onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
                .assertExists()
        }

    @Test
    fun anUnmappableOnlyTraitLeavesTheRenderUntouched() =
        runComposeUiTest {
            // The drop is reported on the projection, never rendered: no semantics container, no
            // announced name, no change to what the arm emitted.
            setContent {
                FuaranNode(
                    node(Accessibility(labelledBy = "x", describedBy = "y"), body = "plain body"),
                )
            }
            onNodeWithText("plain body").assertIsDisplayed()
        }
}
