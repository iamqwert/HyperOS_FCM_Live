package io.github.howard20181.hyperos.fcmlive.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

import io.github.howard20181.hyperos.fcmlive.mcu.Scheme;

/**
 * Appearance settings: theme mode (including the AMOLED pure-black variant),
 * dynamic color with a custom seed, Material palette style, color spec version
 * and the in-app language. Stored in a private prefs file — unlike the allowlist
 * these are UI-only, so they never need to reach system_server.
 *
 * <p>Writes use {@code apply()}, not {@code commit()}: they run on the main
 * thread from click handlers, and everything that reads them back is this same
 * process, whose in-memory copy {@code apply()} updates synchronously. Only
 * {@code Prefs} needs {@code commit()}, because there the write has to be on
 * disk before the broadcast that announces it crosses into system_server.
 */
public final class ThemePrefs {

    public static final String PREFS = "fcmlive_theme";

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;
    /** Pure-black dark variant for OLED panels. */
    public static final int MODE_AMOLED = 3;

    public static final int SPEC_2021 = 0;
    public static final int SPEC_2025 = 1;

    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_PALETTE_STYLE = "palette_style";
    private static final String KEY_SPEC = "color_spec";
    private static final String KEY_DYNAMIC_COLOR = "dynamic_color";
    private static final String KEY_SEED_COLOR = "seed_color";
    /** Legacy flag from when AMOLED was a separate switch; migrated on read. */
    private static final String KEY_AMOLED = "amoled";
    private static final String KEY_LANGUAGE = "language";
    /** Follow the device language (the default: the app is not pinned). */
    public static final int LANGUAGE_SYSTEM = -1;
    /** Number of entries in {@code R.array.language_entries}. */
    public static final int LANGUAGE_COUNT = 2;

    /** Default style matches what Android's own Monet engine generates. */
    private static final int DEFAULT_STYLE = Scheme.Variant.TONAL_SPOT.ordinal();
    /** Default spec is the current Material 3 Expressive one. */
    private static final int DEFAULT_SPEC = SPEC_2025;

    private ThemePrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int themeMode(Context context) {
        SharedPreferences p = prefs(context);
        int mode = p.getInt(KEY_THEME_MODE, MODE_SYSTEM);
        // Migrate the old standalone AMOLED switch into a real theme mode.
        if (mode == MODE_DARK && p.getBoolean(KEY_AMOLED, false)) {
            mode = MODE_AMOLED;
            p.edit().putInt(KEY_THEME_MODE, MODE_AMOLED).remove(KEY_AMOLED).apply();
        }
        return mode >= MODE_SYSTEM && mode <= MODE_AMOLED ? mode : MODE_SYSTEM;
    }

    public static void setThemeMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply();
    }

    public static Scheme.Variant paletteStyle(Context context) {
        int ordinal = prefs(context).getInt(KEY_PALETTE_STYLE, DEFAULT_STYLE);
        Scheme.Variant[] values = Scheme.Variant.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : values[DEFAULT_STYLE];
    }

    public static void setPaletteStyle(Context context, Scheme.Variant variant) {
        prefs(context).edit().putInt(KEY_PALETTE_STYLE, variant.ordinal()).apply();
    }

    public static int specVersion(Context context) {
        int spec = prefs(context).getInt(KEY_SPEC, DEFAULT_SPEC);
        return spec == SPEC_2021 || spec == SPEC_2025 ? spec : DEFAULT_SPEC;
    }

    public static void setSpecVersion(Context context, int spec) {
        prefs(context).edit().putInt(KEY_SPEC, spec).apply();
    }

    /** Whether colors follow the wallpaper; when off, {@link #seedColor} wins. */
    public static boolean dynamicColor(Context context) {
        return prefs(context).getBoolean(KEY_DYNAMIC_COLOR, true);
    }

    public static void setDynamicColor(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply();
    }

    /** Custom seed (ARGB) used while dynamic color is off; 0 means unset. */
    public static int seedColor(Context context) {
        return prefs(context).getInt(KEY_SEED_COLOR, 0);
    }

    public static void setSeedColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_SEED_COLOR, color).apply();
    }

    /** Whether the resolved mode is the AMOLED pure-black variant. */
    public static boolean isAmoled(Context context) {
        return themeMode(context) == MODE_AMOLED;
    }

    /**
     * In-app language, or {@link #LANGUAGE_SYSTEM} to follow the device.
     *
     * <p>Not strictly a theme setting, but it is applied by the very same
     * configuration rewrite in {@link ThemeSupport#attach} that forces
     * light/dark, so the two are changed by one and the same recreate. Going
     * through our own preference instead of {@code LocaleManager} keeps the
     * behaviour identical on every ROM (HyperOS included) and works below the
     * per-app-language APIs.
     */
    public static int language(Context context) {
        int index = prefs(context).getInt(KEY_LANGUAGE, LANGUAGE_SYSTEM);
        return index >= 0 && index < LANGUAGE_COUNT ? index : LANGUAGE_SYSTEM;
    }

    public static void setLanguage(Context context, int index) {
        prefs(context).edit().putInt(KEY_LANGUAGE, index).apply();
    }

    /** Locale to force, or {@code null} to keep the device's own choice. */
    public static Locale locale(Context context) {
        switch (language(context)) {
            case 0:
                return Locale.SIMPLIFIED_CHINESE;
            case 1:
                return Locale.ENGLISH;
            default:
                return null;
        }
    }

    /** Resolved dark/light for the current mode (MODE_SYSTEM reads the device). */
    public static boolean isDark(Context context) {
        switch (themeMode(context)) {
            case MODE_LIGHT:
                return false;
            case MODE_DARK:
            case MODE_AMOLED:
                return true;
            default:
                int mask = context.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                return mask == Configuration.UI_MODE_NIGHT_YES;
        }
    }
}
