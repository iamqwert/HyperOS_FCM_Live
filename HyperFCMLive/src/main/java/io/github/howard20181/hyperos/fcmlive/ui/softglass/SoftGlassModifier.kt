package io.github.howard20181.hyperos.fcmlive.ui.softglass

// Migrated from Deadliner 210e4a2d4c6bf7df291b6c6852d39366bc1fee17.
// Integration changes: package and MIUIX semantic UI bindings only.
//
// The source's chromatic tint profiles are gone: this app's glass is one
// neutral wash on the bar, the tab capsule and the top-bar actions, and the
// colourise/screen-lift pair only ever ran for callers that asked for it —
// none did. What is left is the neutral path, stated once.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.effect
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

@Composable
fun Modifier.softGlassMaterial(
    softGlass: SoftGlassSpec,
    backdrop: LayerBackdrop?,
    shape: Shape,
    tint: Color,
    isDark: Boolean,
    recipe: GlassMaterialRecipe,
    interaction: GlassMaterialInteraction = GlassMaterialInteraction(),
): Modifier {
    val blurSpec = recipe.blur
    val resolvedMaterial = softGlass.copy(
        blurRadius = softGlass.blurRadius * blurSpec.radiusMultiplier,
        noiseCoefficient = (softGlass.noiseCoefficient * blurSpec.noiseMultiplier)
            .coerceAtLeast(0f),
    )
    val resolvedTint = tint.copy(
        alpha = (tint.alpha * blurSpec.tintAlphaMultiplier).coerceIn(0f, 1f),
    )
    val canDrawBackdrop = resolvedMaterial.textureBlurEnabled && backdrop != null
    val canDrawOpticalStack = canDrawBackdrop &&
        recipe.refraction.enabled &&
        isRuntimeShaderSupported()
    val material = when {
        canDrawOpticalStack -> Modifier.glassOpticalBackdrop(
            backdrop = requireNotNull(backdrop),
            shape = shape,
            material = resolvedMaterial,
            tint = resolvedTint,
            recipe = recipe,
            interaction = interaction,
            isDark = isDark,
        ).opticalGlassEdge(shape, recipe.edgeOptics, isDark)

        canDrawBackdrop -> {
            val blurColors = resolvedMaterial.rememberBlurColors(
                additionalBlendColors = listOf(BlendColorEntry(resolvedTint)),
            )
            Modifier
                .softGlassTextureBlur(
                    softGlass = resolvedMaterial,
                    backdrop = requireNotNull(backdrop),
                    shape = shape,
                    colors = blurColors,
                )
                .fallbackGlassEdge(shape, recipe.edgeOptics, isDark)
        }

        else -> Modifier
            .background(color = resolvedTint, shape = shape)
            .fallbackGlassEdge(shape, recipe.edgeOptics, isDark)
    }

    return this.then(material)
}

/**
 * A single backdrop effect chain keeps the Gaussian blur inside the refractive
 * lens: colour treatment -> full material blur -> capsule lens -> gentle
 * softening.
 */
private fun Modifier.glassOpticalBackdrop(
    backdrop: LayerBackdrop,
    shape: Shape,
    material: SoftGlassSpec,
    tint: Color,
    recipe: GlassMaterialRecipe,
    interaction: GlassMaterialInteraction,
    isDark: Boolean,
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        val maxBlurRadius = recipe.blur.maxRadius.coerceAtLeast(0f)
        // The radius the material ships with, used as the shader path always
        // used it: the optics stack's own number, NOT converted to pixels. It
        // reads as a gentler blur than the fallback's dp reading on the same
        // glass, and that soft focus is the look's resting character — the
        // delivered default, not an oversight.
        val blurRadius = material.blurRadius.coerceIn(0f, maxBlurRadius)
        val refraction = recipe.refraction
        val postBlurRadiusPx = if (recipe.layering.postRefractionBlurEnabled) {
            recipe.layering.postRefractionBlurRadius.toPx().coerceAtLeast(0f)
        } else {
            0f
        }
        // Set before the blur: the library raises the padding to its kernel
        // reach anyway, and raising it after would re-run this whole block once
        // (see BackdropEffectScope.padding).
        val effectPaddingPx = postBlurRadiusPx * 2f
        padding = effectPaddingPx

        colorControls(
            brightness = material.blurBrightness,
            contrast = material.blurContrast,
            saturation = material.blurSaturation,
        )
        if (blurRadius > 0f) {
            // The upstream chain's own pass: a full-resolution BlurEffect, then
            // the lens. No downscale is taken, so every pixel-space uniform
            // below is already in this layer's pixels.
            effect(
                BlurEffect(
                    radiusX = blurRadius,
                    radiusY = blurRadius,
                    edgeTreatment = TileMode.Clamp,
                ),
            )
        }

        runtimeShaderEffect(
            key = GlassRefractionShaderKey,
            shaderString = GlassRefractionShader,
            uniformShaderName = "source",
        ) {
            val highlight = recipe.edgeOptics.highlightAlpha * EDGE_HIGHLIGHT_SCALE *
                if (isDark) recipe.edgeOptics.darkHighlightMultiplier else 1f
            setFloatUniform("content_origin", effectPaddingPx, effectPaddingPx)
            setFloatUniform("content_size", size.width, size.height)
            // Every surface this app draws is a capsule: the lens radius is
            // half the rendered height.
            setFloatUniform("corner_radius", size.height / 2f)
            setFloatUniform("refraction_height", refraction.height.toPx())
            setFloatUniform(
                "refraction_amount",
                refraction.amount.toPx() * interaction.refractionScale.coerceAtLeast(0f),
            )
            setFloatUniform("depth_effect", refraction.depthEffect.coerceIn(0f, 1f))
            setFloatUniform(
                "chromatic_aberration",
                refraction.chromaticAberration.toPx(),
            )
            setFloatUniform("noise_coefficient", material.noiseCoefficient.coerceAtLeast(0f))
            setFloatUniform(
                "highlight_alpha",
                (highlight * interaction.highlightScale.coerceAtLeast(0f)).coerceIn(0f, 1f),
            )
            setFloatUniform(
                "highlight_gray",
                recipe.edgeOptics.highlightGray.coerceIn(0f, 1f),
            )
        }
        if (postBlurRadiusPx > 0f) {
            effect(
                BlurEffect(
                    radiusX = postBlurRadiusPx,
                    radiusY = postBlurRadiusPx,
                    edgeTreatment = TileMode.Clamp,
                ),
            )
        }
    },
    onDrawSurface = {
        drawRect(tint)
    },
    contentBlendMode = material.blurContentBlendMode,
)

private fun Modifier.fallbackGlassEdge(
    shape: Shape,
    spec: GlassEdgeOpticsSpec,
    isDark: Boolean,
): Modifier {
    val baseAlpha = (if (isDark) spec.fallbackDarkAlpha else spec.fallbackLightAlpha) *
        FALLBACK_EDGE_ALPHA_SCALE
    val edgeColor = grayscaleColor(spec.highlightGray)
    val edgeBrush = Brush.verticalGradient(
        colors = listOf(
            edgeColor.copy(alpha = baseAlpha),
            edgeColor.copy(alpha = baseAlpha * 0.66f),
            edgeColor.copy(alpha = baseAlpha * 0.42f),
        ),
    )
    return border(
        width = spec.fallbackWidth * EDGE_WIDTH_SCALE,
        brush = edgeBrush,
        shape = shape,
    )
}

private fun Modifier.opticalGlassEdge(
    shape: Shape,
    spec: GlassEdgeOpticsSpec,
    isDark: Boolean,
): Modifier {
    val darkMultiplier = if (isDark) spec.darkHighlightMultiplier else 1f
    val alpha = (spec.highlightAlpha * EDGE_HIGHLIGHT_SCALE * darkMultiplier).coerceIn(0f, 1f)
    val edgeColor = grayscaleColor(spec.highlightGray)
    val edgeBrush = Brush.verticalGradient(
        colors = listOf(
            edgeColor.copy(alpha = alpha),
            edgeColor.copy(alpha = alpha * 0.62f),
            edgeColor.copy(alpha = alpha * 0.30f),
        ),
    )
    return border(
        width = spec.fallbackWidth * EDGE_WIDTH_SCALE,
        brush = edgeBrush,
        shape = shape,
    )
}

private fun grayscaleColor(value: Float): Color {
    val gray = value.coerceIn(0f, 1f)
    return Color(red = gray, green = gray, blue = gray, alpha = 1f)
}

private const val EDGE_HIGHLIGHT_SCALE = 0.85f
private const val EDGE_WIDTH_SCALE = 1f
private const val FALLBACK_EDGE_ALPHA_SCALE = 0.45f
