package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * FCM wake allowlist, shared between the module's settings UI (app process) and
 * the Xposed hooks (system_server) via libxposed's cross-process remote
 * preferences (`XposedInterface.getRemotePreferences`).
 *
 * system_server cannot read the module's private files (SELinux MLS categories)
 * and querying an on-demand provider is unreliable, so we use the framework's
 * own cross-process prefs as the single source of truth. After writing, the app
 * broadcasts [ACTION_ALLOWLIST_CHANGED] so the system_server hook re-reads
 * its in-memory copy.
 *
 * A local private-prefs mirror is kept so the settings UI can sort allowlisted
 * apps to the top immediately on launch, before libxposed finishes binding.
 *
 * ## Master / sub switch rule (read before adding any new pair)
 *
 * A sub switch is rendered inside `AnimatedVisibility(visible = <master>)`, so
 * it is **invisible but not inert** when the master is off — the pref keeps its
 * last value and a hook that reads only the sub key would still act. Any new
 * master/sub pair therefore has to be checked in three places, not one:
 *
 *  1. every hook read site **ANDs the master flag** (including secondary gates
 *     shared by both branches) — see `isSleepKeepaliveDataEnabled()` /
 *     `isSleepKeepaliveChargingOnlyEnabled()` in Hooker.kt;
 *  2. the UI keeps the sub switch inside the *same* `AnimatedVisibility` as the
 *     master;
 *  3. the key is added to `MainActivity.reloadAllowlist`'s pending-repair set,
 *     so a value the module never saw gets pushed again.
 *
 * The current pair is `sleep_keepalive` ⊃ `…_data` / `…_charging_only`. The
 * WeChat pair was removed with the shield (see HOOKS_AND_DIAGNOSTICS.md §4.8).
 *
 * Rare paths need an explicit "applied / handed back to the ROM" log line:
 * **never infer that a hook worked from the absence of a log line.**
 */
object Prefs {
    const val MODULE_PKG = "io.github.howard20181.hyperos.fcmlive"
    /** Remote prefs group shared by the app process and system_server. */
    const val GROUP_CONFIG = "config"
    const val KEY_ALLOWLIST = "allowlist"
    /** Local mirror group (UI-only; remote remains source of truth for hooks). */
    const val LOCAL_PREFS = "fcmlive_allowlist_cache"
    /** UI-only: set while the mirror holds edits the module service never saw. */
    private const val KEY_PENDING_PUSH = "allowlist_pending_push"
    /** UI-only: overflow menu "Show FCM-supported apps". */
    const val KEY_SHOW_FCM_ONLY = "show_fcm_supported_only"
    /**
     * UI-only: overflow menu "Exclude MiPush apps". Kept next to
     * [KEY_SHOW_FCM_ONLY] because it is the same kind of setting — a
     * question about what the list offers, not about what the hooks do, so it
     * stays out of [GROUP_CONFIG] and needs no broadcast.
     */
    const val KEY_EXCLUDE_MIPUSH = "exclude_mipush_apps"
    /**
     * Remote + local: overflow menu "Strict mode". Unlike
     * [KEY_SHOW_FCM_ONLY] this one decides what the hooks do, so it sits
     * in [GROUP_CONFIG] next to the allowlist and is re-read by the same
     * broadcast; the local mirror only carries the answer before libxposed binds.
     */
    const val KEY_STRICT_MODE = "strict_mode"
    /** UI-only: set while the mirror holds a strict-mode change the module never saw. */
    private const val KEY_STRICT_PENDING_PUSH = "strict_mode_pending_push"
    /**
     * Remote + local: "keep WiFi up during sleep" experiment — the master
     * switch.
     *
     * On OS4/V816 the sleep mode does *not* filter per uid:
     * `PhoneSleepModeController#applySleepConfig` turns WiFi **and** mobile
     * data off outright, so no per-app whitelist can save the FCM channel —
     * measured 01:38:00→07:08:57 with no network at all.
     *
     * This switch stops the WiFi cutoff. Mobile data is a separate decision
     * ([KEY_SLEEP_KEEPALIVE_DATA]) and stays on the system's own policy by
     * default, because WiFi-only is the cheaper half: an unattended phone at
     * home is on WiFi anyway, and holding the cellular radio open is the part
     * that actually costs power. A night with no WiFi gets nothing out of
     * this switch — that is what the sub-switch is for.
     *
     * Default **off**, like every other experiment:
     * keeping a radio up all night defeats the power saving the user turned
     * sleep mode on for, and the effect is device-wide rather than scoped to
     * the apps the module watches. It is opt-in on the experiment screen,
     * where the cost is spelled out.
     */
    const val KEY_SLEEP_KEEPALIVE = "sleep_keepalive"
    /** UI-only: set while the mirror holds a keepalive change the module never saw. */
    private const val KEY_SLEEP_KEEPALIVE_PENDING_PUSH = "sleep_keepalive_pending_push"

    /**
     * Remote + local: "keep mobile data up during sleep" sub-switch.
     *
     * Only consulted while [KEY_SLEEP_KEEPALIVE] is on; on its own it does
     * nothing at all, which is why the experiment screen only reveals it once
     * the master switch is on. With it on, the pair behaves like a single
     * "keep the whole network up" switch: both radios survive the night, at a
     * higher cost.
     *
     * Default **off**, same reasoning as the master switch — ask before
     * holding a radio open overnight.
     */
    const val KEY_SLEEP_KEEPALIVE_DATA = "sleep_keepalive_data"
    /** UI-only: set while the mirror holds a data-keepalive change the module never saw. */
    private const val KEY_SLEEP_KEEPALIVE_DATA_PENDING_PUSH = "sleep_keepalive_data_pending_push"

    /**
     * Remote + local: "only while charging" sub-switch of the sleep keepalive.
     *
     * The master switch holds a radio open for the whole night, and that only
     * earns its cost when the battery is not what is being spent — i.e. when
     * the phone sits on the charger. With this on, the cutoff hooks stand down
     * whenever the device is not charging and the ROM cuts the network exactly
     * as it would have: an unplugged night keeps the power saving, a charging
     * night keeps the FCM channel.
     *
     * Applies to both radios, so it is a sibling of [KEY_SLEEP_KEEPALIVE_DATA]
     * rather than nested under it. Only consulted while [KEY_SLEEP_KEEPALIVE]
     * is on, like the data sub-switch. Default **off**: it narrows a switch
     * the user deliberately turned on, and narrowing is still a change to what
     * they asked for.
     */
    const val KEY_SLEEP_KEEPALIVE_CHARGING = "sleep_keepalive_charging_only"
    /** UI-only: set while the mirror holds a charging-only change the module never saw. */
    private const val KEY_SLEEP_KEEPALIVE_CHARGING_PENDING_PUSH =
        "sleep_keepalive_charging_only_pending_push"

    /**
     * Remote + local: "WeChat doze keepout" experiment.
     *
     * The PowerKeeper process hardcodes WeChat into its domestic
     * always-white set (`DeviceIdleController$1`) and re-adds it to the AOSP
     * battery-optimization whitelist on every power-mode change — through the
     * single funnel `CommonAdapter.addPowerSaveWhitelistApps`, which is also
     * what persists /data/system/deviceidle.xml. This switch drops WeChat
     * from that call's argument list.
     *
     * Default **off**, like every other experiment: it overwrites where the
     * system puts WeChat, the effect outlives the process (the entry is not
     * written back until the switch is turned off), and the whitelist already
     * holds WeChat today — the hook prevents the next write, it does not
     * clear the stored one.
     */
    const val KEY_WECHAT_DOZE_KEEPOUT = "wechat_doze_keepout"
    /** UI-only: set while the mirror holds a keepout change the module never saw. */
    private const val KEY_WECHAT_DOZE_KEEPOUT_PENDING_PUSH = "wechat_doze_keepout_pending_push"
    /** Action the app broadcasts after writing, to refresh system_server. */
    const val ACTION_ALLOWLIST_CHANGED = MODULE_PKG + ".ALLOWLIST_CHANGED"

    /**
     * Remote prefs handle published by the settings UI once libxposed binds, so
     * other screens (e.g. About) can read/write the allowlist without binding a
     * second service listener. Null when the module service is not bound.
     */
    @Volatile
    private var sRemotePrefs: SharedPreferences? = null

    @JvmStatic
    fun setRemote(remotePrefs: SharedPreferences?) {
        sRemotePrefs = remotePrefs
    }

    @JvmStatic
    fun remote(): SharedPreferences? = sRemotePrefs

    /** Package names the user allows FCM to wake / auto-launch. */
    @JvmStatic
    fun readAllowlist(remotePrefs: SharedPreferences?): MutableSet<String> {
        return readSet(remotePrefs)
    }

    @JvmStatic
    fun readLocalAllowlist(context: Context): MutableSet<String> {
        return readSet(localPrefs(context))
    }

    /** Copied defensively: callers mutate the result, and the stored set is shared. */
    private fun readSet(prefs: SharedPreferences?): MutableSet<String> {
        if (prefs == null) {
            return HashSet()
        }
        val set = prefs.getStringSet(KEY_ALLOWLIST, Collections.emptySet())
        return if (set != null) HashSet(set) else HashSet()
    }

    @JvmStatic
    fun writeLocalAllowlist(context: Context, allowlist: Set<String>) {
        localPrefs(context).edit().putStringSet(KEY_ALLOWLIST, HashSet(allowlist)).apply()
    }

    /**
     * Whether the local mirror holds a change the module service never received,
     * because the service was not bound when the user made it. The next bind then
     * pushes the mirror up instead of adopting the (older) remote set, which is
     * what used to silently revert such a change.
     */
    @JvmStatic
    fun hasPendingPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_PENDING_PUSH, false)
    }

    private fun markPendingPush(context: Context) {
        localPrefs(context).edit().putBoolean(KEY_PENDING_PUSH, true).apply()
    }

    private fun clearPendingPush(context: Context) {
        localPrefs(context).edit().putBoolean(KEY_PENDING_PUSH, false).apply()
    }

    /** Strict mode as the UI last left it; the mirror is what the settings screen shows. */
    @JvmStatic
    fun readLocalStrictMode(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_STRICT_MODE, false)
    }

    /** Strict-mode counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingStrictPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_STRICT_PENDING_PUSH, false)
    }

    /** Sleep-keepalive value as the UI last left it; the mirror is what the experiment screen shows. */
    @JvmStatic
    fun readLocalSleepKeepalive(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE, false)
    }

    /** Sleep-keepalive counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingSleepKeepalivePush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE_PENDING_PUSH, false)
    }

    /**
     * Write the sleep-keepalive flag and make it live.
     *
     * Same shape as [writeStrictMode]. The hook lives in the PowerKeeper
     * process and reads the remote value lazily at each qualifying call, so
     * flipping this takes effect on the next sleep entry without a reboot.
     */
    @JvmStatic
    fun writeSleepKeepalive(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_SLEEP_KEEPALIVE, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit().putBoolean(KEY_SLEEP_KEEPALIVE_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit().putBoolean(KEY_SLEEP_KEEPALIVE_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putBoolean(KEY_SLEEP_KEEPALIVE, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit().putBoolean(KEY_SLEEP_KEEPALIVE_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
        }
    }

    /** Sleep-keepalive data sub-switch value as the UI last left it. */
    @JvmStatic
    fun readLocalSleepKeepaliveData(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE_DATA, false)
    }

    /** Sleep-keepalive data counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingSleepKeepaliveDataPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE_DATA_PENDING_PUSH, false)
    }

    /**
     * Write the sleep-keepalive data sub-switch and make it live.
     *
     * Same shape as [writeSleepKeepalive]. The hook in the PowerKeeper process
     * reads the remote value lazily at each qualifying call, so flipping this
     * takes effect on the next sleep entry without a reboot.
     */
    @JvmStatic
    fun writeSleepKeepaliveData(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_SLEEP_KEEPALIVE_DATA, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit().putBoolean(KEY_SLEEP_KEEPALIVE_DATA_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit().putBoolean(KEY_SLEEP_KEEPALIVE_DATA_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putBoolean(KEY_SLEEP_KEEPALIVE_DATA, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit()
                    .putBoolean(KEY_SLEEP_KEEPALIVE_DATA_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
        }
    }

    /** Sleep-keepalive charging-only value as the UI last left it. */
    @JvmStatic
    fun readLocalSleepKeepaliveCharging(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE_CHARGING, false)
    }

    /** Sleep-keepalive charging-only counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingSleepKeepaliveChargingPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_SLEEP_KEEPALIVE_CHARGING_PENDING_PUSH, false)
    }

    /**
     * Write the sleep-keepalive charging-only sub-switch and make it live.
     *
     * Same shape as [writeSleepKeepalive]: the hook in the PowerKeeper process
     * reads the remote value lazily at each qualifying call, so flipping this
     * takes effect on the next sleep entry without a reboot.
     */
    @JvmStatic
    fun writeSleepKeepaliveCharging(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_SLEEP_KEEPALIVE_CHARGING, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit()
                .putBoolean(KEY_SLEEP_KEEPALIVE_CHARGING_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit()
            .putBoolean(KEY_SLEEP_KEEPALIVE_CHARGING_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putBoolean(KEY_SLEEP_KEEPALIVE_CHARGING, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit()
                    .putBoolean(KEY_SLEEP_KEEPALIVE_CHARGING_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
        }
    }

    /** WeChat-doze-keepout value as the UI last left it; the mirror is what the experiment screen shows. */
    @JvmStatic
    fun readLocalWechatDozeKeepout(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WECHAT_DOZE_KEEPOUT, false)
    }

    /** WeChat-doze-keepout counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingWechatDozeKeepoutPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WECHAT_DOZE_KEEPOUT_PENDING_PUSH, false)
    }

    /**
     * Write the WeChat-doze-keepout flag and make it live.
     *
     * Same shape as [writeStrictMode]. The hook lives in the PowerKeeper
     * process and reads the remote value lazily at each qualifying call, so
     * flipping this takes effect on the next whitelist write without a reboot.
     */
    @JvmStatic
    fun writeWechatDozeKeepout(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_WECHAT_DOZE_KEEPOUT, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit().putBoolean(KEY_WECHAT_DOZE_KEEPOUT_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit().putBoolean(KEY_WECHAT_DOZE_KEEPOUT_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putBoolean(KEY_WECHAT_DOZE_KEEPOUT, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit().putBoolean(KEY_WECHAT_DOZE_KEEPOUT_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
        }
    }

    /**
     * Write strict mode and make it live.
     *
     * Same shape as [writeAllowlist]: the remote boolean is what the
     * hooks read, and [broadcastAllowlistChanged] is what makes them
     * re-read it — they load the whole [GROUP_CONFIG] group in one go, so
     * one broadcast refreshes the allowlist and this flag together. When the
     * module service is not bound yet the change stays in the mirror and is
     * flagged, so the next bind pushes it up instead of dropping it.
     */
    @JvmStatic
    fun writeStrictMode(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_STRICT_MODE, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit().putBoolean(KEY_STRICT_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit().putBoolean(KEY_STRICT_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putBoolean(KEY_STRICT_MODE, enabled).commit()
            } catch (t: Throwable) {
                // As with the allowlist: a failed write must not pass for a live
                // change, so the next bind pushes the mirror up again.
                localPrefs(app).edit().putBoolean(KEY_STRICT_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
        }
    }

    private fun localPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
    }

    /** Application context where available: broadcasts must not outlive the caller. */
    private fun appContext(context: Context): Context {
        val app = context.applicationContext
        return app ?: context
    }

    /**
     * Serialises allowlist writes: one background thread, in order, so a burst of
     * taps cannot interleave and lose the last write.
     */
    private val WRITER: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        val thread = Thread(runnable, "fcmlive-allowlist-write")
        thread.isDaemon = true
        thread
    }

    /**
     * Write the allowlist and make it live.
     *
     * Remote prefs are what system_server's hooks read, so this write plus the
     * broadcast that follows it is what makes a change take effect — no refresh, no
     * restart. The write itself is a synchronous cross-process commit and therefore
     * runs on a background thread: on the calling (main) thread it could stall the
     * UI, and `apply()` is not an option here because the broadcast must not
     * outrun the value it announces. When [remotePrefs] is null (module
     * service not bound in this process) the change is kept in the local mirror and
     * flagged, so the next bind pushes it up rather than dropping it.
     */
    @JvmStatic
    fun writeAllowlist(
        context: Context,
        remotePrefs: SharedPreferences?,
        allowlist: Set<String>
    ) {
        val copy = HashSet(allowlist)
        val app = appContext(context)
        writeLocalAllowlist(app, copy)
        if (remotePrefs == null) {
            markPendingPush(app)
            broadcastAllowlistChanged(app)
            return
        }
        clearPendingPush(app)
        WRITER.execute {
            try {
                remotePrefs.edit().putStringSet(KEY_ALLOWLIST, copy).commit()
            } catch (t: Throwable) {
                // A failed write must not pass for a live change: keep the flag so
                // the next bind pushes the mirror up again.
                markPendingPush(app)
            }
            // Announced after the write, so a reader of the prefs sees this value.
            broadcastAllowlistChanged(app)
        }
    }

    /**
     * Ask system_server to re-read the shared config group. Sent three times
     * over ~1.5s because the receiver there is installed by a retry loop shortly
     * after boot (`Hooker.installAllowlistReceiverAsync`): a change made
     * in that window would otherwise be dropped and appear to need a refresh.
     *
     * Despite the name it is not allowlist-only: the receiver reloads
     * [GROUP_CONFIG] wholesale, so this also carries a strict-mode change
     * (see [writeStrictMode]) — which is why the two share one action.
     */
    @JvmStatic
    fun broadcastAllowlistChanged(context: Context) {
        val app = appContext(context)
        app.sendBroadcast(Intent(ACTION_ALLOWLIST_CHANGED))
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ app.sendBroadcast(Intent(ACTION_ALLOWLIST_CHANGED)) }, 400L)
        handler.postDelayed({ app.sendBroadcast(Intent(ACTION_ALLOWLIST_CHANGED)) }, 1500L)
    }
}
