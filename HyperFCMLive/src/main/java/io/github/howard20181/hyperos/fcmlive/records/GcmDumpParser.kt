package io.github.howard20181.hyperos.fcmlive.records

import java.util.Calendar

/**
 * A single ring-buffer line of GCM's service dump, already reduced to what the
 * records page shows.
 *
 * [text] is GMS's own wording, network names prettified; [kind] is our
 * classification of it. [ts] is wall-clock millis, inferred by the year — the
 * dump prints `MM-dd HH:mm:ss.SSS` without one.
 */
data class GcmEvent(
    val ts: Long,
    val net: Int,
    val kind: EventKind,
    val text: String
) {
    enum class EventKind {
        Connected,
        Login,
        Heartbeat,
        Network,
        Reconnect,
        Close,

        /**
         * A close-family line without an `err:` code — `Received Close`, a
         * bare `Disconnected`, and the policy-driven `Disconnect since …`
         * pair. GMS's binary contains all of them, but none carries a code,
         * so they stay connection-only and uncharacterised.
         */
        ServerClose,
        Doze,
        IqStanza,
        MessageReceived,
        MessageAcked,
        BroadcastCompleted,
        BroadcastFailed,
        BroadcastNoResponse,

        /**
         * The notification proxy: instead of handing the message to the app,
         * GMS built the notification itself (`cois.call()` builds a bitmap +
         * NotificationManager post) — the terminal push event for apps that
         * are not running, and the reason such a push has no broadcast line.
         */
        ProxiedNotification,

        /** The ordered broadcast came back `result=CANCELLED` — aborted. */
        BroadcastCancelled,

        /**
         * Transport-level faults GMS logs onto the same ring — writer-thread
         * I/O exceptions, an oversized read. They belong to no single stage:
         * the connection is the thing that broke.
         */
        Transport,
        Other,

        /**
         * Not from GMS: the store inserts this marker when the dump's `pid=`
         * changes, because the event ring is in-memory and starts empty after
         * every GMS restart.
         */
        GmsRestart
    }
}

/**
 * The numbers of a close event: `Close err:27 time:4` is GMS's own `err:` code
 * and the connection's age in seconds.
 *
 * The code's **name** comes from GMS's own binary ([CloseCodes.nameOf]); the
 * dump's `err:` space and FCM Diagnostics' `Last close code:` are the same.
 */
data class CloseDetail(val code: Int, val ageSeconds: Int)

/**
 * GMS's own close-code → name table, extracted from the binary that displays
 * it (`Ljjap;->a(I)`, the "Last close code:" line of FCM Diagnostics' advanced
 * view) — GMS 26.41.62. These are the official semantics; observation only
 * adds context around them. See FCM_DIAGNOSTICS_FORMATS.md §7.
 */
object CloseCodes {
    /** Old connection replaced after the active network changed. */
    const val NETWORK_SWITCHED = 27

    /** Network callback reports the object invalid; 25 lands in the same event. */
    const val NETWORK_OBJECT_INVALID = 20
    const val NETWORK_OBJECT_INVALID_ALIAS = 25

    /** No heartbeat answer in time; GMS reconnects. */
    const val HEARTBEAT_TIMEOUT = 6

    /** No network at all (`net=-1` co-occurrence, `ERR_NET_DOWN`). */
    const val NET_DOWN = 1

    private val NAMES = mapOf(
        1 to "ERR_NET_DOWN",
        3 to "ERR_UNKNOWN_HOST",
        4 to "ERR_AUTH",
        6 to "ERR_HEARTBEAT_ACK_TIMEOUT",
        10 to "ERR_UNKNOWN",
        11 to "ERR_DISABLED",
        14 to "ERR_PROTOCOL",
        15 to "ERR_SERVICE_END",
        16 to "ERR_IO_CONNECTING",
        17 to "ERR_MANUAL",
        18 to "ERR_REMOTE_CLOSE",
        19 to "ERR_IO_RST",
        20 to "ERR_IO_FIN",
        21 to "ERR_IO_WRITE",
        22 to "ERR_UNREACHABLE_HOST",
        23 to "ERR_UNREACHABLE_PORT",
        24 to "ERR_CONNECTION_TIMEOUT",
        25 to "ERR_CONNECTION_REFUSED_REMOTELY",
        26 to "ERR_SSL_EXCEPTION",
        27 to "ERR_CLOSE_BY_NEW_CONNECTION",
        28 to "ERR_IO_RST_HB",
        29 to "ERR_MCS_RECONNECT_REQUEST",
        30 to "ERR_CLOSE_BY_USER_UNLOCKED",
        31 to "ERR_MISSING_CHECKIN_CREDENTIALS",
        32 to "ERR_SERVER_NOT_AUTHORIZED",
        33 to "ERR_SERVER_NOT_ALLOWED",
        34 to "ERR_SERVER_INTERNAL_ERROR",
        35 to "ERR_FAILED_PROBE",
        36 to "ERR_IO_WHILE_HANDLING_MESSAGE"
    )

    /** The official `ERR_*` name, or null when the code is outside GMS's table. */
    fun nameOf(code: Int): String? = NAMES[code]

    /** True for a code GMS itself names; false means the table has no entry. */
    fun isKnown(code: Int): Boolean = NAMES.containsKey(code)
}

/**
 * GCM's connection numbers, the snapshot half of the records page.
 *
 * Only what the page shows is kept, plus the small per-transport tables and
 * health readings the page now has rows for (see the `*ByType` / `last*` /
 * `*Delay` fields). The app queue and affinity lists are kept as package names
 * only — those lists run to dozens of lines, and a reader wants to know which
 * apps, not each entry's opaque `v<n>` value. Message content is not part of
 * the dump at all, and the dump's `DeviceID:` line is deliberately never parsed.
 *
 * Every field added after the first release is optional: GMS prints these lines
 * conditionally (a healthy connection prints no close code, no ping, no reset),
 * so "absent" has to stay distinguishable from "zero".
 */
data class GcmSnapshot(
    val pid: Int,
    val connected: Boolean,
    /** `mtalk.google.com:5228` — the IP in the dump is dropped on purpose. */
    val endpoint: String,
    val streamId: String,
    val connects: Int,
    val failedLogins: Int,
    val connectTime: String,
    /** The clock time of the next heartbeat alarm (`02:00`), empty when absent. */
    val heartbeatAlarm: String,
    /** The interval that connection started from, in seconds (`360`); empty when absent. */
    val heartbeatAlarmInitialS: String,
    /** Last connection's adaptive-heartbeat state; null when the dump has none. */
    val heartbeat: HeartbeatState?,
    val reconnectAlarm: String,
    val globalBackoff: String,
    val network: NetworkStatus,
    val queuedClients: Int,
    val affinityClients: Int,
    /** `High priority quota usage:` body, empty when the dump lists none. */
    val highPriorityQuota: String,
    /**
     * Downlink stanzas GMS accepted but that never reached the app — the
     * `Queued messages:` block at the dump's end (`reason=… retries=…`).
     * -1 when the dump has no such block at all.
     */
    val pendingMessages: Int,
    /* ---- link detail (2026-10-24, §10.2 of FCM_DIAGNOSTICS_FORMATS.md) ---- */

    /** The dump printed `Connecting` in place of the Client block. */
    val connecting: Boolean = false,
    /** `lastConnectionDurationS=` — the previous connection's lifetime; null when absent. */
    val lastConnectionDurationS: Int? = null,
    /** `disconnectTime=` — when the previous connection dropped; empty when absent. */
    val disconnectTime: String = "",
    /** `Fallback mode:` enum name; empty when absent. */
    val fallbackMode: String = "",
    /** `Adaptive Heartbeat type N: <bool>`, one row per transport the dump names. */
    val adaptiveHeartbeatByType: List<TypeFlag> = emptyList(),
    /** `connectionsLimit:` paired with the transport line above it. */
    val connectionsLimitByType: List<TypeCount> = emptyList(),
    /** `Heartbeat waiting ack` — a heartbeat went out and none came back. */
    val heartbeatAwaitingAck: Boolean = false,
    /** `Last ping:` value; empty when absent. */
    val lastPing: String = "",
    /** `Last heartbeat reset connection <elapsed> ago`'s elapsed text; empty when absent. */
    val lastHeartbeatReset: String = "",
    /** `Seen good heartbeat in last connection?`; null when the dump has no such line. */
    val seenGoodHeartbeat: Boolean? = null,
    /** `Reads within / that exceeded the wakelock budget`; null when the dump omits them. */
    val wakelockReadsWithin: Int? = null,
    val wakelockReadsExceeded: Int? = null,
    /** `Total Delay:` breakdown of one delivery; null when the dump prints none. */
    val totalDelay: TotalDelay? = null,
    /** `Apps supporting client queue:` packages, in dump order. */
    val queuedPackages: List<String> = emptyList(),
    /** `Affinity Scores:` entries, highest score first. */
    val affinityScores: List<AffinityScore> = emptyList(),
    /** `Next backoff interval per network:` entries, in dump order. */
    val networkBackoffs: List<NetworkBackoff> = emptyList(),
    /** Transport type id to the name the dump gives it (`Cell`, `WiFi`). */
    val networkTypeNames: Map<Int, String> = emptyMap()
)

/** One `Adaptive Heartbeat type N: <bool>` line. */
data class TypeFlag(val type: Int, val enabled: Boolean)

/** One `connectionsLimit:` line, owned by the transport type named above it. */
data class TypeCount(val type: Int, val value: Int)

/** `Total Delay: …ms (Server-to-Client: …ms, Pre-Broadcast Queue: …ms, App Broadcast: …ms, Result: …)`. */
data class TotalDelay(
    val totalMs: Int,
    val serverToClientMs: Int,
    val queueMs: Int,
    val broadcastMs: Int,
    val result: Int
)

/** One `Affinity Scores:` line — the package and the score GMS gave it. */
data class AffinityScore(val packageName: String, val score: Double)

/** One `Next backoff interval per network:` line. */
data class NetworkBackoff(val network: String, val duration: String)

data class HeartbeatState(
    val intervalMs: Int,
    val phase: Int,
    val good: Int,
    val bad: Int
)

data class NetworkStatus(
    /** Prettified, e.g. `WiFi 102`; empty when the dump prints none. */
    val active: String,
    val preferred: String,
    val inUse: String,
    val available: List<String>
)

/** One downlink stanza GMS accepted for an app. */
data class MessageRef(val packageName: String, val messageId: String)

/** A completed broadcast line: id, measured duration and priority. */
data class BroadcastRef(
    val packageName: String,
    val messageId: String,
    val durationMs: Int,
    val priority: String
)

/** Parsed dump: the snapshot plus every event line still in the ring. */
data class GcmDump(
    val snapshot: GcmSnapshot,
    val events: List<GcmEvent>
)

/**
 * Parses `dumpsys activity service com.google.android.gms/.gcm.GcmService`.
 *
 * The dump is a stable, human-readable listing (verified on GMS 26.41.62 /
 * HyperOS 4.0.4.0): a `Client:` block, network status, then the event ring —
 * `MM-dd HH:mm:ss.SSS net=N: <text>` lines — followed by the app queue and
 * affinity blocks. Every lookup is defensive: a section a future GMS renames
 * simply renders as "—" instead of failing the whole page.
 *
 * `DeviceID:` is never touched. The dump carries no message content anywhere;
 * message bodies only exist in FCM Diagnostics' own in-memory store, which no
 * `dumpsys` exposes.
 */
object GcmDumpParser {

    private val HEADER = Regex("""SERVICE com\.google\.android\.gms/\.gcm\.GcmService\s+\S+\s+pid=(\d+)""")
    private val EVENT = Regex(
        """^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+net=(-?\d+):\s?(.*)$"""
    )
    /** The closing brace must be escaped: ICU's regex rejects a bare `}`. */
    private val GCM_NETWORK = Regex("""GcmNetwork\{(\d+) (\w+)\((\d+)\)\}""")

    /**
     * An `McsConnection` wrapper around a network. GMS nests the network two
     * ways — `McsConnection{GcmNetwork{100 Cell(0)}, type=0}` on HyperOS 4
     * (verified), the flat `McsConnection{Cell 100, type=0}` elsewhere — and
     * may append flag tokens. Both inner shapes resolve to one transport name.
     */
    private val MCS_CONNECTION = Regex(
        """McsConnection\{((?:GcmNetwork\{[^{}]*\}|[^{}]*?)),\s*type=(-?\d+)(?:,\s*([^{}]*))?\}"""
    )

    /** The flat inner shape `Cell 100` (transport, then netId). */
    private val NETWORK_PAIR = Regex("""(\w+)\s+(\d+)""")
    private val ENDPOINT = Regex("""connected=([^,]+),port=(\d+)""")
    private val STREAM = Regex("""streamId=([^,]+)""")
    private val CONNECTS = Regex("""connects=(\d+)""")
    private val FAILED_LOGINS = Regex("""failedLogins=(\d+)""")
    private val CONNECT_TIME = Regex("""connectTime=([^\s]+)""")
    private val ALARM = Regex("""alarm\(([^ )]+)[^)]*?initial:\s*(\d+)s\)""")
    /** Same line, with the transport id kept: the dump prints one per transport. */
    private val ADAPTIVE_TYPED = Regex("""Adaptive Heartbeat type (\d+):\s*(true|false)""")
    private val CONNECTIONS_LIMIT = Regex("""connectionsLimit:\s*(\d+)""")
    private val LAST_DURATION = Regex("""lastConnectionDurationS=(-?\d+)""")
    private val DISCONNECT_TIME = Regex("""disconnectTime=([^\s,]+)""")

    /**
     * The link-health lines of the Client block. All of them are printed only
     * when there is something to say — a settled connection prints none — so a
     * miss here means "not reported", never "fine".
     */
    private val AWAITING_ACK = Regex("""^Heartbeat waiting ack\b""")
    private val LAST_PING = Regex("""^Last ping:\s*(.+)$""")
    private val HEARTBEAT_RESET = Regex("""^Last heartbeat reset connection (.+) ago$""")
    private val SEEN_GOOD = Regex("""^Seen good heartbeat in last connection\?\s*(true|false)""")
    private val WAKELOCK_WITHIN = Regex("""Reads within the wakelock budget:\s*(\d+)""")
    private val WAKELOCK_EXCEEDED = Regex("""Reads that exceeded the wakelock budget:\s*(\d+)""")
    private val TOTAL_DELAY = Regex(
        """Total Delay:\s*(-?\d+)ms \(Server-to-Client:\s*(-?\d+)ms, """ +
            """Pre-Broadcast Queue:\s*(-?\d+)ms, App Broadcast:\s*(-?\d+)ms, Result:\s*(-?\d+)\)"""
    )
    /** `Connecting` alone stands in for the whole Client block while dialling. */
    private val CONNECTING_LINE = Regex("""^Connecting$""")
    private val HB_INTERVAL = Regex("""heartbeat_interval:\s*(\d+)""")
    private val HB_PHASE = Regex("""phase:\s*(\d+)""")
    private val HB_GOOD = Regex("""good_heartbeat_count:\s*(\d+)""")
    private val HB_BAD = Regex("""bad_heartbeat_count:\s*(\d+)""")
    private val CLOSE_CODE = Regex("""err:(-?\d+)""")
    private val CLOSE_AGE = Regex("""time:(-?\d+)""")
    private val NET_SWITCHED = Regex("""Active network switched to (.+?), was (.+)""")
    private val NET_NOW = Regex("""Active network is now (.+)""")
    private val NET_CHANGED = Regex("""onGcmNetworkChanged: (.+)""")
    private val QUEUE_ENTRY = Regex("""^[\w.]+:\d+\s+v\d+$""")
    private val AFFINITY_ENTRY = Regex("""^[\w.]+:\d+\s+[\d.]+$""")
    /** Every queued-message entry carries `reason=<n>`; the block's other line is a pointer sentence. */
    private val QUEUED_MESSAGE_ENTRY = Regex("""reason=\d+""")
    private val HB_ALARM_DELAY = Regex("""^Heartbeat alarm delay: (-?\d+) ms$""")
    private val HB_ALARM_EARLY = Regex("""^Heartbeat alarm fired early: (.+)$""")
    private val RECONNECT_ALARM_DELAY = Regex("""^Reconnect alarm delay: (-?\d+) ms$""")
    private val FAILED_CONNECTION_ERR = Regex("""^Failed connection err:(-?\d+)$""")
    private val FAILED_CONNECT_HOSTS = Regex("""^Failed to connect to (.+?), trying (.+)$""")
    private val PARALLEL_SWITCH = Regex("""^Starting parallel (.+?) in place of existing (.+)$""")
    private val CONNECTING_USING = Regex("""^Connecting using (.+)$""")

    /**
     * Message/broadcast lines. The `\d+:` in the id is what keeps
     * `Received IqStanza 1` out: its second token carries no message-id
     * prefix, and its meaning is not documented anywhere this module can cite.
     */
    private val RECEIVED_MESSAGE = Regex("""^Received ([\w.]+) (\d+:\S+)$""")
    private val ACKED_MESSAGE = Regex("""^Acked ([\w.]+):\d+ (\d+:\S+)$""")
    private val BROADCAST_COMPLETED =
        Regex("""^Successful broadcast to ([\w.]+) \(id=(\d+:\S+) time=(\d+)ms priority=(\w+)\)$""")
    /** `… priority=HIGH)` — the tail every broadcast line carries. */
    private val BROADCAST_PRIORITY = Regex("""priority=(\w+)""")

    private val BROADCAST_STOPPED = Regex("""^Failed to broadcast to stopped app ([\w.]+)""")
    private val BROADCAST_NO_RESPONSE = Regex("""^No response to broadcast from ([\w.]+)""")

    /**
     * The rest of GMS's broadcast-failure family — all four carry the same
     * `(id=… time=… priority=…)` tail as the stopped-app line, only the head
     * differs. Enumerated from the binary's event strings (`GcmChimeraService.b`
     * callers), not guessed: uninstalled, mid-update, a non-zero result code,
     * and a missing Intent package.
     */
    private val BROADCAST_UNINSTALLED = Regex("""^Failed to broadcast to uninstalled app ([\w.]+)""")
    private val BROADCAST_UPDATING = Regex("""^Failed to broadcast to ([\w.]+), it may be updating""")
    private val BROADCAST_ERROR_CODE = Regex("""^([\w.]+) returned error code=(-?\d+) to broadcast""")
    private val BROADCAST_NO_PACKAGE = Regex("""^Broadcast sent to ([\w.]+) missing Intent package""")

    /** `Proxied notification for <pkg> (id=<msgid>)` — the app GMS stood in for. */
    private val PROXIED_NOTIFICATION = Regex("""^Proxied notification for ([\w.]+)""")

    /** `broadcast intent callback: result=CANCELLED for<pkg>` — the aborted broadcast. */
    private val BROADCAST_CANCELLED = Regex("""^broadcast intent callback: result=CANCELLED for([\w.]+)""")

    /** `Alarm <name> fired %d ms late` — the heartbeat alarm overslept. */
    private val ALARM_LATE = Regex("""^Alarm .+ fired (-?\d+) ms late$""")

    /** `Read took too long, took <%d> ms to read and parse <%d> bytes`. */
    private val READ_TOO_LONG = Regex("""^Read took too long, took (\d+) ms to read and parse (\d+) bytes$""")

    /** `Force releasing GOOGLE_C2DM lock after %d ms`. */
    private val LOCK_RELEASE = Regex("""^Force releasing GOOGLE_C2DM lock after (-?\d+) ms$""")

    fun parse(dump: String): GcmDump? {
        val lines = dump.lines()
        val header = lines.firstOrNull { HEADER.containsMatchIn(it) } ?: return null
        val pid = HEADER.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: -1

        val client = lines.takeWhile { !it.trim().startsWith("Reconnect Scheduler Alarm") }
        val all = client.joinToString("\n")

        // The per-transport tables are paired by position, so they are read in
        // one pass over the Client block rather than by regex over the whole
        // dump: `Adaptive Heartbeat type N` is followed by that transport's
        // `connectionsLimit`, and repeating the pair is how GMS lists them.
        val typed = parseTypedHeartbeat(client)
        val snapshot = GcmSnapshot(
            pid = pid,
            connected = lines.firstOrNull { it.trim().startsWith("Is client connected:") }
                ?.substringAfter(':')?.trim() == "true",
            endpoint = ENDPOINT.find(all)?.let {
                // `connected=mtalk.google.com/74.125.x.y,port=5228` — the IP is
                // dropped, only the host and port are shown.
                "${it.groupValues[1].substringBefore('/').trim()}:${it.groupValues[2]}"
            } ?: "",
            streamId = STREAM.find(all)?.groupValues?.get(1)?.trim() ?: "",
            connects = CONNECTS.find(all)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            failedLogins = FAILED_LOGINS.find(all)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            connectTime = CONNECT_TIME.find(all)?.groupValues?.get(1)?.substringBefore('/') ?: "",
            heartbeatAlarm = ALARM.find(all)?.groupValues?.get(1) ?: "",
            // `alarm(02:00 initial: 360s)` — the clock time the next alarm
            // fires at, and the interval this connection started from. Kept as
            // two raw values: the sentence around them is the page's job.
            heartbeatAlarmInitialS = ALARM.find(all)?.groupValues?.get(2) ?: "",
            heartbeat = parseHeartbeat(lines),
            reconnectAlarm = lines.firstOrNull { it.trim().startsWith("Reconnect Scheduler Alarm:") }
                ?.substringAfter(':')?.trim() ?: "",
            globalBackoff = lines.firstOrNull { it.trim().startsWith("Global backoff:") }
                ?.substringAfter(':')?.trim() ?: "",
            network = parseNetworkStatus(lines),
            queuedClients = countBlock(lines, "Apps supporting client queue:") { QUEUE_ENTRY.matches(it.trim()) },
            affinityClients = countBlock(lines, "Affinity Scores:") { AFFINITY_ENTRY.matches(it.trim()) },
            highPriorityQuota = block(lines, "High priority quota usage:"),
            pendingMessages = countQueuedMessages(lines),
            connecting = lines.any { CONNECTING_LINE.matches(it.trim()) },
            lastConnectionDurationS = LAST_DURATION.find(dump)
                ?.groupValues?.get(1)?.toIntOrNull(),
            disconnectTime = DISCONNECT_TIME.find(dump)?.groupValues?.get(1) ?: "",
            fallbackMode = lines.firstOrNull { it.trim().startsWith("Fallback mode:") }
                ?.substringAfter(':')?.trim() ?: "",
            adaptiveHeartbeatByType = typed.first,
            connectionsLimitByType = typed.second,
            heartbeatAwaitingAck = lines.any { AWAITING_ACK.containsMatchIn(it.trim()) },
            lastPing = lines.firstNotNullOfOrNull { LAST_PING.find(it.trim())?.groupValues?.get(1) }
                ?: "",
            lastHeartbeatReset = lines.firstNotNullOfOrNull {
                HEARTBEAT_RESET.find(it.trim())?.groupValues?.get(1)
            } ?: "",
            // Line-anchored, so it has to be searched line by line: `^` in a
            // Kotlin regex does not match at every `\n` of a joined block.
            seenGoodHeartbeat = lines.firstNotNullOfOrNull {
                SEEN_GOOD.find(it.trim())?.groupValues?.get(1)
            }?.let { it == "true" },
            wakelockReadsWithin = WAKELOCK_WITHIN.find(dump)?.groupValues?.get(1)?.toIntOrNull(),
            wakelockReadsExceeded = WAKELOCK_EXCEEDED.find(dump)?.groupValues?.get(1)?.toIntOrNull(),
            totalDelay = TOTAL_DELAY.find(dump)?.let {
                TotalDelay(
                    totalMs = it.groupValues[1].toIntOrNull() ?: 0,
                    serverToClientMs = it.groupValues[2].toIntOrNull() ?: 0,
                    queueMs = it.groupValues[3].toIntOrNull() ?: 0,
                    broadcastMs = it.groupValues[4].toIntOrNull() ?: 0,
                    result = it.groupValues[5].toIntOrNull() ?: 0
                )
            },
            queuedPackages = blockEntries(lines, "Apps supporting client queue:", QUEUE_ENTRY)
                .map { it.substringBefore(':') },
            affinityScores = blockEntries(lines, "Affinity Scores:", AFFINITY_ENTRY)
                .mapNotNull { entry ->
                    val score = entry.substringAfterLast(' ').trim().toDoubleOrNull()
                    if (score == null) null else AffinityScore(entry.substringBefore(':'), score)
                }
                .sortedByDescending { it.score },
            networkBackoffs = parseNetworkBackoffs(lines),
            networkTypeNames = networkTypeNames(lines)
        )

        return GcmDump(snapshot = snapshot, events = parseEvents(lines))
    }

    /** Parses one event line; null when it is not one. */
    fun parseEvent(line: String): GcmEvent? {
        val match = EVENT.find(line.trim()) ?: return null
        val (month, day, hour, minute, second, millis, net, text) = match.destructured
        val ts = timestamp(
            month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt(), millis.toInt()
        ) ?: return null
        val pretty = prettifyEventText(text.trim())
        return GcmEvent(ts = ts, net = net.toInt(), kind = classify(pretty), text = pretty)
    }

    /**
     * `MM-dd` carries no year. Parse as the current year, and treat a date
     * more than a month ahead as last year's — a dump taken on New Year's Day
     * still holds December's lines.
     */
    private fun timestamp(m: Int, d: Int, h: Int, min: Int, s: Int, ms: Int): Long? {
        return try {
            val cal = Calendar.getInstance()
            val now = cal.timeInMillis
            cal.set(Calendar.MONTH, m - 1)
            cal.set(Calendar.DAY_OF_MONTH, d)
            cal.set(Calendar.HOUR_OF_DAY, h)
            cal.set(Calendar.MINUTE, min)
            cal.set(Calendar.SECOND, s)
            cal.set(Calendar.MILLISECOND, ms)
            var ts = cal.timeInMillis
            if (ts > now + 30L * 24 * 60 * 60 * 1000) {
                cal.add(Calendar.YEAR, -1)
                ts = cal.timeInMillis
            }
            ts
        } catch (_: Throwable) {
            null
        }
    }

    private fun classify(text: String): GcmEvent.EventKind = when {
        text == "Connected" -> GcmEvent.EventKind.Connected
        // `Heartbeat timeout, GCM connection reset` is the alarm side of the
        // same failure a following `Close err:6` reports; keep it in the
        // heartbeat kind so the taxonomy can cross-label it as an anomaly.
        text.startsWith("Heartbeat timeout, GCM connection reset") ->
            GcmEvent.EventKind.Heartbeat
        text.startsWith("Sent LoginRequest") || text.startsWith("Login error ") ||
            text.startsWith("Login failed on server") ||
            text.startsWith("Missing Android ID or token") ||
            text.startsWith("Invalid appId") ||
            text.startsWith("Got new GCM ID/token") ||
            text.startsWith("Sending IID ") ||
            text.startsWith("Received message before LoginResponse") ->
            GcmEvent.EventKind.Login
        text.startsWith("Received Server HB") || text.startsWith("Sent Server HB Ack") ||
            text.startsWith("Sent Client HB") || text.startsWith("Received Client HB Ack") ||
            text.startsWith("Heartbeat alarm delay") || text.startsWith("Alarm ") ->
            GcmEvent.EventKind.Heartbeat
        text.startsWith("Close err:") -> GcmEvent.EventKind.Close
        text == "Received Close" || text == "Sent Close" || text == "Disconnected" ||
            text.startsWith("Disconnect since") ||
            text.startsWith("Closing because of a remote restart") ->
            GcmEvent.EventKind.ServerClose
        text.startsWith("Reconnect alarm") || text.startsWith("Global backoff") ||
            text.startsWith("Reconnect Scheduler Alarm") || text.startsWith("Starting parallel") ||
            text.startsWith("Connecting using") || text.startsWith("Failed connection err") ||
            text.startsWith("Failed to connect to") ||
            text.startsWith("Skipping connect attempt") ||
            text.startsWith("No addresses for host") ->
            GcmEvent.EventKind.Reconnect
        text.startsWith("Active network") || text.startsWith("onGcmNetworkChanged") ->
            GcmEvent.EventKind.Network
        text.startsWith("Client Entering doze") || text.startsWith("Client Exiting doze") ||
            text.startsWith("Sending enter doze bit") || text.startsWith("Force release") ||
            text.startsWith("Force releasing") ->
            GcmEvent.EventKind.Doze
        text.startsWith("Sent IqStanza") || text.startsWith("Received IqStanza") ->
            GcmEvent.EventKind.IqStanza
        text.startsWith("Successful broadcast to ") -> GcmEvent.EventKind.BroadcastCompleted
        text.startsWith("Proxied notification for ") -> GcmEvent.EventKind.ProxiedNotification
        text.startsWith("broadcast intent callback: result=CANCELLED for") ->
            GcmEvent.EventKind.BroadcastCancelled
        text.startsWith("Failed to broadcast to ") ||
            text.startsWith("Broadcast sent to ") ||
            BROADCAST_ERROR_CODE.containsMatchIn(text) -> GcmEvent.EventKind.BroadcastFailed
        text.startsWith("No response to broadcast from ") -> GcmEvent.EventKind.BroadcastNoResponse
        text.startsWith("IOException in writerThread") ||
            text.startsWith("IOException while handling message") ||
            text.startsWith("Unexpected error in writerThread") ||
            text.startsWith("Read took too long") -> GcmEvent.EventKind.Transport
        // Any other `Sent <stanza>` the writer emits: the concrete forms above
        // (IqStanza / Close / LoginRequest / HB) are already consumed, so what
        // reaches here is a protocol stanza this build does not name.
        text.startsWith("Sent ") -> GcmEvent.EventKind.IqStanza
        ACKED_MESSAGE.matches(text) -> GcmEvent.EventKind.MessageAcked
        RECEIVED_MESSAGE.matches(text) -> GcmEvent.EventKind.MessageReceived
        else -> GcmEvent.EventKind.Other
    }

    /**
     * The same line-kind mapping for text that did not arrive through a fresh
     * parse — the store keeps the raw text, and the taxonomy re-derives the
     * stage from it so archives written by older builds classify correctly.
     */
    internal fun classifyText(text: String): GcmEvent.EventKind = classify(text)

    /** `Received <pkg> <msgid>` fields, or null when [text] is not one. */
    fun parseMessageReceived(text: String): MessageRef? {
        val match = RECEIVED_MESSAGE.find(text) ?: return null
        return MessageRef(match.groupValues[1], match.groupValues[2])
    }

    /** `Acked <pkg>:N <msgid>` fields, or null when [text] is not one. */
    fun parseMessageAcked(text: String): MessageRef? {
        val match = ACKED_MESSAGE.find(text) ?: return null
        return MessageRef(match.groupValues[1], match.groupValues[2])
    }

    /** `Successful broadcast to ...` fields, or null when [text] is not one. */
    fun parseBroadcastCompleted(text: String): BroadcastRef? {
        val match = BROADCAST_COMPLETED.find(text) ?: return null
        return BroadcastRef(
            packageName = match.groupValues[1],
            messageId = match.groupValues[2],
            durationMs = match.groupValues[3].toIntOrNull() ?: 0,
            priority = match.groupValues[4]
        )
    }

    /** The target package of a failed / unanswered broadcast, when the line names one. */
    fun parseBroadcastTarget(text: String): String? =
        BROADCAST_STOPPED.find(text)?.groupValues?.get(1)
            ?: BROADCAST_NO_RESPONSE.find(text)?.groupValues?.get(1)
            ?: BROADCAST_UNINSTALLED.find(text)?.groupValues?.get(1)
            ?: BROADCAST_UPDATING.find(text)?.groupValues?.get(1)
            ?: BROADCAST_ERROR_CODE.find(text)?.groupValues?.get(1)
            ?: BROADCAST_NO_PACKAGE.find(text)?.groupValues?.get(1)

    /** `priority=HIGH` on a broadcast line — `HIGH` / `NORMAL` as GMS prints them. */
    fun parseBroadcastPriority(text: String): String? =
        BROADCAST_PRIORITY.find(text)?.groupValues?.get(1)

    /** The app a proxied notification stood in for. */
    fun parseProxiedTarget(text: String): String? =
        PROXIED_NOTIFICATION.find(text)?.groupValues?.get(1)

    /** The app whose ordered broadcast came back cancelled. */
    fun parseCancelledTarget(text: String): String? =
        BROADCAST_CANCELLED.find(text)?.groupValues?.get(1)

    /** `<pkg> returned error code=%d to broadcast` — the receiver's own result code. */
    fun parseBroadcastErrorCode(text: String): Int? =
        BROADCAST_ERROR_CODE.find(text)?.groupValues?.get(2)?.toIntOrNull()

    /** `Alarm <name> fired %d ms late` — how far the alarm overslept. */
    fun parseAlarmLate(text: String): Long? =
        ALARM_LATE.find(text)?.groupValues?.get(1)?.toLongOrNull()

    /** `Read took too long…` — elapsed millis and bytes, as GMS reports them. */
    fun parseReadTooLong(text: String): Pair<Long, Long>? =
        READ_TOO_LONG.find(text)?.let {
            val ms = it.groupValues[1].toLongOrNull() ?: return null
            val bytes = it.groupValues[2].toLongOrNull() ?: return null
            ms to bytes
        }

    /** `Force releasing GOOGLE_C2DM lock after %d ms` — the wakelock timeout. */
    fun parseLockReleaseDelay(text: String): Long? =
        LOCK_RELEASE.find(text)?.groupValues?.get(1)?.toLongOrNull()

    /** `Close err:27 time:4` numbers, or null when [text] is not a close. */
    fun parseClose(text: String): CloseDetail? {
        val code = CLOSE_CODE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val age = CLOSE_AGE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return CloseDetail(code, age)
    }

    private fun parseHeartbeat(lines: List<String>): HeartbeatState? {
        // The dump repeats an algorithm block per stored connection and one
        // more under `Last connected:` — the last occurrence is the live one.
        val block = lines.lastOrNull { it.trim().startsWith("HB algorithm:") } ?: return null
        return HeartbeatState(
            intervalMs = HB_INTERVAL.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            phase = HB_PHASE.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            good = HB_GOOD.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            bad = HB_BAD.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        )
    }

    private fun parseNetworkStatus(lines: List<String>): NetworkStatus {
        val activeIndex = lines.indexOfFirst { it.trim() == "Network Status:" }
        val block = if (activeIndex >= 0) lines.drop(activeIndex + 1) else emptyList()
        fun valueAfter(prefix: String): String {
            val line = block.firstOrNull { it.trim().startsWith(prefix) } ?: return ""
            return prettifyNetworks(line.trim().substringAfter(prefix).trim())
        }

        val availableIndex = lines.indexOfFirst { it.trim() == "available:" }
        val available = mutableListOf<String>()
        if (availableIndex >= 0) {
            for (line in lines.drop(availableIndex + 1)) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) break
                available += prettifyNetworks(trimmed)
            }
        }
        return NetworkStatus(
            active = valueAfter("Active:"),
            preferred = valueAfter("Preferred:"),
            inUse = valueAfter("In-use by client:"),
            available = available
        )
    }

    private fun countBlock(lines: List<String>, header: String, predicate: (String) -> Boolean): Int {
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) return 0
        return lines.drop(start + 1).takeWhile { it.trim().isNotEmpty() }.count(predicate)
    }

    /** The block's entries that match [entry], left as raw lines. */
    private fun blockEntries(lines: List<String>, header: String, entry: Regex): List<String> {
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) return emptyList()
        return lines.drop(start + 1)
            .takeWhile { it.trim().isNotEmpty() }
            .map { it.trim() }
            .filter(entry::matches)
    }

    /**
     * `Adaptive Heartbeat type N: <bool>` and the `connectionsLimit:` under it,
     * paired by position — GMS repeats the two-line pair once per transport, so
     * the limit belongs to the type named just above it. Reading them by regex
     * over the whole block would take the first of each and silently drop the
     * second transport, which is exactly the cellular-versus-WiFi difference
     * the page wants to show.
     */
    private fun parseTypedHeartbeat(lines: List<String>): Pair<List<TypeFlag>, List<TypeCount>> {
        val flags = mutableListOf<TypeFlag>()
        val limits = mutableListOf<TypeCount>()
        var type: Int? = null
        for (line in lines) {
            val trimmed = line.trim()
            val adaptive = ADAPTIVE_TYPED.find(trimmed)
            if (adaptive != null) {
                val id = adaptive.groupValues[1].toIntOrNull()
                if (id != null) {
                    type = id
                    flags += TypeFlag(id, adaptive.groupValues[2] == "true")
                }
                continue
            }
            val limit = CONNECTIONS_LIMIT.find(trimmed)
            if (limit != null) {
                val value = limit.groupValues[1].toIntOrNull()
                val owner = type
                if (value != null && owner != null) limits += TypeCount(owner, value)
            }
        }
        return flags to limits
    }

    /**
     * `Next backoff interval per network:` — one `<network>, <duration>` line
     * each. The duration is `java.time.Duration.toString()` (`PT12S`); the page
     * formats it, this keeps GMS's own text.
     */
    private fun parseNetworkBackoffs(lines: List<String>): List<NetworkBackoff> {
        val start = lines.indexOfFirst { it.trim() == "Next backoff interval per network:" }
        if (start < 0) return emptyList()
        return lines.drop(start + 1)
            .takeWhile { it.trim().isNotEmpty() }
            .map { it.trim() }
            .mapNotNull { line ->
                val network = line.substringBeforeLast(',').trim()
                val duration = line.substringAfterLast(',', "").trim()
                if (network.isEmpty() || duration.isEmpty()) {
                    null
                } else {
                    NetworkBackoff(prettifyNetworks(network), duration)
                }
            }
    }

    /**
     * The transport names the dump itself prints — `GcmNetwork{102 WiFi(1)}`
     * says type 1 is `WiFi`. Learning them here is what lets the per-type rows
     * read `Cell 关闭，WiFi 开启` instead of `type 0 / type 1`.
     */
    private fun networkTypeNames(lines: List<String>): Map<Int, String> {
        val names = mutableMapOf<Int, String>()
        for (line in lines) {
            for (match in GCM_NETWORK.findAll(line)) {
                val type = match.groupValues[3].toIntOrNull() ?: continue
                names[type] = match.groupValues[2]
            }
        }
        return names
    }

    /**
     * The `Queued messages:` block, counting only lines that carry a `reason=`
     * — every real entry does, and the block's other known line is GMS's
     * `For scheduler stats see SchedulerService dump.` pointer, which is not a
     * pending message (counting it made an idle queue read "1 条" forever).
     * -1 means the dump printed no such block at all.
     */
    private fun countQueuedMessages(lines: List<String>): Int {
        val start = lines.indexOfFirst { it.trim().startsWith("Queued messages") }
        if (start < 0) return -1
        return lines.drop(start + 1)
            .takeWhile { it.trim().isNotEmpty() }
            .count { QUEUED_MESSAGE_ENTRY.containsMatchIn(it) }
    }

    private fun block(lines: List<String>, header: String): String {
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) return ""
        return lines.drop(start + 1).takeWhile { it.trim().isNotEmpty() }
            .joinToString("\n") { it.trim() }
    }

    private fun parseEvents(lines: List<String>): List<GcmEvent> =
        lines.mapNotNull(::parseEvent).distinctBy { it.ts to it.text }

    /**
     * The network a network-change event line names as its subject, already in
     * prettified `Cell` / `WiFi` form — `switched to`/`is now`/
     * `onGcmNetworkChanged` name the network the client lands on,
     * `went away, was` the one it lost. Any trailing flag clause is dropped.
     * Null when the line names none: an unknown shape keeps its raw text.
     */
    fun parseNetworkTarget(text: String): String? {
        val raw = when {
            text.startsWith("Active network switched to ") ->
                NET_SWITCHED.find(text)?.groupValues?.get(1)
            text.startsWith("Active network went away, was ") ->
                text.removePrefix("Active network went away, was ")
            text.startsWith("Active network is now ") ->
                NET_NOW.find(text)?.groupValues?.get(1)
            text.startsWith("onGcmNetworkChanged: ") ->
                NET_CHANGED.find(text)?.groupValues?.get(1)
            else -> null
        } ?: return null
        return networkName(raw).ifEmpty { null }
    }

    /** True when the line reports the active network vanishing, not a handover. */
    fun isNetworkGone(text: String): Boolean =
        text.startsWith("Active network went away")

    /**
     * The `was` (previous) network of a handover / gone line — the value the
     * new title templates quote as 原为 B. Null on shapes that name no
     * previous network (`is now` / `onGcmNetworkChanged`).
     */
    fun parseNetworkPrevious(text: String): String? {
        val raw = when {
            text.startsWith("Active network switched to ") ->
                NET_SWITCHED.find(text)?.groupValues?.get(2)
            text.startsWith("Active network went away, was ") ->
                text.removePrefix("Active network went away, was ")
            else -> null
        } ?: return null
        return networkName(raw).ifEmpty { null }
    }

    /** `Heartbeat alarm delay: %d ms` — when the next heartbeat alarm is set for.
     *  A negative value is a real reading: the alarm is overdue (past due, not
     *  yet fired), which is itself diagnostic. */
    fun parseHeartbeatAlarmDelay(text: String): Long? =
        HB_ALARM_DELAY.find(text)?.groupValues?.get(1)?.toLongOrNull()

    /** `Heartbeat alarm fired early: %s` — the raw reason the alarm fired ahead of time. */
    fun parseHeartbeatAlarmEarly(text: String): String? =
        HB_ALARM_EARLY.find(text)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    /** `Reconnect alarm delay: %d ms` — when the next reconnect attempt is set for. */
    fun parseReconnectAlarmDelay(text: String): Long? =
        RECONNECT_ALARM_DELAY.find(text)?.groupValues?.get(1)?.toLongOrNull()

    /** `Failed connection err:%d` — the attempt's error code (the close-code space). */
    fun parseFailedConnectionCode(text: String): Int? =
        FAILED_CONNECTION_ERR.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** `Failed to connect to %s, trying %s` — the host that failed and the fallback. */
    fun parseFailedConnectHosts(text: String): Pair<String, String>? =
        FAILED_CONNECT_HOSTS.find(text)?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }

    /**
     * `Starting parallel %s in place of existing %s` — the new and the replaced
     * networks, flag clauses stripped so the title quotes transports only.
     */
    fun parseParallelSwitch(text: String): Pair<String, String>? =
        PARALLEL_SWITCH.find(text)?.let {
            val taking = networkName(it.groupValues[1])
            val replaced = networkName(it.groupValues[2])
            // One side without a transport reads as a broken sentence, so the
            // caller falls back to the plain title instead.
            if (taking.isEmpty() || replaced.isEmpty()) null else taking to replaced
        }

    /** `Connecting using %s` — the network the next attempt uses. */
    fun parseConnectingUsing(text: String): String? =
        CONNECTING_USING.find(text)
            ?.let { networkName(it.groupValues[1]) }
            ?.ifEmpty { null }

    /**
     * True when the attempt runs over the alternative host/port — GMS's
     * `FALLBACK_ALTERNATIVE_HOSTPORT` flag on the `McsConnection`. The title
     * folds it as a clause instead of a bracket.
     */
    fun parseConnectingFallbackPort(text: String): Boolean =
        text.contains("FALLBACK_ALTERNATIVE_HOSTPORT")

    /** `Global backoff: <duration>` — the value after the label, GMS's own wording. */
    fun parseGlobalBackoff(text: String): String? =
        if (text.startsWith("Global backoff: ")) {
            text.removePrefix("Global backoff: ").trim().ifEmpty { null }
        } else {
            null
        }

    /**
     * Status-page variant: keeps the netId (`WiFi 102`) — the structured
     * configuration benefits from the concrete network identity.
     */
    fun prettifyNetworks(text: String): String = prettifyNetworkTokens(text, withNetId = true)

    /**
     * Event-line variant: type only (`WiFi` / `Cell`), no netId — the events
     * page reads the transport type, not the number.
     *
     * Both variants drop GMS's wrappers entirely: an event line names a
     * transport, not a data structure, so
     * `McsConnection{GcmNetwork{100 Cell(0)}, type=0}` becomes `Cell`. Flag
     * tokens stay, comma-separated, so the raw line a tap reveals still
     * carries them; the title reads the known one through
     * [parseConnectingFallbackPort].
     */
    internal fun prettifyEventText(text: String): String =
        prettifyNetworkTokens(text, withNetId = false)

    private fun prettifyNetworkTokens(text: String, withNetId: Boolean): String {
        // McsConnection first: its inner network is a GcmNetwork token itself,
        // so consuming the wrapper before the standalone pass is what keeps
        // the braces out of the result.
        val mcs = MCS_CONNECTION.replace(text) { match ->
            val (name, netId) = networkOf(match.groupValues[1])
            buildString {
                append(name)
                if (withNetId && netId.isNotEmpty()) append(' ').append(netId)
                val flags = match.groupValues[3].trim()
                if (flags.isNotEmpty()) append(", ").append(flags)
            }
        }
        return GCM_NETWORK.replace(mcs) { match ->
            val netId = match.groupValues[1]
            val name = match.groupValues[2]
            if (withNetId) "$name $netId" else name
        }
    }

    /** Transport name and netId out of either inner `McsConnection` shape. */
    private fun networkOf(inner: String): Pair<String, String> {
        GCM_NETWORK.find(inner)?.let { match ->
            return match.groupValues[2] to match.groupValues[1]
        }
        NETWORK_PAIR.find(inner)?.let { match ->
            return match.groupValues[1] to match.groupValues[2]
        }
        return inner.trim() to ""
    }

    /** A network value with any trailing `, <flag>` clause stripped. */
    private fun networkName(raw: String): String = raw.substringBefore(',').trim()
}
