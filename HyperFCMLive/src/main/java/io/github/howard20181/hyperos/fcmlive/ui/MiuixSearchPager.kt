package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.howard20181.hyperos.fcmlive.ui.softglass.LocalSoftGlassSpec
import io.github.howard20181.hyperos.fcmlive.ui.softglass.SoftGlassSurface
import io.github.howard20181.hyperos.fcmlive.ui.softglass.softGlassTintColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * The Miuix look's search, lifted from the reference app's own search
 * machinery (the Example's `utils/SearchStatus` and `component/SuperSearchBar`)
 * nearly verbatim — the same status machine, the same bar, the same overlay,
 * the same animations, parameter for parameter. Only the Cancel label is
 * localised where the reference hard-codes English, and the results content is
 * ours to fill.
 *
 * The shape of the experience, as the reference authors it:
 *
 * - **Collapsed** — a fake capsule ([SearchBarFake]) stands in the top bar's
 *   bottom slot, inert, with the page's search hint. It is what the user sees
 *   and taps.
 * - **Expanding / Expanded** — a full-screen layer ([SearchPager]) rises over
 *   the page (surface fading in over 200 ms), carrying the real
 *   [SearchBar]. The bar starts where the fake capsule sits and rides to the
 *   screen's top edge (300 ms, `LinearOutSlowInEasing`), the Cancel word
 *   slides in at the row's end, and the results fill the screen.
 * - **Collapsing / Collapsed** — Cancel or a predictive back ([NavigationBackHandler])
 *   starts the ride back down; when the animation lands the search text is
 *   cleared (`onAnimationComplete`), so reopening starts clean.
 */

@Stable
data class SearchStatus(
    val label: String,
    val searchText: String = "",
    val current: Status = Status.COLLAPSED,
    val resultStatus: ResultStatus = ResultStatus.DEFAULT,
) {
    fun isExpand() = current == Status.EXPANDED
    fun isCollapsed() = current == Status.COLLAPSED
    fun shouldExpand() = current == Status.EXPANDED || current == Status.EXPANDING
    fun shouldCollapsed() = current == Status.COLLAPSED || current == Status.COLLAPSING
    fun isAnimatingExpand() = current == Status.EXPANDING

    fun onAnimationComplete(): SearchStatus = when (current) {
        Status.EXPANDING -> copy(current = Status.EXPANDED)
        Status.COLLAPSING -> copy(searchText = "", current = Status.COLLAPSED)
        else -> this
    }

    @Composable
    fun TopAppBarAnim(
        modifier: Modifier = Modifier,
        visible: Boolean = shouldCollapsed(),
        backgroundColor: Color = MiuixTheme.colorScheme.surface,
        content: @Composable () -> Unit,
    ) {
        Box(modifier = modifier) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(backgroundColor),
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { this.alpha = if (visible) 1f else 0f },
            ) { content() }
        }
    }

    enum class Status { EXPANDED, EXPANDING, COLLAPSED, COLLAPSING }
    enum class ResultStatus { DEFAULT, EMPTY, LOAD, SHOW }
}

// Search Pager Composable
@Composable
fun SearchStatus.SearchPager(
    onSearchStatusChange: (SearchStatus) -> Unit,
    offsetY: Dp,
    defaultResult: @Composable () -> Unit,
    expandBar: @Composable (SearchStatus, (SearchStatus) -> Unit, () -> Dp, Color) -> Unit = { searchStatus, onStatusChange, padding, color ->
        SearchBar(searchStatus, onStatusChange, padding, color)
    },
    searchBarTopPadding: () -> Dp = { 12.dp },
    collapsedCapsuleColor: Color? = null,
    result: LazyListScope.() -> Unit,
) {
    val searchStatus = this
    val onSearchStatusChangeUpdated = rememberUpdatedState(onSearchStatusChange)
    val searchStatusUpdated = rememberUpdatedState(searchStatus)
    val onCancelSearch = remember {
        {
            onSearchStatusChangeUpdated.value(
                searchStatusUpdated.value.copy(
                    searchText = "",
                    current = SearchStatus.Status.COLLAPSING,
                ),
            )
        }
    }
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val topPadding by animateDpAsState(
        targetValue = if (searchStatus.shouldExpand()) {
            systemBarsPadding + 12.dp
        } else {
            max(offsetY, 0.dp)
        },
        animationSpec = tween(300, easing = LinearOutSlowInEasing),
        label = "SearchPagerTopPadding",
    ) {
        onSearchStatusChange(searchStatus.onAnimationComplete())
    }
    val surfaceAlpha by animateFloatAsState(
        if (searchStatus.shouldExpand()) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "SearchPagerSurfaceAlpha",
    )

    val surfaceColor = MiuixTheme.colorScheme.surface
    Column(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(5f)
            .drawBehind { drawRect(surfaceColor.copy(alpha = surfaceAlpha)) }
            .semantics { onClick { false } }
            .then(
                if (!searchStatus.isCollapsed()) Modifier.pointerInput(Unit) { } else Modifier,
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .topInset { topPadding }
                .then(
                    if (!searchStatus.isCollapsed()) {
                        if (collapsedCapsuleColor != null) {
                            Modifier.drawBehind { drawRect(surfaceColor.copy(alpha = surfaceAlpha)) }
                        } else {
                            Modifier.background(surfaceColor)
                        }
                    } else {
                        Modifier
                    },
                ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!searchStatus.isCollapsed()) {
                val capsuleColor = collapsedCapsuleColor?.let {
                    lerp(it, MiuixTheme.colorScheme.surfaceContainerHigh, surfaceAlpha)
                } ?: MiuixTheme.colorScheme.surfaceContainerHigh
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (collapsedCapsuleColor != null) {
                                Modifier.drawBehind { drawRect(surfaceColor.copy(alpha = surfaceAlpha)) }
                            } else {
                                Modifier.background(surfaceColor)
                            },
                        ),
                ) {
                    expandBar(searchStatus, onSearchStatusChange, searchBarTopPadding, capsuleColor)
                }
            }
            AnimatedVisibility(
                visible = searchStatus.isExpand() || searchStatus.isAnimatingExpand(),
                enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it }),
            ) {
                Text(
                    text = stringResource(android.R.string.cancel),
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 4.dp, end = 16.dp, bottom = 6.dp)
                        .topInset(searchBarTopPadding)
                        .clickable(
                            interactionSource = null,
                            enabled = searchStatus.isExpand(),
                            indication = null,
                            onClick = onCancelSearch,
                        ),
                )
                val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
                NavigationBackHandler(
                    state = navEventState,
                    isBackEnabled = true,
                    onBackCompleted = {
                        onSearchStatusChange(
                            searchStatus.copy(
                                searchText = "",
                                current = SearchStatus.Status.COLLAPSING,
                            ),
                        )
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = searchStatus.isExpand(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            when (searchStatus.resultStatus) {
                SearchStatus.ResultStatus.DEFAULT -> defaultResult()

                SearchStatus.ResultStatus.EMPTY -> {}

                SearchStatus.ResultStatus.LOAD -> {}

                SearchStatus.ResultStatus.SHOW -> LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                ) {
                    result()
                }
            }
        }
    }
}

@Composable
fun SearchBar(
    searchStatus: SearchStatus,
    onSearchStatusChange: (SearchStatus) -> Unit,
    searchBarTopPadding: () -> Dp = { 12.dp },
    color: Color = MiuixTheme.colorScheme.surfaceContainerHigh,
) {
    val focusRequester = remember { FocusRequester() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val onSearchStatusChangeUpdated = rememberUpdatedState(onSearchStatusChange)
    val searchStatusUpdated = rememberUpdatedState(searchStatus)
    val onClearSearch = remember {
        { onSearchStatusChangeUpdated.value(searchStatusUpdated.value.copy(searchText = "")) }
    }

    // Deliberately plain in every look, the soft-glass one included: the
    // floating capsule is the style's chrome, but the full-screen search page
    // is an opaque sheet by design — a glass bar on top of it has nothing
    // honest to sample, and the reference keeps this field plain too. The
    // capsule-to-plain handoff at either end of the ride is accepted.
    InputField(
        query = searchStatus.searchText,
        onQueryChange = { onSearchStatusChange(searchStatus.copy(searchText = it)) },
        label = searchStatus.label,
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = "back",
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 16.dp, end = 8.dp),
                tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                searchStatus.searchText.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Icon(
                    imageVector = MiuixIcons.Basic.SearchCleanup,
                    tint = MiuixTheme.colorScheme.onSurface,
                    contentDescription = "Clean",
                    modifier = Modifier
                        .size(44.dp)
                        .padding(start = 8.dp, end = 16.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onClearSearch,
                        ),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp)
            .topInset(searchBarTopPadding)
            .focusRequester(focusRequester),
        color = color,
        onSearch = {},
        expanded = searchStatus.shouldExpand(),
        onExpandedChange = {
            onSearchStatusChange(
                searchStatus.copy(
                    current = if (it) SearchStatus.Status.EXPANDED else SearchStatus.Status.COLLAPSED,
                ),
            )
        },
    )
    LaunchedEffect(Unit) {
        if (!expanded && searchStatus.shouldExpand()) {
            focusRequester.requestFocus()
            expanded = true
        }
    }
}

@Composable
fun SearchBarFake(
    label: String,
    searchBarTopPadding: () -> Dp = { 12.dp },
    capsuleColor: Color? = null,
) {
    // The capsule's own plate, over the bar's frost. It is the look that decides,
    // and only the look: the caller's [capsuleColor] is the fill that stands in
    // for the material under a frosted bar (the reference's High at 80%), so it
    // is exactly what the material replaces when it is drawn — and the plate now
    // samples the same list the bar's frost does, so the two agree.
    //
    // The field itself is untouched: same [InputField], same icon, same padding,
    // same geometry — its own opaque container colour is simply cleared so the
    // material shows instead of a second surface, exactly as the home foot's pill
    // keeps the library's capsule and takes the glass as paint.
    val glass = softGlassChrome()
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        InputField(
            query = "",
            onQueryChange = { },
            label = label,
            leadingIcon = {
                Icon(
                    imageVector = MiuixIcons.Basic.Search,
                    contentDescription = "Search",
                    modifier = Modifier
                        .size(44.dp)
                        .padding(start = 16.dp, end = 8.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                )
            },
            modifier = fieldModifier,
            color = if (glass) Color.Transparent else {
                capsuleColor ?: MiuixTheme.colorScheme.surfaceContainerHigh
            },
            onSearch = { },
            enabled = false,
            expanded = false,
            onExpandedChange = { },
        )
    }
    val placement = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .padding(bottom = 6.dp)
        .topInset(searchBarTopPadding)
    if (glass) {
        SoftGlassSurface(
            modifier = placement,
            tint = LocalSoftGlassSpec.current.fineTuning
                .softGlassTintColor(MiuixTheme.colorScheme.onSurface.luminance() > 0.5f)
                .copy(alpha = LocalSoftGlassSpec.current.topBarTintAlpha),
        ) {
            field(Modifier.fillMaxWidth())
        }
    } else {
        field(placement)
    }
}

/** Reserves [inset] above the content, read during layout so a changing value never recomposes the caller. */
private fun Modifier.topInset(inset: () -> Dp): Modifier = layout { measurable, constraints ->
    val insetPx = inset().roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = -insetPx))
    layout(placeable.width, placeable.height + insetPx) {
        placeable.placeRelative(0, insetPx)
    }
}
