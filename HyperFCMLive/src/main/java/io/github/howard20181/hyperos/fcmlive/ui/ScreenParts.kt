package io.github.howard20181.hyperos.fcmlive.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces
import kotlin.math.max
import kotlin.math.min

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
        color = LocalAppSurfaces.current.card,
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
/**
 * Floor for a menu whose entries are bare numbers — the WiFi score thresholds.
 * Two digits plus the row's 10dp padding and the panel's 8dp edges come to
 * roughly 56dp, so [MENU_MIN_WIDTH] left a number column sitting in mostly
 * empty space; this keeps it reading as a menu while cutting the panel's
 * left-to-right extent by about a third.
 */
val MENU_NUMERIC_MIN_WIDTH = 88.dp
/** The overflow menu's own floor (`popup_overflow.xml` used 180dp). */
val MENU_OVERFLOW_MIN_WIDTH = 156.dp

/**
 * The trailing control on a switch row: the material3 switch's own 52dp track
 * (`SwitchTokens.TrackWidth`) and the [SWITCH_TRAILING_GAP] the row leaves
 * before it. [TRAILING_SLOT_WIDTH] is the two added up, which is what a
 * [SettingsMenuCard] has to reserve for its value.
 */
val SWITCH_TRACK_WIDTH = 52.dp
/** Gap between the text column and the trailing control on a row. */
val SWITCH_TRAILING_GAP = 12.dp

/**
 * Footprint of the trailing control on a switch row — the width a
 * [SettingsMenuCard] holds its value in.
 *
 * The value has to claim the whole footprint rather than just a gap: the text
 * column beside it is `weight(1f)`, so it takes whatever the trailing control
 * does not claim — and a two-character value claims some 30dp less than a
 * switch does. Left to size itself, the value let the menu card's description
 * run several characters further right than the descriptions right above it on
 * the switch rows, which is what made the two wrap columns visibly disagree.
 */
val TRAILING_SLOT_WIDTH = SWITCH_TRAILING_GAP + SWITCH_TRACK_WIDTH

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
 *
 * [selected] is the plate, and only the appearance menus pass it: there it names
 * the value the menu is currently set to, and that is worth a mark because the
 * row is a *choice* the user made. The overflow menu's four toggles pass
 * [checkable] alone — their state is already drawn, twice: the row's semantics
 * carry 「已选中／未选中」 and the trailing box shows it. A plate on top of a
 * checked box would be a third copy of the same bit, not a second purpose.
 * Material's `CheckableDropdownMenuItem` does paint one, and that is the part of
 * its appearance this app does not copy — the box is the affordance here.
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
            // One plate for every "this is the current one" surface in the app:
            // the app list's selected row wears `PrimaryContainer` too (see
            // `ui/MainScreen.kt`), so a menu and the list agree instead of each
            // naming the current item in a different colour.
            //
            // This role is a *measurement*, not a token reading. The pinned
            // build's own token class says otherwise — alpha27's
            // `StandardMenuTokens.ItemSelectedContainerColor` is
            // `TertiaryContainer` — and following it is what put a plate of the
            // wrong hue on this row. The reference implementation (DPIS,
            // material3 1.5.0-alpha28, stock `SelectableDropdownMenuItem` with
            // only `containerColor` overridden to transparent) renders the
            // selected entry of its theme menu as `#3B3B3B` in MONOCHROME light;
            // on the official colour utilities that hex is `PrimaryContainer`
            // (tone 25) and `TertiaryContainer` is `#747474` (tone 50). Where a
            // token reading and a reference implementation's pixels disagree,
            // the pixels are the spec.
            //
            // The pair is kept whole: the plate and the label on it are one
            // decision, never a container and a lookalike foreground.
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
                // Paired with the plate above; see [MenuItemRow]'s note. It
                // used to be `onSurface` for both states, which looks fine on
                // the default palette and then collapses: measured over
                // `4 seeds x 9 styles x 2 specs x light/dark`, `onSurface` on
                // the `primaryContainer` plate falls to 1.13:1 (VIBRANT, 2025
                // spec, dark) while the paired role never drops below 4.54:1.
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
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
 * glyph stays legible on both the checked and unchecked thumb — see
 * [appSwitchColors] for the one place those colors are decided.
 *
 * The glyphs are drawables rather than `Icons.Filled.*` on purpose: the icon
 * pack that ships them is the older material-design-icons set, and the app's
 * icon red line (theme/ComposeTokens.kt) admits Material Symbols only.
 */
@Composable
fun SwitchThumbMark(checked: Boolean) {
    Icon(
        painter = painterResource(
            if (checked) R.drawable.ic_check else R.drawable.ic_close
        ),
        contentDescription = null,
        modifier = Modifier.size(SwitchDefaults.IconSize)
    )
}

/**
 * Switch colors: the material3 tokens, plus the one guard the tokens need.
 *
 * Both marks are drawn on the *thumb*, so the colour that matters is the one
 * paired with the handle. Read off material3 1.5.0-alpha27's own
 * `SwitchTokens`:
 *
 * | state | handle | icon |
 * | --- | --- | --- |
 * | selected | `OnPrimary` | `OnPrimaryContainer` |
 * | unselected | `Outline` | `SurfaceContainerHighest` |
 *
 * Those are the library's values and they are kept as they are. What the
 * library does not do is contrast-check them, and the selected pair collapses
 * in three families. Measured over
 * `4 seeds x 9 styles x 2 specs x light/dark` it falls below 3:1 in 36 of 144
 * combinations:
 *
 * | where | why | measured |
 * | --- | --- | --- |
 * | MONOCHROME | the only style whose `onPrimaryContainer` (tone 100) is *lighter* than its `onPrimary` (tone 90) | 1.2-1.3:1 |
 * | CONTENT / FIDELITY, light | `onPrimary` is pure white and `onPrimaryContainer` is a pastel taken from the source | 1.4:1 |
 * | VIBRANT, 2025 spec | `onPrimary` and `onPrimaryContainer` land two tones apart | 1.1-1.2:1 |
 *
 * So the token wins unless it cannot be seen: below [MIN_ICON_CONTRAST] the
 * mark falls back to the handle's own partner role — `primary` for the handle
 * `onPrimary`, which measures 6.1-17.2:1 on exactly those combinations. The
 * guard is a measurement rather than a list of style names, which is what made
 * it catch the second and third families for free once the 2025 spec and the
 * FIDELITY/CONTENT container rules were actually implemented; a hand-written
 * list would have named MONOCHROME and missed them.
 *
 * Every switch in the app passes these, so the decision lives here rather than
 * being re-stated per call site.
 */
@Composable
fun appSwitchColors(): SwitchColors {
    val scheme = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedIconColor = legibleOn(
            scheme.onPrimary,
            listOf(scheme.onPrimaryContainer, scheme.primary)
        ),
        uncheckedIconColor = legibleOn(
            scheme.outline,
            listOf(scheme.surfaceContainerHighest, scheme.onSurface)
        )
    )
}

/**
 * The first of [candidates] that is legible on [background], or the last one if
 * none is. Callers list the official token first, so the fallback only ever
 * runs where the token itself is the problem.
 *
 * Takes a list rather than `vararg` because [Color] is a value class, and
 * Kotlin 2.x rejects a value class as a vararg element type.
 */
private fun legibleOn(background: Color, candidates: List<Color>): Color {
    return candidates.firstOrNull { contrastRatio(it, background) >= MIN_ICON_CONTRAST }
        ?: candidates.last()
}

/** WCAG contrast ratio between two opaque colors. */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/** Icons are non-text content, so 3:1 is the threshold that applies. */
private const val MIN_ICON_CONTRAST = 3.0f

/**
 * A switch row — icon, title, optional state line, description and the switch
 * itself.
 *
 * The whole row is the switch's hit area ([Role.Switch] is what a screen reader
 * announces), matching the XML, which put the listener on the row.
 *
 * The switch thumb carries the on/off mark via [SwitchThumbMark]; the state
 * line stays plain text. [stateLine] is the on-state text, [stateLineOff] the
 * off-state one; each shows only while its state holds.
 *
 * [first] / [last] place the row in a connected group of settings rows — the
 * section pattern every settings screen uses. Left at their defaults the row
 * is a section of its own, which is what a one-row section is; inside a group
 * the neighbours share a 2dp seam and only the ends keep the group radius.
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
    stateLineOff: String? = null,
    first: Boolean = true,
    last: Boolean = true
) {
    val shape = connectedGroupShape(first, last)
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
        color = LocalAppSurfaces.current.card,
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
                // 12dp gap + the switch's own 52dp track = [TRAILING_SLOT_WIDTH],
                // the slot [SettingsMenuCard] holds its value in.
                modifier = Modifier.padding(start = SWITCH_TRAILING_GAP),
                checked = checked,
                onCheckedChange = { toggle() },
                colors = appSwitchColors(),
                thumbContent = { SwitchThumbMark(checked = checked) }
            )
        }
    }
}

/**
 * A card whose value is picked from a menu — icon, title, description, the
 * chosen label on the right where a switch would go.
 *
 * Card language is [SettingsSwitchCard]'s exactly, because the two are meant to
 * sit in the same section and a differently-shaped card reads as a different
 * kind of thing. Only the trailing control differs.
 *
 * The menu is not reinvented: the anchoring and measuring are the appearance
 * rows already proved on the About page, since both things that go wrong there
 * go wrong here. A row given `fillMaxWidth` inside the popup makes the panel
 * window-wide, and a window-wide panel cannot fit beside its anchor, so the
 * position provider abandons the anchor for the window margin and lands the
 * panel out beside the card. And material3 is pinned to a build with no
 * `menuAnchorPosition`, so bottom-end anchoring is done with
 * `matchParentSize().wrapContentSize(BottomEnd)` — see theme/ComposeTokens.kt.
 *
 * The whole row opens the menu, matching the switch card whose whole row
 * toggles: one obvious target, no separate caret to aim at.
 */
@Composable
fun SettingsMenuCard(
    iconRes: Int,
    title: String,
    description: String,
    entries: List<String>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Widest-label floor for the panel; see [MENU_NUMERIC_MIN_WIDTH]. */
    menuMinWidth: Dp = MENU_MIN_WIDTH,
    /** Group slot, as in [SettingsSwitchCard]; the defaults are a lone row. */
    first: Boolean = true,
    last: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val shapes = LocalAppShapes.current
    val shape = connectedGroupShape(first, last)
    val view = LocalView.current
    val current = entries.getOrNull(currentIndex) ?: ""

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = LocalAppSurfaces.current.card,
            shape = shape
        ) {
            Row(
                modifier = Modifier
                    // See [MenuItemRow]: the clip has to come before the click.
                    .clip(shape)
                    .clickable(role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        expanded = true
                    }
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
                    if (description.isNotEmpty()) {
                        Text(
                            modifier = Modifier.padding(top = 4.dp),
                            text = description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                // The value is held in the slot a switch would occupy
                // ([TRAILING_SLOT_WIDTH]) so the description beside it wraps at
                // the same column as the switch rows — and inside that slot it
                // is centred on the switch's own track rather than pushed to
                // the card's edge, which is what puts the number on the same
                // vertical axis as the switch on the row above instead of
                // sitting under the switch's right half.
                Box(
                    modifier = Modifier
                        .width(TRAILING_SLOT_WIDTH)
                        .padding(start = SWITCH_TRAILING_GAP),
                    contentAlignment = Alignment.Center
                ) {
                    // Same accent role as the appearance rows: the one place on
                    // the page the active choice is named, so it moves with the
                    // seed.
                    Text(
                        text = current,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .wrapContentSize(Alignment.BottomEnd)
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .defaultMinSize(minWidth = menuMinWidth),
                shape = shapes.menu,
                // The popup level, not the card's. A dropdown comes to rest on
                // the card it was launched from, so sharing that card's fill
                // left the overlap with no edge at all — and the shadow that
                // was meant to separate them is the one a dark appearance
                // cannot show. Material's answer is tone, not an outline: the
                // panel wears a different level of the same ramp, in both
                // appearances. See [AppSurfaces] for the levels and the
                // measured steps.
                containerColor = LocalAppSurfaces.current.popup,
                offset = DpOffset(x = -MENU_EDGE_INSET, y = MENU_ANCHOR_GAP)
            ) {
                entries.forEachIndexed { index, label ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
                    }
                    MenuItemRow(
                        label = label,
                        modifier = Modifier.fillMaxWidth(),
                        minWidth = menuMinWidth,
                        selected = index == currentIndex,
                        onClick = {
                            expanded = false
                            onPick(index)
                        }
                    )
                }
            }
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
