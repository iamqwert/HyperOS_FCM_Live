package io.github.howard20181.hyperos.fcmlive

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.PowerExemptionManager
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object HookerSupport {
    internal const val TAG = "HyperGreeze"
    internal val CN_DEFER_BROADCAST = listOf(
        "com.google.android.intent.action.GCM_RECONNECT",
        "com.google.android.gcm.DISCONNECTED",
        "com.google.android.gcm.CONNECTED",
        "com.google.android.gms.gcm.HEARTBEAT_ALARM"
    )

    const val ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE"
    const val ACTION_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"
    const val FCM_MESSAGING_SERVICE_CLASS =
        "com.google.firebase.messaging.FirebaseMessagingService"
    const val FCM_IID_RECEIVER_CLASS =
        "com.google.firebase.iid.FirebaseInstanceIdReceiver"
    internal const val GMS_PACKAGE_NAME = "com.google.android.gms"
    internal const val GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent"
    internal const val WECHAT_PACKAGE_NAME = "com.tencent.mm"

    /**
     * OS4/V816 sleep mode lives here, in the PowerKeeper process — not in
     * system_server's MiuiNetworkPolicyManagerService, which is why
     * hooking that service's sleep whitelist never fired.
     */
    internal const val SLEEP_CONTROLLER_CLASS =
        "com.miui.powerkeeper.statemachine.PhoneSleepModeController"

    /**
     * Push-family actions the autostart pair acts on.
     *
     * The c2dm ones match by suffix because the family has historical
     * prefixes; the Firebase ones are exact. Everything the autostart
     * branches do is gated on this being true, which is what keeps them off
     * the hot path of the broadcast queue.
     */
    internal val PUSH_ACTION_SUFFIXES = arrayOf(
        ".android.c2dm.intent.RECEIVE",
        ".android.c2dm.intent.REGISTRATION"
    )
    internal val PUSH_ACTIONS = setOf(
        "com.google.firebase.MESSAGING_EVENT",
        "com.google.firebase.INSTANCE_ID_EVENT",
        "com.google.firebase.NEW_TOKEN"
    )

    /**
     * MIUI's autostart AppOp — `com.miui.internal.os.MiuiHooks.OP_AUTO_START`.
     * Not an AOSP op: the MIUI range starts at 10000.
     *
     * **This one is the behaviour; `10053` is only the switch the Security
     * Centre UI shows.** Confirmed on V816 by opening autostart for a
     * package and watching both ops: the ROM writes and reverts the pair
     * together, but writing `10008` alone is already enough to change what
     * the ROM does. So the ~25 packages sitting at `10008=allow /
     * 10053=ignore` are not in an inconsistent *functional* state — only
     * the Settings row is lying. (Corollary: the user's own revert path is
     * the two-step one — a row showing "off" has to be turned "on" before
     * "off" writes `10008` back.)
     *
     * **Why this op decides delivery although the module already answers
     * the broadcast gate.** The hook on
     * `BroadcastQueueModernStubImpl#checkApplicationAutoStart` short-circuits
     * at method entry for caller=GMS + c2dm, so on the FCM hop the op is
     * never even read there. The op is read by the *other* checkpoints
     * (see HOOKS_AND_DIAGNOSTICS §「自启动 AppOps 10008 共四类检查点」):
     * service start/bind (`AutoStartManagerServiceStub#isAllowStartService`
     * and friends) and process start (`ProcessManagerService#isAllowAutoStart`).
     * A message whose process is cached still has to start the messaging
     * service; one whose process was reaped has to start the process. Both
     * read this op, which is why the same write fixes both and why whether
     * the process was dead does not change the outcome.
     *
     * Rejection shows up in logcat as `MIUILOG-AutoStart, Service/Provider/
     * Broadcast Reject …`; the service/activity sibling family is what the
     * read-only `checkWakePath` probe logs as `checkWakePath DENIED`.
     */
    internal const val MIUIOP_AUTO_START = 10008

    /**
     * `10053 = OP_AUTO_START_SWITCH` — the manager toggle's own op, which
     * 手机管家's autostart switch writes alongside `10008`
     * (framework.jar → `miui/MiuiAppOpsNames.java`). Writing it is the
     * display-symmetry rung; whether the manager's row *reads* it for
     * display is not yet bytecode-confirmed, so nothing on screen
     * promises a visible change.
     */
    internal const val MIUIOP_AUTO_START_SWITCH = 10053

    /**
     * Outcomes of [writeAutostartIfNeeded]. Ints rather than an enum so the
     * broadcast paths stay allocation-free; the two callers only compare
     * them, they never hold them.
     */
    internal const val AUTOSTART_ALREADY = 0
    internal const val AUTOSTART_WRITTEN = 1
    internal const val AUTOSTART_FAILED = -1

    /**
     * The package carries [ApplicationInfo.FLAG_SYSTEM], so the write was
     * refused on purpose. One flag covers every preloaded shape the user
     * can end up with — factory apps on any preloaded partition, and
     * updated system apps, where the update only replaces the APK under
     * /data and never clears the flag.
     *
     * The reason for the refusal is reversibility, not harm: the ROM's own
     * undo path for a written op is 手机管家's autostart toggle, and the
     * deep system packages do not appear on that page at all. This module
     * never learns the previous op values, so a write it cannot undo
     * through the ROM is a write it cannot undo at all.
     */
    internal const val AUTOSTART_SKIPPED_SYSTEM = 2

    /**
     * The allowlisted package is no longer installed — checked once, then
     * uninstalled without being unchecked first. There is nothing to write
     * and nothing to fail at: the entry is stale in the allowlist until the
     * user unchecks it (or reinstalls), and the ROM's own op row for a
     * removed package is gone anyway. Skipped silently, like
     * [AUTOSTART_SKIPPED_SYSTEM], because it is a state the allowlist can
     * legitimately sit in, not an error — an ERROR per stale entry per
     * broadcast would be indistinguishable from a storm.
     */
    internal const val AUTOSTART_SKIPPED_MISSING = 3

    /**
     * Shortest interval between two autostart writes for the same package on
     * the *lazy* path — see [maybeWriteAutostart]. The setting is persistent,
     * so a burst of pushes does not need a burst of writes, and the
     * read-before-write makes an already-allowed package cost nothing but a
     * read.
     *
     * [applyAutostartToAllowlist] ignores it, because that path is one
     * deliberate user action rather than a stream of system events.
     */
    internal const val WAKE_AUTOSTART_COOLDOWN_MS = 60_000L
    /** Package → last autostart write attempt, best-effort under concurrency. */
    internal val wakeAutostartLastMs = ConcurrentHashMap<String, Long>()

    /**
     * Set while the module itself is reading the autostart op for its
     * read-before-write. The root choke hooks the very method this read
     * goes through, and without the flag the choke's answer ("allowed")
     * would make every write see an already-allowed op and silently stop
     * the persistent half from ever writing while the root rung is on.
     * Thread-local because the write runs on the allowlist handler thread;
     * always cleared in a finally.
     */
    internal val autostartWriteReadInFlight = ThreadLocal<Boolean>()

    /**
     * `AppOpsManager#setMode(int, int, String, int)` and
     * `#checkOpNoThrow(int, int, String)`, resolved once.
     *
     * Neither overload is in the public SDK — it only exposes the name-based
     * forms, because the op *code* is the hidden half. Resolved by
     * reflection and invoked through [XposedInterface.getInvoker], the route
     * the module already uses for hidden framework methods; a miss makes
     * [writeAutostartIfNeeded] report [AUTOSTART_FAILED] rather than crash.
     */
    internal val APP_OPS_SET_MODE: Method? by lazy {
        try {
            AppOpsManager::class.java.getDeclaredMethod(
                "setMode",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            null
        }
    }
    internal val APP_OPS_CHECK_OP_NO_THROW: Method? by lazy {
        try {
            AppOpsManager::class.java.getDeclaredMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
        } catch (t: Throwable) {
            null
        }
    }


    /**
     * WiFi scorer behind the relaxed weak-signal switch.
     *
     * It lives in `/system_ext/framework/miui-wifi-service.jar`, not in
     * services.jar, and NetworkBoost.jar only reaches it by reflection — so
     * to re-verify on another generation, grep across the framework jars
     * rather than trusting the presence of NetworkBoost.jar.
     *
     * The 50 is the ROM's own constant (`const/16 v2, #int 50` a few
     * instructions above the `if-ge` that decides `isUsable`); it is not a
     * tunable this module owns, and a *higher* floor would invent a verdict
     * stricter than the ROM's.
     */
    internal const val WIFI_SCORER_CLASS =
        "com.android.server.wifi.global.global_scorer.AmlMiuiThirdPartScorer"
    internal const val WIFI_SCORER_NOTIFY_METHOD = "notifyScoreAndIsUsable"
    internal const val WIFI_SCORER_SCORE_FIELD = "mLegacyIntScore"
    /**
     * The ROM's own verdict threshold, not a tunable this module owns: a
     * *higher* one would invent a verdict stricter than the ROM's.
     */
    internal const val WIFI_SCORE_USABLE_MIN = 50
    /**
     * What the score is rewritten to, deliberately one whole point above
     * [WIFI_SCORE_USABLE_MIN] rather than equal to it.
     *
     * Equal also works today, because the ROM asks `< 50` and 50 clears
     * that. It stops working the day a generation asks `<= 50`, and it
     * would then stop silently — the log still says "reported as usable
     * instead" while the ROM quietly disagrees. One point costs nothing
     * here, since nothing downstream reads this field during the call we
     * are inside, and it keeps the verdict on the far side of either
     * comparison.
     */
    internal const val WIFI_SCORE_CLAMP_TARGET = WIFI_SCORE_USABLE_MIN + 1
    internal const val WIFI_SCORE_CLAMP_LOG_INTERVAL_MS = 30_000L
    /**
     * Services whose implementation ships in the same jar as the scorer, so
     * their binder's loader can resolve it. Order does not matter; any one
     * of them being published is enough.
     */
    internal val WIFI_SCORER_CLASS_LOADER_SERVICES = arrayOf(
        "MiuiWifiService",
        "AmlConnectivityService",
        "MiuiNetPathOptimizerService"
    )
    internal const val SLEEP_APPLY_METHOD = "applySleepConfig"
    internal const val SLEEP_RESTORE_METHOD = "restoreSleepConfig"

    /**
     * Total cutoff switch PowerKeeper itself refuses to bypass: when this
     * reads 1 the whole "turn WiFi and mobile data off" block inside
     * applySleepConfig is jumped over, because the ROM would otherwise
     * break delivery of life-safety messages. Reporting 1 for this one
     * read — and nothing else — reuses that vendor-designed escape hatch
     * instead of fighting the calls further downstream.
     */
    internal const val SLEEP_EARTHQUAKE_KEY = "key_open_earthquake_warning"
    internal const val GMS_TRAFFIC_PROBE_INTERVAL_MS = 30 * 60_000L
    internal const val GMS_TRAFFIC_NUDGE_RESAMPLE_MS = 15_000L

    /** java.util.System property key holding the latest probe chain generation. */
    internal const val TRAFFIC_PROBE_GENERATION_KEY = "hyperfcmlive.trafficProbe.generation"

    /**
     * Gate-W heartbeat floor. The gate is reached thousands of times a night,
     * and a fixed every-Nth log buries everything else in modules_*.log.
     * Time-throttling keeps the "the gate was reached" evidence while cutting
     * the volume by roughly an order of magnitude.
     */
    internal const val WAKE_PATH_HEARTBEAT_MIN_MS = 30 * 60_000L

    /** Gate-W / Gate-B: detailed probe lines before falling back to plain counters. */
    internal const val WAKE_PATH_DETAIL_LIMIT = 10

    /**
     * Autostart gate: detailed lines, budgeted **per callee and per hour**
     * rather than once per process.
     *
     * The budget used to be a single process-lifetime allowance of 20 shared
     * by both gates. An evening of normal phone use spent it before
     * midnight, so the quiet hours — the only stretch in which a denial is
     * interesting — were left with counters and no detail. "Nothing to
     * read" is precisely the case the detail exists for, and the budget was
     * the reason for it.
     *
     * Per callee so one chatty package cannot spend every other package's
     * allowance, and hourly so a burst cannot spend the rest of the night's.
     * The hourly total is a volume backstop for the case where many distinct
     * checked packages are all being denied at once.
     */
    internal const val AUTOSTART_GATE_DETAIL_PER_CALLEE = 4
    internal const val AUTOSTART_GATE_DETAIL_PER_HOUR = 40
    internal const val AUTOSTART_GATE_DETAIL_WINDOW_MS = 60 * 60_000L

    // OS4 (myron) symbols, confirmed against `miui-services` dex: the class is
    // `AutoStartManagerServiceStubImpl`, not the `…Stub` the diagnostics doc
    // used to quote — `loadClass` on the shorter name finds nothing.
    internal const val AUTOSTART_STUB_CLASS =
        "com.android.server.am.AutoStartManagerServiceStubImpl"
    internal const val AUTOSTART_SERVICE_METHOD = "isAllowStartService"
    internal const val AUTOSTART_PROCESS_METHOD = "isAllowAutoStart"
    /**
     * Restart-after-death. Same class as the two above, and the only
     * autostart checkpoint on the "service was killed and is being
     * scheduled back" path — `ActiveServices.scheduleServiceRestartLocked`.
     * Two overloads exist; both take the callee package as parameter 0.
     */
    internal const val AUTOSTART_RESTART_METHOD = "canRestartServiceLocked"

    /**
     * MIUI's pre-start feedback: the fourth place in this ROM that reads
     * `10008`, and the only one outside `am`'s autostart family.
     *
     * `PreStartFeedbackImpl#isAutoStart(String, int)` is the read itself — a
     * private helper returning `true` when the op is `MODE_ALLOWED`. Its
     * callers are the pre-start *punishments*: `isProhibitShowWindow` (block
     * a window), `isTempDeniedNotificationForPreStart` (withhold a
     * notification) and `reportProcStartedLocked` (track a cached process
     * for kill). So an app that starts in the background without autostart
     * is not merely refused — the ROM also takes its window and its
     * notification away, which is a delivery-side effect the other three
     * checkpoints cannot produce.
     *
     * Probed read-only: whether these paths are even enabled on this build
     * is a static ROM flag, not something the module may assume.
     */
    internal const val AUTOSTART_PRE_START_CLASS =
        "com.android.server.am.PreStartFeedbackImpl"
    internal const val AUTOSTART_PRE_START_METHOD = "isAutoStart"

    internal const val AUTOSTART_GATE_SERVICE = "service"
    internal const val AUTOSTART_GATE_PROCESS = "process"
    internal const val AUTOSTART_GATE_RESTART = "restart"
    internal const val AUTOSTART_GATE_PRE_START = "pre-start"

    /**
     * Detail-bucket key for the root choke. Not a gate the ROM can deny —
     * the choke answers before the ROM's own logic runs — so it never
     * appears in a `DENIED` line; it only borrows the detail budget so one
     * active package cannot flood the log with per-query lines.
     */
    internal const val AUTOSTART_GATE_ROOT = "root"

    /**
     * §5 alarm gate: the Impl overrides the Stub, so the Impl is the live
     * target; the base type is kept as fallback for ROMs that never split it.
     */
    internal val ALARM_GATE_CLASS_NAMES = listOf(
        "com.android.server.alarm.AlarmManagerServiceStubImpl",
        "com.android.server.alarm.AlarmManagerServiceStub"
    )
    internal const val MILLET_NO_RESTRICT_APP_KEY = "MILLET_NO_RESTRICT_APP"

    /** PowerKeeper user config table: source row for bgControl. */
    internal const val USER_TABLE_URI = "content://com.miui.powerkeeper.configure/userTable"
    internal const val COL_PKG_NAME = "pkgName"
    internal const val COL_USER_ID = "userId"
    internal const val COL_LAST_CONFIGURED = "lastConfigured"
    internal const val COL_BG_CONTROL = "bgControl"
    internal const val BG_CONTROL_NO_RESTRICT = "noRestrict"

    /** P3 scenario constants (from live PowerKeeper dumps). */
    internal const val SCENARIO_MUI_AUTO_GMS = 0   // isGmsCoreApp + miuiAuto
    internal const val SCENARIO_NO_RESTRICT = 8    // bgControl = noRestrict

    /** P4 recovery actions (outbound IPC to GMS/GSF, not hooks). */
    private const val ACTION_GCM_RECONNECT = "com.google.android.intent.action.GCM_RECONNECT"
    private const val ACTION_GTALK_HEARTBEAT = "com.google.android.intent.action.GTALK_HEARTBEAT"
    private const val ACTION_MCS_HEARTBEAT = "com.google.android.intent.action.MCS_HEARTBEAT"
    internal const val GSF_PACKAGE_NAME = "com.google.android.gsf"
    internal val RECOVERY_BROADCAST_ACTIONS = arrayOf(
        ACTION_GCM_RECONNECT,
        ACTION_GTALK_HEARTBEAT,
        ACTION_MCS_HEARTBEAT
    )
    internal const val CHIMERA_PROVIDER_URI = "content://com.google.android.gms.chimera"

    internal const val ALLOWLIST_STALE_MS = 10_000L
    internal const val ALLOWLIST_FAILURE_BACKOFF_BASE_MS = 1_000L
    internal const val ALLOWLIST_RELOAD_MIN_MS = 500L
    internal const val ALLOWLIST_REGISTER_RETRY_MS = 1_000L
    internal const val ALLOWLIST_REGISTER_MAX_ATTEMPTS = 120
    internal const val FCM_CACHE_TTL_MS = 5L * 60L * 1000L
    internal const val FCM_CACHE_MAX = 256

    internal val STACK_WALKER: StackWalker =
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

    private var powerExemptionManager: PowerExemptionManager? = null

    /**
     * The one caller used to sit behind `SDK_INT >= S`; minSdk is 35, so
     * that branch could never be taken and its `@RequiresApi(S)` was
     * documenting nothing — both were removed, and this is now a plain
     * helper.
     */
    internal fun getPowerExemptionManager(context: Context): PowerExemptionManager {
        if (powerExemptionManager == null) {
            powerExemptionManager = PowerExemptionManager(context)
        }
        return powerExemptionManager!!
    }

    internal fun targetPackageOf(intent: Intent): String? {
        val pkg = intent.getPackage()
        if (pkg != null) {
            return pkg
        }
        val component = intent.component
        return component?.packageName
    }

    internal fun addIfAbsentInPlace(target: List<*>?, value: String) {
        if (target == null || target.contains(value)) {
            return
        }
        try {
            @Suppress("UNCHECKED_CAST")
            (target as MutableList<Any?>).add(value)
        } catch (ignored: Throwable) {
        }
    }
}
