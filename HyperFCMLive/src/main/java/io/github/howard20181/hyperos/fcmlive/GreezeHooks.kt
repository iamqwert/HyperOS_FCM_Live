package io.github.howard20181.hyperos.fcmlive

import android.content.Intent
import android.content.pm.ResolveInfo
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
 * [hookGreezeBroadcastCache], [hookProcessPolicy] and [hookAwareResourceControl].
 *
 * `Hooker`'s instance capabilities are injected at the call sites, the same way
 * [GmsSupport] and [AutostartGate] receive theirs: `module` is the
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
 * `Hooker`'s allowlist state, and `systemContext` / `onApplyAutostart` feed
 * [AllowlistGate.moduleAppliesTo].
 *
 * The broadcast-gate counters ([broadcastGateAllowedCount] and friends) are
 * read back by `Hooker`'s [Hooker.broadcastGateSummary], which is why they are
 * `internal` here and not private.
 */
internal object GreezeHooks {

    /**
     * P0/P1 broadcast-gate counters (2026-10-02).
     *
     * One-shot booleans alone cannot answer "did the change take effect": a
     * legitimately-never-taken branch looks identical to a broken hook. These
     * counters are printed together once per traffic-probe tick
     * (see `Hooker#broadcastGateSummary`), so a night's log shows the split even
     * when every count is zero.
     */
    @Volatile
    internal var broadcastGateAllowedCount = 0

    /** Subset of [broadcastGateAllowedCount] decided by callerUid, not callerPkg. */
    @Volatile
    internal var broadcastGateAllowedByUidCount = 0

    /** c2dm from GMS with the callee off the allowlist: handed back to the ROM. */
    @Volatile
    internal var broadcastGateSkippedCount = 0

    /** GMS-internal reconnect actions allowed through the same gate. */
    @Volatile
    internal var broadcastGateCnActionCount = 0

    /** c2dm that reached `DomesticPolicyManager#deferBroadcast` unsuppressed. */
    @Volatile
    internal var deferC2dmPassthroughCount = 0

    /** One-shot: first c2dm allow decided by the isAllowBroadcast gate. */
    @Volatile
    private var broadcastGateAllowedLogged = false

    /** One-shot: first c2dm deliberately left to the ROM policy (P0 behaviour). */
    @Volatile
    private var broadcastGateSkippedLogged = false

    /** One-shot: first c2dm arriving at `DomesticPolicyManager#deferBroadcast`. */
    @Volatile
    private var deferC2dmPassthroughLogged = false

    /**
     * Greeze net release: freeze-time network restrictions skipped for checked
     * apps. Not an autostart verdict at all — it rides the greeze hook — so it
     * lives in its own number and its own summary word (`net-released`), never
     * mixing into the autostart gate's `released=` group.
     */
    @Volatile
    internal var greezeNetReleaseCount = 0

    /** Uids whose first net-release already produced a detail line. */
    private val greezeNetReleaseLoggedUids = HashSet<Int>()

    /** One-shot: confirms P0 #1 actually suppressed a network restriction for GMS. */
    @Volatile
    private var gmsRestrictNetLogged = false

    /** One-shot: confirms 3.6 actually kept the UDP packet filter off GMS. */
    @Volatile
    private var gmsUdpFilterLogged = false

    /** One-shot: confirms the ListAppsManager system-blacklist prune actually ran. */
    @Volatile
    private var listAppsBlacklistPruneLogged = false

    /** One-shot: confirms the ListAppsManager use-data whitelist add actually ran. */
    @Volatile
    private var listAppsUseDataAddLogged = false

    @Volatile
    private var noNetworkBlacklistMismatchLogged = false

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
     * - Policy dispatch is Domestic here (`mCurrentCNPolicy: 1`, region CN), so
     *   `InternationalPolicyManager#isPushApp` is dead code on this device, and
     *   `isPushApp == true` means *restrict* that app's network — the opposite of
     *   what the name suggests.
     * - GMS does not enter the freeze path at all: per-uid accounting reads
     *   `uid=10133 frozen=0s` after 15h and `noControl` reports
     *   `no_freeze:invisible`. The network hooks in this file are therefore
     *   defence in depth, not the fix — see [hookDomesticRestrictNet].
     * - The real MIUI network engine is netd's
     *   `OemNetdListener.setMiuiFirewallRule` (0 rules installed at runtime
     *   here). The older `enablemiuistandby enable` standby chain is a dead
     *   letter on this ROM — dnsproxyd answers `500 Command not recognized` — and
     *   is intercepted only as an OTA hedge.
     *
     * A quiet night proves nothing on its own; HOOKS_AND_DIAGNOSTICS.md §6.4
     * fixes the wording for that case ("not observed in this window", never
     * "not needed").
     */
    internal fun hookGreezeManagerService(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        isGmsUid: (Int) -> Boolean,
        forceBooleanArg: (Class<*>, String, Boolean, String, Int) -> Unit,
        systemContext: () -> android.content.Context?,
        onApplyAutostart: () -> Unit,
        findMethod: (Class<*>, String, Array<Class<*>?>) -> Method?
    ) {
        val GreezeManagerServiceClass =
            classLoader.loadClass("com.miui.server.greeze.GreezeManagerService")
        try {
            val isAllowBroadcastMethod = findMethod(
                GreezeManagerServiceClass,
                "isAllowBroadcast",
                arrayOf(
                    Int::class.javaPrimitiveType, String::class.java,
                    Int::class.javaPrimitiveType, String::class.java, String::class.java
                )
            )
            val getPackageNameFromUidMethod = findMethod(
                GreezeManagerServiceClass,
                "getPackageNameFromUid",
                arrayOf(Int::class.javaPrimitiveType)
            )
            getPackageNameFromUidMethod?.isAccessible = true
            if (getPackageNameFromUidMethod == null) {
                module.log(
                    Log.INFO, HookerSupport.TAG,
                    "GreezeManagerService#getPackageNameFromUid absent;" +
                        " isAllowBroadcast falls back to the raw callee argument"
                )
            }
            if (isAllowBroadcastMethod == null) {
                module.log(Log.ERROR, HookerSupport.TAG, "GreezeManagerService#isAllowBroadcast absent, skip")
            } else {
                val uidLookup = getPackageNameFromUidMethod
                hookE(isAllowBroadcastMethod).intercept { chain: XposedInterface.Chain ->
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
                            module.log(Log.ERROR, HookerSupport.TAG, "Failed to get callee package name", e)
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
                        val callerUidIsGms = callerUid is Int && isGmsUid(callerUid)
                        val calleeIsGms = HookerSupport.GMS_PACKAGE_NAME == calleePkgName ||
                            HookerSupport.GMS_PERSISTENT_PROCESS_NAME == calleePkgName
                        if (HookerSupport.ACTION_REMOTE_INTENT == action &&
                            (callerPkgIsGms || callerUidIsGms) &&
                            AllowlistGate.moduleAppliesTo(module, systemContext, onApplyAutostart, calleePkgName)
                        ) {
                            broadcastGateAllowedCount++
                            if (!callerPkgIsGms) {
                                broadcastGateAllowedByUidCount++
                            }
                            if (!broadcastGateAllowedLogged) {
                                broadcastGateAllowedLogged = true
                                module.log(
                                    Log.INFO, HookerSupport.TAG,
                                    "isAllowBroadcast: c2dm allowed for callee=$calleePkgName " +
                                        "(callerUid=$callerUid, callerPkg=$callerPkg, " +
                                        "matchedBy=${if (callerPkgIsGms) "callerPkg" else "callerUid"})"
                                )
                            }
                            return@intercept true
                        }
                        if (calleeIsGms && HookerSupport.CN_DEFER_BROADCAST.contains(action)) {
                            broadcastGateCnActionCount++
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
                            broadcastGateSkippedCount++
                            if (!broadcastGateSkippedLogged) {
                                broadcastGateSkippedLogged = true
                                module.log(
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
                module.log(
                    Log.INFO, HookerSupport.TAG,
                    "P1: isAllowBroadcast hooked; caller-uid fallback armed (arg0=callerUid)"
                )
            }
        } catch (e: Exception) {
            module.log(Log.ERROR, HookerSupport.TAG, "Failed to hook GreezeManagerService#isAllowBroadcast", e)
        }
        try {
            val deferBroadcastForMiuiMethod = GreezeManagerServiceClass.getDeclaredMethod(
                "deferBroadcastForMiui", String::class.java
            )
            hookE(deferBroadcastForMiuiMethod).intercept { chain: XposedInterface.Chain ->
                if ((chain.getArg(0) as? String)?.let { HookerSupport.CN_DEFER_BROADCAST.contains(it) } == true) {
                    return@intercept false
                }
                chain.proceed()
            }
            module.deoptimize(deferBroadcastForMiuiMethod)
        } catch (e: Exception) {
            module.log(Log.ERROR, HookerSupport.TAG, "Failed to hook GreezeManagerService#deferBroadcastForMiui", e)
        }
        // ponytail: mGmsLimitEnabled is cleared on every call, never once at
        //   install. It is pure runtime state — constructor-initialised to true,
        //   and the dump command is its only writer — so every system_server
        //   restart resets it and a one-shot clean-up would be silently undone
        //   (independently corroborated by hyperos-fcm-fix's greeze notes).
        //   Cost: one Unsafe write per call. Condition to drop it: proof on this
        //   generation that the field is never read after boot.
        val triggerGMSLimitActionMethod: Method
        try {
            triggerGMSLimitActionMethod = try {
                GreezeManagerServiceClass.getDeclaredMethod(
                    "triggerGMSLimitAction", Boolean::class.javaPrimitiveType
                )
            } catch (ignored: NoSuchMethodException) {
                GreezeManagerServiceClass.getDeclaredMethod("triggerGMSLimitAction")
            }
            hookE(triggerGMSLimitActionMethod).intercept { chain: XposedInterface.Chain ->
                if (chain.args.isNotEmpty()) {
                    val args = chain.args.toTypedArray()
                    args[0] = false
                    return@intercept chain.proceed(args)
                }
                try {
                    val mGmsLimitEnabled =
                        GreezeManagerServiceClass.getDeclaredField("mGmsLimitEnabled")
                    UnsafeUtils.setBooleanField(mGmsLimitEnabled, chain.thisObject, false)
                } catch (t: Throwable) {
                    module.log(Log.ERROR, HookerSupport.TAG, "Failed to clear mGmsLimitEnabled", t)
                }
                chain.proceed()
            }
            module.deoptimize(triggerGMSLimitActionMethod)
        } catch (e: Throwable) {
            module.log(Log.ERROR, HookerSupport.TAG, "Failed to hook GreezeManagerService#triggerGMSLimitAction", e)
        }
        forceBooleanArg(
            GreezeManagerServiceClass,
            "updateGmsNetStatus",
            false,
            "GreezeManagerService",
            Log.INFO
        )
    }

    internal fun hookDomesticPolicyManager(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        isGmsUid: (Int) -> Boolean,
        netReleaseEnabled: () -> Boolean,
        allowlistPackageForUid: (Int) -> String?
    ) {
        val DomesticPolicyManagerClass =
            classLoader.loadClass("com.miui.server.greeze.DomesticPolicyManager")
        val deferBroadcastMethod = DomesticPolicyManagerClass.getDeclaredMethod(
            "deferBroadcast", String::class.java
        )
        hookE(deferBroadcastMethod).intercept { chain: XposedInterface.Chain ->
            // P0 (2026-10-02): the c2dm branch that used to sit here is gone.
            //
            // It rested on the belief that c2dm never reaches this method on a CN
            // build ("structural dead end"). Runtime evidence disproves it: the
            // module's own sentinel fired at 2026-10-02T07:55:54. The disassembly
            // agrees, and the exact chain in
            // `GreezeManagerService#isAllowBroadcast` is:
            //
            //   009a  PolicyManager.isCnModel()
            //   009e  if-nez v0, 00bc   ; isCnModel() != 0 (CN) => branch to 00bc,
            //                          ; skipping the whole short-circuit block
            //   00a0  InternationalPolicyManager.enableNewStrategy()
            //   00a4  if-eqz v0, 00bc   ; false => also branch to 00bc
            //   00a6..00bb             ; only non-CN + new strategy: answers true
            //                          ; (allowed) for isAutoStartRestrict or c2dm
            //   00d9  deferBroadcastForMiui(action)
            //
            // So c2dm is short-circuited at 00bb on **non-CN builds only**; on a CN
            // build it always falls through to 00d9. enableNewStrategy() is just
            // `sget-boolean InternationalPolicyManager.mNewController`, and
            // isCnModel() is `PolicyManagerConfig.sCnModel`. This device reports
            // region CN and `dumpsys greezer` shows mCurrentCNPolicy=1, so the
            // 009a/009e pair alone settles the branch — which is why the runtime
            // sample and the static derivation agree. Note that
            // `persist.sys.greeze.oversea` plays no part here: it is folded into
            // mNewController, and mNewController is read only *after* the CN test.
            //
            // deferBroadcastForMiui then needs `mMiuiDeferBroadcast` (this ROM:
            // android.intent.action.BATTERY_CHANGED only) to miss, and its
            // `000c if-nez mScreenOn, 001b` shows `PolicyManager#deferBroadcast`
            // is reached only while the screen is ON — so the sentinel sample
            // implies the screen was on at 07:55:54.
            //
            // This signature carries the action and nothing else, so the old
            // branch exempted c2dm for *every* caller — including deliveries to
            // apps the user never selected, which quietly defeated the allowlist.
            // The decision now belongs solely to
            // `GreezeManagerService#isAllowBroadcast`, which sees callerUid,
            // callerPkgName, calleeUid and calleePkgName at once. Here only the
            // GMS-internal reconnect actions stay exempt.
            val action = chain.getArg(0) as? String
            if (action != null && HookerSupport.CN_DEFER_BROADCAST.contains(action)) {
                return@intercept false
            }
            if (HookerSupport.ACTION_REMOTE_INTENT == action) {
                // Success-path counter for the tightened behaviour: a c2dm
                // broadcast that the isAllowBroadcast gate did not consume is
                // genuinely handed back to the ROM's deferral policy. Zero here
                // is not a failure — it means every c2dm was already allowed
                // upstream (every callee involved was on the list).
                deferC2dmPassthroughCount++
                if (!deferC2dmPassthroughLogged) {
                    deferC2dmPassthroughLogged = true
                    module.log(
                        Log.INFO, HookerSupport.TAG,
                        "deferBroadcast: c2dm reached the ROM policy " +
                            "(P0: no longer bypassed here)"
                    )
                }
            }
            chain.proceed()
        }
        module.deoptimize(deferBroadcastMethod)
        module.log(
            Log.INFO, HookerSupport.TAG,
            "P0: DomesticPolicyManager#deferBroadcast hooked; c2dm bypass removed, " +
                "CN reconnect actions only"
        )
        hookDomesticRestrictNet(
            module, DomesticPolicyManagerClass, hookE, logSkip,
            isGmsUid, netReleaseEnabled, allowlistPackageForUid
        )
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
     * Currently inert: greezer history shows GMS never enters the freeze path on
     * this device (E7-3 closed, negative). Shipped as defence: if a future ROM or
     * state freezes GMS, this keeps the network restriction off.
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
        module: XposedInterface,
        domesticPolicyManagerClass: Class<*>,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        isGmsUid: (Int) -> Boolean,
        netReleaseEnabled: () -> Boolean,
        allowlistPackageForUid: (Int) -> String?
    ) {
        try {
            val isRestrictNetMethod = domesticPolicyManagerClass.getDeclaredMethod(
                "isRestrictNet", Int::class.javaPrimitiveType
            )
            hookE(isRestrictNetMethod).intercept { chain: XposedInterface.Chain ->
                val uid = chain.getArg(0)
                if (uid is Int && isGmsUid(uid)) {
                    // Dedicated one-shot: `restrictNetMatchLogged` belongs to the
                    // isPushApp stack-walk branch and must not be consumed here.
                    if (!gmsRestrictNetLogged) {
                        gmsRestrictNetLogged = true
                        module.log(
                            Log.INFO, HookerSupport.TAG,
                            "DomesticPolicyManager#isRestrictNet: kept GMS (uid $uid) unrestricted"
                        )
                    }
                    return@intercept false
                }
                if (uid is Int && netReleaseEnabled()) {
                    val checkedPkg = allowlistPackageForUid(uid)
                    if (checkedPkg != null) {
                        greezeNetReleaseCount++
                        // Detail budget: one line per uid, ever — the summary
                        // tick carries the volume, so repeating the same names
                        // each freeze would only spend log bytes.
                        synchronized(greezeNetReleaseLoggedUids) {
                            if (greezeNetReleaseLoggedUids.add(uid)) {
                                module.log(
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
            module.log(
                Log.INFO, HookerSupport.TAG,
                "DomesticPolicyManager#isRestrictNet hooked for GMS; " +
                    "checked apps follow greeze_net_release"
            )
        } catch (e: NoSuchMethodException) {
            logSkip("DomesticPolicyManager#isRestrictNet absent, skip")
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
     * Still inert today: E7-3 shows GMS never enters the freeze path, so this never
     * fires in normal use. Shipped as defence, and no benefit is claimed.
     */
    internal fun hookUdpPackageRestrict(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        isGmsUid: (Int) -> Boolean
    ) {
        try {
            val greezeManagerServiceClass =
                classLoader.loadClass("com.miui.server.greeze.GreezeManagerService")
            val udpPackageRestrictMethod = greezeManagerServiceClass.getDeclaredMethod(
                "udpPackageRestrict",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            udpPackageRestrictMethod.isAccessible = true
            hookE(udpPackageRestrictMethod).intercept { chain: XposedInterface.Chain ->
                val uid = chain.getArg(0)
                val allow = chain.getArg(1) == true
                if (uid is Int && allow && isGmsUid(uid)) {
                    if (!gmsUdpFilterLogged) {
                        gmsUdpFilterLogged = true
                        module.log(
                            Log.INFO, HookerSupport.TAG,
                            "udpPackageRestrict: skipped UDP filter for GMS (uid $uid)"
                        )
                    }
                    return@intercept null
                }
                chain.proceed()
            }
            module.deoptimize(udpPackageRestrictMethod)
            module.log(Log.INFO, HookerSupport.TAG, "GreezeManagerService#udpPackageRestrict hooked for GMS")
        } catch (e: ClassNotFoundException) {
            logSkip("GreezeManagerService absent, udpPackageRestrict skip")
        } catch (e: NoSuchMethodException) {
            logSkip("GreezeManagerService#udpPackageRestrict absent, skip")
        }
    }

    internal fun hookListAppsManager(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder
    ) {
        val ListAppsManagerClass =
            classLoader.loadClass("com.miui.server.greeze.power.ListAppsManager")
        var mSystemBlackListField: Field? = null
        try {
            mSystemBlackListField = ListAppsManagerClass.getDeclaredField("mSystemBlackList")
        } catch (e: NoSuchFieldException) {
            try {
                mSystemBlackListField = ListAppsManagerClass.getDeclaredField("SYSTEM_BLACK_LIST")
            } catch (ex: NoSuchFieldException) {
                module.log(
                    Log.ERROR, HookerSupport.TAG,
                    "Failed to find ListAppsManager.mSystemBlackList or ListAppsManager.SYSTEM_BLACK_LIST",
                    e
                )
            }
        }
        if (mSystemBlackListField != null) {
            mSystemBlackListField.isAccessible = true
            val constructors = ListAppsManagerClass.declaredConstructors
            for (constructor in constructors) {
                val field = mSystemBlackListField
                hookE(constructor).intercept { chain: XposedInterface.Chain ->
                    try {
                        chain.proceed()
                    } finally {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val mSystemBlackList =
                                field.get(chain.thisObject) as MutableList<String>?
                            // One-shot confirmation: this constructor fires during
                            // system_server startup and used to mutate silently, so
                            // "hook installed" and "mutation actually happened" were
                            // indistinguishable in the logs.
                            val removed = mSystemBlackList?.remove(HookerSupport.GMS_PACKAGE_NAME)
                            if (removed != null && !listAppsBlacklistPruneLogged) {
                                listAppsBlacklistPruneLogged = true
                                module.log(
                                    Log.INFO, HookerSupport.TAG,
                                    "ListAppsManager: pruned GMS from system blacklist " +
                                        "(was present=$removed)"
                                )
                            }
                        } catch (e: Exception) {
                            module.log(Log.ERROR, HookerSupport.TAG, "Failed to modify system blacklist", e)
                        }
                    }
                }
                module.deoptimize(constructor)
            }
        }
        try {
            val isInWhiteListMethod = ListAppsManagerClass.getDeclaredMethod(
                "isInWhiteList", String::class.java
            )
            var mUseDataWhiteListField: Field? = null
            try {
                mUseDataWhiteListField = ListAppsManagerClass.getDeclaredField("mUseDataWhiteList")
            } catch (e: NoSuchFieldException) {
                try {
                    mUseDataWhiteListField =
                        ListAppsManagerClass.getDeclaredField("USE_DATA_WHITE_LIST")
                } catch (ex: NoSuchFieldException) {
                    module.log(
                        Log.ERROR, HookerSupport.TAG,
                        "Failed to find ListAppsManager.mUseDataWhiteList or ListAppsManager.USE_DATA_WHITE_LIST",
                        e
                    )
                }
            }
            if (mUseDataWhiteListField != null) {
                mUseDataWhiteListField.isAccessible = true
                val field = mUseDataWhiteListField
                hookE(isInWhiteListMethod).intercept { chain: XposedInterface.Chain ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val mUseDataWhiteList =
                            field.get(chain.thisObject) as MutableSet<String>?
                        val added = mUseDataWhiteList?.add(HookerSupport.GMS_PACKAGE_NAME)
                        // isInWhiteList is a hot query path: confirm the mutation
                        // once, then stay silent — the fixed per-line log cost
                        // dwarfs the message body, and this hook runs on every
                        // use-data query for every package.
                        if (added != null && !listAppsUseDataAddLogged) {
                            listAppsUseDataAddLogged = true
                            module.log(
                                Log.INFO, HookerSupport.TAG,
                                "ListAppsManager: added GMS to use-data whitelist " +
                                    "(was absent=$added)"
                            )
                        }
                    } catch (e: Exception) {
                        module.log(Log.ERROR, HookerSupport.TAG, "Failed to modify use data whitelist", e)
                    }
                    chain.proceed()
                }
            }
        } catch (e: NoSuchMethodException) {
            module.log(Log.ERROR, HookerSupport.TAG, "Failed to hook ListAppsManager#isInWhiteList", e)
        }
    }

    internal fun hookBroadcastQueueModernStubImpl(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        systemContext: () -> android.content.Context?,
        onApplyAutostart: () -> Unit
    ) {
        val BroadcastQueueModernStubImplClass =
            classLoader.loadClass("com.android.server.am.BroadcastQueueModernStubImpl")
        val BroadcastQueueClass = classLoader.loadClass("com.android.server.am.BroadcastQueue")
        val BroadcastRecordClass = classLoader.loadClass("com.android.server.am.BroadcastRecord")
        val callerPackageField = BroadcastRecordClass.getDeclaredField("callerPackage")
        callerPackageField.isAccessible = true
        val intentField = BroadcastRecordClass.getDeclaredField("intent")
        intentField.isAccessible = true
        val checkApplicationAutoStartMethod = BroadcastQueueModernStubImplClass.getDeclaredMethod(
            "checkApplicationAutoStart",
            BroadcastQueueClass,
            BroadcastRecordClass,
            ResolveInfo::class.java
        )
        hookE(checkApplicationAutoStartMethod).intercept { chain: XposedInterface.Chain ->
            try {
                val broadcastRecord = chain.getArg(1)
                val callerPackage = callerPackageField.get(broadcastRecord) as? String
                val intent = intentField.get(broadcastRecord) as? Intent
                val targetPackage = intent?.let { HookerSupport.targetPackageOf(it) }
                if (callerPackage != null &&
                    HookerSupport.GMS_PACKAGE_NAME == callerPackage &&
                    intent != null &&
                    HookerSupport.ACTION_REMOTE_INTENT == intent.action &&
                    targetPackage != null &&
                    AllowlistGate.moduleAppliesTo(module, systemContext, onApplyAutostart, targetPackage)
                ) {
                    return@intercept true
                }
                // The branch above is the only autostart gate this module
                // answers. A second branch used to drop its caller restriction
                // and answer any push broadcast from the intent alone, the way
                // the two reference modules do; it is gone because it never ran
                // on anything the shipped GMS→c2dm hop had not answered already
                // — real FCM calls always come from GMS, so `callerPackage` is
                // GMS on every broadcast the module cares about, and with all
                // four experiment switches off push still arrived.
            } catch (e: Exception) {
                module.log(
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
            hookE(isRestrictReceiverMethod).intercept { chain: XposedInterface.Chain ->
                try {
                    val intent = chain.getArg(0) as? Intent
                    val callerPackage = chain.getArg(2) as? String
                    val calleeUid = chain.getArg(3) as Int
                    val calleePackage = chain.getArg(4) as? String
                    if (HookerSupport.GMS_PACKAGE_NAME == callerPackage &&
                        intent != null &&
                        HookerSupport.ACTION_REMOTE_INTENT == intent.action &&
                        AllowlistGate.moduleAppliesTo(module, systemContext, onApplyAutostart, calleePackage)
                    ) {
                        // Same reason string and caller uid the native pass-through
                        // path uses, so greeze bookkeeping stays consistent.
                        thawUidAsyncMethod.invoke(chain.thisObject, calleeUid, 1000, "bc_action")
                        return@intercept false
                    }
                } catch (e: Exception) {
                    module.log(
                        Log.ERROR, HookerSupport.TAG,
                        "Failed to modify GreezeManagerService#isRestrictReceiver", e
                    )
                }
                chain.proceed()
            }
            module.deoptimize(isRestrictReceiverMethod)
        } catch (e: NoSuchMethodException) {
            logSkip("GreezeManagerService#isRestrictReceiver absent, skip")
        } catch (e: ClassNotFoundException) {
            logSkip("GreezeManagerService absent, isRestrictReceiver not hooked")
        }
    }

    /**
     * Stops greeze from parking a c2dm broadcast instead of delivering it.
     *
     * GreezeManagerService#isNeedCachedBroadcast(Intent, int uid, String pkg) runs after
     * the receiver has been found frozen and returns true to mean "cache this broadcast
     * and replay it once the target thaws". That is the mechanism behind a broadcast
     * showing up as delivered in the AMS log while the app stays silent until the next
     * unlock. Answering false for c2dm keeps the normal delivery path.
     *
     * The uid argument is deliberately unused: it identifies the frozen receiver, and the
     * allowlist the user configured is expressed in package names.
     */
    internal fun hookGreezeBroadcastCache(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        systemContext: () -> android.content.Context?,
        onApplyAutostart: () -> Unit
    ) {
        try {
            val GreezeManagerServiceClass =
                classLoader.loadClass("com.miui.server.greeze.GreezeManagerService")
            val isNeedCachedBroadcastMethod = GreezeManagerServiceClass.getDeclaredMethod(
                "isNeedCachedBroadcast",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            hookE(isNeedCachedBroadcastMethod).intercept { chain: XposedInterface.Chain ->
                try {
                    val intent = chain.getArg(0) as? Intent
                    val packageName = chain.getArg(2) as? String
                    if (intent != null &&
                        HookerSupport.ACTION_REMOTE_INTENT == intent.action &&
                        AllowlistGate.moduleAppliesTo(module, systemContext, onApplyAutostart, packageName)
                    ) {
                        return@intercept false
                    }
                } catch (e: Exception) {
                    module.log(
                        Log.ERROR, HookerSupport.TAG,
                        "Failed to modify GreezeManagerService#isNeedCachedBroadcast", e
                    )
                }
                chain.proceed()
            }
            module.deoptimize(isNeedCachedBroadcastMethod)
        } catch (e: ClassNotFoundException) {
            logSkip("GreezeManagerService absent, broadcast cache not hooked")
        } catch (e: NoSuchMethodException) {
            logSkip("GreezeManagerService#isNeedCachedBroadcast absent, skip")
        }
    }

    // ponytail: GMS is only ever APPENDED to the returned list, never removed
    //   from a ROM-side list. Comparable external modules remove GMS from
    //   `whiteApps` and from Millet's black/white lists on the assumption that
    //   they are restriction lists; that polarity has never been confirmed, and
    //   if any of them is an allow-list the removal tightens instead of
    //   loosening. Cost: the entry is rebuilt per query instead of persisted.
    //   Condition to revisit: bytecode or runtime proof of a list's polarity on
    //   this generation — and even then, prefer appending over removing.
    internal fun hookProcessPolicy(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder
    ) {
        val ProcessPolicyClass = classLoader.loadClass("com.android.server.am.ProcessPolicy")
        val getWhiteListMethod = ProcessPolicyClass.getDeclaredMethod(
            "getWhiteList", Int::class.javaPrimitiveType
        )
        hookE(getWhiteListMethod).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            try {
                val flags = chain.getArg(0)
                if (flags is Int && (flags and 1) != 0 && result is List<*>) {
                    val source = result
                    val whiteList = ArrayList<Any?>(source)
                    addIfAbsent(whiteList, HookerSupport.GMS_PACKAGE_NAME)
                    addIfAbsent(whiteList, HookerSupport.GMS_PERSISTENT_PROCESS_NAME)
                    HookerSupport.addIfAbsentInPlace(source, HookerSupport.GMS_PACKAGE_NAME)
                    HookerSupport.addIfAbsentInPlace(source, HookerSupport.GMS_PERSISTENT_PROCESS_NAME)
                    return@intercept whiteList
                }
            } catch (t: Throwable) {
                module.log(Log.ERROR, HookerSupport.TAG, "Failed to extend ProcessPolicy white list", t)
            }
            result
        }
    }

    private fun addIfAbsent(list: MutableList<Any?>, value: String) {
        if (!list.contains(value)) {
            list.add(value)
        }
    }

    internal fun hookAwareResourceControl(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        gmsUid: () -> Int?
    ) {
        val AwareResourceControlClass =
            classLoader.loadClass("com.miui.server.greeze.power.AwareResourceControl")
        val mNoNetworkBlackUidsField =
            AwareResourceControlClass.getDeclaredField("mNoNetworkBlackUids")
        mNoNetworkBlackUidsField.isAccessible = true
        for (constructor in AwareResourceControlClass.declaredConstructors) {
            hookE(constructor).intercept { chain: XposedInterface.Chain ->
                try {
                    chain.proceed()
                } finally {
                    try {
                        pruneGmsFromNoNetworkBlacklist(module, mNoNetworkBlackUidsField, chain.thisObject, gmsUid)
                    } catch (t: Throwable) {
                        module.log(
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
        awareResourceControl: Any,
        gmsUid: () -> Int?
    ) {
        val raw = blacklistField.get(awareResourceControl)
        if (raw !is Collection<*>) {
            return
        }
        @Suppress("UNCHECKED_CAST")
        val blacklist = raw as MutableCollection<Any?>
        val removedByName = blacklist.remove(HookerSupport.GMS_PACKAGE_NAME)
        val uid = gmsUid()
        val removedByUid = uid != null && blacklist.remove(uid)
        if (removedByUid || removedByName) {
            module.log(
                Log.INFO, HookerSupport.TAG, "Removed GMS from NoNetworkBlackUids (by " +
                    (if (removedByUid) "uid $uid" else "package name") + ")"
            )
        } else if (!noNetworkBlacklistMismatchLogged) {
            noNetworkBlacklistMismatchLogged = true
            module.log(
                Log.INFO, HookerSupport.TAG, "NoNetworkBlackUids (size=" + blacklist.size +
                    ") matched neither the GMS package name nor its uid" +
                    (if (uid == null) " (uid not resolvable yet)" else "")
            )
        }
    }
}
