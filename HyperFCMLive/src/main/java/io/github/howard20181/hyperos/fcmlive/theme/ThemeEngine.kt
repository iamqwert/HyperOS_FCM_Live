package io.github.howard20181.hyperos.fcmlive.theme

import android.content.Context
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme

/**
 * Builds the runtime color scheme. The seed comes from the system's own
 * Material You accent (so the palette still follows the wallpaper); the style
 * and spec version come from [ThemePrefs].
 */
object ThemeEngine {

    /** Brand seed used when the platform has no dynamic color (below API 31). */
    private val FALLBACK_SEED: Int = 0xFF8B4A5A.toInt()

    @Volatile
    private var sCached: AppPalette? = null

    @Volatile
    private var sCacheKey: String? = null

    /** Drop the cached palette; call after any appearance setting changes. */
    @JvmStatic
    fun invalidate() {
        sCached = null
        sCacheKey = null
    }

    @JvmStatic
    fun palette(context: Context): AppPalette {
        val dark = ThemePrefs.isDark(context)
        val variant = ThemePrefs.paletteStyle(context)
        val spec = ThemePrefs.specVersion(context)
        val amoled = dark && ThemePrefs.isAmoled(context)
        val dynamic = ThemePrefs.dynamicColor(context)
        val seed = seed(context, dynamic)

        val key = (if (dark) "d" else "l") + ':' + variant.ordinal + ':' + spec + ':' +
            (if (amoled) 'a' else 'n') + ':' + (if (dynamic) 'w' else 'c') + ':' + seed
        val cached = sCached
        if (cached != null && key == sCacheKey) {
            return cached
        }
        val specEnum = if (spec == ThemePrefs.SPEC_2025) {
            Scheme.Spec.SPEC_2025
        } else {
            Scheme.Spec.SPEC_2021
        }
        val built = AppPalette(Scheme.create(seed, variant, dark, specEnum), dark, amoled)
        sCached = built
        sCacheKey = key
        return built
    }

    /**
     * Seed color: with dynamic color on, the middle tone of the system accent
     * palette carries the wallpaper's hue (Android 12+); with dynamic color
     * off, the user's own seed is used instead. Both feed the same Material
     * color utilities, so derivation stays spec-compliant either way. If the
     * platform does not expose a system accent, the app's brand color is used.
     */
    private fun seed(context: Context, dynamic: Boolean): Int {
        if (!dynamic) {
            val custom = ThemePrefs.seedColor(context)
            if (custom != 0) {
                return custom
            }
        }
        try {
            @Suppress("DEPRECATION")
            return context.resources.getColor(android.R.color.system_accent1_500)
        } catch (ignored: Throwable) {
            // Fall through to the brand seed.
        }
        return FALLBACK_SEED
    }
}
