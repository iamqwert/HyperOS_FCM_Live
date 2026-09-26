package io.github.howard20181.hyperos.fcmlive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Desktop icon visibility. The icon is an activity-alias (see
 * `.LauncherAlias` in the manifest), so showing/hiding is a component
 * enable/disable write — kept here because both the main screen and the about
 * screen need it.
 */
object LauncherIcon {

    private const val TAG = "LauncherIcon"

    /** True when the alias is currently reachable from the launcher. */
    @JvmStatic
    fun isVisible(context: Context): Boolean {
        return try {
            val state = context.packageManager
                .getComponentEnabledSetting(alias(context))
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                false
            } else {
                state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
        } catch (t: Throwable) {
            true
        }
    }

    @JvmStatic
    fun isHidden(context: Context): Boolean = !isVisible(context)

    /**
     * Apply the requested visibility. Returns whether PackageManager accepted
     * the write; callers own any user-visible confirmation.
     */
    @JvmStatic
    fun setHidden(context: Context, hidden: Boolean): Boolean {
        val pm = context.packageManager
        val alias = alias(context)
        val state = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        var applied = false
        try {
            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
            applied = true
        } catch (t: Throwable) {
            // Some ROMs reject the alias write; fall back to every resolved
            // launcher activity so the icon still disappears.
            try {
                val home = Intent(Intent.ACTION_MAIN)
                home.addCategory(Intent.CATEGORY_LAUNCHER)
                home.setPackage(context.packageName)
                val list = pm.queryIntentActivities(home, 0)
                for (ri in list) {
                    val info = ri.activityInfo ?: continue
                    pm.setComponentEnabledSetting(
                        ComponentName(info.packageName, info.name),
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                    applied = true
                }
            } catch (t2: Throwable) {
                Log.w(TAG, "setComponentEnabledSetting failed", t2)
            }
        }

        try {
            val changed = Intent(
                Intent.ACTION_PACKAGE_CHANGED,
                Uri.parse("package:" + context.packageName)
            )
            @Suppress("DEPRECATION")
            changed.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME, alias.className)
            context.sendBroadcast(changed)
        } catch (ignored: Throwable) {
        }
        return applied
    }

    private fun alias(context: Context): ComponentName {
        return ComponentName(context.packageName, context.packageName + ".LauncherAlias")
    }
}
