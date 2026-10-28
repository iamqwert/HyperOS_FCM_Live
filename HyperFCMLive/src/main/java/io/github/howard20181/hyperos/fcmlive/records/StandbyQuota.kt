package io.github.howard20181.hyperos.fcmlive.records

/**
 * GMS's **per-app daily high-priority quota**, derived from the app's App
 * Standby bucket.
 *
 * This is not a guess and not a heuristic: it is GMS's own table, read out of
 * the bytecode (`FCM_BEHAVIOR_EVIDENCE.md` §12). Two Phenotype flags carry it,
 * both in the `com.google.android.gms.gcm` package:
 *
 *  - `gcm_high_priority_quotas = "30,20,40,5"` — parsed pairwise into a
 *    `NavigableMap<Integer,Integer>` by `Lcobk;->d()` and consulted with
 *    `floorEntry(bucket)` in `Lcobk;->c()`. So bucket ≤ 29 finds no key and
 *    means "unlimited"; 30..39 → 20; ≥ 40 → 5.
 *  - `gcm_high_priority_quota_period_millis = 86_400_000` (24 h) — the window
 *    the counter resets on.
 *
 * `Lcobk;->b(Lcocb)` then returns `max(0, quota - used)`, and `Lcnyc;->b`
 * **suppresses the delivery** (returns false, no `FINISHED_AFTER_HANDLED`, so
 * the server retries against TTL) once that reaches 0 — unless the app's
 * notification-affinity score clears `0.1`
 * (`AaBatteryBucketing__notification_affinity_throttler_threshold`).
 *
 * Only the **ceiling** is derivable here. The "used" half lives in GMS's
 * private store and is not readable from outside its process, so the page
 * states a limit, never a remainder.
 *
 * The bucket itself has to be read in `system_server` (the `getAppStandbyBucket
 * (String)` overload is gated on `PACKAGE_USAGE_STATS`, which the app process
 * does not hold); the mapping below is a pure function so it can be pinned by
 * a unit test without a device.
 */
object StandbyQuota {

    /** Returned by [quotaFor] when the bucket maps to no limit at all. */
    const val UNLIMITED = -1

    /**
     * `gcm_high_priority_quotas`, in the flag's own pairwise order and read
     * with floor-entry semantics: the first entry whose key the bucket reaches
     * or exceeds wins.
     */
    private val TABLE = listOf(40 to 5, 30 to 20)

    /** Buckets at or above this value carry a limit (i.e. exclude ACTIVE 10 / WORKING_SET 20). */
    private const val LEAST_LIMITED_BUCKET = 30

    /**
     * Daily high-priority messages allowed for [bucket], or [UNLIMITED].
     *
     * A negative bucket is what GMS itself uses for "could not read a bucket"
     * (`Lcnxc;->a` returns -1 on failure) and is treated as unlimited, matching
     * `floorEntry` returning null for it.
     */
    fun quotaFor(bucket: Int): Int {
        if (bucket < 0) return UNLIMITED
        for ((from, quota) in TABLE) {
            if (bucket >= from) return quota
        }
        return UNLIMITED
    }

    /** True when [bucket] is one GMS would throttle against a daily cap. */
    fun isLimited(bucket: Int): Boolean = bucket >= LEAST_LIMITED_BUCKET

    /**
     * App Standby bucket names, for the row's value text prefer names over
     * raw numbers. Only the four the platform actually hands out are listed;
     * anything else falls back to the number.
     */
    fun bucketName(bucket: Int): String = when (bucket) {
        5 -> "EXEMPTED"
        10 -> "ACTIVE"
        20 -> "WORKING_SET"
        30 -> "FREQUENT"
        40 -> "RARE"
        45 -> "RESTRICTED"
        50 -> "NEVER"
        else -> bucket.toString()
    }

    /**
     * The package list out of a GCM dump's `Apps supporting client queue:`
     * block.
     *
     * Read here rather than shared with [GcmDumpParser] on purpose: the hook
     * side only needs the names to look buckets up, and pulling the full parser
     * into `system_server` for that would drag the event model along with it.
     * The block is a flat list of `pkg:user v<opaque>` lines ending at the next
     * section header, so the extraction is a prefix test, not a parse.
     */
    fun packagesFromDump(dump: String): List<String> {
        val header = "Apps supporting client queue:"
        val start = dump.indexOf(header)
        if (start < 0) return emptyList()
        val out = ArrayList<String>()
        for (line in dump.substring(start + header.length).lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                // The block is terminated by a blank line; entries never are.
                if (out.isNotEmpty()) break else continue
            }
            val colon = trimmed.indexOf(':')
            if (colon <= 0) {
                if (out.isNotEmpty()) break
                continue
            }
            // `Affinity Scores:` and friends have no colon-then-digits shape;
            // an entry is `pkg:user`, so require the tail to start with a digit.
            val tail = trimmed.substring(colon + 1)
            if (tail.isEmpty() || !tail[0].isDigit()) {
                if (out.isNotEmpty()) break
                continue
            }
            out.add(trimmed.substring(0, colon))
        }
        return out
    }

    /**
     * Bridge encoding for the bucket map: `pkg=bucket` joined by `;`.
     *
     * A broadcast extra has to be a primitive, and neither package names nor
     * these numbers can contain `=` or `;`, so the format needs no escaping.
     */
    fun encode(buckets: Map<String, Int>): String =
        buckets.entries.joinToString(";") { "${it.key}=${it.value}" }

    /** Inverse of [encode]; malformed pairs are dropped, never thrown on. */
    fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (pair in raw.split(';')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val value = pair.substring(eq + 1).toIntOrNull() ?: continue
            out[pair.substring(0, eq)] = value
        }
        return out
    }
}
