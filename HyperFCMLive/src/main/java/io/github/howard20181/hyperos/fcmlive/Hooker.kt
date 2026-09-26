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
            hookProcessCleanerBase(classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook ProcessCleanerBase", t)
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
                hookGlobalFeatureConfigureHelper(classLoader)
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Failed to hook GlobalFeatureConfigureHelper", t)
            }
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        log(Log.WARN, TAG, "Hot reload requested — a full reboot is recommended for reliability")
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
        hookE(deferBroadcastMethod).intercept { _: XposedInterface.Chain -> false }
        deoptimize(deferBroadcastMethod)
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
                    if (args.size > 1) {
                        args[1] = true
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
                            val rewritten = cmdArgs.copyOf()
                            rewritten[1] = "allow"
                            args[3] = rewritten
                            return@intercept chain.proceed(args)
                        }
                        if ("enablemiuistandby" == cmd && cmdArgs.isNotEmpty() &&
                            "enable" == cmdArgs[0].toString()
                        ) {
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
            } catch (ignored: NoSuchMethodException) {
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
                            if (result is List<*> && !result.contains(GMS_PACKAGE_NAME)) {
                                val source = result
                                val whiteList = ArrayList<Any?>(source)
                                whiteList.add(GMS_PACKAGE_NAME)
                                addIfAbsentInPlace(source, GMS_PACKAGE_NAME)
                                return@intercept whiteList
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

    @Volatile
    private var sAllowlist: Set<String> = emptySet()

    @Volatile
    private var sStrictMode = false

    private fun loadAllowlistFromRemotePrefs() {
        try {
            val prefs = getRemotePreferences(Prefs.GROUP_CONFIG)
            val set = prefs.getStringSet(Prefs.KEY_ALLOWLIST, emptySet())
            sAllowlist = if (set != null) HashSet(set) else HashSet()
            sStrictMode = prefs.getBoolean(Prefs.KEY_STRICT_MODE, false)
        } catch (e: Exception) {
            log(Log.ERROR, TAG, "Failed to read remote allowlist", e)
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
            SystemClock.uptimeMillis() - sAllowlistReadMs >= ALLOWLIST_STALE_MS
        ) {
            requestAllowlistReload()
        }
        return HashSet(sAllowlist)
    }

    private fun shouldWake(targetPackage: String?): Boolean {
        val allowlist = getFcmAllowlist()
        return allowlist.isEmpty() || allowlist.contains(targetPackage)
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

        private const val ALLOWLIST_STALE_MS = 10_000L
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
