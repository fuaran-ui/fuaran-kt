// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/*
 * The wire's bare-string enums (WIRE_FORMAT.md 3.5), modelled as Kotlin `enum class`es.
 *
 * A `when` over any of these is exhaustive without an `else`, so adding a variant is a
 * compile-time obligation across every dispatch site — the same "a new case is a build
 * error" property the native Rust `enum` host relies on. Decode goes through
 * [enumOf], which surfaces an unknown string as a typed `UNKNOWN_DU_CASE` rather than a
 * fallback value.
 *
 * The enums themselves are GENERATED from the corpus's idl.json into Enums.generated.kt (Phase 2299);
 * this file keeps the two readers and the wire-spelling seam they share.
 */

/** Resolve a bare-enum string to its [Enum] case, or raise `UNKNOWN_DU_CASE` at [path]. */
inline fun <reified E : Enum<E>> enumOf(raw: String, path: String): E =
    enumValues<E>().firstOrNull { it.name == raw }
        ?: throw FuaranDecodeException(
            FuaranDecodeException.UNKNOWN_DU_CASE,
            path,
            "unrecognised ${E::class.simpleName} '$raw'; expected one of ${enumValues<E>().joinToString(", ") { it.name }}",
        )

/**
 * A bare-string enum whose wire spelling is NOT its Kotlin case name.
 *
 * Most of the wire's enums are PascalCase and [enumOf] matches them by constant name. Two are
 * lower-case by specification — `SortDirection` (`asc` / `desc`) and `LinkProtection` (`email`)
 * — so they carry their spelling explicitly rather than forcing a lower-case Kotlin constant.
 * Resolution goes through [wireEnumOf], which raises the same typed `UNKNOWN_DU_CASE`.
 */
interface WireEnum {
    val wire: String
}

/** Resolve a bare-enum string by its WIRE spelling, or raise `UNKNOWN_DU_CASE` at [path]. */
inline fun <reified E> wireEnumOf(raw: String, path: String): E where E : Enum<E>, E : WireEnum =
    enumValues<E>().firstOrNull { it.wire == raw }
        ?: throw FuaranDecodeException(
            FuaranDecodeException.UNKNOWN_DU_CASE,
            path,
            "unrecognised ${E::class.simpleName} '$raw'; expected one of ${enumValues<E>().joinToString(", ") { it.wire }}",
        )
