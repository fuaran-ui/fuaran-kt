// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/**
 * The interaction seam (Phase 545) — the minimal live-session surface a driver or a Compose host
 * needs to close the loop, decoupled from the concrete JNI [FuaranSession].
 *
 * `fuaran-ui` (the pure-JVM decoder) stays free of any native dependency: [FuaranSession] implements
 * this seam, the server-driven driver and the Compose host program against it, and a test can supply
 * an in-memory fake — so the interaction / write-back logic is exercised without the Rust core on the
 * classpath. Every write path (`applyOp` / `setState` / `setFilter` / `setQuery`) is authoritative on
 * the *Rust* side; a reject surfaces as a typed [FuaranException]. Nothing here re-encodes a node —
 * the tree is read back as canonical JSON from the session and decoded with [decodeNode].
 */
interface TreeSession : AutoCloseable {
    /** The current tree as canonical wire JSON — the re-projection entry point ([decodeNode] it). */
    fun treeJson(): String

    /** Apply a canonical `TreeOp` JSON; throws [FuaranException] on a validator reject. */
    fun applyOp(opJson: String)

    /** Write a reactive `$state.<key>` slot from a JSON value string. */
    fun setState(key: String, valueJson: String)

    /** Write a `$filters.<name>` slot from a JSON value string. */
    fun setFilter(key: String, valueJson: String)

    /** Seed a `$queries.<name>` result slot from a JSON value string. */
    fun setQuery(key: String, valueJson: String)
}

/**
 * Dispatch a wire [Action] against a [TreeSession]'s state channel — the "control interaction →
 * `applyOp` / `setState`" half of the round-trip. Only the actions carrying a **wire-survivable**
 * state effect ([SetStateAction], [ChainAction] over such actions) touch the session; the host- and
 * closure-bearing actions (`Dispatch`'s opaque msg, `Call` / `Notify` / `Navigate` / `AiTool` /
 * `Invoke` / clipboard / file reads) have no session-side effect here and are reported back to the
 * caller for host routing — never silently executed.
 *
 * A [FuaranException] from the session (a validator reject) propagates to the caller, which is
 * expected to surface it as a typed, rendered error state (the driver's `Rejected`, the host's
 * `lastError`) rather than crash the loop.
 */
object ActionDispatch {
    /** The outcome of dispatching an action: which host-side actions still need routing. */
    data class Outcome(val hostActions: List<Action>)

    fun apply(session: TreeSession, action: Action): Outcome {
        val host = mutableListOf<Action>()
        applyInto(session, action, host)
        return Outcome(host)
    }

    private fun applyInto(session: TreeSession, action: Action, host: MutableList<Action>) {
        when (action) {
            is ChainAction -> action.ops.forEach { applyInto(session, it, host) }
            is SetStateAction -> session.setState(action.key, action.value.encode())
            // Host / closure actions — no wire-survivable session effect; hand back for host routing.
            DispatchAction,
            is CommitLocalAction,
            is CallAction,
            is NotifyAction,
            is NavigateAction,
            is AiToolAction,
            is WriteToClipboardAction,
            is ReadFileBodyAction,
            is InvokeAction,
            -> host.add(action)
        }
    }

    /**
     * A form-field / control write-back: set a `$state.<key>` slot to a scalar the control produced.
     * The value is JSON-encoded here (string/number/bool) so callers pass a raw Kotlin value, not
     * pre-serialised JSON. The session (Rust validator) remains the authority on acceptance.
     */
    fun writeBack(session: TreeSession, stateKey: String, value: JsonValue) {
        session.setState(stateKey, value.encode())
    }
}
