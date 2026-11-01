package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * The GMS support / observation subsystem (P1–P4), extracted verbatim from
 * `Hooker` (zero-behaviour refactor). `Hooker`'s instance capabilities are
 * injected at the call sites: `module` is the [XposedInterface] the module
 * entry object is (`log` / `deoptimize`), `hookE` is `Hooker#hookE` (the
 * counted, `setId`-deduplicated hook builder — its `hooksInstalled` counter
 * feeds the end-of-install summary that stays with `Hooker`), `logSkip` /
 * `logSkipOtherGeneration` are the two absent-target reports (the counters and
 * the merged cross-generation line live with `Hooker`), `recordProbe` feeds
 * `Hooker`'s merged probe line, `isGmsUid` is `Hooker`'s shared uid gate
 * (gmsUid / cachedGmsAppId stay with `Hooker` — several subsystems consult
 * them), `forceBooleanArg` is `Hooker`'s shared boolean-arg rewriter (the
 * greeze side hooks through it too), and `getSystemContext` is the lazy
 * system-server context supplier whose cached state stays with `Hooker`.
 */
internal object GmsSupport {

    /** Count of suppressed `enablemiuistandby enable` commands (standby firewall chain). */
    private var standbyFirewallSkipCount = 0

    /** Guards userTable write-back against re-entry via hooked config writers. */
    @Volatile
    private var userTableReassertInFlight = false

    /**
     * PowerKeeper's GMS network-control surface, which differs wholesale
     * between ROM generations *under the same version number* (both OS3 and
     * OS4 ship PowerKeeper 4.2.00 here). Filename, versionName and class names
     * are therefore not evidence of which generation is loaded: when in doubt,
     * pull the running APK (`pm path` → `adb pull` → unzip) and md5 its dex
     * against the local samples first.
     *
     * OS3 carries a local state machine plus an iptables chain:
     * `NetdExecutor.initGmsChain` builds `gms_wall` with a REJECT action,
     * `setGmsChainState` toggles it, and `GmsObserver` drives
     * `updateGmsNetWork` / `updateGmsState` / `updateGmsAlarm` / `disableGms*`
     * off a `mGmsControlEnabled` flag derived from Play's bgControl. OS4
     * removed all of that. Its only GMS limit exit is a reflection hop into
     * greeze — `updateFrameworkGmsNetStatus` → `IGreezeManager
     * .updateGmsNetStatus` — gated by `GreezeManagerService.mGmsLimitEnabled`.
     *
     * Every `// OS3-only` target below is kept as defence in depth for OS3. On
     * OS4 those resolutions throw NoSuchMethodException and are reported
     * through [logSkipOtherGeneration] (DEBUG). Ten such lines are the
     * expected, correct output on OS4 — they are not a coverage gap, and must
     * not be "fixed" by deleting the hooks or by inventing OS4 replacements
     * for methods OS4 never had. Conversely, an INFO-level [logSkip] from this
     * file means a target the *current* generation should carry is missing.
     */
    internal fun hookGmsObserver(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit,
        recordProbe: (String) -> Unit,
        isGmsUid: (Int) -> Boolean,
        forceBooleanArg: (Class<*>, String, Boolean, String, Int) -> Unit
    ) {
        try {
            val NetdExecutorClass = classLoader.loadClass("com.miui.powerkeeper.utils.NetdExecutor")
            // OS3-only (cross-generation): the "gms_wall" REJECT chain this
            // hook neutralises by passing "ACCEPT" does not exist on OS4. The
            // method is absent there, so this reports through
            // logSkipOtherGeneration (DEBUG) and installs nothing — expected,
            // not a gap. See hookGmsObserver's header.
            try {
                val initGmsChainMethod = NetdExecutorClass.getDeclaredMethod(
                    "initGmsChain",
                    String::class.java, Int::class.javaPrimitiveType, String::class.java
                )
                hookE(initGmsChainMethod).intercept { chain: XposedInterface.Chain ->
                    val args = chain.args.toTypedArray()
                    args[2] = "ACCEPT"
                    chain.proceed(args)
                }
                module.deoptimize(initGmsChainMethod)
            } catch (e: NoSuchMethodException) {
                logSkipOtherGeneration("NetdExecutor#initGmsChain")
            }
            // P2 (`NetdExecutor#setGmsDnsBlockerState` → false) was removed on
            // 2026-10-30. The method still resolves on OS4, but a full-dex scan
            // of PowerKeeper finds **no caller at all** — and no reflective
            // lookup either (the name appears in no string pool entry), so the
            // old "dead on OS4, kept because resolving is free" note was
            // describing a hook that could never execute. `NetdExecutor#execute`
            // below still covers the DNS half for any generation that does call
            // it, because `setuiddnsrule` for the GMS uid is rewritten there.
            // OS3-only (cross-generation): the gms_wall chain toggle has no
            // OS4 counterpart, so this reports absent there via
            // logSkipOtherGeneration (DEBUG). See hookGmsObserver's header.
            try {
                val setGmsChainStateMethod = NetdExecutorClass.getDeclaredMethod(
                    "setGmsChainState",
                    String::class.java, Boolean::class.javaPrimitiveType
                )
                hookE(setGmsChainStateMethod).intercept { chain: XposedInterface.Chain ->
                    val args = chain.args.toTypedArray()
                    // NetdExecutor.setGmsChainState(chain, enable): enable==true 下发
                    // "set_chain_state <chain> enable"，配合 initGmsChain(gms_wall, uid, "REJECT")
                    // 即 true=开墙阻断 GMS（与已删除的 P2 `setGmsDnsBlockerState` 同极性）。
                    if (args.size > 1) {
                        args[1] = false
                    }
                    chain.proceed(args)
                }
                module.deoptimize(setGmsChainStateMethod)
            } catch (e: NoSuchMethodException) {
                logSkipOtherGeneration("NetdExecutor#setGmsChainState")
            }
            try {
                val executeMethod = NetdExecutorClass.getDeclaredMethod(
                    "execute",
                    Int::class.javaPrimitiveType, String::class.java, String::class.java,
                    Array<Any>::class.java
                )
                val executeReturn = executeMethod.returnType
                val skipValue = skipValueFor(executeReturn)
                hookE(executeMethod).intercept { chain: XposedInterface.Chain ->
                    val args = chain.args.toTypedArray()
                    if (args.size >= 4 && args[2] is String && args[3] is Array<*>) {
                        val cmd = args[2] as String
                        @Suppress("UNCHECKED_CAST")
                        val cmdArgs = args[3] as Array<Any?>
                        if ("setuiddnsrule" == cmd && cmdArgs.size >= 2) {
                            // Gate by uid, never rewrite blind. On OS4 no
                            // PowerKeeper code sends `setuiddnsrule` at all (the
                            // old GMS-only wrapper `setGmsDnsBlockerState(IZ)`
                            // had no caller and its hook was removed); this
                            // branch is the generation-proof fallback for a ROM
                            // that does send it. Gating keeps a future caller
                            // for other uids passing through untouched instead
                            // of having its rule silently flipped to "allow".
                            // Unparseable uid also passes through — never
                            // rewrite what cannot be proven to be GMS.
                            val uid = cmdArgs[0]?.toString()?.toIntOrNull() ?: -1
                            if (isGmsUid(uid)) {
                                val rewritten = cmdArgs.copyOf()
                                rewritten[1] = "allow"
                                args[3] = rewritten
                                return@intercept chain.proceed(args)
                            }
                            return@intercept chain.proceed()
                        }
                        if ("enablemiuistandby" == cmd && cmdArgs.isNotEmpty() &&
                            "enable" == cmdArgs[0].toString()
                        ) {
                            val skipped = ++standbyFirewallSkipCount
                            if (skipped == 1 || skipped % 10 == 0) {
                                module.logGated(
                                    Log.INFO, HookerSupport.TAG,
                                    "standby-firewall: suppressed 'enablemiuistandby enable' #$skipped"
                                )
                            }
                            return@intercept skipValue
                        }
                    }
                    chain.proceed()
                }
                module.deoptimize(executeMethod)
                // Folded into the merged `probe:` line. This is the same kind of
                // static signature fact as the other probe findings, and it was
                // the only probe in this process printing on a line of its own.
                val skipText = skipValue?.toString() ?: "null"
                recordProbe("NetdExecutor#execute->${executeReturn.name},skip=$skipText")
            } catch (e: NoSuchMethodException) {
                logSkip("NetdExecutor#execute not found, skip command-level GMS net hooks")
            }
        } catch (e: ClassNotFoundException) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to hook NetdExecutor", e)
        }
        try {
            val GmsObserverClass = classLoader.loadClass("com.miui.powerkeeper.utils.GmsObserver")
            // OS3-only (cross-generation): the local state machine
            // (mGmsBlocked / mGmsControlEnabled) that drove these three was
            // removed on OS4, so on the test device all three report absent at
            // DEBUG — the default level of [forceBooleanArg]. See
            // hookGmsObserver's header. Do not delete them on the strength of
            // that DEBUG line.
            for (legacyName in arrayOf(
                "updateGmsAlarm", "updateGmsNetWork", "updateGoogleReletivesWakelock"
            )) {
                forceBooleanArg(GmsObserverClass, legacyName, false, "GmsObserver", Log.DEBUG)
            }
            // OS3-only (cross-generation): the hard "disable GMS" entry.
            // Absent on OS4, which has no local state machine to gate it.
            // `disableGmsApps` was dropped on 2026-10-30 after the OS3 dump:
            // the method exists on neither supported generation (the OS3 dex
            // and the OS4 dex both lack it), so its hook could never have
            // installed anywhere.
            for (alwaysSkip in arrayOf("disableGms")) {
                try {
                    val disableMethod = GmsObserverClass.getDeclaredMethod(alwaysSkip)
                    hookE(disableMethod).intercept { _: XposedInterface.Chain -> null }
                    module.deoptimize(disableMethod)
                } catch (e: NoSuchMethodException) {
                    logSkipOtherGeneration("GmsObserver#$alwaysSkip")
                }
            }
            // OS3-only (cross-generation): the three flag setters of the local
            // state machine. Absent on OS4 for the same reason as above.
            for (limitFlag in arrayOf("updateGmsEnabled", "updateGmsState", "updateGmsInstalled")) {
                forceBooleanArg(GmsObserverClass, limitFlag, false, "GmsObserver", Log.DEBUG)
            }
            // OS4's limit path (`updateFrameworkGmsNetStatus` → greeze
            // `updateGmsNetStatus`) is deliberately NOT hooked here any more
            // (2026-10-30). The same call is pinned to false on the system_server
            // side by S5 (`GreezeManagerService#updateGmsNetStatus`), which is the
            // end every caller funnels through — PowerKeeper's reflection hop, the
            // handler route, all of them. Hooking both ends bought nothing but a
            // second layer for one effect. If S5 ever reports absent on a
            // generation that still limits GMS, the install summary says so
            // (INFO) — that is the signal to bring this hook back.
            //
            // P9 stays on both generations: the reachability callback is pinned to
            // "reachable". On OS4 that is idempotent with S5 (its only outward
            // effect is the updateGmsNetStatus argument S5 pins), but on OS3 the
            // limit path is the local state machine (P5/P6/P7) and this callback is
            // the only thing keeping GMS's reachability true. Deleting it on
            // OS4-only evidence would be wrong: OS3 shares this exact target.
            //
            // P10/P11 remain removed: `c`'s only call edge is this callback, and
            // googleNetworkDisconnect's callback ends in the same place, so P9
            // covers both.
            forceBooleanArg(
                GmsObserverClass, "onGoogleReachabilityChanged", true, "GmsObserver", Log.INFO
            )
        } catch (e: ClassNotFoundException) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to hook GmsObserver", e)
        }
    }

    /**
     * P3: force GMS's compiled scenario to 8 (noRestrict) instead of 0.
     *
     * fillScenarioContent() special-cases GmsCoreUtils.isGmsCoreApp: a
     * miuiAuto row becomes scenario 0 for GMS but scenario 2 for normal apps.
     * Scenario 0 makes isNoRestrict() return true (so older code thinks GMS is
     * unrestricted) yet dealNoRestrictApp() only queries the literal
     * bgControl="noRestrict" rows — so GMS never enters MILLET_NO_RESTRICT_APP.
     *
     * Rewriting scenario 0 → 8 for GMS makes the compiled profile match the
     * "noRestrict" scenario that a normal app gets when the user selects
     * "Unrestricted", aligning UI / AOSP DeviceIdle / private policy state.
     *
     * **This one is a consistency fix, nothing more, and that is settled.**
     * There is no user-visible symptom it repairs and no measurable gain to
     * report, so it must not be counted as a deliverable improvement, offered as
     * evidence that GMS is unrestricted, or re-opened for "does it actually do
     * anything" — the answer is "it makes three states agree", and that is the
     * whole scope.
     *
     * Field layout (verified from OS3/OS4 PowerKeeper DEX):
     *   PowerKeeperAppConfigure.pkg : String
     *   PowerKeeperAppConfigure.scenario : int
     *
     * OS3 fillScenarioContent(Context,int,PowerKeeperAppConfigure,
     *   UserConfigureHelper,String,List,List)V
     * OS4 adds a trailing Map parameter.
     */
    @Volatile
    private var sScenarioAlignedLogged = false

    internal fun hookScenarioCompiler(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkipOtherGeneration: (String) -> Unit,
        getSystemContext: () -> Context?
    ) {
        val configureClass =
            classLoader.loadClass("com.miui.powerkeeper.provider.PowerKeeperAppConfigure")
        val pkgField = configureClass.getDeclaredField("pkg")
        pkgField.isAccessible = true
        val scenarioField = configureClass.getDeclaredField("scenario")
        scenarioField.isAccessible = true

        // OS3: 7 params; OS4: 8 params (extra Map). PowerKeeperAppConfigure is arg 2.
        // A missing form is the other generation's shape, so it reports through
        // logSkipOtherGeneration (DEBUG) instead of passing silently — same
        // two-level absence contract as every other hook in this module.
        for (paramCount in intArrayOf(7, 8)) {
            val method = configureClass.declaredMethods.firstOrNull { m ->
                m.name == "fillScenarioContent" && m.parameterCount == paramCount
            }
            if (method == null) {
                logSkipOtherGeneration("PowerKeeperAppConfigure#fillScenarioContent($paramCount args)")
                continue
            }

            hookE(method).intercept { chain: XposedInterface.Chain ->
                chain.proceed()
                try {
                    val cfg = chain.getArg(2) ?: return@intercept null
                    val pkg = pkgField.get(cfg) as? String ?: return@intercept null
                    if (HookerSupport.GMS_PACKAGE_NAME != pkg) return@intercept null
                    val scenario = scenarioField.getInt(cfg)
                    if (scenario == HookerSupport.SCENARIO_MUI_AUTO_GMS) {
                        scenarioField.setInt(cfg, HookerSupport.SCENARIO_NO_RESTRICT)
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "P3: rewrote GMS scenario ${HookerSupport.SCENARIO_MUI_AUTO_GMS} → ${HookerSupport.SCENARIO_NO_RESTRICT}"
                        )
                    } else if (scenario == HookerSupport.SCENARIO_NO_RESTRICT && !sScenarioAlignedLogged) {
                        sScenarioAlignedLogged = true
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "P3: GMS scenario already ${HookerSupport.SCENARIO_NO_RESTRICT} " +
                                "(the compiler reaches this hook; no rewrite needed)"
                        )
                    }
                    try {
                        ensureGmsUserTableBgControl(module, getSystemContext)
                    } catch (t: Throwable) {
                        module.logGated(Log.WARN, HookerSupport.TAG, "P3: userTable write-back failed", t)
                    }
                } catch (t: Throwable) {
                    module.logGated(Log.ERROR, HookerSupport.TAG, "P3: failed to rewrite GMS scenario", t)
                }
                null
            }
            module.deoptimize(method)
            module.logGated(Log.INFO, HookerSupport.TAG, "PowerKeeperAppConfigure#fillScenarioContent($paramCount args) hooked for P3")
        }
    }

    /**
     * P2: Greezer freeze-path safety net in system_server.
     *
     * Verified against OS3/OS4 miui-services.jar:
     * - AurogonImmobulusMode.isNoRestrictApp(String)Z  — the exact mNoRestrictAppSet
     *   check used by both lambda$triggerQuickFreeze$0 and PolicyMaker's filter chain.
     * - AurogonImmobulusMode.triggerQuickFreeze(I,I)V
     * - PolicyMaker.isAllowFreeze(I)I  — returns int (CANNOT_FREEZE constant).
     * - OS4 extra: isNoRestrictFreezeable(String,I)Z.
     *
     * All targets live in miui-services.jar (system_server). GMS itself is never
     * hooked — only the framework-side freeze policy is told to treat GMS as
     * no-restrict.
     */
    @Volatile
    private var sFreezeGateAnsweredLogged = false

    @Volatile
    private var sFreezeableAnsweredLogged = false

    internal fun hookGreezerNoRestrict(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit,
        isGmsUid: (Int) -> Boolean
    ) {
        // Primary: isNoRestrictApp(pkg) — boolean, no constant guessing needed.
        // Returning true means "GMS is in the no-restrict set", so every freeze
        // path that consults mNoRestrictAppSet (Aurogon quick-freeze and
        // PowerStrategyMode) skips GMS.
        try {
            val aurogonClass =
                classLoader.loadClass("com.miui.server.greeze.AurogonImmobulusMode")
            try {
                val isNoRestrictAppMethod = aurogonClass.getDeclaredMethod(
                    "isNoRestrictApp", String::class.java
                )
                hookE(isNoRestrictAppMethod).intercept { chain: XposedInterface.Chain ->
                    val pkg = chain.getArg(0)
                    if (HookerSupport.GMS_PACKAGE_NAME == pkg) {
                        if (!sFreezeGateAnsweredLogged) {
                            sFreezeGateAnsweredLogged = true
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "no-restrict chain: isNoRestrictApp(GMS) answered true " +
                                    "(freeze path consulted the module and skipped GMS)"
                            )
                        }
                        return@intercept true
                    }
                    chain.proceed()
                }
                module.deoptimize(isNoRestrictAppMethod)
            } catch (e: NoSuchMethodException) {
                logSkip("AurogonImmobulusMode#isNoRestrictApp absent, skip")
            }

            // OS4: isNoRestrictFreezeable(pkg, reason) — false means "do not freeze".
            try {
                val isNoRestrictFreezeableMethod = aurogonClass.getDeclaredMethod(
                    "isNoRestrictFreezeable", String::class.java, Int::class.javaPrimitiveType
                )
                hookE(isNoRestrictFreezeableMethod).intercept { chain: XposedInterface.Chain ->
                    val pkg = chain.getArg(0)
                    if (HookerSupport.GMS_PACKAGE_NAME == pkg) {
                        if (!sFreezeableAnsweredLogged) {
                            sFreezeableAnsweredLogged = true
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "no-restrict chain: isNoRestrictFreezeable(GMS) answered false " +
                                    "(the OS4 freezeable branch consulted the module)"
                            )
                        }
                        return@intercept false
                    }
                    chain.proceed()
                }
                module.deoptimize(isNoRestrictFreezeableMethod)
            } catch (e: NoSuchMethodException) {
                logSkipOtherGeneration("AurogonImmobulusMode#isNoRestrictFreezeable")
            }

            // triggerQuickFreeze(uid, reason) — skip GMS entirely.
            val triggerQuickFreezeMethods = aurogonClass.declaredMethods.filter { m ->
                m.name == "triggerQuickFreeze" && m.parameterCount == 2
            }
            if (triggerQuickFreezeMethods.isEmpty()) {
                logSkip("AurogonImmobulusMode#triggerQuickFreeze(I,I) absent, skip")
            } else {
                for (method in triggerQuickFreezeMethods) {
                    method.isAccessible = true
                    hookE(method).intercept { chain: XposedInterface.Chain ->
                        val uid = chain.getArg(0)
                        if (uid is Int && isGmsUid(uid)) {
                            return@intercept skipValueFor(method.returnType)
                        }
                        chain.proceed()
                    }
                    module.deoptimize(method)
                }
                module.logGated(Log.INFO, HookerSupport.TAG, "AurogonImmobulusMode#triggerQuickFreeze hooked")
            }
        } catch (e: ClassNotFoundException) {
            logSkip("AurogonImmobulusMode class absent, skip")
        } catch (e: Throwable) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to hook AurogonImmobulusMode", e)
        }

        // PolicyMaker.isAllowFreeze(uid): int return (CANNOT_FREEZE when in the
        // no-restrict set). Returning 0 / false for GMS closes the PowerStrategyMode
        // (tobg / from system) window before PowerKeeper regenerates the projection.
        try {
            val policyMakerClass =
                classLoader.loadClass("com.miui.server.greeze.power.PolicyMaker")
            val isAllowFreezeMethods = policyMakerClass.declaredMethods.filter { m ->
                m.name == "isAllowFreeze" && m.parameterCount == 1
            }
            if (isAllowFreezeMethods.isEmpty()) {
                logSkip("PolicyMaker#isAllowFreeze absent, skip")
            } else {
                for (method in isAllowFreezeMethods) {
                    method.isAccessible = true
                    hookE(method).intercept { chain: XposedInterface.Chain ->
                        val uid = chain.getArg(0)
                        if (uid is Int && isGmsUid(uid)) {
                            return@intercept skipValueFor(method.returnType)
                        }
                        chain.proceed()
                    }
                    module.deoptimize(method)
                    module.logGated(Log.INFO, HookerSupport.TAG, "PolicyMaker#isAllowFreeze hooked (${method.returnType.simpleName})")
                }
            }
        } catch (e: ClassNotFoundException) {
            logSkip("PolicyMaker class absent, skip")
        } catch (e: Throwable) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to hook PolicyMaker#isAllowFreeze", e)
        }
    }

    /**
     * Read Settings.System.MILLET_NO_RESTRICT_APP and append GMS when missing.
     * Preserves every existing entry and ordering. Also writes GMS's
     * userTable.bgControl back to "noRestrict" so the source row matches.
     * After a repair, triggers P4 recovery so an already-frozen GMS gets a
     * chance to reconnect.
     *
     * Write-surface audit (2026-10-03, extended 2026-10-08, recounted
     * 2026-10-26): the module persists anything outside its own prefs in exactly
     * **three** places —
     *
     *  1. this one: `Settings.System.MILLET_NO_RESTRICT_APP`, GMS appended;
     *  2. [ensureGmsUserTableBgControl]: the PowerKeeper `userTable` row for GMS,
     *     `bgControl = noRestrict`;
     *  3. the WeChat doze keepout (default off) — and that one is **indirect**:
     *     the hook drops WeChat from `CommonAdapter.addPowerSaveWhitelistApps`'
     *     argument list and the *ROM* then writes `/data/system/deviceidle.xml`
     *     without it. The module never touches that file.
     *
     * The earlier "five places" count was wrong and is retired: it still listed
     * the autostart `10053` switch-op backfill, which 4.2.0 deleted, and the
     * fifth entry was never identified by anyone. Three is the whole surface;
     * a fourth would have to be a new write, and it has to justify itself
     * against this list.
     *
     * All three widen a restriction; none can blacklist an app, and there is no
     * path that leaves an app worse off after uninstalling the module than it
     * was before installing it. Runtime-only effects a reboot erases — sleep-mode
     * uid injections, netd/DNS rule rewrites, in-memory hook answers — are
     * deliberately not counted here: this list tracks what persists.
     */
    @Volatile
    private var sMilletPresentLogged = false

    internal fun ensureGmsInMilletSetting(
        module: XposedInterface,
        getSystemContext: () -> Context?
    ) {
        ensureGmsUserTableBgControl(module, getSystemContext)
        val context = getSystemContext() ?: getPowerKeeperContext() ?: return
        val resolver = context.contentResolver
        val raw = android.provider.Settings.System.getString(resolver, HookerSupport.MILLET_NO_RESTRICT_APP_KEY)
            ?: ""
        val entries = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.contains(HookerSupport.GMS_PACKAGE_NAME)) {
            if (!sMilletPresentLogged) {
                sMilletPresentLogged = true
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "MILLET_NO_RESTRICT_APP: GMS already present (${entries.size} entries)"
                )
            }
            return
        }
        val updated = if (entries.isEmpty()) {
            HookerSupport.GMS_PACKAGE_NAME
        } else {
            entries.joinToString(", ") + ", " + HookerSupport.GMS_PACKAGE_NAME
        }
        android.provider.Settings.System.putString(resolver, HookerSupport.MILLET_NO_RESTRICT_APP_KEY, updated)
        module.logGated(Log.INFO, HookerSupport.TAG, "MILLET_NO_RESTRICT_APP: appended GMS (was: $raw)")
        // P4: if GMS was frozen during the missing-entry window, nudge it awake.
        recoverGmsConnection(module, context)
    }

    /**
     * Write-back: set GMS's PowerKeeper userTable.bgControl to "noRestrict".
     *
     * P1 already injects GMS into the no-restrict projection and P3 rewrites
     * the compiled scenario; this keeps the *source* row aligned so
     * dealNoRestrictApp() and any regeneration that literally filters
     * bgControl="noRestrict" include GMS without leaning on the interceptors.
     * Only the GMS row is touched; other packages keep whatever the user set.
     */
    internal fun ensureGmsUserTableBgControl(
        module: XposedInterface,
        getSystemContext: () -> Context?
    ) {
        if (userTableReassertInFlight) return
        userTableReassertInFlight = true
        try {
            val pk = getPowerKeeperContext()
            val sys = getSystemContext()
            val context = pk ?: sys
            if (context == null) {
                module.logGated(Log.WARN, HookerSupport.TAG, "userTable: no Context (powerKeeper=$pk system=$sys), skip write-back")
                return
            }
            module.logGated(Log.INFO, HookerSupport.TAG, "userTable: ensure GMS bgControl via ${if (pk != null) "powerkeeper" else "system"}")
            val uri = android.net.Uri.parse(HookerSupport.USER_TABLE_URI)
            var current: String? = null
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(HookerSupport.COL_BG_CONTROL),
                    "${HookerSupport.COL_PKG_NAME} = ?",
                    arrayOf(HookerSupport.GMS_PACKAGE_NAME),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        current = cursor.getString(0)
                    }
                }
            } catch (t: Throwable) {
                module.logGated(Log.WARN, HookerSupport.TAG, "userTable: query failed", t)
                return
            }
            module.logGated(Log.INFO, HookerSupport.TAG, "userTable: GMS current bgControl=$current")
            if (current == HookerSupport.BG_CONTROL_NO_RESTRICT) return

            val values = android.content.ContentValues()
            values.put(HookerSupport.COL_BG_CONTROL, HookerSupport.BG_CONTROL_NO_RESTRICT)
            try {
                val updated = context.contentResolver.update(
                    uri,
                    values,
                    "${HookerSupport.COL_PKG_NAME} = ?",
                    arrayOf(HookerSupport.GMS_PACKAGE_NAME)
                )
                module.logGated(Log.INFO, HookerSupport.TAG, "userTable: update $current -> ${HookerSupport.BG_CONTROL_NO_RESTRICT} count=$updated")
                if (updated > 0) return
            } catch (t: Throwable) {
                module.logGated(Log.WARN, HookerSupport.TAG, "userTable: update failed", t)
            }
            values.put(HookerSupport.COL_PKG_NAME, HookerSupport.GMS_PACKAGE_NAME)
            values.put(HookerSupport.COL_USER_ID, 0)
            values.put(HookerSupport.COL_LAST_CONFIGURED, System.currentTimeMillis())
            try {
                val inserted = context.contentResolver.insert(uri, values)
                module.logGated(Log.INFO, HookerSupport.TAG, "userTable: insert result=$inserted")
            } catch (t: Throwable) {
                module.logGated(Log.WARN, HookerSupport.TAG, "userTable: insert GMS row failed", t)
            }
        } catch (t: Throwable) {
            module.logGated(Log.WARN, HookerSupport.TAG, "userTable: ensure failed", t)
        } finally {
            userTableReassertInFlight = false
        }
    }

    /**
     * P4: ask GMS/GSF to re-establish its FCM connection and un-freeze.
     *
     * All actions are outbound IPC TO GMS/GSF (broadcasts + content query),
     * not hooks inside GMS. Inspired by FCMGuard's heartbeat approach:
     * GCM_RECONNECT alone may miss the MCS/GTalk reconnect paths on some
     * builds, so GTALK_HEARTBEAT and MCS_HEARTBEAT are also sent. GSF
     * (com.google.android.gsf) participates in the FCM transport chain
     * alongside GMS and is included as a target.
     */
    internal fun recoverGmsConnection(module: XposedInterface, context: Context) {
        sendRecoveryBroadcasts(module, context)
        queryChimera(module, context)
    }

    /**
     * The broadcast half of P4, split out because the two halves have different
     * cost: this one returns immediately, [queryChimera] blocks.
     */
    internal fun sendRecoveryBroadcasts(module: XposedInterface, context: Context) {
        for (target in arrayOf(HookerSupport.GMS_PACKAGE_NAME, HookerSupport.GSF_PACKAGE_NAME)) {
            for (action in HookerSupport.RECOVERY_BROADCAST_ACTIONS) {
                try {
                    val intent = Intent(action)
                    intent.setPackage(target)
                    context.sendBroadcast(intent)
                } catch (t: Throwable) {
                    module.logGated(Log.WARN, HookerSupport.TAG, "Failed to send $action to $target", t)
                }
            }
        }
        module.logGated(Log.INFO, HookerSupport.TAG, "P4: recovery broadcasts sent to GMS+GSF")
    }

    /**
     * The provider half of P4. Separate from the broadcasts because it is the
     * blocking call of the pair: callers that must not block (a hook chain) run it
     * on their own thread.
     */
    internal fun queryChimera(module: XposedInterface, context: Context) {
        try {
            val uri = android.net.Uri.parse(HookerSupport.CHIMERA_PROVIDER_URI)
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.close()
            module.logGated(Log.INFO, HookerSupport.TAG, "Chimera provider query completed")
        } catch (t: Throwable) {
            module.logGated(Log.WARN, HookerSupport.TAG, "Chimera provider query failed (non-fatal)", t)
        }
    }

    /**
     * PowerKeeper's own Context, distinct from system_server's.
     * Cached after the first successful lookup in this process.
     */
    @Volatile
    private var powerKeeperContext: Context? = null

    private fun getPowerKeeperContext(): Context? {
        if (powerKeeperContext != null) return powerKeeperContext
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThreadClass.getMethod("currentApplication")
            val ctx = currentApplication.invoke(null)
            if (ctx is Context) {
                powerKeeperContext = ctx
                ctx
            } else {
                null
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun skipValueFor(returnType: Class<*>): Any? {
        if (returnType == Void.TYPE || !returnType.isPrimitive) {
            return null
        }
        return when (returnType) {
            Boolean::class.javaPrimitiveType -> java.lang.Boolean.FALSE
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> 0.toChar()
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }
}
