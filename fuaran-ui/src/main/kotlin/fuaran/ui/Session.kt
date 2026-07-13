// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

import java.lang.ref.Cleaner
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The confined session wrapper over the Rust reference core (Phase 543).
 *
 * The C-ABI session is **single-owner** (`fuaran.h` threading contract): it, and every
 * call taking it, must stay on one thread for its whole lifetime. [FuaranSession]
 * enforces that by construction — it owns a private single-threaded [ExecutorService]
 * and routes every native call through it, so a session can never be touched
 * concurrently even if the wrapper is shared. `fuaran_last_error` is per-thread, so the
 * failing `new` and its error read run on that same executor thread.
 *
 * Lifetime is leak-safe: [close] frees the handle exactly once (idempotent) and a
 * [Cleaner] backstop reclaims a session that is dropped without a close. Both routes run
 * the free on the confinement executor, honouring single-owner even at reclamation.
 *
 * The native surface is reached through the [FuaranNativeBridge] seam — `fuaran-ui`
 * takes no dependency on the JNI module; the binding module supplies a concrete bridge.
 */
class FuaranSession private constructor(
    private val bridge: FuaranNativeBridge,
    private val executor: ExecutorService,
    private val handle: Long,
) : AutoCloseable {
    @Volatile
    private var closed = false
    private val cleanable: Cleaner.Cleanable = CLEANER.register(this, ReleaseState(bridge, executor, handle))

    /** The current tree, re-encoded to canonical wire JSON — the round-trip exit point. */
    fun treeJson(): String = onExecutor { bridge.sessionTreeJson(handle).toString(UTF_8) }

    /** The current tree rendered to a body-fragment HTML string. */
    fun render(): String = onExecutor { bridge.sessionRender(handle).toString(UTF_8) }

    /**
     * Apply a canonical wire `TreeOp` JSON. On success the session adopts the new tree;
     * on failure the held tree is untouched and a typed [FuaranException] is raised with
     * the canonical code + path.
     */
    fun applyOp(opJson: String) {
        val result = onExecutor { bridge.sessionApplyOp(handle, opJson.toByteArray(UTF_8)).toString(UTF_8) }
        throwIfError(result)
    }

    /** Write a reactive `$state.<key>` slot from a JSON value. Re-read [treeJson] / [render] to observe. */
    fun setState(key: String, valueJson: String) = writeSlot(key, valueJson, bridge::sessionSetState)

    /** Write a `$filters.<name>` slot from a JSON value. */
    fun setFilter(key: String, valueJson: String) = writeSlot(key, valueJson, bridge::sessionSetFilter)

    /** Seed a `$queries.<name>` result slot from a JSON value. */
    fun setQuery(key: String, valueJson: String) = writeSlot(key, valueJson, bridge::sessionSetQuery)

    override fun close() {
        if (closed) return
        closed = true
        // Cleaner guarantees the release action runs at most once, whether via clean() or GC.
        cleanable.clean()
    }

    private fun writeSlot(key: String, valueJson: String, fn: (Long, ByteArray, ByteArray) -> ByteArray) {
        val result = onExecutor { fn(handle, key.toByteArray(UTF_8), valueJson.toByteArray(UTF_8)).toString(UTF_8) }
        throwIfError(result)
    }

    private fun <T> onExecutor(block: () -> T): T {
        check(!closed) { "FuaranSession has been closed" }
        return try {
            executor.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** The Cleaner action — holds only the handle + bridge + executor, never the session. */
    private class ReleaseState(
        private val bridge: FuaranNativeBridge,
        private val executor: ExecutorService,
        private val handle: Long,
    ) : Runnable {
        override fun run() {
            try {
                executor.submit { bridge.sessionFree(handle) }.get()
            } catch (_: Throwable) {
                // Reclamation is best-effort; never let a free failure escape the Cleaner thread.
            } finally {
                executor.shutdown()
            }
        }
    }

    companion object {
        private val CLEANER: Cleaner = Cleaner.create()

        /**
         * Decode a canonical wire `Node` JSON into a new confined session over [bridge].
         * Raises [FuaranException] (with the canonical code + path) when the core rejects
         * the tree.
         */
        fun create(bridge: FuaranNativeBridge, nodeJson: String): FuaranSession {
            val executor =
                Executors.newSingleThreadExecutor { r -> Thread(r, "fuaran-session").apply { isDaemon = true } }
            val handle =
                try {
                    executor.submit(
                        Callable {
                            val h = bridge.sessionNew(nodeJson.toByteArray(UTF_8))
                            if (h == 0L) {
                                // Per-thread last-error: read on the same executor thread that failed `new`.
                                throw FuaranException.fromEnvelope(bridge.lastError().toString(UTF_8))
                            }
                            h
                        },
                    ).get()
                } catch (e: ExecutionException) {
                    executor.shutdownNow()
                    throw e.cause ?: e
                }
            return FuaranSession(bridge, executor, handle)
        }

        private fun throwIfError(resultJson: String) {
            val root = Json.parse(resultJson)
            if (root is JsonObject && root["error"] != null) {
                throw FuaranException.fromEnvelope(resultJson)
            }
        }
    }
}

/**
 * The native seam: the raw byte-array marshalling surface a JNI binding module provides.
 * All text crosses as UTF-8 `ByteArray`; the session handle is an opaque `Long`. Keeping
 * this an interface lets `fuaran-ui` stay free of any JNI / platform dependency — the
 * binding module (`fuaran-core`) supplies the concrete bridge.
 */
interface FuaranNativeBridge {
    /** Decode a node JSON into a new session handle, or `0` on failure (read [lastError]). */
    fun sessionNew(nodeJson: ByteArray): Long

    /** The last `sessionNew` failure envelope on this thread (empty when the last new succeeded). */
    fun lastError(): ByteArray

    fun sessionFree(handle: Long)

    fun sessionRender(handle: Long): ByteArray

    fun sessionTreeJson(handle: Long): ByteArray

    fun sessionApplyOp(handle: Long, opJson: ByteArray): ByteArray

    fun sessionSetState(handle: Long, key: ByteArray, value: ByteArray): ByteArray

    fun sessionSetFilter(handle: Long, key: ByteArray, value: ByteArray): ByteArray

    fun sessionSetQuery(handle: Long, key: ByteArray, value: ByteArray): ByteArray
}

/**
 * A structured session failure surfaced from the C-ABI error envelope
 * (`{"error":{"class","code","message","path"}}`), carrying the canonical code + path so
 * a caller reasons about it the same way the codec hosts do.
 */
class FuaranException(
    val code: String,
    val path: String?,
    val errorClass: String?,
    detail: String,
) : Exception("$code${path?.let { " at $it" } ?: ""}: $detail") {
    companion object {
        /** Parse the `{"error":{...}}` envelope; fall back to a generic message on a surprise shape. */
        fun fromEnvelope(envelopeJson: String): FuaranException {
            val root =
                try {
                    Json.parse(envelopeJson)
                } catch (_: JsonSyntaxException) {
                    return FuaranException("UNKNOWN", null, null, "unparseable error envelope: $envelopeJson")
                }
            val error = (root as? JsonObject)?.get("error") as? JsonObject
                ?: return FuaranException("UNKNOWN", null, null, envelopeJson.ifEmpty { "empty error envelope" })
            fun str(key: String): String? = (error[key] as? JsonString)?.value
            return FuaranException(
                code = str("code") ?: "UNKNOWN",
                path = str("path"),
                errorClass = str("class"),
                detail = str("message") ?: "session error",
            )
        }
    }
}
