package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Smart Power 拦截放行 — OS3 世代实验功能（scope 入口：[Hooker] 的
 * systemServerGroups，开关 [Prefs.KEY_SMARTPOWER_RELEASE]）。
 *
 * OS3 的 SmartPower 子系统（`com.android.server.am.SmartPowerService` +
 * `com.miui.server.smartpower.SmartPowerPolicyManager`）会拦截「发往可休眠且
 * 在拦截名单内的进程」的广播，以及「服务端进程 idle 时」的自启动服务
 * （`shouldInterceptBroadcastLocked` / `shouldInterceptService`，返回 true =
 * 拦截）。已勾选应用夜间处于休眠态时恰是这套判定的高发区，推送广播和
 * MessagingService 启动都可能被丢弃。
 *
 * 两个 hook 点与 com.hyperos.fcmhook v1.2.32 的选择一致（该模块亦只在
 * OS3/旧 MIUI 上生效）：
 * - `SmartPowerService#shouldInterceptBroadcast(ProcessRecord, BroadcastRecord)`
 *   是广播拦截的统一包装入口；Intent 取自 `BroadcastRecord.intent` 字段。
 * - `SmartPowerPolicyManager#shouldInterceptService(Intent, CallerInfo,
 *   ServiceInfo)` 是服务拦截的决策本体（Service 侧包装最终委托到这里），
 *   挂 Manager 一处即覆盖全部调用路径。
 *
 * 门控遵循本项目一贯的白名单收窄：广播侧 = 调用方为 GMS + c2dm `RECEIVE`
 * + 目标包已勾选；服务侧 = 服务所属包已勾选。命中才 `setResult(false)`
 * （放行），其余广播原样交回 ROM。
 *
 * **OS4 上整段是死代码，且这是刻意安排的。** 真机验证（HyperOS
 * 4.0.0.33，services.jar + miui-services.jar 全 dex 扫描）：
 * `shouldInterceptBroadcast` / `shouldInterceptService` 零命中，SmartPower
 * 在 OS4 已退化为被动观察者（onBroadcastStatusChanged 等），拦截决策移入
 * BroadcastQueueModernStubImpl → greeze isRestrictReceiver（主线已覆盖）。
 * 因此安装侧不做任何世代判断：方法找不到就走
 * `logSkipOtherGeneration`——「只有另一代才携带的目标」正是这条日志的
 * 既有语义。OS4 设备上零安装、零开销，日志自解释。
 *
 * 开关实时读取（`releaseEnabled`），勾选即生效，无需重启。关闭时两个
 * hook 仍在但只多一次远程配置读取，与其它实验开关的静默模式一致。
 */
internal object SmartPowerHooks {

    private const val SMART_POWER_SERVICE_CLASS =
        "com.android.server.am.SmartPowerService"
    private const val SMART_POWER_POLICY_MANAGER_CLASS =
        "com.miui.server.smartpower.SmartPowerPolicyManager"
    private const val BROADCAST_METHOD = "shouldInterceptBroadcast"
    private const val SERVICE_METHOD = "shouldInterceptService"

    /** 开关放行的拦截次数（广播 + 服务合计），由 [Hooker] 的汇总行读出。 */
    @Volatile
    internal var smartPowerReleaseCount = 0

    internal fun hookSmartPowerIntercept(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit,
        releaseEnabled: () -> Boolean,
        systemContext: () -> Context?
    ) {
        hookBroadcastIntercept(
            module, classLoader, hookE, logSkip, logSkipOtherGeneration,
            releaseEnabled, systemContext
        )
        hookServiceIntercept(
            module, classLoader, hookE, logSkip, logSkipOtherGeneration,
            releaseEnabled, systemContext
        )
    }

    /**
     * 广播侧：`shouldInterceptBroadcast(ProcessRecord, BroadcastRecord)`，
     * 强制 false = 不拦截。Intent/调用方都从 BroadcastRecord 字段取，参数
     * 位上没有 Intent 可直接用。
     */
    private fun hookBroadcastIntercept(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit,
        releaseEnabled: () -> Boolean,
        systemContext: () -> Context?
    ) {
        try {
            val serviceClass = classLoader.loadClass(SMART_POWER_SERVICE_CLASS)
            val processRecordClass =
                classLoader.loadClass("com.android.server.am.ProcessRecord")
            val broadcastRecordClass =
                classLoader.loadClass("com.android.server.am.BroadcastRecord")
            val method = serviceClass.getDeclaredMethod(
                BROADCAST_METHOD,
                processRecordClass,
                broadcastRecordClass
            )
            val intentField = broadcastRecordClass.getDeclaredField("intent")
            intentField.isAccessible = true
            val callerPackageField =
                broadcastRecordClass.getDeclaredField("callerPackage")
            callerPackageField.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                try {
                    if (releaseEnabled()) {
                        val record = chain.getArg(1)
                        val intent = intentField.get(record) as? Intent
                        val callerPackage =
                            callerPackageField.get(record) as? String
                        val targetPackage = intent?.let {
                            HookerSupport.targetPackageOf(it)
                        }
                        if (callerPackage == HookerSupport.GMS_PACKAGE_NAME &&
                            intent != null &&
                            HookerSupport.ACTION_REMOTE_INTENT == intent.action &&
                            targetPackage != null &&
                            AllowlistGate.moduleAppliesTo(
                                module, systemContext, targetPackage
                            )
                        ) {
                            smartPowerReleaseCount++
                            return@intercept false
                        }
                    }
                } catch (e: Exception) {
                    module.logGated(
                        Log.ERROR, HookerSupport.TAG,
                        "Failed to modify SmartPowerService#$BROADCAST_METHOD", e
                    )
                }
                chain.proceed()
            }
            module.deoptimize(method)
        } catch (e: NoSuchMethodException) {
            // OS4 的 SmartPowerService 还在但只剩观察者方法——本世代没有
            // 这个拦截点，属于「只有另一代才携带的目标」。
            logSkipOtherGeneration(
                "$SMART_POWER_SERVICE_CLASS#$BROADCAST_METHOD absent on this generation, skip"
            )
        } catch (e: ClassNotFoundException) {
            logSkip("$SMART_POWER_SERVICE_CLASS absent, $BROADCAST_METHOD not hooked")
        }
    }

    /**
     * 服务侧：`SmartPowerPolicyManager#shouldInterceptService(Intent,
     * CallerInfo, ServiceInfo)`，强制 false = 不拦截。Service 侧包装
     * （SmartPowerService#shouldInterceptService）最终委托到这里，挂
     * Manager 一处即覆盖全路径。FCM 触发的 MessagingService 启动不携带
     * c2dm action，因此门控只看服务所属包是否已勾选。
     */
    private fun hookServiceIntercept(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit,
        releaseEnabled: () -> Boolean,
        systemContext: () -> Context?
    ) {
        try {
            val managerClass = classLoader.loadClass(SMART_POWER_POLICY_MANAGER_CLASS)
            val callerInfoClass = classLoader.loadClass("miui.security.CallerInfo")
            val method = managerClass.getDeclaredMethod(
                SERVICE_METHOD,
                Intent::class.java,
                callerInfoClass,
                ServiceInfo::class.java
            )
            hookE(method).intercept { chain: XposedInterface.Chain ->
                try {
                    if (releaseEnabled()) {
                        val serviceInfo = chain.getArg(2) as? ServiceInfo
                        val packageName = serviceInfo?.applicationInfo?.packageName
                        if (packageName != null &&
                            AllowlistGate.moduleAppliesTo(
                                module, systemContext, packageName
                            )
                        ) {
                            smartPowerReleaseCount++
                            return@intercept false
                        }
                    }
                } catch (e: Exception) {
                    module.logGated(
                        Log.ERROR, HookerSupport.TAG,
                        "Failed to modify SmartPowerPolicyManager#$SERVICE_METHOD", e
                    )
                }
                chain.proceed()
            }
            module.deoptimize(method)
        } catch (e: NoSuchMethodException) {
            logSkipOtherGeneration(
                "$SMART_POWER_POLICY_MANAGER_CLASS#$SERVICE_METHOD absent on this generation, skip"
            )
        } catch (e: ClassNotFoundException) {
            logSkip("$SMART_POWER_POLICY_MANAGER_CLASS absent, $SERVICE_METHOD not hooked")
        }
    }
}
