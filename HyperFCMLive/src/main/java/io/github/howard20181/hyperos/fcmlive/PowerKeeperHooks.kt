package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * The PowerKeeper hooks, extracted verbatim from `Hooker` (zero-behaviour
 * refactor): [hookAppStandbyUidState] (GMS allow re-assert),
 * [hookNoRestrictList] (P1: keep GMS in `MILLET_NO_RESTRICT_APP`) and
 * [hookWechatDozeKeepout] (the default-off WeChat doze experiment).
 *
 * `Hooker`'s instance capabilities are injected at the call sites, the same
 * way [GreezeHooks] and [AlarmGate] receive theirs: `module` is the
 * [XposedInterface] the module entry object is (`log` / `deoptimize` /
 * `getRemotePreferences`), `hookE` is `Hooker#hookE` (the counted,
 * `setId`-deduplicated hook builder — its `hooksInstalled` counter feeds the
 * end-of-install summary that stays with `Hooker`), `logSkip` is the
 * absent-target report (the counter stays with `Hooker`), `isGmsUid` is
 * `Hooker`'s shared GMS uid gate (cachedGmsAppId stays with `Hooker`), and
 * `systemContext` is `Hooker#getSystemContext` (the
 * [GmsSupport] userTable writers consult it).
 */
internal object PowerKeeperHooks {

    /** Whitelist writes this process has seen WeChat dropped from (doze-keepout). */
    @Volatile
    private var wechatDozeKeepoutCount = 0L

    /**
     * Keep GMS out of PowerKeeper's per-uid network restriction set.
     *
     * AppStandbyController.setUidState(int uid, boolean allow) is where the
     * per-uid decision is made. The second parameter really is named "allow"
     * — the method prints "setUidState, uid = %d allow = %b" itself. Its body
     * stores the value into mUidState and then drives DeviceIdlePolicyHelper,
     * so this single call is the convergence point for standby restriction.
     * The downstream helper method is obfuscated and its name differs per
     * ROM generation — OS3 calls s:(IZ)V, OS4 calls r:(IZ)V (both classes also
     * carry the other letter with a different signature). It has exactly one
     * call site in either generation, so no second in-process path can
     * restrict GMS behind setUidState's back. Do not hard-code the letter:
     * only setUidState itself is hooked, and its (IZ)V signature is stable.
     *
     * Do not locate it by name: PowerKeeper declares six methods called
     * setUidState, and the (IZ)V signature alone exists in four classes —
     * AppStandbyController, DeviceIdleController, KillProcessController and
     * SensorController. Only AppStandbyController's feeds the standby network
     * state; the other three drive the doze temporary allowlist, process
     * killing and sensor policy, and a fifth same-named method on
     * AppClusterController moves cluster membership. Match on class plus
     * signature, never on the bare name. The standby chain was walked through
     * the whole dex (OS4, miui-services) and is single-call-site at every
     * level: AppStandbyController#setUidState → DeviceIdlePolicyHelper.r →
     * q → IUsageStatsManager.setAppInactive(pkg, !allow, userId).
     *
     * Only the argument is rewritten. Do not pre-seed mUidState to true: when
     * the incoming value equals the cached one the method returns early, so a
     * true cache would suppress the recovery path instead of triggering it.
     * Forcing allow=true lets the method converge: a restriction attempt sees
     * allow(true) differ from the cached false, then writes true and lifts the
     * restriction. If the divergence ever needs fixing, the safe direction is
     * to force the cache to false, never true — false guarantees the branch
     * actually executes.
     *
     * Not a fix by itself: calling setUidState(gmsUid, true) from outside does
     * not bypass the early return — the early return lives inside that same
     * method, and a cache of true makes the call a no-op, which is exactly the
     * divergent case it would be meant to repair. It also cannot converge the
     * cache with reality: mUidState only consults external state on the first
     * seeding (getUidState), afterwards it is write-only. The only working
     * form is the pair — force the cache to false, then invoke setUidState
     * (uid, true) so the body runs end to end. Because that body also fires
     * sendConnectivityActionToApp(uid), doing it on a timer means waking GMS
     * repeatedly; if it is ever added, drive it from events (module load,
     * screen-on, the pre-flight we already run before delivering a broadcast)
     * with a long minimum interval, never from a periodic tick.
     *
     * That divergence stays unimplemented on purpose: every observable signal
     * on the test device says it is not happening (dumpsys netpolicy shows
     * UID 10133 as policy=4 ALLOW_METERED_BACKGROUND with background
     * restriction off, and GMS is present in all three DeviceIdle whitelist
     * sections), so adding reflexive cache writes would be speculative risk.
     * Four root-free checks decide it, and they are the whole evidence base —
     * re-run them before claiming the gap has closed or opened:
     * am get-standby-bucket com.google.android.gms (5 = ACTIVE),
     * dumpsys netpolicy (policy=4 for UID 10133),
     * dumpsys greezer (frozen=0s for uid 10133),
     * dumpsys deviceidle whitelist (GMS in user, system and system-excidle).
     *
     * Known residual gap: if GMS gets restricted out-of-band (never through
     * setUidState) while mUidState still reads true, even an allow=true call
     * short-circuits and nothing lifts the block. P4 recovery does not cover
     * it — it is an outbound "please reconnect" nudge to GMS/GSF, not a lift of
     * a uid restriction, and it only fires on sleep-mode exit or a
     * MILLET_NO_RESTRICT_APP repair, neither of which recurs on its own.
     */
    internal fun hookAppStandbyUidState(
        module: XposedInterface,
        packageName: String,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        isGmsUid: (Int) -> Boolean
    ) {
        try {
            val appStandbyControllerClass =
                classLoader.loadClass("com.miui.powerkeeper.controller.AppStandbyController")
            try {
                val setUidStateMethod = appStandbyControllerClass.getDeclaredMethod(
                    "setUidState",
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                )
                hookE(setUidStateMethod).intercept { chain: XposedInterface.Chain ->
                    val args = chain.args.toTypedArray()
                    if (args.size > 1 && args[0] is Int) {
                        val uid = args[0] as Int
                        if (isGmsUid(uid) && java.lang.Boolean.TRUE != args[1]) {
                            args[1] = true
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "AppStandbyController#setUidState: kept GMS (uid $uid) allowed"
                            )
                        }
                    }
                    chain.proceed(args)
                }
                module.deoptimize(setUidStateMethod)
                // Tag the log with pkg/userId: this line repeats once per
                // package-ready pass (every hot reload re-runs it), so a raw
                // count reads like several hooks when setId() has in fact
                // collapsed them into a single live one.
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "AppStandbyController#setUidState hooked for GMS allow re-assert" +
                        " (pkg=$packageName, userId=${android.os.Process.myUid() / 100000})"
                )
            } catch (e: NoSuchMethodException) {
                logSkip("AppStandbyController#setUidState absent, skip")
            }
        } catch (e: ClassNotFoundException) {
            logSkip("AppStandbyController class absent, skip")
        }
    }

    /**
     * P1: keep GMS in Settings.System.MILLET_NO_RESTRICT_APP.
     *
     * PowerKeeper generates that setting from userTable rows whose literal
     * bgControl equals "noRestrict". GMS is stuck at "miuiAuto" (scenario 0)
     * because the policy UI hides the selector for packages without a launcher
     * icon, so dealNoRestrictApp() never includes it. Greezer's
     * mNoRestrictAppSet is the shared filter for both the Aurogon quick-freeze
     * path and PowerStrategyMode (tobg / from system); without GMS in the set
     * the UID gets frozen even when mGmsLimitEnabled is false.
     *
     * Hooking inside PowerKeeper removes the race that Shizuku watchdogs have:
     * every regeneration of the projection includes GMS at the source.
     */
    // ponytail: GMS is added to the *query result*, never written back to
    //   Settings or to PowerKeeper's own table. The three non-injecting external
    //   projects all write `MILLET_NO_RESTRICT_APP` / `aurogon_enable` instead,
    //   and therefore have to poll forever against PowerKeeper regenerating the
    //   setting; rebuilding the answer at query time is immune to that race by
    //   construction. Cost: no effect outside the code path that asks this
    //   helper. Condition to change: a generation that reads the list without
    //   going through UserConfigureHelper — and even then, another query-time
    //   hook beats a persisted write, which would outlive the module.
    internal fun hookNoRestrictList(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        systemContext: () -> Context?
    ) {
        // Source-level: ensure getNoRestrictApps() always returns GMS.
        try {
            val userConfigureHelperClass =
                classLoader.loadClass("com.miui.powerkeeper.provider.UserConfigureHelper")
            val getNoRestrictAppsMethod = userConfigureHelperClass.getDeclaredMethod(
                "getNoRestrictApps", Context::class.java
            )
            hookE(getNoRestrictAppsMethod).intercept { chain: XposedInterface.Chain ->
                val result = chain.proceed()
                try {
                    if (result is MutableList<*>) {
                        @Suppress("UNCHECKED_CAST")
                        addIfAbsent(result as MutableList<Any?>, HookerSupport.GMS_PACKAGE_NAME)
                    } else if (result is List<*>) {
                        val copy = ArrayList<Any?>(result)
                        addIfAbsent(copy, HookerSupport.GMS_PACKAGE_NAME)
                        return@intercept copy
                    }
                } catch (t: Throwable) {
                    module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to extend getNoRestrictApps", t)
                }
                try {
                    GmsSupport.ensureGmsUserTableBgControl(module, systemContext)
                } catch (t: Throwable) {
                    module.logGated(Log.WARN, HookerSupport.TAG, "Failed to write back userTable.bgControl", t)
                }
                result
            }
            module.deoptimize(getNoRestrictAppsMethod)
        } catch (e: NoSuchMethodException) {
            logSkip("UserConfigureHelper#getNoRestrictApps absent, skip")
        } catch (e: ClassNotFoundException) {
            logSkip("UserConfigureHelper class absent, skip")
        }

        // Any user-config writer can put GMS back to miuiAuto; force and re-assert.
        try {
            val writerClass =
                classLoader.loadClass("com.miui.powerkeeper.provider.UserConfigureHelper")
            // One line for the whole set, not one per method: these are N methods
            // of a single class answering the same question ("which writer can put
            // GMS back to miuiAuto"), and each line pays the ~161 B LSPosed prefix.
            // An install check reads the hooked *set*, not which line installed
            // which method.
            val reAssertHooked = ArrayList<String>()
            for (method in writerClass.declaredMethods) {
                val name = method.name
                val looksWriter =
                    name.contains("update", ignoreCase = true) ||
                        name.contains("save", ignoreCase = true) ||
                        name.contains("insert", ignoreCase = true) ||
                        name.contains("modify", ignoreCase = true) ||
                        name.contains("setBg", ignoreCase = true)
                if (!looksWriter || name.contains("get", ignoreCase = true)) continue
                val isBgControlSetter = name.contains("setBgControl", ignoreCase = true)
                hookE(method).intercept { chain: XposedInterface.Chain ->
                    val rawArgs = chain.args
                    var args: Array<Any?>? = null
                    if (isBgControlSetter) {
                        val copy = rawArgs.toTypedArray()
                        val touchesGms = copy.any { it == HookerSupport.GMS_PACKAGE_NAME }
                        if (touchesGms) {
                            for (i in copy.indices) {
                                val a = copy[i]
                                if (a is String && a != HookerSupport.GMS_PACKAGE_NAME && a != HookerSupport.COL_BG_CONTROL) {
                                    copy[i] = HookerSupport.BG_CONTROL_NO_RESTRICT
                                    module.logGated(
                                        Log.INFO, HookerSupport.TAG,
                                        "setBgControl: forced GMS control $a -> ${HookerSupport.BG_CONTROL_NO_RESTRICT}"
                                    )
                                }
                            }
                            args = copy
                        }
                    }
                    val result = if (args != null) chain.proceed(args) else chain.proceed()
                    try {
                        GmsSupport.ensureGmsUserTableBgControl(module, systemContext)
                    } catch (t: Throwable) {
                        module.logGated(Log.WARN, HookerSupport.TAG, "userTable re-assert after ${method.name} failed", t)
                    }
                    result
                }
                module.deoptimize(method)
                reAssertHooked.add(method.name)
            }
            if (reAssertHooked.isNotEmpty()) {
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "UserConfigureHelper#${reAssertHooked.joinToString("/")} " +
                        "hooked for userTable re-assert"
                )
            }
        } catch (e: ClassNotFoundException) {
            // Already reported above when getNoRestrictApps was missing.
        }

        // Belt-and-suspenders: after dealNoRestrictApp() writes the projection,
        // verify GMS is present and repair if a path bypassed getNoRestrictApps.
        try {
            val activeStateControllerClass =
                classLoader.loadClass("com.miui.powerkeeper.controller.ActiveStateController")
            val dealNoRestrictAppMethod = activeStateControllerClass.getDeclaredMethod(
                "dealNoRestrictApp"
            )
            hookE(dealNoRestrictAppMethod).intercept { chain: XposedInterface.Chain ->
                chain.proceed()
                try {
                    GmsSupport.ensureGmsInMilletSetting(module, systemContext)
                } catch (t: Throwable) {
                    module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to repair MILLET_NO_RESTRICT_APP", t)
                }
            }
            module.deoptimize(dealNoRestrictAppMethod)
        } catch (e: NoSuchMethodException) {
            logSkip("ActiveStateController#dealNoRestrictApp absent, skip")
        } catch (e: ClassNotFoundException) {
            logSkip("ActiveStateController class absent, skip")
        }
    }

    private fun addIfAbsent(list: MutableList<Any?>, value: String) {
        if (!list.contains(value)) {
            list.add(value)
        }
    }

    /**
     * Experiment (default off): keep WeChat out of the AOSP battery-optimization
     * whitelist — the Doze "user" section Settings shows as 未优化, persisted
     * to /data/system/deviceidle.xml.
     *
     * ROM forensics (OS4 V816): `com.miui.powerkeeper.controller
     * .DeviceIdleController$1` hardcodes the domestic (non-international
     * build) always-white set to WeChat / QQ / QQ-lite / deskclock. On every
     * power-mode change the controller resolves those packages to appIds and
     * reaches `DeviceIdlePolicyHelper.e` → `c` →
     * `CommonAdapter.addPowerSaveWhitelistApps` — the single invoke in this
     * process into the AOSP `IDeviceIdleController.addPowerSaveWhitelistApps`
     * binder, the call that both flips the app to "not optimized" in Settings
     * and writes deviceidle.xml. So a manual "optimized" pick survives exactly
     * one cycle: the next power-mode change re-adds WeChat and writes it back.
     * The cloud feature `doze_whitelist_apps` only reaches the feature
     * database; the whitelist write itself still funnels through this method.
     *
     * The hook removes WeChat from the argument array and passes the rest
     * through untouched — QQ, the clock and every cloud entry behave as
     * before, and the forced-doze remove/restore pairing above this funnel is
     * unaffected (an add that never happened needs no removal). It does not
     * remove an entry already stored in deviceidle.xml: clearing that is one
     * manual flip of WeChat back to "optimized" in Settings, after which this
     * hook is what keeps it from coming back. The filter runs behind the cheap
     * gates (switch off → pass, WeChat not in the array → pass), so its cost
     * is bounded by how rarely powerkeeper touches the whitelist at all.
     */
    internal fun hookWechatDozeKeepout(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit
    ) {
        val adapterClass = try {
            classLoader.loadClass("com.miui.powerkeeper.utils.CommonAdapter")
        } catch (t: Throwable) {
            logSkip("CommonAdapter absent, doze-keepout skip")
            return
        }
        // Found by name and arity rather than by parameter types: the first
        // parameter is the hidden IDeviceIdleController interface, and loading
        // it just to describe the method is an avoidable failure mode.
        val addMethod = adapterClass.declaredMethods.firstOrNull {
            it.name == "addPowerSaveWhitelistApps" && it.parameterTypes.size == 2
        }
        if (addMethod == null) {
            logSkip("CommonAdapter#addPowerSaveWhitelistApps absent, doze-keepout skip")
            return
        }
        addMethod.isAccessible = true
        hookE(addMethod).intercept { chain: XposedInterface.Chain ->
            val pkgs = chain.getArg(1) as? Array<String>
            if (pkgs == null || !isWechatDozeKeepoutEnabled(module)) {
                return@intercept chain.proceed()
            }
            if (!pkgs.contains(HookerSupport.WECHAT_PACKAGE_NAME)) {
                return@intercept chain.proceed()
            }
            val kept = pkgs.filter { it != HookerSupport.WECHAT_PACKAGE_NAME }.toTypedArray()
            val dropped = ++wechatDozeKeepoutCount
            if (dropped == 1L || dropped % 10L == 0L) {
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "doze-keepout: dropped WeChat from a battery-optimization " +
                        "whitelist write #$dropped"
                )
            }
            val args = chain.args.toTypedArray()
            args[1] = kept
            chain.proceed(args)
        }
        module.deoptimize(addMethod)
        // Sentinel wording, per the misread that cost a night: hooked does not
        // mean active, and active does not mean the stored entry was cleared.
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "CommonAdapter#addPowerSaveWhitelistApps hooked (doze-keepout, default " +
                "off): idle until the experiment switch is on, then WeChat is left " +
                "out of whitelist writes; entries already on the list are not removed"
        )
    }

    /**
     * The keepout flag lives in the shared config group and is read lazily at
     * each qualifying call: the PowerKeeper process has no broadcast receiver,
     * and the reads only happen after the package gate matches, so the
     * frequency is that of whitelist writes, not of general battery traffic.
     */
    private fun isWechatDozeKeepoutEnabled(module: XposedInterface): Boolean {
        return try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
                .also { HookLog.refresh(it) }
                .getBoolean(Prefs.KEY_WECHAT_DOZE_KEEPOUT, false)
        } catch (ignored: Throwable) {
            // Fail closed: an unreadable switch must not start deciding
            // where the system puts an app.
            false
        }
    }
}
