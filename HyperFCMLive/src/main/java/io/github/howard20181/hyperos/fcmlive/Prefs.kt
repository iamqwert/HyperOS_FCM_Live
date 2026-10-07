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
 * its in-memory copy. A second action is sent only when the autostart write is
 * turned on — [ACTION_APPLY_AUTOSTART], which asks the hook to run that write
 * over the whole allowlist instead of waiting for each app's next push (see
 * [KEY_WAKE_WRITE_AUTOSTART]).
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
 *     shared by both branches) — see `isSleepKeepaliveDataEnabled()` in
 *     Hooker.kt;
 *  2. the UI keeps the sub switch inside the *same* `AnimatedVisibility` as the
 *     master;
 *  3. the key is added to `MainActivity.reloadAllowlist`'s pending-repair set,
 *     so a value the module never saw gets pushed again.
 *
 * A sub-option that is not a boolean follows the same three, with one
 * relaxation: `wifi_weak_signal_floor` is reached only inside the branch its
 * master already opened, so the AND is structural and needs no extra flag. It
 * still has to sit in the master's `AnimatedVisibility` and still has to be
 * sanitized — a value from a retired option list would otherwise read as a
 * depth nobody offers any more.
 *
 * The remaining pair is `wifi_weak_signal_relaxed` ⊃ `wifi_weak_signal_floor`.
 * `wake_write_autostart` used to be the sub-switch of `wake_autostart_relaxed`
 * and is now a switch of its own: the master answered one broadcast gate the
 * shipped GMS→c2dm hop already covers — real FCM calls always come from GMS —
 * so it could never change an outcome the sub-switch did not reach anyway.
 * `wake_stopped_packages` is gone for the same reason: the flag it added to a
 * broadcast is the same flag the shipped hop adds for the GMS→c2dm call, so
 * what it reached beyond that hop was never an FCM broadcast at all. It keeps a
 * section of its own because it gates a different ROM mechanism from the
 * AOSP stopped state the shipped hop opens (the MIUI autostart AppOp), and it
 * is the one experiment that writes a user-visible setting. `sleep_keepalive` and
 * `sleep_keepalive_data` used to be one of these and are now **peers**: each
 * radio in sleep mode's cutoff follows its own key, neither gates the other, and
 * the screen shows both rows — see
 * `isSleepKeepaliveDataEnabled()` in Hooker.kt for why rule 1 stopped applying
 * to that pair. A `…_charging_only` sibling
 * used to live beside them and was later removed — see
 * HOOKS_AND_DIAGNOSTICS.md §5.5 for why ("only while charging" narrowed the
 * master and could never widen it, and the premise it was justified by turned
 * out to be false). The WeChat pair was removed with the shield (see
 * HOOKS_AND_DIAGNOSTICS.md Appendix C).
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
     * this switch — that is what the other switch is for.
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
     * Remote + local: "keep mobile data up during sleep".
     *
     * A **peer** of [KEY_SLEEP_KEEPALIVE], not its sub-switch. Each of the two
     * radios in sleep mode's cutoff follows its own key and this one is read on
     * its own, so turning the WiFi switch off no longer silences it. With both
     * on the pair behaves like a single "keep the whole network up" switch —
     * both radios survive the night, at a higher cost — but either half can
     * also be had without the other.
     *
     * Default **off**, same reasoning as the WiFi switch — ask before
     * holding a radio open overnight.
     */
    const val KEY_SLEEP_KEEPALIVE_DATA = "sleep_keepalive_data"
    /** UI-only: set while the mirror holds a data-keepalive change the module never saw. */
    private const val KEY_SLEEP_KEEPALIVE_DATA_PENDING_PUSH = "sleep_keepalive_data_pending_push"

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

    /**
     * Remote + local: "relaxed WiFi weak-signal switch" experiment.
     *
     * Background, measured on-device (HyperOS V816, see Hooks doc §5.9.2):
     * `AmlMiuiThirdPartScorer` turns `mLegacyIntScore` into a usable/unusable
     * verdict at a hardcoded threshold of 50, and reports it outward once per
     * update through `notifyScoreAndIsUsable()`. A score below 50 makes
     * `WifiScoreReport` mark the network `+EXITING`, and ConnectivityService
     * then moves the default network to cellular for 30 s. The hook clamps the
     * score to 50 for the duration of that one call, so the weak-signal verdict
     * is never published.
     *
     * Scope of the change, which is why it belongs in this file's experiment
     * set rather than near the FCM allowlist:
     * - the clamp lives in the arguments of a single in-flight call; nothing is
     *   written to disk, to Settings, or anywhere else upstream, so turning the
     *   switch off — or uninstalling — leaves no residue;
     * - the real score is restored on the way out, so mechanism that does not
     *   go through the scorer (carrier/UI decisions, WiFi actually leaving) is
     *   untouched.
     *
     * Default **off**, like every other experiment: it deliberately keeps the
     * device on a WiFi link the ROM judged too weak, which is a quality-of-
     * service trade, not a repair.
     */
    const val KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED = "wifi_weak_signal_switch_relaxed"
    /** UI-only: set while the mirror holds a relaxed-switch change the module never saw. */
    private const val KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED_PENDING_PUSH =
        "wifi_weak_signal_switch_relaxed_pending_push"

    /**
     * Remote + local: how weak a WiFi link may get before this switch stops
     * covering it — the sub-option of [KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED].
     *
     * The master switch on its own rescues *every* score below the ROM's floor,
     * however far below: a link scored 33 gets exactly the treatment one scored
     * 49 does. Only consulted while the master is on, so it can narrow but
     * never widen the master, and it needs no other gate — the hook reaches it
     * only inside the branch the master already opened.
     *
     * Below the chosen value the score is handed to the ROM untouched, so the
     * network moves to cellular exactly as it would without the module. That is
     * the trade being offered: the switch exists to stop needless switching,
     * and there is a depth past which staying is worse than the switch it was
     * trying to avoid. Nothing is gained by pretending otherwise, and the
     * description on screen says so.
     *
     * The four values step by five. The ROM's own floor is 50; around 45 is the
     * marginal band that produced the recovery decisions in the V816 samples —
     * one gaming window (1409 samples) reached 33 with only four samples below
     * 35, and another (1245 samples) never went below 44 — so 30 is offered
     * rather than proved, as the far end of a scale the middle of which is
     * where the measurement actually sits.
     *
     * Default **45**: the narrowest rescue, so switching the master on changes
     * the least. Anything deeper is a deliberate widening, not a default.
     */
    const val KEY_WIFI_WEAK_SIGNAL_FLOOR = "wifi_weak_signal_floor"
    /** UI-only: set while the mirror holds a floor change the module never saw. */
    private const val KEY_WIFI_WEAK_SIGNAL_FLOOR_PENDING_PUSH =
        "wifi_weak_signal_floor_pending_push"
    /** Offered floors, narrowest first. */
    val WIFI_WEAK_SIGNAL_FLOORS: IntArray = intArrayOf(45, 40, 35, 30)
    /** Default floor: see [KEY_WIFI_WEAK_SIGNAL_FLOOR]. */
    const val WIFI_WEAK_SIGNAL_FLOOR_DEFAULT = 45

    /**
     * Remote + local: "write the autostart permission" switch.
     *
     * For the explicitly checked packages, this writes the ROM's own AppOp:
     * the MIUI autostart op (`10008`) is set to `MODE_ALLOWED` for the package, which is exactly what
     * `android.miui.AppOpsUtils#setApplicationAutoStart(ctx, pkg, true)` does
     * — the package then passes `WakePathChecker#isAllowedByWakePathRule` for
     * every later broadcast too, including the ones the module never sees.
     *
     * It used to be the sub-switch of `wake_autostart_relaxed`, which answered
     * that same gate in memory for broadcasts the module did intercept. That
     * master is gone: every real FCM broadcast is the shipped GMS→c2dm hop
     * (the caller is always GMS), so the master's branch never ran on anything
     * the shipped path had not already answered, and measuring with all four
     * experiment switches off still had push arriving. What is left is the half
     * that outlives the broadcast — the op on disk.
     *
     * The write happens on two occasions. Turning the switch **on** applies it
     * to the whole allowlist at once — the app broadcasts
     * [ACTION_APPLY_AUTOSTART] and the module walks the list — so the row takes
     * effect on the spot and the result can be checked from a shell straight
     * away. Afterwards it is *lazy*: each qualifying push (the c2dm / Firebase
     * action family) rewrites the op for its own package, which is what
     * corrects a package some other tool has reset. That second half is why a
     * package is not fixed the instant it drifts — it waits for its next push.
     *
     * This is the one experiment that edits a user-visible system setting, and
     * the earlier "bypass the gate, never write the op" line is relaxed for it
     * on purpose: it is off by default and only reachable from the experiment
     * screen, so the write is a choice the user made. It is not reverted when
     * the switch is turned off — the ROM's own control reverses it instead:
     * switching *autostart* off in 手机管家's app management writes both this
     * op and its switch companion back, so the user can undo it there without
     * the module having to remember anything.
     *
     * Default **off**, same reasoning as the stopped-state switch.
     */
    const val KEY_WAKE_WRITE_AUTOSTART = "wake_write_autostart"
    /** UI-only: set while the mirror holds an autostart-write change the module never saw. */
    private const val KEY_WAKE_WRITE_AUTOSTART_PENDING_PUSH =
        "wake_write_autostart_pending_push"

    /**
     * Remote + local: release the ROM's autostart verdict for a checked app.
     *
     * The write above and this one fix the same thing by different means, and
     * the difference is worth keeping straight because it is the whole reason
     * this row exists. Writing the op changes a setting the user can see, and
     * from then on the ROM allows *every* later start on its own — including
     * the ones this module never sees. This switch answers two checkpoints in
     * memory instead: the service start
     * (`AutoStartManagerServiceStubImpl#isAllowStartService`) and the process
     * start (`ProcessManagerService#isAllowAutoStart`). Nothing is written, so
     * closing the row restores the ROM's own verdict at once, and nothing can
     * drift out from under 手机管家.
     *
     * The pair is not redundant in the other direction either. The module's
     * shipped hook answers the *broadcast* gate
     * (`BroadcastQueueModernStubImpl#checkApplicationAutoStart`) at method
     * entry, so on the GMS→c2dm hop the op is never read there — the two
     * checkpoints above are read on their own, which is why the op has to be
     * written or answered even though the broadcast gate is already open.
     *
     * Whether the app's process was cached or reaped decides which of the two
     * is asked, not whether one is asked: a cached process still has to start
     * its messaging service. Both read the same op, so the two paths do not
     * need separate treatments.
     *
     * Default **off**, like every other experiment.
     */
    const val KEY_AUTOSTART_GATE_RELEASE = "autostart_gate_release"
    /** UI-only: set while the mirror holds an autostart-gate change the module never saw. */
    private const val KEY_AUTOSTART_GATE_RELEASE_PENDING_PUSH =
        "autostart_gate_release_pending_push"

    /**
     * Remote + local: release the restart-after-death gate for a checked app.
     *
     * `AutoStartManagerServiceStubImpl#canRestartServiceLocked` is the one
     * `10008` read whose verdict decides freezing directly: `false` sends the
     * process to `KillProcessInfo` instead of a restart. Both overloads were
     * probe-only until live samples showed the gate actually denying checked
     * packages (2026-10-07: `restart DENIED` with `callee` naming a checked
     * push target), which is the condition the probe was waiting for.
     *
     * Reads **only its own key** — not the master above it — so the ladder
     * stays a set of independently testable variables rather than an AND
     * chain: isolating the freeze gate alone is the whole point of the rung.
     *
     * Default **off**, like every other experiment.
     */
    const val KEY_AUTOSTART_RESTART_RELEASE = "autostart_restart_release"
    /** UI-only: set while the mirror holds a restart-gate change the module never saw. */
    private const val KEY_AUTOSTART_RESTART_RELEASE_PENDING_PUSH =
        "autostart_restart_release_pending_push"

    /**
     * Remote + local: answer the autostart op query itself for a checked app.
     *
     * Hooks the `AppOpsManager` client entry the ROM's own readers call —
     * `checkOpNoThrow(op, uid, pkg)` and `noteOpNoThrow(op, uid, pkg, tag,
     * message)` — and returns `MODE_ALLOWED` for op `10008` when the package
     * is checked. One hook covers every system_server-side reader that funnels
     * through these two calls, including ones no per-checkpoint hook names.
     * Reads only in system_server, so the manager UI's own reads (another
     * process) stay untouched — the display keeps telling the truth.
     *
     * Two consequences are accepted and documented rather than hidden: the
     * pre-start punishment path reads the same query, so it is released too;
     * and a faked `noteOp` return skips the usage note, so the manager's own
     * autostart record can miss entries for checked apps.
     *
     * Default **off**, like every other experiment.
     */
    const val KEY_AUTOSTART_ROOT_RELEASE = "autostart_root_release"
    /** UI-only: set while the mirror holds a root-gate change the module never saw. */
    private const val KEY_AUTOSTART_ROOT_RELEASE_PENDING_PUSH =
        "autostart_root_release_pending_push"

    /**
     * Remote + local: also write the autostart *switch* op (`10053`,
     * `OP_AUTO_START_SWITCH`) alongside the behavior op.
     *
     * Meaningless alone — it is ANDed with [KEY_WAKE_WRITE_AUTOSTART] on the
     * hook side, so a rung with nothing under it stays inert. Its purpose is
     * display symmetry: the manager's own toggle writes both ops, so writing
     * only `10008` leaves the switch side behind. Whether the manager's row
     * reads `10053` for display is not yet bytecode-confirmed — until then the
     * copy promises nothing about what the manager will show.
     *
     * Default **off**, like every other experiment.
     */
    const val KEY_WAKE_WRITE_AUTOSTART_SWITCH = "wake_write_autostart_switch"
    /** UI-only: set while the mirror holds a switch-write change the module never saw. */
    private const val KEY_WAKE_WRITE_AUTOSTART_SWITCH_PENDING_PUSH =
        "wake_write_autostart_switch_pending_push"

    /** Action the app broadcasts after writing, to refresh system_server. */
    const val ACTION_ALLOWLIST_CHANGED = MODULE_PKG + ".ALLOWLIST_CHANGED"

    /**
     * Action that asks the system_server hook to run the autostart write over
     * the whole allowlist right now, instead of waiting for each package to be
     * handed a push first.
     *
     * Deliberately not folded into [ACTION_ALLOWLIST_CHANGED]: that one is a
     * "re-read your copy" hint sent after *every* settings write, and it is
     * throttled on the receiving side. This one asks for a one-off piece of
     * work, and losing it costs nothing beyond the lazy path it was meant to
     * short-circuit.
     */
    const val ACTION_APPLY_AUTOSTART = MODULE_PKG + ".APPLY_AUTOSTART"

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

    /** Relaxed WiFi weak-signal switch value as the UI last left it. */
    @JvmStatic
    fun readLocalWifiWeakSignalSwitchRelaxed(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED, false)
    }

    /** Relaxed WiFi weak-signal switch counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingWifiWeakSignalSwitchRelaxedPush(context: Context): Boolean {
        return localPrefs(context)
            .getBoolean(KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED_PENDING_PUSH, false)
    }

    /**
     * Write the relaxed WiFi weak-signal flag and make it live.
     *
     * Same shape as [writeWechatDozeKeepout]. The hook lives in system_server
     * and reads the remote value lazily at each qualifying call, so flipping
     * this takes effect on the next score update without a reboot.
     */
    @JvmStatic
    fun writeWifiWeakSignalSwitchRelaxed(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit()
                .putBoolean(KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit()
            .putBoolean(KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit()
                    .putBoolean(KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit()
                    .putBoolean(KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
        }
    }

    /** True when [value] is one of the offered floors. */
    @JvmStatic
    fun isValidWeakSignalFloor(value: Int): Boolean {
        for (floor in WIFI_WEAK_SIGNAL_FLOORS) {
            if (floor == value) {
                return true
            }
        }
        return false
    }

    /**
     * Fall back to the default rather than acting on an unknown value: a floor
     * left over from a wider list of options would otherwise be read as a
     * depth nobody offers any more, and the two failures look alike.
     */
    @JvmStatic
    fun sanitizeWeakSignalFloor(value: Int): Int {
        return if (isValidWeakSignalFloor(value)) value else WIFI_WEAK_SIGNAL_FLOOR_DEFAULT
    }

    /** Weak-signal floor as the UI last left it, sanitized the same way the hook does. */
    @JvmStatic
    fun readLocalWifiWeakSignalFloor(context: Context): Int {
        return sanitizeWeakSignalFloor(
            localPrefs(context).getInt(KEY_WIFI_WEAK_SIGNAL_FLOOR, WIFI_WEAK_SIGNAL_FLOOR_DEFAULT)
        )
    }

    /** Weak-signal floor counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingWifiWeakSignalFloorPush(context: Context): Boolean {
        return localPrefs(context)
            .getBoolean(KEY_WIFI_WEAK_SIGNAL_FLOOR_PENDING_PUSH, false)
    }

    /**
     * Write the floor and make it live. Integer rather than boolean, otherwise
     * identical to [writeWifiWeakSignalSwitchRelaxed]; the hook reads it at the
     * same lazily-refreshed call, so a change lands on the next score update.
     */
    @JvmStatic
    fun writeWifiWeakSignalFloor(
        context: Context,
        remotePrefs: SharedPreferences?,
        floor: Int
    ) {
        val value = sanitizeWeakSignalFloor(floor)
        val app = appContext(context)
        localPrefs(app).edit().putInt(KEY_WIFI_WEAK_SIGNAL_FLOOR, value).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit()
                .putBoolean(KEY_WIFI_WEAK_SIGNAL_FLOOR_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit()
            .putBoolean(KEY_WIFI_WEAK_SIGNAL_FLOOR_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putInt(KEY_WIFI_WEAK_SIGNAL_FLOOR, value).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit()
                    .putBoolean(KEY_WIFI_WEAK_SIGNAL_FLOOR_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
        }
    }

    /** Autostart-write switch value as the UI last left it. */
    @JvmStatic
    fun readLocalWakeWriteAutostart(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WAKE_WRITE_AUTOSTART, false)
    }

    /** Autostart-write counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingWakeWriteAutostartPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WAKE_WRITE_AUTOSTART_PENDING_PUSH, false)
    }

    /**
     * Write the autostart-write switch and make it live.
     *
     * Same shape as [writeSleepKeepalive]. It stands on its own now, so
     * unlike a sub-switch there is no master whose state hides it: the value the
     * hook reads is the value shown on screen.
     */
    @JvmStatic
    fun writeWakeWriteAutostart(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_WAKE_WRITE_AUTOSTART, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit()
                .putBoolean(KEY_WAKE_WRITE_AUTOSTART_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit()
            .putBoolean(KEY_WAKE_WRITE_AUTOSTART_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putBoolean(KEY_WAKE_WRITE_AUTOSTART, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit()
                    .putBoolean(KEY_WAKE_WRITE_AUTOSTART_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
            // Turning this switch on is the one moment the user is looking at this
            // row, so the write is applied to the whole allowlist now rather
            // than whenever each app happens to receive its next push. Sent only
            // when enabling: turning it off deliberately leaves what was already
            // written alone, and the module never learns the previous values.
            if (enabled) {
                broadcastApplyAutostart(app)
            }
        }
    }

    /** Switch-write switch value as the UI last left it. */
    @JvmStatic
    fun readLocalWakeWriteAutostartSwitch(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WAKE_WRITE_AUTOSTART_SWITCH, false)
    }

    /** Switch-write counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingWakeWriteAutostartSwitchPush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_WAKE_WRITE_AUTOSTART_SWITCH_PENDING_PUSH, false)
    }

    /**
     * Write the switch-write switch and make it live.
     *
     * Same shape as [writeWakeWriteAutostart]: the writes ride the same two
     * occasions (the on-request walk over the whole allowlist, then the lazy
     * per-push rewrite), so enabling this one also asks for the walk now —
     * otherwise every package would wait for its next push before its
     * `10053` followed its `10008`.
     */
    @JvmStatic
    fun writeWakeWriteAutostartSwitch(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_WAKE_WRITE_AUTOSTART_SWITCH, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit()
                .putBoolean(KEY_WAKE_WRITE_AUTOSTART_SWITCH_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit()
            .putBoolean(KEY_WAKE_WRITE_AUTOSTART_SWITCH_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit()
                    .putBoolean(KEY_WAKE_WRITE_AUTOSTART_SWITCH, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit()
                    .putBoolean(KEY_WAKE_WRITE_AUTOSTART_SWITCH_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
            if (enabled) {
                broadcastApplyAutostart(app)
            }
        }
    }

    /** Autostart-gate switch value as the UI last left it. */
    @JvmStatic
    fun readLocalAutostartGateRelease(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_AUTOSTART_GATE_RELEASE, false)
    }

    /** Autostart-gate counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingAutostartGateReleasePush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_AUTOSTART_GATE_RELEASE_PENDING_PUSH, false)
    }

    /**
     * Write the autostart-gate switch and make it live.
     *
     * Same shape as [writeWakeWriteAutostart], and unlike the write above there
     * is nothing to apply up front: the hook answers a gate the ROM asks about
     * at the moment of the start, so the value takes effect on the next one
     * without an apply broadcast.
     */
    @JvmStatic
    fun writeAutostartGateRelease(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_AUTOSTART_GATE_RELEASE, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit()
                .putBoolean(KEY_AUTOSTART_GATE_RELEASE_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit()
            .putBoolean(KEY_AUTOSTART_GATE_RELEASE_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putBoolean(KEY_AUTOSTART_GATE_RELEASE, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit()
                    .putBoolean(KEY_AUTOSTART_GATE_RELEASE_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
        }
    }

    /** Restart-gate switch value as the UI last left it. */
    @JvmStatic
    fun readLocalAutostartRestartRelease(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_AUTOSTART_RESTART_RELEASE, false)
    }

    /** Restart-gate counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingAutostartRestartReleasePush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_AUTOSTART_RESTART_RELEASE_PENDING_PUSH, false)
    }

    /**
     * Write the restart-gate switch and make it live.
     *
     * Same shape as [writeAutostartGateRelease]: the hook answers a gate the
     * ROM asks about at the moment of the decision, so the value takes effect
     * on the next one without an apply broadcast.
     */
    @JvmStatic
    fun writeAutostartRestartRelease(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_AUTOSTART_RESTART_RELEASE, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit()
                .putBoolean(KEY_AUTOSTART_RESTART_RELEASE_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit()
            .putBoolean(KEY_AUTOSTART_RESTART_RELEASE_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putBoolean(KEY_AUTOSTART_RESTART_RELEASE, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit()
                    .putBoolean(KEY_AUTOSTART_RESTART_RELEASE_PENDING_PUSH, true).apply()
            }
            broadcastAllowlistChanged(app)
        }
    }

    /** Root-gate switch value as the UI last left it. */
    @JvmStatic
    fun readLocalAutostartRootRelease(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_AUTOSTART_ROOT_RELEASE, false)
    }

    /** Root-gate counterpart of [hasPendingPush]. */
    @JvmStatic
    fun hasPendingAutostartRootReleasePush(context: Context): Boolean {
        return localPrefs(context).getBoolean(KEY_AUTOSTART_ROOT_RELEASE_PENDING_PUSH, false)
    }

    /**
     * Write the root-gate switch and make it live.
     *
     * Same shape as [writeAutostartGateRelease]: the hook answers a query the
     * ROM makes at the moment of the read, so the value takes effect on the
     * next one without an apply broadcast.
     */
    @JvmStatic
    fun writeAutostartRootRelease(
        context: Context,
        remotePrefs: SharedPreferences?,
        enabled: Boolean
    ) {
        val app = appContext(context)
        localPrefs(app).edit().putBoolean(KEY_AUTOSTART_ROOT_RELEASE, enabled).apply()
        if (remotePrefs == null) {
            localPrefs(app).edit()
                .putBoolean(KEY_AUTOSTART_ROOT_RELEASE_PENDING_PUSH, true).apply()
            broadcastAllowlistChanged(app)
            return
        }
        localPrefs(app).edit()
            .putBoolean(KEY_AUTOSTART_ROOT_RELEASE_PENDING_PUSH, false).apply()
        WRITER.execute {
            try {
                remotePrefs.edit().putBoolean(KEY_AUTOSTART_ROOT_RELEASE, enabled).commit()
            } catch (t: Throwable) {
                localPrefs(app).edit()
                    .putBoolean(KEY_AUTOSTART_ROOT_RELEASE_PENDING_PUSH, true).apply()
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

    /**
     * Keys of experiments this version no longer has.
     *
     * `wake_stopped_packages` (and its pending-push marker) is the first entry:
     * the switch that added `FLAG_INCLUDE_STOPPED_PACKAGES` to a broadcast for
     * a checked package from any caller is gone — the shipped GMS→c2dm hop
     * already adds that flag, so the switch could only ever change the outcome
     * of a broadcast that was not FCM. Nothing reads the key any more, so what
     * dropping it buys is that a dump of the module's config stops listing a
     * switch that does not exist, and that a key later reused under a different
     * meaning cannot inherit a value from the switch that used to own it.
     *
     * `wake_autostart_relaxed` went the same way in the same release: it
     * released the autostart verdict for one broadcast at a time, and every
     * real FCM broadcast is the shipped hop where the verdict is already
     * answered. Its name survives only inside the autostart ladder's
     * `autostart_*` keys, which are different mechanisms with different keys.
     *
     * Add to this list when a switch is removed. There is no expiry: the entry
     * has to outlast every version that could still read the key.
     */
    private val RETIRED_CONFIG_KEYS = arrayOf(
        "wake_stopped_packages",
        "wake_stopped_packages_pending_push",
        "wake_autostart_relaxed",
        "wake_autostart_relaxed_pending_push"
    )

    /**
     * Remove [RETIRED_CONFIG_KEYS] from both copies of the config: the local
     * mirror the screens read, and the remote copy the hooks in system_server
     * read. Both were written, so both are cleaned.
     *
     * Called each time the service binds rather than once, and deliberately
     * without a "done" marker: a run with a null [remotePrefs] can only reach
     * the mirror and is therefore not finished, and marking it done would leave
     * the copy the module reads dirty forever. Once it has succeeded every
     * `contains` is false, so the call costs two reads per key and no write.
     *
     * No [broadcastAllowlistChanged] afterwards: nothing reads these keys any
     * more, so there is nothing for system_server to reload.
     */
    @JvmStatic
    fun dropRetiredConfigKeys(context: Context, remotePrefs: SharedPreferences?) {
        val app = appContext(context)
        if (remotePrefs == null) {
            // Only the mirror is reachable this time. Clean it, and let the next
            // bind take the copy that matters.
            val local = localPrefs(app).edit()
            for (key in RETIRED_CONFIG_KEYS) {
                local.remove(key)
            }
            local.apply()
            return
        }
        if (RETIRED_CONFIG_KEYS.none { localPrefs(app).contains(it) } &&
            RETIRED_CONFIG_KEYS.none { remotePrefs.contains(it) }
        ) {
            return
        }
        WRITER.execute {
            try {
                val remote = remotePrefs.edit()
                for (key in RETIRED_CONFIG_KEYS) {
                    remote.remove(key)
                }
                remote.commit()
            } catch (ignored: Throwable) {
                // Left as it was: the call runs again on the next bind, so a
                // failed removal costs one more attempt and nothing else.
            }
            val local = localPrefs(app).edit()
            for (key in RETIRED_CONFIG_KEYS) {
                local.remove(key)
            }
            local.apply()
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

    /**
     * Ask system_server to apply the autostart write to every allowlisted
     * package now — see [ACTION_APPLY_AUTOSTART].
     *
     * Sent once rather than repeated like [broadcastAllowlistChanged]: the
     * receiving side re-reads the allowlist itself before walking it, so a miss
     * here is covered by the three reload broadcasts, and a duplicate would
     * only re-walk a list that is already written.
     */
    @JvmStatic
    fun broadcastApplyAutostart(context: Context) {
        appContext(context).sendBroadcast(Intent(ACTION_APPLY_AUTOSTART))
    }
}
