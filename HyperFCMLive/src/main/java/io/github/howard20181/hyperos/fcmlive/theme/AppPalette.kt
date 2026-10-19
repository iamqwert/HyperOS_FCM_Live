package io.github.howard20181.hyperos.fcmlive.theme

import io.github.howard20181.hyperos.fcmlive.mcu.Scheme

/**
 * The app's semantic colors for one appearance configuration. Every field is
 * resolved once, from a [Scheme], and then read by the UI.
 *
 * Public int/boolean roles stay `@JvmField` so existing Java call sites keep
 * field access (`palette.primary`) without switching to synthetic getters.
 */
class AppPalette internal constructor(
    /** Full Material roles; Compose theme bridge maps these to `ColorScheme`. */
    @JvmField
    val scheme: Scheme,
    dark: Boolean
) {

    /** Raw Material roles, kept for callers that need the full set. */
    @JvmField
    val primary: Int = scheme.primary
    @JvmField
    val onPrimary: Int = scheme.onPrimary
    @JvmField
    val primaryContainer: Int = scheme.primaryContainer
    @JvmField
    val surface: Int
    @JvmField
    val surfaceVariant: Int = scheme.surfaceVariant
    @JvmField
    val surfaceContainerLowest: Int
    @JvmField
    val surfaceContainerLow: Int
    @JvmField
    val surfaceContainerHigh: Int
    @JvmField
    val surfaceContainerHighest: Int
    @JvmField
    val onSurface: Int = scheme.onSurface
    @JvmField
    val onSurfaceVariant: Int = scheme.onSurfaceVariant
    @JvmField
    val outline: Int = scheme.outline
    @JvmField
    val outlineVariant: Int = scheme.outlineVariant
    @JvmField
    val inverseSurface: Int = scheme.inverseSurface
    @JvmField
    val inverseOnSurface: Int = scheme.inverseOnSurface

    /** Semantic aliases used by the layouts. */
    @JvmField
    val pageBg: Int
    /**
     * The one raised container in the app — every card, every row of a
     * settings group, the plates that carry content. On the light and dark
     * ramps it is the `surfaceBright` role; see the `init` block for how the
     * three levels (page / card / popup) are assembled. It is *not* the fill
     * for a control drawn over content — the back plate is
     * `surfaceContainerHighest` at 60% (`ui/AppTopBar.kt`).
     */
    @JvmField
    val cardBg: Int
    @JvmField
    val popupBg: Int

    @JvmField
    val dark: Boolean = dark

    init {
        // The three levels this app paints, as CAM16 tones (which are L*):
        //
        //   light : popup 94  =  page 94   <  card 98
        //   dark  : popup 12  =  page 12   <  card 24
        //
        // Light and dark read the scheme's own roles and nothing else: the page
        // is `surfaceContainer`, the raised container is `surfaceBright`, and
        // the popup is `surfaceContainer` again — the very role M3 hands its
        // menus (`MenuDefaults.ContainerColor`). A dropdown therefore lands on
        // the exact tone of the page it is drawn over, and a card owns one
        // clear step above both. No role is picked by eye and no tone is
        // normalised: each style hands its neutral palette a different amount of
        // the seed hue — 0 for MONOCHROME and RAINBOW, 1.4 for NEUTRAL, 5-6 for
        // TONAL_SPOT, 8 on the 2021 spec vs 18 on the 2025 one for EXPRESSIVE,
        // 28 for VIBRANT, the source color itself for CONTENT and FIDELITY —
        // and that number *is* the style: a wash at one end, an unmistakable
        // tint at the other. Pulling them all onto one value is what used to
        // make every style, and therefore both specs, look the same on screen,
        // so the setting the user had just changed was the one thing the
        // surfaces could not show.
        //
        // `surfaceBright` is the role that holds "the raised thing" in both
        // appearances: `surface` would do in light (it equals `surfaceBright`
        // there) but collapses onto the page in dark, while `surfaceBright`
        // stays the bright end. Roles are a rank, not a look — `surface` sits
        // above `surfaceContainerHighest` in light and below it in dark — so
        // the pair is written here rather than mapped from a single role.
        //
        // What the panel still has to do is differ from the card it lands on,
        // and it does that by tone: see [AppSurfaces] and `ComposeTokens.kt`
        // for why that is a role choice and never a border.
        surface = scheme.surface
        surfaceContainerLowest = scheme.surfaceContainerLowest
        surfaceContainerLow = scheme.surfaceContainerLow
        surfaceContainerHigh = scheme.surfaceContainerHigh
        surfaceContainerHighest = scheme.surfaceContainerHighest

        pageBg = scheme.surfaceContainer
        cardBg = scheme.surfaceBright
        popupBg = scheme.surfaceContainer

        // No tooltip colours here, and no `hint` either. Both used to be
        // hand-picked pairs from the View era (`#1C1B1F`/`#E6E0E5` and their
        // dark twins; `outlineVariant` as a hint grey) and both outlived their
        // readers: the tooltip is now material3's `PlainTooltip`
        // (ui/AppTopBar.kt, ui/AboutScreen.kt, ui/MainScreen.kt) whose colours
        // are the library's own tokens — `TooltipDefaults.plainTooltipColors`
        // is `inverseSurface` on `inverseOnSurface`, the official role read off
        // this very scheme, so a copy here would only be a second, drifting
        // answer. A field with no reader is not a setting.
        //
        // No press colour lives here either. Press feedback is the Miuix
        // indication, installed in `ComposeTheme.kt` as
        // `MiuixIndication(colors.onBackground)`, and it stays neutral rather
        // than taking the accent hue: an accent ripple reads as a color-style
        // surprise on Neutral / Vibrant / etc., while a neutral wash stays
        // quiet on every palette style.
    }
}
