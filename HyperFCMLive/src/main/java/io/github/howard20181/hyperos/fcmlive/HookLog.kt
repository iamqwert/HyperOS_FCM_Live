package io.github.howard20181.hyperos.fcmlive

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * The module-log gate behind the "显示调试日志" experiment
 * ([Prefs.KEY_DEBUG_LOG], default off).
 *
 * The module used to log everything unconditionally: install passes, per-push
 * verdicts, probe findings. That volume is diagnostic gold but everyday noise,
 * so the default output is now **warnings and worse only** — the module stays
 * silent as long as nothing goes wrong, and [Prefs.KEY_DEBUG_LOG] restores the
 * full stream for a troubleshooting session. The rule at the top of Prefs.kt is
 * untouched: an "applied" line still exists for every rare path; it is the
 * gate that decides whether it is *emitted*, not whether it is *written*.
 *
 * The flag is a process-wide snapshot refreshed wherever the remote config
 * group is opened anyway — at both module entries, the allowlist reload
 * (which every config write reaches through the reload broadcast), and the
 * per-call reads the PowerKeeper-side hooks already do. A flip on the
 * experiment screen therefore lands without a reboot, on the next config
 * reload, and a process that never opens the remote prefs simply keeps its
 * last snapshot — which for a fresh process is the default (off), the same
 * value the hook would read anyway.
 */
internal object HookLog {

    /** Snapshot of [Prefs.KEY_DEBUG_LOG] in this process; refreshed lazily. */
    @Volatile
    internal var debugEnabled: Boolean = false

    /**
     * Re-read [Prefs.KEY_DEBUG_LOG] from the remote config group. Any failure
     * keeps the previous snapshot: a gate must never turn a read error into a
     * log storm, nor silence what was already being emitted.
     */
    internal fun refresh(prefs: SharedPreferences?) {
        if (prefs == null) {
            return
        }
        try {
            debugEnabled = prefs.getBoolean(Prefs.KEY_DEBUG_LOG, false)
        } catch (ignored: Throwable) {
        }
    }
}

/**
 * The gated drop-in for [XposedInterface.log]: everything from [Log.WARN] up
 * (warning, error, assert — the "fatal" tier included) always goes out, so a
 * problem can never be hidden by the switch it is reported through; the
 * informational stream goes out only while debug logging is on.
 */
internal fun XposedInterface.logGated(priority: Int, tag: String, msg: String) {
    if (priority < Log.WARN && !HookLog.debugEnabled) {
        return
    }
    log(priority, tag, msg)
}

/** Throwable variant of [logGated]; the same [Log.WARN] floor applies. */
internal fun XposedInterface.logGated(priority: Int, tag: String, msg: String, tr: Throwable) {
    if (priority < Log.WARN && !HookLog.debugEnabled) {
        return
    }
    log(priority, tag, msg, tr)
}
