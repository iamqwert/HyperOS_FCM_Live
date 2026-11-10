package io.github.howard20181.hyperos.fcmlive.ui

// The docked MIUIX page strip. It reproduces the library's `basic.TabRow` as
// it looks at its defaults, because that row cannot be used as-is: see the note
// on [MiuixDockedTabRow] for the drag it allows and why removing the scroll
// container is the only fix that does not also break taps.

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme

object MiuixDockedTabRowDefaults {

    /** `TabRowDefaults.TabRowHeight` — the row the library draws at its defaults. */
    val Height = 42.dp

    /** `TabRowDefaults.TabRowCornerRadius`. */
    val CornerRadius = 12.dp

    /** The gap the library's row runs between its slots. */
    val ItemSpacing = 9.dp

    /**
     * `TabItem`'s own horizontal air — the padding that made the library's row
     * unable to fit six labels (see `MiuixMetrics.CategoryTabMinWidth`). Two
     * tabs have room to spare for it, so it stays: it is what makes a slot look
     * like a button rather than a bare word.
     */
    val ItemPadding = 12.dp
}

/**
 * The docked MIUIX page strip — 状态 / 事件 — drawn the way the library's own
 * `basic.TabRow` draws it: 42dp tall, one slot per tab split evenly across the
 * width, 12dp continuous-curvature corners, an outlined slot when unselected
 * and a filled plate when selected, labels in `body1` with the selected one
 * Bold.
 *
 * **Why it is not the library's row.** That row is always a `LazyRow` with the
 * library's overscroll effect on top. Two tabs divide the width exactly, so
 * there is nothing to scroll, yet the effect is installed anyway and a sideways
 * drag slides the entire strip out of place — measured on device, a 150dp drag
 * moved it ~98dp, cutting the left label off and leaving empty bar on the
 * right, before it sprang back. Chrome does not pan, and a two-way switch has
 * nowhere to pan to.
 *
 * Absorbing the gesture instead was tried and rejected: consuming the pan in
 * the INITIAL pass does hold the strip still, but it also left the tab's own
 * tap detector holding a press it never saw move, so a drag that ended over the
 * other tab selected it — a drag must not be able to change the page. With no
 * scroll container in the tree there is no gesture to fight over, and nothing
 * else changes: taps select, long-press and a11y stay with `selectable` and
 * `Role.Tab`, and the strip cannot move.
 *
 * The six-category strip below keeps the library's `TabRowWithContour`: six
 * labels genuinely do not fit on a narrow screen, and scrolling to the last of
 * them is the intended way to reach it.
 *
 * @param tabs the labels, in tab order.
 * @param selectedTabIndex the tab wearing the plate; clamped into range.
 * @param onTabSelected called with the index of the tab tapped.
 */
@Composable
fun MiuixDockedTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return
    val colors = MiuixTheme.colorScheme
    val selectedIndex = selectedTabIndex.coerceIn(0, tabs.lastIndex)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MiuixDockedTabRowDefaults.Height)
            // The library's row paints its own `surface` under the slots. The
            // docked bar above is the same page tone, so this only matters
            // where the two differ — and then it is the row the library drew.
            .background(colors.surface)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MiuixDockedTabRowDefaults.ItemSpacing)
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The library's two states: the selected slot is a plate
                    // (its own `squircleBackground`, the same brush the rest of
                    // the look is filled with), every other one is a 1dp
                    // outline drawn on the same silhouette.
                    .then(
                        if (selected) {
                            Modifier.squircleBackground(
                                color = colors.surfaceContainer,
                                cornerRadius = MiuixDockedTabRowDefaults.CornerRadius
                            )
                        } else {
                            Modifier.squircleBorder(
                                width = { 1.dp },
                                color = { colors.outline },
                                cornerRadius = MiuixDockedTabRowDefaults.CornerRadius
                            )
                        }
                    )
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        interactionSource = interactionSource,
                        // The library's row passes no indication either: the
                        // plate is the feedback.
                        indication = null,
                        onClick = { onTabSelected(index) }
                    )
                    .padding(horizontal = MiuixDockedTabRowDefaults.ItemPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) colors.onBackground else colors.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
