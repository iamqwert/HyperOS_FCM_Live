package io.github.howard20181.hyperos.fcmlive

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Pair
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Xposed module entry: keeps FCM / GMS wake paths alive on HyperOS.
 *
 * Do not "modernize" away:
 * - Public class extending [XposedModule] with a no-arg constructor (proguard).
 * - Hook callbacks run in system_server / PowerKeeper: never throw out of them,
 *   never block the main thread, never switch to coroutines.
 * - The four FCM marker constants are shared with the settings list.
 *
 * Scope limits this module stays inside:
 * - Root is not module privilege. No `su`/`exec su` from a hook (watchdog risk),
 *   no SELinux changes, no injection into GMS or into a target app, no cloud
 *   (云控) countermeasures, no global writes upstream of this module's own
 *   five documented write sites — see the write-surface audit on
 *   [ensureGmsInMilletSetting].
 *
 * Defence placement, which decides whether a hook is worth having:
 * - A live path whose current branch does not hit GMS → keep a **sentinel**
 *   hook, and word the user-facing copy as "armed", never as "took effect",
 *   until it is confirmed at runtime.
 * - A mechanism that is dead end-to-end → do not add a hook; use cheap
 *   observation instead.
 * - **A behaviour change that has not been confirmed at runtime is not made.**
 *   When in doubt, ship one log line that states the assumption instead — a
 *   guess you can read beats a change you cannot verify.
 */
@SuppressLint("PrivateApi")
class Hooker : XposedModule() {

    private var param: Pair<String, ClassLoader>? = null
    private var systemContext: Context? = null

    /**
     * Install-pass bookkeeping (counters, absent buffers, probe findings, the
     * end-of-install summary and the group runner) — see [InstallBookkeeping].
     * The forwarders below keep every group-table entry's `::hookE` /
     * `::logSkip` references — and every extracted subsystem's injected
     * callables — pointing at the same implementations as before the
     * extraction.
     */
    private val installBook = InstallBookkeeping(this)

    private fun hookE(executable: Executable): XposedInterface.HookBuilder =
        installBook.hookE(executable)

    /** This generation is expected to carry it, and does not. Counted and logged. */
    private fun logSkip(message: String) = installBook.logSkip(message)

    private fun logSkipOtherGeneration(symbol: String) = installBook.logSkipOtherGeneration(symbol)

    private fun recordProbe(finding: String) = installBook.recordProbe(finding)

    private fun flushOtherGenerationAbsent() = installBook.flushOtherGenerationAbsent()

    private fun flushProbes() = installBook.flushProbes()

    private fun logSummary(process: String) = installBook.logSummary(process)

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader
        this.param = Pair.create("system", classLoader)
        // Load the debug-log snapshot before the install pass logs anything, so
        // a troubleshooting session opened before this boot already sees the
        // install lines; a failed read keeps the gate closed, which is the
        // same value a fresh snapshot would carry.
        try {
            HookLog.refresh(getRemotePreferences(Prefs.GROUP_CONFIG))
        } catch (ignored: Throwable) {
        }
        try {
            hookSystemServer(classLoader)
        } catch (tr: Throwable) {
            logGated(Log.ERROR, HookerSupport.TAG, "Failed to hook SystemServer", tr)
        }
        logSummary("system_server")
    }

    private fun hookSystemServer(classLoader: ClassLoader) {
        // The flush belongs to the install pass, not to [logSummary]. On hot
        // reload [onHotReloaded] calls this function directly and never goes
        // through [onSystemServerStarting], so the summary is never reached —
        // buffering without this flush would drop every merged finding silently
        // on every hot reload, which is the one failure mode the merged lines
        // exist to make visible. `finally` so a throw cannot lose them either.
        installBook.installPassCollecting = true
        try {
            for (group in systemServerGroups(classLoader)) {
                installBook.installGroup(group)
            }
        } finally {
            installBook.installPassCollecting = false
            flushOtherGenerationAbsent()
            flushProbes()
        }
    }

    /**
     * The system_server install surface: one line per group, in install order.
     *
     * Adding a group is adding a line here, not copying another try/catch.
     * [installGroup] gives every group the isolation the hand-written blocks
     * had: a throw inside one group leaves all the others installed, and
     * [logSummary] still reports once at the end.
     *
     * Three things this table has to keep that the upstream pattern
     * (`FIXES: List<Pair<String, Fixes>>` + a single `hook skip` line) has no
     * equivalent for:
     * 1. [hookE] bookkeeping — `setId()` de-duplication plus the
     *    `hooksInstalled` / `hookTargetsAbsent` counters behind the
     *    `N hook(s) installed, M target(s) absent` line that every install
     *    check ends with.
     * 2. Two absent levels — [logSkip] (INFO) for a target this generation is
     *    expected to carry, [logSkipOtherGeneration] (DEBUG) for a target only
     *    another generation carries. Collapsing them into one "skip" line is
     *    what makes "never existed on this generation" indistinguishable from
     *    "lost in the last OTA".
     * 3. The failure wording `Failed to <verb> <name>`, which
     *    HOOKS_AND_DIAGNOSTICS.md 3.11 quotes verbatim — hence the verb being
     *    part of the entry rather than a fixed "hook".
     */
    private fun systemServerGroups(classLoader: ClassLoader): List<Group> = listOf(
        Group("hook", "allowlist receiver") {
            AllowlistGate.hookAllowlist(this, ::getSystemContext, ::applyAutostartToAllowlist)
        },
        Group("hook", "GreezeManagerService") {
            GreezeHooks.hookGreezeManagerService(
                this, classLoader, ::hookE, ::logSkip, ::isGmsUid,
                ::forceBooleanArg, ::getSystemContext, ::applyAutostartToAllowlist,
                { cls, name, types -> findMethod(cls, name, *types) }
            )
        },
        Group("hook", "GreezerNoRestrict") {
            GmsSupport.hookGreezerNoRestrict(
                this, classLoader, ::hookE, ::logSkip, ::logSkipOtherGeneration, ::isGmsUid
            )
        },
        Group("hook", "DomesticPolicyManager") {
            GreezeHooks.hookDomesticPolicyManager(
                this, classLoader, ::hookE, ::logSkip, ::isGmsUid,
                ::isGreezeNetReleaseEnabled, ::allowlistPackageForUid
            )
        },
        Group("hook", "ListAppsManager") {
            GreezeHooks.hookListAppsManager(this, classLoader, ::hookE)
        },
        Group("hook", "BroadcastQueueModernStubImpl") {
            GreezeHooks.hookBroadcastQueueModernStubImpl(
                this, classLoader, ::hookE, ::logSkip,
                ::getSystemContext, ::applyAutostartToAllowlist
            )
        },
        Group("hook", "greeze broadcast cache") {
            GreezeHooks.hookGreezeBroadcastCache(
                this, classLoader, ::hookE, ::logSkip,
                ::getSystemContext, ::applyAutostartToAllowlist
            )
        },
        Group("hook", "ProcessPolicy") {
            GreezeHooks.hookProcessPolicy(this, classLoader, ::hookE)
        },
        Group("hook", "AwareResourceControl") {
            GreezeHooks.hookAwareResourceControl(this, classLoader, ::hookE, ::gmsUid)
        },
        Group("hook", "sleep-mode network policy") {
            SleepModeGate.hookSleepModeNetworkPolicy(
                this, classLoader, ::hookE, ::logSkip, ::logSkipOtherGeneration,
                ::getSystemContext, ::gmsUid, ::broadcastGateSummary,
                { GmsSupport.recoverGmsConnection(this, it) }
            )
        },
        Group("hook", "ActivityManagerService") {
            AmsHooks.hookActivityManagerService(
                this, classLoader, ::hookE,
                { cls, name, types -> findMethod(cls, name, *types) },
                ::getSystemContext, ::applyAutostartToAllowlist
            )
        },
        Group("hook", "InternationalPolicyManager") {
            hookInternationalPolicyManager(classLoader)
        },
        Group("hook", "udpPackageRestrict") {
            GreezeHooks.hookUdpPackageRestrict(this, classLoader, ::hookE, ::logSkip, ::isGmsUid)
        },
        Group("hook", "ProcessCleanerBase") {
            ProcessCleanerHooks.hookProcessCleanerBase(
                this, classLoader, ::hookE, ::getSystemContext, ::applyAutostartToAllowlist
            )
        },
        Group("hook", "autostart gate") {
            AutostartGate.hookAutostartGate(
                this, classLoader, ::hookE, ::logSkipOtherGeneration,
                ::getSystemContext, ::applyAutostartToAllowlist
            )
        },
        Group("hook", "alarm gate") {
            AlarmGate.hookAlarmGate(
                this, classLoader, ::hookE, ::logSkip, ::logSkipOtherGeneration, ::isGmsUid
            )
        },
        Group("hook", "wifi weak-signal switch") {
            WifiWeakSignalSwitch.hook(
                this, classLoader, ::hookE, ::logSkip, ::logSkipOtherGeneration
            )
        },
        Group("install", "wake-path probe") {
            WakePathProbe.probeWakePath(this, classLoader, ::hookE, ::logSkipOtherGeneration)
        },
        Group("install", "broadcast wake-path probe") {
            WakePathProbe.probeBroadcastWakePath(this, classLoader, ::hookE, ::logSkipOtherGeneration)
        },
        Group("probe", "mMessageApp") {
            SystemProbes.probeGmsInMessageApp(this, classLoader, ::logSkip, ::recordProbe)
        },
        Group("probe", "sleep-mode uid rule") {
            SystemProbes.probeSleepModeUidRule(this, classLoader, ::logSkip, ::recordProbe)
        },
        Group("probe", "packet filter support") {
            SystemProbes.probePacketFilterSupport(this, classLoader, ::logSkip, ::recordProbe)
        },
        Group("install", "socket-teardown probe") {
            SystemProbes.probeSocketTeardown(
                this, classLoader, ::hookE, ::logSkip, ::recordProbe, ::isGmsUid
            )
        },
        Group("start", "GMS traffic probe") {
            TrafficProbe.startGmsTrafficProbe(this, ::gmsUid, ::broadcastGateSummary)
        },
    )

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        val packageName = param.packageName
        val classLoader = param.classLoader
        this.param = Pair.create(packageName, classLoader)
        // Same pre-install snapshot as the system_server entry: the PowerKeeper
        // process has no allowlist reload to ride, so the entry is where the
        // debug-log gate gets its first — and otherwise only — config read.
        try {
            HookLog.refresh(getRemotePreferences(Prefs.GROUP_CONFIG))
        } catch (ignored: Throwable) {
        }
        try {
            hookPackage(packageName, classLoader)
        } catch (tr: Throwable) {
            logGated(Log.ERROR, HookerSupport.TAG, "Failed to hook package", tr)
        }
        logSummary(packageName)
    }

    private fun hookPackage(packageName: String, classLoader: ClassLoader) {
        // Same reasoning as [hookSystemServer]: this is also the hot-reload entry
        // point. Only the PowerKeeper domain installs anything, so for any other
        // package both buffers are empty and the flush is a no-op.
        installBook.installPassCollecting = true
        try {
            if ("com.miui.powerkeeper" == packageName) {
                for (group in powerKeeperGroups(packageName, classLoader)) {
                    installBook.installGroup(group)
                }
            }
        } finally {
            installBook.installPassCollecting = false
            flushOtherGenerationAbsent()
            flushProbes()
        }
    }

    /** The PowerKeeper install surface: same shape and same rules as [systemServerGroups]. */
    private fun powerKeeperGroups(packageName: String, classLoader: ClassLoader): List<Group> =
        listOf(
            Group("hook", "GmsObserver") {
                GmsSupport.hookGmsObserver(
                    this, classLoader, ::hookE, ::logSkip, ::logSkipOtherGeneration,
                    ::recordProbe, ::isGmsUid, ::forceBooleanArg
                )
            },
            Group("hook", "AppStandbyController") {
                PowerKeeperHooks.hookAppStandbyUidState(
                    this, packageName, classLoader, ::hookE, ::logSkip, ::isGmsUid
                )
            },
            Group("hook", "GlobalFeatureConfigureHelper") {
                SleepModeGate.hookGlobalFeatureConfigureHelper(this, classLoader, ::hookE, ::logSkip)
            },
            Group("hook", "NoRestrictList") {
                PowerKeeperHooks.hookNoRestrictList(
                    this, classLoader, ::hookE, ::logSkip, ::getSystemContext
                )
            },
            Group("hook", "ScenarioCompiler") {
                GmsSupport.hookScenarioCompiler(this, classLoader, ::hookE, ::getSystemContext)
            },
            Group("hook", "WeChat doze keepout") {
                PowerKeeperHooks.hookWechatDozeKeepout(this, classLoader, ::hookE, ::logSkip)
            },
            Group("hook", "sleep-mode network keepalive") {
                SleepModeGate.hookSleepModeNetworkKeepalive(this, classLoader, ::hookE, ::logSkip)
            },
        )

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Hot reload is fully supported on LSPosed API 102: onHotReloaded unhooks every
        // stale handle and re-runs the whole install sequence, so nothing is left behind
        // that would need a reboot. Do not reintroduce any "reboot required" wording —
        // HELP.md / README state no reboot is needed and this method proves it.
        logGated(Log.INFO, HookerSupport.TAG, "Hot reload requested — re-installing hooks without reboot")
        param.setSavedInstanceState(this.param)
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        param.oldHookHandles.forEach { h ->
            try {
                h.unhook()
            } catch (ignored: Throwable) {
            }
        }
        val saved = param.savedInstanceState
        if (saved is Pair<*, *>) {
            val packageName = saved.first as? String
            val classLoader = saved.second as? ClassLoader
            if (packageName != null && classLoader != null) {
                this.param = Pair.create(packageName, classLoader)
                try {
                    if (param.isSystemServer) {
                        hookSystemServer(classLoader)
                    } else {
                        hookPackage(packageName, classLoader)
                    }
                } catch (tr: Throwable) {
                    logGated(Log.ERROR, HookerSupport.TAG, "Hot reload failed", tr)
                }
            }
        }
    }

    private fun gmsUid(): Int? {
        return try {
            val context = getSystemContext() ?: return null
            context.packageManager.getApplicationInfo(HookerSupport.GMS_PACKAGE_NAME, 0).uid
        } catch (t: Throwable) {
            null
        }
    }

    @Volatile
    private var systemContextFailureLogged = false

    private fun getSystemContext(): Context? {
        if (systemContext == null) {
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentApplication = activityThreadClass.getMethod("currentApplication")
                val ctx = currentApplication.invoke(null)
                if (ctx is Context) {
                    systemContext = ctx
                }
            } catch (t: Throwable) {
                // Every caller treats a null context as "this group is skipped":
                // the allowlist receiver is never installed, the GMS uid cannot be
                // resolved, and so on. That used to leave no trace at all, so the
                // module could be installed, report N hooks, and still be quietly
                // inert. Once per boot is enough — this is retried on every call.
                if (!systemContextFailureLogged) {
                    systemContextFailureLogged = true
                    logGated(Log.WARN, HookerSupport.TAG, "System context unavailable", t)
                }
            }
        }
        return systemContext
    }

    /**
     * Pin the single boolean argument of [name] to [value] and let the call
     * through: one shape, one helper, several targets (see the call sites).
     *
     * [level] is what keeps the two readings of "absent" apart, and the reason
     * this replaced a helper that hardcoded [logSkipOtherGeneration]: an
     * OS3-only target and an OS4 load-bearing target have the same shape, but a
     * miss on the second must not be reported at the same level as a miss on
     * the first. Callers pass [Log.DEBUG] for "another generation's method"
     * (routed to [logSkipOtherGeneration], so it joins the merged
     * cross-generation line and stays out of `target(s) absent`) and
     * [Log.INFO] for "a target this generation should carry".
     *
     * Pinning the argument unconditionally is equivalent to the older
     * only-if-it-is-true form used by `updateFrameworkGmsNetStatus`: the
     * argument is a primitive boolean, so writing the value it already holds is
     * a no-op.
     */
    internal fun forceBooleanArg(
        owner: Class<*>,
        name: String,
        value: Boolean,
        label: String,
        level: Int = Log.DEBUG
    ) {
        try {
            val method = owner.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
            hookE(method).intercept { chain: XposedInterface.Chain ->
                val args = chain.args.toTypedArray()
                if (args.isNotEmpty()) {
                    args[0] = value
                }
                chain.proceed(args)
            }
            deoptimize(method)
        } catch (e: NoSuchMethodException) {
            if (level == Log.DEBUG) {
                logSkipOtherGeneration("$label#$name")
            } else {
                logSkip("$label#$name absent, skip")
            }
        }
    }

    /**
     * True when [uid] belongs to GMS (any Android user). Uses the package
     * manager when available; falls back to comparing against known GMS UIDs
     * resolved once per process.
     */
    private fun isGmsUid(uid: Int): Boolean {
        val appId = uid % 100000
        // Cache hit: done.
        if (cachedGmsAppId != null) {
            return appId == cachedGmsAppId
        }
        // First call: resolve and cache.
        val gms = gmsUid()
        if (gms != null) {
            cachedGmsAppId = gms % 100000
            return appId == cachedGmsAppId
        }
        // gmsUid() failed; try PackageManager directly.
        return try {
            val context = getSystemContext() ?: return false
            val info = context.packageManager.getApplicationInfo(HookerSupport.GMS_PACKAGE_NAME, 0)
            val resolved = info.uid % 100000
            cachedGmsAppId = resolved
            appId == resolved
        } catch (ignored: Throwable) {
            false
        }
    }

    @Volatile
    private var cachedGmsAppId: Int? = null

    private fun findMethod(
        owner: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>?
    ): Method? {
        return try {
            owner.getDeclaredMethod(name, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * The AllowlistGate callback handle. `AllowlistGate` stores whatever this
     * file hands it as `onApplyAutostart` and invokes it for
     * [Prefs.ACTION_APPLY_AUTOSTART] on the allowlist handler thread; the walk
     * itself lives in [AutostartGate.applyAutostartToAllowlist]. This one-line
     * forwarder keeps every `::applyAutostartToAllowlist` reference — and the
     * callback path documented in `AllowlistGate` — pointing at the same
     * callable as before the extraction.
     */
    private fun applyAutostartToAllowlist() {
        AutostartGate.applyAutostartToAllowlist(this, ::getSystemContext)
    }

    /**
     * P0/P1/P2 evidence, appended to every traffic-probe tick.
     *
     * Reading it:
     *  - `allowed` counts c2dm the isAllowBroadcast gate admitted. `uid-fallback`
     *    is the subset the *uid* carried rather than the caller package string
     *    (P1) — a non-zero value means the fallback was load-bearing.
     *  - `skipped` counts c2dm to callees the user did not select.
     *    Those now fall through to the ROM policy, which is the P0 fix.
     *  - `reached-defer` counts c2dm that actually arrived at
     *    `DomesticPolicyManager#deferBroadcast` without being suppressed; a
     *    non-zero value proves P0 is active on this ROM/branch.
     * All four zero means the branch was never exercised this interval.
     *
     * `autostart gate` rides the same tick for the same reason: its detail lines
     * only fire on a denial, so without this "never denied" and "never asked"
     * would look identical. `service-denied` / `process-denied` non-zero is the
     * ROM refusing a start the broadcast hook already waved through — the
     * finding that says the autostart op, not the broadcast gate, is what
     * decides delivery.
     *
     * `net-released` rides it too: the greeze net-release detail line fires
     * once per uid, ever, so the counter is the only measure of how often
     * freezes actually hit checked apps — and a steady zero with the switch
     * on means greeze never froze a checked app this interval.
     *
     * The wake-path half is the P2 probe, and it rides this tick on purpose: the
     * probe's own detailed lines only fire when its gate is actually reached, so
     * without this tick "never reached" and "reached and always allowed" would
     * look identical. `c2dm-denied` non-zero is the finding that would reopen the
     * module's design.
     *
     * Returned rather than logged: the caller ([TrafficProbe.probeGmsTraffic]) appends it to
     * the traffic line it shares a tick with.
     */
    internal fun broadcastGateSummary(): String {
        // An idle tick is by far the common case — the counters sit at zero for
        // days on a device where nothing is being gated. Every counter is still
        // named, so "never exercised" stays greppable and distinguishable from
        // "reached and always allowed".
        val idle = GreezeHooks.broadcastGateAllowedCount == 0 &&
            GreezeHooks.broadcastGateSkippedCount == 0 &&
            GreezeHooks.deferC2dmPassthroughCount == 0 &&
            GreezeHooks.broadcastGateCnActionCount == 0 &&
            WakePathProbe.broadcastWakePathReachedCount == 0 &&
            WakePathProbe.broadcastWakePathC2dmCount == 0 &&
            WakePathProbe.broadcastWakePathDeniedCount == 0 &&
            WakePathProbe.broadcastWakePathC2dmDeniedCount == 0 &&
            AutostartGate.autostartGateServiceDeniedCount == 0 &&
            AutostartGate.autostartGateProcessDeniedCount == 0 &&
            AutostartGate.autostartGateRestartDeniedCount == 0 &&
            AutostartGate.autostartGatePreStartDeniedCount == 0 &&
            AutostartGate.autostartGateReleaseCount == 0 &&
            AutostartGate.autostartGateRestartReleaseCount == 0 &&
            AutostartGate.autostartRootReleaseCount == 0 &&
            GreezeHooks.greezeNetReleaseCount == 0
        if (idle) {
            return "broadcast gate idle — c2dm " +
                "allowed/skipped/reached-defer/cn-actions = 0, " +
                "wake-path reached/c2dm/denied/c2dm-denied = 0, " +
                "autostart gate service/process/restart/pre-start-denied, " +
                "released, restart-released, root-released, net-released = 0"
        }
        return "broadcast gate c2dm allowed=${GreezeHooks.broadcastGateAllowedCount} " +
            "(uid-fallback=${GreezeHooks.broadcastGateAllowedByUidCount}), " +
            "skipped=${GreezeHooks.broadcastGateSkippedCount}, " +
            "reached-defer=${GreezeHooks.deferC2dmPassthroughCount}, " +
            "cn-actions=${GreezeHooks.broadcastGateCnActionCount}; " +
            "wake-path reached=${WakePathProbe.broadcastWakePathReachedCount}, " +
            "c2dm=${WakePathProbe.broadcastWakePathC2dmCount}, " +
            "denied=${WakePathProbe.broadcastWakePathDeniedCount}, " +
            "c2dm-denied=${WakePathProbe.broadcastWakePathC2dmDeniedCount}; " +
            "autostart gate service-denied=${AutostartGate.autostartGateServiceDeniedCount}, " +
                "process-denied=${AutostartGate.autostartGateProcessDeniedCount}, " +
                "restart-denied=${AutostartGate.autostartGateRestartDeniedCount}, " +
                "pre-start-denied=${AutostartGate.autostartGatePreStartDeniedCount}, " +
                "released=${AutostartGate.autostartGateReleaseCount}, " +
                "restart-released=${AutostartGate.autostartGateRestartReleaseCount}, " +
                "root-released=${AutostartGate.autostartRootReleaseCount}, " +
                "net-released=${GreezeHooks.greezeNetReleaseCount}"
    }

    /**
     * Experiment switch: keep a checked app's network up across a greeze
     * freeze. Reads its **own** key — it answers the freeze-time network cut,
     * not the autostart verdict, and must stay measurable on its own.
     *
     * Fails to *disabled*: this answers a verdict the ROM owns.
     */
    private fun isGreezeNetReleaseEnabled(): Boolean {
        return try {
            val prefs = getRemotePreferences(Prefs.GROUP_CONFIG)
            // This read runs on every qualifying freeze, which makes it one of
            // the config openings the debug-log gate refreshes from (see
            // [HookLog]); the greeze path logs its verdicts through the gate.
            HookLog.refresh(prefs)
            prefs.getBoolean(Prefs.KEY_GREEZE_NET_RELEASE, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * The allowlisted package a uid belongs to, if any.
     *
     * A uid can host several packages (shared uid is rare but legal), so the
     * check is "any of them is checked" and one checked name is returned for
     * the log line. Resolved through the package manager per call, with no
     * cache: the only caller of `isRestrictNet` is `freezeUids`, so the extra
     * hop rides an already-cold path and a cache would need install/uninstall
     * invalidation to stay honest. GMS never reaches here — the shipped branch
     * above answers the GMS uid first.
     */
    private fun allowlistPackageForUid(uid: Int): String? {
        val packages = try {
            getSystemContext()?.packageManager?.getPackagesForUid(uid)
        } catch (ignored: Throwable) {
            null
        } ?: return null
        val allowlist = AllowlistGate.getFcmAllowlist(this, ::getSystemContext, ::applyAutostartToAllowlist)
        if (allowlist.isEmpty()) {
            return null
        }
        for (pkg in packages) {
            if (allowlist.contains(pkg)) {
                return pkg
            }
        }
        return null
    }

    @Volatile
    private var restrictNetMatchLogged = false

    /**
     * P0/P1 broadcast-gate counters live with [GreezeHooks] now (they are only
     * written by the greeze hooks); the wake-path half lives with
     * [WakePathProbe]. This summary reads them from there.
     */

    /**
     * `InternationalPolicyManager#isPushApp` — kept as is, and deliberately
     * **not** mirrored with an International partner for P0 #1.
     *
     * Forensics (HyperOS V816), re-confirmed before this hook was left in place:
     *
     * - `com.miui.server.greeze.PolicyManager` is the interface both policy
     *   implementations satisfy, and its method table declares `isRestrictNet`
     *   **only** — there is no `isPushApp` slot. So no `invoke-interface` edge
     *   into this method exists anywhere in system_server; its sole callers are
     *   in-class `invoke-direct` self-calls inside `InternationalPolicyManager`.
     * - This device selects the Domestic implementation
     *   (`AurogonImmobulusMode#restorePolicyManager`; `dumpsys greezer` reports
     *   `mCurrentCNPolicy: 1` with the force-CN flag false), so
     *   `InternationalPolicyManager` is never instantiated here and this hook
     *   is inert locally.
     *
     * Two consequences follow, and both are intentional:
     *
     * - No International counterpart for the P0 #1
     *   `DomesticPolicyManager#isRestrictNet` hook is added. Nothing on this
     *   device can execute it, so its behaviour could never be observed —
     *   unverifiable risk in exchange for zero local benefit.
     * - This hook stays. International ROMs do instantiate the class, and there
     *   `isRestrictNet` still funnels through `isPushApp`.
     */
    private fun hookInternationalPolicyManager(classLoader: ClassLoader) {
        val InternationalPolicyManagerClass =
            classLoader.loadClass("com.miui.server.greeze.InternationalPolicyManager")
        val isPushAppMethod = InternationalPolicyManagerClass.getDeclaredMethod(
            "isPushApp", String::class.java
        )
        val restrictNetOwner = InternationalPolicyManagerClass.name
        val systemServerCl = InternationalPolicyManagerClass.classLoader
        hookE(isPushAppMethod).intercept { chain: XposedInterface.Chain ->
            val pkg = chain.getArg(0) as? String
            if (AllowlistGate.moduleAppliesTo(this, ::getSystemContext, ::applyAutostartToAllowlist, pkg)) {
                try {
                    val fromRestrictNet = HookerSupport.STACK_WALKER.walk { frames ->
                        frames.anyMatch { frame ->
                            "isRestrictNet" == frame.methodName &&
                                (restrictNetOwner == frame.className ||
                                    (frame.declaringClass != null &&
                                        frame.declaringClass.classLoader === systemServerCl))
                        }
                    }
                    if (fromRestrictNet) {
                        if (!restrictNetMatchLogged) {
                            restrictNetMatchLogged = true
                            logGated(
                                Log.INFO, HookerSupport.TAG, "isPushApp: caller isRestrictNet matched;" +
                                    " answering false"
                            )
                        }
                        return@intercept false
                    }
                } catch (t: Throwable) {
                    logGated(Log.ERROR, HookerSupport.TAG, "Stack inspection failed", t)
                }
            }
            chain.proceed()
        }
    }

}
