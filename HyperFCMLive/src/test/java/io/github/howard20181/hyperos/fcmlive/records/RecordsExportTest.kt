package io.github.howard20181.hyperos.fcmlive.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the page hands OUT of the archive: the exported diagnostic report.
 *
 * The report is a plain function on purpose — one assembled while rendering
 * cannot be asserted in a JVM test, and its failure mode is silent: it simply
 * lies about the order of events.
 *
 * The event search that used to live beside it was removed (难以完善，不再需要)
 * rather than half-finished: filtering had to mean the same thing in both
 * locales and both looks, and the title it was typed against is assembled per
 * look, so what it found changed with the appearance.
 */
class RecordsExportTest {

    private val messageId = "0:1792724616128948%0f493ae6f9fd7ecd"

    // ---------- exported report ----------

    private val dump = """
        SERVICE com.google.android.gms/.gcm.GcmService d34737b pid=6053 user=0
          Client:
            DeviceID: 4071119566194407730
            connected=mtalk.google.com/74.125.24.188,port=5228
            streamId=13/11,connects=8,failedLogins=0,lastConnectionDurationS=0
            connectTime=34:35/00:00
            Heartbeat: alarm(03:30 initial: 360s)
            Reconnect Scheduler Alarm: OFF
            Is client connected: true
            Network Status:
              Active:           GcmNetwork{102 WiFi(1)}
              Preferred:        GcmNetwork{102 WiFi(1)}
              In-use by client: GcmNetwork{102 WiFi(1)}

            10-23 22:21:36.258 net=1: Close err:20 time=3694
            10-23 22:19:02.086 net=1: Received com.android.chrome $messageId
    """.trimIndent()

    private val parsed = requireNotNull(GcmDumpParser.parse(dump))

    private fun report(
        snapshot: GcmSnapshot? = parsed.snapshot,
        fetchedAt: Long = 1_000L,
        exportedAt: Long = 1_000L,
        events: List<GcmEvent> = parsed.events
    ): String = diagnosticsReport(
        archive = RecordsStore.Archive(snapshot = snapshot, fetchedAt = fetchedAt, events = events),
        versionName = "4.2.0",
        exportedAt = exportedAt
    )

    @Test
    fun theReportIsHeaderSnapshotThenEvents() {
        val report = report()
        val header = report.lines()
        assertEquals("HyperOS FCM Live 诊断报告", header[0])
        assertTrue(header[1].startsWith("模块版本: 4.2.0"))
        assertTrue(report.contains("[快照]"))
        assertTrue(report.contains("pid=6053"))
        assertTrue(report.contains("network.active=WiFi 102"))
        assertTrue(report.contains("[事件] 旧 → 新"))
        assertTrue("the archive is newest-first; the report is chronological",
            report.indexOf("Received com.android.chrome") < report.indexOf("Close err:20"))
    }

    @Test
    fun theReportCountsWhatItHoldsAndSaysWhetherItIsStale() {
        val fresh = report(fetchedAt = 1_000L)
        assertTrue(fresh.contains("事件条数: 2"))
        assertTrue(fresh.contains("采样是否过期: 否"))

        val stale = report(fetchedAt = 1_000L, exportedAt = 1_000L + 200_001L)
        assertTrue("a snapshot two minutes old is no longer live", stale.contains("采样是否过期: 是"))
    }

    @Test
    fun anEmptyArchiveStillProducesAReadableReport() {
        val report = diagnosticsReport(
            archive = RecordsStore.Archive(snapshot = null, fetchedAt = 0, events = emptyList()),
            versionName = "4.2.0",
            exportedAt = 1_000L
        )
        assertTrue(report.contains("采样时间: 无"))
        assertTrue(report.contains("事件条数: 0"))
        assertTrue(report.contains("无"))
        assertTrue(report.contains("[事件] 旧 → 新"))
    }

    @Test
    fun theReportKeepsTheRawLineSoARegressionIsReproducible() {
        // The title is derived; the text is the evidence. A row keeps the netId
        // and the message id exactly as stored, so a report can be diffed
        // against the dump instead of against a translated sentence.
        val report = report()
        assertTrue(report.contains("$messageId"))
        assertTrue(report.contains("net=1 MessageReceived: Received com.android.chrome"))
        assertTrue(report.contains("Close err:20 time=3694"))
    }

    @Test
    fun theDeviceIdNeverReachesTheReport() {
        // The dump carries it; nothing in this module parses it, and the report
        // is meant to be pasted into a chat.
        assertFalse(report().contains("4071119566194407730"))
    }

    @Test
    fun theReportStatesTheEnvironmentAndSkipsRowsItDoesNotHave() {
        // 环境行排在模块版本之前：报「推送不来了」时先要知道设备和系统。
        // 空值不出现，也不补占位（与状态页的条件行同一条规则）。
        val report = diagnosticsReport(
            archive = RecordsStore.Archive(
                snapshot = parsed.snapshot,
                fetchedAt = 1_000L,
                events = parsed.events
            ),
            versionName = "4.3.1",
            exportedAt = 1_000L,
            env = DiagnosticsEnv(
                device = "REDMI K90 Pro Max",
                osVersion = "4.0.4.0.XPMCNXM",
                androidVersion = "17 (API 37)",
                managerVersion = ""
            )
        )
        val lines = report.lines()
        assertEquals("机型: REDMI K90 Pro Max", lines[1])
        assertEquals("系统版本: 4.0.4.0.XPMCNXM", lines[2])
        assertEquals("Android: 17 (API 37)", lines[3])
        assertEquals("an empty row is dropped, not filled in", "模块版本: 4.3.1", lines[4])
        assertFalse(report.contains("管理器版本"))
    }

    @Test
    fun theReportSaysWhetherTheHooksInstalledAtAll() {
        // 这是「钩子装上没有」的答案：只有模块 system_server 侧知道。
        val summary = "HyperFCMLive active in system_server: 120 hook(s) installed, " +
            "3 target(s) absent on this ROM"
        val withSummary = diagnosticsReport(
            archive = RecordsStore.Archive(
                snapshot = parsed.snapshot,
                fetchedAt = 1_000L,
                events = parsed.events,
                hookSummary = summary
            ),
            versionName = "4.3.1",
            exportedAt = 1_000L
        )
        assertTrue(withSummary.contains("钩子装载: $summary"))
        // 模块没回报时说「未回报」，不猜、不留空
        assertTrue(report().contains("钩子装载: 模块未回报"))
    }
}
