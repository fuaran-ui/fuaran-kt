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
            val host = FuaranHost(session)
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
            val host = FuaranHost(session)
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
            val host = FuaranHost(session)
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

    override fun treeJson(): String = current

    override fun applyOp(opJson: String) {
        if (opJson.contains("__reject__")) {
            throw FuaranException("VALIDATION_REJECT", "/", "validation", "fake reject")
        }
        current = opJson
    }

    override fun setState(key: String, valueJson: String) {
        stateWrites.add(key to valueJson)
    }

    override fun setFilter(key: String, valueJson: String) {
        stateWrites.add("filter:$key" to valueJson)
    }

    override fun setQuery(key: String, valueJson: String) {
        stateWrites.add("query:$key" to valueJson)
    }

    override fun close() {}
}
