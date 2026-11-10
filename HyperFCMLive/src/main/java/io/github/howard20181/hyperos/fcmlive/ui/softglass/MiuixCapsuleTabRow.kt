package io.github.howard20181.hyperos.fcmlive.ui.softglass

// The look is F1Info/Deadliner's `ImmersiveTextTabRow` (ui/navigation/), carried
// over as the strip both Miuix-dressed looks wear in place of the library's tab
// rows. Integration changes: the material comes from this module's own
// soft-glass stack instead of being re-derived, and the strip has to survive
// six labels as well as two — see [MiuixCapsuleTabRowDefaults].

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

object MiuixCapsuleTabRowDefaults {
    /**
     * The pinned chrome's height: `SearchBarDefaults.InputFieldMinHeight`'s
     * 45dp, the same 45 the library's `TabRowWithContour` uses.
     *
     * It used to copy the plain [top.yukonga.miuix.kmp.basic.TabRow]'s 42dp so
     * that swapping the strip in would not move the page below it. But the strip
     * is pinned directly under the bar, beside the apps page's 45dp search
     * capsule in the same slot — at 42 the three dp it lacked pushed this page's
     * first row three dp above the apps page's, and the two pages' content lines
     * never met. One height for both pinned chromes keeps them on one line.
     */
    val Height = 45.dp

    /** The gap between the capsule's edge and the pill riding inside it. */
    val HorizontalPadding = 3.dp
    val VerticalPadding = 3.dp

    /** Air either side of a label; the pair is what makes a tab look tappable. */
    val ItemHorizontalPadding = 12.dp

    /**
     * The floor a tab may be squeezed to before the strip scrolls instead.
     * The two-glyph Chinese labels the records page uses sit on it exactly; an
     * English label wider than this simply keeps its own width.
     */
    val MinimumItemWidth = 48.dp

    val Shape: Shape = SoftGlassDefaults.Shape

    /**
     * The capsule's own wash over the page, the reference's resting values: it
     * is the container the glass material is drawn over, and with no material
     * (see [MiuixCapsuleTabRow]'s note on backdrops) it is the whole strip.
     */
    fun containerAlpha(isDark: Boolean): Float = if (isDark) 0.74f else 0.82f

    /** The pill under the selected label, faint in both lights — an ink wash, not a plate. */
    fun indicatorAlpha(isDark: Boolean): Float = if (isDark) 0.13f else 0.075f

    /**
     * The strip's own lens: the delivered material with its radius multiplied
     * up to 92 × 0.8 = 73.6 on the look's base. The two records-page strips
     * were tuned up by eye and are the only surfaces that take it — the
     * floating bar, its plates and the bar's actions keep the delivered
     * 0.217 (≈ MIUIX's PuredThinGlass 20dp).
     */
    const val BlurRadiusMultiplier = 0.8f
}

/**
 * The Miuix tab strip as a capsule, after F1Info's `ImmersiveTextTabRow`.
 *
 * The shape of the thing: one capsule the width of the page's content, a pill
 * that springs to whichever label is selected, labels that shade from half to
 * full ink on selection. Nothing is drawn between tabs — no divider, no outline
 * per tab, which is what makes it read as one plate rather than as the library's
 * row of outlined buttons.
 *
 * The glass Miuix looks wear it — 柔光玻璃 always, plain MIUIX once its
 * floating-bar row is ON: one plate, one layout, and one glass material over
 * it. Both are handed the page's own recorded layer, so both refract the
 * content passing under the strip. Plain MIUIX with the docked bar keeps the
 * library's own `TabRow` instead; the call site decides, not this component.
 *
 * **On the backdrop.** The reference's strip lives in a collapsing top bar, so
 * it can sample the content scrolling under it. The records page now does
 * exactly that: its strips mount inside the page bar's TRANSPARENT gradient
 * band
 * ([io.github.howard20181.hyperos.fcmlive.ui.PageScaffold.barExtension]) but
 * OUTSIDE the recorded body, so the page's own layer — provided through
 * [LocalSoftGlassBackdrop] by the page scaffold — is a legal source with no
 * render-node cycle, and the rows travelling under the band are real content
 * to blur. Passing `backdrop = null` leaves only the container wash: a
 * half-transparent plate and no frost at all.
 *
 * @param tabs the labels, in tab order.
 * @param selectedTabIndex the tab the pill sits under; clamped into range.
 * @param onTabSelected called with the index of the tab tapped.
 */
@Composable
fun MiuixCapsuleTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = LocalSoftGlassBackdrop.current,
) {
    if (tabs.isEmpty()) return

    val softGlass = LocalSoftGlassSpec.current
    val contentColor = MiuixTheme.colorScheme.onSurfaceContainer
    val isDark = contentColor.luminance() > 0.5f
    val shape = MiuixCapsuleTabRowDefaults.Shape
    val containerColor = MiuixTheme.colorScheme.surfaceContainer.copy(
        alpha = MiuixCapsuleTabRowDefaults.containerAlpha(isDark),
    )
    val indicatorColor = contentColor.copy(
        alpha = MiuixCapsuleTabRowDefaults.indicatorAlpha(isDark),
    )
    val selectedIndex = selectedTabIndex.coerceIn(0, tabs.lastIndex)

    // The strip's type, once: the label and the measurement that decides how
    // wide its tab is have to be the same text, or a tab sized for one weight
    // would clip the other. Bold is the selected weight — MIUIX's own for a
    // tab (TabRow) — so measuring with it is measuring the worst case.
    val labelStyle = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 16.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val itemInsets = MiuixCapsuleTabRowDefaults.ItemHorizontalPadding * 2
    val labelWidths = remember(measurer, density, tabs, labelStyle) {
        tabs.map { label ->
            val widthPx = measurer.measure(
                text = AnnotatedString(label),
                style = labelStyle.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false,
            ).size.width
            with(density) { widthPx.toDp() } + itemInsets
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(MiuixCapsuleTabRowDefaults.Height)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The outer rim belongs to the PLATE, not to the frost: it used
                // to hang on the glass layer below, so any strip handed a null
                // backdrop lost its shadow as well as its blur. Both Miuix
                // looks keep the rim now, glass or not.
                .softGlassShadow(shape = shape, isDark = isDark)
                .clip(shape)
                .background(containerColor)
        )
        if (softGlass.enabled && backdrop != null) {
            // The strip's own lens: the delivered material with its radius
            // raised (see [MiuixCapsuleTabRowDefaults.BlurRadiusMultiplier]).
            // The floating bar and the bar's actions keep the default.
            val base = SoftGlassRecipes.floatingNavigation(softGlass.fineTuning)
            SoftGlassSurface(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                recipe = base.copy(
                    blur = base.blur.copy(
                        radiusMultiplier = MiuixCapsuleTabRowDefaults.BlurRadiusMultiplier,
                    ),
                ),
                backdrop = backdrop,
            ) {}
        }

        val available = (
            maxWidth -
                MiuixCapsuleTabRowDefaults.HorizontalPadding * 2
            ).coerceAtLeast(0.dp)
        val itemWidths = capsuleTabItemWidths(labelWidths, available)
        val contentWidth = itemWidths.runWidth(itemWidths.size)
        val scrollable = contentWidth > available
        val scrollState = rememberScrollState()

        // A restored selection can be a tab the strip is not showing — the
        // reference never needs this because its strip always fits. Only the
        // edge cases are chased: a tap is on screen by definition, so this
        // slides nothing during normal use.
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

        val indicatorStart by animateFloatAsState(
            targetValue = with(density) {
                itemWidths.runWidth(selectedIndex).toPx()
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "soft_glass_tab_indicator_start",
        )
        val indicatorWidth by animateFloatAsState(
            targetValue = with(density) { itemWidths[selectedIndex].toPx() },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "soft_glass_tab_indicator_width",
        )

        // The pill rides OUTSIDE the scroll container. Compose's horizontalScroll
        // clips its content to a rectangle at the viewport edge, so the bouncy
        // overshoot at either end — first or last tab — had its round corner
        // sliced flat. The reference never shows that: its strip does not scroll,
        // and the only clip around the pill is the capsule itself, so an overshoot
        // is rounded off rather than squared off.
        //
        // So the pill gets its own layer, clipped to the capsule, offset by
        // [scrollState] so it still travels with the labels; the row below keeps
        // the scroll, whose rectangular clip only ever cuts text that has already
        // left the viewport.
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
                        .offset {
                            IntOffset(
                                (indicatorStart - scrollState.value).roundToInt(),
                                0,
                            )
                        }
                        .width(with(density) { indicatorWidth.toDp() })
                        .fillMaxHeight()
                        .clip(shape)
                        .background(indicatorColor)
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
                    val textColor by animateColorAsState(
                        targetValue = if (selected) {
                            contentColor
                        } else {
                            contentColor.copy(alpha = 0.50f)
                        },
                        label = "soft_glass_tab_color_$index",
                    )
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .width(itemWidths[index])
                            .fillMaxHeight()
                            .clip(shape)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onTabSelected(index) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = textColor,
                            style = labelStyle,
                            // MIUIX's own tab emphasis: the selected label is
                            // Bold, the rest Normal.
                            fontWeight = if (selected) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * How wide each tab is: its own label plus air, and the leftover space on a
 * strip that does not fill its capsule shared out evenly.
 *
 * The reference divides the width by the tab count, which is right for its two
 * or three short labels and wrong for this page's six: English labels run from
 * `All` to `Anomalies`, and equal shares clip the long ones while starving the
 * short. Natural widths keep every label whole; the shared-out remainder is
 * what still makes a short strip look like the reference's (equal tabs, edge to
 * edge) instead of a huddle of text in a long capsule. When the natural widths
 * do not fit, they are kept and the strip scrolls — which the reference never
 * has to do, and Chinese almost never does either.
 */
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

/**
 * The width of the first [count] tabs — where a tab starts, and where a run of
 * them ends. The strip lays its tabs out edge to edge, so a tab's offset is the
 * sum of the widths before it.
 */
private fun List<Dp>.runWidth(count: Int): Dp {
    var width = 0.dp
    for (index in 0 until count.coerceAtMost(size)) width += this[index]
    return width
}
