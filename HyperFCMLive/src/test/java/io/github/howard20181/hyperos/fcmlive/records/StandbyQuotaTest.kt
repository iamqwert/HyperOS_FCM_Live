package io.github.howard20181.hyperos.fcmlive.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the App Standby → high-priority quota mapping to GMS's own table.
 *
 * The table is not folklore: `gcm_high_priority_quotas` defaults to
 * `"30,20,40,5"` and `Lcobk;->c` looks it up with `floorEntry(bucket)`
 * (`FCM_BEHAVIOR_EVIDENCE.md` §12.2). Two properties follow from floor-entry
 * and are easy to "fix" wrongly later, so both are asserted directly:
 *
 *  - a bucket **below the smallest key** gets no entry at all, which GMS reads
 *    as `Integer.MAX_VALUE` — i.e. unlimited, not zero;
 *  - a bucket **above the largest key** keeps the last row, so RESTRICTED (45)
 *    and NEVER (50) are 5/day, not "less than 5".
 */
class StandbyQuotaTest {

    @Test
    fun `active and working-set buckets are unlimited`() {
        // 5 = EXEMPTED, 10 = ACTIVE, 20 = WORKING_SET: all below the first key.
        assertEquals(StandbyQuota.UNLIMITED, StandbyQuota.quotaFor(5))
        assertEquals(StandbyQuota.UNLIMITED, StandbyQuota.quotaFor(10))
        assertEquals(StandbyQuota.UNLIMITED, StandbyQuota.quotaFor(20))
        assertEquals(StandbyQuota.UNLIMITED, StandbyQuota.quotaFor(29))
    }

    @Test
    fun `frequent bucket caps at twenty`() {
        assertEquals(20, StandbyQuota.quotaFor(30))
        assertEquals(20, StandbyQuota.quotaFor(39))
    }

    @Test
    fun `rare and below caps at five`() {
        assertEquals(5, StandbyQuota.quotaFor(40))
        // floorEntry keeps the 40 row for anything above it.
        assertEquals(5, StandbyQuota.quotaFor(45))
        assertEquals(5, StandbyQuota.quotaFor(50))
    }

    @Test
    fun `a bucket that could not be read is unlimited`() {
        // Lcnxc->a returns -1 when UsageStatsManager refuses.
        assertEquals(StandbyQuota.UNLIMITED, StandbyQuota.quotaFor(-1))
        assertFalse(StandbyQuota.isLimited(-1))
    }

    @Test
    fun `only buckets at or above frequent are advertised as capped`() {
        assertFalse(StandbyQuota.isLimited(20))
        assertTrue(StandbyQuota.isLimited(30))
        assertTrue(StandbyQuota.isLimited(40))
    }

    @Test
    fun `bucket names cover the platform values`() {
        assertEquals("ACTIVE", StandbyQuota.bucketName(10))
        assertEquals("RARE", StandbyQuota.bucketName(40))
        // Unknown values fall back to the number rather than inventing a name.
        assertEquals("99", StandbyQuota.bucketName(99))
    }

    @Test
    fun `client list is read out of the dump block and stops at the next section`() {
        val dump = """
            SERVICE com.google.android.gms/.gcm.GcmService abc pid=1 user=0
              Client:
                Queued messages:

                Apps supporting client queue:
                com.tencent.mm:0 v39892310
                com.twitter.android:0 v2147483647
                com.openai.chatgpt:0 v2629319

                Affinity Scores:
                com.twitter.android 0.5
        """.trimIndent()
        assertEquals(
            listOf("com.tencent.mm", "com.twitter.android", "com.openai.chatgpt"),
            StandbyQuota.packagesFromDump(dump)
        )
    }

    @Test
    fun `an absent client block yields nothing instead of throwing`() {
        assertEquals(emptyList<String>(), StandbyQuota.packagesFromDump("SERVICE x\n"))
        assertEquals(emptyList<String>(), StandbyQuota.packagesFromDump(""))
    }

    @Test
    fun `bridge encoding round-trips and drops malformed pairs`() {
        val buckets = linkedMapOf("com.a" to 40, "com.b" to 5)
        assertEquals(buckets, StandbyQuota.decode(StandbyQuota.encode(buckets)))
        // No '=' / ';' can appear in a package name, so the format is lossless;
        // a garbage payload must degrade to "unknown", never to an exception.
        assertEquals(emptyMap<String, Int>(), StandbyQuota.decode(null))
        assertEquals(emptyMap<String, Int>(), StandbyQuota.decode("com.a"))
        assertEquals(mapOf("com.a" to 40), StandbyQuota.decode("com.a=40;bad;=7"))
    }
}
