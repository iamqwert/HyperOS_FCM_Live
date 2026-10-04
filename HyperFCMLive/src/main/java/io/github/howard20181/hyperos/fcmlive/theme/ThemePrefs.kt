package io.github.howard20181.hyperos.fcmlive.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme

/**
 * Appearance settings: theme mode (including the AMOLED pure-black variant),
 * dynamic color with a custom seed, Material palette style, and color spec.
 * UI-only prefs — they never reach system_server.
 *
 * Writes use `apply()` on the main thread; in-memory reads see them immediately.
 */
object ThemePrefs {

    const val PREFS = "fcmlive_theme"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2
    /** Pure-black dark variant for OLED panels. */
    const val MODE_AMOLED = 3

    const val SPEC_2021 = 0
    const val SPEC_2025 = 1

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_PALETTE_STYLE = "palette_style"
    private const val KEY_SPEC = "color_spec"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_SEED_COLOR = "seed_color"
    /** Legacy flag from when AMOLED was a separate switch; migrated on read. */
    private const val KEY_AMOLED = "amoled"

    /** Default style matches what Android's own Monet engine generates. */
    private val DEFAULT_STYLE = Scheme.Variant.TONAL_SPOT.ordinal
    private const val DEFAULT_SPEC = SPEC_2025

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun themeMode(context: Context): Int {
        val p = prefs(context)
        var mode = p.getInt(KEY_THEME_MODE, MODE_SYSTEM)
        if (mode == MODE_DARK && p.getBoolean(KEY_AMOLED, false)) {
            mode = MODE_AMOLED
            p.edit().putInt(KEY_THEME_MODE, MODE_AMOLED).remove(KEY_AMOLED).apply()
        }
        return if (mode in MODE_SYSTEM..MODE_AMOLED) mode else MODE_SYSTEM
    }

    @JvmStatic
    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    /**
     * The palette style in effect. Any style is valid on its own — what it can
     * *pair* with is the spec's business, see [specVersion].
     */
    @JvmStatic
    fun paletteStyle(context: Context): Scheme.Variant {
        val ordinal = prefs(context).getInt(KEY_PALETTE_STYLE, DEFAULT_STYLE)
        val values = Scheme.Variant.values()
        return if (ordinal in values.indices) values[ordinal] else values[DEFAULT_STYLE]
    }

    @JvmStatic
    fun setPaletteStyle(context: Context, variant: Scheme.Variant) {
        prefs(context).edit().putInt(KEY_PALETTE_STYLE, variant.ordinal).apply()
    }

    /**
     * The spec to honour, **resolved** against the style in effect.
     *
     * Expressive (2025) publishes rules for four styles and nothing else
     * ([Scheme.Variant.supportsExpressive2025]); asked for on any other style it
     * renders 2021 (upstream `DynamicScheme.maybeFallbackSpecVersion`). The
     * value stored here is the user's *request*, and this call is what turns it
     * into the spec actually in force — which is why the appearance page shows
     * this and not the stored int: a row labelled 2025 above colors generated
     * with the 2021 rules is a label contradicting the screen.
     *
     * The demotion is deliberately **not** written back. A style change is not
     * a decision about the spec, and rewriting the stored request on read made
     * one: a single frame rendered with a non-supporting style retired 2025 on
     * disk for good, so the user's next visit to an expressive style came back
     * 2021 with nothing on screen to explain it. Keeping the request means the
     * pair re-resolves the moment the style can take it again, and the two rows
     * are always read together (see `ui/AboutScreen.kt`), so they cannot
     * disagree either way.
     */
    @JvmStatic
    fun specVersion(context: Context): Int {
        val stored = prefs(context).getInt(KEY_SPEC, DEFAULT_SPEC)
        val requested = if (stored == SPEC_2021 || stored == SPEC_2025) stored else DEFAULT_SPEC
        return if (requested == SPEC_2025 && !paletteStyle(context).supportsExpressive2025) {
            SPEC_2021
        } else {
            requested
        }
    }

    @JvmStatic
    fun setSpecVersion(context: Context, spec: Int) {
        prefs(context).edit().putInt(KEY_SPEC, spec).apply()
    }

    /** Whether colors follow the wallpaper; when off, [seedColor] wins. */
    @JvmStatic
    fun dynamicColor(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DYNAMIC_COLOR, true)
    }

    @JvmStatic
    fun setDynamicColor(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    /** Custom seed (ARGB) used while dynamic color is off; 0 means unset. */
    @JvmStatic
    fun seedColor(context: Context): Int {
        return prefs(context).getInt(KEY_SEED_COLOR, 0)
    }

    @JvmStatic
    fun setSeedColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_SEED_COLOR, color).apply()
    }

    @JvmStatic
    fun isAmoled(context: Context): Boolean = themeMode(context) == MODE_AMOLED

    /** Resolved dark/light for the current mode (MODE_SYSTEM reads the device). */
    @JvmStatic
    fun isDark(context: Context): Boolean {
        return when (themeMode(context)) {
            MODE_LIGHT -> false
            MODE_DARK, MODE_AMOLED -> true
            else -> {
                val mask = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
                mask == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
