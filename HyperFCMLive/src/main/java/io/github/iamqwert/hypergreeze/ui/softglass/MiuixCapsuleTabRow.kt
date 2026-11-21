package io.github.iamqwert.hypergreeze.ui.softglass

// Aligned with MIUIX's `GlassSegmentedTabRow` (`miuix-glass/GlassTabRow.kt`)
// and `IconPage.kt`:
//  - height 40dp, track padding 3dp (34dp inner pill), full pill corners;
//  - opaque resting track fill (`restingTrackColor`) that transitions smoothly
//    (450ms linear) into the `puredThinGlass` soft-glass layer + shadow as
//    content scrolls under the top bar (`LocalSoftGlassBarReveal`);
//  - dual-edge stretching indicator (`indicatorLeft` / `indicatorRight`) driven
//    by `folmeSpring(0.7f, 0.4f)` leading and `folmeSpring(0.75f, 0.5f)`
//    trailing;
//  - indicator fill: light `Black @ 6%` (pressed `14%`), dark `White @ 13.3%`
//    (pressed `26%`), or `primary` with the same alphas when Monet is active;
//  - press feedback: indicator scales down (`folmeSpring(0.99f, 0.15f)` /
//    `0.3f`), indicator tint deepens (`folmeSpring(1f, 0.2f)` /
//    `folmeSpring(0.95f, 0.35f)`), pressed label dims to 0.6f alpha;
//  - typography & ink: selected `subtitle` + (`primary` under Monet else
//    `onSurface`), unselected `body2` + `onSurface` (150ms cubic-bezier);
//  - retains adaptive natural widths + horizontal scroll so both the 2-tab
//    状态/事件 strip and the 6-tab category strip fit cleanly without clipping.

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.iamqwert.hypergreeze.theme.AppearanceState
import io.github.iamqwert.hypergreeze.ui.LocalSoftGlassBarReveal
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

object MiuixCapsuleTabRowDefaults {
    /** `GlassSegmentedTabRowDefaults.Height`: 34dp inner tab + 3dp padding top/bottom. */
    val Height = 40.dp

    /** `GlassSegmentedTabRowDefaults.TrackPadding`. */
    val HorizontalPadding = 3.dp
    val VerticalPadding = 3.dp

    /** Horizontal air either side of a label (`GlassSegmentedTabRowDefaults.TabPaddingHorizontal`). */
    val ItemHorizontalPadding = 16.dp

    /** Tighter per-tab horizontal padding for dense multi-tab rows (e.g. 6 event categories). */
    val CompactItemHorizontalPadding = 6.dp

    /** Minimum tab width for compact multi-tab rows (e.g. 6 event categories). */
    val MinimumItemWidth = 48.dp

    val Shape: Shape = SoftGlassDefaults.Shape

    private const val DarkIndicatorAlpha = 0.133f
    private const val LightIndicatorAlpha = 0.06f
    private const val DarkPressedIndicatorAlpha = 0.26f
    private const val LightPressedIndicatorAlpha = 0.14f
    internal const val PressedContentAlpha = 0.6f

    /** `GlassSegmentedTabRowDefaults.restingTrackColor`: opaque fill before glass rises. */
    @Composable
    fun restingTrackColor(isDark: Boolean, isDynamic: Boolean): Color {
        val surface = MiuixTheme.colorScheme.surface
        return when {
            isDark -> {
                val tint = if (isDynamic) MiuixTheme.colorScheme.onSurface else Color.White
                tint.copy(alpha = DarkIndicatorAlpha).compositeOver(surface)
            }
            isDynamic -> MiuixTheme.colorScheme.surfaceContainer
            else -> Color.White
        }
    }

    @Composable
    private fun indicatorTint(isDark: Boolean, isDynamic: Boolean): Color = when {
        isDynamic -> MiuixTheme.colorScheme.primary
        isDark -> Color.White
        else -> Color.Black
    }

    /** `GlassSegmentedTabRowDefaults.indicatorColor`. */
    @Composable
    fun indicatorColor(isDark: Boolean, isDynamic: Boolean): Color =
        indicatorTint(isDark, isDynamic).copy(
            alpha = if (isDark) DarkIndicatorAlpha else LightIndicatorAlpha,
        )

    /** `GlassSegmentedTabRowDefaults.indicatorPressedColor`. */
    @Composable
    fun indicatorPressedColor(isDark: Boolean, isDynamic: Boolean): Color =
        indicatorTint(isDark, isDynamic).copy(
            alpha = if (isDark) DarkPressedIndicatorAlpha else LightPressedIndicatorAlpha,
        )

    internal val MaterialRampSpec = tween<Float>(durationMillis = 450, easing = LinearEasing)
    internal val ContentColorSpec = tween<Color>(
        durationMillis = 150,
        easing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f),
    )
    internal val ContentFloatSpec = tween<Float>(
        durationMillis = 150,
        easing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f),
    )
    internal fun edgeSpring(leading: Boolean): SpringSpec<Float> =
        if (leading) folmeSpring(0.7f, 0.4f) else folmeSpring(0.75f, 0.5f)
    internal fun pressEnterColor(): SpringSpec<Color> = folmeSpring(1f, 0.2f)
    internal fun pressExitColor(): SpringSpec<Color> = folmeSpring(0.95f, 0.35f)
    internal fun pressDownScale(): SpringSpec<Float> = folmeSpring(0.99f, 0.15f)
    internal fun pressUpScale(): SpringSpec<Float> = folmeSpring(0.99f, 0.3f)
}

@Composable
fun MiuixCapsuleTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHorizontalPadding: Dp = MiuixCapsuleTabRowDefaults.ItemHorizontalPadding,
    backdrop: LayerBackdrop? = LocalSoftGlassBackdrop.current,
    surfaceAlpha: Float = LocalSoftGlassBarReveal.current,
) {
    if (tabs.isEmpty()) return

    val softGlass = LocalSoftGlassSpec.current
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val isDynamic by AppearanceState.miuixMonet
    val shape = MiuixCapsuleTabRowDefaults.Shape
    val trackColor = MiuixCapsuleTabRowDefaults.restingTrackColor(isDark, isDynamic)
    val baseIndicatorColor = MiuixCapsuleTabRowDefaults.indicatorColor(isDark, isDynamic)
    val pressedIndicatorColor = MiuixCapsuleTabRowDefaults.indicatorPressedColor(isDark, isDynamic)
    val selectedContentColor = if (isDynamic) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface
    }
    val contentColor = MiuixTheme.colorScheme.onSurface
    val selectedIndex = selectedTabIndex.coerceIn(0, tabs.lastIndex)

    val glassAvailable = softGlass.enabled && backdrop != null
    val targetRamp = if (glassAvailable) surfaceAlpha.coerceIn(0f, 1f) else 0f
    val rampState by animateFloatAsState(
        targetValue = targetRamp,
        animationSpec = MiuixCapsuleTabRowDefaults.MaterialRampSpec,
        label = "glassSegmentedTabRamp",
    )
    val showGlass by remember(glassAvailable) {
        derivedStateOf { glassAvailable && rampState > 0.001f }
    }

    val selectedStyle = MiuixTheme.textStyles.subtitle
    val unselectedStyle = MiuixTheme.textStyles.body2
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val itemInsets = itemHorizontalPadding * 2
    val labelWidths = remember(measurer, density, tabs, selectedStyle, itemInsets) {
        tabs.map { label ->
            val widthPx = measurer.measure(
                text = AnnotatedString(label),
                style = selectedStyle,
                maxLines = 1,
                softWrap = false,
            ).size.width
            with(density) { widthPx.toDp() } + itemInsets
        }
    }

    // Track per-tab interaction sources so pressing any tab animates the
    // indicator press feedback and dims the pressed label.
    val interactionSources = remember(tabs.size) {
        List(tabs.size) { MutableInteractionSource() }
    }
    val pressedStates = interactionSources.map { it.collectIsPressedAsState().value }
    val anyPressed = pressedStates.any { it }

    val indicatorFill by animateColorAsState(
        targetValue = if (anyPressed) pressedIndicatorColor else baseIndicatorColor,
        animationSpec = if (anyPressed) {
            MiuixCapsuleTabRowDefaults.pressEnterColor()
        } else {
            MiuixCapsuleTabRowDefaults.pressExitColor()
        },
        label = "glassSegmentedTabIndicatorFill",
    )
    val innerHeightPx = with(density) {
        (MiuixCapsuleTabRowDefaults.Height - MiuixCapsuleTabRowDefaults.VerticalPadding * 2).toPx()
    }
    val indicatorScale by animateFloatAsState(
        targetValue = if (anyPressed) {
            // `glassPressScale`: takes 10dp off the smaller side, floored at 0.9f.
            val insetPx = with(density) { 10.dp.toPx() }
            if (innerHeightPx > 0f) {
                ((innerHeightPx - insetPx) / innerHeightPx).coerceIn(0.9f, 1f)
            } else {
                1f
            }
        } else {
            1f
        },
        animationSpec = if (anyPressed) {
            MiuixCapsuleTabRowDefaults.pressDownScale()
        } else {
            MiuixCapsuleTabRowDefaults.pressUpScale()
        },
        label = "glassSegmentedTabIndicatorScale",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(MiuixCapsuleTabRowDefaults.Height)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .softGlassShadow(
                    shape = shape,
                    isDark = isDark,
                    alpha = if (glassAvailable) rampState else 1f,
                )
                .clip(shape)
                .background(trackColor)
        )
        if (softGlass.enabled && backdrop != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (showGlass) rampState else 0f }
            ) {
                if (showGlass) {
                    SoftGlassSurface(
                        modifier = Modifier.fillMaxSize(),
                        shape = shape,
                        recipe = SoftGlassRecipes.floatingNavigation(softGlass.fineTuning),
                        backdrop = backdrop,
                    ) {}
                }
            }
        }

        val available = (
            maxWidth - MiuixCapsuleTabRowDefaults.HorizontalPadding * 2
            ).coerceAtLeast(0.dp)
        val itemWidths = capsuleTabItemWidths(labelWidths, available)
        val contentWidth = itemWidths.runWidth(itemWidths.size)
        val scrollable = contentWidth > available
        val scrollState = rememberScrollState()

        LaunchedEffect(selectedIndex, scrollable, available, itemWidths.size) {
            if (!scrollable) return@LaunchedEffect
            val startPx = with(density) {
                itemWidths.runWidth(selectedIndex).toPx()
            }.roundToInt()
            val endPx = with(density) {
                itemWidths.runWidth(selectedIndex + 1).toPx()
            }.roundToInt()
            val viewportPx = with(density) { available.toPx() }.roundToInt()
            when {
                startPx < scrollState.value -> scrollState.animateScrollTo(startPx)
                endPx > scrollState.value + viewportPx ->
                    scrollState.animateScrollTo(endPx - viewportPx)
            }
        }

        val scope = rememberCoroutineScope()
        val indicatorLeft = remember { Animatable(0f) }
        val indicatorRight = remember { Animatable(0f) }

        LaunchedEffect(selectedIndex, itemWidths) {
            val targetLeft = with(density) { itemWidths.runWidth(selectedIndex).toPx() }
            val targetRight = targetLeft + with(density) { itemWidths[selectedIndex].toPx() }
            if (indicatorRight.value <= indicatorLeft.value) {
                indicatorLeft.snapTo(targetLeft)
                indicatorRight.snapTo(targetRight)
            } else {
                val movingLeft = targetLeft <= indicatorLeft.value
                val leftSpec = MiuixCapsuleTabRowDefaults.edgeSpring(leading = movingLeft)
                val rightSpec = MiuixCapsuleTabRowDefaults.edgeSpring(leading = !movingLeft)
                scope.launch { indicatorLeft.animateTo(targetLeft, leftSpec) }
                scope.launch { indicatorRight.animateTo(targetRight, rightSpec) }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = MiuixCapsuleTabRowDefaults.HorizontalPadding,
                    vertical = MiuixCapsuleTabRowDefaults.VerticalPadding,
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .layout { measurable, constraints ->
                            val start = (indicatorLeft.value - scrollState.value).roundToInt()
                            val end = (indicatorRight.value - scrollState.value).roundToInt()
                            val width = (end - start).coerceAtLeast(0)
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = width, maxWidth = width)
                            )
                            layout(constraints.maxWidth, placeable.height) {
                                placeable.placeRelative(IntOffset(start, 0))
                            }
                        }
                        .graphicsLayer {
                            val scale = indicatorScale
                            val insetPx = (1f - scale) * size.height
                            scaleX = if (size.width > 0f) {
                                ((size.width - insetPx) / size.width).coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                            scaleY = scale
                        }
                        .clip(shape)
                        .drawBehind { drawRect(indicatorFill) }
                )
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(scrollState, enabled = scrollable)
                    .width(if (scrollable) contentWidth else available)
                    .fillMaxHeight()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val pressed = pressedStates[index]
                    val textColor by animateColorAsState(
                        targetValue = if (selected) selectedContentColor else contentColor,
                        animationSpec = MiuixCapsuleTabRowDefaults.ContentColorSpec,
                        label = "glassSegmentedTabColor_$index",
                    )
                    val pressAlpha by animateFloatAsState(
                        targetValue = if (pressed) {
                            MiuixCapsuleTabRowDefaults.PressedContentAlpha
                        } else {
                            1f
                        },
                        animationSpec = MiuixCapsuleTabRowDefaults.ContentFloatSpec,
                        label = "glassSegmentedTabPressAlpha_$index",
                    )
                    Box(
                        modifier = Modifier
                            .width(itemWidths[index])
                            .fillMaxHeight()
                            .clip(shape)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = interactionSources[index],
                                indication = null,
                                onClick = { onTabSelected(index) },
                            )
                            .graphicsLayer { alpha = pressAlpha },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = textColor,
                            style = if (selected) selectedStyle else unselectedStyle,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal fun capsuleTabItemWidths(
    labelWidths: List<Dp>,
    available: Dp,
): List<Dp> {
    if (labelWidths.isEmpty()) return emptyList()
    val natural = labelWidths.map {
        it.coerceAtLeast(MiuixCapsuleTabRowDefaults.MinimumItemWidth)
    }
    val total = natural.runWidth(natural.size)
    if (total >= available) return natural
    val share = (available - total) / natural.size.toFloat()
    return natural.map { it + share }
}

private fun List<Dp>.runWidth(count: Int): Dp {
    var width = 0.dp
    for (index in 0 until count.coerceAtMost(size)) width += this[index]
    return width
}
