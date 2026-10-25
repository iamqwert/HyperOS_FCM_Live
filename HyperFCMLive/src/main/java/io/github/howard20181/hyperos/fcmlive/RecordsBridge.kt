package io.github.howard20181.hyperos.fcmlive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.howard20181.hyperos.fcmlive.records.RecordsProtocol
import io.github.libxposed.api.XposedInterface
import java.io.ByteArrayOutputStream
import java.io.FileInputStream

/**
 * The hook-side half of the 记录 tab.
 *
 * The app cannot read GCM's service dump on its own: `dumpsys activity service`
 * is reserved to the shell / system UID. The module already runs in
 * `system_server`, so it answers one ordered broadcast per read and hands the
 * dump back through the result extras. No root is involved anywhere on this
 * path.
 *
 * The dump is read by calling the activity service's own binder `dump` with
 * the same argument list `/system/bin/dumpsys` uses (`service <component>`),
 * not by reflecting into `ActiveServices`: the argument form is stable public
 * API on the binder, while the internal method's signature moves between
 * Android releases.
 *
 * Every failure is reported as a short string in [RecordsProtocol.EXTRA_ERROR]
 * and never thrown back into `system_server`; a records read is diagnostic and
 * must not affect the module's real work.
 */
internal object RecordsBridge {

    private const val TAG = HookerSupport.TAG
    private const val COMPONENT = "com.google.android.gms/.gcm.GcmService"
    private const val MAX_BYTES = 256 * 1024
    private const val READ_TIMEOUT_MS = 4_000L

    /** `DeviceID:` is not needed by the page and is stripped before it leaves the hook. */
    private val DEVICE_ID = Regex("""(?m)^\s*DeviceID:.*$""")

    /**
     * Replaces the previous generations' token. A receiver registered by an
     * earlier hot-reload pass sees the mismatch on its next delivery and
     * unregisters itself, so reloads cannot stack answers.
     */
    private const val TOKEN_KEY = "fcmlive.records.receiver.token"

    /**
     * The system context is not always ready when the install pass runs:
     * `onSystemServerStarting` fires before `ActivityThread` carries an
     * application context on every boot, and a single eager read that returns
     * null used to leave the bridge unregistered for the WHOLE boot — every
     * records query then timed out (「模块未响应记录查询」) with no recovery
     * short of another reboot. Registration therefore retries until the
     * context shows up.
     */
    private const val CONTEXT_RETRY_MS = 5_000L
    private const val CONTEXT_MAX_ATTEMPTS = 120

    /**
     * Registration can also arrive before the activity service exists.
     * `ContextImpl.registerReceiver` calls `ActivityManager.getService()`,
     * which is still null while SystemServer is inside
     * `startBootstrapServices` — the very pass this bridge installs from. The
     * NPE it throws is therefore not a broken install but a clock that has not
     * reached AMS yet: retry on the same timer instead of losing the receiver
     * for the whole boot. Twelve tries is a minute, far past AMS coming up.
     */
    private const val REGISTER_MAX_ATTEMPTS = 12

    @Volatile
    private var failureLogged = false

    internal fun install(module: XposedInterface, systemContext: () -> Context?) {
        val worker = HandlerThread("fcmlive-records").apply { start() }
        val handler = Handler(worker.looper)
        var attempts = 0
        var registerAttempts = 0
        val register = object : Runnable {
            override fun run() {
                val context = try {
                    systemContext()
                } catch (t: Throwable) {
                    null
                }
                if (context == null) {
                    attempts++
                    if (attempts <= CONTEXT_MAX_ATTEMPTS) {
                        handler.postDelayed(this, CONTEXT_RETRY_MS)
                    } else {
                        module.logGated(
                            Log.WARN, TAG,
                            "Records bridge aborted: system context unavailable " +
                                "after $attempts attempts"
                        )
                        worker.quitSafely()
                    }
                    return
                }
                val token = claimToken()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        if (intent.action != RecordsProtocol.ACTION_QUERY) return
                        if (latestToken() != token) {
                            // An earlier install's receiver: retire without answering.
                            try {
                                receiverContext.unregisterReceiver(this)
                            } catch (ignored: Throwable) {
                            }
                            worker.quitSafely()
                            return
                        }
                        if (!isModuleSender(this, context)) {
                            return
                        }
                        val pending = goAsync()
                        handler.post {
                            try {
                                val extras = Bundle()
                                when (val result = readGcmDump(module)) {
                                    is DumpResult.Success ->
                                        extras.putString(RecordsProtocol.EXTRA_DUMP, result.text)
                                    is DumpResult.Failed ->
                                        extras.putString(RecordsProtocol.EXTRA_ERROR, result.reason)
                                }
                                pending.setResultExtras(extras)
                            } catch (t: Throwable) {
                                logFailureOnce(module, "Records query failed", t)
                            } finally {
                                try {
                                    pending.finish()
                                } catch (ignored: Throwable) {
                                }
                            }
                        }
                    }
                }
                try {
                    context.registerReceiver(
                        receiver,
                        IntentFilter(RecordsProtocol.ACTION_QUERY),
                        null,
                        handler,
                        Context.RECEIVER_EXPORTED
                    )
                    module.logGated(
                        Log.INFO, TAG,
                        "Records bridge installed (token $token, attempts $attempts)"
                    )
                } catch (t: Throwable) {
                    registerAttempts++
                    if (registerAttempts < REGISTER_MAX_ATTEMPTS) {
                        // The usual cause is AMS not published yet (see
                        // REGISTER_MAX_ATTEMPTS); a genuinely broken install
                        // exhausts this in a minute and is reported then, once,
                        // with the stack instead of once per retry.
                        module.logGated(
                            Log.DEBUG, TAG,
                            "Records bridge not registered yet " +
                                "(attempt $registerAttempts): $t"
                        )
                        handler.postDelayed(this, CONTEXT_RETRY_MS)
                    } else {
                        logFailureOnce(
                            module,
                            "Records bridge aborted: registration failed " +
                                "after $registerAttempts attempts",
                            t
                        )
                        worker.quitSafely()
                    }
                }
            }
        }
        register.run()
    }

    /**
     * Reads GCM's service dump through the activity service's binder dump.
     *
     * The dump is written to a pipe: `TransferPipe` inside `ActiveServices`
     * needs a real file descriptor, and the calling thread blocks until GMS
     * has answered. The read happens on its own thread so a slow GCM process
     * cannot fill the pipe and deadlock the write.
     */
    private fun readGcmDump(module: XposedInterface): DumpResult {
        var pipe: Array<ParcelFileDescriptor>? = null
        var reader: Thread? = null
        val body = ByteArrayOutputStream()
        var readFailure: Throwable? = null
        return try {
            pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            reader = Thread({
                try {
                    FileInputStream(readEnd.fileDescriptor).use { input ->
                        val buffer = ByteArray(8 * 1024)
                        while (body.size() < MAX_BYTES) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            val keep = minOf(read, MAX_BYTES - body.size())
                            body.write(buffer, 0, keep)
                        }
                    }
                } catch (t: Throwable) {
                    readFailure = t
                } finally {
                    try {
                        readEnd.close()
                    } catch (ignored: Throwable) {
                    }
                }
            }, "fcmlive-records-read").apply { start() }

            val binder = activityBinder()
                ?: return DumpResult.Failed("activity service unavailable")
            // Same argument list as `dumpsys activity service <component>`.
            binder.dump(writeEnd.fileDescriptor, arrayOf("service", COMPONENT))
            try {
                writeEnd.close()
            } catch (ignored: Throwable) {
            }
            reader.join(READ_TIMEOUT_MS)
            if (reader.isAlive) {
                try {
                    readEnd.close()
                } catch (ignored: Throwable) {
                }
                return DumpResult.Failed("dump read timed out")
            }
            readFailure?.let {
                return DumpResult.Failed("dump read failed: ${it.message ?: it.javaClass.simpleName}")
            }
            val text = body.toString(Charsets.UTF_8.name())
            if (!text.contains("SERVICE $COMPONENT")) {
                return DumpResult.Failed("GcmService did not answer the dump")
            }
            DumpResult.Success(DEVICE_ID.replace(text, ""))
        } catch (t: Throwable) {
            logFailureOnce(module, "GCM dump read failed", t)
            DumpResult.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                pipe?.get(1)?.close()
            } catch (ignored: Throwable) {
            }
            try {
                pipe?.get(0)?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** `android.os.ServiceManager.getService("activity")`, via reflection (hidden API). */
    private fun activityBinder(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity") as? IBinder
    } catch (t: Throwable) {
        null
    }

    /**
     * Only this module's app may drive the query. Identity is the one the
     * framework stamps on the delivery (API 34+, minSdk 35); an unstamped
     * broadcast is rejected rather than trusted.
     */
    private fun isModuleSender(receiver: BroadcastReceiver, context: Context): Boolean = try {
        val fromPackage = receiver.sentFromPackage
        val fromUid = receiver.sentFromUid
        val moduleUid = context.packageManager.getPackageUid(Prefs.MODULE_PKG, 0)
        fromPackage == Prefs.MODULE_PKG && fromUid == moduleUid
    } catch (t: Throwable) {
        false
    }

    private fun claimToken(): Long {
        val props = System.getProperties()
        return synchronized(props) {
            val next = (props.getProperty(TOKEN_KEY)?.toLongOrNull() ?: 0L) + 1
            props.setProperty(TOKEN_KEY, next.toString())
            next
        }
    }

    private fun latestToken(): Long =
        System.getProperties().getProperty(TOKEN_KEY)?.toLongOrNull() ?: 0L

    private fun logFailureOnce(module: XposedInterface, message: String, t: Throwable) {
        if (failureLogged) return
        failureLogged = true
        module.logGated(Log.WARN, TAG, message, t)
    }

    private sealed interface DumpResult {
        data class Success(val text: String) : DumpResult
        data class Failed(val reason: String) : DumpResult
    }
}
