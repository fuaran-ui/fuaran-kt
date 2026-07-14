// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.driver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fuaran.ui.LiteralText
import fuaran.ui.Markdown
import fuaran.ui.Node
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Phase 545 server-driven-driver gate. A `com.sun.net.httpserver` fixture serves an initial tree,
 * a newline-delimited op stream (a valid op, a **rejected** op, a valid op), and an events endpoint;
 * the driver runs the full loop over a [FakeSession] (no native). Asserts:
 *
 *  - the initial tree seeds and re-projects (first [Rendered]);
 *  - each streamed op applies and re-projects;
 *  - the validator reject surfaces as a typed [Rejected] with the last-good tree retained — the loop
 *    **survives** and keeps going;
 *  - an interaction event posts back and the server receives it;
 *  - the reference [HttpUrlTransport] (HttpURLConnection) drives all three endpoints.
 */
class ServerDrivenDriverTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val postedEvents = mutableListOf<String>()

    private fun md(id: String, text: String): String =
        """{"id":"$id","kind":{"${'$'}type":"Markdown","text":{"${'$'}type":"Literal","text":"$text"}}}"""

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seed = md("root", "Hello")
        // The op stream: replace -> reject -> replace (NDJSON).
        val ops =
            listOf(
                """{"cmd":"replace","node":${md("root", "One")}}""",
                """{"cmd":"reject","code":"VALIDATION_REJECT","path":"/root"}""",
                """{"cmd":"replace","node":${md("root", "Three")}}""",
            ).joinToString("\n")

        server.createContext("/tree") { ex -> respond(ex, seed) }
        server.createContext("/ops") { ex -> respond(ex, ops) }
        server.createContext("/events") { ex ->
            postedEvents.add(ex.requestBody.readBytes().toString(UTF_8))
            respond(ex, """{"ok":true}""")
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun respond(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray(UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun text(node: Node): String = ((node.kind as Markdown).text as LiteralText).text

    @Test
    fun driverRunsTheFullLoopAndSurvivesAReject() {
        val transport = HttpUrlTransport(baseUrl)
        val driver = ServerDrivenDriver(transport) { initial -> FakeSession(initial) }

        val states = mutableListOf<DriverState>()
        val finalState = driver.run { states.add(it) }

        // Four steps: seed + three ops.
        assertEquals(4, states.size, "expected one state per step (seed + 3 ops)")

        // 1. Seed re-projects.
        val s0 = states[0]
        assertTrue(s0 is Rendered)
        assertEquals("Hello", text((s0 as Rendered).tree))

        // 2. First op applies + re-projects.
        val s1 = states[1]
        assertTrue(s1 is Rendered)
        assertEquals("One", text((s1 as Rendered).tree))

        // 3. Reject survives as a typed error state, retaining the last-good tree.
        val s2 = states[2]
        assertTrue(s2 is Rejected, "reject must surface as Rejected, was ${s2::class.simpleName}")
        s2 as Rejected
        assertEquals("VALIDATION_REJECT", s2.error.code)
        assertEquals("/root", s2.error.path)
        assertEquals("One", text(s2.tree), "last-good tree retained across the reject")

        // 4. The loop continued: the op after the reject applied.
        val s3 = states[3]
        assertTrue(s3 is Rendered)
        assertEquals("Three", text((s3 as Rendered).tree))

        assertTrue(finalState is Rendered)

        // Interaction event posts back.
        val response = driver.postEvent("""{"type":"click","target":"root"}""")
        assertEquals("""{"ok":true}""", response)
        assertEquals(1, postedEvents.size)
        assertTrue(postedEvents[0].contains("click"))
    }

    @Test
    fun referenceTransportDrivesAllThreeEndpoints() {
        val transport = HttpUrlTransport(baseUrl)
        assertTrue(transport.fetchInitialTree().contains("Hello"))
        val ops = transport.openOpStream().toList()
        assertEquals(3, ops.size, "op stream yields one entry per non-blank NDJSON line")
        assertTrue(ops[1].contains("reject"))
        transport.postEvent("""{"type":"submit"}""")
        assertFalse(postedEvents.isEmpty())
    }
}
