package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent

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
    const val ACTION_INSTANCE_ID_EVENT = "com.google.firebase.INSTANCE_ID_EVENT"
    const val FCM_MESSAGING_SERVICE_CLASS =
        "com.google.firebase.messaging.FirebaseMessagingService"
    const val FCM_IID_RECEIVER_CLASS =
        "com.google.firebase.iid.FirebaseInstanceIdReceiver"

    /** Marker ⑤ namespace: Google's in-house push infrastructure, GnpCore. */
    const val GOOGLE_PUSH_RECEIVER_PREFIX = "com.google.android.libraries.notifications."

    /**
     * Marker ⑤ shape: Google's own apps (Wallet, Photos, Maps…) do not ship any
     * Firebase SDK push component. Their c2dm `RECEIVE` entrypoint resolves to
     * the notifications-platform PushReceiver instead — a class name under the
     * `com.google.android.libraries.notifications.` namespace ending in
     * `PushReceiver`. Verified against Google Wallet 26.39; the namespace is a
     * merged Google library, not per-app code, so random third-party apps do
     * not carry it.
     */
    fun isGooglePushReceiverClass(className: String?): Boolean =
        className != null &&
            className.startsWith(GOOGLE_PUSH_RECEIVER_PREFIX) &&
            className.endsWith("PushReceiver")

    internal const val GMS_PACKAGE_NAME = "com.google.android.gms"
    internal const val GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent"
    internal const val WECHAT_PACKAGE_NAME = "com.tencent.mm"

    /**
     * Sleep mode's radio cutoff lives here, in the PowerKeeper process — not in
     * system_server's MiuiNetworkPolicyManagerService, which is why hooking
     * that service's sleep whitelist never fired on OS4/V816. Verified in the
     * OS3 dump as well (PowerKeeper 4.2.00): `applySleepConfig(SleepState)`
     * exists there too and drives the same `setDataEnabled` / `setWifiEnabled`
     * cutoffs, so this predicate also matches on OS3 — whether OS3's sleep
     * flow reaches it at runtime is a device question, alongside the legacy
     * per-uid chain that S16's arms cover.
     */
    internal const val SLEEP_CONTROLLER_CLASS =
        "com.miui.powerkeeper.statemachine.PhoneSleepModeController"

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

    internal fun targetPackageOf(intent: Intent): String? {
        val pkg = intent.getPackage()
        if (pkg != null) {
            return pkg
        }
        val component = intent.component
        return component?.packageName
    }
}
