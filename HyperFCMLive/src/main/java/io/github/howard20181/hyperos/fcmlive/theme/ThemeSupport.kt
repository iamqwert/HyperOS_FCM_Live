package io.github.howard20181.hyperos.fcmlive.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowInsetsController
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * Hooks the runtime palette into an Activity:
 * - [attach] forces light/dark when the user overrides the system mode, by
 *   rewriting the night flag of the base configuration;
 * - [onCreate] applies the Material You source colour and paints the window
 *   chrome.
 *
 * There is deliberately no `LayoutInflater` factory here any more. It existed
 * to repaint `@color`/`@drawable` roles at inflation time, and nothing is
 * inflated any longer: the five screens are Compose, `res/layout` is gone, and
 * the last framework dialog was replaced by a Compose one. What it cost was a
 * role table to keep in sync, a reflection into the hidden `mFactory2` /
 * `mFactory` fields, and a pause switch every caller had to remember — all of
 * it in service of layouts that no longer exist.
 */
object ThemeSupport {

    /** Call from `Activity.attachBaseContext`. Forces light/dark when pinned. */
    @JvmStatic
    fun attach(base: Context): Context {
        val mode = ThemePrefs.themeMode(base)
        if (mode == ThemePrefs.MODE_SYSTEM) {
            return base
        }
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (mode == ThemePrefs.MODE_DARK || mode == ThemePrefs.MODE_AMOLED) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
        return base.createConfigurationContext(config)
    }

    /**
     * Official Material You dynamic color.
     * - Dynamic on: wallpaper accent (DynamicColors / Monet).
     * - Dynamic off with a custom seed: content-based source from that seed.
     * ThemeEngine / AppPalette drive the Compose palette; this only keeps the
     * window's own theme attributes on the same source colour.
     */
    private fun applyDynamicColors(activity: Activity) {
        try {
            if (ThemePrefs.dynamicColor(activity)) {
                val systemSeed = systemAccentSeed(activity)
                if (systemSeed != 0 && !isNearGrey(systemSeed)) {
                    DynamicColors.applyToActivityIfAvailable(activity)
                } else {
                    // HyperOS can expose a near-grey accent after overnight
                    // palette refresh. The default overlay would then derive a
                    // near-grey ramp, so the window chrome and any framework
                    // widget the theme still dresses read as monochrome.
                    // Re-seed from ThemeEngine (Tonal spot etc. still apply
                    // chroma) so those roles stay visibly colored.
                    val seed = colorfulSeed(activity, systemSeed)
                    val options = DynamicColorsOptions.Builder()
                        .setContentBasedSource(seed)
                        .build()
                    DynamicColors.applyToActivityIfAvailable(activity, options)
                }
            } else {
                val seed = ThemePrefs.seedColor(activity)
                if (seed != 0) {
                    val options = DynamicColorsOptions.Builder()
                        .setContentBasedSource(seed)
                        .build()
                    DynamicColors.applyToActivityIfAvailable(activity, options)
                }
            }
        } catch (ignored: Throwable) {
            // ROM without DynamicColors support: static theme colors remain.
        }
    }

    /** Prefer the in-app seed; fall back to the brand rose when the system hue is empty. */
    private fun colorfulSeed(activity: Activity, systemSeed: Int): Int {
        val themed = ThemeEngine.palette(activity).primary
        if (!isNearGrey(themed)) {
            return themed
        }
        if (systemSeed != 0 && !isNearGrey(systemSeed)) {
            return systemSeed
        }
        return 0xFF8B4A5A.toInt()
    }

    private fun systemAccentSeed(context: Context): Int {
        return try {
            @Suppress("DEPRECATION")
            context.resources.getColor(android.R.color.system_accent1_500)
        } catch (ignored: Throwable) {
            0
        }
    }

    /** True when the color's chroma is too low to carry a Material accent hue. */
    private fun isNearGrey(color: Int): Boolean {
        if (color == 0) {
            return true
        }
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return (max - min) < 0.04f
    }

    /** Call before `setContentView`. Must never throw: UI setup is best-effort. */
    @JvmStatic
    fun onCreate(activity: Activity) {
        try {
            applyDynamicColors(activity)
        } catch (ignored: Throwable) {
        }
        val palette = try {
            ThemeEngine.palette(activity)
        } catch (ignored: Throwable) {
            return
        }
        try {
            applyWindow(activity, palette)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Re-apply the window chrome against the *current* palette, for in-place
     * theme switches. [onCreate] paints the window once at inflation time;
     * after [ThemeEngine.invalidate] the Compose tree re-skins itself (keyed
     * on the engine's generation counter), but the window background and the
     * bar-icon appearance are View-side state that nothing recomposes — so
     * the appearance change path calls this instead of [Activity.recreate],
     * which rebuilt the whole window and read as a jump on every menu pick.
     */
    @JvmStatic
    fun reapplyWindow(activity: Activity) {
        val palette = try {
            ThemeEngine.palette(activity)
        } catch (ignored: Throwable) {
            return
        }
        try {
            applyWindow(activity, palette)
        } catch (ignored: Throwable) {
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
        // Belt and braces for the same reason: if a ROM still honours these two,
        // leaving them at the theme's opaque values paints a solid band over the
        // page colour under the status bar and the home indicator. Transparent
        // makes the window background above the only thing there.
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        // On API 35+ the platform draws the bars transparent for a targeting app
        // and setBackgroundDrawable above is what shows through them. The two
        // setters are deprecated, but a ROM that still reads them would otherwise
        // paint the theme's opaque values over the page colour, so they are forced
        // transparent as well rather than left to chance. The contrast/divider
        // calls stay: they are not deprecated, and disabling the scrim is what
        // keeps the home-indicator strip from reading as a detached grey band.
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
}
