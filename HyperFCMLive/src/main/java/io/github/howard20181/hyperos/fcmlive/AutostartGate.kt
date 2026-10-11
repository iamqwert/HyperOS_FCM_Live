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
 * summary that stays with `Hooker`), `getSystemContext` is
 * `Hooker#getSystemContext` as a supplier, and `onApplyAutostart` is
 * `Hooker#applyAutostartToAllowlist` — the AllowlistGate callback handle,
 * which stays with `Hooker` so the reference path documented there is
 * unchanged.
 */
internal object AutostartGate {

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
        getSystemContext: () -> Context?,
        onApplyAutostart: () -> Unit
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
                // probe exists to answer: modern FCM reaches an app by starting
                // or binding its Firebase* service rather than by sending it a
                // c2dm broadcast (the shipped broadcast hop has never seen one on
                // this ROM — `broadcast gate c2dm allowed` stays 0). So the
                // detail carries the component's short class name, which names
                // the service the ROM was about to refuse.
                val detail = intent?.let { shortComponent(it) }
                autostartGateVerdict(
                    module, getSystemContext, onApplyAutostart,
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
                    module, getSystemContext, onApplyAutostart,
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
                    module, getSystemContext, onApplyAutostart,
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
                    module, getSystemContext, onApplyAutostart,
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
                // The module's own read-before-write goes through this same
                // method; answering it here would make every write see "already
                // allowed" and silently stop the persistent half from ever
                // writing. The write path raises this flag around its read.
                if (java.lang.Boolean.TRUE == HookerSupport.autostartWriteReadInFlight.get()) {
                    return@intercept chain.proceed()
                }
                var answered = false
                try {
                    val pkg = chain.getArg(2) as? String
                    if ((chain.getArg(0) as? Int) == HookerSupport.MIUIOP_AUTO_START &&
                        pkg != null &&
                        wakeExplicitlyAllows(module, getSystemContext, onApplyAutostart, pkg) &&
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
        onApplyAutostart: () -> Unit,
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
            if (callee == null || !wakeExplicitlyAllows(module, getSystemContext, onApplyAutostart, callee)) {
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
     * Experiment switch: write the autostart *switch* op (`10053`) alongside
     * the behavior op. ANDed with the behavior write on the hook side, so
     * this alone writes nothing.
     *
     * Fails to *disabled*: this writes a setting the user can see, so an
     * unreadable switch must leave the ROM's own value alone.
     */
    private fun isWakeWriteAutostartSwitchEnabled(module: XposedInterface): Boolean {
        return try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
                .also { HookLog.refresh(it) }
                .getBoolean(Prefs.KEY_WAKE_WRITE_AUTOSTART_SWITCH, false)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * Experiment switch: write the package's autostart op.
     *
     * It used to be the sub-switch of a master that answered the same ROM gate
     * in memory, and was ANDed with it so that turning the master off stopped
     * the hook from writing a user-visible setting behind a control the user
     * could no longer see. That master is gone — see the comment in the
     * [checkApplicationAutoStart] hook — so this is now read on its own: the
     * row on screen is the whole control, and there is no hidden gate above it.
     *
     * Fails to *disabled*: this writes a setting the user can see, so an
     * unreadable switch must leave the ROM's own value alone.
     */
    internal fun isWakeWriteAutostartEnabled(module: XposedInterface): Boolean {
        return try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
                .also { HookLog.refresh(it) }
                .getBoolean(Prefs.KEY_WAKE_WRITE_AUTOSTART, false)
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
     * experiments here, which write user-visible ROM settings and whose
     * descriptions promise "only the checked apps". The empty-list behaviour of
     * the two now agrees (both mean "no app"), so the two differ solely on GMS.
     */
    internal fun wakeExplicitlyAllows(
        module: XposedInterface,
        getSystemContext: () -> Context?,
        onApplyAutostart: () -> Unit,
        packageName: String
    ): Boolean {
        return AllowlistGate.allowlistContains(module, getSystemContext, onApplyAutostart, packageName)
    }

    /**
     * Whether [action] belongs to the push family the autostart pair acts on.
     *
     * The c2dm actions match by suffix because the family has historical
     * prefixes (`com.google.android.c2dm`, `com.google.android.gcm`); the
     * Firebase ones are exact. Same set the two reference modules use, and the
     * same reasoning as the shipped GMS→c2dm hop, widened past c2dm only for
     * this experiment.
     */
    internal fun isPushAction(action: String?): Boolean {
        if (action == null) {
            return false
        }
        return HookerSupport.PUSH_ACTIONS.contains(action) || HookerSupport.PUSH_ACTION_SUFFIXES.any { action.endsWith(it) }
    }

    /**
     * The lazy half of the autostart pair: the write for the one package a push
     * was just aimed at.
     *
     * This is what keeps the setting from drifting back: a freeze tool that
     * resets the op is corrected the next time its package gets a push. It is
     * no longer the only way the op is written — [applyAutostartToAllowlist]
     * covers the whole list the moment the user flips the sub-switch on, which
     * is what makes the switch observable from a shell right after tapping it.
     *
     * One line per write and nothing on a no-op, so the log stays quiet: a
     * package already at `MODE_ALLOWED` (and every package inside its throttle
     * window) prints nothing at all.
     */
    internal fun maybeWriteAutostart(
        module: XposedInterface,
        getSystemContext: () -> Context?,
        packageName: String
    ) {
        if (writeAutostartIfNeeded(module, getSystemContext, packageName, throttle = true) == HookerSupport.AUTOSTART_WRITTEN) {
            val ops = if (isWakeWriteAutostartSwitchEnabled(module)) " (10008, 10053)" else " (10008)"
            module.logGated(Log.INFO, HookerSupport.TAG, "wake: set the autostart op$ops of $packageName to allowed")
        }
    }

    /**
     * Run the autostart write over the whole allowlist, once, on request from
     * the settings UI — see [Prefs.ACTION_APPLY_AUTOSTART].
     *
     * Without this the sub-switch was purely lazy: flipping it changed nothing
     * on disk until each app happened to receive its next push, which for a
     * rarely-pushed package could be tomorrow or never, and left no way to
     * confirm the write from a shell right after tapping the row.
     *
     * Deliberately not a repeating task and not run at boot: its job is to make
     * one user action take effect, and the lazy path above is what maintains
     * the result afterwards. Nothing here un-does the write — the module never
     * learns the previous values, so turning the switch off leaves them alone.
     *
     * Runs on the allowlist handler thread, which serialises it against itself
     * (a burst of taps queues, it does not interleave) and against the allowlist
     * reads. The list is re-read here rather than taken from the last reload:
     * the reload throttle would otherwise let this walk a stale copy.
     *
     * Logging is one line for the whole walk, never one per package — this runs
     * over a list the user can see, and per-package lines would print all of it
     * on every tap.
     */
    internal fun applyAutostartToAllowlist(
        module: XposedInterface,
        getSystemContext: () -> Context?
    ) {
        if (!isWakeWriteAutostartEnabled(module)) {
            return
        }
        AllowlistGate.loadAllowlistFromRemotePrefs(module)
        val allowlist = AllowlistGate.getFcmAllowlist(
            module, getSystemContext, { applyAutostartToAllowlist(module, getSystemContext) }
        )
        if (allowlist.isEmpty()) {
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "wake: autostart write requested with an empty allowlist, nothing to write"
            )
            return
        }
        var written = 0
        var already = 0
        var skipped = 0
        var missing = 0
        var failed = 0
        for (pkg in allowlist) {
            // throttle = false: this is a user action, so it writes even inside
            // the lazy path's window. An already-allowed package is still only
            // a read, so re-tapping the switch costs no settings writes.
            when (writeAutostartIfNeeded(module, getSystemContext, pkg, throttle = false)) {
                HookerSupport.AUTOSTART_WRITTEN -> written++
                HookerSupport.AUTOSTART_FAILED -> failed++
                HookerSupport.AUTOSTART_SKIPPED_SYSTEM -> skipped++
                HookerSupport.AUTOSTART_SKIPPED_MISSING -> missing++
                else -> already++
            }
        }
        module.logGated(
            Log.INFO, HookerSupport.TAG,
            "wake: autostart on request over ${allowlist.size} allowlisted package(s): " +
                "$written written, $already already allowed, $skipped system, " +
                "$missing missing, $failed failed"
        )
    }

    /**
     * Set the package's MIUI autostart AppOp to "allowed" if it is not allowed
     * already — the persistent half of the autostart pair, and the one place
     * the module edits a user-visible system setting.
     *
     * With the switch-write rung on, the op the manager toggle owns (`10053`)
     * is written right after the behavior op (`10008`), the same pair 手机管家
     * writes when its own toggle moves. Each op is read before written and
     * each write counts; the return value says whether *anything* was written.
     *
     * There is no public API for this. The op is `MIUIOP_AUTO_START = 10008`
     * (`com.miui.internal.os.MiuiHooks.OP_AUTO_START`, OS4 miui-framework
     * bytecode), and MIUI's own write is
     * `android.miui.AppOpsUtils#setApplicationAutoStart(Context, String, boolean)`
     * — which is a three-line wrapper around exactly the call made here:
     * it resolves the uid with `PackageManager#getPackageUidAsUser` and then
     * `AppOpsManager.setMode(10008, uid, pkg, autoStart ? 0 : 2)`
     * (`AppOpsUtils` disassembly, offsets 0x0000-0x0026: `if-eqz` picks
     * `const/4 #int 0` = `MODE_ALLOWED`, else `const/4 #int 2` =
     * `MODE_ERRORED`). Calling `setMode` directly keeps this off the
     * `AppOpsUtils` hidden class and lets the uid be resolved for the user this
     * module actually runs in.
     *
     * The op code is not in the SDK either — the public `AppOpsManager` only
     * exposes the name-based overloads — so both methods are resolved by
     * reflection and invoked through [XposedInterface.getInvoker], the same
     * route the module already uses for other hidden framework methods.
     *
     * Identity is cleared around the calls. The hook runs on the broadcast's
     * binder thread, so without it the ROM would judge the *sender's* uid:
     * `setMode` enforces `MANAGE_APP_OPS_MODES`, which the module only holds as
     * system_server (uid 1000). Scope is these calls and nothing else — clearing
     * across `chain.proceed()` would let every broadcast run as system.
     *
     * Read-before-write, so a package already at `MODE_ALLOWED` costs a read and
     * no settings write. [throttle] is false for the on-request walk, which is
     * user-driven and must take effect even inside the lazy path's window.
     *
     * System packages are refused outright — see [AUTOSTART_SKIPPED_SYSTEM].
     * The refusal is checked on the same [ApplicationInfo] the uid resolution
     * already needs, so it costs nothing extra, and a package that cannot be
     * resolved at all still falls through to [AUTOSTART_FAILED]: an
     * unresolvable package does not get written either.
     *
     * Returns [AUTOSTART_WRITTEN], [AUTOSTART_ALREADY],
     * [AUTOSTART_SKIPPED_SYSTEM], [AUTOSTART_SKIPPED_MISSING] or
     * [AUTOSTART_FAILED]. Logging is left to
     * the caller because the two callers want different shapes: the lazy path
     * wants a line per write, the on-request walk wants one line for the
     * whole list. The one exception is a failure, which is logged here with
     * the exception — a failure has to name its package to be useful, and it
     * is never the common case.
     */
    private fun writeAutostartIfNeeded(
        module: XposedInterface,
        getSystemContext: () -> Context?,
        packageName: String,
        throttle: Boolean
    ): Int {
        val setMode = HookerSupport.APP_OPS_SET_MODE
        val checkOpNoThrow = HookerSupport.APP_OPS_CHECK_OP_NO_THROW
        if (setMode == null || checkOpNoThrow == null) {
            return HookerSupport.AUTOSTART_FAILED
        }
        if (throttle) {
            val now = SystemClock.elapsedRealtime()
            val last = HookerSupport.wakeAutostartLastMs.putIfAbsent(packageName, now)
            if (last != null) {
                if (now - last < HookerSupport.WAKE_AUTOSTART_COOLDOWN_MS) {
                    return HookerSupport.AUTOSTART_ALREADY
                }
                HookerSupport.wakeAutostartLastMs[packageName] = now
            }
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
                // silently — see [AUTOSTART_SKIPPED_MISSING]. Not the generic
                // catch below: that logs an ERROR with a stack per stale entry
                // per broadcast, which is noise, not a failure.
                return HookerSupport.AUTOSTART_SKIPPED_MISSING
            }
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                return HookerSupport.AUTOSTART_SKIPPED_SYSTEM
            }
            val uid = ai.uid
            // The read-before-write goes through the same AppOpsManager entry
            // the root choke hooks; with the flag up the choke passes the read
            // through instead of answering it, so the persistent half keeps
            // seeing the ops' real values even while the root rung is on.
            HookerSupport.autostartWriteReadInFlight.set(true)
            var wrote = false
            try {
                wrote = writeOpIfDenied(
                    module, appOps, checkOpNoThrow, setMode, HookerSupport.MIUIOP_AUTO_START, uid, packageName
                )
                if (isWakeWriteAutostartSwitchEnabled(module)) {
                    // The switch op rides the same occasions and the same
                    // guard as the behavior op — 手机管家's own toggle writes
                    // the pair together, so the module does too.
                    wrote = writeOpIfDenied(
                        module, appOps, checkOpNoThrow, setMode,
                        HookerSupport.MIUIOP_AUTO_START_SWITCH, uid, packageName
                    ) || wrote
                }
            } finally {
                HookerSupport.autostartWriteReadInFlight.set(false)
            }
            return if (wrote) HookerSupport.AUTOSTART_WRITTEN else HookerSupport.AUTOSTART_ALREADY
        } catch (t: Throwable) {
            module.logGated(Log.ERROR, HookerSupport.TAG, "wake: failed to write the autostart op of $packageName", t)
            return HookerSupport.AUTOSTART_FAILED
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * One op of the autostart pair: read it, and set it to `MODE_ALLOWED` if
     * it is not allowed already. True when this call wrote, false when the op
     * was already allowed (a read, no settings write).
     */
    private fun writeOpIfDenied(
        module: XposedInterface,
        appOps: AppOpsManager,
        checkOpNoThrow: Method,
        setMode: Method,
        op: Int,
        uid: Int,
        packageName: String
    ): Boolean {
        val current =
            module.getInvoker(checkOpNoThrow).invoke(appOps, op, uid, packageName)
        if (current == AppOpsManager.MODE_ALLOWED) {
            return false
        }
        module.getInvoker(setMode).invoke(
            appOps, op, uid, packageName, AppOpsManager.MODE_ALLOWED
        )
        return true
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
