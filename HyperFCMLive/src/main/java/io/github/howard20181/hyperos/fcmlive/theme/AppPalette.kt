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
    dark: Boolean,
    amoled: Boolean
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
    val iconTint: Int
    @JvmField
    val popupBg: Int
    /** Neutral press ripple: onSurface at low alpha, not the accent hue. */
    @JvmField
    val ripple: Int

    @JvmField
    val dark: Boolean = dark

    init {
        // The three levels this app paints, as CAM16 tones (which are L*):
        //
        //   light : popup 94  =  page 94   <  card 98
        //   dark  : popup 12  =  page 12   <  card 24
        //   AMOLED:           page 0   <  card 6   <  popup 12
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
        //
        // AMOLED is the one deliberate exception: the same three levels crushed
        // onto a near-black ramp (0 / 6 / 12) so an OLED page costs nothing to
        // light. The hue stays out of that ramp on purpose — it exists to save
        // power, and a tint would put the seed back into the black — and its
        // steps are the ones the dark ramp itself takes, which keeps an AMOLED
        // appearance reading like an ordinary dark one. Written in tones,
        // because a hex step is not a perceptual step: #000000 next to #0A0A0A
        // is a L* step of 0.8 — invisible, and the card melts into the page —
        // while #000000 next to #141414 is 6.0, the same step the dark ramp
        // takes between its own page and card.
        val amoledRamp = amoled && dark

        // Raw roles, mapped verbatim into `ColorScheme` by ComposeTheme. AMOLED
        // repaints them onto its own ramp too, so anything that reads a
        // `surface*` token still stays inside the near-black volume.
        surface = if (amoledRamp) 0xFF000000.toInt() else scheme.surface
        surfaceContainerLowest =
            if (amoledRamp) 0xFF000000.toInt() else scheme.surfaceContainerLowest
        surfaceContainerLow =
            if (amoledRamp) 0xFF141414.toInt() else scheme.surfaceContainerLow
        surfaceContainerHigh =
            if (amoledRamp) 0xFF202020.toInt() else scheme.surfaceContainerHigh
        surfaceContainerHighest =
            if (amoledRamp) 0xFF262626.toInt() else scheme.surfaceContainerHighest

        pageBg = if (amoledRamp) 0xFF000000.toInt() else scheme.surfaceContainer
        cardBg = if (amoledRamp) 0xFF141414.toInt() else scheme.surfaceBright
        popupBg = if (amoledRamp) 0xFF202020.toInt() else scheme.surfaceContainer

        iconTint = onSurfaceVariant
        // No tooltip colours here, and no `hint` either. Both used to be
        // hand-picked pairs from the View era (`#1C1B1F`/`#E6E0E5` and their
        // dark twins; `outlineVariant` as a hint grey) and both outlived their
        // readers: the tooltip is now material3's `PlainTooltip`
        // (ui/AppTopBar.kt, ui/AboutScreen.kt, ui/MainScreen.kt) whose colours
        // are the library's own tokens — `TooltipDefaults.plainTooltipColors`
        // is `inverseSurface` on `inverseOnSurface`, the official role read off
        // this very scheme, so a copy here would only be a second, drifting
        // answer. A field with no reader is not a setting.
        // Grey state layer (onSurface @ ~16%): accent ripples read as a
        // color-style surprise on Neutral / Vibrant / etc. A neutral wash
        // stays quiet on every palette style.
        ripple = withAlpha(onSurface, 0x29)
    }

    private companion object {
        private fun withAlpha(argb: Int, alpha: Int): Int {
            return (alpha shl 24) or (argb and 0x00FFFFFF)
        }
    }
}
