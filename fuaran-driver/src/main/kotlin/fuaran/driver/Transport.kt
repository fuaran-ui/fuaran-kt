// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.driver

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The transport seam for the server-driven driver (Phase 545). Three primitives are all the SDUI
 * loop needs:
 *
 *  - [fetchInitialTree] — GET the initial canonical `Node` JSON the session is seeded with.
 *  - [openOpStream] — a lazy sequence of canonical `TreeOp` JSON strings (newline-delimited on the
 *    wire); the driver applies each against the session in order.
 *  - [postEvent] — POST an interaction event back to the server, returning its response body.
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
}

/**
 * The reference [FuaranTransport] over `HttpURLConnection` (JDK stdlib — no third-party HTTP client,
 * per the dependency-light stance). The op stream is **newline-delimited JSON** (NDJSON): each
 * non-blank line of the ops response is one `TreeOp`. Endpoints default to `/tree`, `/ops`, `/events`
 * under [baseUrl] and are individually overridable.
 */
class HttpUrlTransport(
    private val baseUrl: String,
    private val treePath: String = "/tree",
    private val opsPath: String = "/ops",
    private val eventsPath: String = "/events",
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
) : FuaranTransport {
    override fun fetchInitialTree(): String = get(treePath)

    override fun postEvent(eventJson: String): String = post(eventsPath, eventJson)

    override fun openOpStream(): Sequence<String> =
        sequence {
            val conn = open(opsPath, "GET")
            try {
                conn.connect()
                requireOk(conn, opsPath)
                val reader = conn.inputStream.bufferedReader(UTF_8)
                yieldAll(nonBlankLines(reader))
            } finally {
                conn.disconnect()
            }
        }

    private fun get(path: String): String {
        val conn = open(path, "GET")
        try {
            conn.connect()
            requireOk(conn, path)
            return conn.inputStream.readBytes().toString(UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, body: String): String {
        val conn = open(path, "POST")
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

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URI(baseUrl.trimEnd('/') + path).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
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

    private fun nonBlankLines(reader: BufferedReader): Sequence<String> =
        sequence {
            reader.use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isNotBlank()) yield(line)
                }
            }
        }
}

/** A transport-layer failure (non-2xx, connection error) — distinct from a session-layer reject. */
class TransportException(message: String) : Exception(message)
