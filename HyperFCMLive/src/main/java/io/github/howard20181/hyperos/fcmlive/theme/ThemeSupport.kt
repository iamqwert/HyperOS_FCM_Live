package io.github.howard20181.hyperos.fcmlive.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.WindowInsetsController

/**
 * Hooks the runtime palette into an Activity:
 * - [attach] forces light/dark when the user overrides the system
 *   mode, by rewriting the night flag of the base configuration;
 * - [onCreate] installs the inflation-time painter and repaints the
 *   window chrome.
 */
object ThemeSupport {

    /** Call from `Activity.attachBaseContext`. */
    @JvmStatic
    fun attach(base: Context): Context {
        val mode = ThemePrefs.themeMode(base)
        val locale = ThemePrefs.locale(base)
        if (mode == ThemePrefs.MODE_SYSTEM && locale == null) {
            return base
        }
        val config = Configuration(base.resources.configuration)
        if (mode != ThemePrefs.MODE_SYSTEM) {
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (mode == ThemePrefs.MODE_DARK) {
                    Configuration.UI_MODE_NIGHT_YES
                } else {
                    Configuration.UI_MODE_NIGHT_NO
                }
        }
        if (locale != null) {
            // Same rewrite, second axis: the chosen locale decides which values-*
            // folder resolves, so the in-app language switch costs no extra
            // machinery — a recreate re-runs attach() and re-inflates everything.
            config.setLocale(locale)
        }
        return base.createConfigurationContext(config)
    }

    /** Call before `setContentView`. */
    @JvmStatic
    fun onCreate(activity: Activity) {
        val palette = ThemeEngine.palette(activity)
        installFactory(activity, palette)
        applyWindow(activity, palette)
    }

    private fun installFactory(activity: Activity, palette: AppPalette) {
        try {
            val inflater = activity.layoutInflater
            inflater.setFactory2(ThemeFactory(activity, inflater, palette))
        } catch (ignored: Throwable) {
            // A factory can only be installed once; skip rather than crash.
        }
    }

    private fun applyWindow(activity: Activity, palette: AppPalette) {
        val window = activity.window ?: return
        val surface = palette.pageBg
        // The window background is what shows through the system-bar areas, so
        // it has to be the page colour: Android 15 (API 35) forces edge-to-edge
        // and draws both bars transparent, which makes this drawable the only
        // thing covering the status bar and the gesture/home-indicator strip.
        window.setBackgroundDrawable(ColorDrawable(surface))
        // The per-bar colour setters are gone: they are deprecated and ignored once
        // the app targets API 35 (this project's minSdk), which the platform draws
        // edge-to-edge with transparent bars — setBackgroundDrawable above is what
        // actually shows through them. The contrast/divider calls stay: they are not
        // deprecated and disabling the scrim is what keeps the home-indicator strip
        // from showing as a detached grey band.
        @Suppress("DEPRECATION")
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        @Suppress("DEPRECATION")
        window.navigationBarDividerColor = Color.TRANSPARENT

        // The decor view has to be installed before the insets controller can
        // be reached. This runs from onCreate, before setContentView, so
        // PhoneWindow.mDecorView is still null — and getInsetsController()
        // dereferences it unconditionally, which crashed the app on launch.
        // getDecorView() creates the decor on demand, which is exactly what
        // this call is for; the null check is kept for exotic Window impls.
        val decor = window.decorView ?: return

        // Bar icon appearance follows the *runtime* palette, not the system
        // setting, because the user can force light/dark inside the app.
        val controller = window.insetsController
        if (controller != null) {
            val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (palette.dark) 0 else appearance, appearance)
        }
    }

    /** Solid rounded rectangle using the current palette (for code-built rows). */
    @JvmStatic
    fun cardBackground(context: Context, color: Int, radiusDp: Float): Drawable {
        return ThemeFactory.roundRect(context, color, radiusDp)
    }
}
