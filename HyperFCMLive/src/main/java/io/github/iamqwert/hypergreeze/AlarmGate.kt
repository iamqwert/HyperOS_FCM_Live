package io.github.iamqwert.hypergreeze

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The §5 alarm delivery gate, extracted verbatim from `Hooker`
 * (zero-behaviour refactor).
 *
 * `Hooker`'s instance capabilities are injected at the call site, the same way
 * [GreezeHooks] and [SleepModeGate] receive theirs: `module` is the
 * [XposedInterface] the module entry object is (`log` / `deoptimize`),
 * `hookE` is `Hooker#hookE` (the counted, `setId`-deduplicated hook builder —
 * its `hooksInstalled` counter feeds the end-of-install summary that stays
 * with `Hooker`), `logSkip` is the absent-target report and
 * `logSkipOtherGeneration` the deferred one (both counters stay with
 * `Hooker`), and `isGmsUid` is `Hooker`'s shared GMS uid gate
 * (cachedGmsAppId stays with `Hooker` — several subsystems consult it).
 */
internal object AlarmGate {

    /** One-shot: confirms the §5 alarm gate re-allowed a GMS alarm the ROM had denied. */
    @Volatile
    private var alarmGateBypassLogged = false

    /** One-shot: confirms the §5 alarm gate is actually reached for GMS alarms. */
    @Volatile
    private var alarmGateSeenLogged = false

    /** One-shot: confirms alignAlarmLocked skipped screen-off alignment for a GMS alarm. */
    @Volatile
    private var alarmAlignBypassLogged = false

    /** One-shot: confirms adjustAlarmLocked skipped screen-off/offline deferral for a GMS alarm. */
    @Volatile
    private var alarmAdjustBypassLogged = false

    /** One-shot: confirms adjustAlarmLocked actively cleared a pre-existing vendor pending delay. */
    @Volatile
    private var alarmAdjustClearedLogged = false

    /** One-shot: confirms isExemptFromSsru exempted a GMS alarm the ROM would have gated. */
    @Volatile
    private var alarmSsruExemptLogged = false

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
     * why this one was nearly mis-filed as dead code.
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
    internal fun hookAlarmGate(env: HookEnv) {
        val module = env.module
        val classLoader = env.classLoader
        val alarmClass = try {
            classLoader.loadClass("com.android.server.alarm.Alarm")
        } catch (e: ClassNotFoundException) {
            env.logSkip("com.android.server.alarm.Alarm absent, alarm gate skip")
            return
        }
        val creatorUidField: Field = try {
            alarmClass.getDeclaredField("creatorUid")
        } catch (e: NoSuchFieldException) {
            env.logSkip("Alarm#creatorUid absent, alarm gate skip")
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
        var stubClass: Class<*>? = null
        var methods: List<Method> = emptyList()
        for (name in HookerSupport.ALARM_GATE_CLASS_NAMES) {
            val clazz = try {
                classLoader.loadClass(name)
            } catch (ignored: ClassNotFoundException) {
                continue
            }
            if (stubClass == null) {
                stubClass = clazz
            }
            val found = clazz.declaredMethods.filter { m ->
                m.name == "checkAlarmIsAllowedSend" &&
                    m.parameterCount == 2 &&
                    m.returnType == Boolean::class.javaPrimitiveType
            }
            if (found.isNotEmpty()) {
                stubClass = clazz
                methods = found
                break
            }
        }
        if (methods.isEmpty()) {
            env.logSkipOtherGeneration("checkAlarmIsAllowedSend (alarm gate)")
        } else {
            for (method in methods) {
                val field = creatorUidField
                method.isAccessible = true
                env.hookE(method).intercept { chain: XposedInterface.Chain ->
                    val result = chain.proceed()
                    try {
                        if (java.lang.Boolean.FALSE == result) {
                            val alarm = chain.getArg(1)
                            if (alarm != null) {
                                val uid = field.getInt(alarm)
                                if (env.isGmsUid(uid)) {
                                    // One-shot INFO: the only way to tell "the ROM
                                    // actually denies GMS alarms here" apart from
                                    // "the gate is never reached".
                                    if (!alarmGateBypassLogged) {
                                        alarmGateBypassLogged = true
                                        module.logGated(
                                            Log.INFO, HookerSupport.TAG,
                                            "checkAlarmIsAllowedSend: re-allowed denied GMS alarm" +
                                                " (creatorUid=$uid)"
                                        )
                                    }
                                    return@intercept true
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to evaluate alarm gate", t)
                    }
                    try {
                        // Counterpart of the one-shot above: proves the gate is
                        // actually reached for GMS alarms when the ROM allows them,
                        // so a silent log later means "never denied", not "never run".
                        if (java.lang.Boolean.TRUE == result && !alarmGateSeenLogged) {
                            val alarm = chain.getArg(1)
                            if (alarm != null) {
                                val uid = field.getInt(alarm)
                                if (env.isGmsUid(uid)) {
                                    alarmGateSeenLogged = true
                                    module.logGated(
                                        Log.INFO, HookerSupport.TAG,
                                        "checkAlarmIsAllowedSend: GMS alarm allowed by ROM" +
                                            " (creatorUid=$uid)"
                                    )
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to evaluate alarm gate (allow path)", t)
                    }
                    result
                }
                module.deoptimize(method)
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "checkAlarmIsAllowedSend hooked on ${method.declaringClass.simpleName}"
                )
            }
        }

        // CN ROMs omit GMS from AlarmManagerServiceStubImpl.ADJUST_WHITE_LIST:
        // framework-ext-res puts com.google.android.gms in ADJUST_WHITE_LIST_GLOBAL
        // (0x11030001), which init() only merges when Build.IS_INTERNATIONAL_BUILD
        // is true. Hook the three OS3/OS4 alarm policy points directly by creatorUid
        // so hot-reload works without re-running init() and GSF (shared uid) is
        // covered alongside GMS.
        val resolvedStub = stubClass ?: return
        hookAlarmSkipForGms(
            env = env,
            stubClass = resolvedStub,
            alarmClass = alarmClass,
            creatorUidField = creatorUidField,
            methodName = "alignAlarmLocked",
            logTag = "alignAlarmLocked: skipped screen-off alignment for GMS alarm",
            alreadyLogged = { alarmAlignBypassLogged },
            markLogged = { alarmAlignBypassLogged = true }
        )
        hookAdjustAlarmLocked(env, resolvedStub, alarmClass, creatorUidField)
        hookSsruExemption(env, resolvedStub, alarmClass, creatorUidField)
    }

    /**
     * Entry-point bypass for `adjustAlarmLocked(Alarm)Z`.
     *
     * Unlike `alignAlarmLocked`, an alarm scheduled before hot reload or early-boot
     * GMS UID resolution may already carry a vendor pending delay in
     * `Alarm.policyElapsed[POWER_PENDING_POLICY_INDEX]`. When `setPolicyElapsed`
     * and the policy index constants are present (verified on OS4 `services.jar`),
     * clear that slot to `0L` and propagate the real boolean return value so
     * `AlarmStore` reschedules delivery without disturbing requester/Doze policies.
     * Falls back to returning `false` if the fields/method are unavailable.
     *
     * Also deoptimizes `proxyAlarm` and `-$$Nest$madjustAlarmLocked` on the stub
     * class because `adjustAlarmLocked` is `private` and otherwise eligible for
     * ART inlining into its same-class callers.
     */
    private fun hookAdjustAlarmLocked(
        env: HookEnv,
        stubClass: Class<*>,
        alarmClass: Class<*>,
        creatorUidField: Field
    ) {
        val module = env.module
        val method = try {
            stubClass.getDeclaredMethod("adjustAlarmLocked", alarmClass)
        } catch (ignored: NoSuchMethodException) {
            env.logSkip("${stubClass.simpleName}#adjustAlarmLocked absent, skip")
            return
        }
        if (method.returnType != Boolean::class.javaPrimitiveType) {
            env.logSkip(
                "${stubClass.simpleName}#adjustAlarmLocked unexpected return type " +
                    "${method.returnType.name}, skip"
            )
            return
        }
        val pendingClear = resolvePendingPolicyClear(alarmClass)
        method.isAccessible = true
        env.hookE(method).intercept { chain: XposedInterface.Chain ->
            try {
                val alarm = chain.getArg(0)
                if (alarm != null) {
                    val uid = creatorUidField.getInt(alarm)
                    if (env.isGmsUid(uid)) {
                        if (pendingClear != null) {
                            try {
                                val changed = module.getInvoker(pendingClear.setPolicyElapsed)
                                    .invoke(alarm, pendingClear.pendingPolicyIndex, 0L) == true
                                if (changed && !alarmAdjustClearedLogged) {
                                    alarmAdjustClearedLogged = true
                                    module.logGated(
                                        Log.INFO, HookerSupport.TAG,
                                        "adjustAlarmLocked: cleared pre-existing vendor pending delay " +
                                            "for GMS alarm (creatorUid=$uid, policy=${pendingClear.pendingPolicyIndex})"
                                    )
                                } else if (!alarmAdjustBypassLogged) {
                                    alarmAdjustBypassLogged = true
                                    module.logGated(
                                        Log.INFO, HookerSupport.TAG,
                                        "adjustAlarmLocked: skipped screen-off/offline deferral for GMS alarm (creatorUid=$uid)"
                                    )
                                }
                                return@intercept changed
                            } catch (t: Throwable) {
                                module.logGated(
                                    Log.ERROR, HookerSupport.TAG,
                                    "adjustAlarmLocked: setPolicyElapsed failed, falling back to false", t
                                )
                            }
                        }
                        if (!alarmAdjustBypassLogged) {
                            alarmAdjustBypassLogged = true
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "adjustAlarmLocked: skipped screen-off/offline deferral for GMS alarm (creatorUid=$uid)"
                            )
                        }
                        return@intercept false
                    }
                }
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to evaluate adjustAlarmLocked", t)
            }
            chain.proceed()
        }
        module.deoptimize(method)
        for (callerName in arrayOf("proxyAlarm", "-\$\$Nest\$madjustAlarmLocked")) {
            for (caller in stubClass.declaredMethods) {
                if (caller.name == callerName) {
                    module.deoptimize(caller)
                }
            }
        }
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "adjustAlarmLocked hooked on ${method.declaringClass.simpleName}" +
                " (clearPending=${pendingClear != null})"
        )
    }

    private class PendingPolicyClear(
        val pendingPolicyIndex: Int,
        val setPolicyElapsed: Method
    )

    private fun resolvePendingPolicyClear(alarmClass: Class<*>): PendingPolicyClear? {
        return try {
            val pendingPolicy = alarmClass.getDeclaredField("POWER_PENDING_POLICY_INDEX")
                .apply { isAccessible = true }
                .getInt(null)
            val policyCount = alarmClass.getDeclaredField("NUM_POLICIES")
                .apply { isAccessible = true }
                .getInt(null)
            if (pendingPolicy <= 0 || pendingPolicy >= policyCount) {
                return null
            }
            val setPolicy = alarmClass.getDeclaredMethod(
                "setPolicyElapsed",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )
            if (setPolicy.returnType != Boolean::class.javaPrimitiveType) {
                return null
            }
            setPolicy.isAccessible = true
            PendingPolicyClear(pendingPolicy, setPolicy)
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * Entry-point bypass returning `false` ("not modified") for GMS alarms.
     *
     * Must intercept *before* `chain.proceed()` because both `alignAlarmLocked`
     * and `adjustAlarmLocked` mutate `Alarm` in place (`windowLength`,
     * `whenElapsed`, `policyElapsed[5]`) before returning `true`. Returning
     * `false` at entry matches the ROM's own `ADJUST_WHITE_LIST` early exit.
     */
    private fun hookAlarmSkipForGms(
        env: HookEnv,
        stubClass: Class<*>,
        alarmClass: Class<*>,
        creatorUidField: Field,
        methodName: String,
        logTag: String,
        alreadyLogged: () -> Boolean,
        markLogged: () -> Unit
    ) {
        val module = env.module
        val method = try {
            stubClass.getDeclaredMethod(methodName, alarmClass)
        } catch (ignored: NoSuchMethodException) {
            env.logSkip("${stubClass.simpleName}#$methodName absent, skip")
            return
        }
        if (method.returnType != Boolean::class.javaPrimitiveType) {
            env.logSkip("${stubClass.simpleName}#$methodName unexpected return type ${method.returnType.name}, skip")
            return
        }
        method.isAccessible = true
        env.hookE(method).intercept { chain: XposedInterface.Chain ->
            try {
                val alarm = chain.getArg(0)
                if (alarm != null) {
                    val uid = creatorUidField.getInt(alarm)
                    if (env.isGmsUid(uid)) {
                        if (!alreadyLogged()) {
                            markLogged()
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "$logTag (creatorUid=$uid)"
                            )
                        }
                        return@intercept false
                    }
                }
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to evaluate $methodName", t)
            }
            chain.proceed()
        }
        module.deoptimize(method)
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "$methodName hooked on ${method.declaringClass.simpleName}"
        )
    }

    /**
     * SSRU alarm exemption (`isExemptFromSsru(Alarm)Z`).
     *
     * Read-only predicate consulted by both `timeBasedOnSsruLocked(Alarm)`
     * (which otherwise pushes `policyElapsed[4]` out by 1 year when budget is
     * exhausted) and `AlarmManagerService$AlarmThread#run`. Runs the ROM's
     * body first and widens only a `false` verdict for GMS/GSF uids.
     */
    private fun hookSsruExemption(
        env: HookEnv,
        stubClass: Class<*>,
        alarmClass: Class<*>,
        creatorUidField: Field
    ) {
        val module = env.module
        val method = try {
            stubClass.getDeclaredMethod("isExemptFromSsru", alarmClass)
        } catch (ignored: NoSuchMethodException) {
            env.logSkip("${stubClass.simpleName}#isExemptFromSsru absent, skip")
            return
        }
        if (method.returnType != Boolean::class.javaPrimitiveType) {
            env.logSkip("${stubClass.simpleName}#isExemptFromSsru unexpected return type ${method.returnType.name}, skip")
            return
        }
        method.isAccessible = true
        env.hookE(method).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            try {
                if (java.lang.Boolean.FALSE == result) {
                    val alarm = chain.getArg(0)
                    if (alarm != null) {
                        val uid = creatorUidField.getInt(alarm)
                        if (env.isGmsUid(uid)) {
                            if (!alarmSsruExemptLogged) {
                                alarmSsruExemptLogged = true
                                module.logGated(
                                    Log.INFO, HookerSupport.TAG,
                                    "isExemptFromSsru: exempted GMS alarm from SSRU" +
                                        " (creatorUid=$uid)"
                                )
                            }
                            return@intercept true
                        }
                    }
                }
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to evaluate isExemptFromSsru", t)
            }
            result
        }
        module.deoptimize(method)
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "isExemptFromSsru hooked on ${method.declaringClass.simpleName}"
        )
    }
}
