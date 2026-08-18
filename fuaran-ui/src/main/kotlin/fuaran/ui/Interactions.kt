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

    /**
     * The current tree as a **resolved projection** — [treeJson] with every scalar-slot
     * `Binding.Transform` folded to the value it evaluates to (Phase 650). The *render* path
     * decodes this, so a decode-only surface renders resolved compute values. Defaults to
     * [treeJson] for a conformer with no evaluator (an in-memory fake / a Transform-free tree);
     * the live [FuaranSession] overrides it with the core's resolved projection. A Kotlin
     * interface default is overridden by the class member unambiguously, so no evaluator leaks
     * into the pure surface.
     */
    fun projectResolved(): String = treeJson()

    /** Apply a canonical `TreeOp` JSON; throws [FuaranException] on a validator reject. */
    fun applyOp(opJson: String)

    /** Write a reactive `$state.<key>` slot from a JSON value string. */
    fun setState(key: String, valueJson: String)

    /** Write a `$filters.<name>` slot from a JSON value string. */
    fun setFilter(key: String, valueJson: String)

    /** Seed a `$queries.<name>` result slot from a JSON value string. */
    fun setQuery(key: String, valueJson: String)

    /**
     * The **resolved rows** of one row-bearing node (`DataGrid` / `Chart` / `Map` /
     * `Sparkline`), evaluated against the session's live sources.
     *
     * The out-of-band companion to [projectResolved], and it exists because that projection
     * cannot carry this: a row-context `Transform` resolves to a *collection*, and the wire's
     * `Static` slot erases a collection to `"<opaque>"` (§2 rule 11). So resolved rows cannot
     * ride the tree at all, and a decode-only surface renders every data-bound grid empty
     * however completely it decodes. This is the hand-off that fixes that — the core
     * evaluates, this surface renders.
     *
     * Defaults to [ResolvedRows.NotResolved] for a conformer with no evaluator (an in-memory
     * fake): the honest answer, and the safe one, since it renders as a loading surface rather
     * than asserting an emptiness the fake never established. Safe as an interface default for
     * the same reason [projectResolved] is one — a Kotlin class member overrides it
     * unambiguously, so the live [FuaranSession] always wins.
     */
    fun resolvedRows(nodeId: String): ResolvedRows = ResolvedRows.NotResolved
}

/**
 * The outcome of a [TreeSession.resolvedRows] request.
 *
 * Three cases, not two, and the distinction is load-bearing at the render boundary: a source
 * that has not resolved (still loading, or a `Transform` that errored) must render differently
 * from one that genuinely resolved to nothing. Collapsing them shows "no data" for "not yet".
 */
sealed interface ResolvedRows {
    /** The source resolved. Possibly to zero rows — that is an **empty state**. */
    data class Rows(val rows: List<JsonValue>) : ResolvedRows

    /** The source did not resolve. Render a **loading** surface, never an empty table. */
    data object NotResolved : ResolvedRows

    /**
     * No node carries that id, or its kind has no row source at all — a caller mistake rather
     * than a data condition.
     */
    data object NoRowSource : ResolvedRows
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
            // A `value` SetState is wire-complete: the payload is right there, so the session
            // applies it. A `valueFrom` SetState is NOT - resolving the binding needs the render
            // context (a `Selection` reads the clicked row of a named grid), which a pure session
            // does not carry. Routing it to the host is the honest answer; applying a placeholder
            // would write a wrong value under a right-looking key.
            is SetStateAction ->
                action.value?.let { session.setState(action.key, it.encode()) } ?: host.add(action)
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
