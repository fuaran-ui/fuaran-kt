// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/**
 * The wire's bare-string enums (WIRE_FORMAT.md 3.5), modelled as Kotlin `enum class`es.
 *
 * A `when` over any of these is exhaustive without an `else`, so adding a variant is a
 * compile-time obligation across every dispatch site — the same "a new case is a build
 * error" property the native Rust `enum` host relies on. Decode goes through
 * [enumOf], which surfaces an unknown string as a typed `UNKNOWN_DU_CASE` rather than a
 * fallback value.
 */

enum class Emphasis { Quiet, Normal, Loud }

enum class ToneVariant { Default, Subdued, Brand, Success, Warning, Critical, Info }

enum class StyleWeight { Compact, Standard, Spacious }

enum class BadgeVariant { Neutral, Brand, Success, Warning, Critical, Info }

enum class ButtonVariant { Primary, Secondary, Tertiary, Destructive }

enum class HeadingVariant { Standard, Eyebrow, Caption, Lead }

enum class ChartKind { Line, Bar, Area, Pie, Scatter, Heatmap }

enum class Orientation { Vertical, Horizontal }

enum class ScrollOrientation { Vertical, Horizontal, Both }

enum class ImageVariant { Default, Avatar, Rounded }

enum class DateStyle { Short, Medium, Long, Full }

enum class RelativeTimeUnit { Second, Minute, Hour, Day, Week, Month, Year }

enum class FileReadEncoding { Text, Base64, DataUrl }

enum class HashStrictness { StrictReplay, AdvisoryWarning, Enforced }

enum class BoxRole { Group, Card, Dashboard, Separator }

enum class MathDisplay { Inline, Block }

enum class DateFieldVariant { Date, Time, DateTime }

enum class MountDirection { OutOnly, InOnly, TwoWay }

enum class HostEffect { Pure, ReadsHost, WritesHost }

enum class Determinism { Deterministic, Clock, Random, Network }

/** Resolve a bare-enum string to its [Enum] case, or raise `UNKNOWN_DU_CASE` at [path]. */
inline fun <reified E : Enum<E>> enumOf(raw: String, path: String): E =
    enumValues<E>().firstOrNull { it.name == raw }
        ?: throw FuaranDecodeException(
            FuaranDecodeException.UNKNOWN_DU_CASE,
            path,
            "unrecognised ${E::class.simpleName} '$raw'; expected one of ${enumValues<E>().joinToString(", ") { it.name }}",
        )

enum class IconSize { Small, Medium, Large }

enum class DurationUnit { Seconds, Minutes, Hours }

enum class DurationStyle { Compact, Clock, Long }

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

/** `staticRows.defaultSort.direction` / a grid's `defaultSort.direction` — a closed pair. */
enum class SortDirection(override val wire: String) : WireEnum { Asc("asc"), Desc("desc") }

/** `Link.protection` — the obfuscation the host applies to a rendered `mailto:` href. */
enum class LinkProtection(override val wire: String) : WireEnum { Email("email") }

/** `FieldRule.format` — the closed named-format set a text field's value is held to. */
enum class TextFormat(override val wire: String) : WireEnum {
    Email("email"),
    Url("url"),
    Tel("tel"),
}

/** `CompareRule.op` — the cross-field comparison operator. */
enum class CompareOp(override val wire: String) : WireEnum {
    Eq("eq"),
    Neq("neq"),
    Lt("lt"),
    Lte("lte"),
    Gt("gt"),
    Gte("gte"),
}

/** Resolve a bare-enum string by its WIRE spelling, or raise `UNKNOWN_DU_CASE` at [path]. */
inline fun <reified E> wireEnumOf(raw: String, path: String): E where E : Enum<E>, E : WireEnum =
    enumValues<E>().firstOrNull { it.wire == raw }
        ?: throw FuaranDecodeException(
            FuaranDecodeException.UNKNOWN_DU_CASE,
            path,
            "unrecognised ${E::class.simpleName} '$raw'; expected one of ${enumValues<E>().joinToString(", ") { it.wire }}",
        )
