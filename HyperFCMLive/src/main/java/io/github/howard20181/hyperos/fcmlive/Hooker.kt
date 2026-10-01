package io.github.howard20181.hyperos.fcmlive

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerExemptionManager
import android.os.Process
import android.net.TrafficStats
import android.os.SystemClock
import android.util.Log
import android.util.Pair
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xposed module entry: keeps FCM / GMS wake paths alive on HyperOS.
 *
 * Do not "modernize" away:
 * - Public class extending [XposedModule] with a no-arg constructor (proguard).
 * - Hook callbacks run in system_server / PowerKeeper: never throw out of them,
 *   never block the main thread, never switch to coroutines.
 * - The four FCM marker constants are shared with the settings list.
 */
@SuppressLint("PrivateApi")
class Hooker : XposedModule() {

    private var param: Pair<String, ClassLoader>? = null
    private var systemContext: Context? = null

    /**
     * Counts behind the end-of-install summary line.
     * Every hook goes through [hookE]; absent targets are counted by [logSkip].
     */
    private var hooksInstalled = 0
    private var hookTargetsAbsent = 0

    private fun hookE(executable: Executable): XposedInterface.HookBuilder {
        val builder = hook(executable)
        hooksInstalled++
        if (apiVersion >= 102) {
            builder.setId(executable.toGenericString())
        }
        return builder
    }

    private fun logSkip(message: String) {
        logSkip(message, Log.INFO)
    }

    private fun logSkipOtherGeneration(message: String) {
        logSkip(message, Log.DEBUG)
    }

    private fun logSkip(message: String, level: Int) {
        hookTargetsAbsent++
        log(level, TAG, message)
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

    private fun logSummary(process: String) {
        log(
            Log.INFO, TAG, "HyperFCMLive active in $process: " +
                "$hooksInstalled hook(s) installed, " +
                "$hookTargetsAbsent target(s) absent on this ROM"
        )
    }

    private fun hookSystemServer(classLoader: ClassLoader) {
        try {
            hookAllowlist()
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook allowlist receiver", t)
        }
        try {
            hookGreezeManagerService(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService", t)
        }
        try {
            hookGreezerNoRestrict(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook GreezerNoRestrict", t)
        }
        try {
            hookDomesticPolicyManager(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook DomesticPolicyManager", t)
        }
        try {
            hookListAppsManager(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook ListAppsManager", t)
        }
        try {
            hookBroadcastQueueModernStubImpl(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook BroadcastQueueModernStubImpl", t)
        }
        try {
            hookGreezeBroadcastCache(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook greeze broadcast cache", t)
        }
        try {
            hookProcessPolicy(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook ProcessPolicy", t)
        }
        try {
            hookAwareResourceControl(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook AwareResourceControl", t)
        }
        try {
            hookSleepModeNetworkPolicy(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook sleep-mode network policy", t)
        }
        try {
            hookActivityManagerService(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook ActivityManagerService", t)
        }
        try {
            hookInternationalPolicyManager(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook InternationalPolicyManager", t)
        }
        try {
            hookUdpPackageRestrict(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook udpPackageRestrict", t)
        }
        try {
            hookProcessCleanerBase(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook ProcessCleanerBase", t)
        }
        try {
            hookAlarmGate(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook alarm gate", t)
        }
        try {
            probeWakePath(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to install wake-path probe", t)
        }
        try {
            probeGmsInMessageApp(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to probe mMessageApp", t)
        }
        try {
            probeSleepModeUidRule(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to probe sleep-mode uid rule", t)
        }
        try {
            probePacketFilterSupport(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to probe packet filter support", t)
        }
        try {
            probeSocketTeardown(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to install socket-teardown probe", t)
        }
        try {
            startGmsTrafficProbe()
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to start GMS traffic probe", t)
        }
    }

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
            val method = cm.getDeclaredMethod(
                name, java.lang.Integer.TYPE, java.lang.Boolean.TYPE
            )
            log(Log.INFO, TAG, "sleep-mode probe: ConnectivityManager#$name present: $method")
        } catch (e: NoSuchMethodException) {
            log(Log.INFO, TAG, "sleep-mode probe: ConnectivityManager#$name ABSENT (gate A3 negative)")
        } catch (e: ClassNotFoundException) {
            logSkip("ConnectivityManager absent, sleep-mode probe skip")
        }
        probeReflectiveMethod(
            classLoader,
            "android.net.ConnectivityManager",
            "enableSleepModeChain",
            "sleep-mode chain probe",
            java.lang.Boolean.TYPE
        )
    }

    /**
     * Reports whether a hidden framework method exists, which is the only way to
     * tell MIUI's reflective trampolines from real work: the target symbol lives in
     * framework.jar, so it is absent from every services.jar dex and cannot be
     * found statically.
     */
    private fun probeReflectiveMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        label: String,
        vararg parameterTypes: Class<*>
    ) {
        try {
            val clazz = classLoader.loadClass(className)
            val method = clazz.getDeclaredMethod(methodName, *parameterTypes)
            log(Log.INFO, TAG, "$label: ${clazz.simpleName}#$methodName present: $method")
        } catch (e: NoSuchMethodException) {
            log(Log.INFO, TAG, "$label: ${className.substringAfterLast('.')}#$methodName ABSENT")
        } catch (e: ClassNotFoundException) {
            logSkip("$className absent, $label skip")
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
            log(Log.INFO, TAG, "packet filter probe: isSupportPacketFilter=$supported")
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
        hookSocketTeardown(
            classLoader,
            "com.miui.whetstone.WhetstoneActivityManager",
            "doDesSocketForUid",
            "client"
        )
        hookSocketTeardown(
            classLoader,
            "com.miui.whetstone.server.WhetstoneActivityManagerService",
            "doDesSocketForUid",
            "server"
        )
        hookSocketTeardown(
            classLoader,
            "com.android.server.net.MiuiNetworkManagementService",
            "doDesSocketForUid",
            "impl"
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun hookSocketTeardown(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        label: String
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
        log(Log.INFO, TAG, "socket-teardown probe[$label]: $methodName hooked (read-only)")
    }

    /** Reports whether each half of the Whetstone pair resolves in system_server. */
    private fun reportWhetstoneClasses(classLoader: ClassLoader) {
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
            val declares = clazz.declaredMethods.any { it.name == "doDesSocketForUid" }
            log(
                Log.INFO, TAG,
                "whetstone probe: $name resolvable, declares doDesSocketForUid=$declares"
            )
        }
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
            log(
                Log.INFO, TAG,
                "mMessageApp probe: size=${list?.size ?: -1}, containsGms=$present"
            )
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
        if ("com.miui.powerkeeper" == packageName) {
            try {
                hookGmsObserver(classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook GmsObserver", t)
            }
            try {
                hookAppStandbyUidState(packageName, classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook AppStandbyController", t)
            }
            try {
                hookGlobalFeatureConfigureHelper(classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook GlobalFeatureConfigureHelper", t)
            }
            try {
                hookNoRestrictList(classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook NoRestrictList", t)
            }
            try {
                hookScenarioCompiler(classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook ScenarioCompiler", t)
            }
            try {
                hookWechatBatteryShield(classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook WeChat battery shield", t)
            }
        }
    }

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
                    if (action is String &&
                        (((chain.getArg(1) as? String)?.let {
                            GMS_PACKAGE_NAME == it &&
                                ACTION_REMOTE_INTENT == action &&
                                shouldApply(calleePkgName)
                        } == true) ||
                            ((GMS_PACKAGE_NAME == calleePkgName ||
                                GMS_PERSISTENT_PROCESS_NAME == calleePkgName) &&
                                CN_DEFER_BROADCAST.contains(action)))
                    ) {
                        return@intercept true
                    }
                    chain.proceed()
                }
                deoptimize(isAllowBroadcastMethod)
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
        try {
            val updateGmsNetStatusMethod = GreezeManagerServiceClass.getDeclaredMethod(
                "updateGmsNetStatus", Boolean::class.javaPrimitiveType
            )
            hookE(updateGmsNetStatusMethod).intercept { chain: XposedInterface.Chain ->
                val args = chain.args.toTypedArray()
                if (args.isNotEmpty()) {
                    args[0] = false
                }
                chain.proceed(args)
            }
            deoptimize(updateGmsNetStatusMethod)
        } catch (e: NoSuchMethodException) {
            logSkip("GreezeManagerService#updateGmsNetStatus absent, skip")
        }
    }

    private fun hookDomesticPolicyManager(classLoader: ClassLoader) {
        val DomesticPolicyManagerClass =
            classLoader.loadClass("com.miui.server.greeze.DomesticPolicyManager")
        val deferBroadcastMethod = DomesticPolicyManagerClass.getDeclaredMethod(
            "deferBroadcast", String::class.java
        )
        hookE(deferBroadcastMethod).intercept { chain: XposedInterface.Chain ->
            // Bypass deferral for the complete FCM chain:
            //  - CN_DEFER_BROADCAST: GMS-internal reconnect/heartbeat actions
            //  - ACTION_REMOTE_INTENT: GMS → target-app c2dm delivery
            // Everything else follows the normal deferral policy (aligned with
            // strict mode's minimal-intervention philosophy).
            val action = chain.getArg(0) as? String
            if (action != null &&
                (CN_DEFER_BROADCAST.contains(action) || ACTION_REMOTE_INTENT == action)
            ) {
                // Success-path log for c2dm only: the four CN actions fire constantly
                // (reconnect / heartbeat) and would flood the log. This is the only way to
                // tell "c2dm really flows through here" apart from "never invoked at all" —
                // required before any decision about gating this branch by package name,
                // which the (String) signature does not expose anyway.
                if (ACTION_REMOTE_INTENT == action && !c2dmDeferBypassLogged) {
                    c2dmDeferBypassLogged = true
                    log(
                        Log.INFO, TAG,
                        "deferBroadcast: c2dm delivery reached this hook; defer suppressed"
                    )
                }
                return@intercept false
            }
            chain.proceed()
        }
        deoptimize(deferBroadcastMethod)
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
     * state freezes GMS, this keeps the network restriction off. Only the GMS uid
     * is forced — every other uid proceeds unchanged, so the instant-messaging
     * apps that *are* on the exempt list keep their existing policy.
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
                chain.proceed()
            }
            deoptimize(isRestrictNetMethod)
            log(Log.INFO, TAG, "DomesticPolicyManager#isRestrictNet hooked for GMS")
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
                            mSystemBlackList?.remove(GMS_PACKAGE_NAME)
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
                        mUseDataWhiteList?.add(GMS_PACKAGE_NAME)
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
                    shouldWake(targetPackage)
                ) {
                    return@intercept true
                }
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
                        shouldWake(calleePackage)
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
                        shouldWake(packageName)
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

    private fun getSystemContext(): Context? {
        if (systemContext == null) {
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentApplication = activityThreadClass.getMethod("currentApplication")
                val ctx = currentApplication.invoke(null)
                if (ctx is Context) {
                    systemContext = ctx
                }
            } catch (ignored: Throwable) {
            }
        }
        return systemContext
    }

    /**
     * MIUI 睡眠模式（PhoneSleepModeController）入睡后会打开一条断网链，
     * 只放行 `sleep_mode_network_white_apps` 名单中的应用，其余整夜掐网。
     * GMS 默认不在名单里，于是 FCM 长连接被静默切断 —— 表现就是
     * "FCM 以为只是网络断了"，直到心跳超时重连才恢复。
     *
     * 进入睡眠时 $45 广播接收器的顺序是：
     *   setSleepModeWhitelistUidRules()   // 对 mSleepModeWhitelistUids 逐个下发 added=true
     *   enableSleepModeChain(true)        // 打开断网链
     * 所以只需在下发之前把 GMS 塞进名单，无需改动链开关的语义；
     * 退出时 clearSleepModeWhitelistUidRules() 会对称撤销，不会留下残留规则。
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
        } catch (e: NoSuchFieldException) {
            logSkip("MiuiNetworkPolicyManagerService.mSleepModeWhitelistUids absent, skip")
            return
        }
        val applyMethod = try {
            serviceClass.getDeclaredMethod("setSleepModeWhitelistUidRules")
        } catch (e: NoSuchMethodException) {
            logSkipOtherGeneration(
                "MiuiNetworkPolicyManagerService#setSleepModeWhitelistUidRules absent, skip"
            )
            return
        }
        whitelistField.isAccessible = true
        applyMethod.isAccessible = true
        hookE(applyMethod).intercept { chain: XposedInterface.Chain ->
            try {
                addGmsToSleepModeWhitelist(whitelistField, chain.thisObject)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to extend sleep-mode network whitelist", t)
            }
            chain.proceed()
        }
        deoptimize(applyMethod)
        log(Log.INFO, TAG, "Sleep-mode network whitelist hooked: GMS will stay online overnight")

        // Belt and braces: if GMS lost its connection overnight (older ROM
        // without the whitelist hook above, or the rule never reached netd),
        // the socket is stale by the time the chain comes down. Nudge GMS to
        // drop it and reconnect instead of waiting for the next heartbeat.
        // Gated by sGmsKeptOnSleepWhitelist: when the whitelist kept GMS online
        // all night, the nudge would only tear down a healthy MCS, so it is
        // skipped and a log line records the decision instead.
        val chainMethod = try {
            serviceClass.getDeclaredMethod(
                "enableSleepModeChain", Boolean::class.javaPrimitiveType
            )
        } catch (e: NoSuchMethodException) {
            logSkipOtherGeneration(
                "MiuiNetworkPolicyManagerService#enableSleepModeChain absent, skip"
            )
            return
        }
        chainMethod.isAccessible = true
        hookE(chainMethod).intercept { chain: XposedInterface.Chain ->
            val enabling = chain.getArg(0) == true
            if (enabling) {
                // Entering sleep used to be silent, which made "the callback never
                // ran" and "it ran but the whitelist was empty" look identical in
                // the logs. Record it, plus the size we are about to iterate over.
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
                    log(
                        Log.INFO, TAG,
                        "Sleep mode exited: GMS was kept on the whitelist, " +
                            "skipping recovery nudge (MCS untouched)"
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
     */
    private fun sleepModeWhitelistSize(whitelistField: Field, owner: Any?): String {
        return try {
            val raw = whitelistField.get(owner)
            if (raw is Collection<*>) raw.size.toString() else "<not a collection>"
        } catch (t: Throwable) {
            "<unreadable>"
        }
    }

    /**
     * Experiment (default off): stop PowerKeeper from silently rewriting
     * WeChat's battery policy to "no restrict".
     *
     * ROM forensics (OS4 V816, PowerSaveConfigureManager): the AIDL getter
     * `getPowerSaveAppConfigure` embeds a promotion write — when the package
     * answers `PowerManager.isIgnoringBatteryOptimizations == true` and its
     * userTable.bgControl is still "miuiAuto", the getter composes a Bundle
     * with AppConfigure="no_restrict" and calls `setPowerSaveAppConfigure`
     * from inside itself. Combined with the cloud-tended doze whitelist
     * (cloud feature "doze_whitelist_apps" → GlobalFeatureConfigureHelper →
     * DeviceIdlePolicyHelper; WeChat observed on-device in the user section
     * of `dumpsys deviceidle whitelist`), this is the loop that keeps
     * reverting WeChat to 无限制 after the user picks a stricter policy.
     *
     * The hook passes every call through except the exact promotion write
     * for WeChat. Manual writes from the settings UI have no getter frame in
     * the call stack and still work — including a manual "no restrict"; the
     * switch only stops the *automatic* rewrite. The stack check runs after
     * the cheap gates (switch off → pass, other package → pass, other value
     * → pass), so its cost is bounded by how rarely those match.
     */
    private fun hookWechatBatteryShield(classLoader: ClassLoader) {
        val managerClass = classLoader.loadClass(
            "com.miui.powerkeeper.provider.PowerSaveConfigureManager"
        )
        val setMethod = try {
            managerClass.getDeclaredMethod("setPowerSaveAppConfigure", Bundle::class.java)
        } catch (e: NoSuchMethodException) {
            logSkip("PowerSaveConfigureManager#setPowerSaveAppConfigure absent, wechat-shield skip")
            return
        }
        val skipValue = skipValueFor(setMethod.returnType)
        hookE(setMethod).intercept { chain: XposedInterface.Chain ->
            val bundle = chain.getArg(0) as? Bundle
            if (bundle == null || !isWechatShieldEnabled()) {
                return@intercept chain.proceed()
            }
            if (WECHAT_PACKAGE_NAME != bundle.getString("App")) {
                return@intercept chain.proceed()
            }
            if ("no_restrict" != bundle.getString("AppConfigure")) {
                return@intercept chain.proceed()
            }
            var fromGetter = false
            for (frame in Thread.currentThread().stackTrace) {
                if (frame.className == WECHAT_SHIELD_OWNER_CLASS &&
                    "getPowerSaveAppConfigure" == frame.methodName
                ) {
                    fromGetter = true
                    break
                }
            }
            if (!fromGetter) {
                return@intercept chain.proceed()
            }
            val blocked = ++wechatShieldBlockCount
            if (blocked == 1L || blocked % 10L == 0L) {
                log(
                    Log.INFO, TAG,
                    "wechat-shield: blocked auto no_restrict promotion #$blocked"
                )
            }
            skipValue
        }
        deoptimize(setMethod)
        log(
            Log.INFO, TAG,
            "PowerSaveConfigureManager#setPowerSaveAppConfigure hooked (wechat-shield, default off)"
        )
    }

    /**
     * The shield flag lives in the shared config group, but unlike the
     * system_server hooks there is no broadcast receiver in the PowerKeeper
     * process — read it lazily at each qualifying call instead. The reads
     * only happen after the package/value gates match, so the frequency is
     * that of WeChat promotion attempts, not of battery-policy traffic.
     */
    private fun isWechatShieldEnabled(): Boolean {
        return try {
            getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_WECHAT_SHIELD, false)
        } catch (ignored: Throwable) {
            // Fail open: an unreadable switch must not start rewriting
            // system behavior — the feature is opt-in, a failed read keeps
            // it off.
            false
        }
    }

    private fun hookForceFalse(owner: Class<*>, name: String) {
        try {
            val method = owner.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
            hookE(method).intercept { chain: XposedInterface.Chain ->
                val args = chain.args.toTypedArray()
                args[0] = false
                chain.proceed(args)
            }
            deoptimize(method)
        } catch (e: NoSuchMethodException) {
            logSkipOtherGeneration("GmsObserver#$name absent, skip")
        }
    }

    private fun hookGmsObserver(classLoader: ClassLoader) {
        try {
            val NetdExecutorClass = classLoader.loadClass("com.miui.powerkeeper.utils.NetdExecutor")
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
                logSkipOtherGeneration("NetdExecutor#initGmsChain absent, skip")
            }
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
                logSkipOtherGeneration("NetdExecutor#setGmsChainState absent, skip")
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
                log(
                    Log.INFO, TAG, "NetdExecutor#execute returns " + executeReturn.name +
                        "; standby-firewall skip returns " + (skipValue?.toString() ?: "null")
                )
            } catch (e: NoSuchMethodException) {
                logSkip("NetdExecutor#execute not found, skip command-level GMS net hooks")
            }
        } catch (e: ClassNotFoundException) {
            log(Log.ERROR, TAG, "Failed to hook NetdExecutor", e)
        }
        try {
            val GmsObserverClass = classLoader.loadClass("com.miui.powerkeeper.utils.GmsObserver")
            for (legacyName in arrayOf(
                "updateGmsAlarm", "updateGmsNetWork", "updateGoogleReletivesWakelock"
            )) {
                hookForceFalse(GmsObserverClass, legacyName)
            }
            for (alwaysSkip in arrayOf("disableGms", "disableGmsApps")) {
                try {
                    val disableMethod = GmsObserverClass.getDeclaredMethod(alwaysSkip)
                    hookE(disableMethod).intercept { _: XposedInterface.Chain -> null }
                    deoptimize(disableMethod)
                } catch (e: NoSuchMethodException) {
                    logSkipOtherGeneration("GmsObserver#$alwaysSkip absent, skip")
                }
            }
            for (limitFlag in arrayOf("updateGmsEnabled", "updateGmsState", "updateGmsInstalled")) {
                hookForceFalse(GmsObserverClass, limitFlag)
            }
            try {
                val updateFrameworkGmsNetStatusMethod = GmsObserverClass.getDeclaredMethod(
                    "updateFrameworkGmsNetStatus", Boolean::class.javaPrimitiveType
                )
                hookE(updateFrameworkGmsNetStatusMethod).intercept { chain: XposedInterface.Chain ->
                    val args = chain.args.toTypedArray()
                    if (args.isNotEmpty() && java.lang.Boolean.TRUE == args[0]) {
                        args[0] = false
                    }
                    chain.proceed(args)
                }
                deoptimize(updateFrameworkGmsNetStatusMethod)
            } catch (e: NoSuchMethodException) {
                logSkip("GmsObserver#updateFrameworkGmsNetStatus absent, skip")
            }
            try {
                val onGoogleReachabilityChangedMethod = GmsObserverClass.getDeclaredMethod(
                    "onGoogleReachabilityChanged", Boolean::class.javaPrimitiveType
                )
                hookE(onGoogleReachabilityChangedMethod).intercept { chain: XposedInterface.Chain ->
                    val args = chain.args.toTypedArray()
                    args[0] = true
                    chain.proceed(args)
                }
                deoptimize(onGoogleReachabilityChangedMethod)
            } catch (e: NoSuchMethodException) {
                logSkip("GmsObserver#onGoogleReachabilityChanged absent, skip")
            }
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
                log(Log.INFO, TAG, "UserConfigureHelper#${method.name} hooked for userTable re-assert")
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
                logSkipOtherGeneration("AurogonImmobulusMode#isNoRestrictFreezeable absent, skip")
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

    @Volatile
    private var sStrictMode = false

    /** Blocked auto-promotions since this classloader loaded (wechat-shield). */
    @Volatile
    private var wechatShieldBlockCount = 0L


    private fun loadAllowlistFromRemotePrefs() {
        try {
            val prefs = getRemotePreferences(Prefs.GROUP_CONFIG)
            val set = prefs.getStringSet(Prefs.KEY_ALLOWLIST, emptySet())
            val loaded = if (set != null) HashSet(set) else HashSet()
            val strict = prefs.getBoolean(Prefs.KEY_STRICT_MODE, false)
            if (loaded != sAllowlist || strict != sStrictMode) {
                // Log on content change, not on every read: the stale-path reload
                // would otherwise repeat an identical line every ALLOWLIST_STALE_MS.
                log(
                    Log.INFO, TAG,
                    "allowlist loaded: ${loaded.size} pkg(s), strict=$strict"
                )
            }
            sAllowlist = loaded
            sStrictMode = strict
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
            for (attempt in 0 until ALLOWLIST_REGISTER_MAX_ATTEMPTS) {
                if (registerAllowlistReceiver()) {
                    return@Thread
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
     * Wake-time privileges: auto-start allowance, stopped-package delivery and the
     * 2000ms temporary power exemption.
     *
     * GMS is exempt here for the same reason it is exempt in [shouldApply]: the whole
     * point of the module is that the GMS/FCM chain never gets narrowed, no matter how
     * the allowlist is configured. Without this branch a non-empty allowlist would make
     * every [shouldWake] call site answer false when the callee happens to be GMS itself
     * (the only call site without a caller check is `isNeedCachedBroadcast`).
     */
    private fun shouldWake(targetPackage: String?): Boolean {
        val allowlist = getFcmAllowlist()
        return allowlist.isEmpty() ||
            allowlist.contains(targetPackage) ||
            GMS_PACKAGE_NAME == targetPackage ||
            GMS_PERSISTENT_PROCESS_NAME == targetPackage
    }

    private fun shouldApply(packageName: String?): Boolean {
        if (!sStrictMode) {
            return true
        }
        val allowlist = getFcmAllowlist()
        if (allowlist.isEmpty()) {
            return true
        }
        return allowlist.contains(packageName) ||
            GMS_PACKAGE_NAME == packageName ||
            GMS_PERSISTENT_PROCESS_NAME == packageName
    }

    private fun registerAllowlistReceiver(): Boolean {
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
                    if (Prefs.ACTION_ALLOWLIST_CHANGED == intent.action) {
                        requestAllowlistReload()
                    }
                }
            }
            val filter = IntentFilter(Prefs.ACTION_ALLOWLIST_CHANGED)
            val handler = allowlistBackgroundHandler()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                sys.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
            } else {
                sys.registerReceiver(receiver, filter, null, handler)
            }
            allowlistReceiverRegistered = true
            log(Log.INFO, TAG, "Allowlist receiver installed")
            return true
        } catch (e: Throwable) {
            return false
        } finally {
            allowlistRegistering.set(false)
        }
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
                        shouldWake(targetPackage)
                    ) {
                        try {
                            if ((intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
                                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            }
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "Failed to add FLAG_INCLUDE_STOPPED_PACKAGES", t)
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
                            }
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "Failed to add temporary power exemption", t)
                        }
                    }
                } catch (t: Throwable) {
                    log(Log.ERROR, TAG, "C2DM broadcast hook failed", t)
                }
            }
            chain.proceed()
        }
        deoptimize(broadcastMethod)
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
            logSkipOtherGeneration("checkAlarmIsAllowedSend absent, alarm gate skip")
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
        probeBackgroundHandler().post { probeGmsTraffic("startup") }
        probeBackgroundHandler().postDelayed(object : Runnable {
            override fun run() {
                if (latestTrafficProbeGeneration() != generation) {
                    log(
                        Log.INFO, TAG,
                        "gms traffic probe: chain generation $generation superseded, " +
                            "retire without rescheduling"
                    )
                    return
                }
                probeGmsTraffic("periodic")
                probeBackgroundHandler().postDelayed(this, GMS_TRAFFIC_PROBE_INTERVAL_MS)
            }
        }, GMS_TRAFFIC_PROBE_INTERVAL_MS)
        log(
            Log.INFO, TAG,
            "gms traffic probe: scheduled every ${GMS_TRAFFIC_PROBE_INTERVAL_MS / 60_000} min " +
                "(read-only, generation $generation)"
        )
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
        val uid = gmsUid()
        if (uid == null) {
            log(Log.INFO, TAG, "gms traffic probe [$reason]: GMS uid unresolved, skip")
            return
        }
        log(
            Log.INFO, TAG,
            "gms traffic probe [$reason]: uid=$uid ${trafficSinceLastProbe(uid)}"
        )
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
            logSkipOtherGeneration("ActivityManagerServiceImpl absent, wake-path probe skip")
            return
        }
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name == "checkWakePath" &&
                m.parameterCount == 7 &&
                m.returnType == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            logSkipOtherGeneration("checkWakePath absent, wake-path probe skip")
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
                    if (denied <= 10) {
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

    /** One-shot: confirms c2dm actually reaches `DomesticPolicyManager#deferBroadcast`. */
    @Volatile
    private var c2dmDeferBypassLogged = false

    /** One-shot: confirms the §5 alarm gate re-allowed a GMS alarm the ROM had denied. */
    @Volatile
    private var alarmGateBypassLogged = false

    /** Gate-W: one-shot for the first `checkWakePath` denial, and the first reach. */
    @Volatile
    private var wakePathDeniedLogged = false

    @Volatile
    private var wakePathReachedLogged = false

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

    /** 3.2 gate: how many times the ROM really asked netd to destroy sockets. */
    @Volatile
    private var socketTeardownCount = 0

    /** One-shot: confirms P0 #1 actually suppressed a network restriction for GMS. */
    @Volatile
    private var gmsRestrictNetLogged = false

    /** One-shot: confirms 3.6 actually kept the UDP packet filter off GMS. */
    @Volatile
    private var gmsUdpFilterLogged = false

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
     */
    @Volatile
    private var sGmsKeptOnSleepWhitelist = false

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                shouldApply(pkg)
            ) {
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
                    shouldApply(pkgName) &&
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
        val serviceIntent = Intent(ACTION_MESSAGING_EVENT)
        serviceIntent.setPackage(packageName)
        if (pm.queryIntentServices(serviceIntent, 0).isNotEmpty()) {
            return true
        }
        val receiverIntent = Intent(ACTION_REMOTE_INTENT)
        receiverIntent.setPackage(packageName)
        if (pm.queryBroadcastReceivers(receiverIntent, 0).isNotEmpty()) {
            return true
        }
        try {
            pm.getServiceInfo(ComponentName(packageName, FCM_MESSAGING_SERVICE_CLASS), 0)
            return true
        } catch (ignored: Throwable) {
        }
        try {
            pm.getReceiverInfo(ComponentName(packageName, FCM_IID_RECEIVER_CLASS), 0)
            return true
        } catch (ignored: Throwable) {
        }
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
        private const val WECHAT_SHIELD_OWNER_CLASS =
            "com.miui.powerkeeper.provider.PowerSaveConfigureManager"

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

        @RequiresApi(Build.VERSION_CODES.S)
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
