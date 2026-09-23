package io.github.howard20181.hyperos.fcmlive.theme;

import android.content.Context;

import io.github.howard20181.hyperos.fcmlive.mcu.Scheme;

/**
 * Builds the runtime color scheme. The seed comes from the system's own
 * Material You accent (so the palette still follows the wallpaper); the style
 * and spec version come from {@link ThemePrefs}.
 */
public final class ThemeEngine {

    /** Brand seed used when the platform has no dynamic color (below API 31). */
    private static final int FALLBACK_SEED = 0xFF8B4A5A;

    private static volatile AppPalette sCached;
    private static volatile String sCacheKey;

    private ThemeEngine() {
    }

    /** Drop the cached palette; call after any appearance setting changes. */
    public static void invalidate() {
        sCached = null;
        sCacheKey = null;
    }

    public static AppPalette palette(Context context) {
        boolean dark = ThemePrefs.isDark(context);
        Scheme.Variant variant = ThemePrefs.paletteStyle(context);
        int spec = ThemePrefs.specVersion(context);
        boolean amoled = dark && ThemePrefs.isAmoled(context);
        boolean dynamic = ThemePrefs.dynamicColor(context);
        int seed = seed(context, dynamic);

        String key = (dark ? "d" : "l") + ':' + variant.ordinal() + ':' + spec + ':'
                + (amoled ? 'a' : 'n') + ':' + (dynamic ? 'w' : 'c') + ':' + seed;
        AppPalette cached = sCached;
        if (cached != null && key.equals(sCacheKey)) {
            return cached;
        }
        Scheme.Spec specEnum = spec == ThemePrefs.SPEC_2025
                ? Scheme.Spec.SPEC_2025 : Scheme.Spec.SPEC_2021;
        AppPalette built = new AppPalette(Scheme.create(seed, variant, dark, specEnum), dark, amoled);
        sCached = built;
        sCacheKey = key;
        return built;
    }

    /**
     * Seed color: with dynamic color on, the middle tone of the system accent
     * palette carries the wallpaper's hue (Android 12+); with dynamic color
     * off, the user's own seed is used instead. Both feed the same Material
     * color utilities, so derivation stays spec-compliant either way. If the
     * platform does not expose a system accent, the app's brand color is used.
     */
    private static int seed(Context context, boolean dynamic) {
        if (!dynamic) {
            int custom = ThemePrefs.seedColor(context);
            if (custom != 0) {
                return custom;
            }
        }
        try {
            return context.getResources().getColor(android.R.color.system_accent1_500);
        } catch (Throwable ignored) {
            // Fall through to the brand seed.
        }
        return FALLBACK_SEED;
    }
}
