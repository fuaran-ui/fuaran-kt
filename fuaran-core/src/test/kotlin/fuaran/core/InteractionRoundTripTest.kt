// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fuaran.driver.HttpUrlTransport
import fuaran.driver.Rejected
import fuaran.driver.Rendered
import fuaran.driver.ServerDrivenDriver
import fuaran.ui.ActionDispatch
import fuaran.ui.FuaranException
import fuaran.ui.JsonNumber
import fuaran.ui.LiteralText
import fuaran.ui.Markdown
import fuaran.ui.Metric
import fuaran.ui.SetStateAction
import fuaran.ui.FuaranSession
import fuaran.ui.decodeNode
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Phase 545 **live-native** interaction round-trip leg — the same round-trip the Compose-side
 * `InteractionTest` proves against a fake, now against the **real Rust session** through the JNI
 * binding. Exercises the two write paths that matter:
 *
 *  - `ActionDispatch` of a wire Action / a raw `EditNode` op → the Rust core applies + validates →
 *    `tree_json` is re-read → `decodeNode` re-projects the sealed model (no wire-JSON handling outside
 *    the session boundary);
 *  - a validator reject surfaces as a typed `FuaranException`;
 *  - the `ServerDrivenDriver` runs its whole loop over the live session against an in-JVM fixture.
 *
 * **Gated:** the desktop native shim is supplied via `-Dfuaran.lib` (built by
 * `dev-scripts/build-native-desktop.ps1`; wired through the Gradle `test` task from `-Pfuaran.lib`).
 * When it is absent the whole class **skips cleanly** — the Swift-side clean-skip pattern — so
 * `:fuaran-core:test` is green on a box without the Rust toolchain, unless `FUARAN_REQUIRE_NATIVE=1`
 * says the caller built the shim and expects the leg to RUN. See [NativeLeg].
 */
class InteractionRoundTripTest {
    companion object {
        // Phase 1703: the load-or-skip decision moved to the module-wide `NativeLeg` gate, which
        // gives this leg the half it was missing — under `FUARAN_REQUIRE_NATIVE=1` an absent shim
        // is a FAILURE rather than a clean skip. The skip is right on a box with no Rust toolchain
        // and wrong in a workflow that has just built one, and only the caller can tell which case
        // it is in: before this, a CI job that built the shim and then failed to pass its path
        // would have skipped every test here and reported green.
        @BeforeClass
        @JvmStatic
        fun loadNative() = NativeLeg.loadOrSkip()

        // 0.2.0 canonical seeds: bare-string Literals; the scalar displayed value is `value`
        // (the retired `source` spelling is a hard decode error in the core).
        private val SEED_METRIC =
            """{"id":"metric-1","kind":{"${'$'}type":"Metric","format":{"${'$'}type":"Currency","code":"GBP"},""" +
                """"label":"Revenue","tone":"Brand","value":{"${'$'}type":"Static","value":1234.5}}}"""

        private val EDIT_TO_MARKDOWN =
            """{"${'$'}type":"EditNode","newKind":{"${'$'}type":"Markdown","text":{"${'$'}type":"Literal","text":"Edited"}},"target":"metric-1"}"""

        private val SEED_STATE_METRIC =
            """{"id":"m","kind":{"${'$'}type":"Metric","label":"Live",""" +
                """"value":{"${'$'}type":"State","defaultValue":0,"key":"n"}}}"""
    }

    @Test
    fun editOpRoundTripsThroughTheLiveCoreAndReProjects() {
        FuaranSession.create(NativeBridge, SEED_METRIC).use { session ->
            val before = decodeNode(session.treeJson())
            assertTrue(before.kind is Metric, "seed decodes to a Metric")

            // The interaction: apply the structural op, then re-project from the session's tree_json.
            session.applyOp(EDIT_TO_MARKDOWN)
            val after = decodeNode(session.treeJson())

            assertEquals("metric-1", after.id)
            val kind = after.kind
            assertTrue(kind is Markdown, "expected Markdown after EditNode, was ${kind::class.simpleName}")
            val text = kind.text
            assertTrue(text is LiteralText && text.text == "Edited")
        }
    }

    @Test
    fun actionDispatchWritesStateThroughTheLiveCore() {
        FuaranSession.create(NativeBridge, SEED_STATE_METRIC).use { session ->
            // A wire SetState action dispatched through the state channel — the control-interaction path.
            ActionDispatch.apply(session, SetStateAction(key = "n", value = JsonNumber("99")))
            // Re-projection still round-trips after the store write.
            val node = decodeNode(session.treeJson())
            assertEquals("m", node.id)
        }
    }

    @Test
    fun aValidatorRejectSurfacesAsTypedException() {
        FuaranSession.create(NativeBridge, SEED_METRIC).use { session ->
            var thrown: FuaranException? = null
            try {
                session.applyOp("""{"${'$'}type":"EditNode","newKind":{"${'$'}type":"NotAKind"},"target":"metric-1"}""")
            } catch (e: FuaranException) {
                thrown = e
            }
            assertNotNull(thrown, "an undecodable op must reject with a typed FuaranException")
            assertTrue(thrown.code.isNotBlank(), "reject carried a code")
        }
    }

    @Test
    fun serverDrivenDriverRunsTheLoopOverTheLiveCore() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/tree") { ex -> respond(ex, SEED_METRIC) }
            server.createContext("/ops") { ex -> respond(ex, EDIT_TO_MARKDOWN) }
            server.createContext("/events") { ex -> respond(ex, """{"ok":true}""") }
            server.start()
            val baseUrl = "http://127.0.0.1:${server.address.port}"

            val driver = ServerDrivenDriver(HttpUrlTransport(baseUrl)) { seed -> FuaranSession.create(NativeBridge, seed) }
            val states = mutableListOf<String>()
            driver.run { st ->
                when (st) {
                    is Rendered -> states.add((st.tree.kind)::class.simpleName ?: "?")
                    is Rejected -> states.add("Rejected(${st.error.code})")
                    else -> states.add("Fatal")
                }
            }
            // Seed projects to a Metric; the streamed EditNode re-projects to a Markdown — a live SDUI loop.
            assertEquals(listOf("Metric", "Markdown"), states)
        } finally {
            server.stop(0)
        }
    }

    private fun respond(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray(UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
