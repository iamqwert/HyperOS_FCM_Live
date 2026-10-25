package io.github.howard20181.hyperos.fcmlive.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the snapshot half of the parser to a dump taken from a real device
 * (GMS 26.41.62 on HyperOS 4.0.4.0, 2026-10-24), trimmed to one event line per
 * shape and to two entries per list.
 *
 * Why it exists: most of these fields are printed *conditionally* by GMS. A
 * regex that quietly stops matching does not fail — it just makes a row
 * disappear, which looks exactly like "the link is healthy and nothing to
 * report". This test makes a lost field a red build instead.
 */
class GcmDumpParserTest {

    private val dump = """
        SERVICE com.google.android.gms/.gcm.GcmService d34737b pid=6053 user=0
          Client:
            DeviceID: 4071119566194407730
            connected=mtalk.google.com/74.125.24.188,port=5228
            streamId=13/11,connects=8,failedLogins=0,lastConnectionDurationS=0
            connectTime=34:35/00:00
            Heartbeat: alarm(03:30 initial: 360s)
            Adaptive Heartbeat type 0: false
            connectionsLimit: 5

            All stored connections: 

            Adaptive Heartbeat type 1: true
            connectionsLimit: 15

            All stored connections: 
            Network type: 1
            HB algorithm: FastSlowHeartbeatAlgorithm{fast_upward_trigger: 3, slow_upward_trigger: 30, after_ratio_drop_upward_trigger: 3, fall_fast_trigger: 3, drop_ratio: 0.75, interval_range: [110000, 1730000], interval_step: 60000, initial_interval: 530000, good_heartbeat_count: 14, bad_heartbeat_count: 0, heartbeat_interval: 230000, phase: 2}
            Reconnect Scheduler Alarm: OFF
            Global backoff: PT4.026S
            Next backoff interval per network:
              GcmNetwork{100 Cell(0)}, PT12S

            Fallback mode: FALLBACK_NONE
            Supports restricted networks: true
            Is client connected: true
            Network Status:
              Active:           GcmNetwork{102 WiFi(1)}
              Preferred:        GcmNetwork{102 WiFi(1)}
              In-use by client: GcmNetwork{102 WiFi(1)}

            ConnectivityManagerVariantImplAsync
             active: GcmNetwork{102 WiFi(1)}
             available: 
              GcmNetwork{100 Cell(0)}
              GcmNetwork{102 WiFi(1)}

            10-23 22:19:02.086 net=1: Received com.google.android.googlequicksearchbox 0:1792765141834557%8b460216f9fd7ecd
            10-23 22:21:36.258 net=1: Close err:20 time:3694
            10-23 22:21:46.262 net=1: Reconnect alarm delay: 4 ms

            Apps supporting client queue:
            com.android.chrome:0 v805908704
            org.telegram.messenger:0 v72291

            Affinity Scores:
            org.telegram.messenger:0 0.921

            High priority quota usage:

            Queued messages:
            For scheduler stats see SchedulerService dump.
    """.trimIndent()

    private val snapshot = requireNotNull(GcmDumpParser.parse(dump)).snapshot

    @Test
    fun headerAndClientCounters() {
        assertEquals(6053, snapshot.pid)
        assertTrue(snapshot.connected)
        assertFalse(snapshot.connecting)
        assertEquals("mtalk.google.com:5228", snapshot.endpoint)
        assertEquals("13/11", snapshot.streamId)
        assertEquals(8, snapshot.connects)
        assertEquals(0, snapshot.failedLogins)
        assertEquals("34:35", snapshot.connectTime)
        assertEquals("03:30", snapshot.heartbeatAlarm)
        assertEquals("360", snapshot.heartbeatAlarmInitialS)
    }

    @Test
    fun lastSessionIsReadOnlyWhenItCarriesAValue() {
        // Zero is what a settled connection reports; the row stays hidden, so
        // the parser keeps the number and the page decides.
        assertEquals(0, snapshot.lastConnectionDurationS)
        assertEquals("", snapshot.disconnectTime)
    }

    @Test
    fun adaptiveHeartbeatIsPerTransport() {
        assertEquals(
            listOf(TypeFlag(type = 0, enabled = false), TypeFlag(type = 1, enabled = true)),
            snapshot.adaptiveHeartbeatByType
        )
        // The limit belongs to the transport named above it, not to the dump's
        // first one.
        assertEquals(
            listOf(TypeCount(type = 0, value = 5), TypeCount(type = 1, value = 15)),
            snapshot.connectionsLimitByType
        )
        assertEquals("Cell", snapshot.networkTypeNames[0])
        assertEquals("WiFi", snapshot.networkTypeNames[1])
    }

    @Test
    fun heartbeatAlgorithmIsTheLiveBlock() {
        val heartbeat = requireNotNull(snapshot.heartbeat)
        assertEquals(230000, heartbeat.intervalMs)
        assertEquals(2, heartbeat.phase)
        assertEquals(14, heartbeat.good)
        assertEquals(0, heartbeat.bad)
    }

    @Test
    fun silentHealthFieldsStayAbsent() {
        assertFalse(snapshot.heartbeatAwaitingAck)
        assertEquals("", snapshot.lastPing)
        assertEquals("", snapshot.lastHeartbeatReset)
        assertNull(snapshot.seenGoodHeartbeat)
        assertNull(snapshot.wakelockReadsWithin)
        assertNull(snapshot.wakelockReadsExceeded)
        assertNull(snapshot.totalDelay)
    }

    @Test
    fun reconnectPolicy() {
        assertEquals("OFF", snapshot.reconnectAlarm)
        assertEquals("PT4.026S", snapshot.globalBackoff)
        assertEquals(
            listOf(NetworkBackoff(network = "Cell · 100", duration = "PT12S")),
            snapshot.networkBackoffs
        )
        assertEquals("FALLBACK_NONE", snapshot.fallbackMode)
    }

    @Test
    fun networkStatus() {
        assertEquals("WiFi · 102", snapshot.network.active)
        assertEquals("WiFi · 102", snapshot.network.preferred)
        assertEquals("WiFi · 102", snapshot.network.inUse)
        assertEquals(listOf("Cell · 100", "WiFi · 102"), snapshot.network.available)
    }

    @Test
    fun clientListsKeepNamesNotJustCounts() {
        assertEquals(2, snapshot.queuedClients)
        assertEquals(listOf("com.android.chrome", "org.telegram.messenger"), snapshot.queuedPackages)
        assertEquals(1, snapshot.affinityClients)
        assertEquals(listOf(AffinityScore("org.telegram.messenger", 0.921)), snapshot.affinityScores)
    }

    @Test
    fun schedulerPointerIsNotAPendingMessage() {
        // The block's only line here is GMS's "see SchedulerService dump" hint.
        // Counting every line read that hint as one queued message, so an idle
        // queue said "1 条" forever.
        assertEquals(0, snapshot.pendingMessages)
    }

    @Test
    fun eventRingStillParses() {
        val events = requireNotNull(GcmDumpParser.parse(dump)).events
        // Received, Close, Reconnect alarm delay — the ring keeps every one.
        assertEquals(3, events.size)
        assertEquals(GcmEvent.EventKind.MessageReceived, events[0].kind)
        assertEquals(20, GcmDumpParser.parseClose(events[1].text)?.code)
    }
}
