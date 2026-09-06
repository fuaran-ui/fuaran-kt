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
            // The FIRST projection has no last-good tree to fall back to, so a failure here is
            // terminal rather than survivable — the honest classification, and the one that keeps
            // `lastTree` below from ever being asked for a tree that does not exist.
            var last: DriverState =
                try {
                    project(s)
                } catch (t: Throwable) {
                    if (asProjectionFailure(t) == null) throw t
                    val fatal = Fatal(t)
                    onState(fatal)
                    return@use fatal
                }
            onState(last)
            for (op in transport.openOpStream()) {
                last =
                    try {
                        s.applyOp(op)
                        project(s)
                    } catch (t: Throwable) {
                        // Survive it: keep the last-good tree, surface the typed error.
                        //
                        // This caught `FuaranException` alone, which is only HALF the set the loop
                        // can meet: the Rust core accepts wire vocabulary this decode-only
                        // projection does not model, so a streamed op that lands such a node makes
                        // `project` throw a decode failure — and that escaped the loop entirely,
                        // ending the driver on an op the session itself accepted. `asProjectionFailure`
                        // names the survivable set in one place, shared with the Compose host, so the
                        // two cannot drift apart. Anything outside it still propagates.
                        val failure = asProjectionFailure(t) ?: throw t
                        Rejected(failure, lastTree(last))
                    }
                onState(last)
            }
            last
        }
    }

    /** POST an interaction event (a control dispatch, a form submit) back to the server. */
    fun postEvent(eventJson: String): String = transport.postEvent(eventJson)

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
