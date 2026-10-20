package io.github.howard20181.hyperos.fcmlive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The FCM allowlist subsystem, extracted verbatim from `Hooker` (zero-behaviour
 * refactor). `Hooker`'s instance capabilities are injected at the call sites:
 * `module` is the [XposedInterface] the module entry object is, and
 * `systemContext` is `Hooker#getSystemContext` as a supplier (its lazy
 * resolution and retry-on-null semantics stay with the caller).
 */
internal object AllowlistGate {

    @Volatile
    private var sAllowlist: Set<String> = emptySet()

    // ---- Auto-check experiment (Prefs.KEY_AUTO_CHECK_NEW / _BASELINE) ----
    //
    // The switch widens the allowlist without writing it: the effective answer
    // is `sAllowlist ∪ autoSet`, where autoSet is the package manager's FCM
    // client scan minus the baseline. The baseline is the part the hook cannot
    // compute on its own — "what existed when the user turned the switch on,
    // plus everything they unchecked since" — so it rides the same remote
    // reload the allowlist does, and the scan half is recomputed locally.

    /** The experiment switch as the last reload saw it. */
    @Volatile
    private var sAutoCheckEnabled = false

    /**
     * Packages the widening must never serve: the FCM set as it stood when the
     * switch was turned on, grown by every later uncheck. **Null means the key
     * is absent from the remote group** — the toggle landed without its
     * baseline (one half of the pair write lost) — and the widening stays off
     * entirely: fail closed, never "widen for everything".
     */
    @Volatile
    private var sAutoBaseline: Set<String>? = null

    /**
     * The last scan's widening candidates: FCM clients minus the baseline as
     * the scan saw it. The baseline filter is applied again at every read, so a
     * baseline that changed between reloads cannot leak a package back in
     * through a scan that predates the change.
     */
    @Volatile
    private var sAutoSet: Set<String> = emptySet()

    /** Last completed auto-check scan; drives the lazy stale check. */
    @Volatile
    private var sAutoScanMs = 0L

    /** Coalesces the scan bursts a stream of installs would otherwise cause. */
    private val sAutoScanQueued = AtomicBoolean(false)

    internal fun loadAllowlistFromRemotePrefs(module: XposedInterface) {
        try {
            val prefs = module.getRemotePreferences(Prefs.GROUP_CONFIG)
            // This reload is the one config read every settings write reaches
            // (the reload broadcast), so it doubles as the debug-log gate's
            // freshest snapshot in system_server — see [HookLog].
            HookLog.refresh(prefs)
            val set = prefs.getStringSet(Prefs.KEY_ALLOWLIST, emptySet())
            val loaded = if (set != null) HashSet(set) else HashSet()
            if (loaded != sAllowlist) {
                // Log on content change, not on every read: the stale-path reload
                // would otherwise repeat an identical line every ALLOWLIST_STALE_MS.
                // The package count is deliberately not printed: without the names
                // it only ever said "not empty", which the boolean says directly,
                // and a 9-to-5 edit read as the same line either way.
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "allowlist loaded: selected=${loaded.isNotEmpty()}"
                )
            }
            sAllowlist = loaded
            loadAutoCheckConfig(module, prefs)
            sAllowlistFreshMs = SystemClock.uptimeMillis()
            sAllowlistFailureStreak = 0
        } catch (e: Exception) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to read remote allowlist", e)
            // A failed read must not push the next lazy retry a full
            // ALLOWLIST_STALE_MS into the future ("failure delays retry").
            // Backdate the freshness stamp so getFcmAllowlist() retries after an
            // exponentially growing backoff (1s, 2s, 4s ... capped at
            // ALLOWLIST_STALE_MS). sAllowlistReadMs keeps the real attempt time,
            // so requestAllowlistReload() still throttles repeated reads.
            val streak = ++sAllowlistFailureStreak
            val backoffMs = minOf(
                HookerSupport.ALLOWLIST_FAILURE_BACKOFF_BASE_MS shl (streak - 1).coerceAtMost(4),
                HookerSupport.ALLOWLIST_STALE_MS
            )
            sAllowlistFreshMs =
                SystemClock.uptimeMillis() - HookerSupport.ALLOWLIST_STALE_MS + backoffMs
        }
        sAllowlistReadMs = SystemClock.uptimeMillis()
    }

    /**
     * Read the auto-check pair from the same remote snapshot the allowlist just
     * came from. Runs inside the reload's try block, so a throw leaves the last
     * good pair in place — the same contract the allowlist itself has.
     *
     * A change invalidates the scan stamp (not the cached set): the next
     * decision re-scans, and until then the old set is filtered through the
     * *new* baseline, which can only narrow it.
     */
    private fun loadAutoCheckConfig(module: XposedInterface, prefs: SharedPreferences) {
        val enabled = prefs.getBoolean(Prefs.KEY_AUTO_CHECK_NEW, false)
        val stored = prefs.getStringSet(Prefs.KEY_AUTO_CHECK_BASELINE, null)
        val baseline: Set<String>? = if (stored != null) HashSet(stored) else null
        if (enabled != sAutoCheckEnabled || baseline != sAutoBaseline) {
            // -1 marks "key absent" in the log, the one state that disables the
            // widening outright; 0 is a legitimately empty baseline (nothing
            // qualified when the switch turned on — everything after it is new).
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "auto-check loaded: enabled=$enabled baseline=${baseline?.size ?: -1}"
            )
            sAutoScanMs = 0
        }
        sAutoCheckEnabled = enabled
        sAutoBaseline = baseline
    }

    /**
     * The widening's answer for one package, without any freshness work: a
     * plain read of the reload state plus the cached scan. The baseline test
     * runs here too — [sAutoSet] excludes the baseline as of its own scan, and
     * this re-check closes the gap for a baseline that moved since.
     */
    private fun autoAllows(packageName: String?): Boolean {
        if (packageName == null || !sAutoCheckEnabled) {
            return false
        }
        val baseline = sAutoBaseline ?: return false
        if (baseline.contains(packageName)) {
            return false
        }
        return sAutoSet.contains(packageName)
    }

    /**
     * Queue a re-scan when the cached one has gone stale. Called from the same
     * hot paths as [touchAllowlistFreshness]; like it, the work happens on the
     * allowlist handler thread and the caller answers from the cached state.
     */
    private fun touchAutoCheckFreshness(
        module: XposedInterface,
        systemContext: () -> Context?
    ) {
        if (!sAutoCheckEnabled || sAutoBaseline == null) {
            return
        }
        if (SystemClock.uptimeMillis() - sAutoScanMs < HookerSupport.ALLOWLIST_STALE_MS) {
            return
        }
        scheduleAutoScan(module, systemContext)
    }

    /**
     * Post one scan to the allowlist handler thread. Coalesced: a burst of
     * installs (or a decision stream during one) queues a single scan.
     */
    private fun scheduleAutoScan(
        module: XposedInterface,
        systemContext: () -> Context?
    ) {
        if (!sAutoCheckEnabled || sAutoBaseline == null) {
            return
        }
        if (!sAutoScanQueued.compareAndSet(false, true)) {
            return
        }
        try {
            allowlistBackgroundHandler().post {
                sAutoScanQueued.set(false)
                runAutoScan(module, systemContext)
            }
        } catch (t: Throwable) {
            sAutoScanQueued.set(false)
            module.logGated(Log.WARN, HookerSupport.TAG, "Failed to schedule the auto-check scan", t)
        }
    }

    /**
     * Re-run the FCM client scan and rebuild the widening set.
     *
     * The scan needs the system context, which can be null during early boot;
     * the stamp is left stale in that case, so the next decision retries — the
     * same self-healing shape the allowlist read uses. A failed scan keeps the
     * previous set: a partial marker answer can only narrow the widening, never
     * widen it, and the stale stamp brings a full rescan once the package
     * manager answers again.
     */
    private fun runAutoScan(
        module: XposedInterface,
        systemContext: () -> Context?
    ) {
        val baseline = sAutoBaseline
        if (!sAutoCheckEnabled || baseline == null) {
            return
        }
        val sys = systemContext() ?: return
        val fcm = try {
            FcmAppScan.queryFcmPackages(sys.packageManager) { label, t ->
                module.logGated(Log.WARN, HookerSupport.TAG, label, t)
            }
        } catch (t: Throwable) {
            module.logGated(Log.WARN, HookerSupport.TAG, "auto-check scan failed", t)
            return
        }
        val widened = HashSet(fcm)
        widened.removeAll(baseline)
        if (widened != sAutoSet) {
            // Content change only, mirroring the allowlist line: the counts are
            // the part a troubleshooting session can act on.
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "auto-check scan: fcm=${fcm.size} widened=${widened.size}"
            )
        }
        sAutoSet = widened
        sAutoScanMs = SystemClock.uptimeMillis()
    }

    internal fun hookAllowlist(
        module: XposedInterface,
        systemContext: () -> Context?
    ) {
        loadAllowlistFromRemotePrefs(module)
        installAllowlistReceiverAsync(module, systemContext)
    }

    @Volatile
    private var allowlistReceiverRegistered = false
    private val allowlistRegistering = AtomicBoolean(false)
    private val allowlistReloadQueued = AtomicBoolean(false)

    @Volatile
    private var sAllowlistReadMs = 0L

    /** Last SUCCESSFUL allowlist read; drives the lazy stale check in [getFcmAllowlist]. */
    @Volatile
    private var sAllowlistFreshMs = 0L

    /** Consecutive failed allowlist reads; grows the retry backoff, reset on success. */
    @Volatile
    private var sAllowlistFailureStreak = 0

    @Volatile
    private var allowlistHandler: Handler? = null

    private fun allowlistBackgroundHandler(): Handler {
        val handler = allowlistHandler
        if (handler != null) {
            return handler
        }
        synchronized(this) {
            if (allowlistHandler == null) {
                val thread = HandlerThread("fcmlive-allowlist")
                thread.start()
                allowlistHandler = Handler(thread.looper)
            }
            return allowlistHandler!!
        }
    }

    /**
     * Run [task] on the allowlist handler thread after [delayMs].
     *
     * Exists so other subsystems can borrow the thread's serialization without
     * reaching into the receiver plumbing: the autostart backfill uses it to
     * park its boot-time whole-allowlist walk somewhere that cannot race the
     * allowlist reads. Fails silently when the handler thread cannot start —
     * the callers of this helper are maintenance passes, not hot paths.
     */
    internal fun postDelayedToAllowlistHandler(
        module: XposedInterface,
        delayMs: Long,
        task: () -> Unit
    ) {
        try {
            allowlistBackgroundHandler().postDelayed(task, delayMs)
        } catch (t: Throwable) {
            module.logGated(Log.WARN, HookerSupport.TAG, "Failed to schedule a task on the allowlist handler", t)
        }
    }

    private fun requestAllowlistReload(module: XposedInterface) {
        val sinceLastRead = SystemClock.uptimeMillis() - sAllowlistReadMs
        if (sinceLastRead >= HookerSupport.ALLOWLIST_RELOAD_MIN_MS) {
            allowlistBackgroundHandler().post { loadAllowlistFromRemotePrefs(module) }
            return
        }
        if (!allowlistReloadQueued.compareAndSet(false, true)) {
            return
        }
        allowlistBackgroundHandler().postDelayed({
            allowlistReloadQueued.set(false)
            loadAllowlistFromRemotePrefs(module)
        }, HookerSupport.ALLOWLIST_RELOAD_MIN_MS - sinceLastRead)
    }

    private fun installAllowlistReceiverAsync(
        module: XposedInterface,
        systemContext: () -> Context?
    ) {
        if (allowlistReceiverRegistered) {
            return
        }
        val t = Thread({
            // Early boot is why this loop exists, so an early failure is the
            // expected case, not the reportable one: at uptime ~13s
            // `ContextImpl.registerReceiverInternal` still holds a null
            // IActivityManager and throws NPE; the retry then succeeds a few
            // seconds later (observed on device 2026-10-05: attempt 0 at
            // 23:03:14.787 failed, an attempt 3s later logged "installed").
            // Only the final attempt reports the throwable — reporting attempt 0
            // would put a stack trace in every single boot log for something that
            // heals itself 3s later, and would then let the once-per-boot guard
            // swallow a genuine failure on the lazy path.
            for (attempt in 0 until HookerSupport.ALLOWLIST_REGISTER_MAX_ATTEMPTS) {
                val lastAttempt = attempt == HookerSupport.ALLOWLIST_REGISTER_MAX_ATTEMPTS - 1
                if (registerAllowlistReceiver(module, systemContext, reportFailure = lastAttempt)) {
                    return@Thread
                }
                if (lastAttempt) {
                    break
                }
                try {
                    Thread.sleep(HookerSupport.ALLOWLIST_REGISTER_RETRY_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
            module.logGated(
                Log.WARN, HookerSupport.TAG, "Allowlist receiver not installed during boot;" +
                    " falling back to lazy registration"
            )
        }, "fcmlive-allowlist-register")
        t.isDaemon = true
        t.start()
    }

    /**
     * Lazy-reload bookkeeping shared by every allowlist read: make sure the
     * receiver is registered, and nudge a reload if the in-memory copy has gone
     * stale without one. Split out so [allowlistContains] can do a membership test
     * without the defensive copy [getFcmAllowlist] makes for callers that walk the
     * set — the broadcast / autostart gates ask "is this package on the list"
     * thousands of times and never need the list itself.
     */
    private fun touchAllowlistFreshness(
        module: XposedInterface,
        systemContext: () -> Context?
    ) {
        registerAllowlistReceiver(module, systemContext)
        if (!allowlistReceiverRegistered &&
            SystemClock.uptimeMillis() - sAllowlistFreshMs >= HookerSupport.ALLOWLIST_STALE_MS
        ) {
            requestAllowlistReload(module)
        }
        // The auto-check scan rides the same freshness idea: answer from the
        // cached set, re-scan in the background when it has gone stale.
        touchAutoCheckFreshness(module, systemContext)
    }

    internal fun getFcmAllowlist(
        module: XposedInterface,
        systemContext: () -> Context?
    ): Set<String> {
        touchAllowlistFreshness(module, systemContext)
        // A copy: callers that walk this (the autostart migration, the apply pass) must
        // not see sAllowlist swapped out from under them mid-iteration by a reload.
        val merged = HashSet(sAllowlist)
        // Auto-check widening, with the baseline filter re-applied at read time:
        // sAutoSet excludes the baseline as of its own scan, and a baseline that
        // changed since must not leak a package back in through the cached set.
        val baseline = sAutoBaseline
        if (sAutoCheckEnabled && baseline != null) {
            for (pkg in sAutoSet) {
                if (!baseline.contains(pkg)) {
                    merged.add(pkg)
                }
            }
        }
        return merged
    }

    /** Membership without the copy — the hot path for the gates. */
    internal fun allowlistContains(
        module: XposedInterface,
        systemContext: () -> Context?,
        packageName: String?
    ): Boolean {
        touchAllowlistFreshness(module, systemContext)
        // sAllowlist is only ever replaced wholesale (never mutated in place), so a
        // volatile read is a stable snapshot; no copy needed for a single contains().
        if (sAllowlist.contains(packageName)) {
            return true
        }
        return autoAllows(packageName)
    }

    /**
     * The single decision point: may the module act for [packageName]?
     *
     * The allowlist is the only control, and it always narrows — there is no
     * global "release everything" switch any more:
     *
     * - an app on the list gets help;
     * - an app off the list gets none of it, so the ROM's own verdict stands;
     * - an **empty** list is "nothing is selected", so every app is off the list
     *   and every restriction stays in force. The user releases restrictions one
     *   app at a time by checking it.
     *
     * GMS is exempt unconditionally. That is the whole point of the module — the
     * GMS/FCM chain itself is never narrowed, whatever the list holds — and it
     * also keeps `isNeedCachedBroadcast` safe, the one call site that cannot
     * check the caller and whose callee is sometimes GMS itself.
     *
     * 2026-10-02: replaced the former `shouldApply` + `shouldWake` pair.
     * 2026-10-09: the `tier` argument and the old strict-mode escape are gone —
     * the allowlist now narrows every gate alike, empty list included.
     */
    internal fun moduleAppliesTo(
        module: XposedInterface,
        systemContext: () -> Context?,
        packageName: String?
    ): Boolean {
        if (HookerSupport.GMS_PACKAGE_NAME == packageName || HookerSupport.GMS_PERSISTENT_PROCESS_NAME == packageName) {
            return true
        }
        return allowlistContains(module, systemContext, packageName)
    }

    /**
     * @param reportFailure log the throwable when this attempt fails. The boot
     *   retry loop passes `false` for every attempt but the last, because an
     *   early-boot failure is expected and self-healing: reporting it would put a
     *   stack trace in every boot log and consume the once-per-boot guard before
     *   a genuine failure could use it.
     */
    private fun registerAllowlistReceiver(
        module: XposedInterface,
        systemContext: () -> Context?,
        reportFailure: Boolean = true
    ): Boolean {
        if (allowlistReceiverRegistered) {
            return true
        }
        if (!allowlistRegistering.compareAndSet(false, true)) {
            return allowlistReceiverRegistered
        }
        try {
            val sys = systemContext() ?: return false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // This receiver *has* to be RECEIVER_EXPORTED: it lives on
                    // the system_server context and the only legitimate sender —
                    // the settings app — is a different uid, so a non-exported
                    // registration would never receive anything. Being exported
                    // with no broadcast permission also means any app on the
                    // device can reach these two actions, so the sender is
                    // checked in the callback instead of at registration time.
                    val fromPackage = try {
                        sentFromPackage
                    } catch (t: Throwable) {
                        null
                    }
                    val fromUid = try {
                        sentFromUid
                    } catch (t: Throwable) {
                        Process.INVALID_UID
                    }
                    if (allowlistSenderIsForeign(fromPackage, fromUid)) {
                        logForeignAllowlistSenderOnce(module, fromPackage, fromUid)
                        return
                    }
                    when (intent.action) {
                        Prefs.ACTION_ALLOWLIST_CHANGED -> requestAllowlistReload(module)
                    }
                }
            }
            val filter = IntentFilter(Prefs.ACTION_ALLOWLIST_CHANGED)
            val handler = allowlistBackgroundHandler()
            sys.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
            // Package events feed the auto-check scan cache, on a receiver of
            // their own — deliberately NOT an action on the receiver above.
            // That one rejects every sender it cannot positively identify as
            // this module, and a system broadcast's sender is the platform
            // ("android"), which would be rejected as foreign. These two
            // actions are protected broadcasts: only the system can send them,
            // so there is nothing for a sender check to guard against.
            val packageReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_PACKAGE_ADDED,
                        Intent.ACTION_PACKAGE_REPLACED -> scheduleAutoScan(module, systemContext)
                    }
                }
            }
            val packageFilter = IntentFilter(Intent.ACTION_PACKAGE_ADDED)
            packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
            sys.registerReceiver(
                packageReceiver, packageFilter, null, handler, Context.RECEIVER_EXPORTED
            )
            allowlistReceiverRegistered = true
            module.logGated(Log.INFO, HookerSupport.TAG, "Allowlist receiver installed")
            return true
        } catch (e: Throwable) {
            // The boot retry loop reports only its final attempt, so it stays
            // silent here. The lazy path — this method called straight from
            // getFcmAllowlist — reports, because before there was any logging at
            // all a failure there left the allowlist quietly stale with nothing
            // in the log to say why.
            if (reportFailure) {
                logAllowlistRegisterFailureOnce(module, e)
            }
            return false
        } finally {
            allowlistRegistering.set(false)
        }
    }

    /**
     * Whether a delivery was positively sent by somebody other than this module's
     * own app.
     *
     * Registration cannot express this: a required broadcast permission would have
     * to be granted to the settings app, and a permission that is ever missing
     * would take the whole allowlist path down silently — a worse failure than the
     * one it guards against. So the identity the system already stamps on every
     * delivery is read back instead ([BroadcastReceiver.getSentFromPackage] /
     * [BroadcastReceiver.getSentFromUid], API 34+, and minSdk is 35).
     *
     * Fail-open by construction: only a sender that is *positively* identified as
     * different is rejected. Anything unstamped (null package and INVALID_UID) is
     * processed exactly as it was before this check existed, so it can never
     * narrow the legitimate path — the worst case is that it filters nothing.
     *
     * `internal` so the unit tests can pin the sender matrix down; this is the
     * gate that keeps foreign packages from rewriting the allowlist.
     */
    internal fun allowlistSenderIsForeign(fromPackage: String?, fromUid: Int): Boolean {
        if (fromPackage != null) {
            return fromPackage != Prefs.MODULE_PKG
        }
        if (fromUid == Process.INVALID_UID) {
            return false
        }
        // Unknown package but a known uid: system / root / shell are the only
        // uids besides the module's own that legitimately drive this (adb
        // `am broadcast` debugging).
        return fromUid != Process.SYSTEM_UID &&
            fromUid != Process.ROOT_UID &&
            fromUid != Process.SHELL_UID
    }

    @Volatile
    private var foreignAllowlistSenderLogged = false

    /** Once per boot: a rejected sender is worth seeing, a flood of them is not. */
    private fun logForeignAllowlistSenderOnce(module: XposedInterface, fromPackage: String?, fromUid: Int) {
        if (foreignAllowlistSenderLogged) {
            return
        }
        foreignAllowlistSenderLogged = true
        module.logGated(
            Log.WARN, HookerSupport.TAG,
            "Ignored allowlist broadcast from " +
                (fromPackage ?: "uid $fromUid") +
                "; only " + Prefs.MODULE_PKG + " may drive the allowlist"
        )
    }

    @Volatile
    private var allowlistRegisterFailureLogged = false

    /**
     * Once per boot. Callers are the lazy path and the boot loop's final attempt.
     * The guard matters because the lazy path runs inside a hook callback
     * (getFcmAllowlist is on the hot path), which would otherwise repeat the same
     * stack trace on every wake.
     */
    private fun logAllowlistRegisterFailureOnce(module: XposedInterface, t: Throwable) {
        if (allowlistRegisterFailureLogged) {
            return
        }
        allowlistRegisterFailureLogged = true
        module.logGated(Log.WARN, HookerSupport.TAG, "Allowlist receiver registration failed", t)
    }
}
