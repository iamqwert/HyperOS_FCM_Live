package io.github.howard20181.hyperos.fcmlive.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun closeRowsCarryTheSessionTheyEnded() {
        // Newest first, the way the archive holds them. The close at 3000s
        // belongs to the session opened at 2000s (one second up); the one at
        // 1000s has no `Connected` before it in the archive, so it stays
        // absent — a duration must never be guessed from an earlier session.
        val closedYoung = GcmEvent(3_000, 1, GcmEvent.EventKind.Close, "Close err:6 time:1")
        val connected = GcmEvent(2_000, 1, GcmEvent.EventKind.Connected, "Connected")
        val closedOrphan = GcmEvent(1_000, 1, GcmEvent.EventKind.Close, "Close err:20 time:1")
        val uptimes = EventTaxonomy.connectionUptimes(listOf(closedYoung, connected, closedOrphan))
        assertEquals(1L, uptimes[closedYoung])
        assertNull(uptimes[closedOrphan])
        assertEquals(1, uptimes.size)
    }

    @Test
    fun samplingGapMarkerIsItsOwnKind() {
        // The store's overflow row: its text would classify as `Other`, so the
        // stored kind is what keeps it recognisable after a reload.
        val marker = GcmEvent(12_000, -1, GcmEvent.EventKind.SamplingGap, "SamplingGap 42s")
        assertEquals(GcmEvent.EventKind.SamplingGap, EventTaxonomy.kindOf(marker))
        assertEquals(EventSeverity.Watch, EventTaxonomy.severity(marker))
        assertEquals(42L, GcmDumpParser.parseSamplingGapSeconds(marker.text))
        // It belongs to 异常 (Watch severity) and to 全部 only.
        assertTrue(EventTaxonomy.classes(marker).inTab(EventTab.Error))
        assertFalse(EventTaxonomy.classes(marker).inTab(EventTab.Connection))
    }

    @Test
    fun pushStagesShareTheArrivalTime() {
        // One delivery, newest first. Both later stages print the same message
        // id, so both can say how long after arrival they happened — the
        // number "the push is slow" never had.
        val dump = """
            10-24 12:10:02.300 net=0: Successful broadcast to com.example (id=0:abc time=322ms priority=HIGH)
            10-24 12:10:01.200 net=0: Acked com.example:7 0:abc
            10-24 12:10:00.000 net=0: Received com.example 0:abc
        """.trimIndent().lines()

        val events = GcmDumpParser.parseEventsForTest(dump)
        val arrivals = EventTaxonomy.pushArrivals(events)
        val acked = events.first { it.text.startsWith("Acked ") }
        val completed = events.first { it.text.startsWith("Successful broadcast") }

        assertEquals(1_200L, arrivals[acked])
        assertEquals(2_300L, arrivals[completed])
        // The arrival row itself is the clock, not a measurement.
        assertNull(arrivals[events.first { it.text.startsWith("Received ") }])
    }

    @Test
    fun pushStagesWithoutAnArrivalStaySilent() {
        // No `Received` in the archive (rolled out, or read before it landed):
        // a stage row must not borrow another delivery's clock.
        val dump = """
            10-24 12:10:02.300 net=0: Successful broadcast to com.example (id=0:abc time=322ms priority=HIGH)
        """.trimIndent().lines()

        val events = GcmDumpParser.parseEventsForTest(dump)
        assertTrue(EventTaxonomy.pushArrivals(events).isEmpty())
    }

    @Test
    fun redeliveryRestartsTheClock() {
        // The same id printed twice: the later arrival is the one a later
        // stage belongs to, so the gap is measured from the second print.
        val dump = """
            10-24 12:10:30.000 net=0: Acked com.example:7 0:abc
            10-24 12:10:20.000 net=0: Received com.example 0:abc
            10-24 12:10:00.000 net=0: Received com.example 0:abc
        """.trimIndent().lines()

        val events = GcmDumpParser.parseEventsForTest(dump)
        val arrivals = EventTaxonomy.pushArrivals(events)
        val acked = events.first { it.text.startsWith("Acked ") }
        assertEquals(10_000L, arrivals[acked])
    }

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
        // rewritten to `WiFi 106`), which is the form these rows carry.
        assertEquals(
            "WiFi 106",
            GcmDumpParser.parseNetworkTarget("Active network switched to WiFi 106, was Cell 103")
                ?.format()
        )
        assertEquals(
            "WiFi 104",
            GcmDumpParser.parseNetworkTarget("Active network is now WiFi 104")?.format()
        )
        assertEquals(
            "WiFi 106",
            GcmDumpParser.parseNetworkTarget("onGcmNetworkChanged: WiFi 106")?.format()
        )
        assertEquals(
            "the `went away` shape names the network it lost",
            "Cell 103",
            GcmDumpParser.parseNetworkTarget("Active network went away, was Cell 103")?.format()
        )
        assertNull(GcmDumpParser.parseNetworkTarget("some future GCM network line"))
    }

    @Test
    fun aDisconnectedNetworkKeepsItsNetIdButLosesItsBraces() {
        // What the device actually prints once a network is down:
        // `GcmNetwork{103 Cell(0) DISCONNECTED}`. The braces must not survive
        // into a title, and the netId and the state word must — the netId is
        // what tells two cell networks apart, the state explains the
        // `Skipping connect attempt since the network is suspended` that
        // follows it.
        val text = GcmDumpParser.prettifyEventText(
            "Active network switched to GcmNetwork{103 Cell(0) DISCONNECTED}, " +
                "was GcmNetwork{105 Cell(0)}"
        )
        assertEquals(
            "Active network switched to Cell 103 (DISCONNECTED), was Cell 105",
            text
        )
        val target = GcmDumpParser.parseNetworkTarget(text)
        assertEquals("Cell 103 (DISCONNECTED)", target?.format())
        assertEquals("103", target?.netId)
        assertTrue("the dump marks this one as gone", target!!.isDown)
        // Cell -> Cell is still a handover: the two netIds differ.
        val previous = GcmDumpParser.parseNetworkPrevious(text)
        assertEquals("Cell 105", previous?.format())
        assertNotEquals(target.netId, previous?.netId)
    }

    @Test
    fun prettifyReadsTheVpnTailAndTheSuspendedState() {
        // A VPN object names the network it rides in brackets; the number in
        // there is a transport type id, not a netId, so it is dropped.
        assertEquals(
            "Active network is now VPN 104 [WiFi]",
            GcmDumpParser.prettifyEventText(
                "Active network is now GcmNetwork{104 VPN(17) [WiFi(1)]}"
            )
        )
        assertEquals(
            "Active network went away, was Cell 103 (DISCONNECTED)",
            GcmDumpParser.prettifyEventText(
                "Active network went away, was GcmNetwork{103 Cell(0) DISCONNECTED}"
            )
        )
    }

    @Test
    fun capabilityFlagsAreNotStateWords() {
        // `BYPASSABLE` / `RESTRICTED` / `CONSTRAINED` are properties of a
        // network, not something that happened to it. Taking the first cap
        // word as the state put an untranslated token in a row's title
        // (`已变更为 VPN 105（BYPASSABLE）`) and lengthened the line for
        // nothing; only `DISCONNECTED` survives now.
        val text = GcmDumpParser.prettifyEventText(
            "Active network is now GcmNetwork{105 VPN(17) [WiFi(1)] BYPASSABLE}"
        )
        assertEquals("Active network is now VPN 105 [WiFi]", text)
        assertNull(GcmDumpParser.parseNetworkTarget(text)?.state)
    }

    @Test
    fun nestedMcsConnectionKeepsTheNetId() {
        // The nested wrapper HyperOS 4 prints (see the McsConnection regex);
        // its inner object is a GcmNetwork token, braces and all.
        assertEquals(
            "Cell 100",
            GcmDumpParser.prettifyEventText("McsConnection{GcmNetwork{100 Cell(0)}, type=0}")
        )
    }

    @Test
    fun networkGoneIsOnlyTheVanishingShape() {
        assertTrue(GcmDumpParser.isNetworkGone("Active network went away, was Cell 103"))
        assertFalse(GcmDumpParser.isNetworkGone("Active network switched to WiFi 106, was Cell 103"))
        assertFalse(GcmDumpParser.isNetworkGone("Active network is now WiFi 104"))
        assertFalse(GcmDumpParser.isNetworkGone("onGcmNetworkChanged: WiFi 106"))
    }

    @Test
    fun networkSwitchRegexTakesTheTargetNotThePreviousNetwork() {
        // A handover line names two networks; the title must carry the one the
        // client landed on.
        val text = "Active network switched to WiFi 106, was Cell 103"
        assertEquals("WiFi 106", GcmDumpParser.parseNetworkTarget(text)?.format())
        assertFalse(GcmDumpParser.isNetworkGone(text))
    }

    // ---------- the "how did it change?" answer ----------

    @Test
    fun aSameTransportHandoverIsAChangeBecauseTheNetIdDiffers() {
        // Mobile data moving from one cell network to another is a real
        // change even though the transport is identical — the netId is what
        // tells the two apart, and dropping it produced the tautology
        // `切换为 Cell，原为 Cell`.
        val text = GcmDumpParser.prettifyEventText(
            "Active network switched to GcmNetwork{107 Cell(0)}, was GcmNetwork{103 Cell(0)}"
        )
        val target = GcmDumpParser.parseNetworkTarget(text)
        val previous = GcmDumpParser.parseNetworkPrevious(text)
        assertEquals("Cell 107", target?.format())
        assertEquals("Cell 103", previous?.format())
        assertFalse(
            "two cell networks with different netIds are two networks",
            target!!.sameAs(previous!!)
        )
        assertEquals(
            "the same netId is the same network",
            true,
            target.sameAs(GcmDumpParser.NetworkRef("Cell", "107"))
        )
    }

    @Test
    fun aBareTransportNameIsNotComparableToANetworkWithAnId() {
        // GMS prints the bare transport on some paths (the `was` side of a
        // handover), which says nothing about WHICH network it was. Such a
        // pair must never be read as "the same network".
        val bare = GcmDumpParser.NetworkRef("Cell", null)
        val withId = GcmDumpParser.NetworkRef("Cell", "103")
        assertFalse("a bare name cannot be matched", bare.sameAs(withId))
        assertFalse("nor the other way round", withId.sameAs(bare))
    }

    @Test
    fun theCallbackLineBorrowsThePreviousNetworkFromItsSibling() {
        // GMS's callback prints `onGcmNetworkChanged: <new>` and then the
        // handover line naming both ends — the callback's own line carries
        // only the new network, so without the sibling it cannot answer
        // "how did it change?".
        val dump = """
            10-24 12:09:08.100 net=0: Active network switched to GcmNetwork{103 Cell(0)}, was GcmNetwork{101 Cell(0)}
            10-24 12:09:08.150 net=0: onGcmNetworkChanged: GcmNetwork{103 Cell(0)}
        """.trimIndent().lines()

        val events = GcmDumpParser.parseEventsForTest(dump)
        assertEquals(2, events.size)
        val callback = events.first { it.text.startsWith("onGcmNetworkChanged: ") }
        assertEquals("Cell 103", GcmDumpParser.parseNetworkTarget(callback.text)?.format())
        assertEquals(
            "the callback line now knows the network it came from",
            "Cell 101",
            callback.previousNetwork?.format()
        )
        val handover = events.first { it.text.startsWith("Active network switched to ") }
        assertEquals("Cell 101", handover.previousNetwork?.format())
    }

    @Test
    fun aCallbackLineWithNoMatchingSiblingKeepsOnlyWhatItSays() {
        // Nothing to pair it with: the row must not invent a previous network.
        val dump = """
            10-24 12:09:08.150 net=0: onGcmNetworkChanged: GcmNetwork{103 Cell(0)}
        """.trimIndent().lines()

        val events = GcmDumpParser.parseEventsForTest(dump)
        assertEquals(1, events.size)
        assertNull(events.first().previousNetwork)
    }
}
