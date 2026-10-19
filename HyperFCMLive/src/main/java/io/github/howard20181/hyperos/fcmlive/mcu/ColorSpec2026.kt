/*
 * Copyright 2026 Google LLC
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
 * The 2026 spec — the CMF scheme's own layer on top of Material 3 Expressive.
 *
 * Every definition below is the same extend-on-`super` pattern [ColorSpec2025] uses, with one
 * structural difference that decides where this spec may be used: **each tone is written for the
 * CMF variant and nothing else** — the non-CMF branch falls to `0.0`, the same sentinel upstream
 * uses, which renders as raw black. That is why the 2026 spec only ever resolves for
 * [Scheme.Variant.CMF] (see [DynamicScheme.maybeFallbackSpecVersion], which downgrades every other
 * variant's request before a scheme is built), while CMF itself demands this spec — upstream
 * `SchemeCmf` rejects any other version outright.
 *
 * The upstream file is a subclass of the 2025 spec overriding ~40 roles; this port follows it role
 * for role, in the function style the rest of this vendored subset uses. Two adaptations:
 *  - Upstream `SchemeCmf` takes a *list* of source colors; this app is single-seed, so the
 *    list-derived rules (tertiary from the second source, `?: source` when absent) collapse to the
 *    single-source branch — which is also exactly what upstream's own secondary constructor does.
 *  - The CMF **palettes** (primary at full chroma, secondary at half, tertiary at three quarters,
 *    neutrals at a fifth, and the piecewise error hue) are upstream's `SchemeCmf` constructor body;
 *    they arrive here as [getPrimaryPalette]-family overrides so [Scheme]'s "palettes come from the
 *    requested spec" flow builds a CMF scheme without a scheme subclass.
 */
internal class ColorSpec2026 : ColorSpec2025() {

    // ----------------------------------------------------------------- tone helpers

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

    private fun tMaxC(
        palette: TonalPalette,
        lowerBound: Double = 0.0,
        upperBound: Double = 100.0,
        chromaMultiplier: Double = 1.0
    ): Double {
        val answer = findBestToneForChroma(
            palette.hue, palette.chroma * chromaMultiplier, 100.0, true
        )
        return answer.coerceIn(lowerBound, upperBound)
    }

    private fun tMinC(
        palette: TonalPalette,
        lowerBound: Double = 0.0,
        upperBound: Double = 100.0
    ): Double {
        val answer = findBestToneForChroma(palette.hue, palette.chroma, 0.0, false)
        return answer.coerceIn(lowerBound, upperBound)
    }

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

    // ----------------------------------------------------------------- surfaces

    override fun surface(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("surface")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 4.0 else 98.0) else 0.0
            }
            .setIsBackground(true)
            .build()
        return super.surface().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun surfaceDim(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("surface_dim")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 4.0 else 87.0) else 0.0
            }
            .setIsBackground(true)
            .setChromaMultiplier { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 1.0 else 1.7) else 0.0
            }
            .build()
        return super.surfaceDim().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun surfaceBright(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("surface_bright")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 18.0 else 98.0) else 0.0
            }
            .setIsBackground(true)
            .setChromaMultiplier { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 1.7 else 1.0) else 0.0
            }
            .build()
        return super.surfaceBright().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun surfaceContainerLowest(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("surface_container_lowest")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 0.0 else 100.0) else 0.0
            }
            .setIsBackground(true)
            .build()
        return super.surfaceContainerLowest().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun surfaceContainerLow(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("surface_container_low")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 6.0 else 96.0) else 0.0
            }
            .setIsBackground(true)
            .setChromaMultiplier { s -> if (s.variant == Scheme.Variant.CMF) 1.25 else 0.0 }
            .build()
        return super.surfaceContainerLow().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun surfaceContainer(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("surface_container")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 9.0 else 94.0) else 0.0
            }
            .setIsBackground(true)
            .setChromaMultiplier { s -> if (s.variant == Scheme.Variant.CMF) 1.4 else 0.0 }
            .build()
        return super.surfaceContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun surfaceContainerHigh(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("surface_container_high")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 12.0 else 92.0) else 0.0
            }
            .setIsBackground(true)
            .setChromaMultiplier { s -> if (s.variant == Scheme.Variant.CMF) 1.5 else 0.0 }
            .build()
        return super.surfaceContainerHigh().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun surfaceContainerHighest(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("surface_container_highest")
            .setPalette { s -> s.neutralPalette }
            .setTone { s ->
                if (s.variant == Scheme.Variant.CMF) (if (s.isDark) 15.0 else 90.0) else 0.0
            }
            .setIsBackground(true)
            .setChromaMultiplier { s -> if (s.variant == Scheme.Variant.CMF) 1.7 else 0.0 }
            .build()
        return super.surfaceContainerHighest().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onSurface(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_surface")
            .setPalette { s -> s.neutralPalette }
            .setChromaMultiplier { s -> if (s.variant == Scheme.Variant.CMF) 1.7 else 0.0 }
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { s -> getContrastCurve(if (s.isDark) 11.0 else 9.0) }
            .build()
        return super.onSurface().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun onSurfaceVariant(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_surface_variant")
            .setPalette { s -> s.neutralPalette }
            .setChromaMultiplier { s -> if (s.variant == Scheme.Variant.CMF) 1.7 else 0.0 }
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { s -> getContrastCurve(if (s.isDark) 6.0 else 4.5) }
            .build()
        return super.onSurfaceVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun outline(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("outline")
            .setPalette { s -> s.neutralPalette }
            .setChromaMultiplier { s -> if (s.variant == Scheme.Variant.CMF) 1.7 else 0.0 }
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { getContrastCurve(3.0) }
            .build()
        return super.outline().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun outlineVariant(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("outline_variant")
            .setPalette { s -> s.neutralPalette }
            .setChromaMultiplier { s -> if (s.variant == Scheme.Variant.CMF) 1.7 else 0.0 }
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { getContrastCurve(1.5) }
            .build()
        return super.outlineVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun inverseSurface(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("inverse_surface")
            .setPalette { s -> s.neutralPalette }
            .setTone { s -> if (s.isDark) 98.0 else 4.0 }
            .setIsBackground(true)
            .setChromaMultiplier { s -> if (s.variant == Scheme.Variant.CMF) 1.7 else 0.0 }
            .build()
        return super.inverseSurface().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun inverseOnSurface(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("inverse_on_surface")
            .setPalette { s -> s.neutralPalette }
            .setBackground { inverseSurface() }
            .setContrastCurve { getContrastCurve(7.0) }
            .build()
        return super.inverseOnSurface().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    // ----------------------------------------------------------------- primary

    override fun primary(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("primary")
            .setPalette { s -> s.primaryPalette }
            .setTone { s ->
                if (s.sourceColorHct.chroma <= 12) {
                    (if (s.isDark) 80.0 else 40.0)
                } else {
                    s.sourceColorHct.tone
                }
            }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { getContrastCurve(4.5) }
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
        return super.primary().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun primaryDim(): DynamicColor {
        // Remapped to primary in 2026 spec.
        val color2026 = primary().toBuilder().setName("primary_dim").build()
        return super.primaryDim().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun onPrimary(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_primary")
            .setPalette { s -> s.primaryPalette }
            .setBackground { primary() }
            .setContrastCurve { getContrastCurve(6.0) }
            .build()
        return super.onPrimary().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun primaryContainer(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("primary_container")
            .setPalette { s -> s.primaryPalette }
            .setTone { s ->
                if (!s.isDark && s.sourceColorHct.chroma <= 12) {
                    90.0
                } else if (s.sourceColorHct.tone > 55) {
                    s.sourceColorHct.tone.coerceIn(61.0, 90.0)
                } else {
                    s.sourceColorHct.tone.coerceIn(30.0, 49.0)
                }
            }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.primaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onPrimaryContainer(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_primary_container")
            .setPalette { s -> s.primaryPalette }
            .setBackground { primaryContainer() }
            .setContrastCurve { getContrastCurve(6.0) }
            .build()
        return super.onPrimaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun primaryFixed(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("primary_fixed")
            .setPalette { s -> s.primaryPalette }
            .setTone { s ->
                val tempS = DynamicScheme.from(s, false, 0.0)
                primaryContainer().getTone(tempS)
            }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.primaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun primaryFixedDim(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("primary_fixed_dim")
            .setPalette { s -> s.primaryPalette }
            .setTone { s -> primaryFixed().getTone(s) }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setToneDeltaPair {
                ToneDeltaPair(
                    primaryFixedDim(), primaryFixed(), 5.0,
                    TonePolarity.DARKER, DeltaConstraint.EXACT
                )
            }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.primaryFixedDim().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onPrimaryFixed(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_primary_fixed")
            .setPalette { s -> s.primaryPalette }
            .setBackground { s ->
                if (primaryFixed().getTone(s) > 57) primaryFixedDim() else primaryFixed()
            }
            .setContrastCurve { getContrastCurve(7.0) }
            .build()
        return super.onPrimaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onPrimaryFixedVariant(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_primary_fixed_variant")
            .setPalette { s -> s.primaryPalette }
            .setBackground { s ->
                if (primaryFixed().getTone(s) > 57) primaryFixedDim() else primaryFixed()
            }
            .setContrastCurve { getContrastCurve(4.5) }
            .build()
        return super.onPrimaryFixedVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    // ----------------------------------------------------------------- secondary

    override fun secondary(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("secondary")
            .setPalette { s -> s.secondaryPalette }
            .setTone { s ->
                if (s.isDark) tMinC(s.secondaryPalette) else tMaxC(s.secondaryPalette)
            }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { getContrastCurve(4.5) }
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
        return super.secondary().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun secondaryDim(): DynamicColor {
        // Remapped to secondary in 2026 spec.
        val color2026 = secondary().toBuilder().setName("secondary_dim").build()
        return super.secondaryDim().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onSecondary(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_secondary")
            .setPalette { s -> s.secondaryPalette }
            .setBackground { secondary() }
            .setContrastCurve { getContrastCurve(6.0) }
            .build()
        return super.onSecondary().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun secondaryContainer(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("secondary_container")
            .setPalette { s -> s.secondaryPalette }
            .setTone { s ->
                if (s.isDark) {
                    tMinC(s.secondaryPalette, 20.0, 49.0)
                } else {
                    tMaxC(s.secondaryPalette, 61.0, 90.0)
                }
            }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.secondaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onSecondaryContainer(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_secondary_container")
            .setPalette { s -> s.secondaryPalette }
            .setBackground { secondaryContainer() }
            .setContrastCurve { getContrastCurve(6.0) }
            .build()
        return super.onSecondaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun secondaryFixed(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("secondary_fixed")
            .setPalette { s -> s.secondaryPalette }
            .setTone { s ->
                val tempS = DynamicScheme.from(s, false, 0.0)
                secondaryContainer().getTone(tempS)
            }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.secondaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun secondaryFixedDim(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("secondary_fixed_dim")
            .setPalette { s -> s.secondaryPalette }
            .setTone { s -> secondaryFixed().getTone(s) }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setToneDeltaPair {
                ToneDeltaPair(
                    secondaryFixedDim(), secondaryFixed(), 5.0,
                    TonePolarity.DARKER, DeltaConstraint.EXACT
                )
            }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.secondaryFixedDim().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onSecondaryFixed(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_secondary_fixed")
            .setPalette { s -> s.secondaryPalette }
            .setBackground { s ->
                if (secondaryFixed().getTone(s) > 57) secondaryFixedDim() else secondaryFixed()
            }
            .setContrastCurve { getContrastCurve(7.0) }
            .build()
        return super.onSecondaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onSecondaryFixedVariant(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_secondary_fixed_variant")
            .setPalette { s -> s.secondaryPalette }
            .setBackground { s ->
                if (secondaryFixed().getTone(s) > 57) secondaryFixedDim() else secondaryFixed()
            }
            .setContrastCurve { getContrastCurve(4.5) }
            .build()
        return super.onSecondaryFixedVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    // ----------------------------------------------------------------- tertiary

    override fun tertiary(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("tertiary")
            .setPalette { s -> s.tertiaryPalette }
            // Upstream takes the second source color's tone when one was given;
            // single-seed, this is always the source's own tone.
            .setTone { s -> s.sourceColorHct.tone }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { getContrastCurve(4.5) }
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
        return super.tertiary().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun tertiaryDim(): DynamicColor {
        // Remapped to tertiary in 2026 spec.
        val color2026 = tertiary().toBuilder().setName("tertiary_dim").build()
        return super.tertiaryDim().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onTertiary(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_tertiary")
            .setPalette { s -> s.tertiaryPalette }
            .setBackground { tertiary() }
            .setContrastCurve { getContrastCurve(6.0) }
            .build()
        return super.onTertiary().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun tertiaryContainer(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("tertiary_container")
            .setPalette { s -> s.tertiaryPalette }
            // Single source: the "second source" is the source itself.
            .setTone { s ->
                if (s.sourceColorHct.tone > 55) {
                    s.sourceColorHct.tone.coerceIn(61.0, 90.0)
                } else {
                    s.sourceColorHct.tone.coerceIn(20.0, 49.0)
                }
            }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.tertiaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onTertiaryContainer(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_tertiary_container")
            .setPalette { s -> s.tertiaryPalette }
            .setBackground { tertiaryContainer() }
            .setContrastCurve { getContrastCurve(6.0) }
            .build()
        return super.onTertiaryContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun tertiaryFixed(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("tertiary_fixed")
            .setPalette { s -> s.tertiaryPalette }
            .setTone { s ->
                val tempS = DynamicScheme.from(s, false, 0.0)
                tertiaryContainer().getTone(tempS)
            }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.tertiaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun tertiaryFixedDim(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("tertiary_fixed_dim")
            .setPalette { s -> s.tertiaryPalette }
            .setTone { s -> tertiaryFixed().getTone(s) }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setToneDeltaPair {
                ToneDeltaPair(
                    tertiaryFixedDim(), tertiaryFixed(), 5.0,
                    TonePolarity.DARKER, DeltaConstraint.EXACT
                )
            }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.tertiaryFixedDim().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onTertiaryFixed(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_tertiary_fixed")
            .setPalette { s -> s.tertiaryPalette }
            .setBackground { s ->
                if (tertiaryFixed().getTone(s) > 57) tertiaryFixedDim() else tertiaryFixed()
            }
            .setContrastCurve { getContrastCurve(7.0) }
            .build()
        return super.onTertiaryFixed().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onTertiaryFixedVariant(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_tertiary_fixed_variant")
            .setPalette { s -> s.tertiaryPalette }
            .setBackground { s ->
                if (tertiaryFixed().getTone(s) > 57) tertiaryFixedDim() else tertiaryFixed()
            }
            .setContrastCurve { getContrastCurve(4.5) }
            .build()
        return super.onTertiaryFixedVariant().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    // ----------------------------------------------------------------- error

    override fun error(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("error")
            .setPalette { s -> s.errorPalette }
            .setTone { s -> tMaxC(s.errorPalette) }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { getContrastCurve(4.5) }
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
        return super.error().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun errorDim(): DynamicColor {
        // Remapped to error in 2026 spec.
        val color2026 = error().toBuilder().setName("error_dim").build()
        return super.errorDim().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun onError(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_error")
            .setPalette { s -> s.errorPalette }
            .setBackground { error() }
            .setContrastCurve { getContrastCurve(6.0) }
            .build()
        return super.onError().toBuilder().extendSpecVersion(Scheme.Spec.SPEC_2026, color2026).build()
    }

    override fun errorContainer(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("error_container")
            .setPalette { s -> s.errorPalette }
            .setTone { s ->
                if (s.isDark) tMinC(s.errorPalette) else tMaxC(s.errorPalette)
            }
            .setIsBackground(true)
            .setBackground { s -> highestSurface(s) }
            .setContrastCurve { s -> if (s.contrastLevel > 0) getContrastCurve(1.5) else null }
            .build()
        return super.errorContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    override fun onErrorContainer(): DynamicColor {
        val color2026 = DynamicColor.Builder()
            .setName("on_error_container")
            .setPalette { s -> s.errorPalette }
            .setBackground { errorContainer() }
            .setContrastCurve { getContrastCurve(6.0) }
            .build()
        return super.onErrorContainer().toBuilder()
            .extendSpecVersion(Scheme.Spec.SPEC_2026, color2026)
            .build()
    }

    // ----------------------------------------------------------------- CMF palettes

    /**
     * The CMF palettes — upstream `SchemeCmf`'s constructor body, expressed the way [Scheme] asks
     * for palettes: per spec, per variant. Single source, so the tertiary is the source at three
     * quarters chroma (upstream's rule when no second color is supplied).
     */
    override fun getPrimaryPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = if (variant == Scheme.Variant.CMF) {
        TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma)
    } else {
        super.getPrimaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    override fun getSecondaryPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = if (variant == Scheme.Variant.CMF) {
        TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma * 0.5)
    } else {
        super.getSecondaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    override fun getTertiaryPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = if (variant == Scheme.Variant.CMF) {
        TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma * 0.75)
    } else {
        super.getTertiaryPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    override fun getNeutralPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = if (variant == Scheme.Variant.CMF) {
        TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma * 0.2)
    } else {
        super.getNeutralPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    override fun getNeutralVariantPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = if (variant == Scheme.Variant.CMF) {
        TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma * 0.2)
    } else {
        super.getNeutralVariantPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    override fun getErrorPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette? = if (variant == Scheme.Variant.CMF) {
        TonalPalette.fromHueAndChroma(
            getErrorHue(sourceColorHct.hue, sourceColorHct.hue),
            sourceColorHct.chroma.coerceAtLeast(50.0)
        )
    } else {
        super.getErrorPalette(variant, sourceColorHct, isDark, platform, contrastLevel)
    }

    /**
     * Upstream `SchemeCmf`'s error hue table. The second argument is the *tertiary* palette's hue;
     * single-seed, the tertiary hue is the source hue, so both arguments agree.
     */
    private fun getErrorHue(primaryHue: Double, tertiaryHue: Double): Double {
        if (primaryHue <= 8) {
            return if (tertiaryHue <= 24) 28.0 else if (tertiaryHue <= 32) 16.0 else 20.0
        } else if (primaryHue <= 16) {
            return if (tertiaryHue <= 24) 32.0 else if (tertiaryHue <= 32) 20.0 else 24.0
        } else if (primaryHue <= 20) {
            return if (tertiaryHue <= 28) 32.0 else if (tertiaryHue <= 32) 24.0 else 28.0
        } else if (primaryHue <= 28) {
            return if (tertiaryHue <= 24) 32.0 else 16.0
        } else if (primaryHue <= 32) {
            return if (tertiaryHue <= 20) 24.0 else if (tertiaryHue <= 28) 16.0 else 20.0
        } else if (primaryHue <= 40) {
            return if (tertiaryHue > 20 && tertiaryHue <= 28) 16.0 else 24.0
        } else if (primaryHue <= 152) {
            return if (tertiaryHue > 24 && tertiaryHue <= 36) 20.0 else 32.0
        } else if (primaryHue <= 272) {
            return if (tertiaryHue > 20 && tertiaryHue <= 28) 16.0 else 24.0
        } else {
            return if (tertiaryHue > 12 && tertiaryHue <= 28) 32.0 else 16.0
        }
    }
}
