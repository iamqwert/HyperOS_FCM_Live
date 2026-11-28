package io.github.iamqwert.hypergreeze

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.util.Log
import io.github.iamqwert.hypergreeze.theme.AppearanceState
import io.github.iamqwert.hypergreeze.theme.ThemePrefs

/**
 * Desktop icon visibility and dynamic-color icon selection.
 *
 * The launcher entry is split into two mutually exclusive activity-aliases in
 * the manifest:
 *  - `.LauncherAlias` (default-enabled, brand warm-gradient icon)
 *  - `.LauncherAliasDynamic` (default-disabled, wallpaper Monet dynamic icon)
 *
 * Hiding disables both; showing enables whichever variant [ThemePrefs.dynamicLauncherIcon]
 * selects. Switching between the two enables the target alias BEFORE disabling
 * the old one so launchers never observe a zero-entry gap.
 */
object LauncherIcon {

    private const val TAG = "LauncherIcon"

    /** True when either launcher alias is currently reachable from the home screen. */
    @JvmStatic
    fun isVisible(context: Context): Boolean {
        return isAliasEnabled(context, defaultAlias(context), defaultEnabled = true) ||
            isAliasEnabled(context, dynamicAlias(context), defaultEnabled = false)
    }

    @JvmStatic
    fun isHidden(context: Context): Boolean = !isVisible(context)

    /**
     * Current dynamic-icon preference, reconciled against the live launcher
     * aliases when the icon is visible.
     */
    @JvmStatic
    fun isDynamicEnabled(context: Context): Boolean {
        if (isAliasEnabled(context, dynamicAlias(context), defaultEnabled = false)) return true
        if (isAliasEnabled(context, defaultAlias(context), defaultEnabled = true)) return false
        return ThemePrefs.dynamicLauncherIcon(context)
    }

    /**
     * Apply the requested visibility while preserving the user's dynamic-icon
     * choice. Returns whether PackageManager accepted the write; callers own
     * any user-visible confirmation.
     */
    @JvmStatic
    fun setHidden(context: Context, hidden: Boolean): Boolean {
        val dynamic = ThemePrefs.dynamicLauncherIcon(context)
        return applyState(context, hidden = hidden, dynamic = dynamic)
    }

    /**
     * Persist whether the launcher icon uses system dynamic colors and update
     * the active alias immediately when the icon is currently visible.
     */
    @JvmStatic
    fun setDynamicIcon(context: Context, dynamic: Boolean): Boolean {
        AppearanceState.setDynamicLauncherIcon(context, dynamic)
        syncSplashScreenTheme(context, dynamic)
        if (isHidden(context)) return true
        return applyState(context, hidden = false, dynamic = dynamic)
    }

    /**
     * Persist the matching Android 13+ SplashScreen theme (`Theme.HyperFCMLive.DynamicSplash`
     * when dynamic launcher icon is enabled, `Resources.ID_NULL` to reset to the default
     * warm-gradient splash). Safe no-op when called without an Activity context.
     */
    @JvmStatic
    fun syncSplashScreenTheme(
        context: Context,
        dynamic: Boolean = isDynamicEnabled(context),
    ) {
        val activity = findActivity(context) ?: return
        val themeRes = if (dynamic) R.style.Theme_HyperFCMLive_DynamicSplash else Resources.ID_NULL
        try {
            activity.splashScreen.setSplashScreenTheme(themeRes)
        } catch (t: Throwable) {
            Log.w(TAG, "setSplashScreenTheme failed", t)
        }
    }

    private fun findActivity(context: Context): Activity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return cur as? Activity
    }

    private fun applyState(context: Context, hidden: Boolean, dynamic: Boolean): Boolean {
        val pm = context.packageManager
        val defAlias = defaultAlias(context)
        val dynAlias = dynamicAlias(context)
        var applied = false

        try {
            if (hidden) {
                pm.setComponentEnabledSetting(
                    defAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    dynAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } else if (dynamic) {
                // Enable target first so the launcher keeps a valid entry during the swap.
                pm.setComponentEnabledSetting(
                    dynAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    defAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } else {
                pm.setComponentEnabledSetting(
                    defAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    dynAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            applied = true
        } catch (t: Throwable) {
            // Some ROMs reject an alias write; fall back to every resolved
            // launcher activity when hiding so the icon still disappears.
            if (hidden) {
                try {
                    val home = Intent(Intent.ACTION_MAIN)
                    home.addCategory(Intent.CATEGORY_LAUNCHER)
                    home.setPackage(context.packageName)
                    val list = pm.queryIntentActivities(home, 0)
                    for (ri in list) {
                        val info = ri.activityInfo ?: continue
                        pm.setComponentEnabledSetting(
                            ComponentName(info.packageName, info.name),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        applied = true
                    }
                } catch (t2: Throwable) {
                    Log.w(TAG, "setComponentEnabledSetting fallback failed", t2)
                }
            } else {
                Log.w(TAG, "setComponentEnabledSetting failed", t)
            }
        }

        val activeName = if (dynamic) dynAlias.className else defAlias.className
        try {
            val changed = Intent(
                Intent.ACTION_PACKAGE_CHANGED,
                Uri.parse("package:" + context.packageName)
            )
            @Suppress("DEPRECATION")
            changed.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME, activeName)
            context.sendBroadcast(changed)
        } catch (t: Throwable) {
            // The component write above already landed; this broadcast only asks
            // launchers to re-read. Worth a line, not worth failing the call.
            Log.w(TAG, "PACKAGE_CHANGED notify failed", t)
        }
        return applied
    }

    private fun isAliasEnabled(
        context: Context,
        component: ComponentName,
        defaultEnabled: Boolean
    ): Boolean {
        return try {
            when (context.packageManager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> defaultEnabled
                else -> false
            }
        } catch (t: Throwable) {
            defaultEnabled
        }
    }

    private fun defaultAlias(context: Context): ComponentName {
        return ComponentName(context.packageName, context.packageName + ".LauncherAlias")
    }

    private fun dynamicAlias(context: Context): ComponentName {
        return ComponentName(context.packageName, context.packageName + ".LauncherAliasDynamic")
    }
}
