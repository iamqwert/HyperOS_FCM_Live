package io.github.howard20181.hyperos.fcmlive.theme;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

import java.util.Locale;

/**
 * Hooks the runtime palette into an Activity:
 * <ul>
 *   <li>{@link #attach} forces light/dark when the user overrides the system
 *       mode, by rewriting the night flag of the base configuration;</li>
 *   <li>{@link #onCreate} installs the inflation-time painter and repaints the
 *       window chrome.</li>
 * </ul>
 */
public final class ThemeSupport {

    private ThemeSupport() {
    }

    /** Call from {@code Activity.attachBaseContext}. */
    public static Context attach(Context base) {
        int mode = ThemePrefs.themeMode(base);
        Locale locale = ThemePrefs.locale(base);
        if (mode == ThemePrefs.MODE_SYSTEM && locale == null) {
            return base;
        }
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (mode != ThemePrefs.MODE_SYSTEM) {
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (mode == ThemePrefs.MODE_DARK
                            ? Configuration.UI_MODE_NIGHT_YES
                            : Configuration.UI_MODE_NIGHT_NO);
        }
        if (locale != null) {
            // Same rewrite, second axis: the chosen locale decides which values-*
            // folder resolves, so the in-app language switch costs no extra
            // machinery — a recreate re-runs attach() and re-inflates everything.
            config.setLocale(locale);
        }
        return base.createConfigurationContext(config);
    }

    /** Call before {@code setContentView}. */
    public static void onCreate(Activity activity) {
        AppPalette palette = ThemeEngine.palette(activity);
        installFactory(activity, palette);
        applyWindow(activity, palette);
    }

    private static void installFactory(Activity activity, AppPalette palette) {
        try {
            LayoutInflater inflater = activity.getLayoutInflater();
            inflater.setFactory2(new ThemeFactory(activity, inflater, palette));
        } catch (Throwable ignored) {
            // A factory can only be installed once; skip rather than crash.
        }
    }

    private static void applyWindow(Activity activity, AppPalette palette) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        int surface = palette.pageBg;
        // The window background is what shows through the system-bar areas, so
        // it has to be the page colour: Android 15 (API 35) forces edge-to-edge
        // and draws both bars transparent, which makes this drawable the only
        // thing covering the status bar and the gesture/home-indicator strip.
        window.setBackgroundDrawable(new ColorDrawable(surface));
        // The per-bar colour setters are gone: they are deprecated and ignored once
        // the app targets API 35 (this project's minSdk), which the platform draws
        // edge-to-edge with transparent bars — setBackgroundDrawable above is what
        // actually shows through them. The contrast/divider calls stay: they are not
        // deprecated and disabling the scrim is what keeps the home-indicator strip
        // from showing as a detached grey band.
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        window.setNavigationBarDividerColor(Color.TRANSPARENT);

        // The decor view has to be installed before the insets controller can
        // be reached. This runs from onCreate, before setContentView, so
        // PhoneWindow.mDecorView is still null — and getInsetsController()
        // dereferences it unconditionally, which crashed the app on launch.
        // getDecorView() creates the decor on demand, which is exactly what
        // this call is for; the null check is kept for exotic Window impls.
        View decor = window.getDecorView();
        if (decor == null) {
            return;
        }

        // Bar icon appearance follows the *runtime* palette, not the system
        // setting, because the user can force light/dark inside the app.
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(palette.dark ? 0 : appearance, appearance);
        }
    }

    /** Solid rounded rectangle using the current palette (for code-built rows). */
    public static Drawable cardBackground(Context context, int color, float radiusDp) {
        return ThemeFactory.roundRect(context, color, radiusDp);
    }
}
