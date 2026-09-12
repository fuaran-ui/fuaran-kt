// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

import java.lang.ref.Cleaner
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

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
 * Lifetime is leak-safe AND close-safe: [close] frees the handle exactly once (idempotent) and a
 * [Cleaner] backstop reclaims a session that is dropped without a close. Both routes QUEUE the
 * free on the confinement executor, so it runs behind every call already submitted — an in-flight
 * read completes against a live handle, and a call submitted after the free is refused with
 * [FuaranSessionClosedException] rather than reaching a freed one. Closing from another thread
 * while a call is in flight is therefore safe, which is what the "safe even if the wrapper is
 * shared" claim above means in full.
 *
 * The native surface is reached through the [FuaranNativeBridge] seam — `fuaran-ui`
 * takes no dependency on the JNI module; the binding module supplies a concrete bridge.
 */
class FuaranSession private constructor(
    private val bridge: FuaranNativeBridge,
    private val executor: ExecutorService,
    private val handle: Long,
) : AutoCloseable, TreeSession {
    /**
     * Set by the FREE TASK, on the executor thread, immediately before it hands the handle back to
     * the core — and read by every other submitted task, on that same thread.
     *
     * This flag, and not [closeRequested], is what makes close safe against a concurrent call, and
     * the distinction is the whole fix. The executor is single-threaded and FIFO, so at the moment
     * a task RUNS, the free has either already run (this is set — the handle is gone, refuse) or is
     * queued behind it (this is clear — the handle is live, proceed). A flag set by [close] on the
     * CALLER's thread cannot tell those two apart: it is true in both, so it would either refuse a
     * call that was legitimately in flight or admit one that is about to touch freed memory.
     *
     * Shared with [ReleaseState] as an object of its own rather than reached through the session,
     * because a [Cleaner] action must never hold a reference to the object it is registered for —
     * one would keep the session reachable forever and the backstop would never fire at all.
     */
    private val freed = AtomicBoolean(false)

    /**
     * Set by [close] on the calling thread: a fast, BEST-EFFORT refusal of the ordinary
     * use-after-close mistake, so a caller sees a typed error at their own call site rather than
     * one executor round trip later. Deliberately not the safety mechanism — see [freed].
     */
    @Volatile
    private var closeRequested = false

    private val cleanable: Cleaner.Cleanable =
        CLEANER.register(this, ReleaseState(bridge, executor, handle, freed))

    /** The current tree, re-encoded to canonical wire JSON — the round-trip exit point. */
    override fun treeJson(): String = onExecutor { bridge.sessionTreeJson(handle).toString(UTF_8) }

    /**
     * The current tree as a **resolved projection** (Phase 650): `treeJson` with every
     * scalar-slot `Binding.Transform` folded to the value it evaluates to against the live
     * sources. The decode-only surface cannot evaluate a `Transform` itself, so this is the
     * read the render path decodes — the Rust core resolves compute values.
     */
    override fun projectResolved(): String = onExecutor { bridge.sessionProjectResolved(handle).toString(UTF_8) }

    /** The current tree rendered to a body-fragment HTML string. */
    fun render(): String = onExecutor { bridge.sessionRender(handle).toString(UTF_8) }

    /**
     * Apply a canonical wire `TreeOp` JSON. On success the session adopts the new tree;
     * on failure the held tree is untouched and a typed [FuaranException] is raised with
     * the canonical code + path.
     */
    override fun applyOp(opJson: String) {
        val result = onExecutor { bridge.sessionApplyOp(handle, opJson.toByteArray(UTF_8)).toString(UTF_8) }
        throwIfError(result)
    }

    /**
     * RELOCATE a node already in the tree — the drag-move (Phase 1673). Returns the core's success
     * envelope, `{"ok":true,"op":{..}}`, carrying the op the verb EMITTED.
     *
     * The op rides back because it is the artefact the verb computed: a host that journals, replays
     * or diffs its op-stream needs it and cannot re-derive it from the resulting tree.
     *
     * The node KEEPS ITS ID: the core emits `MoveNode` (plus a `ReorderChildren` when appending
     * does not already give the wanted order), so nothing is minted and nothing is remapped. That
     * is why a caller cannot spell a move as place-then-remove — between those two ops the moved
     * id either does not exist or exists twice.
     *
     * This surface is a DECODE-ONLY projection: it cannot author a `TreeOp`, so without this entry
     * point the only way to move a node from Kotlin was to reimplement the placement algebra here,
     * which is the second implementation the C-ABI exists to prevent.
     *
     * ALL FIVE verbs are surfaced (Phase 1703). Phase 1673 asked whether a decode-only projection
     * should author placements GENERALLY, deliberately left it open, and surfaced only the
     * drag-move. The answer is yes, and the argument is that the question was settled by `move`
     * rather than raised by it: a surface that can relocate a node but not insert one is not a
     * narrower answer to "may this tier author placements" — it is the same answer applied to one
     * fifth of the algebra, with the other four fifths reachable only through the reimplementation
     * this seam exists to prevent.
     *
     * On refusal the held tree is UNTOUCHED and a typed [FuaranException] is raised whose class is
     * `"placement"` (the apply-side refusal this move would have met, PRE-STATED — so a drag UI can
     * grey out an illegal drop without a dry-run apply) or `"request"`.
     *
     * @param source the id of the node to relocate.
     * @param parentId the destination container.
     * @param placement where among the destination's children it lands.
     */
    fun move(source: String, parentId: String, placement: Placement): String =
        placementVerb(
            bridge::sessionMove,
            placementRequest(parentId, placement, listOf("source" to JsonString(source).encode())),
        )

    /**
     * Insert a NEW node among a parent's children (Phase 1703).
     *
     * [childJson] is a canonical wire `Node` DOCUMENT, not a [Node], and that follows from the tier
     * being decode-only: this surface parses the wire form and does not emit it, so there is no
     * `Node` → JSON direction here to offer and inventing one would be a second encoder — the same
     * defect one layer up. The document is spliced verbatim and judged by the CORE's parser, which
     * is the decoder that owns that judgement; a malformed one comes back as a `"request"` error
     * rather than being quietly reshaped here.
     *
     * Raises `ParentNotFound` / `ChildlessKind` when the destination cannot take a child,
     * `UnknownAnchor` when the anchor is not among its post-op children, and `DuplicateId` when an
     * id in the child is already in the tree — a `place` mints and remaps NOTHING, which is exactly
     * what separates it from [paste].
     */
    fun place(childJson: String, parentId: String, placement: Placement): String =
        placementVerb(
            bridge::sessionPlace,
            placementRequest(parentId, placement, listOf("child" to childJson)),
        )

    /**
     * Move a node one or more positions among its OWN siblings (Phase 1703) — the keyboard/handle
     * nudge, which takes no destination because it never leaves its parent.
     *
     * [delta] is a whole number of sibling positions; negative moves earlier. Raises
     * `CannotNudgeRoot` (the root has no siblings) or `NudgeOutOfRange` (the result would fall
     * outside the sibling list) — both refused before any op is emitted, so a held-key repeat stops
     * at the end rather than clamping silently.
     */
    fun nudge(target: String, delta: Int): String =
        placementVerb(
            bridge::sessionNudge,
            jsonObjectOf(listOf("target" to JsonString(target).encode(), "delta" to delta.toString())),
        )

    /**
     * Copy a node ALREADY IN THE TREE and place the copy (Phase 1703).
     *
     * Unlike [move] the copy is a NEW node: every id in it that collides with one already in the
     * tree is remapped, and ids that do not collide are preserved. [idPrefix] selects the
     * deterministic strategy — minted ids are `<prefix>-1`, `-2`, … in traversal order — and
     * omitting it takes the derived strategy (`<oldId>-copy`, then `-copy-2`, …). Pass one when the
     * caller must PREDICT the minted ids; omit it when the copy should read as a copy of something.
     */
    fun duplicate(source: String, parentId: String, placement: Placement, idPrefix: String? = null): String =
        placementVerb(
            bridge::sessionDuplicate,
            placementRequest(parentId, placement, cloneMembers("source" to JsonString(source).encode(), idPrefix)),
        )

    /**
     * Place a subtree lifted from ANOTHER tree (Phase 1703) — the clipboard verb.
     *
     * The same id-remapping contract as [duplicate] (that is the whole difference from [place],
     * which refuses a collision rather than remapping it); what differs is where the subtree came
     * from, so it arrives as a document rather than as an id. See [place] on why a document.
     */
    fun paste(
        subtreeJson: String,
        parentId: String,
        placement: Placement,
        idPrefix: String? = null,
    ): String =
        placementVerb(
            bridge::sessionPaste,
            placementRequest(parentId, placement, cloneMembers("subtree" to subtreeJson, idPrefix)),
        )

    // --- Placement boundary helpers ------------------------------------------------------------

    /**
     * One placement call: marshal the request, read the envelope, raise on a refusal, hand back the
     * core's success envelope `{"ok":true,"op":{..}}`.
     */
    private fun placementVerb(fn: (Long, ByteArray) -> ByteArray, request: String): String {
        val result = onExecutor { fn(handle, request.toByteArray(UTF_8)).toString(UTF_8) }
        throwIfError(result)
        return result
    }

    /**
     * A request object from members that are ALREADY ENCODED JSON.
     *
     * Every string member is encoded through this tier's own writer at its call site
     * (`JsonString(x).encode()`) rather than concatenated: a node id is caller data, and an
     * unescaped quote in one would close the string and come back as a core-side parse error — a
     * Kotlin-side defect wearing a core-side error's clothes.
     *
     * A node DOCUMENT is spliced verbatim instead. That is not a hole in the encoder: re-reading it
     * through this tier's reader would move the judgement about what is a valid wire document from
     * the core — which owns it, and whose limits are the ones that apply — to a reader whose limits
     * are its own. A malformed splice is refused by the core's parser as a `"request"` error.
     */
    private fun jsonObjectOf(members: List<Pair<String, String>>): String =
        members.joinToString(",", "{", "}") { (key, encoded) -> JsonString(key).encode() + ":" + encoded }

    /** The destination members every placement verb but [nudge] carries, plus the verb's own. */
    private fun placementRequest(
        parentId: String,
        placement: Placement,
        extra: List<Pair<String, String>>,
    ): String {
        val members = mutableListOf<Pair<String, String>>()
        members += "parentId" to JsonString(parentId).encode()
        members += "placement" to JsonString(placement.caseName()).encode()
        placement.anchorId()?.let { members += "anchor" to JsonString(it).encode() }
        members += extra
        return jsonObjectOf(members)
    }

    /** The clone verbs' members: what is being cloned, plus an optional fresh-id strategy. */
    private fun cloneMembers(subject: Pair<String, String>, idPrefix: String?): List<Pair<String, String>> =
        if (idPrefix == null) listOf(subject) else listOf(subject, "idPrefix" to JsonString(idPrefix).encode())

    /** Write a reactive `$state.<key>` slot from a JSON value. Re-read [treeJson] / [render] to observe. */
    override fun setState(key: String, valueJson: String) = writeSlot(key, valueJson, bridge::sessionSetState)

    /** Write a `$filters.<name>` slot from a JSON value. */
    override fun setFilter(key: String, valueJson: String) = writeSlot(key, valueJson, bridge::sessionSetFilter)

    /** Seed a `$queries.<name>` result slot from a JSON value. */
    override fun setQuery(key: String, valueJson: String) = writeSlot(key, valueJson, bridge::sessionSetQuery)

    /**
     * The **resolved rows** of one row-bearing node, evaluated core-side — what a decode-only
     * surface cannot get from the tree, because a resolved collection cannot ride a `Static`
     * slot (§2 rule 11). See [TreeSession.resolvedRows].
     *
     * The three outcomes stay distinct all the way to the renderer. An unparseable or empty
     * response degrades to [ResolvedRows.NotResolved] (a loading surface) rather than to zero
     * rows, so a boundary failure can never masquerade as "this grid is empty".
     */
    override fun resolvedRows(nodeId: String): ResolvedRows {
        val json = onExecutor { bridge.sessionResolvedRows(handle, nodeId.toByteArray(UTF_8)).toString(UTF_8) }
        return parseResolvedRows(json)
    }

    /**
     * Free the session's handle. Idempotent, and safe to call while another thread has a call in
     * flight: the free is QUEUED on the confinement executor behind every call already submitted,
     * so an in-flight read completes against a live handle and only a call submitted after it is
     * refused (with [FuaranSessionClosedException]).
     *
     * It BLOCKS until that queued free has run — behind every call ahead of it — so a close from
     * a UI thread waits on whatever native work is in flight. Never call it FROM the confinement
     * executor (a session's own callback), which would wait on itself.
     *
     * The `if (closed) return` guard this replaced was not the idempotence it looked like: two
     * threads could both read `false` and both proceed. [Cleaner.Cleanable.clean] already
     * guarantees the release action runs at most once whichever thread reaches it and whether it
     * arrives by this call or by garbage collection, so the guard added a race and no protection.
     */
    override fun close() {
        closeRequested = true
        cleanable.clean()
    }

    private fun writeSlot(key: String, valueJson: String, fn: (Long, ByteArray, ByteArray) -> ByteArray) {
        val result = onExecutor { fn(handle, key.toByteArray(UTF_8), valueJson.toByteArray(UTF_8)).toString(UTF_8) }
        throwIfError(result)
    }

    /**
     * Run one native call on the confinement executor.
     *
     * The closed check is INSIDE the submitted block, and that placement is the fix for the
     * use-after-free this wrapper used to carry. It was `check(!closed)` here, on the CALLING
     * thread, followed by a submit — so a thread could pass the check, be descheduled, and submit
     * after another thread's [close] had already queued (or run) `sessionFree`, sending
     * `sessionTreeJson` at a handle the core no longer owns. That window is not narrow in the way
     * it looks: on Android the closing thread is usually the main thread tearing a screen down
     * while a background read is mid-flight, which is the common case rather than the exotic one.
     *
     * Reading [freed] from inside the task closes it because the executor is single-threaded and
     * FIFO: the flag is written by the free task on that same thread, so "is the handle still
     * mine" is answered in the free's own order rather than against a clock. See [freed].
     */
    private fun <T> onExecutor(block: () -> T): T {
        // Best-effort, for a caller's own benefit: an unambiguous use-after-close reported at the
        // call site instead of one round trip later. Not the guard — that is inside the task.
        if (closeRequested) throw FuaranSessionClosedException()
        val future =
            try {
                executor.submit(
                    Callable {
                        if (freed.get()) throw FuaranSessionClosedException()
                        block()
                    },
                )
            } catch (_: RejectedExecutionException) {
                // The executor is already shut down — the session was closed between the check
                // above and this submit. A typed refusal, not the raw platform exception.
                throw FuaranSessionClosedException()
            }
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * The Cleaner action — holds the handle, bridge, executor and the shared [freed] flag, never
     * the session itself (a reference to it would keep the session reachable forever and this
     * backstop would never run).
     */
    private class ReleaseState(
        private val bridge: FuaranNativeBridge,
        private val executor: ExecutorService,
        private val handle: Long,
        private val freed: AtomicBoolean,
    ) : Runnable {
        override fun run() {
            try {
                executor
                    .submit {
                        // Set on the EXECUTOR thread, immediately before the free, so every task
                        // that runs after this one observes it and every task queued before it has
                        // already run. That ordering — not a lock, and not a volatile read on the
                        // closing thread — is what makes a concurrent call safe.
                        freed.set(true)
                        bridge.sessionFree(handle)
                    }
                    .get()
            } catch (_: Throwable) {
                // Reclamation is best-effort; never let a free failure escape the Cleaner thread.
                // This action runs at most once (Cleanable's contract) and nothing else shuts the
                // executor down, so a throw here is the native free itself failing — the handle
                // is then leaked deliberately rather than freed twice or reached again: `freed`
                // is already set, so every later call is refused.
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

        /**
         * Parse the resolved-rows envelope: `{"resolved":true,"rows":[…]}`,
         * `{"resolved":false}`, or the `NO_ROW_SOURCE` error envelope.
         *
         * Anything unrecognised — empty bytes, unparseable JSON, `resolved` true with a
         * missing or non-array `rows` — degrades to [ResolvedRows.NotResolved]. Deliberate:
         * the failure then shows as a loading surface, honest about not knowing, where
         * `Rows(emptyList())` would assert an emptiness the core never claimed.
         */
        internal fun parseResolvedRows(json: String): ResolvedRows {
            if (json.isEmpty()) return ResolvedRows.NotResolved
            val root = runCatching { Json.parse(json) }.getOrNull() as? JsonObject ?: return ResolvedRows.NotResolved
            if (root["error"] != null) return ResolvedRows.NoRowSource
            if ((root["resolved"] as? JsonBool)?.value != true) return ResolvedRows.NotResolved
            val rows = root["rows"] as? JsonArray ?: return ResolvedRows.NotResolved
            return ResolvedRows.Rows(rows.items)
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

    /** The current tree as a resolved projection — `tree_json` with scalar Transforms folded (Phase 650). */
    fun sessionProjectResolved(handle: Long): ByteArray

    /** The resolved rows of one row-bearing node, addressed by node id (Phase 752/753). */
    fun sessionResolvedRows(handle: Long, nodeId: ByteArray): ByteArray

    /**
     * The five placement verbs (Phase 833; `move` surfaced here at Phase 1673, the rest at 1703).
     * Each takes ONE canonical-JSON request document and returns `{"ok":true,"op":{..}}` carrying
     * the op it emitted, or an error envelope whose class is `"placement"` or `"request"`; on
     * refusal the held tree is untouched.
     *
     * ```
     * place      {"parentId":..,"placement":"Last"|"First"|"Before"|"After","anchor":..?,"child":{..node..}}
     * move       { ..destination.., "source":.. }
     * paste      { ..destination.., "subtree":{..node..}, "idPrefix":..? }
     * duplicate  { ..destination.., "source":.., "idPrefix":..? }
     * nudge      {"target":..,"delta":<whole number of sibling positions>}
     * ```
     *
     * One shape across all five, which is why they sit together here: a binding writes one
     * marshalling body and five one-line forwards rather than five bodies.
     */
    fun sessionPlace(handle: Long, requestJson: ByteArray): ByteArray

    /** See [sessionPlace]. Relocates a node already in the tree; the node KEEPS ITS ID. */
    fun sessionMove(handle: Long, requestJson: ByteArray): ByteArray

    /** See [sessionPlace]. Moves a node among its own siblings; takes no destination. */
    fun sessionNudge(handle: Long, requestJson: ByteArray): ByteArray

    /** See [sessionPlace]. Copies a node already in the tree, minting ids for the copy. */
    fun sessionDuplicate(handle: Long, requestJson: ByteArray): ByteArray

    /** See [sessionPlace]. Places a subtree from another tree, remapping colliding ids. */
    fun sessionPaste(handle: Long, requestJson: ByteArray): ByteArray

    fun sessionApplyOp(handle: Long, opJson: ByteArray): ByteArray

    fun sessionSetState(handle: Long, key: ByteArray, value: ByteArray): ByteArray

    fun sessionSetFilter(handle: Long, key: ByteArray, value: ByteArray): ByteArray

    fun sessionSetQuery(handle: Long, key: ByteArray, value: ByteArray): ByteArray
}

/**
 * Where a placement puts a node among its new siblings (Phase 833's placement algebra, reached
 * from this surface by [FuaranSession.move]).
 *
 * The anchor is carried by [Before] / [After] and by nothing else, which is deliberate: the core
 * REFUSES an anchor supplied alongside `Last` / `First` rather than silently dropping it — a caller
 * that supplied one has stated an intent that would not be honoured — so a type that cannot express
 * the refused shape is better than one that can and is told off for it.
 */
sealed interface Placement {
    /** Append to the destination's children. */
    data object Last : Placement

    /** Insert as the destination's first child. */
    data object First : Placement

    /** Insert immediately before [anchor], which must be among the destination's post-op children. */
    data class Before(val anchor: String) : Placement

    /** Insert immediately after [anchor], which must be among the destination's post-op children. */
    data class After(val anchor: String) : Placement
}

/** The wire spelling of a placement case — the `"placement"` member of a request document. */
internal fun Placement.caseName(): String =
    when (this) {
        Placement.Last -> "Last"
        Placement.First -> "First"
        is Placement.Before -> "Before"
        is Placement.After -> "After"
    }

/**
 * The anchor a placement carries, or `null` for the two that take none.
 *
 * Named `anchorId` rather than `anchor` on purpose: an extension property called `anchor` would sit
 * beside a member of the same name on two of the cases, and which one a reader thinks is being read
 * inside the `when` should not be a question anybody has to answer.
 */
internal fun Placement.anchorId(): String? =
    when (this) {
        Placement.Last, Placement.First -> null
        is Placement.Before -> anchor
        is Placement.After -> anchor
    }

/**
 * A call was made on a [FuaranSession] whose handle has been (or is about to be) freed.
 *
 * Deliberately **not** a [FuaranException]. That type means "the Rust core rejected this" — a
 * validator refusal a host is meant to survive and show — and the interaction host and the
 * server-driven driver both catch it and carry on. Using it here would report a caller lifecycle
 * defect as an ordinary data reject, which is exactly how a use-after-close would go unnoticed.
 *
 * It extends [IllegalStateException] because that is what the wrapper raised before it was typed
 * (`check(!closed)`), so a caller already handling that keeps working, and a lifecycle mistake
 * remains a lifecycle mistake to anyone who did not care about the distinction.
 */
class FuaranSessionClosedException(
    message: String = "FuaranSession has been closed; its native handle is no longer valid",
) : IllegalStateException(message)

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
                } catch (_: JsonLimitException) {
                    // The reader now raises a second type. This is the ERROR path, so an
                    // escaping exception here would replace a structured failure with an
                    // unstructured one at exactly the moment a caller is already handling
                    // one — catch it rather than let the reader's contract leak.
                    return FuaranException("UNKNOWN", null, null, "over-large error envelope")
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
