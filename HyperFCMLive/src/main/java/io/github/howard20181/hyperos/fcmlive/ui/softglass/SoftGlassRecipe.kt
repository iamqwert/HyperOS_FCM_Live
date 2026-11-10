package io.github.howard20181.hyperos.fcmlive.ui.softglass

// Migrated from Deadliner 210e4a2d4c6bf7df291b6c6852d39366bc1fee17.
// Integration changes: package and MIUIX semantic UI bindings only.
//
// The source's material catalogue (bottom sheet, dialog, quick-add, tablet
// sidebar) is gone: this app composes one glass object — the floating bottom
// bar — and every recipe nothing called was a set of numbers that could only
// drift away from the one that is used. What is left is that recipe and the
// tuning record behind it, plus the interaction the bar's press drives.

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class GlassBlurSpec(
    val radiusMultiplier: Float = 1f,
    /** The ceiling the scaled radius is clamped to on the shader-backed path. */
    val maxRadius: Float = 150f,
    val noiseMultiplier: Float = 1f,
    val tintAlphaMultiplier: Float = 1f,
)

@Immutable
data class GlassRefractionSpec(
    val enabled: Boolean = true,
    val height: Dp = 18.dp,
    val amount: Dp = 18.dp,
    val depthEffect: Float = 0.60f,
    val chromaticAberration: Dp = 1.dp,
)

@Immutable
data class GlassEdgeOpticsSpec(
    val highlightAlpha: Float = 0.95f,
    val highlightGray: Float = 1f,
    val darkHighlightMultiplier: Float = 0.20f,
    val fallbackWidth: Dp = 0.5.dp,
    val fallbackLightAlpha: Float = 0.46f,
    val fallbackDarkAlpha: Float = 0.08f,
)

@Immutable
data class GlassLayeringSpec(
    val postRefractionBlurEnabled: Boolean = true,
    val postRefractionBlurRadius: Dp = 0.5.dp,
)

/**
 * One complete glass material. There is no geometry field: every surface this
 * app draws is a capsule, so the shader derives its corner radius from the
 * rendered height.
 */
@Immutable
data class GlassMaterialRecipe(
    val blur: GlassBlurSpec = GlassBlurSpec(),
    val refraction: GlassRefractionSpec = GlassRefractionSpec(),
    val edgeOptics: GlassEdgeOpticsSpec = GlassEdgeOpticsSpec(),
    val layering: GlassLayeringSpec = GlassLayeringSpec(),
)

/** What a finger is doing to the material right now: optics scaling only. */
@Immutable
data class GlassMaterialInteraction(
    val refractionScale: Float = 1f,
    val highlightScale: Float = 1f,
)

object SoftGlassRecipes {
    val FloatingNavigation = GlassMaterialRecipe(
        blur = GlassBlurSpec(
            radiusMultiplier = 0.217f,
            tintAlphaMultiplier = 0.90f,
        ),
    )

    /** The floating bar's material with the user's own tuning folded in. */
    fun floatingNavigation(
        fineTuning: SoftGlassFineTuning,
    ): GlassMaterialRecipe {
        val tuning = fineTuning.normalized()
        return FloatingNavigation.copy(
            blur = FloatingNavigation.blur.copy(
                radiusMultiplier = tuning.glassBlurRadiusMultiplier,
                noiseMultiplier = tuning.glassNoiseMultiplier,
                tintAlphaMultiplier = tuning.glassTintAlphaMultiplier,
            ),
            refraction = FloatingNavigation.refraction.copy(
                enabled = tuning.refractionEnabled,
                height = tuning.refractionHeightDp.dp,
                amount = tuning.refractionAmountDp.dp,
                depthEffect = tuning.refractionDepthEffect,
                chromaticAberration = tuning.chromaticAberrationDp.dp,
            ),
            edgeOptics = FloatingNavigation.edgeOptics.copy(
                highlightAlpha = tuning.edgeHighlightAlpha,
                highlightGray = tuning.edgeHighlightGray,
                darkHighlightMultiplier = tuning.darkHighlightMultiplier,
                fallbackWidth = tuning.fallbackEdgeWidthDp.dp,
                fallbackLightAlpha = tuning.fallbackLightAlpha,
                fallbackDarkAlpha = tuning.fallbackDarkAlpha,
            ),
            layering = FloatingNavigation.layering.copy(
                postRefractionBlurEnabled = tuning.postRefractionBlurEnabled,
                postRefractionBlurRadius = tuning.postRefractionBlurRadiusDp.dp,
            ),
        )
    }
}

fun SoftGlassFineTuning.softGlassTintColor(isDark: Boolean): Color {
    val tuning = normalized()
    val gray = if (isDark) tuning.glassTintDarkGray else tuning.glassTintLightGray
    return Color(red = gray, green = gray, blue = gray, alpha = 1f)
}
