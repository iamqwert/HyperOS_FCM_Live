package io.github.howard20181.hyperos.fcmlive.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes

/**
 * Group heading shared by the secondary pages.
 *
 * Mirrors the XML the View screens still use: `BodyMedium` at sans-medium in
 * `md_primary`, 8dp of side padding and a 28dp gap above (12dp for the first
 * heading, which only needs to clear the top bar). `titleSmall` was the
 * earlier Compose choice, but it carries 0.1sp tracking the XML side does not,
 * so the two read slightly differently on the same screen.
 */
@Composable
fun SectionTitle(res: Int, modifier: Modifier = Modifier, first: Boolean = false) {
    Text(
        text = stringResource(res),
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(
                start = 8.dp,
                top = if (first) 12.dp else 28.dp,
                end = 8.dp,
                bottom = 12.dp
            ),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.bodyMedium
    )
}

/** Gap between two rows of one connected group. */
val GROUP_ROW_GAP = 2.dp

/**
 * The shape of one slot in a connected group of cards: the outer corners take
 * the group radius while the seams stay tight.
 *
 * Stated once because two places build it — [GroupRow] for the list rows and
 * `DynamicColorCard` on the appearance page, which is a card that opens. A
 * radius change has to land in both or the two stop matching.
 */
@Composable
fun connectedGroupShape(first: Boolean, last: Boolean): Shape {
    val shapes = LocalAppShapes.current
    return when {
        first && last -> shapes.menu
        first -> RoundedCornerShape(
            topStart = shapes.groupOuter.topStart,
            topEnd = shapes.groupOuter.topEnd,
            bottomStart = shapes.groupInner.bottomStart,
            bottomEnd = shapes.groupInner.bottomEnd
        )
        last -> RoundedCornerShape(
            topStart = shapes.groupInner.topStart,
            topEnd = shapes.groupInner.topEnd,
            bottomStart = shapes.groupOuter.bottomStart,
            bottomEnd = shapes.groupOuter.bottomEnd
        )
        else -> shapes.groupInner
    }
}

/**
 * One slot in a connected group of cards. The shape comes from
 * [LocalAppShapes], so it follows the chosen colour spec.
 *
 * [onClick] may be null for purely informative rows (the privacy page's
 * permission rows): they get the same card body without a ripple or a haptic,
 * because a row that does nothing must not pretend to be a button.
 */
@Composable
fun GroupRow(
    first: Boolean,
    last: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = connectedGroupShape(first, last)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = shape
    ) {
        val view = LocalView.current
        val clickModifier = if (onClick != null) {
            Modifier
                // See [MenuItemRow]: the clip has to come before `clickable`.
                .clip(shape)
                .clickable(onClick = {
                    // A card tap is a navigation-grade tap: CONTEXT_CLICK is
                    // the subtle tick the system uses for list items, lighter
                    // than a keypress.
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onClick()
                })
        } else {
            Modifier
        }
        Column(
            modifier = clickModifier
                .heightIn(min = 72.dp)
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.Center
        ) {
            content()
        }
    }
}

// Metrics of the popup menus. DropdownMenu's own content column carries a
// built-in 8dp vertical padding (DropdownMenuVerticalPadding in Menu.kt) — the
// panel edge the eye measures against. Everything else is tuned to that: rows
// get NO vertical padding of their own, so the ripple spans the full 40dp row
// and sits the built-in 8dp from the panel's top/bottom edges; [MENU_ITEM_GAP]
// spacers between rows keep the same 8dp inside the panel, and rows take an
// 8dp horizontal padding so the ripple is equidistant from all four edges.
val MENU_ANCHOR_GAP = 4.dp
val MENU_OUTER_PAD = 8.dp
val MENU_ITEM_GAP = 8.dp
val MENU_ITEM_PAD_H = 10.dp
val MENU_ITEM_HEIGHT = 40.dp
/** How far the panel sits back from the anchor's right edge. */
val MENU_EDGE_INSET = 12.dp
/** Floor so a two-character label still gets a menu, not a chip. */
val MENU_MIN_WIDTH = 136.dp
/** The overflow menu's own floor (`popup_overflow.xml` used 180dp). */
val MENU_OVERFLOW_MIN_WIDTH = 156.dp

/**
 * One row of a popup menu: a rounded plate when it is the current value, a
 * rounded — but transparent — target otherwise.
 *
 * The [Modifier.clip] in front of the click is the whole point of this
 * composable. The ripple is painted by the indication node the click installs,
 * and that node draws inside the *node's* bounds, which are a rectangle. A
 * shape handed to [Surface] only clips [Surface]'s own content — it says
 * nothing about where the indication is drawn — so without an explicit clip in
 * front, every press painted a square ripple over a rounded row. The XML masked
 * its ripple against a 12dp rectangle for exactly this reason.
 *
 * [checkable] decides which gesture the row wears. Non-null means the row *is*
 * a checkbox — the checked state lands on the row's own semantics, so a screen
 * reader announces 「已选中／未选中」 while the row has focus, and the trailing
 * box is only decoration. Null means the row is a plain button: that is what
 * the appearance menus are, where [selected] names the current value rather
 * than a state the row can toggle between.
 */
@Composable
fun MenuItemRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    checkable: Boolean? = null,
    minWidth: Dp = MENU_MIN_WIDTH,
    arrangement: Arrangement.Horizontal = Arrangement.Start,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = LocalAppShapes.current.menuItem
    // Horizontal inset first, then the fixed floor, then the clip: the clip has
    // to be the last thing before the gesture or the ripple is a rectangle.
    // Vertically the row stays full-bleed, so the ripple spans the whole row
    // height and reads tall; the distance to the panel's top/bottom edges comes
    // from DropdownMenu's built-in 8dp column padding and the [MENU_ITEM_GAP]
    // spacers between rows — see the metrics note above.
    val laidOut = modifier
        .padding(horizontal = MENU_OUTER_PAD)
        .defaultMinSize(minWidth = minWidth, minHeight = MENU_ITEM_HEIGHT)
        .clip(shape)
    val gesture = if (checkable != null) {
        laidOut.toggleable(
            value = checkable,
            role = Role.Checkbox,
            onValueChange = { onClick() }
        )
    } else {
        laidOut.clickable(role = Role.Button, onClick = onClick)
    }
    Surface(
        modifier = gesture,
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = MENU_ITEM_HEIGHT)
                .padding(horizontal = MENU_ITEM_PAD_H),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = arrangement
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(MENU_ITEM_PAD_H))
                trailing()
            }
        }
    }
}

/**
 * Leading icon shared by the settings rows.
 *
 * The end inset must be applied *before* the size: written the other way round
 * it is carved out of the box and the glyph ends up squeezed into the sliver
 * left over instead of sitting 24dp wide with a gap after it.
 */
@Composable
fun RowIcon(painter: Painter, modifier: Modifier = Modifier) {
    Icon(
        painter = painter,
        contentDescription = null,
        modifier = modifier
            .padding(end = 14.dp)
            .size(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * The mark the View-era rows carried, drawn inside the switch thumb: a check
 * while on, a cross while off — the state reads at a glance even before the
 * thumb colours register. Tint follows the switch's own icon color, so the
 * glyph stays legible on both the checked and unchecked thumb.
 */
@Composable
fun SwitchThumbMark(checked: Boolean) {
    Icon(
        imageVector = if (checked) Icons.Filled.Check else Icons.Filled.Close,
        contentDescription = null,
        modifier = Modifier.size(SwitchDefaults.IconSize)
    )
}

/**
 * A standalone switch card — icon, title, optional state line, description and
 * the switch itself.
 *
 * The whole row is the switch's hit area ([Role.Switch] is what a screen reader
 * announces), matching the XML, which put the listener on the row.
 *
 * The switch thumb carries the on/off mark via [SwitchThumbMark]; the state
 * line stays plain text. [stateLine] is the on-state text, [stateLineOff] the
 * off-state one; each shows only while its state holds.
 */
@Composable
fun SettingsSwitchCard(
    iconRes: Int,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    stateLine: String? = null,
    stateLineOff: String? = null
) {
    val shape = LocalAppShapes.current.card
    val view = LocalView.current
    // One toggle path for both hit areas — the row and the switch itself — so
    // the haptic fires exactly once per flip, whichever way it was flipped.
    val toggle: () -> Unit = {
        // VIRTUAL_KEY: the click a switch is expected to make in the hand.
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onCheckedChange(!checked)
    }
    val stateText = when {
        checked && stateLine != null -> stateLine
        !checked && stateLineOff != null -> stateLineOff
        else -> null
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = shape
    ) {
        Row(
            modifier = Modifier
                // See [MenuItemRow]: the clip has to come before the click.
                // `toggleable` rather than `clickable(role)`: the checked state
                // lives on the row's own semantics, so a screen reader focused
                // on the row announces "on/off" without first reaching the
                // inner Switch node.
                .clip(shape)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = { toggle() }
                )
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RowIcon(painter = painterResource(iconRes))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (stateText != null) {
                    Text(
                        text = stateText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (description.isNotEmpty()) {
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Switch(
                modifier = Modifier.padding(start = 12.dp),
                checked = checked,
                onCheckedChange = { toggle() },
                thumbContent = { SwitchThumbMark(checked = checked) }
            )
        }
    }
}

/**
 * A row that leads somewhere: icon, title, subtitle, and an optional trailing
 * slot for things like the update badge.
 */
@Composable
fun NavCard(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    GroupRow(first = first, last = last, onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(painter = painterResource(iconRes))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (trailing != null) {
                trailing()
            }
        }
    }
}
