package io.github.howard20181.hyperos.fcmlive

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
    const val ERROR_INSTANCE_ID = "Failed to query instance-id receivers"
    const val ERROR_C2DM = "Failed to query C2DM receivers"

    /**
     * The raw marker sets, one per manifest query. Kept separate rather than
     * merged into a package→flags map because the admission rule (see
     * [isFcmClient]) counts *kinds* of markers, not their sum.
     */
    class Markers(
        @JvmField val iid: Set<String>,
        @JvmField val messaging: Set<String>,
        @JvmField val receive: Set<String>,
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
        val byIidReceiver = HashSet<String>()
        val byMessagingEvent = HashSet<String>()
        val byReceive = HashSet<String>()
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
            val receivers = pm.queryBroadcastReceivers(
                Intent(HookerSupport.ACTION_INSTANCE_ID_EVENT),
                PackageManager.ResolveInfoFlags.of(0)
            )
            for (ri in receivers) {
                ri.activityInfo?.let { byIidReceiver.add(it.packageName) }
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
                if (HookerSupport.isGooglePushReceiverClass(info.name)) {
                    byGooglePushReceiver.add(info.packageName)
                }
            }
        } catch (t: Throwable) {
            failed = true
            onQueryError(ERROR_C2DM, t)
        }
        return Markers(
            byIidReceiver, byMessagingEvent, byReceive, byGooglePushReceiver, failed
        )
    }

    /**
     * The one rule, in one place: **any two of ②③④, or ② alone, ③ alone, or ⑤ alone.**
     *
     * Written out as an explicit table rather than as arithmetic on a count,
     * because the accepted shapes are not a count — "② alone" and "③ alone"
     * are each one marker while ②+③ is two, and `④ alone` has to be rejected
     * even though it is also one marker. A boolean table says that;
     * `sum >= 2 || has2 || has3` hides it.
     *
     * ⑤ — the RECEIVE receiver resolves to Google's notifications-platform
     * PushReceiver — is accepted alone: Google's own apps carry that
     * entrypoint and none of the Firebase SDK markers, and the class
     * namespace is a merged Google library that third-party apps do not
     * reproduce.
     */
    fun isFcmClient(
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
        // Everything else: ④ alone, or nothing.
        return false
    }

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
                    markers.iid.contains(pkg),          // ②
                    markers.messaging.contains(pkg),    // ③
                    markers.receive.contains(pkg),      // ④
                    markers.googleReceive.contains(pkg) // ⑤
                )
            ) {
                fcm.add(pkg)
            }
        }
        return fcm
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
