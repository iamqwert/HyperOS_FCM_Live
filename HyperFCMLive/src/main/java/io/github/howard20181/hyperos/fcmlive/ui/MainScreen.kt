package io.github.howard20181.hyperos.fcmlive.ui

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.AppearanceState
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

import top.yukonga.miuix.kmp.basic.PullToRefresh as MiuixPullToRefresh
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Sort
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import io.github.howard20181.hyperos.fcmlive.ui.softglass.LocalSoftGlassBackdrop
import io.github.howard20181.hyperos.fcmlive.ui.softglass.SoftGlassIconButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces

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
    val excludeMiPushApps: Boolean = false
)

/** Every action the top bar can ask for. */
data class MainActions(
    val onBack: () -> Unit,
    val onSearch: () -> Unit,
    val onStartMultiSelect: () -> Unit,
    // The batch bar's leading ✕: leave the mode without saving. The back
    // gesture/arrow keeps its dual meaning (exit search first, else exit the
    // mode); this is the dedicated, unambiguous exit.
    val onExitMultiSelect: () -> Unit,
    // One save in the reconcile model: checkbox = final membership, save applies
    // the add/remove diff. Replaces the separate add/remove actions.
    val onBatchSave: () -> Unit,
    val onSelectAll: () -> Unit,
    val onToggleShowSystemApps: () -> Unit,
    val onToggleExcludeMiPush: () -> Unit
)

/**
 * The whole page: bar, list and pull-to-refresh.
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
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalScrollBarApi::class
)
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
    modifier: Modifier = Modifier,
    icons: Map<String, Drawable?> = emptyMap(),
    refreshing: Boolean,
    onRefresh: () -> Unit,
    // Remembered *saveably*, inside the composition, under a name: the position
    // has to survive both a rotation and the composition being torn down and
    // rebuilt. The Activity used to build the state itself and hand it in, which
    // is the one way to get a `LazyListState` nobody ever saves — the list came
    // back at the top after a rotation, the opposite of what building it there was
    // meant to achieve. So it stays internal, but through
    // [rememberPageListState]: the saveable registry lives on the view, not the
    // composition, so the position is restored even when the composition is
    // disposed wholesale — which is what a rotation does to it.
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

    if (LocalUiStyle.current.isMiuixBased) {
        // The Miuix frame: the Scaffold the overlay popups open into (the
        // overflow menu below), a large title in a gradient band, and the
        // search capsule as the LIST'S FIRST ITEM — it scrolls away with the
        // rows instead of pinning chrome under the bar. With no pinned chrome
        // left, this page wears the same glass band the other Miuix pages wear
        // (see [PageScaffold]); plain MIUIX still follows the floating-bar row
        // and falls back to the library's default bar when that row is off.
        // The system's own pull-to-refresh, the circle with the refresh text,
        // stays the M3 spinner's counterpart.
        val scrollBehavior = MiuixScrollBehavior()
        // The page's own layer for its own chrome — the bar's controls. Recorded
        // over the list below and not over the bar, so the controls that read it
        // are its siblings rather than its contents; see
        // [rememberPageLayerBackdrop] for why that has to be this page's layer
        // and not the shell's.
        val chromeGlass = softGlassChrome()
        val pageBackdrop = rememberPageLayerBackdrop()

        // The capsule's top inset, the reference's own ride ([IconPage]'s
        // dynamicTopPadding): 12dp while the large title stands, collapsing to
        // 0 as the bar folds to the compact title — which is what pulls the
        // capsule up against the small title instead of leaving it at the
        // large title's spacing. A lambda keeps the fraction out of
        // composition; the NaN guard is the same one [MiuixGlassTopAppBar]
        // documents for this very property (0/0 before the scroll range is
        // measured).
        val searchBarTopPadding: () -> Dp = remember(scrollBehavior) {
            {
                val fraction = scrollBehavior.state.collapsedFraction
                12.dp * (1f - (if (fraction.isNaN()) 0f else fraction))
            }
        }

        // The search, as the reference app authors it (MiuixSearchPager.kt —
        // its own machinery, ported): a fake capsule in the bar's bottom slot,
        // and tapping it raises a full-screen layer on which the real bar rides
        // from the capsule's position to the screen's top. The text itself
        // still lives with the Activity — the same hoisted `query` the M3 look
        // types into — so every status transition the machine makes lands here
        // as both a status write and the matching query write, and the
        // Activity's debounced filter keeps feeding whichever list is showing.
        val searchHint = stringResource(R.string.search_hint)
        var searchStatus by remember { mutableStateOf(SearchStatus(label = searchHint)) }
        var searchOffsetY by remember { mutableStateOf(0.dp) }
        val updateSearchStatus: (SearchStatus) -> Unit = { next ->
            searchStatus = next
            if (query != next.searchText) onQueryChange(next.searchText)
        }
        // The result kind, the reference's own derivation: blank query is the
        // page's default content, no hits is nothing, hits is the list. `apps`
        // already is the query's filtered product — the Activity owns the
        // debounced filter, so the kind follows it one debounce behind, the
        // way the reference's own pages lag their filter.
        val searchResultStatus = when {
            query.isBlank() -> SearchStatus.ResultStatus.DEFAULT
            apps.isEmpty() -> SearchStatus.ResultStatus.EMPTY
            else -> SearchStatus.ResultStatus.SHOW
        }
        LaunchedEffect(searchResultStatus) {
            if (searchStatus.resultStatus != searchResultStatus) {
                updateSearchStatus(searchStatus.copy(resultStatus = searchResultStatus))
            }
        }
        // The resting capsule wears its plain High fill: there is no glass
        // behind it to blend into any more, so the reference's 80% variant has
        // nothing to sit on.
        val searchCapsuleColor: Color? = null

        // The search entry is a LIST ITEM now, not a bar slot: it scrolls up
        // with the rows, which is what lets the bar above wear the gradient
        // band — nothing pinned reaches under it. Its window Y is still
        // reported, so the pager's ride starts from wherever the capsule sits
        // when it is tapped (see [SearchStatus.SearchPager]).
        val searchHeader: @Composable () -> Unit = {
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                    .onGloballyPositioned { coordinates ->
                        searchOffsetY = with(density) {
                            coordinates.positionInWindow().y.toDp()
                        }
                    }
                    .then(
                        if (searchStatus.isCollapsed()) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures {
                                    updateSearchStatus(
                                        searchStatus.copy(
                                            current = SearchStatus.Status.EXPANDING
                                        )
                                    )
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                SearchBarFake(
                    label = searchStatus.label,
                    capsuleColor = searchCapsuleColor,
                    // The list already insets its items: a second inset here
                    // would narrow the field against the app cards below it.
                    horizontalInset = 0.dp,
                    // The list's own item spacing owns the gap to the first row.
                    bottomPadding = 0.dp
                )
            }
        }
        Box(modifier = modifier.fillMaxSize()) {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            // The app's page level, not Miuix's own default: the app's surface
            // policy owns the page colour, and a default-coloured scaffold would
            // disagree with the window chrome behind the bar.
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // The bar's controls read the page's own layer; the provider
                // wraps the bar alone, because the list below is the recorded
                // node and reads nothing. The reveal beside it is the look's
                // bare-at-the-top rule: the plates appear once the list has
                // scrolled under the bar.
                CompositionLocalProvider(
                    LocalSoftGlassBackdrop provides pageBackdrop,
                    LocalSoftGlassBarReveal provides softGlassBarMaterialProgress(
                        scrollBehavior.state.collapsedFraction
                    )
                ) {
                    // The reference's own cross-fade: the resting bar fades out
                    // while the pager's surface fades in, so the expanding bar
                    // never overlaps a standing capsule. Transparent, not the
                    // page tone: a band needs the body's rows behind it.
                    searchStatus.TopAppBarAnim(
                        backgroundColor = Color.Transparent
                    ) {
                        MainTopBar(
                            state = topBarState,
                            actions = actions,
                            scrollBehavior = scrollBehavior
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The list alone; the bar above is composed outside this node,
                    // which is what lets its controls read the layer — see
                    // [rememberPageLayerBackdrop].
                    .then(if (chromeGlass) Modifier.layerBackdrop(pageBackdrop) else Modifier)
                    // The bar's collapse and its band's fade-in both read this
                    // connection. The page has no pinned chrome of its own now,
                    // so it collapses exactly like every other Miuix page.
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                MiuixPullToRefresh(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    // The circle's anchor follows the bar as it collapses; the
                    // library reads it from the behavior, which the body box
                    // above now drives through nested scroll.
                    topAppBarScrollBehavior = scrollBehavior,
                    // The library's defaults are hard-coded English; the text is
                    // part of the indicator, so it localises here.
                    refreshTexts = listOf(
                        stringResource(R.string.refresh_pull_down),
                        stringResource(R.string.refresh_release),
                        stringResource(R.string.refreshing),
                        stringResource(R.string.refresh_done)
                    ),
                    // Full height, no top padding: rows travel UNDER the bar,
                    // which paints over them. The bar's room rides in the
                    // list's content padding instead (see [AppListPane]).
                    // `contentPadding` pads only the REFRESH HEADER, not the
                    // content: it anchors the indicator just below the bar —
                    // the library's own demo passes the toolbar inset here.
                    // Without it the header sits at the very top of the screen,
                    // behind the bar, where a pull only grows it in place:
                    // nothing appears until the circle has stretched past the
                    // bar's bottom edge.
                    contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
                    modifier = Modifier.fillMaxSize()
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
                        topPadding = innerPadding.calculateTopPadding(),
                        // Plus the floating bar's clearance (see
                        // [LocalFloatingBarClearance]): this page is a tab, and
                        // the pill hovers over the foot of every tab — the list
                        // has to be able to come to rest above it. Zero wherever
                        // the bar is docked.
                        bottomPadding = innerPadding.calculateBottomPadding() +
                            LocalFloatingBarClearance.current,
                        // The search capsule, as the list's first item: it
                        // leaves the screen with the rows when the page scrolls.
                        header = searchHeader,
                        modifier = Modifier.fillMaxSize()
                    )
                    // The reference's scroll indicator, riding the same list —
                    // every list page in the reference hangs one beside its
                    // list (MainPage's does), through the same body scope.
                    VerticalScrollBar(
                        adapter = rememberScrollBarAdapter(lazyListState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        trackPadding = PaddingValues(
                            top = appListOpeningAir() + innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding() +
                                LocalFloatingBarClearance.current
                        )
                    )
                }
            }
        }
        // The full-screen search layer, over the whole page — the reference
        // hosts it in the scaffold's own popup slot; a Box around the
        // scaffold is this page's equivalent seat, and the layer is the
        // scaffold's SIBLING here, covering the bar too. Collapsed it is an
        // empty, fully transparent, non-intercepting layer; expanded it
        // carries the results, the same rows the page's list shows, one card
        // to a row with the reference's own 12/6 spacing.
        searchStatus.SearchPager(
            onSearchStatusChange = updateSearchStatus,
            offsetY = searchOffsetY,
            defaultResult = {},
            searchBarTopPadding = searchBarTopPadding,
            collapsedCapsuleColor = searchCapsuleColor,
            // The sheet covers the page but NOT the home's bottom chrome, which
            // is composed outside it. Keep the last result clear of that bar.
            resultBottomPadding = LocalFloatingBarClearance.current,
        ) {
                items(items = apps, key = { it.packageName }) { app ->
                    AppRow(
                        label = app.label,
                        packageName = app.packageName,
                        icon = icons[app.packageName],
                        checked = app.checked,
                        supportMiPush = app.supportMiPush,
                        exempt = app.exempt,
                        multiSelect = multiSelect,
                        rowSelected = selected.contains(app.packageName),
                        onClick = { onRowClick(app) },
                        onLongClick = { onRowLongClick(app) },
                        onIconMissing = { loadIcon(app) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        return
    }
    // The M3 home. Like every secondary page, the title is a collapsing
    // large bar — the search field is the LIST'S FIRST ITEM now, travelling
    // up with the rows instead of standing under the bar — and the
    // diagnostics entry is a card on the module tab, not a FAB. The bar
    // paints itself the page's background (rows travel under it and are
    // occluded; the bar's room rides in the list's content padding) — no
    // glass: the Miuix look paints a gradient band instead.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            // The bar reads the list's scroll through this connection; the
            // pull-to-refresh box below passes scrolls through.
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        // ime joins the default system bars: the window is adjustResize
        // (AndroidManifest), and with the keyboard up the list's bottom
        // padding rides the ime inset — the last row stays reachable above
        // the keyboard instead of sitting behind it. Keyboard down, the
        // inset is zero and the layout is unchanged.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .union(WindowInsets.ime),
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
                m3ScrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier
                .fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // The spinner anchors at the box's top — behind the
                        // bar, where a pull only swells it in place. Dropped
                        // below the bar (the Miuix branch anchors its refresh
                        // header the same way), a pull shows it at once.
                        .offset(y = innerPadding.calculateTopPadding())
                    // Stock indicator dress: the neutral container colour
                    // LoadingIndicator ships with. A primaryContainer skin
                    // used to be painted over it — legal, but every other
                    // indicator in the toolkit is neutral and there is no
                    // pairing rule this one needs to bend for.
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
                    topPadding = innerPadding.calculateTopPadding(),
                    // The bar's row is spent by the frame above, so this is the
                    // clearance alone: the air the list keeps at its foot —
                    // without it the last row landed flush against the bar and
                    // read crowded.
                    bottomPadding = innerPadding.calculateBottomPadding() +
                        LocalFloatingBarClearance.current,
                    // The search card, as the list's first item: it scrolls off
                    // with the rows. The list already insets its items, so the
                    // card drops the horizontal padding it wore in the bar.
                    header = {
                        InlineSearchCard(
                            query = query,
                            onQueryChange = onQueryChange,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.background)
                                // The list's own 8dp item spacing carries the
                                // rest of the 12dp gap the pinned card used to
                                // keep entirely to itself.
                                .padding(bottom = 4.dp)
                        )
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    state: MainTopBarState,
    actions: MainActions,
    modifier: Modifier = Modifier,
    /**
     * The Miuix bar's collapse behavior. Owned by [MainScreen] so the body's
     * nested-scroll connection and the bar read the same state; only the Miuix
     * branch uses it.
     */
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior? = null,
    /**
     * The M3 bar's collapse behavior, owned by [MainScreen] so its
     * nested-scroll connection wraps the same Scaffold the bar sits in.
     * Unused under Miuix, which carries its own behavior type.
     */
    m3ScrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior? = null
) {
    if (LocalUiStyle.current.isMiuixBased) {
        // The search capsule is no longer a bar slot: MainScreen mounts it as
        // the list's first item, so it travels up with the rows. With nothing
        // pinned under the bar, this page can wear the same gradient band as
        // every other Miuix page. Plain MIUIX still follows the floating-bar
        // row and keeps the library's default bar when that row is off.
        val floatingBar by AppearanceState.floatingBar
        val useGlassBar = softGlassChrome() || floatingBar
        // MainScreen always hands one in; the fallback only protects the
        // signature for the M3 call site, which never enters this branch.
        val behavior = scrollBehavior ?: MiuixScrollBehavior()
        val fraction = behavior.state.collapsedFraction
        val scrolled = !fraction.isNaN() && fraction > 0f
        val view = LocalView.current
        // Every operation control on this bar ticks (the row press's own
        // KEYBOARD_TAP, the app page's one feedback language): the batch bar's
        // ✕ and ✓, the resting bar's batch entry, and the overflow (筛选).
        val leading: @Composable () -> Unit = {
            BarIconButton(onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                actions.onExitMultiSelect()
            }) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.batch_exit),
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        val emptyControl: @Composable () -> Unit = {}
        // Batch mode keeps the resting bar's own shape: the same collapsing
        // large title, with the count in the title slot — 「应用」 simply
        // becomes 「已选择 N 项」. The mode's controls stay at the bar's right
        // end where HyperOS's own batch pages keep them: the select-all flip,
        // then 保存. Glyphs, not words — 全选 widening to 取消全选 shoved the
        // count sideways on every flip.
        val barActions: @Composable RowScope.() -> Unit = {
            if (state.multiSelect) {
                BatchSelectMenu(
                    allVisibleSelected = state.allVisibleSelected,
                    onSelectAll = actions.onSelectAll
                )
                // The bar packs its actions edge to edge, which the 24dp ink
                // buttons absorb but the 柔光玻璃 44dp glass discs do not —
                // two of them parked adjacent read as one blob.
                if (softGlassChrome()) Spacer(Modifier.width(8.dp))
                BarIconButton(onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    actions.onBatchSave()
                }) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = stringResource(R.string.batch_save),
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                // Resting bar: batch mode's entry one slot left of the
                // overflow — the same two-control bar the reference app rests
                // in. The NORMAL weight, so this bar carries one stroke.
                TopBarIconButton(
                    painter = rememberVectorPainter(MiuixIcons.Normal.SelectAll),
                    description = stringResource(R.string.batch_ops),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        actions.onStartMultiSelect()
                    }
                )
                // Same air the batch bar gets: under 柔光玻璃 these are 44dp
                // glass discs parked rim to rim without it.
                if (softGlassChrome()) Spacer(Modifier.width(8.dp))
                OverflowMenu(state.overflow, actions)
            }
        }
        if (useGlassBar) {
            MiuixGlassTopAppBar(
                title = state.title,
                scrolled = scrolled,
                showBack = false,
                backDescription = "",
                onBack = {},
                scrollBehavior = behavior,
                modifier = modifier,
                actions = barActions,
                navigationIcon = if (state.multiSelect) leading else null
            )
        } else {
            Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
                MiuixTopAppBar(
                    title = state.title,
                    largeTitle = state.title,
                    color = MaterialTheme.colorScheme.background,
                    navigationIcon = if (state.multiSelect) leading else emptyControl,
                    actions = barActions,
                    scrollBehavior = behavior
                )
            }
        }
        return
    }
    if (state.multiSelect) {
        // Batch mode keeps the resting bar's own shape — the same collapsing
        // large title. 「应用」 becomes 「已选择 N 项」 in the title slot, the
        // leading ✕ leaves without saving, and the bar's right end carries the
        // mode's controls where HyperOS's own batch pages keep them: the
        // select-all flip, then 保存. Glyphs, not words — 全选 widening to
        // 取消全选 would shove the count sideways on every flip. The
        // select-all control is a single icon that states the action it
        // offers: select_all at rest, select once everything is on.
        //
        // The search card lives in the list now, so it stays put across the
        // mode's edges without a second copy here: batch is a tool over the
        // list, not a different page.
        Column(modifier = modifier.fillMaxWidth()) {
            AppTopBar(
                titleRes = R.string.settings_title,
                title = state.title,
                onBack = { actions.onExitMultiSelect() },
                navIconRes = R.drawable.ic_close,
                navDescRes = R.string.batch_exit,
                actions = {
                    TopBarIconButton(
                        painter = painterResource(
                            if (state.allVisibleSelected) {
                                R.drawable.ic_select
                            } else {
                                R.drawable.ic_select_all
                            }
                        ),
                        description = stringResource(
                            if (state.allVisibleSelected) {
                                R.string.deselect_all
                            } else {
                                R.string.select_all
                            }
                        ),
                        onClick = { actions.onSelectAll() }
                    )
                    TopBarIconButton(
                        painter = painterResource(R.drawable.ic_done_all),
                        description = stringResource(R.string.batch_save),
                        onClick = { actions.onBatchSave() }
                    )
                },
                scrollBehavior = m3ScrollBehavior
            )
        }
        return
    }
    // The resting M3 bar: the collapsing large title every secondary page
    // wears, the batch entry one slot left of the overflow at its end — the
    // same two-control bar the Miuix branch rests in. The search card is
    // the list's first item (see MainScreen), not a bar slot.
    Column(modifier = modifier.fillMaxWidth()) {
        AppTopBar(
            titleRes = R.string.settings_title,
            onBack = {},
            showBack = false,
            actions = {
                TopBarIconButton(
                    painter = painterResource(R.drawable.ic_checklist),
                    description = stringResource(R.string.batch_ops),
                    onClick = { actions.onStartMultiSelect() }
                )
                OverflowMenu(state.overflow, actions)
            },
            scrollBehavior = m3ScrollBehavior
        )
    }
}

/**
 * The applications tab's inline search: the list's first item, so it scrolls
 * up with the rows instead of standing fixed under the collapsing title.
 * Always composed — the old search-icon round trip (icon → focus → back
 * arrow) is gone along with the auto-focus dance that needed it.
 */
@Composable
private fun InlineSearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val searchHint = stringResource(R.string.search_hint)
    Surface(
        // One tone deeper than the bar's High fill: the field reads as a
        // pressed-in control against the page, not a washed-out chip.
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        // The bare field announces itself to a screen reader
                        // as just 「可编辑文本框」 — the hint is a sibling
                        // text node, not a label. Naming the field after the
                        // hint is the accessibility fix for a decorated
                        // BasicTextField (developer.android.com/develop/ui/
                        // compose/accessibility); the placeholder below is
                        // silenced so the hint is spoken once, by the field.
                        .semantics { contentDescription = searchHint },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    text = searchHint,
                                    // Decorative duplicate of the field's own
                                    // contentDescription — hidden from the
                                    // accessibility tree, visible to the eye.
                                    modifier = Modifier.clearAndSetSemantics { },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            if (query.isNotEmpty()) {
                TopBarIconButton(
                    painter = painterResource(R.drawable.ic_close),
                    description = stringResource(R.string.exit_search),
                    onClick = { onQueryChange("") }
                )
            }
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
 * These stay bare `IconButton`s under Miuix on purpose — the home bar's icons
 * are a row of peers over a list, and the floating-plate dress is for the
 * secondary pages' lone back control (`PageScaffold`, where the plate also
 * fades in on scroll). Under Material the icons stay bare for the same
 * peer-row reason; only that look's `AppTopBar` back control wears a plate.
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
    if (LocalUiStyle.current.isMiuixBased) {
        LongPressTooltip(label = description) {
            // The bar's own material — the library's button on the plain Miuix
            // look, 柔光玻璃's plate under that style (see [BarIconButton]); this
            // KDoc's note above is about why the *hand-rolled* plates were
            // dropped. The glyph keeps the bar's own ink — onSurface,
            // the same color the bar's title paints with. The library's
            // onBackgroundVariant is a caption tone (a washed blue-grey);
            // tinting the actions with it is what made this bar read paler
            // than every system app's.
            BarIconButton(onClick = onClick, modifier = modifier) {
                Icon(
                    painter = painter,
                    contentDescription = description,
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    } else {
        // Material's own tooltip wrapper, the same one the overflow's three
        // dots wear (see [OverflowMenu]). The Miuix [LongPressTooltip] must
        // not appear here: wrapping this branch's button in it is what made
        // every glyph it carried silently vanish under the Material look —
        // the batch entry, the batch mode's select-all and save, all gone
        // while the (unwrapped) dots beside them stayed.
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Below
            ),
            tooltip = {
                PlainTooltip { Text(description) }
            },
            state = rememberTooltipState()
        ) {
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
 * A home-bar icon control, in whichever material the bar's own look is milled
 * from: the library's bare button on both plain looks, the 柔光玻璃 plate under
 * [UiStyle.SoftGlass] — the same material, sizes, glyph ink and press language
 * everywhere else in that look, so a bar cannot end up half dressed. The plate
 * is chrome for a bar with content under it, so its material fades with the
 * page's collapse ([LocalSoftGlassBarReveal]) — the reference's own
 * construction, one control whose glass plate breathes over a glyph that never
 * changes identity, not two controls cross-fading.
 *
 * The plates sample the PAGE's own layer, carried here by [MainScreen] and
 * [PageScaffold] (see [rememberPageLayerBackdrop]) rather than read off the
 * composition: a control standing in the bar must not draw a layer it is itself
 * recorded into.
 */
@Composable
private fun BarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (softGlassChrome()) {
        // One control, the reference way: the plate's material alpha rides the
        // collapse progress, so at the page's top this reads as a bare glyph —
        // same button, same spot, the glass simply not there yet.
        val reveal by animateFloatAsState(
            targetValue = LocalSoftGlassBarReveal.current,
            animationSpec = SOFT_GLASS_REVEAL_FADE
        )
        SoftGlassIconButton(
            onClick = onClick,
            modifier = modifier,
            materialAlpha = reveal,
            content = content
        )
    } else {
        MiuixIconButton(onClick = onClick, modifier = modifier, content = content)
    }
}

/**
 * The batch bar's selection control, in the reference home bar's own top-right
 * language: a [BarIconButton] wearing the library's SelectAll glyph,
 * opening the selection menu — 全选 / 取消全选 as two option rows, the current
 * state carrying the check, a pick collapsing the panel. The same panel, row
 * type and dim treatment as [OverflowMenu] (this is its machinery with two
 * rows); only the anchor differs.
 *
 * It exists because the words could not: 全选 widening to 取消全选 shoved the
 * 「已选择 N 项」 title sideways on every flip. A glyph holds one width for
 * both states, so the title never moves.
 */
@Composable
private fun BatchSelectMenu(
    allVisibleSelected: Boolean,
    onSelectAll: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    // The page dim behind the panel, reported to the shell's bars exactly as
    // [OverflowMenu] reports its own — see [PopupOverlay].
    val overlayKey = remember { Any() }
    DisposableEffect(overlayKey) {
        onDispose { PopupOverlay.clear(overlayKey) }
    }
    LaunchedEffect(expanded) { PopupOverlay.report(overlayKey, expanded) }
    Box {
        BarIconButton(onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            expanded = true
        }) {
            Icon(
                // Same weight as the bar's own copy of this glyph (see the
                // resting bar's note): the popup row and the control that opens
                // it are one control in two states, and a weight change between
                // them would read as the glyph growing when the panel opens.
                imageVector = MiuixIcons.Normal.SelectAll,
                contentDescription = stringResource(
                    if (allVisibleSelected) R.string.deselect_all else R.string.select_all
                ),
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
        OverlayListPopup(
            show = expanded,
            onDismissRequest = { expanded = false },
            // End-aligned at the screen's right corner and clamped inside the
            // window — [OverflowMenuPositionProvider], the same provider the
            // overflow uses.
            popupPositionProvider = OverflowMenuPositionProvider,
            alignment = top.yukonga.miuix.kmp.basic.PopupPositionProvider.Align.TopEnd
        ) {
            // Two option rows in the menu's own language: the check marks the
            // state the list is in right now. Each row drives toward its own
            // state, so a pick that is already current simply collapses.
            ListPopupColumn {
                DropdownImpl(
                    item = DropdownItem(text = stringResource(R.string.select_all)),
                    optionSize = 2,
                    isSelected = allVisibleSelected,
                    index = 0,
                    isFirst = true,
                    isLast = false,
                    onSelectedIndexChange = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        expanded = false
                        if (!allVisibleSelected) onSelectAll()
                    }
                )
                DropdownImpl(
                    item = DropdownItem(text = stringResource(R.string.deselect_all)),
                    optionSize = 2,
                    isSelected = !allVisibleSelected,
                    index = 1,
                    isFirst = false,
                    isLast = true,
                    onSelectedIndexChange = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        expanded = false
                        if (allVisibleSelected) onSelectAll()
                    }
                )
            }
        }
    }
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
 * The width is intrinsic so the three check boxes land on one vertical line the
 * way `popup_overflow.xml` aligned them, but the popup is still measured from
 * its widest row — never from the window, which is what made the settings menus
 * open at the card's left edge.
 *
 * There is deliberately no divider above "设置": the XML had none, and the three
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
    if (LocalUiStyle.current.isMiuixBased) {
        // This popup dims the page behind it exactly as the settings rows'
        // dropdowns do, so the shell's bars have to hear about it: the veil is
        // drawn inside the layer the glass would sample, and a bar reading a veil
        // as its own backdrop is a bar lighting up over the popup instead of
        // standing behind it. See [PopupOverlay].
        val overlayKey = remember { Any() }
        DisposableEffect(overlayKey) {
            onDispose { PopupOverlay.clear(overlayKey) }
        }
        LaunchedEffect(expanded) { PopupOverlay.report(overlayKey, expanded) }
        // The filter button ticks like every other operation control on this
        // bar (see MainTopBar's note).
        val view = LocalView.current
        Box {
            // No long-press bubble under this look — see [LongPressTooltip].
            // In the bar's own material, like every home-bar control (see
            // [BarIconButton] and [TopBarIconButton]).
            BarIconButton(onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                expanded = true
            }) {
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
                    stringResource(R.string.exclude_mipush_apps),
                    stringResource(R.string.show_system_apps)
                )
                val toggled = listOf(
                    state.excludeMiPushApps,
                    state.showSystemApps
                )
                val toggles: List<() -> Unit> = listOf(
                    actions.onToggleExcludeMiPush,
                    actions.onToggleShowSystemApps
                )
                // Settings no longer lives here — it moved to the bar's leading
                // edge. The divider that separated the toggles from it went with
                // it: the remaining rows answer the same yes/no kind, and
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
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                PlainTooltip { Text(moreLabel) }
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
            // No "设置" row here: the bottom navigation carries settings on
            // this look, and a second entry would be two doors to one room.
            // (The Miuix branch above keeps it — that look has no bar.)
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
    if (LocalUiStyle.current.isMiuixBased) {
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
 * The air the app list opens on, below its top bar.
 *
 * None on the Miuix looks: the search capsule is the list's FIRST ITEM now and
 * carries its own top inset, so a gap here would only push it away from the
 * bar. Material keeps its 4dp: its search card is the first item too, and the
 * list's 8dp item spacing plus the card's own 4dp bottom lands on the same
 * 12dp gap the pinned card used to keep to itself.
 */
@Composable
private fun appListOpeningAir(): Dp =
    if (LocalUiStyle.current.isMiuixBased) 0.dp else 4.dp

/**
 * The app list: a `LazyColumn` keyed by package name.
 *
 * The key is also why the scroll position survives a refresh without the manual
 * `setSelectionFromTop` the ListView needed — keys let Compose follow rows whose
 * order moved because a check promoted them. The page's search field mounts as
 * the FIRST item through [header], so it travels up with the rows rather than
 * pinning chrome under the bar; the list's own content padding insets it, which
 * is why the field itself no longer carries a horizontal padding of its own.
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
    modifier: Modifier = Modifier,
    icons: Map<String, Drawable?> = emptyMap(),
    topPadding: Dp = 0.dp,
    bottomPadding: Dp,
    /** First list item — the page's search field, travelling with the rows. */
    header: (@Composable () -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .listScrollEndHaptic(),
            state = lazyListState,
            // The bottom room comes from the caller's `innerPadding` (navigation
            // bar inset + FAB reservation). As *content* padding it lives inside
            // the scroll: cards glide under the gesture hint line on the way past
            // and only come to rest that far above it. The top room rides here too
            // on the Miuix look (the glass bar's height): rows scroll UNDER the
            // base of the bar instead of stopping at its edge.
            contentPadding = PaddingValues(
                start = pageHorizontalInset(), top = appListOpeningAir() + topPadding,
                end = pageHorizontalInset(), bottom = bottomPadding
            ),
            // 12dp between cards under Miuix, the spacing the reference apps leave
            // between their section cards; 8dp is the Material ramp's own choice.
            verticalArrangement = Arrangement.spacedBy(
                if (LocalUiStyle.current.isMiuixBased) 12.dp else 8.dp
            )
        ) {
            if (header != null) {
                item(key = "searchHeader") { header() }
            }
            items(items = apps, key = { it.packageName }) { app ->
                AppRow(
                    label = app.label,
                    packageName = app.packageName,
                    icon = icons[app.packageName],
                    checked = app.checked,
                    supportMiPush = app.supportMiPush,
                    exempt = app.exempt,
                    multiSelect = multiSelect,
                    rowSelected = selected.contains(app.packageName),
                    onClick = { onRowClick(app) },
                    onLongClick = { onRowLongClick(app) },
                    onIconMissing = { loadIcon(app) }
                )
            }
        }
        // The look's own indicator, on the track the list's content padding
        // leaves: the thumb starts under the glass bar and stops above the
        // gesture strip. It only paints while the list is moving, so a short
        // list — and this list at rest — looks exactly as it did before.
        MiuixScrollBar(
            listState = lazyListState,
            modifier = Modifier.align(Alignment.CenterEnd),
            top = appListOpeningAir() + topPadding,
            bottom = bottomPadding
        )
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
 * [exempt] rows — GMS and its persistent process — are drawn as unavailable:
 * desaturated to `onSurfaceVariant` at reduced emphasis, with the checkbox
 * withheld and no ripple or click handling at all. For
 * GMS the module always acts for it whatever the allowlist says, so a checkbox
 * there would be a control that does nothing — and a control that does nothing
 * reads as a bug. The subtitle keeps the package name every row shows.
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
    val selectedWash = cardSelected && !LocalUiStyle.current.isMiuixBased
    // Miuix cards carry the one radius the library's own Card carries; the M3
    // ramp (and its 2025 shape set) belongs to the other look.
    val shape = if (LocalUiStyle.current.isMiuixBased) {
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
    val selectionState = stringResource(
        if (rowSelected) R.string.row_selected else R.string.row_unselected
    )
    val description = remember(
        label, packageName, allowlistState, supportMiPush, multiSelect,
        selectionState, exempt
    ) {
        if (exempt) {
            // The checkbox state is not part of what an exempt row means, so it
            // is not announced.
            listOf(label, packageName).joinToString("，")
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

    // Sizing wrapper around the card. It used to anchor the Miuix long-press
    // popup; the popup is gone (long-press now starts a batch selection), and
    // the wrapper stays as the row's fillMaxWidth host.
    Box(modifier = Modifier.fillMaxWidth()) {
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
                        indication = if (LocalUiStyle.current.isMiuixBased) {
                            LocalIndication.current
                        } else {
                            ripple()
                        },
                        onLongClick = onLongClick,
                        onClick = {
                            // CONTEXT_CLICK — the same list-item tap tick the
                            // shared GroupRow fires; one list language for
                            // both looks.
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
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
                    AppText(
                        text = label,
                        // Miuix rows print their title at headline1's size with a
                        // medium weight (what BasicComponent sets); keeping the
                        // app list on the M3 titleMedium under this look is what
                        // made its rows read as foreign next to the settings rows.
                        style = if (LocalUiStyle.current.isMiuixBased) {
                            TextStyle(
                                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        color = labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (supportMiPush) {
                        MiPushBadge(text = mipushTag)
                    }
                }
                AppText(
                    // Every row — exempt or not — prints its package name here:
                    // the package name is the one thing every row shows, and an
                    // exempt row reads no different for being exempt.
                    text = packageName,
                    // Miuix rows print their summary at body2's size (again what
                    // BasicComponent sets); Material rows take the list token's
                    // supporting size, bodyMedium — the same grade the settings
                    // rows and the records rows now read.
                    style = if (LocalUiStyle.current.isMiuixBased) {
                        TextStyle(fontSize = MiuixTheme.textStyles.body2.fontSize)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = supportingColor
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (LocalUiStyle.current.isMiuixBased) {
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
                            // Stages like tapping the row, so it carries the
                            // row's CONTEXT_CLICK tick, not a keypress.
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
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
            } else if (multiSelect && !exempt) {
                // The Material look's batch rows answer with their own checkbox, the
                // M3 multi-select language, mirroring the Miuix branch above: the
                // staged state lives at the trailing edge instead of the saved
                // allowlist glyph, which under batch semantics would be a readout
                // that never moves while the tap rewrites something else. Silent in
                // the a11y tree for the same reason — the row carries the state.
                Checkbox(
                    checked = rowSelected,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics {}
                )
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
}

/**
 * The MiPush chip: primary-container fill at the small corner radius, so the
 * label takes that fill's own partner (`onPrimaryContainer`). It used to be
 * `primary`, which is the *plate's* partner only when the plate is a handle —
 * on MONOCHROME that was #000000 on a #3B3B3B fill. In a selected row the chip's
 * fill and the row's fill are the same role, so the plate merges with the row;
 * the label still reads, which is the part that matters.
 *
 * The label is M3's labelSmall under the Material look and that style's Miuix
 * counterpart under Miuix — the footnote size, which is the same 11sp, at the
 * same medium weight — rather than a Material style inside a Miuix row.
 */
@Composable
private fun MiPushBadge(text: String, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    AppText(
        text = text,
        style = if (LocalUiStyle.current.isMiuixBased) {
            TextStyle(
                fontSize = MiuixTheme.textStyles.footnote2.fontSize,
                fontWeight = FontWeight.Medium
            )
        } else {
            MaterialTheme.typography.labelSmall
        },
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
                onExitMultiSelect = {},
                onBatchSave = {},
                onSelectAll = {},
                onToggleShowSystemApps = {},
                onToggleExcludeMiPush = {}
            ),
            query = "",
            onQueryChange = {},
            apps = listOf(
                AppListStore.AppEntry("com.tencent.mm", "微信").apply {
                    checked = true
                },
                AppListStore.AppEntry("org.telegram.messenger", "Telegram"),
                AppListStore.AppEntry("com.example.pushapp", "推送示例").apply {
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
            onRefresh = {}
        )
    }
}

/**
 * The home search entry under the Miuix look is the reference's own pager now
 * ([SearchStatus.SearchPager], see MiuixSearchPager.kt) — the fake capsule in
 * the bar ([MainTopBar]'s bottom slot) and the full-screen layer in
 * [MainScreen]'s Miuix frame. The hand-rolled field this file once carried
 * (`MiuixSearchCard`, the library InputField plus a self-written cancel and a
 * plain BackHandler) is gone with the machinery it stood in for: every piece
 * it improvised — the expand-on-focus semantics, the cancel's spec, the
 * back-to-collapse — is what the ported pager does natively, and the pieces it
 * lacked (the ride-to-top, the surface fade, the predictive back) are what the
 * pager adds.
 */

