package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The AOSP `ActivityManagerService` surface — the stopped-package delivery /
 * temporary power exemption hook — extracted verbatim from `Hooker`
 * (zero-behaviour refactor).
 *
 * `Hooker`'s instance capabilities are injected at the call site, the same way
 * [GreezeHooks] and [AlarmGate] receive theirs: `module` is the
 * [XposedInterface] the module entry object is (`log` / `deoptimize` /
 * `getInvoker`), `hookE` is `Hooker#hookE` (the counted, `setId`-deduplicated
 * hook builder — its `hooksInstalled` counter feeds the end-of-install summary
 * that stays with `Hooker`), `findMethod` is the null-returning lookup helper
 * (passed as `(Class, String, Array)` because Kotlin forbids `vararg` on
 * function-type parameters), `systemContext` is `Hooker#getSystemContext`, and
 * `onApplyAutostart` is the allowlist callback handle that keeps
 * `AllowlistGate` / `AutostartGate` writes pointed at the same callable as
 * before the extraction.
 */
internal object AmsHooks {

    internal fun hookActivityManagerService(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        findMethod: (Class<*>, String, Array<Class<*>?>) -> Method?,
        systemContext: () -> Context?,
        onApplyAutostart: () -> Unit
    ) {
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
            "getRecordForAppLOSP", arrayOf(IApplicationThreadClass)
        )
        if (getRecordMethod == null) {
            getRecordMethod = findMethod(
                ActivityManagerServiceClass,
                "getRecordForAppLocked", arrayOf(IApplicationThreadClass)
            )
        }
        if (getRecordMethod == null) {
            module.logGated(
                Log.WARN, HookerSupport.TAG, "No ActivityManagerService#getRecordForApp*;" +
                    " the broadcast caller is identified by binder uid instead"
            )
        }
        var broadcastMethod: Method? = null
        var intentArgIndex = 2
        val featureSignatures = listOf<Array<Class<*>?>>(
            arrayOf<Class<*>?>(
                IApplicationThreadClass, String::class.java, Intent::class.java, String::class.java,
                IIntentReceiverClass, Int::class.javaPrimitiveType!!, String::class.java,
                Bundle::class.java,
                Array<String>::class.java, Array<String>::class.java, Array<String>::class.java,
                Int::class.javaPrimitiveType!!, Bundle::class.java,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ),
            arrayOf<Class<*>?>(
                IApplicationThreadClass, String::class.java, Intent::class.java, String::class.java,
                IIntentReceiverClass, Int::class.javaPrimitiveType!!, String::class.java,
                Bundle::class.java,
                Array<String>::class.java, Array<String>::class.java,
                Int::class.javaPrimitiveType!!, Bundle::class.java,
                Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            ),
            arrayOf<Class<*>?>(
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
                "broadcastIntentWithFeature", signature
            )
            if (broadcastMethod != null) {
                break
            }
        }
        if (broadcastMethod == null) {
            broadcastMethod = findMethod(
                ActivityManagerServiceClass, "broadcastIntent",
                arrayOf<Class<*>?>(
                    IApplicationThreadClass,
                    Intent::class.java, String::class.java, IIntentReceiverClass,
                    Int::class.javaPrimitiveType, String::class.java, Bundle::class.java,
                    Array<String>::class.java, Int::class.javaPrimitiveType, Bundle::class.java,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            )
            if (broadcastMethod != null) {
                intentArgIndex = 1
            }
        }
        if (broadcastMethod == null) {
            module.logGated(
                Log.ERROR, HookerSupport.TAG, "No broadcastIntent* in ActivityManagerService;" +
                    " stopped-package delivery and the power exemption are not installed"
            )
            return
        }
        val finalGetRecordMethod = getRecordMethod
        val finalIntentArgIndex = intentArgIndex
        hookE(broadcastMethod).intercept { chain: XposedInterface.Chain ->
            val intent = chain.getArg(finalIntentArgIndex) as? Intent
            if (intent != null && HookerSupport.ACTION_REMOTE_INTENT == intent.action) {
                try {
                    val targetPackage = HookerSupport.targetPackageOf(intent)
                    if (callerIsGms(
                            module, systemContext,
                            finalGetRecordMethod, infoField,
                            chain.thisObject, chain.getArg(0)
                        ) &&
                        targetPackage != null &&
                        AllowlistGate.moduleAppliesTo(module, systemContext, onApplyAutostart, targetPackage)
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
                            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to add FLAG_INCLUDE_STOPPED_PACKAGES", t)
                        }
                        try {
                            val mContext = mContextField.get(chain.thisObject) as? Context
                            if (mContext != null) {
                                HookerSupport.getPowerExemptionManager(mContext).addToTemporaryAllowList(
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
                            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to add temporary power exemption", t)
                        }
                    }
                } catch (t: Throwable) {
                    module.logGated(Log.ERROR, HookerSupport.TAG, "C2DM broadcast hook failed", t)
                }
            }
            // Experiment: the autostart switch's write. It used to be the
            // persistent half of a pair whose runtime half answered the same
            // gate in memory for broadcasts the module intercepted; that half is
            // gone — see the [checkApplicationAutoStart] hook — so what is left
            // writes the ROM's own autostart AppOp for the package, see
            // maybeWriteAutostart. The action test is the cheap local one, so
            // the remote-prefs read only happens for a push broadcast.
            if (intent != null && AutostartGate.isPushAction(intent.action)) {
                try {
                    val autostartPackage = HookerSupport.targetPackageOf(intent)
                    if (autostartPackage != null &&
                        AutostartGate.isWakeWriteAutostartEnabled(module) &&
                        AutostartGate.wakeExplicitlyAllows(module, systemContext, onApplyAutostart, autostartPackage)
                    ) {
                        AutostartGate.maybeWriteAutostart(module, systemContext, autostartPackage)
                    }
                } catch (t: Throwable) {
                    module.logGated(Log.ERROR, HookerSupport.TAG, "Wake autostart write hook failed", t)
                }
            }
            chain.proceed()
        }
        module.deoptimize(broadcastMethod)
    }

    private fun callerIsGms(
        module: XposedInterface,
        systemContext: () -> Context?,
        getRecordMethod: Method?,
        infoField: Field,
        ams: Any,
        callerThread: Any?
    ): Boolean {
        if (getRecordMethod != null && callerThread != null) {
            try {
                val app = module.getInvoker(getRecordMethod).invoke(ams, callerThread)
                val info = if (app != null) infoField.get(app) else null
                if (info is ApplicationInfo) {
                    return HookerSupport.GMS_PACKAGE_NAME == info.packageName
                }
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to resolve the broadcast caller", t)
            }
        }
        return binderCallerIsGms(module, systemContext)
    }

    private fun binderCallerIsGms(module: XposedInterface, systemContext: () -> Context?): Boolean {
        try {
            val context = systemContext() ?: return false
            val packages = context.packageManager
                .getPackagesForUid(Binder.getCallingUid()) ?: return false
            for (pkg in packages) {
                if (HookerSupport.GMS_PACKAGE_NAME == pkg) {
                    return true
                }
            }
        } catch (t: Throwable) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to resolve the binder caller uid", t)
        }
        return false
    }
}
