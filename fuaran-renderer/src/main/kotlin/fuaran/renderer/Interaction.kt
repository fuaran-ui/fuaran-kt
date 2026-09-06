// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

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
class FuaranHost(private val session: TreeSession, initialTree: Node) {
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

    /** Write a form-control edit through the `$state.<key>` channel per the wire write-back rules. */
    fun writeBack(stateKey: String, value: JsonValue) = guarded {
        ActionDispatch.writeBack(session, stateKey, value)
        reproject()
    }

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

    companion object {
        /**
         * Seed a host from a live session by reading and projecting its current tree — the
         * fallible half of construction, named and kept OUT of the constructor so no composition
         * can be unwound by it. Throws whatever the projection throws: a [FuaranException] from
         * the session, or a decode failure for a tree the projection does not model.
         *
         * The Swift twin's `start(session:)` is the same factory for the same reason.
         */
        fun start(session: TreeSession): FuaranHost =
            FuaranHost(session, decodeNode(session.projectResolved()))
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
