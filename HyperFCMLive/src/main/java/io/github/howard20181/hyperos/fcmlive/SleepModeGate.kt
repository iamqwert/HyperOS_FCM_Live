package io.github.howard20181.hyperos.fcmlive

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field

/**
 * The sleep-mode network subsystem, extracted verbatim from `Hooker`
 * (zero-behaviour refactor). `Hooker`'s instance capabilities are injected at
 * the call sites: `module` is the [XposedInterface] the module entry object is
 * (`log` / `deoptimize` / `getRemotePreferences`), `hookE` is `Hooker#hookE`
 * (the counted, `setId`-deduplicated hook builder — its `hooksInstalled`
 * counter feeds the end-of-install summary that stays with `Hooker`),
 * `logSkip` / `logSkipOtherGeneration` are the two absent-target reports (the
 * counters and the merged cross-generation line live with `Hooker`), and
 * `getSystemContext` / `gmsUid` / `broadcastGateSummary` /
 * `recoverGmsConnection` are the suppliers of the sleep-exit P4 recovery
 * nudge — their lazy resolution and state stay with `Hooker`.
 */
internal object SleepModeGate {

    /**
     * True when GMS was successfully added to the sleep-mode network whitelist
     * during the current sleep session.
     *
     * When set, sleep exit skips the P4 recovery nudge: GMS stayed online all
     * night, so its MCS connection is healthy by construction and the recovery
     * broadcasts would only tear it down. Stays false when the whitelist path
     * failed (old ROM fallback / uid unresolved / rules never ran), which is
     * exactly the case the nudge was written for.
     *
     * The step from "the uid rule was applied" to "the link stayed up" is a
     * generation assumption, not a measurement — see the exit branch in
     * [armSleepModeChain] and HOOKS_AND_DIAGNOSTICS.md §3.8.6.
     */
    @Volatile
    private var sGmsKeptOnSleepWhitelist = false

    /**
     * One-shot sentinel: the ROM actually ran the legacy per-uid sleep-mode
     * whitelist path ([armSleepModeWhitelist]).
     *
     * On OS4/V816 this must never fire — sleep mode there cuts WiFi and mobile
     * data in PowerKeeper and never filters per uid. A `legacy path FIRED`
     * line therefore means the device has moved away from that reading (new
     * OTA, or an OS3-generation ROM), and conclusion has to be rebuilt from
     * the log rather than from the source comments. One-shot: the path may run
     * on every sleep entry for years, and the finding does not change.
     */
    @Volatile
    private var sSleepWhitelistPathFired = false

    /** One-shot sentinel for [armSleepModeChain]; same job as its sibling above. */
    @Volatile
    private var sSleepChainPathFired = false

    /**
     * The legacy MIUI sleep-mode network chain, in system_server's
     * `MiuiNetworkPolicyManagerService`.
     *
     * **What it was written for.** Older ROMs opened a per-uid network chain
     * at sleep entry and let through only `sleep_mode_network_white_apps`;
     * GMS was not on that list, so the FCM socket was cut silently and only
     * came back on the next heartbeat. The receiver's order at entry is:
     *
     *   setSleepModeWhitelistUidRules()   // pushes added=true for every uid in mSleepModeWhitelistUids
     *   enableSleepModeChain(true)        // opens the chain
     *
     * so injecting GMS into the set before the rules are pushed was enough,
     * and exit revokes symmetrically via clearSleepModeWhitelistUidRules().
     *
     * **Why it is only a sentinel now (2026-10-02).** On OS4/V816 this path
     * never runs: sleep mode does not filter per uid at all — PowerKeeper's
     * `PhoneSleepModeController#applySleepConfig` switches WiFi and mobile data
     * off outright (measured 01:38:00→07:08:57, GCM `net=-1`). Reading the
     * armed hook as protection is what once made an all-night cutoff look like
     * a working setup, so the install-time line says so outright and the real
     * keepalive lives in [hookSleepModeNetworkKeepalive].
     *
     * **Sentinels.** Both arms report the first time the ROM actually runs
     * them (`sleep-mode sentinel: legacy path FIRED — …`). On V816 neither
     * line should ever appear; if one does, the ROM generation changed, the
     * "cuts in PowerKeeper" reading no longer covers the device, and the
     * conclusion has to be rebuilt from that log rather than from this file.
     * The two arms are armed **independently** for the same reason: they back
     * two different claims, and until 2026-10-03 a missing whitelist method
     * returned early, which also skipped the chain hook — on such a ROM the
     * sleep-exit reconnect would have run unhooked and unreported.
     */
    internal fun hookSleepModeNetworkPolicy(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit,
        getSystemContext: () -> Context?,
        gmsUid: () -> Int?,
        broadcastGateSummary: () -> String,
        recoverGmsConnection: (Context) -> Unit
    ) {
        val serviceClass = try {
            classLoader.loadClass("com.android.server.net.MiuiNetworkPolicyManagerService")
        } catch (e: ClassNotFoundException) {
            logSkip("MiuiNetworkPolicyManagerService class absent, skip")
            return
        }
        val whitelistField = try {
            serviceClass.getDeclaredField("mSleepModeWhitelistUids")
                .also { it.isAccessible = true }
        } catch (e: NoSuchFieldException) {
            logSkipOtherGeneration(
                "MiuiNetworkPolicyManagerService.mSleepModeWhitelistUids (sleep-whitelist arm)"
            )
            null
        }
        val whitelistArmed = armSleepModeWhitelist(
            module, serviceClass, whitelistField, hookE, logSkipOtherGeneration, gmsUid
        )
        val chainArmed = armSleepModeChain(
            module, serviceClass, whitelistField, hookE, logSkipOtherGeneration,
            getSystemContext, gmsUid, broadcastGateSummary, recoverGmsConnection
        )
        // One install-time line for both arms, so "no sentinel fired" is never
        // the only evidence: the log states which arms exist on this ROM and
        // what a FIRED line would mean.
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "Sleep-mode legacy per-uid chain armed: whitelist=$whitelistArmed, " +
                "chain=$chainArmed (sentinel: V816 cuts the network in PowerKeeper, " +
                "not here; a later 'sleep-mode sentinel: legacy path FIRED' line " +
                "means this ROM does run it and the assumption needs re-checking)"
        )
    }

    /**
     * Arm 1: inject GMS into `mSleepModeWhitelistUids` before the rules are
     * pushed to ConnectivityManager.
     *
     * @return true when the injection hook is installed.
     */
    private fun armSleepModeWhitelist(
        module: XposedInterface,
        serviceClass: Class<*>,
        whitelistField: Field?,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkipOtherGeneration: (String) -> Unit,
        gmsUid: () -> Int?
    ): Boolean {
        if (whitelistField == null) return false
        val applyMethod = try {
            serviceClass.getDeclaredMethod("setSleepModeWhitelistUidRules")
        } catch (e: NoSuchMethodException) {
            logSkipOtherGeneration(
                "MiuiNetworkPolicyManagerService#setSleepModeWhitelistUidRules " +
                    "(sleep-whitelist arm)"
            )
            return false
        }
        applyMethod.isAccessible = true
        hookE(applyMethod).intercept { chain: XposedInterface.Chain ->
            if (!sSleepWhitelistPathFired) {
                sSleepWhitelistPathFired = true
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "sleep-mode sentinel: legacy path FIRED — " +
                        "#setSleepModeWhitelistUidRules ran on this ROM, so this " +
                        "device no longer matches the V816 'sleep cuts the network " +
                        "in PowerKeeper, not here' reading"
                )
            }
            try {
                addGmsToSleepModeWhitelist(module, whitelistField, chain.thisObject, gmsUid)
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to extend sleep-mode network whitelist", t)
            }
            chain.proceed()
        }
        module.deoptimize(applyMethod)
        return true
    }

    /**
     * Arm 2: the chain toggle. Entering records the whitelist size; leaving
     * carries the P4 recovery nudge, whose gate is described at the call site.
     *
     * @return true when the chain hook is installed.
     */
    private fun armSleepModeChain(
        module: XposedInterface,
        serviceClass: Class<*>,
        whitelistField: Field?,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkipOtherGeneration: (String) -> Unit,
        getSystemContext: () -> Context?,
        gmsUid: () -> Int?,
        broadcastGateSummary: () -> String,
        recoverGmsConnection: (Context) -> Unit
    ): Boolean {
        val chainMethod = try {
            serviceClass.getDeclaredMethod(
                "enableSleepModeChain", Boolean::class.javaPrimitiveType
            )
        } catch (e: NoSuchMethodException) {
            logSkipOtherGeneration(
                "MiuiNetworkPolicyManagerService#enableSleepModeChain"
            )
            return false
        }
        chainMethod.isAccessible = true
        hookE(chainMethod).intercept { chain: XposedInterface.Chain ->
            val enabling = chain.getArg(0) == true
            if (enabling) {
                // Entering sleep used to be silent, which made "the callback never
                // ran" and "it ran but the whitelist was empty" look identical in
                // the logs. Record it, plus the size we are about to iterate over.
                if (!sSleepChainPathFired) {
                    sSleepChainPathFired = true
                    module.logGated(
                        Log.INFO, HookerSupport.TAG,
                        "sleep-mode sentinel: legacy path FIRED — " +
                            "#enableSleepModeChain ran on this ROM; sleep enters " +
                            "and exits through the per-uid chain here, not through " +
                            "PowerKeeper's radio cutoff"
                    )
                }
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "Sleep mode entering: chain enabled, whitelist size " +
                        sleepModeWhitelistSize(whitelistField, chain.thisObject)
                )
            }
            chain.proceed()
            if (!enabling) {
                if (sGmsKeptOnSleepWhitelist) {
                    // GMS was on the sleep whitelist, so it stayed online and its
                    // MCS connection is healthy. The recovery broadcasts make GMS
                    // drop its current MCS — sending them here would tear down the
                    // very connection the whitelist protected all night.
                    //
                    // The flag only proves the *uid rule* was applied, and the
                    // step from there to "the link stayed up" is a generation
                    // assumption: true on V816 (where this whole path is dead
                    // anyway), but a ROM that both whitelists uids and cuts the
                    // radios in PowerKeeper would satisfy the flag while the
                    // link was down. The line names the assumption rather than
                    // leaving it to be re-derived; the cross-generation gap is
                    // tracked in HOOKS_AND_DIAGNOSTICS.md §3.8.6.
                    module.logGated(
                        Log.INFO, HookerSupport.TAG,
                        "Sleep mode exited: GMS was kept on the whitelist, " +
                            "skipping recovery nudge (MCS untouched; assumes the " +
                            "whitelist path means no radio was cut — on a ROM that " +
                            "also cuts them in PowerKeeper this skip is wrong)"
                    )
                } else {
                    module.logGated(
                        Log.INFO, HookerSupport.TAG,
                        "Sleep mode exited: GMS not whitelisted (network was cut), " +
                            "nudging GMS to reconnect"
                    )
                    val context = getSystemContext()
                    if (context != null) {
                        // Sample before the nudge: those broadcasts make GMS drop its
                        // current MCS connection, so the pre-nudge state is what tells
                        // us whether the nudge broke a connection that was still alive.
                        TrafficProbe.probeGmsTraffic(
                            module, gmsUid, broadcastGateSummary, "before sleep-exit nudge"
                        )
                        Thread { recoverGmsConnection(context) }.start()
                        TrafficProbe.probeBackgroundHandler().postDelayed(
                            {
                                TrafficProbe.probeGmsTraffic(
                                    module, gmsUid, broadcastGateSummary,
                                    "after sleep-exit nudge"
                                )
                            },
                            HookerSupport.GMS_TRAFFIC_NUDGE_RESAMPLE_MS
                        )
                    }
                }
                // Next session starts from a clean slate: the flag must reflect
                // what happens in *that* session, not a stale previous one.
                sGmsKeptOnSleepWhitelist = false
            }
        }
        module.deoptimize(chainMethod)
        return true
    }

    /**
     * Adds GMS to the "keep network during sleep mode" set. Called on the
     * service's own handler thread, right before the rules are pushed to
     * ConnectivityManager, so no other thread observes a half-updated set.
     */
    private fun addGmsToSleepModeWhitelist(
        module: XposedInterface,
        whitelistField: Field,
        owner: Any,
        gmsUid: () -> Int?
    ) {
        val raw = whitelistField.get(owner)
        if (raw !is MutableCollection<*>) {
            sGmsKeptOnSleepWhitelist = false
            module.logGated(
                Log.WARN, HookerSupport.TAG,
                "Sleep mode entering: whitelist field is ${raw?.javaClass?.name ?: "null"}, " +
                    "not a mutable collection, GMS not added"
            )
            return
        }
        @Suppress("UNCHECKED_CAST")
        val whitelist = raw as MutableCollection<Any?>
        val uid = gmsUid()
        if (uid == null) {
            sGmsKeptOnSleepWhitelist = false
            module.logGated(
                Log.WARN, HookerSupport.TAG,
                "Sleep mode entering: GMS uid unresolved, GMS not added"
            )
            return
        }
        if (whitelist.add(uid)) {
            sGmsKeptOnSleepWhitelist = true
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "Sleep mode entering: kept GMS (uid $uid) on the network whitelist"
            )
        } else {
            // Already present: either the ROM populated the set itself (which the
            // static analysis says it never does) or a previous pass left it there.
            sGmsKeptOnSleepWhitelist = true
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "Sleep mode entering: GMS (uid $uid) already whitelisted, size ${whitelist.size}"
            )
        }
    }

    /**
     * Read-only size of the sleep-mode whitelist, for diagnostics only.
     * Never throws: this runs inside a hook callback in system_server.
     *
     * Takes a nullable field because the chain arm is armed even when the
     * whitelist field is missing (the two arms are independent), and the size
     * is worth reporting either way — "field absent" is a different finding
     * from "present but empty".
     */
    private fun sleepModeWhitelistSize(whitelistField: Field?, owner: Any?): String {
        if (whitelistField == null) return "<field absent>"
        return try {
            val raw = whitelistField.get(owner)
            if (raw is Collection<*>) raw.size.toString() else "<not a collection>"
        } catch (t: Throwable) {
            "<unreadable>"
        }
    }

    /**
     * Sleep-mode network keepalive, hooked in the PowerKeeper process.
     *
     * OS4/V816 does **not** filter sleep-mode network access per uid.
     * `PhoneSleepModeController#applySleepConfig` turns WiFi and mobile data
     * off outright, so no per-app whitelist can preserve the FCM channel —
     * measured on-device 01:38:00→07:08:57 with GCM reporting `net=-1`,
     * i.e. no network reachable at all, cellular included.
     *
     * Both switches are flipped directly inside `applySleepConfig`:
     *   CommonAdapter.setDataEnabled(TelephonyManager, false)   (static)
     *   WifiManager.setWifiEnabled(false)
     * so those two are hooked here rather than anything in system_server.
     *
     * Only calls whose stack carries `PhoneSleepModeController
     * #applySleepConfig` are touched: a user switching WiFi off by hand has
     * no such frame and still works.
     *
     * ## Why the cutoff calls are stopped here instead of skipped upstream
     *
     * `applySleepConfig` jumps over the whole cutoff block when
     * `key_open_earthquake_warning` reads 1, and an earlier revision answered
     * that read with 1 — the vendor's own "must stay reachable" escape hatch.
     * That shortcut is all-or-nothing: it skips `setDataEnabled(false)` and
     * `setWifiEnabled(false)` **and, with them, the
     * `SleepState.setPreviousEnable`/`writeToDb` calls that record which
     * radios were on before sleep**. Skipping the record is harmless while
     * neither radio is ever cut, but it makes "keep WiFi, let mobile data go"
     * impossible to express — and that is the entire point of the data
     * sub-switch. Worse, `restoreSleepConfig` reads those same bits back to
     * decide what to turn on again, so a radio cut without a recorded
     * previous-enable would stay off after sleep with nothing to restore it.
     *
     * So the ROM now runs its full path and the cutoff calls are stopped here
     * instead. `SleepState` is a bitmask (1 = data, 2 = WiFi, 4 = WiFi AP,
     * 16 = keyguard notification, 32 = FOD, 128 = pickup) and
     * `setPreviousEnable(ch, true)` also sets the matching `restore` bit, so
     * every radio that was on before sleep is guaranteed to be restored. The
     * one visible change is that the keyguard-notification leg of that block
     * now runs as the ROM intended, instead of being skipped along with the
     * radios. That leg is not cosmetic: at `29e42e` the ROM reads
     * `Settings.System "wakeup_for_keyguard_notification"` (default -1),
     * stores it in `SleepState.previousNotification`, sets the restore bit 16
     * when it was on, and then `putInt(..., 0)` — i.e. sleep turns off "light
     * the screen when a lock-screen notification arrives", and
     * `restoreSleepConfig` (`29feb2`) writes the previous value back on exit.
     * Letting it run means notifications do not wake the screen overnight;
     * skipping it (what the flag shortcut did) leaves that on, so every
     * overnight push lights the screen. It is the ROM's own power saving and
     * this module has no opinion about it either way — it runs because the
     * block runs, not because we chose it.
     *
     * Policy — each radio follows its own switch: WiFi under
     * `Prefs.KEY_SLEEP_KEEPALIVE`, mobile data under
     * `Prefs.KEY_SLEEP_KEEPALIVE_DATA`. Both keys are read lazily at each cutoff
     * call, so a flip lands on the next sleep entry without a reboot, and
     * neither key gates the other — a night that keeps exactly one radio up is
     * a configuration the user can ask for, which is why the experiment screen
     * draws the two switches as peers rather than as a master and a sub. Who
     * may actually use the restored network is still decided by Doze's per-uid
     * chain, which this hook leaves alone.
     *
     * Two limits are stated here rather than left to be discovered:
     *  - On the degraded path ([hookSleepModeEarthquakeFlag]) neither cutoff
     *    interceptor runs at all — that branch skips the whole cutoff block.
     *    It is only reachable when one of the two cutoff methods cannot be
     *    hooked, and the install-time log says so outright.
     *  - Both interceptors require the stack to carry
     *    [SLEEP_CONTROLLER_CLASS]`#applySleepConfig` / `#restoreSleepConfig`
     *    ([calledFromSleepApply] / [calledFromSleepConfig]), and that class is
     *    the OS4/V816 home of sleep mode. On a ROM whose sleep mode lives
     *    elsewhere — OS3 goes through system_server's per-uid chain, which is
     *    what the legacy arms below exist for — the predicate can never match,
     *    so this switch intercepts nothing and the radios are cut exactly as if
     *    it were off. That is a **silent no-op, not a failure**: both hooks are
     *    installed and the install log says so, so never read a healthy-looking
     *    log as "the switch is doing something". Which side of the fork a
     *    device is on comes from the `Sleep-mode legacy per-uid chain armed: …`
     *    line and the two `legacy path FIRED` sentinels, not from this switch.
     */
    internal fun hookSleepModeNetworkKeepalive(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit
    ) {
        val wifiMethod = try {
            classLoader.loadClass("android.net.wifi.WifiManager")
                .getDeclaredMethod("setWifiEnabled", Boolean::class.javaPrimitiveType)
        } catch (t: Throwable) {
            logSkip("WifiManager#setWifiEnabled absent, sleep-keepalive WiFi skip")
            null
        }
        val dataMethod = try {
            classLoader.loadClass("com.miui.powerkeeper.utils.CommonAdapter")
                .getDeclaredMethod(
                    "setDataEnabled",
                    TelephonyManager::class.java,
                    Boolean::class.javaPrimitiveType
                )
        } catch (t: Throwable) {
            logSkip("CommonAdapter#setDataEnabled absent, sleep-keepalive data skip")
            null
        }

        if (wifiMethod == null || dataMethod == null) {
            // Degrade rather than half-promise. Cutting a radio that
            // SleepState never recorded would leave restoreSleepConfig with
            // nothing to turn back on, so a hook that cannot stop both calls
            // must not stop either: take the vendor's own no-cutoff branch
            // instead, which keeps both radios up for the whole night.
            hookSleepModeEarthquakeFlag(module, classLoader, hookE, logSkip)
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "Sleep-mode network keepalive: cutoff hooks incomplete, degraded " +
                    "to PowerKeeper's own no-cutoff branch (both radios stay up " +
                    "for the whole night)"
            )
            return
        }

        wifiMethod.isAccessible = true
        hookE(wifiMethod).intercept { chain: XposedInterface.Chain ->
            val enabling = chain.getArg(0) == true
            if (enabling || !calledFromSleepApply()) {
                return@intercept chain.proceed()
            }
            // One line per sleep entry either way, so the night's log states
            // which policy ran instead of leaving it to be inferred.
            if (!isSleepKeepaliveEnabled(module)) {
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "sleep-mode: WiFi left to the ROM policy (keepalive master switch off)"
                )
                return@intercept chain.proceed()
            }
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "sleep-mode: kept WiFi on (sleep would have turned it off)"
            )
            // Report success so the caller does not retry or log a failure.
            true
        }
        module.deoptimize(wifiMethod)

        dataMethod.isAccessible = true
        hookE(dataMethod).intercept { chain: XposedInterface.Chain ->
            val enabling = chain.getArg(1) == true
            if (enabling || !calledFromSleepApply()) {
                return@intercept chain.proceed()
            }
            // One line per sleep entry either way, so the night's log states
            // which policy ran instead of leaving it to be inferred. This used
            // to name two reasons — the WiFi switch off, or this one — because
            // the data switch was gated by the WiFi one; the two are peers now,
            // so there is exactly one way this hook declines.
            if (!isSleepKeepaliveDataEnabled(module)) {
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "sleep-mode: mobile data left to the ROM policy " +
                        "(keepalive data switch off)"
                )
                return@intercept chain.proceed()
            }
            // No pairing rule with the WiFi hook: each radio follows its own
            // switch, and a night where only one of them is kept up is a
            // configuration the user can ask for.
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "sleep-mode: kept mobile data on (keepalive data switch is on)"
            )
            null
        }
        module.deoptimize(dataMethod)

        // Say plainly that nothing happens yet: both switches are off by
        // default, and a hooked-but-idle hook must not read as a working one.
        // That misread already cost a night of diagnosis.
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "Sleep-mode network keepalive hooked: idle until an experiment " +
                "switch is on, then each radio stays up under its own switch " +
                "(WiFi / mobile data); the ROM runs its full cutoff path so " +
                "SleepState still records what to restore."
        )
    }

    /** True when the current call stack is a sleep-mode entry applying its config. */
    private fun calledFromSleepApply(): Boolean {
        for (frame in Thread.currentThread().stackTrace) {
            if (HookerSupport.SLEEP_CONTROLLER_CLASS == frame.className &&
                HookerSupport.SLEEP_APPLY_METHOD == frame.methodName
            ) {
                return true
            }
        }
        return false
    }

    /** True when the stack is either sleep entry or exit; both must see the same flag. */
    private fun calledFromSleepConfig(): Boolean {
        for (frame in Thread.currentThread().stackTrace) {
            if (HookerSupport.SLEEP_CONTROLLER_CLASS == frame.className &&
                (HookerSupport.SLEEP_APPLY_METHOD == frame.methodName ||
                    HookerSupport.SLEEP_RESTORE_METHOD == frame.methodName)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Makes sleep mode take its own "must stay reachable" path — the **degrade**
     * path, armed only when [hookSleepModeNetworkKeepalive] cannot hook both
     * cutoff calls.
     *
     * `applySleepConfig` reads `key_open_earthquake_warning` and, when it is 1,
     * skips the entire block that calls `setDataEnabled(false)` and
     * `setWifiEnabled(false)` — along with the `SleepState` bookkeeping inside
     * it. That is exactly why this is no longer the normal path: it can keep
     * both radios up, but it cannot keep **one** of them up and let the other
     * go. As a fallback the gap does not matter — the only choice left there is
     * between "both radios up" and "network gone, with nothing the user can do
     * about it" — so it arms when **either** sleep switch is on and says so.
     * (That is also why the experiment screen's two switches, which are peers
     * here, cannot both be honoured on this path.)
     *
     * This answers the flag read and nothing else: the setting itself is
     * untouched, so no earthquake feature is turned on and no other reader of
     * the key sees a different value.
     */
    private fun hookSleepModeEarthquakeFlag(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit
    ) {
        val getIntForUser = try {
            classLoader.loadClass("android.provider.Settings\$Secure")
                .getDeclaredMethod(
                    "getIntForUser",
                    ContentResolver::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
        } catch (t: Throwable) {
            logSkip("Settings\$Secure#getIntForUser absent, sleep-keepalive flag skip")
            null
        }
        if (getIntForUser == null) return
        getIntForUser.isAccessible = true
        hookE(getIntForUser).intercept { chain: XposedInterface.Chain ->
            // Cheap rejection first: this runs for every Secure int read made
            // by PowerKeeper, so the stack walk must stay off the fast path.
            if (HookerSupport.SLEEP_EARTHQUAKE_KEY != chain.getArg(1)) {
                return@intercept chain.proceed()
            }
            if ((!isSleepKeepaliveEnabled(module) && !isSleepKeepaliveDataEnabled(module)) ||
                !calledFromSleepConfig()
            ) {
                return@intercept chain.proceed()
            }
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "sleep-mode: reported life-safety reachability on, " +
                    "PowerKeeper will skip the network cutoff"
            )
            1
        }
        module.deoptimize(getIntForUser)
    }

    /**
     * Keepalive switch, read lazily like the WeChat keepout's.
     *
     * Fails to *disabled*: this is an opt-in experiment
     * that overrides a power-saving decision the user asked for, so an
     * unreadable switch must leave sleep mode alone rather than quietly
     * keeping both radios up all night.
     */
    private fun isSleepKeepaliveEnabled(module: XposedInterface): Boolean {
        return try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
                .also { HookLog.refresh(it) }
                .getBoolean(Prefs.KEY_SLEEP_KEEPALIVE, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * Mobile-data switch, read lazily like its WiFi sibling.
     *
     * The two sleep switches are **peers, not a master and a sub**: each radio
     * follows its own key, and a night that keeps exactly one of them up is a
     * configuration the user can ask for. This one used to AND the WiFi key,
     * back when "keep WiFi, let mobile data go" was the only shape the pair
     * offered; hiding it on screen behind the other would now claim a hierarchy
     * the hook does not have.
     *
     * Fails to *disabled* for the same reason as the WeChat pair: an
     * unreadable switch must mean "leave the system's own data policy alone",
     * never "hold cellular open all night".
     */
    private fun isSleepKeepaliveDataEnabled(module: XposedInterface): Boolean {
        return try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
                .also { HookLog.refresh(it) }
                .getBoolean(Prefs.KEY_SLEEP_KEEPALIVE_DATA, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * doze-wl-sentinel observability. The hook itself only preserves the
     * status quo (GMS is already in the doze whitelist on this device), so
     * without these two signals the hook would be unverifiable: "reached"
     * proves the hook is alive on every boot (PowerKeeperAppConfigure
     * assembly always calls through), the inject counter proves the
     * injection path works when the cloud ever drops GMS.
     */
    @Volatile
    private var sDozeSentinelReachedLogged = false

    @Volatile
    private var sDozeSentinelInjectCount = 0

    internal fun hookGlobalFeatureConfigureHelper(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit
    ) {
        try {
            val GlobalFeatureConfigureHelperClass = classLoader.loadClass(
                "com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper"
            )
            for (argType in arrayOf(Bundle::class.java, Context::class.java)) {
                try {
                    val getDozeWhiteListAppsMethod =
                        GlobalFeatureConfigureHelperClass.getDeclaredMethod(
                            "getDozeWhiteListApps", argType
                        )
                    hookE(getDozeWhiteListAppsMethod).intercept { chain: XposedInterface.Chain ->
                        val result = chain.proceed()
                        try {
                            if (result is List<*>) {
                                val hasGms = result.contains(HookerSupport.GMS_PACKAGE_NAME)
                                if (!sDozeSentinelReachedLogged) {
                                    sDozeSentinelReachedLogged = true
                                    module.logGated(
                                        Log.INFO, HookerSupport.TAG,
                                        "doze-wl-sentinel: reached (arg=${argType.simpleName}), " +
                                            "size=${result.size}, hasGms=$hasGms"
                                    )
                                }
                                if (!hasGms) {
                                    sDozeSentinelInjectCount++
                                    if (sDozeSentinelInjectCount == 1 ||
                                        sDozeSentinelInjectCount % 10 == 0
                                    ) {
                                        module.logGated(
                                            Log.INFO, HookerSupport.TAG,
                                            "doze-wl-sentinel: GMS missing from doze whitelist, " +
                                                "injected #$sDozeSentinelInjectCount (size ${result.size})"
                                        )
                                    }
                                    val source = result
                                    val whiteList = ArrayList<Any?>(source)
                                    whiteList.add(HookerSupport.GMS_PACKAGE_NAME)
                                    HookerSupport.addIfAbsentInPlace(source, HookerSupport.GMS_PACKAGE_NAME)
                                    return@intercept whiteList
                                }
                            }
                        } catch (t: Throwable) {
                            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to extend doze white list", t)
                        }
                        result
                    }
                } catch (e: NoSuchMethodException) {
                    logSkip(
                        "GlobalFeatureConfigureHelper#getDozeWhiteListApps(" +
                            argType.simpleName + ") absent, skip"
                    )
                }
            }
        } catch (e: ClassNotFoundException) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to hook GlobalFeatureConfigureHelper", e)
        }
    }
}
