// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.AmbientLocale
import fuaran.ui.Binding
import fuaran.ui.BoundText
import fuaran.ui.ComputedBinding
import fuaran.ui.CurrencyValueFormat
import fuaran.ui.CustomValueFormat
import fuaran.ui.DateValueFormat
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
import fuaran.ui.NoValueFormat
import fuaran.ui.NumberValueFormat
import fuaran.ui.PercentValueFormat
import fuaran.ui.QueryBinding
import fuaran.ui.ResolvedRows
import fuaran.ui.SelectionBinding
import fuaran.ui.SignificantDigitsValueFormat
import fuaran.ui.StateBinding
import fuaran.ui.StaticBinding
import fuaran.ui.TextSource
import fuaran.ui.ToneVariant
import fuaran.ui.TransformBinding
import fuaran.ui.ValueFormat

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
    /**
     * Node id -> the resolved rows of that row-bearing node, seeded by the host from
     * [TreeSession.resolvedRows].
     *
     * Rows arrive through this channel rather than out of the tree because they cannot ride
     * the tree: a row-context `Transform` resolves to a collection, which the wire's `Static`
     * slot erases to `"<opaque>"` (§2 rule 11). The core evaluates and the host seeds; the
     * renderer stays free of JNI, exactly as it is for `state`.
     *
     * An **absent** entry is not an empty grid. It means nothing has been seeded for that
     * node, which reads as [ResolvedRows.NotResolved] at the render site: a loading surface.
     * Only an explicit `Rows(emptyList())` asserts emptiness.
     */
    val rows: Map<String, ResolvedRows> = emptyMap(),
) {
    companion object {
        val Empty = BindingContext()
    }

    /**
     * The resolved rows seeded for [nodeId], or [ResolvedRows.NotResolved] when nothing has
     * been seeded — the one place that default is decided, so no render arm has to remember it.
     */
    fun rowsFor(nodeId: String): ResolvedRows = rows[nodeId] ?: ResolvedRows.NotResolved

    /** Resolve a [Binding] to a display string. Exhaustive over the sealed hierarchy — no `else`. */
    fun resolve(binding: Binding): String =
        when (binding) {
            is StaticBinding -> jsonScalar(binding.value)
            is StateBinding ->
                state[binding.key]?.let(::jsonScalar)
                    ?: binding.defaultValue?.let(::jsonScalar)
                    ?: ""
            is QueryBinding -> ""
            // 0.2.0/0.2.9 — the declared defaultValue is yielded until the slot is first written.
            is FilterBinding -> binding.defaultValue?.let(::jsonScalar) ?: ""
            is SelectionBinding -> binding.defaultValue?.let(::jsonScalar) ?: ""
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

// ── Grid-cell lowering (Phase 753) ───────────────────────────────────────────
//
// Pure functions, kept out of the composable file: they carry the load-bearing semantics —
// most of all the tone lookup's unmapped fallback — and a plain unit test exercises them
// without a Compose host. Parity-locked with the sibling native surface's equivalents.

/**
 * Project a row property to its display text. The canonical text of the datum, not a
 * formatted rendering: a tone map's keys are the author's raw values, so keying on a
 * formatted string would not match them.
 *
 * Scalars go through the module's one [jsonScalar] printer rather than a second inline copy —
 * a private reader here is how a cell would come to spell a number differently from the rest
 * of the renderer, at which point an author's map entry silently stops matching. Structural
 * values have no cell text at all (never a joined rendering of an array or object).
 */
fun projectRowFieldString(row: JsonValue, field: String): String {
    val value = (row as? JsonObject)?.get(field) ?: return ""
    return when (value) {
        is JsonString, is JsonNumber, is JsonBool -> jsonScalar(value)
        JsonNull, is JsonArray, is JsonObject -> ""
    }
}

/**
 * Apply a column's [ValueFormat] to a projected value, for the cell kinds that display a
 * formatted datum. Non-numeric text and structural formats fall through unchanged rather than
 * inventing a rendering.
 */
fun formatCellValue(text: String, format: ValueFormat): String {
    val n = text.toDoubleOrNull() ?: return text
    return when (format) {
        NoValueFormat, CustomValueFormat, is DateValueFormat -> text
        is NumberValueFormat -> String.format("%.${format.decimals ?: 0}f", n)
        is CurrencyValueFormat -> "${format.code} " + String.format("%.2f", n)
        is PercentValueFormat -> String.format("%.${format.decimals ?: 0}f%%", n * 100)
        is SignificantDigitsValueFormat -> String.format("%.${format.digits}g", n)
    }
}

/**
 * Lower a [TonedPillCell] for one row: the named field's text IS the pill's label, and its
 * tone is the map's entry for that text, or [defaultTone] for a value the map does not mention.
 *
 * The whole of the declarative pill's semantics in one function, because a per-surface copy of
 * a lookup-with-fallback is exactly how two hosts come to disagree about an *unmapped* value —
 * the case a parity test misses most easily.
 */
fun tonedPillOf(
    row: JsonValue,
    field: String,
    map: Map<String, ToneVariant>,
    defaultTone: ToneVariant,
): Pair<String, ToneVariant> {
    val label = projectRowFieldString(row, field)
    return label to (map[label] ?: defaultTone)
}
