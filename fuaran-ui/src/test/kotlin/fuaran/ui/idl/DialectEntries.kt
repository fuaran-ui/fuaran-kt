// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui.idl

/**
 * The VOCABULARY of the host dialect (Phase 2299) — the kinds of statement [DIALECT] may make about
 * where this surface's public model diverges from what the generator's uniform rules produce.
 *
 * Every entry names its subject in IDL terms and carries a reason. Every entry is ORPHAN-REFUSING
 * and REDUNDANCY-REFUSING at generation: one naming a kind, record, union, case, enum or field the
 * IDL does not have fails the run, and so does one whose override equals what the rules would
 * produce anyway. A declaration that has stopped being true therefore cannot outlive its subject,
 * which is the whole value of an enumeration of divergences: it is complete, or generation fails.
 *
 * There is deliberately NO entry for dropping a wire DATA field (Phase 2331): every field the rules
 * reach is carried, and a type whose constructor order is pinned by [FieldOrder] REFUSES generation
 * naming a field its order does not place, so the next field the IDL gains is carried or fails loudly.
 *
 * Subjects ("owners") are spelled as the IDL spells them: a kind by its tag (`Box`), a record by
 * its name (`TabHeader`), a union case as `Union.Case` (`Binding.Static`), and the node envelope as
 * `Node`. Kotlin names in an entry are the PUBLIC names this surface ships.
 */
sealed interface DialectEntry {
    val reason: String
}

/** The public Kotlin name of an IDL kind, record, union or enum differs from its IDL name. */
data class TypeName(val idl: String, val kotlin: String, override val reason: String) : DialectEntry

/**
 * The case classes of [union] are named by [pattern] (`{case}` stands for the IDL case tag) rather
 * than by the rule's `{case}{Union}`.
 */
data class CaseNames(val union: String, val pattern: String, override val reason: String) : DialectEntry

/** One case class of a union is named [kotlin], whatever the union's pattern says. */
data class CaseName(val union: String, val case: String, val kotlin: String, override val reason: String) : DialectEntry

/** A field's public property name differs from its IDL name. */
data class FieldName(val owner: String, val field: String, val kotlin: String, override val reason: String) : DialectEntry

/** A field's Kotlin BASE type (no `?`) differs from the rule's mapping of its IDL type. */
data class FieldType(val owner: String, val field: String, val kotlin: String, override val reason: String) : DialectEntry

/**
 * A field's nullability and constructor default differ from what its optionality class gives
 * (required: non-null, no default; optional: nullable, `= null`; omit-at-default: non-null, the
 * IDL's declared default). [default] is Kotlin source, or `null` for no default.
 */
data class Optionality(
    val owner: String,
    val field: String,
    val nullable: Boolean,
    val default: String?,
    override val reason: String,
) : DialectEntry

/**
 * A closure slot (which the rules omit — its wire form is at most the `"<closure>"` sentinel) is
 * carried as a `Boolean` PRESENCE flag named [kotlin], `false` when the key is absent.
 */
data class PresenceFlag(val owner: String, val field: String, val kotlin: String, override val reason: String) : DialectEntry

/**
 * The public constructor order of [owner], by Kotlin property name. The rule is IDL declaration
 * order; a data class's positional order and its `componentN` functions are public API, so a type
 * whose shipped order predates the IDL keeps it here.
 */
data class FieldOrder(val owner: String, val order: List<String>, override val reason: String) : DialectEntry

/** An IDL record, union or enum this projection does not model as a type of its own. */
data class TypeNotModelled(val idl: String, override val reason: String) : DialectEntry

/**
 * An IDL record or union whose public Kotlin shape is NOT a function of its IDL type, so it stays
 * hand-written in `Model.kt`. The generator still pins it: [members] maps every IDL case (union)
 * or field (record) onto the Kotlin case class or property that carries it, an IDL member missing
 * from the map fails generation, and a generated exhaustive `when` (union) or property read
 * (record) over the Kotlin side makes drift on that side a compile error.
 */
data class HandWritten(val idl: String, val members: Map<String, String>, override val reason: String) : DialectEntry

/** A kind's category differs from the one the IDL declares for it (after [CategoryName]). */
data class CategoryOf(val kind: String, val category: String, override val reason: String) : DialectEntry

/** The `NodeCategory` constant an IDL category is spelled as. */
data class CategoryName(val idl: String, val kotlin: String, override val reason: String) : DialectEntry

/** The constant order of `NodeCategory` (the rule: first appearance over the IDL's kind rows). */
data class CategoryOrder(val order: List<String>, override val reason: String) : DialectEntry

/** The constant order of an enum (the rule: IDL case order), by Kotlin constant name. */
data class EnumOrder(val enum: String, val order: List<String>, override val reason: String) : DialectEntry

/** A constant this surface's enum carries that the IDL's case set does not have. */
data class ExtraEnumCase(val enum: String, val constant: String, override val reason: String) : DialectEntry

/**
 * An enum whose IDL declares `hostCases` is spelled with the WIRE tokens as its constants and no
 * [fuaran.ui.WireEnum] mapping (the rule: host case names, each carrying its wire token).
 */
data class WireTokenConstants(val enum: String, override val reason: String) : DialectEntry
