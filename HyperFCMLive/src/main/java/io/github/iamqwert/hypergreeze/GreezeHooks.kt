package io.github.iamqwert.hypergreeze

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The greeze freeze / broadcast-policy subsystem, extracted verbatim from
 * `Hooker` (zero-behaviour refactor). Everything the ROM's
 * `com.miui.server.greeze.*` surface touches lives here:
 * [hookGreezeManagerService], [hookDomesticPolicyManager] (with the
 * `isRestrictNet` defence), [hookUdpPackageRestrict], [hookListAppsManager],
 * [hookBroadcastQueueModernStubImpl] (with the warm-receiver gate),
 * [hookProcessPolicy] and [hookAwareResourceControl].
 *
 * `Hooker`'s instance capabilities are injected at the call sites, the same way
 * [GmsSupport] receives them: `module` is the
 * [XposedInterface] the module entry object is (`log` / `deoptimize` /
 * `getInvoker`), `hookE` is `Hooker#hookE` (the counted, `setId`-deduplicated
 * hook builder — its `hooksInstalled` counter feeds the end-of-install summary
 * that stays with `Hooker`), `logSkip` is the absent-target report (the counter
 * stays with `Hooker`), `findMethod` is the null-returning lookup helper,
 * `isGmsUid` / `gmsUid` are the shared GMS uid gates (cachedGmsAppId stays with
 * `Hooker` — several subsystems consult them), `forceBooleanArg` is `Hooker`'s
 * shared boolean-arg rewriter (it routes its own misses through
 * `logSkip` / `logSkipOtherGeneration`), `netReleaseEnabled` /
 * `allowlistPackageForUid` read the `greeze_net_release` experiment off
 * `Hooker`'s allowlist state, and `systemContext` feeds
 * [AllowlistGate.moduleAppliesTo].
 *
 * Observability policy (2026-10-30): this file carries **one-shot** lines only.
 * A hook that fired prints once, per process; silence means the branch was
 * never taken. The per-tick counters and the 30-min broadcast-gate summary were
 * removed with the probe layer — nothing consumed them, and the counters made
 * every hook look like an observation post instead of a fix.
 */
internal object GreezeHooks {

    /** One-shot: first c2dm allow decided by the isRestrictReceiver gate. */
    @Volatile
    private var broadcastGateAllowedLogged = false

    /** One-shot: first allow answered by the checkApplicationAutoStart hook itself. */
    @Volatile
    private var broadcastGateAutostartAllowedLogged = false

    /** One-shot: first c2dm deliberately left to the ROM policy (P0 behaviour). */
    @Volatile
    private var broadcastGateSkippedLogged = false

    /** Uids whose first net-release already produced a detail line. */
    private val greezeNetReleaseLoggedUids = HashSet<Int>()

    /** One-shot: confirms P0 #1 actually suppressed a network restriction for GMS. */
    @Volatile
    private var gmsRestrictNetLogged = false

    /** One-shot: confirms 3.6 actually kept the UDP packet filter off GMS. */
    @Volatile
    private var gmsUdpFilterLogged = false

    /** One-shot: confirms the ListAppsManager use-data whitelist add actually ran. */
    @Volatile
    private var listAppsUseDataAddLogged = false

    @Volatile
    private var noNetworkBlacklistMismatchLogged = false

    /** One-shot: the frozen-process wake-lock gate answered "allowed" for GMS. */
    @Volatile
    private var gmsWakeUpListAllowedLogged = false

    /** One-shot: confirms triggerGMSLimitAction was suppressed before pruning GMS allowlist. */
    @Volatile
    private var gmsLimitActionSuppressedLogged = false

    /** One-shot: confirms post-c2dm tryDelayFreeze extended the anti-refreeze grace window. */
    @Volatile
    private var c2dmFreezeDelayLogged = false

    /** One-shot: confirms isJobRestrict allowed a post-c2dm job within the 10s wake window. */
    @Volatile
    private var c2dmJobAllowedLogged = false

    /**
     * Per-uid deadline (`SystemClock.uptimeMillis()`) for the short post-c2dm
     * wake window during which `GreezeManagerService#isJobRestrict(uid)` is
     * relaxed for allowlisted FCM apps (aligned with [HookerSupport.C2DM_FREEZE_DELAY_MS]).
     */
    private val c2dmWakeDeadlines = HashMap<Int, Long>()

    private fun recordC2dmWakeWindow(uid: Int) {
        val now = SystemClock.uptimeMillis()
        val deadline = now + HookerSupport.C2DM_FREEZE_DELAY_MS
        synchronized(c2dmWakeDeadlines) {
            if (c2dmWakeDeadlines.size >= 64) {
                val it = c2dmWakeDeadlines.entries.iterator()
                while (it.hasNext()) {
                    if (it.next().value <= now) {
                        it.remove()
                    }
                }
                if (c2dmWakeDeadlines.size >= 64) {
                    c2dmWakeDeadlines.clear()
                }
            }
            val existing = c2dmWakeDeadlines[uid]
            if (existing == null || deadline > existing) {
                c2dmWakeDeadlines[uid] = deadline
            }
        }
    }

    private fun isInC2dmWakeWindow(uid: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        synchronized(c2dmWakeDeadlines) {
            val deadline = c2dmWakeDeadlines[uid] ?: return false
            if (now < deadline) {
                return true
            }
            c2dmWakeDeadlines.remove(uid)
            return false
        }
    }

    /**
     * greeze (`com.miui.server.greeze.GreezeManagerService`): the ROM's freeze /
     * broadcast-policy engine, and this module's largest system_server surface.
     *
     * The findings every decision below rests on (OS4 V816, each one read back
     * from the running device, not from the disassembly alone):
     *
     * - `adb shell dumpsys greezer` is the one command worth running first: it
     *   prints the live Settings (enable / fz_timeout / monitor),
     *   `mCurrentCNPolicy`, `mGmsLimitEnabled`, the frozen process list, the
     *   greeze history, **and the real contents of `mBroadcastTargetWhiteList`**
     *   — 8 entries on this device, all Tencent / Feishu / Rimet. GMS is not one
     *   of them, which is the whole reason the broadcast gates below exist.
     * - Policy dispatch is Domestic here (`mCurrentCNPolicy: 1`, region CN).
     *   `isPushApp` is declared **only** in `InternationalPolicyManager` (not in
     *   the `PolicyManager` interface, not in `DomesticPolicyManager`), and that
     *   class is never instantiated here — the hook on it was removed on
     *   2026-10-30 rather than kept "for international ROMs": those do not need
     *   this module at all, so there is no machine on which it could ever run.
     * - GMS does not enter the freeze path at all: per-uid accounting reads
     *   `uid=10133 frozen=0s` after 15h and `noControl` reports
     *   `no_freeze:invisible`. The network hooks in this file are therefore
     *   dormant, not the fix — but they are load-bearing peers of the freeze
     *   chain, not redundancy: S12 answers at the chain's first gate (see the
     *   reason map in [hookDomesticRestrictNet]), and the moment a caller
     *   bypasses PolicyMaker, these are what keep GMS's network alive.
     * - The real MIUI network engine is netd's
     *   `OemNetdListener.setMiuiFirewallRule` (0 rules installed at runtime
     *   here). The older `enablemiuistandby enable` standby chain is a dead
     *   letter on this ROM — dnsproxyd answers `500 Command not recognized` — and
     *   is intercepted only as an OTA hedge.
     *
     * A quiet night proves nothing on its own; FCM_HOOK_TECH_DOC.md §6.9
     * fixes the wording for that case ("not observed in this window", never
     * "not needed").
     */
    internal fun hookGreezeManagerService(env: HookEnv) {
        val module = env.module
        val GreezeManagerServiceClass =
            env.classLoader.loadClass("com.miui.server.greeze.GreezeManagerService")
        try {
            val isAllowBroadcastMethod = env.findMethod(
                GreezeManagerServiceClass,
                "isAllowBroadcast",
                Int::class.javaPrimitiveType, String::class.java,
                Int::class.javaPrimitiveType, String::class.java, String::class.java
            )
            val getPackageNameFromUidMethod = env.findMethod(
                GreezeManagerServiceClass,
                "getPackageNameFromUid",
                Int::class.javaPrimitiveType
            )
            getPackageNameFromUidMethod?.isAccessible = true
            if (getPackageNameFromUidMethod == null) {
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "GreezeManagerService#getPackageNameFromUid absent;" +
                        " isAllowBroadcast falls back to the raw callee argument"
                )
            }
            if (isAllowBroadcastMethod == null) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "GreezeManagerService#isAllowBroadcast absent, skip")
            } else {
                val uidLookup = getPackageNameFromUidMethod
                env.hookE(isAllowBroadcastMethod).intercept { chain: XposedInterface.Chain ->
                    // Argument order taken from the disassembly of the only caller
                    // (GreezeManagerService, offset 00f4), not from the parameter
                    // names, because everything below depends on it:
                    //   arg0 = callerUid, arg1 = callerPkgName,
                    //   arg2 = calleeUid, arg3 = calleePkgName, arg4 = action
                    // The caller inverts the result: true ⇒ thawUidAsync(calleeUid)
                    // and deliver, false ⇒ isNeedCachedBroadcast (defer).
                    var calleePkgName: String? = chain.getArg(3) as? String
                    if (uidLookup != null) {
                        try {
                            val calleeUid = chain.getArg(2)
                            if (calleeUid is Int) {
                                val calleePackageName =
                                    module.getInvoker(uidLookup).invoke(chain.thisObject, calleeUid)
                                if (calleePackageName is String) {
                                    calleePkgName = calleePackageName
                                }
                            }
                        } catch (e: Exception) {
                            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to get callee package name", e)
                        }
                    }
                    val action = chain.getArg(4)
                    if (action is String) {
                        val callerUid = chain.getArg(0)
                        val callerPkg = chain.getArg(1) as? String
                        val callerPkgIsGms = HookerSupport.GMS_PACKAGE_NAME == callerPkg
                        // P1 (2026-10-02): callerPkgName is not always populated in
                        // the caller's Slog output, but callerUid always is. Same
                        // gate, sturdier key — the two paths are counted separately
                        // so the log shows which one actually carried the decision.
                        val callerUidIsGms = callerUid is Int && env.isGmsUid(callerUid)
                        val calleeIsGms = HookerSupport.GMS_PACKAGE_NAME == calleePkgName ||
                            HookerSupport.GMS_PERSISTENT_PROCESS_NAME == calleePkgName
                        // The c2dm allow lives in isRestrictReceiver (S10). The
                        // fallback that used to sit here was removed on
                        // 2026-10-30 after the OS3 dump settled S10's targets:
                        // `isRestrictReceiver(Intent,int,String,int,String)` and
                        // `thawUidAsync(int,int,String)` exist with identical
                        // signatures in both supported dexes, so S10 installs on
                        // every generation this module supports. If it ever
                        // fails, it reports through the install summary and the
                        // ROM's freeze policy takes over, as on a device
                        // without this module.
                        if (calleeIsGms && HookerSupport.CN_DEFER_BROADCAST.contains(action)) {
                            return@intercept true
                        }
                        if (HookerSupport.ACTION_REMOTE_INTENT == action &&
                            (callerPkgIsGms || callerUidIsGms)
                        ) {
                            // Reachable with the callee off the user's list.
                            // Counted so "an unselected app is treated
                            // exactly like a phone without the module" is an
                            // observation rather than a claim — this is the path P0
                            // hands back to the ROM.
                            if (!broadcastGateSkippedLogged) {
                                broadcastGateSkippedLogged = true
                                module.logGated(
                                    Log.INFO, HookerSupport.TAG,
                                    "isAllowBroadcast: c2dm not intercepted for " +
                                        "callee=$calleePkgName (not on the allowlist); " +
                                        "left to the ROM policy"
                                )
                            }
                        }
                    }
                    chain.proceed()
                }
                module.deoptimize(isAllowBroadcastMethod)
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "P1: isAllowBroadcast hooked for GMS-internal reconnect actions only; " +
                        "the c2dm allow lives in isRestrictReceiver (S10)"
                )
            }
        } catch (e: Exception) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to hook GreezeManagerService#isAllowBroadcast", e)
        }
        // S3 (`deferBroadcastForMiui` → false for CN actions) was removed on
        // 2026-10-30. Reachability on OS4 needs three things at once: the callee
        // FULLY_FROZEN (isRestrictReceiver is the only caller of isAllowBroadcast),
        // the cloud list `mAllowBroadcast` (or an important-app callee) admitting
        // the action, and — for the GMS callee case that matters here — a pass
        // through the frame where S2 already answers the same four actions at
        // isAllowBroadcast's entry. No sample ever existed; the residual face is
        // cloud-gated, which is the module's own exemption rule for keeping a hook.

        // S4 (`triggerGMSLimitAction()V`): restored after verifying that
        // `GreezeManagerService$12$1#run()` sends `mHandler.sendEmptyMessageDelayed(10, 10000L)`
        // when `isGmsApp(uid) && !mScreenOn`, and `H#handleMessage` what=10 invokes
        // `triggerGMSLimitAction()` directly on both OS3 and OS4. When `mGmsLimitEnabled`
        // is true, the method calls `removeAurogonAllowList("com.google.android.gms")`
        // before `triggerQuickFreeze(uid, 0)` — suppressing the entry prevents GMS from
        // being stripped out of the Aurogon allowlist as well as skipping quick-freeze.
        try {
            val triggerGmsLimitMethod =
                GreezeManagerServiceClass.getDeclaredMethod("triggerGMSLimitAction")
            triggerGmsLimitMethod.isAccessible = true
            env.hookE(triggerGmsLimitMethod).intercept { _: XposedInterface.Chain ->
                if (!gmsLimitActionSuppressedLogged) {
                    gmsLimitActionSuppressedLogged = true
                    module.logGated(
                        Log.INFO, HookerSupport.TAG,
                        "GreezeManagerService#triggerGMSLimitAction: suppressed GMS limit action"
                    )
                }
                null
            }
            module.deoptimize(triggerGmsLimitMethod)
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "GreezeManagerService#triggerGMSLimitAction hooked"
            )
        } catch (e: NoSuchMethodException) {
            env.logSkip("GreezeManagerService#triggerGMSLimitAction absent, skip")
        }

        // S5's target is OS4-only: the OS3 dex has no `updateGmsNetStatus`
        // (verified 2026-10-30), so a miss there is a cross-generation fact,
        // not a same-generation gap — hence DEBUG, which keeps the OS3 install
        // summary's "absent on this ROM" count honest.
        env.forceBooleanArg(
            GreezeManagerServiceClass,
            "updateGmsNetStatus",
            false,
            "GreezeManagerService",
            Log.DEBUG
        )

        // Post-c2dm job window: during LaunchMode / ImmobulusMode, Greeze's
        // `isJobRestrict(uid)` can block WorkManager/JobScheduler jobs scheduled
        // right after an FCM broadcast arrives. Narrowed strictly to allowlisted
        // uids within the 10s window recorded by `checkApplicationAutoStart` /
        // `isRestrictReceiver`; outside that window the ROM's verdict is untouched.
        try {
            val isJobRestrictMethod = GreezeManagerServiceClass.getDeclaredMethod(
                "isJobRestrict",
                Int::class.javaPrimitiveType
            )
            isJobRestrictMethod.isAccessible = true
            env.hookE(isJobRestrictMethod).intercept { chain: XposedInterface.Chain ->
                val result = chain.proceed()
                try {
                    if (java.lang.Boolean.TRUE == result) {
                        val uid = chain.getArg(0)
                        if (uid is Int && isInC2dmWakeWindow(uid)) {
                            if (!c2dmJobAllowedLogged) {
                                c2dmJobAllowedLogged = true
                                module.logGated(
                                    Log.INFO, HookerSupport.TAG,
                                    "GreezeManagerService#isJobRestrict: allowed post-c2dm job " +
                                        "for uid=$uid (within ${HookerSupport.C2DM_FREEZE_DELAY_MS}ms window)"
                                )
                            }
                            return@intercept false
                        }
                    }
                } catch (t: Throwable) {
                    module.logGated(
                        Log.ERROR, HookerSupport.TAG,
                        "GreezeManagerService#isJobRestrict hook failed", t
                    )
                }
                result
            }
            module.deoptimize(isJobRestrictMethod)
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "GreezeManagerService#isJobRestrict hooked (post-c2dm 10s window only)"
            )
        } catch (e: NoSuchMethodException) {
            env.logSkip("GreezeManagerService#isJobRestrict absent, skip")
        }
    }

    internal fun hookDomesticPolicyManager(
        env: HookEnv,
        netReleaseEnabled: () -> Boolean
    ) {
        val DomesticPolicyManagerClass =
            env.classLoader.loadClass("com.miui.server.greeze.DomesticPolicyManager")
        // S6 (`DomesticPolicyManager#deferBroadcast` counter) was removed on
        // 2026-10-30: a c2dm reaching the ROM deferral policy is already visible
        // as "not allowed upstream", and the counter had no consumer after the
        // broadcast-gate summary went away.

        hookDomesticRestrictNet(env, DomesticPolicyManagerClass, netReleaseEnabled)
    }

    /**
     * P0 #1 (action list 2.1): `DomesticPolicyManager#isRestrictNet(I)Z` → false for GMS.
     *
     * Polarity matters and was wrong in every earlier round, so it is recorded
     * here from the bytecode instead of from the method names:
     *
     *   isNeedRestictNetworkPolicy(uid) == mMessageApp.contains(pkg)   // @16cc44
     *   isRestrictNet(uid)               == !isNeedRestictNetworkPolicy(uid)   // @172c90
     *
     * `mMessageApp` is therefore the **exempt** list (544 entries on this ROM,
     * probed at runtime: GMS absent), and the only caller of `isRestrictNet` is
     * inside `GreezeManagerService.freezeUids(...)`:
     *
     *   if (isRestrictNet(uid)) { flags |= 0x0C00; closeSocketForAurogon(uid);
     *                             updateAurogonUidRule(uid, true); }
     *
     * So true really means "restrict this uid's network", and GMS — being absent
     * from the exempt list — would get its sockets torn if it were ever frozen.
     *
     * Not applicable today: `dumpsys greezer` shows GMS never entering the freeze
     * path on this device (E7-3 closed, negative), so the hook does not fire in
     * normal use. Settled 2026-10-30 — where S12 actually answers inside
     * `AurogonFilterManager#filter` (the `0x40` bit) and where it does not:
     *
     *   0x40 SET   PolicyMaker#checkFilterWhitelist   0x20026a40  ← isAllowFreeze
     *              main chain, FIRST gate (S13 pins isAllowFreeze above it, and
     *              CANNOT_FREEZE=0, so the pin is correct)
     *   0x40 SET   BigData…triggerHighCpuLoadFreeze   0x237eafa
     *   0x40 SET   GMS#triggerIdleAppAction           0x23feefe
     *   no 0x40    GMS#freezeAction (both gates)      0x50000000 / 0x20000000
     *   no 0x40    PolicyMaker#checkStatePolicy       0x2000004  (second gate,
     *              only reached when the first said FREEZE_ABLE)
     *   no 0x40    GMS#preCheckInterest               0x1521c00 (+0x20|0x200|0x2000
     *              when the screen is on)
     *   no 0x40    BgCpuHighLoadManager#checkAllExemptions 0x378ab2
     *
     * So S12 stops GMS at the first gate of the main freeze chain; the branches
     * below only matter for callers that bypass PolicyMaker. This hook shares
     * the freeze path's fate: if GMS ever freezes, this is what keeps its
     * network alive.
     *
     * Experiment branch (`greeze_net_release`, default off): the same `false`
     * for a uid whose package is on the FCM wake allowlist. An empty allowlist
     * answers no uid, and an unreadable switch reads as off — the freeze
     * decision itself is untouched either way, only its network half. GMS is
     * deliberately not answered by the branch: the forced answer above is the
     * only path that touches the GMS uid, so the two stay separately countable.
     * Without the experiment branch, every other uid proceeds unchanged, so
     * the instant-messaging apps that *are* on the exempt list keep their
     * existing policy.
     */
    private fun hookDomesticRestrictNet(
        env: HookEnv,
        domesticPolicyManagerClass: Class<*>,
        netReleaseEnabled: () -> Boolean
    ) {
        val module = env.module
        try {
            val isRestrictNetMethod = domesticPolicyManagerClass.getDeclaredMethod(
                "isRestrictNet", Int::class.javaPrimitiveType
            )
            env.hookE(isRestrictNetMethod).intercept { chain: XposedInterface.Chain ->
                val uid = chain.getArg(0)
                if (uid is Int && env.isGmsUid(uid)) {
                    // Dedicated one-shot: this fires once per boot, and it is the
                    // only evidence that the Domestic restrict-net branch is real.
                    if (!gmsRestrictNetLogged) {
                        gmsRestrictNetLogged = true
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "DomesticPolicyManager#isRestrictNet: kept GMS (uid $uid) unrestricted"
                        )
                    }
                    return@intercept false
                }
                if (uid is Int && netReleaseEnabled()) {
                    val checkedPkg = env.allowlistPackageForUid(uid)
                    if (checkedPkg != null) {
                        // Detail budget: one line per uid, ever — the summary
                        // tick carries the volume, so repeating the same names
                        // each freeze would only spend log bytes.
                        synchronized(greezeNetReleaseLoggedUids) {
                            if (greezeNetReleaseLoggedUids.add(uid)) {
                                module.logGated(
                                    Log.INFO, HookerSupport.TAG,
                                    "greeze net release: kept checked app " +
                                        "$checkedPkg (uid $uid) unrestricted"
                                )
                            }
                        }
                        return@intercept false
                    }
                }
                chain.proceed()
            }
            module.deoptimize(isRestrictNetMethod)
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "DomesticPolicyManager#isRestrictNet hooked for GMS; " +
                    "checked apps follow greeze_net_release"
            )
        } catch (e: NoSuchMethodException) {
            env.logSkip("DomesticPolicyManager#isRestrictNet absent, skip")
        }
    }

    /**
     * Action list 3.6: keep GMS off the ROM's UDP packet filter.
     *
     * Eight rounds judged `updateAurogonUidRule` dead because the Domestic
     * implementation is an empty method — but that only covers the policy
     * dispatch. Both branches of the *host* method end in the same private tail:
     *
     *   GreezeManagerService.updateAurogonUidRule(uid, allow)          @18bf08
     *     ├─ CN:      PolicyManager.updateAurogonUidRule(...)   // Domestic = empty
     *     └─ non-CN:  reflect ConnectivityManager#updateAurogonUidRule
     *     ⇒ both fall through to udpPackageRestrict(uid, allow) @18ba14
     *          allow=true  → PowerInsightService.setUidNetworkFilter(uid)
     *          allow=false → PowerInsightService.clearUidNetworkFilter(uid)
     *
     * The freeze callers pass `true`, the thaw / binderDied / appDied callers pass
     * `false`, so this mirrors 3.1: filtering is applied on freeze and withdrawn on
     * thaw. Unlike 3.1 the tail is **not** an empty method on this device (CN model,
     * `mPowerMilletEnable` true, `FilterEnablePolicy.isSupportPacketFilter()` true),
     * so it is the more defensible of the two defence hooks.
     *
     * Only the `allow=true` direction is skipped. Skipping `allow=false` too would
     * strand an already-installed filter and leave GMS permanently filtered.
     *
     * Not applicable today: E7-3 shows GMS never entering the freeze path, so
     * this does not fire in normal use. It shares the freeze path's fate; S12's
     * answer point inside `filter` is mapped in [hookDomesticRestrictNet]
     * (0x40 set on the main chain's first gate, absent from `freezeAction`'s
     * own two gates).
     */
    internal fun hookUdpPackageRestrict(env: HookEnv) {
        val module = env.module
        try {
            val greezeManagerServiceClass =
                env.classLoader.loadClass("com.miui.server.greeze.GreezeManagerService")
            val udpPackageRestrictMethod = greezeManagerServiceClass.getDeclaredMethod(
                "udpPackageRestrict",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            udpPackageRestrictMethod.isAccessible = true
            env.hookE(udpPackageRestrictMethod).intercept { chain: XposedInterface.Chain ->
                val uid = chain.getArg(0)
                val allow = chain.getArg(1) == true
                if (uid is Int && allow && env.isGmsUid(uid)) {
                    if (!gmsUdpFilterLogged) {
                        gmsUdpFilterLogged = true
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "udpPackageRestrict: skipped UDP filter for GMS (uid $uid)"
                        )
                    }
                    return@intercept null
                }
                chain.proceed()
            }
            module.deoptimize(udpPackageRestrictMethod)
            module.logGated(Log.INFO, HookerSupport.TAG, "GreezeManagerService#udpPackageRestrict hooked for GMS")
        } catch (e: ClassNotFoundException) {
            env.logSkip("GreezeManagerService absent, udpPackageRestrict skip")
        } catch (e: NoSuchMethodException) {
            env.logSkip("GreezeManagerService#udpPackageRestrict absent, skip")
        }
    }

    internal fun hookListAppsManager(env: HookEnv) {
        val module = env.module
        val ListAppsManagerClass =
            env.classLoader.loadClass("com.miui.server.greeze.power.ListAppsManager")
        // SYSTEM_BLACK_LIST is deliberately NOT touched any more (2026-10-30).
        // The OS4 dex settles the polarity the earlier revision could only
        // guess at:
        //
        //   ListAppsManager.<init>  → SYSTEM_BLACK_LIST.addAll(array/greezer_power_black_list)
        //                             if (!PolicyManager.isCnModel()) SYSTEM_BLACK_LIST.remove("com.google.android.gms")
        //   PolicyMaker#checkStatePolicy      → isIncludeSystemBlack && plugged → CANNOT_FREEZE ("systemblack")
        //   PolicyMaker#preCheckSystemApp     → isIncludeSystemBlack && plugged → "sysblack_plugged"
        //
        // So the set is an *exemption* list, and the ROM removes GMS from it only
        // on non-CN builds — on this CN device `com.google.android.gms` is in
        // `greezer_power_black_list` on purpose. Pruning it was the opposite of
        // the module's goal (it removed a charging-time freeze exemption).
        val useDataField = findFirstField(
            ListAppsManagerClass, "mUseDataWhiteList", "USE_DATA_WHITE_LIST"
        )
        if (useDataField == null) {
            module.logGated(
                Log.ERROR, HookerSupport.TAG,
                "Failed to find ListAppsManager.mUseDataWhiteList or ListAppsManager.USE_DATA_WHITE_LIST"
            )
        }
        // The use-data set is what `PolicyMaker#preCheckSystemApp` reads for the
        // "whiteapp" exemption, and the ROM does not seed GMS into it on CN.
        // The edit is idempotent, so one post-construction hook suffices.
        // The use-data half used to sit on isInWhiteList, i.e. on every use-data
        // query for every package: the set is process-wide and the entry only
        // ever has to appear once, so a reflective field read per query bought
        // nothing after the first call.
        if (useDataField != null) {
            for (constructor in ListAppsManagerClass.declaredConstructors) {
                env.hookE(constructor).intercept { chain: XposedInterface.Chain ->
                    try {
                        chain.proceed()
                    } finally {
                        try {
                            addGmsToUseDataWhiteList(module, useDataField, chain.thisObject)
                        } catch (t: Throwable) {
                            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to modify use data whitelist", t)
                        }
                    }
                }
                module.deoptimize(constructor)
            }
        }
        // The isInWhiteList re-add backstop was removed on 2026-10-30 (second
        // pass). OS4 dex: the only mutators of USE_DATA_WHITE_LIST are
        // updateSleepMode(Z) — removeAll/addAll of SLEEP_MODE_CTRL_LIST, whose
        // seed resource `greezer_sleep_mode_control_list` (0x1103013e) holds
        // seven Xiaomi packages and never GMS — and dealCloudWhiteList(String),
        // which only adds. Nothing on this generation can remove GMS from the
        // set after the constructor injection above, so a per-query re-add hook
        // had no reachable trigger. If a future ROM adds GMS to that resource,
        // re-check here; the constructor hook alone does not survive a
        // updateSleepMode(true) sweep in that case.
    }

    /** First declared field that exists, made accessible. Both ROM spellings are tried. */
    private fun findFirstField(owner: Class<*>, vararg names: String): Field? {
        for (name in names) {
            try {
                return owner.getDeclaredField(name).also { it.isAccessible = true }
            } catch (ignored: NoSuchFieldException) {
            }
        }
        return null
    }

    private fun addGmsToUseDataWhiteList(module: XposedInterface, field: Field, owner: Any) {
        val set = useDataWhiteListOf(field, owner) ?: return
        val added = set.add(HookerSupport.GMS_PACKAGE_NAME)
        if (!listAppsUseDataAddLogged) {
            listAppsUseDataAddLogged = true
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "ListAppsManager: added GMS to use-data whitelist (was absent=$added)"
            )
        }
    }

    /**
     * The use-data set, resolved once when the field is static (it is on OS4:
     * `USE_DATA_WHITE_LIST`), read per instance otherwise. Cached so the hot
     * query path stops paying a reflective field read for a set that only ever
     * needs one membership answer.
     */
    @Volatile
    private var useDataWhiteListCache: MutableSet<String>? = null

    @Suppress("UNCHECKED_CAST")
    private fun useDataWhiteListOf(field: Field, owner: Any?): MutableSet<String>? {
        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
            useDataWhiteListCache?.let { return it }
            val resolved = try {
                field.get(null) as? MutableSet<String>
            } catch (t: Throwable) {
                null
            }
            if (resolved != null) {
                useDataWhiteListCache = resolved
            }
            return resolved
        }
        return try {
            field.get(owner) as? MutableSet<String>
        } catch (t: Throwable) {
            null
        }
    }

    internal fun hookBroadcastQueueModernStubImpl(env: HookEnv) {
        val module = env.module
        val classLoader = env.classLoader
        val BroadcastQueueModernStubImplClass =
            classLoader.loadClass("com.android.server.am.BroadcastQueueModernStubImpl")
        val BroadcastQueueClass = classLoader.loadClass("com.android.server.am.BroadcastQueue")
        val BroadcastRecordClass = classLoader.loadClass("com.android.server.am.BroadcastRecord")
        val callerPackageField = BroadcastRecordClass.getDeclaredField("callerPackage")
        callerPackageField.isAccessible = true
        val callingUidField = try {
            BroadcastRecordClass.getDeclaredField("callingUid").also { it.isAccessible = true }
        } catch (ignored: NoSuchFieldException) {
            null
        }
        val intentField = BroadcastRecordClass.getDeclaredField("intent")
        intentField.isAccessible = true
        val checkApplicationAutoStartMethod = BroadcastQueueModernStubImplClass.getDeclaredMethod(
            "checkApplicationAutoStart",
            BroadcastQueueClass,
            BroadcastRecordClass,
            ResolveInfo::class.java
        )
        env.hookE(checkApplicationAutoStartMethod).intercept { chain: XposedInterface.Chain ->
            try {
                val broadcastRecord = chain.getArg(1)
                val callerPackage = callerPackageField.get(broadcastRecord) as? String
                val callerPkgIsGms = HookerSupport.GMS_PACKAGE_NAME == callerPackage
                val callerUidIsGms = !callerPkgIsGms && callingUidField != null &&
                    env.isGmsUid(callingUidField.getInt(broadcastRecord))
                val intent = intentField.get(broadcastRecord) as? Intent
                val resolveInfo = chain.getArg(2) as? ResolveInfo
                val targetPackage = intent?.let { HookerSupport.targetPackageOf(it) }
                    ?: resolveInfo?.activityInfo?.applicationInfo?.packageName
                    ?: resolveInfo?.activityInfo?.packageName
                if ((callerPkgIsGms || callerUidIsGms) &&
                    intent != null &&
                    HookerSupport.ACTION_REMOTE_INTENT == intent.action &&
                    targetPackage != null &&
                    AllowlistGate.moduleAppliesTo(module, env::systemContext, targetPackage)
                ) {
                    val calleeUid = resolveInfo?.activityInfo?.applicationInfo?.uid
                    if (calleeUid != null) {
                        recordC2dmWakeWindow(calleeUid)
                    }
                    if (!broadcastGateAutostartAllowedLogged) {
                        broadcastGateAutostartAllowedLogged = true
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "checkApplicationAutoStart: c2dm allowed for callee=$targetPackage " +
                                "(matchedBy=${if (callerPkgIsGms) "callerPkg" else "callerUid"}, " +
                                "answered at method entry, before MIUIOP 10008 is read)"
                        )
                    }
                    return@intercept true
                }
                // The only autostart gate this module answers is the branch
                // above. A second branch used to drop its caller restriction
                // and answer any push broadcast from the intent alone, the way
                // the two reference modules do; it is gone because it never ran
                // on anything the shipped GMS→c2dm hop had not answered already
                // — real FCM calls always come from GMS, so `callerPackage` is
                // GMS on every broadcast the module cares about, and with all
                // four experiment switches off push still arrived.
            } catch (e: Exception) {
                module.logGated(
                    Log.ERROR, HookerSupport.TAG,
                    "Failed to modify BroadcastQueueModernStubImpl#checkApplicationAutoStart", e
                )
            }
            chain.proceed()
        }
        module.deoptimize(checkApplicationAutoStartMethod)

        // Second greeze gate. checkApplicationAutoStart only covers the cold-start
        // (ResolveInfo) path; a warm but frozen receiver goes through this one, which
        // asks GreezeManagerService#isRestrictReceiver. An earlier revision
        // short-circuited BroadcastQueueModernStubImpl#checkReceiverIfRestricted, which
        // skipped the thawUidAsync("bc_action") that isRestrictReceiver performs on its
        // native pass-through path: the broadcast was dispatched to a still-frozen
        // process, no one ever thawed it, and GMS retried the same message forever
        // ("No response to broadcast"). Hook isRestrictReceiver itself instead — answer
        // false (not restricted) and reproduce the native thaw before delivering.
        //
        // ponytail: checkReceiverIfRestricted is deliberately left unhooked, even
        //   though every comparable external module still short-circuits it —
        //   short-circuiting skips the thaw above. Cost: one extra
        //   isRestrictReceiver round-trip per c2dm broadcast. Condition to add it
        //   back: a real sample that is allowed here yet still ends in
        //   "No response to broadcast", with proof the thaw is not what saved it.
        try {
            val GreezeManagerServiceClass =
                classLoader.loadClass("com.miui.server.greeze.GreezeManagerService")
            val isRestrictReceiverMethod = GreezeManagerServiceClass.getDeclaredMethod(
                "isRestrictReceiver",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val thawUidAsyncMethod = GreezeManagerServiceClass.getDeclaredMethod(
                "thawUidAsync",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            // OS3/OS4 both declare isUidFrozen(int)Z; checking it avoids posting
            // redundant thawUidAsync runnables for already-unfrozen receivers.
            val isUidFrozenMethod = env.findMethod(
                GreezeManagerServiceClass,
                "isUidFrozen",
                Int::class.javaPrimitiveType
            )?.also { it.isAccessible = true }
            // OS3/OS4 anti-refreeze grace helper: Greeze's own thawUidAsync only
            // grants 2000ms via ActionExecute#tryDelayFreeze(uid, 2000L, false).
            // Extending to 10s (aligned with GMS's 10s Doze temp_allowlist) lets
            // the target app finish TLS handshake / message fetch after wake.
            // tryDelayFreeze takes max(existing, now + delay), so it never
            // truncates a longer ROM-granted grace window.
            val powerStrategyModeField = try {
                GreezeManagerServiceClass.getDeclaredField("mPowerStrategyMode")
                    .also { it.isAccessible = true }
            } catch (ignored: NoSuchFieldException) {
                null
            }
            val getActionExecuteMethod = try {
                classLoader.loadClass("com.miui.server.greeze.power.PowerStrategyMode")
                    .getDeclaredMethod("getActionExecute")
                    .also { it.isAccessible = true }
            } catch (ignored: Throwable) {
                null
            }
            val tryDelayFreezeMethod = try {
                classLoader.loadClass("com.miui.server.greeze.power.ActionExecute")
                    .getDeclaredMethod(
                        "tryDelayFreeze",
                        Int::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    ).also { it.isAccessible = true }
            } catch (ignored: Throwable) {
                null
            }
            env.hookE(isRestrictReceiverMethod).intercept { chain: XposedInterface.Chain ->
                try {
                    val intent = chain.getArg(0) as? Intent
                    val callerUid = chain.getArg(1)
                    val callerPackage = chain.getArg(2) as? String
                    val calleeUid = chain.getArg(3) as Int
                    val calleePackage = chain.getArg(4) as? String
                    // Two caller keys on purpose: callerPkgName is not always
                    // populated on this hop, callerUid always is. This branch is
                    // now the module's only c2dm allow (the isAllowBroadcast
                    // duplicate was removed), so the uid fallback lives here —
                    // without it a c2dm whose caller package string came through
                    // empty would fall through to the ROM's freeze policy.
                    val callerPkgIsGms = HookerSupport.GMS_PACKAGE_NAME == callerPackage
                    val callerUidIsGms = callerUid is Int && env.isGmsUid(callerUid)
                    if ((callerPkgIsGms || callerUidIsGms) &&
                        intent != null &&
                        HookerSupport.ACTION_REMOTE_INTENT == intent.action &&
                        AllowlistGate.moduleAppliesTo(module, env::systemContext, calleePackage)
                    ) {
                        recordC2dmWakeWindow(calleeUid)
                        if (!broadcastGateAllowedLogged) {
                            broadcastGateAllowedLogged = true
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "isRestrictReceiver: c2dm allowed for callee=$calleePackage " +
                                    "(callerUid=$callerUid, callerPkg=$callerPackage, " +
                                    "matchedBy=${if (callerPkgIsGms) "callerPkg" else "callerUid"})"
                            )
                        }
                        if (powerStrategyModeField != null &&
                            getActionExecuteMethod != null &&
                            tryDelayFreezeMethod != null
                        ) {
                            try {
                                val mode = powerStrategyModeField.get(chain.thisObject)
                                val actionExecute =
                                    if (mode != null) getActionExecuteMethod.invoke(mode) else null
                                if (actionExecute != null) {
                                    tryDelayFreezeMethod.invoke(
                                        actionExecute,
                                        calleeUid,
                                        HookerSupport.C2DM_FREEZE_DELAY_MS,
                                        true
                                    )
                                    if (!c2dmFreezeDelayLogged) {
                                        c2dmFreezeDelayLogged = true
                                        module.logGated(
                                            Log.INFO, HookerSupport.TAG,
                                            "isRestrictReceiver: extended anti-refreeze grace to " +
                                                "${HookerSupport.C2DM_FREEZE_DELAY_MS}ms for " +
                                                "callee=$calleePackage (uid=$calleeUid)"
                                        )
                                    }
                                }
                            } catch (t: Throwable) {
                                module.logGated(
                                    Log.WARN, HookerSupport.TAG,
                                    "isRestrictReceiver: tryDelayFreeze failed (non-fatal)", t
                                )
                            }
                        }
                        // Only invoke thawUidAsync when the uid is actually frozen
                        // (fall back to thawing if isUidFrozen is unavailable).
                        val frozen = if (isUidFrozenMethod != null) {
                            module.getInvoker(isUidFrozenMethod)
                                .invoke(chain.thisObject, calleeUid) == true
                        } else {
                            true
                        }
                        if (frozen) {
                            // Caller uid 1000 and a reason string follow the ROM's own
                            // thawUidAsync calls. The native allowed path thaws with
                            // "broadcast"; this hook deliberately keeps "bc_action"
                            // (what the ROM's cached-broadcast early exits use) so a
                            // module-triggered thaw stays distinguishable in greeze
                            // history from a native one.
                            thawUidAsyncMethod.invoke(chain.thisObject, calleeUid, 1000, "bc_action")
                        }
                        return@intercept false
                    }
                } catch (e: Exception) {
                    module.logGated(
                        Log.ERROR, HookerSupport.TAG,
                        "Failed to modify GreezeManagerService#isRestrictReceiver", e
                    )
                }
                chain.proceed()
            }
            module.deoptimize(isRestrictReceiverMethod)
        } catch (e: NoSuchMethodException) {
            env.logSkip("GreezeManagerService#isRestrictReceiver absent, skip")
        } catch (e: ClassNotFoundException) {
            env.logSkip("GreezeManagerService absent, isRestrictReceiver not hooked")
        }
    }

    // S11 (`isNeedCachedBroadcast` → false for c2dm) was removed on 2026-10-30.
    // It was shadowed end to end: the only caller of that method is
    // isRestrictReceiver's `else` branch, reached only when isAllowBroadcast
    // answered "restricted" — and the S10 hook answers the same c2dm question at
    // that method's entry, so the call site no longer runs. Its remaining face
    // was "c2dm from a caller that is not GMS", which the FCM hop never produces.
    // The trade is one layer instead of two: if S10 ever fails to install it
    // reports ERROR, and the ROM's freeze policy takes over as it does on a
    // device without this module.

    // ponytail: GMS is only ever APPENDED to the returned list, never removed
    //   from a ROM-side list. Comparable external modules remove GMS from
    //   `whiteApps` and from Millet's black/white lists on the assumption that
    //   they are restriction lists; that polarity has never been confirmed, and
    //   if any of them is an allow-list the removal tightens instead of
    //   loosening. Cost: the entry is rebuilt per query instead of persisted.
    //   Condition to revisit: bytecode or runtime proof of a list's polarity on
    //   this generation — and even then, prefer appending over removing.
    //
    // OS4 status (2026-10-30 second pass): the static seed already carries GMS —
    // `systemReady` loads sStaticWhiteList from framework-ext-res
    // `package_static_white_list_base` (0x110300ad, 33 entries, GMS included) —
    // so the append below is a no-op on this generation and the hook stays as an
    // OS3 hedge only. The `gms.persistent` append was removed with it: that is a
    // process name, not a package, and can never match an entry of a package
    // list.
    internal fun hookProcessPolicy(env: HookEnv) {
        val module = env.module
        val ProcessPolicyClass = env.classLoader.loadClass("com.android.server.am.ProcessPolicy")
        val getWhiteListMethod = ProcessPolicyClass.getDeclaredMethod(
            "getWhiteList", Int::class.javaPrimitiveType
        )
        env.hookE(getWhiteListMethod).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            try {
                val flags = chain.getArg(0)
                if (flags is Int && (flags and 1) != 0 && result is List<*>) {
                    // Only the returned copy is extended: getWhiteList builds a
                    // fresh ArrayList on every call (verified against the OS4
                    // miui-services dex), so an in-place add on `source` would
                    // have no observer — that write used to sit here.
                    val whiteList = ArrayList<Any?>(result)
                    HookerSupport.addIfAbsent(whiteList, HookerSupport.GMS_PACKAGE_NAME)
                    return@intercept whiteList
                }
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to extend ProcessPolicy white list", t)
            }
            result
        }
        module.deoptimize(getWhiteListMethod)
    }

    /**
     * P5 (defence): keep GMS out of the ROM's frozen-process wake-lock gate.
     *
     * `PowerManagerService#setWakeLockDisabledStateLocked` disables a cached
     * process's partial wakelocks unless
     * `PowerManagerServiceStub#isAllowWakeUpList(uid)` answers true; the stub
     * forwards to `GreezeManagerService#isAllowWakeUpList(uid)`, which resolves
     * the uid's packages and asks `AurogonImmobulusMode#isAllowWakeUpList(pkg)`
     * against `mAllowWakeUpPackageNameList` (built from the `_wakeuplist`
     * resource and refreshed by cloud). The module never injected GMS into that
     * list.
     *
     * Device facts (2026-10-28, `dumpsys power`): `no_cached_wake_locks=true`
     * and `disable_frozen_process_wakelocks=true (def)`, so the gate is live;
     * `dumpsys greezer` still shows GMS never frozen, so this stays defence in
     * depth rather than a claimed fix. Only the GMS uid is answered — every
     * other uid proceeds untouched.
     */
    internal fun hookAllowWakeUpList(env: HookEnv) {
        val module = env.module
        try {
            val greezeManagerServiceClass =
                env.classLoader.loadClass("com.miui.server.greeze.GreezeManagerService")
            val isAllowWakeUpListMethod = greezeManagerServiceClass.getDeclaredMethod(
                "isAllowWakeUpList", Int::class.javaPrimitiveType
            )
            env.hookE(isAllowWakeUpListMethod).intercept { chain: XposedInterface.Chain ->
                val result = chain.proceed()
                try {
                    val uid = chain.getArg(0)
                    if (uid is Int && env.isGmsUid(uid) && java.lang.Boolean.FALSE == result) {
                        // One-shot only when the ROM actually denied: a logged
                        // "kept" claim on an already-allowed call would be a
                        // sentinel that proves nothing.
                        if (!gmsWakeUpListAllowedLogged) {
                            gmsWakeUpListAllowedLogged = true
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "GreezeManagerService#isAllowWakeUpList: re-allowed GMS " +
                                    "(uid $uid) after the ROM denied the wakelock"
                            )
                        }
                        return@intercept true
                    }
                } catch (t: Throwable) {
                    module.logGated(
                        Log.ERROR, HookerSupport.TAG, "isAllowWakeUpList hook failed", t
                    )
                }
                result
            }
            module.deoptimize(isAllowWakeUpListMethod)
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "GreezeManagerService#isAllowWakeUpList hooked for GMS"
            )
        } catch (e: ClassNotFoundException) {
            env.logSkip("GreezeManagerService class absent, isAllowWakeUpList skip")
        } catch (e: NoSuchMethodException) {
            env.logSkip("GreezeManagerService#isAllowWakeUpList absent, skip")
        }
    }

    // S31 (`isNeedCachedAlarmForAurogon` → false for GMS) was removed on
    // 2026-10-30, after the OS4 dex ruled it out end to end:
    //
    //   isNeedCachedAlarmForAurogonInner(uid)
    //     getFreezeState(uid) != FULLY_FROZEN → false
    //     isApp(uid) == false                → false
    //     !pkg.contains("xiaomi")            → false      ← GMS dies here
    //     …then mAurogonAlarmAllowList / ForbidList decide
    //
    // `com.google.android.gms` contains neither "xiaomi" nor "miui", so the ROM
    // answers false for the GMS uid before any policy list is consulted — the
    // hook's "true → false" branch had no input to act on, frozen or not. The
    // caller (`AlarmManagerService#triggerAlarmsLocked`) additionally requires
    // `isUidFrozen(creatorUid)`, which GMS never is on this device.

    internal fun hookAwareResourceControl(env: HookEnv) {
        val module = env.module
        val AwareResourceControlClass =
            env.classLoader.loadClass("com.miui.server.greeze.power.AwareResourceControl")
        val mNoNetworkBlackUidsField =
            AwareResourceControlClass.getDeclaredField("mNoNetworkBlackUids")
        mNoNetworkBlackUidsField.isAccessible = true
        for (constructor in AwareResourceControlClass.declaredConstructors) {
            env.hookE(constructor).intercept { chain: XposedInterface.Chain ->
                try {
                    chain.proceed()
                } finally {
                    try {
                        pruneGmsFromNoNetworkBlacklist(module, mNoNetworkBlackUidsField, chain.thisObject)
                    } catch (t: Throwable) {
                        module.logGated(
                            Log.ERROR, HookerSupport.TAG,
                            "Failed to modify AwareResourceControl.mNoNetworkBlackUids", t
                        )
                    }
                }
            }
            module.deoptimize(constructor)
        }
    }

    private fun pruneGmsFromNoNetworkBlacklist(
        module: XposedInterface,
        blacklistField: Field,
        awareResourceControl: Any
    ) {
        val raw = blacklistField.get(awareResourceControl)
        if (raw !is Collection<*>) {
            return
        }
        @Suppress("UNCHECKED_CAST")
        val blacklist = raw as MutableCollection<Any?>
        // Name only: the OS4 field is List<String> seeded with package names, so a
        // uid can never be an element and remove(uid) was a guaranteed false that
        // additionally paid a PackageManager lookup on every construction.
        val removed = blacklist.remove(HookerSupport.GMS_PACKAGE_NAME)
        if (removed) {
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "Removed GMS from NoNetworkBlackUids (by package name)"
            )
        } else if (!noNetworkBlacklistMismatchLogged) {
            noNetworkBlacklistMismatchLogged = true
            module.logGated(
                Log.INFO, HookerSupport.TAG, "NoNetworkBlackUids (size=" + blacklist.size +
                    ") did not contain the GMS package name"
            )
        }
    }
}
