/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * ----
 *
 * Vendored subset of Google material-color-utilities (Apache-2.0):
 * https://github.com/material-foundation/material-color-utilities
 */
package io.github.howard20181.hyperos.fcmlive.mcu

/**
 * A color that adjusts itself based on UI state, represented by [DynamicScheme].
 *
 * The color adjusts to accommodate a desired contrast level, or other adjustments such as
 * differing in light mode versus dark mode, or what the theme is, or what the color that produced
 * the theme is.
 *
 * Colors without backgrounds do not change tone when contrast changes. Colors with backgrounds
 * become closer to their background as contrast lowers, and further when contrast increases.
 *
 * Every input needed to calculate a color, adjust it for a contrast level, and ensure it has a
 * certain tone difference from another color is a function of the scheme. That is what makes the
 * official rules expressible without subclassing.
 */
internal class DynamicColor(
    /** The name of the dynamic color. */
    val name: String,
    /** Provides the [TonalPalette] for the scheme. */
    val palette: ((DynamicScheme) -> TonalPalette)?,
    /** Provides the tone for the scheme. */
    val tone: (DynamicScheme) -> Double,
    /** Whether this color is a background, with some other color as the foreground. */
    val isBackground: Boolean,
    /** Multiplier applied to the palette chroma when constructing the color. */
    val chromaMultiplier: ((DynamicScheme) -> Double)?,
    /** The background of this color, if it exists. */
    val background: ((DynamicScheme) -> DynamicColor?)?,
    /** A second background of this color, if it exists. */
    val secondBackground: ((DynamicScheme) -> DynamicColor?)?,
    /** How the contrast against [background] behaves at the four contrast levels. */
    val contrastCurve: ((DynamicScheme) -> ContrastCurve?)?,
    /** A tone distance constraint shared with another color, or null for none. */
    val toneDeltaPair: ((DynamicScheme) -> ToneDeltaPair?)?,
    /** Opacity of the color, between 0 and 1, or null for fully opaque. */
    val opacity: ((DynamicScheme) -> Double?)?
) {

    /** Returns this color as ARGB. */
    fun getArgb(scheme: DynamicScheme): Int {
        val argb = getHct(scheme).toInt()
        val percentage = opacity?.invoke(scheme) ?: return argb
        val alpha = MathUtils.clampInt(0, 255, Math.round(percentage * 255).toInt())
        return (argb and 0x00ffffff) or (alpha shl 24)
    }

    /** Returns this color as HCT. Resolved by the spec that matches the scheme's version. */
    fun getHct(scheme: DynamicScheme): Hct {
        return ColorSpecs.get(scheme.specVersion).getHct(scheme, this)
    }

    /** Returns the tone in HCT, ranging from 0 to 100, of the resolved color. */
    fun getTone(scheme: DynamicScheme): Double {
        return ColorSpecs.get(scheme.specVersion).getTone(scheme, this)
    }

    fun toBuilder(): Builder {
        return Builder()
            .setName(name)
            .setPalette(palette)
            .setTone(tone)
            .setIsBackground(isBackground)
            .setChromaMultiplier(chromaMultiplier)
            .setBackground(background)
            .setSecondBackground(secondBackground)
            .setContrastCurve(contrastCurve)
            .setToneDeltaPair(toneDeltaPair)
            .setOpacity(opacity)
    }

    /** Builder for [DynamicColor]. Every field is optional except the name. */
    internal class Builder {
        private var name: String? = null
        private var palette: ((DynamicScheme) -> TonalPalette)? = null
        private var tone: ((DynamicScheme) -> Double)? = null
        private var isBackground: Boolean = false
        private var chromaMultiplier: ((DynamicScheme) -> Double)? = null
        private var background: ((DynamicScheme) -> DynamicColor?)? = null
        private var secondBackground: ((DynamicScheme) -> DynamicColor?)? = null
        private var contrastCurve: ((DynamicScheme) -> ContrastCurve?)? = null
        private var toneDeltaPair: ((DynamicScheme) -> ToneDeltaPair?)? = null
        private var opacity: ((DynamicScheme) -> Double?)? = null

        fun setName(name: String): Builder = apply { this.name = name }

        fun setPalette(palette: ((DynamicScheme) -> TonalPalette)?): Builder =
            apply { this.palette = palette }

        fun setTone(tone: ((DynamicScheme) -> Double)?): Builder = apply { this.tone = tone }

        fun setIsBackground(isBackground: Boolean): Builder =
            apply { this.isBackground = isBackground }

        fun setChromaMultiplier(chromaMultiplier: ((DynamicScheme) -> Double)?): Builder =
            apply { this.chromaMultiplier = chromaMultiplier }

        fun setBackground(background: ((DynamicScheme) -> DynamicColor?)?): Builder =
            apply { this.background = background }

        fun setSecondBackground(secondBackground: ((DynamicScheme) -> DynamicColor?)?): Builder =
            apply { this.secondBackground = secondBackground }

        fun setContrastCurve(contrastCurve: ((DynamicScheme) -> ContrastCurve?)?): Builder =
            apply { this.contrastCurve = contrastCurve }

        fun setToneDeltaPair(toneDeltaPair: ((DynamicScheme) -> ToneDeltaPair?)?): Builder =
            apply { this.toneDeltaPair = toneDeltaPair }

        fun setOpacity(opacity: ((DynamicScheme) -> Double?)?): Builder =
            apply { this.opacity = opacity }

        /**
         * Defers to [extendedColor] for every scheme whose spec version is at least
         * [specVersion], and keeps this builder's own definition below it.
         *
         * This is how a newer spec layers on top of an older one: the newer spec writes its own
         * definition, passes it here together with the version it was introduced in, and the
         * resulting color resolves to the right definition per scheme.
         */
        fun extendSpecVersion(specVersion: Scheme.Spec, extendedColor: DynamicColor): Builder {
            validateExtendedColor(specVersion, extendedColor)
            return Builder()
                .setName(name!!)
                .setIsBackground(isBackground)
                .setPalette { s ->
                    val source = if (s.specVersion >= specVersion) extendedColor.palette else palette
                    source!!.invoke(s)
                }
                .setTone { s ->
                    val source = if (s.specVersion >= specVersion) extendedColor.tone else tone
                    source!!.invoke(s)
                }
                .setChromaMultiplier { s ->
                    val source =
                        if (s.specVersion >= specVersion) extendedColor.chromaMultiplier
                        else chromaMultiplier
                    source?.invoke(s) ?: 1.0
                }
                .setBackground { s ->
                    val source =
                        if (s.specVersion >= specVersion) extendedColor.background else background
                    source?.invoke(s)
                }
                .setSecondBackground { s ->
                    val source =
                        if (s.specVersion >= specVersion) extendedColor.secondBackground
                        else secondBackground
                    source?.invoke(s)
                }
                .setContrastCurve { s ->
                    val source =
                        if (s.specVersion >= specVersion) extendedColor.contrastCurve
                        else contrastCurve
                    source?.invoke(s)
                }
                .setToneDeltaPair { s ->
                    val source =
                        if (s.specVersion >= specVersion) extendedColor.toneDeltaPair
                        else toneDeltaPair
                    source?.invoke(s)
                }
                .setOpacity { s ->
                    val source =
                        if (s.specVersion >= specVersion) extendedColor.opacity else opacity
                    source?.invoke(s)
                }
        }

        fun build(): DynamicColor {
            val name = this.name!!
            val background = this.background
            if (background == null && secondBackground != null) {
                throw IllegalArgumentException(
                    "Color $name has secondBackground defined, but background is not defined."
                )
            }
            if (background == null && contrastCurve != null) {
                throw IllegalArgumentException(
                    "Color $name has contrastCurve defined, but background is not defined."
                )
            }
            if (background != null && contrastCurve == null) {
                throw IllegalArgumentException(
                    "Color $name has background defined, but contrastCurve is not defined."
                )
            }
            return DynamicColor(
                name = name,
                palette = palette,
                tone = tone ?: getInitialToneFromBackground(background),
                isBackground = isBackground,
                chromaMultiplier = chromaMultiplier,
                background = background,
                secondBackground = secondBackground,
                contrastCurve = contrastCurve,
                toneDeltaPair = toneDeltaPair,
                opacity = opacity
            )
        }

        private fun validateExtendedColor(specVersion: Scheme.Spec, extendedColor: DynamicColor) {
            if (name != extendedColor.name) {
                throw IllegalArgumentException(
                    "Attempting to extend color $name with color ${extendedColor.name} of " +
                        "different name for spec version $specVersion."
                )
            }
            if (isBackground != extendedColor.isBackground) {
                val self = if (isBackground) "background" else "foreground"
                val other = if (extendedColor.isBackground) "background" else "foreground"
                throw IllegalArgumentException(
                    "Attempting to extend color $name as a $self with color " +
                        "${extendedColor.name} as a $other for spec version $specVersion."
                )
            }
        }
    }

    companion object {

        /** A simple color with no background, for colors that have no background. */
        fun fromPalette(
            name: String,
            palette: (DynamicScheme) -> TonalPalette,
            tone: (DynamicScheme) -> Double
        ): DynamicColor {
            return Builder()
                .setName(name)
                .setPalette(palette)
                .setTone(tone)
                .build()
        }

        /** A simple color with no background and an explicit `isBackground`. */
        fun fromPalette(
            name: String,
            palette: (DynamicScheme) -> TonalPalette,
            tone: (DynamicScheme) -> Double,
            isBackground: Boolean
        ): DynamicColor {
            return Builder()
                .setName(name)
                .setPalette(palette)
                .setTone(tone)
                .setIsBackground(isBackground)
                .build()
        }

        /** Creates a color from a hex code. The result has no background. */
        fun fromArgb(name: String, argb: Int): DynamicColor {
            val hct = Hct.fromInt(argb)
            val palette = TonalPalette.fromInt(argb)
            return fromPalette(name, { palette }, { hct.tone })
        }

        /**
         * Given a background tone, finds a foreground tone that reaches a contrast ratio as close
         * to `ratio` as possible.
         */
        fun foregroundTone(bgTone: Double, ratio: Double): Double {
            val lighterTone = Contrast.lighterUnsafe(bgTone, ratio)
            val darkerTone = Contrast.darkerUnsafe(bgTone, ratio)
            val lighterRatio = Contrast.ratioOfTones(lighterTone, bgTone)
            val darkerRatio = Contrast.ratioOfTones(darkerTone, bgTone)
            val preferLighter = tonePrefersLightForeground(bgTone)

            if (preferLighter) {
                // "Negligible difference" handles an edge case where the initial contrast ratio is
                // high (ex. 13.0), the ratio passed in is that high ratio, and both the lighter and
                // darker ratios fail to pass it. Observed with Tonal Spot's on-primary-container
                // turning black momentarily between high and max contrast in light mode.
                val negligibleDifference =
                    Math.abs(lighterRatio - darkerRatio) < 0.1 &&
                        lighterRatio < ratio &&
                        darkerRatio < ratio
                return if (lighterRatio >= ratio || lighterRatio >= darkerRatio || negligibleDifference) {
                    lighterTone
                } else {
                    darkerTone
                }
            }
            return if (darkerRatio >= ratio || darkerRatio >= lighterRatio) darkerTone else lighterTone
        }

        /** Adjusts a tone down such that white has 4.5 contrast, if reasonably close to it. */
        fun enableLightForeground(tone: Double): Double {
            if (tonePrefersLightForeground(tone) && !toneAllowsLightForeground(tone)) {
                return 49.0
            }
            return tone
        }

        /**
         * People prefer white foregrounds on ~T60-70. Observed over time, and by Andrew Somers
         * during research for APCA.
         *
         * T60 is used so that skipping down to T49 to ensure light foregrounds creates the smallest
         * discontinuity possible. `tertiaryContainer` in the dark monochrome scheme requires a tone
         * of 60, so it must not be adjusted; 60 is therefore excluded here.
         */
        fun tonePrefersLightForeground(tone: Double): Boolean = Math.round(tone).toInt() < 60

        /** Tones less than ~T50 always permit white at 4.5 contrast. */
        fun toneAllowsLightForeground(tone: Double): Boolean = Math.round(tone).toInt() <= 49

        fun getInitialToneFromBackground(
            background: ((DynamicScheme) -> DynamicColor?)?
        ): (DynamicScheme) -> Double {
            if (background == null) {
                return { 50.0 }
            }
            return { s -> background.invoke(s)?.getTone(s) ?: 50.0 }
        }
    }
}
