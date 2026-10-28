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
 * function-type parameters), and `systemContext` is `Hooker#getSystemContext`.
 */
internal object AmsHooks {

    internal fun hookActivityManagerService(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        findMethod: (Class<*>, String, Array<Class<*>?>) -> Method?,
        systemContext: () -> Context?
    ) {
        val ActivityManagerServiceClass =
            classLoader.loadClass("com.android.server.am.ActivityManagerService")
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
                        AllowlistGate.moduleAppliesTo(module, systemContext, targetPackage)
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
                        // No temporary power exemption here. GMS already attaches one
                        // to every delivery that needs it: 10 s / type 0 for high and
                        // reduced priority, 20 s / type 4 for normal priority whose
                        // receiver declares the FINISHED_AFTER_HANDLED meta-data
                        // (Lcnyc;->p). AMS applies that option later, through the
                        // PUSH_TEMP_ALLOWLIST_UI_MSG handler, so a longer ROM grant
                        // overwrites anything this synchronous path could add.
                        //
                        // The direct 2 s call that used to sit here was therefore
                        // redundant where GMS grants, and actively wrong elsewhere:
                        // reason 102 is the one code DeviceIdleController maps through
                        // the over-quota DeviceConfig (default type 1, not a freeze
                        // delay), and addPowerSaveTempWhitelistAppDirectInternal
                        // overwrites the end time instead of taking the maximum, so a
                        // 2 s grant can truncate a longer entry left by a previous
                        // message. Only the stopped-package flag remains, because GMS
                        // does not add that one.
                    }
                } catch (t: Throwable) {
                    module.logGated(Log.ERROR, HookerSupport.TAG, "C2DM broadcast hook failed", t)
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
