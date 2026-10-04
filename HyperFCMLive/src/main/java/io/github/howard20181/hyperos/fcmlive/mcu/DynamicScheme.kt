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
 * Provides the settings a dynamic color is resolved against, plus the six tonal palettes.
 *
 * Requires: a source color, a variant, whether it is dark, a contrast level and a spec version.
 * The spec version is **resolved on construction** — a scheme never claims a version that does not
 * publish rules for its variant.
 */
internal class DynamicScheme(
    /** The source color of the scheme, in HCT. */
    val sourceColorHct: Hct,
    /** The variant of the scheme. */
    val variant: Scheme.Variant,
    /** Whether the scheme is dark mode. */
    val isDark: Boolean,
    /**
     * From -1 to 1. -1 is minimum contrast, 0 is standard (the design as spec'd), 1 is maximum
     * contrast.
     */
    val contrastLevel: Double,
    /** The platform the scheme is intended for. */
    val platform: Platform,
    requestedSpecVersion: Scheme.Spec,
    val primaryPalette: TonalPalette,
    val secondaryPalette: TonalPalette,
    val tertiaryPalette: TonalPalette,
    val neutralPalette: TonalPalette,
    val neutralVariantPalette: TonalPalette,
    val errorPalette: TonalPalette
) {

    /** The source color of the scheme in ARGB format. */
    val sourceColorArgb: Int = sourceColorHct.toInt()

    /**
     * The spec version actually in force. See [maybeFallbackSpecVersion] for why a requested
     * version can be reduced.
     */
    val specVersion: Scheme.Spec = maybeFallbackSpecVersion(requestedSpecVersion, variant)

    /** The platform on which this scheme is intended to be used. */
    enum class Platform {
        PHONE,
        WATCH
    }

    fun getHct(dynamicColor: DynamicColor): Hct = dynamicColor.getHct(this)

    fun getArgb(dynamicColor: DynamicColor): Int = dynamicColor.getArgb(this)

    companion object {

        /**
         * The same scheme, re-solved for another mode and contrast level.
         *
         * Used by the 2025 spec to ask "what tone would this role have in light mode at standard
         * contrast?" while resolving a role in *this* scheme — that is how the fixed colors stay
         * fixed. Note the resolved [specVersion] is what gets re-requested, so a scheme that fell
         * back to 2021 does not accidentally acquire 2025 rules.
         */
        fun from(other: DynamicScheme, isDark: Boolean, contrastLevel: Double): DynamicScheme =
            DynamicScheme(
                other.sourceColorHct,
                other.variant,
                isDark,
                contrastLevel,
                other.platform,
                other.specVersion,
                other.primaryPalette,
                other.secondaryPalette,
                other.tertiaryPalette,
                other.neutralPalette,
                other.neutralVariantPalette,
                other.errorPalette
            )

        /**
         * Returns the spec version to use for the given variant.
         *
         * Upstream also downgrades a requested 2026 to 2025 for the four expressive variants; this
         * port publishes 2021 and 2025 only, so the rule reduces to "the four expressive variants
         * keep the requested version, everything else is 2021".
         */
        fun maybeFallbackSpecVersion(
            specVersion: Scheme.Spec,
            variant: Scheme.Variant
        ): Scheme.Spec {
            if (variant.supportsExpressive2025) {
                return specVersion
            }
            return Scheme.Spec.SPEC_2021
        }

        /**
         * Returns a new hue based on a piecewise function and the source color's hue.
         *
         * For example, for `hueBreakpoints = {0, 101, 210, 360}`, `hues = {26, 39, 28}` the result
         * is 26 for `0 <= hue < 101`, 39 for `101 <= hue < 210`, 28 for `210 <= hue < 360`; when no
         * condition matches the source hue is returned.
         */
        fun getPiecewiseValue(
            sourceColorHct: Hct,
            hueBreakpoints: DoubleArray,
            hues: DoubleArray
        ): Double {
            val size = Math.min(hueBreakpoints.size - 1, hues.size)
            val sourceHue = sourceColorHct.hue
            for (i in 0 until size) {
                if (sourceHue >= hueBreakpoints[i] && sourceHue < hueBreakpoints[i + 1]) {
                    return MathUtils.sanitizeDegreesDouble(hues[i])
                }
            }
            // No condition matched; return the source value.
            return sourceHue
        }

        /**
         * Returns a shifted hue based on a piecewise function and the source color's hue.
         *
         * For example, for `hueBreakpoints = {0, 101, 210, 360}`,
         * `rotations = {26, -39, 28}` the result is `hue + 26` for `0 <= hue < 101`, `hue - 39` for
         * `101 <= hue < 210`, `hue + 28` for `210 <= hue < 360`.
         */
        fun getRotatedHue(
            sourceColorHct: Hct,
            hueBreakpoints: DoubleArray,
            rotations: DoubleArray
        ): Double {
            var rotation = getPiecewiseValue(sourceColorHct, hueBreakpoints, rotations)
            if (Math.min(hueBreakpoints.size - 1, rotations.size) <= 0) {
                // No condition matched; return the source hue.
                rotation = 0.0
            }
            return MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + rotation)
        }
    }
}
