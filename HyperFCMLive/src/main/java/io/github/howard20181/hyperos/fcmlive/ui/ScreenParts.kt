package io.github.howard20181.hyperos.fcmlive.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.AppearanceState
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import io.github.howard20181.hyperos.fcmlive.ui.softglass.LocalSoftGlassBackdrop
import io.github.howard20181.hyperos.fcmlive.ui.softglass.LocalSoftGlassSpec
import io.github.howard20181.hyperos.fcmlive.ui.softglass.SoftGlassIconButton
import io.github.howard20181.hyperos.fcmlive.ui.softglass.SoftGlassRecipes
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as MiuixSmallTopAppBar
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Group heading shared by every page, in both looks' languages.
 *
 * The Material branch reads as the M3 settings templates print a section
 * break: `labelLarge` in the accent, 8dp of side padding and a 28dp gap above
 * (12dp for the first heading, which only needs to clear the top bar).
 * `titleSmall` was the earlier choice, but its 0.1sp tracking read as a
 * subtitle rather than a break. The Miuix branch is the system settings' own
 * `SmallTitle` — small, grey, no accent — see the body. Both branches declare
 * the heading semantics: a screen reader must not lose the section structure
 * the moment the look is switched.
 */
@Composable
fun SectionTitle(res: Int, modifier: Modifier = Modifier, first: Boolean = false) {
    if (LocalUiStyle.current.isMiuixBased) {
        // The Miuix header is the system settings' own: small, grey, part of the
        // page. Blue was the Material reading of a section break; under Miuix an
        // accent there is noise, and every reference app renders it as this grey.
        // The header sits 28dp off the screen edge — the margin Miuix's own
        // SmallTitle leaves — of which the page list already provides 12, so the
        // rest is stated here. At 8 it read as flush with the cards below.
        SmallTitle(
            text = stringResource(res),
            // The Material branch declares the section break a heading; the
            // Miuix branch must too, or a screen reader loses the section
            // structure the moment the look is switched.
            modifier = modifier
                .fillMaxWidth()
                .semantics { heading() },
            insideMargin = PaddingValues(
                start = 16.dp,
                top = if (first) 12.dp else 20.dp,
                end = 8.dp,
                bottom = 8.dp
            )
        )
        return
    }
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
        // labelLarge is the style M3 settings templates print section headers
        // in — small, medium-weight, tracked — not the body size this wore
        // before. The primary tone itself stays: that is the reference
        // reading of a section break.
        style = MaterialTheme.typography.labelLarge
    )
}

/**
 * The scroll state of a whole-page list.
 *
 * Every page body in the app is a `LazyColumn`, and every one of them owes its
 * position to this rather than to `LazyColumn`'s default so the rule is named in
 * one place: the position has to outlive the composition, not just the
 * recomposition — [WindowSnapshot] repaints a stopped page by detaching and
 * re-attaching its view tree, which disposes the composition wholesale, and only
 * a saveable state comes back from that.
 *
 * Deliberately positional (no custom saveable key): upstream deprecated keyed
 * `rememberSaveable` — a name bypasses positional scoping and can share or lose
 * state across bodies (r.android.com/3610053). What actually keeps a page in
 * place here is the positional saveable plus the fix that stopped a live page
 * being rebuilt under the reader, not the key the earlier version of this passed.
 */
@Composable
fun rememberPageListState(): LazyListState {
    return rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
}

/**
 * The Miuix look's scroll-end haptic, the way the reference apps mount it:
 * every [androidx.compose.foundation.lazy.LazyColumn] under this look carries
 * [Modifier.scrollEndHaptic] (KernelSU's Miuix pages and the official example's
 * `pageScrollModifiers` both do, the example with the switch defaulted on). A
 * fling that hits the top or bottom edge answers with the light list-edge tick
 * — the feel system Settings itself uses — and only under this look: the M3
 * branch keeps the platform stretch with no added vibration.
 */
fun Modifier.listScrollEndHaptic(): Modifier = composed {
    if (LocalUiStyle.current.isMiuixBased) Modifier.scrollEndHaptic() else Modifier
}

/**
 * The registry of currently-open popups — dropdown rows, overflow menus — that
 * the shell's chromes consult. A popup's veil is drawn inside the layer the
 * frosted bars record, so glass reading that layer would take the veil in as
 * its own sample and go pale over the page; and the pill answers by dropping
 * back to its opaque build while any popup is up. Reporters key by their own
 * identity ([Any]), so nested or sibling popups never fight over one flag.
 */
object PopupOverlay {
    private val overlays = mutableStateMapOf<Any, Boolean>()

    /** True while at least one popup holds its veil up. */
    val dimming: Boolean
        get() = overlays.isNotEmpty()

    fun report(key: Any, visible: Boolean) {
        if (visible) overlays[key] = true else overlays.remove(key)
    }

    fun clear(key: Any) {
        overlays.remove(key)
    }
}

/**
 * The vibrancy lift the glass chromes apply to what they sample — a brighter,
 * more saturated read, not only a blur. Mirrored from KernelSU's
 * `ui/component/liquid/Vibrancy.kt` (itself adapted from Kyant0's
 * AndroidLiquidGlass, Apache 2.0).
 */
internal fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}

/** Gap between two rows of one connected group. */
val GROUP_ROW_GAP = 2.dp

/**
 * A connected group of settings rows — the container that owns the seam.
 *
 * Rows are caller slots; the spacing between them is this container's
 * invariant, never the caller's choreography. Under M3 the group is a stack of
 * separate cards and the seam is [GROUP_ROW_GAP]; under Miuix the group is one
 * flush card, the spacing is zero and the rows' own first/last shapes fuse
 * them. Callers just place rows inside.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!LocalUiStyle.current.isMiuixBased) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(GROUP_ROW_GAP),
            content = content
        )
        return
    }
    val backdrop = LocalMiuixGlassBackdrop.current
    if (backdrop == null) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            content = content
        )
        return
    }
    // Glass group: one glass plane behind the whole run. Each row carries its
    // OWN blur, and where two of them meet the anti-aliased edge lets a hairline
    // of whatever sits behind through. Over a still card fill that hairline is
    // invisible; over a background that moves it is not — it paints the moving
    // gradient across the card, the line visible between the about page's rows.
    // Under this plane the edge meets glass sampled from the very same pixel, so
    // the hairline goes. The reference has none to show: its rows share ONE card
    // node, where there is no edge between rows to leak.
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .textureBlur(
                    backdrop = backdrop,
                    shape = connectedGroupShape(first = true, last = true),
                    blurRadius = CARD_BLUR_RADIUS,
                    colors = BlurColors(
                        blendColors = if (miuixGlassIsDark()) CARD_BLEND_DARK
                        else CARD_BLEND_LIGHT
                    ),
                    enabled = true
                )
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            content = content
        )
    }
}

/**
 * The glass backdrop a Miuix page offers its cards: the page's own dynamic
 * background, recorded into a layer so a card can sample what sits behind it.
 * Null — the default — leaves every card on its solid fill, which is what every
 * page without a dynamic background wants.
 *
 * Only the OS3-background pages have one to offer, and only the about page has
 * the background to fill it ([MiuixBgEffectBackground]).
 */
val LocalMiuixGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * The room the Miuix look's floating bar takes at the foot of the screen, as the
 * page's own bottom inset should read it. Zero — the default — wherever the bar
 * is docked or absent, and the whole look is unchanged there.
 *
 * The statement of intent here is the reading the pages give it: it is added to
 * the inset [PageScaffold] hands a page's list, which makes it *scroll room*. A
 * list can then travel that much further, so its last row comes to rest above
 * the pill instead of behind it — while the rows still pass UNDER the pill on
 * the way, so the glass keeps something to blur. Reserving the height on the
 * content box instead would have cleared the pill by taking the glass's content
 * away, which is the one thing this look exists for.
 */
val LocalFloatingBarClearance = compositionLocalOf { 0.dp }

/**
 * The glass tints' dark read. The app's own palette owns darkness — it can force
 * a look the system does not — so this asks the page background rather than the
 * system night flag, exactly as the backdrop's own dark read does.
 */
@Composable
private fun miuixGlassIsDark(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The surface a Miuix row sits on: the look's card fill normally, the same row
 * as frosted glass wherever the page provides [LocalMiuixGlassBackdrop].
 *
 * The glass is the reference's own card treatment — KernelSU's about page — a
 * [textureBlur] of the recorded dynamic background with the reference's
 * light/dark blend tints, over a transparent fill, so the gradient shows
 * THROUGH the card instead of being covered by it. Shape, metrics and the row's
 * press feedback are untouched: only what the card is made of changes.
 */
@Composable
internal fun MiuixCardSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val backdrop = LocalMiuixGlassBackdrop.current
    if (backdrop == null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = LocalAppSurfaces.current.card,
            shape = shape,
            content = content
        )
        return
    }
    val isDark = miuixGlassIsDark()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = CARD_BLUR_RADIUS,
                colors = BlurColors(
                    blendColors = if (isDark) CARD_BLEND_DARK else CARD_BLEND_LIGHT
                ),
                enabled = true
            ),
        color = Color.Transparent,
        shape = shape,
        content = content
    )
}

/** The card glass's blur radius, in the blur library's own units. */
private const val CARD_BLUR_RADIUS = 60f

/**
 * The card glass's tints, copied from the reference (KernelSU's own
 * `ColorBlendToken`): a blue overlay plus a hard white light on light pages, a
 * grey luminosity pass plus a plus-darker on dark ones.
 */
private val CARD_BLEND_LIGHT = listOf(
    BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
    BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight)
)
private val CARD_BLEND_DARK = listOf(
    BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
    BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker)
)

/**
 * The seam as a placeable atom — the manual counterpart of [SettingsGroup],
 * for the one layout the container cannot express: a group whose rows appear
 * and disappear under `AnimatedVisibility`.
 *
 * `spacedBy` only learns a child is gone once the exit animation has fully
 * removed it, so a seam NEXT TO an animated row stays at full width while the
 * row itself is already gone — the reader watches the card shrink away and a
 * 4dp void hold its place until the animation ends. The fix is placement: a
 * seam that must collapse WITH the row goes inside the `AnimatedVisibility`
 * content (it shrinks with the height), a seam between two always-visible
 * rows stays outside as a plain `GroupRowGap()` line.
 *
 * Everywhere else, use [SettingsGroup]: a seam the caller has to remember is a
 * seam a caller forgets. No-op under Miuix, whose group is one flush card.
 */
@Composable
fun GroupRowGap() {
    if (!LocalUiStyle.current.isMiuixBased) {
        Spacer(modifier = Modifier.fillMaxWidth().height(GROUP_ROW_GAP))
    }
}

/**
 * The long-press bubble on a top-bar control — or, under the Miuix look, the
 * deliberate absence of one.
 *
 * Material keeps the M3 tooltip it has always had. Miuix drops the bubble on
 * purpose: HyperOS's own control language has no long-press hint at all, its
 * controls answer a press with the library's dim/sink feedback, and an M3
 * bubble floating above that is a second design language on the same pixel —
 * the same reason the Miuix look borrows Miuix's icons and press states
 * everywhere else. The label still reaches the reader, because every bar icon
 * carries its own `contentDescription`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongPressTooltip(
    label: String,
    position: TooltipAnchorPosition = TooltipAnchorPosition.Below,
    content: @Composable () -> Unit
) {
    if (LocalUiStyle.current.isMiuixBased) {
        content()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(position),
        // No style override: `PlainTooltip` provides its own spec type
        // (PlainTooltipTokens.SupportingTextFont, M3's Label Large) to the
        // content, and every other bar icon's bubble reads that way.
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        content()
    }
}

/**
 * The horizontal inset of a settings page's list: 16dp under Material, where the
 * M3 guideline puts it, and 12dp under Miuix, the margin the reference apps
 * (and the system settings they imitate) leave around their cards.
 */
@Composable
fun pageHorizontalInset(): Dp {
    return if (LocalUiStyle.current.isMiuixBased) 12.dp else 16.dp
}

/** Miuix CardDefaults.CornerRadius, the group radius under the Miuix look. */
private val MIUIX_CARD_RADIUS = 16.dp

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
    if (LocalUiStyle.current.isMiuixBased) {
        // Miuix cards: one radius on the group's outer ends, square seams inside.
        // The radius is CardDefaults.CornerRadius, so a group reads exactly like
        // a Miuix Card with the rows stacked in it.
        return when {
            first && last -> RoundedCornerShape(MIUIX_CARD_RADIUS)
            first -> RoundedCornerShape(
                topStart = MIUIX_CARD_RADIUS, topEnd = MIUIX_CARD_RADIUS
            )
            last -> RoundedCornerShape(
                bottomStart = MIUIX_CARD_RADIUS, bottomEnd = MIUIX_CARD_RADIUS
            )
            else -> RectangleShape
        }
    }
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
    /**
     * The tap's haptic tick. The information rows on the home click as an
     * affordance only — the user asked for no vibration there — while every
     * navigational row keeps the standard CONTEXT_CLICK.
     */
    haptic: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = connectedGroupShape(first, last)
    // Row metrics follow the list spec on both looks. 56dp is the single-line
    // row height — M3's list minimum, and [BasicComponent]'s own floor
    // (BasicComponentDefaults.InsideMargin under Miuix) — and a two-line row
    // reaches M3's 72dp double-line height when the summary line carries the
    // 4dp gap above it (24+4+16 of type against 28dp of vertical padding), so
    // neither look needs a taller floor. Every row's summary line takes that
    // gap; Miuix's own rows (ArrowPreference and friends) space their lines on
    // the library's metrics and never pass through this column.
    val miuixRow = LocalUiStyle.current.isMiuixBased
    MiuixCardSurface(shape = shape, modifier = modifier) {
        val view = LocalView.current
        val clickModifier = if (onClick != null) {
            Modifier
                // See [MenuItemRow]: the clip has to come before `clickable`.
                .clip(shape)
                .clickable(onClick = {
                    // A card tap is a navigation-grade tap: CONTEXT_CLICK is
                    // the subtle tick the system uses for list items, lighter
                    // than a keypress. Suppressed for rows the caller marks
                    // click-only (`haptic = false`).
                    if (haptic) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    onClick()
                })
        } else {
            Modifier
        }
        Column(
            modifier = clickModifier
                .heightIn(min = 56.dp)
                .padding(
                    start = 16.dp,
                    top = if (miuixRow) 16.dp else 14.dp,
                    end = 16.dp,
                    bottom = if (miuixRow) 16.dp else 14.dp
                ),
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
 *
 * It also decides the one thing the Miuix look changes about every row, from the
 * single place all rows already go through: under that look the icon is not
 * drawn at all, so the text column moves into where it stood and the row reads
 * like the system's own settings — title and summary against the card's margin,
 * nothing in front of them. No row, and no row added later, has to agree to
 * that; a row that stopped using this composable would simply stop honouring it,
 * which is why every settings row draws its leading icon through here.
 */
@Composable
fun RowIcon(painter: Painter, modifier: Modifier = Modifier) {
    if (!LocalUiStyle.current.showRowIcons) {
        return
    }
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
 * The paint mixed over the Miuix bar's blurred backdrop. The number IS the
 * example's (`PageUtils.BlurredBar`): the Gaussian band blends the page tone
 * at 0.8 over a 25dp blur. The earlier hand-picked 0.85/64dp pair predated the
 * example comparison and read as a heavier film than the reference ever shows.
 */
private const val BAR_GLASS_SCRIM_GAUSSIAN = 0.8f

/** The blur strength of the plain-Miuix title-bar glass, in dp — the example's own. */
private val GLASS_BLUR_GAUSSIAN = 25.dp

/**
 * The 柔光玻璃 look's title-bar blur, verbatim from the example's progressive
 * recipe (`PageUtils.BlurredBar`): a 10dp blur that is strongest at the bar's
 * top edge and fades to clear pixels at its foot, the falloff reshaped by the
 * example's 2.2 power curve, with the page tone blended at 0.3. Drawn by
 * miuix's own [progressiveTextureBlur] — the RuntimeShader path Haze has no
 * counterpart for — so this style only engages where a RuntimeShader exists;
 * anything older falls back to the Gaussian band.
 */
private val GLASS_BLUR_PROGRESSIVE = 10f

/** The page-tone blend under the progressive blur — the example's own. */
private const val BAR_GLASS_SCRIM_PROGRESSIVE = 0.3f

/** The falloff curve of the progressive blur — the example's own. */
private const val GLASS_BLUR_CURVE = 2.2f

/**
 * The blurred title-bar glass, one recipe per look. The plain Miuix look runs
 * on Haze (dev.chrisbanes.haze), not miuix's own blur — the "why" lives with
 * the dependency in build.gradle — as one uniform pass: [GLASS_BLUR_GAUSSIAN]
 * blur with a page-tone tint at [BAR_GLASS_SCRIM_GAUSSIAN], a band with a hard
 * bottom edge and no spatial fade. The 柔光玻璃 look instead draws the
 * example's progressive blur through miuix's [progressiveTextureBlur] (see
 * [GLASS_BLUR_PROGRESSIVE]), which needs the page's recorded [Backdrop] — the
 * caller hands it in only where the style has recorded one, and a missing
 * shader engine falls back to the Haze band.
 *
 * Either way the glass breathes with the page: [progress] — the caller's
 * bar-collapse fraction — scales the radius and the tint together, so a page
 * at its top reads as plain background paint and the depth builds as rows
 * travel under the bar.
 */

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
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixSwitchSlice(
            title = title,
            summary = miuixSummary(stateLine, stateLineOff, checked, description),
            checked = checked,
            onCheckedChange = onCheckedChange,
            first = first,
            last = last,
            modifier = modifier
        )
        return
    }
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
                        // The state line wears the summary's 4dp gap only when it
                        // IS the summary — no description below it. With a
                        // description following, the row is M3's three-line item
                        // (88dp) and the gap stays where it is, above the
                        // description.
                        modifier = if (description.isEmpty()) {
                            Modifier.padding(top = 4.dp)
                        } else {
                            Modifier
                        },
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
    if (LocalUiStyle.current.isMiuixBased) {
        // The bar has to be told this popup is open: its veil lands inside the
        // layer the floating pill records, and glass over that layer washes the
        // pill out with the page instead of leaving it untouched behind the
        // popup. See [PopupOverlay].
        val overlayKey = remember { Any() }
        DisposableEffect(overlayKey) {
            onDispose { PopupOverlay.clear(overlayKey) }
        }
        // The menu's option rows tick on the way down, like every other popup
        // under this look — the pick lands in the preference's own callback.
        val view = LocalView.current
        MiuixCardSurface(shape = connectedGroupShape(first, last), modifier = modifier) {
            // Miuix's own dropdown row: the value with the up-down arrow beside
            // it, a menu whose selected entry is blue with a check, and the page
            // dimmed behind the popup. Needs the Miuix Scaffold, which
            // [PageScaffold] supplies under this look.
            OverlayDropdownPreference(
                items = entries,
                selectedIndex = currentIndex,
                title = title,
                summary = description.ifEmpty { null },
                onSelectedIndexChange = { picked ->
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPick(picked)
                },
                onExpandedChange = { PopupOverlay.report(overlayKey, it) }
            )
        }
        return
    }
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
    /** Optional second line; an empty string renders a single-line row. */
    subtitle: String,
    onClick: () -> Unit,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    // Under Miuix this is ArrowPreference, not a restyled card: the chevron, the
    // margins and the press feedback are the library's, and the trailing slot
    // (the update badge) lands before the chevron through endActions.
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixCardSurface(shape = connectedGroupShape(first, last), modifier = modifier) {
            ArrowPreference(
                title = title,
                summary = subtitle.ifEmpty { null },
                onClick = onClick,
                endActions = if (trailing != null) {
                    { trailing() }
                } else {
                    {}
                }
            )
        }
        return
    }
    GroupRow(first = first, last = last, onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(painter = painterResource(iconRes))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
            if (LocalUiStyle.current.isMiuixBased) {
                // The Miuix "this leads somewhere" mark: a chevron after the
                // trailing slot, in the quiet grey of the bar's other affordances.
                Spacer(modifier = Modifier.width(8.dp))
                Image(
                    imageVector = MiuixIcons.Basic.ArrowRight,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                )
            }
        }
    }
}

/**
 * One summary line for a Miuix row: the Material card carries a state line and
 * a description at the same time, [top.yukonga.miuix.kmp.basic.BasicComponent]
 * carries one summary — the state the row is in wins, because that is the line
 * that changes under the user's finger.
 */
internal fun miuixSummary(
    stateLine: String?,
    stateLineOff: String?,
    checked: Boolean,
    description: String
): String? {
    val state = when {
        checked && stateLine != null -> stateLine
        !checked && stateLineOff != null -> stateLineOff
        else -> null
    }
    return state ?: description.ifEmpty { null }
}

/**
 * A switch row under the Miuix look: Miuix's own [BasicComponent] body with
 * Miuix's own [MiuixSwitch] as the end action, sitting in this app's group card
 * so a section still reads as one card.
 *
 * The switch is Miuix's rather than Material's on purpose: no on/off mark inside
 * the thumb (the track colour says it), no press shadow around it, and the row
 * press is Miuix's sink feedback rather than a ripple.
 *
 * The switch is keyed on [ThemeEngine.generation], and that key is load-bearing.
 * An appearance change (莫奈颜色, 强调色, dark mode) bumps the generation and
 * Miuix's `Colors` singleton mutates in place — at which point the library's
 * own `Switch` runs its `animateColorAsState` track/thumb ramps, roughly
 * 300 ms of spring, from the old palette to the new one. Every visible switch
 * on the page drifts through that ramp together while everything else on the
 * page (text, cards, the window) repaints instantly, and the drift is what
 * read as "the other switches flicker". Rebuilding the node on a generation
 * bump gives `animateColorAsState` a fresh initial value — its first
 * composition snaps to the target — so the switches change colour the same
 * instant the page does. An ordinary toggle touches no generation and keeps
 * the library's own flip animation untouched.
 */
@Composable
internal fun MiuixSwitchSlice(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val toggle: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onCheckedChange(!checked)
    }
    // Reading the generation here is what subscribes this row to appearance
    // changes; without the read the row would be skipped as unchanged and the
    // key below would never move.
    val generation = ThemeEngine.generation
    MiuixCardSurface(shape = connectedGroupShape(first, last), modifier = modifier) {
        BasicComponent(
            title = title,
            summary = summary,
            onClick = toggle,
            // What the library's own switch row declares (SwitchPreference puts
            // role = Role.Switch on its BasicComponent): the whole row reads as a
            // switch to a screen reader, not a plain button.
            role = Role.Switch,
            endActions = {
                key(generation) {
                    // The library's switch rows leave 8dp between the control and
                    // the row's own 16dp inside margin (SwitchPreference's end
                    // padding) — without it the switch sits tighter to the card
                    // edge than every library row.
                    MiuixSwitch(
                        checked = checked,
                        onCheckedChange = { toggle() },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        )
    }
}

/**
 * The Miuix title bar's glass — home title + search bar and secondary pages
 * alike. A wrapper, not a sheet:
 * whatever the caller puts inside defines the glass extent, and the Haze blur —
 * constant radius, faded out toward the bottom edge by one alpha mask over
 * both the blur and the tint (see the block comment on [GLASS_BLUR_RADIUS]) —
 * spans exactly that. It is the same construction LibChecker draws its app bar
 * blur with: the glass only has something to sample because the page's list
 * underlaps the bar. The M3 look wore this wrapper too and has dropped it
 * (its bars paint themselves the page background — [AppTopBar]).
 *
 * The extent hugging the bar is load-bearing. An earlier attempt used a fixed
 * 200dp sheet that reached well past the bar, and on device it broke both
 * ways: at rest the first section titles below the bar sat inside the sheet
 * and were blurred into smudges, and while scrolling a card straddling the
 * sheet's edge was sharp at its foot and blurred at its head. Nothing here
 * reaches past the bar's bottom edge.
 *
 * Placed in the scaffold's topBar slot this is outside the source subtree
 * (inside it, the glass's own output would be captured back as a source and
 * feed on itself), over the scrolling rows and under the bar's text, and it
 * consumes no touches — the bar's controls stay tappable.
 *
 * @param progress the page's bar-collapse fraction, 0 at the top of a page and
 *   1 once the large title has fully collapsed. The blur and the tint scale
 *   with it (see the block comment on [GLASS_BLUR_RADIUS]); callers without a
 *   collapsing bar (the home pages) leave it at 1 and keep the constant glass.
 * @param frost how much the pane frosts what it shows, on top of [progress]:
 *   1 is the full glass, 0 leaves the pane a plain window on the page — the
 *   scenery behind it reads sharp and no tint is laid over it. That is what a
 *   page carrying scenery of its own wears while the glass is switched off: it
 *   still needs the pane, because an opaque bar would guillotine its scenery.
 * @param backdrop the page's recorded layer, handed in only by the 柔光玻璃
 *   look — its blur runs on miuix's progressive shader, which reads the layer
 *   instead of Haze's sources (see [GLASS_BLUR_PROGRESSIVE]). `null` — every
 *   other look, or a device without a shader engine — keeps the Haze band.
 */
@Composable
fun HazeGlassBar(
    hazeState: HazeState,
    scrim: Color,
    modifier: Modifier = Modifier,
    progress: Float = 1f,
    frost: Float = 1f,
    backdrop: Backdrop? = null,
    content: @Composable () -> Unit
) {
    // The collapse fraction reads as NaN before the bar's scroll range has
    // been measured (0/0) — which is the state of a freshly composed MIUIX
    // tree, i.e. exactly the moment a style switch or page entry lands in —
    // and NaN * 64dp hit Haze's radius check ("must be specified and
    // non-negative") as a hard crash. Sanitize to the at-rest reading and
    // floor the radius above zero (a sub-visible 1dp, indistinguishable from
    // none) so the style never receives an unspeakable value.
    val safeProgress = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    // Frost rides the same ramp the progress does: one strength drives both, so
    // the switch can only ever scale the glass down to plain paper thin air.
    val strength = safeProgress * frost.coerceIn(0f, 1f)
    // One recipe per look. The plain Miuix look runs the Example's Gaussian on
    // Haze: a uniform frosted band, a 25dp blur with the page tone at 0.8, the
    // hard bottom edge a classic frosted bar has. The 柔光玻璃 look runs the
    // Example's progressive blur on miuix's own shader instead — 10dp strongest
    // at the top edge, fading to clear at the foot on the 2.2 curve, page tone
    // at 0.3 — which is exactly the pairing the reference draws; it needs the
    // page's recorded layer and a working RuntimeShader, so anything without
    // either lands back on the Gaussian band. (An earlier take removed the
    // progressive style outright because its fade washed out the apps page's
    // search capsule; the capsule has since gone plain, which was the whole
    // conflict, and the style is back — scoped to this look, no switch.)
    val blurRadius = (GLASS_BLUR_GAUSSIAN * strength).coerceAtLeast(1.dp)
    val scrimAlpha = BAR_GLASS_SCRIM_GAUSSIAN * strength
    val progressive = backdrop != null && isRuntimeShaderSupported()
    Box(modifier = modifier.fillMaxWidth()) {
        if (progressive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop!!,
                        shape = RectangleShape,
                        blurRadius = (GLASS_BLUR_PROGRESSIVE * strength).coerceAtLeast(1f),
                        gradient = ProgressiveBlur.Top.copy(curve = GLASS_BLUR_CURVE),
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    color = scrim.copy(alpha = BAR_GLASS_SCRIM_PROGRESSIVE * strength)
                                )
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .hazeBlur(
                        input = HazeInput.Sources(hazeState),
                        style = HazeBlurStyle {
                            blurRadius(blurRadius)
                            // Page tone: the resting glass reads as the bar's paint,
                            // not as a grey film over it. The tint rides the same
                            // ramp as the blur, so the two never fall out of step.
                            backgroundColor(scrim)
                            colorEffects(
                                listOf(
                                    HazeColorEffect.tint(
                                        scrim.copy(alpha = scrimAlpha)
                                    )
                                )
                            )
                            // Scroll-invariant: [progress] moves strength, never
                            // the mask shape.
                        }
                    )
            )
        }
        content()
    }
}

/** The fill the M3 look dresses a bar's lone leading control with — see [AppTopBar]. */
const val TOP_BAR_PLATE_ALPHA = 0.6f

/**
 * Whether the chrome a Miuix page draws for itself — its bar's controls — wears
 * 柔光玻璃. True only under [UiStyle.SoftGlass]: the material is what that style
 * *is*, so a page wearing it while the pick says plain Miuix would be the style
 * row lying about what it does. The bar's own pane is not this — it stays the
 * Haze frost on every Miuix-dressed look (see [MiuixGlassBar]); this answers for
 * the controls that sit on it.
 */
@Composable
internal fun softGlassChrome(): Boolean = LocalUiStyle.current == UiStyle.SoftGlass

/**
 * The layer a Miuix page records so its OWN chrome has something to refract.
 *
 * A page cannot sample the shell's backdrop, and the reason is structural, not a
 * shortcut: the shell records the page body *including this page's bar*, so a
 * control in the bar drawing the shell's layer would be drawing a layer it is
 * itself part of — the RenderNode cycle. F1Info hits the same wall and answers it
 * the same way its `MainRoute` does: a per-page layer whose recorded subtree is
 * the body only, with the bar composed outside it (see the call sites), so the
 * controls are siblings of the record and free to read it.
 *
 * The record starts from the page colour before the content, exactly as the
 * shell's does, and for the same reason: a frame the page has not painted yet
 * would otherwise record as transparent and read as black through the lens.
 */
@Composable
internal fun rememberPageLayerBackdrop(): LayerBackdrop {
    // Read above the lambda: the record is not a composable context.
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

/**
 * The leading control of a Miuix page bar.
 *
 * Bare is the library's own composition and what every Miuix page shipped with.
 * The glass is 柔光玻璃's: the same 44dp plate the rest of that look's chrome
 * wears ([SoftGlassIconButton]), which the bar carries as its sibling of the
 * page's recorded layer — see [rememberPageLayerBackdrop] for why that has to be
 * the page's own layer rather than the shell's.
 *
 * The glyph keeps the bare control's ink and size in both, so the material is the
 * only difference between the two.
 */
@Composable
private fun MiuixBackControl(
    description: String,
    onClick: () -> Unit,
    glass: Boolean
) {
    val glyph: @Composable () -> Unit = {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = description,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
    if (glass) {
        SoftGlassIconButton(
            onClick = onClick,
            recipe = SoftGlassRecipes.floatingNavigation(LocalSoftGlassSpec.current.fineTuning),
            content = glyph
        )
    } else {
        IconButton(onClick = onClick, content = glyph)
    }
}

/**
 * The page frame every secondary page uses, in whichever shell the app is
 * wearing.
 *
 * Material is the frame as it has always been. Miuix is the system settings'
 * own: the large-title bar that collapses to the small centred one as the page
 * scrolls up under it, and — load-bearing, not cosmetic — Miuix's Scaffold,
 * because the Miuix overlay popups ([OverlayDropdownPreference],
 * [OverlayListPopup]) render into the Scaffold's popup host and dim the page
 * behind them. Without this frame those menus have nowhere to open into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    titleRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** False hides the back control: the bottom-nav tabs are top-level pages. */
    showBack: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    /**
     * Drawn behind the page body on the Miuix look only, spanning the full
     * scaffold (the glass bar reads it back through its blur). The M3 branch
     * ignores it — the M3 look has no counterpart for this effect.
     */
    background: (@Composable BoxScope.() -> Unit)? = null,
    /**
     * Miuix only, and only for a page whose own hero IS its title: draw the bar
     * compact — the centred title alone, no band of its own — and let that title
     * arrive as the hero scrolls away. The number is how far the page's hero has
     * travelled, `0f` with the hero whole and `1f` once it is gone. Null keeps
     * the standard large-title bar.
     *
     * The model is KernelSU's about page, and the two reads below are its recipe:
     * the title fades in over the tail of the travel, and the pane builds on the
     * whole of it — the ramp a large bar would have taken from its own collapse
     * fraction. A compact bar cannot supply either, because the library pins its
     * [MiuixScrollBehavior] flat the moment a `SmallTopAppBar` attaches; hence
     * the page measures its own hero and hands the number in.
     */
    barScrollProgress: Float? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    if (LocalUiStyle.current.isMiuixBased) {
        val title = stringResource(titleRes)
        val scrollBehavior = MiuixScrollBehavior()
        // The glass is this look's default dressing, not a switch (the Example
        // ships it enabled): the pane is always mounted, so the bar always
        // frosts the content scrolling under it (`hazeSource` on the body box
        // below — Haze, see [HazeGlassBar]). A page that brings scenery of its
        // own ([background], today the about page's mesh) needs the pane too:
        // an opaque bar would cut that scenery off at the bar's foot and leave
        // a flat band of page colour above it.
        val pane = true
        val hazeState = rememberHazeState()
        // The page's own layer, and the controls that read it, described where
        // they are built below. Nothing is recorded outside 柔光玻璃: the plain
        // Miuix look has no control here that samples anything, and a record
        // costs a RenderNode capture whether or not it is drawn.
        val chromeGlass = softGlassChrome()
        val pageBackdrop = rememberPageLayerBackdrop()
        MiuixScaffold(
            modifier = modifier,
            // The page colour on both the scaffold and the bar, not Miuix's
            // defaults: the app's surface policy owns the page colour, and the
            // defaults would disagree with it behind the content and in the bar.
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // The leading control, whichever bar wears it. The library's own
                // arrow in the library's own bare button — every floating-plate
                // attempt here OUTSIDE the soft-glass material (grey outline,
                // bright puck with halo, the library's FAB, a painted crescent
                // shadow) ended in mud one way or another, so the bare control
                // stays the icon it was. 柔光玻璃 is the one plate that is not a
                // hand-rolled attempt: it is the material the rest of that look
                // is milled from — see [MiuixBackControl].
                val backControl: @Composable () -> Unit = if (showBack) {
                    {
                        val description = stringResource(io.github.howard20181.hyperos.fcmlive.R.string.back)
                        MiuixBackControl(
                            description = description,
                            onClick = onBack,
                            glass = chromeGlass
                        )
                    }
                } else {
                    {}
                }
                // The paint belongs to the pane wherever there is one; with the
                // switch off and no scenery of its own, the bar paints itself the
                // page colour.
                val barColor = if (pane) Color.Transparent else MaterialTheme.colorScheme.background
                val bar: @Composable () -> Unit = {
                    // The controls on the bar read the page's OWN layer. The
                    // provider wraps only the bar, because only the bar reads it
                    // — the page body is the recorded node and does not.
                    CompositionLocalProvider(LocalSoftGlassBackdrop provides pageBackdrop) {
                    val travel = barScrollProgress
                    if (travel == null) {
                        MiuixTopAppBar(
                            title = title,
                            largeTitle = title,
                            color = barColor,
                            navigationIcon = backControl,
                            actions = actions,
                            scrollBehavior = scrollBehavior
                        )
                    } else {
                        // The hero IS this page's title, so the bar keeps none of
                        // its own: the reference's compact bar, whose title walks
                        // in as the hero walks out — nothing over the hero's first
                        // third of travel, the whole title once it is gone.
                        MiuixSmallTopAppBar(
                            title = title,
                            titleColor = MiuixTheme.colorScheme.onSurface.copy(
                                alpha = ((travel - 0.35f) / 0.65f).coerceIn(0f, 1f)
                            ),
                            color = barColor,
                            navigationIcon = backControl,
                            actions = actions,
                            scrollBehavior = scrollBehavior
                        )
                    }
                    }
                }
                // The glass wraps the bar (extent = the bar's own, collapsing
                // included); the paint is the glass's job — hence what the bar
                // above is told to be transparent for. With no pane at all the
                // bar stands in its own colour and no layer is recorded.
                if (pane) {
                    HazeGlassBar(
                        hazeState = hazeState,
                        scrim = MaterialTheme.colorScheme.background,
                        // A large bar hands in its own collapse fraction; a
                        // compact hero page hands in its hero's travel instead
                        // (the compact bar is pinned flat — see
                        // [barScrollProgress]).
                        progress = barScrollProgress ?: scrollBehavior.state.collapsedFraction,
                        // The pane never goes away in this look; the glass is
                        // always full.
                        frost = 1f,
                        // The 柔光玻璃 look's blur reads the recorded layer; the
                        // layer is only recorded where that look is live.
                        backdrop = if (chromeGlass) pageBackdrop else null
                    ) {
                        bar()
                    }
                } else {
                    bar()
                }
            }
        ) { innerPadding ->
            // No snackbar host on this look: its acknowledgments are the system
            // toast (see the About and Licenses pages), not a bar at the foot.
            //
            // The floating bar's clearance rides in here — see
            // [LocalFloatingBarClearance]: it is given to the page as inset, so it
            // becomes scroll room at the end of the list rather than a shorter
            // viewport. Zero everywhere the docked bar is used.
            val clearance = LocalFloatingBarClearance.current
            val layoutDirection = LocalLayoutDirection.current
            val bodyPadding = if (clearance == 0.dp) {
                innerPadding
            } else {
                PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    bottom = innerPadding.calculateBottomPadding() + clearance
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Only a pane reads a source layer back, so nothing is
                    // recorded when there is no pane to read it.
                    .then(if (pane) Modifier.hazeSource(hazeState) else Modifier)
                    // The body alone, and the bar above is composed OUTSIDE this
                    // node — that is what lets the bar's control read the layer
                    // without the cycle; see [rememberPageLayerBackdrop].
                    .then(if (chromeGlass) Modifier.layerBackdrop(pageBackdrop) else Modifier)
                    // The bar reads the list's scroll through this connection;
                    // the page bodies all scroll, so putting it here covers them
                    // all without each page having to opt in.
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
            ) {
                // Behind the body and under the glass bar — the bar's blur
                // picks it up through the haze source above.
                background?.invoke(this)
                content(bodyPadding)
            }
        }
        return
    }
    // The Material counterpart of the Miuix branch above: the large title at
    // rest, exitUntilCollapsed into the 64dp bar as the list scrolls up under
    // it. The connection hangs on the Scaffold modifier so every page body
    // that scrolls drives the collapse without opting in.
    //
    // No glass on this look — the bar paints itself the page's background (see
    // [AppTopBar]); rows travelling under it are occluded, which is exactly
    // what the pre-glass treatment did.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    androidx.compose.material3.Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            if (snackbarHostState != null) SnackbarHost(snackbarHostState)
        },
        topBar = {
            AppTopBar(
                titleRes = titleRes,
                onBack = onBack,
                showBack = showBack,
                actions = actions,
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        // The floating bar's clearance, applied exactly as the Miuix branch
        // above applies it — THE unified foot rule: every page built on
        // [PageScaffold] rests its last row this far above the shell's bar,
        // on both looks, without each page knowing the bar exists. (The apps
        // tab is the one page that builds its own scaffold; it spends the
        // same [LocalFloatingBarClearance] on its list — one value, two
        // spenders, so a new page can only match by doing nothing.)
        val clearance = LocalFloatingBarClearance.current
        val layoutDirection = LocalLayoutDirection.current
        val bodyPadding = if (clearance == 0.dp) {
            innerPadding
        } else {
            PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection),
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = innerPadding.calculateBottomPadding() + clearance
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            content(bodyPadding)
        }
    }
}

/**
 * Text styles for the plain-prose pages (licences, privacy).
 *
 * Under the Miuix look these are the sizes and weights the library's own rows
 * are built from — read out of `BasicComponent`, which every Miuix preference in
 * this app sits on: its title is `headline1`'s 17sp at `FontWeight.Medium` and
 * its summary is `body2`'s 14sp. Picking `textStyles.main` (17sp, no weight) for
 * a page title instead left those pages a weight lighter than every settings row
 * beside them — the type read as Material wearing Miuix colours, which is
 * exactly what it looked like.
 *
 * The lookup is a @Composable call, so each branch only ever touches its own
 * theme's accessor.
 */
@Composable
fun pageBodyStyle(): TextStyle =
    if (LocalUiStyle.current.isMiuixBased) {
        // The row title grade: 17sp, Medium — `BasicComponent`'s own two
        // parameters, spelled out here rather than reached through `main`, which
        // states the size and leaves the weight to the platform's default.
        MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.Medium)
    } else {
        MaterialTheme.typography.bodyLarge
    }

/**
 * Secondary line under [pageBodyStyle]: the row summary grade — Miuix `body2`
 * (14sp, the size `BasicComponent` prints its summary at) against M3 bodySmall.
 */
@Composable
fun pageSecondaryStyle(): TextStyle =
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixTheme.textStyles.body2
    } else {
        MaterialTheme.typography.bodySmall
    }

/**
 * Running prose (dialog bodies, paragraphs): the same row-summary grade as
 * [pageSecondaryStyle] under Miuix — a paragraph is the same 14sp the library
 * prints summaries at, so a sheet's prose and the row that opened it agree —
 * against M3 bodyMedium.
 */
@Composable
fun pageProseStyle(): TextStyle =
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixTheme.textStyles.body2
    } else {
        MaterialTheme.typography.bodyMedium
    }

/**
 * Text in whichever look the app is wearing.
 *
 * The two looks keep their type in different places, and the primitive is what
 * picks it: material3's [Text] merges the caller's [style] onto the *Material*
 * typography, while the library's [MiuixText] merges it onto
 * `LocalTextStyles.current.main`. A Miuix style handed to the material3
 * primitive therefore only overrides what that style states — the M3 line
 * heights and letter spacings it leaves unspecified stay, and the run reads as
 * Material type inside a Miuix row even though every size in it is Miuix's.
 *
 * Callers hand over a style from [pageBodyStyle] and its siblings (or the
 * look-branched styles the list rows build), and get that look's type back.
 * Both defaults are the look's own, so a caller that states nothing still lands
 * on the right one.
 */
@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixText(
            text = text,
            modifier = modifier,
            color = color,
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow
        )
    } else {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

/**
 * A dialog in whichever shell the app is wearing.
 *
 * Material is the M3 [androidx.compose.material3.AlertDialog] as before. Miuix is
 * the system's own [OverlayDialog] — the HyperOS sheet rendered through the
 * scaffold's [top.yukonga.miuix.kmp.basic.Scaffold] popup host, the path the
 * library's own dialog demo takes (the window-level variant is the desktop
 * shell) — with [MiuixTextButton]s in the action row, laid out the way the
 * reference apps and the library demo lay theirs out: dismiss at the start,
 * confirm at the end, the confirm in the primary fill. The body prints
 * left-aligned in the page's prose style; the licence text goes through
 * [content] as a scrollable card.
 *
 * [content] overrides [text] for bodies that are not one paragraph (the licence
 * text is scrollable); it renders inside the dialog's own body slot.
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String? = null,
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    dismissLabel: String? = null,
    onDismiss: () -> Unit = {},
    // An optional third action for the Material branch's bottom row (the
    // licence sheet's 查看源代码): it renders where a dismiss button would,
    // leading of the confirm. The Miuix branch ignores it — Miuix dialogs
    // that need an extra action lay their own foot row (MiuixLicenseDialog).
    neutralButton: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null
) {
    if (LocalUiStyle.current.isMiuixBased) {
        // A dialog dims the page exactly as a dropdown does, and its veil lands
        // inside the layer the floating pill records — so it reports itself too,
        // and the pill holds its plain build while it is up. See [PopupOverlay].
        // Composed only while shown (callers wrap it in a `let`), so entering the
        // composition is the open and leaving it is the close.
        val overlayKey = remember { Any() }
        DisposableEffect(overlayKey) {
            PopupOverlay.report(overlayKey, true)
            onDispose { PopupOverlay.clear(overlayKey) }
        }
        OverlayDialog(
            show = true,
            title = title,
            onDismissRequest = onDismissRequest
        ) {
            Column {
                if (content != null) {
                    content()
                } else if (text != null) {
                    // Printed by hand rather than handed to the library's
                    // summary slot: that slot centres its text, which shredded
                    // a multi-line caution into a centred wall. Left-aligned in
                    // the page's prose style, in the summary's own color slot —
                    // the same reading order the Material branch prints with.
                    AppText(
                        text = text,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = pageProseStyle(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // The system dialog's own action row: two equal-width buttons,
                // dismiss leading and confirm trailing — not a right-aligned
                // pair of text links, which is what read as "not Miuix". The
                // confirm side carries the primary fill, the way the licence
                // sheet's closing button does: one look at the row and the
                // forward action is the filled one.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (dismissLabel != null) {
                        MiuixTextButton(
                            text = dismissLabel,
                            onClick = {
                                onDismiss()
                                onDismissRequest()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (confirmLabel != null) {
                        MiuixTextButton(
                            text = confirmLabel,
                            onClick = {
                                onConfirm()
                                onDismissRequest()
                            },
                            modifier = Modifier.weight(1f),
                            colors = MiuixButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
        return
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        // Popup level, like every panel drawn over the page: M3's dialog default
        // is a tone *below* the page on this ramp, so the dialog would sit behind
        // the surface it is drawn over.
        containerColor = LocalAppSurfaces.current.popup,
        title = { Text(title) },
        text = {
            if (content != null) {
                content()
            } else if (text != null) {
                Text(text)
            }
        },
        // Contract parity with the Miuix branch above: callers hand the action
        // itself and the dialog always ends with onDismissRequest — a bare
        // onConfirm/onDismiss leaves the dialog on screen (only the system
        // back gesture could close it).
        confirmButton = {
            if (confirmLabel != null) {
                TextButton(onClick = {
                    onConfirm()
                    onDismissRequest()
                }) { Text(confirmLabel) }
            }
        },
        dismissButton = {
            when {
                dismissLabel != null -> TextButton(onClick = {
                    onDismiss()
                    onDismissRequest()
                }) { Text(dismissLabel) }
                // No dismiss, but a neutral action was handed in: the slot is
                // just layout, so the neutral button leads the row instead.
                neutralButton != null -> neutralButton()
            }
        }
    )
}
