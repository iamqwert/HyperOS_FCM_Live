package io.github.howard20181.hyperos.fcmlive.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The network rows spell a network as `Cell 103` — a plain space, no `·`.
 *
 * That is a **change of format**, and the archive keeps seven days of rows, so
 * both shapes are on the page at once after an upgrade: every stored event is
 * re-prettified on load (`RecordsStore.eventsFrom` → `prettifyEventText`), and
 * that pass has to retire the old mid-dot or the same network prints two ways
 * in adjacent rows.
 *
 * The three things this pins:
 *
 *  1. what the formatter emits now;
 *  2. that the old shape still **parses** — a handover recorded before the
 *     change must keep its netId, or it collapses back into the tautology
 *     「切换为 Cell，原为 Cell」 this project already fixed once;
 *  3. that the healing pass does not touch anything else.
 */
class NetworkSeparatorTest {

    @Test
    fun `the formatter emits a space, not a mid-dot`() {
        val ref = GcmDumpParser.NetworkRef(transport = "Cell", netId = "103")
        assertEquals("Cell 103", ref.format())
        assertFalse(ref.format().contains('·'))
    }

    @Test
    fun `state and underlying tail keep their shape`() {
        val ref = GcmDumpParser.NetworkRef(
            transport = "VPN",
            netId = "105",
            state = "DISCONNECTED",
            underlying = "WiFi 102"
        )
        assertEquals("VPN 105 (DISCONNECTED) [WiFi 102]", ref.format())
    }

    @Test
    fun `a stored mid-dot row is healed on load`() {
        assertEquals(
            "onGcmNetworkChanged: Cell 103",
            GcmDumpParser.normalizeLegacySeparator("onGcmNetworkChanged: Cell · 103")
        )
        assertEquals(
            "Active network switched to Cell 107, was Cell 103",
            GcmDumpParser.normalizeLegacySeparator(
                "Active network switched to Cell · 107, was Cell · 103"
            )
        )
    }

    @Test
    fun `the healing pass leaves other text alone`() {
        val untouched = "Received Server HB, interval received: 0"
        assertEquals(untouched, GcmDumpParser.normalizeLegacySeparator(untouched))
        // Already-healed text must stay healed (the pass runs on every load).
        assertEquals("Cell 103", GcmDumpParser.normalizeLegacySeparator("Cell 103"))
    }

    @Test
    fun `an old row still parses its netId, so a handover stays visible`() {
        // This is the case that must not regress: without the netId the two
        // ends of a mobile-data handover read as the same network.
        val target = GcmDumpParser.parseNetworkTarget("Active network is now Cell · 107")
        assertNotNull(target)
        assertEquals("107", target?.netId)
        assertEquals("Cell 107", target?.format())

        val previous = GcmDumpParser.parseNetworkTarget("Active network went away, was Cell · 103")
        assertNotNull(previous)
        assertEquals("103", previous?.netId)
    }

    @Test
    fun `a fresh dump line is prettified without the mid-dot`() {
        // The wrapper (`McsConnection{…}, type=0`) is dropped whole, not just
        // its braces: the row names a network, and `type=0` is the dump's own
        // transport enum, already carried by the transport word.
        val text = GcmDumpParser.prettifyEventText(
            "Active network is now McsConnection{GcmNetwork{105 Cell(0)}, type=0}"
        )
        assertEquals("Active network is now Cell 105", text)
        assertFalse(text.contains('·'))
    }
}
