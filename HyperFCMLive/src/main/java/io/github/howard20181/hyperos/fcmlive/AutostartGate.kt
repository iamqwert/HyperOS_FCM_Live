package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * MIUI autostart gate — the service-bind checkpoint.
 *
 * `AutoStartManagerServiceStubImpl#isAllowStartService` is the stub a MIUI
 * bind (and the JobService / restart verdicts) passes through before the
 * autostart answer is returned; its record is the same per-app autostart entry
 * MIUIOP 10008 gates at its root.
 * With the experiment switch on, a `false` verdict is re-answered `true` for a
 * checked app; with it off, the ROM's verdict proceeds untouched.
 *
 * The ROM's body runs first, and only its denial is overridden (2026-11-01).
 * Decoded from the OS4 miui-services dex, the 4-arg overload is, in order:
 * resolve the service → `BarrageListenerServiceStub#isAllowStartBarrage(pkg)`
 * (false ⇒ "MiBarrage: Disallowing to start service" + deny) →
 * `AppOpsManager#noteOpNoThrow(10008, uid, pkg, "…#isAllowStartService")`
 * (anything but MODE_ALLOWED ⇒ "MIUILOG- Reject service" + deny). Answering
 * `true` at entry therefore also skipped the barrage gate and the appop
 * bookkeeping, which this module never meant to touch; `chain.proceed()`
 * first keeps both and leaves only the autostart verdict widened.
 *
 * Two checkpoints, one switch each (2026-11-01):
 *
 *  - `AutoStartManagerServiceStubImpl#isAllowStartService` — the service-bind
 *    verdict. The name says "start", but the 3-arg overload's only outside
 *    entry is `ActiveServiceManagementImpl#canBindService` (a one-line
 *    forward); `ActiveServices#startServiceLocked` has no MIUI call edge at
 *    all, so this checkpoint is not on the `startService` path;
 *  - `ProcessManagerService#isAllowAutoStart(pkg, uid)` — the process verdict,
 *    whose whole body is `AppOpsManager#checkOpNoThrow(10008)`.
 *
 * The two answer different ROM questions and are read at different moments,
 * which is why they are two switches: the service rung fires on a service
 * start, the process rung on the restart / preload paths. Neither is the c2dm
 * cold start — that happens on the broadcast path (S9/S10).
 *
 * The process half is restored on legality, not on a sample: `MIUIOP 10008` is
 * a vendor autostart toggle with no AOSP counterpart, and a broadcast bringing
 * its target process up is ordinary delivery. Its callers are
 * `ProcessStarter#isAllowRestartProcessLock` / `restartDiedAppOrNot` / the
 * `xmsf` restart paths, `ProcessManagerService#isForceStopEnable` and
 * `ProcessPolicy#isFastBootEnable`, so it is answered per package rather than
 * per Intent — the same key the ROM itself uses.
 *
 * The restart gate (`canRestartServiceLocked`) and the appop root gate
 * (`MIUIOP 10008` reads system-wide) stay retired: `restart DENIED` is
 * observable from the ROM's autostart manager and does not block delivery, and
 * answering the appop root would change every caller of `10008`, not just the
 * push path.
 *
 * Nothing is written — the answer exists for the duration of one call, so
 * flipping the switch off restores the ROM's own behaviour at the next start.
 */
internal object AutostartGate {

    /** One-shot: the ROM denied a checked app's service start and this gate re-answered it. */
    @Volatile
    private var autostartGateBypassLogged = false

    /** One-shot: the ROM denied a checked app's process start and this gate re-answered it. */
    @Volatile
    private var processStartGateBypassLogged = false

    private const val AUTOSTART_STUB_CLASS =
        "com.android.server.am.AutoStartManagerServiceStubImpl"
    private const val ALLOW_START_SERVICE_METHOD = "isAllowStartService"
    private const val PROCESS_MANAGER_CLASS =
        "com.android.server.am.ProcessManagerService"
    private const val ALLOW_AUTO_START_METHOD = "isAllowAutoStart"

    /**
     * Install the hook on every `isAllowStartService` overload that returns
     * boolean — the stub carries a 3- and a 4-parameter shape on this ROM, and
     * both route the same decision. The Intent is found among the arguments
     * rather than by position, because the two shapes place it differently.
     */
    internal fun hookAutostartGate(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit,
        systemContext: () -> Context?,
        // One switch per checkpoint since 2026-11-01: the section master is
        // read by the caller, so each lambda already means "master && this
        // rung".
        isServiceReleaseEnabled: () -> Boolean,
        isProcessReleaseEnabled: () -> Boolean
    ) {
        hookServiceStart(
            module, classLoader, hookE, logSkip, logSkipOtherGeneration,
            systemContext, isServiceReleaseEnabled
        )
        hookProcessStart(
            module, classLoader, hookE, logSkip, systemContext, isProcessReleaseEnabled
        )
    }

    private fun hookServiceStart(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit,
        systemContext: () -> Context?,
        isReleaseEnabled: () -> Boolean
    ) {
        val stubClass = try {
            classLoader.loadClass(AUTOSTART_STUB_CLASS)
        } catch (ignored: ClassNotFoundException) {
            logSkipOtherGeneration(
                "$AUTOSTART_STUB_CLASS absent on this generation, " +
                    "$ALLOW_START_SERVICE_METHOD not hooked"
            )
            return
        }
        val methods: List<Method> = stubClass.declaredMethods.filter {
            it.name == ALLOW_START_SERVICE_METHOD &&
                it.returnType == Boolean::class.javaPrimitiveType
        }
        if (methods.isEmpty()) {
            logSkip("$AUTOSTART_STUB_CLASS#$ALLOW_START_SERVICE_METHOD absent, skip")
            return
        }
        for (method in methods) {
            method.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                val result = chain.proceed()
                try {
                    if (java.lang.Boolean.FALSE == result && isReleaseEnabled()) {
                        val intent = chain.args.firstOrNull { it is Intent } as? Intent
                        // The stub is also asked for component-less starts; a
                        // null target cannot be matched against the allowlist,
                        // so those keep the ROM's denial.
                        val target = intent?.component?.packageName ?: intent?.getPackage()
                        if (target != null &&
                            AllowlistGate.moduleAppliesTo(module, systemContext, target)
                        ) {
                            // One-shot: this fires only when the ROM actually
                            // denied, so silence means "the gate never had to
                            // answer", not "the hook never ran".
                            if (!autostartGateBypassLogged) {
                                autostartGateBypassLogged = true
                                module.logGated(
                                    Log.INFO, HookerSupport.TAG,
                                    "autostart gate: re-allowed $target after the ROM " +
                                        "denied the service start"
                                )
                            }
                            return@intercept true
                        }
                    }
                } catch (t: Throwable) {
                    module.logGated(
                        Log.ERROR, HookerSupport.TAG, "isAllowStartService hook failed", t
                    )
                }
                result
            }
            module.deoptimize(method)
        }
    }

    /**
     * Process checkpoint: `ProcessManagerService#isAllowAutoStart(String, int)`
     * returns `AppOpsManager#checkOpNoThrow(10008, uid, pkg) == MODE_ALLOWED`.
     *
     * What it covers is the **restart / preload** verdict, not the c2dm cold
     * start: the callers are `ProcessStarter#isAllowRestartProcessLock` /
     * `restartDiedAppOrNot` / the `xmsf` restart paths, plus
     * `ProcessManagerService#isForceStopEnable` and `ProcessPolicy#isFastBootEnable`.
     * A push that brings a dead process up goes through S9/S10 on the broadcast
     * path instead.
     *
     * Restored on legality rather than on a sample: `10008` is the MIUI
     * autostart toggle (手机管家 自启动), AOSP has no such process gate. The
     * answer is widened only for a checked package (and GMS unconditionally,
     * through [AllowlistGate.moduleAppliesTo]) and only when the ROM denied it —
     * `chain.proceed()` runs first, so the appop read still happens.
     */
    private fun hookProcessStart(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        systemContext: () -> Context?,
        isReleaseEnabled: () -> Boolean
    ) {
        val processManagerClass = try {
            classLoader.loadClass(PROCESS_MANAGER_CLASS)
        } catch (ignored: ClassNotFoundException) {
            logSkip("$PROCESS_MANAGER_CLASS absent, $ALLOW_AUTO_START_METHOD not hooked")
            return
        }
        val methods: List<Method> = processManagerClass.declaredMethods.filter {
            it.name == ALLOW_AUTO_START_METHOD &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterCount == 2
        }
        if (methods.isEmpty()) {
            logSkip("$PROCESS_MANAGER_CLASS#$ALLOW_AUTO_START_METHOD absent, skip")
            return
        }
        for (method in methods) {
            method.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                val result = chain.proceed()
                try {
                    if (java.lang.Boolean.FALSE == result && isReleaseEnabled()) {
                        val target = chain.getArg(0) as? String
                        if (target != null &&
                            AllowlistGate.moduleAppliesTo(module, systemContext, target)
                        ) {
                            if (!processStartGateBypassLogged) {
                                processStartGateBypassLogged = true
                                module.logGated(
                                    Log.INFO, HookerSupport.TAG,
                                    "autostart gate: re-allowed $target after the ROM " +
                                        "denied the process start"
                                )
                            }
                            return@intercept true
                        }
                    }
                } catch (t: Throwable) {
                    module.logGated(
                        Log.ERROR, HookerSupport.TAG, "isAllowAutoStart hook failed", t
                    )
                }
                result
            }
            module.deoptimize(method)
        }
    }
}
