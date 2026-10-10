package io.github.howard20181.hyperos.fcmlive.ui

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import top.yukonga.miuix.kmp.basic.PullToRefresh as MiuixPullToRefresh
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Sort
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.InputField as MiuixInputField
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.FloatingActionButton as MiuixFloatingActionButton
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces
import io.github.howard20181.hyperos.fcmlive.theme.LocalMiuixLightPrimary

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

/** The overflow toggles. Pure state — the Activity owns the consequences. */
data class OverflowState(
    val showSystemApps: Boolean = false,
    val showFcmSupportedOnly: Boolean = false,
    val excludeMiPushApps: Boolean = false
)

/** Every action the top bar can ask for. */
data class MainActions(
    val onBack: () -> Unit,
    val onSearch: () -> Unit,
    val onStartMultiSelect: () -> Unit,
    val onBatchAdd: () -> Unit,
    val onBatchRemove: () -> Unit,
    val onSelectAll: () -> Unit,
    val onAbout: () -> Unit,
    val onToggleShowSystemApps: () -> Unit,
    val onToggleShowFcmOnly: () -> Unit,
    val onToggleExcludeMiPush: () -> Unit
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
    icons: Map<String, Drawable?> = emptyMap(),
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    // Remembered *saveably*, inside the composition, under a name: the position
    // has to survive both a rotation and the composition being torn down and
    // rebuilt. The Activity used to build the state itself and hand it in, which
    // is the one way to get a `LazyListState` nobody ever saves — the list came
    // back at the top after a rotation, the opposite of what building it there was
    // meant to achieve. So it stays internal, but through
    // [rememberPageListState]: the saveable registry lives on the view, not the
    // composition, so the position is restored even when the composition is
    // disposed wholesale — which is what
    // [io.github.howard20181.hyperos.fcmlive.ui.WindowSnapshot] used to do to this
    // window to refresh the predictive-back preview picture, and what it still
    // does to any page that is stopped at the time.
    lazyListState: LazyListState = rememberPageListState(),
    // The M3 feedback surface. Owned by the caller, because that is where the
    // messages originate; hosted here, because this is the tree on screen.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val pullState = rememberPullToRefreshState()

    // A finished refresh puts the list back at the top.
    //
    // The scan re-sorts: checked apps first, everything else alphabetically. A
    // row the user just unchecked therefore leaves the top group and lands
    // wherever its name puts it, which can be hundreds of rows down. A
    // `LazyColumn` does not hold the scroll *index* across that — on every
    // measure it rewrites the position to wherever the remembered first visible
    // row's key moved to (LazyList, `updateScrollPositionIfTheFirstItemWasMoved`),
    // so the page travelled down with the row that was unchecked. Uncheck a row
    // near the top, pull to refresh, and the view ended up at the bottom of the
    // list.
    //
    // The top is where a refresh belongs: the pull reaches this box only as
    // unconsumed nested scroll, which a list that is not already at the very top
    // does not produce. Asking for item 0 is also what makes the move hold —
    // `scrollToItem` forgets the stored key (`requestPositionAndForgetLastKnownKey`),
    // so the next measure has nothing left to pull the position down with.
    var wasRefreshing by remember { mutableStateOf(refreshing) }
    LaunchedEffect(refreshing) {
        if (wasRefreshing && !refreshing) {
            lazyListState.scrollToItem(0)
        }
        wasRefreshing = refreshing
    }

    if (LocalUiStyle.current == UiStyle.Miuix) {
        // The Miuix frame: the Scaffold the overlay popups open into (the
        // overflow menu below), a large title that holds still with the search
        // field pinned beneath it — the list scrolls under both, and neither
        // the title nor the search leaves the page as the list travels — and
        // the system's own pull-to-refresh, the circle with the refresh text,
        // not the M3 spinner.
        val scrollBehavior = MiuixScrollBehavior()
        MiuixScaffold(
            modifier = modifier,
            // The page colour, not Miuix's own default: under AMOLED the page is
            // true black and a default-coloured scaffold would paint a grey
            // window behind it.
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                MainTopBar(
                    state = topBarState,
                    actions = actions,
                    query = query,
                    onQueryChange = onQueryChange,
                    scrollBehavior = scrollBehavior
                )
            },
            floatingActionButton = {
                DiagnosticsFab(onClick = onDiagnostics)
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                MiuixPullToRefresh(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    // The bar never collapses on this page, so the circle's
                    // anchor is a constant — but the library still reads it
                    // from the behavior, so the behavior rides along.
                    topAppBarScrollBehavior = scrollBehavior,
                    // The library's defaults are hard-coded English; the text is
                    // part of the indicator, so it localises here.
                    refreshTexts = listOf(
                        stringResource(R.string.refresh_pull_down),
                        stringResource(R.string.refresh_release),
                        stringResource(R.string.refreshing),
                        stringResource(R.string.refresh_done)
                    ),
                    // The bar's height is taken off the refresh container itself,
                    // not the list inside it: the circle has to come down *below*
                    // the bar or it spins where the bar hides it.
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                ) {
                    AppListPane(
                        apps = apps,
                        multiSelect = multiSelect,
                        selected = selected,
                        lazyListState = lazyListState,
                        onRowClick = onRowClick,
                        onRowLongClick = onRowLongClick,
                        loadIcon = loadIcon,
                        icons = icons,
                        bottomPadding = innerPadding.calculateBottomPadding() + FAB_CLEARANCE,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        return
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // `background`, not `surface`: it is the mapped `pageBg`, and it is
        // also what the Activity paints the window with, so the strip under
        // the status bar and the gesture hint line cannot end up a different
        // tone.
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
                    icons = icons,
                    bottomPadding = innerPadding.calculateBottomPadding() + FAB_CLEARANCE
                )
            }
        )
    }
}

/**
 * Where the overflow panel lands: the anchor's end edge, clamped inside the
 * window.
 *
 * The library's default provider is start-aligned and unclamped; anchored to the
 * overflow button at the screen's right corner it measured the panel rightwards
 * and its right edge ran into the window edge. This is the provider KernelSU's
 * manager uses for the same menu, kept verbatim.
 */
private val OverflowMenuPositionProvider = object : top.yukonga.miuix.kmp.basic.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: top.yukonga.miuix.kmp.basic.PopupPositionProvider.Align
    ): IntOffset {
        val offsetX = anchorBounds.right - popupContentSize.width - popupMargin.right
        val offsetY = anchorBounds.bottom + popupMargin.top
        return IntOffset(
            x = offsetX.coerceIn(
                windowBounds.left,
                (windowBounds.right - popupContentSize.width - popupMargin.right)
                    .coerceAtLeast(windowBounds.left)
            ),
            y = offsetY.coerceIn(
                (windowBounds.top + popupMargin.top)
                    .coerceAtMost(windowBounds.bottom - popupContentSize.height - popupMargin.bottom),
                windowBounds.bottom - popupContentSize.height - popupMargin.bottom
            )
        )
    }

    override fun getMargins(): PaddingValues = PaddingValues(start = 20.dp)
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
    // Above, unlike the top-bar icons: this one sits at the bottom of the
    // screen, so below would run it into the gesture strip. The bubble is
    // Material's own; Miuix renders the FAB bare (see [LongPressTooltip]).
    LongPressTooltip(
        label = label,
        position = TooltipAnchorPosition.Above
    ) {
        if (LocalUiStyle.current == UiStyle.Miuix) {
            // Miuix's own FAB with two pins the library defaults do not give.
            // The plate reads the LIGHT palette's primary through
            // [LocalMiuixLightPrimary] — the system does not mute a floating
            // action when the theme turns dark, and the active palette would.
            // And the wrench is white on it in both modes, the system's own
            // pairing: the inherited ambient ink went near-black in the light
            // theme and drowned the glyph against the blue.
            MiuixFloatingActionButton(
                onClick = onClick,
                containerColor = LocalMiuixLightPrimary.current,
                // No shadow: HyperOS's own floating buttons sit flat on the
                // page, and the library's 4dp default read as a grey halo
                // under the plate — flat is the platform look here.
                shadowElevation = 0.dp
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fcm_diagnostics),
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            return@LongPressTooltip
        }
        FloatingActionButton(
            onClick = onClick,
            shape = LocalAppShapes.current.fab,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fcm_diagnostics),
                contentDescription = label,
                // 28dp, not the 24dp an icon defaults to: `build` is a thin
                // glyph that draws well inside its box, so at 24dp the wrench
                // sat in a pool of empty plate and read small next to the
                // top-bar icons. Larger glyph, same 56dp container — the plate
                // is what the target size is measured on, not the ink.
                modifier = Modifier.size(28.dp)
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
    modifier: Modifier = Modifier,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior? = null
) {
    if (LocalUiStyle.current == UiStyle.Miuix) {
        // The system settings' own bar: the large title at the left while the
        // page rests, and the search field pinned directly beneath it. The
        // field does NOT ride in the list — a list-head field scrolls away
        // with the first fling, and then the page's one search entry is gone
        // exactly when the user has scrolled far enough to want it. Pinned
        // under the bar it stays put while the list scrolls under it, the way
        // the manager's own repo page wears its search.
        Column(modifier = modifier) {
            MiuixTopAppBar(
                title = state.title,
                largeTitle = state.title,
                // Same reason as the scaffold: the bar is the page's own colour,
                // which under AMOLED is black rather than Miuix's default.
                color = MaterialTheme.colorScheme.background,
                navigationIcon = {
                    if (state.multiSelect) {
                        // The selection bar's cancel control is Close, not the back
                        // arrow (the reference batch page's bar): batch mode is a
                        // mode of this page, not a level of navigation, and what
                        // the tap leaves is the mode, not the page.
                        TopBarIconButton(
                            painter = rememberVectorPainter(MiuixIcons.Close),
                            description = stringResource(R.string.exit_multi_select),
                            onClick = actions.onBack
                        )
                    } else {
                        // Settings moved here from the overflow row. Library glyph,
                        // library press feedback — the bar stays one icon language.
                        TopBarIconButton(
                            painter = rememberVectorPainter(MiuixIcons.Settings),
                            description = stringResource(R.string.settings),
                            onClick = actions.onAbout
                        )
                    }
                },
                actions = {
                    if (state.multiSelect) {
                        // Two controls, both from the library's own set: the
                        // select-all flip, and the overflow that carries the two
                        // staging actions. The three-icon row this replaces was
                        // crowded, and it spoke Material Symbols on a Miuix bar.
                        TopBarIconButton(
                            painter = rememberVectorPainter(MiuixIcons.SelectAll),
                            description = stringResource(
                                if (state.allVisibleSelected) R.string.deselect_all
                                else R.string.select_all
                            ),
                            onClick = actions.onSelectAll
                        )
                        SelectionMenu(actions)
                    } else {
                        // Batch mode's entry, one slot to the left of the overflow
                        // — the same two-control bar the reference app rests in.
                        TopBarIconButton(
                            painter = rememberVectorPainter(MiuixIcons.SelectAll),
                            description = stringResource(R.string.batch_ops),
                            onClick = actions.onStartMultiSelect
                        )
                        OverflowMenu(state.overflow, actions)
                    }
                },
                scrollBehavior = scrollBehavior
            )
            // The pinned field. The same list inset (12dp) the cards below
            // scroll with, so the capsule and the cards share one column edge;
            // the card's own bottom padding supplies the gap to the list.
            MiuixSearchCard(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        return
    }
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
 *
 * These stay bare `IconButton`s on purpose, and the home bar is excluded from
 * every "fill the leading control" change: only the back control of a secondary
 * page wears a plate (`ui/AppTopBar.kt`). Here the icons are a row of peers over
 * a list — plating them would turn four tool icons into four buttons competing
 * with the content, and the bar has no leading control to single out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBarIconButton(
    painter: Painter,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The bubble is Material's own; under Miuix [LongPressTooltip] renders the
    // button bare — HyperOS puts no long-press hint on its controls.
    LongPressTooltip(label = description) {
        if (LocalUiStyle.current == UiStyle.Miuix) {
            // Miuix's own button: its press feedback is the library's, but the
            // glyph keeps the bar's own ink — onSurface, the same color the
            // bar's title paints with. The library's onBackgroundVariant is a
            // caption tone (a washed blue-grey); tinting the actions with it
            // is what made this bar read paler than every system app's.
            MiuixIconButton(onClick = onClick, modifier = modifier) {
                Icon(
                    painter = painter,
                    contentDescription = description,
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            IconButton(onClick = onClick, modifier = modifier) {
                Icon(
                    painter = painter,
                    contentDescription = description,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
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
    // The Miuix overflow: an overlay popup — the page dimmed behind it, the
    // selected entry in the accent with a check — instead of the M3 dropdown
    // window, which can neither dim what is behind it nor mark the current value
    // without putting a plate behind it.
    if (LocalUiStyle.current == UiStyle.Miuix) {
        Box {
            // No long-press bubble under this look — see [LongPressTooltip].
            MiuixIconButton(onClick = { expanded = true }) {
                // The library's sort glyph — the arrow-and-rows mark the
                // reference apps carry in this slot. The ink is the bar's own
                // (onSurface): the library's onBackgroundVariant is a caption
                // tone, and HyperOS puts no washed grey on its bar icons.
                // The NORMAL weight, not the Regular default: measured against
                // the settings gear's ring (≈1.66dp of stroke at 24dp) the
                // Regular rows run 1.78dp and read heavier than every glyph
                // beside them; Normal's 1.62dp sits on the gear.
                Icon(
                    imageVector = MiuixIcons.Normal.Sort,
                    contentDescription = moreLabel,
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
            OverlayListPopup(
                show = expanded,
                onDismissRequest = { expanded = false },
                // End-aligned to the anchor and clamped inside the window: the
                // default start-aligned provider measures the panel from the
                // anchor's left edge, and at the screen's right corner that put
                // the panel's right edge against (and past) the window edge.
                // This is KernelSU's MenuPositionProvider, verbatim.
                popupPositionProvider = OverflowMenuPositionProvider,
                alignment = top.yukonga.miuix.kmp.basic.PopupPositionProvider.Align.TopEnd
            ) {
                // Selection is the accent plus a check, the menu's own language
                // (the reference apps' sort and mode menus) — a checkbox plate on
                // top of it would be the same bit drawn twice.
                // The library's own option rows, so the panel gets what it is
                // built with: the larger padding above the first row and below the
                // last (a menu whose ink touches the panel edge reads as a list
                // with no lid), the accent plus a check on the current value, and
                // the chevron-and-button role for the row that navigates rather
                // than toggles.
                val toggleLabels = listOf(
                    stringResource(R.string.show_fcm_supported_apps),
                    stringResource(R.string.exclude_mipush_apps),
                    stringResource(R.string.show_system_apps)
                )
                val toggled = listOf(
                    state.showFcmSupportedOnly,
                    state.excludeMiPushApps,
                    state.showSystemApps
                )
                val toggles: List<() -> Unit> = listOf(
                    actions.onToggleShowFcmOnly,
                    actions.onToggleExcludeMiPush,
                    actions.onToggleShowSystemApps
                )
                // Settings no longer lives here — it moved to the bar's leading
                // edge. The divider that separated the toggles from it went with
                // it: the three remaining rows answer the same yes/no kind, and
                // a rule under them read as a seam pointing at nothing.
                val rowCount = toggleLabels.size
                ListPopupColumn {
                    toggleLabels.forEachIndexed { index, label ->
                        DropdownImpl(
                            item = DropdownItem(text = label),
                            optionSize = rowCount,
                            isSelected = toggled[index],
                            index = index,
                            isFirst = index == 0,
                            isLast = index == toggleLabels.lastIndex,
                            onSelectedIndexChange = {
                                expanded = false
                                toggles[index]()
                            }
                        )
                    }
                }
            }
        }
        return
    }
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
            // Popup level, as every dropdown — see LocalAppSurfaces.
            containerColor = LocalAppSurfaces.current.popup,
            offset = DpOffset(x = -MENU_EDGE_INSET, y = 0.dp)
        ) {
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
                label = stringResource(R.string.settings),
                modifier = Modifier.fillMaxWidth(),
                minWidth = MENU_OVERFLOW_MIN_WIDTH,
                onClick = { expanded = false; actions.onAbout() }
            )
        }
    }
}

/**
 * Batch mode's action menu: the two staging actions behind the overflow glyph,
 * where the normal mode keeps its toggles.
 *
 * The bar follows the reference app's selection bar — close leading, then
 * select all and the overflow — instead of the three-icon row that crowded the
 * title. The entries are actions, not states, so none carries the check; an
 * empty staging set still opens the menu, and the tap answers with the
 * 「未选择应用」 line, the same answer the icon row used to give.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionMenu(actions: MainActions) {
    var expanded by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.more_menu)
    Box {
        // Miuix-only component: no long-press bubble under this look — see
        // [LongPressTooltip].
        MiuixIconButton(onClick = { expanded = true }) {
            // Same NORMAL weight as the overflow glyph beside it — see there.
            Icon(
                imageVector = MiuixIcons.Normal.Sort,
                contentDescription = moreLabel,
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
        OverlayListPopup(
            show = expanded,
            onDismissRequest = { expanded = false },
            // End-aligned to the anchor, clamped inside the window — the same
            // provider the normal overflow uses.
            popupPositionProvider = OverflowMenuPositionProvider,
            alignment = top.yukonga.miuix.kmp.basic.PopupPositionProvider.Align.TopEnd
        ) {
            val addLabel = stringResource(R.string.batch_add_allowlist)
            val removeLabel = stringResource(R.string.batch_remove_allowlist)
            ListPopupColumn {
                DropdownImpl(
                    item = DropdownItem(text = addLabel),
                    optionSize = 2,
                    isSelected = false,
                    index = 0,
                    isFirst = true,
                    isLast = false,
                    onSelectedIndexChange = {
                        expanded = false
                        actions.onBatchAdd()
                    }
                )
                DropdownImpl(
                    item = DropdownItem(text = removeLabel),
                    optionSize = 2,
                    isSelected = false,
                    index = 1,
                    isFirst = false,
                    isLast = true,
                    onSelectedIndexChange = {
                        expanded = false
                        actions.onBatchRemove()
                    }
                )
            }
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
    if (LocalUiStyle.current == UiStyle.Miuix) {
        MiuixCheckbox(
            state = androidx.compose.ui.state.ToggleableState(checked),
            onClick = null,
            modifier = Modifier.clearAndSetSemantics {}
        )
        return
    }
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
    icons: Map<String, Drawable?> = emptyMap(),
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
        // 12dp between cards under Miuix, the spacing the reference apps leave
        // between their section cards; 8dp is the Material ramp's own choice.
        verticalArrangement = Arrangement.spacedBy(
            if (LocalUiStyle.current == UiStyle.Miuix) 12.dp else 8.dp
        )
    ) {
        items(items = apps, key = { it.packageName }) { app ->
            AppRow(
                label = app.label,
                packageName = app.packageName,
                icon = icons[app.packageName],
                checked = app.checked,
                supportMiPush = app.supportMiPush,
                exempt = app.exempt,
                exemptNote = app.exemptNote,
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
 *
 * [exempt] rows — GMS and its persistent process, and the odd framework package
 * whose only FCM marker is a leftover action — are drawn as unavailable:
 * desaturated to `onSurfaceVariant` at reduced emphasis, with the checkbox
 * replaced by a note saying why, and no ripple or click handling at all. For
 * GMS the module always acts for it whatever the allowlist says, so a checkbox
 * there would be a control that does nothing; for the others there is no FCM
 * client to act for at all. Either way, a control that does nothing reads as a
 * bug.
 *
 * [exemptNote] carries the reason as a string resource rather than being fixed
 * here, because the two cases are not the same sentence.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.AppRow(
    label: String,
    packageName: String,
    icon: Drawable?,
    checked: Boolean,
    supportMiPush: Boolean,
    exempt: Boolean,
    exemptNote: Int,
    multiSelect: Boolean,
    rowSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onIconMissing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardSelected = multiSelect && rowSelected
    // Under Miuix the selection is the trailing checkbox — the batch language
    // of the reference app: the card keeps its own fill and a check marks the
    // row. The primaryContainer wash is the M3 selection language, so it only
    // paints in the Material look.
    val selectedWash = cardSelected && LocalUiStyle.current != UiStyle.Miuix
    // Miuix cards carry the one radius the library's own Card carries; the M3
    // ramp (and its 2025 shape set) belongs to the other look.
    val shape = if (LocalUiStyle.current == UiStyle.Miuix) {
        androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    } else {
        LocalAppShapes.current.card
    }
    // A selected row is `primaryContainer`, so all three foregrounds on it have
    // to come from that role's pair. `onSurface`, `onSurfaceVariant` and
    // `primary` each read fine on the card and each disappear here: MONOCHROME's
    // `primaryContainer` is tone 25 (#3B3B3B), `onSurface` is tone 10 and
    // `primary` tone 0. The on/off of the allowlist stays legible because the
    // glyph itself differs — the tint's job under selection is contrast.
    //
    // An exempt row is never cardSelected (it cannot be staged — see the tap
    // handlers), so its colours only have to work on the plain card.
    val labelColor = when {
        selectedWash -> MaterialTheme.colorScheme.onPrimaryContainer
        exempt -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = when {
        selectedWash -> MaterialTheme.colorScheme.onPrimaryContainer
        exempt -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusTint = when {
        selectedWash -> MaterialTheme.colorScheme.onPrimaryContainer
        exempt -> MaterialTheme.colorScheme.outline
        checked -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val allowlistState = stringResource(
        if (checked) R.string.a11y_allowlist_on else R.string.a11y_allowlist_off
    )
    val mipushTag = stringResource(R.string.mipush_badge)
    // The reason this row is inert. Taken from the entry rather than fixed
    // here: an exempt row's sentence has to match the reason, and there is
    // more than one reason.
    val exemptNoteText = if (exemptNote != 0) stringResource(exemptNote) else ""
    val selectionState = stringResource(
        if (rowSelected) R.string.row_selected else R.string.row_unselected
    )
    val description = remember(
        label, packageName, allowlistState, supportMiPush, multiSelect,
        selectionState, exempt, exemptNoteText
    ) {
        if (exempt) {
            // The checkbox state is not part of what an exempt row means, so it
            // is not announced; what replaces it is.
            listOf(label, packageName, exemptNoteText).joinToString("，")
        } else {
            buildList {
                add(label)
                add(packageName)
                add(allowlistState)
                if (supportMiPush) add(mipushTag)
                if (multiSelect) add(selectionState)
            }.joinToString("，")
        }
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
                if (multiSelect && !exempt) {
                    selected = rowSelected
                }
                if (exempt) {
                    // Tells accessibility services the row is present but not
                    // actionable, so a screen reader does not invite a tap that
                    // would do nothing.
                    disabled()
                }
            }
            // See [MenuItemRow]: the ripple `combinedClickable` installs is
            // painted inside this node's rectangular bounds, so without the
            // clip in front it spills over the card's rounded corners.
            .clip(shape)
            // An exempt row installs no clickable at all rather than a clickable
            // that ignores taps: no ripple, no haptic, and nothing for a
            // long-press to start a selection with.
            .then(
                if (exempt) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        // Under Miuix the row press is the library's own feedback,
                        // not an M3 ripple: LocalIndication carries Miuix's
                        // indication from the theme, so reading it here keeps one
                        // press language per look instead of a Material ripple on
                        // a Miuix screen.
                        indication = if (LocalUiStyle.current == UiStyle.Miuix) {
                            LocalIndication.current
                        } else {
                            ripple()
                        },
                        onLongClick = onLongClick,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onClick()
                        }
                    )
                }
            ),
        shape = shape,
        color = if (selectedWash) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            LocalAppSurfaces.current.card
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
                // Dimmed rather than hidden: the icon still identifies the app,
                // but at reduced opacity so the row reads as unavailable before
                // the label is read.
                alpha = if (exempt) 0.38f else 1f,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (supportMiPush) {
                        MiPushBadge(text = mipushTag)
                    }
                }
                Text(
                    // An exempt row says why it cannot be checked in place of
                    // its package name — the package name is the one thing the
                    // user already knows about it, and the reason is the thing
                    // they do not.
                    text = if (exempt) exemptNoteText else packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingColor
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (LocalUiStyle.current == UiStyle.Miuix) {
                if (multiSelect && !exempt) {
                    // The batch row's trailing control is a checkbox, not the
                    // switch (the reference app's batch rows): the selection is
                    // the check, and tapping it stages or unstages exactly like
                    // tapping the row. The row's contentDescription already
                    // carries the selected/unselected state, so the box itself
                    // is silent in the a11y tree.
                    MiuixCheckbox(
                        state = if (rowSelected) ToggleableState.On else ToggleableState.Off,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onClick()
                        },
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                } else {
                    // Miuix's capsule switch, mirroring the row's own tap. An exempt
                    // row is the library's own disabled case -- a switch locked in the
                    // on position, which is what "always in effect, nothing to check"
                    // looks like -- rather than a Material-Symbols padlock standing on
                    // a Miuix card. The row's description already carries the allowlist
                    // state, so the switch adds no second accessibility node.
                    MiuixSwitch(
                        checked = if (exempt) true else checked,
                        onCheckedChange = if (exempt) null else { _ -> onClick() },
                        enabled = !exempt,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            } else if (exempt) {
                // A lock rather than the on/off glyph: the checkbox states do
                // not apply, and drawing an "off" tick would suggest the user
                // could turn it on.
                Icon(
                    painter = painterResource(R.drawable.ic_status_locked),
                    contentDescription = null,
                    tint = statusTint,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Icon(
                    painter = painterResource(
                        if (checked) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
                    ),
                    contentDescription = null,
                    tint = statusTint,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * The MiPush chip: primary-container fill at the small corner radius, so the
 * label takes that fill's own partner (`onPrimaryContainer`). It used to be
 * `primary`, which is the *plate's* partner only when the plate is a handle —
 * on MONOCHROME that was #000000 on a #3B3B3B fill. In a selected row the chip's
 * fill and the row's fill are the same role, so the plate merges with the row;
 * the label still reads, which is the part that matters.
 */
@Composable
private fun MiPushBadge(text: String, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                onStartMultiSelect = {},
                onBatchAdd = {},
                onBatchRemove = {},
                onSelectAll = {},
                onAbout = {},
                onToggleShowSystemApps = {},
                onToggleShowFcmOnly = {},
                onToggleExcludeMiPush = {}
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

/**
 * The home search entry under the Miuix look: the library's own [InputField],
 * bare — exactly as the reference apps and the official docs render it, with no
 * card around it. The field's own body is the capsule (surfaceContainerHigh
 * fill), the search glyph leading and the cleanup glyph trailing, so wrapping it
 * in a card was a second capsule inside the first and read as a mistake, not as
 * a container.
 *
 * `expanded` follows the library's [SearchBar] semantics — focus is what expands
 * — rather than the hardcoded `true` an earlier pass wired in. Hardcoded true
 * made the field's own `LaunchedEffect(expanded)` grab focus on the first frame,
 * so opening the app woke the keyboard with nothing to search for yet; and with
 * the field permanently "expanded", its placeholder label never showed.
 *
 * The cancel sits where the library's [SearchBar] puts `outsideEndAction`:
 * outside the capsule at the row's end, sliding in only while the field is
 * active. Its spec is the demo's verbatim — Miuix text in the primary colour,
 * no press indication — and it collapses the field itself: the 0.9.1
 * [InputField] carries none of the collapse logic the newer library has, so
 * clearing the query and dropping focus are done here explicitly.
 *
 * The query plumbing is the same the Material look's search field uses: the text
 * lives in the Activity's state and every keystroke lands in its filter entry
 * point, so nothing about how a search behaves changes between the looks.
 */
@Composable
private fun MiuixSearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hint = stringResource(R.string.search_hint)
    val cancelLabel = stringResource(android.R.string.cancel)
    val focusManager = LocalFocusManager.current
    var active by remember { mutableStateOf(false) }
    // The focused field owns the back press: the first return (after the IME
    // has taken its own) clears a non-empty query, and the next one collapses
    // the field — never the app. Without this, back while searching killed the
    // activity and landed on the launcher, which is the one exit nobody means.
    BackHandler(enabled = active) {
        if (query.isNotEmpty()) {
            onQueryChange("")
        } else {
            active = false
            focusManager.clearFocus()
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiuixInputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = { },
            expanded = active,
            onExpandedChange = { active = it },
            label = hint,
            modifier = Modifier.weight(1f)
        )
        // The library's enter/exit for outsideEndAction, verbatim: the cancel
        // rides in from the right edge of the capsule and back out on collapse.
        AnimatedVisibility(
            visible = active,
            enter = expandHorizontally() +
                slideInHorizontally(initialOffsetX = { it }),
            exit = shrinkHorizontally() +
                slideOutHorizontally(targetOffsetX = { it })
        ) {
            MiuixText(
                text = cancelLabel,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable(
                        interactionSource = null,
                        indication = null
                    ) {
                        onQueryChange("")
                        active = false
                        focusManager.clearFocus()
                    }
            )
        }
    }
}
