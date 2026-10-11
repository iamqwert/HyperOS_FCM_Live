package io.github.howard20181.hyperos.fcmlive

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * The install-pass bookkeeping, extracted verbatim from `Hooker`
 * (zero-behaviour refactor): the counted, `setId`-deduplicated hook builder,
 * the two absent levels ([logSkip] / [logSkipOtherGeneration]) with their
 * buffers and flushes, the read-only probe finding buffer, the end-of-install
 * summary line, and the [Group] runner that isolates a throwing group from the
 * groups behind it.
 *
 * The `Hooker` entry class keeps thin forwarders (`::hookE`, `::logSkip`, …)
 * so every group-table entry and every already-extracted subsystem
 * ([GreezeHooks], [PowerKeeperHooks], …) receives the same callables as before
 * the extraction; the lifecycle entry points
 * (`onSystemServerStarting` / `onPackageReady` / hot reload) also stay with
 * `Hooker`, because they are the module entry's overrides.
 *
 * One `Hooker` (and therefore one [InstallBookkeeping]) exists per process, so
 * the buffers hold exactly one install pass.
 */
internal class InstallBookkeeping(private val module: XposedModule) {

    /**
     * True only between the start and the end of an install pass.
     *
     * A late arrival lands after [flushOtherGenerationAbsent] has run: the wifi
     * weak-signal retry lives on its own thread and is not joined by its group,
     * so its "unreachable after retry" verdict can come back after the pass
     * closed. Such a line is printed on its own instead of being buffered for a
     * flush that may never come (the next one is a hot reload or a reboot away).
     */
    internal var installPassCollecting = false

    /**
     * Counts behind the end-of-install summary line.
     * Every hook goes through [hookE]; absent targets are counted by [logSkip]
     * (same generation) and [logSkipOtherGeneration] (another generation).
     */
    private var hooksInstalled = 0
    private var hookTargetsAbsent = 0

    /**
     * Absent on *another* supported generation, counted apart from
     * [hookTargetsAbsent] so the summary line can say which is which.
     *
     * On the test ROM the whole PowerKeeper set reports absent (ten symbols
     * that only OS3 carries). Folded into one number that read as
     * `10 target(s) absent`, i.e. a healthy install looked like ten broken
     * hooks — and the reading that actually matters, "did this OTA drop
     * something this generation is supposed to have", was invisible.
     */
    private var hookTargetsAbsentOtherGeneration = 0

    /**
     * Symbols collected by [logSkipOtherGeneration], flushed as one line.
     *
     * Ten OS3-only symbols on this ROM answered the same question in ten
     * lines, and each line pays the LSPosed per-line prefix (~160 B) — more
     * than most of the messages themselves. The symbols are all kept, so a
     * ROM that starts carrying one still shows as a differing entry.
     */
    private val otherGenerationAbsent = ArrayList<String>()

    /**
     * Findings from the read-only existence probes, flushed as one line.
     *
     * These answer "does this ROM carry the symbol / is the feature flag on",
     * which does not change between generations. Nine of them used to print a
     * line each. Only the *normal* reading is folded in: an abnormal one (a
     * layer that stopped resolving, a socket-teardown half that is gone) still
     * prints its own line, because that is the case worth noticing.
     */
    private val probeFindings = ArrayList<String>()

    internal fun hookE(executable: Executable): XposedInterface.HookBuilder {
        val builder = module.hook(executable)
        hooksInstalled++
        if (module.apiVersion >= 102) {
            builder.setId(executable.toGenericString())
        }
        return builder
    }

    /** This generation is expected to carry it, and does not. Counted and logged. */
    internal fun logSkip(message: String) {
        hookTargetsAbsent++
        module.log(Log.INFO, HookerSupport.TAG, message)
    }

    /**
     * Another supported generation carries it, this one does not — expected,
     * not a gap. Counted in [hookTargetsAbsentOtherGeneration] (never in
     * [hookTargetsAbsent]) and reported by [flushOtherGenerationAbsent] at
     * DEBUG, on one line together with its siblings.
     *
     * Reserved for targets that are *expected* to be missing on at least one
     * supported ROM generation: an entry here means "this generation does not
     * carry the method", not "the hook is broken". Because that distinction is
     * what separates the two readings, never route a same-generation miss
     * through here: a genuine regression on the current generation would then
     * hide among the expected ones. Every caller must be a target whose absence
     * is explained by a known generation split — see `hookGmsObserver` for the
     * PowerKeeper set, the largest user of this path.
     *
     * [symbol] is the missing symbol, optionally with a parenthetical when the
     * cause is not a generation split (`checkWakePath (wake-path probe)`,
     * `AmlMiuiThirdPartScorer (unreachable after retry)`), since the consequent
     * "so what did not get installed" would otherwise be lost when the lines
     * are merged.
     */
    internal fun logSkipOtherGeneration(symbol: String) {
        hookTargetsAbsentOtherGeneration++
        if (!installPassCollecting) {
            module.log(Log.DEBUG, HookerSupport.TAG, "cross-generation target(s) absent (1), skip: $symbol")
            return
        }
        otherGenerationAbsent.add(symbol)
    }

    /**
     * One line for every cross-generation miss collected during an install pass.
     *
     * Called at the end of the pass — `hookSystemServer` / `hookPackage`, the two
     * entry points that hot reload also uses — and cleared afterwards. Each
     * process gets its own `Hooker` instance, so the list holds exactly one pass.
     */
    internal fun flushOtherGenerationAbsent() {
        if (otherGenerationAbsent.isEmpty()) return
        module.log(
            Log.DEBUG, HookerSupport.TAG,
            "cross-generation target(s) absent (${otherGenerationAbsent.size}), skip: " +
                otherGenerationAbsent.joinToString(", ")
        )
        otherGenerationAbsent.clear()
    }

    /** Collects one [probeFindings] entry; see the field for why they are merged. */
    internal fun recordProbe(finding: String) {
        probeFindings.add(finding)
    }

    /** One line for every read-only probe answered during this install pass. */
    internal fun flushProbes() {
        if (probeFindings.isEmpty()) return
        module.log(Log.INFO, HookerSupport.TAG, "probe: " + probeFindings.joinToString(" "))
        probeFindings.clear()
    }

    internal fun logSummary(process: String) {
        // No flush here on purpose: the merged probe / cross-generation lines are
        // flushed at the end of the install pass (`hookSystemServer` /
        // `hookPackage`), which is the only place guaranteed to run — see the
        // note there about the hot-reload path skipping this function.
        val crossGeneration = hookTargetsAbsentOtherGeneration
        module.log(
            Log.INFO, HookerSupport.TAG, "HyperFCMLive active in $process: " +
                "$hooksInstalled hook(s) installed, " +
                "$hookTargetsAbsent target(s) absent on this ROM" +
                if (crossGeneration == 0) {
                    ""
                } else {
                    ", $crossGeneration cross-generation (expected)"
                }
        )
    }

    /** Runs one group; a throw inside it cannot reach the groups behind it. */
    internal fun installGroup(group: Group) {
        try {
            group.install()
        } catch (t: Throwable) {
            module.log(Log.ERROR, HookerSupport.TAG, "Failed to ${group.verb} ${group.name}", t)
        }
    }
}

/**
 * One install group. [verb] and [name] are the failure wording
 * (`Failed to <verb> <name>`), not decoration: that string is quoted in the
 * diagnostics doc and grepped out of overnight logs.
 */
internal class Group(val verb: String, val name: String, val install: () -> Unit)
