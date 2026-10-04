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
 * A Material Design 3 dynamic color scheme: the six tonal palettes plus the resolved color roles
 * for one (seed color, palette style, spec version, light/dark) combination.
 *
 * This is a thin adapter, and deliberately so. The rules are **not** stated here: they live in
 * [ColorSpec2021] and [ColorSpec2025], which are ported from upstream and decide everything —
 * which palette a style uses, what tone each role starts at, what it is measured against, and how
 * it moves with the contrast level. This class only says *which* roles the app reads and resolves
 * them once, so the UI never walks the rule engine.
 *
 * Every role is resolved once, in the constructor.
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
        FRUIT_SALAD;

        /**
         * Whether the Expressive (2025) spec publishes rules for this style.
         *
         * The 2025 spec covers four styles and no more; every other style is generated with the
         * 2021 rules (official `DynamicScheme.maybeFallbackSpecVersion`). That makes the two
         * appearance settings *not* independent — a "2025" label on a style that falls back is a
         * label contradicting the colors on screen — so this is the one place the rule is stated.
         * The style menu, the spec menu, [ThemePrefs.specVersion] and
         * [DynamicScheme.maybeFallbackSpecVersion] all ask this instead of repeating the list.
         */
        val supportsExpressive2025: Boolean
            get() = this == NEUTRAL || this == TONAL_SPOT || this == VIBRANT ||
                this == EXPRESSIVE
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

    /**
     * The spec version **in force**, which is not always the one requested: a style the 2025 spec
     * has no rules for is generated with the 2021 rules and reports 2021 here. See
     * [DynamicScheme.maybeFallbackSpecVersion].
     */
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
    var inversePrimary: Int = 0
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
        val platform = DynamicScheme.Platform.PHONE
        // The app ships the design as spec'd and offers no contrast slider, so
        // every scheme is built at the standard contrast level. The engine
        // supports -1..1; nothing here would have to change to expose it.
        val contrastLevel = 0.0

        // Palettes come from the spec that was *requested* — upstream builds a
        // scheme's palettes before the version is resolved, and the 2025 spec's
        // palette rules already fall through to the 2021 ones for the styles it
        // does not cover.
        val requested = ColorSpecs.get(spec)
        val p = requested.getPrimaryPalette(variant, source, dark, platform, contrastLevel)
        val s = requested.getSecondaryPalette(variant, source, dark, platform, contrastLevel)
        val t = requested.getTertiaryPalette(variant, source, dark, platform, contrastLevel)
        val n = requested.getNeutralPalette(variant, source, dark, platform, contrastLevel)
        val nv = requested.getNeutralVariantPalette(variant, source, dark, platform, contrastLevel)
        val err = requested.getErrorPalette(variant, source, dark, platform, contrastLevel)
            ?: TonalPalette.fromHueAndChroma(25.0, 84.0)

        val scheme = DynamicScheme(
            source, variant, dark, contrastLevel, platform, spec, p, s, t, n, nv, err
        )

        this.variant = variant
        this.spec = scheme.specVersion
        primaryPalette = scheme.primaryPalette
        secondaryPalette = scheme.secondaryPalette
        tertiaryPalette = scheme.tertiaryPalette
        neutralPalette = scheme.neutralPalette
        neutralVariantPalette = scheme.neutralVariantPalette
        errorPalette = scheme.errorPalette

        // Roles are built from the newest spec (as upstream does) and resolved
        // against this scheme's own version, so each one picks the definition
        // that applies to it.
        val roles = ColorSpecs.newest
        primary = scheme.getArgb(roles.primary())
        onPrimary = scheme.getArgb(roles.onPrimary())
        primaryContainer = scheme.getArgb(roles.primaryContainer())
        onPrimaryContainer = scheme.getArgb(roles.onPrimaryContainer())

        secondary = scheme.getArgb(roles.secondary())
        onSecondary = scheme.getArgb(roles.onSecondary())
        secondaryContainer = scheme.getArgb(roles.secondaryContainer())
        onSecondaryContainer = scheme.getArgb(roles.onSecondaryContainer())

        tertiary = scheme.getArgb(roles.tertiary())
        onTertiary = scheme.getArgb(roles.onTertiary())
        tertiaryContainer = scheme.getArgb(roles.tertiaryContainer())
        onTertiaryContainer = scheme.getArgb(roles.onTertiaryContainer())

        surface = scheme.getArgb(roles.surface())
        surfaceDim = scheme.getArgb(roles.surfaceDim())
        surfaceBright = scheme.getArgb(roles.surfaceBright())
        surfaceContainerLowest = scheme.getArgb(roles.surfaceContainerLowest())
        surfaceContainerLow = scheme.getArgb(roles.surfaceContainerLow())
        surfaceContainer = scheme.getArgb(roles.surfaceContainer())
        surfaceContainerHigh = scheme.getArgb(roles.surfaceContainerHigh())
        surfaceContainerHighest = scheme.getArgb(roles.surfaceContainerHighest())
        onSurface = scheme.getArgb(roles.onSurface())
        surfaceVariant = scheme.getArgb(roles.surfaceVariant())
        onSurfaceVariant = scheme.getArgb(roles.onSurfaceVariant())
        outline = scheme.getArgb(roles.outline())
        outlineVariant = scheme.getArgb(roles.outlineVariant())
        inversePrimary = scheme.getArgb(roles.inversePrimary())
        inverseSurface = scheme.getArgb(roles.inverseSurface())
        inverseOnSurface = scheme.getArgb(roles.inverseOnSurface())

        error = scheme.getArgb(roles.error())
        onError = scheme.getArgb(roles.onError())
        errorContainer = scheme.getArgb(roles.errorContainer())
        onErrorContainer = scheme.getArgb(roles.onErrorContainer())
    }

    companion object {
        /** Builds a scheme from a seed (source) color. */
        @JvmStatic
        fun create(seedArgb: Int, variant: Variant, dark: Boolean, spec: Spec): Scheme {
            return Scheme(Hct.fromInt(seedArgb), variant, dark, spec)
        }
    }
}
