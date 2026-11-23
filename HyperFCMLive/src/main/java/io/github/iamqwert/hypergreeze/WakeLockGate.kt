package io.github.iamqwert.hypergreeze

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Vendor partial wakelock policy gate in `PowerManagerServiceImpl`.
 *
 * Complements [GreezeHooks.hookAllowWakeUpList] (which covers the frozen-process
 * wake-lock gate in Greeze) by guarding the Whetstone / PowerKeeper policy path:
 * - `PowerManagerServiceImpl#isWakelockDisabledByPolicy(WakeLock)Z` — runs the
 *   ROM method first (zero reflection cost on allowed wakelocks); only when the
 *   ROM answers `true` (disabled) does it inspect `mFlags` and `getRealOwners`,
 *   exempting strictly `PARTIAL_WAKE_LOCK` locks whose owners are all GMS UIDs.
 * - `PowerManagerServiceImpl#setUidPartialWakeLockDisabledState(int, String, boolean[, String])`
 *   — ignores vendor disable requests (`disabled == true`) targeting a GMS UID
 *   without flipping them to enable requests, so AOSP's own disabled state is
 *   never cleared.
 *
 * `PowerKeeperPolicy#isWakelockDisabledByPolicy` is intentionally NOT hooked:
 * on OS4 its sole caller is `PowerManagerServiceImpl#isWakelockDisabledByPolicy`,
 * which carries the full `WakeLock` / `WorkSource` context needed to avoid
 * exempting shared wakelocks held on behalf of non-GMS apps.
 */
internal object WakeLockGate {

    private const val POWER_STUB_CLASS = "com.android.server.power.PowerManagerServiceImpl"
    private const val WAKELOCK_CLASS = "com.android.server.power.PowerManagerService\$WakeLock"
    private const val WAKE_LOCK_LEVEL_MASK = 0xffff
    private const val PARTIAL_WAKE_LOCK = 1

    /** One-shot: confirms isWakelockDisabledByPolicy re-allowed a GMS partial wakelock. */
    @Volatile
    private var policyBypassLogged = false

    /** One-shot: confirms a vendor disable request for a GMS partial wakelock was ignored. */
    @Volatile
    private var disableRequestIgnoredLogged = false

    internal fun shouldExemptPartialWakeLock(
        flags: Int,
        owners: IntArray?,
        isGmsUid: (Int) -> Boolean
    ): Boolean {
        if ((flags and WAKE_LOCK_LEVEL_MASK) != PARTIAL_WAKE_LOCK) return false
        if (owners == null || owners.isEmpty()) return false
        for (uid in owners) {
            if (!isGmsUid(uid)) return false
        }
        return true
    }

    internal fun hookWakeLockPolicy(env: HookEnv) {
        val module = env.module
        val classLoader = env.classLoader
        val powerStubClass = try {
            classLoader.loadClass(POWER_STUB_CLASS)
        } catch (e: ClassNotFoundException) {
            env.logSkip("$POWER_STUB_CLASS absent, wakelock policy skip")
            return
        }

        hookPolicyDecision(env, powerStubClass)
        hookDisableRequest(env, powerStubClass, withReason = false)
        hookDisableRequest(env, powerStubClass, withReason = true)
    }

    private fun hookPolicyDecision(env: HookEnv, powerStubClass: Class<*>) {
        val module = env.module
        val wakeLockClass = try {
            env.classLoader.loadClass(WAKELOCK_CLASS)
        } catch (e: ClassNotFoundException) {
            env.logSkip("$WAKELOCK_CLASS absent, isWakelockDisabledByPolicy skip")
            return
        }
        val flagsField = try {
            wakeLockClass.getDeclaredField("mFlags").apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            env.logSkip("WakeLock#mFlags absent, isWakelockDisabledByPolicy skip")
            return
        }
        val getRealOwnersMethod = try {
            powerStubClass.getDeclaredMethod("getRealOwners", wakeLockClass).apply {
                if (returnType != IntArray::class.java) {
                    throw NoSuchMethodException(toGenericString())
                }
                isAccessible = true
            }
        } catch (e: NoSuchMethodException) {
            env.logSkip("PowerManagerServiceImpl#getRealOwners absent, isWakelockDisabledByPolicy skip")
            return
        }
        val decisionMethod = try {
            powerStubClass.getDeclaredMethod("isWakelockDisabledByPolicy", wakeLockClass).apply {
                if (returnType != Boolean::class.javaPrimitiveType) {
                    throw NoSuchMethodException(toGenericString())
                }
                isAccessible = true
            }
        } catch (e: NoSuchMethodException) {
            env.logSkip("PowerManagerServiceImpl#isWakelockDisabledByPolicy absent, skip")
            return
        }

        env.hookE(decisionMethod).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            if (java.lang.Boolean.TRUE == result) {
                try {
                    val wakeLock = chain.getArg(0)
                    if (wakeLock != null) {
                        val flags = flagsField.getInt(wakeLock)
                        if ((flags and WAKE_LOCK_LEVEL_MASK) == PARTIAL_WAKE_LOCK) {
                            val owners = module.getInvoker(getRealOwnersMethod)
                                .invoke(chain.thisObject, wakeLock) as? IntArray
                            if (shouldExemptPartialWakeLock(flags, owners, env::isGmsUid)) {
                                if (!policyBypassLogged) {
                                    policyBypassLogged = true
                                    module.logGated(
                                        Log.INFO, HookerSupport.TAG,
                                        "PowerManagerServiceImpl#isWakelockDisabledByPolicy: " +
                                            "re-allowed GMS partial wakelock (owners=${owners?.joinToString()})"
                                    )
                                }
                                return@intercept false
                            }
                        }
                    }
                } catch (t: Throwable) {
                    module.logGated(
                        Log.ERROR, HookerSupport.TAG,
                        "Failed to evaluate isWakelockDisabledByPolicy", t
                    )
                }
            }
            result
        }
        module.deoptimize(decisionMethod)
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "PowerManagerServiceImpl#isWakelockDisabledByPolicy hooked for GMS"
        )
    }

    private fun hookDisableRequest(env: HookEnv, powerStubClass: Class<*>, withReason: Boolean) {
        val module = env.module
        val method = try {
            if (withReason) {
                powerStubClass.getDeclaredMethod(
                    "setUidPartialWakeLockDisabledState",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    String::class.java
                )
            } else {
                powerStubClass.getDeclaredMethod(
                    "setUidPartialWakeLockDisabledState",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
            }
        } catch (e: NoSuchMethodException) {
            env.logSkip(
                "PowerManagerServiceImpl#setUidPartialWakeLockDisabledState" +
                    "(reason=$withReason) absent, skip"
            )
            return
        }
        method.isAccessible = true
        env.hookE(method).intercept { chain: XposedInterface.Chain ->
            try {
                val uid = chain.getArg(0)
                val disabled = chain.getArg(2)
                if (disabled == true && uid is Int && env.isGmsUid(uid)) {
                    if (!disableRequestIgnoredLogged) {
                        disableRequestIgnoredLogged = true
                        val tag = chain.getArg(1) as? String
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "PowerManagerServiceImpl#setUidPartialWakeLockDisabledState: " +
                                "ignored vendor disable request for GMS (uid=$uid, tag=$tag)"
                        )
                    }
                    return@intercept null
                }
            } catch (t: Throwable) {
                module.logGated(
                    Log.ERROR, HookerSupport.TAG,
                    "Failed to evaluate setUidPartialWakeLockDisabledState", t
                )
            }
            chain.proceed()
        }
        module.deoptimize(method)
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "PowerManagerServiceImpl#setUidPartialWakeLockDisabledState(reason=$withReason) hooked for GMS"
        )
    }
}
