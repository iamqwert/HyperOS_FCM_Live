package io.github.howard20181.hyperos.fcmlive

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * The autostart gate and write subsystem, extracted verbatim from `Hooker`
 * (zero-behaviour refactor). `Hooker`'s instance capabilities are injected at
 * the call sites: `module` is the [XposedInterface] the module entry object is
 * (`log` / `deoptimize` / `getRemotePreferences` / `getInvoker`), `hookE` and
 * `logSkipOtherGeneration` are `Hooker`'s counted hook builder and its
 * cross-generation absent report (their counters feed the end-of-install
 * summary that stays with `Hooker`), and `getSystemContext` is
 * `Hooker#getSystemContext` as a supplier.
 *
 * The write half of this object is the boot-time migration only: it backfills
 * the manager switch op (`10053`) for packages an older build left at
 * `10008=allow / 10053=ignore`, and never writes the behavior op — the switch
 * that used to grant autostart is retired, and the experiment screen carries a
 * plain entry into 手机管家's own autostart management instead.
 */
internal object AutostartGate {

    /**
     * How long after system_server install the boot-time backfill walk waits.
     *
     * Sixty seconds: comfortably past the tail of service bring-up (the AppOps
     * service the walk reads is registered well before this on every observed
     * boot), yet still inside the window where the user has not usually started
     * interacting with the device, so the walk's one read per allowlisted
     * package lands on an idle binder pool.
     */
    private const val AUTOSTART_BACKFILL_DELAY_MS = 60_000L

    /**
     * The four autostart checkpoints that read `MIUIOP 10008`, and the runtime
     * half of the autostart story.
     *
     * `BroadcastQueueModernStubImpl#checkApplicationAutoStart` — the module's
     * hook — answers at method entry for caller=GMS + c2dm, so on the FCM hop
     * `MIUIOP 10008` is never read there at all. It *is* read by the
     * checkpoints below, which is why setting the op changes behaviour even
     * though the module already answers the broadcast gate:
     *
     *  - `AutoStartManagerServiceStubImpl#isAllowStartService` — starting or
     *    binding a service. This is the one that fires for a *cached* process:
     *    the broadcast is delivered without a process start, and the receiver
     *    then has to start the messaging service.
     *  - `ProcessManagerService#isAllowAutoStart` — starting a process. This is
     *    the one that fires once the process has been reaped.
     *  - `AutoStartManagerServiceStubImpl#canRestartServiceLocked` — scheduling
     *    a killed service back. Reached only from
     *    `ActiveServices.scheduleServiceRestartLocked`, so it is a third path
     *    rather than a third reading of the same one.
     *  - `PreStartFeedbackImpl#isAutoStart` — the pre-start *punishment* path.
     *    See [AUTOSTART_PRE_START_METHOD].
     *
     * The first two read the same op, so one setting fixes both, and whether the
     * process was dead does not change which of the two is asked. User-side
     * evidence (2026-10-06): with `10008` denied, normal-priority pushes did not
     * arrive at all; allowing it was enough, and `10053` — the op the Security
     * Centre row shows — changed nothing.
     *
     * The last two differ. `canRestartServiceLocked` was a probe until live
     * samples showed the gate actually denying checked packages (2026-10-07:
     * `restart DENIED` lines with a checked push target as `callee`), which is
     * the condition the probe was waiting for — releasing a checkpoint whose
     * denials had never been observed would have been an intervention nobody
     * could measure. It is now releasable under its **own** experiment key,
     * not the master's: the freeze decision is a separate mechanism (its
     * `false` verdict sends the process to `KillProcessInfo` instead of a
     * restart), and isolating it is the reason the rung exists.
     * `PreStartFeedbackImpl#isAutoStart` stays a probe: its four callers give
     * the same boolean three punishment meanings and one bookkeeping one, and
     * the whole path sits behind static ROM flags whose enablement on this
     * generation is unknown.
     *
     * Read-through by default: the verdict is passed on untouched, denials for a
     * checked callee are counted, and each (gate, callee) pair gets its own
     * detail allowance per hour (see [AUTOSTART_GATE_DETAIL_PER_CALLEE]). The
     * per-gate experiment keys turn a counted denial into an override, and
     * then only when the ROM was about to deny a callee the user checked —
     * nothing is written to disk either way.
     */
    internal fun hookAutostartGate(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkipOtherGeneration: (String) -> Unit,
        getSystemContext: () -> Context?
    ) {
        var installed = 0
        val autostartStubClass = try {
            classLoader.loadClass(HookerSupport.AUTOSTART_STUB_CLASS)
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("${HookerSupport.AUTOSTART_STUB_CLASS} (autostart gate)")
            null
        }
        val serviceTargets = autostartStubClass?.declaredMethods?.mapNotNull { m ->
            if (m.name != HookerSupport.AUTOSTART_SERVICE_METHOD ||
                m.returnType != Boolean::class.javaPrimitiveType
            ) {
                return@mapNotNull null
            }
            val intentIndex = m.parameterTypes.indexOfFirst { it == Intent::class.java }
            if (intentIndex < 0) null else kotlin.Pair(m, intentIndex)
        } ?: emptyList<kotlin.Pair<Method, Int>>()
        if (serviceTargets.isEmpty() && autostartStubClass != null) {
            logSkipOtherGeneration("${HookerSupport.AUTOSTART_SERVICE_METHOD} (autostart gate)")
        }
        for ((method, intentIndex) in serviceTargets) {
            method.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                val intent = chain.getArg(intentIndex) as? Intent
                val callee = intent?.component?.packageName ?: intent?.getPackage()
                // The callee alone cannot tell a push wake from any other
                // service start, and that distinction is the whole question this
                // probe exists to answer: for a push wake the callee is reached
                // via its Firebase* service, while the c2dm broadcast hop is
                // counted by the broadcast-gate probes (GreezeHooks) — see the
                // blind-spot note on `broadcastGateAutostartAllowedCount`: the
                // hop does carry FCM traffic on this ROM, but the greeze freeze
                // gate is never asked for a non-frozen callee and this module's
                // own checkApplicationAutoStart early return short-circuits the
                // downstream wake-path probe, so `c2dm allowed` can read 0 while
                // broadcasts flow. So the detail carries the component's short
                // class name, which names the service the ROM was about to refuse.
                val detail = intent?.let { shortComponent(it) }
                autostartGateVerdict(
                    module, getSystemContext,
                    chain, HookerSupport.AUTOSTART_GATE_SERVICE, callee, detail
                )
            }
            module.deoptimize(method)
            installed++
        }
        val processClass = try {
            classLoader.loadClass("com.android.server.am.ProcessManagerService")
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("ProcessManagerService (autostart gate)")
            null
        }
        val processTarget = processClass?.declaredMethods?.firstOrNull { m ->
            m.name == HookerSupport.AUTOSTART_PROCESS_METHOD &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (processTarget == null) {
            if (processClass != null) {
                logSkipOtherGeneration("${HookerSupport.AUTOSTART_PROCESS_METHOD}(String,int) (autostart gate)")
            }
        } else {
            processTarget.isAccessible = true
            hookE(processTarget).intercept { chain: XposedInterface.Chain ->
                autostartGateVerdict(
                    module, getSystemContext,
                    chain, HookerSupport.AUTOSTART_GATE_PROCESS, chain.getArg(0) as? String
                )
            }
            module.deoptimize(processTarget)
            installed++
        }
        // Gate G2: restart-after-death. Both overloads take the callee as
        // parameter 0, so one matcher covers them; there is nothing else on
        // this class by that name returning a boolean. Releasable under its
        // own experiment key — see the class note above.
        val restartTargets = autostartStubClass?.declaredMethods?.filter { m ->
            m.name == HookerSupport.AUTOSTART_RESTART_METHOD &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterCount >= 1 &&
                m.parameterTypes[0] == String::class.java
        } ?: emptyList()
        if (restartTargets.isEmpty() && autostartStubClass != null) {
            logSkipOtherGeneration("${HookerSupport.AUTOSTART_RESTART_METHOD} (autostart gate probe)")
        }
        for (method in restartTargets) {
            method.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                autostartGateVerdict(
                    module, getSystemContext,
                    chain,
                    HookerSupport.AUTOSTART_GATE_RESTART,
                    chain.getArg(0) as? String
                )
            }
            module.deoptimize(method)
            installed++
        }
        // Probe G1: the pre-start path. Private helper on a class reached only
        // through its own instance, so the probe targets the read itself rather
        // than one of the four callers — every one of them asks the same
        // question through this method.
        val preStartClass = try {
            classLoader.loadClass(HookerSupport.AUTOSTART_PRE_START_CLASS)
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("${HookerSupport.AUTOSTART_PRE_START_CLASS} (autostart gate probe)")
            null
        }
        val preStartTarget = preStartClass?.declaredMethods?.firstOrNull { m ->
            m.name == HookerSupport.AUTOSTART_PRE_START_METHOD &&
                m.returnType == Boolean::class.javaPrimitiveType &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (preStartTarget == null) {
            if (preStartClass != null) {
                logSkipOtherGeneration(
                    "${HookerSupport.AUTOSTART_PRE_START_METHOD}(String,int) (autostart gate probe)"
                )
            }
        } else {
            preStartTarget.isAccessible = true
            hookE(preStartTarget).intercept { chain: XposedInterface.Chain ->
                autostartGateVerdict(
                    module, getSystemContext,
                    chain,
                    HookerSupport.AUTOSTART_GATE_PRE_START,
                    chain.getArg(0) as? String,
                    releaseable = false
                )
            }
            module.deoptimize(preStartTarget)
            installed++
        }
        // Root choke: one hook at the AppOpsManager client entry covers every
        // system_server-side reader that funnels through it, including the
        // per-checkpoint hooks above and the pre-start punishment path — the
        // choke cannot tell callers apart, which is its documented cost. The
        // manager UI reads the same op from its own process, where this hook
        // does not exist, so the display keeps telling the truth. Count-only
        // per event (one detail line per package per window), because the ROM
        // never "denies" here — the answer is pre-empted before the ROM's own
        // logic runs.
        val appOpsClass = try {
            classLoader.loadClass("android.app.AppOpsManager")
        } catch (e: ClassNotFoundException) {
            logSkipOtherGeneration("android.app.AppOpsManager (autostart root choke)")
            null
        }
        val rootTargets = appOpsClass?.declaredMethods?.filter { m ->
            m.returnType != Int::class.javaPrimitiveType ||
                m.parameterTypes.firstOrNull() != Int::class.javaPrimitiveType ||
                m.parameterTypes.getOrNull(1) != Int::class.javaPrimitiveType ||
                m.parameterTypes.getOrNull(2) != String::class.java
        }?.let { ints ->
            ints.filter { m ->
                m.name == "checkOpNoThrow" && m.parameterCount == 3
            } + ints.filter { m ->
                m.name == "noteOpNoThrow" &&
                    m.parameterCount == 5 &&
                    m.parameterTypes[3] == String::class.java &&
                    m.parameterTypes[4] == String::class.java
            }
        } ?: emptyList()
        if (rootTargets.isEmpty() && appOpsClass != null) {
            logSkipOtherGeneration("AppOpsManager checkOpNoThrow/noteOpNoThrow (autostart root choke)")
        }
        for (method in rootTargets) {
            method.isAccessible = true
            hookE(method).intercept { chain: XposedInterface.Chain ->
                // The migration's read-before-write goes through this same
                // method; answering it here would make every read see "already
                // allowed" and silently stop the manager-switch backfill from
                // ever writing while the root rung is on. The migration raises
                // this flag around its reads.
                if (java.lang.Boolean.TRUE == HookerSupport.autostartWriteReadInFlight.get()) {
                    return@intercept chain.proceed()
                }
                var answered = false
                try {
                    val pkg = chain.getArg(2) as? String
                    if ((chain.getArg(0) as? Int) == HookerSupport.MIUIOP_AUTO_START &&
                        pkg != null &&
                        wakeExplicitlyAllows(module, getSystemContext, pkg) &&
                        isAutostartRootReleaseEnabled(module)
                    ) {
                        ++autostartRootReleaseCount
                        answered = true
                        if (claimAutostartGateDetail(HookerSupport.AUTOSTART_GATE_ROOT, pkg)) {
                            module.logGated(
                                Log.INFO, HookerSupport.TAG,
                                "autostart root: answered op 10008 as allowed " +
                                    "(callee=$pkg)"
                            )
                        }
                    }
                } catch (t: Throwable) {
                    module.logGated(Log.ERROR, HookerSupport.TAG, "autostart root choke failed", t)
                }
                if (answered) AppOpsManager.MODE_ALLOWED else chain.proceed()
            }
            module.deoptimize(method)
            installed++
        }
        if (installed > 0) {
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "autostart gate: $installed checkpoint(s) hooked (read-through); " +
                    "denials counted unconditionally, each releasable gate follows " +
                    "its own experiment switch, pre-start probed read-only, " +
                    "AppOps root choke answers op 10008 before the ROM judges"
            )
        }
    }

    /**
     * One autostart-gate verdict: count a denial for a checked callee, and
     * release it when the experiment switch says to.
     *
     * Returns the value the caller should hand back to the ROM — the original
     * verdict, or `true` when this overrode it.
     *
     * One line per denial, carrying the outcome as well: the denial and the
     * release used to be two lines with two independent detail budgets, so a
     * late denial could be logged while its release silently fell past the
     * limit and the pair became unreadable — which is exactly how the first
     * sample ended, with a `DENIED` that nobody could tell apart from a
     * `kept`. Merging them costs one line per event instead of two, so the
     * budget below now covers twice as many events as it used to.
     */
    private fun autostartGateVerdict(
        module: XposedInterface,
        getSystemContext: () -> Context?,
        chain: XposedInterface.Chain,
        gate: String,
        callee: String?,
        detail: String? = null,
        releaseable: Boolean = true
    ): Any? {
        val result = chain.proceed()
        try {
            if (java.lang.Boolean.TRUE == result) {
                return result
            }
            if (callee == null || !wakeExplicitlyAllows(module, getSystemContext, callee)) {
                return result
            }
            val denied = when (gate) {
                HookerSupport.AUTOSTART_GATE_SERVICE -> ++autostartGateServiceDeniedCount
                HookerSupport.AUTOSTART_GATE_PROCESS -> ++autostartGateProcessDeniedCount
                HookerSupport.AUTOSTART_GATE_RESTART -> ++autostartGateRestartDeniedCount
                else -> ++autostartGatePreStartDeniedCount
            }
            // Probed checkpoints are answered by the ROM exactly as it answered
            // them before this hook existed, whatever the switch says: the point
            // of the probe is to learn whether they fire, and releasing them in
            // the same step would make the observation and the intervention
            // impossible to tell apart.
            //
            // The two releasable gate families each read their own experiment
            // key — deliberately not an AND chain under the master, so that
            // one rung can be isolated and measured on its own.
            val release = releaseable && when (gate) {
                HookerSupport.AUTOSTART_GATE_RESTART -> isAutostartRestartReleaseEnabled(module)
                else -> isAutostartGateReleaseEnabled(module)
            }
            if (release) {
                if (gate == HookerSupport.AUTOSTART_GATE_RESTART) {
                    ++autostartGateRestartReleaseCount
                } else {
                    ++autostartGateReleaseCount
                }
            }
            if (claimAutostartGateDetail(gate, callee)) {
                // Who asked, as far as the binder layer can tell. A real uid
                // other than 1000 means the call crossed a process boundary,
                // which is the only way this gate can name its caller; 1000
                // means system_server asked itself, and the caller stays
                // unknown. Either answer is a fact, so it is logged as read.
                val callerUid = try {
                    Binder.getCallingUid()
                } catch (t: Throwable) {
                    -1
                }
                module.logGated(
                    Log.INFO, HookerSupport.TAG,
                    "autostart gate: ${autostartGateLabel(gate)} DENIED #$denied -> " +
                        (if (!releaseable) "probe" else if (release) "released" else "kept") +
                        " (callee=$callee" +
                        (if (detail == null) "" else ", $detail") +
                        ", caller-uid=$callerUid)"
                )
            }
            if (release) {
                return true
            }
        } catch (t: Throwable) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "Failed to evaluate autostart gate", t)
        }
        return result
    }

    /**
     * Human wording for the gate key inside a detail line.
     *
     * The two original gates keep the exact letters they have always had —
     * `service start DENIED` / `process start DENIED` — because saved samples
     * and the diagnostics notes grep for them; only the two probes added
     * alongside need new words, and they get the gate name on its own. A gate
     * the ROM answered by *restarting* a killed service is not a "start", and a
     * pre-start checkpoint that is itself a probe should not borrow the start
     * family's phrasing.
     */
    private fun autostartGateLabel(gate: String): String = when (gate) {
        HookerSupport.AUTOSTART_GATE_SERVICE -> "$gate start"
        HookerSupport.AUTOSTART_GATE_PROCESS -> "$gate start"
        else -> gate
    }

    /**
     * `ServiceName` out of an intent meant to start one — the short class name,
     * because the full one repeats the package that is already in the line, and
     * the action when there is no component. Null when neither is present.
     */
    private fun shortComponent(intent: Intent): String? {
        val component = intent.component
        if (component != null) {
            val cls = component.className
            val dot = cls.lastIndexOf('.')
            return cls.substring(dot + 1)
        }
        val action = intent.action
        if (!action.isNullOrEmpty()) {
            val dot = action.lastIndexOf('.')
            return "action=" + action.substring(dot + 1)
        }
        return null
    }

    /**
     * Experiment switch: release the autostart verdict for a checked callee.
     *
     * Fails to *disabled*: this answers a gate the ROM owns, and an unreadable
     * switch must leave the ROM's own decision alone.
     */
    private fun isAutostartGateReleaseEnabled(module: XposedInterface): Boolean {
        return try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
                .also { HookLog.refresh(it) }
                .getBoolean(Prefs.KEY_AUTOSTART_GATE_RELEASE, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * Experiment switch: release the restart gate (freeze instead of restart)
     * for a checked callee. Reads its **own** key — not the master's — so the
     * freeze rung can be measured in isolation.
     *
     * Fails to *disabled*, same reasoning as the master above.
     */
    private fun isAutostartRestartReleaseEnabled(module: XposedInterface): Boolean {
        return try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
                .also { HookLog.refresh(it) }
                .getBoolean(Prefs.KEY_AUTOSTART_RESTART_RELEASE, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * Experiment switch: answer the autostart op query itself for a checked
     * package. Reads its **own** key, same isolation reasoning.
     *
     * Fails to *disabled*, same reasoning as the master above.
     */
    private fun isAutostartRootReleaseEnabled(module: XposedInterface): Boolean {
        return try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
                .also { HookLog.refresh(it) }
                .getBoolean(Prefs.KEY_AUTOSTART_ROOT_RELEASE, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * The experiment's own allowlist gate: membership is required, so an empty
     * list means "no app" rather than "every app".
     *
     * Kept separate from [AllowlistGate.moduleAppliesTo] for the GMS exemption only: that one
     * lets GMS through unconditionally, which is right for the shipped wake
     * privileges — the GMS/FCM chain must never be narrowed — but wrong for the
     * experiments here, which answer user-visible ROM gates and whose
     * descriptions promise "only the checked apps". The empty-list behaviour of
     * the two now agrees (both mean "no app"), so the two differ solely on GMS.
     */
    internal fun wakeExplicitlyAllows(
        module: XposedInterface,
        getSystemContext: () -> Context?,
        packageName: String
    ): Boolean {
        return AllowlistGate.allowlistContains(module, getSystemContext, packageName)
    }

    /**
     * The boot-time migration: a delayed whole-allowlist walk, run once per
     * system_server generation, that backfills the manager switch op (`10053`)
     * for packages an older build left at `10008=allow / 10053=ignore` —
     * functionally allowed but with 手机管家's autostart row showing "off", the
     * "self-deceiving" state the retired write switch created and this walk
     * exists to end. With no user action beyond the reboot a module update
     * causes anyway, every allowlisted package the user can see in the manager
     * ends up showing the state the behavior op is actually in.
     *
     * This is a **reconciliation**, not a write switch: the behavior op (`10008`)
     * is only ever read here, never written. A package the user (or the retired
     * switch) did not allow is left exactly as it is, and so is anything the
     * manager already shows as on — the read-before-write touches nothing that
     * is already consistent. 手机管家's own toggles are never fought: whatever
     * the user changed there since the last boot is the state this walk finds
     * and keeps.
     *
     * The retired switch's key is deliberately *not* consulted — the walk runs
     * unconditionally — because the packages that need the backfill are
     * identified by the ops' state on disk, not by a config value: an old
     * user who never opens the app again (so the retired keys are dropped
     * without ever being seen) must still be migrated.
     *
     * Delayed rather than run inline: install happens while system_server is
     * still bringing services up, and the AppOps service the walk needs is
     * registered only later — running it immediately would fail every read.
     * A failed walk is not retried within the generation; the next boot tries
     * again. Hot reloads re-arm it, which costs one redundant read per package
     * and nothing more.
     */
    internal fun backfillAutostartOpsAtStartup(
        module: XposedInterface,
        getSystemContext: () -> Context?
    ) {
        AllowlistGate.postDelayedToAllowlistHandler(module, AUTOSTART_BACKFILL_DELAY_MS) {
            walkAutostartSwitchOpSync(module, getSystemContext)
        }
    }

    /**
     * The migration walk itself: re-read the allowlist, backfill `10053` for
     * every allowlisted package sitting at `10008=allow / 10053=ignore`, and
     * log one line for the whole pass.
     */
    private fun walkAutostartSwitchOpSync(
        module: XposedInterface,
        getSystemContext: () -> Context?
    ) {
        AllowlistGate.loadAllowlistFromRemotePrefs(module)
        val allowlist = AllowlistGate.getFcmAllowlist(module, getSystemContext)
        if (allowlist.isEmpty()) {
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "wake: autostart sync skipped, the allowlist is empty"
            )
            return
        }
        var synced = 0
        var consistent = 0
        var skipped = 0
        var missing = 0
        var failed = 0
        for (pkg in allowlist) {
            when (syncAutostartSwitchOpIfNeeded(module, getSystemContext, pkg)) {
                HookerSupport.AUTOSTART_WRITTEN -> synced++
                HookerSupport.AUTOSTART_FAILED -> failed++
                HookerSupport.AUTOSTART_SKIPPED_SYSTEM -> skipped++
                HookerSupport.AUTOSTART_SKIPPED_MISSING -> missing++
                else -> consistent++
            }
        }
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "wake: autostart on startup over ${allowlist.size} allowlisted package(s): " +
                "$synced manager-switch op(s) backfilled, $consistent already consistent, " +
                "$skipped system, $missing missing, $failed failed"
        )
    }

    /**
     * One package of the migration: read the op pair, and set the manager
     * switch op (`10053`) to `MODE_ALLOWED` only when the behavior op (`10008`)
     * is allowed and the switch op is not — the exact fingerprint older builds
     * left behind. Everything else is a read and nothing more.
     *
     * There is no public API for this. The op is
     * `MIUIOP_AUTO_START_SWITCH = 10053`, and MIUI's own write is
     * `android.miui.AppOpsUtils#setApplicationAutoStart(Context, String, boolean)`
     * — `AppOpsManager.setMode(op, uid, pkg, allowed ? 0 : 2)` underneath
     * (`AppOpsUtils` disassembly: `MODE_ALLOWED` / `MODE_ERRORED`). Calling
     * `setMode` directly keeps this off the `AppOpsUtils` hidden class and lets
     * the uid be resolved for the user this module actually runs in.
     *
     * The op code is not in the SDK either — the public `AppOpsManager` only
     * exposes the name-based overloads — so both methods are resolved by
     * reflection and invoked through [XposedInterface.getInvoker], the same
     * route the module already uses for other hidden framework methods.
     *
     * Identity is cleared around the calls. The walk runs on the allowlist
     * handler thread inside system_server, so the uid it presents is already
     * 1000 — the clear is belt-and-braces, kept because the ROM judges
     * `setMode` against `MANAGE_APP_OPS_MODES` and the thread's identity is
     * not part of any contract this module owns.
     *
     * Both reads go through the same AppOpsManager entry the root choke hooks;
     * with the flag up the choke passes them through instead of answering, so
     * the migration keeps seeing the ops' real values even while the root rung
     * is on.
     *
     * System packages are refused outright — see [AUTOSTART_SKIPPED_SYSTEM] for
     * why (the manager page cannot undo an op it does not list). A package that
     * cannot be resolved at all falls through to [HookerSupport.AUTOSTART_FAILED].
     *
     * Returns [HookerSupport.AUTOSTART_WRITTEN], [HookerSupport.AUTOSTART_ALREADY],
     * [HookerSupport.AUTOSTART_SKIPPED_SYSTEM], [HookerSupport.AUTOSTART_SKIPPED_MISSING]
     * or [HookerSupport.AUTOSTART_FAILED]. A failure is logged here with the
     * exception — it has to name its package to be useful.
     */
    private fun syncAutostartSwitchOpIfNeeded(
        module: XposedInterface,
        getSystemContext: () -> Context?,
        packageName: String
    ): Int {
        val setMode = HookerSupport.APP_OPS_SET_MODE
        val checkOpNoThrow = HookerSupport.APP_OPS_CHECK_OP_NO_THROW
        if (setMode == null || checkOpNoThrow == null) {
            return HookerSupport.AUTOSTART_FAILED
        }
        val context = getSystemContext() ?: return HookerSupport.AUTOSTART_FAILED
        val token = Binder.clearCallingIdentity()
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return HookerSupport.AUTOSTART_FAILED
            val ai = try {
                context.packageManager.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                // Checked, then uninstalled without being unchecked first: skip
                // silently — see [HookerSupport.AUTOSTART_SKIPPED_MISSING]. Not the
                // generic catch below: that logs an ERROR with a stack per stale
                // entry per boot, which is noise, not a failure.
                return HookerSupport.AUTOSTART_SKIPPED_MISSING
            }
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                return HookerSupport.AUTOSTART_SKIPPED_SYSTEM
            }
            val uid = ai.uid
            HookerSupport.autostartWriteReadInFlight.set(true)
            try {
                val behaviorOp = module.getInvoker(checkOpNoThrow).invoke(
                    appOps, HookerSupport.MIUIOP_AUTO_START, uid, packageName
                )
                if (behaviorOp != AppOpsManager.MODE_ALLOWED) {
                    // Never allowed by anyone (or switched off in the manager,
                    // which writes the pair back): nothing to migrate.
                    return HookerSupport.AUTOSTART_ALREADY
                }
                val switchOp = module.getInvoker(checkOpNoThrow).invoke(
                    appOps, HookerSupport.MIUIOP_AUTO_START_SWITCH, uid, packageName
                )
                if (switchOp == AppOpsManager.MODE_ALLOWED) {
                    return HookerSupport.AUTOSTART_ALREADY
                }
                module.getInvoker(setMode).invoke(
                    appOps, HookerSupport.MIUIOP_AUTO_START_SWITCH, uid, packageName,
                    AppOpsManager.MODE_ALLOWED
                )
                return HookerSupport.AUTOSTART_WRITTEN
            } finally {
                HookerSupport.autostartWriteReadInFlight.set(false)
            }
        } catch (t: Throwable) {
            module.logGated(
                Log.ERROR, HookerSupport.TAG,
                "wake: failed to sync the autostart switch op of $packageName", t
            )
            return HookerSupport.AUTOSTART_FAILED
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * Autostart gate: denials the ROM issued for a *checked* callee, split by
     * the checkpoint that issued them. See [hookAutostartGate].
     *
     * Counted whether or not the experiment switch is on, so a user who never
     * turns it on still gets the evidence: non-zero here is the ROM refusing a
     * start that the module's own broadcast hook already waved through.
     */
    @Volatile
    internal var autostartGateServiceDeniedCount = 0

    @Volatile
    internal var autostartGateProcessDeniedCount = 0

    /**
     * Autostart gate: denials from the two checkpoints added alongside the pair
     * above, counted the same way and for the same reason.
     *
     * Both are **read-only**: they exist to establish whether these ROM paths
     * are live and whether they ever deny a checked package, before anything is
     * allowed to answer them. A counter that stays zero is the answer "not on
     * this build" — which is a fact worth having, and one the module cannot get
     * by reading the ROM alone.
     */
    @Volatile
    internal var autostartGateRestartDeniedCount = 0

    @Volatile
    internal var autostartGatePreStartDeniedCount = 0

    /** Autostart gate: verdicts this hook flipped from deny to allow. */
    @Volatile
    internal var autostartGateReleaseCount = 0

    /**
     * Restart gate: verdicts flipped from freeze to restart. Counted apart
     * from [autostartGateReleaseCount] because the gate answers a different
     * question — whether a dead service may come back at all, not whether a
     * start may happen — and the ladder reads the two rungs separately.
     */
    @Volatile
    internal var autostartGateRestartReleaseCount = 0

    /**
     * Root choke: queries answered as allowed before the ROM's own logic ran.
     * Not a denial count at all — the ROM never refuses here — so it lives in
     * its own number and never mixes into the `released=` pair.
     */
    @Volatile
    internal var autostartRootReleaseCount = 0

    /**
     * Autostart gate detail budget: "(gate/callee)" → lines already spent in
     * the current window, plus the window's start and total. See
     * [AUTOSTART_GATE_DETAIL_PER_CALLEE] for why it is shaped this way.
     *
     * Guarded by [autostartGateDetailLock] rather than made a concurrent map,
     * because the reset has to see the buckets and the window start as one
     * decision: two threads entering on either side of the boundary would
     * otherwise clear each other's work or hand out a second full allowance.
     * Every access is short and none of them can block on the ROM.
     */
    private val autostartGateDetailBuckets = HashMap<String, Int>()
    private val autostartGateDetailLock = Any()
    private var autostartGateDetailWindowStartMs = 0L
    private var autostartGateDetailWindowTotal = 0

    /**
     * Whether this denial may spend a detail line, rolling the window when it
     * has aged out. True for at most [AUTOSTART_GATE_DETAIL_PER_CALLEE] lines
     * per (gate, callee) pair per window.
     *
     * The bucket key is the pair, not the bare callee: an active callee that
     * gets denied at several gates in the same window would otherwise spend
     * one shared allowance across all of them and starve the later gates —
     * which is exactly what the first live window did (22 denials, 10 lines,
     * the restart gate starved by the process gate before it).
     */
    private fun claimAutostartGateDetail(gate: String, callee: String): Boolean {
        synchronized(autostartGateDetailLock) {
            val now = SystemClock.elapsedRealtime()
            if (now - autostartGateDetailWindowStartMs >= HookerSupport.AUTOSTART_GATE_DETAIL_WINDOW_MS) {
                autostartGateDetailBuckets.clear()
                autostartGateDetailWindowTotal = 0
                autostartGateDetailWindowStartMs = now
            }
            val key = "$gate/$callee"
            val used = autostartGateDetailBuckets[key] ?: 0
            if (used >= HookerSupport.AUTOSTART_GATE_DETAIL_PER_CALLEE ||
                autostartGateDetailWindowTotal >= HookerSupport.AUTOSTART_GATE_DETAIL_PER_HOUR
            ) {
                return false
            }
            autostartGateDetailBuckets[key] = used + 1
            autostartGateDetailWindowTotal++
            return true
        }
    }
}
