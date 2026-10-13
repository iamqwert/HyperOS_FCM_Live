package io.github.howard20181.hyperos.fcmlive.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.LauncherIcon
import io.github.howard20181.hyperos.fcmlive.Prefs
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.UpdateChecker
import io.github.howard20181.hyperos.fcmlive.mcu.Hct
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme
import io.github.howard20181.hyperos.fcmlive.theme.AppearanceState
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.MIUIX_ACCENT_OPTIONS
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import io.github.howard20181.hyperos.fcmlive.theme.ThemePrefs
import kotlinx.coroutines.launch

/**
 * Settings: appearance, allowlist backup, links, update check.
 *
 * Everything that used to be a hand-built View lives here now — the three
 * appearance menus were a ~190 line in-window overlay (positioned, outlined and
 * animated by hand because a popup window clipped its own overshoot), and the
 * seed swatches a custom [android.graphics.drawable.Drawable]. Compose has a
 * menu and a canvas, so both collapse to a few lines each.
 *
 * Side effects the screen cannot own stay in [AboutActions]: the SAF
 * import/export needs an `ActivityResultRegistry`, and the theme's View-side
 * half — window chrome and the back-preview snapshot — is the Activity's
 * `applyAppearanceChange`. The palette itself never reaches the Activity: the
 * appearance rows write through [AppearanceState], whose setters invalidate
 * the engine, and the theme re-skins in place.
 */
data class AboutActions(
    val onViewSource: () -> Unit,
    val onLicenses: () -> Unit,
    val onPrivacy: () -> Unit,
    val onExperiment: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
    val onHelp: () -> Unit,
    /** Runs the update check; [report] carries back which answer to show. */
    val onCheckUpdate: (report: (UpdateOutcome) -> Unit) -> Unit,
    /** Opens the release page for the update just offered, and clears the badge. */
    val onUpdateOpen: () -> Unit,
    /** Version line, read from PackageManager — the update check's own source. */
    val versionLine: String,
    /**
     * Repaints the View-side chrome a theme change must move but no
     * recomposition reaches: the window background and the back-preview
     * snapshot. The palette itself is invalidated by the [AppearanceState]
     * setters — this callback never touches it.
     */
    val onAppearanceChange: () -> Unit
)

/**
 * What a manual update check answered, in the terms the screen shows it.
 *
 * Three answers, not two: "no newer release" and "the check itself failed" read
 * very differently to someone who is wondering whether they are up to date, and
 * a failed check must not be allowed to look like a clean bill of health.
 */
sealed interface UpdateOutcome {
    /** Already on the newest release. */
    data object None : UpdateOutcome

    /** The check did not complete — no network, or nothing parseable came back. */
    data object Error : UpdateOutcome

    /** A newer release exists; [version] is what the offer names. */
    data class Available(val version: String) : UpdateOutcome
}

/** Theme modes in array order, i.e. [ThemePrefs.MODE_SYSTEM]..[ThemePrefs.MODE_AMOLED]. */
private val ALL_THEME_MODES = listOf(0, 1, 2, 3)

/** The modes the Miuix look offers: its own dark palette has no pure-black variant. */
private val MODES_SYSTEM_LIGHT_DARK = listOf(0, 1, 2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    actions: AboutActions,
    modifier: Modifier = Modifier,
    /**
     * The M3 feedback surface. Owned by the caller — the messages come from
     * there — and hosted here, because this is the tree on screen.
     */
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    PageScaffold(
        titleRes = R.string.settings,
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        // The help entry is an icon under Material and a section card under
        // Miuix (the "其他" section at the foot of the page) — the system's
        // settings put nothing but settings on the bar, and a lone icon is the
        // Material way of saying "there is one more thing".
        actions = if (LocalUiStyle.current == UiStyle.Miuix) {
            {}
        } else {
            {
                val helpText = stringResource(R.string.help)
                TopBarIconButton(
                    painter = painterResource(R.drawable.ic_help),
                    description = helpText,
                    onClick = actions.onHelp
                )
            }
        }
    ) { innerPadding ->
        AboutBody(
            actions = actions,
            snackbarHostState = snackbarHostState,
            bottomPadding = innerPadding.calculateBottomPadding(),
            modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
        )
    }
}

@Composable
private fun AboutBody(
    actions: AboutActions,
    snackbarHostState: SnackbarHostState,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Everything appearance-related reads its live value straight from
    // [AppearanceState] — the Compose state layer over ThemePrefs — so there is
    // no remembered mirror to keep in step: a setter writes the pref, the state
    // every reader subscribes to, and the palette engine in one call. Only the
    // two non-appearance values keep local state: the launcher-icon visibility
    // is a system-side setting with no palette behind it, and the update badge
    // is screen state by definition.
    var hideIcon by remember { mutableStateOf(LauncherIcon.isHidden(context)) }
    // Read once per screen: the hook side owns the live value, and the mirror
    // is only the answer the UI last left behind (same rule as the experiment
    // page's mirrors).
    var wechatDozeKeepout by remember { mutableStateOf(Prefs.readLocalWechatDozeKeepout(context)) }
    var sleepKeepalive by remember { mutableStateOf(Prefs.readLocalSleepKeepalive(context)) }
    var sleepKeepaliveData by remember {
        mutableStateOf(Prefs.readLocalSleepKeepaliveData(context))
    }
    var debugLog by remember {
        mutableStateOf(Prefs.readLocalDebugLog(context))
    }
    var updateBadge by remember { mutableStateOf(UpdateChecker.isUpdateAvailable(context)) }
    val themeMode by AppearanceState.themeMode
    val uiStyle by AppearanceState.uiStyle
    val dynamic by AppearanceState.dynamicColor
    val seed by AppearanceState.seedColor
    val miuixMonet by AppearanceState.miuixMonet
    val miuixAccent by AppearanceState.miuixAccent
    val paletteStyle by AppearanceState.paletteStyle
    val colorSpec by AppearanceState.colorSpec
    // The whole theme section is scoped by this: 动态颜色 / 调色风格 / 颜色规格 are
    // the app's own colour engine's controls, and the Miuix look does not go
    // through that engine at all. A row that changes nothing has no business being
    // on screen, and a row that is hidden but still feeding the look is worse —
    // see MiuixStyleTheme, which stops reading them for exactly that reason.
    val miuixStyle = uiStyle == ThemePrefs.UI_STYLE_MIUIX
    // The offer, while it is on screen. Keeping it here rather than in the
    // Activity is what makes the dialog a Compose dialog: it is screen state,
    // and the Activity only supplies the URL it would open.
    var updateOffer by remember { mutableStateOf<UpdateOutcome.Available?>(null) }

    val modeEntries = stringArrayResource(R.array.theme_mode_entries)
    val uiStyleEntries = stringArrayResource(R.array.ui_style_entries)
    val styleEntries = stringArrayResource(R.array.palette_style_entries)
    val specEntries = stringArrayResource(R.array.color_spec_entries)
    // 强调色's menu: entry 0 is the stored 0 (跟随壁纸), the rest are
    // [MIUIX_ACCENT_OPTIONS] in order. One list for labels, one for values, the
    // same index over both — the mapping the pick writes through.
    val accentEntries = stringArrayResource(R.array.miuix_accent_entries)
    val accentValues = remember { listOf(0) + MIUIX_ACCENT_OPTIONS.toList() }

    // The two appearance menus are scoped by each other, because the settings
    // are not independent: the 2025 spec covers four palette styles and nothing
    // else (`Scheme.Variant.supportsExpressive2025`), so every other style falls
    // back to 2021. Offering a style the spec in effect cannot render is
    // offering a choice that silently does nothing; offering a spec the current
    // style cannot take is offering one whose label never moves. Each menu
    // therefore lists only what is reachable from where the user is now:
    //   palette ⟹ supported by the spec in effect
    //   spec    ⟹ 2025 only while the style can take it
    // Note the lists cannot strand the current value: an in-effect 2025 implies
    // by construction a style that supports it, so that style is always in the
    // filtered list.
    //
    // Both lists are built from the *resolved* spec, which is also what makes
    // this the way back out of a pair the 2025 spec cannot render: the request
    // itself is never rewritten (see ThemePrefs.specVersion), so a style that
    // can take it brings 2025 straight back.
    val paletteVariants = Scheme.Variant.entries.filter {
        colorSpec != ThemePrefs.SPEC_2025 || it.supportsExpressive2025
    }
    val paletteStyleNow = variantAt(paletteStyle)
    val specValues = if (paletteStyleNow.supportsExpressive2025) {
        listOf(ThemePrefs.SPEC_2021, ThemePrefs.SPEC_2025)
    } else {
        listOf(ThemePrefs.SPEC_2021)
    }

    val checkingText = stringResource(R.string.update_checking)
    val noneText = stringResource(R.string.update_none)
    val errorText = stringResource(R.string.update_error)
    val updateOpenLabel = stringResource(R.string.update_open)
    val cancelLabel = stringResource(android.R.string.cancel)
    val hideText = stringResource(R.string.hide_icon_toast)
    val showText = stringResource(R.string.show_icon_toast)
    // Read here, in composition, not inside show(): LocalUiStyle.current is a
    // @Composable read and show() is a plain function.
    val miuixNow = LocalUiStyle.current == UiStyle.Miuix

    fun show(text: String) {
        if (miuixNow) {
            // This look's one-line acknowledgment is the system toast, not the
            // Material bar at the page's foot.
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            // One line at a time: the message replacing the current one should
            // not have to wait out its predecessor's four seconds first.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    updateOffer?.let { offer ->
        AppDialog(
            onDismissRequest = { updateOffer = null },
            title = stringResource(R.string.update_found_title),
            // The badge is cleared where the URL is opened, so the stored flag
            // and what is on screen can only move together.
            text = stringResource(R.string.update_found, offer.version),
            confirmLabel = updateOpenLabel,
            onConfirm = {
                updateBadge = false
                actions.onUpdateOpen()
            },
            dismissLabel = cancelLabel
        )
    }

    val inset = pageHorizontalInset()
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = inset, end = inset),
        // The four appearance menus all re-skin the app on a pick, so this page is
        // the one place a menu could visibly throw the reader back to the start.
        state = rememberPageListState(),
        // The gesture-hint strip is scroll-through, not a hard stop: rows pass
        // under it while scrolling and only rest that far above the edge.
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        item {
            SectionTitle(R.string.about_section_display, first = true)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_hide_icon,
                title = stringResource(R.string.hide_launcher_icon),
                description = "",
                stateLine = stringResource(R.string.about_sub_hide_icon_on),
                stateLineOff = stringResource(R.string.about_sub_hide_icon_off),
                checked = hideIcon,
                onCheckedChange = { checked ->
                    LauncherIcon.setHidden(context, checked)
                    show(if (checked) hideText else showText)
                    hideIcon = checked
                }
            )
        }

        item {
            SectionTitle(R.string.about_section_theme)
            // AMOLED is the Material engine's pure-black ramp; the Miuix look is
            // dressed by Miuix's own dark palette and never reads this mode, so
            // the entry is material-only — mirroring the colour rows below. The
            // stored value survives the hiding: a mode picked while in Material
            // still applies there after switching back. An index of -1 (AMOLED
            // stored while this look is on) simply leaves the row without a
            // highlighted entry; the dropdown tolerates it.
            val visibleModes = if (miuixStyle) MODES_SYSTEM_LIGHT_DARK else ALL_THEME_MODES
            PopupCard(
                iconRes = R.drawable.ic_theme_mode,
                title = stringResource(R.string.theme_mode),
                subtitle = stringResource(R.string.about_sub_theme_mode),
                entries = visibleModes.map { modeEntries[it] },
                currentIndex = visibleModes.indexOf(themeMode),
                first = true,
                last = false,
                onPick = { index ->
                    AppearanceState.setThemeMode(context, visibleModes[index])
                    actions.onAppearanceChange()
                }
            )
            GroupRowGap()
            PopupCard(
                iconRes = R.drawable.ic_ui_style,
                title = stringResource(R.string.ui_style),
                subtitle = stringResource(R.string.about_sub_ui_style),
                entries = uiStyleEntries.toList(),
                currentIndex = uiStyle,
                first = false,
                last = false,
                onPick = { index ->
                    AppearanceState.setUiStyle(context, index)
                    actions.onAppearanceChange()
                }
            )
            // Only the Miuix look reads this. Under the app's own look the colours
            // come from its engine and 动态颜色 below is the switch for them, so a
            // second Monet row would offer a choice that changes nothing — and the
            // one that does change something would be a row away from it.
            if (miuixStyle) {
                GroupRowGap()
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_dynamic_color,
                    title = stringResource(R.string.miuix_monet),
                    description = stringResource(R.string.about_sub_miuix_monet),
                    checked = miuixMonet,
                    onCheckedChange = { checked ->
                        AppearanceState.setMiuixMonet(context, checked)
                        actions.onAppearanceChange()
                    },
                    first = false,
                    // Last of the section while Monet is off; the 强调色 row
                    // underneath takes the corner while it is on.
                    last = !miuixMonet
                )
                // The key colour the Monet derivation starts from, offered as the
                // reference manager (KernelSU's colour palette) offers its own:
                // one menu, "follow the wallpaper" first, the preset swatches
                // behind it. The row means nothing with Monet off — the palette
                // is then Miuix's own HyperOS scheme, which no key colour
                // reaches — so it only exists while the switch above is on,
                // exactly the way the Material body hides its seed grid while
                // dynamic colour runs.
                if (miuixMonet) {
                    GroupRowGap()
                    PopupCard(
                        iconRes = R.drawable.ic_palette_style,
                        title = stringResource(R.string.miuix_accent),
                        subtitle = stringResource(R.string.about_sub_miuix_accent),
                        entries = accentEntries.toList(),
                        currentIndex = accentValues.indexOf(miuixAccent)
                            .takeIf { it >= 0 } ?: 0,
                        first = false,
                        last = true,
                        onPick = { index ->
                            AppearanceState.setMiuixAccent(context, accentValues[index])
                            actions.onAppearanceChange()
                        }
                    )
                }
            }
            // Material only, on screen: these are the app's own colour engine's
            // controls and the Miuix look is not dressed by that engine. Hidden here
            // but still read by it, so a seed or a palette family picked while in
            // Material carries across instead of being silently dropped — the why is
            // argued at MiuixStyleTheme, where the reads live.
            if (!miuixStyle) {
                GroupRowGap()
                DynamicColorCard(
                    dynamic = dynamic,
                    seed = seed,
                    first = false,
                    last = false,
                    onDynamicChange = { checked ->
                        AppearanceState.setDynamicColor(context, checked)
                        actions.onAppearanceChange()
                    },
                    onSeedChange = { color ->
                        AppearanceState.setSeedColor(context, color)
                        actions.onAppearanceChange()
                    }
                )
                GroupRowGap()
                PopupCard(
                    iconRes = R.drawable.ic_palette_style,
                    title = stringResource(R.string.palette_style),
                    subtitle = stringResource(R.string.about_sub_palette_style),
                    // Entry index == Scheme.Variant.ordinal, which is how the
                    // string array is written; [paletteVariants] is that list minus
                    // whatever the spec in effect cannot render.
                    entries = paletteVariants.map { styleEntries[it.ordinal] },
                    currentIndex = paletteVariants.indexOf(paletteStyleNow),
                    first = false,
                    last = false,
                    onPick = { index ->
                        // Mapping through the filtered list is what keeps the write
                        // honest: the index is a position in *this* menu, not an
                        // ordinal. The setter refreshes the resolved spec with it —
                        // changing the style can put the spec out of reach (see
                        // ThemePrefs.specVersion), so the spec row re-reads the
                        // resolved value instead of keeping its own copy — the two
                        // rows can only ever move together.
                        AppearanceState.setPaletteStyle(context, paletteVariants[index])
                        actions.onAppearanceChange()
                    }
                )
                GroupRowGap()
                PopupCard(
                    iconRes = R.drawable.ic_color_spec,
                    title = stringResource(R.string.color_spec),
                    subtitle = stringResource(R.string.about_sub_color_spec),
                    entries = specValues.map { specEntries[it] },
                    currentIndex = specValues.indexOf(colorSpec),
                    first = false,
                    last = true,
                    onPick = { index ->
                        AppearanceState.setSpecVersion(context, specValues[index])
                        actions.onAppearanceChange()
                    }
                )
            }
        }

        item {
            // The battery switch is a plain runtime switch like any appearance
            // toggle — it never qualified as an experiment (no probe, no gate,
            // one hook reading one flag), so it lives on this page instead of
            // behind the experiment gateway. Beside it sits a plain entry into
            // the AOSP battery-usage page: the switch above moves WeChat out of
            // the optimization whitelist, and this is where the user checks the
            // state that page shows.
            SectionTitle(R.string.about_section_battery)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_energy_program_time_used,
                title = stringResource(R.string.experiment_wechat_doze_keepout),
                description = stringResource(R.string.experiment_wechat_doze_keepout_desc),
                checked = wechatDozeKeepout,
                onCheckedChange = { checked ->
                    Prefs.writeWechatDozeKeepout(context, Prefs.remote(), checked)
                    wechatDozeKeepout = checked
                },
                first = true,
                last = false
            )
            GroupRowGap()
            NavCard(
                iconRes = R.drawable.ic_battery_profile,
                title = stringResource(R.string.about_battery_aosp_usage),
                subtitle = stringResource(R.string.about_battery_aosp_usage_desc),
                onClick = {
                    try {
                        context.startActivity(
                            Intent().setClassName(
                                "com.android.settings",
                                "com.android.settings.Settings\$AppBatteryUsageActivity"
                            )
                        )
                    } catch (ignored: ActivityNotFoundException) {
                        Toast.makeText(
                            context,
                            R.string.about_battery_aosp_usage_missing,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                first = false,
                last = true
            )
        }

        item {
            // Same rationale as the battery row: two runtime switches, one
            // hook flag each, no probe and no gate — sleep mode's network
            // cutoff is a user-facing behaviour, not an experiment. Two
            // radios, two switches, no master: whichever is on is the half of
            // the network that survives the night, and the other half is left
            // to the ROM either way, so both rows are always on screen.
            SectionTitle(R.string.experiment_section_sleep)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_signal_wifi_3_bar_lock,
                title = stringResource(R.string.experiment_sleep_keepalive),
                description = stringResource(R.string.experiment_sleep_keepalive_desc),
                checked = sleepKeepalive,
                onCheckedChange = { checked ->
                    Prefs.writeSleepKeepalive(context, Prefs.remote(), checked)
                    sleepKeepalive = checked
                },
                first = true,
                last = false
            )
            GroupRowGap()
            SettingsSwitchCard(
                iconRes = R.drawable.ic_network_locked,
                title = stringResource(R.string.experiment_sleep_keepalive_data),
                description = stringResource(R.string.experiment_sleep_keepalive_data_desc),
                checked = sleepKeepaliveData,
                onCheckedChange = { checked ->
                    Prefs.writeSleepKeepaliveData(context, Prefs.remote(), checked)
                    sleepKeepaliveData = checked
                },
                first = false,
                last = true
            )
        }

        item {
            // One merged section: the debug switch on top (a pure emission
            // toggle — nothing here changes what a hook decides, only whether
            // the log line describing it is written out), the experiment
            // gateway below. No confirm dialog, no allowlist dependency in
            // either row.
            SectionTitle(R.string.about_section_experiment_debug)
            SettingsSwitchCard(
                // Style-dependent icon: under Material 3 the row carries the
                // filled bug_report glyph — the "log a defect report" reading —
                // while the MIUIX style keeps the diagnostics glyph it shares
                // with the module-status page.
                iconRes = if (AppearanceState.uiStyle.intValue == ThemePrefs.UI_STYLE_MATERIAL) {
                    R.drawable.ic_bug_report
                } else {
                    R.drawable.ic_fcm_diagnostics
                },
                title = stringResource(R.string.experiment_debug_log),
                description = stringResource(R.string.experiment_debug_log_desc),
                checked = debugLog,
                onCheckedChange = { checked ->
                    Prefs.writeDebugLog(context, Prefs.remote(), checked)
                    debugLog = checked
                },
                first = true,
                last = false
            )
            GroupRowGap()
            NavCard(
                iconRes = R.drawable.ic_science,
                title = stringResource(R.string.experiment),
                subtitle = stringResource(R.string.about_sub_experiment),
                onClick = actions.onExperiment,
                first = false,
                last = true
            )
        }

        // Under Miuix the help entry lives in its own one-card "其他" section,
        // right after 实验与调试 — the material the help covers is the toggles
        // behind that gateway, so the two read together — and before
        // 数据/关于, the closing groups the way the system's own settings park
        // their project rows at the foot. Under Material the top-bar icon is
        // the entry and this section is not composed at all.
        if (miuixStyle) {
            item {
                SectionTitle(R.string.about_section_other)
                NavCard(
                    iconRes = R.drawable.ic_help,
                    title = stringResource(R.string.help_guide),
                    subtitle = stringResource(R.string.about_sub_help),
                    onClick = actions.onHelp,
                    first = true,
                    last = true
                )
            }
        }

        item {
            // The data section sits directly above 关于, keeping the project
            // rows as the closing group the way the system's own settings park
            // theirs at the foot.
            SectionTitle(R.string.about_section_backup)
            NavCard(
                iconRes = R.drawable.ic_export,
                title = stringResource(R.string.export_allowlist),
                subtitle = stringResource(R.string.about_sub_export),
                onClick = actions.onExport,
                first = true,
                last = false
            )
            GroupRowGap()
            NavCard(
                iconRes = R.drawable.ic_import,
                title = stringResource(R.string.import_allowlist),
                subtitle = stringResource(R.string.about_sub_import),
                onClick = actions.onImport,
                first = false,
                last = true
            )
        }

        item {
            // One group, as the XML had it: the update check, the privacy page
            // and both project links share the "关于" card. Splitting privacy
            // into a group of its own moved two rows that belong under it.
            SectionTitle(R.string.about_section_project)
            NavCard(
                iconRes = R.drawable.ic_check_update,
                title = stringResource(R.string.check_for_updates),
                subtitle = actions.versionLine,
                onClick = {
                    show(checkingText)
                    actions.onCheckUpdate { outcome ->
                        updateBadge = outcome is UpdateOutcome.Available
                        when (outcome) {
                            UpdateOutcome.None -> show(noneText)
                            UpdateOutcome.Error -> show(errorText)
                            is UpdateOutcome.Available -> updateOffer = outcome
                        }
                    }
                },
                first = true,
                last = false,
                trailing = {
                    if (updateBadge) {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                    }
                }
            )
            GroupRowGap()
            NavCard(
                iconRes = R.drawable.ic_privacy,
                title = stringResource(R.string.about_section_privacy),
                subtitle = stringResource(R.string.about_sub_privacy),
                onClick = actions.onPrivacy,
                first = false,
                last = false
            )
            GroupRowGap()
            NavCard(
                iconRes = R.drawable.ic_view_source,
                title = stringResource(R.string.view_source_code),
                subtitle = stringResource(R.string.about_sub_source),
                onClick = actions.onViewSource,
                first = false,
                last = false
            )
            GroupRowGap()
            NavCard(
                iconRes = R.drawable.ic_license,
                title = stringResource(R.string.open_source_licenses),
                subtitle = stringResource(R.string.about_sub_licenses),
                onClick = actions.onLicenses,
                first = false,
                last = true
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * A row whose tap opens a menu: the current value sits on the right in the
 * accent, and the menu marks it with the primary container tone. The View
 * version built the panel by hand inside the window; a [DropdownMenu] does the
 * positioning.
 *
 * The menu must stay as narrow as its widest label. A [Modifier.fillMaxWidth]
 * row inside it makes the popup as wide as the window, and a window-wide menu
 * no longer fits beside its anchor — the position provider then falls back to
 * the window's own left margin, which is what put the panel out beside the
 * card instead of under it.
 */
@Composable
private fun PopupCard(
    iconRes: Int,
    title: String,
    subtitle: String,
    entries: List<String>,
    currentIndex: Int,
    first: Boolean,
    last: Boolean,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Under the Miuix look this row is the shared menu row: the value, the
    // up-down arrow beside it, and Miuix's own popup. The Material body below
    // differs only in chrome, so nothing is lost by delegating.
    if (LocalUiStyle.current == UiStyle.Miuix) {
        SettingsMenuCard(
            iconRes = iconRes,
            title = title,
            description = subtitle,
            entries = entries,
            currentIndex = currentIndex,
            onPick = onPick,
            modifier = modifier,
            first = first,
            last = last
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val shapes = LocalAppShapes.current
    val current = entries.getOrNull(currentIndex) ?: ""

    Box(modifier = modifier.fillMaxWidth()) {
        GroupRow(first = first, last = last, onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(painter = painterResource(iconRes))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // The accent, like the XML's `md_primary`: this is the one
                // place on the page the active choice is named, so it has to
                // move with the seed the rest of the page took.
                Text(
                    modifier = Modifier.padding(start = 12.dp),
                    text = current,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        // A DropdownMenu is a popup, and its anchor is the zero-size node the
        // popup leaves behind — so *where inside this Box that node lands* is
        // what the panel is measured from. Bottom-end puts it at the card's
        // bottom-right corner, which is where the hand-built panel was: the
        // position provider then prefers "menu end aligned to anchor end", so
        // the panel sits back from the card's right edge. Left at the default
        // top-start it aligned to the card's left edge and opened over the
        // card's own top rows.
        Box(
            modifier = Modifier
                .matchParentSize()
                .wrapContentSize(Alignment.BottomEnd)
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // Intrinsic width + full-width rows: the menu measures from its
                // widest label, and every row then stretches to that same
                // width. Left to size themselves, short options draw a ripple
                // and a selected plate narrower than the panel — the two
                // "颜色规格" rows were visibly not the same size.
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .defaultMinSize(minWidth = MENU_MIN_WIDTH),
                // The group radius, not the menu's own: the panel is drawn on
                // the page's card language, and M3's default menu corner is
                // much tighter than anything else on this screen.
                shape = shapes.menu,
                // Popup level, as every dropdown — see LocalAppSurfaces. This
                // is M3's own menu default (`MenuTokens.ContainerColor` =
                // `surfaceContainer`), the same level as the page; the panel's
                // own tone is what separates it from the card it lands on,
                // never an outline.
                containerColor = LocalAppSurfaces.current.popup,
                // Negative on purpose: the provider *adds* the offset to the
                // chosen candidate, and the candidate we land on is
                // "menu end at anchor end", so a negative X is what pulls the
                // panel back off the card's right edge.
                offset = DpOffset(x = -MENU_EDGE_INSET, y = MENU_ANCHOR_GAP)
            ) {
                entries.forEachIndexed { index, label ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
                    }
                    MenuItemRow(
                        label = label,
                        modifier = Modifier.fillMaxWidth(),
                        minWidth = MENU_MIN_WIDTH,
                        selected = index == currentIndex,
                        onClick = {
                            expanded = false
                            onPick(index)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Dynamic color plus its seed grid. While the switch is on the palette follows
 * the wallpaper, so the seeds would be meaningless — they are hidden, exactly
 * as the XML did. The grid joins and leaves with an expand/collapse so the
 * appearance reads as this card opening up rather than a block teleporting in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DynamicColorCard(
    dynamic: Boolean,
    seed: Int,
    first: Boolean,
    last: Boolean,
    onDynamicChange: (Boolean) -> Unit,
    onSeedChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = connectedGroupShape(first, last)
    val motion = MaterialTheme.motionScheme
    val stateLine = stringResource(
        if (dynamic) R.string.about_sub_dynamic_color_on
        else R.string.about_sub_dynamic_color_off
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LocalAppSurfaces.current.card,
        shape = shape
    ) {
        val view = LocalView.current
        // Same contract as [SettingsSwitchCard]: one toggle path for the row
        // and the switch itself, haptic exactly once per flip. `toggleable`
        // like the shared card too, so the row announces its state.
        val toggle: () -> Unit = {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onDynamicChange(!dynamic)
        }
        Column {
            Row(
                modifier = Modifier
                    // See [MenuItemRow]: the clip has to come before
                    // the click, or the ripple is a rectangle.
                    .clip(shape)
                    .toggleable(
                        value = dynamic,
                        role = Role.Switch,
                        onValueChange = { toggle() }
                    )
                    .height(72.dp)
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RowIcon(painter = painterResource(R.drawable.ic_dynamic_color))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dynamic_color),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stateLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    modifier = Modifier.padding(start = 12.dp),
                    checked = dynamic,
                    onCheckedChange = { toggle() },
                    colors = appSwitchColors(),
                    thumbContent = { SwitchThumbMark(checked = dynamic) }
                )
            }
            AnimatedVisibility(
                visible = !dynamic,
                // The theme's own motion, not a hard-coded 250/200ms pair. On
                // the Expressive spec these are the springs the surrounding
                // library components already move with, so the grid opens the
                // way the switch itself flips; on the 2021 spec they are the
                // standard curves. Pin the durations and this card becomes the
                // one place on the page that still animates like the old theme.
                enter = expandVertically(animationSpec = motion.defaultSpatialSpec()) +
                    fadeIn(animationSpec = motion.defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = motion.defaultSpatialSpec()) +
                    fadeOut(animationSpec = motion.defaultEffectsSpec())
            ) {
                SeedGrid(
                    selected = seed,
                    onPick = onSeedChange,
                    modifier = Modifier.padding(
                        start = 16.dp, end = 16.dp, bottom = 16.dp
                    )
                )
            }
        }
    }
}

/**
 * The preset seeds, six per row. The cell is the touch target so it stays at
 * least 36dp even when a narrow screen shrinks the dot inside it.
 */
@Composable
private fun SeedGrid(selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cell = maxOf(36.dp, maxWidth / SEED_COLUMNS)
        val dot = minOf(44.dp, cell - 8.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SEED_COLORS.toList().chunked(SEED_COLUMNS).forEach { row ->
                Row {
                    row.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(cell)
                                .clip(CircleShape)
                                .clickable { onPick(color) },
                            contentAlignment = Alignment.Center
                        ) {
                            SeedDot(seed = color, selected = color == selected, dotSize = dot)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A seed preview: a circle split into the tonal ramp the seed generates — dark
 * on the left, light on the right, mid tone in the bottom-right quadrant — so
 * each option hints at the palette it produces. The active one wears a primary
 * ring and a check on its dark half, where it stays readable.
 */
@Composable
private fun SeedDot(seed: Int, selected: Boolean, dotSize: androidx.compose.ui.unit.Dp) {
    val ringColor = MaterialTheme.colorScheme.primary
    val tones = remember(seed) { seedTones(seed) }
    Canvas(modifier = Modifier.size(dotSize)) {
        val stroke = 2.dp.toPx()
        val radius = size.minDimension / 2f - stroke
        val cx = size.width / 2f
        val cy = size.height / 2f
        val box = Size(radius * 2f, radius * 2f)
        val corner = Offset(cx - radius, cy - radius)
        drawCircle(color = tones.light, radius = radius, center = Offset(cx, cy))
        drawArc(
            color = tones.mid, startAngle = 0f, sweepAngle = 90f, useCenter = true,
            topLeft = corner, size = box
        )
        drawArc(
            color = tones.dark, startAngle = 90f, sweepAngle = 180f, useCenter = true,
            topLeft = corner, size = box
        )
        if (selected) {
            drawCircle(
                color = ringColor, radius = radius + stroke / 2f,
                center = Offset(cx, cy), style = Stroke(width = stroke)
            )
            val check = Path().apply {
                val kx = cx - radius * 0.45f
                moveTo(kx - radius * 0.28f, cy)
                lineTo(kx - radius * 0.05f, cy + radius * 0.23f)
                lineTo(kx + radius * 0.36f, cy - radius * 0.26f)
            }
            drawPath(
                path = check, color = Color.White,
                style = Stroke(
                    width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round
                )
            )
        }
    }
}

private class SeedTones(val dark: Color, val mid: Color, val light: Color)

/**
 * The three tones of a seed's ramp. Chroma is floored so near-grey seeds still
 * read as a ramp instead of three identical greys.
 */
private fun seedTones(seed: Int): SeedTones {
    val hct = Hct.fromInt(seed)
    val hue = hct.hue
    val chroma = maxOf(hct.chroma, 8.0)
    return SeedTones(
        dark = Color(Hct.from(hue, chroma, 40.0).toInt()),
        mid = Color(Hct.from(hue, chroma, 80.0).toInt()),
        light = Color(Hct.from(hue, chroma, 90.0).toInt())
    )
}

private fun variantAt(index: Int): Scheme.Variant {
    val values = Scheme.Variant.values()
    return if (index >= 0 && index < values.size) values[index] else Scheme.Variant.TONAL_SPOT
}

/** Preset seeds offered when dynamic color is off (MD3-friendly hues). */
private val SEED_COLORS = intArrayOf(
    0xFF6750A4.toInt(), // Material baseline purple (default)
    0xFFB3261E.toInt(), // red
    0xFFBF360C.toInt(), // deep orange
    0xFFE65100.toInt(), // orange
    0xFF9A6200.toInt(), // amber
    0xFF827717.toInt(), // olive
    0xFF558B2F.toInt(), // lime
    0xFF2E7D32.toInt(), // green
    0xFF006A6A.toInt(), // teal
    0xFF00838F.toInt(), // cyan
    0xFF0277BD.toInt(), // light blue
    0xFF0B57D0.toInt(), // blue
    0xFF3949AB.toInt(), // indigo
    0xFF5E35B1.toInt(), // deep purple
    0xFFAD1457.toInt(), // pink
    0xFF5F5E62.toInt(), // neutral grey
)

/** Six keeps the block to three rows on a phone. */
private const val SEED_COLUMNS = 6

@Preview(name = "About — light", showBackground = true)
@Preview(
    name = "About — dark intent",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun AboutScreenPreview() {
    HyperFCMLiveTheme {
        Surface {
            AboutScreen(
                onBack = {},
                actions = AboutActions(
                    onViewSource = {},
                    onLicenses = {},
                    onPrivacy = {},
                    onExperiment = {},
                    onExport = {},
                    onImport = {},
                    onHelp = {},
                    onCheckUpdate = {},
                    onUpdateOpen = {},
                    versionLine = "当前版本 3.7.0 (45)",
                    onAppearanceChange = {}
                )
            )
        }
    }
}
