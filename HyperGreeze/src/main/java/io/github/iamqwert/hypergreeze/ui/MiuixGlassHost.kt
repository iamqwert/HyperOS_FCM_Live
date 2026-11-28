package io.github.iamqwert.hypergreeze.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.github.iamqwert.hypergreeze.theme.AppearanceState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRowDefaults
import top.yukonga.miuix.kmp.glass.glass
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShadow
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.drawGlassShadow
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Root container size injected from the app root's `BoxWithConstraints`.
 * Wide-screen and viewport calculations prefer this over `LocalWindowInfo.current.containerSize`.
 */
val LocalContainerSize = compositionLocalOf<IntSize?> { null }

/** Unscaled platform density before the app's custom density scale is applied. */
val LocalPlatformDensity = compositionLocalOf<Density?> { null }

/** Whether the current viewport should display the wide-screen [top.yukonga.miuix.kmp.glass.GlassNavigationRail]. */
@Composable
fun shouldUseGlassNavigationRail(): Boolean {
    val containerSize = LocalContainerSize.current ?: LocalWindowInfo.current.containerSize
    if (containerSize.width <= 0 || containerSize.height <= 0) return false
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return with(density) {
        val widthDp = containerSize.width.toDp()
        val heightDp = containerSize.height.toDp()
        val ratio = heightDp / widthDp
        widthDp >= 840.dp || (widthDp >= 600.dp && ratio < 1.2f)
    }
}

/** Whether the wide-screen [top.yukonga.miuix.kmp.glass.GlassNavigationRail] should start expanded. */
@Composable
fun shouldExpandGlassNavigationRail(): Boolean {
    val containerSize = LocalContainerSize.current ?: LocalWindowInfo.current.containerSize
    if (containerSize.width <= 0) return false
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return with(density) { containerSize.width.toDp() >= 1200.dp }
}

/** Unified blur gate for all `miuix-glass` surfaces. */
@Composable
fun isMiuixBlurActive(): Boolean =
    AppearanceState.enableBlur.value && isRuntimeShaderSupported()

/**
 * Records a backdrop seeded with an opaque `MiuixTheme.colorScheme.surface` fill so transparent
 * list regions never sample black or stale frames. Returns `null` when blur is disabled.
 */
@Composable
fun rememberMiuixSurfaceBackdrop(enabled: Boolean = isMiuixBlurActive()): LayerBackdrop? {
    if (!enabled) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/**
 * Page-local backdrop recorded around a page's scrollable body (excluding its top/bottom bars)
 * for `GlassTopAppBar` and `GlassSegmentedTabRow` sampling.
 */
val LocalTopAppBarBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * Whole-page backdrop recorded outside a page's `MiuixScaffold` (covering body + top/bottom bars)
 * for overlays (`GlassDialog`, `GlassBottomSheet`, `GlassDropdownPreference`, `GlassIconDropdownMenu`).
 * Must never contain the overlay host itself to prevent self-sampling.
 */
val LocalPageBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * Wraps a Miuix page (containing its `MiuixScaffold`) to record [LocalPageBackdrop] for overlays.
 * Strictly applies `layerBackdrop` on `fillMaxSize()` before any insets or padding.
 */
@Composable
fun PageBackdropBox(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val blurActive = enabled && isMiuixBlurActive()
    val pageBackdrop = rememberMiuixSurfaceBackdrop(blurActive)
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier),
    ) {
        CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
            content()
        }
    }
}

/**
 * A full-window layer drawn above persistent chrome such as [top.yukonga.miuix.kmp.glass.GlassNavigationRail].
 * Window-positioned glass popups (`GlassIconDropdownMenu`, etc.) lift into this host via [TopLayer].
 */
@Stable
class TopLayerState {
    internal val entries = mutableStateListOf<TopLayerEntry>()
}

@Stable
internal class TopLayerEntry {
    var content: @Composable BoxScope.() -> Unit by mutableStateOf({})
}

val LocalTopLayer = staticCompositionLocalOf<TopLayerState?> { null }

/** Draws [content] in the nearest [TopLayerHost], or in place when there is none. */
@Composable
fun TopLayer(content: @Composable BoxScope.() -> Unit) {
    val layer = LocalTopLayer.current
    if (layer == null) {
        Box(modifier = Modifier.fillMaxSize(), content = content)
        return
    }
    val entry = remember { TopLayerEntry() }
    SideEffect { entry.content = content }
    DisposableEffect(layer, entry) {
        layer.entries += entry
        onDispose { layer.entries -= entry }
    }
}

/** Hosts the content lifted by [TopLayer]. Place it after [top.yukonga.miuix.kmp.glass.GlassNavigationRail]. */
@Composable
fun TopLayerHost(
    state: TopLayerState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        state.entries.forEach { entry ->
            key(entry) { entry.content(this) }
        }
    }
}

/** Whether the wide-screen persistent glass navigation rail is currently active. */
val LocalUseGlassNavigationRail = compositionLocalOf { false }

private val ZeroTopBarStartInset: () -> Dp = { 0.dp }
internal val DefaultTopAppBarTitleAlpha: () -> Float = { 1f }

/**
 * Dynamic start inset reserved for the expanded glass sidebar. Read exclusively in the layout
 * phase (never during composition) to avoid per-frame recomposition while the rail morphs.
 */
val LocalTopBarStartInset = staticCompositionLocalOf<() -> Dp> { ZeroTopBarStartInset }

/** Extra title opacity driven by chrome overlapping the top bar (e.g. collapsed glass sidebar). */
val LocalTopAppBarTitleAlpha = staticCompositionLocalOf<() -> Float> { DefaultTopAppBarTitleAlpha }

/**
 * Start offset of centered chrome (collapsed glass sidebar capsule) obstructing the top bar center.
 * Uses `compositionLocalOf` so changes do not recompose the full tree.
 */
val LocalTopAppBarCenterObstructionStart = compositionLocalOf { Dp.Unspecified }

/** Current page's [ScrollBehavior], allowing `Modifier.pageOverscroll()` to feed `overscrollOffset`. */
val LocalPageScrollBehavior = compositionLocalOf<ScrollBehavior?> { null }

/**
 * Applies the dynamic start inset from [LocalTopBarStartInset] during the layout phase,
 * shrinking the child's available width and placing it shifted toward the end edge while
 * keeping backgrounds full-bleed.
 *
 * Use directly on components that already apply horizontal platform safe areas themselves
 * (such as `GlassTopAppBar` with `defaultWindowInsetsPadding = true`). For page bodies and
 * overlays that do not apply horizontal safe areas, use [miuixPageHorizontalInsets].
 */
@Composable
fun Modifier.miuixSidebarStartInset(): Modifier {
    val insetProvider = LocalTopBarStartInset.current
    if (insetProvider === ZeroTopBarStartInset) return this
    return this.layout { measurable, constraints ->
        val startPx = insetProvider().roundToPx().coerceIn(0, constraints.maxWidth)
        val placeable = measurable.measure(constraints.offset(horizontal = -startPx))
        layout(placeable.width + startPx, placeable.height) {
            placeable.placeRelative(startPx, 0)
        }
    }
}

/**
 * Applies horizontal platform safe areas (`systemBars ∪ captionBar ∪ displayCutout`) followed by
 * [miuixSidebarStartInset], matching Miuix's `AppPageInsets.contentInsets` horizontal contract so
 * page content clears both display cutouts and the expanded [top.yukonga.miuix.kmp.glass.GlassNavigationRail].
 */
@Composable
fun Modifier.miuixPageHorizontalInsets(): Modifier {
    val systemBars = WindowInsets.systemBars
    val captionBar = WindowInsets.captionBar
    val displayCutout = WindowInsets.displayCutout
    val horizontalSafeInsets = remember(systemBars, captionBar, displayCutout) {
        systemBars.union(captionBar).union(displayCutout).only(WindowInsetsSides.Horizontal)
    }
    return this
        .windowInsetsPadding(horizontalSafeInsets)
        .miuixSidebarStartInset()
}

/** 0ms instant navigation transition used when jumping from a sub-page via the persistent sidebar. */
val InstantNavTransition: NavTransition = navGraphicsTransition(
    opaqueDepth = 1f,
    dismissDirection = NavSwipeDirection.None,
    motion = NavMotion(
        programmatic = NavSettleSpec.Tween(durationMillis = 0, easing = LinearEasing),
    ),
) {}

/**
 * Bottom margin matching `GlassNavigationBar`'s `defaultWindowInsetsPadding`:
 * 32dp when no navigation bar inset is present, 16dp + navInset otherwise.
 */
fun glassNavigationBarBottomMargin(navBarInset: Dp): Dp =
    if (navBarInset > 0.dp) navBarInset + 16.dp else 32.dp

/**
 * A multi-tab segmented glass strip sharing [GlassSegmentedTabRowDefaults]'s exact track shape,
 * material, bloom stroke, shadow and indicator palette, but sizing each tab to its measured label
 * width (distributing spare width when everything fits, and horizontally scrolling when narrow
 * viewports or long English labels exceed the track width) so 6-tab strips never collapse into
 * ellipses.
 */
@Composable
fun AdaptiveGlassSegmentedTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    surfaceAlpha: Float = 0f,
    shadow: GlassShadow? = GlassShadows.Float,
    height: Dp = GlassSegmentedTabRowDefaults.Height,
    tabHorizontalPadding: Dp = 8.dp,
    tabGap: Dp = 2.dp,
) {
    if (tabs.isEmpty()) return
    val index = selectedIndex.coerceIn(0, tabs.lastIndex)
    val runtimeShaderSupported = isRuntimeShaderSupported()
    val resolvedBackdrop = backdrop?.takeIf { runtimeShaderSupported }
    val targetRamp = if (resolvedBackdrop != null) surfaceAlpha.coerceIn(0f, 1f) else 0f
    val rampState = animateFloatAsState(
        targetValue = targetRamp,
        animationSpec = tween(durationMillis = 450, easing = LinearEasing),
        label = "adaptiveGlassTabRamp",
    )
    val showGlass by remember(resolvedBackdrop) {
        computedStateOf { resolvedBackdrop != null && rampState.value > 0.001f }
    }

    val trackShape = GlassSegmentedTabRowDefaults.shape(height)
    val trackPadding = GlassSegmentedTabRowDefaults.TrackPadding
    val innerHeight = height - trackPadding * 2
    val indicatorShape = GlassSegmentedTabRowDefaults.shape(innerHeight)
    val trackColor = GlassSegmentedTabRowDefaults.restingTrackColor()
    val indicatorColor = GlassSegmentedTabRowDefaults.indicatorColor()
    val material = GlassSegmentedTabRowDefaults.material()
    val stroke = GlassSegmentedTabRowDefaults.stroke()
    val selectedContentColor = if (MiuixTheme.isDynamicColor) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val unselectedContentColor = MiuixTheme.colorScheme.onSurface

    val baseStyle = MiuixTheme.textStyles.body2
    val boldStyle = remember(baseStyle) { baseStyle.copy(fontWeight = FontWeight.Bold) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val padPx = with(density) { tabHorizontalPadding.toPx() }
    val gapPx = with(density) { tabGap.toPx() }
    val minSlotWidthsPx = remember(tabs, boldStyle, padPx) {
        tabs.map { label ->
            val measured = textMeasurer.measure(text = label, style = boldStyle, maxLines = 1).size.width
            measured + padPx * 2f + 2f
        }
    }

    val scope = rememberCoroutineScope()
    val indicatorLeft = remember { Animatable(0f) }
    val indicatorRight = remember { Animatable(0f) }
    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (shadow != null) {
                    Modifier.drawBehind {
                        val shadowAlpha = if (runtimeShaderSupported) rampState.value else 1f
                        drawGlassShadow(trackShape, shadow, shadowAlpha)
                    }
                } else {
                    Modifier
                }
            )
            .clip(trackShape)
            .drawBehind { drawRect(trackColor) },
    ) {
        val availableInnerWidthPx = with(density) {
            (maxWidth - trackPadding * 2).toPx().coerceAtLeast(0f)
        }
        val totalGapPx = gapPx * (tabs.size - 1).coerceAtLeast(0)
        val totalMinWidthPx = minSlotWidthsPx.sum() + totalGapPx
        val needsScroll = totalMinWidthPx > availableInnerWidthPx + 0.5f
        val slotWidthsPx = remember(minSlotWidthsPx, availableInnerWidthPx, totalMinWidthPx, needsScroll) {
            if (needsScroll || availableInnerWidthPx <= 0f) {
                minSlotWidthsPx
            } else {
                val extraPerSlot = (availableInnerWidthPx - totalMinWidthPx) / tabs.size
                minSlotWidthsPx.map { it + extraPerSlot }
            }
        }
        val slotStartsPx = remember(slotWidthsPx, gapPx) {
            val starts = FloatArray(slotWidthsPx.size)
            var cursor = 0f
            for (i in slotWidthsPx.indices) {
                starts[i] = cursor
                cursor += slotWidthsPx[i] + gapPx
            }
            starts
        }

        LaunchedEffect(index, slotStartsPx, slotWidthsPx, needsScroll, availableInnerWidthPx) {
            val targetLeft = slotStartsPx[index]
            val targetRight = targetLeft + slotWidthsPx[index]
            if (indicatorRight.value <= indicatorLeft.value) {
                indicatorLeft.snapTo(targetLeft)
                indicatorRight.snapTo(targetRight)
            } else {
                val movingLeft = targetLeft <= indicatorLeft.value
                val leftSpec = if (movingLeft) folmeSpring<Float>(0.7f, 0.4f) else folmeSpring<Float>(0.75f, 0.5f)
                val rightSpec = if (movingLeft) folmeSpring<Float>(0.75f, 0.5f) else folmeSpring<Float>(0.7f, 0.4f)
                scope.launch { indicatorLeft.animateTo(targetLeft, leftSpec) }
                scope.launch { indicatorRight.animateTo(targetRight, rightSpec) }
            }
            if (needsScroll && availableInnerWidthPx > 0f) {
                val center = (targetLeft + targetRight) * 0.5f
                val targetScroll = (center - availableInnerWidthPx * 0.5f).roundToInt().coerceAtLeast(0)
                scrollState.animateScrollTo(targetScroll)
            }
        }

        if (resolvedBackdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { this.alpha = if (showGlass) rampState.value else 0f }
                    .glass(
                        backdrop = resolvedBackdrop,
                        shape = trackShape,
                        style = GlassDefaults.Style,
                        alpha = 1f,
                        material = material,
                        stroke = stroke,
                        enabled = showGlass,
                        shading = false,
                    ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(trackPadding)
                .then(if (needsScroll) Modifier.horizontalScroll(scrollState) else Modifier),
        ) {
            // Sliding pill indicator
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .layout { measurable, constraints ->
                        val start = indicatorLeft.value.roundToInt().coerceAtLeast(0)
                        val end = indicatorRight.value.roundToInt().coerceAtLeast(start)
                        val width = (end - start).coerceAtLeast(0)
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = width, maxWidth = width)
                        )
                        layout(width, placeable.height) {
                            placeable.placeRelative(IntOffset(start, 0))
                        }
                    }
                    .clip(indicatorShape)
                    .drawBehind { drawRect(indicatorColor) },
            )
            // Labels row
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(tabGap),
            ) {
                tabs.forEachIndexed { position, label ->
                    key(label) {
                        val isSelected = position == index
                        val contentColorState = animateColorAsState(
                            targetValue = if (isSelected) selectedContentColor else unselectedContentColor,
                            animationSpec = tween(durationMillis = 150),
                            label = "adaptiveGlassTabContent",
                        )
                        val colorProducer = remember { ColorProducer { contentColorState.value } }
                        val slotWidthDp = with(density) { slotWidthsPx[position].toDp() }
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .width(slotWidthDp)
                                .fillMaxHeight()
                                .clip(indicatorShape)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = { onSelect(position) },
                                )
                                .semantics(mergeDescendants = true) {
                                    role = Role.Tab
                                    selected = isSelected
                                    onClick {
                                        onSelect(position)
                                        true
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            MiuixText(
                                text = label,
                                color = colorProducer,
                                style = if (isSelected) boldStyle else baseStyle,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }
        }
    }
}
