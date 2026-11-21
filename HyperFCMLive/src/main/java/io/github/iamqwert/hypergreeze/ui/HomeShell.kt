package io.github.iamqwert.hypergreeze.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.iamqwert.hypergreeze.R
import io.github.iamqwert.hypergreeze.theme.AppearanceState
import io.github.iamqwert.hypergreeze.theme.LocalUiStyle
import io.github.iamqwert.hypergreeze.theme.ThemePrefs
import io.github.iamqwert.hypergreeze.ui.softglass.SoftGlassNavigationBar
import io.github.iamqwert.hypergreeze.ui.softglass.SoftGlassNavigationBarDefaults
import io.github.iamqwert.hypergreeze.ui.softglass.SoftGlassNavigationItem
import io.github.iamqwert.hypergreeze.ui.softglass.softGlassNavigationBarBottomMargin
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The three tabs of the M3 home's bottom navigation.
 *
 * 「设置」 reuses the settings page's own string: two copies of one label would
 * only be a drift waiting to happen. The about page is NOT a tab — it is a
 * second-level page reached from the module tab's about card.
 *
 * Each tab carries both fill states of its Material Symbol: the bar renders
 * the outlined (fill=0) cut for the tab at rest and the filled (fill=1) cut
 * for the selected one — the spec's way of marking selection, on top of the
 * indicator pill the item draws.
 */
enum class HomeTab(val titleRes: Int, val iconRes: Int, val iconFilledRes: Int) {
    Modules(R.string.nav_modules, R.drawable.ic_home, R.drawable.ic_home_filled),
    Apps(R.string.nav_apps, R.drawable.ic_apps_outline, R.drawable.ic_apps),
    /**
     * GCM's own event log, read through the module's system_server half (see
     * [io.github.iamqwert.hypergreeze.records.RecordsScreen]). Sits
     * between the whitelist and settings: it is a read-out, not a control.
     */
    Records(R.string.nav_records, R.drawable.ic_records, R.drawable.ic_records),
    Settings(R.string.settings, R.drawable.ic_settings_outline, R.drawable.ic_settings)
}

/**
 * The Miuix cut of each tab glyph: the library's own icon set — the marks
 * every Miuix-styled system app draws — not the Material Symbols the M3 bar
 * wears. One glyph per tab: selection under this look is the bar's own ink
 * treatment (tint on the selected item), not a fill swap between two cuts.
 */
private val HomeTab.miuixIcon: ImageVector
    get() = when (this) {
        HomeTab.Modules -> MiuixIcons.Home
        HomeTab.Apps -> MiuixIcons.GridView
        HomeTab.Records -> MiuixIcons.Notes
        HomeTab.Settings -> MiuixIcons.Settings
    }

/**
 * The Material 3 Expressive home frame: one [ShortNavigationBar] docked at the
 * screen's foot, the selected tab filling the rest — the plain bottom
 * navigation the spec describes, not a floating pill.
 *
 * The bar is the *short* bar, the expressive cut: the toolkit's baseline
 * [androidx.compose.material3.NavigationBar] is the pre-2025 component and the
 * M3 site no longer recommends it for new work, while `ShortNavigationBar` is
 * the one bar the library ships marked expressive. Its container height is the
 * same `NavigationBarTokens.ContainerHeight` token (64dp in this release — the
 * "80dp" the old comment here carried was stale), so the frame's geometry is
 * unchanged — what differs is the item: the indicator is the full-corner pill
 * the expressive spec draws, sized to the icon rather than to the item's cell.
 *
 * The bar sits in a plain [Column], not a Scaffold slot: every tab is itself a
 * full Scaffold (the app list's search bar, the settings page's collapsing
 * title), and a nested `bottomBar` would make each of them pad for a bar they
 * cannot see. Stacked as siblings, each tab's Scaffold fills the space above
 * the bar on its own.
 *
 * Two details keep that stacking honest:
 *  - `consumeWindowInsets` marks the navigation-bar inset spent inside the
 *    content box. The bar's own background already reaches under the gesture
 *    strip; without this, each tab's Scaffold would read the same inset again
 *    and leave a dead band of page background above the bar.
 *  - Each tab composes under its own [androidx.compose.runtime.saveable.SaveableStateProvider]
 *    key, so a page's scroll position (and everything else the saveable
 *    registry holds) survives a round trip to another tab.
 *
 * M3 only — the Miuix home keeps its single-page layout, so this composable
 * is never composed under that look.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeShell(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (HomeTab) -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        // The bar below is a reserved row here, so a page's viewport already
        // stops at the bar's top edge — but the last row of a list would still
        // land flush against it, which is the crowding the app page showed. The
        // clearance gives the list the same air at its foot that its search box
        // has at the top; it is the pages' business to spend it (see
        // [LocalFloatingBarClearance]), and every page does.
        CompositionLocalProvider(LocalFloatingBarClearance provides FLOATING_BAR_GAP) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .consumeWindowInsets(ShortNavigationBarDefaults.windowInsets)
            ) {
                val stateHolder = rememberSaveableStateHolder()
                val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                // Standard Material 3 top-level navigation transition: Fade Through
                // (fast, critically-damped cross-fade with a subtle 0.98 -> 1.0 scale)
                // so tab switches are immediately interactive with zero pager hold.
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        (fadeIn(animationSpec = effectsSpec) +
                            scaleIn(initialScale = 0.98f, animationSpec = effectsSpec)) togetherWith
                            fadeOut(animationSpec = effectsSpec)
                    },
                    label = "HomeTabFadeThrough"
                ) { tab ->
                    stateHolder.SaveableStateProvider(tab) {
                        content(tab)
                    }
                }
            }
        }
        // The bar, on the expressive cut: [ShortNavigationBar] pads the
        // gesture inset INSIDE itself off the same container token the
        // baseline bar reads (NavigationBarTokens.ContainerHeight, 64dp), so
        // the ruled height and the stock M3 footing both survive the swap. The
        // content box above consumes the navigation-bar inset (see its
        // consumeWindowInsets, which now reads the short bar's default so both
        // sides reference one value), so pages still stop at the bar's top
        // edge; at large font scales the bar grows the same way the default
        // always did.
        ShortNavigationBar {
            HomeTab.entries.forEach { tab ->
                ShortNavigationBarItem(
                    selected = tab == selected,
                    onClick = {
                        // Deliberately silent: a tab swap is not a press on
                        // content, and HyperOS's own tab bars do not tick.
                        onSelect(tab)
                    },
                    icon = {
                        // fill=0 at rest, fill=1 selected — the symbol's two
                        // cuts swap on selection, so the glyph itself marks
                        // the tab as much as the pill under it does.
                        Icon(
                            painter = painterResource(
                                if (tab == selected) tab.iconFilledRes else tab.iconRes
                            ),
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(tab.titleRes)) }
                )
            }
        }
    }
}

/**
 * The Miuix home frame — the same three tabs as [HomeShell]. What the foot
 * looks like is decided by the look and, under the Miuix look, by one switch:
 *
 *  - Miuix look, 悬浮底栏 off: the library's own docked [MiuixNavigationBar],
 *    frosted — the standard HyperOS bottom bar.
 *  - Miuix look, 悬浮底栏 on: the same bar lifted into a floating capsule,
 *    its glass the Example's textureBlur recipe over the recorded pages.
 *  - SoftGlass look: always floating, and the bar is MIUIX's glass navigation
 *    bar ([SoftGlassNavigationBar]) on this app's material — the look's
 *    defining chrome, so it is not a switch but a certainty.
 *
 * The skeleton mirrors [HomeShell] exactly, for the same reasons: the bar is a
 * plain sibling below the content box (each tab is itself a full
 * [top.yukonga.miuix.kmp.basic.Scaffold], which must not pad for a bar it
 * cannot see), and each tab composes under its own SaveableStateProvider key
 * so a page's scroll position survives a round trip to another tab.
 *
 * The docked bar's inset bookkeeping: the library bar pads itself with the
 * navigation-bar inset INSIDE its own surface (its background reaches the
 * screen edge), so the content box consumes that inset — the same
 * `consumeWindowInsets` pass [HomeShell] runs — and each tab's Scaffold reads
 * a zero bottom inset instead of padding a dead band of page background above
 * the bar. The floating frames keep the same consume: nothing reserves the
 * bar's height, so the page content scrolls under the glass.
 *
 * The tab glyphs are the library's own icon set (see [miuixIcon]) under every
 * floating chrome; selection is the chrome's own ink treatment, never a fill
 * swap between two cuts.
 *
 * Miuix-dressed looks only — never composed under the Material look.
 */
@Composable
fun MiuixHomeShell(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (HomeTab) -> Unit
) {
    val stateHolder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    MiuixScaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp)
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            val blurEnabled by AppearanceState.enableBlur
            val backdropSurface = MiuixTheme.colorScheme.surface
            val backdrop = rememberLayerBackdrop {
                drawRect(backdropSurface)
                drawContent()
            }
            val insetDp = with(density) {
                WindowInsets.navigationBars.getBottom(this).toDp()
            }
            val clearance = SoftGlassNavigationBarDefaults.Height +
                softGlassNavigationBarBottomMargin(insetDp) +
                FLOATING_BAR_GAP
            CompositionLocalProvider(LocalFloatingBarClearance provides clearance) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurEnabled) Modifier.layerBackdrop(backdrop) else Modifier)
                        .consumeWindowInsets(WindowInsets.navigationBars)
                ) {
                    AnimatedTabContent(
                        selected = selected,
                        onSwiped = onSelect,
                        stateHolder = stateHolder,
                        content = content
                    )
                }
            }
            SoftGlassNavigationBar(
                items = HomeTab.entries.map { tab ->
                    SoftGlassNavigationItem(
                        icon = tab.miuixIcon,
                        label = stringResource(tab.titleRes),
                    )
                },
                selectedIndex = selected.ordinal,
                onSelect = { onSelect(HomeTab.entries[it]) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = SOFTGLASS_BAR_SIDE_INSET),
                backdrop = if (blurEnabled) backdrop else null,
            )
        }
    }
}

/**
 * The air between the chrome's edge and the end of a page's content.
 */
private val FLOATING_BAR_GAP = 16.dp

/**
 * The air the SoftGlass bar keeps off each side of the screen — MIUIX's own
 * inset for its glass navigation bar (the library's doc and its example both
 * pass 24dp). The bar itself owns its foot margin (MIUIX's floating margin over
 * the navigation inset); only the sides are the page shell's business.
 */
private val SOFTGLASS_BAR_SIDE_INSET = 24.dp

/**
 * The tabs under whichever frame is in use, as a real pager — the reference's
 * container (KernelSU's MainScreen uses a HorizontalPager), and the reason its
 * tab switches never flash: pages are laid out adjacently and the swipe moves
 * the strip, so a switch never disposes one page before the next is drawn, and
 * a tap that lands mid-swipe simply re-aims the strip from wherever it is. The
 * old AnimatedContent slide did dispose the loser mid-transition, which is what
 * let a frame of nothing through on fast switches.
 *
 * Swiping the page itself switches tabs too, as the reference allows. Each tab
 * still composes under its own [SaveableStateProvider] key, and the pager keeps
 * every tab's neighbour composed, so a page's scroll position survives a round
 * trip either way.
 */
@Composable
private fun AnimatedTabContent(
    selected: HomeTab,
    onSwiped: (HomeTab) -> Unit,
    stateHolder: SaveableStateHolder,
    content: @Composable (HomeTab) -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = selected.ordinal,
        pageCount = { HomeTab.entries.size }
    )
    val scope = rememberCoroutineScope()
    // The example's MainPagerState, folded into effect-shaped code: a tap aims
    // the strip with the look's own navigation spring (Miuix's
    // [PagerState.springAnimateToPage] under Miuix/SoftGlass, or
    // `MaterialTheme.motionScheme.defaultSpatialSpec()` under Material) under a
    // cancelling job, and WHILE that runs the page→selection sync is gated off —
    // the strip slides THROUGH intermediate pages, and an ungated sync flips the
    // bar's selection as each one crosses (the icon flicker two-finger tapping
    // produced; the example gates it with `isNavigating` for exactly that).
    var isNavigating by remember { mutableStateOf(false) }
    val navJob = remember { mutableStateOf<Job?>(null) }
    // The tab a swipe has just reported upward, still waiting for `selected` to
    // catch up. The report is bookkeeping, not a navigation order: without this
    // record the selection echoing back would re-enter the effect below and
    // spring the strip the rest of the way the instant a drag crossed the page's
    // halfway mark — the strip leaping out from under a finger still on the
    // glass, the "swing and it commits" feel. The Example draws the same line:
    // syncPage only updates the selection; only a bar tap steers the pager.
    var swipeSyncedTab by remember { mutableStateOf<HomeTab?>(null) }
    // A tap: aim the strip at the tab — UNCONDITIONALLY (aiming at the page the
    // strip already heads to is filtered only by the navigating guard below; a
    // mid-flight aim at the SAME target must not restart the spring, or every
    // re-tap would kick the animation and the strip would never settle).
    LaunchedEffect(selected) {
        // The selection catching up to a swipe adopts where the finger put the
        // strip; the flingBehaviour owns whatever remains of the gesture.
        if (swipeSyncedTab == selected) {
            swipeSyncedTab = null
            return@LaunchedEffect
        }
        if (isNavigating && pagerState.targetPage == selected.ordinal) return@LaunchedEffect
        navJob.value?.cancel()
        isNavigating = true
        // A bar tap owns the strip from here: any swipe report still pending is
        // stale — its selection is being overridden, so it must not swallow a
        // later tap on the tab it named.
        swipeSyncedTab = null
        navJob.value = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(selected.ordinal)
            } finally {
                if (navJob.value == myJob) {
                    isNavigating = false
                    // The strip landed somewhere other than where the tap asked
                    // (a swipe took over mid-flight): adopt where it actually is.
                    if (pagerState.currentPage != selected.ordinal) {
                        onSwiped(HomeTab.entries[pagerState.currentPage])
                    }
                }
            }
        }
    }
    // A swipe: report the tab the strip crossed to — only when no tap-driven
    // animation owns the strip, and on [PagerState.currentPage] (the example's
    // syncPage), never on settledPage: the settled page trails the finger and
    // would double-report through intermediate pages.
    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && pagerState.currentPage != selected.ordinal) {
            val tab = HomeTab.entries[pagerState.currentPage]
            swipeSyncedTab = tab
            onSwiped(tab)
        }
    }
    val enablePageUserScroll by AppearanceState.enablePageUserScroll
    val pagerGestureMode by AppearanceState.pagerGestureMode
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = PagerNavigationSpringSpec,
    )
    val nativeConnection = PagerDefaults.pageNestedScrollConnection(
        pagerState,
        Orientation.Horizontal,
    )
    val crossAxis = pagerGestureMode == ThemePrefs.PAGER_GESTURE_CROSS_AXIS
    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .pagerGestureOverride(
                pagerState = pagerState,
                mode = pagerGestureMode,
                enabled = enablePageUserScroll,
            ),
        userScrollEnabled = enablePageUserScroll && !crossAxis,
        // Keeps every tab's neighbour composed (three tabs, one neighbour each),
        // so a swipe starts on an already-built page.
        beyondViewportPageCount = 1,
        // Match the tap animation's spring on each look so tap and swipe settle
        // with one physical feel.
        flingBehavior = flingBehavior,
        verticalAlignment = Alignment.Top,
        pageNestedScrollConnection = if (crossAxis) {
            PagerGestureNestedScrollConnection
        } else {
            nativeConnection
        },
    ) { page ->
        val tab = HomeTab.entries[page]
        stateHolder.SaveableStateProvider(tab) {
            content(tab)
        }
    }
}
