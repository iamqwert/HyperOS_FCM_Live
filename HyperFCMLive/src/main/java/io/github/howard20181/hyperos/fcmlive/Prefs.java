package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * FCM wake allowlist, shared between the module's settings UI (app process) and
 * the Xposed hooks (system_server) via libxposed's cross-process remote
 * preferences ({@code XposedInterface.getRemotePreferences}).
 *
 * system_server cannot read the module's private files (SELinux MLS categories)
 * and querying an on-demand provider is unreliable, so we use the framework's
 * own cross-process prefs as the single source of truth. After writing, the app
 * broadcasts {@link #ACTION_ALLOWLIST_CHANGED} so the system_server hook re-reads
 * its in-memory copy.
 *
 * A local private-prefs mirror is kept so the settings UI can sort allowlisted
 * apps to the top immediately on launch, before libxposed finishes binding.
 */
public final class Prefs {
    public static final String MODULE_PKG = "io.github.howard20181.hyperos.fcmlive";
    /** Remote prefs group shared by the app process and system_server. */
    public static final String GROUP_CONFIG = "config";
    public static final String KEY_ALLOWLIST = "allowlist";
    /** Local mirror group (UI-only; remote remains source of truth for hooks). */
    public static final String LOCAL_PREFS = "fcmlive_allowlist_cache";
    /** UI-only: overflow menu "Show FCM-supported apps". */
    public static final String KEY_SHOW_FCM_ONLY = "show_fcm_supported_only";
    /** Action the app broadcasts after writing, to refresh system_server. */
    public static final String ACTION_ALLOWLIST_CHANGED = MODULE_PKG + ".ALLOWLIST_CHANGED";

    private Prefs() {
    }

    /** Package names the user allows FCM to wake / auto-launch. */
    public static Set<String> readAllowlist(SharedPreferences remotePrefs) {
        Set<String> set = remotePrefs.getStringSet(KEY_ALLOWLIST, Collections.emptySet());
        return set != null ? new HashSet<>(set) : new HashSet<>();
    }

    public static Set<String> readLocalAllowlist(Context context) {
        SharedPreferences p = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        Set<String> set = p.getStringSet(KEY_ALLOWLIST, Collections.emptySet());
        return set != null ? new HashSet<>(set) : new HashSet<>();
    }

    public static void writeLocalAllowlist(Context context, Set<String> allowlist) {
        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_ALLOWLIST, new HashSet<>(allowlist))
                .apply();
    }

    public static void writeAllowlist(Context context, SharedPreferences remotePrefs,
                                      Set<String> allowlist) {
        Set<String> copy = new HashSet<>(allowlist);
        remotePrefs.edit().putStringSet(KEY_ALLOWLIST, copy).commit();
        writeLocalAllowlist(context, copy);
        context.sendBroadcast(new Intent(ACTION_ALLOWLIST_CHANGED));
    }
}
