// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import fuaran.ui.FuaranException
import fuaran.ui.TreeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Phase 545 interaction round-trip gate (Compose-side, native-free).
 *
 * A [FakeTreeSession] stands in for the live Rust session so the **state-holder + recomposition**
 * behaviour is provable headlessly under Robolectric: dispatch/apply → session → `tree` Compose state
 * changes → the subtree recomposes; a reject sets [FuaranHost.lastError] and keeps the last-good tree;
 * a form-control edit writes through the `$state` channel. The **live-native** round-trip (a real
 * `EditNode` through the Rust validator) is the `:fuaran-core` `InteractionRoundTripTest` leg.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InteractionTest {
    private fun md(text: String): String =
        """{"id":"root","kind":{"${'$'}type":"Markdown","text":{"${'$'}type":"Literal","text":"$text"}}}"""

    @Test
    fun applyingAnOpReprojectsAndRecomposes() =
        runComposeUiTest {
            val session = FakeTreeSession(md("Hello"))
            val host = FuaranHost.start(session)
            setContent { FuaranTheme(darkTheme = false) { InteractiveFuaranTree(host) } }
            waitForIdle()

            onNodeWithText("Hello").assertIsDisplayed()

            // A structural edit through the session re-projects and recomposes the subtree.
            host.applyOp(md("World"))
            waitForIdle()

            onNodeWithText("World").assertIsDisplayed()
            assertNull("a successful apply clears lastError", host.lastError)
        }

    @Test
    fun aValidatorRejectSurfacesAsTypedErrorAndKeepsLastGoodTree() =
        runComposeUiTest {
            val session = FakeTreeSession(md("Stable"))
            val host = FuaranHost.start(session)
            setContent { FuaranTheme(darkTheme = false) { InteractiveFuaranTree(host) } }
            waitForIdle()

            host.applyOp("""{"__reject__":true}""")
            waitForIdle()

            val err = host.lastError
            assertNotNull("reject must set a typed lastError", err)
            assertEquals("VALIDATION_REJECT", err!!.code)
            // The last-good tree is retained and still rendered.
            onNodeWithText("Stable").assertIsDisplayed()
        }

    /**
     * A write that lands a node this projection does not model is survived: [FuaranHost.lastError]
     * carries the decode failure, the last-good tree stays on screen, and the Compose main thread
     * is never unwound. The fake swaps its tree on the `\$state` write, which is the shape of the
     * live core accepting vocabulary this decode-only surface cannot read.
     */
    @Test
    fun aDecoderGapAfterAStateWriteSurfacesAsLastErrorAndKeepsLastGoodTree() =
        runComposeUiTest {
            val session = FakeTreeSession(md("Stable"))
            val host = FuaranHost.start(session)
            setContent { FuaranTheme(darkTheme = false) { InteractiveFuaranTree(host) } }
            waitForIdle()

            session.treeAfterNextWrite = """{"id":"root","kind":{"${'$'}type":"NotAKindThisProjectionModels"}}"""
            host.writeBack("name", "Ada")
            waitForIdle()

            val err = host.lastError
            assertNotNull("a decode gap must set a typed lastError", err)
            assertEquals("WRONG_NODE_KIND", err!!.code)
            assertEquals("decode", err.errorClass)
            onNodeWithText("Stable").assertIsDisplayed()

            // And the host is still alive: a good write clears the error and re-projects.
            host.applyOp(md("Recovered"))
            waitForIdle()
            assertNull(host.lastError)
            onNodeWithText("Recovered").assertIsDisplayed()
        }

    @Test
    fun formControlEditWritesThroughTheStateChannel() =
        runComposeUiTest {
            // A Form with a single text field bound to $state.name.
            val form =
                """{"id":"f","kind":{"${'$'}type":"Form","submitLabel":{"${'$'}type":"Literal","text":"Go"},""" +
                    """"onSubmit":{"${'$'}type":"Dispatch"},"fields":[{"id":"name","required":false,""" +
                    """"label":{"${'$'}type":"Literal","text":"Name"},"kind":{"${'$'}type":"Text",""" +
                    """"value":{"${'$'}type":"State","key":"name","defaultValue":""}}}]}}"""
            val session = FakeTreeSession(form)
            val host = FuaranHost.start(session)
            setContent { FuaranTheme(darkTheme = false) { InteractiveFuaranTree(host) } }
            waitForIdle()

            onNode(hasSetTextAction()).performTextInput("Ada")
            waitForIdle()

            assertTrue("expected a \$state.name write-back, saw ${session.stateWrites}", session.stateWrites.any { it.first == "name" })
            val lastWrite = session.stateWrites.last { it.first == "name" }
            assertTrue("write-back value is JSON-encoded", lastWrite.second.contains("Ada"))
        }
}

/**
 * A native-free [TreeSession] for the renderer interaction tests. `applyOp` treats a `__reject__`
 * marker as a validator reject (a typed [FuaranException]) and otherwise adopts the op payload as the
 * replacement tree JSON — enough to drive the re-projection + recomposition path without the Rust core.
 */
class FakeTreeSession(initial: String) : TreeSession {
    private var current: String = initial
    val stateWrites: MutableList<Pair<String, String>> = mutableListOf()

    /** When set, the next `\$state` write adopts this tree — a write the core accepts and re-projects. */
    var treeAfterNextWrite: String? = null

    override fun treeJson(): String = current

    /**
     * This fake carries no evaluator, so its resolved projection IS its tree — stated explicitly
     * because `projectResolved` is abstract now. It used to default to `treeJson()` on the
     * interface, which made a conformer that resolves nothing indistinguishable from one that was
     * never asked to.
     */
    override fun projectResolved(): String = current

    override fun applyOp(opJson: String) {
        if (opJson.contains("__reject__")) {
            throw FuaranException("VALIDATION_REJECT", "/", "validation", "fake reject")
        }
        current = opJson
    }

    override fun setState(key: String, valueJson: String) {
        stateWrites.add(key to valueJson)
        treeAfterNextWrite?.let {
            current = it
            treeAfterNextWrite = null
        }
    }

    override fun setFilter(key: String, valueJson: String) {
        stateWrites.add("filter:$key" to valueJson)
    }

    override fun setQuery(key: String, valueJson: String) {
        stateWrites.add("query:$key" to valueJson)
    }

    override fun close() {}
}
