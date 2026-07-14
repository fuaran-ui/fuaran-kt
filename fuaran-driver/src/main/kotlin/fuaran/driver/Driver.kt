// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.driver

import fuaran.ui.FuaranException
import fuaran.ui.Node
import fuaran.ui.TreeSession
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
 * A streamed op was **rejected by the validator** (the Rust core is the reject authority). The driver
 * **survives** it: the [error] is the typed reject and [tree] is the retained last-good projection, so
 * the host renders an error state over a still-valid tree rather than crashing the loop.
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
            var last: DriverState = project(s)
            onState(last)
            for (op in transport.openOpStream()) {
                last =
                    try {
                        s.applyOp(op)
                        project(s)
                    } catch (e: FuaranException) {
                        // Survive the reject: keep the last-good tree, surface the typed error.
                        Rejected(e, lastTree(last))
                    }
                onState(last)
            }
            last
        }
    }

    /** POST an interaction event (a control dispatch, a form submit) back to the server. */
    fun postEvent(eventJson: String): String = transport.postEvent(eventJson)

    private fun project(session: TreeSession): Rendered = Rendered(decodeNode(session.treeJson()))

    private fun lastTree(state: DriverState): Node =
        when (state) {
            is Rendered -> state.tree
            is Rejected -> state.tree
            is Fatal -> error("no tree available before the first successful projection")
        }
}
