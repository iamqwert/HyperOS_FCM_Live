package io.github.howard20181.hyperos.fcmlive

import android.net.TrafficStats
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * The GMS traffic-probe subsystem, extracted verbatim from `Hooker`
 * (zero-behaviour refactor). `Hooker`'s instance capabilities are injected at
 * the call sites: `module` is the [XposedInterface] the module entry object is,
 * `gmsUid` is `Hooker#gmsUid` as a supplier (resolved at probe time, so the uid
 * lookup keeps happening on the probe's own schedule), and `gateSummary` is
 * `Hooker#broadcastGateSummary` as a supplier — the gate counters it reads stay
 * with `Hooker`.
 */
internal object TrafficProbe {

    /**
     * Read-only probe: is GMS still exchanging traffic?
     *
     * Hooks record which gates the ROM opened; they say nothing about the outcome,
     * and the outcome is what decides whether the sleep-exit nudge is warranted at
     * all. The only connection observable reachable from the system_server domain
     * is the per-uid byte counter, so that is what this samples.
     *
     * A /proc/net/tcp pass (looking for an ESTABLISHED MCS socket) was implemented
     * and then removed. Two reasons, either one fatal on its own:
     *   - It is unreadable from the system_server SELinux domain: the file is
     *     labelled proc_net_tcp_udp and Enforcing gives system_server no read on
     *     it (adb's shell domain does — adb being able to read it proves nothing
     *     about what a hook can read). Android has also been closing /proc/net off
     *     since 10 for side-channel reasons, and every device this module targets
     *     runs far newer than that, so "it may be readable on other builds" was
     *     never a real possibility. Making it readable would mean loosening
     *     SELinux, which is off the table.
     *   - Even granted the permission it would answer the wrong question. The two
     *     ways this ROM actually starves GMS are DNS interception and firewall
     *     DROP; neither notifies the endpoint, so the socket stays ESTABLISHED
     *     and the table reports a healthy connection over a dead one. The one
     *     path that genuinely closes sockets, closeSocketForAurogon, has a
     *     measured hit rate of zero for GMS on this ROM.
     */
    internal fun startGmsTrafficProbe(
        module: XposedInterface,
        gmsUid: () -> Int?,
        gateSummary: () -> String
    ) {
        // Claim the latest chain generation before scheduling: on every hot
        // reload hookPackage re-runs and a fresh module classloader brings a
        // fresh companion, so a plain field cannot be seen by chains scheduled
        // by earlier instances. The counter therefore lives in
        // System.getProperties() — a boot-classloader object that is shared
        // across module reloads within the same host process. A stale chain
        // detects the mismatch in run() and retires instead of stacking yet
        // another parallel 30-min chain (observed overnight: three chains
        // interleaving after two reloads).
        val generation = claimTrafficProbeGeneration()
        // The schedule declaration used to be a line of its own. It rides the
        // startup report instead: same reader, same moment, and the startup line
        // is already the one naming the generation a hot-reload check looks at.
        val intervalMin = HookerSupport.GMS_TRAFFIC_PROBE_INTERVAL_MS / 60_000
        probeBackgroundHandler().post {
            probeGmsTraffic(module, gmsUid, gateSummary, "startup, read-only, every ${intervalMin}min, gen $generation")
        }
        probeBackgroundHandler().postDelayed(object : Runnable {
            override fun run() {
                if (latestTrafficProbeGeneration() != generation) {
                    module.logGated(
                        Log.INFO, HookerSupport.TAG,
                        "gms probe: chain generation $generation superseded, " +
                            "retire without rescheduling"
                    )
                    return
                }
                probeGmsTraffic(module, gmsUid, gateSummary, "periodic")
                probeBackgroundHandler().postDelayed(this, HookerSupport.GMS_TRAFFIC_PROBE_INTERVAL_MS)
            }
        }, HookerSupport.GMS_TRAFFIC_PROBE_INTERVAL_MS)
    }

    /**
     * Claims a new traffic-probe chain generation, monotonically increasing
     * per host process. Java-level [System] properties only — nothing here
     * touches android.os.SystemProperties or crosses SELinux.
     */
    private fun claimTrafficProbeGeneration(): Long {
        val props = System.getProperties()
        return synchronized(props) {
            val next = (props.getProperty(HookerSupport.TRAFFIC_PROBE_GENERATION_KEY)?.toLongOrNull() ?: 0L) + 1
            props.setProperty(HookerSupport.TRAFFIC_PROBE_GENERATION_KEY, next.toString())
            next
        }
    }

    /** Reads the latest claimed chain generation; superseded chains see a mismatch. */
    private fun latestTrafficProbeGeneration(): Long =
        System.getProperties().getProperty(HookerSupport.TRAFFIC_PROBE_GENERATION_KEY)?.toLongOrNull() ?: 0L

    internal fun probeGmsTraffic(
        module: XposedInterface,
        gmsUid: () -> Int?,
        gateSummary: () -> String,
        reason: String
    ) {
        // One line per tick. The gate counters and the traffic delta come off the
        // same timer and answer the same question — "did anything get gated, and
        // is GMS still talking" — yet this function used to print them as two
        // back-to-back lines (three when nothing was gated but a counter was
        // non-zero). The gate half is still built first, so the counters are
        // reported even on ROMs where GMS's uid cannot be resolved.
        val uid = gmsUid()
        val traffic = if (uid == null) {
            "GMS uid unresolved"
        } else {
            "uid=$uid ${trafficSinceLastProbe(uid)}"
        }
        module.logGated(Log.INFO, HookerSupport.TAG, "gms probe [$reason]: $traffic; ${gateSummary()}")
    }

    /**
     * A growing counter means GMS is still exchanging traffic, which is the
     * observable we actually need when deciding whether the nudge was warranted.
     */
    private fun trafficSinceLastProbe(uid: Int): String {
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) {
            return "traffic=unsupported"
        }
        val prevRx = lastGmsRxBytes
        val prevTx = lastGmsTxBytes
        lastGmsRxBytes = rx
        lastGmsTxBytes = tx
        if (prevRx < 0 || prevTx < 0) {
            return "rx=${rx}B tx=${tx}B (baseline)"
        }
        return "rx=+${rx - prevRx}B tx=+${tx - prevTx}B"
    }

    @Volatile
    private var probeHandler: Handler? = null

    /** Per-uid counters from the previous sample; -1 until the first read. */
    @Volatile
    private var lastGmsRxBytes = -1L
    @Volatile
    private var lastGmsTxBytes = -1L

    internal fun probeBackgroundHandler(): Handler {
        val handler = probeHandler
        if (handler != null) {
            return handler
        }
        synchronized(this) {
            if (probeHandler == null) {
                val thread = HandlerThread("fcmlive-probe")
                thread.start()
                probeHandler = Handler(thread.looper)
            }
            return probeHandler!!
        }
    }
}
