package io.github.howard20181.hyperos.fcmlive.records

import io.github.howard20181.hyperos.fcmlive.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden tests for a row's visible title.
 *
 * The class of bug this exists for: `GcmNetwork{…}` reached the screen, a
 * handover between two cell networks read as `切换为 Cell，原为 Cell`, a
 * `DISCONNECTED` capability flag turned into a bracketed English token. Each
 * of those was found by installing a build and reading a screenshot, because
 * the title was a `@Composable` and a JVM test could not call it.
 *
 * [eventTitle] now takes its lookups as [EventText], so a test can assert the
 * exact resource and the exact arguments that reach the screen. The assertions
 * read as "which string, filled with what" — the resource id is the golden,
 * the arguments are the payload, and a title that silently changes either one
 * fails here instead of on a phone.
 */
class EventTitlesTest {

    /** Records every lookup a title makes, in order. */
    private class RecordingText : EventText {
        data class Call(val id: Int, val args: List<Any>)

        val calls = ArrayList<Call>()

        override fun get(id: Int): String {
            calls += Call(id, emptyList())
            return "<$id>"
        }

        override fun format(id: Int, vararg args: Any): String {
            calls += Call(id, args.toList())
            return "<$id>"
        }
    }

    private fun titleOf(dumpLine: String, uptimeSeconds: Long? = null): RecordingText {
        val event = GcmDumpParser.parseEventsForTest(listOf(dumpLine)).single()
        val text = RecordingText()
        eventTitle(event, EventTaxonomy.kindOf(event), uptimeSeconds, text)
        return text
    }

    @Test
    fun sameTransportHandoverNamesBothNetIds() {
        // The train case: the mobile data did move, and the only evidence is
        // the netId. A title without the numbers would read as no change.
        val text = titleOf(
            "10-24 12:09:08.100 net=0: Active network switched to " +
                "GcmNetwork{103 Cell(0)}, was GcmNetwork{101 Cell(0)}"
        )

        assertEquals(
            listOf(
                RecordingText.Call(
                    R.string.records_event_network_switched_same,
                    listOf("Cell", "101", "103")
                )
            ),
            text.calls
        )
    }

    @Test
    fun crossTransportHandoverNamesBothTransports() {
        // Cell → WiFi: the transports already say which network is which, so
        // the netIds would only lengthen the line.
        val text = titleOf(
            "10-24 12:09:08.100 net=0: Active network switched to " +
                "GcmNetwork{106 WiFi(1)}, was GcmNetwork{101 Cell(0)}"
        )

        assertEquals(
            listOf(
                RecordingText.Call(
                    R.string.records_event_network_switched,
                    listOf("Cell 101", "WiFi 106")
                )
            ),
            text.calls
        )
    }

    @Test
    fun vanishedNetworkReadsAsDropped() {
        val text = titleOf(
            "10-24 12:09:08.100 net=0: Active network went away, " +
                "was GcmNetwork{103 Cell(0)}"
        )

        assertEquals(
            listOf(
                RecordingText.Call(
                    R.string.records_event_network_dropped,
                    listOf("Cell 103")
                )
            ),
            text.calls
        )
    }

    @Test
    fun closeFoldsItsUptimeAsATrailingClause() {
        // err:6 is not an orderly ending, so the row reads 原因，连接关闭 and
        // then the age: 432 s is 7 min 12 s.
        val text = titleOf("10-24 12:09:08.100 net=0: Close err:6 time:432", 432)

        assertEquals(
            listOf(
                // The age clause is built before the sentence that follows it:
                // 432 s → 7 分 12 秒, then the gloss, then 原因，连接关闭.
                RecordingText.Call(R.string.records_minutes, listOf(7, 12)),
                RecordingText.Call(
                    R.string.records_close_uptime,
                    listOf("<${R.string.records_minutes}>")
                ),
                RecordingText.Call(R.string.records_close_gloss_6, emptyList()),
                RecordingText.Call(
                    R.string.records_close_with,
                    listOf("<${R.string.records_close_gloss_6}>")
                )
            ),
            text.calls
        )
    }

    @Test
    fun orderlyCloseIsOneSentence() {
        // err:20 (normal end) is a whole sentence: no 连接关闭 clause, and no
        // age when the session start is unknown.
        val text = titleOf("10-24 12:09:08.100 net=0: Close err:20 time:60")

        assertEquals(
            listOf(RecordingText.Call(R.string.records_close_gloss_20, emptyList())),
            text.calls
        )
    }

    @Test
    fun samplingGapFoldsHowMuchIsMissing() {
        val event = GcmEvent(
            ts = 1_000,
            net = -1,
            kind = GcmEvent.EventKind.SamplingGap,
            text = "SamplingGap 42s"
        )
        val text = RecordingText()
        eventTitle(event, GcmEvent.EventKind.SamplingGap, null, text)

        assertEquals(
            listOf(
                // The inner duration is formatted first, then wrapped.
                RecordingText.Call(R.string.records_seconds, listOf("42")),
                RecordingText.Call(
                    R.string.records_event_sampling_gap,
                    listOf("<${R.string.records_seconds}>")
                )
            ),
            text.calls
        )
    }

    @Test
    fun unknownNetworkShapeFallsBackToTheKindLabel() {
        // Fail-open: a network line with no network object in it keeps a
        // readable title rather than an empty row.
        val text = titleOf("10-24 12:09:08.100 net=0: Active network went away")

        assertEquals(
            listOf(RecordingText.Call(R.string.records_event_network, emptyList())),
            text.calls
        )
    }

    @Test
    fun overdueReconnectAlarmReadsAsOverdue() {
        // The dump prints the delay in ms and the string carries the unit; a
        // negative delay means the alarm is already past due, so the row says
        // so and shows the magnitude instead of "-5000".
        val text = titleOf("10-24 12:09:08.100 net=0: Reconnect alarm delay: -5000 ms")

        assertEquals(
            listOf(
                RecordingText.Call(
                    R.string.records_event_reconnect_alarm_scheduled,
                    emptyList()
                ),
                RecordingText.Call(R.string.records_delay_overdue, listOf(5000L))
            ),
            text.calls
        )
    }

    private fun broadcastSuffix(arrivalMs: Long?): RecordingText {
        val event = GcmDumpParser.parseEventsForTest(
            listOf(
                "10-24 12:09:08.100 net=0: Successful broadcast to com.example " +
                    "(id=0:abc time=322ms priority=HIGH)"
            )
        ).single()
        val text = RecordingText()
        pushStageSuffix(event, EventTaxonomy.kindOf(event), arrivalMs, text)
        return text
    }

    @Test
    fun theEndToEndArrivalReplacesTheLastHopDuration() {
        // 2.3 s end to end, of which the broadcast hop was 322 ms. Printing
        // both made `，高优先级` / `，普通优先级` the clause an 11 sp line
        // dropped (the row read `普通…` on device), so the arrival is the only
        // number now — it already contains the hop.
        assertEquals(
            listOf(
                RecordingText.Call(R.string.records_push_arrival_s, listOf(2L, 3L)),
                RecordingText.Call(R.string.records_push_priority_high, emptyList())
            ),
            broadcastSuffix(2_300L).calls
        )
    }

    @Test
    fun withoutAnArrivalTheLastHopStays() {
        // No `Received` row to pair with: then the broadcast's own time is the
        // longest measurement there is, and dropping it would hide the only
        // number the row has.
        assertEquals(
            listOf(
                RecordingText.Call(R.string.records_push_duration, listOf(322)),
                RecordingText.Call(R.string.records_push_priority_high, emptyList())
            ),
            broadcastSuffix(null).calls
        )
    }
}
