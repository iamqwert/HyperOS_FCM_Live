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
     * The library [top.yukonga.miuix.kmp.basic.TabRow]'s own height. Swapping
     * the strip for this one must not move the page below it, so the height is
     * not a taste of its own.
     */
    val Height = 42.dp

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
 * Both Miuix-dressed looks wear it (MIUIX and 柔光玻璃): the plate is a layout,
 * and the two looks differ in material only. 柔光玻璃 gets the glass on top
 * whenever it has a backdrop to refract; plain Miuix never provides one, so it
 * gets the exact strip the reference shows at the top of its own page. That is
 * the same split the look table has everywhere else — one structure, the
 * material as the difference.
 *
 * **On the backdrop.** The reference's strip lives in a collapsing top bar, so
 * it can sample the content scrolling under it — and the records page now does
 * exactly that: its strips are mounted in the page bar's glass pane
 * ([io.github.howard20181.hyperos.fcmlive.ui.PageScaffold.barExtension]), with
 * the pane's own recipe covering the blur, so this strip wears the same
 * construction and resolves to the reference's resting state: the container
 * wash alone, with the material joining only when a caller hands a
 * [LayerBackdrop] in (through the parameter or [LocalSoftGlassBackdrop]).
 * That is not a shortcut around the material — a layer recorded here would
 * capture the page background the strip already sits on, so the optics would
 * cost a full-page render node to refract a flat colour. A page that lets
 * content run under its strip gets the glass by providing the layer, with no
 * change at this call site.
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
    // would clip the other. Medium is the selected weight, so measuring with it
    // is measuring the worst case.
    val labelStyle = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 16.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val itemInsets = MiuixCapsuleTabRowDefaults.ItemHorizontalPadding * 2
    val labelWidths = remember(measurer, density, tabs, labelStyle) {
        tabs.map { label ->
            val widthPx = measurer.measure(
                text = AnnotatedString(label),
                style = labelStyle.copy(fontWeight = FontWeight.Medium),
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
                .clip(shape)
                .background(containerColor)
        )
        if (softGlass.enabled && backdrop != null) {
            SoftGlassSurface(
                modifier = Modifier
                    .fillMaxSize()
                    .softGlassShadow(shape = shape, isDark = isDark),
                shape = shape,
                recipe = SoftGlassRecipes.floatingNavigation(softGlass.fineTuning),
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = MiuixCapsuleTabRowDefaults.HorizontalPadding,
                    vertical = MiuixCapsuleTabRowDefaults.VerticalPadding,
                )
                .horizontalScroll(scrollState, enabled = scrollable)
        ) {
            Box(
                modifier = Modifier
                    .width(if (scrollable) contentWidth else available)
                    .fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(indicatorStart.roundToInt(), 0) }
                        .width(with(density) { indicatorWidth.toDp() })
                        .fillMaxHeight()
                        .clip(shape)
                        .background(indicatorColor)
                )
                Row(
                    modifier = Modifier
                        .fillMaxSize()
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
                                fontWeight = if (selected) {
                                    FontWeight.Medium
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
