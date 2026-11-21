package io.github.iamqwert.hypergreeze

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * MIUI force-stop ("clean") protection: [hookProcessCleanerBase] answers false
 * from `ProcessManagerService#isForceStopEnable(ProcessRecord, int)` for
 * allowlisted apps that declare an FCM component, reusing
 * [FcmAppScan.declaresFcm] with a per-package TTL cache so the admission rule
 * never drifts from the app list or auto-check.
 *
 * Hooked at `ProcessManagerService#isForceStopEnable` rather than
 * `ProcessCleanerBase#isForceStopEnable` because OS3/OS4 carry two callers:
 * - `ProcessCleanerBase#killOnce` → `ProcessCleanerBase#isForceStopEnable`
 * - `ProcessMemoryCleaner#killProcessByMinAdj` → `SystemPressureController#isForceStopEnable`
 * Both converge on `ProcessManagerService#isForceStopEnable(ProcessRecord, int)`.
 * Returning `false` only downgrades force-stop to an ordinary process kill
 * (memory is still reclaimed; the app simply avoids the `STOPPED` flag).
 */
internal object ProcessCleanerHooks {

    /** One-shot: confirms a force-stop verdict was downgraded to ordinary kill. */
    @Volatile
    private var forceStopDowngradedLogged = false

    internal fun hookProcessCleanerBase(env: HookEnv) {
        val module = env.module
        val classLoader = env.classLoader
        val processRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord")
        val mGetApplicationInfo = processRecordClass.getDeclaredMethod("getApplicationInfo")
        val processManagerServiceClass =
            classLoader.loadClass("com.android.server.am.ProcessManagerService")
        val mPkms = try {
            processManagerServiceClass.getDeclaredField("mPkms").also { it.isAccessible = true }
        } catch (ignored: NoSuchFieldException) {
            null
        }
        val isForceStopEnableMethod = processManagerServiceClass.getDeclaredMethod(
            "isForceStopEnable",
            processRecordClass,
            Int::class.javaPrimitiveType
        )
        env.hookE(isForceStopEnableMethod).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            try {
                if (java.lang.Boolean.TRUE == result) {
                    val policy = chain.getArg(1)
                    val pm = (if (mPkms != null) mPkms.get(chain.thisObject) as? PackageManager else null)
                        ?: env.systemContext()?.packageManager
                    val info =
                        module.getInvoker(mGetApplicationInfo).invoke(chain.getArg(0)) as? ApplicationInfo
                    val pkgName = info?.packageName
                    if (policy is Int && policy != 13 &&
                        pm != null &&
                        pkgName != null &&
                        AllowlistGate.moduleAppliesTo(module, env::systemContext, pkgName) &&
                        declaresFcmComponent(module, pm, pkgName)
                    ) {
                        if (!forceStopDowngradedLogged) {
                            forceStopDowngradedLogged = true
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "ProcessManagerService#isForceStopEnable: downgraded force-stop to " +
                                    "ordinary kill for $pkgName (policy=$policy)"
                            )
                        }
                        return@intercept false
                    }
                }
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "isForceStopEnable hook failed", t)
            }
            result
        }
        module.deoptimize(isForceStopEnableMethod)
    }

    private val fcmCache = HashMap<String, FcmQuery>()

    private fun declaresFcmComponent(
        module: XposedInterface,
        pm: PackageManager,
        packageName: String
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        synchronized(fcmCache) {
            val cached = fcmCache[packageName]
            if (cached != null && now - cached.checkedAtMs < HookerSupport.FCM_CACHE_TTL_MS) {
                return cached.declares
            }
        }
        val declares: Boolean = try {
            FcmAppScan.declaresFcm(pm, packageName)
        } catch (t: Throwable) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "FCM lookup failed for $packageName", t)
            return false
        }
        synchronized(fcmCache) {
            if (fcmCache.size >= HookerSupport.FCM_CACHE_MAX) {
                fcmCache.clear()
            }
            fcmCache[packageName] = FcmQuery(declares, now)
        }
        return declares
    }

    private class FcmQuery(val declares: Boolean, val checkedAtMs: Long)
}
