/*
 * UI red lines for this module — each one cost a rebuild to discover.
 *
 * - This file is the single definition of the app's type / shape / motion /
 *   surface scale. Add or change a scale here, never at a call site.
 * - Compose: `.clip(shape)` must come **before** `.clickable`, or the ripple
 *   spills outside the shape.
 * - A FAB needs an explicit FAB_CLEARANCE (88.dp) above the list; the
 *   scaffold does not reserve it.
 * - material3 is pinned to 1.5.0-alpha27, which does not accept
 *   `menuAnchorPosition`: right-aligned dropdowns are done with
 *   `matchParentSize().wrapContentSize(BottomEnd)` instead.
 * - Icons — **BOTTOM LINE: every icon in this app is a Material Symbols
 *   glyph, in its `fill1` form.** No hand-drawn art, no approximate or
 *   look-alike substitutes, no plain material-design-icons glyphs, no
 *   outline-only glyphs where a filled one exists. Pull the path verbatim
 *   from
 *   `https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/<name>/fill1/24px.svg`
 *   (byte-identical to a fonts.google.com download; `materialsymbolsrounded`
 *   or `materialsymbolssharp` are the same URL with the family swapped, and
 *   are the only sanctioned deviation — pick one per icon, never mix within
 *   a row), then move it onto the 960 canvas:
 *   `viewportWidth/Height="960"` plus `<group android:translateY="960">`.
 *   A `FILL0` in a fonts.google.com download's filename does **not** mean the
 *   glyph lacks a filled form — plenty of glyphs (e.g. `android_cell_4_bar`,
 *   `save`, `settings_backup_restore`) serve the same path for fill0 and
 *   fill1, and the fill0 URL 404s. Judge compliance by whether the `fill1`
 *   URL above answers 200, never by the downloaded filename.
 *   Two things are deliberately not icons and stay as they are: the launcher
 *   icon layers (`ic_launcher_*`, this app's own brand mark) and the
 *   launcher shortcut tiles (`drawable-xxhdpi/ic_shortcut_*.png`).
 *   Where a Symbols glyph's fill1 form is itself a ring
 *   (`radio_button_unchecked`), that is the glyph — do not "fix" it into a
 *   disc.
 * - Settings-shaped screens (Settings, Experiment, Privacy, Licenses) are laid
 *   out as **sectioned settings over a segmented list**, and a new one must
 *   follow the same structure rather than invent its own:
 *   one `SectionTitle` per section, then that section's rows as ONE connected
 *   group — `GroupRow` / `SettingsSwitchCard` / `SettingsMenuCard` / `NavCard`
 *   all take `first` / `last`, rows are separated by `GROUP_ROW_GAP` (2dp) and
 *   only the ends keep the group radius. A row revealed by another row is the
 *   *second row of its master's group*, never a card of its own, so the master
 *   passes `last = false` for exactly as long as the revealed row is on screen.
 * - **A container colour and the text on it are one decision, not two.** Every
 *   `xContainer` takes `onXContainer` and nothing else — and a handle takes its
 *   partner the same way (the switch thumb is `onPrimary`, so its mark is
 *   `primary`; see `ui/ScreenParts.kt` `appSwitchColors`). The trap is that an
 *   unrelated foreground *looks* fine: `onSurface` on `primaryContainer` is a
 *   `7.91:1` label on TONAL_SPOT (2025 spec, light) and on MONOCHROME — whose
 *   `primaryContainer` is tone 25 — it is `1.54:1`, i.e. gone. Measured over
 *   `4 seeds x 9 styles x 2 specs x light/dark` the pair bottoms out at
 *   `1.13:1` (VIBRANT, 2025 spec, dark) while the paired role never drops below
 *   `4.54:1`. So when a role is given a container, give it the pair, and check
 *   the result against the official material-color-utilities numbers rather
 *   than by eye.
 * - **Two settings can be coupled; say so once.** The colour spec and the
 *   palette style are not independent: the 2025 spec only covers four styles
 *   (`Scheme.Variant.supportsExpressive2025`) and every other style renders
 *   2021. That one property is what the style menu, the spec menu and
 *   `ThemePrefs.specVersion` all read, so no two rows can disagree about which
 *   pairs exist — see ui/AboutScreen.kt for the scoping and ThemePrefs for the
 *   resolution.
 * - **One raised container for the whole app, and the page's own level for the
 *   panels that land on it.** Cards are
 *   `Surface(color = LocalAppSurfaces.current.card, shape = AppShapes.card)`,
 *   and so is every other raised surface *holding content* — group plates, nav
 *   rows. The card is `surfaceBright`; the page it sits on is
 *   `surfaceContainer`. A **control** drawn over that content is not a card and
 *   does not take this role: the top bar's back plate is
 *   `surfaceContainerHighest` at 60% (`ui/AppTopBar.kt`, the role both
 *   references fill it with), because a control painted in the card's own fill
 *   reads as the card rather than as something laid on top of it.
 *   A **popup** wears the page's level again
 *   (`LocalAppSurfaces.current.popup`): a dropdown is anchored to a row inside
 *   a card, so most of the panel rests on that card, and painting it with the
 *   card's own fill left the overlap with no edge at all — the only thing left
 *   to separate two identical fills is the elevation shadow, which a dark
 *   appearance cannot show. Every dropdown and dialog therefore takes `popup`,
 *   never `card`.
 *   **A panel is separated by tone, never by a border.** A stroke is what you
 *   reach for when the tone step is wrong, and it reads as a wireframe around
 *   the one surface in the app that is drawn over others. Material's own menus
 *   carry no outline: they are `Surface(color = MenuTokens.ContainerColor
 *   (= surfaceContainer), tonalElevation = MenuDefaults.TonalElevation,
 *   shadowElevation = MenuDefaults.ShadowElevation)` and nothing else, and this
 *   app's popup wears that same `surfaceContainer` role. What a panel needs is
 *   a *level*, and here they are, as CAM16 tones (which are L*):
 *
 *     light : popup 94  =  page 94   <  card 98
 *     dark  : popup 12  =  page 12   <  card 24
 *     AMOLED:           page 0   <  card 6   <  popup 12
 *
 *   Light and dark read the scheme's roles straight — page and popup are both
 *   `surfaceContainer`, the card is `surfaceBright` — so the panel sits on the
 *   page's exact tone, as Material's own menu does, and the step that decides
 *   whether it reads as a panel is the one to the *card* it lands on: 4 tones
 *   in light, 12 in dark, 6 in AMOLED. Over the page there is no tone step at
 *   all; that case is carried by the panel's shadow, which is exactly why the
 *   popup is the page's level and never the level just off it. AMOLED is the
 *   near-black ramp, neutral on purpose. Measure a level assignment in tones
 *   before trusting it: a hex step is not a step, and `#000000` next to
 *   `#0A0A0A` is 0.8, which is how an AMOLED menu came to melt into its page.
 *   The *tint* of every level is read straight off the scheme, so the app wears
 *   a different page in every palette style — that is the point of the setting,
 *   and normalising the tint is what once made both specs look identical. Do
 *   not hand a call site a raw `colorScheme.surface*` for a page, a card or a
 *   popup either: AMOLED repaints the ramp outside the roles, so the three
 *   levels only exist as a set. [AppPalette] owns them.
 * - **The official token is the default — but a token is a claim about the
 *   spec, not the spec itself: where a token and a reference implementation's
 *   rendering disagree, the rendering is what this app has to match.**
 *   Component roles are read off material3's own token classes
 *   (`SwitchTokens.SelectedHandleColor` is `OnPrimary`, its
 *   `SelectedIconColor` is `OnPrimaryContainer`, `MenuDefaults.ContainerColor`
 *   is `SurfaceContainer`). The two places that do **not** follow the token,
 *   both with a measured reason:
 *   - a menu entry that is the current value wears `PrimaryContainer` /
 *     `OnPrimaryContainer` (`ui/ScreenParts.kt` `MenuItemRow`), and **not** the
 *     `StandardMenuTokens.ItemSelectedContainerColor` the pinned alpha27 names.
 *     On this app's own default setting that token renders a pink plate
 *     (`#F4BFE3`, TONAL_SPOT / 2025 spec, light) beside a purple accent, and in
 *     dark mode it renders that same pale pink — the loudest thing on screen.
 *     The reference implementation (DPIS, material3 1.5.0-alpha28, stock
 *     `SelectableDropdownMenuItem` with only `containerColor` overridden to
 *     transparent) paints its theme menu's current entry `#3B3B3B` in
 *     MONOCHROME light; on the official colour utilities that hex is
 *     `PrimaryContainer` (tone 25) while `TertiaryContainer` is `#747474`
 *     (tone 50). The same role is what the app list's selected row already
 *     wears (`ui/MainScreen.kt`) and what a checked overflow-menu row wears,
 *     which is material3's own reading too: `CheckableDropdownMenuItem` takes
 *     the same `SelectableMenuItemColors` as `SelectableDropdownMenuItem` and
 *     feeds its checked flag into the selected slot. One item cannot be the
 *     current value in one colour here and a different colour there.
 *     (This row has been through the pre-1.5 `SecondaryContainer` token and
 *     alpha27's `TertiaryContainer` since. Both were token readings, and both
 *     put a colour on screen the reference never showed.)
 *   - the selected switch mark, whose token pair collapses. It sits on
 *     the handle, and a measured audit over
 *     `4 seeds x 9 styles x 2 specs x light/dark` shows the pair falling below
 *   3:1 in three families — MONOCHROME (whose `onPrimaryContainer` is *lighter*
 *   than its `onPrimary`, 1.2-1.3:1), CONTENT and FIDELITY in light mode
 *   (`onPrimary` is pure white and `onPrimaryContainer` is a pastel taken from
 *   the source, 1.4:1), and VIBRANT on the 2025 spec (1.1-1.2:1) — 36 of the
 *   144 combinations. There the mark falls back to the handle's partner role,
 *   which measures 6.1-17.2:1 on those same combinations; the guard is a
 *   measured 3:1 check (`ui/ScreenParts.kt` `appSwitchColors`) rather than a
 *   list of style names, so it cannot go stale when a palette or a spec
 *   changes — and it fired in three families this round for exactly that
 *   reason, two of which were not known before the 2025 spec was implemented.
 */

package io.github.howard20181.hyperos.fcmlive.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Motion, per colour-spec generation.
 *
 * This is the pillar that separates the two specs on the component layer:
 * animations built into Material components — item reordering, settling
 * states, shape morphs — read `MaterialTheme.motionScheme` instead of
 * hard-coding a tween. [MotionScheme.expressive] gives them the springy
 * overshoot curve; [MotionScheme.standard] is the flatter 2021 curve.
 *
 * Binding it to the colour spec is what stops one generation from wearing the
 * other's skin: picking "Material You (2021)" and still moving like Expressive
 * is the one thing the user would actually feel.
 *
 * Requires material3 1.5.0-alpha or newer: 1.4.0 ships this exact API but
 * declares it Kotlin-internal, so it cannot be referenced there.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun motionSchemeFor(expressive: Boolean): MotionScheme =
    if (expressive) MotionScheme.expressive() else MotionScheme.standard()

/**
 * The two MaterialTheme pillars that [HyperFCMLiveTheme] used to leave at their
 * library defaults: typescale and shape scale.
 *
 * Both restate official M3 spec values rather than inventing numbers of their
 * own, and they are spelled out in full even where the library default would
 * already match. That is deliberate: this file stays the one place the app's
 * type and shape scale can be read and changed, instead of a dozen call sites
 * each carrying their own `.sp` and `.dp`.
 *
 * The XML half of the scale (`res/values/type.xml`, `dimens.xml`, `shape.xml`,
 * `motion.xml`) was deleted once the last View screen became Compose. It had no
 * runtime consumer left, and a second copy of the same numbers is not a
 * cross-check — it is a copy that drifts, and one that ships in the APK.
 */
val HyperFCMLiveTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
)

/**
 * The generic shape scale — the M3 corner-radius tokens, restated here in dp.
 *
 * Both generations deliberately share these five values. M3 Expressive did not
 * redefine the original scale — the May 2025 update *added* three higher steps
 * (`Large increased` 20dp, `Extra large increased` 32dp, `Extra extra large`
 * 48dp) and moved components onto them. The lower five steps still mean exactly
 * what they meant in 2021, so the token definitions stay put and the App roles
 * below are what actually shifts.
 */
val HyperFCMLiveShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Shape roles this app actually paints.
 *
 * [HyperFCMLiveShapes] carries the generic scale; this carries the *mapping*,
 * which is where the two screens differ by intent rather than by taste: a
 * standalone card is ExtraLarge while a grouped container is Large, even though
 * both are "a rounded surface". The 2021 values were read back off the drawable
 * the View layer used to inflate, before that layer was retired, so this is
 * what the screens already looked like rather than a fresh decision.
 *
 * Provided through [LocalAppShapes] because MaterialTheme has only the five
 * generic slots and no room for App-specific roles.
 */
class AppShapes(
    val card: CornerBasedShape,
    val menu: CornerBasedShape,
    val menuItem: CornerBasedShape,
    val tooltip: CornerBasedShape,
    val fab: CornerBasedShape,
    /**
     * Corners of a connected group — the About and Licenses card stacks, where
     * rows touch and only the ends keep the group radius.
     *
     * Both come from the same steps the other roles use: the ends are [menu]
     * and the seams are [tooltip] (extraSmall), so the pair moves with the spec
     * for free.
     */
    val groupOuter: CornerBasedShape,
    val groupInner: CornerBasedShape,
) {
    companion object {
        /**
         * Material You (2021): the mapping the retired View layer was using.
         *
         * | role     | dp | step        | why                                       |
         * |----------|----|-------------|-------------------------------------------|
         * | card     | 16 | large       | same radius the About card group shows    |
         * | menu     | 16 | large       | menus and grouped cards share the card    |
         * |          |    |             | radius so they read as one surface        |
         * | menuItem | 8  | —           | tighter than large: a 40dp row needs a    |
         * |          |    |             | corner that reads, not one that curves    |
         * | tooltip  | 4  | extraSmall  | tooltips stay small in both specs         |
         * | fab      | 16 | large       | matches the card it floats above          |
         *
         * `card` is pinned to the group radius on purpose: one corner value for
         * every card on every screen — the standalone privacy cards, the switch
         * cards and the main list rows all match the four cards under the About
         * heading, instead of each role drifting to its own number.
         */
        val Baseline = AppShapes(
            card = RoundedCornerShape(16.dp),
            menu = RoundedCornerShape(16.dp),
            menuItem = RoundedCornerShape(8.dp),
            tooltip = RoundedCornerShape(4.dp),
            fab = RoundedCornerShape(16.dp),
            groupOuter = RoundedCornerShape(16.dp),
            groupInner = RoundedCornerShape(4.dp),
        )

        /**
         * M3 Expressive (2025): every role steps up one notch, using only the
         * steps Expressive actually added — no invented numbers.
         *
         * | role     | 2021 | 2025 | step the 2025 value comes from |
         * |----------|------|------|--------------------------------|
         * | card     | 16   | 20   | largeIncreased (group radius)  |
         * | menu     | 16   | 20   | largeIncreased                 |
         * | menuItem | 8    | 8    | unchanged: see Baseline        |
         * | tooltip  | 4    | 4    | extraSmall (unchanged on purpose: |
         * |          |      |      |  tooltips stay small in both specs) |
         * | fab      | 16   | 20   | largeIncreased                 |
         */
        val Expressive = AppShapes(
            card = RoundedCornerShape(20.dp),
            menu = RoundedCornerShape(20.dp),
            menuItem = RoundedCornerShape(8.dp),
            tooltip = RoundedCornerShape(4.dp),
            fab = RoundedCornerShape(20.dp),
            groupOuter = RoundedCornerShape(20.dp),
            // Unchanged like tooltip: a seam between two rows is not a place
            // Expressive asks for more radius.
            groupInner = RoundedCornerShape(4.dp),
        )
    }
}

internal fun appShapesFor(expressive: Boolean): AppShapes =
    if (expressive) AppShapes.Expressive else AppShapes.Baseline

val LocalAppShapes = staticCompositionLocalOf { AppShapes.Baseline }

/**
 * The app's own container colors — the raised container and the panel that
 * lands on it.
 *
 * [AppPalette] resolves the app's three levels (page, card, popup) once; this
 * carries the two that are not a `ColorScheme` slot to the call sites, the same
 * way [LocalAppShapes] carries the shape roles. They live beside the scheme
 * rather than inside it because AMOLED repaints the ramp outside the roles: on
 * the light and dark ramps the levels *are* Material roles (page and popup
 * `surfaceContainer`, card `surfaceBright`), but an AMOLED page is true black,
 * which no role resolves to. One local keeps that exception in one place.
 *
 * [popup] exists for a geometric reason: a popup panel is anchored to a row
 * *inside* a card, so most of it covers the card rather than the page. Painting
 * it with [card] made the overlap invisible — the only thing left to separate
 * two identical fills was the elevation shadow, which dark mode cannot show at
 * all. The panel therefore wears the page's own level — Material's
 * `MenuDefaults.ContainerColor`, which is `surfaceContainer` — and the tone step
 * to the card is what separates it in every appearance; see `ComposeTokens.kt`'s
 * red lines for the levels and the gaps between them. There is no `border`
 * anywhere for this: an outline around the only overlaid surface in the app is
 * what a panel wears when its tone is wrong, not what makes it legible.
 *
 * The page stays where it is: it is `MaterialTheme.colorScheme.background`
 * (`AppPalette.pageBg`), which is also what the window is painted with.
 */
@Immutable
class AppSurfaces(
    /** Fill of every card, settings row, group and raised plate. */
    val card: Color,
    /**
     * Fill of every popup panel — dropdown menus and dialogs. Distinct from
     * [card] on purpose: see the note above.
     */
    val popup: Color
) {
    companion object {
        /**
         * Reached only outside a theme — a `@Preview` before the palette
         * resolves. `Unspecified` paints nothing, which is honest for a frame
         * that has not decided its colors yet; in-app the local is always
         * provided by [HyperFCMLiveTheme].
         */
        val Default = AppSurfaces(card = Color.Unspecified, popup = Color.Unspecified)
    }
}

val LocalAppSurfaces = staticCompositionLocalOf { AppSurfaces.Default }
