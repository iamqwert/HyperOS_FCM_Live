package io.github.howard20181.hyperos.fcmlive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

/**
 * Desktop icon visibility. The icon is an activity-alias (see
 * {@code .LauncherAlias} in the manifest), so showing/hiding is a component
 * enable/disable write — kept here because both the main screen and the about
 * screen need it.
 */
public final class LauncherIcon {

    private static final String TAG = "LauncherIcon";

    private LauncherIcon() {
    }

    /** True when the alias is currently reachable from the launcher. */
    public static boolean isVisible(Context context) {
        try {
            int state = context.getPackageManager()
                    .getComponentEnabledSetting(alias(context));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isHidden(Context context) {
        return !isVisible(context);
    }

    /**
     * Apply the requested visibility. Returns whether PackageManager accepted
     * the write; callers own any user-visible confirmation.
     */
    public static boolean setHidden(Context context, boolean hidden) {
        PackageManager pm = context.getPackageManager();
        ComponentName alias = alias(context);
        int state = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        boolean applied = false;
        try {
            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP);
            applied = true;
        } catch (Throwable t) {
            // Some ROMs reject the alias write; fall back to every resolved
            // launcher activity so the icon still disappears.
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_LAUNCHER);
                home.setPackage(context.getPackageName());
                java.util.List<ResolveInfo> list = pm.queryIntentActivities(home, 0);
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null) {
                        continue;
                    }
                    pm.setComponentEnabledSetting(
                            new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                            state,
                            PackageManager.DONT_KILL_APP);
                    applied = true;
                }
            } catch (Throwable t2) {
                Log.w(TAG, "setComponentEnabledSetting failed", t2);
            }
        }

        try {
            Intent changed = new Intent(Intent.ACTION_PACKAGE_CHANGED,
                    Uri.parse("package:" + context.getPackageName()));
            changed.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME, alias.getClassName());
            context.sendBroadcast(changed);
        } catch (Throwable ignored) {
        }
        return applied;
    }

    private static ComponentName alias(Context context) {
        return new ComponentName(context.getPackageName(),
                context.getPackageName() + ".LauncherAlias");
    }
}
