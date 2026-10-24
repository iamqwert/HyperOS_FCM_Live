package io.github.howard20181.hyperos.fcmlive.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the events-page taxonomy to the lines actually produced by GcmService.
 *
 * The point is not to test Compose: it is to make the "which tab sees this
 * event" matrix explicit, so a later parser change cannot silently empty a
 * tab, duplicate an event across readings, or lose an unclassified line from
 * 「全部」.
 */
class RecordsTaxonomyTest {

    private val messageId = "0:1792724616128948%0f493ae6f9fd7ecd"

    private fun event(text: String): GcmEvent =
        GcmEvent(ts = 0L, net = 1, kind = GcmDumpParser.classifyText(text), text = text)

    private fun classes(text: String): EventClasses =
        EventTaxonomy.classes(event(text))

    private fun assertIn(tab: EventTab, text: String) {
        assertTrue("$text should be in $tab", classes(text).inTab(tab))
    }

    private fun assertNotIn(tab: EventTab, text: String) {
        assertFalse("$text should not be in $tab", classes(text).inTab(tab))
    }

    // ---------- connection ----------

    @Test
    fun connectedIsConnectionOnly() {
        assertEquals(setOf(EventLabel.Connection), classes("Connected").labels)
    }

    @Test
    fun loginIsConnectionOnly() {
        assertEquals(
            setOf(EventLabel.Connection),
            classes("Sent LoginRequest; HB interval sent: 230000").labels
        )
    }

    @Test
    fun iqStanzaIsConnectionProtocol() {
        for (text in listOf("Sent IqStanza 1", "Received IqStanza 1")) {
            val classes = classes(text)
            assertEquals(setOf(EventLabel.Connection), classes.labels)
            assertEquals(GcmEvent.EventKind.IqStanza, event(text).kind)
            assertIn(EventTab.Connection, text)
        }
    }

    @Test
    fun close27IsAnOrderlyHandover() {
        // 27 = replaced by a newer connection. GMS's table calls that the
        // connection doing what it should, so the row keeps its connection
        // label and stays out of 异常 — the 异常 tab is exactly "severity is
        // 需留意 or 异常", and this one is 正常.
        val classes = classes("Close err:27 time:1")
        assertEquals(setOf(EventLabel.Connection), classes.labels)
        assertNull(classes.errorStage)
        assertEquals(EventSeverity.Normal, EventTaxonomy.severity(event("Close err:27 time:1")))
    }

    @Test
    fun close30IsConnectionAndAnomalyInConnectionStage() {
        val classes = classes("Close err:30 time:67")
        assertEquals(setOf(EventLabel.Connection, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Connection, classes.errorStage)
    }

    @Test
    fun close20IsAnOrderlyClose() {
        // 20 = ERR_IO_FIN, a peer FIN: orderly. Same rule as 27.
        val classes = classes("Close err:20 time:65")
        assertEquals(setOf(EventLabel.Connection), classes.labels)
        assertNull(classes.errorStage)
        assertEquals(EventSeverity.Normal, EventTaxonomy.severity(event("Close err:20 time:65")))
    }

    @Test
    fun close6IsConnectionHeartbeatAndAnomalyInHeartbeatStage() {
        val classes = classes("Close err:6 time:7520")
        assertEquals(
            setOf(EventLabel.Connection, EventLabel.Heartbeat, EventLabel.Error),
            classes.labels
        )
        assertEquals(ErrorStage.Heartbeat, classes.errorStage)
    }

    @Test
    fun globalBackoffIsConnectionAndAnomalyInReconnectStage() {
        val classes = classes("Global backoff: PT8.388S")
        assertEquals(setOf(EventLabel.Connection, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Reconnect, classes.errorStage)
    }

    @Test
    fun reconnectAlarmDelayIsConnectionAndAnomalyInReconnectStage() {
        val classes = classes("Reconnect alarm delay: 4 ms")
        assertEquals(setOf(EventLabel.Connection, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Reconnect, classes.errorStage)
    }

    @Test
    fun reconnectSchedulerIsWatched() {
        // An alarm that is OFF is worth a glance but is not a failure: 需留意.
        val classes = classes("Reconnect Scheduler Alarm: OFF")
        assertEquals(setOf(EventLabel.Connection, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Reconnect, classes.errorStage)
        assertEquals(
            EventSeverity.Watch,
            EventTaxonomy.severity(event("Reconnect Scheduler Alarm: OFF"))
        )
    }

    // ---------- heartbeat ----------

    @Test
    fun heartbeatLinesAreHeartbeatOnly() {
        for (text in listOf(
            "Received Server HB, interval received: 0",
            "Sent Server HB Ack; interval sent: 0",
            "Sent Client HB; interval sent: 0",
            "Received Client HB Ack, interval received: 0"
        )) {
            assertEquals(setOf(EventLabel.Heartbeat), classes(text).labels)
        }
    }

    @Test
    fun heartbeatDelayIsWatchedNotFailed() {
        // A late alarm is 需留意, not 异常: the row lands in the anomaly tab
        // (amber dot) without claiming anything failed.
        val classes = classes("Heartbeat alarm delay: 902 ms")
        assertEquals(setOf(EventLabel.Heartbeat, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Heartbeat, classes.errorStage)
        assertEquals(
            EventSeverity.Watch,
            EventTaxonomy.severity(event("Heartbeat alarm delay: 902 ms"))
        )
    }

    @Test
    fun heartbeatTimeoutIsHeartbeatAndAnomalyInHeartbeatStage() {
        for (text in listOf(
            "Heartbeat timeout, GCM connection reset",
            "Heartbeat timeout, GCM connection reset GcmNetwork{102 WiFi(1)}"
        )) {
            val classes = classes(text)
            assertEquals(
                "$text should cross-label",
                setOf(EventLabel.Heartbeat, EventLabel.Error),
                classes.labels
            )
            assertEquals(ErrorStage.Heartbeat, classes.errorStage)
            assertIn(EventTab.Heartbeat, text)
            assertIn(EventTab.Error, text)
        }
    }

    // ---------- connection-only close family ----------

    @Test
    fun receivedCloseIsConnectionAndWatched() {
        // A close line with no code cannot be graded, and losing the connection
        // is worth a look: Connection plus 需留意.
        val classes = classes("Received Close")
        assertEquals(setOf(EventLabel.Connection, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Connection, classes.errorStage)
        assertEquals(GcmEvent.EventKind.ServerClose, event("Received Close").kind)
        assertIn(EventTab.Connection, "Received Close")
        assertIn(EventTab.Error, "Received Close")
    }

    @Test
    fun bareDisconnectedIsConnectionAndWatched() {
        val classes = classes("Disconnected")
        assertEquals(setOf(EventLabel.Connection, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Connection, classes.errorStage)
        assertEquals(GcmEvent.EventKind.ServerClose, event("Disconnected").kind)
    }

    @Test
    fun failedConnectionIsReconnectAnomaly() {
        val classes = classes("Failed connection err:35")
        assertEquals(
            setOf(EventLabel.Connection, EventLabel.Error),
            classes.labels
        )
        assertEquals(ErrorStage.Reconnect, classes.errorStage)
        assertIn(EventTab.Connection, "Failed connection err:35")
        assertIn(EventTab.Error, "Failed connection err:35")
    }

    @Test
    fun failedToConnectIsReconnectAnomaly() {
        val text = "Failed to connect to mtalk.google.com, trying alt5-mtalk.google.com"
        val classes = classes(text)
        assertEquals(setOf(EventLabel.Connection, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Reconnect, classes.errorStage)
        assertIn(EventTab.Connection, text)
        assertIn(EventTab.Error, text)
    }

    @Test
    fun disconnectSinceNoNetworksIsConnectionAndWatched() {
        val classes = classes("Disconnect since no networks are available")
        assertEquals(setOf(EventLabel.Connection, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Connection, classes.errorStage)
        assertEquals(
            GcmEvent.EventKind.ServerClose,
            event("Disconnect since no networks are available").kind
        )
        assertIn(EventTab.Connection, "Disconnect since no networks are available")
        assertIn(EventTab.Error, "Disconnect since no networks are available")
    }

    @Test
    fun disconnectSinceDisabledIsConnectionAndWatched() {
        val classes = classes("Disconnect since GCM is no longer enabled")
        assertEquals(setOf(EventLabel.Connection, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Connection, classes.errorStage)
    }

    // ---------- push ----------

    @Test
    fun receivedMessageIsPushOnly() {
        val text = "Received com.android.chrome $messageId"
        val classes = classes(text)
        assertEquals(setOf(EventLabel.Push), classes.labels)
        assertEquals(GcmEvent.EventKind.MessageReceived, event(text).kind)
        assertNotIn(EventTab.Error, text)
    }

    @Test
    fun ackedMessageIsPushOnly() {
        val text = "Acked com.android.chrome:0 $messageId"
        assertEquals(setOf(EventLabel.Push), classes(text).labels)
        assertEquals(GcmEvent.EventKind.MessageAcked, event(text).kind)
    }

    @Test
    fun completedBroadcastIsPushOnly() {
        val text = "Successful broadcast to com.android.chrome " +
            "(id=$messageId time=182ms priority=NORMAL)"
        assertEquals(setOf(EventLabel.Push), classes(text).labels)
        assertEquals(GcmEvent.EventKind.BroadcastCompleted, event(text).kind)
        assertNotIn(EventTab.Error, text)
    }

    @Test
    fun stoppedBroadcastIsPushAndAnomalyInBroadcastStage() {
        val text = "Failed to broadcast to stopped app com.example.app"
        val classes = classes(text)
        assertEquals(setOf(EventLabel.Push, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Broadcast, classes.errorStage)
        assertEquals(GcmEvent.EventKind.BroadcastFailed, event(text).kind)
    }

    @Test
    fun noResponseBroadcastIsPushAndAnomalyInBroadcastStage() {
        val text = "No response to broadcast from com.example.app"
        val classes = classes(text)
        assertEquals(setOf(EventLabel.Push, EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Broadcast, classes.errorStage)
        assertEquals(GcmEvent.EventKind.BroadcastNoResponse, event(text).kind)
    }

    // ---------- sleep ----------

    @Test
    fun dozeIsSleep() {
        assertIn(EventTab.Sleep, "Client Entering doze")
        assertIn(EventTab.Sleep, "Client Exiting doze")
    }

    // ---------- unclassified ----------

    @Test
    fun restartMarkerIsWatchedAndKeptInAll() {
        // The marker carries no line of its own, so it has no 竖切 label — but
        // a GMS restart wipes every counter the status page shows, which is
        // 需留意, so the 异常 tab keeps it.
        val restart = GcmEvent(ts = 0L, net = -1, kind = GcmEvent.EventKind.GmsRestart, text = "")
        val classes = EventTaxonomy.classes(restart)
        assertEquals(setOf(EventLabel.Error), classes.labels)
        assertEquals(ErrorStage.Connection, classes.errorStage)
        assertTrue(classes.inTab(EventTab.All))
        assertTrue(classes.inTab(EventTab.Error))
    }

    @Test
    fun unknownLineIsUnclassifiedAndKeptInAll() {
        val text = "some future GCM line"
        assertTrue(classes(text).labels.isEmpty())
        assertIn(EventTab.All, text)
        assertNotIn(EventTab.Connection, text)
        assertNotIn(EventTab.Heartbeat, text)
        assertNotIn(EventTab.Push, text)
        assertNotIn(EventTab.Sleep, text)
        assertNotIn(EventTab.Error, text)
    }

    // ---------- tab coverage ----------

    @Test
    fun allEventsTabKeepsEveryLineIncludingUnknownOnes() {
        val lines = listOf(
            "Connected",
            "Sent LoginRequest; HB interval sent: 230000",
            "Heartbeat alarm delay: 902 ms",
            "Close err:6 time:7520",
            "Close err:30 time:67",
            "Client Entering doze",
            "Received com.android.chrome $messageId",
            "Successful broadcast to com.android.chrome (id=$messageId time=182ms priority=NORMAL)",
            "Sent IqStanza 1",
            "some future GCM line"
        )
        for (line in lines) {
            assertIn(EventTab.All, line)
        }
    }

    @Test
    fun anomalyTabIsCrossCuttingWithoutDuplicatingAnEvent() {
        val lines = listOf(
            "Close err:20 time:65",
            "Close err:27 time:1",
            "Close err:30 time:67",
            "Close err:6 time:7520",
            "Global backoff: PT8.388S",
            "Reconnect alarm delay: 4 ms",
            "Failed to broadcast to stopped app com.example.app",
            "No response to broadcast from com.example.app",
            "Connected",
            "Client Entering doze"
        )
        val events = lines.map(::event)
        val inError = events.filter { EventTaxonomy.classes(it).inTab(EventTab.Error) }
        // Six, not eight: 20 and 27 are orderly closes and are 正常, so they stay
        // in 连接 only.
        assertEquals(6, inError.size)
        assertEquals(
            "an event must appear once in a tab, no matter how many labels it has",
            inError.size,
            inError.distinctBy { it.ts to it.text }.size
        )
        assertTrue(
            "close 20 is also a connection event; the anomaly tab must not remove it there",
            EventTaxonomy.classes(events[0]).inTab(EventTab.Connection)
        )
        assertTrue(
            "a broadcast failure is also a push event",
            EventTaxonomy.classes(events[6]).inTab(EventTab.Push)
        )
    }

    // ---------- network-change title extraction ----------

    @Test
    fun networkTargetExtractionCoversEveryRawShape() {
        // The event text arrives prettified (`GcmNetwork{106 WiFi(1)}` already
        // rewritten to `WiFi · 106`), which is the form these rows carry.
        assertEquals(
            "WiFi · 106",
            GcmDumpParser.parseNetworkTarget("Active network switched to WiFi · 106, was Cell · 103")
        )
        assertEquals(
            "WiFi · 104",
            GcmDumpParser.parseNetworkTarget("Active network is now WiFi · 104")
        )
        assertEquals(
            "WiFi · 106",
            GcmDumpParser.parseNetworkTarget("onGcmNetworkChanged: WiFi · 106")
        )
        assertEquals(
            "the `went away` shape names the network it lost",
            "Cell · 103",
            GcmDumpParser.parseNetworkTarget("Active network went away, was Cell · 103")
        )
        assertNull(GcmDumpParser.parseNetworkTarget("some future GCM network line"))
    }

    @Test
    fun networkGoneIsOnlyTheVanishingShape() {
        assertTrue(GcmDumpParser.isNetworkGone("Active network went away, was Cell · 103"))
        assertFalse(GcmDumpParser.isNetworkGone("Active network switched to WiFi · 106, was Cell · 103"))
        assertFalse(GcmDumpParser.isNetworkGone("Active network is now WiFi · 104"))
        assertFalse(GcmDumpParser.isNetworkGone("onGcmNetworkChanged: WiFi · 106"))
    }

    @Test
    fun networkSwitchRegexTakesTheTargetNotThePreviousNetwork() {
        // A handover line names two networks; the title must carry the one the
        // client landed on.
        val text = "Active network switched to WiFi · 106, was Cell · 103"
        assertEquals("WiFi · 106", GcmDumpParser.parseNetworkTarget(text))
        assertFalse(GcmDumpParser.isNetworkGone(text))
    }
}
