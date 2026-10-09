package io.github.howard20181.hyperos.fcmlive

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.ContentResolver
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.IBinder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerExemptionManager
import android.os.Process
import android.provider.Settings
import android.net.TrafficStats
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import android.util.Pair
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
     * Counts behind the end-of-install summary line.
     * Every hook goes through [hookE]; absent targets are counted by [logSkip]
     * (same generation) and [logSkipOtherGeneration] (another generation).
     */
    private var hooksInstalled = 0
    private var hookTargetsAbsent = 0

    /**
     * Absent on *another* supported generation, counted apart from
     * [hookTargetsAbsent] so the summary line can say which is which.
     *
     * On the test ROM the whole PowerKeeper set reports absent (ten symbols
     * that only OS3 carries). Folded into one number that read as
     * `10 target(s) absent`, i.e. a healthy install looked like ten broken
     * hooks — and the reading that actually matters, "did this OTA drop
     * something this generation is supposed to have", was invisible.
     */
    private var hookTargetsAbsentOtherGeneration = 0

    /**
     * Symbols collected by [logSkipOtherGeneration], flushed as one line.
     *
     * Ten OS3-only symbols on this ROM answered the same question in ten
     * lines, and each line pays the LSPosed per-line prefix (~160 B) — more
     * than most of the messages themselves. The symbols are all kept, so a
     * ROM that starts carrying one still shows as a differing entry.
     */
    private val otherGenerationAbsent = ArrayList<String>()

    /**
     * Findings from the read-only existence probes, flushed as one line.
     *
     * These answer "does this ROM carry the symbol / is the feature flag on",
     * which does not change between generations. Nine of them used to print a
     * line each. Only the *normal* reading is folded in: an abnormal one (a
     * layer that stopped resolving, a socket-teardown half that is gone) still
     * prints its own line, because that is the case worth noticing.
     */
    private val probeFindings = ArrayList<String>()

    /**
     * True only between the start and the end of an install pass.
     *
     * A late arrival lands after [flushOtherGenerationAbsent] has run: the wifi
     * weak-signal retry lives on its own thread and is not joined by its group,
     * so its "unreachable after retry" verdict can come back after the pass
     * closed. Such a line is printed on its own instead of being buffered for a
     * flush that may never come (the next one is a hot reload or a reboot away).
     */
    private var installPassCollecting = false

    private fun hookE(executable: Executable): XposedInterface.HookBuilder {
        val builder = hook(executable)
        hooksInstalled++
        if (apiVersion >= 102) {
            builder.setId(executable.toGenericString())
        }
        return builder
    }

    /** This generation is expected to carry it, and does not. Counted and logged. */
    private fun logSkip(message: String) {
        hookTargetsAbsent++
        log(Log.INFO, TAG, message)
    }

    /**
     * Another supported generation carries it, this one does not — expected,
     * not a gap. Counted in [hookTargetsAbsentOtherGeneration] (never in
     * [hookTargetsAbsent]) and reported by [flushOtherGenerationAbsent] at
     * DEBUG, on one line together with its siblings.
     *
     * Reserved for targets that are *expected* to be missing on at least one
     * supported ROM generation: an entry here means "this generation does not
     * carry the method", not "the hook is broken". Because that distinction is
     * what separates the two readings, never route a same-generation miss
     * through here: a genuine regression on the current generation would then
     * hide among the expected ones. Every caller must be a target whose absence
     * is explained by a known generation split — see [hookGmsObserver] for the
     * PowerKeeper set, the largest user of this path.
     *
     * [symbol] is the missing symbol, optionally with a parenthetical when the
     * cause is not a generation split (`checkWakePath (wake-path probe)`,
     * `AmlMiuiThirdPartScorer (unreachable after retry)`), since the consequent
     * "so what did not get installed" would otherwise be lost when the lines
     * are merged.
     */
    private fun logSkipOtherGeneration(symbol: String) {
        hookTargetsAbsentOtherGeneration++
        if (!installPassCollecting) {
            log(Log.DEBUG, TAG, "cross-generation target(s) absent (1), skip: $symbol")
            return
        }
        otherGenerationAbsent.add(symbol)
    }

    /**
     * One line for every cross-generation miss collected during an install pass.
     *
     * Called at the end of the pass — [hookSystemServer] / [hookPackage], the two
     * entry points that hot reload also uses — and cleared afterwards. Each
     * process gets its own [Hooker] instance, so the list holds exactly one pass.
     */
    private fun flushOtherGenerationAbsent() {
        if (otherGenerationAbsent.isEmpty()) return
        log(
            Log.DEBUG, TAG,
            "cross-generation target(s) absent (${otherGenerationAbsent.size}), skip: " +
                otherGenerationAbsent.joinToString(", ")
        )
        otherGenerationAbsent.clear()
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader
        this.param = Pair.create("system", classLoader)
        try {
            hookSystemServer(classLoader)
        } catch (tr: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook SystemServer", tr)
        }
        logSummary("system_server")
    }

    /** Collects one [probeFindings] entry; see the field for why they are merged. */
    private fun recordProbe(finding: String) {
        probeFindings.add(finding)
    }

    /** One line for every read-only probe answered during this install pass. */
    private fun flushProbes() {
        if (probeFindings.isEmpty()) return
        log(Log.INFO, TAG, "probe: " + probeFindings.joinToString(" "))
        probeFindings.clear()
    }

    private fun logSummary(process: String) {
        // No flush here on purpose: the merged probe / cross-generation lines are
        // flushed at the end of the install pass ([hookSystemServer] /
        // [hookPackage]), which is the only place guaranteed to run — see the
        // note there about the hot-reload path skipping this function.
        val crossGeneration = hookTargetsAbsentOtherGeneration
        log(
            Log.INFO, TAG, "HyperFCMLive active in $process: " +
                "$hooksInstalled hook(s) installed, " +
                "$hookTargetsAbsent target(s) absent on this ROM" +
                if (crossGeneration == 0) {
                    ""
                } else {
                    ", $crossGeneration cross-generation (expected)"
                }
        )
    }

    private fun hookSystemServer(classLoader: ClassLoader) {
        // The flush belongs to the install pass, not to [logSummary]. On hot
        // reload [onHotReloaded] calls this function directly and never goes
        // through [onSystemServerStarting], so the summary is never reached —
        // buffering without this flush would drop every merged finding silently
        // on every hot reload, which is the one failure mode the merged lines
        // exist to make visible. `finally` so a throw cannot lose them either.
        installPassCollecting = true
        try {
            for (group in systemServerGroups(classLoader)) {
                installGroup(group)
            }
        } finally {
            installPassCollecting = false
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
        Group("hook", "allowlist receiver") { hookAllowlist() },
        Group("hook", "GreezeManagerService") { hookGreezeManagerService(classLoader) },
        Group("hook", "GreezerNoRestrict") { hookGreezerNoRestrict(classLoader) },
        Group("hook", "DomesticPolicyManager") { hookDomesticPolicyManager(classLoader) },
        Group("hook", "ListAppsManager") { hookListAppsManager(classLoader) },
        Group("hook", "BroadcastQueueModernStubImpl") {
            hookBroadcastQueueModernStubImpl(classLoader)
        },
        Group("hook", "greeze broadcast cache") { hookGreezeBroadcastCache(classLoader) },
        Group("hook", "ProcessPolicy") { hookProcessPolicy(classLoader) },
        Group("hook", "AwareResourceControl") { hookAwareResourceControl(classLoader) },
        Group("hook", "sleep-mode network policy") { hookSleepModeNetworkPolicy(classLoader) },
        Group("hook", "ActivityManagerService") { hookActivityManagerService(classLoader) },
        Group("hook", "InternationalPolicyManager") {
            hookInternationalPolicyManager(classLoader)
        },
        Group("hook", "udpPackageRestrict") { hookUdpPackageRestrict(classLoader) },
        Group("hook", "ProcessCleanerBase") { hookProcessCleanerBase(classLoader) },
        Group("hook", "autostart gate") { hookAutostartGate(classLoader) },
        Group("hook", "alarm gate") { hookAlarmGate(classLoader) },
        Group("hook", "wifi weak-signal switch") { hookWifiWeakSignalSwitch(classLoader) },
        Group("install", "wake-path probe") { probeWakePath(classLoader) },
        Group("install", "broadcast wake-path probe") { probeBroadcastWakePath(classLoader) },
        Group("probe", "mMessageApp") { probeGmsInMessageApp(classLoader) },
        Group("probe", "sleep-mode uid rule") { probeSleepModeUidRule(classLoader) },
        Group("probe", "packet filter support") { probePacketFilterSupport(classLoader) },
        Group("install", "socket-teardown probe") { probeSocketTeardown(classLoader) },
        Group("start", "GMS traffic probe") { startGmsTrafficProbe() },
    )

    /** Runs one group; a throw inside it cannot reach the groups behind it. */
    private fun installGroup(group: Group) {
        try {
            group.install()
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to ${group.verb} ${group.name}", t)
        }
    }

    /**
     * One install group. [verb] and [name] are the failure wording
     * (`Failed to <verb> <name>`), not decoration: that string is quoted in the
     * diagnostics doc and grepped out of overnight logs.
     */
    private class Group(val verb: String, val name: String, val install: () -> Unit)

    /**
     * Read-only probe for gate A3 (action list 2.2).
     *
     * `MiuiNetworkPolicyManagerService#updateSleepModeWhitelistUidRules` reaches
     * the real work only through `Class.forName("android.net.ConnectivityManager")
     * .getDeclaredMethod("updateSleepModeUidRule", int, boolean)`. The symbol is
     * therefore absent from every services.jar dex, and its existence cannot be
     * settled statically — asking the live framework is the only way to tell a
     * working path from a no-op reflection stub.
     */
    private fun probeSleepModeUidRule(classLoader: ClassLoader) {
        val name = "updateSleepModeUidRule"
        try {
            val cm = classLoader.loadClass("android.net.ConnectivityManager")
            cm.getDeclaredMethod(name, java.lang.Integer.TYPE, java.lang.Boolean.TYPE)
            // The lookup pins the exact parameter list, so "present" already means
            // "present with the signature the caller below needs".
            recordProbe("ConnectivityManager#$name=present")
        } catch (e: NoSuchMethodException) {
            recordProbe("ConnectivityManager#$name=ABSENT(gateA3)")
        } catch (e: ClassNotFoundException) {
            logSkip("ConnectivityManager absent, sleep-mode probe skip")
        }
        probeReflectiveMethod(
            classLoader,
            "android.net.ConnectivityManager",
            "enableSleepModeChain",
            java.lang.Boolean.TYPE
        )
    }

    /**
     * Reports whether a hidden framework method exists, which is the only way to
     * tell MIUI's reflective trampolines from real work: the target symbol lives in
     * framework.jar, so it is absent from every services.jar dex and cannot be
     * found statically.
     *
     * The finding is keyed `<SimpleClass>#<method>`, which is unambiguous on its
     * own — the merged probe line carries several of them. A missing *class* is
     * still reported on its own line: that is a finding, not a routine reading.
     */
    private fun probeReflectiveMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>
    ) {
        val key = "${className.substringAfterLast('.')}#$methodName"
        try {
            classLoader.loadClass(className).getDeclaredMethod(methodName, *parameterTypes)
            recordProbe("$key=present")
        } catch (e: NoSuchMethodException) {
            recordProbe("$key=ABSENT")
        } catch (e: ClassNotFoundException) {
            logSkip("$className absent, $methodName probe skip")
        }
    }

    /**
     * Read-only probe for the UDP packet-filter capability (action list 3.6).
     *
     * `GreezeManagerService#updateAurogonUidRule` ends in `udpPackageRestrict` on
     * **both** the CN and the non-CN branch, and that tail calls
     * `PowerInsightService#setUidNetworkFilter(uid)`. Whether any of it does
     * anything depends on `FilterEnablePolicy.isSupportPacketFilter()`, which is
     * assembled at runtime from a custom feature flag, a platform check and a
     * cloud switch.
     */
    private fun probePacketFilterSupport(classLoader: ClassLoader) {
        try {
            val policy =
                classLoader.loadClass("com.miui.powerinsight.packetfilter.FilterEnablePolicy")
            val supported =
                policy.getDeclaredMethod("isSupportPacketFilter").invoke(null) as? Boolean
            recordProbe("packet-filter=$supported")
        } catch (e: ClassNotFoundException) {
            logSkip("FilterEnablePolicy absent, packet filter probe skip")
        } catch (e: NoSuchMethodException) {
            logSkip("FilterEnablePolicy#isSupportPacketFilter absent, probe skip")
        }
    }

    /**
     * Read-only probe for the 3.2 socket-teardown chain.
     *
     * Dex-level forensics found no caller for any of the three entry points, but the
     * client half was never on the ROM jars that were grepped: `WhetstoneActivityManager`
     * lives in `/system_ext/framework/miui-framework.jar` (**not** `/system/framework`),
     * and its `doDesSocketForUid` forwards over the `IWhetstoneActivityManager` AIDL
     * (`TRANSACTION_doDesSocketForUid` exists in the generated Stub). A binder transport
     * is invisible to `invoke-*` counting, so two questions stay open statically:
     *
     * 1. is the **server** class (`WhetstoneActivityManagerService`) even resolvable in
     *    system_server and does it implement `doDesSocketForUid`? Its definition is in
     *    none of the six dexes dumped from services.jar / miui-services.jar, yet
     *    miui-services.jar does `new-instance` it.
     * 2. does anything ever reach the real implementation
     *    `MiuiNetworkManagementService#doDesSocketForUid(String, int[], boolean)`?
     *
     * Both are answered at runtime here; nothing is modified, and only the first few
     * calls are logged (plus every call that touches a GMS uid).
     *
     * Three layers are watched, because the transport hops twice:
     *
     * `WhetstoneActivityManager` (static, client) ──AIDL "whetstone.activity"──▶
     *     `WhetstoneActivityManagerService` (server) ──▶ `MiuiNetworkManagementService` (impl)
     *
     * A single-layer probe could miss the call entirely if the server reaches netd on
     * its own, so all three are instrumented read-only.
     *
     * Why this stays an observation slot rather than being retired: a static
     * "zero callers" verdict would be a **false negative** here. The service
     * half is published — `adb shell service list` includes `whetstone.activity`
     * — so arbitrary processes can reach it over binder, and the client half is
     * not in the services.jar / miui-services.jar corpus that a dex grep covers.
     * Entry points therefore cannot be counted statically at all, which is
     * exactly why they are counted here, at runtime.
     *
     * Current reading on this device: structurally reachable, zero calls in the
     * ~6 minute observation window ⇒ "structurally reachable, never triggered",
     * **not** dead code. Only sustained zero-traffic over a much longer window
     * justifies downgrading — never a `invoke-*` count.
     */
    private fun probeSocketTeardown(classLoader: ClassLoader) {
        reportWhetstoneClasses(classLoader)
        val layers = ArrayList<String>(3)
        hookSocketTeardown(
            classLoader,
            "com.miui.whetstone.WhetstoneActivityManager",
            "doDesSocketForUid",
            "client",
            layers
        )
        hookSocketTeardown(
            classLoader,
            "com.miui.whetstone.server.WhetstoneActivityManagerService",
            "doDesSocketForUid",
            "server",
            layers
        )
        hookSocketTeardown(
            classLoader,
            "com.android.server.net.MiuiNetworkManagementService",
            "doDesSocketForUid",
            "impl",
            layers
        )
        // A layer that failed to install reported itself through logSkip above;
        // only the layers that did install are folded into the merged line.
        if (layers.isNotEmpty()) {
            recordProbe("socket-teardown=${layers.joinToString("+")}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun hookSocketTeardown(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        label: String,
        installedLayers: MutableList<String>
    ) {
        val clazz = try {
            classLoader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            logSkip("$className absent, socket-teardown probe ($label) skip")
            return
        }
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name == methodName &&
                m.parameterTypes.contentEquals(
                    arrayOf(
                        String::class.java,
                        IntArray::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                )
        }
        if (method == null) {
            logSkip("$className#$methodName/3 absent, socket-teardown probe ($label) skip")
            return
        }
        method.isAccessible = true
        hookE(method).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            try {
                val n = ++socketTeardownCount
                val pkg = chain.getArg(0) as? String
                val uids = chain.getArg(1) as? IntArray
                val all = chain.getArg(2) as? Boolean
                val gmsHit = uids?.any { isGmsUid(it) } ?: false
                if (gmsHit || n <= 10) {
                    log(
                        Log.INFO, TAG,
                        "socket-teardown probe[$label]: $methodName #$n " +
                            "(pkg=$pkg uids=${uids?.contentToString()} all=$all gmsHit=$gmsHit)"
                    )
                }
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to inspect socket-teardown args ($label)", t)
            }
            result
        }
        deoptimize(method)
        installedLayers.add(label)
    }

    /**
     * Reports whether each half of the Whetstone pair resolves in system_server,
     * and whether it declares the entry point.
     *
     * The normal reading is folded into the merged probe line as a pair of
     * counts. A half that stops resolving is *not*: it keeps its own line,
     * because a counter cannot say which half went missing.
     */
    private fun reportWhetstoneClasses(classLoader: ClassLoader) {
        var resolvable = 0
        var declaring = 0
        for (name in arrayOf(
            "com.miui.whetstone.WhetstoneActivityManager",
            "com.miui.whetstone.server.WhetstoneActivityManagerService"
        )) {
            val clazz = try {
                classLoader.loadClass(name)
            } catch (t: Throwable) {
                log(Log.INFO, TAG, "whetstone probe: $name NOT resolvable here")
                continue
            }
            resolvable++
            if (clazz.declaredMethods.any { it.name == "doDesSocketForUid" }) {
                declaring++
            }
        }
        recordProbe("whetstone=res:$resolvable/2,decl:$declaring/2")
    }

    /**
     * Read-only probe for Gate-R (action list 2.1).
     *
     * `AurogonImmobulusMode.mMessageApp` is the ROM-supplied instant-messaging
     * package list, and it is the *only* input to
     * `isNeedRestictNetworkPolicy(uid)` — which in turn is the whole body of
     * `DomesticPolicyManager#isRestrictNet`.
     *
     * The list is an **exemption** list, not a restriction list:
     * `isRestrictNet == !mMessageApp.contains(pkg)` (see the polarity note in the
     * action list). So:
     *
     * - GMS **absent** ⇒ `isRestrictNet(gmsUid)` is **true**, i.e. the ROM is
     *   willing to strip GMS networking on freeze ⇒ the hook has a real effect.
     * - GMS **present** ⇒ it already returns false and the hook would be a no-op.
     *
     * The field is `PUBLIC STATIC` and written in `<clinit>`, so a plain
     * reflective read returns the final list (and triggers class init if the
     * class has not been touched yet). Nothing is modified here.
     */
    private fun probeGmsInMessageApp(classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass("com.miui.server.greeze.AurogonImmobulusMode")
            val field = clazz.getDeclaredField("mMessageApp")
            field.isAccessible = true
            val list = field.get(null) as? Collection<*>
            val present = list?.contains(GMS_PACKAGE_NAME) ?: false
            recordProbe("mMessageApp=${list?.size ?: -1},gms=$present")
        } catch (e: NoSuchFieldException) {
            logSkip("AurogonImmobulusMode#mMessageApp absent, probe skip")
        } catch (e: ClassNotFoundException) {
            logSkip("AurogonImmobulusMode absent, mMessageApp probe skip")
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        val packageName = param.packageName
        val classLoader = param.classLoader
        this.param = Pair.create(packageName, classLoader)
        try {
            hookPackage(packageName, classLoader)
        } catch (tr: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook package", tr)
        }
        logSummary(packageName)
    }

    private fun hookPackage(packageName: String, classLoader: ClassLoader) {
        // Same reasoning as [hookSystemServer]: this is also the hot-reload entry
        // point. Only the PowerKeeper domain installs anything, so for any other
        // package both buffers are empty and the flush is a no-op.
        installPassCollecting = true
        try {
            if ("com.miui.powerkeeper" == packageName) {
                for (group in powerKeeperGroups(packageName, classLoader)) {
                    installGroup(group)
                }
            }
        } finally {
            installPassCollecting = false
            flushOtherGenerationAbsent()
            flushProbes()
        }
    }

    /** The PowerKeeper install surface: same shape and same rules as [systemServerGroups]. */
    private fun powerKeeperGroups(packageName: String, classLoader: ClassLoader): List<Group> =
        listOf(
            Group("hook", "GmsObserver") { hookGmsObserver(classLoader) },
            Group("hook", "AppStandbyController") {
                hookAppStandbyUidState(packageName, classLoader)
            },
            Group("hook", "GlobalFeatureConfigureHelper") {
                hookGlobalFeatureConfigureHelper(classLoader)
            },
            Group("hook", "NoRestrictList") { hookNoRestrictList(classLoader) },
            Group("hook", "ScenarioCompiler") { hookScenarioCompiler(classLoader) },
            Group("hook", "WeChat doze keepout") { hookWechatDozeKeepout(classLoader) },
            Group("hook", "sleep-mode network keepalive") {
                hookSleepModeNetworkKeepalive(classLoader)
            },
        )

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Hot reload is fully supported on LSPosed API 102: onHotReloaded unhooks every
        // stale handle and re-runs the whole install sequence, so nothing is left behind
        // that would need a reboot. Do not reintroduce any "reboot required" wording —
        // HELP.md / README state no reboot is needed and this method proves it.
        log(Log.INFO, TAG, "Hot reload requested — re-installing hooks without reboot")
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
                    log(Log.ERROR, TAG, "Hot reload failed", tr)
                }
            }
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
    private fun hookGreezeManagerService(classLoader: ClassLoader) {
        val GreezeManagerServiceClass =
            classLoader.loadClass("com.miui.server.greeze.GreezeManagerService")
        try {
            val isAllowBroadcastMethod = findMethod(
                GreezeManagerServiceClass,
                "isAllowBroadcast",
                Int::class.javaPrimitiveType, String::class.java,
                Int::class.javaPrimitiveType, String::class.java, String::class.java
            )
            val getPackageNameFromUidMethod = findMethod(
                GreezeManagerServiceClass,
                "getPackageNameFromUid",
                Int::class.javaPrimitiveType
            )
            getPackageNameFromUidMethod?.isAccessible = true
            if (getPackageNameFromUidMethod == null) {
                log(
                    Log.INFO, TAG,
                    "GreezeManagerService#getPackageNameFromUid absent;" +
                        " isAllowBroadcast falls back to the raw callee argument"
                )
            }
            if (isAllowBroadcastMethod == null) {
                log(Log.ERROR, TAG, "GreezeManagerService#isAllowBroadcast absent, skip")
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
                                    getInvoker(uidLookup).invoke(chain.thisObject, calleeUid)
                                if (calleePackageName is String) {
                                    calleePkgName = calleePackageName
                                }
                            }
                        } catch (e: Exception) {
                            log(Log.ERROR, TAG, "Failed to get callee package name", e)
                        }
                    }
                    val action = chain.getArg(4)
                    if (action is String) {
                        val callerUid = chain.getArg(0)
                        val callerPkg = chain.getArg(1) as? String
                        val callerPkgIsGms = GMS_PACKAGE_NAME == callerPkg
                        // P1 (2026-10-02): callerPkgName is not always populated in
                        // the caller's Slog output, but callerUid always is. Same
                        // gate, sturdier key — the two paths are counted separately
                        // so the log shows which one actually carried the decision.
                        val callerUidIsGms = callerUid is Int && isGmsUid(callerUid)
                        val calleeIsGms = GMS_PACKAGE_NAME == calleePkgName ||
                            GMS_PERSISTENT_PROCESS_NAME == calleePkgName
                        if (ACTION_REMOTE_INTENT == action &&
                            (callerPkgIsGms || callerUidIsGms) &&
                            moduleAppliesTo(calleePkgName)
                        ) {
                            broadcastGateAllowedCount++
                            if (!callerPkgIsGms) {
                                broadcastGateAllowedByUidCount++
                            }
                            if (!broadcastGateAllowedLogged) {
                                broadcastGateAllowedLogged = true
                                log(
                                    Log.INFO, TAG,
                                    "isAllowBroadcast: c2dm allowed for callee=$calleePkgName " +
                                        "(callerUid=$callerUid, callerPkg=$callerPkg, " +
                                        "matchedBy=${if (callerPkgIsGms) "callerPkg" else "callerUid"})"
                                )
                            }
                            return@intercept true
                        }
                        if (calleeIsGms && CN_DEFER_BROADCAST.contains(action)) {
                            broadcastGateCnActionCount++
                            return@intercept true
                        }
                        if (ACTION_REMOTE_INTENT == action &&
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
                                log(
                                    Log.INFO, TAG,
                                    "isAllowBroadcast: c2dm not intercepted for " +
                                        "callee=$calleePkgName (not on the allowlist); " +
                                        "left to the ROM policy"
                                )
                            }
                        }
                    }
                    chain.proceed()
                }
                deoptimize(isAllowBroadcastMethod)
                log(
                    Log.INFO, TAG,
                    "P1: isAllowBroadcast hooked; caller-uid fallback armed (arg0=callerUid)"
                )
            }
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#isAllowBroadcast", e)
        }
        try {
            val deferBroadcastForMiuiMethod = GreezeManagerServiceClass.getDeclaredMethod(
                "deferBroadcastForMiui", String::class.java
            )
            hookE(deferBroadcastForMiuiMethod).intercept { chain: XposedInterface.Chain ->
                if ((chain.getArg(0) as? String)?.let { CN_DEFER_BROADCAST.contains(it) } == true) {
                    return@intercept false
                }
                chain.proceed()
            }
            deoptimize(deferBroadcastForMiuiMethod)
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#deferBroadcastForMiui", e)
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
                    log(Log.ERROR, TAG, "Failed to clear mGmsLimitEnabled", t)
                }
                chain.proceed()
            }
            deoptimize(triggerGMSLimitActionMethod)
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#triggerGMSLimitAction", e)
        }
        forceBooleanArg(
            GreezeManagerServiceClass,
            "updateGmsNetStatus",
            false,
            "GreezeManagerService",
            Log.INFO
        )
    }

    private fun hookDomesticPolicyManager(classLoader: ClassLoader) {
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
            if (action != null && CN_DEFER_BROADCAST.contains(action)) {
                return@intercept false
            }
            if (ACTION_REMOTE_INTENT == action) {
                // Success-path counter for the tightened behaviour: a c2dm
                // broadcast that the isAllowBroadcast gate did not consume is
                // genuinely handed back to the ROM's deferral policy. Zero here
                // is not a failure — it means every c2dm was already allowed
                // upstream (every callee involved was on the list).
                deferC2dmPassthroughCount++
                if (!deferC2dmPassthroughLogged) {
                    deferC2dmPassthroughLogged = true
                    log(
                        Log.INFO, TAG,
                        "deferBroadcast: c2dm reached the ROM policy " +
                            "(P0: no longer bypassed here)"
                    )
                }
            }
            chain.proceed()
        }
        deoptimize(deferBroadcastMethod)
        log(
            Log.INFO, TAG,
            "P0: DomesticPolicyManager#deferBroadcast hooked; c2dm bypass removed, " +
                "CN reconnect actions only"
        )
        hookDomesticRestrictNet(DomesticPolicyManagerClass)
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
    private fun hookDomesticRestrictNet(domesticPolicyManagerClass: Class<*>) {
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
                        log(
                            Log.INFO, TAG,
                            "DomesticPolicyManager#isRestrictNet: kept GMS (uid $uid) unrestricted"
                        )
                    }
                    return@intercept false
                }
                if (uid is Int && isGreezeNetReleaseEnabled()) {
                    val checkedPkg = allowlistPackageForUid(uid)
                    if (checkedPkg != null) {
                        greezeNetReleaseCount++
                        // Detail budget: one line per uid, ever — the summary
                        // tick carries the volume, so repeating the same names
                        // each freeze would only spend log bytes.
                        synchronized(greezeNetReleaseLoggedUids) {
                            if (greezeNetReleaseLoggedUids.add(uid)) {
                                log(
                                    Log.INFO, TAG,
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
            deoptimize(isRestrictNetMethod)
            log(
                Log.INFO, TAG,
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
    private fun hookUdpPackageRestrict(classLoader: ClassLoader) {
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
                        log(
                            Log.INFO, TAG,
                            "udpPackageRestrict: skipped UDP filter for GMS (uid $uid)"
                        )
                    }
                    return@intercept null
                }
                chain.proceed()
            }
            deoptimize(udpPackageRestrictMethod)
            log(Log.INFO, TAG, "GreezeManagerService#udpPackageRestrict hooked for GMS")
        } catch (e: ClassNotFoundException) {
            logSkip("GreezeManagerService absent, udpPackageRestrict skip")
        } catch (e: NoSuchMethodException) {
            logSkip("GreezeManagerService#udpPackageRestrict absent, skip")
        }
    }

    private fun hookListAppsManager(classLoader: ClassLoader) {
        val ListAppsManagerClass =
            classLoader.loadClass("com.miui.server.greeze.power.ListAppsManager")
        var mSystemBlackListField: Field? = null
        try {
            mSystemBlackListField = ListAppsManagerClass.getDeclaredField("mSystemBlackList")
        } catch (e: NoSuchFieldException) {
            try {
                mSystemBlackListField = ListAppsManagerClass.getDeclaredField("SYSTEM_BLACK_LIST")
            } catch (ex: NoSuchFieldException) {
                log(
                    Log.ERROR, TAG,
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
                            val removed = mSystemBlackList?.remove(GMS_PACKAGE_NAME)
                            if (removed != null && !listAppsBlacklistPruneLogged) {
                                listAppsBlacklistPruneLogged = true
                                log(
                                    Log.INFO, TAG,
                                    "ListAppsManager: pruned GMS from system blacklist " +
                                        "(was present=$removed)"
                                )
                            }
                        } catch (e: Exception) {
                            log(Log.ERROR, TAG, "Failed to modify system blacklist", e)
                        }
                    }
                }
                deoptimize(constructor)
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
                    log(
                        Log.ERROR, TAG,
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
                        val added = mUseDataWhiteList?.add(GMS_PACKAGE_NAME)
                        // isInWhiteList is a hot query path: confirm the mutation
                        // once, then stay silent — the fixed per-line log cost
                        // dwarfs the message body, and this hook runs on every
                        // use-data query for every package.
                        if (added != null && !listAppsUseDataAddLogged) {
                            listAppsUseDataAddLogged = true
                            log(
                                Log.INFO, TAG,
                                "ListAppsManager: added GMS to use-data whitelist " +
                                    "(was absent=$added)"
                            )
                        }
                    } catch (e: Exception) {
                        log(Log.ERROR, TAG, "Failed to modify use data whitelist", e)
                    }
                    chain.proceed()
                }
            }
        } catch (e: NoSuchMethodException) {
            log(Log.ERROR, TAG, "Failed to hook ListAppsManager#isInWhiteList", e)
        }
    }

    private fun hookBroadcastQueueModernStubImpl(classLoader: ClassLoader) {
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
                val targetPackage = intent?.let { targetPackageOf(it) }
                if (callerPackage != null &&
                    GMS_PACKAGE_NAME == callerPackage &&
                    intent != null &&
                    ACTION_REMOTE_INTENT == intent.action &&
                    targetPackage != null &&
                    moduleAppliesTo(targetPackage)
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
                log(
                    Log.ERROR, TAG,
                    "Failed to modify BroadcastQueueModernStubImpl#checkApplicationAutoStart", e
                )
            }
            chain.proceed()
        }
        deoptimize(checkApplicationAutoStartMethod)

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
                    if (GMS_PACKAGE_NAME == callerPackage &&
                        intent != null &&
                        ACTION_REMOTE_INTENT == intent.action &&
                        moduleAppliesTo(calleePackage)
                    ) {
                        // Same reason string and caller uid the native pass-through
                        // path uses, so greeze bookkeeping stays consistent.
                        thawUidAsyncMethod.invoke(chain.thisObject, calleeUid, 1000, "bc_action")
                        return@intercept false
                    }
                } catch (e: Exception) {
                    log(
                        Log.ERROR, TAG,
                        "Failed to modify GreezeManagerService#isRestrictReceiver", e
                    )
                }
                chain.proceed()
            }
            deoptimize(isRestrictReceiverMethod)
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
    private fun hookGreezeBroadcastCache(classLoader: ClassLoader) {
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
                        ACTION_REMOTE_INTENT == intent.action &&
                        moduleAppliesTo(packageName)
                    ) {
                        return@intercept false
                    }
                } catch (e: Exception) {
                    log(
                        Log.ERROR, TAG,
                        "Failed to modify GreezeManagerService#isNeedCachedBroadcast", e
                    )
                }
                chain.proceed()
            }
            deoptimize(isNeedCachedBroadcastMethod)
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
    private fun hookProcessPolicy(classLoader: ClassLoader) {
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
                    addIfAbsent(whiteList, GMS_PACKAGE_NAME)
                    addIfAbsent(whiteList, GMS_PERSISTENT_PROCESS_NAME)
                    addIfAbsentInPlace(source, GMS_PACKAGE_NAME)
                    addIfAbsentInPlace(source, GMS_PERSISTENT_PROCESS_NAME)
                    return@intercept whiteList
                }
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to extend ProcessPolicy white list", t)
            }
            result
        }
    }

    private fun addIfAbsent(list: MutableList<Any?>, value: String) {
        if (!list.contains(value)) {
            list.add(value)
        }
    }

    private fun addIfAbsentInPlace(target: List<*>?, value: String) {
        if (target == null || target.contains(value)) {
            return
        }
        try {
            @Suppress("UNCHECKED_CAST")
            (target as MutableList<Any?>).add(value)
        } catch (ignored: Throwable) {
        }
    }

    private fun hookAwareResourceControl(classLoader: ClassLoader) {
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
                        pruneGmsFromNoNetworkBlacklist(mNoNetworkBlackUidsField, chain.thisObject)
                    } catch (t: Throwable) {
                        log(
                            Log.ERROR, TAG,
                            "Failed to modify AwareResourceControl.mNoNetworkBlackUids", t
                        )
                    }
                }
            }
            deoptimize(constructor)
        }
    }

    @Volatile
    private var noNetworkBlacklistMismatchLogged = false

    private fun pruneGmsFromNoNetworkBlacklist(blacklistField: Field, awareResourceControl: Any) {
        val raw = blacklistField.get(awareResourceControl)
        if (raw !is Collection<*>) {
            return
        }
        @Suppress("UNCHECKED_CAST")
        val blacklist = raw as MutableCollection<Any?>
        val removedByName = blacklist.remove(GMS_PACKAGE_NAME)
        val uid = gmsUid()
        val removedByUid = uid != null && blacklist.remove(uid)
        if (removedByUid || removedByName) {
            log(
                Log.INFO, TAG, "Removed GMS from NoNetworkBlackUids (by " +
                    (if (removedByUid) "uid $uid" else "package name") + ")"
            )
        } else if (!noNetworkBlacklistMismatchLogged) {
            noNetworkBlacklistMismatchLogged = true
            log(
                Log.INFO, TAG, "NoNetworkBlackUids (size=" + blacklist.size +
                    ") matched neither the GMS package name nor its uid" +
                    (if (uid == null) " (uid not resolvable yet)" else "")
            )
        }
    }

    private fun gmsUid(): Int? {
        return try {
            val context = getSystemContext() ?: return null
            context.packageManager.getApplicationInfo(GMS_PACKAGE_NAME, 0).uid
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
                    log(Log.WARN, TAG, "System context unavailable", t)
                }
            }
        }
        return systemContext
    }

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
    private fun hookSleepModeNetworkPolicy(classLoader: ClassLoader) {
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
        val whitelistArmed = armSleepModeWhitelist(serviceClass, whitelistField)
        val chainArmed = armSleepModeChain(serviceClass, whitelistField)
        // One install-time line for both arms, so "no sentinel fired" is never
        // the only evidence: the log states which arms exist on this ROM and
        // what a FIRED line would mean.
        log(
            Log.INFO, TAG,
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
    private fun armSleepModeWhitelist(serviceClass: Class<*>, whitelistField: Field?): Boolean {
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
                log(
                    Log.INFO, TAG,
                    "sleep-mode sentinel: legacy path FIRED — " +
                        "#setSleepModeWhitelistUidRules ran on this ROM, so this " +
                        "device no longer matches the V816 'sleep cuts the network " +
                        "in PowerKeeper, not here' reading"
                )
            }
            try {
                addGmsToSleepModeWhitelist(whitelistField, chain.thisObject)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to extend sleep-mode network whitelist", t)
            }
            chain.proceed()
        }
        deoptimize(applyMethod)
        return true
    }

    /**
     * Arm 2: the chain toggle. Entering records the whitelist size; leaving
     * carries the P4 recovery nudge, whose gate is described at the call site.
     *
     * @return true when the chain hook is installed.
     */
    private fun armSleepModeChain(serviceClass: Class<*>, whitelistField: Field?): Boolean {
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
                    log(
                        Log.INFO, TAG,
                        "sleep-mode sentinel: legacy path FIRED — " +
                            "#enableSleepModeChain ran on this ROM; sleep enters " +
                            "and exits through the per-uid chain here, not through " +
                            "PowerKeeper's radio cutoff"
                    )
                }
                log(
                    Log.INFO, TAG,
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
                    log(
                        Log.INFO, TAG,
                        "Sleep mode exited: GMS was kept on the whitelist, " +
                            "skipping recovery nudge (MCS untouched; assumes the " +
                            "whitelist path means no radio was cut — on a ROM that " +
                            "also cuts them in PowerKeeper this skip is wrong)"
                    )
                } else {
                    log(
                        Log.INFO, TAG,
                        "Sleep mode exited: GMS not whitelisted (network was cut), " +
                            "nudging GMS to reconnect"
                    )
                    val context = getSystemContext()
                    if (context != null) {
                        // Sample before the nudge: those broadcasts make GMS drop its
                        // current MCS connection, so the pre-nudge state is what tells
                        // us whether the nudge broke a connection that was still alive.
                        probeGmsTraffic("before sleep-exit nudge")
                        Thread { recoverGmsConnection(context) }.start()
                        probeBackgroundHandler().postDelayed(
                            { probeGmsTraffic("after sleep-exit nudge") },
                            GMS_TRAFFIC_NUDGE_RESAMPLE_MS
                        )
                    }
                }
                // Next session starts from a clean slate: the flag must reflect
                // what happens in *that* session, not a stale previous one.
                sGmsKeptOnSleepWhitelist = false
            }
        }
        deoptimize(chainMethod)
        return true
    }

    /**
     * Adds GMS to the "keep network during sleep mode" set. Called on the
     * service's own handler thread, right before the rules are pushed to
     * ConnectivityManager, so no other thread observes a half-updated set.
     */
    private fun addGmsToSleepModeWhitelist(whitelistField: Field, owner: Any) {
        val raw = whitelistField.get(owner)
        if (raw !is MutableCollection<*>) {
            sGmsKeptOnSleepWhitelist = false
            log(
                Log.WARN, TAG,
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
            log(
                Log.WARN, TAG,
                "Sleep mode entering: GMS uid unresolved, GMS not added"
            )
            return
        }
        if (whitelist.add(uid)) {
            sGmsKeptOnSleepWhitelist = true
            log(
                Log.INFO, TAG,
                "Sleep mode entering: kept GMS (uid $uid) on the network whitelist"
            )
        } else {
            // Already present: either the ROM populated the set itself (which the
            // static analysis says it never does) or a previous pass left it there.
            sGmsKeptOnSleepWhitelist = true
            log(
                Log.INFO, TAG,
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
    /**
     * Relaxed WiFi weak-signal switch, hooked in system_server.
     *
     * What the ROM does without us (measured on-device, V816, see
     * HOOKS_AND_DIAGNOSTICS.md §3.9): `AmlMiuiThirdPartScorer` keeps a legacy
     * score in `mLegacyIntScore` and turns it into a usable/unusable verdict at
     * a **hardcoded** 50 inside `notifyScoreAndIsUsable()`. A single call there
     * publishes everything outward — `notifyScoreUpdate(sessionId, score)` for
     * the value and `notifyStatusUpdate(sessionId, isUsable)` for the verdict —
     * so a score below 50 reaches `WifiScoreReport` as "not usable", which sets
     * the network `+EXITING` and moves the default network to cellular for 30 s.
     * Both write paths into `mLegacyIntScore` converge on that one method, so
     * it is the cheapest point at which the verdict can be withheld.
     *
     * What this hook changes, and what it deliberately does not:
     * - the clamp exists only for the lifetime of the call; the field is
     *   restored in a `finally`, so the class's own state machine still sees
     *   the real reading and nothing is persisted anywhere — this is why it
     *   replaces the earlier idea of lowering the `cloud_min_rssi_*` settings
     *   keys (those survive in `settings_system.xml` after an uninstall, and
     *   their direction turned out to be unproven anyway);
     * - the score value published upstream is what decides whether WiFi stays
     *   the default network, so reporting the floor *is* the intervention;
     * - a real failure that does not travel through this scorer — WiFi leaving,
     *   carrier or UI decisions, validation failures — is untouched;
     * - how far down it reaches is the user's call, not ours. Scores below
     *   [Prefs.KEY_WIFI_WEAK_SIGNAL_FLOOR] go through unmodified, so a link bad
     *   enough to be past saving is still handed to a mechanism that can pick
     *   another network. Every rescue is conditional on that floor, and the
     *   master switch must be on for it to be consulted at all.
     *
     * Known uncovered exit, deliberately not handled yet: the ROM also calls
     * `notifySwitchNetworkByOtherStrategies type = 1003`, which bypasses this
     * score channel entirely. It was observed 27 times in one gaming window
     * without ever causing a switch, so it is a watch item and not a fixed
     * gap — if a switch is later reported while every sub-floor score stayed
     * unflipped, check that call first before suspecting this hook. See
     * HOOKS_AND_DIAGNOSTICS.md §6.3.
     *
     * Class resolution note, which is the reason this one is not a plain
     * `classLoader.loadClass`: the class is **not reachable from system_server's
     * own classloader**. `miui-wifi-service.jar` is loaded separately (the build
     * that runs it is the one whose logcat emits `AmlMiuiThirdPartScorer` from
     * the system_server pid), so both call sites below return ClassNotFound
     * while the class is plainly live in the process — the first attempt at this
     * hook reported "absent" on a ROM where the class was demonstrably running.
     * The fallback therefore walks to the loader that did load it, through the
     * binder of a service whose implementation comes from the same jar.
     */
    private val wifiScorerResolved = AtomicBoolean(false)
    // Two throttles, not one: clamping and handing back are opposite verdicts,
    // and sharing a window between them lets whichever is louder starve the
    // other — which would leave the rarer one looking like it never happened.
    private val lastWifiScoreClampLogMs = AtomicLong(0L)
    private val lastWifiScoreSkipLogMs = AtomicLong(0L)

    private fun hookWifiWeakSignalSwitch(classLoader: ClassLoader) {
        if (tryHookWifiScorer(classLoader)) {
            return
        }
        // Nothing to hook yet: at system_server start the WiFi service has not
        // been published, so there is no loader to borrow. Retry off-thread
        // instead of giving up — the summary line below is printed before this
        // resolves, so its counts describe the synchronous phase only.
        val waiter = Thread({
            for (attempt in 1..40) {
                try {
                    Thread.sleep(3000L)
                } catch (ignored: InterruptedException) {
                    return@Thread
                }
                if (tryHookWifiScorer(classLoader)) {
                    return@Thread
                }
            }
            logSkipOtherGeneration(
                "AmlMiuiThirdPartScorer (unreachable after retry)"
            )
        }, "fcmlive-wifi-scorer")
        waiter.isDaemon = true
        waiter.start()
    }

    /** Installs the scorer hook if the class can be reached this time. */
    private fun tryHookWifiScorer(classLoader: ClassLoader): Boolean {
        if (wifiScorerResolved.get()) {
            return true
        }
        val scorerClass = resolveWifiScorerClass(classLoader) ?: return false
        val notifyMethod = try {
            scorerClass.getDeclaredMethod(WIFI_SCORER_NOTIFY_METHOD)
        } catch (t: Throwable) {
            logSkip("AmlMiuiThirdPartScorer#notifyScoreAndIsUsable absent, wifi-weak-signal switch skip")
            wifiScorerResolved.set(true)
            return true
        }
        val scoreField = try {
            scorerClass.getDeclaredField(WIFI_SCORER_SCORE_FIELD)
        } catch (t: Throwable) {
            logSkip("AmlMiuiThirdPartScorer#mLegacyIntScore absent, wifi-weak-signal switch skip")
            wifiScorerResolved.set(true)
            return true
        }
        notifyMethod.isAccessible = true
        scoreField.isAccessible = true
        hookE(notifyMethod).intercept { chain: XposedInterface.Chain ->
            if (!isWifiWeakSignalSwitchRelaxed()) {
                chain.proceed()
            } else {
                // Read here rather than outside, which is also what gates it:
                // this branch only runs while the master switch is on, so the
                // sub-option cannot survive its parent being turned off.
                val floor = readWifiWeakSignalFloor()
                val self = chain.thisObject
                var reported = 0
                var read = false
                var clamped = false
                try {
                    reported = scoreField.getInt(self)
                    read = true
                    // Both halves are load-bearing. `reported < WIFI_SCORE_USABLE_MIN`
                    // is the ROM's own verdict — nothing above it needs saving,
                    // and rewriting it would invent a decision the ROM did not
                    // make. `reported >= floor` is how deep the user lets this
                    // reach; below that the score goes through untouched, which
                    // is why there is no third branch.
                    if (reported < WIFI_SCORE_USABLE_MIN && reported >= floor) {
                        scoreField.setInt(self, WIFI_SCORE_CLAMP_TARGET)
                        clamped = true
                    }
                } catch (ignored: Throwable) {
                    // Fail closed for the intervention and never for the call:
                    // the ROM's own verdict goes through untouched.
                }
                if (clamped) {
                    noteWifiScoreClamp(reported, floor)
                } else if (read && reported < WIFI_SCORE_USABLE_MIN) {
                    // Handing back is a decision too, and one that later looks
                    // like "the hook missed it". Say so while it happens. The
                    // upper bound matters as much as the lower one: most scores
                    // a healthy link gets are above the floor already, and those
                    // were never ours to hand back — logging them would read as
                    // an intervention every thirty seconds on a quiet day.
                    noteWifiScoreSkip(reported, floor)
                }
                try {
                    chain.proceed()
                } finally {
                    if (clamped) {
                        try {
                            scoreField.setInt(self, reported)
                        } catch (ignored: Throwable) {
                            log(Log.WARN, TAG, "wifi-weak-signal: score restore failed")
                        }
                    }
                }
            }
        }
        deoptimize(notifyMethod)
        wifiScorerResolved.set(true)
        // Sentinel wording, same discipline as doze-keepout: hooked is not
        // active, and active is not the same as "the ROM would have switched".
        log(
            Log.INFO, TAG,
            "AmlMiuiThirdPartScorer#notifyScoreAndIsUsable hooked (wifi-weak-signal " +
                "switch, default off): idle until the experiment switch is on, then " +
                "scores from the chosen floor up to $WIFI_SCORE_USABLE_MIN are " +
                "reported as usable; deeper ones are left to the ROM"
        )
        return true
    }

    /**
     * The class lives outside the process' main classpath, so ask the loaders
     * that could plausibly own it: our own, the system one, and — the one that
     * actually works here — the loader of any registered service whose
     * implementation ships in the same jar.
     */
    private fun resolveWifiScorerClass(classLoader: ClassLoader): Class<*>? {
        val candidates = mutableListOf<ClassLoader>(classLoader)
        try {
            candidates.add(ClassLoader.getSystemClassLoader())
        } catch (ignored: Throwable) {
        }
        for (service in WIFI_SCORER_CLASS_LOADER_SERVICES) {
            val impl = serviceBinder(service) ?: continue
            val owner = impl.javaClass.classLoader ?: continue
            if (!candidates.contains(owner)) {
                candidates.add(owner)
            }
        }
        for (candidate in candidates) {
            try {
                return candidate.loadClass(WIFI_SCORER_CLASS)
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    /**
     * Local (raw) binder of a service, or null before it is published. From
     * inside system_server `getService` returns the implementation object, so
     * its class carries the loader we need; the reflection keeps this off the
     * hidden-API surface the module would otherwise depend on at compile time.
     */
    private fun serviceBinder(name: String): IBinder? {
        return try {
            val manager = Class.forName("android.os.ServiceManager")
            val binder = manager.getMethod("getService", String::class.java)
                .invoke(null, name)
            binder as? IBinder
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * Throttled trace of an actual intervention. The ROM re-scores on its own
     * cadence, so an unthrottled line here would be the noisiest thing the
     * module does; 30 s keeps one line per visible link-quality episode while
     * still proving the hook had something to do. The score is printed because
     * it is the only value that says *how far* below the ROM's own 50 the link
     * was judged — without it, "it fired" and "it fired at 49" look alike, and
     * those mean very different things for deciding whether to keep the switch.
     */
    private fun noteWifiScoreClamp(actual: Int, floor: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWifiScoreClampLogMs.get() < WIFI_SCORE_CLAMP_LOG_INTERVAL_MS) {
            return
        }
        lastWifiScoreClampLogMs.set(now)
        log(
            Log.INFO, TAG,
            "wifi-weak-signal: reported $actual met the chosen floor $floor but would " +
                "have failed the ROM's $WIFI_SCORE_USABLE_MIN; reported as usable instead"
        )
    }

    /**
     * The same trace for the opposite verdict. Without it, "the network went to
     * cellular anyway" reads as a hook that failed to fire when in fact it
     * fired and chose not to act — and since [Prefs.KEY_WIFI_WEAK_SIGNAL_FLOOR]
     * is what decides, the line names the floor rather than leaving it to be
     * guessed from the score.
     */
    private fun noteWifiScoreSkip(actual: Int, floor: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWifiScoreSkipLogMs.get() < WIFI_SCORE_CLAMP_LOG_INTERVAL_MS) {
            return
        }
        lastWifiScoreSkipLogMs.set(now)
        log(
            Log.INFO, TAG,
            "wifi-weak-signal: reported $actual is below the chosen floor $floor; " +
                "left to the ROM's own policy"
        )
    }

    /**
     * The flag lives in the shared config group and is read lazily at each
     * score update: the class has no broadcast receiver, and updates arrive on
     * its own handler thread, so the read frequency is that of score updates,
     * not of general connectivity traffic.
     */
    private fun isWifiWeakSignalSwitchRelaxed(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED, false)
        } catch (ignored: Throwable) {
            // Fail closed: an unreadable switch must not start deciding how the
            // ROM judges its own WiFi link.
            false
        }
    }

    /**
     * The floor the sub-option chose, read at the same lazy cadence as the
     * master flag.
     *
     * Unreadable or off-list both land on the default — the narrowest floor
     * rather than the widest. The two failures are the opposite direction to
     * guess in, and only one of them is recoverable by reading it again; if the
     * list ever loses a value, an unrestorable read here means the module
     * quietly starts covering depths nobody chose.
     */
    private fun readWifiWeakSignalFloor(): Int {
        return try {
            Prefs.sanitizeWeakSignalFloor(
                getRemotePreferences(Prefs.GROUP_CONFIG)
                    .getInt(Prefs.KEY_WIFI_WEAK_SIGNAL_FLOOR, Prefs.WIFI_WEAK_SIGNAL_FLOOR_DEFAULT)
            )
        } catch (ignored: Throwable) {
            Prefs.WIFI_WEAK_SIGNAL_FLOOR_DEFAULT
        }
    }

    private fun hookWechatDozeKeepout(classLoader: ClassLoader) {
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
            if (pkgs == null || !isWechatDozeKeepoutEnabled()) {
                return@intercept chain.proceed()
            }
            if (!pkgs.contains(WECHAT_PACKAGE_NAME)) {
                return@intercept chain.proceed()
            }
            val kept = pkgs.filter { it != WECHAT_PACKAGE_NAME }.toTypedArray()
            val dropped = ++wechatDozeKeepoutCount
            if (dropped == 1L || dropped % 10L == 0L) {
                log(
                    Log.INFO, TAG,
                    "doze-keepout: dropped WeChat from a battery-optimization " +
                        "whitelist write #$dropped"
                )
            }
            val args = chain.args.toTypedArray()
            args[1] = kept
            chain.proceed(args)
        }
        deoptimize(addMethod)
        // Sentinel wording, per the misread that cost a night: hooked does not
        // mean active, and active does not mean the stored entry was cleared.
        log(
            Log.INFO, TAG,
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
    private fun isWechatDozeKeepoutEnabled(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_WECHAT_DOZE_KEEPOUT, false)
        } catch (ignored: Throwable) {
            // Fail closed: an unreadable switch must not start deciding
            // where the system puts an app.
            false
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
    private fun hookSleepModeNetworkKeepalive(classLoader: ClassLoader) {
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
            hookSleepModeEarthquakeFlag(classLoader)
            log(
                Log.INFO, TAG,
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
            if (!isSleepKeepaliveEnabled()) {
                log(
                    Log.INFO, TAG,
                    "sleep-mode: WiFi left to the ROM policy (keepalive master switch off)"
                )
                return@intercept chain.proceed()
            }
            log(
                Log.INFO, TAG,
                "sleep-mode: kept WiFi on (sleep would have turned it off)"
            )
            // Report success so the caller does not retry or log a failure.
            true
        }
        deoptimize(wifiMethod)

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
            if (!isSleepKeepaliveDataEnabled()) {
                log(
                    Log.INFO, TAG,
                    "sleep-mode: mobile data left to the ROM policy " +
                        "(keepalive data switch off)"
                )
                return@intercept chain.proceed()
            }
            // No pairing rule with the WiFi hook: each radio follows its own
            // switch, and a night where only one of them is kept up is a
            // configuration the user can ask for.
            log(
                Log.INFO, TAG,
                "sleep-mode: kept mobile data on (keepalive data switch is on)"
            )
            null
        }
        deoptimize(dataMethod)

        // Say plainly that nothing happens yet: both switches are off by
        // default, and a hooked-but-idle hook must not read as a working one.
        // That misread already cost a night of diagnosis.
        log(
            Log.INFO, TAG,
            "Sleep-mode network keepalive hooked: idle until an experiment " +
                "switch is on, then each radio stays up under its own switch " +
                "(WiFi / mobile data); the ROM runs its full cutoff path so " +
                "SleepState still records what to restore."
        )
    }

    /** True when the current call stack is a sleep-mode entry applying its config. */
    private fun calledFromSleepApply(): Boolean {
        for (frame in Thread.currentThread().stackTrace) {
            if (SLEEP_CONTROLLER_CLASS == frame.className &&
                SLEEP_APPLY_METHOD == frame.methodName
            ) {
                return true
            }
        }
        return false
    }

    /** True when the stack is either sleep entry or exit; both must see the same flag. */
    private fun calledFromSleepConfig(): Boolean {
        for (frame in Thread.currentThread().stackTrace) {
            if (SLEEP_CONTROLLER_CLASS == frame.className &&
                (SLEEP_APPLY_METHOD == frame.methodName ||
                    SLEEP_RESTORE_METHOD == frame.methodName)
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
    private fun hookSleepModeEarthquakeFlag(classLoader: ClassLoader) {
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
            if (SLEEP_EARTHQUAKE_KEY != chain.getArg(1)) {
                return@intercept chain.proceed()
            }
            if ((!isSleepKeepaliveEnabled() && !isSleepKeepaliveDataEnabled()) ||
                !calledFromSleepConfig()
            ) {
                return@intercept chain.proceed()
            }
            log(
                Log.INFO, TAG,
                "sleep-mode: reported life-safety reachability on, " +
                    "PowerKeeper will skip the network cutoff"
            )
            1
        }
        deoptimize(getIntForUser)
    }

    /**
     * Keepalive switch, read lazily like the WeChat keepout's.
     *
     * Fails to *disabled*: this is an opt-in experiment
     * that overrides a power-saving decision the user asked for, so an
     * unreadable switch must leave sleep mode alone rather than quietly
     * keeping both radios up all night.
     */
    private fun isSleepKeepaliveEnabled(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
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
    private fun isSleepKeepaliveDataEnabled(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_SLEEP_KEEPALIVE_DATA, false)
        } catch (ignored: Throwable) {
            false
        }
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
    private fun forceBooleanArg(
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
    private fun hookGmsObserver(classLoader: ClassLoader) {
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
                deoptimize(initGmsChainMethod)
            } catch (e: NoSuchMethodException) {
                logSkipOtherGeneration("NetdExecutor#initGmsChain")
            }
            // Present on both generations, but dead on OS4: the method exists
            // (so this never reports absent) yet no dex in the OS4 ROM invokes
            // it — only OS3's NetdExecutor does. Kept because a hook that
            // resolves is free, and the polarity (false) is the safe direction
            // regardless of whether anything still calls it.
            try {
                val setGmsDnsBlockerStateMethod = NetdExecutorClass.getDeclaredMethod(
                    "setGmsDnsBlockerState",
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                )
                hookE(setGmsDnsBlockerStateMethod).intercept { chain: XposedInterface.Chain ->
                    val args = chain.args.toTypedArray()
                    if (args.size > 1) {
                        args[1] = false
                    }
                    chain.proceed(args)
                }
                deoptimize(setGmsDnsBlockerStateMethod)
            } catch (e: NoSuchMethodException) {
                logSkip("NetdExecutor#setGmsDnsBlockerState absent, skip")
            }
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
                    // 即 true=开墙阻断 GMS；与 setGmsDnsBlockerState(true→"deny") 一致。
                    if (args.size > 1) {
                        args[1] = false
                    }
                    chain.proceed(args)
                }
                deoptimize(setGmsChainStateMethod)
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
                            // Gate by uid, never rewrite blind. On this ROM the
                            // only PowerKeeper caller is the GMS-only wrapper
                            // setGmsDnsBlockerState(IZ), so the gate is pure
                            // future-proofing: a ROM generation that adds
                            // callers for other uids passes through untouched
                            // instead of having its rule silently flipped to
                            // "allow". Unparseable uid also passes through —
                            // never rewrite what cannot be proven to be GMS.
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
                                log(
                                    Log.INFO, TAG,
                                    "standby-firewall: suppressed 'enablemiuistandby enable' #$skipped"
                                )
                            }
                            return@intercept skipValue
                        }
                    }
                    chain.proceed()
                }
                deoptimize(executeMethod)
                // Folded into the merged `probe:` line. This is the same kind of
                // static signature fact as the other probe findings, and it was
                // the only probe in this process printing on a line of its own.
                val skipText = skipValue?.toString() ?: "null"
                recordProbe("NetdExecutor#execute->${executeReturn.name},skip=$skipText")
            } catch (e: NoSuchMethodException) {
                logSkip("NetdExecutor#execute not found, skip command-level GMS net hooks")
            }
        } catch (e: ClassNotFoundException) {
            log(Log.ERROR, TAG, "Failed to hook NetdExecutor", e)
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
                forceBooleanArg(GmsObserverClass, legacyName, false, "GmsObserver")
            }
            // OS3-only (cross-generation): the hard "disable GMS" entries.
            // Absent on OS4, which has no local state machine to gate them.
            for (alwaysSkip in arrayOf("disableGms", "disableGmsApps")) {
                try {
                    val disableMethod = GmsObserverClass.getDeclaredMethod(alwaysSkip)
                    hookE(disableMethod).intercept { _: XposedInterface.Chain -> null }
                    deoptimize(disableMethod)
                } catch (e: NoSuchMethodException) {
                    logSkipOtherGeneration("GmsObserver#$alwaysSkip")
                }
            }
            // OS3-only (cross-generation): the three flag setters of the local
            // state machine. Absent on OS4 for the same reason as above.
            for (limitFlag in arrayOf("updateGmsEnabled", "updateGmsState", "updateGmsInstalled")) {
                forceBooleanArg(GmsObserverClass, limitFlag, false, "GmsObserver")
            }
            // OS4's live limit exit, and the reason this whole function still
            // earns its place on the test generation: OS4 routes the sole GMS
            // network limit through here (reflection into greeze), not through
            // any of the OS3 methods above. INFO (not DEBUG) on purpose — this
            // one is load-bearing, so a miss on the current generation must be
            // visible in the install summary.
            forceBooleanArg(
                GmsObserverClass, "updateFrameworkGmsNetStatus", false, "GmsObserver", Log.INFO
            )
            // OS4 reachability callback, pinned to "reachable".
            forceBooleanArg(
                GmsObserverClass, "onGoogleReachabilityChanged", true, "GmsObserver", Log.INFO
            )
            try {
                val bridgeMethod = GmsObserverClass.getDeclaredMethod(
                    "c", GmsObserverClass, Boolean::class.javaPrimitiveType
                )
                hookE(bridgeMethod).intercept { chain: XposedInterface.Chain ->
                    val args = chain.args.toTypedArray()
                    args[1] = true
                    chain.proceed(args)
                }
                deoptimize(bridgeMethod)
                // Install confirmation on purpose: "c" is an obfuscated name
                // that drifts across ROM generations, and a silent failure here
                // would look identical to the hook working. This line is the
                // drift alarm — its absence after a ROM update is a finding.
                log(
                    Log.INFO, TAG,
                    "GmsObserver#c (obfuscated connected-bridge) hooked, forced connected=true"
                )
            } catch (ignored: NoSuchMethodException) {
                logSkip("GmsObserver#c (obfuscated connected-bridge) absent, skip")
            }
        } catch (e: ClassNotFoundException) {
            log(Log.ERROR, TAG, "Failed to hook GmsObserver", e)
        }
        var disconnectHooked = false
        for (i in 1..8) {
            if (disconnectHooked) break
            val listenerName = "com.miui.powerkeeper.utils.GmsObserver\$$i"
            try {
                val GmsObserverListenerClass = classLoader.loadClass(listenerName)
                try {
                    val disconnectMethod =
                        GmsObserverListenerClass.getDeclaredMethod("googleNetworkDisconnect")
                    hookE(disconnectMethod).intercept { _: XposedInterface.Chain -> null }
                    deoptimize(disconnectMethod)
                    disconnectHooked = true
                    log(Log.INFO, TAG, listenerName + "#googleNetworkDisconnect hooked")
                } catch (ignored: NoSuchMethodException) {
                }
            } catch (ignored: ClassNotFoundException) {
            }
        }
        if (!disconnectHooked) {
            logSkip("GmsObserver\$*#googleNetworkDisconnect absent, skip disconnect rewrite")
        }
    }

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
    private fun hookAppStandbyUidState(packageName: String, classLoader: ClassLoader) {
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
                            log(
                                Log.INFO, TAG,
                                "AppStandbyController#setUidState: kept GMS (uid $uid) allowed"
                            )
                        }
                    }
                    chain.proceed(args)
                }
                deoptimize(setUidStateMethod)
                // Tag the log with pkg/userId: this line repeats once per
                // package-ready pass (every hot reload re-runs it), so a raw
                // count reads like several hooks when setId() has in fact
                // collapsed them into a single live one.
                log(
                    Log.INFO, TAG,
                    "AppStandbyController#setUidState hooked for GMS allow re-assert" +
                        " (pkg=$packageName, userId=${Process.myUid() / 100000})"
                )
            } catch (e: NoSuchMethodException) {
                logSkip("AppStandbyController#setUidState absent, skip")
            }
        } catch (e: ClassNotFoundException) {
            logSkip("AppStandbyController class absent, skip")
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

    private fun hookGlobalFeatureConfigureHelper(classLoader: ClassLoader) {
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
                                val hasGms = result.contains(GMS_PACKAGE_NAME)
                                if (!sDozeSentinelReachedLogged) {
                                    sDozeSentinelReachedLogged = true
                                    log(
                                        Log.INFO, TAG,
                                        "doze-wl-sentinel: reached (arg=${argType.simpleName}), " +
                                            "size=${result.size}, hasGms=$hasGms"
                                    )
                                }
                                if (!hasGms) {
                                    sDozeSentinelInjectCount++
                                    if (sDozeSentinelInjectCount == 1 ||
                                        sDozeSentinelInjectCount % 10 == 0
                                    ) {
                                        log(
                                            Log.INFO, TAG,
                                            "doze-wl-sentinel: GMS missing from doze whitelist, " +
                                                "injected #$sDozeSentinelInjectCount (size ${result.size})"
                                        )
                                    }
                                    val source = result
                                    val whiteList = ArrayList<Any?>(source)
                                    whiteList.add(GMS_PACKAGE_NAME)
                                    addIfAbsentInPlace(source, GMS_PACKAGE_NAME)
                                    return@intercept whiteList
                                }
                            }
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "Failed to extend doze white list", t)
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
            log(Log.ERROR, TAG, "Failed to hook GlobalFeatureConfigureHelper", e)
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
    private fun hookNoRestrictList(classLoader: ClassLoader) {
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
                        addIfAbsent(result as MutableList<Any?>, GMS_PACKAGE_NAME)
                    } else if (result is List<*>) {
                        val copy = ArrayList<Any?>(result)
                        addIfAbsent(copy, GMS_PACKAGE_NAME)
                        return@intercept copy
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "Failed to extend getNoRestrictApps", t)
                }
                try {
                    ensureGmsUserTableBgControl()
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "Failed to write back userTable.bgControl", t)
                }
                result
            }
            deoptimize(getNoRestrictAppsMethod)
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
                        val touchesGms = copy.any { it == GMS_PACKAGE_NAME }
                        if (touchesGms) {
                            for (i in copy.indices) {
                                val a = copy[i]
                                if (a is String && a != GMS_PACKAGE_NAME && a != COL_BG_CONTROL) {
                                    copy[i] = BG_CONTROL_NO_RESTRICT
                                    log(
                                        Log.INFO, TAG,
                                        "setBgControl: forced GMS control $a -> $BG_CONTROL_NO_RESTRICT"
                                    )
                                }
                            }
                            args = copy
                        }
                    }
                    val result = if (args != null) chain.proceed(args) else chain.proceed()
                    try {
                        ensureGmsUserTableBgControl()
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "userTable re-assert after ${method.name} failed", t)
                    }
                    result
                }
                deoptimize(method)
                reAssertHooked.add(method.name)
            }
            if (reAssertHooked.isNotEmpty()) {
                log(
                    Log.INFO, TAG,
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
                    ensureGmsInMilletSetting()
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "Failed to repair MILLET_NO_RESTRICT_APP", t)
                }
            }
            deoptimize(dealNoRestrictAppMethod)
        } catch (e: NoSuchMethodException) {
            logSkip("ActiveStateController#dealNoRestrictApp absent, skip")
        } catch (e: ClassNotFoundException) {
            logSkip("ActiveStateController class absent, skip")
        }
    }

    /**
     * Read Settings.System.MILLET_NO_RESTRICT_APP and append GMS when missing.
     * Preserves every existing entry and ordering. Also writes GMS's
     * userTable.bgControl back to "noRestrict" so the source row matches.
     * After a repair, triggers P4 recovery so an already-frozen GMS gets a
     * chance to reconnect.
     *
     * Write-surface audit (2026-10-03, extended 2026-10-08): these are the only
     * **five** places in the module that persist anything outside its own prefs —
     * this one, [ensureGmsUserTableBgControl], the WeChat doze keepout (default
     * off), and the autostart op writes ([writeAutostartIfNeeded]: `setMode(10008)`,
     * plus `setMode(10053)` behind its own sub-rung — both default off). All of
     * them widen a restriction; none can blacklist an app, and there is no path
     * that leaves an app worse off after uninstalling the module than it was
     * before installing it. Runtime-only effects a reboot erases — sleep-mode
     * uid injections, netd/DNS rule rewrites, in-memory hook answers — are
     * deliberately not counted here: this list tracks what persists. Keep it
     * that way: a new write has to justify itself against this list.
     */
    private fun ensureGmsInMilletSetting() {
        ensureGmsUserTableBgControl()
        val context = getSystemContext() ?: getPowerKeeperContext() ?: return
        val resolver = context.contentResolver
        val raw = android.provider.Settings.System.getString(resolver, MILLET_NO_RESTRICT_APP_KEY)
            ?: ""
        val entries = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.contains(GMS_PACKAGE_NAME)) return
        val updated = if (entries.isEmpty()) {
            GMS_PACKAGE_NAME
        } else {
            entries.joinToString(", ") + ", " + GMS_PACKAGE_NAME
        }
        android.provider.Settings.System.putString(resolver, MILLET_NO_RESTRICT_APP_KEY, updated)
        log(Log.INFO, TAG, "MILLET_NO_RESTRICT_APP: appended GMS (was: $raw)")
        // P4: if GMS was frozen during the missing-entry window, nudge it awake.
        recoverGmsConnection(context)
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
    private fun ensureGmsUserTableBgControl() {
        if (userTableReassertInFlight) return
        userTableReassertInFlight = true
        try {
            val pk = getPowerKeeperContext()
            val sys = getSystemContext()
            val context = pk ?: sys
            if (context == null) {
                log(Log.WARN, TAG, "userTable: no Context (powerKeeper=$pk system=$sys), skip write-back")
                return
            }
            log(Log.INFO, TAG, "userTable: ensure GMS bgControl via ${if (pk != null) "powerkeeper" else "system"}")
            val uri = android.net.Uri.parse(USER_TABLE_URI)
            var current: String? = null
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(COL_BG_CONTROL),
                    "$COL_PKG_NAME = ?",
                    arrayOf(GMS_PACKAGE_NAME),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        current = cursor.getString(0)
                    }
                }
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "userTable: query failed", t)
                return
            }
            log(Log.INFO, TAG, "userTable: GMS current bgControl=$current")
            if (current == BG_CONTROL_NO_RESTRICT) return

            val values = android.content.ContentValues()
            values.put(COL_BG_CONTROL, BG_CONTROL_NO_RESTRICT)
            try {
                val updated = context.contentResolver.update(
                    uri,
                    values,
                    "$COL_PKG_NAME = ?",
                    arrayOf(GMS_PACKAGE_NAME)
                )
                log(Log.INFO, TAG, "userTable: update $current -> $BG_CONTROL_NO_RESTRICT count=$updated")
                if (updated > 0) return
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "userTable: update failed", t)
            }
            values.put(COL_PKG_NAME, GMS_PACKAGE_NAME)
            values.put(COL_USER_ID, 0)
            values.put(COL_LAST_CONFIGURED, System.currentTimeMillis())
            try {
                val inserted = context.contentResolver.insert(uri, values)
                log(Log.INFO, TAG, "userTable: insert result=$inserted")
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "userTable: insert GMS row failed", t)
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "userTable: ensure failed", t)
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
    private fun recoverGmsConnection(context: Context) {
        for (target in arrayOf(GMS_PACKAGE_NAME, GSF_PACKAGE_NAME)) {
            for (action in RECOVERY_BROADCAST_ACTIONS) {
                try {
                    val intent = Intent(action)
                    intent.setPackage(target)
                    context.sendBroadcast(intent)
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "Failed to send $action to $target", t)
                }
            }
        }
        log(Log.INFO, TAG, "P4: recovery broadcasts sent to GMS+GSF")
        try {
            val uri = android.net.Uri.parse(CHIMERA_PROVIDER_URI)
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.close()
            log(Log.INFO, TAG, "Chimera provider query completed")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Chimera provider query failed (non-fatal)", t)
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
     * Field layout (verified from OS3/OS4 PowerKeeper DEX):
     *   PowerKeeperAppConfigure.pkg : String
     *   PowerKeeperAppConfigure.scenario : int
     *
     * OS3 fillScenarioContent(Context,int,PowerKeeperAppConfigure,
     *   UserConfigureHelper,String,List,List)V
     * OS4 adds a trailing Map parameter.
     */
    private fun hookScenarioCompiler(classLoader: ClassLoader) {
        val configureClass =
            classLoader.loadClass("com.miui.powerkeeper.provider.PowerKeeperAppConfigure")
        val pkgField = configureClass.getDeclaredField("pkg")
        pkgField.isAccessible = true
        val scenarioField = configureClass.getDeclaredField("scenario")
        scenarioField.isAccessible = true

        // OS3: 7 params; OS4: 8 params (extra Map). PowerKeeperAppConfigure is arg 2.
        for (paramCount in intArrayOf(7, 8)) {
            val method = configureClass.declaredMethods.firstOrNull { m ->
                m.name == "fillScenarioContent" && m.parameterCount == paramCount
            } ?: continue

            hookE(method).intercept { chain: XposedInterface.Chain ->
                chain.proceed()
                try {
                    val cfg = chain.getArg(2) ?: return@intercept null
                    val pkg = pkgField.get(cfg) as? String ?: return@intercept null
                    if (GMS_PACKAGE_NAME != pkg) return@intercept null
                    val scenario = scenarioField.getInt(cfg)
                    if (scenario == SCENARIO_MUI_AUTO_GMS) {
                        scenarioField.setInt(cfg, SCENARIO_NO_RESTRICT)
                        log(
                            Log.INFO, TAG,
                            "P3: rewrote GMS scenario $SCENARIO_MUI_AUTO_GMS → $SCENARIO_NO_RESTRICT"
                        )
                    }
                    try {
                        ensureGmsUserTableBgControl()
                    } catch (t: Throwable) {
                        log(Log.WARN, TAG, "P3: userTable write-back failed", t)
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "P3: failed to rewrite GMS scenario", t)
                }
                null
            }
            deoptimize(method)
            log(Log.INFO, TAG, "PowerKeeperAppConfigure#fillScenarioContent($paramCount args) hooked for P3")
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
    private fun hookGreezerNoRestrict(classLoader: ClassLoader) {
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
                    if (GMS_PACKAGE_NAME == pkg) {
                        return@intercept true
                    }
                    chain.proceed()
                }
                deoptimize(isNoRestrictAppMethod)
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
                    if (GMS_PACKAGE_NAME == pkg) {
                        return@intercept false
                    }
                    chain.proceed()
                }
                deoptimize(isNoRestrictFreezeableMethod)
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
                    deoptimize(method)
                }
                log(Log.INFO, TAG, "AurogonImmobulusMode#triggerQuickFreeze hooked")
            }
        } catch (e: ClassNotFoundException) {
            logSkip("AurogonImmobulusMode class absent, skip")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook AurogonImmobulusMode", e)
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
                    deoptimize(method)
                    log(Log.INFO, TAG, "PolicyMaker#isAllowFreeze hooked (${method.returnType.simpleName})")
                }
            }
        } catch (e: ClassNotFoundException) {
            logSkip("PolicyMaker class absent, skip")
        } catch (e: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook PolicyMaker#isAllowFreeze", e)
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
            val info = context.packageManager.getApplicationInfo(GMS_PACKAGE_NAME, 0)
            val resolved = info.uid % 100000
            cachedGmsAppId = resolved
            appId == resolved
        } catch (ignored: Throwable) {
            false
        }
    }

    @Volatile
    private var cachedGmsAppId: Int? = null

    @Volatile
    private var sAllowlist: Set<String> = emptySet()

    /** Whitelist writes this process has seen WeChat dropped from (doze-keepout). */
    private var wechatDozeKeepoutCount = 0L


    private fun loadAllowlistFromRemotePrefs() {
        try {
            val prefs = getRemotePreferences(Prefs.GROUP_CONFIG)
            val set = prefs.getStringSet(Prefs.KEY_ALLOWLIST, emptySet())
            val loaded = if (set != null) HashSet(set) else HashSet()
            if (loaded != sAllowlist) {
                // Log on content change, not on every read: the stale-path reload
                // would otherwise repeat an identical line every ALLOWLIST_STALE_MS.
                // The package count is deliberately not printed: without the names
                // it only ever said "not empty", which the boolean says directly,
                // and a 9-to-5 edit read as the same line either way.
                log(
                    Log.INFO, TAG,
                    "allowlist loaded: selected=${loaded.isNotEmpty()}"
                )
            }
            sAllowlist = loaded
            sAllowlistFreshMs = SystemClock.uptimeMillis()
            sAllowlistFailureStreak = 0
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Failed to read remote allowlist", e)
            // A failed read must not push the next lazy retry a full
            // ALLOWLIST_STALE_MS into the future ("failure delays retry").
            // Backdate the freshness stamp so getFcmAllowlist() retries after an
            // exponentially growing backoff (1s, 2s, 4s ... capped at
            // ALLOWLIST_STALE_MS). sAllowlistReadMs keeps the real attempt time,
            // so requestAllowlistReload() still throttles repeated reads.
            val streak = ++sAllowlistFailureStreak
            val backoffMs = minOf(
                ALLOWLIST_FAILURE_BACKOFF_BASE_MS shl (streak - 1).coerceAtMost(4),
                ALLOWLIST_STALE_MS
            )
            sAllowlistFreshMs =
                SystemClock.uptimeMillis() - ALLOWLIST_STALE_MS + backoffMs
        }
        sAllowlistReadMs = SystemClock.uptimeMillis()
    }

    private fun hookAllowlist() {
        loadAllowlistFromRemotePrefs()
        installAllowlistReceiverAsync()
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

    private fun requestAllowlistReload() {
        val sinceLastRead = SystemClock.uptimeMillis() - sAllowlistReadMs
        if (sinceLastRead >= ALLOWLIST_RELOAD_MIN_MS) {
            allowlistBackgroundHandler().post { loadAllowlistFromRemotePrefs() }
            return
        }
        if (!allowlistReloadQueued.compareAndSet(false, true)) {
            return
        }
        allowlistBackgroundHandler().postDelayed({
            allowlistReloadQueued.set(false)
            loadAllowlistFromRemotePrefs()
        }, ALLOWLIST_RELOAD_MIN_MS - sinceLastRead)
    }

    private fun installAllowlistReceiverAsync() {
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
            for (attempt in 0 until ALLOWLIST_REGISTER_MAX_ATTEMPTS) {
                val lastAttempt = attempt == ALLOWLIST_REGISTER_MAX_ATTEMPTS - 1
                if (registerAllowlistReceiver(reportFailure = lastAttempt)) {
                    return@Thread
                }
                if (lastAttempt) {
                    break
                }
                try {
                    Thread.sleep(ALLOWLIST_REGISTER_RETRY_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
            log(
                Log.WARN, TAG, "Allowlist receiver not installed during boot;" +
                    " falling back to lazy registration"
            )
        }, "fcmlive-allowlist-register")
        t.isDaemon = true
        t.start()
    }

    private fun getFcmAllowlist(): Set<String> {
        registerAllowlistReceiver()
        if (!allowlistReceiverRegistered &&
            SystemClock.uptimeMillis() - sAllowlistFreshMs >= ALLOWLIST_STALE_MS
        ) {
            requestAllowlistReload()
        }
        return HashSet(sAllowlist)
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
    private fun moduleAppliesTo(packageName: String?): Boolean {
        if (GMS_PACKAGE_NAME == packageName || GMS_PERSISTENT_PROCESS_NAME == packageName) {
            return true
        }
        return getFcmAllowlist().contains(packageName)
    }

    /**
     * @param reportFailure log the throwable when this attempt fails. The boot
     *   retry loop passes `false` for every attempt but the last, because an
     *   early-boot failure is expected and self-healing: reporting it would put a
     *   stack trace in every boot log and consume the once-per-boot guard before
     *   a genuine failure could use it.
     */
    private fun registerAllowlistReceiver(reportFailure: Boolean = true): Boolean {
        if (allowlistReceiverRegistered) {
            return true
        }
        if (!allowlistRegistering.compareAndSet(false, true)) {
            return allowlistReceiverRegistered
        }
        try {
            val sys = getSystemContext() ?: return false
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
                        logForeignAllowlistSenderOnce(fromPackage, fromUid)
                        return
                    }
                    when (intent.action) {
                        Prefs.ACTION_ALLOWLIST_CHANGED -> requestAllowlistReload()
                        Prefs.ACTION_APPLY_AUTOSTART -> applyAutostartToAllowlist()
                    }
                }
            }
            val filter = IntentFilter(Prefs.ACTION_ALLOWLIST_CHANGED)
            filter.addAction(Prefs.ACTION_APPLY_AUTOSTART)
            val handler = allowlistBackgroundHandler()
            sys.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
            allowlistReceiverRegistered = true
            log(Log.INFO, TAG, "Allowlist receiver installed")
            return true
        } catch (e: Throwable) {
            // The boot retry loop reports only its final attempt, so it stays
            // silent here. The lazy path — this method called straight from
            // getFcmAllowlist — reports, because before there was any logging at
            // all a failure there left the allowlist quietly stale with nothing
            // in the log to say why.
            if (reportFailure) {
                logAllowlistRegisterFailureOnce(e)
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
     */
    private fun allowlistSenderIsForeign(fromPackage: String?, fromUid: Int): Boolean {
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
    private fun logForeignAllowlistSenderOnce(fromPackage: String?, fromUid: Int) {
        if (foreignAllowlistSenderLogged) {
            return
        }
        foreignAllowlistSenderLogged = true
        log(
            Log.WARN, TAG,
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
    private fun logAllowlistRegisterFailureOnce(t: Throwable) {
        if (allowlistRegisterFailureLogged) {
            return
        }
        allowlistRegisterFailureLogged = true
        log(Log.WARN, TAG, "Allowlist receiver registration failed", t)
    }

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

    private fun callerIsGms(
        getRecordMethod: Method?,
        infoField: Field,
        ams: Any,
        callerThread: Any?
    ): Boolean {
        if (getRecordMethod != null && callerThread != null) {
            try {
                val app = getInvoker(getRecordMethod).invoke(ams, callerThread)
                val info = if (app != null) infoField.get(app) else null
                if (info is ApplicationInfo) {
                    return GMS_PACKAGE_NAME == info.packageName
                }
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to resolve the broadcast caller", t)
            }
        }
        return binderCallerIsGms()
    }

    private fun binderCallerIsGms(): Boolean {
        try {
            val context = getSystemContext() ?: return false
            val packages = context.packageManager
                .getPackagesForUid(Binder.getCallingUid()) ?: return false
            for (pkg in packages) {
                if (GMS_PACKAGE_NAME == pkg) {
                    return true
                }
            }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to resolve the binder caller uid", t)
        }
        return false
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        val ActivityManagerServiceClass =
            classLoader.loadClass("com.android.server.am.ActivityManagerService")
        val mContextField = ActivityManagerServiceClass.getDeclaredField("mContext")
        mContextField.isAccessible = true
        val IApplicationThreadClass = classLoader.loadClass("android.app.IApplicationThread")
        val IIntentReceiverClass = classLoader.loadClass("android.content.IIntentReceiver")
        val ProcessRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord")
        val infoField = ProcessRecordClass.getDeclaredField("info")
        infoField.isAccessible = true
        var getRecordMethod = findMethod(
            ActivityManagerServiceClass,
            "getRecordForAppLOSP", IApplicationThreadClass
        )
        if (getRecordMethod == null) {
            getRecordMethod = findMethod(
                ActivityManagerServiceClass,
                "getRecordForAppLocked", IApplicationThreadClass
            )
        }
        if (getRecordMethod == null) {
            log(
                Log.WARN, TAG, "No ActivityManagerService#getRecordForApp*;" +
                    " the broadcast caller is identified by binder uid instead"
            )
        }
        var broadcastMethod: Method? = null
        var intentArgIndex = 2
        val featureSignatures = listOf(
            arrayOf<Class<*>>(
                IApplicationThreadClass, String::class.java, Intent::class.java, String::class.java,
                IIntentReceiverClass, Int::class.javaPrimitiveType!!, String::class.java,
                Bundle::class.java,
                Array<String>::class.java, Array<String>::class.java, Array<String>::class.java,
                Int::class.javaPrimitiveType!!, Bundle::class.java,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ),
            arrayOf<Class<*>>(
                IApplicationThreadClass, String::class.java, Intent::class.java, String::class.java,
                IIntentReceiverClass, Int::class.javaPrimitiveType!!, String::class.java,
                Bundle::class.java,
                Array<String>::class.java, Array<String>::class.java,
                Int::class.javaPrimitiveType!!, Bundle::class.java,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ),
            arrayOf<Class<*>>(
                IApplicationThreadClass, String::class.java, Intent::class.java, String::class.java,
                IIntentReceiverClass, Int::class.javaPrimitiveType!!, String::class.java,
                Bundle::class.java,
                Array<String>::class.java,
                Int::class.javaPrimitiveType!!, Bundle::class.java,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            )
        )
        for (signature in featureSignatures) {
            broadcastMethod = findMethod(
                ActivityManagerServiceClass,
                "broadcastIntentWithFeature", *signature
            )
            if (broadcastMethod != null) {
                break
            }
        }
        if (broadcastMethod == null) {
            broadcastMethod = findMethod(
                ActivityManagerServiceClass, "broadcastIntent",
                IApplicationThreadClass,
                Intent::class.java, String::class.java, IIntentReceiverClass,
                Int::class.javaPrimitiveType, String::class.java, Bundle::class.java,
                Array<String>::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            if (broadcastMethod != null) {
                intentArgIndex = 1
            }
        }
        if (broadcastMethod == null) {
            log(
                Log.ERROR, TAG, "No broadcastIntent* in ActivityManagerService;" +
                    " stopped-package delivery and the power exemption are not installed"
            )
            return
        }
        val finalGetRecordMethod = getRecordMethod
        val finalIntentArgIndex = intentArgIndex
        hookE(broadcastMethod).intercept { chain: XposedInterface.Chain ->
            val intent = chain.getArg(finalIntentArgIndex) as? Intent
            if (intent != null && ACTION_REMOTE_INTENT == intent.action) {
                try {
                    val targetPackage = targetPackageOf(intent)
                    if (callerIsGms(
                            finalGetRecordMethod, infoField,
                            chain.thisObject, chain.getArg(0)
                        ) &&
                        targetPackage != null &&
                        moduleAppliesTo(targetPackage)
                    ) {
                        // ponytail: only the flag is added — appOp is passed
                        //   through untouched. External modules raise OP_NONE to
                        //   OP_POST_NOTIFICATION here; appOp sits among several
                        //   ints with no reliable position rule, and AOSP defines
                        //   stopped-package delivery by this flag alone. Cost: a
                        //   broadcast the ROM still refuses on appOp grounds
                        //   stays refused. Condition to add it back: a real
                        //   sample of "flag present, still not delivered".
                        try {
                            if ((intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
                                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            }
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "Failed to add FLAG_INCLUDE_STOPPED_PACKAGES", t)
                        }
                        try {
                            val mContext = mContextField.get(chain.thisObject) as? Context
                            if (mContext != null) {
                                getPowerExemptionManager(mContext).addToTemporaryAllowList(
                                    targetPackage,
                                    102,
                                    "GOOGLE_C2DM",
                                    // 2s: verified sufficient end-to-end
                                    // (528-561ms cold start; diagnostics show
                                    // LoginRequest->Connected 0.4-1.4s).
                                    2000
                                )
                            }
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "Failed to add temporary power exemption", t)
                        }
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "C2DM broadcast hook failed", t)
                }
            }
            // Experiment: the autostart switch's write. It used to be the
            // persistent half of a pair whose runtime half answered the same
            // gate in memory for broadcasts the module intercepted; that half is
            // gone — see the [checkApplicationAutoStart] hook — so what is left
            // writes the ROM's own autostart AppOp for the package, see
            // maybeWriteAutostart. The action test is the cheap local one, so
            // the remote-prefs read only happens for a push broadcast.
            if (intent != null && isPushAction(intent.action)) {
                try {
                    val autostartPackage = targetPackageOf(intent)
                    if (autostartPackage != null &&
                        isWakeWriteAutostartEnabled() &&
                        wakeExplicitlyAllows(autostartPackage)
                    ) {
                        maybeWriteAutostart(autostartPackage)
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "Wake autostart write hook failed", t)
                }
            }
            chain.proceed()
        }
        deoptimize(broadcastMethod)
    }

    /**
     * Experiment switch: write the package's autostart op.
     *
     * It used to be the sub-switch of a master that answered the same ROM gate
     * in memory, and was ANDed with it so that turning the master off stopped
     * the hook from writing a user-visible setting behind a control the user
     * could no longer see. That master is gone — see the comment in the
     * [checkApplicationAutoStart] hook — so this is now read on its own: the
     * row on screen is the whole control, and there is no hidden gate above it.
     *
     * Fails to *disabled*: this writes a setting the user can see, so an
     * unreadable switch must leave the ROM's own value alone.
     */
    private fun isWakeWriteAutostartEnabled(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_WAKE_WRITE_AUTOSTART, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * The experiment's own allowlist gate: membership is required, so an empty
     * list means "no app" rather than "every app".
     *
     * Kept separate from [moduleAppliesTo] for the GMS exemption only: that one
     * lets GMS through unconditionally, which is right for the shipped wake
     * privileges — the GMS/FCM chain must never be narrowed — but wrong for the
     * experiments here, which write user-visible ROM settings and whose
     * descriptions promise "only the checked apps". The empty-list behaviour of
     * the two now agrees (both mean "no app"), so the two differ solely on GMS.
     */
    private fun wakeExplicitlyAllows(packageName: String): Boolean {
        return getFcmAllowlist().contains(packageName)
    }

    /**
     * Whether [action] belongs to the push family the autostart pair acts on.
     *
     * The c2dm actions match by suffix because the family has historical
     * prefixes (`com.google.android.c2dm`, `com.google.android.gcm`); the
     * Firebase ones are exact. Same set the two reference modules use, and the
     * same reasoning as the shipped GMS→c2dm hop, widened past c2dm only for
     * this experiment.
     */
    private fun isPushAction(action: String?): Boolean {
        if (action == null) {
            return false
        }
        return PUSH_ACTIONS.contains(action) || PUSH_ACTION_SUFFIXES.any { action.endsWith(it) }
    }

    /**
     * The lazy half of the autostart pair: the write for the one package a push
     * was just aimed at.
     *
     * This is what keeps the setting from drifting back: a freeze tool that
     * resets the op is corrected the next time its package gets a push. It is
     * no longer the only way the op is written — [applyAutostartToAllowlist]
     * covers the whole list the moment the user flips the sub-switch on, which
     * is what makes the switch observable from a shell right after tapping it.
     *
     * One line per write and nothing on a no-op, so the log stays quiet: a
     * package already at `MODE_ALLOWED` (and every package inside its throttle
     * window) prints nothing at all.
     */
    private fun maybeWriteAutostart(packageName: String) {
        if (writeAutostartIfNeeded(packageName, throttle = true) == AUTOSTART_WRITTEN) {
            val ops = if (isWakeWriteAutostartSwitchEnabled()) " (10008, 10053)" else " (10008)"
            log(Log.INFO, TAG, "wake: set the autostart op$ops of $packageName to allowed")
        }
    }

    /**
     * Run the autostart write over the whole allowlist, once, on request from
     * the settings UI — see [Prefs.ACTION_APPLY_AUTOSTART].
     *
     * Without this the sub-switch was purely lazy: flipping it changed nothing
     * on disk until each app happened to receive its next push, which for a
     * rarely-pushed package could be tomorrow or never, and left no way to
     * confirm the write from a shell right after tapping the row.
     *
     * Deliberately not a repeating task and not run at boot: its job is to make
     * one user action take effect, and the lazy path above is what maintains
     * the result afterwards. Nothing here un-does the write — the module never
     * learns the previous values, so turning the switch off leaves them alone.
     *
     * Runs on the allowlist handler thread, which serialises it against itself
     * (a burst of taps queues, it does not interleave) and against the allowlist
     * reads. The list is re-read here rather than taken from the last reload:
     * the reload throttle would otherwise let this walk a stale copy.
     *
     * Logging is one line for the whole walk, never one per package — this runs
     * over a list the user can see, and per-package lines would print all of it
     * on every tap.
     */
    private fun applyAutostartToAllowlist() {
        if (!isWakeWriteAutostartEnabled()) {
            return
        }
        loadAllowlistFromRemotePrefs()
        val allowlist = getFcmAllowlist()
        if (allowlist.isEmpty()) {
            log(
                Log.INFO, TAG,
                "wake: autostart write requested with an empty allowlist, nothing to write"
            )
            return
        }
        var written = 0
        var already = 0
        var skipped = 0
        var missing = 0
        var failed = 0
        for (pkg in allowlist) {
            // throttle = false: this is a user action, so it writes even inside
            // the lazy path's window. An already-allowed package is still only
            // a read, so re-tapping the switch costs no settings writes.
            when (writeAutostartIfNeeded(pkg, throttle = false)) {
                AUTOSTART_WRITTEN -> written++
                AUTOSTART_FAILED -> failed++
                AUTOSTART_SKIPPED_SYSTEM -> skipped++
                AUTOSTART_SKIPPED_MISSING -> missing++
                else -> already++
            }
        }
        log(
            Log.INFO, TAG,
            "wake: autostart on request over ${allowlist.size} allowlisted package(s): " +
                "$written written, $already already allowed, $skipped system, " +
                "$missing missing, $failed failed"
        )
    }

    /**
     * Set the package's MIUI autostart AppOp to "allowed" if it is not allowed
     * already — the persistent half of the autostart pair, and the one place
     * the module edits a user-visible system setting.
     *
     * With the switch-write rung on, the op the manager toggle owns (`10053`)
     * is written right after the behavior op (`10008`), the same pair 手机管家
     * writes when its own toggle moves. Each op is read before written and
     * each write counts; the return value says whether *anything* was written.
     *
     * There is no public API for this. The op is `MIUIOP_AUTO_START = 10008`
     * (`com.miui.internal.os.MiuiHooks.OP_AUTO_START`, OS4 miui-framework
     * bytecode), and MIUI's own write is
     * `android.miui.AppOpsUtils#setApplicationAutoStart(Context, String, boolean)`
     * — which is a three-line wrapper around exactly the call made here:
     * it resolves the uid with `PackageManager#getPackageUidAsUser` and then
     * `AppOpsManager.setMode(10008, uid, pkg, autoStart ? 0 : 2)`
     * (`AppOpsUtils` disassembly, offsets 0x0000-0x0026: `if-eqz` picks
     * `const/4 #int 0` = `MODE_ALLOWED`, else `const/4 #int 2` =
     * `MODE_ERRORED`). Calling `setMode` directly keeps this off the
     * `AppOpsUtils` hidden class and lets the uid be resolved for the user this
     * module actually runs in.
     *
     * The op code is not in the SDK either — the public `AppOpsManager` only
     * exposes the name-based overloads — so both methods are resolved by
     * reflection and invoked through [XposedInterface.getInvoker], the same
     * route the module already uses for other hidden framework methods.
     *
     * Identity is cleared around the calls. The hook runs on the broadcast's
     * binder thread, so without it the ROM would judge the *sender's* uid:
     * `setMode` enforces `MANAGE_APP_OPS_MODES`, which the module only holds as
     * system_server (uid 1000). Scope is these calls and nothing else — clearing
     * across `chain.proceed()` would let every broadcast run as system.
     *
     * Read-before-write, so a package already at `MODE_ALLOWED` costs a read and
     * no settings write. [throttle] is false for the on-request walk, which is
     * user-driven and must take effect even inside the lazy path's window.
     *
     * System packages are refused outright — see [AUTOSTART_SKIPPED_SYSTEM].
     * The refusal is checked on the same [ApplicationInfo] the uid resolution
     * already needs, so it costs nothing extra, and a package that cannot be
     * resolved at all still falls through to [AUTOSTART_FAILED]: an
     * unresolvable package does not get written either.
     *
     * Returns [AUTOSTART_WRITTEN], [AUTOSTART_ALREADY],
     * [AUTOSTART_SKIPPED_SYSTEM], [AUTOSTART_SKIPPED_MISSING] or
     * [AUTOSTART_FAILED]. Logging is left to
     * the caller because the two callers want different shapes: the lazy path
     * wants a line per write, the on-request walk wants one line for the
     * whole list. The one exception is a failure, which is logged here with
     * the exception — a failure has to name its package to be useful, and it
     * is never the common case.
     */
    private fun writeAutostartIfNeeded(packageName: String, throttle: Boolean): Int {
        val setMode = APP_OPS_SET_MODE
        val checkOpNoThrow = APP_OPS_CHECK_OP_NO_THROW
        if (setMode == null || checkOpNoThrow == null) {
            return AUTOSTART_FAILED
        }
        if (throttle) {
            val now = SystemClock.elapsedRealtime()
            val last = wakeAutostartLastMs.putIfAbsent(packageName, now)
            if (last != null) {
                if (now - last < WAKE_AUTOSTART_COOLDOWN_MS) {
                    return AUTOSTART_ALREADY
                }
                wakeAutostartLastMs[packageName] = now
            }
        }
        val context = getSystemContext() ?: return AUTOSTART_FAILED
        val token = Binder.clearCallingIdentity()
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return AUTOSTART_FAILED
            val ai = try {
                context.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                // Checked, then uninstalled without being unchecked first: skip
                // silently — see [AUTOSTART_SKIPPED_MISSING]. Not the generic
                // catch below: that logs an ERROR with a stack per stale entry
                // per broadcast, which is noise, not a failure.
                return AUTOSTART_SKIPPED_MISSING
            }
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                return AUTOSTART_SKIPPED_SYSTEM
            }
            val uid = ai.uid
            // The read-before-write goes through the same AppOpsManager entry
            // the root choke hooks; with the flag up the choke passes the read
            // through instead of answering it, so the persistent half keeps
            // seeing the ops' real values even while the root rung is on.
            autostartWriteReadInFlight.set(true)
            var wrote = false
            try {
                wrote = writeOpIfDenied(
                    appOps, checkOpNoThrow, setMode, MIUIOP_AUTO_START, uid, packageName
                )
                if (isWakeWriteAutostartSwitchEnabled()) {
                    // The switch op rides the same occasions and the same
                    // guard as the behavior op — 手机管家's own toggle writes
                    // the pair together, so the module does too.
                    wrote = writeOpIfDenied(
                        appOps, checkOpNoThrow, setMode,
                        MIUIOP_AUTO_START_SWITCH, uid, packageName
                    ) || wrote
                }
            } finally {
                autostartWriteReadInFlight.set(false)
            }
            return if (wrote) AUTOSTART_WRITTEN else AUTOSTART_ALREADY
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "wake: failed to write the autostart op of $packageName", t)
            return AUTOSTART_FAILED
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * One op of the autostart pair: read it, and set it to `MODE_ALLOWED` if
     * it is not allowed already. True when this call wrote, false when the op
     * was already allowed (a read, no settings write).
     */
    private fun writeOpIfDenied(
        appOps: AppOpsManager,
        checkOpNoThrow: Method,
        setMode: Method,
        op: Int,
        uid: Int,
        packageName: String
    ): Boolean {
        val current =
            getInvoker(checkOpNoThrow).invoke(appOps, op, uid, packageName)
        if (current == AppOpsManager.MODE_ALLOWED) {
            return false
        }
        getInvoker(setMode).invoke(
            appOps, op, uid, packageName, AppOpsManager.MODE_ALLOWED
        )
        return true
    }

    /**
     * §5 alarm delivery gate — action list item 3.3 (P1, no hard gate).
     *
     * `checkAlarmIsAllowedSend(Context, Alarm)` decides whether an alarm that has
     * already come due is actually delivered; false drops it. It is a live path
     * on this ROM: the single call site is
     * `AlarmManagerService.triggerAlarmsLocked(ArrayList, long)` in services.jar,
     * i.e. the alarm delivery main path. The Impl shows zero in-class callers
     * only because it overrides `AlarmManagerServiceStub` — overridden methods
     * have to be counted at the base type (forensics rule 11), which is exactly
     * why this one was nearly mis-filed as dead code alongside `isPushApp`.
     *
     * Body (OS4 miui-services dexdump, 40 code units):
     *   if (alarm != null && alarm.operation != null)
     *       return WhetstoneClientManager.isAlarmAllowedLocked(
     *           Binder.getCallingPid(), alarm.operation.getCreatorUid(),
     *           alarm.statsTag, CheckIfAlarmGenralRistrictApply(uid, pid));
     *   return true;
     *
     * Scope is narrow on purpose: re-allow only when the ROM already denied
     * (false) AND the alarm belongs to GMS. Everything else proceeds unchanged,
     * so the allow path gains no new behaviour and no other app is affected.
     * GMS and GSF share a uid, so one uid check covers both.
     */
    private fun hookAlarmGate(classLoader: ClassLoader) {
        val alarmClass = try {
            classLoader.loadClass("com.android.server.alarm.Alarm")
        } catch (e: ClassNotFoundException) {
            logSkip("com.android.server.alarm.Alarm absent, alarm gate skip")
            return
        }
        val creatorUidField: Field = try {
            alarmClass.getDeclaredField("creatorUid")
        } catch (e: NoSuchFieldException) {
            logSkip("Alarm#creatorUid absent, alarm gate skip")
            return
        }
        creatorUidField.isAccessible = true
        // Alarm.creatorUid is the PendingIntent creator uid — seeded from
        // operation.getCreatorUid(), i.e. the exact value the ROM feeds into
        // isAlarmAllowedLocked. Reading the field mirrors the ROM's own judgment
        // without invoking a hidden PendingIntent method.

        // First hit wins: the Impl overrides the Stub, so virtual dispatch only
        // ever enters the Impl. Hooking the base as well would add a hook that
        // can never be entered — it only inflates the install summary.
        var methods: List<Method> = emptyList()
        for (name in ALARM_GATE_CLASS_NAMES) {
            val clazz = try {
                classLoader.loadClass(name)
            } catch (ignored: ClassNotFoundException) {
                continue
            }
            val found = clazz.declaredMethods.filter { m ->
                m.name == "checkAlarmIsAllowedSend" &&
                    m.parameterCount == 2 &&
                    m.returnType == Boolean::class.javaPrimitiveType
            }
            if (found.isNotEmpty()) {
                methods = found
                break
            }
        }
        if (methods.isEmpty()) {
            logSkipOtherGeneration("checkAlarmIsAllowedSend (alarm gate)")
            return
        }
        for (method in methods) {
            val field = creatorUidField
            method.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                val result = chain.proceed()
                try {
                    if (java.lang.Boolean.FALSE == result) {
                        val alarm = chain.getArg(1)
                        if (alarm != null) {
                            val uid = field.getInt(alarm)
                            if (isGmsUid(uid)) {
                                // One-shot INFO: the only way to tell "the ROM
                                // actually denies GMS alarms here" apart from
                                // "the gate is never reached".
                                if (!alarmGateBypassLogged) {
                                    alarmGateBypassLogged = true
                                    log(
                                        Log.INFO, TAG,
                                        "checkAlarmIsAllowedSend: re-allowed denied GMS alarm" +
                                            " (creatorUid=$uid)"
                                    )
                                }
                                return@intercept true
                            }
                        }
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "Failed to evaluate alarm gate", t)
                }
                try {
                    // Counterpart of the one-shot above: proves the gate is
                    // actually reached for GMS alarms when the ROM allows them,
                    // so a silent log later means "never denied", not "never run".
                    if (java.lang.Boolean.TRUE == result && !alarmGateSeenLogged) {
                        val alarm = chain.getArg(1)
                        if (alarm != null) {
                            val uid = field.getInt(alarm)
                            if (isGmsUid(uid)) {
                                alarmGateSeenLogged = true
                                log(
                                    Log.INFO, TAG,
                                    "checkAlarmIsAllowedSend: GMS alarm allowed by ROM" +
                                        " (creatorUid=$uid)"
                                )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "Failed to evaluate alarm gate (allow path)", t)
                }
                result
            }
            deoptimize(method)
            log(
                Log.INFO, TAG,
                "checkAlarmIsAllowedSend hooked on ${method.declaringClass?.simpleName}"
            )
        }
    }

    /**
     * Read-only probe: is GMS still exchanging traffic?
     *
     * Hooks record which gates the ROM opened; they say nothing about the outcome,
     * and the outcome is what decides whether the sleep-exit nudge is warranted at
     * all. The only connection observable reachable from the system_server domain
     * is the per-uid byte counter, so that is what this samples.
     *
     * A /proc/net/tcp pass (looking for an ESTABLISHED MCS socket) was implemented
     * and then removed. Two reasons, either one fatal on its own:
     *   - It is unreadable from the system_server SELinux domain: the file is
     *     labelled proc_net_tcp_udp and Enforcing gives system_server no read on
     *     it (adb's shell domain does — adb being able to read it proves nothing
     *     about what a hook can read). Android has also been closing /proc/net off
     *     since 10 for side-channel reasons, and every device this module targets
     *     runs far newer than that, so "it may be readable on other builds" was
     *     never a real possibility. Making it readable would mean loosening
     *     SELinux, which is off the table.
     *   - Even granted the permission it would answer the wrong question. The two
     *     ways this ROM actually starves GMS are DNS interception and firewall
     *     DROP; neither notifies the endpoint, so the socket stays ESTABLISHED
     *     and the table reports a healthy connection over a dead one. The one
     *     path that genuinely closes sockets, closeSocketForAurogon, has a
     *     measured hit rate of zero for GMS on this ROM.
     */
    private fun startGmsTrafficProbe() {
        // Claim the latest chain generation before scheduling: on every hot
        // reload hookPackage re-runs and a fresh module classloader brings a
        // fresh companion, so a plain field cannot be seen by chains scheduled
        // by earlier instances. The counter therefore lives in
        // System.getProperties() — a boot-classloader object that is shared
        // across module reloads within the same host process. A stale chain
        // detects the mismatch in run() and retires instead of stacking yet
        // another parallel 30-min chain (observed overnight: three chains
        // interleaving after two reloads).
        val generation = claimTrafficProbeGeneration()
        // The schedule declaration used to be a line of its own. It rides the
        // startup report instead: same reader, same moment, and the startup line
        // is already the one naming the generation a hot-reload check looks at.
        val intervalMin = GMS_TRAFFIC_PROBE_INTERVAL_MS / 60_000
        probeBackgroundHandler().post {
            probeGmsTraffic("startup, read-only, every ${intervalMin}min, gen $generation")
        }
        probeBackgroundHandler().postDelayed(object : Runnable {
            override fun run() {
                if (latestTrafficProbeGeneration() != generation) {
                    log(
                        Log.INFO, TAG,
                        "gms probe: chain generation $generation superseded, " +
                            "retire without rescheduling"
                    )
                    return
                }
                probeGmsTraffic("periodic")
                probeBackgroundHandler().postDelayed(this, GMS_TRAFFIC_PROBE_INTERVAL_MS)
            }
        }, GMS_TRAFFIC_PROBE_INTERVAL_MS)
    }

    /**
     * Claims a new traffic-probe chain generation, monotonically increasing
     * per host process. Java-level [System] properties only — nothing here
     * touches android.os.SystemProperties or crosses SELinux.
     */
    private fun claimTrafficProbeGeneration(): Long {
        val props = System.getProperties()
        return synchronized(props) {
            val next = (props.getProperty(TRAFFIC_PROBE_GENERATION_KEY)?.toLongOrNull() ?: 0L) + 1
            props.setProperty(TRAFFIC_PROBE_GENERATION_KEY, next.toString())
            next
        }
    }

    /** Reads the latest claimed chain generation; superseded chains see a mismatch. */
    private fun latestTrafficProbeGeneration(): Long =
        System.getProperties().getProperty(TRAFFIC_PROBE_GENERATION_KEY)?.toLongOrNull() ?: 0L

    private fun probeGmsTraffic(reason: String) {
        // One line per tick. The gate counters and the traffic delta come off the
        // same timer and answer the same question — "did anything get gated, and
        // is GMS still talking" — yet this function used to print them as two
        // back-to-back lines (three when nothing was gated but a counter was
        // non-zero). The gate half is still built first, so the counters are
        // reported even on ROMs where GMS's uid cannot be resolved.
        val uid = gmsUid()
        val traffic = if (uid == null) {
            "GMS uid unresolved"
        } else {
            "uid=$uid ${trafficSinceLastProbe(uid)}"
        }
        log(Log.INFO, TAG, "gms probe [$reason]: $traffic; ${broadcastGateSummary()}")
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
     * Returned rather than logged: the caller ([probeGmsTraffic]) appends it to
     * the traffic line it shares a tick with.
     */
    private fun broadcastGateSummary(): String {
        // An idle tick is by far the common case — the counters sit at zero for
        // days on a device where nothing is being gated. Every counter is still
        // named, so "never exercised" stays greppable and distinguishable from
        // "reached and always allowed".
        val idle = broadcastGateAllowedCount == 0 &&
            broadcastGateSkippedCount == 0 &&
            deferC2dmPassthroughCount == 0 &&
            broadcastGateCnActionCount == 0 &&
            broadcastWakePathReachedCount == 0 &&
            broadcastWakePathC2dmCount == 0 &&
            broadcastWakePathDeniedCount == 0 &&
            broadcastWakePathC2dmDeniedCount == 0 &&
            autostartGateServiceDeniedCount == 0 &&
            autostartGateProcessDeniedCount == 0 &&
            autostartGateRestartDeniedCount == 0 &&
            autostartGatePreStartDeniedCount == 0 &&
            autostartGateReleaseCount == 0 &&
            autostartGateRestartReleaseCount == 0 &&
            autostartRootReleaseCount == 0 &&
            greezeNetReleaseCount == 0
        if (idle) {
            return "broadcast gate idle — c2dm " +
                "allowed/skipped/reached-defer/cn-actions = 0, " +
                "wake-path reached/c2dm/denied/c2dm-denied = 0, " +
                "autostart gate service/process/restart/pre-start-denied, " +
                "released, restart-released, root-released, net-released = 0"
        }
        return "broadcast gate c2dm allowed=${broadcastGateAllowedCount} " +
            "(uid-fallback=${broadcastGateAllowedByUidCount}), " +
            "skipped=${broadcastGateSkippedCount}, " +
            "reached-defer=${deferC2dmPassthroughCount}, " +
            "cn-actions=${broadcastGateCnActionCount}; " +
            "wake-path reached=${broadcastWakePathReachedCount}, " +
            "c2dm=${broadcastWakePathC2dmCount}, " +
            "denied=${broadcastWakePathDeniedCount}, " +
            "c2dm-denied=${broadcastWakePathC2dmDeniedCount}; " +
            "autostart gate service-denied=${autostartGateServiceDeniedCount}, " +
                "process-denied=${autostartGateProcessDeniedCount}, " +
                "restart-denied=${autostartGateRestartDeniedCount}, " +
                "pre-start-denied=${autostartGatePreStartDeniedCount}, " +
                "released=${autostartGateReleaseCount}, " +
                "restart-released=${autostartGateRestartReleaseCount}, " +
                "root-released=${autostartRootReleaseCount}, " +
                "net-released=${greezeNetReleaseCount}"
    }

    /**
     * A growing counter means GMS is still exchanging traffic, which is the
     * observable we actually need when deciding whether the nudge was warranted.
     */
    private fun trafficSinceLastProbe(uid: Int): String {
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) {
            return "traffic=unsupported"
        }
        val prevRx = lastGmsRxBytes
        val prevTx = lastGmsTxBytes
        lastGmsRxBytes = rx
        lastGmsTxBytes = tx
        if (prevRx < 0 || prevTx < 0) {
            return "rx=${rx}B tx=${tx}B (baseline)"
        }
        return "rx=+${rx - prevRx}B tx=+${tx - prevTx}B"
    }

    @Volatile
    private var probeHandler: Handler? = null

    /** Per-uid counters from the previous sample; -1 until the first read. */
    @Volatile
    private var lastGmsRxBytes = -1L
    @Volatile
    private var lastGmsTxBytes = -1L

    private fun probeBackgroundHandler(): Handler {
        val handler = probeHandler
        if (handler != null) {
            return handler
        }
        synchronized(this) {
            if (probeHandler == null) {
                val thread = HandlerThread("fcmlive-probe")
                thread.start()
                probeHandler = Handler(thread.looper)
            }
            return probeHandler!!
        }
    }

    /**
     * Gate-W probe (read-only).
     *
     * The wake-path chain is the one §7 candidate with a **cross-jar** entry: AOSP
     * `ActivityManagerService` in services.jar invokes
     * `ActivityManagerServiceStub#checkRunningCompatibility` from at least five sites,
     * the override in `ActivityManagerServiceImpl` funnels into `checkServiceWakePath`
     * and then `checkWakePath`. So unlike the rest of §7 it is definitely reached.
     *
     * What is *not* known is whether it ever denies anything involving GMS — and that
     * is the only question that decides whether a behaviour hook belongs here. This
     * probe therefore never alters the return value: it observes, counts, and records
     * the caller package on the first denial. A silent log means "reached but never
     * denied", which closes the gate negatively.
     */
    private fun probeWakePath(classLoader: ClassLoader) {
        val clazz = try {
            classLoader.loadClass("com.android.server.am.ActivityManagerServiceImpl")
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("ActivityManagerServiceImpl (wake-path probe)")
            return
        }
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name == "checkWakePath" &&
                m.parameterCount == 7 &&
                m.returnType == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            logSkipOtherGeneration("checkWakePath (wake-path probe)")
            return
        }
        // Best effort: CallerInfo#callerPkg identifies the waking side. Absent on some
        // generations, and the probe still works without it (it just logs "?").
        val callerPkgField: Field? = try {
            classLoader.loadClass("miui.security.CallerInfo")
                .getDeclaredField("callerPkg")
                .also { it.isAccessible = true }
        } catch (t: Throwable) {
            null
        }
        method.isAccessible = true
        hookE(method).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            try {
                // Count every entry so an overnight window is quantitative: a bare
                // "0 DENIED" says nothing if the gate was never reached.
                val reached = ++wakePathReachedCount
                if (java.lang.Boolean.FALSE == result) {
                    val denied = ++wakePathDeniedCount
                    val caller = readCallerPkg(chain, callerPkgField)
                    if (denied <= WAKE_PATH_DETAIL_LIMIT) {
                        log(
                            Log.INFO, TAG,
                            "wake-path probe: checkWakePath DENIED #$denied " +
                                "(callerPkg=$caller)"
                        )
                    }
                    // Aggregate per caller so an overnight window stays attributable
                    // after the first 10 detailed lines. Bounded: once 32 distinct
                    // callers are seen, new ones are not recorded.
                    val prev = wakePathDeniedByCaller[caller]
                    if (prev != null || wakePathDeniedByCaller.size < 32) {
                        wakePathDeniedByCaller[caller] = (prev ?: 0) + 1
                    }
                    // The heartbeat lives on the ALLOWED branch; a window where every
                    // entry is denied would never print the aggregation. Time-throttle
                    // a dedicated denied summary instead.
                    val now = SystemClock.elapsedRealtime()
                    if (now - wakePathDeniedSummaryAt >= WAKE_PATH_HEARTBEAT_MIN_MS) {
                        wakePathDeniedSummaryAt = now
                        log(
                            Log.INFO, TAG,
                            "wake-path probe: denied summary denied=$wakePathDeniedCount " +
                                "top=${wakePathTopCallers()}"
                        )
                    }
                } else {
                    // Time-throttled, not count-throttled: one line per window is
                    // enough to prove the gate is live, and `reached` still makes
                    // the volume of traffic through it quantitative.
                    val now = SystemClock.elapsedRealtime()
                    val last = wakePathHeartbeatAt
                    if (last == 0L || now - last >= WAKE_PATH_HEARTBEAT_MIN_MS) {
                        wakePathHeartbeatAt = now
                        val gap = if (last == 0L) "first" else "+${(now - last) / 60_000}m"
                        log(
                            Log.INFO, TAG,
                            "wake-path probe: heartbeat reached=$reached " +
                                "denied=$wakePathDeniedCount gap=$gap " +
                                "top=${wakePathTopCallers()} " +
                                "(callerPkg=${readCallerPkg(chain, callerPkgField)})"
                        )
                    }
                }
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to evaluate wake-path probe", t)
            }
            result
        }
        deoptimize(method)
        log(Log.INFO, TAG, "wake-path probe: checkWakePath hooked (read-only)")
    }

    /**
     * P2 probe (read-only): the *broadcast* wake path.
     *
     * The sibling of [probeWakePath], which watches the service/activity path
     * (`ActivityManagerServiceImpl#checkWakePath`). This one watches
     * `SecurityManagerInternal#checkBroadcastWakePath`, which
     * `BroadcastQueueModernStubImpl#checkApplicationAutoStart` invokes at offset 0186;
     * a false answer makes that caller log "process is not permitted to  wake path"
     * and drop the delivery. The implementation is
     * `com.miui.server.WakePathChecker#checkBroadcastWakePath`, whose caller whitelist
     * is hard-coded to com.miui.home / com.miui.securitycenter / com.miui.carlink —
     * GMS is not on it, so a c2dm arriving from GMS is decided by the wake-path rule
     * engine (`isAllowedByWakePathRule`).
     *
     * Polarity, from the bytecode: true = allowed, and true is also the value
     * returned whenever the decision cannot be taken (null intent, uid -1, empty
     * callee, caller == callee).
     *
     * Read-only: the return value is passed through untouched. Note the c2dm counter
     * can only move when the module is *not* the one deciding — the
     * `checkApplicationAutoStart` hook answers before offset 0186 for callees on the
     * wake tier, so a non-zero count means an unselected callee (or an empty
     * allowlist, where nothing is filtered at all).
     *
     * Output: one immediate `… first reach …` line, per-case detail lines for the
     * first [WAKE_PATH_DETAIL_LIMIT] c2dm arrivals and denials, and the counters in
     * the 30-min broadcast-gate summary.
     */
    private fun probeBroadcastWakePath(classLoader: ClassLoader) {
        val clazz = try {
            classLoader.loadClass("com.miui.server.WakePathChecker")
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("WakePathChecker (broadcast wake-path probe)")
            return
        }
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name == "checkBroadcastWakePath" &&
                m.parameterCount == 5 &&
                m.returnType == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            logSkipOtherGeneration("checkBroadcastWakePath (broadcast wake-path probe)")
            return
        }
        method.isAccessible = true
        hookE(method).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            try {
                val action = (chain.getArg(0) as? Intent)?.action
                val caller = chain.getArg(1) as? String
                val callee = resolvePackageOf(chain.getArg(3))
                val allowed = java.lang.Boolean.TRUE == result
                // Count every entry: "0 denied" is unreadable without knowing whether
                // the gate was reached at all.
                val reached = ++broadcastWakePathReachedCount
                if (reached == 1) {
                    // The falsifiable half of §5.1's rule, and the only immediate one:
                    // everything else waits for the 30-min summary, and the detail lines
                    // below only exist for c2dm and denials. One line, ever — a silent
                    // log therefore keeps meaning "never reached", while a line here
                    // with a still-zero `denied` means "reached and always allowed".
                    log(
                        Log.INFO, TAG,
                        "wake-path probe: broadcast gate first reach " +
                            "(action=$action caller=$caller callee=$callee " +
                            "verdict=${if (allowed) "allowed" else "blocked"})"
                    )
                }
                if (ACTION_REMOTE_INTENT == action) {
                    val c2dm = ++broadcastWakePathC2dmCount
                    if (c2dm <= WAKE_PATH_DETAIL_LIMIT) {
                        log(
                            Log.INFO, TAG,
                            "wake-path probe: broadcast c2dm reached the gate #$c2dm " +
                                "(caller=$caller callee=$callee " +
                                "verdict=${if (allowed) "allowed" else "blocked"})"
                        )
                    }
                    if (!allowed) {
                        broadcastWakePathC2dmDeniedCount++
                    }
                }
                if (!allowed) {
                    val denied = ++broadcastWakePathDeniedCount
                    if (denied <= WAKE_PATH_DETAIL_LIMIT) {
                        log(
                            Log.INFO, TAG,
                            "wake-path probe: broadcast DENIED #$denied " +
                                "(action=$action caller=$caller callee=$callee)"
                        )
                    }
                }
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to evaluate broadcast wake-path probe", t)
            }
            result
        }
        deoptimize(method)
        log(
            Log.INFO, TAG,
            "P2: wake-path probe: checkBroadcastWakePath hooked (read-only); " +
                "counter rides the 30min broadcast-gate summary"
        )
    }

    /**
     * The four autostart checkpoints that read `MIUIOP 10008`, and the runtime
     * half of the autostart story.
     *
     * `BroadcastQueueModernStubImpl#checkApplicationAutoStart` — the module's
     * hook — answers at method entry for caller=GMS + c2dm, so on the FCM hop
     * `MIUIOP 10008` is never read there at all. It *is* read by the
     * checkpoints below, which is why setting the op changes behaviour even
     * though the module already answers the broadcast gate:
     *
     *  - `AutoStartManagerServiceStubImpl#isAllowStartService` — starting or
     *    binding a service. This is the one that fires for a *cached* process:
     *    the broadcast is delivered without a process start, and the receiver
     *    then has to start the messaging service.
     *  - `ProcessManagerService#isAllowAutoStart` — starting a process. This is
     *    the one that fires once the process has been reaped.
     *  - `AutoStartManagerServiceStubImpl#canRestartServiceLocked` — scheduling
     *    a killed service back. Reached only from
     *    `ActiveServices.scheduleServiceRestartLocked`, so it is a third path
     *    rather than a third reading of the same one.
     *  - `PreStartFeedbackImpl#isAutoStart` — the pre-start *punishment* path.
     *    See [AUTOSTART_PRE_START_METHOD].
     *
     * The first two read the same op, so one setting fixes both, and whether the
     * process was dead does not change which of the two is asked. User-side
     * evidence (2026-10-06): with `10008` denied, normal-priority pushes did not
     * arrive at all; allowing it was enough, and `10053` — the op the Security
     * Centre row shows — changed nothing.
     *
     * The last two differ. `canRestartServiceLocked` was a probe until live
     * samples showed the gate actually denying checked packages (2026-10-07:
     * `restart DENIED` lines with a checked push target as `callee`), which is
     * the condition the probe was waiting for — releasing a checkpoint whose
     * denials had never been observed would have been an intervention nobody
     * could measure. It is now releasable under its **own** experiment key,
     * not the master's: the freeze decision is a separate mechanism (its
     * `false` verdict sends the process to `KillProcessInfo` instead of a
     * restart), and isolating it is the reason the rung exists.
     * `PreStartFeedbackImpl#isAutoStart` stays a probe: its four callers give
     * the same boolean three punishment meanings and one bookkeeping one, and
     * the whole path sits behind static ROM flags whose enablement on this
     * generation is unknown.
     *
     * Read-through by default: the verdict is passed on untouched, denials for a
     * checked callee are counted, and each (gate, callee) pair gets its own
     * detail allowance per hour (see [AUTOSTART_GATE_DETAIL_PER_CALLEE]). The
     * per-gate experiment keys turn a counted denial into an override, and
     * then only when the ROM was about to deny a callee the user checked —
     * nothing is written to disk either way.
     */
    private fun hookAutostartGate(classLoader: ClassLoader) {
        var installed = 0
        val autostartStubClass = try {
            classLoader.loadClass(AUTOSTART_STUB_CLASS)
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("$AUTOSTART_STUB_CLASS (autostart gate)")
            null
        }
        val serviceTargets = autostartStubClass?.declaredMethods?.mapNotNull { m ->
            if (m.name != AUTOSTART_SERVICE_METHOD ||
                m.returnType != Boolean::class.javaPrimitiveType
            ) {
                return@mapNotNull null
            }
            val intentIndex = m.parameterTypes.indexOfFirst { it == Intent::class.java }
            if (intentIndex < 0) null else kotlin.Pair(m, intentIndex)
        } ?: emptyList<kotlin.Pair<Method, Int>>()
        if (serviceTargets.isEmpty() && autostartStubClass != null) {
            logSkipOtherGeneration("$AUTOSTART_SERVICE_METHOD (autostart gate)")
        }
        for ((method, intentIndex) in serviceTargets) {
            method.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                val intent = chain.getArg(intentIndex) as? Intent
                val callee = intent?.component?.packageName ?: intent?.getPackage()
                // The callee alone cannot tell a push wake from any other
                // service start, and that distinction is the whole question this
                // probe exists to answer: modern FCM reaches an app by starting
                // or binding its Firebase* service rather than by sending it a
                // c2dm broadcast (the shipped broadcast hop has never seen one on
                // this ROM — `broadcast gate c2dm allowed` stays 0). So the
                // detail carries the component's short class name, which names
                // the service the ROM was about to refuse.
                val detail = intent?.let { shortComponent(it) }
                autostartGateVerdict(chain, AUTOSTART_GATE_SERVICE, callee, detail)
            }
            deoptimize(method)
            installed++
        }
        val processClass = try {
            classLoader.loadClass("com.android.server.am.ProcessManagerService")
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("ProcessManagerService (autostart gate)")
            null
        }
        val processTarget = processClass?.declaredMethods?.firstOrNull { m ->
            m.name == AUTOSTART_PROCESS_METHOD &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (processTarget == null) {
            if (processClass != null) {
                logSkipOtherGeneration("$AUTOSTART_PROCESS_METHOD(String,int) (autostart gate)")
            }
        } else {
            processTarget.isAccessible = true
            hookE(processTarget).intercept { chain: XposedInterface.Chain ->
                autostartGateVerdict(
                    chain, AUTOSTART_GATE_PROCESS, chain.getArg(0) as? String
                )
            }
            deoptimize(processTarget)
            installed++
        }
        // Gate G2: restart-after-death. Both overloads take the callee as
        // parameter 0, so one matcher covers them; there is nothing else on
        // this class by that name returning a boolean. Releasable under its
        // own experiment key — see the class note above.
        val restartTargets = autostartStubClass?.declaredMethods?.filter { m ->
            m.name == AUTOSTART_RESTART_METHOD &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterCount >= 1 &&
                m.parameterTypes[0] == String::class.java
        } ?: emptyList()
        if (restartTargets.isEmpty() && autostartStubClass != null) {
            logSkipOtherGeneration("$AUTOSTART_RESTART_METHOD (autostart gate probe)")
        }
        for (method in restartTargets) {
            method.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                autostartGateVerdict(
                    chain,
                    AUTOSTART_GATE_RESTART,
                    chain.getArg(0) as? String
                )
            }
            deoptimize(method)
            installed++
        }
        // Probe G1: the pre-start path. Private helper on a class reached only
        // through its own instance, so the probe targets the read itself rather
        // than one of the four callers — every one of them asks the same
        // question through this method.
        val preStartClass = try {
            classLoader.loadClass(AUTOSTART_PRE_START_CLASS)
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("$AUTOSTART_PRE_START_CLASS (autostart gate probe)")
            null
        }
        val preStartTarget = preStartClass?.declaredMethods?.firstOrNull { m ->
            m.name == AUTOSTART_PRE_START_METHOD &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (preStartTarget == null) {
            if (preStartClass != null) {
                logSkipOtherGeneration(
                    "$AUTOSTART_PRE_START_METHOD(String,int) (autostart gate probe)"
                )
            }
        } else {
            preStartTarget.isAccessible = true
            hookE(preStartTarget).intercept { chain: XposedInterface.Chain ->
                autostartGateVerdict(
                    chain,
                    AUTOSTART_GATE_PRE_START,
                    chain.getArg(0) as? String,
                    releaseable = false
                )
            }
            deoptimize(preStartTarget)
            installed++
        }
        // Root choke: one hook at the AppOpsManager client entry covers every
        // system_server-side reader that funnels through it, including the
        // per-checkpoint hooks above and the pre-start punishment path — the
        // choke cannot tell callers apart, which is its documented cost. The
        // manager UI reads the same op from its own process, where this hook
        // does not exist, so the display keeps telling the truth. Count-only
        // per event (one detail line per package per window), because the ROM
        // never "denies" here — the answer is pre-empted before the ROM's own
        // logic runs.
        val appOpsClass = try {
            classLoader.loadClass("android.app.AppOpsManager")
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("android.app.AppOpsManager (autostart root choke)")
            null
        }
        val rootTargets = appOpsClass?.declaredMethods?.filter { m ->
            m.returnType != Int::class.javaPrimitiveType ||
                m.parameterTypes.firstOrNull() != Int::class.javaPrimitiveType ||
                m.parameterTypes.getOrNull(1) != Int::class.javaPrimitiveType ||
                m.parameterTypes.getOrNull(2) != String::class.java
        }?.let { ints ->
            ints.filter { m ->
                m.name == "checkOpNoThrow" && m.parameterCount == 3
            } + ints.filter { m ->
                m.name == "noteOpNoThrow" &&
                    m.parameterCount == 5 &&
                    m.parameterTypes[3] == String::class.java &&
                    m.parameterTypes[4] == String::class.java
            }
        } ?: emptyList()
        if (rootTargets.isEmpty() && appOpsClass != null) {
            logSkipOtherGeneration("AppOpsManager checkOpNoThrow/noteOpNoThrow (autostart root choke)")
        }
        for (method in rootTargets) {
            method.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                // The module's own read-before-write goes through this same
                // method; answering it here would make every write see "already
                // allowed" and silently stop the persistent half from ever
                // writing. The write path raises this flag around its read.
                if (java.lang.Boolean.TRUE == autostartWriteReadInFlight.get()) {
                    return@intercept chain.proceed()
                }
                var answered = false
                try {
                    val pkg = chain.getArg(2) as? String
                    if ((chain.getArg(0) as? Int) == MIUIOP_AUTO_START &&
                        pkg != null &&
                        wakeExplicitlyAllows(pkg) &&
                        isAutostartRootReleaseEnabled()
                    ) {
                        ++autostartRootReleaseCount
                        answered = true
                        if (claimAutostartGateDetail(AUTOSTART_GATE_ROOT, pkg)) {
                            log(
                                Log.INFO, TAG,
                                "autostart root: answered op 10008 as allowed " +
                                    "(callee=$pkg)"
                            )
                        }
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "autostart root choke failed", t)
                }
                if (answered) AppOpsManager.MODE_ALLOWED else chain.proceed()
            }
            deoptimize(method)
            installed++
        }
        if (installed > 0) {
            log(
                Log.INFO, TAG,
                "autostart gate: $installed checkpoint(s) hooked (read-through); " +
                    "denials counted unconditionally, each releasable gate follows " +
                    "its own experiment switch, pre-start probed read-only, " +
                    "AppOps root choke answers op 10008 before the ROM judges"
            )
        }
    }

    /**
     * One autostart-gate verdict: count a denial for a checked callee, and
     * release it when the experiment switch says to.
     *
     * Returns the value the caller should hand back to the ROM — the original
     * verdict, or `true` when this overrode it.
     *
     * One line per denial, carrying the outcome as well: the denial and the
     * release used to be two lines with two independent detail budgets, so a
     * late denial could be logged while its release silently fell past the
     * limit and the pair became unreadable — which is exactly how the first
     * sample ended, with a `DENIED` that nobody could tell apart from a
     * `kept`. Merging them costs one line per event instead of two, so the
     * budget below now covers twice as many events as it used to.
     */
    private fun autostartGateVerdict(
        chain: XposedInterface.Chain,
        gate: String,
        callee: String?,
        detail: String? = null,
        releaseable: Boolean = true
    ): Any? {
        val result = chain.proceed()
        try {
            if (java.lang.Boolean.TRUE == result) {
                return result
            }
            if (callee == null || !wakeExplicitlyAllows(callee)) {
                return result
            }
            val denied = when (gate) {
                AUTOSTART_GATE_SERVICE -> ++autostartGateServiceDeniedCount
                AUTOSTART_GATE_PROCESS -> ++autostartGateProcessDeniedCount
                AUTOSTART_GATE_RESTART -> ++autostartGateRestartDeniedCount
                else -> ++autostartGatePreStartDeniedCount
            }
            // Probed checkpoints are answered by the ROM exactly as it answered
            // them before this hook existed, whatever the switch says: the point
            // of the probe is to learn whether they fire, and releasing them in
            // the same step would make the observation and the intervention
            // impossible to tell apart.
            //
            // The two releasable gate families each read their own experiment
            // key — deliberately not an AND chain under the master, so that
            // one rung can be isolated and measured on its own.
            val release = releaseable && when (gate) {
                AUTOSTART_GATE_RESTART -> isAutostartRestartReleaseEnabled()
                else -> isAutostartGateReleaseEnabled()
            }
            if (release) {
                if (gate == AUTOSTART_GATE_RESTART) {
                    ++autostartGateRestartReleaseCount
                } else {
                    ++autostartGateReleaseCount
                }
            }
            if (claimAutostartGateDetail(gate, callee)) {
                // Who asked, as far as the binder layer can tell. A real uid
                // other than 1000 means the call crossed a process boundary,
                // which is the only way this gate can name its caller; 1000
                // means system_server asked itself, and the caller stays
                // unknown. Either answer is a fact, so it is logged as read.
                val callerUid = try {
                    Binder.getCallingUid()
                } catch (t: Throwable) {
                    -1
                }
                log(
                    Log.INFO, TAG,
                    "autostart gate: ${autostartGateLabel(gate)} DENIED #$denied -> " +
                        (if (!releaseable) "probe" else if (release) "released" else "kept") +
                        " (callee=$callee" +
                        (if (detail == null) "" else ", $detail") +
                        ", caller-uid=$callerUid)"
                )
            }
            if (release) {
                return true
            }
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to evaluate autostart gate", t)
        }
        return result
    }

    /**
     * Human wording for the gate key inside a detail line.
     *
     * The two original gates keep the exact letters they have always had —
     * `service start DENIED` / `process start DENIED` — because saved samples
     * and the diagnostics notes grep for them; only the two probes added
     * alongside need new words, and they get the gate name on its own. A gate
     * the ROM answered by *restarting* a killed service is not a "start", and a
     * pre-start checkpoint that is itself a probe should not borrow the start
     * family's phrasing.
     */
    private fun autostartGateLabel(gate: String): String = when (gate) {
        AUTOSTART_GATE_SERVICE -> "$gate start"
        AUTOSTART_GATE_PROCESS -> "$gate start"
        else -> gate
    }

    /**
     * `ServiceName` out of an intent meant to start one — the short class name,
     * because the full one repeats the package that is already in the line, and
     * the action when there is no component. Null when neither is present.
     */
    private fun shortComponent(intent: Intent): String? {
        val component = intent.component
        if (component != null) {
            val cls = component.className
            val dot = cls.lastIndexOf('.')
            return cls.substring(dot + 1)
        }
        val action = intent.action
        if (!action.isNullOrEmpty()) {
            val dot = action.lastIndexOf('.')
            return "action=" + action.substring(dot + 1)
        }
        return null
    }

    /**
     * Experiment switch: release the autostart verdict for a checked callee.
     *
     * Fails to *disabled*: this answers a gate the ROM owns, and an unreadable
     * switch must leave the ROM's own decision alone.
     */
    private fun isAutostartGateReleaseEnabled(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_AUTOSTART_GATE_RELEASE, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * Experiment switch: release the restart gate (freeze instead of restart)
     * for a checked callee. Reads its **own** key — not the master's — so the
     * freeze rung can be measured in isolation.
     *
     * Fails to *disabled*, same reasoning as the master above.
     */
    private fun isAutostartRestartReleaseEnabled(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_AUTOSTART_RESTART_RELEASE, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * Experiment switch: answer the autostart op query itself for a checked
     * package. Reads its **own** key, same isolation reasoning.
     *
     * Fails to *disabled*, same reasoning as the master above.
     */
    private fun isAutostartRootReleaseEnabled(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_AUTOSTART_ROOT_RELEASE, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * Experiment switch: write the autostart *switch* op (`10053`) alongside
     * the behavior op. ANDed with the behavior write on the hook side, so
     * this alone writes nothing.
     *
     * Fails to *disabled*: this writes a setting the user can see, so an
     * unreadable switch must leave the ROM's own value alone.
     */
    private fun isWakeWriteAutostartSwitchEnabled(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_WAKE_WRITE_AUTOSTART_SWITCH, false)
        } catch (ignored: Throwable) {
            false
        }
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
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_GREEZE_NET_RELEASE, false)
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
        val allowlist = getFcmAllowlist()
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

    /** `resolveInfo.activityInfo.applicationInfo.packageName`, `"?"` if unavailable. */
    private fun resolvePackageOf(resolveInfo: Any?): String {
        val info = resolveInfo as? ResolveInfo ?: return "?"
        val activityInfo = info.activityInfo ?: return "?"
        return activityInfo.applicationInfo?.packageName ?: "?"
    }

    private fun readCallerPkg(chain: XposedInterface.Chain, field: Field?): String {
        if (field == null) return "?"
        val info = chain.getArg(1) ?: return "?"
        return try {
            field.get(info) as? String ?: "?"
        } catch (t: Throwable) {
            "?"
        }
    }

    /** Gate-W: top denied callers, "pkg:count" pairs, for overnight attribution. */
    private fun wakePathTopCallers(): String =
        wakePathDeniedByCaller.entries
            .sortedByDescending { it.value }
            .take(3)
            .joinToString(",", prefix = "[", postfix = "]") { "${it.key}:${it.value}" }

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

    @Volatile
    private var restrictNetMatchLogged = false

    /**
     * P0/P1 broadcast-gate counters (2026-10-02).
     *
     * One-shot booleans alone cannot answer "did the change take effect": a
     * legitimately-never-taken branch looks identical to a broken hook. These
     * counters are printed together once per traffic-probe tick
     * (see [broadcastGateSummary]), so a night's log shows the split even
     * when every count is zero.
     */
    @Volatile
    private var broadcastGateAllowedCount = 0

    /** Subset of [broadcastGateAllowedCount] decided by callerUid, not callerPkg. */
    @Volatile
    private var broadcastGateAllowedByUidCount = 0

    /** c2dm from GMS with the callee off the allowlist: handed back to the ROM. */
    @Volatile
    private var broadcastGateSkippedCount = 0

    /** GMS-internal reconnect actions allowed through the same gate. */
    @Volatile
    private var broadcastGateCnActionCount = 0

    /** c2dm that reached `DomesticPolicyManager#deferBroadcast` unsuppressed. */
    @Volatile
    private var deferC2dmPassthroughCount = 0

    /** One-shot: first c2dm allow decided by the isAllowBroadcast gate. */
    @Volatile
    private var broadcastGateAllowedLogged = false

    /** One-shot: first c2dm deliberately left to the ROM policy (P0 behaviour). */
    @Volatile
    private var broadcastGateSkippedLogged = false

    /** One-shot: first c2dm arriving at `DomesticPolicyManager#deferBroadcast`. */
    @Volatile
    private var deferC2dmPassthroughLogged = false

    /** One-shot: confirms the §5 alarm gate re-allowed a GMS alarm the ROM had denied. */
    @Volatile
    private var alarmGateBypassLogged = false

    // Gate-W has no "first denial / first reach" one-shot flags. Both halves are
    // expressed as counters instead: `reached == 1` prints the immediate line and
    // `wakePathDeniedCount` gates the detail lines. Do not reintroduce boolean
    // one-shots here — a flag that is only ever set is indistinguishable from a
    // line that was simply never reached, which is the exact ambiguity this
    // probe exists to remove.

    /** Gate-W: how many times `checkWakePath` denied anything at all. */
    @Volatile
    private var wakePathDeniedCount = 0

    /** Gate-W: denial counts per waking caller, keyed by callerPkg ("?" if unknown). */
    private val wakePathDeniedByCaller = ConcurrentHashMap<String, Int>()

    /** Gate-W: elapsedRealtime of the last time-throttled denied-summary line. */
    @Volatile
    private var wakePathDeniedSummaryAt = 0L

    /** Count of suppressed `enablemiuistandby enable` commands (standby firewall chain). */
    private var standbyFirewallSkipCount = 0

    /** Gate-W: how many times `checkWakePath` was entered at all. */
    @Volatile
    private var wakePathReachedCount = 0

    /** Gate-W heartbeat throttle: elapsedRealtime of the last heartbeat, 0 = none yet. */
    @Volatile
    private var wakePathHeartbeatAt = 0L

    /**
     * Gate-B (P2, 2026-10-02): the broadcast wake path,
     * `BroadcastQueueModernStubImpl#checkApplicationAutoStart` offset 0186 →
     * `SecurityManagerInternal#checkBroadcastWakePath` → `WakePathChecker`.
     *
     * `reached` counts entries, `c2dm` the subset carrying
     * `com.google.android.c2dm.intent.RECEIVE`. `c2dm-denied` is the number that
     * actually matters: a non-zero value means the ROM refuses a GMS push delivery
     * before the module's own gates even see it. All four are reported once per
     * traffic-probe tick, so "never reached" and "reached, never denied" stay
     * distinguishable even when nothing else fires.
     */
    @Volatile
    private var broadcastWakePathReachedCount = 0

    @Volatile
    private var broadcastWakePathC2dmCount = 0

    @Volatile
    private var broadcastWakePathDeniedCount = 0

    @Volatile
    private var broadcastWakePathC2dmDeniedCount = 0

    /**
     * Autostart gate: denials the ROM issued for a *checked* callee, split by
     * the checkpoint that issued them. See [hookAutostartGate].
     *
     * Counted whether or not the experiment switch is on, so a user who never
     * turns it on still gets the evidence: non-zero here is the ROM refusing a
     * start that the module's own broadcast hook already waved through.
     */
    @Volatile
    private var autostartGateServiceDeniedCount = 0

    @Volatile
    private var autostartGateProcessDeniedCount = 0

    /**
     * Autostart gate: denials from the two checkpoints added alongside the pair
     * above, counted the same way and for the same reason.
     *
     * Both are **read-only**: they exist to establish whether these ROM paths
     * are live and whether they ever deny a checked package, before anything is
     * allowed to answer them. A counter that stays zero is the answer "not on
     * this build" — which is a fact worth having, and one the module cannot get
     * by reading the ROM alone.
     */
    @Volatile
    private var autostartGateRestartDeniedCount = 0

    @Volatile
    private var autostartGatePreStartDeniedCount = 0

    /** Autostart gate: verdicts this hook flipped from deny to allow. */
    @Volatile
    private var autostartGateReleaseCount = 0

    /**
     * Restart gate: verdicts flipped from freeze to restart. Counted apart
     * from [autostartGateReleaseCount] because the gate answers a different
     * question — whether a dead service may come back at all, not whether a
     * start may happen — and the ladder reads the two rungs separately.
     */
    @Volatile
    private var autostartGateRestartReleaseCount = 0

    /**
     * Root choke: queries answered as allowed before the ROM's own logic ran.
     * Not a denial count at all — the ROM never refuses here — so it lives in
     * its own number and never mixes into the `released=` pair.
     */
    @Volatile
    private var autostartRootReleaseCount = 0

    /**
     * Greeze net release: freeze-time network restrictions skipped for checked
     * apps. Not an autostart verdict at all — it rides the greeze hook — so it
     * lives in its own number and its own summary word (`net-released`), never
     * mixing into the autostart gate's `released=` group.
     */
    @Volatile
    private var greezeNetReleaseCount = 0

    /** Uids whose first net-release already produced a detail line. */
    private val greezeNetReleaseLoggedUids = HashSet<Int>()

    /**
     * Autostart gate detail budget: "(gate/callee)" → lines already spent in
     * the current window, plus the window's start and total. See
     * [AUTOSTART_GATE_DETAIL_PER_CALLEE] for why it is shaped this way.
     *
     * Guarded by [autostartGateDetailLock] rather than made a concurrent map,
     * because the reset has to see the buckets and the window start as one
     * decision: two threads entering on either side of the boundary would
     * otherwise clear each other's work or hand out a second full allowance.
     * Every access is short and none of them can block on the ROM.
     */
    private val autostartGateDetailBuckets = HashMap<String, Int>()
    private val autostartGateDetailLock = Any()
    private var autostartGateDetailWindowStartMs = 0L
    private var autostartGateDetailWindowTotal = 0

    /**
     * Whether this denial may spend a detail line, rolling the window when it
     * has aged out. True for at most [AUTOSTART_GATE_DETAIL_PER_CALLEE] lines
     * per (gate, callee) pair per window.
     *
     * The bucket key is the pair, not the bare callee: an active callee that
     * gets denied at several gates in the same window would otherwise spend
     * one shared allowance across all of them and starve the later gates —
     * which is exactly what the first live window did (22 denials, 10 lines,
     * the restart gate starved by the process gate before it).
     */
    private fun claimAutostartGateDetail(gate: String, callee: String): Boolean {
        synchronized(autostartGateDetailLock) {
            val now = SystemClock.elapsedRealtime()
            if (now - autostartGateDetailWindowStartMs >= AUTOSTART_GATE_DETAIL_WINDOW_MS) {
                autostartGateDetailBuckets.clear()
                autostartGateDetailWindowTotal = 0
                autostartGateDetailWindowStartMs = now
            }
            val key = "$gate/$callee"
            val used = autostartGateDetailBuckets[key] ?: 0
            if (used >= AUTOSTART_GATE_DETAIL_PER_CALLEE ||
                autostartGateDetailWindowTotal >= AUTOSTART_GATE_DETAIL_PER_HOUR
            ) {
                return false
            }
            autostartGateDetailBuckets[key] = used + 1
            autostartGateDetailWindowTotal++
            return true
        }
    }

    /** 3.2 gate: how many times the ROM really asked netd to destroy sockets. */
    @Volatile
    private var socketTeardownCount = 0

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

    /** One-shot: confirms the §5 alarm gate is actually reached for GMS alarms. */
    @Volatile
    private var alarmGateSeenLogged = false

    /** Guards userTable write-back against re-entry via hooked config writers. */
    @Volatile
    private var userTableReassertInFlight = false

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
            if (moduleAppliesTo(pkg)) {
                try {
                    val fromRestrictNet = STACK_WALKER.walk { frames ->
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
                            log(
                                Log.INFO, TAG, "isPushApp: caller isRestrictNet matched;" +
                                    " answering false"
                            )
                        }
                        return@intercept false
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "Stack inspection failed", t)
                }
            }
            chain.proceed()
        }
    }

    /**
     * MIUI force-stop ("clean") protection: answer false from
     * `ProcessCleanerBase#isForceStopEnable` for apps that declare an FCM
     * component, so the ROM's cleaner leaves them alone.
     *
     * The allowlist governs it like every other gate: only a checked app is
     * protected, and an unchecked one stays exposed to MIUI force-stop. That is
     * the complete explanation for `No response to broadcast …` and
     * `Failed to broadcast to stopped app` in an unchecked app's log — nothing
     * regressed, the app is simply not on the list. Checking it is the fix.
     *
     * GMS is exempt as everywhere else: it is never narrowed.
     *
     * `policy == 13` is passed through untouched: that is the one code this hook
     * never overrides, because overriding it would fight an explicit stop
     * request rather than a background policy.
     */
    private fun hookProcessCleanerBase(classLoader: ClassLoader) {
        val ProcessCleanerBaseClass =
            classLoader.loadClass("com.android.server.am.ProcessCleanerBase")
        val ProcessRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord")
        val mGetApplicationInfo = ProcessRecordClass.getDeclaredMethod("getApplicationInfo")
        val ProcessManagerServiceClass =
            classLoader.loadClass("com.android.server.am.ProcessManagerService")
        val mPkms = ProcessManagerServiceClass.getDeclaredField("mPkms")
        mPkms.isAccessible = true
        val isForceStopEnableMethod = ProcessCleanerBaseClass.getDeclaredMethod(
            "isForceStopEnable",
            ProcessRecordClass,
            Int::class.javaPrimitiveType,
            ProcessManagerServiceClass
        )
        hookE(isForceStopEnableMethod).intercept { chain: XposedInterface.Chain ->
            try {
                val policy = chain.getArg(1)
                val pms = chain.getArg(2)
                val pm = if (pms != null) mPkms.get(pms) as? PackageManager else null
                val info =
                    getInvoker(mGetApplicationInfo).invoke(chain.getArg(0)) as? ApplicationInfo
                val pkgName = info?.packageName
                if (policy is Int && policy != 13 &&
                    pm != null &&
                    pkgName != null &&
                    moduleAppliesTo(pkgName) &&
                    declaresFcmComponent(pm, pkgName)
                ) {
                    return@intercept false
                }
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "isForceStopEnable hook failed", t)
            }
            chain.proceed()
        }
    }

    private val fcmCache = HashMap<String, FcmQuery>()

    /**
     * The four FCM markers the module recognises: a `FirebaseMessagingService`
     * subclass, a `FirebaseInstanceIdReceiver` subclass, and the
     * `MESSAGING_EVENT` / `RECEIVE` intent actions (direct-boot included).
     * The same four drive the "FCM-supported" filter in the app list.
     *
     * **The rule: any two of ②③④ is enough; ② or ③ alone is also enough; ①
     * alone, ④ alone, and nothing at all are out.** Kept identical to
     * `MainActivity.isFcmClient` — asking it the same way is the point, and a
     * divergence would show as an app the list shows but the cleaner leaves
     * alone (or the reverse).
     *
     * Why ② is not required even though it is the SDK's own receiver: the SDK
     * expects an app to hand the message to a receiver *it* declares, so a
     * client can receive `RECEIVE` without ever declaring
     * `FirebaseInstanceIdReceiver`. GMS is the case in hand. Why ① alone is out:
     * `FirebaseMessagingService` is a public base class any app that merely
     * links the SDK inherits, with no promise a push was ever wired up. Why ④
     * alone is out: the pre-Firebase c2dm era left bare `RECEIVE` actions on
     * components unrelated to messaging — `framework-res` still carries one on
     * its factory-reset receiver, a remote-wipe leftover with no Firebase in it
     * at all.
     *
     * Scope, recorded so it stops being re-litigated: this matches Firebase Cloud
     * Messaging only. The domestic push stacks (Mi Push, GeTui, HMS, Honor) are
     * carried by their own host — `com.xiaomi.xmsf`, uid 10206, already on the
     * deviceidle user whitelist — and never travel over c2dm, so an app that
     * relies on them scores false here and is correctly out of scope. Worked
     * example: `com.chinamworld.bocmbci` (uid 10319) contains no Firebase or
     * c2dm component, appears in neither the deviceidle whitelist nor the netd
     * dozable chain, has no process running overnight — and still receives its
     * 04:00 push. "The module does not list this app" is therefore not evidence
     * that the app cannot be reached.
     */
    private fun declaresFcmComponent(pm: PackageManager, packageName: String): Boolean {
        val now = SystemClock.uptimeMillis()
        synchronized(fcmCache) {
            val cached = fcmCache[packageName]
            if (cached != null && now - cached.checkedAtMs < FCM_CACHE_TTL_MS) {
                return cached.declares
            }
        }
        val declares: Boolean = try {
            declaresFcmUncached(pm, packageName)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "FCM lookup failed for $packageName", t)
            return false
        }
        synchronized(fcmCache) {
            if (fcmCache.size >= FCM_CACHE_MAX) {
                fcmCache.clear()
            }
            fcmCache[packageName] = FcmQuery(declares, now)
        }
        return declares
    }

    private fun declaresFcmUncached(pm: PackageManager, packageName: String): Boolean {
        // Marker 1: the SDK's messaging service class.
        var hasMessagingService = true
        try {
            pm.getServiceInfo(ComponentName(packageName, FCM_MESSAGING_SERVICE_CLASS), 0)
        } catch (ignored: Throwable) {
            hasMessagingService = false
        }
        // Marker 2: the SDK's instance-id receiver class.
        var hasIidReceiver = true
        try {
            pm.getReceiverInfo(ComponentName(packageName, FCM_IID_RECEIVER_CLASS), 0)
        } catch (ignored: Throwable) {
            hasIidReceiver = false
        }
        // Marker 3: the MESSAGING_EVENT action. Asked with setPackage so this
        // stays a per-package question.
        val serviceIntent = Intent(ACTION_MESSAGING_EVENT)
        serviceIntent.setPackage(packageName)
        val hasMessagingEvent = pm.queryIntentServices(serviceIntent, 0).isNotEmpty()
        // Marker 4: the c2dm RECEIVE action.
        val receiverIntent = Intent(ACTION_REMOTE_INTENT)
        receiverIntent.setPackage(packageName)
        val hasReceive = pm.queryBroadcastReceivers(receiverIntent, 0).isNotEmpty()
        return isFcmClient(hasMessagingService, hasIidReceiver, hasMessagingEvent, hasReceive)
    }

    /**
     * The one rule, in one place: **any two of ②③④, or ② alone, or ③ alone.**
     *
     * Spelled out as a boolean table rather than arithmetic on a count: the
     * accepted shapes are not a count — "② alone" and "③ alone" are each one
     * marker while ②+③ is two, and `④ alone` has to be rejected even though it
     * is also one marker. `sum >= 2 || has2 || has3` would say it in fewer lines
     * and hide the three shapes.
     *
     * ① contributes to the two-of-three but is never sufficient on its own.
     * Mirrors `MainActivity.isFcmClient`; keep the two in step.
     */
    private fun isFcmClient(
        hasMessagingService: Boolean,
        hasIidReceiver: Boolean,
        hasMessagingEvent: Boolean,
        hasReceive: Boolean
    ): Boolean {
        // ②③④: any two.
        val amongActions =
            (hasIidReceiver && hasMessagingEvent) ||
                (hasIidReceiver && hasReceive) ||
                (hasMessagingEvent && hasReceive)
        if (amongActions) {
            return true
        }
        // ② alone or ③ alone.
        if (hasIidReceiver || hasMessagingEvent) {
            return true
        }
        // Everything else: ① alone, ④ alone, or nothing.
        return false
    }

    private class FcmQuery(val declares: Boolean, val checkedAtMs: Long)

    companion object {
        private const val TAG = "HyperGreeze"
        private val CN_DEFER_BROADCAST = listOf(
            "com.google.android.intent.action.GCM_RECONNECT",
            "com.google.android.gcm.DISCONNECTED",
            "com.google.android.gcm.CONNECTED",
            "com.google.android.gms.gcm.HEARTBEAT_ALARM"
        )

        const val ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE"
        const val ACTION_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"
        const val FCM_MESSAGING_SERVICE_CLASS =
            "com.google.firebase.messaging.FirebaseMessagingService"
        const val FCM_IID_RECEIVER_CLASS =
            "com.google.firebase.iid.FirebaseInstanceIdReceiver"
        private const val GMS_PACKAGE_NAME = "com.google.android.gms"
        private const val GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent"
        private const val WECHAT_PACKAGE_NAME = "com.tencent.mm"

        /**
         * OS4/V816 sleep mode lives here, in the PowerKeeper process — not in
         * system_server's MiuiNetworkPolicyManagerService, which is why
         * hooking that service's sleep whitelist never fired.
         */
        private const val SLEEP_CONTROLLER_CLASS =
            "com.miui.powerkeeper.statemachine.PhoneSleepModeController"

        /**
         * Push-family actions the autostart pair acts on.
         *
         * The c2dm ones match by suffix because the family has historical
         * prefixes; the Firebase ones are exact. Everything the autostart
         * branches do is gated on this being true, which is what keeps them off
         * the hot path of the broadcast queue.
         */
        private val PUSH_ACTION_SUFFIXES = arrayOf(
            ".android.c2dm.intent.RECEIVE",
            ".android.c2dm.intent.REGISTRATION"
        )
        private val PUSH_ACTIONS = setOf(
            "com.google.firebase.MESSAGING_EVENT",
            "com.google.firebase.INSTANCE_ID_EVENT",
            "com.google.firebase.NEW_TOKEN"
        )

        /**
         * MIUI's autostart AppOp — `com.miui.internal.os.MiuiHooks.OP_AUTO_START`.
         * Not an AOSP op: the MIUI range starts at 10000.
         *
         * **This one is the behaviour; `10053` is only the switch the Security
         * Centre UI shows.** Confirmed on V816 by opening autostart for a
         * package and watching both ops: the ROM writes and reverts the pair
         * together, but writing `10008` alone is already enough to change what
         * the ROM does. So the ~25 packages sitting at `10008=allow /
         * 10053=ignore` are not in an inconsistent *functional* state — only
         * the Settings row is lying. (Corollary: the user's own revert path is
         * the two-step one — a row showing "off" has to be turned "on" before
         * "off" writes `10008` back.)
         *
         * **Why this op decides delivery although the module already answers
         * the broadcast gate.** The hook on
         * `BroadcastQueueModernStubImpl#checkApplicationAutoStart` short-circuits
         * at method entry for caller=GMS + c2dm, so on the FCM hop the op is
         * never even read there. The op is read by the *other* checkpoints
         * (see HOOKS_AND_DIAGNOSTICS §「自启动 AppOps 10008 共四类检查点」):
         * service start/bind (`AutoStartManagerServiceStub#isAllowStartService`
         * and friends) and process start (`ProcessManagerService#isAllowAutoStart`).
         * A message whose process is cached still has to start the messaging
         * service; one whose process was reaped has to start the process. Both
         * read this op, which is why the same write fixes both and why whether
         * the process was dead does not change the outcome.
         *
         * Rejection shows up in logcat as `MIUILOG-AutoStart, Service/Provider/
         * Broadcast Reject …`; the service/activity sibling family is what the
         * read-only `checkWakePath` probe logs as `checkWakePath DENIED`.
         */
        private const val MIUIOP_AUTO_START = 10008

        /**
         * `10053 = OP_AUTO_START_SWITCH` — the manager toggle's own op, which
         * 手机管家's autostart switch writes alongside `10008`
         * (framework.jar → `miui/MiuiAppOpsNames.java`). Writing it is the
         * display-symmetry rung; whether the manager's row *reads* it for
         * display is not yet bytecode-confirmed, so nothing on screen
         * promises a visible change.
         */
        private const val MIUIOP_AUTO_START_SWITCH = 10053

        /**
         * Outcomes of [writeAutostartIfNeeded]. Ints rather than an enum so the
         * broadcast paths stay allocation-free; the two callers only compare
         * them, they never hold them.
         */
        private const val AUTOSTART_ALREADY = 0
        private const val AUTOSTART_WRITTEN = 1
        private const val AUTOSTART_FAILED = -1

        /**
         * The package carries [ApplicationInfo.FLAG_SYSTEM], so the write was
         * refused on purpose. One flag covers every preloaded shape the user
         * can end up with — factory apps on any preloaded partition, and
         * updated system apps, where the update only replaces the APK under
         * /data and never clears the flag.
         *
         * The reason for the refusal is reversibility, not harm: the ROM's own
         * undo path for a written op is 手机管家's autostart toggle, and the
         * deep system packages do not appear on that page at all. This module
         * never learns the previous op values, so a write it cannot undo
         * through the ROM is a write it cannot undo at all.
         */
        private const val AUTOSTART_SKIPPED_SYSTEM = 2

        /**
         * The allowlisted package is no longer installed — checked once, then
         * uninstalled without being unchecked first. There is nothing to write
         * and nothing to fail at: the entry is stale in the allowlist until the
         * user unchecks it (or reinstalls), and the ROM's own op row for a
         * removed package is gone anyway. Skipped silently, like
         * [AUTOSTART_SKIPPED_SYSTEM], because it is a state the allowlist can
         * legitimately sit in, not an error — an ERROR per stale entry per
         * broadcast would be indistinguishable from a storm.
         */
        private const val AUTOSTART_SKIPPED_MISSING = 3

        /**
         * Shortest interval between two autostart writes for the same package on
         * the *lazy* path — see [maybeWriteAutostart]. The setting is persistent,
         * so a burst of pushes does not need a burst of writes, and the
         * read-before-write makes an already-allowed package cost nothing but a
         * read.
         *
         * [applyAutostartToAllowlist] ignores it, because that path is one
         * deliberate user action rather than a stream of system events.
         */
        private const val WAKE_AUTOSTART_COOLDOWN_MS = 60_000L
        /** Package → last autostart write attempt, best-effort under concurrency. */
        private val wakeAutostartLastMs = ConcurrentHashMap<String, Long>()

        /**
         * Set while the module itself is reading the autostart op for its
         * read-before-write. The root choke hooks the very method this read
         * goes through, and without the flag the choke's answer ("allowed")
         * would make every write see an already-allowed op and silently stop
         * the persistent half from ever writing while the root rung is on.
         * Thread-local because the write runs on the allowlist handler thread;
         * always cleared in a finally.
         */
        private val autostartWriteReadInFlight = ThreadLocal<Boolean>()

        /**
         * `AppOpsManager#setMode(int, int, String, int)` and
         * `#checkOpNoThrow(int, int, String)`, resolved once.
         *
         * Neither overload is in the public SDK — it only exposes the name-based
         * forms, because the op *code* is the hidden half. Resolved by
         * reflection and invoked through [XposedInterface.getInvoker], the route
         * the module already uses for hidden framework methods; a miss makes
         * [writeAutostartIfNeeded] report [AUTOSTART_FAILED] rather than crash.
         */
        private val APP_OPS_SET_MODE: Method? by lazy {
            try {
                AppOpsManager::class.java.getDeclaredMethod(
                    "setMode",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    String::class.java, Int::class.javaPrimitiveType
                )
            } catch (t: Throwable) {
                null
            }
        }
        private val APP_OPS_CHECK_OP_NO_THROW: Method? by lazy {
            try {
                AppOpsManager::class.java.getDeclaredMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
                )
            } catch (t: Throwable) {
                null
            }
        }


        /**
         * WiFi scorer behind the relaxed weak-signal switch.
         *
         * It lives in `/system_ext/framework/miui-wifi-service.jar`, not in
         * services.jar, and NetworkBoost.jar only reaches it by reflection — so
         * to re-verify on another generation, grep across the framework jars
         * rather than trusting the presence of NetworkBoost.jar.
         *
         * The 50 is the ROM's own constant (`const/16 v2, #int 50` a few
         * instructions above the `if-ge` that decides `isUsable`); it is not a
         * tunable this module owns, and a *higher* floor would invent a verdict
         * stricter than the ROM's.
         */
        private const val WIFI_SCORER_CLASS =
            "com.android.server.wifi.global.global_scorer.AmlMiuiThirdPartScorer"
        private const val WIFI_SCORER_NOTIFY_METHOD = "notifyScoreAndIsUsable"
        private const val WIFI_SCORER_SCORE_FIELD = "mLegacyIntScore"
        /**
         * The ROM's own verdict threshold, not a tunable this module owns: a
         * *higher* one would invent a verdict stricter than the ROM's.
         */
        private const val WIFI_SCORE_USABLE_MIN = 50
        /**
         * What the score is rewritten to, deliberately one whole point above
         * [WIFI_SCORE_USABLE_MIN] rather than equal to it.
         *
         * Equal also works today, because the ROM asks `< 50` and 50 clears
         * that. It stops working the day a generation asks `<= 50`, and it
         * would then stop silently — the log still says "reported as usable
         * instead" while the ROM quietly disagrees. One point costs nothing
         * here, since nothing downstream reads this field during the call we
         * are inside, and it keeps the verdict on the far side of either
         * comparison.
         */
        private const val WIFI_SCORE_CLAMP_TARGET = WIFI_SCORE_USABLE_MIN + 1
        private const val WIFI_SCORE_CLAMP_LOG_INTERVAL_MS = 30_000L
        /**
         * Services whose implementation ships in the same jar as the scorer, so
         * their binder's loader can resolve it. Order does not matter; any one
         * of them being published is enough.
         */
        private val WIFI_SCORER_CLASS_LOADER_SERVICES = arrayOf(
            "MiuiWifiService",
            "AmlConnectivityService",
            "MiuiNetPathOptimizerService"
        )
        private const val SLEEP_APPLY_METHOD = "applySleepConfig"
        private const val SLEEP_RESTORE_METHOD = "restoreSleepConfig"

        /**
         * Total cutoff switch PowerKeeper itself refuses to bypass: when this
         * reads 1 the whole "turn WiFi and mobile data off" block inside
         * applySleepConfig is jumped over, because the ROM would otherwise
         * break delivery of life-safety messages. Reporting 1 for this one
         * read — and nothing else — reuses that vendor-designed escape hatch
         * instead of fighting the calls further downstream.
         */
        private const val SLEEP_EARTHQUAKE_KEY = "key_open_earthquake_warning"
        private const val GMS_TRAFFIC_PROBE_INTERVAL_MS = 30 * 60_000L
        private const val GMS_TRAFFIC_NUDGE_RESAMPLE_MS = 15_000L

        /** java.util.System property key holding the latest probe chain generation. */
        private const val TRAFFIC_PROBE_GENERATION_KEY = "hyperfcmlive.trafficProbe.generation"

        /**
         * Gate-W heartbeat floor. The gate is reached thousands of times a night,
         * and a fixed every-Nth log buries everything else in modules_*.log.
         * Time-throttling keeps the "the gate was reached" evidence while cutting
         * the volume by roughly an order of magnitude.
         */
        private const val WAKE_PATH_HEARTBEAT_MIN_MS = 30 * 60_000L

        /** Gate-W / Gate-B: detailed probe lines before falling back to plain counters. */
        private const val WAKE_PATH_DETAIL_LIMIT = 10

        /**
         * Autostart gate: detailed lines, budgeted **per callee and per hour**
         * rather than once per process.
         *
         * The budget used to be a single process-lifetime allowance of 20 shared
         * by both gates. An evening of normal phone use spent it before
         * midnight, so the quiet hours — the only stretch in which a denial is
         * interesting — were left with counters and no detail. "Nothing to
         * read" is precisely the case the detail exists for, and the budget was
         * the reason for it.
         *
         * Per callee so one chatty package cannot spend every other package's
         * allowance, and hourly so a burst cannot spend the rest of the night's.
         * The hourly total is a volume backstop for the case where many distinct
         * checked packages are all being denied at once.
         */
        private const val AUTOSTART_GATE_DETAIL_PER_CALLEE = 4
        private const val AUTOSTART_GATE_DETAIL_PER_HOUR = 40
        private const val AUTOSTART_GATE_DETAIL_WINDOW_MS = 60 * 60_000L

        // OS4 (myron) symbols, confirmed against `miui-services` dex: the class is
        // `AutoStartManagerServiceStubImpl`, not the `…Stub` the diagnostics doc
        // used to quote — `loadClass` on the shorter name finds nothing.
        private const val AUTOSTART_STUB_CLASS =
            "com.android.server.am.AutoStartManagerServiceStubImpl"
        private const val AUTOSTART_SERVICE_METHOD = "isAllowStartService"
        private const val AUTOSTART_PROCESS_METHOD = "isAllowAutoStart"
        /**
         * Restart-after-death. Same class as the two above, and the only
         * autostart checkpoint on the "service was killed and is being
         * scheduled back" path — `ActiveServices.scheduleServiceRestartLocked`.
         * Two overloads exist; both take the callee package as parameter 0.
         */
        private const val AUTOSTART_RESTART_METHOD = "canRestartServiceLocked"

        /**
         * MIUI's pre-start feedback: the fourth place in this ROM that reads
         * `10008`, and the only one outside `am`'s autostart family.
         *
         * `PreStartFeedbackImpl#isAutoStart(String, int)` is the read itself — a
         * private helper returning `true` when the op is `MODE_ALLOWED`. Its
         * callers are the pre-start *punishments*: `isProhibitShowWindow` (block
         * a window), `isTempDeniedNotificationForPreStart` (withhold a
         * notification) and `reportProcStartedLocked` (track a cached process
         * for kill). So an app that starts in the background without autostart
         * is not merely refused — the ROM also takes its window and its
         * notification away, which is a delivery-side effect the other three
         * checkpoints cannot produce.
         *
         * Probed read-only: whether these paths are even enabled on this build
         * is a static ROM flag, not something the module may assume.
         */
        private const val AUTOSTART_PRE_START_CLASS =
            "com.android.server.am.PreStartFeedbackImpl"
        private const val AUTOSTART_PRE_START_METHOD = "isAutoStart"

        private const val AUTOSTART_GATE_SERVICE = "service"
        private const val AUTOSTART_GATE_PROCESS = "process"
        private const val AUTOSTART_GATE_RESTART = "restart"
        private const val AUTOSTART_GATE_PRE_START = "pre-start"

        /**
         * Detail-bucket key for the root choke. Not a gate the ROM can deny —
         * the choke answers before the ROM's own logic runs — so it never
         * appears in a `DENIED` line; it only borrows the detail budget so one
         * active package cannot flood the log with per-query lines.
         */
        private const val AUTOSTART_GATE_ROOT = "root"

        /**
         * §5 alarm gate: the Impl overrides the Stub, so the Impl is the live
         * target; the base type is kept as fallback for ROMs that never split it.
         */
        private val ALARM_GATE_CLASS_NAMES = listOf(
            "com.android.server.alarm.AlarmManagerServiceStubImpl",
            "com.android.server.alarm.AlarmManagerServiceStub"
        )
        private const val MILLET_NO_RESTRICT_APP_KEY = "MILLET_NO_RESTRICT_APP"

        /** PowerKeeper user config table: source row for bgControl. */
        private const val USER_TABLE_URI = "content://com.miui.powerkeeper.configure/userTable"
        private const val COL_PKG_NAME = "pkgName"
        private const val COL_USER_ID = "userId"
        private const val COL_LAST_CONFIGURED = "lastConfigured"
        private const val COL_BG_CONTROL = "bgControl"
        private const val BG_CONTROL_NO_RESTRICT = "noRestrict"

        /** P3 scenario constants (from live PowerKeeper dumps). */
        private const val SCENARIO_MUI_AUTO_GMS = 0   // isGmsCoreApp + miuiAuto
        private const val SCENARIO_NO_RESTRICT = 8    // bgControl = noRestrict

        /** P4 recovery actions (outbound IPC to GMS/GSF, not hooks). */
        private const val ACTION_GCM_RECONNECT = "com.google.android.intent.action.GCM_RECONNECT"
        private const val ACTION_GTALK_HEARTBEAT = "com.google.android.intent.action.GTALK_HEARTBEAT"
        private const val ACTION_MCS_HEARTBEAT = "com.google.android.intent.action.MCS_HEARTBEAT"
        private const val GSF_PACKAGE_NAME = "com.google.android.gsf"
        private val RECOVERY_BROADCAST_ACTIONS = arrayOf(
            ACTION_GCM_RECONNECT,
            ACTION_GTALK_HEARTBEAT,
            ACTION_MCS_HEARTBEAT
        )
        private const val CHIMERA_PROVIDER_URI = "content://com.google.android.gms.chimera"

        private const val ALLOWLIST_STALE_MS = 10_000L
        private const val ALLOWLIST_FAILURE_BACKOFF_BASE_MS = 1_000L
        private const val ALLOWLIST_RELOAD_MIN_MS = 500L
        private const val ALLOWLIST_REGISTER_RETRY_MS = 1_000L
        private const val ALLOWLIST_REGISTER_MAX_ATTEMPTS = 120
        private const val FCM_CACHE_TTL_MS = 5L * 60L * 1000L
        private const val FCM_CACHE_MAX = 256

        private val STACK_WALKER: StackWalker =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

        private var powerExemptionManager: PowerExemptionManager? = null

        /**
         * The one caller used to sit behind `SDK_INT >= S`; minSdk is 35, so
         * that branch could never be taken and its `@RequiresApi(S)` was
         * documenting nothing — both were removed, and this is now a plain
         * helper.
         */
        private fun getPowerExemptionManager(context: Context): PowerExemptionManager {
            if (powerExemptionManager == null) {
                powerExemptionManager = PowerExemptionManager(context)
            }
            return powerExemptionManager!!
        }

        private fun targetPackageOf(intent: Intent): String? {
            val pkg = intent.getPackage()
            if (pkg != null) {
                return pkg
            }
            val component = intent.component
            return component?.packageName
        }
    }
}
