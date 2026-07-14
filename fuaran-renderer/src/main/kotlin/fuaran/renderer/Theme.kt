// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fuaran.ui.BadgeVariant
import fuaran.ui.Emphasis
import fuaran.ui.HeadingVariant
import fuaran.ui.SemanticStyle
import fuaran.ui.StyleWeight
import fuaran.ui.ToneVariant

/**
 * The Material tone bridge (Phase 545) — the Kotlin twin of the Swift tone bridge.
 *
 * The wire's class-name / tone vocabulary (the Phase 12-H styling contract: [ToneVariant],
 * [Emphasis], [StyleWeight], [HeadingVariant]) is mapped onto a **Material 3 theme layer** so
 * server-emitted styling *intent* renders with native Android styling and adapts to the platform
 * light/dark scheme with no per-node colour handling.
 *
 * The design is deliberately Material-idiomatic: [FuaranTheme] installs a `MaterialTheme`
 * (light or dark [androidx.compose.material3.ColorScheme]) **and** a scheme-matched
 * [FuaranTonePalette] via [LocalFuaranTones]. Renderers read a tone through the [tone] /
 * [badge] composables; because the palette is provided per-scheme, the same tone token resolves
 * to different, scheme-correct colours in light vs dark — that is the acceptance-criterion "differs
 * visibly and correctly between light and dark".
 *
 * Spacing ([FuaranSpacing]) and emphasis/heading typography are **pure** (scheme-independent) and
 * unit-testable without a composition.
 */

// --------------------------------------------------------------------------- //
// Tone colour tokens
// --------------------------------------------------------------------------- //

/** A resolved tone swatch: a filled container, its readable foreground, and an accent for emphasis. */
data class ToneSwatch(val container: Color, val onContainer: Color, val accent: Color)

/**
 * The full tone palette for one platform scheme. Both a light and a dark instance exist
 * ([LightTones] / [DarkTones]); [FuaranTheme] provides the scheme-appropriate one so a tone token
 * is scheme-aware by construction.
 */
data class FuaranTonePalette(
    val default: ToneSwatch,
    val subdued: ToneSwatch,
    val brand: ToneSwatch,
    val success: ToneSwatch,
    val warning: ToneSwatch,
    val critical: ToneSwatch,
    val info: ToneSwatch,
) {
    fun swatch(tone: ToneVariant): ToneSwatch =
        when (tone) {
            ToneVariant.Default -> default
            ToneVariant.Subdued -> subdued
            ToneVariant.Brand -> brand
            ToneVariant.Success -> success
            ToneVariant.Warning -> warning
            ToneVariant.Critical -> critical
            ToneVariant.Info -> info
        }
}

// Light-scheme tones — higher-luminance containers with dark foregrounds (Material tonal-surface feel).
val LightTones =
    FuaranTonePalette(
        default = ToneSwatch(Color(0xFFF2F2F5), Color(0xFF1B1B1F), Color(0xFF3A3A40)),
        subdued = ToneSwatch(Color(0xFFE6E6EA), Color(0xFF5A5A62), Color(0xFF74747C)),
        brand = ToneSwatch(Color(0xFFDCE3FF), Color(0xFF001A41), Color(0xFF2D5BD0)),
        success = ToneSwatch(Color(0xFFD3F1DA), Color(0xFF04361A), Color(0xFF1E7B45)),
        warning = ToneSwatch(Color(0xFFFDE7C2), Color(0xFF3E2E00), Color(0xFF9A6A00)),
        critical = ToneSwatch(Color(0xFFFDDAD6), Color(0xFF410002), Color(0xFFBA1A1A)),
        info = ToneSwatch(Color(0xFFCFE7F5), Color(0xFF04303F), Color(0xFF0C6E93)),
    )

// Dark-scheme tones — low-luminance containers with light foregrounds (the paired counterpart).
val DarkTones =
    FuaranTonePalette(
        default = ToneSwatch(Color(0xFF2A2A2E), Color(0xFFE4E2E6), Color(0xFFC7C6CB)),
        subdued = ToneSwatch(Color(0xFF35353A), Color(0xFFB6B5BC), Color(0xFF9A9AA1)),
        brand = ToneSwatch(Color(0xFF15346F), Color(0xFFDBE1FF), Color(0xFFAEC6FF)),
        success = ToneSwatch(Color(0xFF14512C), Color(0xFFB9F0C5), Color(0xFF8AD6A2)),
        warning = ToneSwatch(Color(0xFF5C4200), Color(0xFFFCDFA6), Color(0xFFE7B45B)),
        critical = ToneSwatch(Color(0xFF93000A), Color(0xFFFFDAD6), Color(0xFFFFB4AB)),
        info = ToneSwatch(Color(0xFF00495F), Color(0xFFBCE7FA), Color(0xFF7FD0F0)),
    )

/** The ambient tone palette; defaults to the light set so tone reads work outside [FuaranTheme]. */
val LocalFuaranTones = staticCompositionLocalOf { LightTones }

/** Resolve a [ToneVariant] to its scheme-appropriate [ToneSwatch]. */
@Composable
fun tone(tone: ToneVariant): ToneSwatch = LocalFuaranTones.current.swatch(tone)

/** Map the [BadgeVariant] vocabulary onto the tone palette (Neutral → the default tone). */
@Composable
fun badge(variant: BadgeVariant): ToneSwatch =
    tone(
        when (variant) {
            BadgeVariant.Neutral -> ToneVariant.Subdued
            BadgeVariant.Brand -> ToneVariant.Brand
            BadgeVariant.Success -> ToneVariant.Success
            BadgeVariant.Warning -> ToneVariant.Warning
            BadgeVariant.Critical -> ToneVariant.Critical
            BadgeVariant.Info -> ToneVariant.Info
        },
    )

// --------------------------------------------------------------------------- //
// Spacing tokens (pure — scheme-independent)
// --------------------------------------------------------------------------- //

/** Spacing tokens keyed off the [StyleWeight] density vocabulary (Compact / Standard / Spacious). */
object FuaranSpacing {
    /** The inner padding a themed container applies at each density. */
    fun pad(weight: StyleWeight): Dp =
        when (weight) {
            StyleWeight.Compact -> 4.dp
            StyleWeight.Standard -> 8.dp
            StyleWeight.Spacious -> 14.dp
        }

    /** The gap between siblings at each density. */
    fun gap(weight: StyleWeight): Dp =
        when (weight) {
            StyleWeight.Compact -> 2.dp
            StyleWeight.Standard -> 6.dp
            StyleWeight.Spacious -> 12.dp
        }
}

// --------------------------------------------------------------------------- //
// Typography tokens (pure)
// --------------------------------------------------------------------------- //

/** The font weight the [Emphasis] facet maps to. */
fun emphasisWeight(emphasis: Emphasis): FontWeight =
    when (emphasis) {
        Emphasis.Quiet -> FontWeight.Light
        Emphasis.Normal -> FontWeight.Normal
        Emphasis.Loud -> FontWeight.Bold
    }

/** A relative type-scale multiplier the [Emphasis] facet maps to (1.0 = the base ramp). */
fun emphasisScale(emphasis: Emphasis): Float =
    when (emphasis) {
        Emphasis.Quiet -> 0.9f
        Emphasis.Normal -> 1.0f
        Emphasis.Loud -> 1.15f
    }

/** The base heading point size (before [emphasisScale]) for a [HeadingVariant] × level. */
fun headingSizeSp(variant: HeadingVariant, level: Int): Float {
    val base =
        when (level.coerceIn(1, 6)) {
            1 -> 26f
            2 -> 22f
            3 -> 19f
            4 -> 17f
            5 -> 15f
            else -> 13f
        }
    return when (variant) {
        HeadingVariant.Standard -> base
        HeadingVariant.Lead -> base * 1.1f
        HeadingVariant.Eyebrow -> 12f
        HeadingVariant.Caption -> 11f
    }
}

// --------------------------------------------------------------------------- //
// The theme wrapper
// --------------------------------------------------------------------------- //

/**
 * Install the Fuaran Material theme: a Material 3 [androidx.compose.material3.ColorScheme] plus the
 * scheme-matched [FuaranTonePalette]. Follows the platform light/dark preference by default; pass
 * [darkTheme] to force a scheme (the render tests drive both explicitly).
 */
@Composable
fun FuaranTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    val tones = if (darkTheme) DarkTones else LightTones
    CompositionLocalProvider(LocalFuaranTones provides tones) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/**
 * Resolve a node's [SemanticStyle] facet to the tone swatch + emphasis weight the render floor
 * applies. A node with no style facet falls back to the [ToneVariant.Default] tone at
 * [Emphasis.Normal] — the neutral surface.
 */
@Composable
fun resolveStyle(style: SemanticStyle?): ResolvedStyle {
    val tv = style?.tone ?: ToneVariant.Default
    val emphasis = style?.emphasis ?: Emphasis.Normal
    val weight = style?.weight ?: StyleWeight.Standard
    return ResolvedStyle(
        swatch = tone(tv),
        weight = emphasisWeight(emphasis),
        scale = emphasisScale(emphasis),
        pad = FuaranSpacing.pad(weight),
        gap = FuaranSpacing.gap(weight),
    )
}

/** The rendered projection of a [SemanticStyle] facet. */
data class ResolvedStyle(
    val swatch: ToneSwatch,
    val weight: FontWeight,
    val scale: Float,
    val pad: Dp,
    val gap: Dp,
)

/** Convenience: scale a base sp value by a [ResolvedStyle]'s type-scale multiplier. */
fun ResolvedStyle.sizeSp(base: Float) = (base * scale).sp
