// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import fuaran.ui.Action
import fuaran.ui.ActionDispatch
import fuaran.ui.Binding
import fuaran.ui.FuaranException
import fuaran.ui.JsonBool
import fuaran.ui.JsonNumber
import fuaran.ui.JsonString
import fuaran.ui.JsonValue
import fuaran.ui.Node
import fuaran.ui.StateBinding
import fuaran.ui.TreeSession
import fuaran.ui.asProjectionFailure
import fuaran.ui.decodeNode
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The interaction round-trip state-holder (Phase 545) — the Kotlin twin of the Swift interaction
 * driver. A [FuaranHost] wraps a live [TreeSession] and exposes the **re-projected tree as Compose
 * state**, so the loop is:
 *
 * ```
 * control interaction → FuaranHost.dispatch / writeBack → TreeSession.applyOp / setState
 *   → session re-encodes tree_json → decodeNode re-projects → `tree` mutableState changes
 *   → Compose recomposes
 * ```
 *
 * **No wire-JSON handling happens outside the session boundary.** The host only ever hands raw op /
 * value JSON to the [TreeSession] and decodes the JSON the session hands back — the Rust core owns
 * canonical encode, apply, mutation, and validation. A validator reject surfaces as a typed
 * [lastError] (the render-side twin of the driver's `Rejected`) while [tree] keeps the last-good
 * projection — the interaction is rejected, the UI is not crashed.
 *
 * Re-projection granularity is deliberately coarse for the floor (re-decode the whole tree per step;
 * "measured before optimising" per the phase). A finer diff is a later optimisation behind the same
 * `tree`-as-state surface.
 */
class FuaranHost(
    private val session: TreeSession,
    initialTree: Node,
    /**
     * Where the session round trip runs. **Single-threaded by default, and that is load-bearing
     * twice over**: it keeps writes in the order they were made (a second edit to one slot cannot
     * overtake the first), and it means the coalescer below only ever has to reason about one
     * in-flight write.
     *
     * Injectable so a test can pass a DIRECT executor and keep its assertions synchronous — the
     * alternative being every existing write-back test learning to poll, which trades a real
     * assertion for a timeout.
     */
    private val worker: Executor = defaultWorker(),
    /**
     * Where the decoded tree is published. Defaults to the Android main looper; a test passes a
     * direct executor.
     */
    private val main: Executor = mainThreadExecutor(),
) {
    /**
     * The current re-projected tree, as Compose state. Reading it in a composable subscribes to it.
     * The render tree is the **resolved projection** (Phase 650), so a scalar `Transform` renders
     * its evaluated value — the core resolves it, the decode-only surface renders what it decodes.
     *
     * The seed is a CONSTRUCTOR ARGUMENT rather than a decode performed here. It used to be
     * `decodeNode(session.projectResolved())` in the initialiser, which made construction itself
     * fallible: the Rust core accepts vocabulary this projection does not model, so a tree carrying
     * one made `FuaranHost(session)` throw — and on the Compose path that is a throw during
     * COMPOSITION, unwinding the main thread with no host yet in existence to record the error on.
     * The decode moved to [start], where a caller can handle it, mirroring the Swift twin's
     * throwing `start(session:)` factory.
     */
    var tree by mutableStateOf(initialTree)
        private set

    /**
     * The last failure the host survived, or `null` when the last write succeeded. Also Compose
     * state.
     *
     * "Failure" is wider than "validator reject" — see [guarded]. A decode gap in the projection
     * lands here too, because from the reader's point of view both are the same event: the tree
     * on screen is the last-good one and something is wrong with what arrived after it.
     */
    var lastError by mutableStateOf<FuaranException?>(null)
        private set

    /**
     * Dispatch a control [Action] through the session's state channel and re-project. Returns the
     * host-side actions (`Navigate` / `Call` / `Notify` / …) that carry no session effect, for the
     * embedding app to route. A validator reject sets [lastError] and leaves [tree] unchanged.
     */
    fun dispatch(action: Action): List<Action> {
        var host = emptyList<Action>()
        guarded {
            host = ActionDispatch.apply(session, action).hostActions
            reproject()
        }
        return host
    }

    /** Apply a raw canonical `TreeOp` JSON (e.g. a structural edit) and re-project. */
    fun applyOp(opJson: String) = guarded {
        session.applyOp(opJson)
        reproject()
    }

    /**
     * True while a write is queued or in flight. Compose state, so a host can show progress and a
     * test can wait on settlement rather than on a sleep.
     */
    var writesPending by mutableStateOf(false)
        private set

    /**
     * The coalescing queue. Its decisions live in `WriteBackQueue.kt`, which carries no Compose and
     * no `android.os` import so they are asserted in the plain-JVM gate rather than only under
     * Robolectric.
     *
     * The decode happens inside `perform`, off the main thread, and not only the session call:
     * `decodeNode` walks the whole tree, so moving the cheap half of the round trip while leaving the
     * expensive half on the main thread would have been a change that measured well and fixed
     * nothing.
     */
    private val writes =
        WriteBackQueue(
            worker = worker,
            perform = { key, value ->
                val outcome =
                    runCatching {
                        ActionDispatch.writeBack(session, key, value)
                        decodeNode(session.projectResolved())
                    }
                main.execute { publish(outcome) }
            },
            onPendingChanged = { p -> main.execute { writesPending = p } },
        )

    /**
     * Write a form-control edit through the `$state.<key>` channel per the wire write-back rules.
     *
     * **It returns immediately; the round trip runs on [worker] and the decoded tree is published
     * back on [main].** It used to do the whole of it inline: the session call, `projectResolved()`
     * and `decodeNode` all ran on whatever thread the control's `onValueChange` was called from,
     * which on Compose is the main thread. So every keystroke in a bound text field paid a session
     * round trip plus a full re-decode of the tree before the next frame could be composed — the
     * Compose buffer had already updated, so the character appeared, and then the UI stuttered. On a
     * live JNI session, whose calls hop to the core's own confining executor and BLOCK waiting for
     * it, that is main-thread work waiting on another thread by construction.
     *
     * **Writes to one key COALESCE, latest wins.** While a write is in flight, a further edit to the
     * same key replaces the queued value rather than adding a second round trip — typing ten
     * characters produces the writes the session can keep up with and one final write carrying what
     * the reader actually typed, instead of ten queued round trips finishing long after they stopped.
     * A superseded value is never sent, which is safe precisely because it was superseded: no reader
     * of that slot can observe a value the author replaced before it was ever written.
     *
     * Coalescing is per KEY and never across keys — two different slots are two different facts, and
     * dropping one because the other was edited later would lose an edit rather than an intermediate.
     * There is no timer: a single edit starts immediately, so this debounces under load and adds no
     * latency when there is none.
     */
    fun writeBack(stateKey: String, value: JsonValue) = writes.submit(stateKey, value)

    /** Convenience for a string-valued control edit. */
    fun writeBack(stateKey: String, text: String) = writeBack(stateKey, JsonString(text))

    /** Convenience for a boolean-valued control edit (checkbox / switch). */
    fun writeBack(stateKey: String, on: Boolean) = writeBack(stateKey, JsonBool(on))

    /**
     * Convenience for a numeric control edit (a slider / ranged number). Writes a JSON **number**,
     * not the string form: a reader binding the same `$state` key expects the datum, and a
     * stringified `"3.0"` would compare and format differently everywhere downstream. The lexeme
     * is `Double.toString`'s — the wire carries numbers as their source text, and a second private
     * printer here is how this position would come to spell a number differently from the codec.
     */
    fun writeBack(stateKey: String, number: Double) = writeBack(stateKey, JsonNumber(number.toString()))

    /**
     * Run one interaction, keeping the host alive whatever it does.
     *
     * It caught `FuaranException` only — the Rust core's validator rejects — which left the OTHER
     * failure the loop can meet uncaught: the core accepts wire vocabulary this decode-only
     * projection does not model, so a write that lands such a node makes [reproject] throw a
     * `FuaranDecodeException`, and that escaped straight onto the Compose main thread. The Swift
     * twin caught every `Error` and kept its last-good tree; this one crashed the app. Same
     * session, same op, two different outcomes depending only on which surface was rendering it.
     *
     * [asProjectionFailure] decides the boundary rather than a `catch (Throwable)` here, so the
     * set is named in one place and both hosts read from it. Anything outside it — a
     * `FuaranSessionClosedException`, an `OutOfMemoryError`, a defect in a caller's own handler —
     * propagates, because a lifecycle mistake is not a data condition and surfacing it as one
     * would hide it behind an error banner forever.
     */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
            lastError = null
        } catch (t: Throwable) {
            lastError = asProjectionFailure(t) ?: throw t
        }
    }

    private fun reproject() {
        // Re-read the RESOLVED projection so a state / filter / selection write that feeds a
        // scalar `Transform` param re-evaluates it (Phase 650).
        tree = decodeNode(session.projectResolved())
    }

    private fun publish(outcome: Result<Node>) {
        outcome.fold(
            onSuccess = {
                tree = it
                lastError = null
            },
            onFailure = { t ->
                // The same survivable set `guarded` uses — named in one place so the synchronous and
                // the off-main paths cannot come to disagree about what a host survives. Anything
                // outside it is a lifecycle defect or a caller's own, and is rethrown on the main
                // thread rather than swallowed into a banner.
                lastError = asProjectionFailure(t) ?: throw t
            },
        )
    }

    companion object {
        /**
         * A daemon single-thread executor. Daemon so a host that is garbage without being closed
         * cannot keep a JVM alive; single so writes stay ordered.
         */
        private fun defaultWorker(): Executor =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "fuaran-writeback").apply { isDaemon = true }
            }

        /**
         * Publish on the Android main looper.
         *
         * Compose state is snapshot-based and safe to write from any thread, so this is not about
         * safety — it is about ORDER: a host reading `tree` and `lastError` in one composition must
         * not see a tree from one write beside an error from another, and posting both to one looper
         * is what makes each publication atomic with respect to composition.
         */
        private fun mainThreadExecutor(): Executor {
            val handler = Handler(Looper.getMainLooper())
            return Executor { r -> handler.post(r) }
        }

        /**
         * Seed a host from a live session by reading and projecting its current tree — the
         * fallible half of construction, named and kept OUT of the constructor so no composition
         * can be unwound by it. Throws whatever the projection throws: a [FuaranException] from
         * the session, or a decode failure for a tree the projection does not model.
         *
         * The Swift twin's `start(session:)` is the same factory for the same reason.
         */
        fun start(
            session: TreeSession,
            worker: Executor = defaultWorker(),
            main: Executor = mainThreadExecutor(),
        ): FuaranHost = FuaranHost(session, decodeNode(session.projectResolved()), worker, main)

        /**
         * An executor that runs its work on the calling thread.
         *
         * Public because a TEST needs it, and hiding it would push every write-back test into
         * polling with a timeout — which turns a definite assertion ("the store received this") into
         * an indefinite one ("the store received this within two seconds"), and makes a genuine
         * regression present as a flake. A host that passes it is choosing the pre-1541 synchronous
         * behaviour deliberately, which is a legitimate thing to want on a surface with no main
         * thread to protect.
         */
        val DirectExecutor: Executor = Executor { it.run() }
    }
}

/**
 * The ambient action sink the interactive renderer dispatches through. `null` in the static render
 * floor (Phase 544 coverage gate, server-side static render) — controls are then inert, exactly the
 * pre-545 behaviour. A [FuaranHost] is provided by [InteractiveFuaranTree]; the interactive control
 * arms consult it and stay no-ops when it is absent.
 */
val LocalActionSink = staticCompositionLocalOf<FuaranHost?> { null }

/**
 * Remember a [FuaranHost] over [session] across recompositions.
 *
 * [initialTree] is supplied by the caller rather than decoded here, and that is the point: a decode
 * inside `remember` runs DURING composition, where a failure has nowhere to go but up through the
 * main thread. Seed it with [FuaranHost.start] (or your own projection) outside composition, where
 * a failing tree is an ordinary error you can show.
 */
@Composable
fun rememberFuaranHost(session: TreeSession, initialTree: Node): FuaranHost =
    remember(session) { FuaranHost(session, initialTree) }

/**
 * Render a live [host]'s tree with interaction wired: the host's [FuaranHost.tree] Compose state is
 * projected through the exhaustive [FuaranNode] spine under a [LocalActionSink] provider, so a control
 * interaction round-trips through the session and recomposes this subtree.
 */
@Composable
fun InteractiveFuaranTree(host: FuaranHost, ctx: BindingContext = BindingContext.Empty) {
    CompositionLocalProvider(LocalActionSink provides host) {
        FuaranNode(host.tree, ctx)
    }
}

/** The `$state.<key>` a binding writes to, or `null` when the binding is not a state slot. */
fun stateKeyOf(binding: Binding?): String? = (binding as? StateBinding)?.key
