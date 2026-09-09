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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Phase 1541 streaming-driver gate, over a real socket.
 *
 * Four properties the loop did not have, each asserted against a fixture a buffered, unbounded reader
 * could not pass:
 *
 *  - a stream that goes QUIET for longer than the socket read timeout survives — the ordinary state of
 *    a server-driven session, and the one that used to kill it after thirty seconds;
 *  - a transport failure reaching the loop is [Fatal] rather than an exception thrown out of `run` at
 *    whatever thread the loop was on;
 *  - a hostile over-cap line is refused BY NAME on the driver's own path;
 *  - an event's REPLY OPS are applied through the same apply-then-project path the stream uses, so an
 *    interaction has visible consequences.
 */
class StreamingDriverTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private fun md(text: String): String =
        """{"id":"root","kind":{"${'$'}type":"Markdown","text":{"${'$'}type":"Literal","text":"$text"}}}"""

    private fun text(node: Node): String = ((node.kind as Markdown).text as LiteralText).text

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tree") { ex -> respond(ex, md("Hello")) }
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

    /** A chunked response written in pieces, with a pause between them. */
    private fun respondChunked(ex: HttpExchange, pieces: List<String>, pauseMillis: Long) {
        ex.responseHeaders.add("Content-Type", "application/x-ndjson")
        ex.sendResponseHeaders(200, 0) // 0 => chunked
        ex.responseBody.use { out ->
            pieces.forEachIndexed { index, piece ->
                out.write(piece.toByteArray(UTF_8))
                out.flush()
                if (index < pieces.size - 1) Thread.sleep(pauseMillis)
            }
        }
    }

    // ── The idle policy, over a real socket ──────────────────────────────────────────────────────

    /**
     * **This test takes at least 35 seconds, deliberately.** It is the only shape that proves the
     * thing: the previous reader set a 30 s socket read timeout and treated the resulting
     * `SocketTimeoutException` as fatal, so a session whose server had nothing to say for half a
     * minute — which is most sessions, most of the time — died with a transport error and a screen
     * that stopped updating. A fake clock proves the arithmetic (see `BoundedStreamTest`); only a real
     * quiet socket proves that a `HttpURLConnection` stream is still readable after its read timeout
     * has fired, which is the assumption the whole policy rests on.
     */
    @Test
    fun aStreamThatGoesQuietForLongerThanTheReadTimeoutSurvives() {
        val idleMillis = 35_000L
        server.createContext("/ops") { ex ->
            respondChunked(
                ex,
                listOf(
                    """{"cmd":"replace","node":${md("One")}}""" + "\n",
                    """{"cmd":"replace","node":${md("Two")}}""" + "\n",
                ),
                idleMillis,
            )
        }

        // A 5 s poll interval against a 35 s silence: seven ticks the reader must absorb.
        val transport = HttpUrlTransport(baseUrl, streamPollMillis = 5_000)
        val driver = ServerDrivenDriver(transport) { initial -> FakeSession(initial) }

        val rendered = mutableListOf<String>()
        val started = System.currentTimeMillis()
        val final = driver.run { state -> if (state is Rendered) rendered.add(text(state.tree)) }
        val elapsed = System.currentTimeMillis() - started

        assertEquals(listOf("Hello", "One", "Two"), rendered, "the loop must survive the quiet stretch")
        assertIs<Rendered>(final)
        assertTrue(
            elapsed >= idleMillis,
            "the fixture must actually have gone quiet for $idleMillis ms; elapsed $elapsed ms",
        )
    }

    // ── A transport failure is terminal, not thrown ──────────────────────────────────────────────

    /**
     * The op stream is opened LAZILY inside the sequence, so a non-2xx `/ops` status surfaces while the
     * loop is iterating — which meant it escaped `run` entirely and unwound the caller's thread.
     */
    @Test
    fun aFailingOpStreamIsFatalRatherThanThrown() {
        server.createContext("/ops") { ex ->
            val bytes = "unavailable".toByteArray(UTF_8)
            ex.sendResponseHeaders(503, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        val driver = ServerDrivenDriver(HttpUrlTransport(baseUrl)) { initial -> FakeSession(initial) }
        val states = mutableListOf<DriverState>()
        val final = driver.run { states.add(it) }

        assertEquals(2, states.size, "the seed, then the failure")
        assertIs<Rendered>(states[0])
        // NOT `Rejected`. A validator reject is survivable because the next op is still coming; a dead
        // transport has no next op, so calling it survivable would leave a host waiting forever on a
        // screen that merely looks stale.
        val fatal = assertIs<Fatal>(states[1])
        assertIs<TransportException>(fatal.cause)
        assertIs<Fatal>(final)
    }

    @Test
    fun anOverCapOpLineReachesTheDriverAsAFatalNamingTheCap() {
        server.createContext("/ops") { ex -> respond(ex, "x".repeat(4096)) }

        val transport = HttpUrlTransport(baseUrl, bounds = OpStreamBounds(maxLineBytes = 8))
        val driver = ServerDrivenDriver(transport) { initial -> FakeSession(initial) }
        val states = mutableListOf<DriverState>()
        driver.run { states.add(it) }

        val fatal = assertIs<Fatal>(states.last())
        val cause = assertIs<TransportException>(fatal.cause)
        assertEquals(TransportFailure.LINE_CAP_EXCEEDED, cause.failure)
        assertContains(cause.message.orEmpty(), "maxLineBytes")
    }

    // ── The reply channel ────────────────────────────────────────────────────────────────────────

    /**
     * The fixture server answers the POST with a `SetState` op, and the driver applies it through the
     * same apply-then-project path a streamed op takes.
     *
     * The event is posted from inside `onState`, which is where a real one arrives: the loop owns the
     * session's lifetime, so a host runs it on a background thread and posts from the UI thread while
     * it is live. Posting after `run` returns would be a call on a closed session — a lifecycle
     * defect, and reported as one rather than as a data condition.
     */
    @Test
    fun postedEventRepliesAreAppliedThroughTheSameProjectionPath() {
        server.createContext("/ops") { ex -> respond(ex, """{"cmd":"replace","node":${md("One")}}""") }
        server.createContext("/events") { ex ->
            ex.requestBody.readBytes()
            respond(ex, """{"cmd":"setState","key":"greeting","value":"Clicked"}""")
        }

        val driver = ServerDrivenDriver(HttpUrlTransport(baseUrl)) { initial -> FakeSession(initial) }
        val replies = mutableListOf<DriverState>()
        var posted = false

        driver.run { state ->
            if (state is Rendered && text(state.tree) == "One" && !posted) {
                posted = true
                driver.postEventApplyingReply("""{"type":"click","target":"root"}""") { replies.add(it) }
            }
        }

        assertTrue(posted, "the fixture stream must have rendered the op the event follows")
        assertEquals(1, replies.size, "one state per applied reply op")
        val rendered = assertIs<Rendered>(replies[0])
        assertEquals(
            "Clicked",
            text(rendered.tree),
            "an event's consequences must reach the projection, not stop at the transport",
        )
    }

    /**
     * A reply op the session rejects is SURVIVED, exactly as a streamed one is — the last-good tree is
     * retained rather than the screen being blanked because a button was pressed.
     */
    @Test
    fun aRejectedReplyOpIsSurvivedWithTheLastGoodTreeRetained() {
        server.createContext("/ops") { ex -> respond(ex, """{"cmd":"replace","node":${md("One")}}""") }
        server.createContext("/events") { ex ->
            ex.requestBody.readBytes()
            respond(ex, """{"cmd":"reject","code":"VALIDATION_REJECT","path":"/root"}""")
        }

        val driver = ServerDrivenDriver(HttpUrlTransport(baseUrl)) { initial -> FakeSession(initial) }
        var replied: DriverState? = null
        driver.run { state ->
            if (state is Rendered && text(state.tree) == "One" && replied == null) {
                replied = driver.postEventApplyingReply("""{"type":"click"}""") {}
            }
        }

        val rejected = assertIs<Rejected>(replied)
        assertEquals("VALIDATION_REJECT", rejected.error.code)
        assertEquals("One", text(rejected.tree), "last-good tree retained across a rejected reply")
    }

    /**
     * The default `postEventOps` is the EMPTY sequence, not the response body. A transport whose
     * server answers with an acknowledgement has not implemented a reply channel, and reading
     * `{"ok":true}` as a `TreeOp` would turn every successful event into a validator reject.
     */
    @Test
    fun aTransportWithNoReplyChannelYieldsNoReplyOpsButStillPosts() {
        val posted = mutableListOf<String>()
        val transport =
            object : FuaranTransport {
                override fun fetchInitialTree(): String = md("Hello")

                override fun openOpStream(): Sequence<String> = emptySequence()

                override fun postEvent(eventJson: String): String {
                    posted.add(eventJson)
                    return """{"ok":true}"""
                }
            }

        assertEquals(emptyList(), transport.postEventOps("""{"type":"click"}""").toList())
        assertEquals(1, posted.size, "the default is \"no reply ops\", never \"no post\"")
    }

    /** Called outside a live loop it reports so, rather than pretending or touching a closed handle. */
    @Test
    fun applyingARepliesOutsideALiveLoopIsFatal() {
        val driver = ServerDrivenDriver(HttpUrlTransport(baseUrl)) { initial -> FakeSession(initial) }
        val state = driver.postEventApplyingReply("""{"type":"click"}""") {}
        assertIs<Fatal>(state)
    }
}
