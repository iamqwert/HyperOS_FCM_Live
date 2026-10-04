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
 * The 2021 spec — the original Material You rules.
 *
 * Every method here returns a [DynamicColor]: a description of how to find a value, not a value.
 * The definition of a role is *which palette*, *which tone*, *against what*, and *how far it moves
 * with the contrast level*; [DynamicColor.getTone] and [DynamicColor.getHct] then resolve it
 * against a concrete scheme.
 *
 * Two things live here that look out of place but are not:
 *  - **The palette factory** (see the block at the bottom). A palette and the rules that read it
 *    are the same decision, so upstream keeps them in one file — that is also why the tonal
 *    palettes differ per style *and* per spec version.
 *  - **The resolution engine** ([getHct] / [getTone]). Roles cannot resolve themselves: the
 *    contrast curve tables, the tone-delta-pair solver and the "awkward zone" handling are all
 *    spec decisions, and the 2025 spec replaces them wholesale.
 */
internal open class ColorSpec2021 {

    // ------------------------------------------------------------ main palettes

    open fun primaryPaletteKeyColor(): DynamicColor = DynamicColor.Builder()
        .setName("primary_palette_key_color")
        .setPalette { s -> s.primaryPalette }
        .setTone { s -> s.primaryPalette.keyColor.tone }
        .build()

    open fun secondaryPaletteKeyColor(): DynamicColor = DynamicColor.Builder()
        .setName("secondary_palette_key_color")
        .setPalette { s -> s.secondaryPalette }
        .setTone { s -> s.secondaryPalette.keyColor.tone }
        .build()

    open fun tertiaryPaletteKeyColor(): DynamicColor = DynamicColor.Builder()
        .setName("tertiary_palette_key_color")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { s -> s.tertiaryPalette.keyColor.tone }
        .build()

    open fun neutralPaletteKeyColor(): DynamicColor = DynamicColor.Builder()
        .setName("neutral_palette_key_color")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> s.neutralPalette.keyColor.tone }
        .build()

    open fun neutralVariantPaletteKeyColor(): DynamicColor = DynamicColor.Builder()
        .setName("neutral_variant_palette_key_color")
        .setPalette { s -> s.neutralVariantPalette }
        .setTone { s -> s.neutralVariantPalette.keyColor.tone }
        .build()

    open fun errorPaletteKeyColor(): DynamicColor = DynamicColor.Builder()
        .setName("error_palette_key_color")
        .setPalette { s -> s.errorPalette }
        .setTone { s -> s.errorPalette.keyColor.tone }
        .build()

    // ----------------------------------------------------------------- surfaces

    open fun background(): DynamicColor = DynamicColor.Builder()
        .setName("background")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 6.0 else 98.0 }
        .setIsBackground(true)
        .build()

    open fun onBackground(): DynamicColor = DynamicColor.Builder()
        .setName("on_background")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 90.0 else 10.0 }
        .setBackground { background() }
        .setContrastCurve { ContrastCurve(3.0, 3.0, 4.5, 7.0) }
        .build()

    open fun surface(): DynamicColor = DynamicColor.Builder()
        .setName("surface")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 6.0 else 98.0 }
        .setIsBackground(true)
        .build()

    open fun surfaceDim(): DynamicColor = DynamicColor.Builder()
        .setName("surface_dim")
        .setPalette { s -> s.neutralPalette }
        .setTone { s ->
            if (s.isDark) 6.0 else ContrastCurve(87.0, 87.0, 80.0, 75.0).get(s.contrastLevel)
        }
        .setIsBackground(true)
        .build()

    open fun surfaceBright(): DynamicColor = DynamicColor.Builder()
        .setName("surface_bright")
        .setPalette { s -> s.neutralPalette }
        .setTone { s ->
            if (s.isDark) ContrastCurve(24.0, 24.0, 29.0, 34.0).get(s.contrastLevel) else 98.0
        }
        .setIsBackground(true)
        .build()

    open fun surfaceContainerLowest(): DynamicColor = DynamicColor.Builder()
        .setName("surface_container_lowest")
        .setPalette { s -> s.neutralPalette }
        .setTone { s ->
            if (s.isDark) ContrastCurve(4.0, 4.0, 2.0, 0.0).get(s.contrastLevel) else 100.0
        }
        .setIsBackground(true)
        .build()

    open fun surfaceContainerLow(): DynamicColor = DynamicColor.Builder()
        .setName("surface_container_low")
        .setPalette { s -> s.neutralPalette }
        .setTone { s ->
            if (s.isDark) {
                ContrastCurve(10.0, 10.0, 11.0, 12.0).get(s.contrastLevel)
            } else {
                ContrastCurve(96.0, 96.0, 96.0, 95.0).get(s.contrastLevel)
            }
        }
        .setIsBackground(true)
        .build()

    open fun surfaceContainer(): DynamicColor = DynamicColor.Builder()
        .setName("surface_container")
        .setPalette { s -> s.neutralPalette }
        .setTone { s ->
            if (s.isDark) {
                ContrastCurve(12.0, 12.0, 16.0, 20.0).get(s.contrastLevel)
            } else {
                ContrastCurve(94.0, 94.0, 92.0, 90.0).get(s.contrastLevel)
            }
        }
        .setIsBackground(true)
        .build()

    open fun surfaceContainerHigh(): DynamicColor = DynamicColor.Builder()
        .setName("surface_container_high")
        .setPalette { s -> s.neutralPalette }
        .setTone { s ->
            if (s.isDark) {
                ContrastCurve(17.0, 17.0, 21.0, 25.0).get(s.contrastLevel)
            } else {
                ContrastCurve(92.0, 92.0, 88.0, 85.0).get(s.contrastLevel)
            }
        }
        .setIsBackground(true)
        .build()

    open fun surfaceContainerHighest(): DynamicColor = DynamicColor.Builder()
        .setName("surface_container_highest")
        .setPalette { s -> s.neutralPalette }
        .setTone { s ->
            if (s.isDark) {
                ContrastCurve(22.0, 22.0, 26.0, 30.0).get(s.contrastLevel)
            } else {
                ContrastCurve(90.0, 90.0, 84.0, 80.0).get(s.contrastLevel)
            }
        }
        .setIsBackground(true)
        .build()

    open fun onSurface(): DynamicColor = DynamicColor.Builder()
        .setName("on_surface")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 90.0 else 10.0 }
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(4.5, 7.0, 11.0, 21.0) }
        .build()

    open fun surfaceVariant(): DynamicColor = DynamicColor.Builder()
        .setName("surface_variant")
        .setPalette { s -> s.neutralVariantPalette }
        .setTone { s -> if (s.isDark) 30.0 else 90.0 }
        .setIsBackground(true)
        .build()

    open fun onSurfaceVariant(): DynamicColor = DynamicColor.Builder()
        .setName("on_surface_variant")
        .setPalette { s -> s.neutralVariantPalette }
        .setTone { s -> if (s.isDark) 80.0 else 30.0 }
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 11.0) }
        .build()

    open fun inverseSurface(): DynamicColor = DynamicColor.Builder()
        .setName("inverse_surface")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 90.0 else 20.0 }
        .setIsBackground(true)
        .build()

    open fun inverseOnSurface(): DynamicColor = DynamicColor.Builder()
        .setName("inverse_on_surface")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 20.0 else 95.0 }
        .setBackground { inverseSurface() }
        .setContrastCurve { ContrastCurve(4.5, 7.0, 11.0, 21.0) }
        .build()

    open fun outline(): DynamicColor = DynamicColor.Builder()
        .setName("outline")
        .setPalette { s -> s.neutralVariantPalette }
        .setTone { s -> if (s.isDark) 60.0 else 50.0 }
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.5, 3.0, 4.5, 7.0) }
        .build()

    open fun outlineVariant(): DynamicColor = DynamicColor.Builder()
        .setName("outline_variant")
        .setPalette { s -> s.neutralVariantPalette }
        .setTone { s -> if (s.isDark) 30.0 else 80.0 }
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .build()

    open fun shadow(): DynamicColor = DynamicColor.Builder()
        .setName("shadow")
        .setPalette { s -> s.neutralPalette }
        .setTone { 0.0 }
        .build()

    open fun scrim(): DynamicColor = DynamicColor.Builder()
        .setName("scrim")
        .setPalette { s -> s.neutralPalette }
        .setTone { 0.0 }
        .build()

    open fun surfaceTint(): DynamicColor = DynamicColor.Builder()
        .setName("surface_tint")
        .setPalette { s -> s.primaryPalette }
        .setTone { s -> if (s.isDark) 80.0 else 40.0 }
        .setIsBackground(true)
        .build()

    // ---------------------------------------------------------------- primaries

    open fun primary(): DynamicColor = DynamicColor.Builder()
        .setName("primary")
        .setPalette { s -> s.primaryPalette }
        .setTone { s ->
            if (isMonochrome(s)) {
                if (s.isDark) 100.0 else 0.0
            } else {
                if (s.isDark) 80.0 else 40.0
            }
        }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 7.0) }
        .setToneDeltaPair {
            ToneDeltaPair(primaryContainer(), primary(), 10.0, TonePolarity.NEARER, false)
        }
        .build()

    open fun primaryDim(): DynamicColor? = null

    open fun onPrimary(): DynamicColor = DynamicColor.Builder()
        .setName("on_primary")
        .setPalette { s -> s.primaryPalette }
        .setTone { s ->
            if (isMonochrome(s)) {
                if (s.isDark) 10.0 else 90.0
            } else {
                if (s.isDark) 20.0 else 100.0
            }
        }
        .setBackground { primary() }
        .setContrastCurve { ContrastCurve(4.5, 7.0, 11.0, 21.0) }
        .build()

    open fun primaryContainer(): DynamicColor = DynamicColor.Builder()
        .setName("primary_container")
        .setPalette { s -> s.primaryPalette }
        .setTone { s ->
            if (isFidelity(s)) {
                s.sourceColorHct.tone
            } else if (isMonochrome(s)) {
                if (s.isDark) 85.0 else 25.0
            } else {
                if (s.isDark) 30.0 else 90.0
            }
        }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(primaryContainer(), primary(), 10.0, TonePolarity.NEARER, false)
        }
        .build()

    open fun onPrimaryContainer(): DynamicColor = DynamicColor.Builder()
        .setName("on_primary_container")
        .setPalette { s -> s.primaryPalette }
        .setTone { s ->
            if (isFidelity(s)) {
                DynamicColor.foregroundTone(primaryContainer().tone(s), 4.5)
            } else if (isMonochrome(s)) {
                if (s.isDark) 0.0 else 100.0
            } else {
                if (s.isDark) 90.0 else 30.0
            }
        }
        .setBackground { primaryContainer() }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 11.0) }
        .build()

    open fun inversePrimary(): DynamicColor = DynamicColor.Builder()
        .setName("inverse_primary")
        .setPalette { s -> s.primaryPalette }
        .setTone { s -> if (s.isDark) 40.0 else 80.0 }
        .setBackground { inverseSurface() }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 7.0) }
        .build()

    // -------------------------------------------------------------- secondaries

    open fun secondary(): DynamicColor = DynamicColor.Builder()
        .setName("secondary")
        .setPalette { s -> s.secondaryPalette }
        .setTone { s -> if (s.isDark) 80.0 else 40.0 }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 7.0) }
        .setToneDeltaPair {
            ToneDeltaPair(secondaryContainer(), secondary(), 10.0, TonePolarity.NEARER, false)
        }
        .build()

    open fun secondaryDim(): DynamicColor? = null

    open fun onSecondary(): DynamicColor = DynamicColor.Builder()
        .setName("on_secondary")
        .setPalette { s -> s.secondaryPalette }
        .setTone { s ->
            if (isMonochrome(s)) {
                if (s.isDark) 10.0 else 100.0
            } else {
                if (s.isDark) 20.0 else 100.0
            }
        }
        .setBackground { secondary() }
        .setContrastCurve { ContrastCurve(4.5, 7.0, 11.0, 21.0) }
        .build()

    open fun secondaryContainer(): DynamicColor = DynamicColor.Builder()
        .setName("secondary_container")
        .setPalette { s -> s.secondaryPalette }
        .setTone { s ->
            val initialTone = if (s.isDark) 30.0 else 90.0
            if (isMonochrome(s)) {
                if (s.isDark) 30.0 else 85.0
            } else if (!isFidelity(s)) {
                initialTone
            } else {
                findDesiredChromaByTone(
                    s.secondaryPalette.hue,
                    s.secondaryPalette.chroma,
                    initialTone,
                    !s.isDark
                )
            }
        }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(secondaryContainer(), secondary(), 10.0, TonePolarity.NEARER, false)
        }
        .build()

    open fun onSecondaryContainer(): DynamicColor = DynamicColor.Builder()
        .setName("on_secondary_container")
        .setPalette { s -> s.secondaryPalette }
        .setTone { s ->
            if (isMonochrome(s)) {
                if (s.isDark) 90.0 else 10.0
            } else if (!isFidelity(s)) {
                if (s.isDark) 90.0 else 30.0
            } else {
                DynamicColor.foregroundTone(secondaryContainer().tone(s), 4.5)
            }
        }
        .setBackground { secondaryContainer() }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 11.0) }
        .build()

    // --------------------------------------------------------------- tertiaries

    open fun tertiary(): DynamicColor = DynamicColor.Builder()
        .setName("tertiary")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { s ->
            if (isMonochrome(s)) {
                if (s.isDark) 90.0 else 25.0
            } else {
                if (s.isDark) 80.0 else 40.0
            }
        }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 7.0) }
        .setToneDeltaPair {
            ToneDeltaPair(tertiaryContainer(), tertiary(), 10.0, TonePolarity.NEARER, false)
        }
        .build()

    open fun tertiaryDim(): DynamicColor? = null

    open fun onTertiary(): DynamicColor = DynamicColor.Builder()
        .setName("on_tertiary")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { s ->
            if (isMonochrome(s)) {
                if (s.isDark) 10.0 else 90.0
            } else {
                if (s.isDark) 20.0 else 100.0
            }
        }
        .setBackground { tertiary() }
        .setContrastCurve { ContrastCurve(4.5, 7.0, 11.0, 21.0) }
        .build()

    open fun tertiaryContainer(): DynamicColor = DynamicColor.Builder()
        .setName("tertiary_container")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { s ->
            if (isMonochrome(s)) {
                if (s.isDark) 60.0 else 49.0
            } else if (!isFidelity(s)) {
                if (s.isDark) 30.0 else 90.0
            } else {
                val proposedHct = s.tertiaryPalette.getHct(s.sourceColorHct.tone)
                DislikeAnalyzer.fixIfDisliked(proposedHct).tone
            }
        }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(tertiaryContainer(), tertiary(), 10.0, TonePolarity.NEARER, false)
        }
        .build()

    open fun onTertiaryContainer(): DynamicColor = DynamicColor.Builder()
        .setName("on_tertiary_container")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { s ->
            if (isMonochrome(s)) {
                if (s.isDark) 0.0 else 100.0
            } else if (!isFidelity(s)) {
                if (s.isDark) 90.0 else 30.0
            } else {
                DynamicColor.foregroundTone(tertiaryContainer().tone(s), 4.5)
            }
        }
        .setBackground { tertiaryContainer() }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 11.0) }
        .build()

    // ------------------------------------------------------------------- errors

    open fun error(): DynamicColor = DynamicColor.Builder()
        .setName("error")
        .setPalette { s -> s.errorPalette }
        .setTone { s -> if (s.isDark) 80.0 else 40.0 }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 7.0) }
        .setToneDeltaPair {
            ToneDeltaPair(errorContainer(), error(), 10.0, TonePolarity.NEARER, false)
        }
        .build()

    open fun errorDim(): DynamicColor? = null

    open fun onError(): DynamicColor = DynamicColor.Builder()
        .setName("on_error")
        .setPalette { s -> s.errorPalette }
        .setTone { s -> if (s.isDark) 20.0 else 100.0 }
        .setBackground { error() }
        .setContrastCurve { ContrastCurve(4.5, 7.0, 11.0, 21.0) }
        .build()

    open fun errorContainer(): DynamicColor = DynamicColor.Builder()
        .setName("error_container")
        .setPalette { s -> s.errorPalette }
        .setTone { s -> if (s.isDark) 30.0 else 90.0 }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(errorContainer(), error(), 10.0, TonePolarity.NEARER, false)
        }
        .build()

    open fun onErrorContainer(): DynamicColor = DynamicColor.Builder()
        .setName("on_error_container")
        .setPalette { s -> s.errorPalette }
        .setTone { s ->
            if (isMonochrome(s)) {
                if (s.isDark) 90.0 else 10.0
            } else {
                if (s.isDark) 90.0 else 30.0
            }
        }
        .setBackground { errorContainer() }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 11.0) }
        .build()

    // ------------------------------------------------------------ primary fixed

    open fun primaryFixed(): DynamicColor = DynamicColor.Builder()
        .setName("primary_fixed")
        .setPalette { s -> s.primaryPalette }
        .setTone { if (isMonochrome(it)) 40.0 else 90.0 }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(primaryFixed(), primaryFixedDim(), 10.0, TonePolarity.LIGHTER, true)
        }
        .build()

    open fun primaryFixedDim(): DynamicColor = DynamicColor.Builder()
        .setName("primary_fixed_dim")
        .setPalette { s -> s.primaryPalette }
        .setTone { if (isMonochrome(it)) 30.0 else 80.0 }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(primaryFixed(), primaryFixedDim(), 10.0, TonePolarity.LIGHTER, true)
        }
        .build()

    open fun onPrimaryFixed(): DynamicColor = DynamicColor.Builder()
        .setName("on_primary_fixed")
        .setPalette { s -> s.primaryPalette }
        .setTone { if (isMonochrome(it)) 100.0 else 10.0 }
        .setBackground { primaryFixedDim() }
        .setSecondBackground { primaryFixed() }
        .setContrastCurve { ContrastCurve(4.5, 7.0, 11.0, 21.0) }
        .build()

    open fun onPrimaryFixedVariant(): DynamicColor = DynamicColor.Builder()
        .setName("on_primary_fixed_variant")
        .setPalette { s -> s.primaryPalette }
        .setTone { if (isMonochrome(it)) 90.0 else 30.0 }
        .setBackground { primaryFixedDim() }
        .setSecondBackground { primaryFixed() }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 11.0) }
        .build()

    // ---------------------------------------------------------- secondary fixed

    open fun secondaryFixed(): DynamicColor = DynamicColor.Builder()
        .setName("secondary_fixed")
        .setPalette { s -> s.secondaryPalette }
        .setTone { if (isMonochrome(it)) 80.0 else 90.0 }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(secondaryFixed(), secondaryFixedDim(), 10.0, TonePolarity.LIGHTER, true)
        }
        .build()

    open fun secondaryFixedDim(): DynamicColor = DynamicColor.Builder()
        .setName("secondary_fixed_dim")
        .setPalette { s -> s.secondaryPalette }
        .setTone { if (isMonochrome(it)) 70.0 else 80.0 }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(secondaryFixed(), secondaryFixedDim(), 10.0, TonePolarity.LIGHTER, true)
        }
        .build()

    open fun onSecondaryFixed(): DynamicColor = DynamicColor.Builder()
        .setName("on_secondary_fixed")
        .setPalette { s -> s.secondaryPalette }
        .setTone { 10.0 }
        .setBackground { secondaryFixedDim() }
        .setSecondBackground { secondaryFixed() }
        .setContrastCurve { ContrastCurve(4.5, 7.0, 11.0, 21.0) }
        .build()

    open fun onSecondaryFixedVariant(): DynamicColor = DynamicColor.Builder()
        .setName("on_secondary_fixed_variant")
        .setPalette { s -> s.secondaryPalette }
        .setTone { if (isMonochrome(it)) 25.0 else 30.0 }
        .setBackground { secondaryFixedDim() }
        .setSecondBackground { secondaryFixed() }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 11.0) }
        .build()

    // ----------------------------------------------------------- tertiary fixed

    open fun tertiaryFixed(): DynamicColor = DynamicColor.Builder()
        .setName("tertiary_fixed")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { if (isMonochrome(it)) 40.0 else 90.0 }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(tertiaryFixed(), tertiaryFixedDim(), 10.0, TonePolarity.LIGHTER, true)
        }
        .build()

    open fun tertiaryFixedDim(): DynamicColor = DynamicColor.Builder()
        .setName("tertiary_fixed_dim")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { if (isMonochrome(it)) 30.0 else 80.0 }
        .setIsBackground(true)
        .setBackground { s -> highestSurface(s) }
        .setContrastCurve { ContrastCurve(1.0, 1.0, 3.0, 4.5) }
        .setToneDeltaPair {
            ToneDeltaPair(tertiaryFixed(), tertiaryFixedDim(), 10.0, TonePolarity.LIGHTER, true)
        }
        .build()

    open fun onTertiaryFixed(): DynamicColor = DynamicColor.Builder()
        .setName("on_tertiary_fixed")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { if (isMonochrome(it)) 100.0 else 10.0 }
        .setBackground { tertiaryFixedDim() }
        .setSecondBackground { tertiaryFixed() }
        .setContrastCurve { ContrastCurve(4.5, 7.0, 11.0, 21.0) }
        .build()

    open fun onTertiaryFixedVariant(): DynamicColor = DynamicColor.Builder()
        .setName("on_tertiary_fixed_variant")
        .setPalette { s -> s.tertiaryPalette }
        .setTone { if (isMonochrome(it)) 90.0 else 30.0 }
        .setBackground { tertiaryFixedDim() }
        .setSecondBackground { tertiaryFixed() }
        .setContrastCurve { ContrastCurve(3.0, 4.5, 7.0, 11.0) }
        .build()

    // -------------------------------------------------------- Android-only roles

    /**
     * These colors were present in the Android framework before Android U and are used by MDC
     * controls. They should be avoided where possible: it is unclear whether they are used on
     * multiple backgrounds, and if they are, they cannot be adjusted for contrast. So they are
     * defined with no background, and therefore never move with the contrast level.
     *
     * For example, a color used on both a white and a black background cannot gain contrast with
     * one without losing it with the other.
     */
    // colorControlActivated is documented as colorAccent in M3 & GM3; colorAccent is documented as
    // colorSecondary in M3 and colorPrimary in GM3. Android used Material's Container as
    // Primary/Secondary/Tertiary at launch, so this is a duplicate of Primary Container.
    open fun controlActivated(): DynamicColor = DynamicColor.Builder()
        .setName("control_activated")
        .setPalette { s -> s.primaryPalette }
        .setTone { s -> if (s.isDark) 30.0 else 90.0 }
        .setIsBackground(true)
        .build()

    // colorControlNormal is documented as textColorSecondary in M3 & GM3. In Material,
    // textColorSecondary points to onSurfaceVariant in the non-disabled state, which is neutral
    // variant T30/80 in light/dark.
    open fun controlNormal(): DynamicColor = DynamicColor.Builder()
        .setName("control_normal")
        .setPalette { s -> s.neutralVariantPalette }
        .setTone { s -> if (s.isDark) 80.0 else 30.0 }
        .build()

    // colorControlHighlight is documented in both M3 & GM3: light #1f000000, dark #33ffffff —
    // black and white with 12% / 20% alpha. Dynamic colors do not support alpha, so this returns
    // black in dark mode and white in light mode plus an explicit opacity.
    open fun controlHighlight(): DynamicColor = DynamicColor.Builder()
        .setName("control_highlight")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 100.0 else 0.0 }
        .setOpacity { s -> if (s.isDark) 0.20 else 0.12 }
        .build()

    // textColorPrimaryInverse is documented in both M3 & GM3 as N10/N90.
    open fun textPrimaryInverse(): DynamicColor = DynamicColor.Builder()
        .setName("text_primary_inverse")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 10.0 else 90.0 }
        .build()

    // textColorSecondaryInverse and textColorTertiaryInverse are documented in both M3 & GM3 as
    // NV30/NV80.
    open fun textSecondaryAndTertiaryInverse(): DynamicColor = DynamicColor.Builder()
        .setName("text_secondary_and_tertiary_inverse")
        .setPalette { s -> s.neutralVariantPalette }
        .setTone { s -> if (s.isDark) 30.0 else 80.0 }
        .build()

    // textColorPrimaryInverseDisableOnly is documented in both M3 & GM3 as N10/N90.
    open fun textPrimaryInverseDisableOnly(): DynamicColor = DynamicColor.Builder()
        .setName("text_primary_inverse_disable_only")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 10.0 else 90.0 }
        .build()

    // textColorSecondaryInverse and textColorTertiaryInverse in the disabled state are documented
    // in both M3 & GM3 as N10/N90.
    open fun textSecondaryAndTertiaryInverseDisabled(): DynamicColor = DynamicColor.Builder()
        .setName("text_secondary_and_tertiary_inverse_disabled")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 10.0 else 90.0 }
        .build()

    // textColorHintInverse is documented in both M3 & GM3 as N10/N90.
    open fun textHintInverse(): DynamicColor = DynamicColor.Builder()
        .setName("text_hint_inverse")
        .setPalette { s -> s.neutralPalette }
        .setTone { s -> if (s.isDark) 10.0 else 90.0 }
        .build()

    // -------------------------------------------------------------------- other

    /**
     * The surface a role is measured against when it does not name one explicitly.
     *
     * Note this is *not* [surface]: M3 treats the brightest surface in dark mode and the dimmest in
     * light mode as "the background" for the purpose of contrast, so content lands on whichever
     * one is furthest from the foreground.
     */
    open fun highestSurface(s: DynamicScheme): DynamicColor =
        if (s.isDark) surfaceBright() else surfaceDim()

    /** Content/fidelity styles take a color straight from the source instead of a fixed tone. */
    private fun isFidelity(scheme: DynamicScheme): Boolean =
        scheme.variant == Scheme.Variant.FIDELITY || scheme.variant == Scheme.Variant.CONTENT

    private fun isMonochrome(scheme: DynamicScheme): Boolean =
        scheme.variant == Scheme.Variant.MONOCHROME

    /**
     * Walks away from [tone] until the palette can supply [chroma], returning the tone that comes
     * closest. Used by `secondary_container` in the fidelity/content styles, where the request is
     * "the source's chroma, near T90" but T90 physically cannot carry it.
     */
    private fun findDesiredChromaByTone(
        hue: Double,
        chroma: Double,
        tone: Double,
        byDecreasingTone: Boolean
    ): Double {
        var answer = tone

        var closestToChroma = Hct.from(hue, chroma, tone)
        if (closestToChroma.chroma < chroma) {
            var chromaPeak = closestToChroma.chroma
            while (closestToChroma.chroma < chroma) {
                answer += if (byDecreasingTone) -1.0 else 1.0
                val potentialSolution = Hct.from(hue, chroma, answer)
                if (chromaPeak > potentialSolution.chroma) {
                    break
                }
                if (Math.abs(potentialSolution.chroma - chroma) < 0.4) {
                    break
                }

                val potentialDelta = Math.abs(potentialSolution.chroma - chroma)
                val currentDelta = Math.abs(closestToChroma.chroma - chroma)
                if (potentialDelta < currentDelta) {
                    closestToChroma = potentialSolution
                }
                chromaPeak = max(chromaPeak, potentialSolution.chroma)
            }
        }

        return answer
    }

    // ------------------------------------------------------ color value solving

    /**
     * Resolves a role to HCT for a scheme.
     *
     * This is crucial for aesthetics: the tone is not simply changed for contrast and then used.
     * Rather, the tone required for the contrast is found, and the color is then rebuilt at the
     * palette's chroma for that tone. That is what lets a role whose nominal tone is T90 — which
     * has very little chroma available — "recover" its intended chroma as contrast increases.
     */
    open fun getHct(scheme: DynamicScheme, color: DynamicColor): Hct {
        val tone = getTone(scheme, color)
        return color.palette!!.invoke(scheme).getHct(tone)
    }

    /**
     * Resolves the tone of a role for a scheme, after the contrast curve and any tone-delta
     * constraint shared with another role.
     *
     * Three cases, in upstream's order: a tone-delta pair (solved against both roles' contrast
     * curves and then pushed apart), no pair (solved against one background), and finally a role
     * that sits on two backgrounds at once (the fixed colors, which must stay legible on both
     * `_fixed` and `_fixed_dim`).
     */
    open fun getTone(scheme: DynamicScheme, color: DynamicColor): Double {
        val decreasingContrast = scheme.contrastLevel < 0
        val toneDeltaPair = color.toneDeltaPair?.invoke(scheme)

        // Case 1: dual foreground, pair of colors with delta constraint.
        if (toneDeltaPair != null) {
            val roleA = toneDeltaPair.roleA
            val roleB = toneDeltaPair.roleB
            val delta = toneDeltaPair.delta
            val polarity = toneDeltaPair.polarity
            val stayTogether = toneDeltaPair.stayTogether

            val aIsNearer = polarity == TonePolarity.NEARER ||
                (polarity == TonePolarity.LIGHTER && !scheme.isDark) ||
                (polarity == TonePolarity.DARKER && !scheme.isDark)
            val nearer = if (aIsNearer) roleA else roleB
            val farther = if (aIsNearer) roleB else roleA
            val amNearer = color.name == nearer.name
            // Tones increase away from the background in dark mode and towards it in light mode,
            // so "expand the delta" points in opposite directions for the two modes.
            val expansionDir = if (scheme.isDark) 1.0 else -1.0
            var nTone = nearer.tone(scheme)
            var fTone = farther.tone(scheme)

            // 1st round: solve to minimum contrast, each role independently.
            val background = color.background
            val nearerCurve = nearer.contrastCurve
            val fartherCurve = farther.contrastCurve
            if (background != null && nearerCurve != null && fartherCurve != null) {
                val bg = background.invoke(scheme)
                val nContrastCurve = nearerCurve.invoke(scheme)
                val fContrastCurve = fartherCurve.invoke(scheme)
                if (bg != null && nContrastCurve != null && fContrastCurve != null) {
                    val nContrast = nContrastCurve.get(scheme.contrastLevel)
                    val fContrast = fContrastCurve.get(scheme.contrastLevel)
                    val bgTone = bg.getTone(scheme)

                    // A color that is already good enough is not adjusted.
                    if (Contrast.ratioOfTones(bgTone, nTone) < nContrast) {
                        nTone = DynamicColor.foregroundTone(bgTone, nContrast)
                    }
                    if (Contrast.ratioOfTones(bgTone, fTone) < fContrast) {
                        fTone = DynamicColor.foregroundTone(bgTone, fContrast)
                    }

                    if (decreasingContrast) {
                        // Going the other way, drop to the bare minimum that still satisfies it.
                        nTone = DynamicColor.foregroundTone(bgTone, nContrast)
                        fTone = DynamicColor.foregroundTone(bgTone, fContrast)
                    }
                }
            }

            // If the constraint is not satisfied, try another round.
            if ((fTone - nTone) * expansionDir < delta) {
                // 2nd round: expand `farther` to match the delta.
                fTone = MathUtils.clampDouble(0.0, 100.0, nTone + delta * expansionDir)
                if ((fTone - nTone) * expansionDir < delta) {
                    // 3rd round: contract `nearer` to match the delta.
                    nTone = MathUtils.clampDouble(0.0, 100.0, fTone - delta * expansionDir)
                }
            }

            // Avoid the 50-59 awkward zone, where two tones are visually indistinguishable but
            // numerically far apart.
            if (nTone >= 50 && nTone < 60) {
                // `nearer` is in the zone: move it away, and `farther` with it.
                if (expansionDir > 0) {
                    nTone = 60.0
                    fTone = max(fTone, nTone + delta * expansionDir)
                } else {
                    nTone = 49.0
                    fTone = min(fTone, nTone + delta * expansionDir)
                }
            } else if (fTone >= 50 && fTone < 60) {
                if (stayTogether) {
                    // Both, so neither ends up on the opposite side of the zone.
                    if (expansionDir > 0) {
                        nTone = 60.0
                        fTone = max(fTone, nTone + delta * expansionDir)
                    } else {
                        nTone = 49.0
                        fTone = min(fTone, nTone + delta * expansionDir)
                    }
                } else {
                    fTone = if (expansionDir > 0) 60.0 else 49.0
                }
            }

            return if (amNearer) nTone else fTone
        }

        // Case 2: no contrast pair; just solve for itself.
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

        if (Contrast.ratioOfTones(bgTone, answer) < desiredRatio) {
            answer = DynamicColor.foregroundTone(bgTone, desiredRatio)
        }

        if (decreasingContrast) {
            answer = DynamicColor.foregroundTone(bgTone, desiredRatio)
        }

        if (color.isBackground && answer >= 50 && answer < 60) {
            // A background must not sit in the awkward zone; pick whichever side still passes.
            answer = if (Contrast.ratioOfTones(49.0, bgTone) >= desiredRatio) 49.0 else 60.0
        }

        val secondBackground = color.secondBackground
        if (secondBackground == null || secondBackground.invoke(scheme) == null) {
            return answer
        }

        // Case 3: adjust for two backgrounds at once.
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
            return if (lightOption == -1.0) 100.0 else lightOption
        }
        if (availables.size == 1) {
            return availables[0]
        }
        return if (darkOption == -1.0) 0.0 else darkOption
    }

    // ------------------------------------------------------------ palette factory

    /**
     * The six palettes for a style.
     *
     * A palette is fully described by a hue and a chroma, so each style is "the source's hue,
     * rotated and attenuated this much". The fidelity and content styles are the exception: they
     * inherit the source's own chroma (and, for tertiary, literally pick an analogous or
     * complementary color), which is exactly what makes those two styles reproduce a brand color
     * instead of a harmonised derivative of it.
     */
    open fun getPrimaryPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.CONTENT, Scheme.Variant.FIDELITY ->
            TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma)
        Scheme.Variant.FRUIT_SALAD ->
            TonalPalette.fromHueAndChroma(
                MathUtils.sanitizeDegreesDouble(sourceColorHct.hue - 50.0), 48.0
            )
        Scheme.Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
        Scheme.Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 12.0)
        Scheme.Variant.RAINBOW -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 48.0)
        Scheme.Variant.TONAL_SPOT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 36.0)
        Scheme.Variant.EXPRESSIVE ->
            TonalPalette.fromHueAndChroma(
                MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + 240.0), 40.0
            )
        Scheme.Variant.VIBRANT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 200.0)
    }

    open fun getSecondaryPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.CONTENT, Scheme.Variant.FIDELITY ->
            TonalPalette.fromHueAndChroma(
                sourceColorHct.hue,
                max(sourceColorHct.chroma - 32.0, sourceColorHct.chroma * 0.5)
            )
        Scheme.Variant.FRUIT_SALAD ->
            TonalPalette.fromHueAndChroma(
                MathUtils.sanitizeDegreesDouble(sourceColorHct.hue - 50.0), 36.0
            )
        Scheme.Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
        Scheme.Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 8.0)
        Scheme.Variant.RAINBOW -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
        Scheme.Variant.TONAL_SPOT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
        Scheme.Variant.EXPRESSIVE ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 21.0, 51.0, 121.0, 151.0, 191.0, 271.0, 321.0, 360.0),
                    doubleArrayOf(45.0, 95.0, 45.0, 20.0, 45.0, 90.0, 45.0, 45.0, 45.0)
                ),
                24.0
            )
        Scheme.Variant.VIBRANT ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 41.0, 61.0, 101.0, 131.0, 181.0, 251.0, 301.0, 360.0),
                    doubleArrayOf(18.0, 15.0, 10.0, 12.0, 15.0, 18.0, 15.0, 12.0, 12.0)
                ),
                24.0
            )
    }

    open fun getTertiaryPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.CONTENT ->
            TonalPalette.fromHct(
                DislikeAnalyzer.fixIfDisliked(
                    TemperatureCache(sourceColorHct).getAnalogousColors(3, 6)[2]
                )
            )
        Scheme.Variant.FIDELITY ->
            TonalPalette.fromHct(
                DislikeAnalyzer.fixIfDisliked(TemperatureCache(sourceColorHct).complement)
            )
        Scheme.Variant.FRUIT_SALAD -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 36.0)
        Scheme.Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
        Scheme.Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
        Scheme.Variant.RAINBOW, Scheme.Variant.TONAL_SPOT ->
            TonalPalette.fromHueAndChroma(
                MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + 60.0), 24.0
            )
        Scheme.Variant.EXPRESSIVE ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 21.0, 51.0, 121.0, 151.0, 191.0, 271.0, 321.0, 360.0),
                    doubleArrayOf(120.0, 120.0, 20.0, 45.0, 20.0, 15.0, 20.0, 120.0, 120.0)
                ),
                32.0
            )
        Scheme.Variant.VIBRANT ->
            TonalPalette.fromHueAndChroma(
                DynamicScheme.getRotatedHue(
                    sourceColorHct,
                    doubleArrayOf(0.0, 41.0, 61.0, 101.0, 131.0, 181.0, 251.0, 301.0, 360.0),
                    doubleArrayOf(35.0, 30.0, 20.0, 25.0, 30.0, 35.0, 30.0, 25.0, 25.0)
                ),
                32.0
            )
    }

    open fun getNeutralPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.CONTENT, Scheme.Variant.FIDELITY ->
            TonalPalette.fromHueAndChroma(sourceColorHct.hue, sourceColorHct.chroma / 8.0)
        Scheme.Variant.FRUIT_SALAD -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 10.0)
        Scheme.Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
        Scheme.Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 2.0)
        Scheme.Variant.RAINBOW -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
        Scheme.Variant.TONAL_SPOT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 6.0)
        Scheme.Variant.EXPRESSIVE ->
            TonalPalette.fromHueAndChroma(
                MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + 15.0), 8.0
            )
        Scheme.Variant.VIBRANT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 10.0)
    }

    open fun getNeutralVariantPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette = when (variant) {
        Scheme.Variant.CONTENT, Scheme.Variant.FIDELITY ->
            TonalPalette.fromHueAndChroma(sourceColorHct.hue, (sourceColorHct.chroma / 8.0) + 4.0)
        Scheme.Variant.FRUIT_SALAD -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 16.0)
        Scheme.Variant.MONOCHROME -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
        Scheme.Variant.NEUTRAL -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 2.0)
        Scheme.Variant.RAINBOW -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 0.0)
        Scheme.Variant.TONAL_SPOT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 8.0)
        Scheme.Variant.EXPRESSIVE ->
            TonalPalette.fromHueAndChroma(
                MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + 15.0), 12.0
            )
        Scheme.Variant.VIBRANT -> TonalPalette.fromHueAndChroma(sourceColorHct.hue, 12.0)
    }

    /**
     * The 2021 spec has no per-style error palette: every style uses a fixed red. Returning null
     * means "use the default", which the caller supplies.
     */
    open fun getErrorPalette(
        variant: Scheme.Variant,
        sourceColorHct: Hct,
        isDark: Boolean,
        platform: DynamicScheme.Platform,
        contrastLevel: Double
    ): TonalPalette? = when (variant) {
        Scheme.Variant.CONTENT,
        Scheme.Variant.FIDELITY,
        Scheme.Variant.FRUIT_SALAD,
        Scheme.Variant.MONOCHROME,
        Scheme.Variant.NEUTRAL,
        Scheme.Variant.RAINBOW,
        Scheme.Variant.TONAL_SPOT,
        Scheme.Variant.EXPRESSIVE,
        Scheme.Variant.VIBRANT -> null
    }
}
