package io.github.howard20181.hyperos.fcmlive.records

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The diagnostic text a reader can hand over instead of a stack of screenshots.
 *
 * Everything in it is already on the page: the stored snapshot, the stored
 * events, and the module version. Nothing is re-read from GMS and nothing new
 * is collected — an export that captured its own data would be a second source
 * of truth, and the whole point of the page is that what you see is what is
 * logged.
 *
 * Plain text on purpose. A JSON file is for a parser; this is for pasting into
 * a chat, so the layout is one fact per line, oldest events first, and every
 * row keeps the raw `text` (the page's prettified title is derived from it, and
 * a regression like `GcmNetwork{…}` reaching the screen is only reproducible
 * from the raw line).
 *
 * The dump's `DeviceID:` is never parsed anywhere in this module, so it cannot
 * appear here; message bodies are not in the dump at all.
 */
internal fun diagnosticsReport(
    archive: RecordsStore.Archive,
    versionName: String,
    exportedAt: Long
): String {
    val snapshot = archive.snapshot
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    return buildString {
        appendLine("HyperOS FCM Live 诊断报告")
        appendLine("模块版本: $versionName")
        appendLine("导出时间: ${stamp.format(Date(exportedAt))}")
        appendLine("采样时间: ${if (archive.fetchedAt > 0) stamp.format(Date(archive.fetchedAt)) else "无"}")
        appendLine("采样是否过期: ${if (archive.isStale(exportedAt)) "是" else "否"}")
        appendLine("事件条数: ${archive.events.size}")

        appendLine()
        appendLine("[快照]")
        if (snapshot == null) {
            appendLine("无")
        } else {
            appendLine("pid=${snapshot.pid}")
            appendLine("connected=${snapshot.connected}")
            appendLine("endpoint=${snapshot.endpoint}")
            appendLine("streamId=${snapshot.streamId}")
            appendLine("connects=${snapshot.connects}")
            appendLine("failedLogins=${snapshot.failedLogins}")
            appendLine("connectTime=${snapshot.connectTime}")
            appendLine("heartbeatAlarm=${snapshot.heartbeatAlarm}")
            appendLine("heartbeatAlarmInitialS=${snapshot.heartbeatAlarmInitialS}")
            appendLine("reconnectAlarm=${snapshot.reconnectAlarm}")
            appendLine("globalBackoff=${snapshot.globalBackoff}")
            appendLine("network.active=${snapshot.network.active}")
            appendLine("network.preferred=${snapshot.network.preferred}")
            appendLine("network.inUse=${snapshot.network.inUse}")
            appendLine("network.available=${snapshot.network.available.joinToString()}")
            appendLine("queuedClients=${snapshot.queuedClients}")
            appendLine("affinityClients=${snapshot.affinityClients}")
            appendLine("highPriorityQuota=${snapshot.highPriorityQuota}")
            appendLine("pendingMessages=${snapshot.pendingMessages}")
            appendLine("connecting=${snapshot.connecting}")
            appendLine("lastConnectionDurationS=${snapshot.lastConnectionDurationS}")
            appendLine("disconnectTime=${snapshot.disconnectTime}")
            appendLine("fallbackMode=${snapshot.fallbackMode}")
            appendLine("heartbeatAwaitingAck=${snapshot.heartbeatAwaitingAck}")
            appendLine("lastPing=${snapshot.lastPing}")
            appendLine("lastHeartbeatReset=${snapshot.lastHeartbeatReset}")
            appendLine("seenGoodHeartbeat=${snapshot.seenGoodHeartbeat}")
            appendLine("wakelockReadsWithin=${snapshot.wakelockReadsWithin}")
            appendLine("wakelockReadsExceeded=${snapshot.wakelockReadsExceeded}")
        }

        appendLine()
        appendLine("[事件] 旧 → 新")
        // The archive holds newest first; a diagnosis is read chronologically.
        for (event in archive.events.asReversed()) {
            appendLine(
                "${line.format(Date(event.ts))} net=${event.net} " +
                    "${EventTaxonomy.kindOf(event).name}: ${event.text}"
            )
        }
    }
}
