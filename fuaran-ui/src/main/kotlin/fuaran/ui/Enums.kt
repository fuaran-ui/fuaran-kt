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
