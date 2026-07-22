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
import fuaran.ui.JsonString
import fuaran.ui.JsonValue
import fuaran.ui.Node
import fuaran.ui.StateBinding
import fuaran.ui.TreeSession
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
class FuaranHost(private val session: TreeSession) {
    /**
     * The current re-projected tree, as Compose state. Reading it in a composable subscribes to it.
     * The render tree is the **resolved projection** (Phase 650), so a scalar `Transform` renders
     * its evaluated value — the core resolves it, the decode-only surface renders what it decodes.
     */
    var tree by mutableStateOf(decodeNode(session.projectResolved()))
        private set

    /** The last validator reject, or `null` when the last write succeeded. Also Compose state. */
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

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
            lastError = null
        } catch (e: FuaranException) {
            lastError = e
        }
    }

    private fun reproject() {
        // Re-read the RESOLVED projection so a state / filter / selection write that feeds a
        // scalar `Transform` param re-evaluates it (Phase 650).
        tree = decodeNode(session.projectResolved())
    }
}

/**
 * The ambient action sink the interactive renderer dispatches through. `null` in the static render
 * floor (Phase 544 coverage gate, server-side static render) — controls are then inert, exactly the
 * pre-545 behaviour. A [FuaranHost] is provided by [InteractiveFuaranTree]; the interactive control
 * arms consult it and stay no-ops when it is absent.
 */
val LocalActionSink = staticCompositionLocalOf<FuaranHost?> { null }

/** Remember a [FuaranHost] over [session] across recompositions. */
@Composable
fun rememberFuaranHost(session: TreeSession): FuaranHost = remember(session) { FuaranHost(session) }

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
