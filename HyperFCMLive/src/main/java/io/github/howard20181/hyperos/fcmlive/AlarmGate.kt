package io.github.howard20181.hyperos.fcmlive

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
    internal fun hookAlarmGate(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit,
        isGmsUid: (Int) -> Boolean
    ) {
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
        for (name in HookerSupport.ALARM_GATE_CLASS_NAMES) {
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
                            if (isGmsUid(uid)) {
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
                "checkAlarmIsAllowedSend hooked on ${method.declaringClass?.simpleName}"
            )
        }
    }
}
