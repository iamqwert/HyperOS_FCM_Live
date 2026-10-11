package io.github.howard20181.hyperos.fcmlive

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The relaxed WiFi weak-signal switch subsystem, extracted verbatim from
 * `Hooker` (zero-behaviour refactor). Injection follows the same convention as
 * [GmsSupport] / [GreezeHooks]: `module` is the [XposedInterface] the module
 * entry object is (`log` / `deoptimize` / `getRemotePreferences`), `hookE` is
 * `Hooker#hookE` (the counted, `setId`-deduplicated hook builder), `logSkip`
 * and `logSkipOtherGeneration` are the two absent-target reports (the counters
 * and the merged cross-generation line stay with `Hooker`).
 */
internal object WifiWeakSignalSwitch {

    /**
     * Relaxed WiFi weak-signal switch, hooked in system_server.
     *
     * What the ROM does without us (measured on-device, V816, see
     * HOOKS_AND_DIAGNOSTICS.md §3.9): `AmlMiuiThirdPartScorer` keeps a legacy
     * score in `mLegacyIntScore` and turns it into a usable/unusable verdict at
     * a **hardcoded** 50 inside `notifyScoreAndIsUsable()`. A single call there
     * publishes everything outward — `notifyScoreUpdate(sessionId, score)` for
     * the value and `notifyStatusUpdate(sessionId, isUsable)` for the verdict —
     * so a score below 50 reaches `WifiScoreReport` as "not usable", which sets
     * the network `+EXITING` and moves the default network to cellular for 30 s.
     * Both write paths into `mLegacyIntScore` converge on that one method, so
     * it is the cheapest point at which the verdict can be withheld.
     *
     * What this hook changes, and what it deliberately does not:
     * - the clamp exists only for the lifetime of the call; the field is
     *   restored in a `finally`, so the class's own state machine still sees
     *   the real reading and nothing is persisted anywhere — this is why it
     *   replaces the earlier idea of lowering the `cloud_min_rssi_*` settings
     *   keys (those survive in `settings_system.xml` after an uninstall, and
     *   their direction turned out to be unproven anyway);
     * - the score value published upstream is what decides whether WiFi stays
     *   the default network, so reporting the floor *is* the intervention;
     * - a real failure that does not travel through this scorer — WiFi leaving,
     *   carrier or UI decisions, validation failures — is untouched;
     * - how far down it reaches is the user's call, not ours. Scores below
     *   [Prefs.KEY_WIFI_WEAK_SIGNAL_FLOOR] go through unmodified, so a link bad
     *   enough to be past saving is still handed to a mechanism that can pick
     *   another network. Every rescue is conditional on that floor, and the
     *   master switch must be on for it to be consulted at all.
     *
     * Known uncovered exit, deliberately not handled yet: the ROM also calls
     * `notifySwitchNetworkByOtherStrategies type = 1003`, which bypasses this
     * score channel entirely. It was observed 27 times in one gaming window
     * without ever causing a switch, so it is a watch item and not a fixed
     * gap — if a switch is later reported while every sub-floor score stayed
     * unflipped, check that call first before suspecting this hook. See
     * HOOKS_AND_DIAGNOSTICS.md §6.3.
     *
     * Class resolution note, which is the reason this one is not a plain
     * `classLoader.loadClass`: the class is **not reachable from system_server's
     * own classloader**. `miui-wifi-service.jar` is loaded separately (the build
     * that runs it is the one whose logcat emits `AmlMiuiThirdPartScorer` from
     * the system_server pid), so both call sites below return ClassNotFound
     * while the class is plainly live in the process — the first attempt at this
     * hook reported "absent" on a ROM where the class was demonstrably running.
     * The fallback therefore walks to the loader that did load it, through the
     * binder of a service whose implementation comes from the same jar.
     */
    private val wifiScorerResolved = AtomicBoolean(false)
    // Two throttles, not one: clamping and handing back are opposite verdicts,
    // and sharing a window between them lets whichever is louder starve the
    // other — which would leave the rarer one looking like it never happened.
    private val lastWifiScoreClampLogMs = AtomicLong(0L)
    private val lastWifiScoreSkipLogMs = AtomicLong(0L)

    internal fun hook(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit,
        logSkipOtherGeneration: (String) -> Unit
    ) {
        if (tryHookWifiScorer(module, classLoader, hookE, logSkip)) {
            return
        }
        // Nothing to hook yet: at system_server start the WiFi service has not
        // been published, so there is no loader to borrow. Retry off-thread
        // instead of giving up — the summary line below is printed before this
        // resolves, so its counts describe the synchronous phase only.
        val waiter = Thread({
            for (attempt in 1..40) {
                try {
                    Thread.sleep(3000L)
                } catch (ignored: InterruptedException) {
                    return@Thread
                }
                if (tryHookWifiScorer(module, classLoader, hookE, logSkip)) {
                    return@Thread
                }
            }
            logSkipOtherGeneration(
                "AmlMiuiThirdPartScorer (unreachable after retry)"
            )
        }, "fcmlive-wifi-scorer")
        waiter.isDaemon = true
        waiter.start()
    }

    /** Installs the scorer hook if the class can be reached this time. */
    private fun tryHookWifiScorer(
        module: XposedInterface,
        classLoader: ClassLoader,
        hookE: (Executable) -> XposedInterface.HookBuilder,
        logSkip: (String) -> Unit
    ): Boolean {
        if (wifiScorerResolved.get()) {
            return true
        }
        val scorerClass = resolveWifiScorerClass(classLoader) ?: return false
        val notifyMethod = try {
            scorerClass.getDeclaredMethod(HookerSupport.WIFI_SCORER_NOTIFY_METHOD)
        } catch (t: Throwable) {
            logSkip("AmlMiuiThirdPartScorer#notifyScoreAndIsUsable absent, wifi-weak-signal switch skip")
            wifiScorerResolved.set(true)
            return true
        }
        val scoreField = try {
            scorerClass.getDeclaredField(HookerSupport.WIFI_SCORER_SCORE_FIELD)
        } catch (t: Throwable) {
            logSkip("AmlMiuiThirdPartScorer#mLegacyIntScore absent, wifi-weak-signal switch skip")
            wifiScorerResolved.set(true)
            return true
        }
        notifyMethod.isAccessible = true
        scoreField.isAccessible = true
        hookE(notifyMethod).intercept { chain: XposedInterface.Chain ->
            if (!isWifiWeakSignalSwitchRelaxed(module)) {
                chain.proceed()
            } else {
                // Read here rather than outside, which is also what gates it:
                // this branch only runs while the master switch is on, so the
                // sub-option cannot survive its parent being turned off.
                val floor = readWifiWeakSignalFloor(module)
                val self = chain.thisObject
                var reported = 0
                var read = false
                var clamped = false
                try {
                    reported = scoreField.getInt(self)
                    read = true
                    // Both halves are load-bearing. `reported < WIFI_SCORE_USABLE_MIN`
                    // is the ROM's own verdict — nothing above it needs saving,
                    // and rewriting it would invent a decision the ROM did not
                    // make. `reported >= floor` is how deep the user lets this
                    // reach; below that the score goes through untouched, which
                    // is why there is no third branch.
                    if (reported < HookerSupport.WIFI_SCORE_USABLE_MIN && reported >= floor) {
                        scoreField.setInt(self, HookerSupport.WIFI_SCORE_CLAMP_TARGET)
                        clamped = true
                    }
                } catch (ignored: Throwable) {
                    // Fail closed for the intervention and never for the call:
                    // the ROM's own verdict goes through untouched.
                }
                if (clamped) {
                    noteWifiScoreClamp(module, reported, floor)
                } else if (read && reported < HookerSupport.WIFI_SCORE_USABLE_MIN) {
                    // Handing back is a decision too, and one that later looks
                    // like "the hook missed it". Say so while it happens. The
                    // upper bound matters as much as the lower one: most scores
                    // a healthy link gets are above the floor already, and those
                    // were never ours to hand back — logging them would read as
                    // an intervention every thirty seconds on a quiet day.
                    noteWifiScoreSkip(module, reported, floor)
                }
                try {
                    chain.proceed()
                } finally {
                    if (clamped) {
                        try {
                            scoreField.setInt(self, reported)
                        } catch (ignored: Throwable) {
                            module.log(Log.WARN, HookerSupport.TAG, "wifi-weak-signal: score restore failed")
                        }
                    }
                }
            }
        }
        module.deoptimize(notifyMethod)
        wifiScorerResolved.set(true)
        // Sentinel wording, same discipline as doze-keepout: hooked is not
        // active, and active is not the same as "the ROM would have switched".
        module.log(
            Log.INFO, HookerSupport.TAG,
            "AmlMiuiThirdPartScorer#notifyScoreAndIsUsable hooked (wifi-weak-signal " +
                "switch, default off): idle until the experiment switch is on, then " +
                "scores from the chosen floor up to ${HookerSupport.WIFI_SCORE_USABLE_MIN} are " +
                "reported as usable; deeper ones are left to the ROM"
        )
        return true
    }

    /**
     * The class lives outside the process' main classpath, so ask the loaders
     * that could plausibly own it: our own, the system one, and — the one that
     * actually works here — the loader of any registered service whose
     * implementation ships in the same jar.
     */
    private fun resolveWifiScorerClass(classLoader: ClassLoader): Class<*>? {
        val candidates = mutableListOf<ClassLoader>(classLoader)
        try {
            candidates.add(ClassLoader.getSystemClassLoader())
        } catch (ignored: Throwable) {
        }
        for (service in HookerSupport.WIFI_SCORER_CLASS_LOADER_SERVICES) {
            val impl = serviceBinder(service) ?: continue
            val owner = impl.javaClass.classLoader ?: continue
            if (!candidates.contains(owner)) {
                candidates.add(owner)
            }
        }
        for (candidate in candidates) {
            try {
                return candidate.loadClass(HookerSupport.WIFI_SCORER_CLASS)
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    /**
     * Local (raw) binder of a service, or null before it is published. From
     * inside system_server `getService` returns the implementation object, so
     * its class carries the loader we need; the reflection keeps this off the
     * hidden-API surface the module would otherwise depend on at compile time.
     */
    private fun serviceBinder(name: String): IBinder? {
        return try {
            val manager = Class.forName("android.os.ServiceManager")
            val binder = manager.getMethod("getService", String::class.java)
                .invoke(null, name)
            binder as? IBinder
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * Throttled trace of an actual intervention. The ROM re-scores on its own
     * cadence, so an unthrottled line here would be the noisiest thing the
     * module does; 30 s keeps one line per visible link-quality episode while
     * still proving the hook had something to do. The score is printed because
     * it is the only value that says *how far* below the ROM's own 50 the link
     * was judged — without it, "it fired" and "it fired at 49" look alike, and
     * those mean very different things for deciding whether to keep the switch.
     */
    private fun noteWifiScoreClamp(module: XposedInterface, actual: Int, floor: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWifiScoreClampLogMs.get() < HookerSupport.WIFI_SCORE_CLAMP_LOG_INTERVAL_MS) {
            return
        }
        lastWifiScoreClampLogMs.set(now)
        module.log(
            Log.INFO, HookerSupport.TAG,
            "wifi-weak-signal: reported $actual met the chosen floor $floor but would " +
                "have failed the ROM's ${HookerSupport.WIFI_SCORE_USABLE_MIN}; reported as usable instead"
        )
    }

    /**
     * The same trace for the opposite verdict. Without it, "the network went to
     * cellular anyway" reads as a hook that failed to fire when in fact it
     * fired and chose not to act — and since [Prefs.KEY_WIFI_WEAK_SIGNAL_FLOOR]
     * is what decides, the line names the floor rather than leaving it to be
     * guessed from the score.
     */
    private fun noteWifiScoreSkip(module: XposedInterface, actual: Int, floor: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWifiScoreSkipLogMs.get() < HookerSupport.WIFI_SCORE_CLAMP_LOG_INTERVAL_MS) {
            return
        }
        lastWifiScoreSkipLogMs.set(now)
        module.log(
            Log.INFO, HookerSupport.TAG,
            "wifi-weak-signal: reported $actual is below the chosen floor $floor; " +
                "left to the ROM's own policy"
        )
    }

    /**
     * The flag lives in the shared config group and is read lazily at each
     * score update: the class has no broadcast receiver, and updates arrive on
     * its own handler thread, so the read frequency is that of score updates,
     * not of general connectivity traffic.
     */
    private fun isWifiWeakSignalSwitchRelaxed(module: XposedInterface): Boolean {
        return try {
            module.getRemotePreferences(Prefs.GROUP_CONFIG)
                .getBoolean(Prefs.KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED, false)
        } catch (ignored: Throwable) {
            // Fail closed: an unreadable switch must not start deciding how the
            // ROM judges its own WiFi link.
            false
        }
    }

    /**
     * The floor the sub-option chose, read at the same lazy cadence as the
     * master flag.
     *
     * Unreadable or off-list both land on the default — the narrowest floor
     * rather than the widest. The two failures are the opposite direction to
     * guess in, and only one of them is recoverable by reading it again; if the
     * list ever loses a value, an unrestorable read here means the module
     * quietly starts covering depths nobody chose.
     */
    private fun readWifiWeakSignalFloor(module: XposedInterface): Int {
        return try {
            Prefs.sanitizeWeakSignalFloor(
                module.getRemotePreferences(Prefs.GROUP_CONFIG)
                    .getInt(Prefs.KEY_WIFI_WEAK_SIGNAL_FLOOR, Prefs.WIFI_WEAK_SIGNAL_FLOOR_DEFAULT)
            )
        } catch (ignored: Throwable) {
            Prefs.WIFI_WEAK_SIGNAL_FLOOR_DEFAULT
        }
    }
}
