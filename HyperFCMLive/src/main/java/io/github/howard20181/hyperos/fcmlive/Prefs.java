package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

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
    /** UI-only: set while the mirror holds edits the module service never saw. */
    private static final String KEY_PENDING_PUSH = "allowlist_pending_push";
    /** UI-only: overflow menu "Show FCM-supported apps". */
    public static final String KEY_SHOW_FCM_ONLY = "show_fcm_supported_only";
    /**
     * UI-only: overflow menu "Exclude MiPush apps". Kept next to
     * {@link #KEY_SHOW_FCM_ONLY} because it is the same kind of setting — a
     * question about what the list offers, not about what the hooks do, so it
     * stays out of {@link #GROUP_CONFIG} and needs no broadcast.
     */
    public static final String KEY_EXCLUDE_MIPUSH = "exclude_mipush_apps";
    /**
     * Remote + local: overflow menu "Strict mode". Unlike
     * {@link #KEY_SHOW_FCM_ONLY} this one decides what the hooks do, so it sits
     * in {@link #GROUP_CONFIG} next to the allowlist and is re-read by the same
     * broadcast; the local mirror only carries the answer before libxposed binds.
     */
    public static final String KEY_STRICT_MODE = "strict_mode";
    /** UI-only: set while the mirror holds a strict-mode change the module never saw. */
    private static final String KEY_STRICT_PENDING_PUSH = "strict_mode_pending_push";
    /** Action the app broadcasts after writing, to refresh system_server. */
    public static final String ACTION_ALLOWLIST_CHANGED = MODULE_PKG + ".ALLOWLIST_CHANGED";

    private Prefs() {
    }

    /**
     * Remote prefs handle published by the settings UI once libxposed binds, so
     * other screens (e.g. About) can read/write the allowlist without binding a
     * second service listener. Null when the module service is not bound.
     */
    private static volatile SharedPreferences sRemotePrefs;

    public static void setRemote(SharedPreferences remotePrefs) {
        sRemotePrefs = remotePrefs;
    }

    public static SharedPreferences remote() {
        return sRemotePrefs;
    }

    /** Package names the user allows FCM to wake / auto-launch. */
    public static Set<String> readAllowlist(SharedPreferences remotePrefs) {
        return readSet(remotePrefs);
    }

    public static Set<String> readLocalAllowlist(Context context) {
        return readSet(localPrefs(context));
    }

    /** Copied defensively: callers mutate the result, and the stored set is shared. */
    private static Set<String> readSet(SharedPreferences prefs) {
        if (prefs == null) {
            return new HashSet<>();
        }
        Set<String> set = prefs.getStringSet(KEY_ALLOWLIST, Collections.emptySet());
        return set != null ? new HashSet<>(set) : new HashSet<>();
    }

    public static void writeLocalAllowlist(Context context, Set<String> allowlist) {
        localPrefs(context).edit().putStringSet(KEY_ALLOWLIST, new HashSet<>(allowlist)).apply();
    }

    /**
     * Whether the local mirror holds a change the module service never received,
     * because the service was not bound when the user made it. The next bind then
     * pushes the mirror up instead of adopting the (older) remote set, which is
     * what used to silently revert such a change.
     */
    public static boolean hasPendingPush(Context context) {
        return localPrefs(context).getBoolean(KEY_PENDING_PUSH, false);
    }

    private static void markPendingPush(Context context) {
        localPrefs(context).edit().putBoolean(KEY_PENDING_PUSH, true).apply();
    }

    private static void clearPendingPush(Context context) {
        localPrefs(context).edit().putBoolean(KEY_PENDING_PUSH, false).apply();
    }

    /** Strict mode as the UI last left it; the mirror is what the settings screen shows. */
    public static boolean readLocalStrictMode(Context context) {
        return localPrefs(context).getBoolean(KEY_STRICT_MODE, false);
    }

    /** Strict-mode counterpart of {@link #hasPendingPush}. */
    public static boolean hasPendingStrictPush(Context context) {
        return localPrefs(context).getBoolean(KEY_STRICT_PENDING_PUSH, false);
    }

    /**
     * Write strict mode and make it live.
     *
     * <p>Same shape as {@link #writeAllowlist}: the remote boolean is what the
     * hooks read, and {@link #broadcastAllowlistChanged} is what makes them
     * re-read it — they load the whole {@link #GROUP_CONFIG} group in one go, so
     * one broadcast refreshes the allowlist and this flag together. When the
     * module service is not bound yet the change stays in the mirror and is
     * flagged, so the next bind pushes it up instead of dropping it.
     */
    public static void writeStrictMode(Context context, SharedPreferences remotePrefs,
                                       boolean enabled) {
        final Context app = appContext(context);
        localPrefs(app).edit().putBoolean(KEY_STRICT_MODE, enabled).apply();
        if (remotePrefs == null) {
            localPrefs(app).edit().putBoolean(KEY_STRICT_PENDING_PUSH, true).apply();
            broadcastAllowlistChanged(app);
            return;
        }
        localPrefs(app).edit().putBoolean(KEY_STRICT_PENDING_PUSH, false).apply();
        WRITER.execute(() -> {
            try {
                remotePrefs.edit().putBoolean(KEY_STRICT_MODE, enabled).commit();
            } catch (Throwable t) {
                // As with the allowlist: a failed write must not pass for a live
                // change, so the next bind pushes the mirror up again.
                localPrefs(app).edit().putBoolean(KEY_STRICT_PENDING_PUSH, true).apply();
            }
            broadcastAllowlistChanged(app);
        });
    }

    private static SharedPreferences localPrefs(Context context) {
        return context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
    }

    /** Application context where available: broadcasts must not outlive the caller. */
    private static Context appContext(Context context) {
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }

    /**
     * Serialises allowlist writes: one background thread, in order, so a burst of
     * taps cannot interleave and lose the last write.
     */
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fcmlive-allowlist-write");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Write the allowlist and make it live.
     *
     * <p>Remote prefs are what system_server's hooks read, so this write plus the
     * broadcast that follows it is what makes a change take effect — no refresh, no
     * restart. The write itself is a synchronous cross-process commit and therefore
     * runs on a background thread: on the calling (main) thread it could stall the
     * UI, and {@code apply()} is not an option here because the broadcast must not
     * outrun the value it announces. When {@code remotePrefs} is null (module
     * service not bound in this process) the change is kept in the local mirror and
     * flagged, so the next bind pushes it up rather than dropping it.
     */
    public static void writeAllowlist(Context context, SharedPreferences remotePrefs,
                                      Set<String> allowlist) {
        final Set<String> copy = new HashSet<>(allowlist);
        final Context app = appContext(context);
        writeLocalAllowlist(app, copy);
        if (remotePrefs == null) {
            markPendingPush(app);
            broadcastAllowlistChanged(app);
            return;
        }
        clearPendingPush(app);
        WRITER.execute(() -> {
            try {
                remotePrefs.edit().putStringSet(KEY_ALLOWLIST, copy).commit();
            } catch (Throwable t) {
                // A failed write must not pass for a live change: keep the flag so
                // the next bind pushes the mirror up again.
                markPendingPush(app);
            }
            // Announced after the write, so a reader of the prefs sees this value.
            broadcastAllowlistChanged(app);
        });
    }

    /**
     * Ask system_server to re-read the shared config group. Sent three times
     * over ~1.5s because the receiver there is installed by a retry loop shortly
     * after boot ({@code Hooker.installAllowlistReceiverAsync}): a change made
     * in that window would otherwise be dropped and appear to need a refresh.
     *
     * <p>Despite the name it is not allowlist-only: the receiver reloads
     * {@link #GROUP_CONFIG} wholesale, so this also carries a strict-mode change
     * (see {@link #writeStrictMode}) — which is why the two share one action.
     */
    public static void broadcastAllowlistChanged(Context context) {
        Context app = appContext(context);
        app.sendBroadcast(new Intent(ACTION_ALLOWLIST_CHANGED));
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> app.sendBroadcast(new Intent(ACTION_ALLOWLIST_CHANGED)), 400L);
        handler.postDelayed(() -> app.sendBroadcast(new Intent(ACTION_ALLOWLIST_CHANGED)), 1500L);
    }
}
