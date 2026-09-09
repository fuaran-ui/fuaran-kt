// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.driver

import fuaran.ui.FuaranException
import fuaran.ui.Node
import fuaran.ui.TreeSession
import fuaran.ui.asProjectionFailure
import fuaran.ui.decodeNode

/**
 * The re-projected state the driver emits after each step. A **typed** surface: the host renders the
 * current [Rendered] tree, shows a [Rejected] validator error inline while keeping the last-good tree,
 * and treats [Fatal] (a transport or seeding failure) as a terminal error screen.
 */
sealed interface DriverState

/** The current tree, freshly re-projected from the session after a successful step. */
data class Rendered(val tree: Node) : DriverState

/**
 * A streamed op did not reach the screen. The driver **survives** it: [error] is the typed failure
 * and [tree] is the retained last-good projection, so the host renders an error state over a
 * still-valid tree rather than crashing the loop.
 *
 * Two causes reach this state and a host is not required to tell them apart. Either the Rust core
 * REJECTED the op (it is the reject authority), or the core accepted it and the resulting tree
 * carries wire vocabulary this decode-only projection does not model, so re-projection refused.
 * Both leave the same situation on screen — the previous tree, plus something wrong with what
 * arrived after it — which is why they share one state rather than being distinguished by a
 * classification no reader could act on. `error.errorClass` says which, for a host that cares.
 */
data class Rejected(val error: FuaranException, val tree: Node) : DriverState

/** A terminal failure — the transport failed or the initial tree could not seed a session. */
data class Fatal(val cause: Throwable) : DriverState

/**
 * The server-driven (SDUI) driver (Phase 545) — the client half of the loop the Go/Rust hosts drive
 * from the server side. It fetches an initial tree over the [FuaranTransport], seeds a [TreeSession]
 * via [sessionFactory], then applies each streamed `TreeOp` against the session and re-projects the
 * tree, emitting a [DriverState] after every step. A validator reject is caught and re-emitted as
 * [Rejected] — the loop continues. No wire-JSON handling happens outside the session boundary: the
 * driver only ever hands raw op JSON to [TreeSession.applyOp] and decodes the JSON the session hands
 * back with [decodeNode].
 *
 * The driver is transport- and session-agnostic (both are seams), so the same loop runs over the live
 * Rust session in production and over an in-JVM fixture + fake session under test.
 */
class ServerDrivenDriver(
    private val transport: FuaranTransport,
    private val sessionFactory: (initialTreeJson: String) -> TreeSession,
) {
    /**
     * The session the loop is currently driving, or `null` outside a [run].
     *
     * It exists so an event can have CONSEQUENCES — see [postEventApplyingReply]. Its lifetime is
     * still [run]'s (`session.use` below closes it), so this field is deliberately not a way to keep
     * a session alive past the loop: a call after [run] returns meets a closed handle, which is a
     * caller lifecycle defect and is surfaced as one.
     */
    @Volatile
    private var live: TreeSession? = null

    @Volatile
    private var lastGood: Node? = null

    /**
     * Run the loop to completion (the op stream is finite in the fixture; a real stream ends when the
     * server closes it). [onState] is invoked once per step. Returns the final [DriverState].
     */
    fun run(onState: (DriverState) -> Unit): DriverState {
        val session =
            try {
                sessionFactory(transport.fetchInitialTree())
            } catch (t: Throwable) {
                val fatal = Fatal(t)
                onState(fatal)
                return fatal
            }

        return session.use { s ->
            try {
                runLoop(s, onState)
            } finally {
                // The loop owns the session's lifetime (`use` closes it on the way out), so the
                // reply channel's view of it ends here too — a `postEventApplyingReply` after the
                // loop must meet "no live loop", not a handle that has been freed underneath it.
                live = null
                lastGood = null
            }
        }
    }

    private fun runLoop(s: TreeSession, onState: (DriverState) -> Unit): DriverState {
        live = s
        // The FIRST projection has no last-good tree to fall back to, so a failure here is
        // terminal rather than survivable — the honest classification, and the one that keeps
        // `lastTree` below from ever being asked for a tree that does not exist.
        val seeded: Rendered =
            try {
                project(s)
            } catch (t: Throwable) {
                if (asProjectionFailure(t) == null) throw t
                val fatal = Fatal(t)
                onState(fatal)
                return fatal
            }
        lastGood = seeded.tree
        var last: DriverState = seeded
        onState(last)
        try {
            for (op in transport.openOpStream()) {
                last =
                    try {
                        s.applyOp(op)
                        project(s).also { lastGood = it.tree }
                    } catch (t: Throwable) {
                        // Survive it: keep the last-good tree, surface the typed error.
                        //
                        // This caught `FuaranException` alone, which is only HALF the set the loop
                        // can meet: the Rust core accepts wire vocabulary this decode-only
                        // projection does not model, so a streamed op that lands such a node makes
                        // `project` throw a decode failure — and that escaped the loop entirely,
                        // ending the driver on an op the session itself accepted. `asProjectionFailure`
                        // names the survivable set in one place, shared with the Compose host, so the
                        // two cannot drift apart. It also covers the reader's own lexer failures
                        // (H-30's `JsonSyntaxException` / `JsonLimitException`), which are data
                        // conditions like any other. Anything outside it still propagates.
                        val failure = asProjectionFailure(t) ?: throw t
                        Rejected(failure, lastTree(last))
                    }
                onState(last)
            }
        } catch (t: Throwable) {
            // A TRANSPORT failure, not a reject — and until Phase 1541 it was neither: nothing
            // caught it, so a dropped connection, a non-2xx `/ops` status or a breached stream
            // bound propagated straight out of `run` and unwound whatever thread the loop was on.
            // The op stream is opened LAZILY inside the sequence above, so even a failure to
            // CONNECT surfaced here rather than at the call to `openOpStream`.
            //
            // It is terminal wherever in the stream it happened, and terminal is the honest
            // classification: a validator reject is survivable because the next op is still
            // coming, whereas a dead transport has no next op — calling it survivable would leave
            // a host waiting forever on a screen that merely looks stale.
            val failure = asTransportFailure(t) ?: throw t
            val fatal = Fatal(failure)
            onState(fatal)
            return fatal
        }
        return last
    }

    /**
     * POST an interaction event (a control dispatch, a form submit) back to the server, returning the
     * raw response body.
     *
     * This does NOT apply the reply — see [postEventApplyingReply] for the loop that does. Kept
     * unchanged so a host that treats the response as its own business (an acknowledgement, a redirect
     * hint) is unaffected.
     */
    fun postEvent(eventJson: String): String = transport.postEvent(eventJson)

    /**
     * POST an interaction event and APPLY the server's reply ops, through the same apply-then-project
     * path the stream uses — so an event's consequences render.
     *
     * Without this an interaction was a one-way message: the server could decide a click had changed
     * the tree and had no way to say so until the next streamed op, which on a request/response server
     * is never. The reply ops are ordinary `TreeOp`s and are treated as such — applied in order, a
     * reject SURVIVED with the last-good tree retained exactly as in [run], and [onState] fired once
     * per applied op.
     *
     * **Call it while [run] is in flight.** The loop owns the session's lifetime, so this reads the
     * session the loop is driving; a host runs [run] on a background thread and posts events from the
     * UI thread, and the session is confined to its own executor so the crossing is safe. Called
     * before the loop has seeded, it returns [Fatal] rather than pretending; called after the loop has
     * finished, the session is closed and the resulting `FuaranSessionClosedException` propagates,
     * because a call on a freed handle is a lifecycle defect and not a data condition.
     */
    fun postEventApplyingReply(eventJson: String, onState: (DriverState) -> Unit): DriverState {
        val s = live
        val seeded = lastGood
        if (s == null || seeded == null) {
            val fatal = Fatal(TransportException("postEventApplyingReply outside a live run() loop"))
            onState(fatal)
            return fatal
        }

        val replies =
            try {
                transport.postEventOps(eventJson).toList()
            } catch (t: Throwable) {
                val fatal = Fatal(asTransportFailure(t) ?: throw t)
                onState(fatal)
                return fatal
            }

        var last: DriverState = Rendered(seeded)
        for (op in replies) {
            last =
                try {
                    s.applyOp(op)
                    project(s).also { lastGood = it.tree }
                } catch (t: Throwable) {
                    val failure = asProjectionFailure(t) ?: throw t
                    Rejected(failure, lastTree(last))
                }
            onState(last)
        }
        return last
    }

    /**
     * Re-project the session's tree for RENDERING — from [TreeSession.projectResolved], never
     * [TreeSession.treeJson].
     *
     * It read `treeJson()`, which is the round-trip EXIT point: every scalar `Binding.Transform`
     * arrives there unevaluated, and a decode-only surface cannot evaluate one — so a metric or a
     * heading driven by a computed value rendered as the empty string on this path, while the
     * interactive host path (which already read the resolved projection) rendered it correctly.
     * Two paths over the same session disagreeing about what the tree says is worse than either
     * one being wrong on its own.
     */
    private fun project(session: TreeSession): Rendered = Rendered(decodeNode(session.projectResolved()))

    private fun lastTree(state: DriverState): Node =
        when (state) {
            is Rendered -> state.tree
            is Rejected -> state.tree
            is Fatal -> error("no tree available before the first successful projection")
        }
}
