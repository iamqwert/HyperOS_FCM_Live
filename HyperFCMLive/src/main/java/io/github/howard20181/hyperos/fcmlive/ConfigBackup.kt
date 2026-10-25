package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.SharedPreferences
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme
import io.github.howard20181.hyperos.fcmlive.theme.AppearanceState
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemePrefs
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

    private const val HEADER = "# fcmlive-backup 2"

    /** The one prefix an import reads a setting from. */
    private const val SETTING_PREFIX = "# set "

    /** Package names only: what an edited file may legitimately contain. */
    private val PACKAGE_NAME = Regex("[A-Za-z0-9._]+")

    /** What [encode] produced, plus the counts the confirmation message quotes. */
    class Snapshot(val text: String, val packages: Int, val settings: Int)

    /** What an imported file turned out to hold. */
    class Restored(
        val packages: Set<String>,
        val settings: Int,
        /** True when a look-and-feel value moved, so the caller must re-skin. */
        val appearanceChanged: Boolean
    ) {
        val isEmpty: Boolean get() = packages.isEmpty() && settings == 0
    }

    /**
     * The whole configuration as the file's text. Settings come first in the
     * order of [settings] — sections in the order a reader would look for them,
     * keys in a fixed order so two backups of the same state are identical
     * files — then the package list, sorted.
     */
    fun encode(context: Context): Snapshot {
        val table = settings()
        val builder = StringBuilder()
        builder.append(HEADER).append('\n')
        var section: String? = null
        for (setting in table) {
            if (setting.section != section) {
                section = setting.section
                builder.append("# ").append(setting.section).append('\n')
            }
            builder.append(SETTING_PREFIX)
                .append(setting.key)
                .append('=')
                .append(setting.read(context))
                .append('\n')
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
        return Snapshot(builder.toString(), sorted.size, table.size)
    }

    /**
     * Applies [text] and reports what landed. Unknown keys are skipped, not
     * rejected: a file written by a newer build stays importable here, minus the
     * settings this build has never heard of.
     */
    fun decode(context: Context, text: String): Restored {
        val parsed = parse(text)
        val values = parsed.values
        val packages = parsed.packages

        var applied = 0
        var appearance = false
        // Table order, not file order: `color_spec` can move `palette_style`
        // (it is that row's superior in the appearance page), and a hand-edited
        // file must not be able to change the outcome by reordering lines.
        for (setting in settings()) {
            val value = values[setting.key] ?: continue
            if (!setting.write(context, value)) continue
            applied++
            if (setting.appearance) appearance = true
        }
        if (appearance) {
            // The disk is authoritative and already written; this is the half
            // recomposition does not get for free — the Compose state every
            // appearance row and the theme engine read.
            AppearanceState.hydrate(context)
            ThemeEngine.invalidate()
        }
        return Restored(packages, applied, appearance)
    }

    /** What a file contained, before anything was written. */
    class Parsed(
        val values: Map<String, String>,
        val packages: Set<String>
    )

    /**
     * Reads the file's structure and nothing else — no prefs, no context — so
     * the format can be pinned by a JVM test ([ConfigBackupTest]).
     */
    fun parse(text: String): Parsed {
        val values = HashMap<String, String>()
        val packages = LinkedHashSet<String>()
        var first = true
        for (raw in text.lineSequence()) {
            // A file round-tripped through a Windows editor can carry a BOM; it
            // would otherwise glue itself onto the header and the first package.
            val line = if (first) raw.removePrefix("\uFEFF").also { first = false } else raw
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
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
        return Parsed(values, packages)
    }

    /** Resolves the incoming values against the tables' own rules. */
    private class Setting(
        val section: String,
        val key: String,
        val appearance: Boolean,
        val read: (Context) -> String,
        val write: (Context, String) -> Boolean
    )

    private fun toggle(
        section: String,
        key: String,
        read: (Context) -> Boolean,
        appearance: Boolean = false,
        write: (Context, Boolean) -> Unit
    ) = Setting(
        section = section,
        key = key,
        appearance = appearance,
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
        write: (Context, Int) -> Unit
    ) = Setting(
        section = section,
        key = key,
        appearance = appearance,
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
                Prefs.KEY_AUTOSTART_GATE_RELEASE,
                Prefs::readLocalAutostartGateRelease
            ) { c, on -> Prefs.writeAutostartGateRelease(c, remote, on) },
            toggle(
                "Experiments",
                Prefs.KEY_AUTOSTART_RESTART_RELEASE,
                Prefs::readLocalAutostartRestartRelease
            ) { c, on -> Prefs.writeAutostartRestartRelease(c, remote, on) },
            toggle(
                "Experiments",
                Prefs.KEY_AUTOSTART_ROOT_RELEASE,
                Prefs::readLocalAutostartRootRelease
            ) { c, on -> Prefs.writeAutostartRootRelease(c, remote, on) },
            toggle(
                "Experiments",
                Prefs.KEY_GREEZE_NET_RELEASE,
                Prefs::readLocalGreezeNetRelease
            ) { c, on -> Prefs.writeGreezeNetRelease(c, remote, on) },
            toggle(
                "Experiments",
                Prefs.KEY_SMARTPOWER_RELEASE,
                Prefs::readLocalSmartPowerRelease
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
