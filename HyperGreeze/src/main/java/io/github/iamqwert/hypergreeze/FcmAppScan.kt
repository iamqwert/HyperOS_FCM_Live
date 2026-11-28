package io.github.iamqwert.hypergreeze

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The FCM-client scan, shared verbatim between the two processes that need it:
 * the settings app builds the 应用 page's rows with it, and — with the
 * auto-check experiment — system_server computes the same set to decide which
 * newly installed packages are FCM clients. One copy is the whole point: the
 * "which apps count as FCM applications" answer must not drift between the
 * screen that displays it and the hook that acts on it.
 *
 * Everything here rides the manifest-declared push actions, so the scan needs
 * no permission in either process: from the app the `<queries>` block makes
 * exactly the declaring packages visible, and in system_server the package
 * manager sees the same set without any visibility filtering at all.
 *
 * GMS is deliberately **not** added here. The app-side list adds it
 * unconditionally on top of the markers, and the hook side exempts it
 * unconditionally at its decision point — both for the same reason, the
 * GMS/FCM chain itself is never narrowed — so a marker scan including it would
 * only invite the two sides to disagree about whether it qualified.
 */
internal object FcmAppScan {

    /** Error labels passed to [onQueryError]; the app side logs them verbatim. */
    const val ERROR_MESSAGING = "Failed to query FCM messaging services"
    const val ERROR_INSTANCE_ID = "Failed to query instance-id services"
    const val ERROR_C2DM = "Failed to query C2DM receivers"

    /**
     * The raw marker sets, one per manifest query.
     *
     * - [iid]: packages declaring a `com.google.firebase.INSTANCE_ID_EVENT` `<service>`
     *   (legacy FirebaseInstanceIdService / third-party IID services).
     * - [messaging]: packages declaring a `com.google.firebase.MESSAGING_EVENT` `<service>`.
     * - [receive]: all packages declaring a `com.google.android.c2dm.intent.RECEIVE` `<receiver>`.
     * - [c2dmSendReceive]: subset of [receive] whose receiver requires
     *   `com.google.android.c2dm.permission.SEND` (standard GMS-authenticated FCM/GCM entrypoints,
     *   excluding unrelated legacy receivers such as `android`'s `MasterClearReceiver`).
     * - [googleReceive]: subset of [receive] resolving to Google's GnpCore `PushReceiver`.
     */
    class Markers(
        @JvmField val iid: Set<String>,
        @JvmField val messaging: Set<String>,
        @JvmField val receive: Set<String>,
        @JvmField val c2dmSendReceive: Set<String>,
        @JvmField val googleReceive: Set<String>,
        /** True when at least one query threw; the results are then partial. */
        @JvmField val failed: Boolean
    )

    /**
     * Run the three manifest queries and collect the marker sets.
     *
     * A failed query degrades to a partial answer rather than throwing: a
     * binder hiccup in one query must not blank out the markers the other two
     * answered for. Callers that need all-or-nothing semantics (the baseline
     * written when the auto-check switch turns on) read [Markers.failed].
     */
    fun queryMarkers(
        pm: PackageManager,
        onQueryError: (label: String, t: Throwable) -> Unit
    ): Markers {
        val byIidService = HashSet<String>()
        val byMessagingEvent = HashSet<String>()
        val byReceive = HashSet<String>()
        val byC2dmSendReceive = HashSet<String>()
        val byGooglePushReceiver = HashSet<String>()
        var failed = false
        try {
            val services = pm.queryIntentServices(
                Intent(HookerSupport.ACTION_MESSAGING_EVENT),
                PackageManager.ResolveInfoFlags.of(0)
            )
            for (ri in services) {
                // Services resolve into serviceInfo; activityInfo is the
                // receiver/activity field and stays null here.
                ri.serviceInfo?.let { byMessagingEvent.add(it.packageName) }
            }
        } catch (t: Throwable) {
            failed = true
            onQueryError(ERROR_MESSAGING, t)
        }
        try {
            // INSTANCE_ID_EVENT is declared on <service> (FirebaseInstanceIdService
            // and legacy wrappers), never on a BroadcastReceiver.
            val services = pm.queryIntentServices(
                Intent(HookerSupport.ACTION_INSTANCE_ID_EVENT),
                PackageManager.ResolveInfoFlags.of(0)
            )
            for (ri in services) {
                ri.serviceInfo?.let { byIidService.add(it.packageName) }
            }
        } catch (t: Throwable) {
            failed = true
            onQueryError(ERROR_INSTANCE_ID, t)
        }
        try {
            val receivers = pm.queryBroadcastReceivers(
                Intent(HookerSupport.ACTION_REMOTE_INTENT),
                PackageManager.ResolveInfoFlags.of(0)
            )
            for (ri in receivers) {
                val info = ri.activityInfo ?: continue
                byReceive.add(info.packageName)
                if (info.permission == HookerSupport.PERMISSION_C2DM_SEND) {
                    byC2dmSendReceive.add(info.packageName)
                }
                if (HookerSupport.isGooglePushReceiverClass(info.name)) {
                    byGooglePushReceiver.add(info.packageName)
                }
            }
        } catch (t: Throwable) {
            failed = true
            onQueryError(ERROR_C2DM, t)
        }
        return Markers(
            byIidService,
            byMessagingEvent,
            byReceive,
            byC2dmSendReceive,
            byGooglePushReceiver,
            failed
        )
    }

    /**
     * Admission rule:
     * - [hasIidService] (`com.google.firebase.INSTANCE_ID_EVENT` service), OR
     * - [hasMessagingEvent] (`com.google.firebase.MESSAGING_EVENT` service), OR
     * - [hasC2dmSendReceive] (`com.google.android.c2dm.intent.RECEIVE` receiver gated by
     *   `com.google.android.c2dm.permission.SEND`), OR
     * - [hasGooglePushReceiver] (`com.google.android.libraries.notifications.*PushReceiver`).
     *
     * Bare `c2dm.intent.RECEIVE` without `com.google.android.c2dm.permission.SEND` (e.g.
     * `android`'s `MasterClearReceiver`, gated by `MASTER_CLEAR`) is rejected.
     */
    fun isFcmClient(
        hasIidService: Boolean,
        hasMessagingEvent: Boolean,
        hasC2dmSendReceive: Boolean,
        hasGooglePushReceiver: Boolean
    ): Boolean =
        hasIidService || hasMessagingEvent || hasC2dmSendReceive || hasGooglePushReceiver

    /**
     * The marker-derived FCM-client set: every package the admission rule
     * accepts. No GMS special-case, no MiPush tagging — callers add what their
     * side needs (the list adds GMS unconditionally and tags MiPush; the hook
     * wants exactly this set).
     */
    fun queryFcmPackages(
        pm: PackageManager,
        onQueryError: (label: String, t: Throwable) -> Unit
    ): Set<String> {
        val markers = queryMarkers(pm, onQueryError)
        val candidates = HashSet<String>()
        candidates.addAll(markers.iid)
        candidates.addAll(markers.messaging)
        candidates.addAll(markers.receive)
        val fcm = HashSet<String>()
        for (pkg in candidates) {
            if (isFcmClient(
                    hasIidService = markers.iid.contains(pkg),
                    hasMessagingEvent = markers.messaging.contains(pkg),
                    hasC2dmSendReceive = markers.c2dmSendReceive.contains(pkg),
                    hasGooglePushReceiver = markers.googleReceive.contains(pkg)
                )
            ) {
                fcm.add(pkg)
            }
        }
        return fcm
    }

    /**
     * Single-package counterpart of [queryFcmPackages]: runs the exact same
     * three manifest queries scoped to [packageName] and evaluates them through
     * [isFcmClient], so `ProcessCleanerHooks` and the app list / auto-check
     * scan can never drift on which packages count as FCM clients.
     */
    fun declaresFcm(pm: PackageManager, packageName: String): Boolean {
        val iidIntent = Intent(HookerSupport.ACTION_INSTANCE_ID_EVENT).setPackage(packageName)
        val hasIidService = pm.queryIntentServices(iidIntent, 0).isNotEmpty()
        val msgIntent = Intent(HookerSupport.ACTION_MESSAGING_EVENT).setPackage(packageName)
        val hasMessagingEvent = pm.queryIntentServices(msgIntent, 0).isNotEmpty()
        var hasC2dmSendReceive = false
        var hasGooglePushReceiver = false
        val receiveIntent = Intent(HookerSupport.ACTION_REMOTE_INTENT).setPackage(packageName)
        for (ri in pm.queryBroadcastReceivers(receiveIntent, 0)) {
            val info = ri.activityInfo ?: continue
            if (info.permission == HookerSupport.PERMISSION_C2DM_SEND) {
                hasC2dmSendReceive = true
            }
            if (HookerSupport.isGooglePushReceiverClass(info.name)) {
                hasGooglePushReceiver = true
            }
            if (hasC2dmSendReceive && hasGooglePushReceiver) {
                break
            }
        }
        return isFcmClient(
            hasIidService = hasIidService,
            hasMessagingEvent = hasMessagingEvent,
            hasC2dmSendReceive = hasC2dmSendReceive,
            hasGooglePushReceiver = hasGooglePushReceiver
        )
    }

    /**
     * Whether `pkg` ships MiPush: it declares the SDK's push service.
     *
     * MiPush is a system-channel push, so an app that has it does not need
     * this module to keep a second (FCM) route alive — the list tags those apps
     * and the overflow menu can leave them out.
     *
     * A direct component lookup — fine here because every caller passes a
     * package the intent queries already made visible. No MiPush action is
     * declared in `<queries>`: the SDK's service often ships without an
     * intent-filter, so a query could not see it, while a component lookup
     * on a visible package can.
     *
     * The lookup has to ask for disabled components, because on MIUI /
     * HyperOS a disabled `XMPushService` means the opposite of what it
     * looks like: MiPush is not an SDK that keeps its own connection, and a
     * disabled state is the sign that MiPush is *live* for that app.
     */
    fun declaresMiPushService(pm: PackageManager, pkg: String, serviceClass: String): Boolean {
        return try {
            pm.getServiceInfo(
                ComponentName(pkg, serviceClass),
                PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
            )
            true
        } catch (ignored: Throwable) {
            // Not declared (or not visible to us): no MiPush.
            false
        }
    }
}
