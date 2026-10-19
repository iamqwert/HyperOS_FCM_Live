package io.github.howard20181.hyperos.fcmlive.ui.softglass

// Migrated from Deadliner 210e4a2d4c6bf7df291b6c6852d39366bc1fee17.
// Integration changes: package and MIUIX semantic UI bindings only.
//
// The four tuning files the source kept apart (a level table, a radius table, a
// fine-tuning record and a modal-material record) are one file here, and two of
// them are gone: this app exposes no tuning UI, so the level presets, the
// "nearest level" lookup and the modal material table had nothing left to serve.
// What remains is the numbers, plus `normalized()` — kept not as a settings
// hook but as the guard that stops a hand-written or future value from leaving
// the ranges the shader was tuned in.

/**
 * The blur radii the saturation ramp is interpolated between. The names are the
 * source's; nothing addresses them by [key] here, they are the table
 * [SoftGlassTuning.saturationForBlurRadius] walks.
 */
enum class SoftGlassLevel(
    val key: String,
    val blurRadius: Float,
    val blurSaturation: Float,
) {
    CrystalClear("crystal_clear", 28f, 1.05f),
    Light("light", 60f, 1.02f),
    Soft("soft", 128f, 1.00f),
    Hazy("hazy", 196f, 0.98f),
    Deep("deep", 256f, 0.95f);
}

/** The radius range the material is defined over, and the ramp from radius to saturation. */
object SoftGlassTuning {
    const val MinBlurRadius = 28f
    const val DefaultBlurRadius = 92f
    const val MaxBlurRadius = 256f

    fun normalizeBlurRadius(value: Float): Float = if (value.isFinite()) {
        value.coerceIn(MinBlurRadius, MaxBlurRadius)
    } else {
        DefaultBlurRadius
    }

    /**
     * Saturation as a function of radius: a wider blur flattens the picture, so
     * it wants less colour pushed back through it. Interpolated between the
     * [SoftGlassLevel] rows either side of [value] rather than stepped, which is
     * what keeps the material from changing character between two radii a
     * hair apart.
     */
    fun saturationForBlurRadius(value: Float): Float {
        val radius = normalizeBlurRadius(value)
        val levels = SoftGlassLevel.entries
        val upperIndex = levels.indexOfFirst { radius <= it.blurRadius }
        if (upperIndex <= 0) return levels.first().blurSaturation

        val upper = levels[upperIndex]
        val lower = levels[upperIndex - 1]
        val fraction = (radius - lower.blurRadius) / (upper.blurRadius - lower.blurRadius)
        return lower.blurSaturation + (upper.blurSaturation - lower.blurSaturation) * fraction
    }
}

/**
 * Every knob the optical stack reads, in one record.
 *
 * The defaults are the source's and are what the look ships with; there is no
 * screen that edits them. The `*Multiplier` and `glass*` fields are the ones
 * the recipes scale per surface — a tint alpha of 0.90 and a blur radius of
 * 0.25 × 92 are what make the pill read as glass rather than as a frosted
 * panel. The `dialog*` / `bottomSheet*` fields the source carried are not here:
 * this app has no glassed modals yet, so nothing read them.
 */
data class SoftGlassFineTuning(
    val saturationMultiplier: Float = 1.05f,
    val noiseCoefficient: Float = 0.095f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val topBarTintAlpha: Float = 0.75f,
    val navigationTintAlpha: Float = 0.75f,
    val glassBlurRadiusMultiplier: Float = 0.25f,
    val glassNoiseMultiplier: Float = 1f,
    val glassTintAlphaMultiplier: Float = 0.90f,
    val glassTintLightGray: Float = 0.99f,
    val glassTintDarkGray: Float = 0.12f,
    val refractionEnabled: Boolean = true,
    val refractionHeightDp: Float = 18f,
    val refractionAmountDp: Float = 18f,
    val refractionDepthEffect: Float = 0.60f,
    val chromaticAberrationDp: Float = 1f,
    val edgeHighlightAlpha: Float = 0.95f,
    val edgeHighlightGray: Float = 1f,
    val darkHighlightMultiplier: Float = 0.20f,
    val fallbackEdgeWidthDp: Float = 0.5f,
    val fallbackLightAlpha: Float = 0.46f,
    val fallbackDarkAlpha: Float = 0.08f,
    val postRefractionBlurEnabled: Boolean = true,
    val postRefractionBlurRadiusDp: Float = 0.5f,
) {
    /** Clamps every field into the range the shader was tuned in. */
    fun normalized(): SoftGlassFineTuning = copy(
        saturationMultiplier = saturationMultiplier.safeCoerceIn(0.75f, 1.25f, 1.05f),
        noiseCoefficient = noiseCoefficient.safeCoerceIn(0f, 0.20f, 0.095f),
        brightness = brightness.safeCoerceIn(-0.20f, 0.20f, 0f),
        contrast = contrast.safeCoerceIn(0.75f, 1.25f, 1f),
        topBarTintAlpha = topBarTintAlpha.safeCoerceIn(0.20f, 1f, 0.75f),
        navigationTintAlpha = navigationTintAlpha.safeCoerceIn(0.20f, 1f, 0.75f),
        glassBlurRadiusMultiplier = glassBlurRadiusMultiplier.safeCoerceIn(0.10f, 1f, 0.25f),
        glassNoiseMultiplier = glassNoiseMultiplier.safeCoerceIn(0f, 2f, 1f),
        glassTintAlphaMultiplier = glassTintAlphaMultiplier.safeCoerceIn(0.50f, 1.50f, 0.90f),
        glassTintLightGray = glassTintLightGray.safeCoerceIn(0f, 1f, 0.99f),
        glassTintDarkGray = glassTintDarkGray.safeCoerceIn(0f, 1f, 0.12f),
        refractionHeightDp = refractionHeightDp.safeCoerceIn(0f, 32f, 18f),
        refractionAmountDp = refractionAmountDp.safeCoerceIn(0f, 32f, 18f),
        refractionDepthEffect = refractionDepthEffect.safeCoerceIn(0f, 0.60f, 0.60f),
        chromaticAberrationDp = chromaticAberrationDp.safeCoerceIn(0f, 4f, 1f),
        edgeHighlightAlpha = edgeHighlightAlpha.safeCoerceIn(0f, 1f, 0.95f),
        edgeHighlightGray = edgeHighlightGray.safeCoerceIn(0f, 1f, 1f),
        darkHighlightMultiplier = darkHighlightMultiplier.safeCoerceIn(0.10f, 2f, 0.20f),
        fallbackEdgeWidthDp = fallbackEdgeWidthDp.safeCoerceIn(0f, 2f, 0.5f),
        fallbackLightAlpha = fallbackLightAlpha.safeCoerceIn(0f, 1f, 0.46f),
        fallbackDarkAlpha = fallbackDarkAlpha.safeCoerceIn(0f, 1f, 0.08f),
        postRefractionBlurRadiusDp = postRefractionBlurRadiusDp.safeCoerceIn(0f, 2f, 0.5f),
    )

    companion object {
        val Default = SoftGlassFineTuning()
    }
}

private fun Float.safeCoerceIn(minimum: Float, maximum: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(minimum, maximum) else fallback
