package io.github.iamqwert.hypergreeze

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Pair
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

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
 *   three documented write sites — see the write-surface audit on
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

    @Volatile
    private var systemContext: Context? = null

    /**
     * Install-pass bookkeeping (counters, absent buffers, probe findings, the
     * end-of-install summary and the group runner) — see [InstallBookkeeping].
     */
    private val installBook = InstallBookkeeping(this)

    private val remotePrefsObserver = RemotePrefsObserver()

    private fun envFor(classLoader: ClassLoader): HookEnv = HookEnv(
        module = this,
        classLoader = classLoader,
        installBook = installBook,
        systemContextProvider = ::getSystemContext,
        gmsUidProvider = ::gmsUid,
        isGmsUidCheck = ::isGmsUid,
        allowlistPackageLookup = ::allowlistPackageForUid
    )

    private fun flushOtherGenerationAbsent() = installBook.flushOtherGenerationAbsent()

    private fun flushProbes() = installBook.flushProbes()

    private fun logSummary(process: String) = installBook.logSummary(process)

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader
        this.param = Pair.create("system", classLoader)
        // Load the debug-log snapshot and attach the parallel RemotePreferences
        // change observer before the install pass logs anything.
        remotePrefsObserver.attach(this, "system_server") { _, _ ->
            AllowlistGate.requestAllowlistReload(this)
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
     *    FCM_HOOK_TECH_DOC.md §3.6 quotes verbatim — hence the verb being
     *    part of the entry rather than a fixed "hook".
     */
    private fun systemServerGroups(classLoader: ClassLoader): List<Group> {
        val env = envFor(classLoader)
        return listOf(
            Group("hook", "allowlist receiver") {
                AllowlistGate.hookAllowlist(this, ::getSystemContext)
            },
            Group("hook", "GreezeManagerService") {
                GreezeHooks.hookGreezeManagerService(env)
            },
            Group("hook", "GreezerNoRestrict") {
                GmsSupport.hookGreezerNoRestrict(env)
            },
            Group("hook", "greeze wake-up list") {
                GreezeHooks.hookAllowWakeUpList(env)
            },
            Group("hook", "wakelock policy") {
                WakeLockGate.hookWakeLockPolicy(env)
            },
            // S31 (`isNeedCachedAlarmForAurogon`) was removed on 2026-10-30: the ROM
            // answers false for any package whose name lacks "xiaomi", so the GMS
            // uid never reached the hook's override. See GreezeHooks.
            Group("hook", "DomesticPolicyManager") {
                GreezeHooks.hookDomesticPolicyManager(env, ::isGreezeNetReleaseEnabled)
            },
            Group("hook", "ListAppsManager") {
                GreezeHooks.hookListAppsManager(env)
            },
            Group("hook", "BroadcastQueueModernStubImpl") {
                GreezeHooks.hookBroadcastQueueModernStubImpl(env)
            },
            Group("hook", "SmartPower intercept") {
                SmartPowerHooks.hookSmartPowerIntercept(env, ::isSmartPowerReleaseEnabled)
            },
            Group("hook", "ProcessPolicy") {
                GreezeHooks.hookProcessPolicy(env)
            },
            Group("hook", "AwareResourceControl") {
                GreezeHooks.hookAwareResourceControl(env)
            },
            Group("hook", "sleep-mode network policy") {
                SleepModeGate.hookSleepModeNetworkPolicy(env) {
                    GmsSupport.recoverGmsConnection(this, it)
                }
            },
            Group("hook", "ActivityManagerService") {
                AmsHooks.hookActivityManagerService(env)
            },
            Group("hook", "udpPackageRestrict") {
                GreezeHooks.hookUdpPackageRestrict(env)
            },
            Group("hook", "ProcessCleanerBase") {
                ProcessCleanerHooks.hookProcessCleanerBase(env)
            },
            Group("hook", "autostart gate") {
                AutostartGate.hookAutostartGate(
                    env,
                    ::isAutostartServiceReleaseEnabled,
                    ::isAutostartProcessReleaseEnabled
                )
            },
            Group("hook", "alarm gate") {
                AlarmGate.hookAlarmGate(env)
            },
            Group("hook", "wifi weak-signal switch") {
                WifiWeakSignalSwitch.hook(env)
            },
            Group("probe", "mMessageApp") {
                SystemProbes.probeGmsInMessageApp(env)
            },
            Group("probe", "sleep-mode uid rule") {
                SystemProbes.probeSleepModeUidRule(env)
            },
            Group("probe", "packet filter support") {
                SystemProbes.probePacketFilterSupport(env)
            },
            Group("start", "records bridge") {
                RecordsBridge.install(this, ::getSystemContext) { installBook.lastSummary }
            },
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        val packageName = param.packageName
        val classLoader = param.classLoader
        this.param = Pair.create(packageName, classLoader)
        // Same pre-install snapshot + RemotePreferences change observer as
        // system_server, so config flips in the module UI can be observed
        // directly in PowerKeeper as well.
        remotePrefsObserver.attach(this, packageName)
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
        // package both buffers are empty and the flush is a no-op. GMS is
        // deliberately NOT a domain: the GMS-side hook family (GCM reconnect
        // repair) was retired in 4.1.1 after GMS hot updates started flagging
        // in-process instrumentation as an integrity violation.
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
    private fun powerKeeperGroups(packageName: String, classLoader: ClassLoader): List<Group> {
        val env = envFor(classLoader)
        return listOf(
            Group("hook", "GmsObserver") {
                GmsSupport.hookGmsObserver(env)
            },
            Group("hook", "AppStandbyController") {
                PowerKeeperHooks.hookAppStandbyUidState(env, packageName)
            },
            Group("hook", "NoRestrictList") {
                PowerKeeperHooks.hookNoRestrictList(env)
            },
            Group("hook", "ScenarioCompiler") {
                GmsSupport.hookScenarioCompiler(env)
            },
            Group("hook", "WeChat doze keepout") {
                PowerKeeperHooks.hookWechatDozeKeepout(env)
            },
            Group("hook", "sleep-mode network keepalive") {
                SleepModeGate.hookSleepModeNetworkKeepalive(env)
            },
        )
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Hot reload is fully supported on LSPosed API 102: onHotReloaded unhooks every
        // stale handle and re-runs the whole install sequence, so nothing is left behind
        // that would need a reboot. Do not reintroduce any "reboot required" wording —
        // HELP.md / README state no reboot is needed and this method proves it.
        logGated(Log.INFO, HookerSupport.TAG, "Hot reload requested — re-installing hooks without reboot")
        remotePrefsObserver.detach()
        param.setSavedInstanceState(this.param)
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        installBook.resetForHotReload()
        val saved = param.savedInstanceState
        if (saved is Pair<*, *>) {
            val packageName = saved.first as? String
            val classLoader = saved.second as? ClassLoader
            if (packageName != null && classLoader != null) {
                this.param = Pair.create(packageName, classLoader)
                try {
                    if (param.isSystemServer) {
                        remotePrefsObserver.attach(this, "system_server") { _, _ ->
                            AllowlistGate.requestAllowlistReload(this)
                        }
                        WifiWeakSignalSwitch.resetForHotReload()
                        hookSystemServer(classLoader)
                    } else {
                        remotePrefsObserver.attach(this, packageName)
                        hookPackage(packageName, classLoader)
                    }
                } catch (tr: Throwable) {
                    logGated(Log.ERROR, HookerSupport.TAG, "Hot reload failed", tr)
                }
            }
        }
        // Unhook only handles not replaced in-place by the pass above, so live
        // hooks experience no unprotected gap during hot reload.
        param.oldHookHandles.forEach { h ->
            if (!installBook.isHookedId(h.id)) {
                try {
                    h.unhook()
                } catch (ignored: Throwable) {
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
     * True when [uid] belongs to GMS (any Android user). Resolves and caches
     * GMS's appId with a 60s TTL so a GMS reinstall / UID change is picked up
     * without paying a PackageManager lookup on every hot-path call.
     */
    private fun isGmsUid(uid: Int): Boolean {
        if (uid < 10000) return false
        val appId = uid % 100000
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedGmsAppId
        if (cached != null && now - cachedGmsAppIdRefreshedMs < GMS_APP_ID_TTL_MS) {
            return appId == cached
        }
        val gms = gmsUid()
        if (gms != null) {
            val resolved = gms % 100000
            cachedGmsAppId = resolved
            cachedGmsAppIdRefreshedMs = now
            return appId == resolved
        }
        // Keep the last known appId if PackageManager is transiently unavailable,
        // but still advance the stamp to avoid hammering PMS on every call.
        cachedGmsAppIdRefreshedMs = now
        return cached != null && appId == cached
    }

    @Volatile
    private var cachedGmsAppId: Int? = null

    @Volatile
    private var cachedGmsAppIdRefreshedMs: Long = Long.MIN_VALUE / 2

    private companion object {
        private const val GMS_APP_ID_TTL_MS = 60_000L
    }

    /**
     * Experiment switch: release OS3 SmartPower intercepts for checked apps.
     * Served from [AllowlistGate]'s broadcast-refreshed snapshot (fail-closed
     * default off). On OS4 the hooks never install (targets absent).
     */
    private fun isSmartPowerReleaseEnabled(): Boolean =
        AllowlistGate.isSmartPowerReleaseEnabled(this)

    /**
     * Experiment switch: keep a checked app's network up across a greeze
     * freeze. Served from [AllowlistGate]'s broadcast-refreshed snapshot
     * (fail-closed default off).
     */
    private fun isGreezeNetReleaseEnabled(): Boolean =
        AllowlistGate.isGreezeNetReleaseEnabled(this)

    private fun isAutostartServiceReleaseEnabled(): Boolean =
        AllowlistGate.isAutostartServiceReleaseEnabled(this)

    private fun isAutostartProcessReleaseEnabled(): Boolean =
        AllowlistGate.isAutostartProcessReleaseEnabled(this)

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
        val allowlist = AllowlistGate.getFcmAllowlist(this, ::getSystemContext)
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


}
