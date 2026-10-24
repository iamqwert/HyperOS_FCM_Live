package io.github.howard20181.hyperos.fcmlive.records

/**
 * The records query protocol between the app and the module's system_server
 * half.
 *
 * The app sends an ordered broadcast; the hook-side receiver (see
 * `RecordsBridge`) fills the result extras with GCM's service dump. Nothing
 * else is exchanged, and the dump never leaves the device.
 */
object RecordsProtocol {

    /** Ordered broadcast the app sends to ask for one GCM dump. */
    const val ACTION_QUERY = "io.github.howard20181.hyperos.fcmlive.action.QUERY_RECORDS"

    /** Result extra: the dump text, when the read succeeded. */
    const val EXTRA_DUMP = "dump"

    /** Result extra: why the read failed, for the app's log. */
    const val EXTRA_ERROR = "error"
}
