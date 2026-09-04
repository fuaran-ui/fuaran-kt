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

/**
 * `Image.fit` — how the decoded pixels fill the box the layout gives the element
 * (WIRE_FORMAT.md 3.6.2). Absent means [Natural].
 */
enum class ImageFit { Natural, Cover, Contain }

/**
 * `Image.aspectRatio` — the box the element reserves BEFORE the image arrives (3.6.2). Absent
 * means [Natural].
 *
 * These are TOKENS, never CSS values: the slot names one of four ratios and never carries a number,
 * a pair, or a stylesheet spelling (`"16 / 9"`, `"16:9"`, `1.7778`). Admitting an arbitrary ratio
 * would put an author-supplied value in a style attribute, which is the free-form escape this format
 * does not have — so `"16/9"` is `UNKNOWN_DU_CASE`, not a second spelling of [SixteenNine].
 *
 * It is a LAYOUT reservation, not a crop: what happens to pixels that do not match the box is
 * [ImageFit]'s statement, and a host derives neither from the other.
 */
enum class ImageAspect { Natural, Square, FourThree, ThreeTwo, SixteenNine }

/**
 * `Image.loading` — whether the client fetches during initial load or defers (3.6.2). Absent means
 * [Eager], and that default is deliberate rather than the "unoptimised" value: deferring an
 * above-the-fold image delays the largest contentful paint, and only the author knows where the
 * image sits. A host MUST NOT infer laziness from position, viewport, or anything else the tree
 * does not say.
 */
enum class ImageLoading { Eager, Lazy }

/**
 * A `TrackEntry.kind` — which timed-text channel a media track carries (WIRE_FORMAT.md 3.6.6).
 * A BARE enum (3.5), so an unrecognised token reports at the entry's own `kind` path with no
 * `$type` suffix.
 *
 * **`Metadata` is absent by design and is not a spelling to guess at.** Its cues are rendered by
 * no user agent and read only by script, so a declarative document naming it would state an intent
 * no conformant host could honour without leaving the vocabulary. The set is closed at four; a
 * fifth is an addition to the format, which is exactly what an `UNKNOWN_DU_CASE` here says.
 */
enum class TrackKind { Subtitles, Captions, Descriptions, Chapters }

/**
 * An `Embed.permissions` element — one sandbox relaxation a document asks for (3.6.8). A BARE enum,
 * so an unrecognised token reports at `$.kind.permissions[i]` with no `$type` suffix.
 *
 * **The empty list is TOTAL DENIAL, and that polarity is the design**: the wire-cheapest document
 * is the most locked-down one, so the default a careless emitter produces is the safe one.
 *
 * **Two relaxations are excluded rather than defaulted off, and are not reserved either** —
 * top-level navigation (the drive-by redirect) and downloads (a file-save prompt in a third
 * party's hands). Both are names a later phase must NOT take. Popups, modals, pointer lock,
 * presentation and orientation lock have no recorded demand and ARE reserved, which is the whole
 * reason the slot is an enum rather than a record of booleans: a fifth case is a bare-string
 * addition rather than a type replacement.
 *
 * A decoder MUST NOT silently drop an unrecognised token: that would turn a document asking for
 * something this vocabulary has no name for into a document asking for LESS, which reads as
 * success. [enumOf]'s refusal is what keeps that honest.
 */
enum class EmbedPermission { AllowScripts, AllowSameOrigin, AllowForms, AllowFullscreen }

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

/**
 * `Metric.trendPolarity` — which way the measured quantity IMPROVES (WIRE_FORMAT.md 3.6.1).
 * Distinct from `tone`, which says how the reading STANDS: a host derives neither from the other,
 * and nothing ever writes back to `tone`.
 *
 * **`Neutral` is reserved by the specification and is deliberately NOT a case here.** The case set
 * IS the accepted wire set, so omitting it means `"Neutral"` fails `UNKNOWN_DU_CASE` naming the two
 * legal spellings, rather than this surface quietly deciding a question the reservation holds open
 * — and no `when` carries a dead arm waiting for a case the wire will not produce. That is the same
 * modelling choice the Rust reference core made for the same slot, and it is why the wire slot is
 * an enum rather than an `inverted` bool: admitting the third case later is one added constant plus
 * an exhaustiveness ERROR at every site that must then decide what it means.
 *
 * No alias arm is registered, and that omission is deliberate: the obvious candidates are precisely
 * the spellings that must not be accepted — `Neutral` would pre-empt the reservation, and
 * `Inverted` / `Descending` would reinstate the boolean spelling 3.6.1 refuses.
 */
enum class TrendPolarity { HigherIsBetter, LowerIsBetter }

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
