package io.github.howard20181.hyperos.fcmlive.records

/**
 * The records query protocol between the app and the module's system_server
 * half.
 *
 * The app sends an ordered broadcast; the hook-side receiver (see
 * `RecordsBridge`) fills the result extras with GCM's service dump. Nothing
 * else is exchanged, and the dump never leaves the device.
 *
 * [EXTRA_SUMMARY] rides along because it answers the first question a
 * diagnosis asks — "did the module install its hooks at all?" — and the only
 * other place that answer exists is the logcat line, which the reporter
 * usually cannot produce. It is the hook side's own end-of-install summary,
 * not something read out of GMS.
 */
object RecordsProtocol {

    /** Ordered broadcast the app sends to ask for one GCM dump. */
    const val ACTION_QUERY = "io.github.howard20181.hyperos.fcmlive.action.QUERY_RECORDS"

    /** Result extra: the dump text, when the read succeeded. */
    const val EXTRA_DUMP = "dump"

    /** Result extra: the hook side's install summary line, when one was built yet. */
    const val EXTRA_SUMMARY = "summary"

    /**
     * Result extra: App Standby buckets for the apps named in the dump's client
     * list, encoded by `StandbyQuota.encode` (`pkg=bucket;pkg=bucket`).
     *
     * It rides here because reading a bucket needs `PACKAGE_USAGE_STATS`, which
     * only the hook side (system_server) holds — the app cannot derive this
     * number on its own. Absent when no bucket could be read (older module
     * build, or `UsageStatsManager` refused); the page then shows nothing
     * rather than guessing.
     */
    const val EXTRA_BUCKETS = "buckets"

    /** Result extra: why the read failed, for the app's log. */
    const val EXTRA_ERROR = "error"
}
