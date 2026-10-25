package io.github.howard20181.hyperos.fcmlive.records

/**
 * The six first-level readings of the events page.
 *
 * [All] is deliberately first: it is the complete timeline plus the structured
 * configuration, and every event — including the ones this taxonomy cannot
 * classify — stays reachable there.
 */
enum class EventTab {
    All,
    Connection,
    Heartbeat,
    Push,
    Sleep,
    Error
}

/** A diagnostic label. One event may carry several (异常 aggregates across stages). */
enum class EventLabel(val tab: EventTab) {
    Connection(EventTab.Connection),
    Heartbeat(EventTab.Heartbeat),
    Push(EventTab.Push),
    Sleep(EventTab.Sleep),
    Error(EventTab.Error)
}

/** The stage an anomaly belongs to, shown on the row so the failure is not read out of context. */
enum class ErrorStage {
    Connection,
    Reconnect,
    Heartbeat,
    Broadcast
}

/**
 * How much a row deserves attention — the dot colour, and the only thing the
 * colour means. Three levels plus "cannot tell":
 *
 * - [Normal]: the connection is doing what it should (connect, login accepted,
 *   heartbeats, protocol traffic, a broadcast that landed).
 * - [Watch]: not healthy, not broken — a reconnect cycle, a network that went
 *   away, a graceful replacement (close 27), GMS restarting, an alarm that
 *   overslept, a wakelock force-released.
 * - [Error]: something failed (a close other than 27, heartbeat timeout, a
 *   broadcast that never arrived).
 * - [Unknown]: the line could not be characterised (an unrecognised shape).
 *
 * Severity is derived per event, not per kind: `Close err:27` and `Close err:6`
 * are the same kind and are not the same news.
 */
enum class EventSeverity { Normal, Watch, Error, Unknown }

/** What the taxonomy says about one event. [errorStage] is non-null only for anomaly-labelled events. */
data class EventClasses(
    val labels: Set<EventLabel>,
    val errorStage: ErrorStage?
) {
    fun inTab(tab: EventTab): Boolean =
        tab == EventTab.All || labels.any { it.tab == tab }
}

/**
 * Maps one GCM event line to the page's tabs.
 *
 * Basis, all from the captured `GcmService` dump and the project's
 * HOOKS_AND_DIAGNOSTICS.md §4.7:
 *
 * - `Connected`, `Sent LoginRequest`, network changes, reconnect setup
 *   (`Connecting using`, `Starting parallel`, reconnect scheduling) and the
 *   protocol `IqStanza` lines -> connection. This tab follows the lifecycle
 *   from the network object to the NCS/MCS connection.
 * - `Received/Sent * HB`, `Heartbeat alarm delay` -> heartbeat. A delay alone
 *   is not an error; §4.7.1 uses it only as the co-occurrence context for
 *   `Close err:6`.
 * - `Received`, `Acked`, `Successful broadcast` -> push. They are independent
 *   stages; none of them proves app processing or a displayed notification.
 * - `Client Entering/Exiting doze` -> sleep. The dump has no screen on/off
 *   lines, so those are not fabricated.
 * - Every `Close err:N` -> connection, and 异常 **only when the code is not an
 *   orderly ending**: 17 (manual), 20 (peer FIN) and 27 (replaced by a newer
 *   connection) are the connection doing its job, so they stay out — that is
 *   what stops a green row from appearing in a tab called 异常.
 * - `Heartbeat timeout, GCM connection reset` -> heartbeat **and** anomaly
 *   (heartbeat stage): it is the alarm side of the failure the following
 *   `Close err:6` reports.
 * - `Failed connection err` -> connection and anomaly (reconnect stage).
 * - `Received Close` / bare `Disconnected` / `Disconnect since …` -> connection
 *   only: close-family lines without a code, left uncharacterised by design.
 * - `Global backoff` and `Reconnect alarm delay` -> connection and anomaly
 *   (reconnect stage). `Reconnect Scheduler Alarm`, `Connecting using` and
 *   `Starting parallel` stay connection-only as setup/scheduling.
 *
 * The anomaly label is derived from [severity], not written per kind: 异常 holds
 * exactly the 需留意 and 异常 rows, so tab membership and dot colour cannot
 * disagree.
 * - `Failed to broadcast to stopped app` / `No response to broadcast from` ->
 *   push and anomaly (broadcast stage).
 * - `GmsRestart` and every unknown line -> no labels: they remain in「全部」
 *   and are not forced into a category.
 *
 * The classifier reads the raw text through [GcmDumpParser.classifyText], so
 * events persisted by an older build (where message lines were still `Other`)
 * are classified by the same rules on the next render.
 */
object EventTaxonomy {

    /**
     * The current line kind. A stored event keeps its raw text; re-deriving the
     * kind here is what lets archives written before the message kinds existed
     * classify (and render) correctly.
     */
    fun kindOf(event: GcmEvent): GcmEvent.EventKind = when (event.kind) {
        // The two markers are the store's, not GMS's: their text would classify
        // as `Other`, so the stored kind is the only thing that knows them.
        GcmEvent.EventKind.GmsRestart,
        GcmEvent.EventKind.SamplingGap -> event.kind
        else -> GcmDumpParser.classifyText(event.text)
    }

    fun classes(event: GcmEvent): EventClasses {
        val kind = kindOf(event)
        val labels = mutableSetOf<EventLabel>()
        var stage: ErrorStage? = null
        when (kind) {
            GcmEvent.EventKind.Connected,
            GcmEvent.EventKind.Login,
            GcmEvent.EventKind.Network,
            GcmEvent.EventKind.IqStanza -> labels += EventLabel.Connection

            GcmEvent.EventKind.Reconnect -> labels += EventLabel.Connection
            GcmEvent.EventKind.Heartbeat -> labels += EventLabel.Heartbeat

            // A close is always part of the connection story; whether it also
            // counts as an anomaly is decided by its severity below, because
            // only some codes mean something went wrong.
            GcmEvent.EventKind.Close -> {
                labels += EventLabel.Connection
                if (GcmDumpParser.parseClose(event.text)?.code == 6) {
                    labels += EventLabel.Heartbeat
                }
            }

            GcmEvent.EventKind.Doze -> labels += EventLabel.Sleep

            // Close-family lines without a code: connection-only, left
            // uncharacterised — no err number, no failure assertion.
            GcmEvent.EventKind.ServerClose -> labels += EventLabel.Connection

            GcmEvent.EventKind.MessageReceived,
            GcmEvent.EventKind.MessageAcked,
            GcmEvent.EventKind.BroadcastCompleted,
            // The proxy is the terminal stage for apps that are not running:
            // GMS showed the notification itself, so no broadcast follows.
            GcmEvent.EventKind.ProxiedNotification -> labels += EventLabel.Push

            GcmEvent.EventKind.BroadcastCancelled,
            GcmEvent.EventKind.BroadcastFailed,
            GcmEvent.EventKind.BroadcastNoResponse -> labels += EventLabel.Push

            // Writer-thread / read faults: the connection itself is what broke.
            GcmEvent.EventKind.Transport -> labels += EventLabel.Connection

            GcmEvent.EventKind.GmsRestart,
            GcmEvent.EventKind.SamplingGap,
            GcmEvent.EventKind.Other -> Unit
        }

        // The anomaly tab is exactly "the rows that are not healthy": it admits
        // a row when its severity is 需留意 or 异常, so a green dot can never
        // appear there. Deriving the label from [severity] instead of from the
        // kind is what keeps the tab and the dot from disagreeing — a close
        // with code 20 is a route change, not a failure.
        when (severity(event, kind)) {
            EventSeverity.Watch, EventSeverity.Error -> {
                labels += EventLabel.Error
                stage = when (kind) {
                    GcmEvent.EventKind.Reconnect -> ErrorStage.Reconnect
                    GcmEvent.EventKind.Heartbeat -> ErrorStage.Heartbeat
                    GcmEvent.EventKind.BroadcastFailed,
                    GcmEvent.EventKind.BroadcastNoResponse,
                    GcmEvent.EventKind.BroadcastCancelled -> ErrorStage.Broadcast
                    GcmEvent.EventKind.Close ->
                        if (GcmDumpParser.parseClose(event.text)?.code == 6) {
                            ErrorStage.Heartbeat
                        } else {
                            ErrorStage.Connection
                        }
                    else -> ErrorStage.Connection
                }
            }
            EventSeverity.Normal, EventSeverity.Unknown -> Unit
        }
        return EventClasses(labels, stage)
    }

    /**
     * The row's severity — see [EventSeverity]. Read off the event, not the
     * kind: a graceful close (27) is a handover, every other code is a fault,
     * and `Active network went away` differs from a normal handover line.
     *
     * Anything this cannot place stays [EventSeverity.Unknown] rather than
     * being guessed into a colour.
     */
    fun severity(event: GcmEvent, kind: GcmEvent.EventKind = kindOf(event)): EventSeverity {
        val text = event.text
        return when (kind) {
            GcmEvent.EventKind.Connected,
            GcmEvent.EventKind.IqStanza,
            GcmEvent.EventKind.MessageReceived,
            GcmEvent.EventKind.MessageAcked,
            GcmEvent.EventKind.BroadcastCompleted,
            GcmEvent.EventKind.ProxiedNotification -> EventSeverity.Normal

            GcmEvent.EventKind.Login -> when {
                text.startsWith("Got new GCM ID/token") -> EventSeverity.Normal
                text.startsWith("Sending IID ") -> EventSeverity.Normal
                text.startsWith("Received message before LoginResponse") -> EventSeverity.Watch
                text.startsWith("Sent LoginRequest") -> EventSeverity.Normal
                // Login error / Login failed on server / Missing Android ID or
                // token / Invalid appId: the session never came up.
                else -> EventSeverity.Error
            }

            GcmEvent.EventKind.Heartbeat -> when {
                text.startsWith("Heartbeat timeout") -> EventSeverity.Error
                text.startsWith("Heartbeat alarm fired early") -> EventSeverity.Normal
                // Any alarm that overslept: the first sign that doze or a power
                // policy is holding the keepalive back.
                text.startsWith("Alarm ") -> EventSeverity.Watch
                // `Heartbeat alarm delay`: the watchdog fired N ms off its
                // deadline. Late is the reading that matters (system held the
                // alarm); early is a cycle that woke before its deadline.
                (GcmDumpParser.parseHeartbeatAlarmDelay(text) ?: 0L) > 0L ->
                    EventSeverity.Watch
                else -> EventSeverity.Normal
            }

            GcmEvent.EventKind.Network ->
                if (text.startsWith("Active network went away")) EventSeverity.Watch
                else EventSeverity.Normal

            GcmEvent.EventKind.Reconnect -> when {
                text.startsWith("Failed connection err") -> EventSeverity.Error
                text.startsWith("No addresses for host") -> EventSeverity.Error
                else -> EventSeverity.Watch
            }

            // Graded per code, not per "it is a close". GMS's own table says
            // which endings are orderly: a peer FIN (20), a manual disconnect
            // (17) and being replaced by a newer connection (27) are the
            // connection doing what it is supposed to. A peer that ends the
            // service, a reset request or an unlock-triggered policy reset are
            // worth a look; everything else is a real failure. `ERR_UNKNOWN`
            // and codes outside the table cannot be characterised at all.
            GcmEvent.EventKind.Close -> when (val code = GcmDumpParser.parseClose(text)?.code) {
                null -> EventSeverity.Unknown
                10 -> EventSeverity.Unknown
                17, 20, CloseCodes.NETWORK_SWITCHED -> EventSeverity.Normal
                15, 18, 29, 30 -> EventSeverity.Watch
                else -> if (CloseCodes.isKnown(code)) {
                    EventSeverity.Error
                } else {
                    EventSeverity.Unknown
                }
            }

            // Closing on our own initiative is routine; losing the connection
            // is worth a look even when the line carries no code.
            GcmEvent.EventKind.ServerClose ->
                if (text == "Sent Close") EventSeverity.Normal else EventSeverity.Watch

            GcmEvent.EventKind.Doze -> when {
                text.startsWith("Sending enter doze bit") ||
                    text.startsWith("Client Entering doze") ||
                    text.startsWith("Client Exiting doze") -> EventSeverity.Normal
                // The wakelock being force-released is the keepalive losing its
                // grip, not a failure.
                else -> EventSeverity.Watch
            }

            GcmEvent.EventKind.BroadcastFailed,
            GcmEvent.EventKind.BroadcastNoResponse,
            GcmEvent.EventKind.Transport -> EventSeverity.Error

            // Nothing crashed, but the message reached nobody.
            GcmEvent.EventKind.BroadcastCancelled -> EventSeverity.Watch

            GcmEvent.EventKind.GmsRestart -> EventSeverity.Watch

            // The history itself lost rows: nothing about the connection is
            // known, only that the page cannot vouch for the gap.
            GcmEvent.EventKind.SamplingGap -> EventSeverity.Watch

            GcmEvent.EventKind.Other -> EventSeverity.Unknown
        }
    }

    /**
     * How long each close row's connection had been up, keyed by that close.
     *
     * GMS prints `Connected` when a session starts and `Close err:N time:M`
     * when it ends; `M` is the connection's own age but it is not on every
     * close line (and `time:` may be absent). Rebuilding the age from the two
     * timestamps works for every close the archive still holds the start of:
     * the ring is walked oldest-first so each `Connected` marks the session
     * that the closes after it belong to.
     *
     * A close whose start has already rolled out of the archive is simply
     * absent from the map — better than a duration guessed from some earlier
     * session. Sessions older than a day are dropped for the same reason: at
     * that distance the pairing is more likely to be a restart artifact than a
     * real uptime.
     */
    fun connectionUptimes(events: List<GcmEvent>): Map<GcmEvent, Long> {
        val out = HashMap<GcmEvent, Long>()
        var connectedAt = 0L
        for (event in events.asReversed()) {
            when (kindOf(event)) {
                GcmEvent.EventKind.Connected -> connectedAt = event.ts
                GcmEvent.EventKind.Close -> {
                    val seconds = (event.ts - connectedAt) / 1000
                    if (connectedAt > 0 && seconds > 0 && seconds < MAX_UPTIME_S) {
                        out[event] = seconds
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private const val MAX_UPTIME_S = 24L * 60 * 60

    /**
     * For each 回执 and 完成 row, how long after its message arrived it happened.
     *
     * The three stages of one delivery print the same `id=<msgid>`: `Received
     * <pkg> <id>`, `Acked <pkg>:N <id>`, `Successful broadcast to <pkg>
     * (id=<id> time=…ms …)`. Each line used to stand alone, so "the push is
     * slow" had no answer on the page — the broadcast's own `time=` only covers
     * its last hop, and the 回执 row had no duration at all.
     *
     * Joining them answers the question that brings someone here: how much of
     * the delay is GCM's (arrival → ack) and how much is the broadcast (the
     * `time=` clause still shows that hop on its own). Nothing is inferred from
     * adjacency: the message id has to match, and the arrival has to be the
     * earlier line — an id that repeats after a re-delivery restarts the clock
     * rather than inheriting an old arrival time.
     */
    fun pushArrivals(events: List<GcmEvent>): Map<GcmEvent, Long> {
        val out = HashMap<GcmEvent, Long>()
        val arrivedAt = HashMap<String, Long>()
        // Oldest first, so a re-delivered id overwrites the earlier arrival:
        // the newest arrival is the one the later stages belong to.
        for (event in events.asReversed()) {
            val messageId = when (kindOf(event)) {
                GcmEvent.EventKind.MessageReceived ->
                    GcmDumpParser.parseMessageReceived(event.text)?.messageId
                GcmEvent.EventKind.MessageAcked ->
                    GcmDumpParser.parseMessageAcked(event.text)?.messageId
                GcmEvent.EventKind.BroadcastCompleted ->
                    GcmDumpParser.parseBroadcastCompleted(event.text)?.messageId
                else -> null
            } ?: continue
            when (kindOf(event)) {
                GcmEvent.EventKind.MessageReceived -> arrivedAt[messageId] = event.ts
                else -> {
                    val start = arrivedAt[messageId] ?: continue
                    val delta = event.ts - start
                    if (delta in 0..MAX_PUSH_ARRIVAL_MS) out[event] = delta
                }
            }
        }
        return out
    }

    /** Ten minutes: longer than any delivery a user would still attribute to this arrival. */
    private const val MAX_PUSH_ARRIVAL_MS = 10L * 60 * 1000
}
