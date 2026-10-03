package io.github.howard20181.hyperos.fcmlive.ui

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes

/**
 * Compose half of the settings screen: the top bar and the app list.
 *
 * Nothing here decides anything. Every value arrives as state and every tap
 * leaves as a callback, so the Activity stays the only owner of what the
 * allowlist means — search debouncing, multi-select staging, the package scan
 * and the Xposed write all live there, untouched by this file.
 *
 * There is no View left in the tree: the inline search field is a Compose
 * [BasicTextField] whose focus and keyboard ride the composition itself —
 * entering search composes the field, which is what raises the IME, and
 * leaving it disposes the node, which is what lowers the IME. The Activity
 * only sees text.
 */

/** Top bar flags; owned by MainActivity and pushed wholesale on every change. */
data class MainTopBarState(
    val title: String,
    val searching: Boolean = false,
    val multiSelect: Boolean = false,
    val allVisibleSelected: Boolean = false,
    val overflow: OverflowState = OverflowState()
)

/** The four overflow toggles. Pure state — the Activity owns the consequences. */
data class OverflowState(
    val showSystemApps: Boolean = false,
    val showFcmSupportedOnly: Boolean = false,
    val excludeMiPushApps: Boolean = false,
    val strictMode: Boolean = false
)

/** Every action the top bar can ask for. */
data class MainActions(
    val onBack: () -> Unit,
    val onSearch: () -> Unit,
    val onBatchAdd: () -> Unit,
    val onBatchRemove: () -> Unit,
    val onSelectAll: () -> Unit,
    val onAbout: () -> Unit,
    val onToggleShowSystemApps: () -> Unit,
    val onToggleShowFcmOnly: () -> Unit,
    val onToggleExcludeMiPush: () -> Unit,
    val onToggleStrictMode: () -> Unit
)

/**
 * The whole page: bar, list, pull-to-refresh and the diagnostics button.
 *
 * Hosting it as one composition is what lets the refresh be the M3 Expressive
 * [LoadingIndicator] instead of a stand-in drawn over a hidden View spinner.
 * A `SwipeRefreshLayout` owns its spinner as a private child and cannot be
 * told to draw another one, so the old shell kept the stock spinner around for
 * its geometry and mirrored position, scale and visibility onto a second view
 * every frame. `PullToRefreshBox` is a Compose gesture that reports its own
 * progress, so none of that survives here — and neither does the View that had
 * to ask a `LazyListState` whether the list was already scrolled, because the
 * nested-scroll handshake answers that by itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    topBarState: MainTopBarState,
    actions: MainActions,
    query: String,
    onQueryChange: (String) -> Unit,
    apps: List<AppListStore.AppEntry>,
    multiSelect: Boolean,
    selected: Set<String>,
    onRowClick: (AppListStore.AppEntry) -> Unit,
    onRowLongClick: (AppListStore.AppEntry) -> Unit,
    loadIcon: (AppListStore.AppEntry) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    // Remembered here, inside the composition, so the position is part of the
    // saveable state the host window restores. The Activity used to build the
    // state itself and hand it in, which is the one way to get a `LazyListState`
    // nobody ever saves — the list came back at the top after a rotation, the
    // opposite of what building it there was meant to achieve.
    lazyListState: LazyListState = rememberLazyListState(),
    // The M3 feedback surface. Owned by the caller, because that is where the
    // messages originate; hosted here, because this is the tree on screen.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val pullState = rememberPullToRefreshState()
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // `background`, not `surface`: it is the mapped `pageBg`, and it is
        // also what the Activity paints the window with, so the strip under the
        // status bar and the gesture hint line cannot end up a different tone.
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MainTopBar(
                state = topBarState,
                actions = actions,
                query = query,
                onQueryChange = onQueryChange
            )
        },
        // No margin of our own. Scaffold already places the FAB one
        // `FabSpacing` (16dp) off the end edge and `FabSpacing + navBar inset`
        // off the bottom — it reserves the same room in `innerPadding`, so the
        // old hand-rolled 20dp/26dp/inset stack pushed the button ~36dp off the
        // right edge and ~62dp off the bottom instead of the XML's 20/34.
        floatingActionButton = {
            DiagnosticsFab(onClick = onDiagnostics)
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier
                // Only the top edge is a hard stop — the bar above owns it.
                // The bottom edge scrolls: the navigation-bar / FAB room goes
                // into the list's `contentPadding` instead of this padding, so
                // rows pass under the gesture hint line while scrolling and
                // nothing paints a dead background band at the screen edge.
                .padding(top = innerPadding.calculateTopPadding())
                .fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            },
            content = {
                AppListPane(
                    apps = apps,
                    multiSelect = multiSelect,
                    selected = selected,
                    lazyListState = lazyListState,
                    onRowClick = onRowClick,
                    onRowLongClick = onRowLongClick,
                    loadIcon = loadIcon,
                    bottomPadding = innerPadding.calculateBottomPadding() + FAB_CLEARANCE
                )
            }
        )
    }
}

/**
 * Room the last card keeps between itself and the navigation-bar inset, so the
 * FAB never rests on it after a full scroll. Scaffold's `innerPadding` covers
 * the navigation-bar inset only — the FAB is not part of it — so the clearance
 * is stated here: FAB height (56dp) + the FAB spacing Scaffold applies (16dp)
 * + one gap of the same 16dp. Scroll-through immersion is unaffected: this
 * lives in the list's `contentPadding`, so cards still glide under the FAB and
 * the gesture line while scrolling.
 */
private val FAB_CLEARANCE = 88.dp

/**
 * FCM diagnostics, with the same long-press tooltip the other icons carry.
 *
 * The bubble used to be a `PopupWindow` measured and clamped against a freeform
 * window by hand; `TooltipBox` does the placement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsFab(onClick: () -> Unit) {
    val label = stringResource(R.string.fcm_diagnostics)
    TooltipBox(
        // Above, unlike the top-bar icons: this one sits at the bottom of the
        // screen, so below would run it into the gesture strip.
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above
        ),
        tooltip = {
            PlainTooltip { Text(label, style = MaterialTheme.typography.bodySmall) }
        },
        state = rememberTooltipState()
    ) {
        FloatingActionButton(
            onClick = onClick,
            shape = LocalAppShapes.current.fab,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fcm_diagnostics),
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    state: MainTopBarState,
    actions: MainActions,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 64dp of bar under the status bar — the same height [AppTopBar]
            // gives every secondary page, and the M3 top-app-bar height. The
            // old 100dp (12dp above and below a 52dp row) read as dead air on
            // both edges; 6dp does what the status bar itself does not.
            .heightIn(min = 64.dp)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 10.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.searching || state.multiSelect) {
            TopBarIconButton(
                painter = painterResource(R.drawable.ic_arrow_back),
                description = stringResource(
                    if (state.multiSelect) R.string.exit_multi_select else R.string.exit_search
                ),
                onClick = actions.onBack
            )
        } else {
            Spacer(modifier = Modifier.width(6.dp))
        }

        // Title and search share this slot: only one of them composes at a
        // time, and the query text lives in the Activity's state, so nothing
        // is lost between the two.
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (!state.searching) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            SearchField(
                visible = state.searching,
                query = query,
                onQueryChange = onQueryChange
            )
        }

        if (state.multiSelect) {
            TopBarIconButton(
                painter = painterResource(R.drawable.ic_batch_add),
                description = stringResource(R.string.batch_add_allowlist),
                onClick = actions.onBatchAdd
            )
            TopBarIconButton(
                painter = painterResource(R.drawable.ic_batch_remove),
                description = stringResource(R.string.batch_remove_allowlist),
                onClick = actions.onBatchRemove
            )
            TopBarIconButton(
                painter = painterResource(
                    if (state.allVisibleSelected) R.drawable.ic_deselect_all else R.drawable.ic_select_all
                ),
                description = stringResource(
                    if (state.allVisibleSelected) R.string.deselect_all else R.string.select_all
                ),
                onClick = actions.onSelectAll
            )
        } else {
            if (!state.searching) {
                TopBarIconButton(
                    painter = painterResource(R.drawable.ic_search),
                    description = stringResource(R.string.tooltip_search),
                    onClick = actions.onSearch
                )
            }
            OverflowMenu(state.overflow, actions)
        }
    }
}

/**
 * Long-press tooltip on a 48dp target, which is what these icons were.
 *
 * The tooltip replaces the hand-placed PopupWindow the Activity used to build
 * for every icon. Same reason to exist (HyperOS puts its own bubble *on* the
 * control) and same gesture, but positioned by the toolkit instead of by
 * measured arithmetic that had to be re-clamped against every freeform window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBarIconButton(
    painter: Painter,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TooltipBox(
        // Below, like the old PopupWindow: `showAtLocation` put the bubble
        // under the anchor with an 8dp gap. Above wedged it between the icon
        // and the status bar, which is where it looked pinned to the bar
        // instead of attached to the button.
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Below
        ),
        tooltip = {
            PlainTooltip {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        IconButton(onClick = onClick) {
            Icon(
                painter = painter,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Inline app search, Compose end to end.
 *
 * The platform SearchView it replaces was kept for its IME plumbing; Compose
 * turns out to own the same plumbing through the composition itself: entering
 * search composes this field, and [LaunchedEffect] rides the first frame to
 * grab focus and raise the keyboard — the "expand and focus" the iconified
 * SearchView did on demand. Leaving search disposes the node, which drops
 * focus and lowers the keyboard without a single explicit call.
 *
 * The Activity sees only text: `onQueryChange` is its debounce entry point,
 * and the query itself lives in the Activity's state, so a restore after a
 * rebuild re-composes the field already holding the text.
 */
@Composable
private fun SearchField(
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit
) {
    if (!visible) {
        return
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .focusRequester(focusRequester),
        singleLine = true,
        // Both colours come from the ColorScheme, not from a colour resource.
        // A `@color/` read here resolves through the *XML* theme attributes,
        // which are a different source from the one this tree is dressed in:
        // the field would keep the theme's own neutral while everything around
        // it followed the picked seed and palette style.
        textStyle = LocalTextStyle.current.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { inner ->
            // Fixed 52dp with the text vertically centred — the same slot the
            // platform field filled, and the same no-plate look: no container,
            // no indicator, palette-tinted text, hint and caret.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                inner()
            }
        }
    )
}

/**
 * Overflow menu, replacing `popup_overflow.xml` and the ~140 lines that used to
 * measure it: the 16dp panel, 12dp rows and aligned check boxes, all inside a
 * window it had to be clamped into by hand.
 *
 * Two things the stock [DropdownMenuItem] got wrong here, which is why the rows
 * are [MenuItemRow] instead:
 *
 * - its ripple is a rectangle. [MenuItemRow] clips before `clickable`, so the
 *   press stays inside the row's rounded plate;
 * - its container colour and corner are the library's, not the page's. Passing
 *   [LocalAppShapes] `menu`/`menuItem` puts the panel back on the same shape
 *   language as the settings menus.
 *
 * The width is intrinsic so the four check boxes land on one vertical line the
 * way `popup_overflow.xml` aligned them, but the popup is still measured from
 * its widest row — never from the window, which is what made the settings menus
 * open at the card's left edge.
 *
 * There is deliberately no divider above "设置": the XML had none, and the four
 * toggles plus a navigation row are one list, not two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverflowMenu(state: OverflowState, actions: MainActions) {
    val shapes = LocalAppShapes.current
    var expanded by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.more_menu)
    Box {
        // Same long-press bubble as every other top-bar icon — the overflow
        // button lost it when the bar moved to Compose and never got it back.
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Below
            ),
            tooltip = {
                PlainTooltip { Text(moreLabel, style = MaterialTheme.typography.bodySmall) }
            },
            state = rememberTooltipState()
        ) {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = moreLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .defaultMinSize(minWidth = MENU_OVERFLOW_MIN_WIDTH),
            shape = shapes.menu,
            offset = DpOffset(x = -MENU_EDGE_INSET, y = 0.dp)
        ) {
            MenuItemRow(
                label = stringResource(R.string.show_system_apps),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                arrangement = Arrangement.SpaceBetween,
                checkable = state.showSystemApps,
                trailing = { OverflowCheckbox(state.showSystemApps) },
                onClick = { expanded = false; actions.onToggleShowSystemApps() }
            )
            Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
            MenuItemRow(
                label = stringResource(R.string.show_fcm_supported_apps),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                arrangement = Arrangement.SpaceBetween,
                checkable = state.showFcmSupportedOnly,
                trailing = { OverflowCheckbox(state.showFcmSupportedOnly) },
                onClick = { expanded = false; actions.onToggleShowFcmOnly() }
            )
            Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
            MenuItemRow(
                label = stringResource(R.string.exclude_mipush_apps),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                arrangement = Arrangement.SpaceBetween,
                checkable = state.excludeMiPushApps,
                trailing = { OverflowCheckbox(state.excludeMiPushApps) },
                onClick = { expanded = false; actions.onToggleExcludeMiPush() }
            )
            Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
            MenuItemRow(
                label = stringResource(R.string.strict_mode),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                arrangement = Arrangement.SpaceBetween,
                checkable = state.strictMode,
                trailing = { OverflowCheckbox(state.strictMode) },
                onClick = { expanded = false; actions.onToggleStrictMode() }
            )
            Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
            MenuItemRow(
                label = stringResource(R.string.settings),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                onClick = { expanded = false; actions.onAbout() }
            )
        }
    }
}

/**
 * The overflow row's box: the shape of a checkbox, none of its semantics.
 *
 * The row itself is the toggle ([MenuItemRow] `checkable`), so it already
 * carries `Role.Checkbox` and the checked state. Left alone, this box would be
 * a second, unreachable stop in the a11y tree — it has no callback behind it,
 * so it would announce itself as a disabled checkbox with no label. Clearing
 * its semantics keeps the plate the eye expects and the tree honest.
 */
@Composable
private fun OverflowCheckbox(checked: Boolean) {
    Checkbox(
        checked = checked,
        onCheckedChange = null,
        modifier = Modifier.clearAndSetSemantics {}
    )
}

/**
 * The app list: a `LazyColumn` keyed by package name.
 *
 * The key is also why the scroll position survives a refresh without the manual
 * `setSelectionFromTop` the ListView needed — keys let Compose follow rows whose
 * order moved because a check promoted them.
 */
@Composable
fun AppListPane(
    apps: List<AppListStore.AppEntry>,
    multiSelect: Boolean,
    selected: Set<String>,
    lazyListState: LazyListState,
    onRowClick: (AppListStore.AppEntry) -> Unit,
    onRowLongClick: (AppListStore.AppEntry) -> Unit,
    loadIcon: (AppListStore.AppEntry) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = lazyListState,
        // The bottom room comes from the caller's `innerPadding` (navigation
        // bar inset + FAB reservation). As *content* padding it lives inside
        // the scroll: cards glide under the gesture hint line on the way past
        // and only come to rest that far above it.
        contentPadding = PaddingValues(
            start = 12.dp, top = 4.dp, end = 12.dp, bottom = bottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = apps, key = { it.packageName }) { app ->
            AppRow(
                label = app.label,
                packageName = app.packageName,
                icon = app.icon,
                checked = app.checked,
                supportMiPush = app.supportMiPush,
                multiSelect = multiSelect,
                rowSelected = selected.contains(app.packageName),
                onClick = { onRowClick(app) },
                onLongClick = { onRowLongClick(app) },
                onIconMissing = { loadIcon(app) }
            )
        }
    }
}

/**
 * One app: card, icon, name with an optional MiPush tag, package name, and the
 * allowlist state.
 *
 * The old row needed a measure pass to stop a long name from pushing its tag off
 * the row; `weight(1f, fill = false)` says that directly, which is most of why
 * this is shorter than the adapter it replaces.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.AppRow(
    label: String,
    packageName: String,
    icon: Drawable?,
    checked: Boolean,
    supportMiPush: Boolean,
    multiSelect: Boolean,
    rowSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onIconMissing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardSelected = multiSelect && rowSelected
    val shape = LocalAppShapes.current.card
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val allowlistState = stringResource(
        if (checked) R.string.a11y_allowlist_on else R.string.a11y_allowlist_off
    )
    val mipushTag = stringResource(R.string.mipush_badge)
    val selectionState = stringResource(
        if (rowSelected) R.string.row_selected else R.string.row_unselected
    )
    val description = remember(label, packageName, allowlistState, supportMiPush, multiSelect, selectionState) {
        buildList {
            add(label)
            add(packageName)
            add(allowlistState)
            if (supportMiPush) add(mipushTag)
            if (multiSelect) add(selectionState)
        }.joinToString("，")
    }

    if (icon == null) {
        // Same trigger the adapter used: ask once per row, then let the
        // coalesced refresh in AppListStore recompose when the icon lands.
        LaunchedEffect(packageName) { onIconMissing() }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .semantics {
                contentDescription = description
                if (multiSelect) {
                    selected = rowSelected
                }
            }
            // See [MenuItemRow]: the ripple `combinedClickable` installs is
            // painted inside this node's rectangular bounds, so without the
            // clip in front it spills over the card's rounded corners.
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onLongClick = onLongClick,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
            ),
        shape = shape,
        color = if (cardSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        }
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = icon?.let { rememberDrawablePainter(it) }
                    ?: painterResource(R.drawable.ic_app_placeholder),
                contentDescription = null,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (supportMiPush) {
                        MiPushBadge(text = mipushTag)
                    }
                }
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(
                    if (checked) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
                ),
                contentDescription = null,
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** The MiPush chip: primary-container fill at the small corner radius. */
@Composable
private fun MiPushBadge(text: String, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier
            .padding(start = 6.dp)
            .drawBehind {
                drawRoundRect(color = fill, size = this.size, cornerRadius = CornerRadius(radius))
            }
            .padding(start = 6.dp, top = 2.dp, end = 6.dp, bottom = 2.dp)
    )
}

/**
 * Draw a [Drawable] as a Compose [Painter].
 *
 * Compose ships no painter for an arbitrary Drawable, and these icons come from
 * `PackageManager`, not from resources. Drawing onto the native canvas is what
 * an ImageView does with the same Drawable — including adaptive icons keeping
 * their safe zone, which a rasterise-to-bitmap detour would break.
 */
@Composable
private fun rememberDrawablePainter(drawable: Drawable): Painter =
    remember(drawable) { DrawablePainter(drawable) }

private class DrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize: Size
        get() = Size(
            drawable.intrinsicWidth.coerceAtLeast(0).toFloat(),
            drawable.intrinsicHeight.coerceAtLeast(0).toFloat()
        )

    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

/**
 * The preview the other four screens already had. This screen is the largest
 * and the only one that had none, which is the one place a rendering change
 * could not be seen before it shipped.
 *
 * The rows are hand-made rather than scanned: a preview has no package manager
 * behind it, and the four states worth looking at are an allowlisted app, a
 * plain FCM app, an app carrying the MiPush tag, and one with neither. Icons
 * fall back to the placeholder, which is also what a row shows while its real
 * icon is still loading.
 */
@Preview(name = "Main — light", showBackground = true)
@Preview(
    name = "Main — dark intent",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun MainScreenPreview() {
    HyperFCMLiveTheme {
        MainScreen(
            topBarState = MainTopBarState(title = "FCM 唤醒白名单"),
            actions = MainActions(
                onBack = {},
                onSearch = {},
                onBatchAdd = {},
                onBatchRemove = {},
                onSelectAll = {},
                onAbout = {},
                onToggleShowSystemApps = {},
                onToggleShowFcmOnly = {},
                onToggleExcludeMiPush = {},
                onToggleStrictMode = {}
            ),
            query = "",
            onQueryChange = {},
            apps = listOf(
                AppListStore.AppEntry("com.tencent.mm", "微信").apply {
                    checked = true
                    supportFcm = true
                },
                AppListStore.AppEntry("org.telegram.messenger", "Telegram").apply {
                    supportFcm = true
                },
                AppListStore.AppEntry("com.example.pushapp", "推送示例").apply {
                    supportFcm = true
                    supportMiPush = true
                },
                AppListStore.AppEntry("com.example.reader", "阅读器")
            ),
            multiSelect = false,
            selected = emptySet(),
            onRowClick = {},
            onRowLongClick = {},
            loadIcon = {},
            refreshing = false,
            onRefresh = {},
            onDiagnostics = {}
        )
    }
}
