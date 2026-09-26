package io.github.howard20181.hyperos.fcmlive.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme

/**
 * Appearance settings: theme mode (including the AMOLED pure-black variant),
 * dynamic color with a custom seed, Material palette style, color spec version
 * and the in-app language. Stored in a private prefs file — unlike the allowlist
 * these are UI-only, so they never need to reach system_server.
 *
 * Writes use `apply()`, not `commit()`: they run on the main
 * thread from click handlers, and everything that reads them back is this same
 * process, whose in-memory copy `apply()` updates synchronously. Only
 * `Prefs` needs `commit()`, because there the write has to be on
 * disk before the broadcast that announces it crosses into system_server.
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
    private const val KEY_LANGUAGE = "language"
    /** Follow the device language (the default: the app is not pinned). */
    const val LANGUAGE_SYSTEM = -1
    /** Number of entries in `R.array.language_entries`. */
    const val LANGUAGE_COUNT = 2

    /** Default style matches what Android's own Monet engine generates. */
    private val DEFAULT_STYLE = Scheme.Variant.TONAL_SPOT.ordinal
    /** Default spec is the current Material 3 Expressive one. */
    private const val DEFAULT_SPEC = SPEC_2025

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun themeMode(context: Context): Int {
        val p = prefs(context)
        var mode = p.getInt(KEY_THEME_MODE, MODE_SYSTEM)
        // Migrate the old standalone AMOLED switch into a real theme mode.
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

    @JvmStatic
    fun specVersion(context: Context): Int {
        val spec = prefs(context).getInt(KEY_SPEC, DEFAULT_SPEC)
        return if (spec == SPEC_2021 || spec == SPEC_2025) spec else DEFAULT_SPEC
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

    /** Whether the resolved mode is the AMOLED pure-black variant. */
    @JvmStatic
    fun isAmoled(context: Context): Boolean = themeMode(context) == MODE_AMOLED

    /**
     * In-app language, or [LANGUAGE_SYSTEM] to follow the device.
     *
     * Not strictly a theme setting, but it is applied by the very same
     * configuration rewrite in [ThemeSupport.attach] that forces
     * light/dark, so the two are changed by one and the same recreate. Going
     * through our own preference instead of `LocaleManager` keeps the
     * behaviour identical on every ROM (HyperOS included) and works below the
     * per-app-language APIs.
     */
    @JvmStatic
    fun language(context: Context): Int {
        val index = prefs(context).getInt(KEY_LANGUAGE, LANGUAGE_SYSTEM)
        return if (index in 0 until LANGUAGE_COUNT) index else LANGUAGE_SYSTEM
    }

    @JvmStatic
    fun setLanguage(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_LANGUAGE, index).apply()
    }

    /** Locale to force, or `null` to keep the device's own choice. */
    @JvmStatic
    fun locale(context: Context): Locale? {
        return when (language(context)) {
            0 -> Locale.SIMPLIFIED_CHINESE
            1 -> Locale.ENGLISH
            else -> null
        }
    }

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
