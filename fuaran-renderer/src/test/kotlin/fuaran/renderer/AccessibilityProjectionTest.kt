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
import fuaran.ui.QueryBinding
import fuaran.ui.StateBinding
import fuaran.ui.StaticBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The accessibility projection.
 *
 * Two tiers, deliberately — the same shape as the grid-cell walk. The **projection** is pure logic
 * and is asserted directly, because that is where the load-bearing semantics live: the role map's
 * unmapped fallback, the empty-label drop, and above all the DROP SET itself, which is the whole
 * content of the policy in `CLAUDE.md` ("dropped, never silently"). The **render** is then proved
 * under the headless Compose harness — the semantics genuinely reach the tree rather than merely
 * being computed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityProjectionTest {
    private val ctx = BindingContext.Empty

    private fun text(s: String) = StaticBinding(JsonString(s))

    private fun flag(b: Boolean) = StaticBinding(JsonBool(b))

    private fun node(a11y: Accessibility?, body: String = "content") =
        Node(id = "n", kind = Markdown(LiteralText(body)), accessibility = a11y)

    // ── The absent trait ─────────────────────────────────────────────────────

    @Test
    fun noTraitProjectsNothing() {
        val p = accessibilityProjection(null, ctx)
        assertTrue(p.isEmpty)
        assertTrue(p.unmapped.isEmpty())
    }

    // ── label ────────────────────────────────────────────────────────────────

    @Test
    fun labelResolvesThroughTheBinding() {
        val p = accessibilityProjection(Accessibility(label = text("Save changes")), ctx)
        assertEquals("Save changes", p.label)
        assertFalse(p.isEmpty)
    }

    @Test
    fun labelResolvingEmptyIsDropped() {
        // Mirrors the reference projection's empty filter — and the stake is higher here than
        // parity: an empty `contentDescription` would ERASE the node's natural name.
        val p = accessibilityProjection(Accessibility(label = text("")), ctx)
        assertNull(p.label)
        assertTrue(p.isEmpty)
    }

    @Test
    fun anUnresolvableLabelBindingIsDroppedNotRenderedAsAPlaceholder() {
        // A host-owned binding has no wire-surviving value at the render floor, so it resolves
        // empty — and an empty label is dropped, never emitted as "".
        val p = accessibilityProjection(Accessibility(label = QueryBinding("orders")), ctx)
        assertNull(p.label)
    }

    // ── role ─────────────────────────────────────────────────────────────────

    @Test
    fun theMappedRoleTokensCarryTheirSemantics() {
        val button = accessibilityProjection(Accessibility(role = "button"), ctx)
        assertEquals(Role.Button, button.role)
        assertTrue(button.unmapped.isEmpty())

        val tab = accessibilityProjection(Accessibility(role = "tab"), ctx)
        assertEquals(Role.Tab, tab.role)
        assertTrue(tab.unmapped.isEmpty())

        val heading = accessibilityProjection(Accessibility(role = "heading"), ctx)
        assertTrue(heading.heading)
        assertNull(heading.role)
        assertTrue(heading.unmapped.isEmpty())
    }

    @Test
    fun everyOtherWireRoleIsReportedUnmappedNotApproximated() {
        // The decision this pins: a role with no Compose semantics that MEANS what it means is
        // dropped rather than approximated. `link` is the one that most invites a substitute —
        // Role.Button would announce a link as a button, which is a mis-statement, not a partial.
        for (token in listOf(
            "link", "dialog", "alert", "status", "banner", "navigation", "main", "form", "region",
            "progressbar", "tablist", "tabpanel",
        )) {
            val p = accessibilityProjection(Accessibility(role = token), ctx)
            assertNull("role $token should carry no semantics role", p.role)
            assertFalse("role $token is not a heading", p.heading)
            assertEquals("role $token should report the drop", listOf(A11ySlot.Role), p.unmapped)
            assertTrue("role $token alone should project nothing", p.isEmpty)
        }
    }

    @Test
    fun aCustomRoleIsUnmappedByDefinition() {
        val p = accessibilityProjection(Accessibility(role = "treegrid"), ctx)
        assertEquals(listOf(A11ySlot.Role), p.unmapped)
    }

    @Test
    fun theRoleTokenIsMatchedExactlyNotCaseFolded() {
        // The wire's tokens are lowercase ARIA roles and the reference emits the token the author
        // declared. Accepting "Button" here would honour a spelling no HTML tier honours.
        val p = accessibilityProjection(Accessibility(role = "Button"), ctx)
        assertNull(p.role)
        assertEquals(listOf(A11ySlot.Role), p.unmapped)
    }

    // ── labelledBy / describedBy — the drop set ──────────────────────────────

    @Test
    fun labelledByAndDescribedByAreReportedUnmapped() {
        val p = accessibilityProjection(
            Accessibility(labelledBy = "heading-1", describedBy = "help-1"),
            ctx,
        )
        assertEquals(listOf(A11ySlot.LabelledBy, A11ySlot.DescribedBy), p.unmapped)
        // Reported, and projecting nothing — the composable is emitted untouched.
        assertTrue(p.isEmpty)
    }

    @Test
    fun theDropSetIsReportedInWireSlotOrder() {
        // Order is part of the contract: the drop set is meant to be READ, and a set that reorders
        // itself per input is one nobody can assert against.
        val p = accessibilityProjection(
            Accessibility(labelledBy = "h", describedBy = "d", role = "banner"),
            ctx,
        )
        assertEquals(listOf(A11ySlot.LabelledBy, A11ySlot.DescribedBy, A11ySlot.Role), p.unmapped)
    }

    // ── liveRegion — the slot this platform maps EXACTLY ─────────────────────

    @Test
    fun politeAndAssertiveKeepTheirDistinction() {
        // Compose carries the politeness distinction the wire declares, so nothing is lost here —
        // unlike the sibling Swift surface, whose declarative analogue cannot express it. The two
        // native surfaces answer to the reference `aria-live`, not to each other.
        assertEquals(
            LiveRegionMode.Polite,
            accessibilityProjection(Accessibility(liveRegion = "polite"), ctx).liveRegion,
        )
        assertEquals(
            LiveRegionMode.Assertive,
            accessibilityProjection(Accessibility(liveRegion = "assertive"), ctx).liveRegion,
        )
    }

    @Test
    fun offProjectsNothingAndIsNotADrop() {
        // `off` asserts "do not announce", which IS the platform default — so the faithful
        // projection is the absence of a live region, not a reported drop.
        val p = accessibilityProjection(Accessibility(liveRegion = "off"), ctx)
        assertNull(p.liveRegion)
        assertTrue(p.unmapped.isEmpty())
        assertTrue(p.isEmpty)
    }

    @Test
    fun anUnrecognisedLiveRegionTokenIsADropNotAGuess() {
        val p = accessibilityProjection(Accessibility(liveRegion = "urgent"), ctx)
        assertNull(p.liveRegion)
        assertEquals(listOf(A11ySlot.LiveRegion), p.unmapped)
    }

    // ── hidden — the aria-hidden analogue ────────────────────────────────────

    @Test
    fun hiddenTrueProjectsTheHiddenFlag() {
        // The author's intent is to REMOVE the subtree from the accessibility tree, and that intent
        // must survive the crossing to a native surface with the same force it has on the web.
        val p = accessibilityProjection(Accessibility(hidden = flag(true)), ctx)
        assertTrue(p.hidden)
        assertFalse(p.isEmpty)
    }

    @Test
    fun hiddenFalseProjectsNothing() {
        // Mirrors the reference: only a resolved-true `hidden` emits. Hiding on `false` would
        // invert the author's assertion.
        val p = accessibilityProjection(Accessibility(hidden = flag(false)), ctx)
        assertFalse(p.hidden)
        assertTrue(p.isEmpty)
    }

    @Test
    fun hiddenResolvesThroughSeededState() {
        val seeded = BindingContext(state = mapOf("decorative" to JsonBool(true)))
        val p = accessibilityProjection(
            Accessibility(hidden = StateBinding("decorative", JsonBool(false))),
            seeded,
        )
        assertTrue(p.hidden)
    }

    // ── The whole trait at once ──────────────────────────────────────────────

    @Test
    fun aFullyPopulatedTraitProjectsTheMappableHalfAndReportsTheRest() {
        val p = accessibilityProjection(
            Accessibility(
                label = text("Open the report"),
                labelledBy = "heading-1",
                describedBy = "help-1",
                role = "button",
                liveRegion = "polite",
                hidden = flag(true),
            ),
            ctx,
        )
        assertEquals("Open the report", p.label)
        assertEquals(Role.Button, p.role)
        assertEquals(LiveRegionMode.Polite, p.liveRegion)
        assertTrue(p.hidden)
        assertEquals(listOf(A11ySlot.LabelledBy, A11ySlot.DescribedBy), p.unmapped)
    }

    @Test
    fun theProjectionNeverThrowsOnAnUnmappableTrait() {
        // The policy as a property rather than a sentence: a render surface does not REFUSE a tree
        // the wire declares valid. Every slot combination projects some (possibly empty) result and
        // reports what it could not carry — a surface that rejected one would fork the vocabulary
        // by platform.
        val p = accessibilityProjection(
            Accessibility(labelledBy = "a", describedBy = "b", role = "tablist"),
            ctx,
        )
        assertTrue(p.isEmpty)
        assertEquals(3, p.unmapped.size)
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
