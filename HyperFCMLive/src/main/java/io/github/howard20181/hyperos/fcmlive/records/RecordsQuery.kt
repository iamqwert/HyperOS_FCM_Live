package io.github.howard20181.hyperos.fcmlive.records

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The app-side half of the records query.
 *
 * Sends one ordered broadcast and waits for the module's system_server half to
 * answer with GCM's dump. The dump itself never leaves the device, and the
 * only receiver allowed to answer is the hook in this module (checked on the
 * hook side — see `RecordsBridge`).
 *
 * A timeout is a normal outcome, not an exception: the module may not be
 * enabled, or the system_server half may not have reached system_server yet.
 */
object RecordsQuery {

    /** Longer than a healthy dump (well under a second), short enough to retry. */
    private const val TIMEOUT_MS = 8_000L

    sealed interface Result {
        /**
         * [summary] is the hook side's own install line ("N hook(s) installed,
         * M target(s) absent…"), null when that pass had not reported yet. It
         * is carried with the dump because the export has to answer "are the
         * hooks installed" without asking the reporter for a logcat.
         *
         * [standbyBuckets] is package → App Standby bucket, empty when the hook
         * side could not read them; see [RecordsProtocol.EXTRA_BUCKETS].
         */
        data class Success(
            val dump: String,
            val summary: String? = null,
            val standbyBuckets: Map<String, Int> = emptyMap()
        ) : Result

        /** The hook answered, but could not read the dump. */
        data class Failed(val reason: String) : Result

        /** Nobody answered in time — the module is inactive or still starting. */
        data object Unavailable : Result
    }

    suspend fun query(context: Context): Result {
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        val extras = try {
                            getResultExtras(false)
                        } catch (t: Throwable) {
                            null
                        }
                        val result = when {
                            extras == null -> Result.Unavailable
                            extras.containsKey(RecordsProtocol.EXTRA_DUMP) ->
                                extras.getString(RecordsProtocol.EXTRA_DUMP)
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        Result.Success(
                                            dump = it,
                                            summary = extras
                                                .getString(RecordsProtocol.EXTRA_SUMMARY)
                                                ?.takeIf { line -> line.isNotBlank() },
                                            standbyBuckets = StandbyQuota.decode(
                                                extras.getString(RecordsProtocol.EXTRA_BUCKETS)
                                            )
                                        )
                                    }
                                    ?: Result.Unavailable
                            extras.containsKey(RecordsProtocol.EXTRA_ERROR) ->
                                Result.Failed(
                                    extras.getString(RecordsProtocol.EXTRA_ERROR).orEmpty()
                                )
                            else -> Result.Unavailable
                        }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                }
                try {
                    context.sendOrderedBroadcast(
                        Intent(RecordsProtocol.ACTION_QUERY),
                        null,
                        options,
                        receiver,
                        Handler(Looper.getMainLooper()),
                        0,
                        null,
                        null
                    )
                } catch (t: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(
                            Result.Failed(t.message ?: t.javaClass.simpleName)
                        )
                    }
                }
            }
        } ?: Result.Unavailable
    }
}
