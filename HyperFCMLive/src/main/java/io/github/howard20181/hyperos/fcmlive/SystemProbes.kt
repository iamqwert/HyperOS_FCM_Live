package io.github.howard20181.hyperos.fcmlive

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * The read-only system_server probes, extracted verbatim from `Hooker`
 * (zero-behaviour refactor): [probeSleepModeUidRule] (gate A3),
 * [probePacketFilterSupport] (action list 3.6) and [probeGmsInMessageApp]
 * (gate R). All three are install-time, hookless ROM-fact reads printed once;
 * the socket-teardown probe (R6) went away with the observation layer on
 * 2026-10-30.
 *
 * `Hooker`'s instance capabilities are injected at the call sites, the same
 * way [GreezeHooks] and [AlarmGate] receive theirs: `module` is the
 * [XposedInterface] the module entry object is (`log` / `deoptimize`),
 * `hookE` is `Hooker#hookE` (the counted, `setId`-deduplicated hook builder —
 * its `hooksInstalled` counter feeds the end-of-install summary that stays
 * with `Hooker`), `logSkip` is the absent-target report (the counter stays
 * with `Hooker`), `recordProbe` feeds the merged probe line
 * ([InstallBookkeeping.flushProbes]), and `isGmsUid` is `Hooker`'s shared GMS
 * uid gate (cachedGmsAppId stays with `Hooker`).
 */
internal object SystemProbes {


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
    internal fun probeSleepModeUidRule(
        module: XposedInterface,
        classLoader: ClassLoader,
        logSkip: (String) -> Unit,
        recordProbe: (String) -> Unit
    ) {
        val name = "updateSleepModeUidRule"
        try {
            val cm = classLoader.loadClass("android.net.ConnectivityManager")
            cm.getDeclaredMethod(name, java.lang.Integer.TYPE, java.lang.Boolean.TYPE)
            // The lookup pins the exact parameter list, so "present" already means
            // "present with the signature the caller below needs".
            recordProbe("ConnectivityManager#$name=present")
        } catch (e: NoSuchMethodException) {
            recordProbe("ConnectivityManager#$name=ABSENT(gateA3)")
        } catch (e: ClassNotFoundException) {
            logSkip("ConnectivityManager absent, sleep-mode probe skip")
        }
        probeReflectiveMethod(
            module, recordProbe, logSkip,
            classLoader,
            "android.net.ConnectivityManager",
            "enableSleepModeChain",
            java.lang.Boolean.TYPE
        )
    }

    /**
     * Reports whether a hidden framework method exists, which is the only way to
     * tell MIUI's reflective trampolines from real work: the target symbol lives in
     * framework.jar, so it is absent from every services.jar dex and cannot be
     * found statically.
     *
     * The finding is keyed `<SimpleClass>#<method>`, which is unambiguous on its
     * own — the merged probe line carries several of them. A missing *class* is
     * still reported on its own line: that is a finding, not a routine reading.
     */
    private fun probeReflectiveMethod(
        module: XposedInterface,
        recordProbe: (String) -> Unit,
        logSkip: (String) -> Unit,
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>
    ) {
        val key = "${className.substringAfterLast('.')}#$methodName"
        try {
            classLoader.loadClass(className).getDeclaredMethod(methodName, *parameterTypes)
            recordProbe("$key=present")
        } catch (e: NoSuchMethodException) {
            recordProbe("$key=ABSENT")
        } catch (e: ClassNotFoundException) {
            logSkip("$className absent, $methodName probe skip")
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
    internal fun probePacketFilterSupport(
        module: XposedInterface,
        classLoader: ClassLoader,
        logSkip: (String) -> Unit,
        recordProbe: (String) -> Unit
    ) {
        try {
            val policy =
                classLoader.loadClass("com.miui.powerinsight.packetfilter.FilterEnablePolicy")
            val supported =
                policy.getDeclaredMethod("isSupportPacketFilter").invoke(null) as? Boolean
            recordProbe("packet-filter=$supported")
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
    // The socket-teardown probe (R6, three `doDesSocketForUid` layers) was
    // removed on 2026-10-30 with the observation layer: its own doc said "0 hits
    // ≠ dead code" because binder transport is invisible statically — an
    // instrument that cannot decide anything, on a hot path, for no consumer.

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
    internal fun probeGmsInMessageApp(
        module: XposedInterface,
        classLoader: ClassLoader,
        logSkip: (String) -> Unit,
        recordProbe: (String) -> Unit
    ) {
        try {
            val clazz = classLoader.loadClass("com.miui.server.greeze.AurogonImmobulusMode")
            val field = clazz.getDeclaredField("mMessageApp")
            field.isAccessible = true
            val list = field.get(null) as? Collection<*>
            val present = list?.contains(HookerSupport.GMS_PACKAGE_NAME) ?: false
            recordProbe("mMessageApp=${list?.size ?: -1},gms=$present")
        } catch (e: NoSuchFieldException) {
            logSkip("AurogonImmobulusMode#mMessageApp absent, probe skip")
        } catch (e: ClassNotFoundException) {
            logSkip("AurogonImmobulusMode absent, mMessageApp probe skip")
        }
    }
}
