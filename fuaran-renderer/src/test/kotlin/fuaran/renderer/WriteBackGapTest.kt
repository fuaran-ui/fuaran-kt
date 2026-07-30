// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 667 — the write-back gaps.
 *
 * The defect class: a control renders, the user edits it, the local Compose buffer updates, and the
 * value never reaches the session store. It is silent — no error, the UI looks right — which makes
 * it the worst kind to leave to inspection. Both hosts render from a sealed tree, so the compiler
 * cannot catch an arm that renders but forgets to write; a test per arm is the only guard.
 *
 * A regression here means a user's typing disappears, so each test drives the CONTROL (not the host
 * API) and asserts the store received the value.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WriteBackGapTest {
    /** A one-field form whose control is `kind`, bound to `$state.slot`. */
    private fun form(kind: String): String =
        """{"id":"f","kind":{"${'$'}type":"Form","fields":[{"id":"x","kind":$kind,"label":"L","required":false}],""" +
            """"onSubmit":{"${'$'}type":"Dispatch"},"submitLabel":"Go"}}"""

    private fun stateBound(type: String, extra: String = ""): String =
        """{"${'$'}type":"$type"$extra,"value":{"${'$'}type":"State","defaultValue":"","key":"slot"}}"""

    private fun host(tree: String): Pair<FuaranHost, FakeTreeSession> {
        val session = FakeTreeSession(tree)
        return FuaranHost(session) to session
    }

    // ── The arms the phase named ─────────────────────────────────────────────

    @Test
    fun textAreaFieldWritesBack() =
        runComposeUiTest {
            val (h, session) = host(form(stateBound("TextArea", ""","rows":3""")))
            setContent { FuaranTheme(darkTheme = false) { InteractiveFuaranTree(h) } }
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("typed")
            waitForIdle()
            assertTrue("TextArea must reach the store, got ${session.stateWrites}", session.stateWrites.isNotEmpty())
            assertEquals("slot", session.stateWrites.last().first)
        }

    // ── The arms the audit found beyond the phase's list ─────────────────────

    @Test
    fun dateFieldWritesBack() =
        runComposeUiTest {
            val (h, session) = host(form(stateBound("Date", ""","variant":"Date"""")))
            setContent { FuaranTheme(darkTheme = false) { InteractiveFuaranTree(h) } }
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("2026-03-01")
            waitForIdle()
            assertEquals("slot", session.stateWrites.last().first)
        }

    @Test
    fun dateRangeFieldWritesBack() =
        runComposeUiTest {
            val (h, session) = host(form(stateBound("DateRange", ""","variant":"Date"""")))
            setContent { FuaranTheme(darkTheme = false) { InteractiveFuaranTree(h) } }
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("2026-03-01")
            waitForIdle()
            assertEquals("slot", session.stateWrites.last().first)
        }

    @Test
    fun segmentedChoiceFieldWritesBackTheChosenOption() =
        runComposeUiTest {
            val kind =
                """{"${'$'}type":"SegmentedChoice","options":{"${'$'}type":"Static","value":"a,b"},""" +
                    """"value":{"${'$'}type":"State","defaultValue":"a","key":"slot"}}"""
            val (h, session) = host(form(kind))
            setContent { FuaranTheme(darkTheme = false) { InteractiveFuaranTree(h) } }
            waitForIdle()
            // Click the RADIO, not its label: the label is a sibling `Text`, not inside a
            // clickable row, so clicking the text would prove nothing about the control.
            // Options render in order, so index 1 is "b".
            onAllNodes(isSelectable())[1].performClick()
            waitForIdle()
            assertTrue("SegmentedChoice must reach the store, got ${session.stateWrites}", session.stateWrites.isNotEmpty())
            assertEquals("slot", session.stateWrites.last().first)
            assertTrue("must write the CHOSEN option", session.stateWrites.last().second.contains("b"))
        }

    // ── The guard: a non-writable binding must write NOTHING ─────────────────

    @Test
    fun aControlOverANonWritableBindingWritesNothing() =
        runComposeUiTest {
            // A Query-bound value is not a writable `$state` slot (the FUARAN069 condition), so the
            // control must stay inert rather than silently inventing a key to write to.
            val kind =
                """{"${'$'}type":"TextArea","rows":3,"value":{"${'$'}type":"Query","dependsOn":[],"name":"q"}}"""
            val (h, session) = host(form(kind))
            setContent { FuaranTheme(darkTheme = false) { InteractiveFuaranTree(h) } }
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("typed")
            waitForIdle()
            assertTrue("a non-writable binding must write nothing, got ${session.stateWrites}", session.stateWrites.isEmpty())
        }
}
