package io.github.iamqwert.hypergreeze.ui.softglass

// MIUIX's NEW dropdown, aligned with miuix-glass (`GlassDropdownPopup` +
// `GlassPopupSurface` + `GlassMotion`) on the 0.9.4 classpath.
//
//  - panel: 24dp corner radius, 8dp vertical content padding, 200–288dp wide,
//    416dp tall before scrolling;
//  - row: 44dp min height, 20dp horizontal ink inset, body1 Medium, selected
//    option tinted with primary + 24dp check slot;
//  - press: 15dp rounded plate inset 7dp, 10% black/white, snap down +
//    folmeSpring(1f, 0.15f) release;
//  - open/close motion: dual-spring arc (`arcBounds` + `arcPosition`) where
//    width starts at 69% and aspect ratio at 0.2 (flat capsule) while corner
//    radius relaxes 4dp → 24dp, panel hidden for the first 10% of travel,
//    50ms linear fade in / 200ms fade out, and one-sided 30dp blur on exit;
//  - measured synchronously in one custom Layout pass so the first frame has
//    settled bounds immediately (no 1-frame hidden delay eating the 50ms fade);
//  - transition state lives INSIDE PopupLayout so mounting never drops the
//    opening spring, and host unmount waits for exit settle (`onDismissFinished`).

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

/** The numbers of MIUIX's glass dropdown, matching `GlassDropdownDefaults` & `GlassMotion`. */
object SoftGlassDropdownDefaults {

    /** Settled panel corner radius — `GlassDropdownDefaults.CornerRadius`. */
    val CornerRadius = 24.dp

    /** Radius the panel opens from — `GlassDropdownDefaults.StartCornerRadius`. */
    val StartCornerRadius = 4.dp

    val MinWidth = 200.dp
    val MaxWidth = 288.dp
    val MaxHeight = 416.dp

    /** Minimum margin from the window's safe area. */
    val SafeMargin = 12.dp

    /** Air above the first and below the last row. */
    val ContentPaddingVertical = 8.dp

    val ItemMinHeight = 44.dp

    /** The row's ink inset — the label starts here. */
    val ItemPaddingHorizontal = 20.dp

    val ItemPaddingVertical = 10.dp

    /** The tick's slot; reserved even when unselected. */
    val ItemTickSize = 24.dp

    val ItemIconGap = 8.dp

    /** Horizontal inset of the pressed plate. */
    val ItemPressInset = 7.dp

    val ItemPressRadius = 15.dp

    /** Width the panel opens from, as a fraction of its settled width (`ARC_START_WIDTH`). */
    internal const val ArcStartWidth = 0.69f

    /** Initial height/width aspect ratio (`ARC_START_RATIO`). */
    internal const val ArcStartRatio = 0.2f

    /** Size progress threshold below which the opening panel stays transparent (`ARC_VISIBLE_FRACTION`). */
    internal const val ArcVisibleFraction = 0.1f

    /** How far the panel blurs itself as it leaves, in dp (`ARC_EXIT_BLUR_DP`). */
    internal const val ExitBlurDp = 30f

    private const val TransformVisibilityThreshold = 0.0015f

    /** `GlassMotion.arcBounds` with Folme settle threshold. */
    internal fun arcBoundsSpring(entering: Boolean): SpringSpec<Float> = folmeSpring(
        damping = 0.8f,
        response = if (entering) 0.35f else 0.22f,
        visibilityThreshold = TransformVisibilityThreshold,
    )

    /** `GlassMotion.arcPosition` with Folme settle threshold. */
    internal fun arcPositionSpring(entering: Boolean): SpringSpec<Float> = folmeSpring(
        damping = 0.8f,
        response = if (entering) 0.22f else 0.35f,
        visibilityThreshold = TransformVisibilityThreshold,
    )

    /** `GlassMotion.arcFade`: 50ms linear in, 200ms linear out. */
    internal fun fadeSpec(entering: Boolean) = tween<Float>(
        durationMillis = if (entering) 50 else 200,
        easing = LinearEasing,
    )

    /** `GlassMotion.arcBlur`: sharp on the way in, 200ms blur on the way out. */
    internal fun sharpnessSpec(entering: Boolean) =
        if (entering) snap<Float>() else tween(durationMillis = 200, easing = LinearEasing)

    /** `GlassMotion.popupPressExit`: critically damped release on menu rows. */
    internal fun pressExitSpring(): SpringSpec<Color> = folmeSpring(
        damping = 1f,
        response = 0.15f,
    )
}

/**
 * MIUIX's anchored glass dropdown.
 *
 * Call with the row's window bounds ([Rect] from `boundsInWindow()`), the page
 * layer the panel may sample, and the option rows ([SoftGlassDropdownItem]).
 */
@Composable
fun SoftGlassDropdownMenu(
    show: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: Rect,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Keep the host mounted from the moment `show` flips true until the inner
    // exit transition actually settles (`onDismissFinished`). The transition
    // itself lives inside [PopupLayout] so entering always starts at 0f on the
    // first frame the host composes.
    var mounted by remember { mutableStateOf(false) }
    if (show && !mounted) {
        mounted = true
    }
    val hostVisible = remember { mutableStateOf(false) }
    hostVisible.value = mounted

    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = show,
        onBackCompleted = onDismissRequest
    )

    if (!mounted) return

    PopupLayout(
        visible = hostVisible,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableWindowDim = false,
        enableBackHandler = false,
        renderInRootScaffold = true
    ) {
        SoftGlassDropdownHost(
            show = show,
            onDismissRequest = onDismissRequest,
            onDismissFinished = { mounted = false },
            anchorBounds = anchorBounds,
            backdrop = backdrop,
            modifier = modifier,
            content = content
        )
    }
}

@Composable
private fun SoftGlassDropdownHost(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    anchorBounds: Rect,
    backdrop: LayerBackdrop?,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)

    // Starts closed inside the host so the very first frame runs the full
    // entering spring from 0f → 1f.
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(show) {
        visibility.targetState = show
    }
    val transition = rememberTransition(visibility, label = "softGlassDropdown")
    val sizeProgress = transition.animateFloat(
        transitionSpec = { SoftGlassDropdownDefaults.arcBoundsSpring(targetState) },
        label = "softGlassDropdownSize",
    ) { if (it) 1f else 0f }
    val positionProgress = transition.animateFloat(
        transitionSpec = { SoftGlassDropdownDefaults.arcPositionSpring(targetState) },
        label = "softGlassDropdownPosition",
    ) { if (it) 1f else 0f }
    val fade = transition.animateFloat(
        transitionSpec = { SoftGlassDropdownDefaults.fadeSpec(targetState) },
        label = "softGlassDropdownFade",
    ) { if (it) 1f else 0f }
    val sharpness = transition.animateFloat(
        transitionSpec = { SoftGlassDropdownDefaults.sharpnessSpec(targetState) },
        label = "softGlassDropdownSharpness",
    ) { if (it) 1f else 0f }

    val active by remember(show, sizeProgress) {
        derivedStateOf { show || sizeProgress.value > 0.0001f }
    }
    LaunchedEffect(show, active) {
        if (!show && !active) {
            currentOnDismissFinished()
        }
    }
    if (!active) return

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val displayCutout = WindowInsets.displayCutout
    val statusBars = WindowInsets.statusBars
    val navigationBars = WindowInsets.navigationBars
    val captionBar = WindowInsets.captionBar
    val containerSize = LocalWindowInfo.current.containerSize
    val windowBounds = remember(
        layoutDirection,
        density,
        displayCutout,
        statusBars,
        navigationBars,
        captionBar,
        containerSize
    ) {
        with(density) {
            IntRect(
                left = displayCutout.getLeft(this, layoutDirection),
                top = statusBars.getTop(this),
                right = containerSize.width - displayCutout.getRight(this, layoutDirection),
                bottom = containerSize.height - navigationBars.getBottom(this) - captionBar.getBottom(this),
            )
        }
    }
    val windowRect = remember(windowBounds) {
        Rect(
            windowBounds.left.toFloat(),
            windowBounds.top.toFloat(),
            windowBounds.right.toFloat(),
            windowBounds.bottom.toFloat(),
        )
    }

    val hostOrigin = remember { mutableStateOf(Offset.Zero) }
    val measured = remember { MeasuredDropdownPanel() }
    val shape = remember(measured) {
        val corner = MeasuredDropdownCorner(measured)
        RoundedCornerShape(corner, corner, corner, corner)
    }
    val isDark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-page outside-tap dismiss layer (no dim).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val released = change.changedToUp()
                            change.consume()
                            if (!change.pressed) {
                                if (released) currentOnDismissRequest()
                                break
                            }
                        }
                    }
                }
        )

        Layout(
            modifier = modifier
                .onGloballyPositioned { hostOrigin.value = it.positionInWindow() }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            (measured.rect.left - hostOrigin.value.x).roundToInt(),
                            (measured.rect.top - hostOrigin.value.y).roundToInt(),
                        )
                    }
                }
                .graphicsLayer {
                    val t = sizeProgress.value.coerceAtLeast(0f)
                    alpha = if (show && t <= SoftGlassDropdownDefaults.ArcVisibleFraction) {
                        0f
                    } else {
                        fade.value
                    }
                    val blurPx = (1f - sharpness.value.coerceIn(0f, 1f)) *
                        SoftGlassDropdownDefaults.ExitBlurDp * this.density
                    renderEffect = if (blurPx > 0.5f) {
                        BlurEffect(blurPx, blurPx, TileMode.Decal)
                    } else {
                        null
                    }
                }
                .softGlassShadow(shape = shape, isDark = isDark)
                .clip(shape)
                .then(
                    if (show) {
                        Modifier
                    } else {
                        Modifier
                            .clearAndSetSemantics { }
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial)
                                            .changes
                                            .forEach { it.consume() }
                                    }
                                }
                            }
                    }
                ),
            content = {
                SoftGlassSurface(
                    shape = shape,
                    cornerRadius = measured.cornerRadius,
                    recipe = SoftGlassRecipes.floatingNavigation(LocalSoftGlassSpec.current.fineTuning),
                    backdrop = backdrop,
                    content = {}
                )
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = SoftGlassDropdownDefaults.ContentPaddingVertical),
                    content = content
                )
            }
        ) { measurables, constraints ->
            val safeMarginPx = SoftGlassDropdownDefaults.SafeMargin.roundToPx()
            val safeInset = safeMarginPx * 2
            val widest = SoftGlassDropdownDefaults.MaxWidth.roundToPx()
                .coerceAtMost((constraints.maxWidth - safeInset).coerceAtLeast(0))
            val narrowest = SoftGlassDropdownDefaults.MinWidth.roundToPx().coerceAtMost(widest)
            val tallest = SoftGlassDropdownDefaults.MaxHeight.roundToPx()
                .coerceAtMost((constraints.maxHeight - safeInset).coerceAtLeast(0))

            val width = measurables[1].maxIntrinsicWidth(tallest).coerceIn(narrowest, widest)
            val viewportHeight = measurables[1].minIntrinsicHeight(width).coerceAtMost(tallest)
            val foreground = measurables[1].measure(
                Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = viewportHeight)
            )
            val endSize = Size(foreground.width.toFloat(), foreground.height.toFloat())

            val origin = hostOrigin.value
            val placementBounds = Rect(
                left = maxOf(windowRect.left, origin.x),
                top = maxOf(windowRect.top, origin.y),
                right = minOf(windowRect.right, origin.x + constraints.maxWidth)
                    .coerceAtLeast(windowRect.left),
                bottom = minOf(windowRect.bottom, origin.y + constraints.maxHeight)
                    .coerceAtLeast(windowRect.top),
            )
            val frame = arcDropdownFrame(
                anchor = anchorBounds,
                end = endSize,
                window = placementBounds,
                margin = SoftGlassDropdownDefaults.SafeMargin.toPx(),
                sizeFraction = sizeProgress.value.coerceAtLeast(0f),
                positionFraction = positionProgress.value.coerceAtLeast(0f),
                startRadius = SoftGlassDropdownDefaults.StartCornerRadius,
                endRadius = SoftGlassDropdownDefaults.CornerRadius,
                direction = layoutDirection,
            )
            measured.rect = frame.rect
            measured.cornerRadius = frame.cornerRadius

            val panelWidth = frame.rect.width.roundToInt().coerceAtLeast(0)
            val panelHeight = frame.rect.height.roundToInt().coerceAtLeast(0)
            val localLeft = frame.rect.left - origin.x
            val localTop = frame.rect.top - origin.y

            val background = measurables[0].measure(Constraints.fixed(panelWidth, panelHeight))
            layout(panelWidth, panelHeight) {
                background.place(0, 0)
                foreground.placeWithLayer(0, 0) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    val fraction = SoftGlassDropdownDefaults.ArcStartWidth +
                        (1f - SoftGlassDropdownDefaults.ArcStartWidth) *
                        sizeProgress.value.coerceAtLeast(0f)
                    val scale = fraction.coerceAtLeast(0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = localLeft - localLeft.roundToInt()
                    translationY = localTop - localTop.roundToInt()
                }
            }
        }
    }
}

/**
 * One option row, in the library's new language: 44dp tall, the current option
 * in the accent with a tick, a press plate inset 7dp and rounded 15dp.
 */
@Composable
fun SoftGlassDropdownItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val pressedColor = if (isDark) {
        Color.White.copy(alpha = 0.1f)
    } else {
        Color.Black.copy(alpha = 0.1f)
    }
    val pressFill by animateColorAsState(
        targetValue = if (pressed) pressedColor else Color.Transparent,
        animationSpec = if (pressed) {
            snap()
        } else {
            SoftGlassDropdownDefaults.pressExitSpring()
        },
        label = "softGlassDropdownItemPress"
    )
    val contentColor = when {
        !enabled -> MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.4f)
        selected -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceContainer
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SoftGlassDropdownDefaults.ItemMinHeight)
            .padding(horizontal = SoftGlassDropdownDefaults.ItemPressInset)
            .drawBehind {
                if (pressFill.alpha > 0f) {
                    drawRoundRect(
                        color = pressFill,
                        cornerRadius = CornerRadius(
                            SoftGlassDropdownDefaults.ItemPressRadius.toPx()
                        )
                    )
                }
            }
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = SoftGlassDropdownDefaults.ItemPaddingHorizontal -
                    SoftGlassDropdownDefaults.ItemPressInset,
                vertical = SoftGlassDropdownDefaults.ItemPaddingVertical
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = MiuixIcons.Basic.Check,
            contentDescription = null,
            modifier = Modifier
                .padding(start = SoftGlassDropdownDefaults.ItemIconGap)
                .size(SoftGlassDropdownDefaults.ItemTickSize),
            tint = if (selected) MiuixTheme.colorScheme.primary else Color.Transparent
        )
    }
}

private data class SoftGlassDropdownPlacement(
    val rect: Rect,
    val alignTop: Boolean
)

private data class SoftGlassDropdownFrame(
    val rect: Rect,
    val cornerRadius: Dp
)

private class MeasuredDropdownPanel {
    var rect: Rect = Rect.Zero
    var cornerRadius: Dp = SoftGlassDropdownDefaults.CornerRadius
}

private class MeasuredDropdownCorner(
    private val measured: MeasuredDropdownPanel
) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        with(density) { measured.cornerRadius.toPx() }
}

private fun placeSoftGlassDropdown(
    anchor: Rect,
    size: Size,
    margin: Float,
    window: Rect,
    direction: LayoutDirection
): SoftGlassDropdownPlacement {
    val endAlignedLeft = if (direction == LayoutDirection.Ltr) {
        anchor.right - size.width
    } else {
        anchor.left
    }
    val minLeft = window.left + margin
    val left = endAlignedLeft.coerceIn(
        minLeft,
        (window.right - size.width - margin).coerceAtLeast(minLeft)
    )
    val minTop = minOf(window.top + margin, anchor.top).coerceAtLeast(window.top)
    val below = window.bottom - margin - anchor.top
    val above = anchor.bottom - minTop
    val alignTop = size.height <= below || below >= above
    val wanted = if (alignTop) anchor.top else anchor.bottom - size.height
    val top = wanted.coerceIn(
        minTop,
        (window.bottom - size.height - margin).coerceAtLeast(minTop)
    )
    return SoftGlassDropdownPlacement(
        rect = Rect(left, top, left + size.width, top + size.height),
        alignTop = alignTop
    )
}

/**
 * Interpolates size and centre independently for `GlassDropdownPopup`'s curved
 * arc motion (flat 69%-wide capsule opening out to full width and height).
 */
private fun arcDropdownFrame(
    anchor: Rect,
    end: Size,
    window: Rect,
    margin: Float,
    sizeFraction: Float,
    positionFraction: Float,
    startRadius: Dp,
    endRadius: Dp,
    direction: LayoutDirection,
): SoftGlassDropdownFrame {
    val placement = placeSoftGlassDropdown(anchor, end, margin, window, direction)
    val startWidth = end.width * SoftGlassDropdownDefaults.ArcStartWidth
    val startHeight = startWidth * SoftGlassDropdownDefaults.ArcStartRatio
    val width = startWidth + (end.width - startWidth) * sizeFraction
    val endRatio = if (end.width > 0f) end.height / end.width else 1f
    val ratio = SoftGlassDropdownDefaults.ArcStartRatio +
        (endRatio - SoftGlassDropdownDefaults.ArcStartRatio) * sizeFraction
    val height = ratio * width

    val alignLeft = direction == LayoutDirection.Rtl
    val startCenterX = if (alignLeft) {
        placement.rect.left + startWidth / 2f
    } else {
        placement.rect.right - startWidth / 2f
    }
    val startCenterY = if (placement.alignTop) {
        placement.rect.top + startHeight / 2f
    } else {
        placement.rect.bottom - startHeight / 2f
    }
    val centerX = startCenterX + (placement.rect.center.x - startCenterX) * positionFraction
    val centerY = startCenterY + (placement.rect.center.y - startCenterY) * positionFraction
    val radius = startRadius + (endRadius - startRadius) * sizeFraction
    return SoftGlassDropdownFrame(
        rect = Rect(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            right = centerX + width / 2f,
            bottom = centerY + height / 2f,
        ),
        cornerRadius = radius,
    )
}
