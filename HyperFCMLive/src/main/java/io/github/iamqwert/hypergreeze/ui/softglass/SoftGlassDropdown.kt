package io.github.iamqwert.hypergreeze.ui.softglass

// MIUIX's NEW dropdown, rebuilt on the 0.9.4 classpath.
//
// The library ships it in miuix-glass (`top.yukonga.miuix.kmp.glass`), which
// publishes no 0.9.4 artifact — miuix-glass-android/0.9.4/*.pom is a 404. What
// is reproduced here is that component's contract, read off
// GlassDropdownPreference / GlassDropdownPopup / GlassPopupItem:
//
//  - the panel is glass, 24dp corner radius, 8dp of vertical content padding,
//    200–288dp wide and 416dp tall before it scrolls;
//  - a row is 44dp tall, 20dp of horizontal ink inset, body1 Medium, and the
//    current option is the accent with a 24dp tick (reserved even when
//    unselected, so labels do not shift);
//  - a press is a 15dp rounded plate inset 7dp, 10% black/white;
//  - opening is the library's arc: the panel grows from the anchor-side corner
//    (69% of its width) while its radius relaxes 4dp → 24dp, on a size spring
//    and a quicker position spring, with a 50ms linear fade;
//  - closing reverses it and blurs itself 30dp on the way out;
//  - there is NO window dim — the new style leaves the page legible behind it —
//    and the full-page layer exists only to take the outside tap.
//
// Two things are the module's, not the library's: the host (0.9.4's public
// `MiuixPopupUtils.PopupLayout`, the same host the library's overlay popups
// use, so glass samples the page layer rather than a separate window) and the
// backdrop, which callers hand in because the popup is composed above the page
// and cannot read the page's composition local.

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

/** The numbers of MIUIX's glass dropdown, one constant per value. */
object SoftGlassDropdownDefaults {

    /** Settled panel corner radius — the library's `GlassDropdownDefaults`. */
    val CornerRadius = 24.dp

    /** Radius the panel opens from; it relaxes to [CornerRadius]. */
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

    /** Width the panel opens from, as a fraction of its settled width. */
    private const val StartWidth = 0.69f

    /** How far the panel blurs itself as it leaves, in dp. */
    internal const val ExitBlurDp = 30f

    /** The panel's size spring (response ≈ 0.35s) and its quicker centre spring. */
    internal fun boundsSpring(entering: Boolean) = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = if (entering) 320f else 800f,
        visibilityThreshold = 0.0015f,
    )

    internal fun positionSpring(entering: Boolean) = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = if (entering) 800f else 320f,
        visibilityThreshold = 0.0015f,
    )

    internal fun cornerSpring() = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = 320f,
        visibilityThreshold = 0.01f,
    )

    internal fun fadeSpec(entering: Boolean) = tween<Float>(
        durationMillis = if (entering) 50 else 200,
        easing = LinearEasing,
    )

    /** One-sided: sharp on the way in, blurred on the way out. */
    internal fun sharpnessSpec(entering: Boolean) =
        if (entering) snap<Float>() else tween(durationMillis = 200, easing = LinearEasing)
}

/**
 * MIUIX's anchored glass dropdown.
 *
 * Call with the row's window bounds ([Rect] from `boundsInWindow()`), the page
 * layer the panel may sample, and the option rows ([SoftGlassDropdownItem]).
 * The host is 0.9.4's public popup layout, so the panel is composed above every
 * page layer — the backdrop handed in here is the page's own record, never the
 * panel itself.
 *
 * There is deliberately no window dim: the new MIUIX style keeps the page
 * legible. The full-page layer under the panel only takes the outside tap.
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
    // Scaffold hosts compose after opening was requested, so the transition
    // starts closed — otherwise the first frame would already be at rest and
    // the opening would have no motion at all.
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(show) { visibility.targetState = show }
    val transition = rememberTransition(visibility, label = "softGlassDropdown")

    // `currentState` is the observable half; `Transition.isRunning` is backed by
    // a plain field and would never re-evaluate this when the exit settles, so
    // the host would stay mounted over the page.
    val active by remember(show, transition) {
        derivedStateOf { show || transition.currentState }
    }
    val hostVisible = remember { mutableStateOf(false) }
    LaunchedEffect(active) { hostVisible.value = active }

    // Back closes through the same door as a pick or an outside tap. The popup
    // host's own handler is off below: it would hide the host without telling
    // the row, leaving the row's `expanded` true after the panel vanished.
    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = active,
        onBackCompleted = onDismissRequest
    )

    if (!active) return

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
            anchorBounds = anchorBounds,
            backdrop = backdrop,
            transition = transition,
            modifier = modifier,
            content = content
        )
    }
}

@Composable
private fun SoftGlassDropdownHost(
    show: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: Rect,
    backdrop: LayerBackdrop?,
    transition: Transition<Boolean>,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val progress by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                SoftGlassDropdownDefaults.boundsSpring(entering = true)
            } else {
                SoftGlassDropdownDefaults.boundsSpring(entering = false)
            }
        },
        label = "softGlassDropdownProgress"
    ) { if (it) 1f else 0f }
    val fade by transition.animateFloat(
        transitionSpec = { SoftGlassDropdownDefaults.fadeSpec(targetState) },
        label = "softGlassDropdownFade"
    ) { if (it) 1f else 0f }
    val sharpness by transition.animateFloat(
        transitionSpec = { SoftGlassDropdownDefaults.sharpnessSpec(targetState) },
        label = "softGlassDropdownSharpness"
    ) { if (it) 1f else 0f }
    val cornerRadius by transition.animateFloat(
        transitionSpec = { SoftGlassDropdownDefaults.cornerSpring() },
        label = "softGlassDropdownCorner"
    ) {
        if (it) SoftGlassDropdownDefaults.CornerRadius.value
        else SoftGlassDropdownDefaults.StartCornerRadius.value
    }

    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
    val marginPx = with(density) { SoftGlassDropdownDefaults.SafeMargin.toPx() }
    val placement = remember(
        anchorBounds,
        panelSize,
        windowSize,
        statusTop,
        navBottom,
        marginPx,
        layoutDirection
    ) {
        placeSoftGlassDropdown(
            anchor = anchorBounds,
            size = Size(panelSize.width.toFloat(), panelSize.height.toFloat()),
            margin = marginPx,
            window = Rect(
                left = 0f,
                top = statusTop,
                right = windowSize.width.toFloat(),
                bottom = windowSize.height - navBottom
            ),
            direction = layoutDirection
        )
    }
    // The library measures the arc from the value's own corner: the panel grows
    // out of the edge nearest the anchor, so the motion reads as the row opening.
    val originX = if (anchorBounds.center.x <= windowSize.width / 2f) 0f else 1f
    val originY = if (placement.alignTop) 0f else 1f
    val measured = panelSize != IntSize.Zero
    val scale = (0.69f + (1f - 0.69f) * progress.coerceAtLeast(0f)).coerceAtLeast(0f)
    val blurPx = with(density) {
        ((1f - sharpness.coerceIn(0f, 1f)) * SoftGlassDropdownDefaults.ExitBlurDp).dp.toPx()
    }
    val shape = RoundedCornerShape(cornerRadius.dp)
    val isDark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { hostOrigin = it.positionInWindow() }
    ) {
        // The page behind the panel: no dim, only the outside tap.
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(show) {
                    detectTapGestures { if (show) onDismissRequest() }
                }
        )
        Box(
            modifier = Modifier
                .offset(placement, hostOrigin)
                .onSizeChanged { panelSize = it }
                .graphicsLayer {
                    alpha = if (measured) fade else 0f
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(originX, originY)
                    renderEffect = if (measured && blurPx > 0.5f) {
                        BlurEffect(blurPx, blurPx, TileMode.Decal)
                    } else {
                        null
                    }
                }
        ) {
            SoftGlassSurface(
                modifier = Modifier
                    .widthIn(
                        min = SoftGlassDropdownDefaults.MinWidth,
                        max = SoftGlassDropdownDefaults.MaxWidth
                    )
                    .width(IntrinsicSize.Max)
                    .defaultMinSize(minWidth = SoftGlassDropdownDefaults.MinWidth)
                    .softGlassShadow(shape = shape, isDark = isDark),
                shape = shape,
                cornerRadius = cornerRadius.dp,
                recipe = SoftGlassRecipes.floatingNavigation(LocalSoftGlassSpec.current.fineTuning),
                backdrop = backdrop
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = SoftGlassDropdownDefaults.MaxWidth)
                        .heightIn(max = SoftGlassDropdownDefaults.MaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = SoftGlassDropdownDefaults.ContentPaddingVertical),
                    content = content
                )
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
        // Snap down, spring out — the library's own `popupPressExit`.
        animationSpec = if (pressed) {
            snap()
        } else {
            spring(dampingRatio = 1f, stiffness = 1200f)
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
                drawRoundRect(
                    color = pressFill,
                    cornerRadius = CornerRadius(
                        SoftGlassDropdownDefaults.ItemPressRadius.toPx()
                    )
                )
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

/** Places the panel: end-aligned to the anchor, then flipped up if there is no room. */
private data class SoftGlassDropdownPlacement(
    val rect: Rect,
    val alignTop: Boolean
)

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
    // The demo's own vertical rule: open downward when the panel fits below the
    // anchor, otherwise pick the side with more room.
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

/** The panel's window offset, mapped into the popup host's own coordinates. */
private fun Modifier.offset(
    placement: SoftGlassDropdownPlacement,
    hostOrigin: Offset
): Modifier = this.then(
    Modifier.offset {
        IntOffset(
            (placement.rect.left - hostOrigin.x).roundToInt(),
            (placement.rect.top - hostOrigin.y).roundToInt()
        )
    }
)
