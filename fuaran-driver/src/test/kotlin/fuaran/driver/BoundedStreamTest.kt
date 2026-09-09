// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.driver

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Phase 1541 stream-bounds and scheme gate.
 *
 * These drive the COMPONENT the live transport uses — [BoundedOpLineReader] is what
 * `HttpUrlTransport.openOpStream` feeds and what `splitOps` runs the buffered reply through — so a
 * bound proven here is the bound that ships. A cap reachable only through a socket is a cap nobody can
 * test without one, which is how a limit comes to be written down and never enforced.
 */
class BoundedStreamTest {
    private fun read(body: String, bounds: OpStreamBounds): List<String> =
        BoundedOpLineReader(body.toByteArray().inputStream(), bounds, "/ops").asSequence().toList()

    // ── The line cap ─────────────────────────────────────────────────────────────────────────────

    /**
     * The hostile input this cap exists for: ONE op, arbitrarily long. `BufferedReader.readLine()`,
     * which this reader replaced, has no length limit whatever — a line with no newline in it is an
     * `OutOfMemoryError` with no diagnosis, delivered to whichever allocation happened to be next.
     */
    @Test
    fun anOverLongOpLineIsRefusedByName() {
        val bounds = OpStreamBounds(maxLineBytes = 16)
        val thrown =
            assertFailsWith<TransportException> { read("x".repeat(64), bounds) }

        // Refused BY NAME in both senses: a typed class a host can branch on, and a message naming
        // the limit that was breached rather than "too long".
        assertEquals(TransportFailure.LINE_CAP_EXCEEDED, thrown.failure)
        assertContains(thrown.message.orEmpty(), "maxLineBytes")
        assertContains(thrown.message.orEmpty(), "16")
        assertContains(thrown.message.orEmpty(), "/ops")
    }

    /**
     * The cap is on ONE line, not on the stream. Without this the assertion above is satisfied by a
     * reader that refuses everything.
     */
    @Test
    fun manyShortOpsPassTheLineCap() {
        val body = List(1_000) { """{"i":1}""" }.joinToString("\n")
        assertEquals(1_000, read(body, OpStreamBounds(maxLineBytes = 16)).size)
    }

    // ── The body cap ─────────────────────────────────────────────────────────────────────────────

    /**
     * The other hostile shape: a stream of individually-legal ops that never ends. The line cap cannot
     * see it; only a total can.
     */
    @Test
    fun anOverLongBodyIsRefusedByName() {
        val body = List(64) { """{"i":1}""" }.joinToString("\n")
        val thrown =
            assertFailsWith<TransportException> { read(body, OpStreamBounds(maxBodyBytes = 32L)) }

        assertEquals(TransportFailure.BODY_CAP_EXCEEDED, thrown.failure)
        assertContains(thrown.message.orEmpty(), "maxBodyBytes")
        assertContains(thrown.message.orEmpty(), "32")
    }

    // ── Framing ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun framingDropsBlankLinesAndCarriageReturnsAndKeepsAnUnterminatedTail() {
        // `\r` is FRAMING on an NDJSON wire, not payload: handing it to a JSON parser is a refusal of
        // a document the server sent correctly.
        val ops = read("{\"a\":1}\r\n\n  \n{\"b\":2}", OpStreamBounds.Default)
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), ops)
    }

    // ── The idle policy ──────────────────────────────────────────────────────────────────────────

    /**
     * The unit-level half of the idle policy, with a fake clock so the budget can be exhausted without
     * spending it. The socket-level half — a real server that goes quiet for 35 s — is
     * `IdleStreamTest`.
     */
    @Test
    fun anIdleTickIsAbsorbedUntilTheBudgetIsSpentAndThenNamed() {
        var now = 0L
        val input = TimingOutInputStream(bytes = "{\"a\":1}\n".toByteArray(), timeoutsBeforeData = 3) { now += 10_000 }

        // Budget 120 s, three 10 s idle ticks: absorbed, and the op arrives.
        val reader = BoundedOpLineReader(input, OpStreamBounds(idleBudgetMillis = 120_000L), "/ops") { now }
        assertEquals("""{"a":1}""", reader.next())

        // Same stream, budget 25 s: the third tick is past it, and the refusal names what ran out.
        now = 0L
        val stingy =
            BoundedOpLineReader(
                TimingOutInputStream("{\"a\":1}\n".toByteArray(), 3) { now += 10_000 },
                OpStreamBounds(idleBudgetMillis = 25_000L),
                "/ops",
            ) { now }
        val thrown = assertFailsWith<TransportException> { stingy.next() }
        assertEquals(TransportFailure.IDLE_BUDGET_EXCEEDED, thrown.failure)
        assertContains(thrown.message.orEmpty(), "idleBudgetMillis")
    }

    // ── The scheme gate ──────────────────────────────────────────────────────────────────────────

    @Test
    fun plainHttpIsRefusedWithATypedErrorUnlessOptedIn() {
        val thrown =
            assertFailsWith<TransportException> {
                HttpUrlTransport.requireAllowedScheme(URI("http://example.test/ops"), allowInsecure = false)
            }
        // TYPED, not a silent downgrade and not a silent upgrade to https — the library does not get
        // to decide what the deployment meant.
        assertEquals(TransportFailure.INSECURE_SCHEME, thrown.failure)
        assertContains(thrown.message.orEmpty(), "allowInsecure")

        // The opt-in is honoured, deliberately and explicitly.
        HttpUrlTransport.requireAllowedScheme(URI("http://example.test/ops"), allowInsecure = true)
    }

    @Test
    fun httpsIsAlwaysAllowedAndLoopbackIsExemptWithoutTheFlag() {
        HttpUrlTransport.requireAllowedScheme(URI("https://example.test/ops"), allowInsecure = false)

        // A development fixture server on the same machine has no intermediary to fear. Requiring a
        // certificate for it would push every developer to pass allowInsecure permanently — which is
        // how an opt-in becomes a default. (The driver gate's own fixture server is one of these.)
        for (base in listOf("http://localhost:8080/ops", "http://127.0.0.1:8080/ops", "http://[::1]:8080/ops")) {
            HttpUrlTransport.requireAllowedScheme(URI(base), allowInsecure = false)
        }

        // A host that merely LOOKS loopback is not: the exemption is on the resolved host, never on a
        // substring of it.
        assertFailsWith<TransportException> {
            HttpUrlTransport.requireAllowedScheme(URI("http://localhost.example.test/ops"), allowInsecure = false)
        }
    }

    @Test
    fun theTransportRefusesAnInsecureBaseBeforeItOpensAConnection() {
        val transport = HttpUrlTransport("http://example.invalid")
        val thrown = assertFailsWith<TransportException> { transport.fetchInitialTree() }
        assertEquals(TransportFailure.INSECURE_SCHEME, thrown.failure)
        // `example.invalid` never resolves, so a refusal that reached DNS would have surfaced as an
        // ordinary connection failure. Getting the scheme class back proves the gate ran first.
        assertTrue(thrown.cause == null, "the scheme gate refuses before any I/O is attempted")
    }
}

/**
 * An input stream that raises `SocketTimeoutException` `timeoutsBeforeData` times before delivering
 * its bytes — the shape a quiet-but-healthy socket presents.
 */
private class TimingOutInputStream(
    private val bytes: ByteArray,
    private var timeoutsBeforeData: Int,
    private val onTimeout: () -> Unit,
) : java.io.InputStream() {
    private var index = 0

    override fun read(): Int {
        if (timeoutsBeforeData > 0) {
            timeoutsBeforeData--
            onTimeout()
            throw java.net.SocketTimeoutException("Read timed out")
        }
        return if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
    }
}
