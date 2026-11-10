package io.github.howard20181.hyperos.fcmlive.ui.softglass

// The layout, motion and semantics are MIUIX's GlassNavigationBar (miuix-glass,
// top.yukonga.miuix.kmp.glass.GlassNavigationBar); the glass itself is this
// app's own soft-glass stack, because miuix-glass ships in the same 0.9.4 line
// but is not published as an artifact yet — and because 柔光玻璃 is defined by
// its own material, not by the library's PuredThinGlass.
//
// What is carried over verbatim, because it is what makes the bar feel like
// the system's: the capsule silhouette, the two-armed indicator (leading and
// trailing edges on different springs, so it stretches while it travels), the
// drag that starts anywhere and picks a destination on the way down, the press
// scaling of the chip, and the 0.6 destination fade under the finger.
//
// What is NOT carried over: the library's `visible` show/hide choreography, its
// `trailingAction` slot and its `alpha` multiplier. This bar is always on
// screen and has no action beside it, and a parameter nothing passes is a
// branch nothing tests.

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One destination of a [SoftGlassNavigationBar].
 *
 * @property icon the glyph; 柔光玻璃 draws one cut for both states, as the look
 *   does everywhere else — selection is the bar's ink and chip, not a fill swap.
 * @property label the caption under the glyph. Null leaves the glyph alone, and
 *   then [contentDescription] is the only thing a screen reader has to read.
 * @property contentDescription overrides [label] for accessibility.
 */
@Immutable
data class SoftGlassNavigationItem(
    val icon: ImageVector,
    val label: String? = null,
    val contentDescription: String? = null,
)

/**
 * MIUIX's GlassNavigationBar numbers. The values are the library's own defaults
 * (GlassNavigationBarDefaults), not a reading of them: the bar is being adopted
 * as the spec writes it, so a later library release can be diffed against this
 * table one constant at a time.
 */
object SoftGlassNavigationBarDefaults {

    /** Minimum height; a two-line caption or a large font scale can grow it. */
    val Height = 54.dp

    /** Glyph size. HEAD of the library moved this from 28dp to 25dp. */
    val IconSize = 25.dp

    /** Caption size, converted to sp at the current density. */
    val LabelSize = 11.dp

    /** Caption size once the system font scale reaches 1.6. */
    val LargeLabelSize = 16.dp

    /** Air above and below the icon-and-caption group. */
    val ContentPaddingVertical = 6.dp

    /** Gap between a glyph and its caption. */
    val LabelSpacing = 0.dp

    /** Inset between the capsule's top and bottom and the indicator chip. */
    val IndicatorPaddingVertical = 3.dp

    /** The bar's own horizontal inset: where the destinations are laid out from. */
    val ContentPaddingHorizontal = 8.dp

    /** How far the chip reaches past its destination's share, on each side. */
    val IndicatorOverhang = 5.dp

    /** Opacity of a destination under the finger. */
    val PressedAlpha = 0.6f

    /** The floating margin riding on top of the navigation-bar inset. */
    val FloatingMargin = 16.dp

    /** The floating margin when there is no navigation-bar inset to ride on. */
    val FloatingMarginNoInset = 32.dp

    /** The capsule: the library's GlassShape(CornerSize(50)) — a full capsule. */
    val Shape: Shape = RoundedCornerShape(percent = 50)

    /** The chip behind the selected destination. */
    val IndicatorShape: Shape = RoundedCornerShape(percent = 50)

    @Composable
    fun isDark(): Boolean = MiuixTheme.colorScheme.background.luminance() < 0.5f

    /** Resting chip fill: the theme tint at six percent in light, twelve in dark. */
    @Composable
    fun indicatorColor(): Color = chipTint().copy(alpha = if (isDark()) 0.12f else 0.06f)

    /** Pressed chip fill: the same tint, heavier. */
    @Composable
    fun indicatorPressedColor(): Color = chipTint().copy(alpha = if (isDark()) 0.26f else 0.16f)

    /**
     * The chip's tint: the dynamic primary when 莫奈颜色 is on, plain black or
     * white otherwise. A static palette's primary is the seed's accent, which
     * would paint the chip a colour the page never uses.
     */
    @Composable
    private fun chipTint(): Color = when {
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.primary
        isDark() -> Color.White
        else -> Color.Black
    }

    /** Selected destination ink: the primary under dynamic colour, else onBackground. */
    @Composable
    fun selectedColor(): Color = if (MiuixTheme.isDynamicColor) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onBackground
    }

    /** Unselected destination ink. The library applies no extra dimming to it. */
    @Composable
    fun unselectedColor(): Color = MiuixTheme.colorScheme.onBackground
}

/**
 * The bottom margin the bar keeps at the foot of the screen: the library's own
 * floating formula, exposed so a page can reserve exactly the bar's extent in
 * its clearance instead of guessing it.
 */
internal fun softGlassNavigationBarBottomMargin(navigationBarInset: Dp): Dp =
    if (navigationBarInset == 0.dp) {
        SoftGlassNavigationBarDefaults.FloatingMarginNoInset
    } else {
        SoftGlassNavigationBarDefaults.FloatingMargin + navigationBarInset
    }

/**
 * A floating bottom bar on 柔光玻璃, after MIUIX's GlassNavigationBar.
 *
 * The indicator follows the pointer while dragging and settles from its current
 * position and velocity on release, without ever overshooting past the outermost
 * destinations. Pressing anywhere selects that destination immediately — that is
 * the system bar's behaviour, and the reason a finger can land and slide in one
 * motion.
 *
 * @param items the destinations, in order.
 * @param selectedIndex the destination the chip sits under; clamped into range.
 * @param onSelect called with the index under the finger, on press and while dragging.
 * @param backdrop the content behind the glass; null fills the capsule with the
 *   theme's surfaceContainer instead of sampling.
 */
@Composable
fun SoftGlassNavigationBar(
    items: List<SoftGlassNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop? = LocalSoftGlassBackdrop.current,
    modifier: Modifier = Modifier,
    height: Dp = SoftGlassNavigationBarDefaults.Height,
) {
    if (items.isEmpty()) return

    val index = selectedIndex.coerceIn(0, items.lastIndex)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val softGlass = LocalSoftGlassSpec.current
    val shape = SoftGlassNavigationBarDefaults.Shape
    val isDark = SoftGlassNavigationBarDefaults.isDark()

    val labelSize = with(density) {
        if (fontScale >= LARGE_FONT_SCALE) {
            SoftGlassNavigationBarDefaults.LargeLabelSize.toSp()
        } else {
            SoftGlassNavigationBarDefaults.LabelSize.toSp()
        }
    }
    val contentInset = with(density) { SoftGlassNavigationBarDefaults.ContentPaddingHorizontal.toPx() }
    val overhang = with(density) { SoftGlassNavigationBarDefaults.IndicatorOverhang.toPx() }

    var barWidth by remember { mutableIntStateOf(0) }
    var pressedIndex by remember { mutableIntStateOf(-1) }
    val dragging = remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }

    fun animateIndicator(
        newLeft: Float,
        newRight: Float,
        leftSpring: SpringSpec<Float> = edgeSpring(newLeft <= left.value),
        rightSpring: SpringSpec<Float> = edgeSpring(newLeft > left.value),
    ) {
        // Both motions own the same two Animatables, retargeted through
        // animateTo so position and velocity survive drag, reversal, selection
        // and release interruptions. The jobs belong to the composition, not to
        // a restarting selection effect.
        scope.launch { left.animateTo(newLeft, leftSpring) }
        scope.launch { right.animateTo(newRight, rightSpring) }
    }

    val slot = if (barWidth <= 0) 0f else (barWidth - contentInset * 2f) / items.size
    val chipWidth = slot + overhang * 2f
    val targetLeft = contentInset - overhang + index * slot
    val targetRight = targetLeft + chipWidth

    LaunchedEffect(targetLeft, targetRight, dragging.value) {
        if (slot <= 0f) return@LaunchedEffect
        if (dragging.value) return@LaunchedEffect
        if (right.value <= left.value) {
            left.snapTo(targetLeft)
            right.snapTo(targetRight)
            return@LaunchedEffect
        }
        animateIndicator(targetLeft, targetRight)
    }

    val chipHeldPx = with(density) {
        (height - SoftGlassNavigationBarDefaults.IndicatorPaddingVertical * 2).toPx()
    }
    val chipPressed = pressedIndex >= 0
    val chipScale by animateFloatAsState(
        targetValue = if (chipPressed) glassPressScale(chipHeldPx, density.density) else 1f,
        animationSpec = if (chipPressed) pressDown() else pressUp(),
        label = "softGlassNavigationChipPress",
    )
    val chipFill = animateColorAsState(
        targetValue = if (chipPressed) {
            SoftGlassNavigationBarDefaults.indicatorPressedColor()
        } else {
            SoftGlassNavigationBarDefaults.indicatorColor()
        },
        animationSpec = if (chipPressed) navPressEnter() else navPressExit(),
        label = "softGlassNavigationChipFill",
    )

    val onSelectState by rememberUpdatedState(onSelect)
    val bottomPadding = with(density) {
        val navigationBarBottom = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
            .asPaddingValues().calculateBottomPadding()
        val captionBarBottom = WindowInsets.captionBar.only(WindowInsetsSides.Bottom)
            .asPaddingValues().calculateBottomPadding()
        val animatedCaptionBarHeight by animateDpAsState(
            targetValue = captionBarBottom.coerceAtLeast(0.dp),
            animationSpec = tween(durationMillis = CAPTION_BAR_ANIM_MS),
            label = "softGlassNavigationCaptionBar",
        )
        softGlassNavigationBarBottomMargin(navigationBarBottom) + animatedCaptionBarHeight
    }

    // The foot margin is a wrapper, not padding on the surface: padding on the
    // glass would shorten the capsule instead of lifting it off the inset.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
    ) {
        SoftGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .softGlassShadow(shape = shape, isDark = isDark)
                // The whole bar is one gesture surface. A down lands on whichever
                // destination it is over and selects it at once; past the touch
                // slop the indicator is dragged and the pointer owns the selection.
                .pointerInput(items.size, contentInset, overhang, layoutDirection) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val selectionWidth = size.width.toFloat()
                        if (selectionWidth <= contentInset * 2f) return@awaitEachGesture
                        // Hit regions are frozen for this gesture: a destination
                        // change resizes the chip, which must not select another.
                        fun logicalX(x: Float) =
                            if (layoutDirection == LayoutDirection.Rtl) size.width - x else x

                        fun indexAt(x: Float): Int {
                            val span = (selectionWidth - contentInset * 2f) / items.size
                            return if (span <= 0f) {
                                0
                            } else {
                                ((x - contentInset) / span).toInt().coerceIn(0, items.lastIndex)
                            }
                        }

                        val minLeft = contentInset - overhang
                        val downX = logicalX(down.position.x)
                        var selectionX = downX
                        var current = indexAt(downX)
                        pressedIndex = current
                        onSelectState(current)
                        var grabOffset = Float.NaN
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed || change.isConsumed) break
                                val width = size.width.toFloat()
                                val chipW = (width - contentInset * 2f) / items.size + overhang * 2f
                                val maxLeft = (width - chipW).coerceAtLeast(0f)
                                val x = logicalX(change.position.x)
                                // Both positions are local, so their delta excludes
                                // any movement caused by the chip's own placement.
                                val pointerDelta = change.position.x - change.previousPosition.x
                                val delta = if (layoutDirection == LayoutDirection.Rtl) {
                                    -pointerDelta
                                } else {
                                    pointerDelta
                                }
                                selectionX += delta
                                if (grabOffset.isNaN()) {
                                    if (abs(selectionX - downX) < viewConfiguration.touchSlop) {
                                        pressedIndex = indexAt(selectionX)
                                        continue
                                    }
                                    val bounds = navigationIndicatorBounds(
                                        left.value,
                                        right.value,
                                        width,
                                        minLeft,
                                    )
                                    grabOffset = x - bounds.x
                                    dragging.value = true
                                }
                                val chipLeft = (x - grabOffset).coerceIn(0f, maxLeft)
                                val next = indexAt(selectionX)
                                val target = navigationDragTarget(
                                    chipLeft,
                                    chipW,
                                    width,
                                    delta,
                                    next != current,
                                )
                                animateIndicator(
                                    target.left,
                                    target.right,
                                    target.leftSpring,
                                    target.rightSpring,
                                )
                                if (next != current) {
                                    current = next
                                    onSelectState(next)
                                }
                                pressedIndex = next
                                change.consume()
                            }
                        } finally {
                            pressedIndex = -1
                            dragging.value = false
                            // Releasing restarts the selection effect against the
                            // latest size; do not settle to geometry captured at
                            // pointer down.
                        }
                    }
                },
            shape = shape,
            recipe = SoftGlassRecipes.floatingNavigation(softGlass.fineTuning),
            interaction = GlassMaterialInteraction(
                refractionScale = if (chipPressed) 1.06f else 1f,
                highlightScale = if (chipPressed) 1.04f else 1f,
            ),
            backdrop = backdrop,
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = height)
                    .onSizeChanged { barWidth = it.width },
                contentAlignment = Alignment.Center,
            ) {
                // The chip. Its own layer, clipped to a capsule, laid out to the two
                // Animatable edges and clamped every frame — never a fixed width
                // sliding by offset, which is what makes the stretch visible.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(vertical = SoftGlassNavigationBarDefaults.IndicatorPaddingVertical)
                        .layout { measurable, constraints ->
                            val bounds = navigationIndicatorBounds(
                                left.value,
                                right.value,
                                constraints.maxWidth.toFloat(),
                                contentInset - overhang,
                            )
                            val start = bounds.x.roundToInt()
                            val width = (bounds.y.roundToInt() - start).coerceAtLeast(0)
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = width, maxWidth = width),
                            )
                            layout(constraints.maxWidth, placeable.height) {
                                placeable.placeRelative(IntOffset(start, 0))
                            }
                        }
                        .graphicsLayer {
                            scaleX = chipScale
                            scaleY = chipScale
                        }
                        .clip(SoftGlassNavigationBarDefaults.IndicatorShape)
                        .drawBehind { drawRect(chipFill.value) },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup()
                        .padding(horizontal = SoftGlassNavigationBarDefaults.ContentPaddingHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { position, item ->
                        key(item) {
                            val selected = position == index
                            val tint by animateColorAsState(
                                targetValue = if (selected) {
                                    SoftGlassNavigationBarDefaults.selectedColor()
                                } else {
                                    SoftGlassNavigationBarDefaults.unselectedColor()
                                },
                                animationSpec = navContent(),
                                label = "softGlassNavigationTint",
                            )
                            val pressAlpha by animateFloatAsState(
                                targetValue = if (position == pressedIndex) {
                                    SoftGlassNavigationBarDefaults.PressedAlpha
                                } else {
                                    1f
                                },
                                animationSpec = navContentFloat(),
                                label = "softGlassNavigationPress",
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(
                                        horizontal = SoftGlassNavigationBarDefaults.IndicatorOverhang,
                                        vertical = SoftGlassNavigationBarDefaults.ContentPaddingVertical,
                                    )
                                    // The chip is the pointer's; the semantics below
                                    // are TalkBack's and a keyboard's, and they are
                                    // the only way a destination is reachable
                                    // without a finger.
                                    .semantics(mergeDescendants = true) {
                                        this.role = Role.Tab
                                        this.selected = selected
                                        (item.contentDescription ?: item.label)?.let {
                                            this.contentDescription = it
                                        }
                                        onClick {
                                            onSelectState(position)
                                            true
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        val activationKey = event.key == Key.Enter ||
                                            event.key == Key.NumPadEnter ||
                                            event.key == Key.Spacebar
                                        if (activationKey) {
                                            if (event.type == KeyEventType.KeyUp) {
                                                onSelectState(position)
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    .focusable()
                                    .graphicsLayer { alpha = pressAlpha },
                                contentAlignment = Alignment.Center,
                            ) {
                                DestinationContent(
                                    item = item,
                                    tint = tint,
                                    labelSize = labelSize,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The glyph-over-caption stack MIUIX draws inside a destination. */
@Composable
private fun DestinationContent(
    item: SoftGlassNavigationItem,
    tint: Color,
    labelSize: TextUnit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SoftGlassNavigationBarDefaults.LabelSpacing),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(SoftGlassNavigationBarDefaults.IconSize),
            tint = tint,
        )
        if (item.label != null) {
            Text(
                text = item.label,
                style = MiuixTheme.textStyles.footnote2.copy(fontSize = labelSize),
                color = tint,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The library's press scaling: a fixed inset shaved off the shorter side. */
private fun glassPressScale(shorterSidePx: Float, density: Float): Float = if (shorterSidePx <= 0f) {
    1f
} else {
    max(
        (shorterSidePx - PRESS_INSET_DP * density) / shorterSidePx,
        PRESS_SCALE_MIN,
    )
}

/**
 * MIUIX's navigation springs, by the library's own (damping, response) pairs —
 * [folmeSpring] turns a response time into a stiffness, so these stay readable
 * as the numbers GlassMotion names.
 */
private fun <T> navIndicator(): SpringSpec<T> = folmeSpring(damping = 0.7f, response = 0.4f)

private fun <T> navIndicatorTrail(): SpringSpec<T> = folmeSpring(damping = 0.75f, response = 0.5f)

private fun <T> navDragFollow(): SpringSpec<T> = folmeSpring(damping = 1f, response = 0.15f)

private fun edgeSpring(leading: Boolean): SpringSpec<Float> =
    if (leading) navIndicator() else navIndicatorTrail()

private fun pressDown(): SpringSpec<Float> = folmeSpring(damping = 0.99f, response = 0.15f)

private fun pressUp(): SpringSpec<Float> = folmeSpring(damping = 0.99f, response = 0.3f)

private fun navPressEnter(): SpringSpec<Color> = folmeSpring(damping = 1f, response = 0.2f)

private fun navPressExit(): SpringSpec<Color> = folmeSpring(damping = 0.95f, response = 0.35f)

/** The curve the bar grades its own contents on. */
private fun navContent() = tween<Color>(
    durationMillis = NAV_CONTENT_ANIM_MS,
    easing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f),
)

private fun navContentFloat() = tween<Float>(
    durationMillis = NAV_CONTENT_ANIM_MS,
    easing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f),
)

/** The indicator's raw drag target, before the press transform is applied. */
internal data class NavigationDragTarget(
    val left: Float,
    val right: Float,
    val following: Boolean,
    val movingRight: Boolean,
) {
    val leftSpring: SpringSpec<Float>
        get() = if (following) navDragFollow() else edgeSpring(!movingRight)

    val rightSpring: SpringSpec<Float>
        get() = if (following) navDragFollow() else edgeSpring(movingRight)
}

/**
 * Same-destination trail versus cross-destination edge spring.
 *
 * The 60px trail cap is deliberately not density-scaled — it is the library's
 * number. Raw targets may extend past the container; [navigationIndicatorBounds]
 * limits what is rendered, not the spring behind it.
 */
internal fun navigationDragTarget(
    left: Float,
    width: Float,
    containerWidth: Float,
    delta: Float,
    changedItem: Boolean,
): NavigationDragTarget {
    val start = left.coerceIn(0f, (containerWidth - width).coerceAtLeast(0f))
    val end = start + width
    val trail = if (changedItem) 0f else (abs(delta) * 4f).coerceAtMost(60f)
    return NavigationDragTarget(
        left = if (delta > 0f && end < containerWidth) start - trail else start,
        right = if (delta < 0f && start > 0f) end + trail else end,
        following = !changedItem,
        movingRight = delta > 0f,
    )
}

/** Clamps rendered edges to the outermost destinations without stopping the spring. */
internal fun navigationIndicatorBounds(
    left: Float,
    right: Float,
    containerWidth: Float,
    inset: Float,
): Offset {
    val min = inset.coerceIn(0f, containerWidth.coerceAtLeast(0f) / 2f)
    val max = (containerWidth - min).coerceAtLeast(min)
    val start = left.coerceIn(min, max)
    return Offset(start, right.coerceIn(start, max))
}

private const val PRESS_INSET_DP = 10f
private const val PRESS_SCALE_MIN = 0.9f
private const val NAV_CONTENT_ANIM_MS = 150
private const val CAPTION_BAR_ANIM_MS = 300
private const val LARGE_FONT_SCALE = 1.6f
