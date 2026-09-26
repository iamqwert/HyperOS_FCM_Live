/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ----
 *
 * Vendored subset of Google material-color-utilities (Apache-2.0):
 * https://github.com/material-foundation/material-color-utilities
 *
 * Implements the two published Material Design 3 color specs:
 *  - SPEC_2021: the original Material You spec (9 palette styles).
 *  - SPEC_2025: Material 3 Expressive. The official library only publishes new
 *    rules for NEUTRAL, TONAL_SPOT, VIBRANT and EXPRESSIVE; every other style
 *    falls back to SPEC_2021 (see DynamicScheme.maybeFallbackSpecVersion).
 */
package io.github.howard20181.hyperos.fcmlive.mcu

/**
 * A Material Design 3 dynamic color scheme: tonal palettes plus the resolved
 * color roles for one (seed color, palette style, spec version, light/dark)
 * combination. Every role is resolved once, in the constructor.
 */
class Scheme private constructor(
    source: Hct,
    variant: Variant,
    dark: Boolean,
    spec: Spec
) {

    /** Material Design 3 palette styles (official `Variant` enum). */
    enum class Variant {
        MONOCHROME,
        NEUTRAL,
        TONAL_SPOT,
        VIBRANT,
        EXPRESSIVE,
        FIDELITY,
        CONTENT,
        RAINBOW,
        FRUIT_SALAD
    }

    /** Material Design 3 color spec versions. */
    enum class Spec {
        SPEC_2021,
        SPEC_2025
    }

    @JvmField
    val primaryPalette: TonalPalette
    @JvmField
    val secondaryPalette: TonalPalette
    @JvmField
    val tertiaryPalette: TonalPalette
    @JvmField
    val neutralPalette: TonalPalette
    @JvmField
    val neutralVariantPalette: TonalPalette
    @JvmField
    val errorPalette: TonalPalette

    @JvmField
    val dark: Boolean = dark
    @JvmField
    val variant: Variant
    @JvmField
    val spec: Spec

    // Public mutable ints match the Java field API (resolved once in <init>).
    @JvmField
    var primary: Int = 0
    @JvmField
    var onPrimary: Int = 0
    @JvmField
    var primaryContainer: Int = 0
    @JvmField
    var onPrimaryContainer: Int = 0
    @JvmField
    var secondary: Int = 0
    @JvmField
    var onSecondary: Int = 0
    @JvmField
    var secondaryContainer: Int = 0
    @JvmField
    var onSecondaryContainer: Int = 0
    @JvmField
    var tertiary: Int = 0
    @JvmField
    var onTertiary: Int = 0
    @JvmField
    var tertiaryContainer: Int = 0
    @JvmField
    var onTertiaryContainer: Int = 0
    @JvmField
    var surface: Int = 0
    @JvmField
    var surfaceDim: Int = 0
    @JvmField
    var surfaceBright: Int = 0
    @JvmField
    var surfaceContainerLowest: Int = 0
    @JvmField
    var surfaceContainerLow: Int = 0
    @JvmField
    var surfaceContainer: Int = 0
    @JvmField
    var surfaceContainerHigh: Int = 0
    @JvmField
    var surfaceContainerHighest: Int = 0
    @JvmField
    var onSurface: Int = 0
    @JvmField
    var surfaceVariant: Int = 0
    @JvmField
    var onSurfaceVariant: Int = 0
    @JvmField
    var outline: Int = 0
    @JvmField
    var outlineVariant: Int = 0
    @JvmField
    var inverseSurface: Int = 0
    @JvmField
    var inverseOnSurface: Int = 0
    @JvmField
    var error: Int = 0
    @JvmField
    var onError: Int = 0
    @JvmField
    var errorContainer: Int = 0
    @JvmField
    var onErrorContainer: Int = 0

    init {
        // Official rule: the 2025 spec only covers four styles; anything else is
        // generated with the 2021 spec.
        val use2025 =
            spec == Spec.SPEC_2025 &&
                (variant == Variant.NEUTRAL ||
                    variant == Variant.TONAL_SPOT ||
                    variant == Variant.VIBRANT ||
                    variant == Variant.EXPRESSIVE)
        this.variant = variant
        this.spec = if (use2025) Spec.SPEC_2025 else Spec.SPEC_2021

        val p = if (use2025) palettes2025(source, variant, dark) else palettes2021(source, variant)
        primaryPalette = p.primary
        secondaryPalette = p.secondary
        tertiaryPalette = p.tertiary
        neutralPalette = p.neutral
        neutralVariantPalette = p.neutralVariant
        errorPalette = p.error

        if (use2025) {
            resolve2025()
        } else {
            resolve2021()
        }
    }

    // ---------------------------------------------------------------- 2021 spec

    private fun resolve2021() {
        primary = color(primaryPalette, if (dark) 80.0 else 40.0)
        onPrimary = color(primaryPalette, if (dark) 20.0 else 100.0)
        primaryContainer = color(primaryPalette, if (dark) 30.0 else 90.0)
        onPrimaryContainer = color(primaryPalette, if (dark) 90.0 else 30.0)

        secondary = color(secondaryPalette, if (dark) 80.0 else 40.0)
        onSecondary = color(secondaryPalette, if (dark) 20.0 else 100.0)
        secondaryContainer = color(secondaryPalette, if (dark) 30.0 else 90.0)
        onSecondaryContainer = color(secondaryPalette, if (dark) 90.0 else 30.0)

        tertiary = color(tertiaryPalette, if (dark) 80.0 else 40.0)
        onTertiary = color(tertiaryPalette, if (dark) 20.0 else 100.0)
        tertiaryContainer = color(tertiaryPalette, if (dark) 30.0 else 90.0)
        onTertiaryContainer = color(tertiaryPalette, if (dark) 90.0 else 30.0)

        surface = color(neutralPalette, if (dark) 6.0 else 98.0)
        surfaceDim = surface
        surfaceBright = surface
        surfaceContainerLowest = color(neutralPalette, if (dark) 4.0 else 100.0)
        surfaceContainerLow = color(neutralPalette, if (dark) 10.0 else 96.0)
        surfaceContainer = color(neutralPalette, if (dark) 12.0 else 94.0)
        surfaceContainerHigh = color(neutralPalette, if (dark) 17.0 else 92.0)
        surfaceContainerHighest = color(neutralPalette, if (dark) 22.0 else 90.0)
        onSurface = color(neutralPalette, if (dark) 90.0 else 10.0)
        surfaceVariant = color(neutralVariantPalette, if (dark) 30.0 else 90.0)
        onSurfaceVariant = color(neutralVariantPalette, if (dark) 80.0 else 30.0)
        outline = color(neutralVariantPalette, if (dark) 60.0 else 50.0)
        outlineVariant = color(neutralVariantPalette, if (dark) 30.0 else 80.0)
        inverseSurface = color(neutralPalette, if (dark) 90.0 else 20.0)
        inverseOnSurface = color(neutralPalette, if (dark) 20.0 else 95.0)

        error = color(errorPalette, if (dark) 80.0 else 40.0)
        onError = color(errorPalette, if (dark) 20.0 else 100.0)
        errorContainer = color(errorPalette, if (dark) 30.0 else 90.0)
        onErrorContainer = color(errorPalette, if (dark) 90.0 else 30.0)
    }

    // ---------------------------------------------------------------- 2025 spec

    private fun resolve2025() {
        val vibrant = variant == Variant.VIBRANT
        val neutralHue = neutralPalette.hue
        val yellowNeutral = Hct.isYellow(neutralHue)

        // --- surfaces -----------------------------------------------------
        val surfaceTone = if (dark) 4.0 else (if (yellowNeutral) 99.0 else (if (vibrant) 97.0 else 98.0))
        val dimTone = if (dark) 4.0 else (if (yellowNeutral) 90.0 else (if (vibrant) 85.0 else 87.0))
        val brightTone = if (dark) 18.0 else (if (yellowNeutral) 99.0 else (if (vibrant) 97.0 else 98.0))

        surface = color(neutralPalette, surfaceTone)
        surfaceDim = color(neutralPalette, dimTone, if (dark) 1.0 else surfaceEdgeChroma())
        surfaceBright = color(neutralPalette, brightTone, if (dark) surfaceEdgeChroma() else 1.0)
        surfaceContainerLowest = color(neutralPalette, if (dark) 0.0 else 100.0)
        surfaceContainerLow =
            color(
                neutralPalette,
                if (dark) 6.0 else (if (yellowNeutral) 98.0 else (if (vibrant) 95.0 else 96.0)),
                containerLowChroma()
            )
        surfaceContainer =
            color(neutralPalette, if (dark) 9.0 else (if (yellowNeutral) 96.0 else (if (vibrant) 92.0 else 94.0)))
        surfaceContainerHigh =
            color(neutralPalette, if (dark) 12.0 else (if (yellowNeutral) 94.0 else (if (vibrant) 90.0 else 92.0)))
        surfaceContainerHighest =
            color(neutralPalette, if (dark) 15.0 else (if (yellowNeutral) 92.0 else (if (vibrant) 88.0 else 90.0)))
        surfaceVariant = color(neutralVariantPalette, if (dark) 30.0 else 90.0)

        // Background used by every contrast curve in the 2025 spec.
        val bgTone = if (dark) brightTone else dimTone

        val onSurfaceTone =
            if (vibrant) {
                contrastTone(tMaxC(neutralPalette, 0.0, 100.0, 1.1), bgTone, if (dark) 11.0 else 9.0)
            } else {
                contrastTone(bgTone, bgTone, if (dark) 11.0 else 9.0)
            }
        onSurface = color(neutralPalette, onSurfaceTone, neutralTextChroma())
        onSurfaceVariant =
            color(neutralPalette, contrastTone(bgTone, bgTone, if (dark) 6.0 else 4.5), neutralTextChroma())
        outline = color(neutralPalette, contrastTone(bgTone, bgTone, 3.0), neutralTextChroma())
        outlineVariant = color(neutralPalette, contrastTone(bgTone, bgTone, 1.5), neutralTextChroma())

        // --- primary -------------------------------------------------------
        var primaryTone: Double
        val primaryContainerTone: Double
        val cyanPrimary = Hct.isCyan(primaryPalette.hue)
        val yellowPrimary = Hct.isYellow(primaryPalette.hue)
        when (variant) {
            Variant.NEUTRAL -> {
                primaryTone = if (dark) 80.0 else 40.0
                primaryContainerTone = if (dark) 30.0 else 90.0
            }
            Variant.TONAL_SPOT -> {
                primaryTone = if (dark) 80.0 else tMaxC(primaryPalette, 0.0, 100.0)
                primaryContainerTone =
                    if (dark) tMinC(primaryPalette, 35.0, 93.0) else tMaxC(primaryPalette, 0.0, 90.0)
            }
            Variant.EXPRESSIVE -> {
                primaryTone =
                    tMaxC(
                        primaryPalette,
                        0.0,
                        if (dark) (if (cyanPrimary) 88.0 else 98.0) else (if (yellowPrimary) 25.0 else 98.0)
                    )
                primaryContainerTone =
                    if (dark) {
                        tMinC(primaryPalette, 30.0, 93.0)
                    } else {
                        tMaxC(primaryPalette, 78.0, if (cyanPrimary) 88.0 else 90.0)
                    }
            }
            else -> {
                // VIBRANT
                primaryTone = tMaxC(primaryPalette, 0.0, if (cyanPrimary) 88.0 else 98.0)
                primaryContainerTone =
                    if (dark) {
                        tMinC(primaryPalette, 66.0, 93.0)
                    } else {
                        tMaxC(primaryPalette, 66.0, if (cyanPrimary) 88.0 else 93.0)
                    }
            }
        }
        // Contrast curve carried by the 2025 "primary" role: 4.5:1 against
        // surfaceDim (light) / surfaceBright (dark). The raw tMaxC tone is often
        // far too light to be used as a foreground color, so it is only kept
        // when it already reaches the ratio.
        if (Contrast.ratioOfTones(primaryTone, bgTone) < 4.5) {
            primaryTone = foregroundTone(bgTone, 4.5)
        }
        primary = color(primaryPalette, primaryTone)
        onPrimary = color(primaryPalette, contrastTone(primaryTone, primaryTone, 6.0))
        primaryContainer = color(primaryPalette, primaryContainerTone)
        onPrimaryContainer =
            color(primaryPalette, contrastTone(primaryContainerTone, primaryContainerTone, 6.0))

        // --- secondary -----------------------------------------------------
        val secondaryTone: Double
        val secondaryContainerTone: Double
        when (variant) {
            Variant.NEUTRAL -> {
                secondaryTone =
                    if (dark) tMinC(secondaryPalette, 0.0, 98.0) else tMaxC(secondaryPalette, 0.0, 100.0)
                secondaryContainerTone = if (dark) 25.0 else 90.0
            }
            Variant.TONAL_SPOT, Variant.EXPRESSIVE -> {
                secondaryTone = if (dark) 80.0 else tMaxC(secondaryPalette, 0.0, 100.0)
                secondaryContainerTone =
                    if (variant == Variant.EXPRESSIVE) {
                        if (dark) 15.0 else tMaxC(secondaryPalette, 90.0, 95.0)
                    } else {
                        if (dark) 25.0 else 90.0
                    }
            }
            else -> {
                // VIBRANT
                secondaryTone =
                    if (dark) tMaxC(secondaryPalette, 0.0, 90.0) else tMaxC(secondaryPalette, 0.0, 98.0)
                secondaryContainerTone =
                    if (dark) tMinC(secondaryPalette, 30.0, 40.0) else tMaxC(secondaryPalette, 84.0, 90.0)
            }
        }
        secondary = color(secondaryPalette, secondaryTone)
        onSecondary = color(secondaryPalette, contrastTone(secondaryTone, secondaryTone, 6.0))
        secondaryContainer = color(secondaryPalette, secondaryContainerTone)
        onSecondaryContainer =
            color(secondaryPalette, contrastTone(secondaryContainerTone, secondaryContainerTone, 6.0))

        // --- tertiary ------------------------------------------------------
        val cyanTertiary = Hct.isCyan(tertiaryPalette.hue)
        val tertiaryTone: Double
        val tertiaryContainerTone: Double
        when (variant) {
            Variant.NEUTRAL -> {
                tertiaryTone =
                    if (dark) tMaxC(tertiaryPalette, 0.0, 98.0) else tMaxC(tertiaryPalette, 0.0, 100.0)
                tertiaryContainerTone =
                    if (dark) tMaxC(tertiaryPalette, 0.0, 93.0) else tMaxC(tertiaryPalette, 0.0, 96.0)
            }
            Variant.TONAL_SPOT -> {
                tertiaryTone =
                    if (dark) tMaxC(tertiaryPalette, 0.0, 98.0) else tMaxC(tertiaryPalette, 0.0, 100.0)
                tertiaryContainerTone =
                    if (dark) tMaxC(tertiaryPalette, 0.0, 93.0) else tMaxC(tertiaryPalette, 0.0, 100.0)
            }
            Variant.EXPRESSIVE -> {
                tertiaryTone =
                    tMaxC(
                        tertiaryPalette,
                        0.0,
                        if (dark) (if (cyanTertiary) 88.0 else 98.0) else (if (cyanTertiary) 88.0 else 100.0)
                    )
                tertiaryContainerTone =
                    if (dark) {
                        tMaxC(tertiaryPalette, 75.0, if (cyanTertiary) 88.0 else 93.0)
                    } else {
                        tMaxC(tertiaryPalette, 75.0, if (cyanTertiary) 88.0 else 100.0)
                    }
            }
            else -> {
                // VIBRANT
                tertiaryTone =
                    tMaxC(
                        tertiaryPalette,
                        0.0,
                        if (dark) (if (cyanTertiary) 88.0 else 98.0) else (if (cyanTertiary) 88.0 else 100.0)
                    )
                tertiaryContainerTone =
                    if (dark) {
                        tMaxC(tertiaryPalette, 0.0, 93.0)
                    } else {
                        tMaxC(tertiaryPalette, 72.0, 100.0)
                    }
            }
        }
        tertiary = color(tertiaryPalette, tertiaryTone)
        onTertiary = color(tertiaryPalette, contrastTone(tertiaryTone, tertiaryTone, 6.0))
        tertiaryContainer = color(tertiaryPalette, tertiaryContainerTone)
        onTertiaryContainer =
            color(tertiaryPalette, contrastTone(tertiaryContainerTone, tertiaryContainerTone, 6.0))

        // --- inverse / error ------------------------------------------------
        val inverseTone = if (dark) 98.0 else 4.0
        inverseSurface = color(neutralPalette, inverseTone)
        inverseOnSurface = color(neutralPalette, contrastTone(inverseTone, inverseTone, 7.0))

        error = color(errorPalette, if (dark) 80.0 else 40.0)
        onError = color(errorPalette, if (dark) 20.0 else 100.0)
        errorContainer = color(errorPalette, if (dark) 30.0 else 90.0)
        onErrorContainer = color(errorPalette, if (dark) 90.0 else 30.0)
    }

    /** Chroma multiplier for surfaceDim (light) / surfaceBright (dark). */
    private fun surfaceEdgeChroma(): Double {
        return when (variant) {
            Variant.NEUTRAL -> 2.5
            Variant.TONAL_SPOT -> 1.7
            Variant.EXPRESSIVE -> if (Hct.isYellow(neutralPalette.hue)) 2.7 else 1.75
            Variant.VIBRANT -> 1.36
            else -> 1.0
        }
    }

    /** Chroma multiplier for surfaceContainerLow. */
    private fun containerLowChroma(): Double {
        return when (variant) {
            Variant.NEUTRAL -> 1.3
            Variant.TONAL_SPOT -> 1.25
            Variant.EXPRESSIVE -> if (Hct.isYellow(neutralPalette.hue)) 1.3 else 1.15
            Variant.VIBRANT -> 1.08
            else -> 1.0
        }
    }

    /** Chroma multiplier shared by onSurface / onSurfaceVariant / outline / outlineVariant. */
    private fun neutralTextChroma(): Double {
        return when (variant) {
            Variant.NEUTRAL -> 2.2
            Variant.TONAL_SPOT -> 1.7
            Variant.EXPRESSIVE -> if (Hct.isYellow(neutralPalette.hue)) (if (dark) 3.0 else 2.3) else 1.6
            else -> 1.0
        }
    }

    private fun color(palette: TonalPalette, tone: Double): Int = color(palette, tone, 1.0)

    private fun color(palette: TonalPalette, tone: Double, chromaMultiplier: Double): Int {
        if (chromaMultiplier == 1.0) {
            return palette.tone(tone)
        }
        return Hct.from(palette.hue, palette.chroma * chromaMultiplier, tone).toInt()
    }

    companion object {
        /** Builds a scheme from a seed (source) color. */
        @JvmStatic
        fun create(seedArgb: Int, variant: Variant, dark: Boolean, spec: Spec): Scheme {
            return Scheme(Hct.fromInt(seedArgb), variant, dark, spec)
        }

        // ---------------------------------------------------------------- palettes

        /** Six tonal palettes. */
        private class Palettes {
            lateinit var primary: TonalPalette
            lateinit var secondary: TonalPalette
            lateinit var tertiary: TonalPalette
            lateinit var neutral: TonalPalette
            lateinit var neutralVariant: TonalPalette
            lateinit var error: TonalPalette
        }

        private fun palettes2021(source: Hct, variant: Variant): Palettes {
            val hue = source.hue
            val chroma = source.chroma
            val p = Palettes()
            when (variant) {
                Variant.MONOCHROME -> {
                    p.primary = TonalPalette.fromHueAndChroma(hue, 0.0)
                    p.secondary = TonalPalette.fromHueAndChroma(hue, 0.0)
                    p.tertiary = TonalPalette.fromHueAndChroma(hue, 0.0)
                    p.neutral = TonalPalette.fromHueAndChroma(hue, 0.0)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 0.0)
                }
                Variant.NEUTRAL -> {
                    p.primary = TonalPalette.fromHueAndChroma(hue, 12.0)
                    p.secondary = TonalPalette.fromHueAndChroma(hue, 8.0)
                    p.tertiary = TonalPalette.fromHueAndChroma(hue, 16.0)
                    p.neutral = TonalPalette.fromHueAndChroma(hue, 2.0)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 2.0)
                }
                Variant.TONAL_SPOT -> {
                    p.primary = TonalPalette.fromHueAndChroma(hue, 36.0)
                    p.secondary = TonalPalette.fromHueAndChroma(hue, 16.0)
                    p.tertiary =
                        TonalPalette.fromHueAndChroma(
                            MathUtils.sanitizeDegreesDouble(hue + 60.0), 24.0
                        )
                    p.neutral = TonalPalette.fromHueAndChroma(hue, 6.0)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 8.0)
                }
                Variant.VIBRANT -> {
                    p.primary = TonalPalette.fromHueAndChroma(hue, 200.0)
                    p.secondary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 41.0, 61.0, 101.0, 131.0, 181.0, 251.0, 301.0, 360.0),
                                doubleArrayOf(18.0, 15.0, 10.0, 12.0, 15.0, 18.0, 15.0, 12.0, 12.0)
                            ),
                            24.0
                        )
                    p.tertiary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 41.0, 61.0, 101.0, 131.0, 181.0, 251.0, 301.0, 360.0),
                                doubleArrayOf(35.0, 30.0, 20.0, 25.0, 30.0, 35.0, 30.0, 25.0, 25.0)
                            ),
                            32.0
                        )
                    p.neutral = TonalPalette.fromHueAndChroma(hue, 10.0)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 12.0)
                }
                Variant.EXPRESSIVE -> {
                    p.primary =
                        TonalPalette.fromHueAndChroma(
                            MathUtils.sanitizeDegreesDouble(hue + 240.0), 40.0
                        )
                    p.secondary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 21.0, 51.0, 121.0, 151.0, 191.0, 271.0, 321.0, 360.0),
                                doubleArrayOf(45.0, 95.0, 45.0, 20.0, 45.0, 90.0, 45.0, 45.0, 45.0)
                            ),
                            24.0
                        )
                    p.tertiary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 21.0, 51.0, 121.0, 151.0, 191.0, 271.0, 321.0, 360.0),
                                doubleArrayOf(120.0, 120.0, 20.0, 45.0, 20.0, 15.0, 20.0, 120.0, 120.0)
                            ),
                            32.0
                        )
                    p.neutral =
                        TonalPalette.fromHueAndChroma(
                            MathUtils.sanitizeDegreesDouble(hue + 15.0), 8.0
                        )
                    p.neutralVariant =
                        TonalPalette.fromHueAndChroma(
                            MathUtils.sanitizeDegreesDouble(hue + 15.0), 12.0
                        )
                }
                Variant.RAINBOW -> {
                    p.primary = TonalPalette.fromHueAndChroma(hue, 48.0)
                    p.secondary = TonalPalette.fromHueAndChroma(hue, 16.0)
                    p.tertiary =
                        TonalPalette.fromHueAndChroma(
                            MathUtils.sanitizeDegreesDouble(hue + 60.0), 24.0
                        )
                    p.neutral = TonalPalette.fromHueAndChroma(hue, 0.0)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 0.0)
                }
                Variant.FRUIT_SALAD -> {
                    p.primary =
                        TonalPalette.fromHueAndChroma(
                            MathUtils.sanitizeDegreesDouble(hue - 50.0), 48.0
                        )
                    p.secondary =
                        TonalPalette.fromHueAndChroma(
                            MathUtils.sanitizeDegreesDouble(hue - 50.0), 36.0
                        )
                    p.tertiary = TonalPalette.fromHueAndChroma(hue, 36.0)
                    p.neutral = TonalPalette.fromHueAndChroma(hue, 10.0)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 16.0)
                }
                else -> {
                    // FIDELITY, CONTENT
                    p.primary = TonalPalette.fromHueAndChroma(hue, chroma)
                    p.secondary =
                        TonalPalette.fromHueAndChroma(hue, Math.max(chroma - 32.0, chroma * 0.5))
                    p.tertiary =
                        if (variant == Variant.FIDELITY) {
                            TonalPalette.fromHct(
                                DislikeAnalyzer.fixIfDisliked(
                                    TemperatureCache(source).complement
                                )
                            )
                        } else {
                            TonalPalette.fromHct(
                                DislikeAnalyzer.fixIfDisliked(
                                    TemperatureCache(source).getAnalogousColors(3, 6)[2]
                                )
                            )
                        }
                    p.neutral = TonalPalette.fromHueAndChroma(hue, chroma / 8.0)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(hue, (chroma / 8.0) + 4.0)
                }
            }
            p.error = TonalPalette.fromHueAndChroma(25.0, 84.0)
            return p
        }

        private fun palettes2025(source: Hct, variant: Variant, dark: Boolean): Palettes {
            val hue = source.hue
            val p = Palettes()
            var neutralHue: Double
            var neutralChroma: Double
            when (variant) {
                Variant.NEUTRAL -> {
                    p.primary = TonalPalette.fromHueAndChroma(hue, if (Hct.isBlue(hue)) 12.0 else 8.0)
                    p.secondary = TonalPalette.fromHueAndChroma(hue, if (Hct.isBlue(hue)) 6.0 else 4.0)
                    p.tertiary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 38.0, 105.0, 161.0, 204.0, 278.0, 333.0, 360.0),
                                doubleArrayOf(-32.0, 26.0, 10.0, -39.0, 24.0, -15.0, -32.0)
                            ),
                            20.0
                        )
                    p.neutral = TonalPalette.fromHueAndChroma(hue, 1.4)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 1.4 * 2.2)
                    p.error = TonalPalette.fromHueAndChroma(errorHue2025(source), 50.0)
                }
                Variant.TONAL_SPOT -> {
                    p.primary = TonalPalette.fromHueAndChroma(hue, if (dark) 26.0 else 32.0)
                    p.secondary = TonalPalette.fromHueAndChroma(hue, 16.0)
                    p.tertiary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 20.0, 71.0, 161.0, 333.0, 360.0),
                                doubleArrayOf(-40.0, 48.0, -32.0, 40.0, -32.0)
                            ),
                            28.0
                        )
                    p.neutral = TonalPalette.fromHueAndChroma(hue, 5.0)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 5 * 1.7)
                    p.error = TonalPalette.fromHueAndChroma(errorHue2025(source), 60.0)
                }
                Variant.EXPRESSIVE -> {
                    p.primary = TonalPalette.fromHueAndChroma(hue, if (dark) 36.0 else 48.0)
                    p.secondary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 105.0, 140.0, 204.0, 253.0, 278.0, 300.0, 333.0, 360.0),
                                doubleArrayOf(-160.0, 155.0, -100.0, 96.0, -96.0, -156.0, -165.0, -160.0)
                            ),
                            if (dark) 16.0 else 24.0
                        )
                    p.tertiary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 105.0, 140.0, 204.0, 253.0, 278.0, 300.0, 333.0, 360.0),
                                doubleArrayOf(-165.0, 160.0, -105.0, 101.0, -101.0, -160.0, -170.0, -165.0)
                            ),
                            48.0
                        )
                    neutralHue = expressiveNeutralHue(source)
                    neutralChroma = if (dark) (if (Hct.isYellow(neutralHue)) 6.0 else 14.0) else 18.0
                    p.neutral = TonalPalette.fromHueAndChroma(neutralHue, neutralChroma)
                    p.neutralVariant =
                        TonalPalette.fromHueAndChroma(
                            neutralHue,
                            neutralChroma * (if (neutralHue >= 105 && neutralHue < 125) 1.6 else 2.3)
                        )
                    p.error = TonalPalette.fromHueAndChroma(errorHue2025(source), 64.0)
                }
                else -> {
                    // VIBRANT
                    p.primary = TonalPalette.fromHueAndChroma(hue, 74.0)
                    p.secondary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 38.0, 105.0, 140.0, 333.0, 360.0),
                                doubleArrayOf(-14.0, 10.0, -14.0, 10.0, -14.0)
                            ),
                            56.0
                        )
                    p.tertiary =
                        TonalPalette.fromHueAndChroma(
                            rotatedHue(
                                source,
                                doubleArrayOf(0.0, 38.0, 71.0, 105.0, 140.0, 161.0, 253.0, 333.0, 360.0),
                                doubleArrayOf(-72.0, 35.0, 24.0, -24.0, 62.0, 50.0, 62.0, -72.0)
                            ),
                            56.0
                        )
                    neutralHue = vibrantNeutralHue(source)
                    neutralChroma = 28.0
                    p.neutral = TonalPalette.fromHueAndChroma(neutralHue, neutralChroma)
                    p.neutralVariant = TonalPalette.fromHueAndChroma(neutralHue, neutralChroma * 1.29)
                    p.error = TonalPalette.fromHueAndChroma(errorHue2025(source), 80.0)
                }
            }
            return p
        }

        private fun expressiveNeutralHue(source: Hct): Double {
            return rotatedHue(
                source,
                doubleArrayOf(0.0, 71.0, 124.0, 253.0, 278.0, 300.0, 360.0),
                doubleArrayOf(10.0, 0.0, 10.0, 0.0, 10.0, 0.0)
            )
        }

        private fun vibrantNeutralHue(source: Hct): Double {
            return rotatedHue(
                source,
                doubleArrayOf(0.0, 38.0, 105.0, 140.0, 333.0, 360.0),
                doubleArrayOf(-14.0, 10.0, -14.0, 10.0, -14.0)
            )
        }

        private fun errorHue2025(source: Hct): Double {
            return piecewise(
                source,
                doubleArrayOf(0.0, 3.0, 13.0, 23.0, 33.0, 43.0, 153.0, 273.0, 360.0),
                doubleArrayOf(12.0, 22.0, 32.0, 12.0, 22.0, 32.0, 22.0, 12.0)
            )
        }

        /** Official DynamicScheme.getRotatedHue. */
        private fun rotatedHue(source: Hct, breakpoints: DoubleArray, rotations: DoubleArray): Double {
            var rotation = piecewise(source, breakpoints, rotations)
            if (Math.min(breakpoints.size - 1, rotations.size) <= 0) {
                rotation = 0.0
            }
            return MathUtils.sanitizeDegreesDouble(source.hue + rotation)
        }

        /** Official DynamicScheme.getPiecewiseValue. */
        private fun piecewise(source: Hct, breakpoints: DoubleArray, hues: DoubleArray): Double {
            val size = Math.min(breakpoints.size - 1, hues.size)
            val sourceHue = source.hue
            for (i in 0 until size) {
                if (sourceHue >= breakpoints[i] && sourceHue < breakpoints[i + 1]) {
                    return MathUtils.sanitizeDegreesDouble(hues[i])
                }
            }
            return sourceHue
        }

        /** Official tMaxC: the tone in [lower, upper] whose color has the most chroma. */
        private fun tMaxC(palette: TonalPalette, lower: Double, upper: Double): Double {
            return tMaxC(palette, lower, upper, 1.0)
        }

        private fun tMaxC(
            palette: TonalPalette,
            lower: Double,
            upper: Double,
            chromaMultiplier: Double
        ): Double {
            val answer =
                findBestToneForChroma(
                    palette.hue, palette.chroma * chromaMultiplier, 100.0, true
                )
            return MathUtils.clampDouble(lower, upper, answer)
        }

        /** Official tMinC: the tone in [lower, upper] whose color has the least chroma. */
        private fun tMinC(palette: TonalPalette, lower: Double, upper: Double): Double {
            val answer = findBestToneForChroma(palette.hue, palette.chroma, 0.0, false)
            return MathUtils.clampDouble(lower, upper, answer)
        }

        private fun findBestToneForChroma(
            hue: Double,
            chroma: Double,
            tone: Double,
            byDecreasingTone: Boolean
        ): Double {
            var tone = tone
            var answer = tone
            var bestCandidate = Hct.from(hue, chroma, answer)
            while (bestCandidate.chroma < chroma) {
                if (tone < 0 || tone > 100) {
                    break
                }
                tone += if (byDecreasingTone) -1.0 else 1.0
                val newCandidate = Hct.from(hue, chroma, tone)
                if (bestCandidate.chroma < newCandidate.chroma) {
                    bestCandidate = newCandidate
                    answer = tone
                }
            }
            return answer
        }

        /** Official DynamicColor.foregroundTone. */
        private fun foregroundTone(bgTone: Double, ratio: Double): Double {
            val lighterTone = Contrast.lighterUnsafe(bgTone, ratio)
            val darkerTone = Contrast.darkerUnsafe(bgTone, ratio)
            val lighterRatio = Contrast.ratioOfTones(lighterTone, bgTone)
            val darkerRatio = Contrast.ratioOfTones(darkerTone, bgTone)
            val preferLighter = tonePrefersLightForeground(bgTone)
            if (preferLighter) {
                val negligibleDifference =
                    Math.abs(lighterRatio - darkerRatio) < 0.1 &&
                        lighterRatio < ratio &&
                        darkerRatio < ratio
                if (lighterRatio >= ratio || lighterRatio >= darkerRatio || negligibleDifference) {
                    return lighterTone
                }
                return darkerTone
            }
            return if (darkerRatio >= ratio || darkerRatio >= lighterRatio) darkerTone else lighterTone
        }

        private fun tonePrefersLightForeground(tone: Double): Boolean {
            return Math.round(tone) < 60
        }

        /** Keep the initial tone when it already reaches the ratio, else solve for one that does. */
        private fun contrastTone(initialTone: Double, bgTone: Double, ratio: Double): Double {
            if (Contrast.ratioOfTones(initialTone, bgTone) >= ratio) {
                return initialTone
            }
            return foregroundTone(bgTone, ratio)
        }
    }
}
