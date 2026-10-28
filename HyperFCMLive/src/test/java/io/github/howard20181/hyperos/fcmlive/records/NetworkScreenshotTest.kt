package io.github.howard20181.hyperos.fcmlive.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rows the user actually saw, reproduced from the screenshots of the
 * 2026-10-24 build. These are the exact event-ring lines the device printed
 * (the screenshots show the rendered titles, whose network objects carry the
 * braces and the state word the dump prints).
 */
class NetworkScreenshotTest {

    /** The ring as the device printed it on 10-24, newest first. */
    private val ring = listOf(
        "10-24 12:09:08.000 net=0: onGcmNetworkChanged: GcmNetwork{103 Cell(0) DISCONNECTED}",
        "10-24 12:09:08.000 net=0: onGcmNetworkChanged: GcmNetwork{103 Cell(0) DISCONNECTED}",
        "10-24 12:09:08.000 net=0: Active network went away, was GcmNetwork{103 Cell(0) DISCONNECTED}",
        "10-24 12:08:50.000 net=0: Skipping connect attempt since the network is suspended. Network: GcmNetwork{103 Cell(0) DISCONNECTED}",
        "10-24 12:04:39.000 net=0: onGcmNetworkChanged: GcmNetwork{103 Cell(0) DISCONNECTED}",
        "10-24 12:04:39.000 net=0: onGcmNetworkChanged: GcmNetwork{103 Cell(0) DISCONNECTED}",
        "10-24 12:04:39.000 net=0: Active network switched to GcmNetwork{103 Cell(0) DISCONNECTED}, was Cell",
        "10-24 11:16:57.000 net=0: onGcmNetworkChanged: Cell",
        "10-24 11:16:57.000 net=0: Connected"
    )

    @Test
    fun noTitleEverCarriesTheRawBraces() {
        val events = GcmDumpParser.parseEventsForTest(ring)
        for (event in events) {
            assertEquals(
                "no braces survive into a row: ${event.text}",
                false,
                event.text.contains("GcmNetwork{")
            )
        }
    }

    @Test
    fun theHandoverRowNamesBothEndsWithTheirNetIds() {
        val events = GcmDumpParser.parseEventsForTest(ring)
        val handover = events.first { it.text.startsWith("Active network switched to ") }
        assertEquals(
            "the landed network keeps its netId and its state",
            "Cell 103 (DISCONNECTED)",
            GcmDumpParser.parseNetworkTarget(handover.text)?.format()
        )
        assertEquals(
            "the previous network is named too",
            "Cell",
            handover.previousNetwork?.format()
        )
    }

    @Test
    fun theCallbackRowsLearnTheNetworkTheyCameFrom() {
        val events = GcmDumpParser.parseEventsForTest(ring)
        val callbacks = events.filter { it.text.startsWith("onGcmNetworkChanged: ") }
        // Three: the 12:04:39 pair is one row (the device printed it twice
        // with the same stamp and the same text, and the ring dedupes on
        // `ts|text`), plus one from 12:09:08 and one from 11:16:57.
        assertEquals("three callback lines", 3, callbacks.size)
        // The one written at the same moment as the handover line can say
        // where it came from. Here the handover's own `was` is the bare
        // transport the device printed (`was Cell`, no netId) — so that is
        // what the callback learns; the id it lacks is the dump's, not ours.
        val withPrevious = callbacks.filter { it.previousNetwork != null }
        assertEquals("the callback of that change", 1, withPrevious.size)
        assertEquals("Cell", withPrevious.first().previousNetwork?.format())
        // The others stay with what their own line says: one names a bare
        // transport (no id to pair on), the other was written minutes away
        // from the handover — a different change of the same network, and
        // borrowing that one's history would be a lie.
        val without = callbacks.filter { it.previousNetwork == null }
        assertEquals("the other two keep only their own line", 2, without.size)
        assertEquals(
            "the bare-transport one",
            "Cell",
            without.first { it.text.endsWith("Cell") }.text.removePrefix("onGcmNetworkChanged: ")
        )
        assertEquals(
            "the later one names its own network and nothing more",
            "Cell 103 (DISCONNECTED)",
            without.first { it.text.startsWith("onGcmNetworkChanged: Cell ") }.text
                .removePrefix("onGcmNetworkChanged: ")
        )
    }

    @Test
    fun theSameTransportHandoverIsVisibleAsSuch() {
        // The user's second complaint: mobile data moving to another mobile
        // data network is a change too, and it must not read as a no-op.
        val lines = listOf(
            "10-24 12:11:51.000 net=0: onGcmNetworkChanged: GcmNetwork{107 Cell(0) DISCONNECTED}",
            "10-24 12:11:51.000 net=0: Active network switched to GcmNetwork{107 Cell(0) DISCONNECTED}, was GcmNetwork{103 Cell(0)}"
        )
        val events = GcmDumpParser.parseEventsForTest(lines)
        val handover = events.first { it.text.startsWith("Active network switched to ") }
        assertEquals("Cell 107 (DISCONNECTED)", GcmDumpParser.parseNetworkTarget(handover.text)?.format())
        assertEquals("Cell 103", handover.previousNetwork?.format())

        val callback = events.first { it.text.startsWith("onGcmNetworkChanged: ") }
        assertNotNull("the callback knows where it came from", callback.previousNetwork)
        assertEquals("Cell 103", callback.previousNetwork?.format())
    }

    @Test
    fun aLaterChangeOfTheSameNetworkDoesNotBorrowTheEarlierHistory() {
        // The same netId changing hands twice in one ring: the second
        // handover is minutes away from the first, and a notification of that
        // later moment must not be given the first one's "was".
        val lines = listOf(
            "10-24 12:04:39.000 net=0: Active network switched to GcmNetwork{103 Cell(0)}, was GcmNetwork{101 Cell(0)}",
            "10-24 12:09:08.000 net=0: onGcmNetworkChanged: GcmNetwork{103 Cell(0)}"
        )
        val events = GcmDumpParser.parseEventsForTest(lines)
        val callback = events.first { it.text.startsWith("onGcmNetworkChanged: ") }
        assertNull(
            "minutes away is a different change, so the earlier one is not its history",
            callback.previousNetwork
        )
    }
}
