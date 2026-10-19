package io.github.howard20181.hyperos.fcmlive.ui.softglass

// Migrated from Deadliner 210e4a2d4c6bf7df291b6c6852d39366bc1fee17.
// Integration changes: package and MIUIX semantic UI bindings only.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.BlurDefaults.blurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

@Immutable
data class SoftGlassSpec(
    val enabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val blurRadius: Float = SoftGlassTuning.DefaultBlurRadius,
    val blurRadiusX: Float? = null,
    val blurRadiusY: Float? = null,
    val noiseCoefficient: Float = SoftGlassFineTuning.Default.noiseCoefficient,
    val blurBlendColors: List<BlendColorEntry> = emptyList(),
    val blurBrightness: Float = SoftGlassFineTuning.Default.brightness,
    val blurContrast: Float = SoftGlassFineTuning.Default.contrast,
    val blurSaturation: Float = SoftGlassTuning.saturationForBlurRadius(
        SoftGlassTuning.DefaultBlurRadius,
    ) * SoftGlassFineTuning.Default.saturationMultiplier,
    val blurContentBlendMode: BlendMode = BlendMode.SrcOver,
    val navigationTintAlpha: Float = SoftGlassFineTuning.Default.navigationTintAlpha,
    val topBarTintAlpha: Float = SoftGlassFineTuning.Default.topBarTintAlpha,
    val fineTuning: SoftGlassFineTuning = SoftGlassFineTuning.Default,
) {
    val resolvedBlurRadius: Float
        get() = blurRadius.coerceIn(0f, BlurDefaults.MaxBlurRadius)

    val resolvedBlurRadiusX: Float
        get() = (blurRadiusX ?: blurRadius).coerceIn(0f, BlurDefaults.MaxBlurRadius)

    val resolvedBlurRadiusY: Float
        get() = (blurRadiusY ?: blurRadius).coerceIn(0f, BlurDefaults.MaxBlurRadius)

    val usesIndependentBlurRadii: Boolean
        get() = blurRadiusX != null || blurRadiusY != null

    val textureBlurEnabled: Boolean
        get() = enabled && blurEnabled
}

fun createSoftGlassSpec(
    enabled: Boolean,
    blurRadius: Float,
    fineTuning: SoftGlassFineTuning,
): SoftGlassSpec {
    val tuning = fineTuning.normalized()
    return SoftGlassSpec(
        enabled = enabled,
        blurRadius = SoftGlassTuning.normalizeBlurRadius(blurRadius),
        noiseCoefficient = tuning.noiseCoefficient,
        blurBrightness = tuning.brightness,
        blurContrast = tuning.contrast,
        blurSaturation = SoftGlassTuning.saturationForBlurRadius(blurRadius) *
            tuning.saturationMultiplier,
        navigationTintAlpha = tuning.navigationTintAlpha,
        topBarTintAlpha = tuning.topBarTintAlpha,
        fineTuning = tuning,
    )
}

@Composable
fun SoftGlassSpec.rememberBlurColors(
    additionalBlendColors: List<BlendColorEntry> = emptyList(),
): BlurColors = blurColors(
    blendColors = additionalBlendColors + blurBlendColors,
    brightness = blurBrightness,
    contrast = blurContrast,
    saturation = blurSaturation,
)

fun Modifier.softGlassTextureBlur(
    softGlass: SoftGlassSpec,
    backdrop: Backdrop,
    shape: Shape,
    colors: BlurColors,
): Modifier = if (softGlass.usesIndependentBlurRadii) {
    textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadiusX = softGlass.resolvedBlurRadiusX,
        blurRadiusY = softGlass.resolvedBlurRadiusY,
        noiseCoefficient = softGlass.noiseCoefficient,
        colors = colors,
        contentBlendMode = softGlass.blurContentBlendMode,
        enabled = softGlass.textureBlurEnabled,
    )
} else {
    textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = softGlass.resolvedBlurRadius,
        noiseCoefficient = softGlass.noiseCoefficient,
        colors = colors,
        contentBlendMode = softGlass.blurContentBlendMode,
        enabled = softGlass.textureBlurEnabled,
    )
}

val LocalSoftGlassSpec = staticCompositionLocalOf { SoftGlassSpec() }

val LocalSoftGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
