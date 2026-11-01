package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.SharedPreferences
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme
import io.github.howard20181.hyperos.fcmlive.theme.AppearanceState
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemePrefs
import io.github.howard20181.hyperos.fcmlive.ui.isOs3Generation
import java.util.Locale

/**
 * The module's settings as one plain-text file — the format behind the Data
 * group's backup and restore rows.
 *
 * ## Why text, and why this shape
 *
 * A text file is the only backup this app can offer without a parser the app
 * itself must version: the reader is the same repository the writer came from,
 * the user can edit it, and diffing two of them says exactly what changed.
 *
 * The file has two kinds of line:
 *
 * ```
 * # fcmlive-backup 2
 * # Module switches
 * # set sleep_keepalive=true
 * # set wifi_weak_signal_floor=40
 * # Apps
 * com.tencent.mm
 * ```
 *
 * - `# set key=value` — one setting. Settings ride **comment lines** on
 *   purpose: a build that predates this format skips every `#` line and
 *   imports the package list alone, which is the old backup's whole meaning.
 *   A bare `key=value` line would have been read as a package name by those
 *   builds and silently added `sleep_keepalive=true` to the allowlist.
 * - Anything else non-empty — a package name. Only well-formed package names
 *   are taken ([PACKAGE_NAME]); an edited file with prose in it imports what it
 *   can instead of stuffing garbage into the allowlist.
 *
 * Everything else (`# Module switches`, `# Apps`) is for the human reading the
 * file and is ignored on import.
 *
 * ## What is deliberately absent
 *
 * - `auto_check_baseline` — the "already known apps" set behind the new-app
 *   checker. It records when this install first saw each package; restoring a
 *   stale copy would make every app installed since read as new again.
 * - every `*_pending_push` key — those are sync state ("the module never saw
 *   this value yet"), not settings. Copying them would claim a push that never
 *   happened.
 *
 * ## Restore is not a raw write
 *
 * Values go back through the same `Prefs.write*` entry points the settings
 * screens use, so each one keeps its own mirror, pending-push flag and reload
 * broadcast. Out-of-range values are refused rather than clamped into something
 * the file never said: a `ui_style=7` from a newer build is skipped, and the
 * row that would have shown it keeps the value the user already has. The two
 * dial-shaped values that *do* clamp (`record_rows_limit`,
 * `wifi_weak_signal_floor`) clamp inside their own writers, where the menu
 * lives — not here.
 */
internal object ConfigBackup {

    /** Suggested name in the document picker; the reader accepts any file. */
    const val FILE_NAME = "fcmlive-config.txt"

    /** The one prefix an import reads a setting from. */
    private const val SETTING_PREFIX = "# set "

    /**
     * The file's identity line, `<HEADER_PREFIX> <version>`.
     *
     * The version is checked on import: a file written by a *newer* build is
     * refused instead of guessed at, because a later format may give the same
     * bare lines a different meaning. A file with no header at all is treated as
     * a pre-format allowlist (packages only) — see [decode].
     */
    private const val HEADER_PREFIX = "# fcmlive-backup"

    /** The version this build writes. Adding a key does not bump it; changing the shape does. */
    private const val FORMAT_VERSION = 2

    private const val HEADER = "$HEADER_PREFIX $FORMAT_VERSION"

    /**
     * Package names only: what an edited file may legitimately contain.
     *
     * At least one dot is required — Android rejects a single-segment package
     * name, and the looseness this replaces (`[A-Za-z0-9._]+`) meant every
     * English word in a mistakenly picked text file ("hello", "true") was
     * imported into the allowlist.
     */
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    /** What [encode] produced, plus the counts the confirmation message quotes. */
    class Snapshot(val text: String, val packages: Int, val settings: Int)

    /** What an imported file turned out to hold. */
    class Restored(
        val packages: Set<String>,
        val settings: Int,
        /**
         * Settings the file carried but this run did not apply: keys this build
         * does not know, values it refuses, and keys that do not exist on this
         * device's generation.
         */
        val skipped: Int,
        /** True when a look-and-feel value moved, so the caller must re-skin. */
        val appearanceChanged: Boolean,
        /** Non-null when the file was refused outright and nothing was written. */
        val rejection: Rejection? = null
    ) {
        enum class Rejection {
            /** The file declares a format version newer than this build understands. */
            TOO_NEW
        }

        val isEmpty: Boolean
            get() = rejection == null && packages.isEmpty() && settings == 0 && skipped == 0
    }

    /**
     * The whole configuration as the file's text. Settings come first in the
     * order of [settings] — sections in the order a reader would look for them,
     * keys in a fixed order so two backups of the same state are identical
     * files — then the package list, sorted.
     *
     * Settings whose target does not exist on this device's generation are left
     * out entirely: writing them would put a value in the file that the source
     * machine never acted on, and a later restore elsewhere would carry it
     * silently (the Smart Power key is the case in hand).
     */
    fun encode(context: Context): Snapshot {
        val table = settings()
        val builder = StringBuilder()
        builder.append(HEADER).append('\n')
        var section: String? = null
        var written = 0
        for (setting in table) {
            if (!setting.available(context)) continue
            if (setting.section != section) {
                section = setting.section
                builder.append("# ").append(setting.section).append('\n')
            }
            builder.append(SETTING_PREFIX)
                .append(setting.key)
                .append('=')
                .append(setting.read(context))
                .append('\n')
            written++
        }

        // The remote copy is the source of truth once the module service is
        // bound; before that the local mirror is all this process has. Same
        // order the settings screens read in, so a backup taken while the
        // module is dead still holds what the user last chose.
        val allow = Prefs.readAllowlist(Prefs.remote())
        val packages = if (allow.isEmpty()) Prefs.readLocalAllowlist(context) else allow
        val sorted = packages.sorted()
        if (sorted.isNotEmpty()) {
            builder.append("# Apps\n")
            for (pkg in sorted) {
                builder.append(pkg).append('\n')
            }
        }
        return Snapshot(builder.toString(), sorted.size, written)
    }

    /**
     * Applies [text] and reports what landed. Unknown keys are skipped, not
     * rejected: a file written by a newer build stays importable here, minus the
     * settings this build has never heard of — and the count of those is
     * reported, so the user is not told "restored" for values that went nowhere.
     *
     * A file with no `# fcmlive-backup` header is a pre-format allowlist (or not
     * ours at all): only its package lines are honoured. Applying its `# set`
     * lines would let any text file write module switches.
     */
    fun decode(context: Context, text: String): Restored {
        val parsed = parse(text)
        val version = parsed.headerVersion
        if (version != null && version > FORMAT_VERSION) {
            return Restored(
                emptySet(), 0, 0, false,
                Restored.Rejection.TOO_NEW
            )
        }
        val values = if (version == null) emptyMap() else parsed.values
        val packages = parsed.packages

        var applied = 0
        var skipped = 0
        var appearance = false
        val table = settings()
        val knownKeys = HashSet<String>(table.size)
        // Table order, not file order: `color_spec` can move `palette_style`
        // (it is that row's superior in the appearance page), and a hand-edited
        // file must not be able to change the outcome by reordering lines.
        for (setting in table) {
            knownKeys.add(setting.key)
            val value = values[setting.key] ?: continue
            // A value for a target this device does not carry (e.g. the
            // OS3-only Smart Power switch restored on OS4) is skipped rather
            // than written: writing it would create a setting the user can
            // neither see nor turn off, and a later backup would carry it on.
            if (!setting.available(context)) {
                skipped++
                continue
            }
            if (!setting.write(context, value)) {
                skipped++
                continue
            }
            applied++
            if (setting.appearance) appearance = true
        }
        skipped += values.keys.count { it !in knownKeys }
        if (appearance) {
            // The disk is authoritative and already written; this is the half
            // recomposition does not get for free — the Compose state every
            // appearance row and the theme engine read.
            AppearanceState.hydrate(context)
            ThemeEngine.invalidate()
        }
        return Restored(packages, applied, skipped, appearance)
    }

    /** What a file contained, before anything was written. */
    class Parsed(
        val values: Map<String, String>,
        val packages: Set<String>,
        /**
         * The number after `# fcmlive-backup`, or null when the first non-empty
         * line is not that header — the pre-format allowlist shape.
         */
        val headerVersion: Int?
    )

    /**
     * Reads the file's structure and nothing else — no prefs, no context — so
     * the format can be pinned by a JVM test ([ConfigBackupTest]).
     */
    fun parse(text: String): Parsed {
        val values = HashMap<String, String>()
        val packages = LinkedHashSet<String>()
        var headerVersion: Int? = null
        var first = true
        // The header is the first non-empty line and nowhere else: an import
        // reads a `#` comment anywhere, so a hand-edited file that moves the
        // marker lower would otherwise be judged by the comment it landed on.
        var headerChecked = false
        for (raw in text.lineSequence()) {
            // A file round-tripped through a Windows editor can carry a BOM; it
            // would otherwise glue itself onto the header and the first package.
            val line = if (first) raw.removePrefix("\uFEFF").also { first = false } else raw
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!headerChecked) {
                headerChecked = true
                if (trimmed.startsWith(HEADER_PREFIX)) {
                    headerVersion = trimmed.removePrefix(HEADER_PREFIX).trim().toIntOrNull()
                }
            }
            if (trimmed.startsWith(SETTING_PREFIX)) {
                val body = trimmed.substring(SETTING_PREFIX.length)
                val split = body.indexOf('=')
                if (split <= 0) continue
                val key = body.substring(0, split).trim()
                if (key.isNotEmpty()) values[key] = body.substring(split + 1).trim()
                continue
            }
            if (trimmed.startsWith("#")) continue
            if (PACKAGE_NAME.matches(trimmed)) packages.add(trimmed)
        }
        return Parsed(values, packages, headerVersion)
    }

    /** Resolves the incoming values against the tables' own rules. */
    private class Setting(
        val section: String,
        val key: String,
        val appearance: Boolean,
        /**
         * Whether this device's generation carries the setting at all. A
         * generation-specific switch (Smart Power, OS3-only) answers false
         * elsewhere, which keeps it out of both the backup and a restore.
         */
        val available: (Context) -> Boolean,
        val read: (Context) -> String,
        val write: (Context, String) -> Boolean
    )

    private fun toggle(
        section: String,
        key: String,
        read: (Context) -> Boolean,
        appearance: Boolean = false,
        available: (Context) -> Boolean = { true },
        write: (Context, Boolean) -> Unit
    ) = Setting(
        section = section,
        key = key,
        appearance = appearance,
        available = available,
        read = { read(it).toString() },
        write = { context, raw ->
            val on = when (raw.lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                else -> null
            }
            if (on == null) {
                false
            } else {
                write(context, on)
                true
            }
        }
    )

    private fun number(
        section: String,
        key: String,
        read: (Context) -> Int,
        valid: (Int) -> Boolean = { true },
        appearance: Boolean = false,
        available: (Context) -> Boolean = { true },
        write: (Context, Int) -> Unit
    ) = Setting(
        section = section,
        key = key,
        appearance = appearance,
        available = available,
        read = { read(it).toString() },
        write = { context, raw ->
            val value = raw.toIntOrNull()
            if (value == null || !valid(value)) {
                false
            } else {
                write(context, value)
                true
            }
        }
    )

    /**
     * The keys this build owns, in file order.
     *
     * The `# Appearance` keys are spelled out as literals rather than imported:
     * [ThemePrefs] keeps them private because nothing outside it is supposed to
     * write them, and a backup format is a compatibility contract — the file's
     * key must not follow a rename inside that object. They are, verbatim, the
     * keys of the `fcmlive_theme` prefs file.
     */
    private fun settings(): List<Setting> {
        val remote = Prefs.remote()
        return listOf(
            toggle(
                "Module switches",
                Prefs.KEY_SLEEP_KEEPALIVE,
                Prefs::readLocalSleepKeepalive
            ) { c, on -> Prefs.writeSleepKeepalive(c, remote, on) },
            toggle(
                "Module switches",
                Prefs.KEY_SLEEP_KEEPALIVE_DATA,
                Prefs::readLocalSleepKeepaliveData
            ) { c, on -> Prefs.writeSleepKeepaliveData(c, remote, on) },

            toggle(
                "Experiments",
                Prefs.KEY_WECHAT_DOZE_KEEPOUT,
                Prefs::readLocalWechatDozeKeepout
            ) { c, on -> Prefs.writeWechatDozeKeepout(c, remote, on) },
            toggle(
                "Experiments",
                Prefs.KEY_WIFI_WEAK_SIGNAL_SWITCH_RELAXED,
                Prefs::readLocalWifiWeakSignalSwitchRelaxed
            ) { c, on -> Prefs.writeWifiWeakSignalSwitchRelaxed(c, remote, on) },
            number(
                "Experiments",
                Prefs.KEY_WIFI_WEAK_SIGNAL_FLOOR,
                Prefs::readLocalWifiWeakSignalFloor,
                valid = { Prefs.isValidWeakSignalFloor(it) }
            ) { c, floor -> Prefs.writeWifiWeakSignalFloor(c, remote, floor) },
            toggle(
                "Experiments",
                Prefs.KEY_GREEZE_NET_RELEASE,
                Prefs::readLocalGreezeNetRelease
            ) { c, on -> Prefs.writeGreezeNetRelease(c, remote, on) },
            toggle(
                "Experiments",
                Prefs.KEY_AUTOSTART_SERVICE_RELEASE,
                Prefs::readLocalAutostartServiceRelease
            ) { c, on -> Prefs.writeAutostartServiceRelease(c, remote, on) },
            toggle(
                "Experiments",
                Prefs.KEY_AUTOSTART_PROCESS_RELEASE,
                Prefs::readLocalAutostartProcessRelease
            ) { c, on -> Prefs.writeAutostartProcessRelease(c, remote, on) },
            toggle(
                "Experiments",
                Prefs.KEY_SMARTPOWER_RELEASE,
                Prefs::readLocalSmartPowerRelease,
                // OS3-only: the interception targets are absent on OS4, where the
                // settings page hides this row. Keeping it out of the file means
                // an OS3 backup cannot leave an invisible `true` behind on an
                // OS4 device — a value the user could not see or turn off.
                available = { isOs3Generation() }
            ) { c, on -> Prefs.writeSmartPowerRelease(c, remote, on) },

            toggle(
                "Debugging",
                Prefs.KEY_DEBUG_LOG,
                Prefs::readLocalDebugLog
            ) { c, on -> Prefs.writeDebugLog(c, remote, on) },

            // The app-list filter is read once per Activity, so a restored value
            // shows up on the next launch. Its baseline is kept from disk rather
            // than restored — see the class KDoc.
            toggle(
                "Apps page",
                Prefs.KEY_AUTO_CHECK_NEW,
                Prefs::readLocalAutoCheckNew
            ) { c, on ->
                Prefs.writeAutoCheckEnable(
                    c,
                    remote,
                    on,
                    Prefs.readLocalAutoCheckBaseline(c) ?: emptySet()
                )
            },
            toggle(
                "Apps page",
                Prefs.KEY_EXCLUDE_MIPUSH,
                Prefs::readLocalExcludeMiPush
            ) { c, on -> Prefs.writeExcludeMiPush(c, on) },
            toggle(
                "Apps page",
                Prefs.KEY_SHOW_SYSTEM,
                Prefs::readLocalShowSystem
            ) { c, on -> Prefs.writeShowSystem(c, on) },

            number(
                "Records",
                Prefs.KEY_RECORD_ROWS,
                Prefs::readRecordRows,
                valid = { it in Prefs.RECORD_ROWS_CHOICES }
            ) { c, rows -> Prefs.writeRecordRows(c, rows) },

            number(
                "Appearance",
                "ui_style",
                ThemePrefs::uiStyle,
                valid = { it in ThemePrefs.UI_STYLE_MATERIAL..ThemePrefs.UI_STYLE_SOFT_GLASS },
                appearance = true
            ) { c, style -> ThemePrefs.setUiStyle(c, style) },
            number(
                "Appearance",
                "theme_mode",
                ThemePrefs::themeMode,
                valid = { it in ThemePrefs.MODE_SYSTEM..ThemePrefs.MODE_DARK },
                appearance = true
            ) { c, mode -> ThemePrefs.setThemeMode(c, mode) },
            number(
                "Appearance",
                "palette_style",
                { ThemePrefs.paletteStyle(it).ordinal },
                valid = { it in Scheme.Variant.entries.indices },
                appearance = true
            ) { c, ordinal ->
                Scheme.Variant.entries.getOrNull(ordinal)?.let { ThemePrefs.setPaletteStyle(c, it) }
            },
            number(
                "Appearance",
                "color_spec",
                ThemePrefs::specVersion,
                valid = { it in ThemePrefs.SPEC_2021..ThemePrefs.SPEC_2026 },
                appearance = true
            ) { c, spec -> ThemePrefs.setSpecVersion(c, spec) },
            toggle(
                "Appearance",
                "dynamic_color",
                ThemePrefs::dynamicColor,
                appearance = true
            ) { c, on -> ThemePrefs.setDynamicColor(c, on) },
            number(
                "Appearance",
                "seed_color",
                ThemePrefs::seedColor,
                appearance = true
            ) { c, seed -> ThemePrefs.setSeedColor(c, seed) },
            toggle(
                "Appearance",
                "miuix_monet",
                ThemePrefs::miuixMonet,
                appearance = true
            ) { c, on -> ThemePrefs.setMiuixMonet(c, on) },
            number(
                "Appearance",
                "miuix_accent",
                ThemePrefs::miuixAccent,
                appearance = true
            ) { c, accent -> ThemePrefs.setMiuixAccent(c, accent) },
            toggle(
                "Appearance",
                "miuix_floating_bar",
                ThemePrefs::floatingBar,
                appearance = true
            ) { c, on -> ThemePrefs.setFloatingBar(c, on) }
        )
    }
}
