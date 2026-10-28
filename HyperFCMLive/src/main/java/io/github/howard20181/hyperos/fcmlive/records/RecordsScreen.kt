@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    // The tooltip wrapper's default position is an experimental M3 type.
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    // The Material look's pull indicator is the M3 Expressive contained one
    // (ContainedLoadingIndicator) — the same spinner the apps page pulls, only
    // seated so it pushes the content down instead of floating over it.
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package io.github.howard20181.hyperos.fcmlive.records

import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.howard20181.hyperos.fcmlive.FcmAppScan
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.ui.AppDialog
import io.github.howard20181.hyperos.fcmlive.ui.GroupRow
import io.github.howard20181.hyperos.fcmlive.ui.GROUP_ROW_GAP
import io.github.howard20181.hyperos.fcmlive.ui.LongPressTooltip
import io.github.howard20181.hyperos.fcmlive.ui.NavCard
import io.github.howard20181.hyperos.fcmlive.ui.PageScaffold
import io.github.howard20181.hyperos.fcmlive.ui.SectionTitle
import io.github.howard20181.hyperos.fcmlive.ui.SectionTitleText
import io.github.howard20181.hyperos.fcmlive.ui.SettingsGroup
import io.github.howard20181.hyperos.fcmlive.ui.softglass.MiuixCapsuleTabRow
import io.github.howard20181.hyperos.fcmlive.ui.connectedGroupShape
import io.github.howard20181.hyperos.fcmlive.ui.androidVersion
import io.github.howard20181.hyperos.fcmlive.ui.deviceName
import io.github.howard20181.hyperos.fcmlive.ui.osVersion
import io.github.howard20181.hyperos.fcmlive.ui.listScrollEndHaptic
import io.github.howard20181.hyperos.fcmlive.ui.pageHorizontalInset
import io.github.howard20181.hyperos.fcmlive.ui.TopBarIconButton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.PullToRefresh as MiuixPullToRefresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The page's two levels: the structured status, and the events reader. */
private enum class PageTab { Status, Events }

private val PageTab.labelRes: Int
    get() = when (this) {
        PageTab.Status -> R.string.records_page_status
        PageTab.Events -> R.string.records_page_events
    }

/**
 * The glyph the M3 look leads each page tab with — a Material Symbols `fill1`
 * drawable, per the icon red line at the top of theme/ComposeTokens.kt.
 *
 * `insights` marks 状态 (the page reads GCM's structured state out of a dump)
 * and `receipt_long` marks 事件 (the tab is a log reader). Both are the symbol
 * set's own names for those ideas, not look-alikes, and both are drawn
 * decoratively — the label beside them already names the tab, so the icon
 * carries no description of its own (see [RecordsLeadingIconTab]).
 */
private val PageTab.symbolRes: Int
    get() = when (this) {
        PageTab.Status -> R.drawable.ic_insights
        PageTab.Events -> R.drawable.ic_receipt_long
    }

/**
 * The 「记录」 tab: GCM's structured state and its event stream, read without
 * opening FCM Diagnostics.
 *
 * The page is deliberately split in two:
 * - 状态 holds the structured configuration (connection, network, heartbeat,
 *   push queue, reconnect policy). It never shows events.
 * - 事件 is the FCM Diagnostics Events reader. It owns a second tab strip
 *   (全部/连接/心跳/推送/休眠/异常) and shows nothing but events; no status
 *   content leaks into it.
 *
 * The 事件 list is a [LazyColumn]; 状态 stays a plain scroll Column (a dozen
 * rows). The list used to be a plain Column too, on the theory that the cap
 * is cheap — measurement said otherwise: at the then-cap of 500 a category switch
 * blocked for 411–1036 ms composing rows the viewport never showed (~12 fit on
 * screen), against 14 ms for a 10-row category. Cost tracked row count, not
 * archive content, so the fix was windowing rather than a shorter history.
 * The lazy recycling crash this page once hit
 * (`measure is called on a deactivated node`) came from `GroupRow` /
 * `MiuixCardSurface` rows carrying a `textureBlur` layer; [EventCard] is a
 * flat Surface for that reason, and the full 全部 list was scrolled to its end
 * on device after the switch without a failure.
 * Both tab strips sit outside the scrolling content — under the Miuix looks
 * inside the bar's glass pane ([PageScaffold.barExtension], which also puts
 * the page on the settings page's blur), under Material in the page column.
 * That pane is the MIUIX Gaussian band on BOTH Miuix-dressed looks here: the
 * strips are its foot, and 柔光玻璃's own progressive fade is clear at its foot,
 * so this page turns the fade off ([PageScaffold.barProgressive]) rather than
 * pin navigation onto a window of sharp rows. Everything else the 柔光玻璃 look
 * draws on this page — the bar's glass control, the surfaces below — is
 * untouched.
 *
 * Under the Miuix looks (MIUIX and 柔光玻璃) both levels run the library's
 * canonical TabRow + HorizontalPager pairing — the shape its own example
 * ships: `selectedTabIndex` reads `pagerState.currentPage` so the indicator
 * rides the swipe, a tab tap springs to the page through
 * [springAnimateToPage], and the fling snaps with [PagerNavigationSpringSpec].
 * Only the *strip* differs inside those looks — and only from Material: both
 * Miuix-dressed looks wear the capsule ([MiuixCapsuleTabRow]) where the Material
 * look keeps M3's tab rows, leading-icon tabs on top
 * ([RecordsLeadingIconTab]) and icon-only tabs below ([RecordsIconTab]), so
 * the pager wiring is identical and the strip's construction is the whole
 * difference between the two families.
 * A pager page leaving the viewport is a whole-page disposal, not lazy-node
 * reuse, so the non-lazy rule still holds per page — and every page keeps a
 * hoisted ScrollState, so a disposed page returns where it left. The Material
 * look keeps the plain `when` switch and the shared-scroll reset.
 *
 * The dump itself is produced by GCM and answered by the module's
 * `system_server` half ([RecordsQuery] → RecordsBridge), so the page does not
 * need root — and has no root path at all: the module being active is the very
 * thing this app exists for, and a dead module leaves nothing worth recording.
 * While inactive the page shows the locked card plus whatever the local
 * archive ([RecordsStore]) kept from the last active period.
 */
@Composable
fun RecordsScreen(
    /** True while this tab is the one on screen; polling is gated on it. */
    active: Boolean,
    /** True while the module is bound to LSPosed in this process. */
    moduleActive: Boolean,
    /** The inactive card's tap target — the module status tab. */
    onOpenStatus: () -> Unit,
    /**
     * The LSPosed manager's framework version, the same string the 状态 page
     * lists. Only the export uses it: a report that omits the manager cannot be
     * matched against the LSPosed API differences the module depends on.
     */
    managerVersion: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var archive by remember { mutableStateOf(RecordsStore.load(context)) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pageTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var eventTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var exportText by remember { mutableStateOf("") }
    var exportProblem by remember { mutableStateOf(false) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    // The 状态 page's own readers (`DeviceInfo.kt`), called once per
    // composition: prop lookups, no permission, no root — and by construction
    // the same values the status page shows.
    val env = remember(managerVersion) {
        DiagnosticsEnv(
            device = deviceName(),
            osVersion = osVersion(),
            androidVersion = androidVersion(),
            managerVersion = managerVersion
        )
    }
    // SAF, the same path the allowlist export uses: the user picks the file,
    // and the page never writes outside a granted URI. The text is built on the
    // click, not in this lambda — a report assembled after the picker returns
    // would describe a page the reader may have left.
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            exportProblem = !runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(exportText.toByteArray())
                }
            }.isSuccess
        }
    }
    val pageTab = PageTab.entries[pageTabIndex.coerceIn(PageTab.entries.indices)]
    val selectedEventTab =
        EventTab.entries[eventTabIndex.coerceIn(EventTab.entries.indices)]

    // Each tab keeps its own scroll position across a round trip: the status
    // tab is a plain Column (a dozen rows), the events tab a LazyColumn.
    val statusScroll = rememberScrollState()

    // The bar's glass, read from the page the reader is actually looking at
    // rather than from the bar's own collapse fraction. The fraction is one
    // accumulated nested-scroll reading of the ONE connection the body box
    // wears, and this body is two page tabs over six category lists: whichever
    // page last moved it owns it, so a tab switch inherits the other tab's
    // frost — full glass over a list sitting at its top, or a clear pane with
    // rows already under it. Each page therefore reports its own travel and
    // the page picks the reading belonging to the tab on screen.
    var statusGlass by remember { mutableFloatStateOf(0f) }
    // One entry per event category, so the bar always shows the reading of the
    // category on screen: a single shared number would be whichever category
    // spoke last, and the spring composes the incoming category while the
    // outgoing one is still the one being reported.
    val eventsGlass = remember { mutableStateMapOf<Int, Float>() }
    // One ramp for both pages, px because both readings are px.
    val glassRampPx = with(LocalDensity.current) { GLASS_RAMP.toPx() }
    // A pager's own reading, blended across the pages it is showing. While a
    // spring is in flight two pages are on screen at once — one leaving, one
    // arriving — and picking either one alone flips mid-flight, which the eye
    // reads as the glass suddenly changing over a page that has not moved.
    // Each page's reading therefore counts for exactly the share of it that is
    // on screen, which is 1 for the settled page and a cross-fade in between.
    val blendOver: (PagerState, (Int) -> Float) -> Float = { state, reading ->
        val info = state.layoutInfo
        val size = info.pageSize
        if (size <= 0 || info.visiblePagesInfo.isEmpty()) {
            reading(state.currentPage)
        } else {
            var sum = 0f
            var weight = 0f
            info.visiblePagesInfo.forEach { page ->
                val shown = (size - abs(page.offset)).coerceIn(0, size)
                val share = shown.toFloat() / size
                sum += share * reading(page.index)
                weight += share
            }
            if (weight == 0f) reading(state.currentPage) else sum / weight
        }
    }
    // The status page is a hoisted [ScrollState], so its travel is readable
    // right here — including the restored offset it comes back with, which is
    // exactly the value a scroll-event-based reading never sees.
    LaunchedEffect(statusScroll, glassRampPx) {
        snapshotFlow { statusScroll.value }.collect { scrolled ->
            statusGlass = (scrolled / glassRampPx).coerceIn(0f, 1f)
        }
    }

    // The Miuix looks' pager wiring, hoisted to the screen on purpose: the two
    // tab strips ride INSIDE the bar's glass pane (PageScaffold's barExtension),
    // and their selected indexes read these pagers — so the states have to
    // outlive the body they used to live in. The Material look ignores them.
    val pagerScope = rememberCoroutineScope()
    val pagePagerState = rememberPagerState(pageCount = { PageTab.entries.size })
    val eventPagerState = rememberPagerState(pageCount = { EventTab.entries.size })
    val pageFling = PagerDefaults.flingBehavior(
        state = pagePagerState,
        snapAnimationSpec = PagerNavigationSpringSpec
    )
    val eventFling = PagerDefaults.flingBehavior(
        state = eventPagerState,
        snapAnimationSpec = PagerNavigationSpringSpec
    )
    val inset = pageHorizontalInset()

    val noSource = !moduleActive

    // The pull indicator's own level, and deliberately not [loading]: `loading`
    // is also raised by the once-a-minute poll, and a spinner appearing on its
    // own would read as a refresh nobody asked for. Only a pull raises this, and
    // every way out of the read below lowers it again — including the two early
    // returns, or a pull on a stack with no module would spin for ever.
    var refreshing by remember { mutableStateOf(false) }

    // When the pull started. A read is fast — the dump parse can come back in
    // well under a hundred milliseconds — and an indicator that appears and
    // vanishes inside a frame reads as a flicker, not as "done". The read below
    // holds [refreshing] for [MIN_REFRESH_VISIBLE_MS] from this stamp, so one
    // pull always shows one complete beat of the indicator.
    var pulledAt by remember { mutableLongStateOf(0L) }

    // One action, one path — the pull is the page's only way to ask for a fresh
    // read now: bump the key, which restarts the read below from its first line
    // (local archive, then the dump), and raise the level so the indicator
    // stands while it runs.
    val pullToRefresh: () -> Unit = {
        if (!refreshing) {
            loading = true
            refreshing = true
            pulledAt = SystemClock.uptimeMillis()
            refreshKey++
        }
    }

    LaunchedEffect(active, moduleActive, refreshKey) {
        if (!active) {
            refreshing = false
            return@LaunchedEffect
        }
        archive = withContext(Dispatchers.IO) { RecordsStore.load(context) }
        problem = null
        if (noSource) {
            loading = false
            refreshing = false
            return@LaunchedEffect
        }
        loading = true
        while (isActive) {
            when (val outcome = withContext(Dispatchers.IO) { read(context) }) {
                is Outcome.Ok -> {
                    archive = outcome.archive
                    problem = null
                }
                Outcome.Unparsable -> problem = R.string.records_error_parse
                Outcome.NoModule -> problem = if (moduleActive) {
                    R.string.records_module_unavailable
                } else {
                    R.string.records_module_off
                }
            }
            loading = false
            // One read is one refresh: the indicator retracts on its own here,
            // and the following passes are the poll's (which never raised it).
            // The hold only pads a fast read up to the beat below; a slow one
            // has already spent it and falls through with no wait at all.
            val stand = MIN_REFRESH_VISIBLE_MS - (SystemClock.uptimeMillis() - pulledAt)
            if (stand > 0) delay(stand)
            refreshing = false
            delay(POLL_INTERVAL_MS)
        }
    }

    PageScaffold(
        titleRes = R.string.nav_records,
        onBack = {},
        showBack = false,
        modifier = modifier,
        actions = {
            // 搜索：难以完善，不再需要。A sheet over a pager that disposes its
            // pages had to filter text rewritten per look and per locale, so
            // what it found changed with the look; the export below is the
            // "hand me the evidence" path that stayed. Do not re-add without
            // solving that first.
            //
            // No 刷新 button: the page refreshes by pulling, on both looks (see
            // the pull seat in the body below). The button and the pull do the
            // same thing — read the dump now — and a bar action that duplicates
            // the page's own gesture is one affordance too many.
            if (LocalUiStyle.current.isMiuixBased) {
                // The bar action every other Miuix screen wears: [TopBarIconButton],
                // the style's button (the 柔光玻璃 glass disc included) with the
                // bar's onSurface ink. A material3 icon here was the odd one out:
                // a Material glyph in a Miuix bar, tinted a caption grey.
                TopBarIconButton(
                    painter = painterResource(R.drawable.ic_upload),
                    description = stringResource(R.string.records_export),
                    onClick = {
                        exportText = diagnosticsReport(
                            archive = archive,
                            versionName = versionName,
                            exportedAt = System.currentTimeMillis(),
                            env = env
                        )
                        createDoc.launch(exportFileName())
                    }
                )
            } else {
                // The Material look keeps the same long-press bubble every other
                // page's bar icons wear ([LongPressTooltip] is a no-op under
                // Miuix); this was the only bar control without one.
                LongPressTooltip(label = stringResource(R.string.records_export)) {
                    IconButton(onClick = {
                        exportText = diagnosticsReport(
                            archive = archive,
                            versionName = versionName,
                            exportedAt = System.currentTimeMillis(),
                            env = env
                        )
                        createDoc.launch(exportFileName())
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_upload),
                            contentDescription = stringResource(R.string.records_export),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        // The Miuix looks' two tab strips, pinned inside the bar's glass pane.
        // This is what puts the page on the settings page's blur: the strips are
        // chrome, not content, so they ride the collapsing bar like the apps
        // page's search capsule — and the pane covers the whole column, bar and
        // strips. The events strip joins only while its page is
        // the target, expanding and collapsing with the dock's own
        // AnimatedVisibility recipe so the pane's height glides instead of
        // jumping. The body below scrolls under the lot — which is the whole
        // point: rows travelling beneath the pane are what the blur reads.
        // This page's own reading of how far its visible content has travelled
        // under the pane — see the two numbers above. Handed in on both Miuix
        // looks and ignored by Material, which has no pane. It feeds the pane's
        // frost and the reveal ONLY: the bar itself is the standard large
        // title, collapsing on the nested scroll like every other page's
        // (compactBar stays false) — a page whose bar collapsed over its own
        // reading grew a small title that never came back, and a title whose
        // alpha was keyed to that reading vanished whenever the visible tab
        // sat at its top.
        barScrollProgress = {
            // The event page's reading is itself a blend: the six categories
            // slide horizontally under the same pane, and a category switch
            // flips `currentPage` mid-flight exactly as a page tab does.
            val events = blendOver(eventPagerState) { eventsGlass[it] ?: 0f }
            blendOver(pagePagerState) { page ->
                if (PageTab.entries.getOrNull(page) == PageTab.Events) events else statusGlass
            }
        },
        barExtension = if (LocalUiStyle.current.isMiuixBased) { {
            // `backdrop = null` on both strips: they sit ON the pane (the bar's
            // Gaussian band is the blur here), and the capsule's
            // SoftGlassSurface path — which samples [LocalSoftGlassBackdrop] —
            // would stack a second glass on glass. The apps page's pinned
            // search capsule runs the same rule: plain wash on the pane, the
            // refraction is the band's business.
            RecordsPageTabRow(
                selected = PageTab.entries[pagePagerState.currentPage],
                onSelect = { tab ->
                    pagerScope.launch { pagePagerState.springAnimateToPage(tab.ordinal) }
                },
                modifier = Modifier.padding(
                    start = inset, end = inset, bottom = STRIP_BREATH
                )
            )
            AnimatedVisibility(
                visible = PageTab.entries[pagePagerState.targetPage] == PageTab.Events,
                // Anchored to the bar: the strip reveals downward from under
                // it and folds back up into it — never a gap between the two.
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                EventsTabRow(
                    selected = EventTab.entries[eventPagerState.currentPage],
                    onSelect = { tab ->
                        pagerScope.launch { eventPagerState.springAnimateToPage(tab.ordinal) }
                    },
                    modifier = Modifier.padding(
                        start = inset, end = inset, bottom = STRIP_BREATH
                    )
                )
            }
        } } else null,
        // The strips ARE the pane's foot, and 柔光玻璃's pane fades to clear
        // exactly there — so under that look the strips and the rows sliding out
        // from under them stayed sharp, which read as "this page has no blur"
        // next to every MIUIX page. The page therefore asks for the MIUIX
        // Gaussian band: 25dp at a constant radius, page tone at 0.8, a hard
        // bottom edge — the very recipe the plain MIUIX look already puts here,
        // reused rather than re-derived (see HazeGlassBar's `progressive`). It
        // is the same pane, so the bar, both strips and the body under them are
        // one frost; nothing else about the look changes.
        barProgressive = false
    ) { innerPadding ->
        // MIUIX 系 (MIUIX / 柔光玻璃): the library example's canonical
        // TabRow + HorizontalPager pairing, both tab levels — with the strips
        // mounted in the bar's glass above (see barExtension) and the pages
        // padded by the pane's full extent so the content starts below them
        // and scrolls under them. The Material look keeps the when() switch
        // below — its PrimaryTabRow has no pager dance of its own.
        if (LocalUiStyle.current.isMiuixBased) {
            MiuixRecordsPagerBody(
                archive = archive,
                noSource = noSource,
                problem = problem,
                loading = loading,
                onOpenStatus = onOpenStatus,
                inset = inset,
                topPad = innerPadding.calculateTopPadding(),
                bottomPad = innerPadding.calculateBottomPadding(),
                pagePagerState = pagePagerState,
                eventPagerState = eventPagerState,
                pageFling = pageFling,
                eventFling = eventFling,
                statusScroll = statusScroll,
                refreshing = refreshing,
                onRefresh = pullToRefresh,
                // The events lists cannot be read from here — their states
                // belong to the pages, which is what keeps a disposed page
                // from handing a wedged scroll session back — so the travel
                // comes back up through this callback instead.
                onEventsScrolled = { page, travelled ->
                    eventsGlass[page] = (travelled / glassRampPx).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxSize()
            )
            return@PageScaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            RecordsPageTabRow(
                selected = pageTab,
                onSelect = { pageTabIndex = it.ordinal },
                modifier = Modifier.padding(start = inset, end = inset)
            )
            // The pull seat. Unlike the apps page — whose box begins at the
            // window's top edge and therefore drops its indicator by the bar's
            // inset — this box begins under the two strips, which are this
            // column's own children above it, so the indicator rests at the
            // box's own top and needs no offset. Clipped, because the column
            // draws the strips first: without the clip a half-risen indicator
            // would be painted OVER the strip it is supposed to slide out from
            // under (the apps page gets that layering for free — its bar is a
            // scaffold slot, drawn after the body).
            val pullState = rememberPullToRefreshState()
            // 阈值触感：拉过阈值的那一刻响一下，与系统自己的下拉刷新同拍。
            // 只在"越过"时响 —— snapshotFlow 本来就只在布尔值翻转时发射，所以
            // 原路退回阈值以内不会补一声，来回蹭阈值也不会响成打字机。
            val view = LocalView.current
            LaunchedEffect(pullState) {
                snapshotFlow { pullState.distanceFraction >= 1f }
                    .collect { armed ->
                        if (armed) {
                            view.performHapticFeedback(
                                HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                            )
                        }
                    }
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = pullToRefresh,
                state = pullState,
                // 官方默认 80dp 在这张页太浅：两次条紧贴内容，手指轻轻一划就见
                // 底，刷新几乎是被"蹭"出来的。120dp 让这一下拉真正需要用力，也
                // 让下面那颗指示器的 0→1 走得慢一点，不再一碰就整个蹦出来。
                threshold = PULL_INDICATOR_THRESHOLD,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
                indicator = {
                    M3PullIndicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            ) {
                // 内容不动。试过 m3e 规范那套"把内容整体推下去"（内容跟着
                // fraction 一起位移）—— 在这张页上是错的：两条 tab 条紧贴内容
                // 上沿、中间没有呼吸位，内容一下移就在条脚下裂出一道缝，看着像
                // 内容脱开了条，而不是像 indicator 把它顶开。指示器因此回到覆盖
                // 式（和应用页同一套），滑出时压住第一行 48dp，收手即退。
                when (pageTab) {
                    PageTab.Status -> StatusTab(
                        archive = archive,
                        noSource = noSource,
                        problem = problem,
                        loading = loading,
                        onOpenStatus = onOpenStatus,
                        inset = inset,
                        bottomPad = innerPadding.calculateBottomPadding(),
                        scrollState = statusScroll,
                        modifier = Modifier.fillMaxSize()
                    )
                    PageTab.Events -> EventsTab(
                        archive = archive,
                        selected = selectedEventTab,
                        onSelect = { eventTabIndex = it.ordinal },
                        inset = inset,
                        bottomPad = innerPadding.calculateBottomPadding(),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
    if (exportProblem) {
        AppDialog(
            onDismissRequest = { exportProblem = false },
            title = stringResource(R.string.records_export),
            text = stringResource(R.string.records_export_failed),
            confirmLabel = stringResource(R.string.dialog_close),
            onConfirm = { exportProblem = false }
        )
    }
}

/** 官方 contained 指示器的容器边长（`LoadingIndicatorDefaults.ContainerWidth`，48dp）。 */
private val PULL_INDICATOR_SIZE = 48.dp

/**
 * 触发刷新所需的下拉距离。官方 [PullToRefreshDefaults.PositionalThreshold] 是 80dp，
 * 在这张页偏浅 —— 条与内容之间没有余量，80dp 的手指位移已经划过两行卡片。120dp
 * 让下拉重新有"要拉到底"的手感，也顺带让指示器的 0→1 走得从容。
 */
private val PULL_INDICATOR_THRESHOLD = 120.dp

/**
 * 指示器落定后与箱顶（第一条 tab 条的下沿）之间的呼吸位。
 *
 * 箱顶正是第一条条的边线，指示器贴着它落定就像长在条上 —— 事件页那条分类条还
 * 紧贴其下，两颗圆直接叠在一起。取值等于一条分类条的高度（48dp），也就是指示器
 * 把自己整个"让"到条下面去：事件页的分类条能完整露在它上方，状态页则落在
 * 「连接」那一行上。比这更小会重新压住条，比这更大就盖住了首张卡片。
 */
private val PULL_INDICATOR_GAP = 48.dp

/**
 * 记录页 M3 那张下拉指示器：官方 M3E 的 contained 形变指示器（48dp / CornerFull /
 * primaryContainer 上 onPrimaryContainer），与 apps 页同一颗。与官方
 * `PullToRefreshDefaults.LoadingIndicator` 唯一的不同是落点：官方按
 * `fraction × 80dp − height` 一路滑到 40dp、把第一行压掉大半；这里滑到距箱顶
 * [PULL_INDICATOR_GAP] 才停住（`f × (边长 + 间距) − 边长`），正好让开下面那条
 * 分类条。
 *
 * 内容不跟它下推：这张页的两条 tab 条紧贴内容上沿，内容位移会在条脚下裂出缝，
 * 见调用处的说明。所以这里是覆盖式，位移只由
 * [PullToRefreshState.distanceFraction] 一个读数决定，逐帧不重组。
 */
@Composable
private fun M3PullIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier
) {
    val gapPx = with(LocalDensity.current) { PULL_INDICATOR_GAP.toPx() }
    Box(
        modifier = modifier
            .size(PULL_INDICATOR_SIZE)
            .graphicsLayer {
                val fraction = state.distanceFraction
                // f=0 时整颗停在箱顶之上（被 clip 掉，看不见），f=1 时落定在距
                // 箱顶 [PULL_INDICATOR_GAP] 处；越过阈值不再继续下移，改成整块
                // 旋转 —— 与官方那颗同规。
                translationY = fraction.coerceIn(0f, 1f) * (size.height + gapPx) - size.height
                rotationZ = if (fraction > 1f) -(fraction - 1f) * 180f else 0f
            }
    ) {
        // 两态的形变序列首尾相接：determinate 拉满是 SoftBurst，indeterminate
        // 的第一个形状也是 SoftBurst，所以这里刻意不带 Crossfade —— 切换的那一
        // 帧本来就同一形状，交叉淡入只会把它抹糊。刷新时是那颗七形状循环的
        // 不定式（SoftBurst → Cookie9 → Pentagon → Pill → Sunny → Cookie4 →
        // Oval，650ms 一个，弹簧 0.6/200）。
        if (isRefreshing) {
            ContainedLoadingIndicator()
        } else {
            ContainedLoadingIndicator(progress = { state.distanceFraction })
        }
    }
}

/** `hyperfcmlive-diagnostics-20261024-2230.txt` — sorts by time in any picker. */
private fun exportFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "hyperfcmlive-diagnostics-$stamp.txt"
}

/**
 * The top-level 状态/事件 strip.
 *
 * Both Miuix-dressed looks wear the capsule ([MiuixCapsuleTabRow]) and its
 * `selectedTabIndex` rides the page pager ([MiuixRecordsPagerBody]); the
 * Material look uses the M3 primary tab row of leading-icon tabs
 * ([RecordsLeadingIconTab]: `insights` before 状态, `receipt_long` before 事件).
 * Two tabs fill the width, so no scrolling is involved.
 *
 * Under the Miuix looks the strip is [PageScaffold]'s `barExtension` — pinned
 * inside the bar's glass pane, riding the collapsing bar the way the apps
 * page's search capsule does, with the page's blur recipe covering it. The
 * Material look keeps the strip in the page column: M3 has no glass to mount
 * it in, and its rows own their divider there.
 *
 * The capsule replaced the library's own row on both looks rather than only on
 * 柔光玻璃, because it is a layout and the two looks differ in material: the
 * library row drew a band with an outlined button per tab, which is not what
 * either look is after. 柔光玻璃 draws the glass over the plate whenever it has
 * a backdrop; plain MIUIX gets the same plate the reference shows at the top of
 * its page. The Material look never sees either — its row is M3's.
 */
@Composable
private fun RecordsPageTabRow(
    selected: PageTab,
    onSelect: (PageTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val labels = PageTab.entries.map { stringResource(it.labelRes) }
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixCapsuleTabRow(
            tabs = labels,
            selectedTabIndex = selected.ordinal,
            onTabSelected = { onSelect(PageTab.entries[it]) },
            // backdrop = null: under the Miuix looks this strip is mounted in
            // the bar's glass pane (PageScaffold.barExtension), where the pane
            // IS the blur — sampling the page layer here would stack a second
            // glass on glass, and the page layer's record covers this strip
            // too (it lives in the recorded body) — glass sampling itself.
            backdrop = null,
            modifier = modifier
        )
        return
    }
    PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary
        // The divider stays the component's own default: M3 draws a
        // HorizontalDivider at the row's foot, which is the separation between
        // this row and the secondary strip below it. The explicit
        // `divider = {}` here used to strip that away, leaving the two rows to
        // melt into one band.
    ) {
        PageTab.entries.forEach { tab ->
            RecordsLeadingIconTab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                symbolRes = tab.symbolRes,
                labelRes = tab.labelRes
            )
        }
    }
}

/**
 * The records page under the Miuix looks (MIUIX and 柔光玻璃), wearing the
 * library example's canonical TabRow + HorizontalPager pairing, with one
 * house rule on top: the pagers are gesture-free. The home's own left/right
 * swipe between the bottom-nav tabs is the only horizontal gesture on this
 * page — an inner swipe-driven pager raced it for every drag and the page
 * flipped wherever the gesture landed — so both pagers here run
 * `userScrollEnabled = false` and switch through tab taps alone
 * ([springAnimateToPage] is programmatic, so the spring and the strip's
 * riding indicator are unaffected).
 *
 * The strips themselves live ABOVE this body — they are [PageScaffold]'s
 * `barExtension`, pinned inside the bar's glass pane like the apps page's
 * search capsule. That mount is what puts the page on the settings page's
 * blur: the pane (the MIUIX Gaussian band on both Miuix-dressed looks, see the
 * screen's own note on `barProgressive`) covers bar + strips, and the pages
 * below are padded by the pane's
 * full extent ([topPad]) while running the FULL height of the body area — so
 * a list scrolled up passes beneath the strips and the bar, and the blur has
 * travelling rows to read. Every page is a plain verticalScroll Column (the
 * class KDoc's no-lazy rule holds per pager page), composed on demand like
 * the example's own pagers — resident pages made this tab heavy enough to
 * jank the home's tab spring — and every scroll position is hoisted here, so
 * a disposed page returns where it left.
 *
 * The body's pull-to-refresh is the library's own ([MiuixPullToRefresh]) — the
 * circle-and-text indicator the apps page wears — wrapped around the PAGER
 * rather than around each page: both levels of pages are scrollables, so the
 * gesture arrives here as unconsumed nested scroll from whichever page is
 * showing, and one container serves 状态 and all six categories alike. The
 * indicator's anchor is the pane, handed in as [topPad] (the library offsets
 * its header by the box's `contentPadding` top and nothing else), so it rises
 * out from under the strips into the open body instead of behind the glass.
 */
@Composable
private fun MiuixRecordsPagerBody(
    archive: RecordsStore.Archive,
    noSource: Boolean,
    problem: Int?,
    loading: Boolean,
    onOpenStatus: () -> Unit,
    inset: androidx.compose.ui.unit.Dp,
    topPad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    pagePagerState: PagerState,
    eventPagerState: PagerState,
    pageFling: TargetedFlingBehavior,
    eventFling: TargetedFlingBehavior,
    statusScroll: ScrollState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    /**
     * The event list's travel, for the bar's glass — forwarded to
     * [EventsCategoriesPager], which tags it with the category that reported
     * it. See the screen's own note: the glass reads the visible page's
     * travel, and the event lists are the only scrollables whose state this
     * body does not hold — their states belong to their pages, which is what
     * keeps a disposed page from handing a wedged scroll session back.
     */
    onEventsScrolled: (page: Int, travelledPx: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // Hoisted above the pagers on purpose: a page is disposed when the reader
    // moves on, and re-deriving the taxonomy for every event on each category
    // entry was measurable. One map per archive, shared by all six categories.
    val eventClasses = remember(archive.events) {
        archive.events.associateWith { EventTaxonomy.classes(it) }
    }
    MiuixPullToRefresh(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        // The library's defaults are hard-coded English; the text is part of
        // the indicator, so it localises here — the same four strings the apps
        // page hands it.
        refreshTexts = listOf(
            stringResource(R.string.refresh_pull_down),
            stringResource(R.string.refresh_release),
            stringResource(R.string.refreshing),
            stringResource(R.string.refresh_done)
        ),
        // Top padding only, and only for the indicator: it anchors the header
        // just below the pane (bar + strips) instead of at the window's top
        // edge, behind the glass, where a pull would only grow it out of sight.
        // No padding on the content either side — the pager takes the box's
        // full height, and each page keeps its own distance from the chrome as
        // CONTENT padding ([topPad]), so rows travel under the glass.
        contentPadding = PaddingValues(top = topPad),
        modifier = modifier
    ) {
        HorizontalPager(
            state = pagePagerState,
            flingBehavior = pageFling,
            // Gesture-free pages: the home's own left/right swipe between the
            // bottom-nav tabs owns every horizontal drag on this page. An
            // inner swipe-driven pager stole that gesture — a drag sometimes
            // flipped the bottom tab, sometimes a records page, whichever
            // node caught it first — so the pager here answers taps only
            // ([springAnimateToPage] is programmatic and unaffected), and
            // drags fall through to the home untouched.
            userScrollEnabled = false,
            // Default on-demand composition, like every pager in the example.
            // A spell of resident pages (beyondViewportPageCount = all) made
            // this page heavy enough that the HOME tab spring — which composes
            // its neighbour pages — dropped frames building status plus all
            // six event categories mid-flight; the tap spring composing one
            // target page is the only cost left, the same one the example pays.
            //
            // Full size, not weight: this is the pull container's own Column,
            // not this function's (a weight would need a ColumnScope receiver
            // that is not in scope here). The header is 0dp tall at rest and
            // takes its height back from this pager while pulled, which is
            // exactly the shift the gesture wants.
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (PageTab.entries[page]) {
                PageTab.Status -> StatusTab(
                    archive = archive,
                    noSource = noSource,
                    problem = problem,
                    loading = loading,
                    onOpenStatus = onOpenStatus,
                    inset = inset,
                    topPad = topPad,
                    bottomPad = bottomPad,
                    scrollState = statusScroll,
                    modifier = Modifier.fillMaxSize()
                )
                PageTab.Events -> EventsCategoriesPager(
                    archive = archive,
                    classes = eventClasses,
                    pagerState = eventPagerState,
                    flingBehavior = eventFling,
                    inset = inset,
                    topPad = topPad,
                    bottomPad = bottomPad,
                    onPageScrolled = onEventsScrolled,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * The 事件 page's six-way category pager — the pairing's inner level, minus
 * its strip: the category strip rides INSIDE the bar's glass pane
 * ([PageScaffold.barExtension], next to the page strip), so the pager here is
 * the scrolling body alone and fills the whole page area, its pages padded by
 * the pane's full extent ([topPad]). Each category keeps its own
 * [LazyListState] (the [EventsContent] side owns it via `rememberSaveable`),
 * so re-entering a category resumes where it left. Hoisted pager state, as
 * before: a pager page is disposed on the way out, and the position has to
 * outlive it.
 */
@Composable
private fun EventsCategoriesPager(
    archive: RecordsStore.Archive,
    classes: Map<GcmEvent, EventClasses>,
    pagerState: PagerState,
    flingBehavior: TargetedFlingBehavior,
    inset: androidx.compose.ui.unit.Dp,
    topPad: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    /**
     * The visible list's travel, tagged with the category it belongs to. Every
     * composed category reports — each keeps its own saved position, and the
     * caller files the readings per category so the bar can pick the one on
     * screen. Gating this on the current page instead would drop the incoming
     * category's first word, which is its restored position spoken on attach,
     * before any gesture has happened.
     */
    onPageScrolled: (page: Int, travelledPx: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    HorizontalPager(
        state = pagerState,
        flingBehavior = flingBehavior,
        // Gesture-free like the outer pager: a horizontal drag must reach
        // the home's bottom-tab swipe, never flip categories mid-gesture.
        // Taps on the contour strip do the switching (spring).
        userScrollEnabled = false,
        // Default on-demand composition, like the example — resident pages
        // here were what janked the home's tab spring (see the outer pager).
        modifier = modifier.fillMaxWidth()
    ) { page ->
        EventsContent(
            archive = archive,
            selected = EventTab.entries[page],
            classes = classes,
            inset = inset,
            topPad = topPad,
            bottomPad = bottomPad,
            onScrolled = { travelled -> onPageScrolled(page, travelled) },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * One event-category page's scrollable body: the day-grouped stream and
 * nothing else — the old hint line above the list and footnote below it are
 * gone; the category tab's own label is explanation enough.
 *
 * A [LazyColumn], because the measurement was unambiguous: with the archive
 * full (500 rows back then) a plain scroll Column composed every row on every category
 * switch — 411–1036 ms per switch for ~12 visible rows, 173–520 ms at 246
 * rows, 14 ms at 10 — while the viewport holds about a dozen. The window is
 * now the only thing built, so the cost no longer tracks the archive size.
 *
 * The earlier crash this page kept hitting (`measure is called on a
 * deactivated node`) came from recycled `GroupRow`/`MiuixCardSurface` rows
 * carrying a `textureBlur` layer; [EventCard] is a flat Surface for exactly
 * that reason, and the 全部 list was scrolled end-to-end on device after this
 * switch with no such failure.
 */
@Composable
private fun EventsContent(
    archive: RecordsStore.Archive,
    selected: EventTab,
    classes: Map<GcmEvent, EventClasses>,
    inset: androidx.compose.ui.unit.Dp,
    /**
     * The distance the first row keeps from the chrome above. Under the Miuix
     * looks this is the glass pane's full extent — bar plus strips — handed in
     * as CONTENT padding, so scrolled rows travel under the pane (the blur's
     * whole diet) instead of the list being shortened by an outer padding.
     * The Material look scrolls beside its strips, not under them, and keeps
     * the default 0 (its column above pads).
     */
    topPad: androidx.compose.ui.unit.Dp = 0.dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    /**
     * How far the list has travelled from its own top, in px, reported so the
     * page's bar can keep its glass while rows sit under it. `0` is the top of
     * the list; anything at or past the caller's ramp reads as full.
     *
     * Needed because the glass's own reading (`collapsedFraction`) is an
     * accumulated nested-scroll value: a page that comes back already scrolled
     * — which is exactly what `rememberSaveable` above does for this list —
     * generates no scroll event, so the fraction is still 0 while the rows are
     * halfway up the screen, and a strength of 0 leaves the pane transparent.
     * Every category reports, tagged with its own index; the screen files the
     * readings and reads the one on screen (see [EventsCategoriesPager]).
     */
    onScrolled: (travelledPx: Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Owned by the page, not hoisted above the pager. A hoisted state survives
    // the page disposal the pager performs on every category change, and that is
    // exactly how this list once wedged: the scroll log caught
    // `detach … inProgress=true` — a page torn down mid-scroll — after which the
    // surviving state ignored every drag (taps still worked) until the process
    // was restarted. `rememberSaveable` keeps the reading position across the
    // disposal while giving each visit a fresh object, so a wedged scroll
    // session cannot come back.
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Report where the list sits, for the bar's glass floor. Keyed on the
    // state object, not on [onScrolled]: a saveable list hands back a NEW
    // object per visit, and re-subscribing is the point — the restored offset
    // is the first thing this reports, without any gesture having happened.
    LaunchedEffect(listState, onScrolled) {
        snapshotFlow {
            // Measured off the layout rather than off
            // `firstVisibleItemScrollOffset`: that reading's zero point moves
            // with the list's top content padding — which here is the glass
            // pane's whole extent — while the item's own offset against the
            // padding is the travel plain to read. Only the first item's
            // travel is measured exactly (the ramp is shorter than a row
            // anyway); anything past it is simply "past the ramp".
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            when {
                first == null -> 0
                first.index > 0 -> Int.MAX_VALUE
                else -> (info.beforeContentPadding - first.offset).coerceAtLeast(0)
            }
        }.collect { onScrolled(it) }
    }

    // [classes] arrives hoisted: a pager page is disposed on the way out, so a
    // remember on this side would re-derive the classification of every event
    // for each category entry. Filtering on the shared map is a lookup per row.
    val visibleEvents = remember(archive.events, selected, classes) {
        archive.events.filter { classes[it]?.inTab(selected) == true }
    }
    val days = remember(visibleEvents) { groupByDay(visibleEvents) }
    // How long each close row's connection had been up. Keyed on the whole
    // archive, not on [visibleEvents]: a close in the 连接 tab may have started
    // with a `Connected` row that the tab also holds, but the pairing has to
    // see the full timeline to stay honest across category filters.
    val uptimes = remember(archive.events) { EventTaxonomy.connectionUptimes(archive.events) }
    // Same idea for delivery: how long after its arrival each 回执 / 完成 row
    // happened, keyed on the message id GMS prints on all three stages.
    val arrivals = remember(archive.events) { EventTaxonomy.pushArrivals(archive.events) }
    // The collapsed row shows the app's display name — a package id is for the
    // expanded raw line, not for a first glance. Keyed on the whole archive,
    // not on the category: switching categories must not re-query
    // PackageManager for the same handful of packages.
    val context = LocalContext.current
    val appLabels = remember(archive.events) {
        val pm = context.packageManager
        archive.events.mapNotNull(::eventPackage).distinct().associateWith { pkg ->
            runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
    }
    val todayLabel = stringResource(R.string.records_day_today)
    val yesterdayLabel = stringResource(R.string.records_day_yesterday)

    // Scroll diagnostics, kept in the release build on purpose: this list has
    // twice wedged into a state where taps worked and drags did nothing, and it
    // cannot be reproduced on demand. Three cheap probes, all Debug level:
    //  - do touches reach the list at all (press / move / release counts);
    //  - every `isScrollInProgress` transition, with the position it happened at
    //    (a `true` that never returns to `false` is the smoking gun);
    //  - page attach / detach, including whether a detach landed mid-scroll —
    //    the pager disposes a page on every category change, and a state that
    //    survives that disposal is the other suspect.
    LaunchedEffect(listState, selected) {
        Log.d(SCROLL_LOG, "attach tab=$selected")
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            Log.d(
                SCROLL_LOG,
                "inProgress=$inProgress tab=$selected idx=${listState.firstVisibleItemIndex} " +
                    "off=${listState.firstVisibleItemScrollOffset} " +
                    "total=${listState.layoutInfo.totalItemsCount} " +
                    "fwd=${listState.canScrollForward} back=${listState.canScrollBackward}"
            )
        }
    }
    DisposableEffect(listState, selected) {
        onDispose {
            Log.d(
                SCROLL_LOG,
                "detach tab=$selected inProgress=${listState.isScrollInProgress} " +
                    "idx=${listState.firstVisibleItemIndex}"
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(selected) {
                awaitPointerEventScope {
                    var moves = 0
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Press -> {
                                moves = 0
                                Log.d(SCROLL_LOG, "press tab=$selected")
                            }
                            PointerEventType.Move -> moves++
                            PointerEventType.Release -> Log.d(
                                SCROLL_LOG,
                                "release tab=$selected moves=$moves " +
                                    "idx=${listState.firstVisibleItemIndex} " +
                                    "off=${listState.firstVisibleItemScrollOffset}"
                            )
                            else -> Unit
                        }
                    }
                }
            }
            .listScrollEndHaptic(),
        // Inset as content padding rather than a modifier padding: the rows
        // still line up with the tab strips, and the scroll area itself keeps
        // the full page width, so the edge haptic fires at the real edges.
        // Under the Miuix looks [topPad] is the pane's whole extent — the list
        // runs the full body height and rows scroll under the glass.
        contentPadding = PaddingValues(
            start = inset, top = topPad, end = inset, bottom = bottomPad
        )
    ) {
        if (visibleEvents.isEmpty()) {
            item(key = "empty") {
                EmptyEventsRow(selected, selected.emptyRes)
            }
            return@LazyColumn
        }
        days.forEach { day ->
            item(key = "day-${day.firstTs}") {
                SectionTitleText(
                    text = dayLabel(day.firstTs, todayLabel, yesterdayLabel)
                )
            }
            itemsIndexed(
                items = day.events,
                // `ts|text` is unique: RecordsStore.trim and parseEvents both
                // dedupe on exactly this pair before anything is written.
                key = { _, event -> "${event.ts}|${event.text}" },
                contentType = { _, event -> EventTaxonomy.kindOf(event) }
            ) { index, event ->
                EventRow(
                    event = event,
                    appLabel = appLabels[eventPackage(event)].orEmpty(),
                    uptimeSeconds = uptimes[event],
                    arrivalMs = arrivals[event],
                    first = index == 0,
                    last = index == day.events.lastIndex
                )
            }
        }
    }
}

/**
 * The 状态 tab: the structured configuration only. A plain vertical-scroll
 * Column, not a LazyColumn: a dozen rows do not need lazy reuse, and avoiding
 * Lazy prefetch here removes a whole class of node-reuse crashes.
 */
@Composable
private fun StatusTab(
    archive: RecordsStore.Archive,
    noSource: Boolean,
    problem: Int?,
    loading: Boolean,
    onOpenStatus: () -> Unit,
    inset: androidx.compose.ui.unit.Dp,
    /**
     * The air above the first row, as CONTENT padding inside the scroll —
     * under the Miuix looks this is the glass pane's full extent, so the
     * scrolled configuration cards travel under the pane's blur. The Material
     * look keeps the default 0: its column above pads, the strips scroll
     * beside, not over, the content.
     */
    topPad: androidx.compose.ui.unit.Dp = 0.dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(start = inset, top = topPad, end = inset, bottom = bottomPad)
    ) {
        val snapshot = archive.snapshot
        // Without a snapshot there is no connection group to host it, so the
        // one-line read status keeps its old place at the top. With one, it
        // moves into the group as the 更新于 row under 已连接时长 — the same
        // fact, next to the numbers it dates.
        if (snapshot == null) {
            RecordsStatusLine(
                loading = loading,
                fetchedAt = archive.fetchedAt,
                stale = archive.isStale(System.currentTimeMillis()),
                problemRes = problem
            )
        }
        if (noSource) {
            val gap = if (LocalUiStyle.current.isMiuixBased) 12.dp else 16.dp
            Spacer(modifier = Modifier.height(gap))
            SettingsGroup {
                NavCard(
                    iconRes = R.drawable.ic_status_locked,
                    title = stringResource(R.string.records_locked_title),
                    subtitle = stringResource(R.string.records_locked_body),
                    onClick = onOpenStatus,
                    first = true,
                    last = true
                )
            }
        }
        if (snapshot != null) {
            // The newest close the ring still holds. The snapshot says whether
            // the link is up; only the events say how the last one ended, and
            // the Client block on this GMS prints no last-error field at all.
            // Events arrive newest first, so the first close found is the one.
            val lastClose = remember(archive.events) {
                archive.events.firstNotNullOfOrNull { event ->
                    if (EventTaxonomy.kindOf(event) != GcmEvent.EventKind.ServerClose) {
                        return@firstNotNullOfOrNull null
                    }
                    GcmDumpParser.parseClose(event.text)?.let { it.code to event.ts }
                }
            }
            ConfigSection(
                R.string.records_section_connection,
                connectionConfigRows(
                    snapshot = snapshot,
                    lastClose = lastClose,
                    stateDetail = connectionVerdictDetail(archive),
                    updatedValue = when {
                        loading -> stringResource(R.string.records_loading)
                        problem != null -> stringResource(problem)
                        archive.fetchedAt == 0L -> stringResource(R.string.records_never)
                        archive.isStale(System.currentTimeMillis()) -> stringResource(
                            R.string.records_updated_stale,
                            timeOf(archive.fetchedAt)
                        )
                        else -> timeOf(archive.fetchedAt)
                    }
                )
            )
            ConfigSection(R.string.records_section_network, networkConfigRows(snapshot))
            ConfigSection(R.string.records_section_heartbeat, heartbeatConfigRows(snapshot))
            // Display names for both client lists, resolved the way the event
            // rows resolve theirs. Keyed on the two lists rather than on the
            // snapshot: the poll hands in a new snapshot every minute, and a
            // PackageManager round trip per package per poll is pointless when
            // the package list has not moved.
            val context = LocalContext.current
            // The manifest-side answer, for the cross-check row. Keyed on the
            // registered list: it is the only thing that can move this diff,
            // and the scan itself is a whole-PackageManager query that must
            // not run once a minute for nothing. An empty registered list
            // means GMS named nobody, in which case every declared app would
            // trivially be "not registered" — that is not a finding, so the
            // comparison is skipped rather than reported as a wall of drift.
            val declaredFcm = remember(snapshot.queuedPackages) {
                if (snapshot.queuedPackages.isEmpty()) {
                    emptySet()
                } else {
                    runCatching {
                        FcmAppScan.queryFcmPackages(context.packageManager) { _, _ -> }
                    }.getOrDefault(emptySet())
                }
            }
            val pushDiff = remember(declaredFcm, snapshot.queuedPackages) {
                if (declaredFcm.isEmpty()) {
                    null
                } else {
                    PushHealth.compare(declaredFcm, snapshot.queuedPackages)
                }
            }
            val appLabels = remember(snapshot.queuedPackages, snapshot.affinityScores, pushDiff) {
                val pm = context.packageManager
                (snapshot.queuedPackages + snapshot.affinityScores.map { it.packageName } +
                    pushDiff?.let { it.declaredOnly + it.registeredOnly }.orEmpty())
                    .distinct()
                    .associateWith { pkg ->
                        runCatching {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        }.getOrDefault(pkg)
                    }
            }
            ConfigSection(
                R.string.records_section_push,
                pushConfigRows(snapshot, appLabels, archive.standbyBuckets, pushDiff)
            )
            ConfigSection(R.string.records_section_error, errorConfigRows(snapshot))
        }
    }
}

/**
 * The bracketed half of the 连接状态 row: the one-sentence answer to "is my push
 * working right now, and how often does it break", folded into the row it
 * qualifies instead of standing above the cards on its own line.
 *
 * Both halves are read off data the page already holds: the snapshot's own
 * connected flag, and the closes the archive kept from the past hour, so the
 * value can always be checked against the rows under it. When the hour holds
 * more than one reason, the split is listed rather than a "most often" winner
 * invented — with two drops of two kinds there is no majority. Null means the
 * row has nothing to add — the bare state already says everything (e.g. no
 * close is left in the ring after a GMS restart).
 */
@Composable
private fun connectionVerdictDetail(archive: RecordsStore.Archive): String? {
    val snapshot = archive.snapshot ?: return null
    // The read's own clock, not "now": the poll hands in a new archive every
    // minute, and recomposing for an unrelated reason must not silently move
    // the one-hour window.
    val readAt = if (archive.fetchedAt > 0L) archive.fetchedAt else System.currentTimeMillis()
    val closes = remember(archive.events, readAt) {
        archive.events.filter { event ->
            event.ts >= readAt - VERDICT_WINDOW_MS &&
                EventTaxonomy.kindOf(event) == GcmEvent.EventKind.Close
        }
    }
    val latestClose = remember(archive.events) {
        archive.events.firstNotNullOfOrNull { event ->
            if (EventTaxonomy.kindOf(event) == GcmEvent.EventKind.Close) event else null
        }
    }
    // Counts per close code, most frequent first. A close whose text carries no
    // parsable code becomes its own bucket rather than vanishing from the
    // split — otherwise the reasons would not add up to the drop count.
    val reasonCounts = remember(closes) {
        closes.groupingBy { GcmDumpParser.parseClose(it.text)?.code }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Int?, Int>> { it.value }
                    .thenBy { it.key ?: Int.MAX_VALUE }
            )
    }
    if (snapshot.connected) {
        if (closes.isEmpty()) return stringResource(R.string.records_verdict_no_drops)
        val separator = stringResource(R.string.records_list_separator)
        val onlyCode = reasonCounts.singleOrNull()?.key
        if (reasonCounts.size == 1) {
            // One reason the whole hour: name it, no "most often" — there is no
            // second candidate for it to beat.
            return if (onlyCode != null) {
                stringResource(
                    R.string.records_verdict_drops_reason,
                    closes.size,
                    closeText(onlyCode)
                )
            } else {
                stringResource(R.string.records_verdict_drops, closes.size)
            }
        }
        // Several reasons: say so and spell the split out. Ranking them would
        // invent a winner when there is none (2 drops, 2 reasons, 1 each).
        val parts = ArrayList<String>(reasonCounts.size)
        for ((code, count) in reasonCounts) {
            parts += if (code != null) {
                stringResource(R.string.records_verdict_reason_count, closeText(code), count)
            } else {
                stringResource(R.string.records_verdict_reason_unknown_count, count)
            }
        }
        return stringResource(
            R.string.records_verdict_reasons,
            closes.size,
            parts.joinToString(separator)
        )
    }
    val last = latestClose ?: return null
    val ago = secondsText(((readAt - last.ts) / 1000).coerceAtLeast(0L).toInt())
    val reason = GcmDumpParser.parseClose(last.text)?.code?.let { closeText(it) }
    return if (reason != null) {
        stringResource(R.string.records_verdict_last_reason, ago, reason)
    } else {
        stringResource(R.string.records_verdict_last, ago)
    }
}

/**
 * The 事件 tab under the Material look: the M3 secondary strip above the
 * shared-scroll [EventsContent]. A category change still resets the scroll to
 * its own top — one shared [LazyListState] has no per-category memory, and a
 * retained offset would often land past the end of a shorter list. The Miuix
 * looks do not pass through here; their categories each keep a hoisted
 * LazyListState inside the pager ([EventsPage]).
 */
@Composable
private fun EventsTab(
    archive: RecordsStore.Archive,
    selected: EventTab,
    onSelect: (EventTab) -> Unit,
    inset: androidx.compose.ui.unit.Dp,
    bottomPad: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    // Owned here rather than inside EventsContent: this composable survives a
    // category change, the content does not.
    val classes = remember(archive.events) {
        archive.events.associateWith { EventTaxonomy.classes(it) }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        EventsTabRow(
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.padding(start = inset, end = inset)
        )
        // `key(selected)`: a new category starts at the top, the behaviour the
        // single shared scroll state used to give — and a fresh scroll state.
        key(selected) {
            EventsContent(
                archive = archive,
                selected = selected,
                classes = classes,
                inset = inset,
                bottomPad = bottomPad,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

private val EventTab.labelRes: Int
    get() = when (this) {
        EventTab.All -> R.string.records_tab_all
        EventTab.Connection -> R.string.records_tab_connection
        EventTab.Heartbeat -> R.string.records_tab_heartbeat
        EventTab.Push -> R.string.records_tab_push
        EventTab.Sleep -> R.string.records_tab_sleep
        EventTab.Error -> R.string.records_tab_error
    }

/**
 * The glyph the M3 look leads each category tab with — Material Symbols
 * `fill1`, same red line as [PageTab.symbolRes]. One per category, chosen for
 * what the category reports rather than for how it is spelled: `overview` for
 * 全部 (the whole stream at a glance), `lan` for 连接 (the network path),
 * `ecg_heart` for 心跳 (the heartbeat trace), `notifications_active` for 推送
 * (a delivered notification), `snooze` for 休眠 (Doze), `error` for 异常.
 */
private val EventTab.symbolRes: Int
    get() = when (this) {
        EventTab.All -> R.drawable.ic_overview
        EventTab.Connection -> R.drawable.ic_lan
        EventTab.Heartbeat -> R.drawable.ic_ecg_heart
        EventTab.Push -> R.drawable.ic_notifications_active
        EventTab.Sleep -> R.drawable.ic_snooze
        EventTab.Error -> R.drawable.ic_error
    }

private val EventTab.emptyRes: Int
    get() = when (this) {
        EventTab.All -> R.string.records_no_events
        EventTab.Connection -> R.string.records_tab_empty_connection
        EventTab.Heartbeat -> R.string.records_tab_empty_heartbeat
        EventTab.Push -> R.string.records_tab_empty_push
        EventTab.Sleep -> R.string.records_tab_empty_sleep
        EventTab.Error -> R.string.records_tab_empty_error
    }

/**
 * The second-level strip — the "content of tab" area.
 *
 * Under the Miuix-dressed looks the capsule ([MiuixCapsuleTabRow]) rides
 * INSIDE the bar's glass pane ([PageScaffold.barExtension], beside the page
 * strip), its `selectedTabIndex` reading the category pager
 * ([EventsCategoriesPager]); under the Material look the row sits between the
 * primary strip and the list, and the M3 row's own divider closes it at the
 * foot. Neither implementation sits inside the list, so neither is subject to
 * Lazy prefetch reuse.
 *
 * The Material look's six are M3 secondary tabs carrying a glyph and nothing
 * else ([RecordsIconTab]), one Material Symbols `fill1` glyph per category —
 * see [EventTab.symbolRes]. Three arguments decide that shape:
 *
 *  - Six icon-only tabs fit one phone width, so this is the fixed
 *    [SecondaryTabRow], not the scrollable one: the six split the width evenly
 *    and the whole category set is visible at once. A scrollable strip of pure
 *    icons is the one thing a scrollable strip cannot afford — the labels were
 *    what told you there were more tabs off the right edge.
 *  - An even sixth (≈60dp on a 360dp-wide phone) is the spacing: comfortably
 *    past the 48dp touch target with the 24dp glyph centred, and no `minTabWidth`
 *    floor to fight, since a glyph has no text width to measure.
 *  - `contentDescription` comes from the category's own label, which the row no
 *    longer shows; with no text in the tab it is the only thing a screen reader
 *    has to announce.
 *
 * The six-way strip is where the capsule earns its keep: it sizes each tab from
 * its own label and only scrolls when the six of them genuinely do not fit, so
 * English's longer labels are not clipped the way an equal six-way share would
 * clip them — and the Chinese labels, all two glyphs, still come out sharing
 * the width edge to edge. Nothing here sits inside the list.
 */
@Composable
private fun EventsTabRow(
    selected: EventTab,
    onSelect: (EventTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val labels = EventTab.entries.map { stringResource(it.labelRes) }
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixCapsuleTabRow(
            tabs = labels,
            selectedTabIndex = selected.ordinal,
            onTabSelected = { onSelect(EventTab.entries[it]) },
            // Same rule as the page strip: the pane is the blur, no second
            // glass on glass (see RecordsPageTabRow).
            backdrop = null,
            modifier = modifier
        )
        return
    }
    SecondaryTabRow(
        selectedTabIndex = selected.ordinal,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary
        // The divider stays the component's own default — the hairline at the
        // row's foot is the spec's separation between the strip and the list it
        // opens onto (and it double-serves as the boundary under the two
        // stacked rows the primary's own divider opens).
    ) {
        EventTab.entries.forEach { tab ->
            RecordsIconTab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                symbolRes = tab.symbolRes,
                labelRes = tab.labelRes
            )
        }
    }
}

/**
 * One tab of the M3 look's top strip: the library's own [LeadingIconTab].
 *
 * The component is exactly what the 状态/事件 strip wanted and why it is not
 * hand-laid: glyph in front of the label 8dp apart, one 48dp line, the pair
 * centred in the tab, the label on the tab's own `titleSmall`-class style, and
 * the selected/unselected ink crossfaded by the component's own transition
 * (provided through `LocalContentColor`, so the icon's default tint and the
 * label inherit the same animated colour without either being told what it is).
 * M3's plain [Tab] stacks an icon ABOVE its label — the primary-tab shape —
 * which is not what this row is after; the secondary strip has no label left
 * to place, so it takes [Tab] through [RecordsIconTab] instead.
 *
 * The glyph is a 24dp Material Symbols `fill1` drawable, the size the
 * component's own documentation asks for. `contentDescription` is null on
 * purpose: the label next to it already names the tab, and announcing both
 * would read every tab twice.
 */
@Composable
private fun RecordsLeadingIconTab(
    selected: Boolean,
    onClick: () -> Unit,
    symbolRes: Int,
    labelRes: Int
) {
    LeadingIconTab(
        selected = selected,
        onClick = onClick,
        text = {
            Text(
                text = stringResource(labelRes),
                maxLines = 1
            )
        },
        icon = {
            Icon(
                painter = painterResource(symbolRes),
                contentDescription = null,
                modifier = Modifier.size(MATERIAL_SYMBOL_SIZE)
            )
        }
    )
}

/**
 * One tab of the M3 look's secondary strip: glyph only, no label.
 *
 * [Tab] with `text = null` is the component's own icon-only form — one 24dp
 * glyph centred in the tab, one 48dp line high, the ink crossfaded by the same
 * transition [RecordsLeadingIconTab] gets, so the six categories sit at exactly
 * the height the 状态/事件 row above them does and the two strips read as one
 * block of chrome.
 *
 * Because the label is gone, `contentDescription` now carries it: with no text
 * in the tab, the description is the tab's only accessible name. The category
 * names are short and unambiguous ("连接", "心跳", "推送"…), so they read fine
 * spoken on their own even without the row's context.
 */
@Composable
private fun RecordsIconTab(
    selected: Boolean,
    onClick: () -> Unit,
    symbolRes: Int,
    labelRes: Int
) {
    Tab(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                painter = painterResource(symbolRes),
                contentDescription = stringResource(labelRes),
                modifier = Modifier.size(MATERIAL_SYMBOL_SIZE)
            )
        }
    )
}

/**
 * The glyph size both M3 strips draw at. Material's tabs take 24dp icons (the
 * [LeadingIconTab] documentation says so outright); the leading place an icon
 * takes in the primary row's width is what its 8dp gap and 16dp side padding
 * are measured around, so this is not a style knob. The secondary strip has
 * nothing to measure around — this size is the whole of each of its tabs'
 * content, so it is what the even sixth is sized for.
 */
private val MATERIAL_SYMBOL_SIZE = 24.dp

@Composable
private fun EmptyEventsRow(tab: EventTab, emptyRes: Int) {
    EventCard(first = true, last = true) {
        PlainInfoRow(
            label = stringResource(tab.labelRes),
            value = stringResource(emptyRes)
        )
    }
}

/**
 * A solid card for the recycled event rows.
 *
 * Deliberately not [GroupRow]/`MiuixCardSurface`: in 柔光玻璃 every such row
 * carries a `textureBlur` layer, and a recycled blur node inside the
 * LazyColumn was deactivated and then measured during draw (`measure is called
 * on a deactivated node`), which crashed at the end of the event list. The
 * status tab can keep the glass because it is a plain `verticalScroll` Column
 * and never recycles its rows; the event list recycles, so it uses this flat
 * surface — which is what lets the list be lazy at all.
 */
@Composable
private fun EventCard(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = connectedGroupShape(first, last)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape),
        color = LocalAppSurfaces.current.card,
        shape = shape
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 56.dp)
                // 16dp all round on both looks: the M3 card's own content
                // padding, and exactly [BasicComponentDefaults.InsideMargin] —
                // the library row this card stands next to under the Miuix
                // looks. The M3 branch used to take 14dp, which read pinched
                // against the 16dp horizontal room (the card's top/bottom
                // inner margins were measurably tighter than its sides).
                .padding(16.dp)
        ) {
            content()
        }
    }
}

/** The plain two-line body of [EventCard] — no glass, no SubcomposeLayout. */
@Composable
private fun PlainInfoRow(label: String, value: String) {
    Column {
        if (LocalUiStyle.current.isMiuixBased) {
            MiuixText(
                text = label,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            MiuixText(
                text = value,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )
            // bodyMedium is the list token's own supporting size
            // (ListTokens.ItemSupportingTextFont). No gap is added on top of
            // it: bodyLarge's 24dp line and bodyMedium's 20dp line already sum
            // to 44, which with the row's 14/14 padding lands exactly on the
            // 72dp two-line height — the 4dp this used to carry existed to
            // prop up a 12sp line and would now overshoot the token.
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * One structured-configuration section: a [SectionTitle] plus the rows the tab
 * wants. A tab with no configuration rows renders nothing; an empty snapshot
 * renders nothing either, and the status line already says "not read yet".
 */
@Composable
private fun ConfigSection(titleRes: Int, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    SectionTitle(titleRes)
    SettingsGroup {
        rows.forEachIndexed { index, row ->
            RecordsRow(
                label = row.first,
                value = row.second,
                first = index == 0,
                last = index == rows.lastIndex
            )
        }
    }
}

/**
 * The connection block: state, endpoint, this session's age, and — when the
 * dump reports them — the last session's age and how it ended.
 *
 * The state is three-way on purpose. The dump prints `Connecting` while a
 * socket is being brought up and no Client block at all in that window, so a
 * two-way "connected" read that window as a plain disconnect, which is the one
 * moment a reader is most likely to be looking.
 */
@Composable
private fun connectionConfigRows(
    snapshot: GcmSnapshot,
    /** The newest close the event ring still holds: code and when it fired. */
    lastClose: Pair<Int, Long>?,
    /** The verdict folded into the 状态 row; null when it has nothing to add. */
    stateDetail: String?,
    /** What the 更新于 row reads — a clock, or the reason it cannot date the read. */
    updatedValue: String
): List<Pair<String, String>> {
    return buildList {
        val state = when {
            snapshot.connected -> stringResource(R.string.records_connected_state)
            snapshot.connecting -> stringResource(R.string.records_connecting_state)
            else -> stringResource(R.string.records_disconnected_state)
        }
        add(
            stringResource(R.string.records_connection_state) to if (stateDetail != null) {
                stringResource(R.string.records_state_note, state, stateDetail)
            } else {
                state
            }
        )
        if (snapshot.endpoint.isNotEmpty()) {
            add(stringResource(R.string.records_endpoint) to snapshot.endpoint)
        }
        // Directly under 服务端: the two answer the same question — which server,
        // and how long has this session with it been up. The counters below
        // (stream / connects / limit) are identities and budgets, not the pair.
        add(stringResource(R.string.records_connected_for) to snapshot.connectTime.ifEmpty { "—" })
        // The read's own timestamp, directly under the session's age: both
        // answer "how recent is what I am reading".
        add(stringResource(R.string.records_updated_label) to updatedValue)
        val lastDuration = snapshot.lastConnectionDurationS
        if (lastDuration != null && lastDuration > 0) {
            add(stringResource(R.string.records_last_connection) to secondsText(lastDuration))
        }
        if (lastClose != null) {
            add(
                stringResource(R.string.records_last_close) to
                    closeText(lastClose.first) + stringResource(R.string.records_list_separator) +
                    timeOf(lastClose.second)
            )
        }
        if (snapshot.disconnectTime.isNotEmpty()) {
            add(stringResource(R.string.records_disconnect_time) to snapshot.disconnectTime)
        }
        add(stringResource(R.string.records_stream) to snapshot.streamId.ifEmpty { "—" })
        add(stringResource(R.string.records_connects) to snapshot.connects.toString())
        val limits = snapshot.connectionsLimitByType
        if (limits.isNotEmpty()) {
            val same = limits.distinctBy { it.value }.size == 1
            add(
                stringResource(R.string.records_connections_limit) to if (same) {
                    limits.first().value.toString()
                } else {
                    limits.joinToString(stringResource(R.string.records_list_separator)) {
                        typeLabel(snapshot, it.type) + " " + it.value
                    }
                }
            )
        }
        // The host-fallback policy the reconnect path is allowed to use. GMS
        // prints it as an enum name; the page names the handful that exist and
        // passes anything else through unchanged rather than guessing at it.
        if (snapshot.fallbackMode.isNotEmpty()) {
            add(
                stringResource(R.string.records_fallback_mode) to
                    fallbackModeText(snapshot.fallbackMode)
            )
        }
        // Shown because it explains a reset the reader would otherwise blame on
        // the network: every counter above belongs to one GMS process.
        if (snapshot.pid > 0) {
            add(stringResource(R.string.records_gms_pid) to snapshot.pid.toString())
        }
    }
}

/** The network block: what GCM sees as active, preferred and available. */
@Composable
private fun networkConfigRows(snapshot: GcmSnapshot): List<Pair<String, String>> {
    return buildList {
        add(stringResource(R.string.records_network_active) to snapshot.network.active.ifEmpty { "—" })
        add(stringResource(R.string.records_network_preferred) to snapshot.network.preferred.ifEmpty { "—" })
        add(stringResource(R.string.records_network_client) to snapshot.network.inUse.ifEmpty { "—" })
        if (snapshot.network.available.isNotEmpty()) {
            add(
                stringResource(R.string.records_network_available) to
                    snapshot.network.available.joinToString(
                        stringResource(R.string.records_list_separator)
                    )
            )
        }
    }
}

/**
 * The keepalive strategy the snapshot exposes: the adaptive switch per
 * transport, the algorithm it landed on, the next alarm, and — when the dump
 * prints them — the four readings that say whether the link is actually
 * healthy rather than merely up.
 *
 * The adaptive switch is per transport because GMS reports it per transport:
 * cellular and WiFi routinely disagree, and a single folded value (the first
 * line of the block) hid exactly the difference a reader wants.
 */
@Composable
private fun heartbeatConfigRows(snapshot: GcmSnapshot): List<Pair<String, String>> {
    val separator = stringResource(R.string.records_list_separator)
    return buildList {
        val flags = snapshot.adaptiveHeartbeatByType
        if (flags.isNotEmpty()) {
            val same = flags.distinctBy { it.enabled }.size == 1
            add(
                stringResource(R.string.records_heartbeat_adaptive) to if (same) {
                    stringResource(if (flags.first().enabled) R.string.records_on else R.string.records_off)
                } else {
                    // The on/off words are read before the join: a join lambda
                    // is not a composable context, so `stringResource` cannot be
                    // called inside it.
                    val on = stringResource(R.string.records_on)
                    val off = stringResource(R.string.records_off)
                    flags.joinToString(separator) {
                        typeLabel(snapshot, it.type) + " " + if (it.enabled) on else off
                    }
                }
            )
        }
        snapshot.heartbeat?.let {
            add(
                stringResource(R.string.records_heartbeat_algorithm) to
                    stringResource(
                        R.string.records_heartbeat_value,
                        it.intervalMs,
                        it.phase,
                        it.good,
                        it.bad
                    )
            )
        }
        if (snapshot.heartbeatAlarm.isNotEmpty()) {
            add(
                stringResource(R.string.records_heartbeat_alarm) to
                    if (snapshot.heartbeatAlarmInitialS.isNotEmpty()) {
                        stringResource(
                            R.string.records_heartbeat_alarm_value,
                            snapshot.heartbeatAlarm,
                            snapshot.heartbeatAlarmInitialS
                        )
                    } else {
                        snapshot.heartbeatAlarm
                    }
            )
        }
        // The link-health readings. Each is printed only when it has something
        // to say, so an absent row means "nothing to report", never "all fine".
        if (snapshot.heartbeatAwaitingAck) {
            add(
                stringResource(R.string.records_heartbeat_ack) to
                    stringResource(R.string.records_heartbeat_ack_value)
            )
        }
        if (snapshot.lastPing.isNotEmpty()) {
            add(stringResource(R.string.records_last_ping) to snapshot.lastPing)
        }
        if (snapshot.lastHeartbeatReset.isNotEmpty()) {
            add(
                stringResource(R.string.records_heartbeat_reset) to
                    stringResource(R.string.records_ago, snapshot.lastHeartbeatReset)
            )
        }
        snapshot.seenGoodHeartbeat?.let {
            add(
                stringResource(R.string.records_seen_good_heartbeat) to
                    stringResource(if (it) R.string.records_yes else R.string.records_no)
            )
        }
    }
}

/**
 * The push-side structured state: the two client lists by app name, the
 * high-priority quota, the pending count, and the last delivery's delay split.
 *
 * The queue list is spelled out in full — every app, not a count and a sample.
 * It answers "which apps does GMS think support the queue", and a truncated
 * list answers it for exactly the apps the reader already knew about. The
 * affinity list stays capped: it is a ranking, and the head of a ranking is the
 * whole of what it says.
 *
 * Names, not package ids: the package is an implementation detail a reader has
 * to look up, and the same resolver the event rows use is already here. An app
 * that is no longer installed falls back to its package id, which is the only
 * name it has left.
 */
@Composable
private fun pushConfigRows(
    snapshot: GcmSnapshot,
    /** Package id to display name, resolved by the caller for both lists. */
    appLabels: Map<String, String>,
    /**
     * App Standby bucket per package, read by the hook side in the same query
     * (see `StandbyQuota`). Empty when it could not be read.
     */
    standbyBuckets: Map<String, Int> = emptyMap(),
    /**
     * Manifest scan versus GMS's own registration list (see `PushHealth`).
     * Null when the comparison could not be made — the rows then stay absent
     * instead of claiming agreement.
     */
    pushDiff: PushHealth.Diff? = null
): List<Pair<String, String>> {
    return buildList {
        val queuedNames = snapshot.queuedPackages.map { appLabels[it] ?: it }
        add(
            stringResource(R.string.records_queued_clients) to
                if (queuedNames.isEmpty()) {
                    snapshot.queuedClients.toString()
                } else {
                    countList(queuedNames)
                }
        )
        // The two lists disagreeing is the finding, so each side gets its own
        // row and an agreeing pair says nothing at all. "Declared, not
        // registered" is the one that costs the user pushes: the app carries
        // the SDK and GMS has no record of it. The reverse is usually residue
        // from an uninstall — worth showing, not worth alarming about.
        pushDiff?.let { diff ->
            // Every name, no cap ([countList], not [countWithNames]): these two
            // rows exist so a reader can act on a specific app, and a list cut
            // off at four hides exactly the apps they have not looked at yet.
            if (diff.declaredOnly.isNotEmpty()) {
                add(
                    stringResource(R.string.records_push_declared_only) to
                        countList(diff.declaredOnly.map { appLabels[it] ?: it })
                )
            }
            if (diff.registeredOnly.isNotEmpty()) {
                add(
                    stringResource(R.string.records_push_registered_only) to
                        countList(diff.registeredOnly.map { appLabels[it] ?: it })
                )
            }
        }
        val affinity = snapshot.affinityScores.map {
            (appLabels[it.packageName] ?: it.packageName) + " " + scoreText(it.score)
        }
        add(
            stringResource(R.string.records_affinity_clients) to
                if (affinity.isEmpty()) {
                    snapshot.affinityClients.toString()
                } else {
                    countWithNames(affinity)
                }
        )
        // The apps GMS will cap today, from its own standby-bucket table. Only
        // the ones that *have* a cap are listed: ACTIVE / WORKING_SET apps are
        // unlimited, and printing "unlimited" for the other ten lines would
        // bury the two that matter. Tightest first — 5/day is the state that
        // makes a push disappear, 20/day is a warning.
        val capped = standbyBuckets.entries
            .filter { StandbyQuota.isLimited(it.value) }
            .sortedBy { StandbyQuota.quotaFor(it.value) }
            .map { (pkg, bucket) ->
                val name = appLabels[pkg] ?: pkg
                name + " " + stringResource(
                    R.string.records_quota_per_day, StandbyQuota.quotaFor(bucket)
                )
            }
        if (capped.isNotEmpty()) {
            // Every capped app, no cap on the list itself: the row exists to
            // name the apps GMS will throttle today, and "…等 N 个" would hide
            // exactly the ones a reader has not thought about yet. Same rule as
            // the client-queue row above ([countList]); only the affinity list
            // stays truncated, because it is a ranking.
            add(stringResource(R.string.records_quota_limited) to countList(capped))
        }
        if (snapshot.highPriorityQuota.isNotEmpty()) {
            add(stringResource(R.string.records_high_priority) to snapshot.highPriorityQuota)
        }
        if (snapshot.pendingMessages >= 0) {
            add(
                stringResource(R.string.records_pending_messages) to
                    if (snapshot.pendingMessages == 0) {
                        stringResource(R.string.records_pending_none)
                    } else {
                        stringResource(R.string.records_pending_count, snapshot.pendingMessages)
                    }
            )
        }
        // One delivery's delay, split the way GMS measures it. This is the only
        // first-hand answer to "why did the push arrive late" — the total alone
        // never says which of the three stages spent the time.
        snapshot.totalDelay?.let {
            add(
                stringResource(R.string.records_delivery_delay) to
                    stringResource(
                        R.string.records_delivery_delay_value,
                        it.totalMs,
                        it.serverToClientMs,
                        it.queueMs,
                        it.broadcastMs
                    )
            )
        }
    }
}

/** The anomaly-side structured state: failed logins and the reconnect policy. */
@Composable
private fun errorConfigRows(snapshot: GcmSnapshot): List<Pair<String, String>> {
    val separator = stringResource(R.string.records_list_separator)
    return buildList {
        add(stringResource(R.string.records_failed_logins) to snapshot.failedLogins.toString())
        if (snapshot.reconnectAlarm.isNotEmpty()) {
            add(stringResource(R.string.records_reconnect_alarm) to snapshot.reconnectAlarm)
        }
        if (snapshot.globalBackoff.isNotEmpty()) {
            // `PT4.026S` is Duration's own syntax; the row reads it as a time.
            add(stringResource(R.string.records_global_backoff) to durationText(snapshot.globalBackoff))
        }
        if (snapshot.networkBackoffs.isNotEmpty()) {
            // A loop, not `map`: `durationText` reads string resources, and a
            // lambda passed to map/joinToString is not a composable context.
            val parts = mutableListOf<String>()
            for (backoff in snapshot.networkBackoffs) {
                parts += backoff.network + " " + durationText(backoff.duration)
            }
            add(stringResource(R.string.records_network_backoff) to parts.joinToString(separator))
        }
        val within = snapshot.wakelockReadsWithin
        val exceeded = snapshot.wakelockReadsExceeded
        if (within != null && exceeded != null) {
            add(
                stringResource(R.string.records_wakelock_reads) to
                    stringResource(R.string.records_wakelock_reads_value, within, exceeded)
            )
        }
    }
}

/**
 * One event, the reference timeline's hierarchy: the severity dot and the
 * event's title own the left of one row, the clock rides the same centre line
 * at the far right, so it can never float off the title's baseline.
 *
 * The row is not tappable and holds no raw line: a tap used to reveal GMS's
 * original wording, and the page's job is to be read at a glance, not queried.
 * Everything the title needs is folded into it — what is left out is left out.
 */
@Composable
private fun EventRow(
    event: GcmEvent,
    appLabel: String,
    first: Boolean,
    last: Boolean,
    /** Seconds the connection had been up when this close ended it; null when unknown. */
    uptimeSeconds: Long? = null,
    /** Milliseconds since the message arrived at this 回执 / 完成 row; null when unknown. */
    arrivalMs: Long? = null
) {
    val miuix = LocalUiStyle.current.isMiuixBased
    val strings = rememberEventText()
    val kind = remember(event.ts, event.text, event.kind) {
        EventTaxonomy.kindOf(event)
    }
    val time = timeShort(event.ts)
    val timeColor = if (miuix) {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Push-chain rows invert the hierarchy: the app's display name is the
    // title and the stage (接收/回执/成功/失败/无响应/通知已代发) reads as a
    // qualifier, so a column of delivery events scans by subject first. The
    // measured duration, the receiver's error code and the broadcast priority
    // all fold into that qualifier — they are the three things a delivery row
    // is read for.
    val pushStage = pushStageLabelRes(kind)
    val pushStageSuffix = pushStageSuffix(event, kind, arrivalMs)
    EventCard(
        first = first,
        last = last,
        // Under M3 the connected-group idiom is a stack of separate cards over
        // the 2dp seam ([GROUP_ROW_GAP], [SettingsGroup]'s own arrangement);
        // the Miuix look fuses a group into one flush card. The list used to
        // fuse rows under BOTH looks, so the M3 reader got one long slab with
        // no air between its entries — the entries now breathe the way the
        // status page's groups always have.
        modifier = if (!miuix && !last) {
            Modifier.padding(bottom = GROUP_ROW_GAP)
        } else {
            Modifier
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(severityColor(EventTaxonomy.severity(event, kind)))
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (pushStage != null) {
                PushTitleLine(
                    appLabel = appLabel,
                    fallbackPackage = eventPackage(event).orEmpty(),
                    kind = kind,
                    stageSuffix = pushStageSuffix,
                    modifier = Modifier.weight(1f)
                )
            } else {
                val title = eventTitle(event, kind, uptimeSeconds, strings)
                if (miuix) {
                    MiuixText(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (miuix) {
                MiuixText(
                    text = time,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = timeColor
                )
            } else {
                // labelSmall is M3's 11sp role — the size this line always
                // wanted — so the scale is unchanged while the weight and the
                // 0.5sp tracking come from the type scale instead of being
                // absent. The monospace family is dropped with it: M3 has no
                // monospace role, and the font's own figures are tabular, so
                // stacked timestamps still line up.
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = timeColor
                )
            }
        }
    }
}

/**
 * The clauses a push row adds after its stage word, in reading order:
 * `，到达后 2.3 秒`, `，耗时 322 ms`, `，错误码 3`, `，高优先级`. Each part is
 * only there when the data carries it, so an absence never reads as a value.
 *
 * `到达后` is the end-to-end half — see [EventTaxonomy.pushArrivals]; the
 * broadcast's own `time=` only ever covered its last hop, which left "the push
 * took 8 seconds" with no number attached to the 8 seconds.
 */
internal fun pushStageSuffix(
    event: GcmEvent,
    kind: GcmEvent.EventKind,
    arrivalMs: Long?,
    strings: EventText
): String {
    val duration = if (kind == GcmEvent.EventKind.BroadcastCompleted) {
        GcmDumpParser.parseBroadcastCompleted(event.text)
    } else {
        null
    }
    val errorCode = if (kind == GcmEvent.EventKind.BroadcastFailed) {
        GcmDumpParser.parseBroadcastErrorCode(event.text)
    } else {
        null
    }
    val priority = if (pushStageLabelRes(kind) != null) {
        GcmDumpParser.parseBroadcastPriority(event.text)
    } else {
        null
    }
    return buildString {
        // The two measurements overlap: 到达后 starts at GMS's own `Received`,
        // so the broadcast's `time=` is only the last hop of it. Printing both
        // pushed the priority clause — the part a 完成 row is read for — off
        // the end of an 11 sp line (`普通优先级` became `普通…`), so the
        // superset wins and `耗时` appears only when the arrival is unknown,
        // e.g. a 完成 row whose `Received` has rolled out of the archive.
        val arrival = arrivalMs?.takeIf { it >= 0 }
        if (arrival != null) {
            append(arrivalClause(arrival, strings))
        }
        if (duration != null && arrival == null) {
            append(strings.format(R.string.records_push_duration, duration.durationMs))
        }
        if (errorCode != null) {
            append(strings.format(R.string.records_push_error_code, errorCode))
        }
        if (priority != null) {
            append(strings.get(priorityClauseRes(priority)))
        }
    }
}

/**
 * `，到达后 840 ms` under a second, `，到达后 2.3 秒` above it.
 *
 * Tenths come from integer arithmetic (`ms / 100`) rather than a decimal
 * format: `%.1f` would print `2,3` under a comma-decimal locale, and every
 * other number on this page is a plain integer.
 */
private fun arrivalClause(arrivalMs: Long, strings: EventText): String {
    if (arrivalMs < 1000) {
        return strings.format(R.string.records_push_arrival_ms, arrivalMs)
    }
    val tenths = arrivalMs / 100
    return strings.format(
        R.string.records_push_arrival_s,
        tenths / 10,
        tenths % 10
    )
}

@Composable
private fun pushStageSuffix(event: GcmEvent, kind: GcmEvent.EventKind, arrivalMs: Long?): String =
    pushStageSuffix(event, kind, arrivalMs, rememberEventText())

/** `priority=HIGH` → the clause GMS's own wording maps onto; unknown stays off. */
private fun priorityClauseRes(priority: String): Int = when (priority) {
    "HIGH" -> R.string.records_push_priority_high
    "NORMAL" -> R.string.records_push_priority_normal
    else -> R.string.records_push_priority_other
}

/**
 * The dot colour = how much the row deserves attention, not which stage it
 * belongs to. The stage is already the tab; the colour's only job is to answer
 * "do I need to look at this" while reading 全部. Four levels, three fixed
 * inks and the theme's own outline for the fourth so it follows the look.
 *
 * Error used to be the theme's `error` role, and that was the one level where
 * following the theme got it wrong: an 8 dp dot is pure signal, and M3's
 * dynamic error in the dark is tone 80 — a pale pink that reads as the
 * quietest ink on the page while marking the one row that wants attention. The
 * same event then looked like two different severities depending on the look,
 * because Miuix's own error stays saturated. A fixed red is now used by both
 * looks: it is chosen for an 8 dp dot on a light or dark page (~4:1 either
 * way), not for text, which is why the push stage's 11 sp error text keeps
 * reading the theme's role — text has a contrast floor the dot does not.
 */
private val SEVERITY_ERROR_INK = Color(0xFFE51C23)

@Composable
private fun severityColor(severity: EventSeverity): Color {
    val miuix = LocalUiStyle.current.isMiuixBased
    return when (severity) {
        EventSeverity.Normal -> Color(0xFF2E9E5B)
        EventSeverity.Watch -> Color(0xFFE8A33D)
        EventSeverity.Error -> SEVERITY_ERROR_INK
        EventSeverity.Unknown -> if (miuix) {
            MiuixTheme.colorScheme.outline
        } else {
            MaterialTheme.colorScheme.outline
        }
    }
}

private fun eventTitleRes(kind: GcmEvent.EventKind): Int = when (kind) {
    GcmEvent.EventKind.Connected -> R.string.records_event_connected
    GcmEvent.EventKind.Login -> R.string.records_event_login
    GcmEvent.EventKind.Heartbeat -> R.string.records_event_heartbeat
    GcmEvent.EventKind.Network -> R.string.records_event_network
    GcmEvent.EventKind.Reconnect -> R.string.records_event_reconnect
    GcmEvent.EventKind.Close -> R.string.records_event_close
    GcmEvent.EventKind.ServerClose -> R.string.records_event_close
    GcmEvent.EventKind.Doze -> R.string.records_event_doze
    GcmEvent.EventKind.IqStanza -> R.string.records_event_iq_stanza
    GcmEvent.EventKind.MessageReceived -> R.string.records_event_message_received
    GcmEvent.EventKind.MessageAcked -> R.string.records_event_message_acked
    GcmEvent.EventKind.BroadcastCompleted -> R.string.records_event_broadcast_completed
    GcmEvent.EventKind.BroadcastFailed -> R.string.records_event_broadcast_failed
    GcmEvent.EventKind.BroadcastNoResponse -> R.string.records_event_broadcast_no_response
    GcmEvent.EventKind.ProxiedNotification -> R.string.records_event_proxied
    GcmEvent.EventKind.BroadcastCancelled -> R.string.records_event_broadcast_cancelled
    GcmEvent.EventKind.GmsRestart -> R.string.records_event_restart
    GcmEvent.EventKind.SamplingGap -> R.string.records_event_sampling_gap_plain
    GcmEvent.EventKind.Transport -> R.string.records_event_transport
    GcmEvent.EventKind.Other -> R.string.records_event_other
}

/**
 * The close-code title gloss, one resource per official code: the Chinese
 * build reads the project's literal gloss, the English build reads GMS's own
 * `ERR_*` name. Null for codes outside the table — they keep the plain
 * 连接关闭 title. The glosses for 15/17/28/30/35 are literal readings only;
 * their deeper semantics are unverified (noted for the record).
 */
private fun closeGlossRes(code: Int): Int? = when (code) {
    1 -> R.string.records_close_gloss_1
    3 -> R.string.records_close_gloss_3
    4 -> R.string.records_close_gloss_4
    6 -> R.string.records_close_gloss_6
    10 -> R.string.records_close_gloss_10
    11 -> R.string.records_close_gloss_11
    14 -> R.string.records_close_gloss_14
    15 -> R.string.records_close_gloss_15
    16 -> R.string.records_close_gloss_16
    17 -> R.string.records_close_gloss_17
    18 -> R.string.records_close_gloss_18
    19 -> R.string.records_close_gloss_19
    20 -> R.string.records_close_gloss_20
    21 -> R.string.records_close_gloss_21
    22 -> R.string.records_close_gloss_22
    23 -> R.string.records_close_gloss_23
    24 -> R.string.records_close_gloss_24
    25 -> R.string.records_close_gloss_25
    26 -> R.string.records_close_gloss_26
    27 -> R.string.records_close_gloss_27
    28 -> R.string.records_close_gloss_28
    29 -> R.string.records_close_gloss_29
    30 -> R.string.records_close_gloss_30
    31 -> R.string.records_close_gloss_31
    32 -> R.string.records_close_gloss_32
    33 -> R.string.records_close_gloss_33
    34 -> R.string.records_close_gloss_34
    35 -> R.string.records_close_gloss_35
    36 -> R.string.records_close_gloss_36
    else -> null
}

/** The push chain's stage qualifier, or null when [kind] is not a push event. */
private fun pushStageLabelRes(kind: GcmEvent.EventKind): Int? = when (kind) {
    GcmEvent.EventKind.MessageReceived -> R.string.records_push_stage_received
    GcmEvent.EventKind.MessageAcked -> R.string.records_push_stage_acked
    GcmEvent.EventKind.BroadcastCompleted -> R.string.records_push_stage_completed
    GcmEvent.EventKind.BroadcastFailed -> R.string.records_push_stage_failed
    GcmEvent.EventKind.BroadcastNoResponse -> R.string.records_push_stage_no_response
    GcmEvent.EventKind.ProxiedNotification -> R.string.records_push_stage_proxied
    GcmEvent.EventKind.BroadcastCancelled -> R.string.records_push_stage_cancelled
    else -> null
}

/**
 * A push-chain row's title line: the app's display name leads and the stage
 * (接收/回执/完成/失败/无响应) follows in small secondary ink — the "who" is
 * the subject, the event kind reads as a qualifier. Anomalous stages (失败 /
 * 无响应) take the error ink so they survive a scan of the 异常 tab.
 *
 * **The name owns the line.** A row used to lay the two out as `weight` plus a
 * trailing text, which meant the qualifier took its natural width first and the
 * name wrapped into whatever was left: `Google Play 服务` came out as `Google` /
 * `Play 服务` — the subject of the row broken in half to make room for its own
 * adverb. [TitleAndQualifier] measures the name first instead, so the rule is
 * the one the eye expects: the name gets the whole width and stays on one line,
 * the qualifier sits after it when it still fits, and drops *below* the name
 * when it does not. Neither half may wrap internally — a qualifier squeezed
 * into the leftover column was the same bug wearing the other hat — so a name
 * longer than the row truncates rather than reflows, which is the only case
 * where anything is lost, and then only the tail of a display name the row
 * cannot show anyway.
 */
@Composable
private fun PushTitleLine(
    appLabel: String,
    fallbackPackage: String,
    kind: GcmEvent.EventKind,
    stageSuffix: String,
    modifier: Modifier = Modifier
) {
    val miuix = LocalUiStyle.current.isMiuixBased
    val name = appLabel.ifEmpty { fallbackPackage.ifEmpty { "—" } }
    val stageRes = pushStageLabelRes(kind) ?: return
    val anomalous = kind == GcmEvent.EventKind.BroadcastFailed ||
        kind == GcmEvent.EventKind.BroadcastNoResponse
    val stageColor = when {
        anomalous && miuix -> MiuixTheme.colorScheme.error
        anomalous -> MaterialTheme.colorScheme.error
        miuix -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val stage = stringResource(stageRes) + stageSuffix
    TitleAndQualifier(
        modifier = modifier,
        title = {
            if (miuix) {
                MiuixText(
                    text = name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        qualifier = {
            if (miuix) {
                MiuixText(
                    text = stage,
                    fontSize = 11.sp,
                    color = stageColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                // One role, not a role plus an override: the qualifier used to
                // wear bodySmall's tracking and line height with an 11sp size
                // forced on top, which is neither style. labelSmall is the
                // 11sp role itself.
                Text(
                    text = stage,
                    color = stageColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

/**
 * A title and its qualifier on one line while both fit, the qualifier below the
 * title when they do not.
 *
 * A [Row] cannot express this: it measures the unweighted child first, so the
 * qualifier kept its width and the title got the remainder — the wrap that
 * started this. A [FlowRow] cannot either, and for a subtler reason: it
 * measures a later child against the space *left on the line*, and a text that
 * can be squeezed always fits that constraint, so it ellipsizes in the leftover
 * column instead of moving down.
 *
 * So both halves are measured against the whole line here, and the placement is
 * decided from those two numbers. The title is measured first and may use every
 * pixel; the qualifier joins it only if it still fits after it. Their own
 * single-line settings ([TextOverflow.Ellipsis], no soft wrap) keep either one
 * from reflowing into a second line of its own — a two-line title was the
 * original complaint, and a two-line qualifier in a sliver of a column would be
 * the same complaint again.
 *
 * The reported width is the whole allotted line, never the sum of the two
 * halves: the caller gives this a `weight`, and a narrower report would let the
 * row's clock slide away from the right edge.
 */
@Composable
private fun TitleAndQualifier(
    modifier: Modifier = Modifier,
    gap: Dp = 6.dp,
    lineGap: Dp = 2.dp,
    title: @Composable () -> Unit,
    qualifier: @Composable () -> Unit
) {
    // One content slot holding both halves, so the policy sees a flat list: the
    // multi-content overload would hand each half over as its own slot list.
    Layout(
        modifier = modifier,
        content = {
            title()
            qualifier()
        }
    ) { measurables, constraints ->
        val lineWidth = constraints.maxWidth
        val titlePlaceable = measurables[0].measure(Constraints(maxWidth = lineWidth))
        val qualifierPlaceable = measurables[1].measure(Constraints(maxWidth = lineWidth))
        val gapPx = gap.roundToPx()
        val lineGapPx = lineGap.roundToPx()
        val sharedLine = titlePlaceable.width + gapPx + qualifierPlaceable.width <= lineWidth
        val width = if (constraints.hasBoundedWidth) {
            lineWidth
        } else if (sharedLine) {
            titlePlaceable.width + gapPx + qualifierPlaceable.width
        } else {
            maxOf(titlePlaceable.width, qualifierPlaceable.width)
        }
        val height = if (sharedLine) {
            maxOf(titlePlaceable.height, qualifierPlaceable.height)
        } else {
            titlePlaceable.height + lineGapPx + qualifierPlaceable.height
        }
        layout(width, height) {
            if (sharedLine) {
                titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
                qualifierPlaceable.placeRelative(
                    titlePlaceable.width + gapPx,
                    (height - qualifierPlaceable.height) / 2
                )
            } else {
                titlePlaceable.placeRelative(0, 0)
                qualifierPlaceable.placeRelative(0, titlePlaceable.height + lineGapPx)
            }
        }
    }
}

/**
 * The row's whole visible content: one fully-Chinese title line that folds in
 * the payload a first read needs — heartbeat and login intervals, alarm and
 * reconnect delays, the backoff duration, a failed attempt's Chinese gloss,
 * handover targets and both networks of a takeover. Everything else (raw
 * codes, host names, message ids) stays in the tap-to-expand raw line — the
 * detail second line is gone by design. The mapping mirrors
 * [GcmDumpParser.classify]'s prefix dispatch; an unrecognised raw shape falls
 * back to the plain kind label (fail-open, like the parser), its text one
 * tap away.
 *
 * Folding style: one Chinese sentence, payload attached after a comma —
 * `收到服务端心跳，间隔 230000 ms`, `被新连接顶替，连接关闭`. No `·`
 * separators and no brackets anywhere: a second clause is always a comma
 * clause, and a value that cannot fold this way stays in the raw line.
 * English build reads the same folding with English base titles.
 */

/**
 * The two lookups a title needs, as an interface instead of the Compose
 * runtime.
 *
 * Titles are the part of this page that keeps being wrong (`GcmNetwork{…}`
 * reaching the screen, a bare `Cell` standing in for a handover), and until
 * now the only way to check one was to install a build and read a screenshot.
 * Making the lookup a parameter is what lets a JVM test call [eventTitle] and
 * assert which resource carried which argument. Production supplies it from
 * [rememberEventText]; tests supply their own table.
 */
internal interface EventText {
    fun get(id: Int): String
    fun format(id: Int, vararg args: Any): String
}

/**
 * The production [EventText]: the app's resources, re-resolved when the
 * configuration changes (locale, font scale), exactly like `stringResource`.
 */
@Composable
private fun rememberEventText(): EventText {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        object : EventText {
            private val resources = context.resources
            override fun get(id: Int): String = resources.getString(id)
            override fun format(id: Int, vararg args: Any): String =
                resources.getString(id, *args)
        }
    }
}

internal fun eventTitle(
    event: GcmEvent,
    kind: GcmEvent.EventKind,
    uptimeSeconds: Long? = null,
    strings: EventText
): String {
    // These two locals are the seam. They shadow the Compose functions of the
    // same name on purpose: the body below is the one that shipped, unchanged,
    // so a refactor cannot quietly pick the wrong resource id along the way.
    fun stringResource(id: Int): String = strings.get(id)
    fun stringResource(id: Int, vararg args: Any): String = strings.format(id, *args)
    return when (kind) {
    GcmEvent.EventKind.Doze -> when {
        event.text.startsWith("Client Entering doze") ->
            stringResource(R.string.records_doze_entering)
        event.text.startsWith("Client Exiting doze") ->
            stringResource(R.string.records_doze_exiting)
        event.text.startsWith("Sending enter doze bit") ->
            stringResource(R.string.records_doze_bit_enter)
        event.text == "Force release of GOOGLE_C2DM lock" ->
            stringResource(R.string.records_doze_lock_release)
        event.text.startsWith("Force releasing GOOGLE_C2DM lock") -> {
            val delay = GcmDumpParser.parseLockReleaseDelay(event.text)
            if (delay != null) {
                stringResource(R.string.records_doze_lock_release_after, delay)
            } else {
                stringResource(R.string.records_doze_lock_release)
            }
        }
        else -> stringResource(eventTitleRes(kind))
    }
    GcmEvent.EventKind.Network -> {
        val target = GcmDumpParser.parseNetworkTarget(event.text)
        // Both network lines of one change are bound at parse time: a handover
        // line's own `was`, or the sibling line of a callback — so the row
        // never has to re-read the text to answer "came from where".
        val previous = event.previousNetwork
        val change = GcmDumpParser.classifyNetworkChange(target, previous)
        // A network that went away and a network that lost its link are the
        // same news to the reader; GMS just prints them on different lines.
        val down = GcmDumpParser.isNetworkGone(event.text) ||
            change == GcmDumpParser.NetworkChange.Dropped
        when {
            event.text.startsWith("Active network is now cellular, but already") ->
                stringResource(R.string.records_event_network_cellular_fallback)
            target == null -> stringResource(eventTitleRes(kind))
            // The identity leads: `Cell 100 网络已断开` says WHICH network in
            // one glance, and repeated rows still read as separate networks.
            down -> stringResource(
                R.string.records_event_network_dropped,
                networkName(target)
            )
            change == GcmDumpParser.NetworkChange.Restored -> stringResource(
                R.string.records_event_network_restored,
                networkName(target)
            )
            // Two networks of one transport are told apart by the netId alone:
            // `Cell 网络已从 100 切换到 107` says "the mobile data moved to
            // another network" in the shortest form that stays true — dropping
            // the numbers would read as no change at all.
            change == GcmDumpParser.NetworkChange.Handover &&
                previous != null &&
                previous.transport == target.transport &&
                previous.netId != null && target.netId != null ->
                stringResource(
                    R.string.records_event_network_switched_same,
                    target.transport,
                    previous.netId,
                    target.netId
                ) + downSuffix(target, strings)
            change == GcmDumpParser.NetworkChange.Handover && previous != null ->
                stringResource(
                    R.string.records_event_network_switched,
                    networkName(previous),
                    networkName(target)
                ) + downSuffix(target, strings)
            // A shape that names only one end (`onGcmNetworkChanged`, or a
            // handover with no paired sibling) has nothing to compare with —
            // the row states where the client is and stops there.
            event.text.startsWith("Active network is now ") && !target.isDown ->
                stringResource(R.string.records_event_network_now, networkName(target))
            else -> stringResource(
                R.string.records_event_network_switched_to,
                networkName(target)
            ) + downSuffix(target, strings)
        }
    }
    GcmEvent.EventKind.Heartbeat -> when {
        // Routine keepalive traffic carries its conclusion only. The
        // negotiated interval is a link parameter, not something a reader can
        // act on — it stays in the raw line, one tap away.
        event.text.startsWith("Received Server HB") ->
            stringResource(R.string.records_hb_server_received)
        event.text.startsWith("Sent Server HB Ack") ->
            stringResource(R.string.records_hb_server_ack)
        event.text.startsWith("Sent Client HB") ->
            stringResource(R.string.records_hb_client_sent)
        event.text.startsWith("Received Client HB Ack") ->
            stringResource(R.string.records_hb_client_ack)
        // This line is the watchdog *firing*, not the alarm being armed: the
        // number is `now - scheduled trigger time`, i.e. how far off schedule
        // the delivery was. Positive means the system delayed it (doze
        // batching), negative means it woke before its deadline.
        event.text.startsWith("Heartbeat alarm delay") -> {
            val base = stringResource(R.string.records_hb_alarm_fired)
            when (val skew = GcmDumpParser.parseHeartbeatAlarmDelay(event.text)) {
                null -> base
                else -> if (skew >= 0) {
                    base + stringResource(R.string.records_hb_alarm_late_by, skew)
                } else {
                    base + stringResource(R.string.records_hb_alarm_early_by, -skew)
                }
            }
        }
        event.text.startsWith("Heartbeat alarm fired early") ->
            stringResource(R.string.records_hb_alarm_early)
        event.text.startsWith("Alarm ") -> {
            val late = GcmDumpParser.parseAlarmLate(event.text)
            if (late != null) {
                stringResource(R.string.records_hb_alarm_late, late)
            } else {
                stringResource(eventTitleRes(kind))
            }
        }
        event.text.startsWith("Heartbeat timeout, GCM connection reset") ->
            stringResource(R.string.records_hb_timeout)
        else -> stringResource(eventTitleRes(kind))
    }
    GcmEvent.EventKind.Close -> {
        // Every code in GMS's official table gets a title qualifier — the
        // Chinese build reads the project gloss, the English build reads the
        // official `ERR_*` name itself. A code outside the table reads
        // 未知关闭原因; a raw shape that parses to no code at all keeps the
        // plain title.
        val code = GcmDumpParser.parseClose(event.text)?.code
        val glossRes = code?.let(::closeGlossRes)
        // An orderly ending (FIN, manual, replaced) is a whole sentence on its
        // own — appending 连接关闭 to 连接正常结束 only repeats itself. Every
        // other code keeps the outcome clause.
        val orderly = code != null && EventTaxonomy.severity(event, kind) == EventSeverity.Normal
        // How old the connection was when it ended. The reason alone cannot
        // tell a flapping link from a session that lived for an hour; the two
        // share close codes. Absent for a close whose `Connected` start has
        // rolled out of the archive — the row then says nothing rather than
        // guessing.
        val age = uptimeSeconds?.takeIf { it > 0 }?.let { secondsText(it.toInt(), strings) }
        val ageClause = if (age != null) {
            stringResource(R.string.records_close_uptime, age)
        } else {
            ""
        }
        val base = when {
            glossRes != null && orderly -> stringResource(glossRes)
            glossRes != null ->
                stringResource(R.string.records_close_with, stringResource(glossRes))
            code != null ->
                stringResource(
                    R.string.records_close_with,
                    stringResource(R.string.records_close_gloss_unknown)
                )
            else -> stringResource(R.string.records_event_close)
        }
        base + ageClause
    }
    GcmEvent.EventKind.ServerClose -> when {
        event.text == "Received Close" -> stringResource(R.string.records_server_close_received)
        event.text == "Disconnected" -> stringResource(R.string.records_event_disconnected)
        event.text.startsWith("Disconnect since no networks") ->
            stringResource(R.string.records_disconnect_no_network)
        event.text.startsWith("Disconnect since GCM is no longer enabled") ->
            stringResource(R.string.records_disconnect_disabled)
        event.text == "Sent Close" -> stringResource(R.string.records_server_close_sent)
        event.text.startsWith("Closing because of a remote restart") ->
            stringResource(R.string.records_close_remote_restart)
        else -> stringResource(eventTitleRes(kind))
    }
    GcmEvent.EventKind.Reconnect -> when {
        // The failed attempt's Chinese gloss (same table as the close titles)
        // rides the title; a code outside the table keeps the plain phrase.
        event.text.startsWith("Failed connection err") -> {
            val gloss = GcmDumpParser.parseFailedConnectionCode(event.text)
                ?.let(::closeGlossRes)
            if (gloss != null) {
                stringResource(R.string.records_event_failed_connection_with,
                    stringResource(gloss))
            } else {
                stringResource(R.string.records_event_failed_connection)
            }
        }
        event.text.startsWith("Failed to connect to") ->
            stringResource(R.string.records_event_host_fallback)
        event.text.startsWith("Skipping connect attempt since the network is suspended") ->
            stringResource(R.string.records_event_connect_skipped_suspended)
        event.text.startsWith("Skipping connect attempt since there is no active network") ->
            stringResource(R.string.records_event_connect_skipped_no_network)
        event.text.startsWith("No addresses for host") ->
            stringResource(R.string.records_event_no_addresses)
        event.text.startsWith("Starting parallel") -> {
            val pair = GcmDumpParser.parseParallelSwitch(event.text)
            if (pair != null) {
                stringResource(
                    R.string.records_event_parallel_with, pair.first, pair.second
                )
            } else {
                stringResource(R.string.records_event_parallel_takeover)
            }
        }
        event.text.startsWith("Connecting using") -> {
            val target = GcmDumpParser.parseConnectingUsing(event.text)
            when {
                target == null -> stringResource(eventTitleRes(kind))
                GcmDumpParser.parseConnectingFallbackPort(event.text) ->
                    stringResource(R.string.records_event_connecting_fallback, target)
                else -> stringResource(R.string.records_event_connecting, target)
            }
        }
        event.text.startsWith("Global backoff") -> {
            val duration = GcmDumpParser.parseGlobalBackoff(event.text)
            if (duration != null) {
                stringResource(R.string.records_global_backoff_event) +
                    stringResource(R.string.records_join_raw, duration)
            } else {
                stringResource(R.string.records_global_backoff_event)
            }
        }
        event.text.startsWith("Reconnect alarm") -> {
            val base = stringResource(R.string.records_event_reconnect_alarm_scheduled)
            when (val delay = GcmDumpParser.parseReconnectAlarmDelay(event.text)) {
                null -> base
                else -> if (delay >= 0) {
                    base + stringResource(R.string.records_reconnect_delay, delay)
                } else {
                    base + stringResource(R.string.records_delay_overdue, -delay)
                }
            }
        }
        else -> stringResource(eventTitleRes(kind))
    }
    GcmEvent.EventKind.IqStanza -> when {
        event.text.startsWith("Sent IqStanza") ->
            stringResource(R.string.records_iq_stanza_sent)
        event.text.startsWith("Received IqStanza") ->
            stringResource(R.string.records_iq_stanza_received)
        // `Sent <stanza>` for a stanza this build does not name: the token
        // stays in the raw line rather than becoming English noise in a title.
        event.text.startsWith("Sent ") ->
            stringResource(R.string.records_iq_stanza_sent_untyped)
        else -> stringResource(eventTitleRes(kind))
    }
    GcmEvent.EventKind.Login -> when {
        event.text.startsWith("Sent LoginRequest") ->
            stringResource(R.string.records_event_login)
        event.text.startsWith("Login failed on server") ->
            stringResource(R.string.records_login_failed_server)
        event.text.startsWith("Missing Android ID or token") ->
            stringResource(R.string.records_login_missing_credentials)
        event.text.startsWith("Invalid appId") ->
            stringResource(R.string.records_login_invalid_app_id)
        event.text.startsWith("Got new GCM ID/token") ->
            stringResource(R.string.records_login_new_token)
        event.text.startsWith("Sending IID SYNC") ->
            stringResource(R.string.records_login_iid_sync)
        event.text.startsWith("Sending IID ") ->
            stringResource(R.string.records_login_iid_broadcast)
        event.text.startsWith("Received message before LoginResponse") ->
            stringResource(R.string.records_login_message_before_response)
        // `Login error <code> <detail>`: the two payloads stay in the raw line.
        else -> stringResource(R.string.records_event_login_failed)
    }

    // Writer-thread and read faults: GMS logged them on the event ring, so the
    // page must name them instead of filing them under 其他.
    GcmEvent.EventKind.Transport -> when {
        event.text.startsWith("IOException in writerThread") ->
            stringResource(R.string.records_transport_writer_io)
        event.text.startsWith("IOException while handling message") ->
            stringResource(R.string.records_transport_handle_io)
        event.text.startsWith("Unexpected error in writerThread") ->
            stringResource(R.string.records_transport_write_error)
        event.text.startsWith("Read took too long") -> {
            val detail = GcmDumpParser.parseReadTooLong(event.text)
            if (detail != null) {
                stringResource(R.string.records_transport_read_slow, detail.first, detail.second)
            } else {
                stringResource(eventTitleRes(kind))
            }
        }
        else -> stringResource(eventTitleRes(kind))
    }
    // The store's overflow marker. The seconds it covers are the only useful
    // part: "the page is missing this much of the timeline" is the reading.
    GcmEvent.EventKind.SamplingGap -> {
        val seconds = GcmDumpParser.parseSamplingGapSeconds(event.text)
        if (seconds != null && seconds > 0) {
            stringResource(R.string.records_event_sampling_gap, secondsText(seconds.toInt(), strings))
        } else {
            stringResource(R.string.records_event_sampling_gap_plain)
        }
    }
    else -> stringResource(eventTitleRes(kind))
    }
}

/** `，但已断开` for a title that landed on a network the dump marks as gone. */
private fun downSuffix(ref: GcmDumpParser.NetworkRef, strings: EventText): String =
    if (ref.isDown) strings.get(R.string.records_network_down_suffix) else ""

/**
 * A network as a row writes it: transport and netId (`Cell 103`) — the pair
 * the dump's `GcmNetwork{…}` object carries.
 *
 * The netId is not decoration. Without it every handover between two networks
 * of the same transport renders as `切换为 Cell，原为 Cell`, which says nothing
 * at all — while "mobile data came back on a different network" and "the same
 * network object came back" are two very different stories (the netId is
 * monotonic within a boot and never reused, so a new number means a new
 * network object: a cell-tower handover while riding a train). See
 * FCM_HOOK_TECH_DOC.md §5.7.2 on `GcmNetwork{N …}`.
 *
 * The dump's `DISCONNECTED` tail is not part of the name: the row's verb says
 * it (`… 网络已断开`), so repeating it in brackets only lengthened the title.
 * A space, not `·`: one glyph of punctuation per row is a glyph not spent on
 * the numbers.
 */
private fun networkName(ref: GcmDumpParser.NetworkRef): String =
    if (ref.netId == null) ref.transport else ref.transport + " " + ref.netId

/** The package an event names, for the app-label cache; null when it names none. */
private fun eventPackage(event: GcmEvent): String? =
    GcmDumpParser.parseMessageReceived(event.text)?.packageName
        ?: GcmDumpParser.parseMessageAcked(event.text)?.packageName
        ?: GcmDumpParser.parseBroadcastCompleted(event.text)?.packageName
        ?: GcmDumpParser.parseProxiedTarget(event.text)
        ?: GcmDumpParser.parseCancelledTarget(event.text)
        ?: GcmDumpParser.parseBroadcastTarget(event.text)

/** One two-line row — the same shape as the module tab's info rows. */
@Composable
private fun RecordsRow(label: String, value: String, first: Boolean, last: Boolean) {
    // No click: these rows read a value out, they do not do anything. The
    // no-op click that used to sit here (with the haptic muted) bought the
    // ripple the reader asked for at the time, but in M3 terms a state layer
    // *means* "this activates something" — so it also put a click action on
    // every row's semantics and had a screen reader announce a config line as
    // an activatable control. Press feedback now belongs only to rows that
    // actually navigate.
    GroupRow(first = first, last = last, onClick = null) {
        Column {
            if (LocalUiStyle.current.isMiuixBased) {
                MiuixText(
                    text = label,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
                MiuixText(
                    text = value,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            } else {
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                // Same pairing as [PlainInfoRow]: the list token's supporting
                // size, and no extra gap — the two line heights alone make the
                // 72dp two-line row.
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** One day's run of events, newest first within the day. */
private class EventDay(
    val year: Int,
    val dayOfYear: Int,
    val firstTs: Long
) {
    val events = ArrayList<GcmEvent>()
}

/**
 * Events arrive newest first, so a single pass is enough: a new day starts
 * whenever the calendar day changes. [EventDay.firstTs] supplies the header's
 * date.
 */
private fun groupByDay(events: List<GcmEvent>): List<EventDay> {
    val days = ArrayList<EventDay>()
    var current: EventDay? = null
    for (event in events) {
        val calendar = Calendar.getInstance().apply { timeInMillis = event.ts }
        val year = calendar.get(Calendar.YEAR)
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val day = current
        if (day == null || day.year != year || day.dayOfYear != dayOfYear) {
            val created = EventDay(year, dayOfYear, event.ts)
            created.events.add(event)
            days += created
            current = created
        } else {
            day.events.add(event)
        }
    }
    return days
}

/** 「今天」/「昨天」by calendar day, `MM-dd` otherwise. */
private fun dayLabel(ts: Long, today: String, yesterday: String): String {
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val now = Calendar.getInstance()
    if (sameDay(now, then)) return today
    val previous = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(previous, then)) return yesterday
    return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ts))
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

/** `HH:mm:ss`, today or not — the day header carries the date and the raw
 * line a tap reveals carries the milliseconds. */
private fun timeShort(ts: Long): String {
    if (ts == 0L) return "—"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
}


/** `HH:mm:ss` today, `MM-dd HH:mm:ss` otherwise. */
private fun timeOf(ts: Long): String {
    if (ts == 0L) return "—"
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm:ss" else "MM-dd HH:mm:ss"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
}

/** How many names a status row spells out before it says 等 and stops. */
private const val INLINE_NAMES = 3

/** `Cell` / `WiFi` when the dump names that transport, `type N` when it does not. */
private fun typeLabel(snapshot: GcmSnapshot, type: Int): String =
    snapshot.networkTypeNames[type] ?: "type $type"

/**
 * The `Fallback mode:` enum as the page's own words.
 *
 * GMS prints the enum name — `FALLBACK_NONE` in older builds, lowercased on
 * newer ones — so the lookup folds case. The set is closed and small; a value
 * outside it is shown verbatim, because inventing a translation for an unknown
 * mode would be worse than the raw token.
 */
@Composable
private fun fallbackModeText(mode: String): String = when (mode.uppercase(Locale.ROOT)) {
    "FALLBACK_NONE" -> stringResource(R.string.records_fallback_none)
    "FALLBACK_NONE_DISALLOW_VPN_BYPASS" -> stringResource(R.string.records_fallback_none_vpn)
    "FALLBACK_CELLULAR" -> stringResource(R.string.records_fallback_cellular)
    "FALLBACK_CONSTRAINED" -> stringResource(R.string.records_fallback_constrained)
    "FALLBACK_RESTRICTED_SATELLITE" -> stringResource(R.string.records_fallback_satellite)
    // The sixth and last member of GMS's enum (`Lhzln`), which the table was
    // missing: the seven `FALLBACK_*` this enum can print are exactly the ones
    // below plus this one. Anything else reaching `else` is a token this build
    // has never seen, not a translation gap.
    "FALLBACK_ALTERNATIVE_HOSTPORT" -> stringResource(R.string.records_fallback_hostport)
    "FALLBACK_MODE_UNKNOWN" -> stringResource(R.string.records_fallback_unknown)
    else -> mode
}

/** A whole number of seconds as a duration a row can read. */
internal fun secondsText(seconds: Int, strings: EventText): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> strings.format(R.string.records_seconds, seconds.toString())
        minutes < 60 -> strings.format(R.string.records_minutes, minutes, seconds % 60)
        else -> strings.format(R.string.records_hours, minutes / 60, minutes % 60)
    }
}

@Composable
private fun secondsText(seconds: Int): String = secondsText(seconds, rememberEventText())

/**
 * `java.time.Duration.toString()` — `PT12S`, `PT4.026S`, `PT1M30S` — as the
 * same kind of sentence the rest of the page uses. Text that will not parse is
 * shown raw: GMS's own syntax beats a blank row.
 */
@Composable
private fun durationText(raw: String): String {
    val millis = try {
        java.time.Duration.parse(raw).toMillis()
    } catch (_: Throwable) {
        return raw
    }
    val seconds = millis / 1000.0
    if (seconds >= 60) return secondsText((millis / 1000).toInt())
    val text = if (seconds % 1.0 == 0.0) {
        seconds.toInt().toString()
    } else {
        String.format(Locale.getDefault(), "%.2f", seconds)
    }
    return stringResource(R.string.records_seconds, text)
}

/** An affinity score, two decimals — the dump's own precision, not more. */
private fun scoreText(score: Double): String =
    String.format(Locale.getDefault(), "%.2f", score)

/**
 * `3 个，a，b，c 等` — a count with the names that make it useful, capped so the
 * row stays a row. For a list whose *tail* is the point (the client queue) use
 * [countList] instead; this one is for rankings, where the head is the answer.
 */
@Composable
private fun countWithNames(names: List<String>): String {
    val separator = stringResource(R.string.records_list_separator)
    val shown = names.take(INLINE_NAMES)
    val head = stringResource(R.string.records_count, names.size) + separator +
        shown.joinToString(separator)
    return if (names.size > shown.size) head + stringResource(R.string.records_more) else head
}

/** `3 个，a，b，c` — every name, no cap: the value wraps instead of truncating. */
@Composable
private fun countList(names: List<String>): String {
    val separator = stringResource(R.string.records_list_separator)
    return stringResource(R.string.records_count, names.size) + separator +
        names.joinToString(separator)
}

/**
 * A close code as the page's own words: the official gloss when the table has
 * one, the bare number when it does not. Same table the event titles read.
 */
@Composable
private fun closeText(code: Int): String {
    val gloss = closeGlossRes(code)
    return if (gloss != null) {
        stringResource(gloss)
    } else {
        stringResource(R.string.records_close_code, code)
    }
}

/**
 * The one-line status inside the 状态 tab: what the last read found and when.
 * A snapshot older than two minutes reads as 已过期 — the poll stopped, or the
 * last read failed, and the numbers below are from before that.
 */
@Composable
private fun RecordsStatusLine(
    loading: Boolean,
    fetchedAt: Long,
    stale: Boolean,
    problemRes: Int?
) {
    val miuix = LocalUiStyle.current.isMiuixBased
    val text = when {
        loading -> stringResource(R.string.records_loading)
        problemRes != null -> stringResource(problemRes)
        fetchedAt == 0L -> stringResource(R.string.records_never)
        stale -> stringResource(
            R.string.records_updated_stale,
            stringResource(R.string.records_updated_at, timeOf(fetchedAt))
        )
        else -> stringResource(R.string.records_updated_at, timeOf(fetchedAt))
    }
    val color = if (problemRes != null) {
        MaterialTheme.colorScheme.error
    } else if (miuix) {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            // Aligned with the section titles above it on both looks: the M3
            // heading now sits straight on the page inset, the Miuix
            // SmallTitle 16dp inside it (12dp inset + 16dp = the reference's
            // 28dp margin). This line follows whichever axis its look uses.
            .padding(
                start = if (miuix) 16.dp else 0.dp,
                top = 8.dp,
                end = if (miuix) 16.dp else 0.dp,
                bottom = 4.dp
            ),
        color = color,
        // The Miuix look reads the library's own fine print — footnote1's
        // 13sp, the scale's caption step — not an M3 style from the other
        // design system.
        //
        // The Material branch reads bodyMedium: this line reports the same
        // thing a row's second line reports (what was read, when), so it sits
        // on the same grade as every row summary in the app rather than a
        // smaller one. bodySmall now has no consumer here.
        style = if (miuix) {
            TextStyle(fontSize = MiuixTheme.textStyles.footnote1.fontSize)
        } else {
            MaterialTheme.typography.bodyMedium
        }
    )
}

private const val TAG = "HyperFCMLive"
private const val POLL_INTERVAL_MS = 60_000L

/**
 * 一次下拉最短站多久。读一份 dump 常常几十毫秒就回来，指示器一闪而过会被读成
 * "闪了一下"而不是"刷好了" —— 所以刷新的档位至少撑这么久，短的补长，长的照旧。
 * 420ms 是一次 complete beat：滑出（弹簧到位）+ 一个形变节拍（650ms 周期的大半），
 * 收手回弹之前眼睛能确认它确实转过。
 */
private const val MIN_REFRESH_VISIBLE_MS = 420L

/** The window the status page's one-sentence verdict counts closes in. */
private const val VERDICT_WINDOW_MS = 60L * 60 * 1000

/**
 * The 12dp breath under each capsule strip — the example's own spacing, which
 * keeps the page body off the capsules' bottom edge. Both strips carry it, so
 * the events page reads strip, breath, strip, breath, content.
 */
private val STRIP_BREATH = 12.dp

/** Logcat tag for the event list's scroll diagnostics. */
private const val SCROLL_LOG = "RecordsScroll"

/**
 * How far a page's list has to travel before the bar's glass is at full
 * strength — the ramp this page hands [PageScaffold.barScrollProgress] for the
 * pane's frost and the reveal. The title's own collapse is the nested scroll's
 * business, never this number's.
 *
 * The bar's collapse eats the first ~44dp of every upward gesture
 * (exitUntilCollapsed pre-scroll) before the list itself moves, so this ramp
 * runs over the list's own travel, one beat behind the title it sits under —
 * the page reading cannot see the consumed stretch, and that is the price of a
 * frost that belongs to the tab on screen rather than to whichever tab scrolled
 * last.
 */
private val GLASS_RAMP = 48.dp

/** One poll's result, mapped to a string resource by the caller. */
private sealed interface Outcome {
    data class Ok(val archive: RecordsStore.Archive) : Outcome

    /** A dump came back but did not parse (GMS renamed its layout). */
    data object Unparsable : Outcome

    /** The module did not answer the query. */
    data object NoModule : Outcome
}

/**
 * One read: the module's hook, the only source. There is no root path — the
 * module being active is the very thing this app exists for.
 */
private suspend fun read(context: android.content.Context): Outcome {
    when (val hook = RecordsQuery.query(context)) {
        is RecordsQuery.Result.Success -> {
            val dump = GcmDumpParser.parse(hook.dump)
            if (dump != null) {
                // The hook side's install line rides the same query; a module
                // build that predates it answers null and the archive keeps
                // whatever it already had. Same for the standby buckets.
                return Outcome.Ok(
                    RecordsStore.capture(
                        context, dump, hook.summary, hook.standbyBuckets
                    ).archive
                )
            }
            Log.w(TAG, "Records: the hook's GCM dump did not parse")
            return Outcome.Unparsable
        }
        is RecordsQuery.Result.Failed ->
            Log.w(TAG, "Records: the hook could not read GCM's dump: ${hook.reason}")
        RecordsQuery.Result.Unavailable -> Unit
    }
    return Outcome.NoModule
}
