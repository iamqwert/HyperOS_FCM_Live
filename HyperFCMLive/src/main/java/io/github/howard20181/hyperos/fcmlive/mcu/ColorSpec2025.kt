/*
 * Copyright 2025 Google LLC
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

import kotlin.math.max
import kotlin.math.min

/**
 * The 2025 spec — Material 3 Expressive.
 *
 * The 2025 rules **replace** the 2021 ones for the four styles the spec covers, and the 2021 rules
 * stay in force for everything else. That is expressed with [DynamicColor.Builder
 * .extendSpecVersion]: each role below keeps `super.<role>()` (which is the 2021 definition) and
 * hands over its own definition tagged with [Scheme.Spec.SPEC_2025]. A color built this way resolves
 * per scheme, so one [DynamicColor] object correctly serves both versions.
 *
 * Two things change shape compared with 2021, and both are worth knowing before reading down:
 *  - **Tones are computed, not tabulated.** Where 2021 says "T30 in dark", 2025 asks the palette
 *    for the highest (`tMaxC`) or lowest (`tMinC`) tone that can actually carry its chroma. That is
 *    why the same role can land on a different tone for different seeds.
 *  - **A palette entry carries a chroma multiplier.** The neutral family gets more saturated as the
 *    surfaces get closer together, so `getHct` multiplies the palette chroma for exactly the roles
 *    that opt in. See [getHct].
 */
internal class ColorSpec2025 : ColorSpec2021() {

    // ----------------------------------------------------------------- surfaces

    override fun background(): DynamicColor {
        // Remapped to surface for the 2025 spec.
        val color2025 = surface().toBuilder().setName("background").build()
        return super.background().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onBackground(): DynamicColor {
        // Remapped to onSurface for the 2025 spec.
        val color2025Builder = onSurface().toBuilder().setName("on_background")
        color2025Builder.setTone { s ->
            if (s.platform == DynamicScheme.Platform.WATCH) 100.0 else onSurface().getTone(s)
        }
        return super.onBackground().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025Builder.build())
            .build()
    }

    override fun surface(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("surface")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) {
                        4.0
                    } else if (Hct.isYellow(s.neutralPalette.hue)) {
                        99.0
                    } else if (s.variant == Scheme.Variant.VIBRANT) {
                        97.0
                    } else {
                        98.0
                    }
                } else {
                    0.0
                }
            }
            .setIsBackground(true)
            .build()
        return super.surface().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2025, color2025).build()
    }

    override fun surfaceDim(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("surface_dim")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.isDark) {
                    4.0
                } else if (Hct.isYellow(s.neutralPalette.hue)) {
                    90.0
                } else if (s.variant == Scheme.Variant.VIBRANT) {
                    85.0
                } else {
                    87.0
                }
            }
            .setIsBackground(true)
            .setChromaMultiplier { s ->
                if (!s.isDark) {
                    when (s.variant) {
                        Scheme.Variant.NEUTRAL -> 2.5
                        Scheme.Variant.TONAL_SPOT -> 1.7
                        Scheme.Variant.EXPRESSIVE ->
                            if (Hct.isYellow(s.neutralPalette.hue)) 2.7 else 1.75
                        Scheme.Variant.VIBRANT -> 1.36
                        else -> 1.0
                    }
                } else {
                    1.0
                }
            }
            .build()
        return super.surfaceDim().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun surfaceBright(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("surface_bright")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.isDark) {
                    18.0
                } else if (Hct.isYellow(s.neutralPalette.hue)) {
                    99.0
                } else if (s.variant == Scheme.Variant.VIBRANT) {
                    97.0
                } else {
                    98.0
                }
            }
            .setIsBackground(true)
            .setChromaMultiplier { s ->
                if (s.isDark) {
                    when (s.variant) {
                        Scheme.Variant.NEUTRAL -> 2.5
                        Scheme.Variant.TONAL_SPOT -> 1.7
                        Scheme.Variant.EXPRESSIVE ->
                            if (Hct.isYellow(s.neutralPalette.hue)) 2.7 else 1.75
                        Scheme.Variant.VIBRANT -> 1.36
                        else -> 1.0
                    }
                } else {
                    1.0
                }
            }
            .build()
        return super.surfaceBright().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun surfaceContainerLowest(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("surface_container_lowest")
            .setPalette { s -> s.neutralPalette }
            .setTone { s -> if (s.isDark) 0.0 else 100.0 }
            .setIsBackground(true)
            .build()
        return super.surfaceContainerLowest().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun surfaceContainerLow(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("surface_container_low")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) {
                        6.0
                    } else if (Hct.isYellow(s.neutralPalette.hue)) {
                        98.0
                    } else if (s.variant == Scheme.Variant.VIBRANT) {
                        95.0
                    } else {
                        96.0
                    }
                } else {
                    15.0
                }
            }
            .setIsBackground(true)
            .setChromaMultiplier { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    when (s.variant) {
                        Scheme.Variant.NEUTRAL -> 1.3
                        Scheme.Variant.TONAL_SPOT -> 1.25
                        Scheme.Variant.EXPRESSIVE ->
                            if (Hct.isYellow(s.neutralPalette.hue)) 1.3 else 1.15
                        Scheme.Variant.VIBRANT -> 1.08
                        else -> 1.0
                    }
                } else {
                    1.0
                }
            }
            .build()
        return super.surfaceContainerLow().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun surfaceContainer(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("surface_container")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) {
                        9.0
                    } else if (Hct.isYellow(s.neutralPalette.hue)) {
                        96.0
                    } else if (s.variant == Scheme.Variant.VIBRANT) {
                        92.0
                    } else {
                        94.0
                    }
                } else {
                    20.0
                }
            }
            .setIsBackground(true)
            .setChromaMultiplier { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    when (s.variant) {
                        Scheme.Variant.NEUTRAL -> 1.6
                        Scheme.Variant.TONAL_SPOT -> 1.4
                        Scheme.Variant.EXPRESSIVE ->
                            if (Hct.isYellow(s.neutralPalette.hue)) 1.6 else 1.3
                        Scheme.Variant.VIBRANT -> 1.15
                        else -> 1.0
                    }
                } else {
                    1.0
                }
            }
            .build()
        return super.surfaceContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun surfaceContainerHigh(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("surface_container_high")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) {
                        12.0
                    } else if (Hct.isYellow(s.neutralPalette.hue)) {
                        94.0
                    } else if (s.variant == Scheme.Variant.VIBRANT) {
                        90.0
                    } else {
                        92.0
                    }
                } else {
                    25.0
                }
            }
            .setIsBackground(true)
            .setChromaMultiplier { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    when (s.variant) {
                        Scheme.Variant.NEUTRAL -> 1.9
                        Scheme.Variant.TONAL_SPOT -> 1.5
                        Scheme.Variant.EXPRESSIVE ->
                            if (Hct.isYellow(s.neutralPalette.hue)) 1.95 else 1.45
                        Scheme.Variant.VIBRANT -> 1.22
                        else -> 1.0
                    }
                } else {
                    1.0
                }
            }
            .build()
        return super.surfaceContainerHigh().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun surfaceContainerHighest(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("surface_container_highest")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.isDark) {
                    15.0
                } else if (Hct.isYellow(s.neutralPalette.hue)) {
                    92.0
                } else if (s.variant == Scheme.Variant.VIBRANT) {
                    88.0
                } else {
                    90.0
                }
            }
            .setIsBackground(true)
            .setChromaMultiplier { s ->
                when (s.variant) {
                    Scheme.Variant.NEUTRAL -> 2.2
                    Scheme.Variant.TONAL_SPOT -> 1.7
                    Scheme.Variant.EXPRESSIVE ->
                        if (Hct.isYellow(s.neutralPalette.hue)) 2.3 else 1.6
                    Scheme.Variant.VIBRANT -> 1.29
                    else -> 1.0
                }
            }
            .build()
        return super.surfaceContainerHighest().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onSurface(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_surface")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.VIBRANT) {
                    tMaxC(s.neutralPalette, 0.0, 100.0, 1.1)
                } else {
                    DynamicColor.getInitialToneFromBackground { scheme ->
                        if (scheme.platform == DynamicScheme.Platform.PHONE) {
                            if (scheme.isDark) surfaceBright() else surfaceDim()
                        } else {
                            surfaceContainerHigh()
                        }
                    }.invoke(s)
                }
            }
            .setChromaMultiplier { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    when (s.variant) {
                        Scheme.Variant.NEUTRAL -> 2.2
                        Scheme.Variant.TONAL_SPOT -> 1.7
                        Scheme.Variant.EXPRESSIVE ->
                            if (Hct.isYellow(s.neutralPalette.hue)) {
                                if (s.isDark) 3.0 else 2.3
                            } else {
                                1.6
                            }
                        else -> 1.0
                    }
                } else {
                    1.0
                }
            }
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    surfaceContainerHigh()
                }
            }
            .setContrastCurve { s ->
                if (s.isDark && s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(11.0)
                } else {
                    getContrastCurve(9.0)
                }
            }
            .build()
        return super.onSurface().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun surfaceVariant(): DynamicColor {
        // Remapped to surfaceContainerHighest for the 2025 spec.
        val color2025 = surfaceContainerHighest().toBuilder().setName("surface_variant").build()
        return super.surfaceVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onSurfaceVariant(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_surface_variant")
            .setPalette { s -> s.neutralPalette }
            .setChromaMultiplier { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    when (s.variant) {
                        Scheme.Variant.NEUTRAL -> 2.2
                        Scheme.Variant.TONAL_SPOT -> 1.7
                        Scheme.Variant.EXPRESSIVE ->
                            if (Hct.isYellow(s.neutralPalette.hue)) {
                                if (s.isDark) 3.0 else 2.3
                            } else {
                                1.6
                            }
                        else -> 1.0
                    }
                } else {
                    1.0
                }
            }
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    surfaceContainerHigh()
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) getContrastCurve(6.0) else getContrastCurve(4.5)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.onSurfaceVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun inverseSurface(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("inverse_surface")
            .setPalette { s -> s.neutralPalette }
            .setTone { s -> if (s.isDark) 98.0 else 4.0 }
            .setIsBackground(true)
            .build()
        return super.inverseSurface().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun inverseOnSurface(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("inverse_on_surface")
            .setPalette { s -> s.neutralPalette }
            .setBackground { inverseSurface() }
            .setContrastCurve { getContrastCurve(7.0) }
            .build()
        return super.inverseOnSurface().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun outline(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("outline")
            .setPalette { s -> s.neutralPalette }
            .setChromaMultiplier { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    when (s.variant) {
                        Scheme.Variant.NEUTRAL -> 2.2
                        Scheme.Variant.TONAL_SPOT -> 1.7
                        Scheme.Variant.EXPRESSIVE ->
                            if (Hct.isYellow(s.neutralPalette.hue)) {
                                if (s.isDark) 3.0 else 2.3
                            } else {
                                1.6
                            }
                        else -> 1.0
                    }
                } else {
                    1.0
                }
            }
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    surfaceContainerHigh()
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(3.0)
                } else {
                    getContrastCurve(4.5)
                }
            }
            .build()
        return super.outline().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2025, color2025).build()
    }

    override fun outlineVariant(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("outline_variant")
            .setPalette { s -> s.neutralPalette }
            .setChromaMultiplier { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    when (s.variant) {
                        Scheme.Variant.NEUTRAL -> 2.2
                        Scheme.Variant.TONAL_SPOT -> 1.7
                        Scheme.Variant.EXPRESSIVE ->
                            if (Hct.isYellow(s.neutralPalette.hue)) {
                                if (s.isDark) 3.0 else 2.3
                            } else {
                                1.6
                            }
                        else -> 1.0
                    }
                } else {
                    1.0
                }
            }
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    surfaceContainerHigh()
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(1.5)
                } else {
                    getContrastCurve(3.0)
                }
            }
            .build()
        return super.outlineVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun surfaceTint(): DynamicColor {
        // Remapped to primary for the 2025 spec.
        val color2025 = primary().toBuilder().setName("surface_tint").build()
        return super.surfaceTint().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    // ---------------------------------------------------------------- primaries

    override fun primary(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("primary")
            .setPalette { s -> s.primaryPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.NEUTRAL) {
                    if (s.platform == DynamicScheme.Platform.PHONE) {
                        if (s.isDark) 80.0 else 40.0
                    } else {
                        90.0
                    }
                } else if (s.variant == Scheme.Variant.TONAL_SPOT) {
                    if (s.platform == DynamicScheme.Platform.PHONE) {
                        if (s.isDark) 80.0 else tMaxC(s.primaryPalette)
                    } else {
                        tMaxC(s.primaryPalette, 0.0, 90.0)
                    }
                } else if (s.variant == Scheme.Variant.EXPRESSIVE) {
                    if (s.platform == DynamicScheme.Platform.PHONE) {
                        val upperBound = if (s.isDark) {
                            if (Hct.isCyan(s.primaryPalette.hue)) 88.0 else 98.0
                        } else {
                            if (Hct.isYellow(s.primaryPalette.hue)) 25.0 else 98.0
                        }
                        tMaxC(s.primaryPalette, 0.0, upperBound)
                    } else { // WATCH
                        tMaxC(s.primaryPalette)
                    }
                } else { // VIBRANT
                    if (s.platform == DynamicScheme.Platform.PHONE) {
                        tMaxC(
                            s.primaryPalette,
                            0.0,
                            if (Hct.isCyan(s.primaryPalette.hue)) 88.0 else 98.0
                        )
                    } else { // WATCH
                        tMaxC(s.primaryPalette)
                    }
                }
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    surfaceContainerHigh()
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(4.5)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .setToneDeltaPair { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    ToneDeltaPair(
                        primaryContainer(), primary(), 5.0,
                        TonePolarity.RELATIVE_LIGHTER, DeltaConstraint.FARTHER
                    )
                } else {
                    null
                }
            }
            .build()
        return super.primary().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2025, color2025).build()
    }

    override fun primaryDim(): DynamicColor = DynamicColor.Builder()
        .setName("primary_dim")
        .setPalette { s -> s.primaryPalette }
        .setTone { s ->
            if (s.variant == Scheme.Variant.NEUTRAL) {
                85.0
            } else if (s.variant == Scheme.Variant.TONAL_SPOT) {
                tMaxC(s.primaryPalette, 0.0, 90.0)
            } else {
                tMaxC(s.primaryPalette)
            }
        }
        .setIsBackground(true)
        .setBackground { surfaceContainerHigh() }
        .setContrastCurve { getContrastCurve(4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(primaryDim(), primary(), 5.0, TonePolarity.DARKER, DeltaConstraint.FARTHER)
        }
        .build()

    override fun onPrimary(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_primary")
            .setPalette { s -> s.primaryPalette }
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) primary() else primaryDim()
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(6.0)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.onPrimary().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun primaryContainer(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("primary_container")
            .setPalette { s -> s.primaryPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    30.0
                } else if (s.variant == Scheme.Variant.NEUTRAL) {
                    if (s.isDark) 30.0 else 90.0
                } else if (s.variant == Scheme.Variant.TONAL_SPOT) {
                    if (s.isDark) {
                        tMinC(s.primaryPalette, 35.0, 93.0)
                    } else {
                        tMaxC(s.primaryPalette, 0.0, 90.0)
                    }
                } else if (s.variant == Scheme.Variant.EXPRESSIVE) {
                    if (s.isDark) {
                        tMinC(s.primaryPalette, 30.0, 93.0)
                    } else {
                        tMaxC(
                            s.primaryPalette,
                            78.0,
                            if (Hct.isCyan(s.primaryPalette.hue)) 88.0 else 90.0
                        )
                    }
                } else { // VIBRANT
                    if (s.isDark) {
                        tMinC(s.primaryPalette, 66.0, 93.0)
                    } else {
                        tMaxC(
                            s.primaryPalette,
                            66.0,
                            if (Hct.isCyan(s.primaryPalette.hue)) 88.0 else 93.0
                        )
                    }
                }
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    null
                }
            }
            .setToneDeltaPair { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    ToneDeltaPair(
                        primaryContainer(), primaryDim(), 10.0,
                        TonePolarity.DARKER, DeltaConstraint.FARTHER
                    )
                } else {
                    null
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE && s.contrastLevel > 0) {
                    getContrastCurve(1.5)
                } else {
                    null
                }
            }
            .build()
        return super.primaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onPrimaryContainer(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_primary_container")
            .setPalette { s -> s.primaryPalette }
            .setBackground { primaryContainer() }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(6.0)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.onPrimaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun inversePrimary(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("inverse_primary")
            .setPalette { s -> s.primaryPalette }
            .setTone { s -> tMaxC(s.primaryPalette) }
            .setBackground { inverseSurface() }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(6.0)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.inversePrimary().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    // -------------------------------------------------------------- secondaries

    override fun secondary(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("secondary")
            .setPalette { s -> s.secondaryPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    if (s.variant == Scheme.Variant.NEUTRAL) 90.0 else tMaxC(s.secondaryPalette, 0.0, 90.0)
                } else if (s.variant == Scheme.Variant.NEUTRAL) {
                    if (s.isDark) tMinC(s.secondaryPalette, 0.0, 98.0) else tMaxC(s.secondaryPalette)
                } else if (s.variant == Scheme.Variant.VIBRANT) {
                    tMaxC(s.secondaryPalette, 0.0, if (s.isDark) 90.0 else 98.0)
                } else { // EXPRESSIVE and TONAL_SPOT
                    if (s.isDark) 80.0 else tMaxC(s.secondaryPalette)
                }
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    surfaceContainerHigh()
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(4.5)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .setToneDeltaPair { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    ToneDeltaPair(
                        secondaryContainer(), secondary(), 5.0,
                        TonePolarity.RELATIVE_LIGHTER, DeltaConstraint.FARTHER
                    )
                } else {
                    null
                }
            }
            .build()
        return super.secondary().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun secondaryDim(): DynamicColor = DynamicColor.Builder()
        .setName("secondary_dim")
        .setPalette { s -> s.secondaryPalette }
        .setTone { s ->
            if (s.variant == Scheme.Variant.NEUTRAL) {
                85.0
            } else {
                tMaxC(s.secondaryPalette, 0.0, 90.0)
            }
        }
        .setIsBackground(true)
        .setBackground { surfaceContainerHigh() }
        .setContrastCurve { getContrastCurve(4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(
                secondaryDim(), secondary(), 5.0, TonePolarity.DARKER, DeltaConstraint.FARTHER
            )
        }
        .build()

    override fun onSecondary(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_secondary")
            .setPalette { s -> s.secondaryPalette }
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) secondary() else secondaryDim()
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(6.0)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.onSecondary().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun secondaryContainer(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("secondary_container")
            .setPalette { s -> s.secondaryPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    30.0
                } else if (s.variant == Scheme.Variant.VIBRANT) {
                    if (s.isDark) {
                        tMinC(s.secondaryPalette, 30.0, 40.0)
                    } else {
                        tMaxC(s.secondaryPalette, 84.0, 90.0)
                    }
                } else if (s.variant == Scheme.Variant.EXPRESSIVE) {
                    if (s.isDark) 15.0 else tMaxC(s.secondaryPalette, 90.0, 95.0)
                } else {
                    if (s.isDark) 25.0 else 90.0
                }
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    null
                }
            }
            .setToneDeltaPair { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    ToneDeltaPair(
                        secondaryContainer(), secondaryDim(), 10.0,
                        TonePolarity.DARKER, DeltaConstraint.FARTHER
                    )
                } else {
                    null
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE && s.contrastLevel > 0) {
                    getContrastCurve(1.5)
                } else {
                    null
                }
            }
            .build()
        return super.secondaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onSecondaryContainer(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_secondary_container")
            .setPalette { s -> s.secondaryPalette }
            .setBackground { secondaryContainer() }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(6.0)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.onSecondaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    // --------------------------------------------------------------- tertiaries

    override fun tertiary(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("tertiary")
            .setPalette { s -> s.tertiaryPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    if (s.variant == Scheme.Variant.TONAL_SPOT) {
                        tMaxC(s.tertiaryPalette, 0.0, 90.0)
                    } else {
                        tMaxC(s.tertiaryPalette)
                    }
                } else if (s.variant == Scheme.Variant.EXPRESSIVE ||
                    s.variant == Scheme.Variant.VIBRANT
                ) {
                    val upperBound = if (Hct.isCyan(s.tertiaryPalette.hue)) {
                        88.0
                    } else {
                        if (s.isDark) 98.0 else 100.0
                    }
                    tMaxC(s.tertiaryPalette, 0.0, upperBound)
                } else { // NEUTRAL and TONAL_SPOT
                    if (s.isDark) tMaxC(s.tertiaryPalette, 0.0, 98.0) else tMaxC(s.tertiaryPalette)
                }
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    surfaceContainerHigh()
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(4.5)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .setToneDeltaPair { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    ToneDeltaPair(
                        tertiaryContainer(), tertiary(), 5.0,
                        TonePolarity.RELATIVE_LIGHTER, DeltaConstraint.FARTHER
                    )
                } else {
                    null
                }
            }
            .build()
        return super.tertiary().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2025, color2025).build()
    }

    override fun tertiaryDim(): DynamicColor = DynamicColor.Builder()
        .setName("tertiary_dim")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { s ->
            if (s.variant == Scheme.Variant.TONAL_SPOT) {
                tMaxC(s.tertiaryPalette, 0.0, 90.0)
            } else {
                tMaxC(s.tertiaryPalette)
            }
        }
        .setIsBackground(true)
        .setBackground { surfaceContainerHigh() }
        .setContrastCurve { getContrastCurve(4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(
                tertiaryDim(), tertiary(), 5.0, TonePolarity.DARKER, DeltaConstraint.FARTHER
            )
        }
        .build()

    override fun onTertiary(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_tertiary")
            .setPalette { s -> s.tertiaryPalette }
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) tertiary() else tertiaryDim()
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(6.0)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.onTertiary().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun tertiaryContainer(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("tertiary_container")
            .setPalette { s -> s.tertiaryPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    if (s.variant == Scheme.Variant.TONAL_SPOT) {
                        tMaxC(s.tertiaryPalette, 0.0, 90.0)
                    } else {
                        tMaxC(s.tertiaryPalette)
                    }
                } else if (s.variant == Scheme.Variant.NEUTRAL) {
                    if (s.isDark) tMaxC(s.tertiaryPalette, 0.0, 93.0) else tMaxC(s.tertiaryPalette, 0.0, 96.0)
                } else if (s.variant == Scheme.Variant.TONAL_SPOT) {
                    tMaxC(s.tertiaryPalette, 0.0, if (s.isDark) 93.0 else 100.0)
                } else if (s.variant == Scheme.Variant.EXPRESSIVE) {
                    val upperBound = if (Hct.isCyan(s.tertiaryPalette.hue)) {
                        88.0
                    } else {
                        if (s.isDark) 93.0 else 100.0
                    }
                    tMaxC(s.tertiaryPalette, 75.0, upperBound)
                } else { // VIBRANT
                    if (s.isDark) {
                        tMaxC(s.tertiaryPalette, 0.0, 93.0)
                    } else {
                        tMaxC(s.tertiaryPalette, 72.0, 100.0)
                    }
                }
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    null
                }
            }
            .setToneDeltaPair { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    ToneDeltaPair(
                        tertiaryContainer(), tertiaryDim(), 10.0,
                        TonePolarity.DARKER, DeltaConstraint.FARTHER
                    )
                } else {
                    null
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE && s.contrastLevel > 0) {
                    getContrastCurve(1.5)
                } else {
                    null
                }
            }
            .build()
        return super.tertiaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onTertiaryContainer(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_tertiary_container")
            .setPalette { s -> s.tertiaryPalette }
            .setBackground { tertiaryContainer() }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(6.0)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.onTertiaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    // ------------------------------------------------------------------- errors

    override fun error(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("error")
            .setPalette { s -> s.errorPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) tMinC(s.errorPalette, 0.0, 98.0) else tMaxC(s.errorPalette)
                } else {
                    tMinC(s.errorPalette)
                }
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    surfaceContainerHigh()
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(4.5)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .setToneDeltaPair { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    ToneDeltaPair(
                        errorContainer(), error(), 5.0,
                        TonePolarity.RELATIVE_LIGHTER, DeltaConstraint.FARTHER
                    )
                } else {
                    null
                }
            }
            .build()
        return super.error().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2025, color2025).build()
    }

    override fun errorDim(): DynamicColor = DynamicColor.Builder()
        .setName("error_dim")
        .setPalette { s -> s.errorPalette }
        .setTone { s -> tMinC(s.errorPalette) }
        .setIsBackground(true)
        .setBackground { surfaceContainerHigh() }
        .setContrastCurve { getContrastCurve(4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(errorDim(), error(), 5.0, TonePolarity.DARKER, DeltaConstraint.FARTHER)
        }
        .build()

    override fun onError(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_error")
            .setPalette { s -> s.errorPalette }
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) error() else errorDim()
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(6.0)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.onError().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2025, color2025).build()
    }

    override fun errorContainer(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("error_container")
            .setPalette { s -> s.errorPalette }
            .setTone { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    30.0
                } else {
                    if (s.isDark) tMinC(s.errorPalette, 30.0, 93.0) else tMaxC(s.errorPalette, 0.0, 90.0)
                }
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    null
                }
            }
            .setToneDeltaPair { s ->
                if (s.platform == DynamicScheme.Platform.WATCH) {
                    ToneDeltaPair(
                        errorContainer(), errorDim(), 10.0,
                        TonePolarity.DARKER, DeltaConstraint.FARTHER
                    )
                } else {
                    null
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE && s.contrastLevel > 0) {
                    getContrastCurve(1.5)
                } else {
                    null
                }
            }
            .build()
        return super.errorContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onErrorContainer(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_error_container")
            .setPalette { s -> s.errorPalette }
            .setBackground { errorContainer() }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    getContrastCurve(4.5)
                } else {
                    getContrastCurve(7.0)
                }
            }
            .build()
        return super.onErrorContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    // ------------------------------------------------------------ primary fixed

    override fun primaryFixed(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("primary_fixed")
            .setPalette { s -> s.primaryPalette }
            .setTone { s ->
                val tempS = DynamicScheme.from(s, isDark = false, contrastLevel = 0.0)
                primaryContainer().getTone(tempS)
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    null
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE && s.contrastLevel > 0) {
                    getContrastCurve(1.5)
                } else {
                    null
                }
            }
            .build()
        return super.primaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun primaryFixedDim(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("primary_fixed_dim")
            .setPalette { s -> s.primaryPalette }
            .setTone { s -> primaryFixed().getTone(s) }
            .setIsBackground(true)
            .setToneDeltaPair {
                ToneDeltaPair(
                    primaryFixedDim(), primaryFixed(), 5.0,
                    TonePolarity.DARKER, DeltaConstraint.EXACT
                )
            }
            .build()
        return super.primaryFixedDim().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onPrimaryFixed(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_primary_fixed")
            .setPalette { s -> s.primaryPalette }
            .setBackground { primaryFixedDim() }
            .setContrastCurve { getContrastCurve(7.0) }
            .build()
        return super.onPrimaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onPrimaryFixedVariant(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_primary_fixed_variant")
            .setPalette { s -> s.primaryPalette }
            .setBackground { primaryFixedDim() }
            .setContrastCurve { getContrastCurve(4.5) }
            .build()
        return super.onPrimaryFixedVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    // ---------------------------------------------------------- secondary fixed

    override fun secondaryFixed(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("secondary_fixed")
            .setPalette { s -> s.secondaryPalette }
            .setTone { s ->
                val tempS = DynamicScheme.from(s, isDark = false, contrastLevel = 0.0)
                secondaryContainer().getTone(tempS)
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    null
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE && s.contrastLevel > 0) {
                    getContrastCurve(1.5)
                } else {
                    null
                }
            }
            .build()
        return super.secondaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun secondaryFixedDim(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("secondary_fixed_dim")
            .setPalette { s -> s.secondaryPalette }
            .setTone { s -> secondaryFixed().getTone(s) }
            .setIsBackground(true)
            .setToneDeltaPair {
                ToneDeltaPair(
                    secondaryFixedDim(), secondaryFixed(), 5.0,
                    TonePolarity.DARKER, DeltaConstraint.EXACT
                )
            }
            .build()
        return super.secondaryFixedDim().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onSecondaryFixed(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_secondary_fixed")
            .setPalette { s -> s.secondaryPalette }
            .setBackground { secondaryFixedDim() }
            .setContrastCurve { getContrastCurve(7.0) }
            .build()
        return super.onSecondaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onSecondaryFixedVariant(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_secondary_fixed_variant")
            .setPalette { s -> s.secondaryPalette }
            .setBackground { secondaryFixedDim() }
            .setContrastCurve { getContrastCurve(4.5) }
            .build()
        return super.onSecondaryFixedVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    // ----------------------------------------------------------- tertiary fixed

    override fun tertiaryFixed(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("tertiary_fixed")
            .setPalette { s -> s.tertiaryPalette }
            .setTone { s ->
                val tempS = DynamicScheme.from(s, isDark = false, contrastLevel = 0.0)
                tertiaryContainer().getTone(tempS)
            }
            .setIsBackground(true)
            .setBackground { s ->
                if (s.platform == DynamicScheme.Platform.PHONE) {
                    if (s.isDark) surfaceBright() else surfaceDim()
                } else {
                    null
                }
            }
            .setContrastCurve { s ->
                if (s.platform == DynamicScheme.Platform.PHONE && s.contrastLevel > 0) {
                    getContrastCurve(1.5)
                } else {
                    null
                }
            }
            .build()
        return super.tertiaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun tertiaryFixedDim(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("tertiary_fixed_dim")
            .setPalette { s -> s.tertiaryPalette }
            .setTone { s -> tertiaryFixed().getTone(s) }
            .setIsBackground(true)
            .setToneDeltaPair {
                ToneDeltaPair(
                    tertiaryFixedDim(), tertiaryFixed(), 5.0,
                    TonePolarity.DARKER, DeltaConstraint.EXACT
                )
            }
            .build()
        return super.tertiaryFixedDim().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onTertiaryFixed(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_tertiary_fixed")
            .setPalette { s -> s.tertiaryPalette }
            .setBackground { tertiaryFixedDim() }
            .setContrastCurve { getContrastCurve(7.0) }
            .build()
        return super.onTertiaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun onTertiaryFixedVariant(): DynamicColor {
        val color2025 = DynamicColor.Builder()
            .setName("on_tertiary_fixed_variant")
            .setPalette { s -> s.tertiaryPalette }
            .setBackground { tertiaryFixedDim() }
            .setContrastCurve { getContrastCurve(4.5) }
            .build()
        return super.onTertiaryFixedVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    // -------------------------------------------------------- Android-only roles

    override fun controlActivated(): DynamicColor {
        // Remapped to primaryContainer for the 2025 spec.
        val color2025 = primaryContainer().toBuilder().setName("control_activated").build()
        return super.controlActivated().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun controlNormal(): DynamicColor {
        // Remapped to onSurfaceVariant for the 2025 spec.
        val color2025 = onSurfaceVariant().toBuilder().setName("control_normal").build()
        return super.controlNormal().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    override fun textPrimaryInverse(): DynamicColor {
        // Remapped to inverseOnSurface for the 2025 spec.
        val color2025 = inverseOnSurface().toBuilder().setName("text_primary_inverse").build()
        return super.textPrimaryInverse().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2025, color2025)
            .build()
    }

    // -------------------------------------------------------------------- other

    /**
     * Finds the tone that can carry the most chroma near [tone], searching in the direction
     * [byDecreasingTone] indicates, and stopping as soon as more tone stops buying more chroma.
     *
     * This is the 2025 answer to 2021's [ColorSpec2021.findDesiredChromaByTone], but the goal is
     * the opposite: 2021 tries to *reach* a requested chroma, 2025 tries to *maximise* it.
     */
    private fun findBestToneForChroma(
        hue: Double,
        chroma: Double,
        tone: Double,
        byDecreasingTone: Boolean
    ): Double {
        var answer = tone
        var bestCandidate = Hct.from(hue, chroma, answer)
        var t = tone
        while (bestCandidate.chroma < chroma) {
            if (t < 0.0 || t > 100.0) {
                break
            }
            t += if (byDecreasingTone) -1.0 else 1.0
            val newCandidate = Hct.from(hue, chroma, t)
            if (bestCandidate.chroma < newCandidate.chroma) {
                bestCandidate = newCandidate
                answer = t
            }
        }
        return answer
    }

    private fun tMaxC(palette: TonalPalette): Double = tMaxC(palette, 0.0, 100.0)

    private fun tMaxC(palette: TonalPalette, lowerBound: Double, upperBound: Double): Double =
        tMaxC(palette, lowerBound, upperBound, 1.0)

    private fun tMaxC(
        palette: TonalPalette,
        lowerBound: Double,
        upperBound: Double,
        chromaMultiplier: Double
    ): Double {
        val answer = findBestToneForChroma(
            palette.hue, palette.chroma * chromaMultiplier, 100.0, true
        )
        return MathUtils.clampDouble(lowerBound, upperBound, answer)
    }

    private fun tMinC(palette: TonalPalette): Double = tMinC(palette, 0.0, 100.0)

    private fun tMinC(palette: TonalPalette, lowerBound: Double, upperBound: Double): Double {
        val answer = findBestToneForChroma(palette.hue, palette.chroma, 0.0, false)
        return MathUtils.clampDouble(lowerBound, upperBound, answer)
    }

    /**
     * The four-point contrast curve for a role that only needs one number, because the 2025 spec
     * changes contrast in whole steps rather than linearly.
     *
     * The table is exhaustive on purpose: a value outside it means a caller invented a level, and
     * silently interpolating would hide that.
     */
    private fun getContrastCurve(defaultContrast: Double): ContrastCurve = when (defaultContrast) {
        1.5 -> ContrastCurve(1.5, 1.5, 3.0, 5.5)
        3.0 -> ContrastCurve(3.0, 3.0, 4.5, 7.0)
        4.5 -> ContrastCurve(4.5, 4.5, 7.0, 11.0)
        6.0 -> ContrastCurve(6.0, 6.0, 7.0, 11.0)
        7.0 -> ContrastCurve(7.0, 7.0, 11.0, 21.0)
        9.0 -> ContrastCurve(9.0, 9.0, 11.0, 21.0)
        11.0 -> ContrastCurve(11.0, 11.0, 21.0, 21.0)
        21.0 -> ContrastCurve(21.0, 21.0, 21.0, 21.0)
        else -> ContrastCurve(defaultContrast, defaultContrast, 7.0, 21.0)
    }

    // ------------------------------------------------------ color value solving

    /**
     * Same idea as the 2021 resolution, plus the chroma multiplier.
     *
     * The 2025 rules deliberately put more saturated neutrals behind the same neutrals, so a role
     * asks for the palette's chroma scaled by a factor. The multiplier is applied *after* the tone
     * is known, and the resulting color is rebuilt from hue + scaled chroma — it is not a filter on
     * the final RGB.
     */
    override fun getHct(scheme: DynamicScheme, color: DynamicColor): Hct {
        val palette = color.palette!!.invoke(scheme)
        val tone = getTone(scheme, color)
        val chromaMultiplier = color.chromaMultiplier?.invoke(scheme) ?: 1.0
        if (chromaMultiplier == 1.0) {
            return palette.getHct(tone)
        }

        val chroma = palette.chroma * chromaMultiplier
        if (tone == 99.0 && Hct.isYellow(palette.hue)) {
            return TonalPalette.fromHueAndChroma(palette.hue, chroma).getHct(tone)
        }
        return Hct.from(palette.hue, chroma, tone)
    }

    /**
     * The 2025 tone solver.
     *
     * It differs from 2021 in three ways that all matter:
     *  - **The tone-delta pair is solved first**, against the reference role's *resolved* tone
     *    (2021 solves both roles against their own backgrounds and then reconciles). The
     *    [DeltaConstraint] decides whether the distance is exact, a floor, or a ceiling.
     *  - **The awkward zone is handled once, generically** — any background role that lands in
     *    T50-59 is pushed out, except `*_fixed_dim`, whose whole job is to be a dim accent.
     *  - **Decreasing contrast only relaxes when asked.** `contrastLevel >= 0` short-circuits the
     *    contrast solve, so a role that already passes is left alone.
     */
    override fun getTone(scheme: DynamicScheme, color: DynamicColor): Double {
        val toneDeltaPair = color.toneDeltaPair?.invoke(scheme)

        // Case 0: tone delta pair.
        if (toneDeltaPair != null) {
            val roleA = toneDeltaPair.roleA
            val roleB = toneDeltaPair.roleB
            val polarity = toneDeltaPair.polarity
            val constraint = toneDeltaPair.constraint
            val absoluteDelta =
                if (polarity == TonePolarity.DARKER ||
                    (polarity == TonePolarity.RELATIVE_LIGHTER && scheme.isDark) ||
                    (polarity == TonePolarity.RELATIVE_DARKER && !scheme.isDark)
                ) {
                    -toneDeltaPair.delta
                } else {
                    toneDeltaPair.delta
                }

            val amRoleA = color.name == roleA.name
            val selfRole = if (amRoleA) roleA else roleB
            val referenceRole = if (amRoleA) roleB else roleA
            var selfTone = selfRole.tone(scheme)
            val referenceTone = referenceRole.getTone(scheme)
            val relativeDelta = absoluteDelta * (if (amRoleA) 1.0 else -1.0)

            when (constraint) {
                DeltaConstraint.EXACT ->
                    selfTone = MathUtils.clampDouble(0.0, 100.0, referenceTone + relativeDelta)
                DeltaConstraint.NEARER ->
                    selfTone = if (relativeDelta > 0) {
                        MathUtils.clampDouble(
                            0.0,
                            100.0,
                            MathUtils.clampDouble(
                                referenceTone, referenceTone + relativeDelta, selfTone
                            )
                        )
                    } else {
                        MathUtils.clampDouble(
                            0.0,
                            100.0,
                            MathUtils.clampDouble(
                                referenceTone + relativeDelta, referenceTone, selfTone
                            )
                        )
                    }
                DeltaConstraint.FARTHER ->
                    selfTone = if (relativeDelta > 0) {
                        MathUtils.clampDouble(referenceTone + relativeDelta, 100.0, selfTone)
                    } else {
                        MathUtils.clampDouble(0.0, referenceTone + relativeDelta, selfTone)
                    }
            }

            val background = color.background
            val contrastCurve = color.contrastCurve
            if (background != null && contrastCurve != null) {
                val bg = background.invoke(scheme)
                val curve = contrastCurve.invoke(scheme)
                if (bg != null && curve != null) {
                    val bgTone = bg.getTone(scheme)
                    val selfContrast = curve.get(scheme.contrastLevel)
                    selfTone = if (Contrast.ratioOfTones(bgTone, selfTone) >= selfContrast &&
                        scheme.contrastLevel >= 0
                    ) {
                        selfTone
                    } else {
                        DynamicColor.foregroundTone(bgTone, selfContrast)
                    }
                }
            }

            // Avoid the awkward tones for background colors, including the accent fixed colors.
            // Accent fixed dim colors should not be adjusted.
            if (color.isBackground && !color.name.endsWith("_fixed_dim")) {
                selfTone = if (selfTone >= 57.0) {
                    MathUtils.clampDouble(65.0, 100.0, selfTone)
                } else {
                    MathUtils.clampDouble(0.0, 49.0, selfTone)
                }
            }

            return selfTone
        }

        // Case 1: no tone delta pair; just solve for itself.
        var answer = color.tone(scheme)

        val background = color.background
        val contrastCurve = color.contrastCurve
        if (background == null ||
            background.invoke(scheme) == null ||
            contrastCurve == null ||
            contrastCurve.invoke(scheme) == null
        ) {
            return answer // No adjustment for colors with no background.
        }

        val bgTone = background.invoke(scheme)!!.getTone(scheme)
        val desiredRatio = contrastCurve.invoke(scheme)!!.get(scheme.contrastLevel)

        // Recalculate from the desired contrast ratio when the current one is not enough, or when
        // the requested contrast level is decreasing (which is below zero).
        answer = if (Contrast.ratioOfTones(bgTone, answer) >= desiredRatio &&
            scheme.contrastLevel >= 0
        ) {
            answer
        } else {
            DynamicColor.foregroundTone(bgTone, desiredRatio)
        }

        if (color.isBackground && !color.name.endsWith("_fixed_dim")) {
            answer = if (answer >= 57.0) {
                MathUtils.clampDouble(65.0, 100.0, answer)
            } else {
                MathUtils.clampDouble(0.0, 49.0, answer)
            }
        }

        val secondBackground = color.secondBackground
        if (secondBackground == null || secondBackground.invoke(scheme) == null) {
            return answer
        }

        // Case 2: adjust for dual backgrounds.
        val bgTone2 = secondBackground.invoke(scheme)!!.getTone(scheme)
        val upper = max(bgTone, bgTone2)
        val lower = min(bgTone, bgTone2)

        if (Contrast.ratioOfTones(upper, answer) >= desiredRatio &&
            Contrast.ratioOfTones(lower, answer) >= desiredRatio
        ) {
            return answer
        }

        // The darkest light tone that satisfies the desired ratio, or -1 if unreachable.
        val lightOption = Contrast.lighter(upper, desiredRatio)
        // The lightest dark tone that satisfies the desired ratio, or -1 if unreachable.
        val darkOption = Contrast.darker(lower, desiredRatio)

        val availables = ArrayList<Double>(2)
        if (lightOption != -1.0) {
            availables.add(lightOption)
        }
        if (darkOption != -1.0) {
            availables.add(darkOption)
        }

        val prefersLight = DynamicColor.tonePrefersLightForeground(bgTone) ||
            DynamicColor.tonePrefersLightForeground(bgTone2)
        if (prefersLight) {
            return if (lightOption < 0) 100.0 else lightOption
        }
        if (availables.size == 1) {
            return availables[0]
        }
        return if (darkOption < 0) 0.0 else darkOption
    }

    // ------------------------------------------------------------ palette factory

    override fun getPrimaryPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.NEUTRAL ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                if (platform == DynamicScheme.Platform.PHONE) {
                    if (Hct.isBlue(sourceColorHct.hue)) 12.0 else 8.0
                } else {
                    if (Hct.isBlue(sourceColorHct.hue)) 16.0 else 12.0
                }
            )
        Scheme.Variant.TONAL_SPOT ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                if (platform == DynamicScheme.Platform.PHONE && isDark) 26.0 else 32.0
            )
        Scheme.Variant.EXPRESSIVE ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                if (platform == DynamicScheme.Platform.PHONE) {
                    if (isDark) 36.0 else 48.0
                } else {
                    40.0
                }
            )
        Scheme.Variant.VIBRANT ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                if (platform == DynamicScheme.Platform.PHONE) 74.0 else 56.0
            )
        else -> super.getPrimaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    override fun getSecondaryPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.NEUTRAL ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                if (platform == DynamicScheme.Platform.PHONE) {
                    if (Hct.isBlue(sourceColorHct.hue)) 6.0 else 4.0
                } else {
                    if (Hct.isBlue(sourceColorHct.hue)) 10.0 else 6.0
                }
            )
        Scheme.Variant.TONAL_SPOT ->
            TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
        Scheme.Variant.EXPRESSIVE ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 105.0, 140.0, 204.0, 253.0, 278.0, 300.0, 333.0, 360.0),
                    doubleArrayOf(-160.0, 155.0, -100.0, 96.0, -96.0, -156.0, -165.0, -160.0)
                ),
                if (platform == DynamicScheme.Platform.PHONE) {
                    if (isDark) 16.0 else 24.0
                } else {
                    24.0
                }
            )
        Scheme.Variant.VIBRANT ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 38.0, 105.0, 140.0, 333.0, 360.0),
                    doubleArrayOf(-14.0, 10.0, -14.0, 10.0, -14.0)
                ),
                if (platform == DynamicScheme.Platform.PHONE) 56.0 else 36.0
            )
        else -> super.getSecondaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    override fun getTertiaryPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.NEUTRAL ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 38.0, 105.0, 161.0, 204.0, 278.0, 333.0, 360.0),
                    doubleArrayOf(-32.0, 26.0, 10.0, -39.0, 24.0, -15.0, -32.0)
                ),
                if (platform == DynamicScheme.Platform.PHONE) 20.0 else 36.0
            )
        Scheme.Variant.TONAL_SPOT ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 20.0, 71.0, 161.0, 333.0, 360.0),
                    doubleArrayOf(-40.0, 48.0, -32.0, 40.0, -32.0)
                ),
                if (platform == DynamicScheme.Platform.PHONE) 28.0 else 32.0
            )
        Scheme.Variant.EXPRESSIVE ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 105.0, 140.0, 204.0, 253.0, 278.0, 300.0, 333.0, 360.0),
                    doubleArrayOf(
                        -165.0, 160.0, -105.0, 101.0, -101.0, -160.0, -170.0, -165.0
                    )
                ),
                48.0
            )
        Scheme.Variant.VIBRANT ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 38.0, 71.0, 105.0, 140.0, 161.0, 253.0, 333.0, 360.0),
                    doubleArrayOf(-72.0, 35.0, 24.0, -24.0, 62.0, 50.0, 62.0, -72.0)
                ),
                56.0
            )
        else -> super.getTertiaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    override fun getNeutralPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.NEUTRAL ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                if (platform == DynamicScheme.Platform.PHONE) 1.4 else 6.0
            )
        Scheme.Variant.TONAL_SPOT ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                if (platform == DynamicScheme.Platform.PHONE) 5.0 else 10.0
            )
        Scheme.Variant.EXPRESSIVE ->
            TonalPalette.fromHueAndChroma(
                getExpressiveNeutralHue(sourceColorHct),
                getExpressiveNeutralChroma(sourceColorHct, isDark, platform)
            )
        Scheme.Variant.VIBRANT ->
            TonalPalette.fromHueAndChroma(
                getVibrantNeutralHue(sourceColorHct),
                getVibrantNeutralChroma(sourceColorHct, platform)
            )
        else -> super.getNeutralPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    override fun getNeutralVariantPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.NEUTRAL ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                (if (platform == DynamicScheme.Platform.PHONE) 1.4 else 6.0) * 2.2
            )
        Scheme.Variant.TONAL_SPOT ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                (if (platform == DynamicScheme.Platform.PHONE) 5.0 else 10.0) * 1.7
            )
        Scheme.Variant.EXPRESSIVE -> {
            val expressiveNeutralHue = getExpressiveNeutralHue(sourceColorHct)
            val expressiveNeutralChroma =
                getExpressiveNeutralChroma(sourceColorHct, isDark, platform)
            TonalPalette.fromHueAndChroma(
                expressiveNeutralHue,
                expressiveNeutralChroma *
                    (
                        if (expressiveNeutralHue >= 105.0 && expressiveNeutralHue < 125.0) {
                            1.6
                        } else {
                            2.3
                        }
                        )
            )
        }
        Scheme.Variant.VIBRANT -> {
            val vibrantNeutralHue = getVibrantNeutralHue(sourceColorHct)
            val vibrantNeutralChroma = getVibrantNeutralChroma(sourceColorHct, platform)
            TonalPalette.fromHueAndChroma(vibrantNeutralHue, vibrantNeutralChroma * 1.29)
        }
        else -> super.getNeutralVariantPalette(
            variant, sourceColorHct, isDark, platform, contrastLevel
        )
    }

    /**
     * The 2025 spec derives the error hue from the source, so an error message does not clash with
     * a strongly-hued theme. Piecewise, because the mapping is not smooth: hue is pulled towards
     * red-ish anchors depending on which part of the wheel the source sits in.
     */
    override fun getErrorPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette? {
        val errorHue = DynamicScheme.getPiecewiseValue(
            sourceColorHct,
            doubleArrayOf(0.0, 3.0, 13.0, 23.0, 33.0, 43.0, 153.0, 273.0, 360.0),
            doubleArrayOf(12.0, 22.0, 32.0, 12.0, 22.0, 32.0, 22.0, 12.0)
        )
        return when (variant) {
            Scheme.Variant.NEUTRAL ->
                TonalPalette.fromHueAndChroma(
                    errorHue, if (platform == DynamicScheme.Platform.PHONE) 50.0 else 40.0
                )
            Scheme.Variant.TONAL_SPOT ->
                TonalPalette.fromHueAndChroma(
                    errorHue, if (platform == DynamicScheme.Platform.PHONE) 60.0 else 48.0
                )
            Scheme.Variant.EXPRESSIVE ->
                TonalPalette.fromHueAndChroma(
                    errorHue, if (platform == DynamicScheme.Platform.PHONE) 64.0 else 48.0
                )
            Scheme.Variant.VIBRANT ->
                TonalPalette.fromHueAndChroma(
                    errorHue, if (platform == DynamicScheme.Platform.PHONE) 80.0 else 60.0
                )
            else -> super.getErrorPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
        }
    }

    private fun getExpressiveNeutralHue(sourceColorHct: Hct): Double =
        DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 71.0, 124.0, 253.0, 278.0, 300.0, 360.0),
            doubleArrayOf(10.0, 0.0, 10.0, 0.0, 10.0, 0.0)
        )

    private fun getExpressiveNeutralChroma(
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform
    ): Double {
        val neutralHue = getExpressiveNeutralHue(sourceColorHct)
        return if (platform == DynamicScheme.Platform.PHONE) {
            if (isDark) {
                if (Hct.isYellow(neutralHue)) 6.0 else 14.0
            } else {
                18.0
            }
        } else {
            12.0
        }
    }

    private fun getVibrantNeutralHue(sourceColorHct: Hct): Double =
        DynamicScheme.getRotatedHue(
            sourceColorHct,
            doubleArrayOf(0.0, 38.0, 105.0, 140.0, 333.0, 360.0),
            doubleArrayOf(-14.0, 10.0, -14.0, 10.0, -14.0)
        )

    private fun getVibrantNeutralChroma(
        sourceColorHct: Hct,
        platform: DynamicScheme.Platform
    ): Double {
        val neutralHue = getVibrantNeutralHue(sourceColorHct)
        return if (platform == DynamicScheme.Platform.PHONE) {
            28.0
        } else {
            if (Hct.isBlue(neutralHue)) 28.0 else 20.0
        }
    }
}
