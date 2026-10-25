package io.github.howard20181.hyperos.fcmlive.records

import android.content.Context
import io.github.howard20181.hyperos.fcmlive.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists what the records page shows.
 *
 * GCM's event ring lives in GMS's memory and clears on every restart, so the
 * only way to keep any history is to parse each dump and append. One JSON file
 * in [Context.getFilesDir] holds both halves — the latest snapshot and the
 * merged event list — because a snapshot without its events (or the reverse)
 * would render as a lie. Writes go through a temp file + rename, so a process
 * death mid-write leaves the previous file intact.
 *
 * Retention: a user-chosen number of newest events (「事件记录上限行数」,
 * 200/300/500/800/1200, default [Prefs.DEFAULT_RECORD_ROWS] = 500), none older
 * than [RETENTION_MS]. The file is a view, not an audit log.
 *
 * The row cap is a setting because the two limits disagree about which one
 * actually binds: a single reconnect storm (one network handover prints three
 * or four lines, a heartbeat timeout a dozen) can fill a few hundred rows in an
 * afternoon, and the page would then claim a seven-day history it does not
 * have. One capture can only ever add the ~200 lines GMS's own ring holds, so
 * anything above that comes from polling across captures.
 */
object RecordsStore {

    private const val FILE_NAME = "records/records.json"
    private const val VERSION = 1
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * How much of a second a gap has to cover before it is worth a row. Two
     * polls normally leave a minute of silence with no events; the ring only
     * stops being continuous when its oldest line is NEWER than the cursor,
     * which cannot happen from mere silence — so the threshold is just "some
     * time at all", and it exists to absorb equal timestamps.
     */
    private const val GAP_FLOOR_MS = 1_000L

    /** What the page renders: latest snapshot plus the merged event history. */
    data class Archive(
        val snapshot: GcmSnapshot?,
        /** When [snapshot] was read; 0 before the first successful capture. */
        val fetchedAt: Long,
        /** Newest first. */
        val events: List<GcmEvent>
    ) {
        /** The snapshot is only "live" while the poll keeps refreshing it. */
        fun isStale(now: Long): Boolean =
            fetchedAt == 0L || now - fetchedAt > 2 * 60 * 1000
    }

    /** One capture's outcome, for the page's one-line status. */
    data class Capture(
        val archive: Archive,
        /** True when this dump came from a different GMS pid than the stored one. */
        val gmsRestarted: Boolean
    )

    fun load(context: Context): Archive = read(file(context)).archive

    /**
     * Merges [dump] into the stored archive and persists it.
     *
     * Dedupe cursor: the parsed event list is the same ring re-read on every
     * poll, so accept only events newer than the stored cursor. When the pid
     * moves, the ring is empty again — take the whole list and insert one
     * [GcmEvent.EventKind.GmsRestart] marker so the gap is visible.
     *
     * The ring is append-only, so it always holds a continuous run of lines
     * ending at its newest one. If its OLDEST line is newer than the cursor,
     * everything between the two was overwritten between polls — that is a
     * real, unrecoverable loss (a reconnect storm prints faster than the 60 s
     * poll reads), and it used to be silent. It now gets a row of its own, so
     * the page cannot imply a continuity the data does not have.
     */
    fun capture(context: Context, dump: GcmDump, now: Long = System.currentTimeMillis()): Capture {
        val file = file(context)
        val persisted = read(file)
        val stored = persisted.archive
        val cursor = persisted.cursor
        return try {
            val restarted = stored.snapshot != null && cursor.pid != dump.snapshot.pid
            val fresh = if (cursor.pid != dump.snapshot.pid) {
                dump.events
            } else {
                dump.events.filter { event ->
                    event.ts > cursor.ts || (event.ts == cursor.ts && event.text != cursor.text)
                }
            }
            val marker = if (restarted) {
                listOf(GcmEvent(now, -1, GcmEvent.EventKind.GmsRestart, ""))
            } else {
                emptyList()
            }
            // Overflow is only readable while the pid is unchanged: after a
            // restart the ring starts empty by design and the cursor is
            // meaningless, which [marker] already reports.
            val ringOldest = dump.events.minByOrNull { it.ts }
            val lostMs = if (!restarted && cursor.pid == dump.snapshot.pid &&
                cursor.ts > 0 && ringOldest != null &&
                ringOldest.ts - cursor.ts >= GAP_FLOOR_MS
            ) {
                ringOldest.ts - cursor.ts
            } else {
                0L
            }
            val gapMarker = if (lostMs > 0 && ringOldest != null) {
                listOf(
                    GcmEvent(
                        // Sits at the boundary where the loss happened, not at
                        // `now`: the rows around it are the evidence.
                        ts = ringOldest.ts,
                        net = -1,
                        kind = GcmEvent.EventKind.SamplingGap,
                        text = "${GcmEvent.EventKind.SamplingGap.name} ${lostMs / 1000}s"
                    )
                )
            } else {
                emptyList()
            }
            val merged = trim(context, fresh + marker + gapMarker + stored.events, now)
            android.util.Log.d(
                "RecordsStore",
                "capture pid=${dump.snapshot.pid} ring=${dump.events.size} fresh=${fresh.size} " +
                    "merged=${merged.size} lost=${lostMs / 1000}s"
            )
            val archive = Archive(snapshot = dump.snapshot, fetchedAt = now, events = merged)
            // The cursor tracks the newest event GMS itself reported, never the
            // restart marker: the marker carries `now`, and a cursor at `now`
            // would filter out every real event the next poll brings.
            val newestFromGms = dump.events.maxByOrNull { it.ts }
            write(
                file = file,
                archive = archive,
                cursor = Cursor(
                    pid = dump.snapshot.pid,
                    ts = newestFromGms?.ts ?: 0,
                    text = newestFromGms?.text ?: ""
                )
            )
            Capture(archive = archive, gmsRestarted = restarted)
        } catch (_: Throwable) {
            // A failed write must not blank the page: keep what was on disk.
            Capture(archive = stored, gmsRestarted = false)
        }
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun trim(context: Context, events: List<GcmEvent>, now: Long): List<GcmEvent> =
        events.asSequence()
            .filter { it.ts >= now - RETENTION_MS }
            .sortedByDescending { it.ts }
            .distinctBy { it.ts to it.text }
            .take(Prefs.readRecordRows(context))
            .toList()

    private data class Cursor(val pid: Int, val ts: Long, val text: String)

    private data class Persisted(val archive: Archive, val cursor: Cursor)

    private fun read(file: File): Persisted {
        val empty = Persisted(
            Archive(snapshot = null, fetchedAt = 0, events = emptyList()),
            Cursor(-1, 0, "")
        )
        if (!file.exists()) return empty
        return try {
            val root = JSONObject(file.readText())
            Persisted(
                archive = Archive(
                    snapshot = root.optJSONObject("snapshot")?.let(::snapshotFrom),
                    fetchedAt = root.optLong("fetched_at", 0),
                    events = root.optJSONArray("events")?.let(::eventsFrom) ?: emptyList()
                ),
                cursor = Cursor(
                    pid = root.optInt("cursor_pid", -1),
                    ts = root.optLong("cursor_ts", 0),
                    text = root.optString("cursor_text", "")
                )
            )
        } catch (_: Throwable) {
            // A corrupt file is not worth a dialog: start over from the next dump.
            empty
        }
    }

    private fun write(file: File, archive: Archive, cursor: Cursor) {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("fetched_at", archive.fetchedAt)
        archive.snapshot?.let { root.put("snapshot", snapshotTo(it)) }
        root.put("cursor_pid", cursor.pid)
        root.put("cursor_ts", cursor.ts)
        root.put("cursor_text", cursor.text)
        root.put("events", JSONArray().apply {
            archive.events.forEach { event ->
                put(
                    JSONObject()
                        .put("ts", event.ts)
                        .put("net", event.net)
                        .put("kind", event.kind.name)
                        .put("text", event.text)
                        .put("prev_net", event.previousNetwork?.transport ?: JSONObject.NULL)
                        .put("prev_id", event.previousNetwork?.netId ?: JSONObject.NULL)
                        .put("prev_state", event.previousNetwork?.state ?: JSONObject.NULL)
                )
            }
        })
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    private fun snapshotTo(s: GcmSnapshot): JSONObject = JSONObject()
        .put("pid", s.pid)
        .put("connected", s.connected)
        .put("endpoint", s.endpoint)
        .put("stream", s.streamId)
        .put("connects", s.connects)
        .put("failed_logins", s.failedLogins)
        .put("connect_time", s.connectTime)
        .put("heartbeat_alarm", s.heartbeatAlarm)
        .put("heartbeat_alarm_initial", s.heartbeatAlarmInitialS)
        .put(
            "heartbeat", s.heartbeat?.let {
                JSONObject()
                    .put("interval", it.intervalMs)
                    .put("phase", it.phase)
                    .put("good", it.good)
                    .put("bad", it.bad)
            }
        )
        .put("reconnect_alarm", s.reconnectAlarm)
        .put("global_backoff", s.globalBackoff)
        .put(
            "network", JSONObject()
                .put("active", s.network.active)
                .put("preferred", s.network.preferred)
                .put("in_use", s.network.inUse)
                .put("available", JSONArray(s.network.available))
        )
        .put("queued", s.queuedClients)
        .put("affinity", s.affinityClients)
        .put("high_priority", s.highPriorityQuota)
        .put("pending_messages", s.pendingMessages)
        // The link-detail block every field added on 2026-10-24 lives in. One
        // object, so an archive written by an older build simply has no `link`
        // and those rows come back empty instead of failing the whole record.
        // `putOpt` drops the nulls: "not reported" has to stay absent, or it
        // reads back as false / zero.
        .put(
            "link", JSONObject()
                .put("connecting", s.connecting)
                .putOpt("last_duration", s.lastConnectionDurationS)
                .put("disconnect_time", s.disconnectTime)
                .put("fallback_mode", s.fallbackMode)
                .put("awaiting_ack", s.heartbeatAwaitingAck)
                .put("last_ping", s.lastPing)
                .put("heartbeat_reset", s.lastHeartbeatReset)
                .putOpt("seen_good", s.seenGoodHeartbeat?.let { if (it) 1 else 0 })
                .putOpt("wakelock_within", s.wakelockReadsWithin)
                .putOpt("wakelock_exceeded", s.wakelockReadsExceeded)
                .put("adaptive_types", JSONArray().apply {
                    s.adaptiveHeartbeatByType.forEach {
                        put(JSONObject().put("type", it.type).put("on", it.enabled))
                    }
                })
                .put("limit_types", JSONArray().apply {
                    s.connectionsLimitByType.forEach {
                        put(JSONObject().put("type", it.type).put("value", it.value))
                    }
                })
                .put("type_names", JSONObject().apply {
                    s.networkTypeNames.forEach { (id, name) -> put(id.toString(), name) }
                })
                .put("queued_packages", JSONArray().apply {
                    s.queuedPackages.forEach { put(it) }
                })
                .put("affinity_scores", JSONArray().apply {
                    s.affinityScores.forEach {
                        put(JSONObject().put("pkg", it.packageName).put("score", it.score))
                    }
                })
                .put("network_backoffs", JSONArray().apply {
                    s.networkBackoffs.forEach {
                        put(JSONObject().put("network", it.network).put("duration", it.duration))
                    }
                })
                .put("total_delay", s.totalDelay?.let {
                    JSONObject()
                        .put("total", it.totalMs)
                        .put("server", it.serverToClientMs)
                        .put("queue", it.queueMs)
                        .put("broadcast", it.broadcastMs)
                        .put("result", it.result)
                })
        )

    private fun snapshotFrom(o: JSONObject): GcmSnapshot {
        val link = o.optJSONObject("link")
        return GcmSnapshot(
            pid = o.optInt("pid", -1),
            connected = o.optBoolean("connected", false),
            endpoint = o.optString("endpoint"),
            streamId = o.optString("stream"),
            connects = o.optInt("connects"),
            failedLogins = o.optInt("failed_logins"),
            connectTime = o.optString("connect_time"),
            heartbeatAlarm = o.optString("heartbeat_alarm"),
            // Archives written before the alarm was split into two values simply
            // carry no second field; the row then shows the clock time alone.
            heartbeatAlarmInitialS = o.optString("heartbeat_alarm_initial"),
            heartbeat = o.optJSONObject("heartbeat")?.let {
                HeartbeatState(
                    intervalMs = it.optInt("interval"),
                    phase = it.optInt("phase"),
                    good = it.optInt("good"),
                    bad = it.optInt("bad")
                )
            },
            reconnectAlarm = o.optString("reconnect_alarm"),
            globalBackoff = o.optString("global_backoff"),
            network = o.optJSONObject("network")?.let {
                NetworkStatus(
                    active = it.optString("active"),
                    preferred = it.optString("preferred"),
                    inUse = it.optString("in_use"),
                    available = it.optJSONArray("available")?.let { array ->
                        (0 until array.length()).map(array::optString)
                    } ?: emptyList()
                )
            } ?: NetworkStatus("", "", "", emptyList()),
            queuedClients = o.optInt("queued"),
            affinityClients = o.optInt("affinity"),
            highPriorityQuota = o.optString("high_priority"),
            pendingMessages = o.optInt("pending_messages", -1),
            connecting = link?.optBoolean("connecting", false) ?: false,
            lastConnectionDurationS = link?.optIntOrNull("last_duration"),
            disconnectTime = link?.optString("disconnect_time").orEmpty(),
            fallbackMode = link?.optString("fallback_mode").orEmpty(),
            adaptiveHeartbeatByType = link?.optJSONArray("adaptive_types")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    TypeFlag(entry.optInt("type"), entry.optBoolean("on"))
                }
            } ?: emptyList(),
            connectionsLimitByType = link?.optJSONArray("limit_types")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    TypeCount(entry.optInt("type"), entry.optInt("value"))
                }
            } ?: emptyList(),
            heartbeatAwaitingAck = link?.optBoolean("awaiting_ack", false) ?: false,
            lastPing = link?.optString("last_ping").orEmpty(),
            lastHeartbeatReset = link?.optString("heartbeat_reset").orEmpty(),
            seenGoodHeartbeat = link?.optIntOrNull("seen_good")?.let { it == 1 },
            wakelockReadsWithin = link?.optIntOrNull("wakelock_within"),
            wakelockReadsExceeded = link?.optIntOrNull("wakelock_exceeded"),
            totalDelay = link?.optJSONObject("total_delay")?.let {
                TotalDelay(
                    totalMs = it.optInt("total"),
                    serverToClientMs = it.optInt("server"),
                    queueMs = it.optInt("queue"),
                    broadcastMs = it.optInt("broadcast"),
                    result = it.optInt("result")
                )
            },
            queuedPackages = link?.optJSONArray("queued_packages")?.let { array ->
                (0 until array.length()).map(array::optString)
            } ?: emptyList(),
            affinityScores = link?.optJSONArray("affinity_scores")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    AffinityScore(entry.optString("pkg"), entry.optDouble("score"))
                }
            } ?: emptyList(),
            networkBackoffs = link?.optJSONArray("network_backoffs")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val entry = array.optJSONObject(index) ?: return@mapNotNull null
                    NetworkBackoff(entry.optString("network"), entry.optString("duration"))
                }
            } ?: emptyList(),
            networkTypeNames = link?.optJSONObject("type_names")?.let { names ->
                names.keys().asSequence().mapNotNull { key ->
                    key.toIntOrNull()?.let { it to names.optString(key) }
                }.toMap()
            } ?: emptyMap()
        )
    }

    /** `optInt` cannot tell "absent" from 0, and these fields need to. */
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    /**
     * Rows come back deduped on `ts|text`: the events list keys rows by that
     * pair, and [trim] keeps the same invariant on the way out — a file that
     * somehow carried a duplicate would make the lazy list throw on a repeated
     * key rather than merely show a row twice.
     */
    private fun eventsFrom(array: JSONArray): List<GcmEvent> = buildList {
        for (i in 0 until array.length()) {
            val event = array.optJSONObject(i) ?: continue
            val kind = runCatching {
                GcmEvent.EventKind.valueOf(event.optString("kind"))
            }.getOrElse { GcmEvent.EventKind.Other }
            // The network a change came FROM. Written by the current version
            // only; an archive written before it has no such field, and the
            // row then falls back to whatever its own text names.
            val prevTransport = event.optString("prev_net").takeIf {
                it.isNotEmpty() && it != "null"
            }
            val previous = prevTransport?.let {
                GcmDumpParser.NetworkRef(
                    transport = it,
                    netId = event.optString("prev_id").takeIf { s ->
                        s.isNotEmpty() && s != "null"
                    },
                    state = event.optString("prev_state").takeIf { s ->
                        s.isNotEmpty() && s != "null"
                    }
                )
            }
            add(
                GcmEvent(
                    ts = event.optLong("ts"),
                    net = event.optInt("net"),
                    kind = kind,
                    // Re-prettified on the way in: an archive written before the
                    // wrapper fix still holds `McsConnection{Cell, type=0}` in
                    // its raw text, and the current rule is idempotent, so this
                    // heals old rows instead of leaving them unreadable.
                    text = GcmDumpParser.prettifyEventText(event.optString("text")),
                    previousNetwork = previous
                )
            )
        }
    }.distinctBy { it.ts to it.text }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
