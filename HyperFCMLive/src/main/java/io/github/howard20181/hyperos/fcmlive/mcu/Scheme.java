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
package io.github.howard20181.hyperos.fcmlive.mcu;

/**
 * A Material Design 3 dynamic color scheme: tonal palettes plus the resolved
 * color roles for one (seed color, palette style, spec version, light/dark)
 * combination. Every role is resolved once, in the constructor.
 */
public final class Scheme {

    /** Material Design 3 palette styles (official {@code Variant} enum). */
    public enum Variant {
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
    public enum Spec {
        SPEC_2021,
        SPEC_2025
    }

    public final TonalPalette primaryPalette;
    public final TonalPalette secondaryPalette;
    public final TonalPalette tertiaryPalette;
    public final TonalPalette neutralPalette;
    public final TonalPalette neutralVariantPalette;
    public final TonalPalette errorPalette;

    public final boolean dark;
    public final Variant variant;
    public final Spec spec;

    public int primary;
    public int onPrimary;
    public int primaryContainer;
    public int onPrimaryContainer;
    public int secondary;
    public int onSecondary;
    public int secondaryContainer;
    public int onSecondaryContainer;
    public int tertiary;
    public int onTertiary;
    public int tertiaryContainer;
    public int onTertiaryContainer;
    public int surface;
    public int surfaceDim;
    public int surfaceBright;
    public int surfaceContainerLowest;
    public int surfaceContainerLow;
    public int surfaceContainer;
    public int surfaceContainerHigh;
    public int surfaceContainerHighest;
    public int onSurface;
    public int surfaceVariant;
    public int onSurfaceVariant;
    public int outline;
    public int outlineVariant;
    public int inverseSurface;
    public int inverseOnSurface;
    public int error;
    public int onError;
    public int errorContainer;
    public int onErrorContainer;

    /** Builds a scheme from a seed (source) color. */
    public static Scheme create(int seedArgb, Variant variant, boolean dark, Spec spec) {
        return new Scheme(Hct.fromInt(seedArgb), variant, dark, spec);
    }

    private Scheme(Hct source, Variant variant, boolean dark, Spec spec) {
        // Official rule: the 2025 spec only covers four styles; anything else is
        // generated with the 2021 spec.
        boolean use2025 =
                spec == Spec.SPEC_2025
                        && (variant == Variant.NEUTRAL
                                || variant == Variant.TONAL_SPOT
                                || variant == Variant.VIBRANT
                                || variant == Variant.EXPRESSIVE);
        this.variant = variant;
        this.spec = use2025 ? Spec.SPEC_2025 : Spec.SPEC_2021;
        this.dark = dark;

        Palettes p = use2025 ? palettes2025(source, variant, dark) : palettes2021(source, variant);
        primaryPalette = p.primary;
        secondaryPalette = p.secondary;
        tertiaryPalette = p.tertiary;
        neutralPalette = p.neutral;
        neutralVariantPalette = p.neutralVariant;
        errorPalette = p.error;

        if (use2025) {
            resolve2025();
        } else {
            resolve2021();
        }
    }

    // ---------------------------------------------------------------- 2021 spec

    private void resolve2021() {
        primary = color(primaryPalette, dark ? 80 : 40);
        onPrimary = color(primaryPalette, dark ? 20 : 100);
        primaryContainer = color(primaryPalette, dark ? 30 : 90);
        onPrimaryContainer = color(primaryPalette, dark ? 90 : 30);

        secondary = color(secondaryPalette, dark ? 80 : 40);
        onSecondary = color(secondaryPalette, dark ? 20 : 100);
        secondaryContainer = color(secondaryPalette, dark ? 30 : 90);
        onSecondaryContainer = color(secondaryPalette, dark ? 90 : 30);

        tertiary = color(tertiaryPalette, dark ? 80 : 40);
        onTertiary = color(tertiaryPalette, dark ? 20 : 100);
        tertiaryContainer = color(tertiaryPalette, dark ? 30 : 90);
        onTertiaryContainer = color(tertiaryPalette, dark ? 90 : 30);

        surface = color(neutralPalette, dark ? 6 : 98);
        surfaceDim = surface;
        surfaceBright = surface;
        surfaceContainerLowest = color(neutralPalette, dark ? 4 : 100);
        surfaceContainerLow = color(neutralPalette, dark ? 10 : 96);
        surfaceContainer = color(neutralPalette, dark ? 12 : 94);
        surfaceContainerHigh = color(neutralPalette, dark ? 17 : 92);
        surfaceContainerHighest = color(neutralPalette, dark ? 22 : 90);
        onSurface = color(neutralPalette, dark ? 90 : 10);
        surfaceVariant = color(neutralVariantPalette, dark ? 30 : 90);
        onSurfaceVariant = color(neutralVariantPalette, dark ? 80 : 30);
        outline = color(neutralVariantPalette, dark ? 60 : 50);
        outlineVariant = color(neutralVariantPalette, dark ? 30 : 80);
        inverseSurface = color(neutralPalette, dark ? 90 : 20);
        inverseOnSurface = color(neutralPalette, dark ? 20 : 95);

        error = color(errorPalette, dark ? 80 : 40);
        onError = color(errorPalette, dark ? 20 : 100);
        errorContainer = color(errorPalette, dark ? 30 : 90);
        onErrorContainer = color(errorPalette, dark ? 90 : 30);
    }

    // ---------------------------------------------------------------- 2025 spec

    private void resolve2025() {
        boolean vibrant = variant == Variant.VIBRANT;
        double neutralHue = neutralPalette.getHue();
        boolean yellowNeutral = Hct.isYellow(neutralHue);

        // --- surfaces -----------------------------------------------------
        double surfaceTone = dark ? 4 : (yellowNeutral ? 99 : (vibrant ? 97 : 98));
        double dimTone = dark ? 4 : (yellowNeutral ? 90 : (vibrant ? 85 : 87));
        double brightTone = dark ? 18 : (yellowNeutral ? 99 : (vibrant ? 97 : 98));

        surface = color(neutralPalette, surfaceTone);
        surfaceDim = color(neutralPalette, dimTone, dark ? 1.0 : surfaceEdgeChroma());
        surfaceBright = color(neutralPalette, brightTone, dark ? surfaceEdgeChroma() : 1.0);
        surfaceContainerLowest = color(neutralPalette, dark ? 0 : 100);
        surfaceContainerLow =
                color(
                        neutralPalette,
                        dark ? 6 : (yellowNeutral ? 98 : (vibrant ? 95 : 96)),
                        containerLowChroma());
        surfaceContainer = color(neutralPalette, dark ? 9 : (yellowNeutral ? 96 : (vibrant ? 92 : 94)));
        surfaceContainerHigh =
                color(neutralPalette, dark ? 12 : (yellowNeutral ? 94 : (vibrant ? 90 : 92)));
        surfaceContainerHighest =
                color(neutralPalette, dark ? 15 : (yellowNeutral ? 92 : (vibrant ? 88 : 90)));
        surfaceVariant = color(neutralVariantPalette, dark ? 30 : 90);

        // Background used by every contrast curve in the 2025 spec.
        double bgTone = dark ? brightTone : dimTone;

        double onSurfaceTone =
                vibrant
                        ? contrastTone(tMaxC(neutralPalette, 0, 100, 1.1), bgTone, dark ? 11 : 9)
                        : contrastTone(bgTone, bgTone, dark ? 11 : 9);
        onSurface = color(neutralPalette, onSurfaceTone, neutralTextChroma());
        onSurfaceVariant =
                color(neutralPalette, contrastTone(bgTone, bgTone, dark ? 6 : 4.5), neutralTextChroma());
        outline = color(neutralPalette, contrastTone(bgTone, bgTone, 3), neutralTextChroma());
        outlineVariant = color(neutralPalette, contrastTone(bgTone, bgTone, 1.5), neutralTextChroma());

        // --- primary -------------------------------------------------------
        double primaryTone;
        double primaryContainerTone;
        boolean cyanPrimary = Hct.isCyan(primaryPalette.getHue());
        boolean yellowPrimary = Hct.isYellow(primaryPalette.getHue());
        switch (variant) {
            case NEUTRAL:
                primaryTone = dark ? 80 : 40;
                primaryContainerTone = dark ? 30 : 90;
                break;
            case TONAL_SPOT:
                primaryTone = dark ? 80 : tMaxC(primaryPalette, 0, 100);
                primaryContainerTone =
                        dark ? tMinC(primaryPalette, 35, 93) : tMaxC(primaryPalette, 0, 90);
                break;
            case EXPRESSIVE:
                primaryTone =
                        tMaxC(
                                primaryPalette,
                                0,
                                dark ? (cyanPrimary ? 88 : 98) : (yellowPrimary ? 25 : 98));
                primaryContainerTone =
                        dark
                                ? tMinC(primaryPalette, 30, 93)
                                : tMaxC(primaryPalette, 78, cyanPrimary ? 88 : 90);
                break;
            default: // VIBRANT
                primaryTone = tMaxC(primaryPalette, 0, cyanPrimary ? 88 : 98);
                primaryContainerTone =
                        dark
                                ? tMinC(primaryPalette, 66, 93)
                                : tMaxC(primaryPalette, 66, cyanPrimary ? 88 : 93);
                break;
        }
        // Contrast curve carried by the 2025 "primary" role: 4.5:1 against
        // surfaceDim (light) / surfaceBright (dark). The raw tMaxC tone is often
        // far too light to be used as a foreground color, so it is only kept
        // when it already reaches the ratio.
        if (Contrast.ratioOfTones(primaryTone, bgTone) < 4.5) {
            primaryTone = foregroundTone(bgTone, 4.5);
        }
        primary = color(primaryPalette, primaryTone);
        onPrimary = color(primaryPalette, contrastTone(primaryTone, primaryTone, 6));
        primaryContainer = color(primaryPalette, primaryContainerTone);
        onPrimaryContainer =
                color(primaryPalette, contrastTone(primaryContainerTone, primaryContainerTone, 6));

        // --- secondary -----------------------------------------------------
        double secondaryTone;
        double secondaryContainerTone;
        switch (variant) {
            case NEUTRAL:
                secondaryTone = dark ? tMinC(secondaryPalette, 0, 98) : tMaxC(secondaryPalette, 0, 100);
                secondaryContainerTone = dark ? 25 : 90;
                break;
            case TONAL_SPOT:
            case EXPRESSIVE:
                secondaryTone = dark ? 80 : tMaxC(secondaryPalette, 0, 100);
                secondaryContainerTone =
                        variant == Variant.EXPRESSIVE
                                ? (dark ? 15 : tMaxC(secondaryPalette, 90, 95))
                                : (dark ? 25 : 90);
                break;
            default: // VIBRANT
                secondaryTone = dark ? tMaxC(secondaryPalette, 0, 90) : tMaxC(secondaryPalette, 0, 98);
                secondaryContainerTone =
                        dark ? tMinC(secondaryPalette, 30, 40) : tMaxC(secondaryPalette, 84, 90);
                break;
        }
        secondary = color(secondaryPalette, secondaryTone);
        onSecondary = color(secondaryPalette, contrastTone(secondaryTone, secondaryTone, 6));
        secondaryContainer = color(secondaryPalette, secondaryContainerTone);
        onSecondaryContainer =
                color(secondaryPalette, contrastTone(secondaryContainerTone, secondaryContainerTone, 6));

        // --- tertiary ------------------------------------------------------
        boolean cyanTertiary = Hct.isCyan(tertiaryPalette.getHue());
        double tertiaryTone;
        double tertiaryContainerTone;
        switch (variant) {
            case NEUTRAL:
                tertiaryTone = dark ? tMaxC(tertiaryPalette, 0, 98) : tMaxC(tertiaryPalette, 0, 100);
                tertiaryContainerTone =
                        dark ? tMaxC(tertiaryPalette, 0, 93) : tMaxC(tertiaryPalette, 0, 96);
                break;
            case TONAL_SPOT:
                tertiaryTone = dark ? tMaxC(tertiaryPalette, 0, 98) : tMaxC(tertiaryPalette, 0, 100);
                tertiaryContainerTone =
                        dark ? tMaxC(tertiaryPalette, 0, 93) : tMaxC(tertiaryPalette, 0, 100);
                break;
            case EXPRESSIVE:
                tertiaryTone =
                        tMaxC(tertiaryPalette, 0, dark ? (cyanTertiary ? 88 : 98) : (cyanTertiary ? 88 : 100));
                tertiaryContainerTone =
                        dark
                                ? tMaxC(tertiaryPalette, 75, cyanTertiary ? 88 : 93)
                                : tMaxC(tertiaryPalette, 75, cyanTertiary ? 88 : 100);
                break;
            default: // VIBRANT
                tertiaryTone =
                        tMaxC(tertiaryPalette, 0, dark ? (cyanTertiary ? 88 : 98) : (cyanTertiary ? 88 : 100));
                tertiaryContainerTone =
                        dark ? tMaxC(tertiaryPalette, 0, 93) : tMaxC(tertiaryPalette, 72, 100);
                break;
        }
        tertiary = color(tertiaryPalette, tertiaryTone);
        onTertiary = color(tertiaryPalette, contrastTone(tertiaryTone, tertiaryTone, 6));
        tertiaryContainer = color(tertiaryPalette, tertiaryContainerTone);
        onTertiaryContainer =
                color(tertiaryPalette, contrastTone(tertiaryContainerTone, tertiaryContainerTone, 6));

        // --- inverse / error ------------------------------------------------
        double inverseTone = dark ? 98 : 4;
        inverseSurface = color(neutralPalette, inverseTone);
        inverseOnSurface = color(neutralPalette, contrastTone(inverseTone, inverseTone, 7));

        error = color(errorPalette, dark ? 80 : 40);
        onError = color(errorPalette, dark ? 20 : 100);
        errorContainer = color(errorPalette, dark ? 30 : 90);
        onErrorContainer = color(errorPalette, dark ? 90 : 30);
    }

    /** Chroma multiplier for surfaceDim (light) / surfaceBright (dark). */
    private double surfaceEdgeChroma() {
        switch (variant) {
            case NEUTRAL:
                return 2.5;
            case TONAL_SPOT:
                return 1.7;
            case EXPRESSIVE:
                return Hct.isYellow(neutralPalette.getHue()) ? 2.7 : 1.75;
            case VIBRANT:
                return 1.36;
            default:
                return 1.0;
        }
    }

    /** Chroma multiplier for surfaceContainerLow. */
    private double containerLowChroma() {
        switch (variant) {
            case NEUTRAL:
                return 1.3;
            case TONAL_SPOT:
                return 1.25;
            case EXPRESSIVE:
                return Hct.isYellow(neutralPalette.getHue()) ? 1.3 : 1.15;
            case VIBRANT:
                return 1.08;
            default:
                return 1.0;
        }
    }

    /** Chroma multiplier shared by onSurface / onSurfaceVariant / outline / outlineVariant. */
    private double neutralTextChroma() {
        switch (variant) {
            case NEUTRAL:
                return 2.2;
            case TONAL_SPOT:
                return 1.7;
            case EXPRESSIVE:
                return Hct.isYellow(neutralPalette.getHue()) ? (dark ? 3.0 : 2.3) : 1.6;
            default:
                return 1.0;
        }
    }

    // ---------------------------------------------------------------- palettes

    /** Six tonal palettes. */
    private static final class Palettes {
        TonalPalette primary;
        TonalPalette secondary;
        TonalPalette tertiary;
        TonalPalette neutral;
        TonalPalette neutralVariant;
        TonalPalette error;
    }

    private static Palettes palettes2021(Hct source, Variant variant) {
        double hue = source.getHue();
        double chroma = source.getChroma();
        Palettes p = new Palettes();
        switch (variant) {
            case MONOCHROME:
                p.primary = TonalPalette.fromHueAndChroma(hue, 0.0);
                p.secondary = TonalPalette.fromHueAndChroma(hue, 0.0);
                p.tertiary = TonalPalette.fromHueAndChroma(hue, 0.0);
                p.neutral = TonalPalette.fromHueAndChroma(hue, 0.0);
                p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 0.0);
                break;
            case NEUTRAL:
                p.primary = TonalPalette.fromHueAndChroma(hue, 12.0);
                p.secondary = TonalPalette.fromHueAndChroma(hue, 8.0);
                p.tertiary = TonalPalette.fromHueAndChroma(hue, 16.0);
                p.neutral = TonalPalette.fromHueAndChroma(hue, 2.0);
                p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 2.0);
                break;
            case TONAL_SPOT:
                p.primary = TonalPalette.fromHueAndChroma(hue, 36.0);
                p.secondary = TonalPalette.fromHueAndChroma(hue, 16.0);
                p.tertiary =
                        TonalPalette.fromHueAndChroma(
                                MathUtils.sanitizeDegreesDouble(hue + 60.0), 24.0);
                p.neutral = TonalPalette.fromHueAndChroma(hue, 6.0);
                p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 8.0);
                break;
            case VIBRANT:
                p.primary = TonalPalette.fromHueAndChroma(hue, 200.0);
                p.secondary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 41, 61, 101, 131, 181, 251, 301, 360},
                                        new double[] {18, 15, 10, 12, 15, 18, 15, 12, 12}),
                                24.0);
                p.tertiary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 41, 61, 101, 131, 181, 251, 301, 360},
                                        new double[] {35, 30, 20, 25, 30, 35, 30, 25, 25}),
                                32.0);
                p.neutral = TonalPalette.fromHueAndChroma(hue, 10.0);
                p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 12.0);
                break;
            case EXPRESSIVE:
                p.primary =
                        TonalPalette.fromHueAndChroma(
                                MathUtils.sanitizeDegreesDouble(hue + 240.0), 40.0);
                p.secondary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 21, 51, 121, 151, 191, 271, 321, 360},
                                        new double[] {45, 95, 45, 20, 45, 90, 45, 45, 45}),
                                24.0);
                p.tertiary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 21, 51, 121, 151, 191, 271, 321, 360},
                                        new double[] {120, 120, 20, 45, 20, 15, 20, 120, 120}),
                                32.0);
                p.neutral =
                        TonalPalette.fromHueAndChroma(
                                MathUtils.sanitizeDegreesDouble(hue + 15.0), 8.0);
                p.neutralVariant =
                        TonalPalette.fromHueAndChroma(
                                MathUtils.sanitizeDegreesDouble(hue + 15.0), 12.0);
                break;
            case RAINBOW:
                p.primary = TonalPalette.fromHueAndChroma(hue, 48.0);
                p.secondary = TonalPalette.fromHueAndChroma(hue, 16.0);
                p.tertiary =
                        TonalPalette.fromHueAndChroma(
                                MathUtils.sanitizeDegreesDouble(hue + 60.0), 24.0);
                p.neutral = TonalPalette.fromHueAndChroma(hue, 0.0);
                p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 0.0);
                break;
            case FRUIT_SALAD:
                p.primary =
                        TonalPalette.fromHueAndChroma(
                                MathUtils.sanitizeDegreesDouble(hue - 50.0), 48.0);
                p.secondary =
                        TonalPalette.fromHueAndChroma(
                                MathUtils.sanitizeDegreesDouble(hue - 50.0), 36.0);
                p.tertiary = TonalPalette.fromHueAndChroma(hue, 36.0);
                p.neutral = TonalPalette.fromHueAndChroma(hue, 10.0);
                p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 16.0);
                break;
            default: // FIDELITY, CONTENT
                p.primary = TonalPalette.fromHueAndChroma(hue, chroma);
                p.secondary =
                        TonalPalette.fromHueAndChroma(hue, Math.max(chroma - 32.0, chroma * 0.5));
                if (variant == Variant.FIDELITY) {
                    p.tertiary =
                            TonalPalette.fromHct(
                                    DislikeAnalyzer.fixIfDisliked(
                                            new TemperatureCache(source).getComplement()));
                } else {
                    p.tertiary =
                            TonalPalette.fromHct(
                                    DislikeAnalyzer.fixIfDisliked(
                                            new TemperatureCache(source)
                                                    .getAnalogousColors(3, 6)
                                                    .get(2)));
                }
                p.neutral = TonalPalette.fromHueAndChroma(hue, chroma / 8.0);
                p.neutralVariant = TonalPalette.fromHueAndChroma(hue, (chroma / 8.0) + 4.0);
                break;
        }
        p.error = TonalPalette.fromHueAndChroma(25.0, 84.0);
        return p;
    }

    private static Palettes palettes2025(Hct source, Variant variant, boolean dark) {
        double hue = source.getHue();
        Palettes p = new Palettes();
        double neutralHue;
        double neutralChroma;
        switch (variant) {
            case NEUTRAL:
                p.primary = TonalPalette.fromHueAndChroma(hue, Hct.isBlue(hue) ? 12 : 8);
                p.secondary = TonalPalette.fromHueAndChroma(hue, Hct.isBlue(hue) ? 6 : 4);
                p.tertiary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 38, 105, 161, 204, 278, 333, 360},
                                        new double[] {-32, 26, 10, -39, 24, -15, -32}),
                                20);
                p.neutral = TonalPalette.fromHueAndChroma(hue, 1.4);
                p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 1.4 * 2.2);
                p.error = TonalPalette.fromHueAndChroma(errorHue2025(source), 50);
                break;
            case TONAL_SPOT:
                p.primary = TonalPalette.fromHueAndChroma(hue, dark ? 26 : 32);
                p.secondary = TonalPalette.fromHueAndChroma(hue, 16);
                p.tertiary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 20, 71, 161, 333, 360},
                                        new double[] {-40, 48, -32, 40, -32}),
                                28);
                p.neutral = TonalPalette.fromHueAndChroma(hue, 5);
                p.neutralVariant = TonalPalette.fromHueAndChroma(hue, 5 * 1.7);
                p.error = TonalPalette.fromHueAndChroma(errorHue2025(source), 60);
                break;
            case EXPRESSIVE:
                p.primary = TonalPalette.fromHueAndChroma(hue, dark ? 36 : 48);
                p.secondary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 105, 140, 204, 253, 278, 300, 333, 360},
                                        new double[] {-160, 155, -100, 96, -96, -156, -165, -160}),
                                dark ? 16 : 24);
                p.tertiary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 105, 140, 204, 253, 278, 300, 333, 360},
                                        new double[] {
                                            -165, 160, -105, 101, -101, -160, -170, -165
                                        }),
                                48);
                neutralHue = expressiveNeutralHue(source);
                neutralChroma = dark ? (Hct.isYellow(neutralHue) ? 6 : 14) : 18;
                p.neutral = TonalPalette.fromHueAndChroma(neutralHue, neutralChroma);
                p.neutralVariant =
                        TonalPalette.fromHueAndChroma(
                                neutralHue,
                                neutralChroma
                                        * (neutralHue >= 105 && neutralHue < 125 ? 1.6 : 2.3));
                p.error = TonalPalette.fromHueAndChroma(errorHue2025(source), 64);
                break;
            default: // VIBRANT
                p.primary = TonalPalette.fromHueAndChroma(hue, 74);
                p.secondary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 38, 105, 140, 333, 360},
                                        new double[] {-14, 10, -14, 10, -14}),
                                56);
                p.tertiary =
                        TonalPalette.fromHueAndChroma(
                                rotatedHue(
                                        source,
                                        new double[] {0, 38, 71, 105, 140, 161, 253, 333, 360},
                                        new double[] {-72, 35, 24, -24, 62, 50, 62, -72}),
                                56);
                neutralHue = vibrantNeutralHue(source);
                neutralChroma = 28;
                p.neutral = TonalPalette.fromHueAndChroma(neutralHue, neutralChroma);
                p.neutralVariant = TonalPalette.fromHueAndChroma(neutralHue, neutralChroma * 1.29);
                p.error = TonalPalette.fromHueAndChroma(errorHue2025(source), 80);
                break;
        }
        return p;
    }

    private static double expressiveNeutralHue(Hct source) {
        return rotatedHue(
                source,
                new double[] {0, 71, 124, 253, 278, 300, 360},
                new double[] {10, 0, 10, 0, 10, 0});
    }

    private static double vibrantNeutralHue(Hct source) {
        return rotatedHue(
                source, new double[] {0, 38, 105, 140, 333, 360}, new double[] {-14, 10, -14, 10, -14});
    }

    private static double errorHue2025(Hct source) {
        return piecewise(
                source,
                new double[] {0, 3, 13, 23, 33, 43, 153, 273, 360},
                new double[] {12, 22, 32, 12, 22, 32, 22, 12});
    }

    /** Official DynamicScheme.getRotatedHue. */
    private static double rotatedHue(Hct source, double[] breakpoints, double[] rotations) {
        double rotation = piecewise(source, breakpoints, rotations);
        if (Math.min(breakpoints.length - 1, rotations.length) <= 0) {
            rotation = 0;
        }
        return MathUtils.sanitizeDegreesDouble(source.getHue() + rotation);
    }

    /** Official DynamicScheme.getPiecewiseValue. */
    private static double piecewise(Hct source, double[] breakpoints, double[] hues) {
        int size = Math.min(breakpoints.length - 1, hues.length);
        double sourceHue = source.getHue();
        for (int i = 0; i < size; i++) {
            if (sourceHue >= breakpoints[i] && sourceHue < breakpoints[i + 1]) {
                return MathUtils.sanitizeDegreesDouble(hues[i]);
            }
        }
        return sourceHue;
    }

    /** Official tMaxC: the tone in [lower, upper] whose color has the most chroma. */
    private static double tMaxC(TonalPalette palette, double lower, double upper) {
        return tMaxC(palette, lower, upper, 1.0);
    }

    private static double tMaxC(
            TonalPalette palette, double lower, double upper, double chromaMultiplier) {
        double answer =
                findBestToneForChroma(
                        palette.getHue(), palette.getChroma() * chromaMultiplier, 100, true);
        return MathUtils.clampDouble(lower, upper, answer);
    }

    /** Official tMinC: the tone in [lower, upper] whose color has the least chroma. */
    private static double tMinC(TonalPalette palette, double lower, double upper) {
        double answer = findBestToneForChroma(palette.getHue(), palette.getChroma(), 0, false);
        return MathUtils.clampDouble(lower, upper, answer);
    }

    private static double findBestToneForChroma(
            double hue, double chroma, double tone, boolean byDecreasingTone) {
        double answer = tone;
        Hct bestCandidate = Hct.from(hue, chroma, answer);
        while (bestCandidate.getChroma() < chroma) {
            if (tone < 0 || tone > 100) {
                break;
            }
            tone += byDecreasingTone ? -1.0 : 1.0;
            Hct newCandidate = Hct.from(hue, chroma, tone);
            if (bestCandidate.getChroma() < newCandidate.getChroma()) {
                bestCandidate = newCandidate;
                answer = tone;
            }
        }
        return answer;
    }

    /** Official DynamicColor.foregroundTone. */
    private static double foregroundTone(double bgTone, double ratio) {
        double lighterTone = Contrast.lighterUnsafe(bgTone, ratio);
        double darkerTone = Contrast.darkerUnsafe(bgTone, ratio);
        double lighterRatio = Contrast.ratioOfTones(lighterTone, bgTone);
        double darkerRatio = Contrast.ratioOfTones(darkerTone, bgTone);
        boolean preferLighter = tonePrefersLightForeground(bgTone);
        if (preferLighter) {
            boolean negligibleDifference =
                    Math.abs(lighterRatio - darkerRatio) < 0.1
                            && lighterRatio < ratio
                            && darkerRatio < ratio;
            if (lighterRatio >= ratio || lighterRatio >= darkerRatio || negligibleDifference) {
                return lighterTone;
            }
            return darkerTone;
        }
        return darkerRatio >= ratio || darkerRatio >= lighterRatio ? darkerTone : lighterTone;
    }

    private static boolean tonePrefersLightForeground(double tone) {
        return Math.round(tone) < 60;
    }

    /** Keep the initial tone when it already reaches the ratio, else solve for one that does. */
    private static double contrastTone(double initialTone, double bgTone, double ratio) {
        if (Contrast.ratioOfTones(initialTone, bgTone) >= ratio) {
            return initialTone;
        }
        return foregroundTone(bgTone, ratio);
    }

    private int color(TonalPalette palette, double tone) {
        return color(palette, tone, 1.0);
    }

    private int color(TonalPalette palette, double tone, double chromaMultiplier) {
        if (chromaMultiplier == 1.0) {
            return palette.tone(tone);
        }
        return Hct.from(palette.getHue(), palette.getChroma() * chromaMultiplier, tone).toInt();
    }
}
