package io.github.howard20181.hyperos.fcmlive.records

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The diagnostic text a reader can hand over instead of a stack of screenshots.
 *
 * Four sources, all already earned by the app itself: the stored snapshot, the
 * stored events, the module version, and the environment rows the 状态 page
 * shows ([DiagnosticsEnv]). The hook summary is the one thing that did not
 * exist before — it arrives with the query that produced the snapshot, from the
 * module's own install pass. Nothing is re-read from GMS and nothing new is
 * collected.
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
/**
 * The environment rows, supplied by the caller rather than read here.
 *
 * They come from the same helpers the 状态 page shows (`DeviceInfo.kt`), so the
 * export and the screen cannot disagree; every field is nullable because each
 * one is a prop lookup that can come back empty, and the export skips an empty
 * row instead of printing a placeholder (the page's own rule).
 */
internal data class DiagnosticsEnv(
    val device: String? = null,
    val osVersion: String? = null,
    val androidVersion: String? = null,
    val managerVersion: String? = null
)

internal fun diagnosticsReport(
    archive: RecordsStore.Archive,
    versionName: String,
    exportedAt: Long,
    env: DiagnosticsEnv = DiagnosticsEnv()
): String {
    val snapshot = archive.snapshot
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    return buildString {
        appendLine("HyperOS FCM Live 诊断报告")
        // 环境排在版本号旁边，因为「推送不来了」的第一问永远是
        // 「什么设备、什么系统、模块有没有真的装上」——这三样以前要靠人再问一遍。
        env.device?.takeIf { it.isNotBlank() }?.let { appendLine("机型: $it") }
        env.osVersion?.takeIf { it.isNotBlank() }?.let { appendLine("系统版本: $it") }
        env.androidVersion?.takeIf { it.isNotBlank() }?.let { appendLine("Android: $it") }
        env.managerVersion?.takeIf { it.isNotBlank() }?.let { appendLine("管理器版本: $it") }
        appendLine("模块版本: $versionName")
        // The hook side's own verdict, from the module's system_server half. Not
        // read out of GMS: it says whether the install pass found its targets.
        appendLine("钩子装载: ${archive.hookSummary?.takeIf { it.isNotBlank() } ?: "模块未回报"}")
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
