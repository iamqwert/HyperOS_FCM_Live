package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.provider.Settings
import io.github.libxposed.service.XposedService

/**
 * Root-free, hook-free "is the module actually working" readout for the settings UI.
 *
 * Before this existed, the only way to confirm the hooks were live was `adb logcat`
 * grepping the `HyperGreeze` tag (and reading `com.miui.powerkeeper`'s copy needed a
 * fresh boot, because the install summary is printed once). That is a developer's
 * procedure, not a user's — someone who just enabled the module in LSPosed and
 * rebooted has no way to tell "it is holding FCM open" from "the scope was never
 * ticked and nothing loaded". This turns that check into a first-class screen.
 *
 * Two signals, both readable by the module's *own* process with no privilege it does
 * not already have:
 *
 *  1. [XposedService.getRunningTargets] (libxposed service API 102) — the framework's
 *     authoritative list of processes this module is currently injected into, each
 *     with a [HostState]. This answers "did system_server / powerkeeper actually load
 *     the hooks, and are they running the installed version". Guarded: frameworks
 *     older than 102 throw, and an unbound service returns nothing — the card degrades
 *     to signal 2 rather than claiming "not working" just because the API is missing.
 *  2. `Settings.System.MILLET_NO_RESTRICT_APP` containing GMS — the concrete, on-disk
 *     effect of the P1 write the hook makes from system_server (keep GMS out of the
 *     Millet/greeze restrict list). Reading a Settings.System value needs no special
 *     permission, so this works even on an API-101 framework: if the key names GMS, a
 *     system_server hook ran and is maintaining it.
 *
 * Nothing here writes to the system, opens `su`, or touches the hooks. It is pure
 * observation of state the module has already caused to exist.
 */
object RuntimeStatus {

    /** The two hosts this module's scope covers (see META-INF/xposed/scope.list). */
    const val HOST_SYSTEM = "system"
    const val HOST_POWERKEEPER = "com.miui.powerkeeper"

    private const val GMS_PACKAGE = "com.google.android.gms"

    /** Written by the hook from system_server; the P1 "keep GMS unrestricted" list. */
    private const val MILLET_NO_RESTRICT_KEY = "MILLET_NO_RESTRICT_APP"

    /**
     * Snapshot produced off the main thread (both signals involve a binder IPC or a
     * Settings read) and published as immutable state to the Compose tree.
     */
    data class Snapshot(
        /** Whether the module service is currently bound (else no hook query is possible). */
        val serviceConnected: Boolean,
        /** Whether `getRunningTargets` is supported by the running framework (API 102+). */
        val targetsSupported: Boolean,
        /** The injected hosts, keyed by scope name; empty until a live list is obtained. */
        val hosts: Map<String, Host>,
        /** Signal 2: GMS is present in the Millet no-restrict list (a hook write happened). */
        val gmsUnrestricted: Boolean,
        /** Whether the user's wake allowlist is empty (only the GMS channel is helped). */
        val allowlistEmpty: Boolean,
    ) {
        data class Host(
            val present: Boolean,
            val state: HostState,
            val pid: Int,
            val loadedVersionCode: Long,
        )

        enum class HostState { UP_TO_DATE, STALE, RELOADING, FAILED, UNKNOWN }

        val systemHost: Host? get() = hosts[HOST_SYSTEM]
        val powerkeeperHost: Host? get() = hosts[HOST_POWERKEEPER]

        /** system_server is the essential host — without it FCM delivery is not helped. */
        val systemUpToDate: Boolean
            get() = systemHost?.present == true &&
                systemHost?.state == HostState.UP_TO_DATE

        /** system_server is injected but running old/failed code (needs reload/reboot). */
        val systemPendingReload: Boolean
            get() = systemHost?.present == true &&
                systemHost?.state != HostState.UP_TO_DATE

        /**
         * Green-light verdict for the headline badge. Keyed on the essential
         * system_server host being present and up to date — a missing
         * `com.miui.powerkeeper` line is shown separately and must not, on its own,
         * read as "not working" (that process can be cold, or the framework can
         * spell it differently). Falls back to the GMS-settings signal when the
         * target API is unavailable, so an API-101 framework with a live hook still
         * reads "working" rather than a false alarm.
         */
        val frameworkActive: Boolean
            get() = if (targetsSupported) serviceConnected && systemUpToDate
            else gmsUnrestricted
    }

    /**
     * Read both signals. Safe off any thread; never throws (framework errors fold into
     * `targetsSupported=false` / an empty host map so the UI shows what *is* known).
     */
    fun probe(context: Context, service: XposedService?, allowlistEmpty: Boolean): Snapshot {
        val gms = try {
            Settings.System.getString(
                context.applicationContext.contentResolver, MILLET_NO_RESTRICT_KEY
            )?.contains(GMS_PACKAGE) == true
        } catch (t: Throwable) {
            false
        }

        val hosts = LinkedHashMap<String, Snapshot.Host>()
        var targetsSupported = false
        val connected = service != null
        if (service != null) {
            try {
                for (t in service.runningTargets) {
                    targetsSupported = true
                    val key = when (t.processName) {
                        HOST_SYSTEM, "system_server" -> HOST_SYSTEM
                        HOST_POWERKEEPER -> HOST_POWERKEEPER
                        else -> continue
                    }
                    hosts[key] = Snapshot.Host(
                        present = true,
                        state = when (t.state) {
                            io.github.libxposed.service.HookedTarget.State.UP_TO_DATE ->
                                Snapshot.HostState.UP_TO_DATE
                            io.github.libxposed.service.HookedTarget.State.STALE ->
                                Snapshot.HostState.STALE
                            io.github.libxposed.service.HookedTarget.State.RELOADING ->
                                Snapshot.HostState.RELOADING
                            io.github.libxposed.service.HookedTarget.State.FAILED ->
                                Snapshot.HostState.FAILED
                        },
                        pid = t.pid,
                        loadedVersionCode = t.loadedVersionCode,
                    )
                }
            } catch (e: UnsupportedOperationException) {
                // Framework predates service API 102: no target list. Signal 2 still applies.
                targetsSupported = false
            } catch (t: Throwable) {
                targetsSupported = false
            }
        }
        return Snapshot(
            serviceConnected = connected,
            targetsSupported = targetsSupported,
            hosts = hosts,
            gmsUnrestricted = gms,
            allowlistEmpty = allowlistEmpty,
        )
    }
}
