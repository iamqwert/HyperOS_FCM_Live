package io.github.howard20181.hyperos.fcmlive.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme

/**
 * Appearance settings: theme mode, dynamic color with a custom seed, Material
 * palette style, and color spec. UI-only prefs — they never reach system_server.
 *
 * Writes use `apply()` on the main thread; in-memory reads see them immediately.
 */
object ThemePrefs {

    const val PREFS = "fcmlive_theme"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2
    /** Retired: the AMOLED pure-black variant, deleted with its ramp. */
    private const val MODE_AMOLED_RETIRED = 3

    const val SPEC_2021 = 0
    const val SPEC_2025 = 1
    /** The 2026 spec — only ever in force under the CMF style; see [specVersion]. */
    const val SPEC_2026 = 2

    /** Which look the app is dressed in. See [UiStyle]. */
    const val UI_STYLE_MATERIAL = 0
    /** Miuix: HyperOS row language — no leading icons, text to the margin. */
    const val UI_STYLE_MIUIX = 1
    /** Miuix's own screens, milled from the 柔光玻璃 material. See [UiStyle.SoftGlass]. */
    const val UI_STYLE_SOFT_GLASS = 2

    private const val KEY_UI_STYLE = "ui_style"
    /** Updating installs within this window of their first install read as fresh. */
    private const val UPGRADE_TOLERANCE_MS = 5_000L
    /** Monet inside the Miuix style only; the Material style has 动态颜色. */
    private const val KEY_MIUIX_MONET = "miuix_monet"
    /**
     * Key colour for the Miuix Monet derivation (强调色). 0 — the default —
     * means "follow the wallpaper"; any other value is an ARGB seed fed to
     * Miuix's Monet engine in place of the platform accent. Only read while
     * [KEY_MIUIX_MONET] is on.
     */
    private const val KEY_MIUIX_ACCENT = "miuix_accent"
    /**
     * Whether the Miuix home foot floats: off is the library's docked pane, on
     * is the texture-blur glass capsule hovered over the list. Only the Miuix
     * look reads this — the SoftGlass look always floats its own glass tab bar,
     * so the row is hidden there and the value ignored.
     *
     * This key was briefly replaced by a material row (`miuix_bar_material`);
     * that experiment is reverted, and the first read migrates the material
     * pick back (1 — 柔光玻璃 — means floating, anything else means docked) and
     * removes the dead key. See [floatingBar].
     */
    private const val KEY_FLOATING_BAR = "miuix_floating_bar"
    /** Retired: the material row this key outlived; see [KEY_FLOATING_BAR]. */
    private const val KEY_BAR_MATERIAL = "miuix_bar_material"
    /** Retired: the liquid refraction sub-switch, deleted with the Example alignment. */
    private const val KEY_LIQUID_GLASS = "miuix_liquid_glass"

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_PALETTE_STYLE = "palette_style"
    private const val KEY_SPEC = "color_spec"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_SEED_COLOR = "seed_color"

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
        // A build before the AMOLED retirement may have stored the pure-black
        // variant; demote it to the ordinary dark mode rather than letting the
        // range check below fall back to 跟随系统.
        if (mode == MODE_AMOLED_RETIRED) {
            mode = MODE_DARK
            p.edit().putInt(KEY_THEME_MODE, MODE_DARK).apply()
        }
        return if (mode in MODE_SYSTEM..MODE_DARK) mode else MODE_SYSTEM
    }

    @JvmStatic
    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    /**
     * Which look the app wears. Held as an int rather than the enum so this file
     * keeps the same shape as every other accessor here, and so a value written by
     * a newer build falls back to [UI_STYLE_MATERIAL] instead of throwing.
     *
     * The KEY_UI_STYLE default is decided once, on the first read that finds the
     * key missing, and then written down: an install that has been *updated* onto
     * a MIUIX-capable build keeps [UI_STYLE_MATERIAL] — the look its user chose
     * by never being offered another — while a fresh install starts on
     * [UI_STYLE_SOFT_GLASS]. From then on the stored value alone decides, so either
     * side's own change persists the ordinary way.
     */
    @JvmStatic
    fun uiStyle(context: Context): Int {
        val p = prefs(context)
        if (!p.contains(KEY_UI_STYLE)) {
            val decided =
                if (isUpgradeFromPreMiuixBuild(context)) UI_STYLE_MATERIAL else UI_STYLE_SOFT_GLASS
            p.edit().putInt(KEY_UI_STYLE, decided).apply()
            return decided
        }
        val stored = p.getInt(KEY_UI_STYLE, UI_STYLE_MATERIAL)
        return when (stored) {
            UI_STYLE_MIUIX -> UI_STYLE_MIUIX
            UI_STYLE_SOFT_GLASS -> UI_STYLE_SOFT_GLASS
            else -> UI_STYLE_MATERIAL
        }
    }

    /**
     * Whether this install ran a build from before the Miuix look existed.
     * Two independent signals, either of which settles it:
     *  - the app's own local pref cache — every earlier build wrote its
     *    allowlist mirror and toggle state there as it ran;
     *  - the package having been updated at least once (first install predates
     *    the last update by more than the tolerance it takes to install twice
     *    in a row by hand).
     */
    private fun isUpgradeFromPreMiuixBuild(context: Context): Boolean {
        if (context
                .getSharedPreferences(
                    io.github.howard20181.hyperos.fcmlive.Prefs.LOCAL_PREFS,
                    Context.MODE_PRIVATE
                )
                .all
                .isNotEmpty()
        ) {
            return true
        }
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime < info.lastUpdateTime - UPGRADE_TOLERANCE_MS
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun setUiStyle(context: Context, style: Int) {
        prefs(context).edit().putInt(KEY_UI_STYLE, style).apply()
    }

    /**
     * Monet inside the Miuix look. Only that look reads this: the Material one has
     * its own 动态颜色 row, which drives the app's own colour engine, and the two
     * must not be allowed to disagree about where colors come from.
     *
     * Off until the user says otherwise: switching into the Miuix look should land
     * on Miuix's own HyperOS scheme, not silently repaint the app from the
     * wallpaper. Once flipped, the choice persists like every other appearance
     * setting.
     */
    @JvmStatic
    fun miuixMonet(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MIUIX_MONET, false)
    }

    @JvmStatic
    fun setMiuixMonet(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MIUIX_MONET, enabled).apply()
    }

    /**
     * The 强调色 picked under the Miuix look, as ARGB; 0 means "no pick —
     * follow the wallpaper". Kept next to [miuixMonet] because it only means
     * something while that switch is on: with Monet off the palette is Miuix's
     * own HyperOS scheme and no key colour can move it.
     */
    @JvmStatic
    fun miuixAccent(context: Context): Int {
        return prefs(context).getInt(KEY_MIUIX_ACCENT, 0)
    }

    @JvmStatic
    fun setMiuixAccent(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_MIUIX_ACCENT, color).apply()
    }

    /**
     * Whether the Miuix home foot floats. Off — the default — is the library's
     * docked pane; on is the texture-blur glass capsule. Only the Miuix look
     * reads this: the Material look always docks its own bar, and the SoftGlass
     * look always floats its own glass tab bar, so neither is allowed to
     * disagree with what their frame builds.
     *
     * The first read on an install that ran the brief material-row build
     * migrates that row's pick back into this switch — 柔光玻璃 (1) was the
     * floating foot, Miuix (0) the docked one — and removes the dead key, so a
     * capsule the user put in place is not handed back the pane.
     */
    @JvmStatic
    fun floatingBar(context: Context): Boolean {
        val p = prefs(context)
        if (!p.contains(KEY_FLOATING_BAR) && p.contains(KEY_BAR_MATERIAL)) {
            val migrated = p.getInt(KEY_BAR_MATERIAL, 0) == 1
            p.edit().putBoolean(KEY_FLOATING_BAR, migrated).remove(KEY_BAR_MATERIAL).apply()
            return migrated
        }
        return p.getBoolean(KEY_FLOATING_BAR, false)
    }

    @JvmStatic
    fun setFloatingBar(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_BAR, enabled).apply()
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

    /**
     * Stores the palette style. The spec menu is this row's superior — the spec
     * migrates the style (see [setSpecVersion]), never the other way around —
     * and under Spec 2026 the row is locked in the UI, so the CMF adopted there
     * is written by [setSpecVersion] alone.
     */
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
        val requested = if (stored in SPEC_2021..SPEC_2026) stored else DEFAULT_SPEC
        val style = paletteStyle(context)
        return when {
            // CMF publishes rules only under the 2026 spec (upstream SchemeCmf requires it).
            style == Scheme.Variant.CMF -> SPEC_2026
            // The 2026 spec's tone rules only resolve for CMF; every other style
            // runs what it can: the expressive four get 2025, the rest 2021.
            requested == SPEC_2026 && style.supportsExpressive2025 -> SPEC_2025
            requested == SPEC_2026 -> SPEC_2021
            requested == SPEC_2025 && !style.supportsExpressive2025 -> SPEC_2021
            else -> requested
        }
    }

    /**
     * Stores the spec request and keeps the style row consistent on disk — the
     * spec is the primary setting, the style follows it:
     *  - 2026 adopts CMF outright (the only style it has rules for), and the
     *    palette row displays that pair locked (the read side hides nothing:
     *    every spec stays listed, because the spec menu is the way *out*).
     *  - 2025 needs one of the expressive styles; anything else on the style
     *    row moves to Tonal Spot so the choice takes effect instead of
     *    silently resolving down to 2021.
     *  - 2021 runs every style; a stranded CMF (cannot happen through the UI,
     *    but a hand-edited export can) falls to Tonal Spot.
     */
    @JvmStatic
    fun setSpecVersion(context: Context, spec: Int) {
        val p = prefs(context)
        p.edit().putInt(KEY_SPEC, spec).apply()
        val style = paletteStyle(context)
        val migrated = when {
            spec == SPEC_2026 -> Scheme.Variant.CMF
            spec == SPEC_2025 && !style.supportsExpressive2025 -> Scheme.Variant.TONAL_SPOT
            style == Scheme.Variant.CMF -> Scheme.Variant.TONAL_SPOT
            else -> null
        }
        migrated?.let { p.edit().putInt(KEY_PALETTE_STYLE, it.ordinal).apply() }
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

    /** Resolved dark/light for the current mode (MODE_SYSTEM reads the device). */
    @JvmStatic
    fun isDark(context: Context): Boolean {
        return when (themeMode(context)) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> {
                // The APPLICATION context, deliberately not the caller's:
                // an activity attached while the user had 深色/浅色 forced
                // carries that pinned night flag in its resources for its
                // whole life ([ThemeSupport.attach] wraps the base context
                // once, at creation). After switching the mode back to
                // 跟随系统, its configuration still answers "dark"/"light"
                // from the pinned flag — the theme then never reverted, which
                // is exactly the report this fixed. The application context is
                // never wrapped, so its configuration is the system's own,
                // kept current by the framework's onConfigurationChanged
                // propagation while the app is open.
                val config = context.applicationContext.resources.configuration
                val mask = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
                mask == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
