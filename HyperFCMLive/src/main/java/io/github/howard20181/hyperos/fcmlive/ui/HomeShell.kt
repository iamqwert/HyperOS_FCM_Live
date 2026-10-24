package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.AppearanceState
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import io.github.howard20181.hyperos.fcmlive.ui.softglass.MiuixFloatingTabBar
import io.github.howard20181.hyperos.fcmlive.ui.softglass.MiuixFloatingTabBarDefaults
import io.github.howard20181.hyperos.fcmlive.ui.softglass.MiuixFloatingTabItem
import io.github.howard20181.hyperos.fcmlive.ui.softglass.MiuixFloatingTabLayout
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
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
     * [io.github.howard20181.hyperos.fcmlive.records.RecordsScreen]). Sits
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
                AnimatedTabContent(
                    selected = selected,
                    onSwiped = onSelect,
                    stateHolder = stateHolder,
                    content = content
                )
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
 *  - SoftGlass look: always floating, and the bar is the reference project's
 *    own glass tab bar ([MiuixFloatingTabBar]) — the look's defining chrome,
 *    so it is not a switch but a certainty.
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
    // The chrome glass itself is this look's default dressing (the Example
    // ships it enabled, so there is no row to turn it off).
    val glass = true
    val floatingBar by AppearanceState.floatingBar
    val softGlassStyle = LocalUiStyle.current == UiStyle.SoftGlass
    // SoftGlass floats by definition; the Miuix look floats when its row says so.
    val floating = softGlassStyle || floatingBar
    // A popup's veil IS part of the recorded layer here — the pages host their
    // own popups, so the scrim lands inside the record — and the Example is
    // fine with exactly that: its bar with a dropdown open still shows blurred,
    // dimmed page content through the glass. Glass over a veiled record reads
    // as dimmed glass, which is the correct look; the old forceOpaque fallback
    // (paint the bar solid while a popup is up) was the "blur turns off" bug.
    val stateHolder = rememberSaveableStateHolder()

    // The three tabs, once: both docked frames carry the same list, so it is
    // written once and handed to whichever of them is composed.
    val barItems: @Composable RowScope.() -> Unit = {
        HomeTab.entries.forEach { tab ->
            MiuixNavigationBarItem(
                selected = tab == selected,
                onClick = {
                    // Deliberately silent, exactly like the M3 bar: a tab swap
                    // is not a press on content, and HyperOS's own tab bars do
                    // not tick.
                    onSelect(tab)
                },
                icon = tab.miuixIcon,
                label = stringResource(tab.titleRes)
            )
        }
    }

    // ONE shell for all four switch combinations. The switches pick how each
    // chrome is PAINTED, never which composables exist, for two reasons that are
    // not cosmetic:
    //
    //  - Identity. A branch per combination meant toggling 模糊 tore down the
    //    tab subtree and built a new one, so the page being looked at — and the
    //    settings list's scroll position with it — went back to the top, while a
    //    switch that only repaints (莫奈颜色) left it where it was. Same page,
    //    same state, one structure.
    //  - Coverage. Every chrome that reacts to a popup's veil, or to the pill's
    //    slide, must be told about it. One frame that forks per switch is one
    //    more chance to wire a signal into the bar that happened to be on screen.
    //
    // So the content box, the tab frame and the chrome slot are all written once
    // below, and the switches only decide four things inline: whether the chrome
    // is docked or floating, whether it frosts, whether the pill wears liquid
    // glass, and what clearance the pages are handed.
    //
    // The chrome is a sibling INSIDE the frame rather than a reserved row outside
    // it, which is what lets a page run under it (see the clearance note below).
    // The Miuix page scaffolds inside would otherwise pad for the chrome twice.
    val density = LocalDensity.current
    MiuixScaffold(
        modifier = modifier.fillMaxSize(),
        // Nothing of its own: every page paints itself (see the page scaffolds'
        // own container colour), and a scaffold surface here would sit between
        // the page and the glass the pill samples.
        containerColor = Color.Transparent,
        // Insets are the pages' business, and each page scaffold handles its own
        // — the library's default padding here would stack a second one on top.
        contentWindowInsets = WindowInsets(0.dp)
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            // KernelSU's recording recipe: the layer starts from the surface
            // colour, THEN the content. Without the fill, anything the pages
            // leave unpainted records as transparent — and a tab swap that
            // interrupts another swap disposes a page mid-slide, leaving exactly
            // that kind of hole for a frame. Transparent records read as BLACK
            // through the pill's lens and tint, which is the rectangle that
            // showed up on fast switches. (The colour is read above: the record
            // lambda is not a composable context.)
            val backdropSurface = MiuixTheme.colorScheme.surface
            val backdrop = rememberLayerBackdrop {
                drawRect(backdropSurface)
                drawContent()
            }
            var barHeight by remember { mutableStateOf(0.dp) }
            // The pill's own space at the foot: the official FloatingNavigationBar
            // pads itself 26dp above the navigation-bar inset (36dp when there is
            // no inset to ride on), and the content box consumed that inset — so
            // the air the pill actually keeps at the foot is recomputed here with
            // the same formula for the pages' clearance.
            val insetDp = with(density) {
                WindowInsets.navigationBars.getBottom(this).toDp()
            }
            val pillBottomAir = if (insetDp != 0.dp) 26.dp + insetDp else 36.dp
            // The docked pane: 模糊 on, no floating bar. It stays mounted while
            // a popup is open — glass over the veiled record is the correct
            // dimmed look, and a detach here rebuilds the bar (the old flicker).
            val paneActive = !floating && glass
            // What the chrome takes at the foot of the screen: its own extent plus
            // a little air, handed to the pages as inset (see
            // [LocalFloatingBarClearance]) so the end of a list comes to rest
            // above the chrome instead of behind it or against it. Deliberately
            // NOT reserved on the content box: the page must run under the chrome
            // or its glass has nothing to sample. The air is [FLOATING_BAR_GAP],
            // the same 16dp the app list keeps between its search box and its
            // first row — one value for the gap between chrome and content
            // everywhere, rather than one per page. The extent itself is per
            // frame: the SoftGlass bar is its 54dp height plus the reference
            // layout's paddings (12 above, 4 below) riding on the navigation
            // inset; the Miuix capsule is its 52dp minimum plus the air the
            // official pill keeps; the docked pane is measured off its wrapper.
            val clearance = when {
                softGlassStyle ->
                    MiuixFloatingTabBarDefaults.Height + 12.dp + 4.dp + insetDp + FLOATING_BAR_GAP
                floating ->
                    FLOATING_BAR_MIN_HEIGHT + pillBottomAir + FLOATING_BAR_GAP
                else ->
                    barHeight + FLOATING_BAR_GAP
            }
            CompositionLocalProvider(LocalFloatingBarClearance provides clearance) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Only a frosted chrome reads a source layer back, so
                        // nothing is recorded for a blur that is not there — the
                        // switch costs the recording, not only the paint. A
                        // floating glass pill records even while a popup dims it
                        // (the pill stays glass under the veil; its surface just
                        // paints opaque), so the record is keyed on the master
                        // alone there.
                        .then(if ((floating && glass) || paneActive) Modifier.layerBackdrop(backdrop) else Modifier)
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
            // The docked bar, gated exactly like the Example's: it fades and
            // shrinks towards the top when the floating pill takes over, and
            // fades back in when the pill is switched off — the pill itself,
            // as in the Example, appears and disappears with no transition of
            // its own. The example's pane anatomy, line for line: the blur is
            // a Box WRAPPING the library bar (textureBlur + the bar colour on
            // the Box, the bar transparent under it), the Box consumes taps
            // with no indication so a touch on the bar's own surface never
            // falls through to the page, and the divider is the library's own
            // default — always drawn, riding on top of the pane too. That
            // hairline is the example's; dropping it under the pane was this
            // frame's own invention.
            AnimatedVisibility(
                visible = !floating,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        // Measured off the wrapper, not assumed: the library's
                        // height is its own business, and a hardcoded number
                        // would drift the moment it changed.
                        .onGloballyPositioned {
                            barHeight = with(density) { it.size.height.toDp() }
                        }
                        .then(
                            if (paneActive) {
                                // The example's exact blur for its docked bar:
                                // textureBlur at 25dp, the bar's own surface
                                // blended at 0.8, no rim highlight.
                                Modifier.textureBlur(
                                    backdrop = backdrop,
                                    shape = RectangleShape,
                                    blurRadius = DOCK_BLUR.value,
                                    colors = BlurColors(
                                        blendColors = listOf(
                                            BlendColorEntry(
                                                MiuixTheme.colorScheme.surface
                                                    .copy(alpha = DOCK_PANE_ALPHA)
                                            )
                                        )
                                    ),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .background(
                            if (paneActive) Color.Transparent else MiuixTheme.colorScheme.surface
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                ) {
                    MiuixNavigationBar(
                        // The library's own surface while there is no pane,
                        // absent under one: the pane above is the surface then.
                        // The glyphs keep their own content colours either way.
                        color = if (paneActive) Color.Transparent else MiuixTheme.colorScheme.surface,
                        content = barItems
                    )
                }
            }
            if (floating) {
                if (softGlassStyle) {
                    // The SoftGlass look's own foot: the reference project's
                    // glass tab bar, ported whole — its glass recipe, drag
                    // physics and stacked layout are that bar's business, and
                    // the bar reads the 柔光玻璃 spec through the locals the
                    // theme already provides. Placement is the reference's own:
                    // pinned to the bottom-center, lifted over the navigation
                    // inset, 16dp off the sides, 4dp off the inset.
                    MiuixFloatingTabBar(
                        items = HomeTab.entries.map { tab ->
                            MiuixFloatingTabItem(
                                key = tab.name,
                                label = stringResource(tab.titleRes),
                                // ONE glyph for both states, as the reference's
                                // items carry: selection is the bar's own ink
                                // and indicator treatment, not a fill swap.
                                selectedIcon = rememberVectorPainter(tab.miuixIcon),
                                unselectedIcon = rememberVectorPainter(tab.miuixIcon),
                            )
                        },
                        selectedKey = selected.name,
                        onItemSelected = { onSelect(HomeTab.valueOf(it.key)) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                        layout = MiuixFloatingTabLayout.Stacked,
                        backdrop = backdrop,
                    )
                } else {
                    // The Miuix look's floating capsule: the library's own
                    // FloatingNavigationBar wearing the Example's textureBlur
                    // recipe over the recorded pages — 25dp blur, the surface
                    // blended at 0.6, a glass-stroke rim keyed to the page tone.
                    // The paint is STRUCTURE-STABLE across popups: the glass
                    // never detaches and never goes opaque — a menu open just
                    // dims what the glass samples, the Example's exact behaviour.
                    //
                    // The example reads darkness straight off the system flag;
                    // this app can force a look the system does not, so the bar's
                    // own page tone decides — the same read the top-bar glass
                    // uses.
                    val pillIsDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
                    // Placement is ours through a wrapper Box: the component hands
                    // the caller's modifier to its inner pill Row, while its own
                    // outer fillMaxWidth Column is the shell Box's direct child and
                    // parks at TopStart unless pinned — an `align` inside the
                    // passed modifier never reaches that Column (it lands on the
                    // Row, which is not the Box's child, and is silently dropped —
                    // the "pill at the top-left" build). The wrapper owns the
                    // corner; the Row centers itself inside the component's
                    // full-width column, so the pill sits centered with its own
                    // natural margins.
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        FloatingNavigationBar(
                            modifier = Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(FLOATING_BAR_CORNER),
                                blurRadius = DOCK_BLUR.value,
                                colors = BlurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(
                                            MiuixTheme.colorScheme.surfaceContainer
                                                .copy(alpha = 0.6f)
                                        )
                                    )
                                ),
                                highlight = if (pillIsDark) {
                                    Highlight.GlassStrokeMiddleDark
                                } else {
                                    Highlight.GlassStrokeMiddleLight
                                },
                            ),
                            // The capsule's own surface is absent: the glass
                            // above it is the surface now.
                            color = Color.Transparent,
                            showDivider = false,
                        ) {
                            HomeTab.entries.forEach { tab ->
                                // Mishka's floating-bar item, verbatim: the
                                // capsule's own item language with the label
                                // under the glyph — 22dp icon + 11sp word in one
                                // shared ink (selection stays a tint question,
                                // never a fill swap), 48dp minimum plate,
                                // selectable() with no indication. The library's
                                // FloatingNavigationBarItem drops the label on
                                // the floor (contentDescription only), so the
                                // text can only come from owning the item.
                                PillTabItem(
                                    label = stringResource(tab.titleRes),
                                    icon = tab.miuixIcon,
                                    selected = tab == selected,
                                    showLabel = true,
                                    onClick = {
                                        // Deliberately silent, exactly like both
                                        // docked bars: a tab swap is not a press
                                        // on content.
                                        onSelect(tab)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The air between the chrome's edge and the end of a page's content, for both
 * frames: the pill's top edge and the docked bar's top edge alike. It is also
 * the gap the app list keeps under its search box, which is where the number
 * comes from — the page's own bottom air should read the same as its top air
 * rather than being a second, unrelated constant.
 */
private val FLOATING_BAR_GAP = 16.dp

/**
 * One tab of the Default pill, carrying its label — [Mishka]'s
 * `MiuixFloatingNavigationBarItem`, lifted whole: a centered column on a
 * 48dp-minimum plate (56 wide with a label, 48 without), the glyph at 22dp and
 * the word at 11sp sharing ONE ink, `selectable` under the Tab role with no
 * indication (the pill row's own layout is the feedback surface; a ripple
 * here would print a rectangle over the glass). The ink is
 * `onSurfaceContainer` at three strengths — resting 0.6, selected 1.0,
 * pressed 0.7 selected / 0.5 not — so selection stays a tint question, never
 * a fill swap, the look's own semantics.
 *
 * [showLabel] rides the same gate Mishka's `BottomBarMode` drives; this shell
 * passes `true` — the labels are why this item exists. When a caller ever
 * wants icons only, the glyph's `contentDescription` takes the label back.
 */
@Composable
private fun PillTabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> onSurfaceContainerColor.copy(alpha = if (selected) 0.7f else 0.5f)
        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = 0.6f)
    }

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = if (showLabel) 56.dp else 48.dp, minHeight = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(horizontal = if (showLabel) 8.dp else 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            modifier = Modifier.size(22.dp),
            imageVector = icon,
            contentDescription = if (showLabel) null else label,
            tint = tint,
        )
        if (showLabel) {
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                // The ambient style's line height is built for 14-16sp body text;
                // at 11sp its leading pads a wedge of air between the icon and
                // the word. Pin the box to the glyphs so the word sits directly
                // under the glyph — the gap that remains is the vector's own
                // bottom padding, which is the reference's gap too.
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The official pill's resting height: the FloatingNavigationBar Row declares a
 * 52dp minimum and its icon-only items never exceed it.
 */
private val FLOATING_BAR_MIN_HEIGHT = 52.dp

/** The pill's corner: the component's own default, restated for the glass shape. */
private val FLOATING_BAR_CORNER = 50.dp

/** The docked glass bar's blur, in dp — the example's radius for its docked bar. */
private val DOCK_BLUR = 25.dp

/**
 * How much of its own surface colour the docked glass bar keeps — the example's
 * blend for its blurred docked bar. Under one, so the blurred content reads
 * through it; high enough that labels riding the bar stay legible.
 */
private const val DOCK_PANE_ALPHA = 0.8f

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
    // the strip with the library's own [PagerState.springAnimateToPage] under a
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
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // Keeps every tab's neighbour composed (three tabs, one neighbour each),
        // so a swipe starts on an already-built page.
        beyondViewportPageCount = 1,
        // The example's fling: the same navigation spring snaps a thrown swipe
        // that animates a tap, so both roads land with one feel.
        flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            snapAnimationSpec = PagerNavigationSpringSpec,
        ),
        verticalAlignment = Alignment.Top,
        pageNestedScrollConnection = PagerDefaults.pageNestedScrollConnection(
            pagerState,
            Orientation.Horizontal
        ),
    ) { page ->
        val tab = HomeTab.entries[page]
        stateHolder.SaveableStateProvider(tab) {
            content(tab)
        }
    }
}
