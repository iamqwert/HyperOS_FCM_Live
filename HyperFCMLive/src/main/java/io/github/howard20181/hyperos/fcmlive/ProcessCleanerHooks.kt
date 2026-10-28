package io.github.howard20181.hyperos.fcmlive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * MIUI force-stop ("clean") protection, extracted verbatim from `Hooker`
 * (zero-behaviour refactor): [hookProcessCleanerBase] answers false from
 * `ProcessCleanerBase#isForceStopEnable` for allowlisted apps that declare an
 * FCM component, plus the shared FCM-component query
 * ([declaresFcmComponent] / [declaresFcmUncached] / [isFcmClient]) it relies
 * on.
 *
 * `Hooker`'s instance capabilities are injected at the call site, the same way
 * [GreezeHooks] and [AlarmGate] receive theirs: `module` is the
 * [XposedInterface] the module entry object is (`log` / `getInvoker`),
 * `hookE` is `Hooker#hookE` (the counted, `setId`-deduplicated hook builder —
 * its `hooksInstalled` counter feeds the end-of-install summary that stays
 * with `Hooker`), and `systemContext` is `Hooker#getSystemContext`.
 */
internal object ProcessCleanerHooks {

    internal fun hookProcessCleanerBase(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        systemContext: () -> Context?
    ) {
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
                    module.getInvoker(mGetApplicationInfo).invoke(chain.getArg(0)) as? ApplicationInfo
                val pkgName = info?.packageName
                if (policy is Int && policy != 13 &&
                    pm != null &&
                    pkgName != null &&
                    AllowlistGate.moduleAppliesTo(module, systemContext, pkgName) &&
                    declaresFcmComponent(module, pm, pkgName)
                ) {
                    return@intercept false
                }
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "isForceStopEnable hook failed", t)
            }
            chain.proceed()
        }
        module.deoptimize(isForceStopEnableMethod)
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
            declaresFcmUncached(pm, packageName)
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

    private fun declaresFcmUncached(pm: PackageManager, packageName: String): Boolean {
        // Marker 1: the SDK's messaging service class.
        var hasMessagingService = true
        try {
            pm.getServiceInfo(ComponentName(packageName, HookerSupport.FCM_MESSAGING_SERVICE_CLASS), 0)
        } catch (ignored: Throwable) {
            hasMessagingService = false
        }
        // Marker 2: the SDK's instance-id receiver class.
        var hasIidReceiver = true
        try {
            pm.getReceiverInfo(ComponentName(packageName, HookerSupport.FCM_IID_RECEIVER_CLASS), 0)
        } catch (ignored: Throwable) {
            hasIidReceiver = false
        }
        // Marker 3: the MESSAGING_EVENT action. Asked with setPackage so this
        // stays a per-package question.
        val serviceIntent = Intent(HookerSupport.ACTION_MESSAGING_EVENT)
        serviceIntent.setPackage(packageName)
        val hasMessagingEvent = pm.queryIntentServices(serviceIntent, 0).isNotEmpty()
        // Markers 4 and 5: the c2dm RECEIVE action, and what class answers it.
        // Asked with setPackage so this stays a per-package question. Google's
        // own apps resolve to the notifications-platform PushReceiver (⑤),
        // which is accepted alone; any other RECEIVE receiver is only the weak
        // marker ④ and is never sufficient on its own.
        var hasReceive = false
        var hasGooglePushReceiver = false
        val receiverIntent = Intent(HookerSupport.ACTION_REMOTE_INTENT)
        receiverIntent.setPackage(packageName)
        for (ri in pm.queryBroadcastReceivers(receiverIntent, 0)) {
            hasReceive = true
            if (HookerSupport.isGooglePushReceiverClass(ri.activityInfo?.name)) {
                hasGooglePushReceiver = true
                break
            }
        }
        return isFcmClient(
            hasMessagingService, hasIidReceiver,
            hasMessagingEvent, hasReceive, hasGooglePushReceiver
        )
    }

    /**
     * The one rule, in one place: **any two of ②③④, or ② alone, ③ alone, or ⑤ alone.**
     *
     * Spelled out as a boolean table rather than arithmetic on a count: the
     * accepted shapes are not a count — "② alone" and "③ alone" are each one
     * marker while ②+③ is two, and `④ alone` has to be rejected even though it
     * is also one marker. `sum >= 2 || has2 || has3` would say it in fewer lines
     * and hide the shapes.
     *
     * ① contributes to the two-of-three but is never sufficient on its own.
     * ⑤ (the RECEIVE receiver is Google's notifications-platform PushReceiver)
     * is accepted alone: it is the entrypoint shape of Google's own apps, whose
     * push infrastructure ships none of the Firebase SDK markers.
     * Mirrors `MainActivity.isFcmClient`; keep the two in step.
     */
    private fun isFcmClient(
        hasMessagingService: Boolean,
        hasIidReceiver: Boolean,
        hasMessagingEvent: Boolean,
        hasReceive: Boolean,
        hasGooglePushReceiver: Boolean
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
        // ⑤ alone: Google's own push entrypoint.
        if (hasGooglePushReceiver) {
            return true
        }
        // Everything else: ① alone, ④ alone, or nothing.
        return false
    }

    private class FcmQuery(val declares: Boolean, val checkedAtMs: Long)
}
