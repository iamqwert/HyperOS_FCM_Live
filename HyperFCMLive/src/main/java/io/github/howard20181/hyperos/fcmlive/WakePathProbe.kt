package io.github.howard20181.hyperos.fcmlive

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * The Gate-W / Gate-B wake-path probes (read-only), extracted verbatim from
 * `Hooker` (zero-behaviour refactor): [probeWakePath] watches the service /
 * activity path (`ActivityManagerServiceImpl#checkWakePath`), and
 * [probeBroadcastWakePath] watches the broadcast path
 * (`WakePathChecker#checkBroadcastWakePath`).
 *
 * `Hooker`'s instance capabilities are injected at the call sites, the same
 * way [GreezeHooks] and [AlarmGate] receive theirs: `module` is the
 * [XposedInterface] the module entry object is (`log` / `deoptimize`),
 * `hookE` is `Hooker#hookE` (the counted, `setId`-deduplicated hook builder —
 * its `hooksInstalled` counter feeds the end-of-install summary that stays
 * with `Hooker`), and `logSkipOtherGeneration` is the deferred absent-target
 * report (the counter stays with `Hooker`).
 *
 * The broadcast-gate counters ([broadcastWakePathReachedCount] and friends)
 * are read back by `Hooker`'s [Hooker.broadcastGateSummary], which is why they
 * are `internal` here and not private; the service-path counters are private
 * because nothing outside [probeWakePath] reads them.
 */
internal object WakePathProbe {

    // Gate-W has no "first denial / first reach" one-shot flags. Both halves are
    // expressed as counters instead: `reached == 1` prints the immediate line and
    // `wakePathDeniedCount` gates the detail lines. Do not reintroduce boolean
    // one-shots here — a flag that is only ever set is indistinguishable from a
    // line that was simply never reached, which is the exact ambiguity this
    // probe exists to remove.

    /** Gate-W: how many times `checkWakePath` denied anything at all. */
    @Volatile
    private var wakePathDeniedCount = 0

    /** Gate-W: denial counts per waking caller, keyed by callerPkg ("?" if unknown). */
    private val wakePathDeniedByCaller = ConcurrentHashMap<String, Int>()

    /** Gate-W: elapsedRealtime of the last time-throttled denied-summary line. */
    @Volatile
    private var wakePathDeniedSummaryAt = 0L

    /** Gate-W: how many times `checkWakePath` was entered at all. */
    @Volatile
    private var wakePathReachedCount = 0

    /** Gate-W heartbeat throttle: elapsedRealtime of the last heartbeat, 0 = none yet. */
    @Volatile
    private var wakePathHeartbeatAt = 0L

    /**
     * Gate-B (P2, 2026-10-02): the broadcast wake path,
     * `BroadcastQueueModernStubImpl#checkApplicationAutoStart` offset 0186 →
     * `SecurityManagerInternal#checkBroadcastWakePath` → `WakePathChecker`.
     *
     * `reached` counts entries, `c2dm` the subset carrying
     * `com.google.android.c2dm.intent.RECEIVE`. `c2dm-denied` is the number that
     * actually matters: a non-zero value means the ROM refuses a GMS push delivery
     * before the module's own gates even see it. All four are reported once per
     * traffic-probe tick, so "never reached" and "reached, never denied" stay
     * distinguishable even when nothing else fires.
     */
    @Volatile
    internal var broadcastWakePathReachedCount = 0

    @Volatile
    internal var broadcastWakePathC2dmCount = 0

    @Volatile
    internal var broadcastWakePathDeniedCount = 0

    @Volatile
    internal var broadcastWakePathC2dmDeniedCount = 0

    /**
     * Gate-W probe (read-only).
     *
     * The wake-path chain is the one §7 candidate with a **cross-jar** entry: AOSP
     * `ActivityManagerService` in services.jar invokes
     * `ActivityManagerServiceStub#checkRunningCompatibility` from at least five sites,
     * the override in `ActivityManagerServiceImpl` funnels into `checkServiceWakePath`
     * and then `checkWakePath`. So unlike the rest of §7 it is definitely reached.
     *
     * What is *not* known is whether it ever denies anything involving GMS — and that
     * is the only question that decides whether a behaviour hook belongs here. This
     * probe therefore never alters the return value: it observes, counts, and records
     * the caller package on the first denial. A silent log means "reached but never
     * denied", which closes the gate negatively.
     */
    internal fun probeWakePath(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkipOtherGeneration: (String) -> Unit
    ) {
        val clazz = try {
            classLoader.loadClass("com.android.server.am.ActivityManagerServiceImpl")
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("ActivityManagerServiceImpl (wake-path probe)")
            return
        }
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name == "checkWakePath" &&
                m.parameterCount == 7 &&
                m.returnType == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            logSkipOtherGeneration("checkWakePath (wake-path probe)")
            return
        }
        // Best effort: CallerInfo#callerPkg identifies the waking side. Absent on some
        // generations, and the probe still works without it (it just logs "?").
        val callerPkgField: Field? = try {
            classLoader.loadClass("miui.security.CallerInfo")
                .getDeclaredField("callerPkg")
                .also { it.isAccessible = true }
        } catch (t: Throwable) {
            null
        }
        method.isAccessible = true
        hookE(method).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            try {
                // Count every entry so an overnight window is quantitative: a bare
                // "0 DENIED" says nothing if the gate was never reached.
                val reached = ++wakePathReachedCount
                if (java.lang.Boolean.FALSE == result) {
                    val denied = ++wakePathDeniedCount
                    val caller = readCallerPkg(chain, callerPkgField)
                    if (denied <= HookerSupport.WAKE_PATH_DETAIL_LIMIT) {
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "wake-path probe: checkWakePath DENIED #$denied " +
                                "(callerPkg=$caller)"
                        )
                    }
                    // Aggregate per caller so an overnight window stays attributable
                    // after the first 10 detailed lines. Bounded: once 32 distinct
                    // callers are seen, new ones are not recorded.
                    val prev = wakePathDeniedByCaller[caller]
                    if (prev != null || wakePathDeniedByCaller.size < 32) {
                        wakePathDeniedByCaller[caller] = (prev ?: 0) + 1
                    }
                    // The heartbeat lives on the ALLOWED branch; a window where every
                    // entry is denied would never print the aggregation. Time-throttle
                    // a dedicated denied summary instead.
                    val now = SystemClock.elapsedRealtime()
                    if (now - wakePathDeniedSummaryAt >= HookerSupport.WAKE_PATH_HEARTBEAT_MIN_MS) {
                        wakePathDeniedSummaryAt = now
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "wake-path probe: denied summary denied=$wakePathDeniedCount " +
                                "top=${wakePathTopCallers()}"
                        )
                    }
                } else {
                    // Time-throttled, not count-throttled: one line per window is
                    // enough to prove the gate is live, and `reached` still makes
                    // the volume of traffic through it quantitative.
                    val now = SystemClock.elapsedRealtime()
                    val last = wakePathHeartbeatAt
                    if (last == 0L || now - last >= HookerSupport.WAKE_PATH_HEARTBEAT_MIN_MS) {
                        wakePathHeartbeatAt = now
                        val gap = if (last == 0L) "first" else "+${(now - last) / 60_000}m"
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "wake-path probe: heartbeat reached=$reached " +
                                "denied=$wakePathDeniedCount gap=$gap " +
                                "top=${wakePathTopCallers()} " +
                                "(callerPkg=${readCallerPkg(chain, callerPkgField)})"
                        )
                    }
                }
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to evaluate wake-path probe", t)
            }
            result
        }
        module.deoptimize(method)
        module.logGated(Log.INFO, HookerSupport.TAG, "wake-path probe: checkWakePath hooked (read-only)")
    }

    /**
     * P2 probe (read-only): the *broadcast* wake path.
     *
     * The sibling of [probeWakePath], which watches the service/activity path
     * (`ActivityManagerServiceImpl#checkWakePath`). This one watches
     * `SecurityManagerInternal#checkBroadcastWakePath`, which
     * `BroadcastQueueModernStubImpl#checkApplicationAutoStart` invokes at offset 0186;
     * a false answer makes that caller log "process is not permitted to  wake path"
     * and drop the delivery. The implementation is
     * `com.miui.server.WakePathChecker#checkBroadcastWakePath`, whose caller whitelist
     * is hard-coded to com.miui.home / com.miui.securitycenter / com.miui.carlink —
     * GMS is not on it, so a c2dm arriving from GMS is decided by the wake-path rule
     * engine (`isAllowedByWakePathRule`).
     *
     * Polarity, from the bytecode: true = allowed, and true is also the value
     * returned whenever the decision cannot be taken (null intent, uid -1, empty
     * callee, caller == callee).
     *
     * Read-only: the return value is passed through untouched. Note the c2dm counter
     * can only move when the module is *not* the one deciding — the
     * `checkApplicationAutoStart` hook answers before offset 0186 for callees on the
     * wake tier, so a non-zero count means an unselected callee (or an empty
     * allowlist, where nothing is filtered at all).
     *
     * Output: one immediate `… first reach …` line, per-case detail lines for the
     * first [HookerSupport.WAKE_PATH_DETAIL_LIMIT] c2dm arrivals and denials, and the
     * counters in the 30-min broadcast-gate summary.
     */
    internal fun probeBroadcastWakePath(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkipOtherGeneration: (String) -> Unit
    ) {
        val clazz = try {
            classLoader.loadClass("com.miui.server.WakePathChecker")
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("WakePathChecker (broadcast wake-path probe)")
            return
        }
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name == "checkBroadcastWakePath" &&
                m.parameterCount == 5 &&
                m.returnType == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            logSkipOtherGeneration("checkBroadcastWakePath (broadcast wake-path probe)")
            return
        }
        method.isAccessible = true
        hookE(method).intercept { chain: XposedInterface.Chain ->
            val result = chain.proceed()
            try {
                val action = (chain.getArg(0) as? Intent)?.action
                val caller = chain.getArg(1) as? String
                val callee = resolvePackageOf(chain.getArg(3))
                val allowed = java.lang.Boolean.TRUE == result
                // Count every entry: "0 denied" is unreadable without knowing whether
                // the gate was reached at all.
                val reached = ++broadcastWakePathReachedCount
                if (reached == 1) {
                    // The falsifiable half of §5.1's rule, and the only immediate one:
                    // everything else waits for the 30-min summary, and the detail lines
                    // below only exist for c2dm and denials. One line, ever — a silent
                    // log therefore keeps meaning "never reached", while a line here
                    // with a still-zero `denied` means "reached and always allowed".
                    module.logGated(
                        Log.INFO, HookerSupport.TAG,
                        "wake-path probe: broadcast gate first reach " +
                            "(action=$action caller=$caller callee=$callee " +
                            "verdict=${if (allowed) "allowed" else "blocked"})"
                    )
                }
                if (HookerSupport.ACTION_REMOTE_INTENT == action) {
                    val c2dm = ++broadcastWakePathC2dmCount
                    if (c2dm <= HookerSupport.WAKE_PATH_DETAIL_LIMIT) {
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "wake-path probe: broadcast c2dm reached the gate #$c2dm " +
                                "(caller=$caller callee=$callee " +
                                "verdict=${if (allowed) "allowed" else "blocked"})"
                        )
                    }
                    if (!allowed) {
                        broadcastWakePathC2dmDeniedCount++
                    }
                }
                if (!allowed) {
                    val denied = ++broadcastWakePathDeniedCount
                    if (denied <= HookerSupport.WAKE_PATH_DETAIL_LIMIT) {
                        module.logGated(
                            Log.INFO, HookerSupport.TAG,
                            "wake-path probe: broadcast DENIED #$denied " +
                                "(action=$action caller=$caller callee=$callee)"
                        )
                    }
                }
            } catch (t: Throwable) {
                module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to evaluate broadcast wake-path probe", t)
            }
            result
        }
        module.deoptimize(method)
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "P2: wake-path probe: checkBroadcastWakePath hooked (read-only); " +
                "counter rides the 30min broadcast-gate summary"
        )
    }

    /** `resolveInfo.activityInfo.applicationInfo.packageName`, `"?"` if unavailable. */
    private fun resolvePackageOf(resolveInfo: Any?): String {
        val info = resolveInfo as? ResolveInfo ?: return "?"
        val activityInfo = info.activityInfo ?: return "?"
        return activityInfo.applicationInfo?.packageName ?: "?"
    }

    private fun readCallerPkg(chain: XposedInterface.Chain, field: Field?): String {
        if (field == null) return "?"
        val info = chain.getArg(1) ?: return "?"
        return try {
            field.get(info) as? String ?: "?"
        } catch (t: Throwable) {
            "?"
        }
    }

    /** Gate-W: top denied callers, "pkg:count" pairs, for overnight attribution. */
    private fun wakePathTopCallers(): String =
        wakePathDeniedByCaller.entries
            .sortedByDescending { it.value }
            .take(3)
            .joinToString(",", prefix = "[", postfix = "]") { "${it.key}:${it.value}" }
}
