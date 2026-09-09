// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.driver

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The transport seam for the server-driven driver (Phase 545; bounded reading, an idle-stream policy,
 * a scheme gate and the reply channel, Phase 1541). Four primitives are all the SDUI loop needs:
 *
 *  - [fetchInitialTree] — GET the initial canonical `Node` JSON the session is seeded with.
 *  - [openOpStream] — a lazy sequence of canonical `TreeOp` JSON strings (newline-delimited on the
 *    wire); the driver applies each against the session in order, as it arrives.
 *  - [postEvent] — POST an interaction event back to the server, returning its response body.
 *  - [postEventOps] — the same POST read as the server's REPLY OPS, so an event's consequences reach
 *    the screen through the same apply-then-project path the stream uses.
 *
 * The seam is transport-agnostic on purpose: [HttpUrlTransport] is a dependency-light reference over
 * the JDK's `HttpURLConnection`, but a consumer can supply an OkHttp / Ktor / WebSocket implementation
 * without touching the driver. Tests supply an in-JVM fixture over `com.sun.net.httpserver`.
 */
interface FuaranTransport {
    /** GET the initial tree as canonical wire `Node` JSON. */
    fun fetchInitialTree(): String

    /** The stream of canonical `TreeOp` JSON strings to apply, in order. May block as data arrives. */
    fun openOpStream(): Sequence<String>

    /** POST an interaction event JSON back to the server; returns the response body. */
    fun postEvent(eventJson: String): String

    /**
     * POST an interaction event and read the reply as the server's REPLY OPS — the ops the event
     * caused, in order.
     *
     * The default is the EMPTY sequence, and that is a decision rather than a stub: a transport whose
     * server answers a POST with an acknowledgement (`{"ok":true}`) has not implemented a reply
     * channel, and a default that read any response body as ops would hand that acknowledgement to the
     * session as a `TreeOp` and turn every successful event into a validator reject. A transport that
     * DOES carry reply ops overrides this; [HttpUrlTransport] does.
     *
     * The POST still happens — the default is "no reply ops", never "no post".
     */
    fun postEventOps(eventJson: String): Sequence<String> {
        postEvent(eventJson)
        return emptySequence()
    }
}

/** The typed classes of transport failure, so a host can branch without parsing a message. */
enum class TransportFailure {
    /** A connection failure, a non-2xx status, a malformed URL. */
    TRANSPORT,

    /** The base URL is not `https` and `allowInsecure` was not passed. */
    INSECURE_SCHEME,

    /** One newline-delimited op exceeded [OpStreamBounds.maxLineBytes]. */
    LINE_CAP_EXCEEDED,

    /** The op stream body exceeded [OpStreamBounds.maxBodyBytes]. */
    BODY_CAP_EXCEEDED,

    /** The stream produced no bytes for longer than [OpStreamBounds.idleBudgetMillis]. */
    IDLE_BUDGET_EXCEEDED,
}

/** A transport-layer failure (non-2xx, connection error, a refused scheme, a breached bound) —
 * distinct from a session-layer reject. */
class TransportException(
    val failure: TransportFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    constructor(message: String) : this(TransportFailure.TRANSPORT, message)
}

/**
 * The explicit bounds an op stream is read under.
 *
 * **Why the stream is bounded at all.** A server-driven client applies whatever the server sends, so
 * the op stream is the one place an SDUI host reads an unbounded amount of attacker-influenced input
 * into memory. The reader here used `BufferedReader.readLine()`, which has no length limit whatever:
 * one line with no newline in it is an `OutOfMemoryError` with no diagnosis, delivered to whichever
 * allocation happened to be next.
 *
 * The values are stated here rather than buried at a call site, because what a host is willing to read
 * is a DEPLOYMENT decision — an embedded client on a metered link and a desktop console do not want
 * the same number — and a limit nobody can find is a limit nobody tunes.
 */
data class OpStreamBounds(
    /** The largest single newline-delimited op this stream will assemble. */
    val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    /** The largest total body this stream will read before refusing. */
    val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    /**
     * How long the stream may produce NO bytes at all before it is given up on.
     *
     * This is the number that makes the socket read timeout survivable — see [BoundedOpLineReader].
     */
    val idleBudgetMillis: Long = DEFAULT_IDLE_BUDGET_MILLIS,
) {
    companion object {
        /** 1 MiB. One `TreeOp` is a small document; a megabyte is already far past any legitimate op. */
        const val DEFAULT_MAX_LINE_BYTES: Int = 1 shl 20

        /** 64 MiB — a whole SESSION's worth of ops, not one op. The cap ends a stream that never ends. */
        const val DEFAULT_MAX_BODY_BYTES: Long = 64L shl 20

        /**
         * Two minutes. A server-driven stream is idle whenever the screen is idle, which is most of
         * the time; this is the point at which silence stops meaning "nothing happened" and starts
         * meaning "nothing is coming".
         */
        const val DEFAULT_IDLE_BUDGET_MILLIS: Long = 120_000L

        val Default = OpStreamBounds()
    }
}

/**
 * Assembles newline-delimited ops from a byte stream under [OpStreamBounds], surviving a socket read
 * timeout.
 *
 * **The idle policy is the half that is easy to get wrong.** An SDUI op stream stays open for as long
 * as the screen does and is SILENT most of that time, so a `readTimeout` firing is the ordinary case,
 * not a failure — and treating it as fatal ended a perfectly healthy session after thirty idle
 * seconds. But a read with no timeout at all is a client that hangs forever against a server that has
 * gone away without closing the socket. So the two concerns are separated: the socket timeout becomes
 * a POLL INTERVAL (a tick on which nothing has arrived, absorbed and retried), and
 * [OpStreamBounds.idleBudgetMillis] is the real limit on silence. A `SocketTimeoutException` is
 * therefore not fatal until the budget is spent, at which point it is reported as
 * [TransportFailure.IDLE_BUDGET_EXCEEDED] — naming what actually ran out.
 *
 * The caps are checked BEFORE the byte is retained, so a hostile line is refused at the limit rather
 * than one allocation past it.
 */
class BoundedOpLineReader(
    private val input: InputStream,
    private val bounds: OpStreamBounds,
    private val label: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val line = ByteArrayOutputStream()
    private var bodyBytes = 0L
    private var idleSince = clock()

    /** The next non-blank op, or `null` at end of stream. */
    fun next(): String? {
        while (true) {
            val b = readByteSurvivingIdleTicks()
            if (b < 0) return takeLine().ifEmpty { null }
            bodyBytes++
            if (bodyBytes > bounds.maxBodyBytes) {
                throw TransportException(
                    TransportFailure.BODY_CAP_EXCEEDED,
                    "$label: op stream body exceeded maxBodyBytes (${bounds.maxBodyBytes} bytes)",
                )
            }
            if (b == '\n'.code) {
                val complete = takeLine()
                if (complete.isNotEmpty()) return complete
                continue
            }
            if (line.size() >= bounds.maxLineBytes) {
                throw TransportException(
                    TransportFailure.LINE_CAP_EXCEEDED,
                    "$label: op line exceeded maxLineBytes (${bounds.maxLineBytes} bytes)",
                )
            }
            line.write(b)
        }
    }

    /** Every op remaining in the stream, lazily. */
    fun asSequence(): Sequence<String> = generateSequence { next() }

    private fun readByteSurvivingIdleTicks(): Int {
        while (true) {
            try {
                val b = input.read()
                idleSince = clock()
                return b
            } catch (e: SocketTimeoutException) {
                val idle = clock() - idleSince
                if (idle >= bounds.idleBudgetMillis) {
                    throw TransportException(
                        TransportFailure.IDLE_BUDGET_EXCEEDED,
                        "$label: no data for $idle ms (idleBudgetMillis=${bounds.idleBudgetMillis})",
                        e,
                    )
                }
                // Otherwise: an idle tick on a healthy stream. Absorb it and read again.
            }
        }
    }

    private fun takeLine(): String {
        var bytes = line.toByteArray()
        line.reset()
        // `\r\n` is a legitimate NDJSON line ending on the wire; the carriage return is FRAMING, not
        // payload, so it is dropped rather than handed to a JSON parser that would refuse it.
        if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
            bytes = bytes.copyOf(bytes.size - 1)
        }
        return bytes.toString(UTF_8).trim()
    }
}

/**
 * The reference [FuaranTransport] over `HttpURLConnection` (JDK stdlib — no third-party HTTP client,
 * per the dependency-light stance). The op stream is **newline-delimited JSON** (NDJSON): each
 * non-blank line of the ops response is one `TreeOp`. Endpoints default to `/tree`, `/ops`, `/events`
 * under [baseUrl] and are individually overridable.
 *
 * @param readTimeoutMs the socket read timeout for the UNARY calls (`/tree`, `/events`), where a
 *   silent server genuinely is a failure.
 * @param streamPollMillis the socket read timeout for the OP STREAM, where a silent server is the
 *   ordinary case. It is a poll interval rather than a limit: see [BoundedOpLineReader]. The real
 *   limit on silence is [OpStreamBounds.idleBudgetMillis].
 * @param allowInsecure permit a non-`https` [baseUrl]. **Off by default**: a server-driven client
 *   applies whatever arrives over this connection to its own tree, so a plaintext transport is not a
 *   weaker version of this feature — it is a different one, in which any intermediary becomes an
 *   author of the UI. A LOOPBACK host (`localhost`, `127.0.0.1`, `::1`) is exempt without the flag,
 *   because a development fixture server on the same machine has no intermediary to fear and requiring
 *   a certificate for it would push every developer to pass the flag permanently — which is how an
 *   opt-in becomes a default. Every other `http://` base is refused with a TYPED
 *   [TransportFailure.INSECURE_SCHEME]; nothing is silently downgraded or silently upgraded.
 */
class HttpUrlTransport(
    private val baseUrl: String,
    private val treePath: String = "/tree",
    private val opsPath: String = "/ops",
    private val eventsPath: String = "/events",
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
    private val bounds: OpStreamBounds = OpStreamBounds.Default,
    private val streamPollMillis: Int = DEFAULT_STREAM_POLL_MILLIS,
    private val allowInsecure: Boolean = false,
) : FuaranTransport {
    override fun fetchInitialTree(): String = get(treePath)

    override fun postEvent(eventJson: String): String = post(eventsPath, eventJson)

    /**
     * The reply ops for an event, read as NDJSON under the same bounds as the stream — an event's
     * consequences are ops like any other, so they are read like any other. An empty (or
     * whitespace-only) reply body means no ops.
     */
    override fun postEventOps(eventJson: String): Sequence<String> =
        splitOps(post(eventsPath, eventJson), bounds, eventsPath).asSequence()

    override fun openOpStream(): Sequence<String> =
        sequence {
            val conn = open(opsPath, "GET", streamPollMillis)
            try {
                conn.connect()
                requireOk(conn, opsPath)
                conn.inputStream.buffered().use { input ->
                    yieldAll(BoundedOpLineReader(input, bounds, opsPath).asSequence())
                }
            } finally {
                conn.disconnect()
            }
        }

    private fun get(path: String): String {
        val conn = open(path, "GET", readTimeoutMs)
        try {
            conn.connect()
            requireOk(conn, path)
            return conn.inputStream.readBytes().toString(UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, body: String): String {
        val conn = open(path, "POST", readTimeoutMs)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray(UTF_8)) }
            requireOk(conn, path)
            return conn.inputStream.readBytes().toString(UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, method: String, readTimeout: Int): HttpURLConnection {
        val uri = URI(baseUrl.trimEnd('/') + path)
        requireAllowedScheme(uri, allowInsecure)
        val conn = uri.toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeout
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun requireOk(conn: HttpURLConnection, path: String) {
        val code = conn.responseCode
        if (code !in 200..299) {
            val detail = conn.errorStream?.readBytes()?.toString(UTF_8).orEmpty()
            throw TransportException("$path returned HTTP $code${if (detail.isBlank()) "" else ": $detail"}")
        }
    }

    companion object {
        /**
         * Five seconds. Short enough that a dead connection is noticed promptly, and harmless when the
         * stream is merely quiet, because an idle tick is absorbed rather than fatal.
         */
        const val DEFAULT_STREAM_POLL_MILLIS: Int = 5_000

        /**
         * The scheme gate. A typed refusal, never a downgrade and never an upgrade: rewriting the
         * caller's `http://` to `https://` would be this library deciding what the deployment meant.
         */
        internal fun requireAllowedScheme(uri: URI, allowInsecure: Boolean) {
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme == "https" || allowInsecure || isLoopback(uri.host)) return
            throw TransportException(
                TransportFailure.INSECURE_SCHEME,
                "refusing a non-https base URL (${scheme.ifEmpty { "no scheme" }}): pass " +
                    "allowInsecure = true to accept it deliberately, or use https. Loopback hosts " +
                    "(localhost, 127.0.0.1, ::1) are exempt.",
            )
        }

        internal fun isLoopback(host: String?): Boolean {
            var h = host?.lowercase().orEmpty()
            if (h.isEmpty()) return false
            // `URI.host` keeps the brackets on an IPv6 literal; normalise so one spelling is tested.
            if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
            return h == "localhost" || h == "127.0.0.1" || h == "::1"
        }

        /**
         * Split an already-buffered body into ops under the same bounds the streaming path applies, so
         * the two forms cannot come to disagree about what is too long.
         */
        internal fun splitOps(body: String, bounds: OpStreamBounds, label: String): List<String> =
            BoundedOpLineReader(body.toByteArray(UTF_8).inputStream(), bounds, label)
                .asSequence()
                .toList()
    }
}

/** Convenience for a host that wants the transport's own failure class from a caught throwable. */
fun asTransportFailure(t: Throwable): TransportException? =
    when (t) {
        is TransportException -> t
        // `SocketTimeoutException` is an `IOException`, so the arm below would catch it; it is named
        // separately because a host reading this needs to see that a timed-out read is a TRANSPORT
        // failure here and not a survivable data condition. The op stream absorbs its own idle ticks
        // (see `BoundedOpLineReader`); one that escapes to here is a socket that genuinely died.
        is SocketTimeoutException -> TransportException(TransportFailure.TRANSPORT, "the connection timed out: ${t.message}", t)
        is IOException -> TransportException(TransportFailure.TRANSPORT, "the connection failed: ${t.message}", t)
        else -> null
    }
