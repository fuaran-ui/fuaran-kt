// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/*
 * The native typed tree — the Kotlin surface of the Fuaran UI wire vocabulary, over
 * the Rust reference core.
 *
 * Every closed wire DU is a `sealed` hierarchy so a per-kind `when` is exhaustive at
 * compile time: a new [NodeKind] (or [Binding] / [Action] / [Shape] / ...) that lands
 * without its dispatch arm is a build error, not a silent runtime fallback. This is a
 * **render projection** — the model is decode-oriented (populated by [decodeNode] from
 * the tree JSON a session hands back); it carries no canonical encoder, and
 * closure-typed and host-opaque slots are intentionally dropped or held as raw
 * [JsonValue] rather than reconstructed.
 */

// --------------------------------------------------------------------------- //
// The hand-written remainder
// --------------------------------------------------------------------------- //
//
// The model itself is GENERATED from the corpus's idl.json into Model.generated.kt (Phase 2299).
// What stays here are the two types whose public shape is not a function of their IDL type, each
// recorded as a HandWritten entry in the generator's dialect (fuaran-ui/src/test/kotlin/fuaran/ui/idl/
// Dialect.kt) and pinned by a generated exhaustive check, so drift on either side is still a build
// or generation error.

/**
 * Phase 1535 — the two spellings of a case's condition. They interleave freely in one ordered
 * `cases` array, and first-match-wins runs over the array in AUTHORED order: a host evaluates case
 * *n* fully before considering case *n+1*, and must not batch all the matches ahead of all the
 * predicates.
 */
sealed interface SwitchCondition

/** Compares the switch's resolved selector against a literal string. */
data class MatchCondition(val value: String) : SwitchCondition

/**
 * Evaluates a `Binding<bool>` and takes the case on a RESOLVED `true` only — a resolved `false`, an
 * unresolved binding and an errored one all fall through. There is no truthiness rule: `0`, `""`
 * and `"false"` are refused by the coercion rather than read as `false`.
 *
 * It consults no selector at all, so a switch whose cases are ALL predicates needs no `on` and has
 * no state key for anything to write.
 */
data class WhenCondition(val binding: Binding) : SwitchCondition

/**
 * EXACTLY ONE of `match` and `when` is present (3.6) — both together and neither at all are decode
 * errors, the same shape and the same reasoning as `SetState`'s `value` / `valueFrom` pair.
 *
 * A case naming no condition is not a case that never matches; it is a document whose author meant
 * something the wire cannot say, and a host that silently skipped it would render the `default` and
 * report nothing. A precedence rule for "both" would have to be specified, agreed on every host and
 * remembered by every author, for a document nobody meant to write.
 */
data class SwitchCase(val condition: SwitchCondition, val child: Node)

sealed interface FragmentArg

data class ScalarArg(val scalar: Scalar) : FragmentArg

data class SlotArg(val tree: Node) : FragmentArg
