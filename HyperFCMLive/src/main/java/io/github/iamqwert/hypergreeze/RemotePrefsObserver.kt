package io.github.iamqwert.hypergreeze

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Parallel observer for libxposed `RemotePreferences` change callbacks
 * (Stage 1 verification).
 *
 * Keeps a strong reference to the registered [SharedPreferences.OnSharedPreferenceChangeListener]
 * (since `SharedPreferences` implementations typically store listeners in a
 * `WeakHashMap`) and explicitly unregisters on [detach] during hot reload so a
 * replaced `Hooker` instance does not keep firing until GC.
 *
 * Existing broadcast-based sync in [AllowlistGate] and lazy reads in
 * PowerKeeper remain intact until dual-process callback reliability is
 * confirmed on device.
 */
internal class RemotePrefsObserver {

    private val lock = Any()
    private var registeredPrefs: SharedPreferences? = null
    private var activeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    internal fun attach(
        module: XposedInterface,
        processLabel: String,
        onChanged: ((SharedPreferences, String?) -> Unit)? = null
    ) {
        try {
            val prefs = module.getRemotePreferences(Prefs.GROUP_CONFIG)
            HookLog.refresh(prefs)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { updated, key ->
                try {
                    HookLog.refresh(updated)
                    module.logGated(
                        Log.INFO, HookerSupport.TAG,
                        "remote-prefs changed in $processLabel: key=$key"
                    )
                    onChanged?.invoke(updated, key)
                } catch (t: Throwable) {
                    module.logGated(
                        Log.WARN, HookerSupport.TAG,
                        "remote-prefs listener callback failed in $processLabel", t
                    )
                }
            }
            synchronized(lock) {
                detachLocked()
                prefs.registerOnSharedPreferenceChangeListener(listener)
                registeredPrefs = prefs
                activeListener = listener
            }
            module.logGated(
                Log.INFO, HookerSupport.TAG,
                "remote-prefs listener attached in $processLabel"
            )
        } catch (t: Throwable) {
            module.logGated(
                Log.WARN, HookerSupport.TAG,
                "Failed to attach remote-prefs listener in $processLabel", t
            )
        }
    }

    internal fun detach() {
        synchronized(lock) {
            detachLocked()
        }
    }

    private fun detachLocked() {
        val prefs = registeredPrefs
        val listener = activeListener
        registeredPrefs = null
        activeListener = null
        if (prefs != null && listener != null) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            } catch (ignored: Throwable) {
            }
        }
    }
}
