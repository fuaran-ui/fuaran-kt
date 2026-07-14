// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.AmbientLocale
import fuaran.ui.Binding
import fuaran.ui.BoundText
import fuaran.ui.ComputedBinding
import fuaran.ui.ExplicitLocale
import fuaran.ui.FilterBinding
import fuaran.ui.FormatBinding
import fuaran.ui.I18nBinding
import fuaran.ui.I18nText
import fuaran.ui.InvokeBinding
import fuaran.ui.JsonArray
import fuaran.ui.JsonBool
import fuaran.ui.JsonNull
import fuaran.ui.JsonNumber
import fuaran.ui.JsonObject
import fuaran.ui.JsonString
import fuaran.ui.JsonValue
import fuaran.ui.LiteralText
import fuaran.ui.LocalBinding
import fuaran.ui.QueryBinding
import fuaran.ui.SelectionBinding
import fuaran.ui.StateBinding
import fuaran.ui.StaticBinding
import fuaran.ui.TextSource
import fuaran.ui.TransformBinding

/**
 * The render-side [Binding] resolver — the "BindingContext mirror sufficient for rendering" the
 * phase calls for. It resolves a static-or-state-backed value to a display string; the full state
 * round-trip (write-back, live queries, transforms) is Phase 545. Reactive, closure-bearing, and
 * host-owned sources (`Query` / `Filter` / `Selection` / `Computed` / `Transform` / `Invoke`) have
 * no wire-surviving value here, so they resolve to empty — a deliberate render-only floor, not a
 * silent drop.
 */
class BindingContext(
    /** State-key -> current value, seeding `State`-backed bindings for the static render. */
    val state: Map<String, JsonValue> = emptyMap(),
) {
    companion object {
        val Empty = BindingContext()
    }

    /** Resolve a [Binding] to a display string. Exhaustive over the sealed hierarchy — no `else`. */
    fun resolve(binding: Binding): String =
        when (binding) {
            is StaticBinding -> jsonScalar(binding.value)
            is StateBinding ->
                state[binding.key]?.let(::jsonScalar)
                    ?: binding.defaultValue?.let(::jsonScalar)
                    ?: ""
            is QueryBinding -> ""
            is FilterBinding -> ""
            is SelectionBinding -> ""
            ComputedBinding -> ""
            is I18nBinding -> binding.key
            is LocalBinding -> resolve(binding.initialFrom)
            is FormatBinding -> {
                val locale =
                    when (val l = binding.locale) {
                        AmbientLocale -> ""
                        is ExplicitLocale -> l.tag
                    }
                // Render floor: show the underlying value; number/date formatting is out of scope
                // here (the format skeleton is preserved on the model, applied by the host in 545).
                @Suppress("UNUSED_EXPRESSION") locale
                resolve(binding.source)
            }
            is TransformBinding -> ""
            is InvokeBinding -> ""
        }

    /** Resolve a [TextSource] to a display string. Exhaustive over the sealed hierarchy. */
    fun resolveText(text: TextSource): String =
        when (text) {
            is LiteralText -> text.text
            is BoundText -> resolve(text.binding)
            is I18nText -> text.key
        }

    fun resolveInt(binding: Binding, default: Int): Int = resolve(binding).trim().toIntOrNull() ?: default

    fun resolveFloat(binding: Binding, default: Float): Float = resolve(binding).trim().toFloatOrNull() ?: default

    fun resolveBool(binding: Binding?): Boolean = binding?.let { resolve(it).trim().equals("true", ignoreCase = true) } ?: false
}

/** Flatten a raw [JsonValue] to a human display string (render-only). Exhaustive over `JsonValue`. */
fun jsonScalar(value: JsonValue): String =
    when (value) {
        is JsonString -> value.value
        is JsonNumber -> value.raw
        is JsonBool -> value.value.toString()
        JsonNull -> ""
        is JsonArray -> value.items.joinToString(", ") { jsonScalar(it) }
        is JsonObject -> value.members.entries.joinToString(", ") { "${it.key}: ${jsonScalar(it.value)}" }
    }
