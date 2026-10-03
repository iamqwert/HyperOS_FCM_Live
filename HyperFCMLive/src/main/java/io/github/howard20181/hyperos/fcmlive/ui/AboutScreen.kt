package io.github.howard20181.hyperos.fcmlive.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.UpdateChecker
import io.github.howard20181.hyperos.fcmlive.mcu.Hct
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
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
 * import/export needs an `ActivityResultRegistry`, and rebuilding the theme is
 * the Activity's `recreate`.
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
    /** Invalidates the palette and rebuilds, as a theme change must. */
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
    Scaffold(
        modifier = modifier,
        // The page colour has to be `background`, not `surface`: `background`
        // is the mapped `pageBg`, which is also what the Activity paints the
        // window with. `surface` is one step off it, and under the status bar
        // and the gesture strip — the two places where the window background is
        // all there is — that step shows as a band.
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(titleRes = R.string.settings, onBack = onBack) {
                val helpText = stringResource(R.string.help)
                TooltipBox(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    // Below, as the old PopupWindow was: it sat under the icon
                    // rather than squeezing itself between the icon and the
                    // status bar.
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                        TooltipAnchorPosition.Below
                    ),
                    tooltip = { PlainTooltip { Text(text = helpText) } },
                    state = rememberTooltipState(),
                    content = {
                        IconButton(onClick = actions.onHelp) {
                            Icon(
                                painter = painterResource(R.drawable.ic_help),
                                contentDescription = helpText
                            )
                        }
                    }
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
    // Read once per screen. Every writer below re-renders locally *and* asks
    // the Activity to rebuild, so the mirror cannot go stale on screen.
    var hideIcon by remember { mutableStateOf(LauncherIcon.isHidden(context)) }
    var dynamic by remember { mutableStateOf(ThemePrefs.dynamicColor(context)) }
    var seed by remember { mutableIntStateOf(ThemePrefs.seedColor(context)) }
    var updateBadge by remember { mutableStateOf(UpdateChecker.isUpdateAvailable(context)) }
    var themeMode by remember { mutableIntStateOf(ThemePrefs.themeMode(context)) }
    var paletteStyle by remember { mutableIntStateOf(ThemePrefs.paletteStyle(context).ordinal) }
    var colorSpec by remember { mutableIntStateOf(ThemePrefs.specVersion(context)) }
    // The offer, while it is on screen. Keeping it here rather than in the
    // Activity is what makes the dialog a Compose dialog: it is screen state,
    // and the Activity only supplies the URL it would open.
    var updateOffer by remember { mutableStateOf<UpdateOutcome.Available?>(null) }

    val modeEntries = stringArrayResource(R.array.theme_mode_entries)
    val styleEntries = stringArrayResource(R.array.palette_style_entries)
    val specEntries = stringArrayResource(R.array.color_spec_entries)

    val checkingText = stringResource(R.string.update_checking)
    val noneText = stringResource(R.string.update_none)
    val errorText = stringResource(R.string.update_error)
    val updateOpenLabel = stringResource(R.string.update_open)
    val cancelLabel = stringResource(android.R.string.cancel)
    val hideText = stringResource(R.string.hide_icon_toast)
    val showText = stringResource(R.string.show_icon_toast)

    fun show(text: String) {
        scope.launch {
            // One line at a time: the message replacing the current one should
            // not have to wait out its predecessor's four seconds first.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    updateOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = { updateOffer = null },
            text = { Text(stringResource(R.string.update_found, offer.version)) },
            confirmButton = {
                TextButton(onClick = {
                    updateOffer = null
                    // The badge is cleared where the URL is opened, so the
                    // stored flag and what is on screen can only move together.
                    updateBadge = false
                    actions.onUpdateOpen()
                }) {
                    Text(updateOpenLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { updateOffer = null }) { Text(cancelLabel) }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp),
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
            PopupCard(
                iconRes = R.drawable.ic_theme_mode,
                title = stringResource(R.string.theme_mode),
                subtitle = stringResource(R.string.about_sub_theme_mode),
                entries = modeEntries.toList(),
                currentIndex = themeMode,
                first = true,
                last = false,
                onPick = { index ->
                    ThemePrefs.setThemeMode(context, index)
                    themeMode = index
                    actions.onAppearanceChange()
                }
            )
            Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
            DynamicColorCard(
                dynamic = dynamic,
                seed = seed,
                first = false,
                last = false,
                onDynamicChange = { checked ->
                    ThemePrefs.setDynamicColor(context, checked)
                    dynamic = checked
                    actions.onAppearanceChange()
                },
                onSeedChange = { color ->
                    ThemePrefs.setSeedColor(context, color)
                    seed = color
                    actions.onAppearanceChange()
                }
            )
            Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
            PopupCard(
                iconRes = R.drawable.ic_palette_style,
                title = stringResource(R.string.palette_style),
                subtitle = stringResource(R.string.about_sub_palette_style),
                entries = styleEntries.toList(),
                currentIndex = paletteStyle,
                first = false,
                last = false,
                onPick = { index ->
                    ThemePrefs.setPaletteStyle(context, variantAt(index))
                    paletteStyle = index
                    actions.onAppearanceChange()
                }
            )
            Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
            PopupCard(
                iconRes = R.drawable.ic_color_spec,
                title = stringResource(R.string.color_spec),
                subtitle = stringResource(R.string.about_sub_color_spec),
                entries = specEntries.toList(),
                currentIndex = colorSpec,
                first = false,
                last = true,
                onPick = { index ->
                    ThemePrefs.setSpecVersion(context, index)
                    colorSpec = index
                    actions.onAppearanceChange()
                }
            )
        }

        item {
            SectionTitle(R.string.about_section_backup)
            NavCard(
                iconRes = R.drawable.ic_export,
                title = stringResource(R.string.export_allowlist),
                subtitle = stringResource(R.string.about_sub_export),
                onClick = actions.onExport,
                first = true,
                last = false
            )
            Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
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
            SectionTitle(R.string.about_section_experiment)
            NavCard(
                iconRes = R.drawable.ic_science,
                title = stringResource(R.string.experiment),
                subtitle = stringResource(R.string.about_sub_experiment),
                onClick = actions.onExperiment,
                first = true,
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
            Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
            NavCard(
                iconRes = R.drawable.ic_privacy,
                title = stringResource(R.string.about_section_privacy),
                subtitle = stringResource(R.string.about_sub_privacy),
                onClick = actions.onPrivacy,
                first = false,
                last = false
            )
            Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
            NavCard(
                iconRes = R.drawable.ic_view_source,
                title = stringResource(R.string.view_source_code),
                subtitle = stringResource(R.string.about_sub_source),
                onClick = actions.onViewSource,
                first = false,
                last = false
            )
            Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
            NavCard(
                iconRes = R.drawable.ic_open_licenses,
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
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
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
                    versionLine = "当前版本 3.5.4 (38)",
                    onAppearanceChange = {}
                )
            )
        }
    }
}
