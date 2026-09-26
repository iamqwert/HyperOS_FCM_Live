package io.github.howard20181.hyperos.fcmlive.theme

import io.github.howard20181.hyperos.fcmlive.mcu.Hct
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme
import kotlin.math.min

/**
 * The app's semantic colors for one appearance configuration. Every field is
 * resolved once, from a [Scheme], and then read by the UI.
 *
 * Public int/boolean roles stay `@JvmField` so existing Java call sites keep
 * field access (`palette.primary`) without switching to synthetic getters.
 */
class AppPalette internal constructor(s: Scheme, dark: Boolean, amoled: Boolean) {

    /** Raw Material roles, kept for callers that need the full set. */
    @JvmField
    val primary: Int = s.primary
    @JvmField
    val onPrimary: Int = s.onPrimary
    @JvmField
    val primaryContainer: Int = s.primaryContainer
    @JvmField
    val surface: Int
    @JvmField
    val surfaceVariant: Int = s.surfaceVariant
    @JvmField
    val surfaceContainerLowest: Int
    @JvmField
    val surfaceContainerLow: Int
    @JvmField
    val surfaceContainerHigh: Int
    @JvmField
    val onSurface: Int = s.onSurface
    @JvmField
    val onSurfaceVariant: Int = s.onSurfaceVariant
    @JvmField
    val outline: Int = s.outline
    @JvmField
    val outlineVariant: Int = s.outlineVariant
    @JvmField
    val inverseSurface: Int = s.inverseSurface
    @JvmField
    val inverseOnSurface: Int = s.inverseOnSurface

    /** Semantic aliases used by the layouts. */
    @JvmField
    val card: Int
    /** Page background behind the cards: a step deeper than [card]. */
    @JvmField
    val pageBg: Int
    @JvmField
    val iconTint: Int
    @JvmField
    val hint: Int
    @JvmField
    val popupBg: Int
    @JvmField
    val tooltipBg: Int
    @JvmField
    val tooltipText: Int
    /** Accent ripple: primary at 24% alpha, matching the old md_popup_item_ripple. */
    @JvmField
    val ripple: Int

    @JvmField
    val dark: Boolean = dark

    init {
        if (amoled && dark) {
            // AMOLED: keep the accent roles, but force the surfaces to a
            // near-black ramp so the background and cards stay readable on
            // an OLED panel without wasting power.
            surface = 0xFF000000.toInt()
            surfaceContainerLowest = 0xFF000000.toInt()
            surfaceContainerLow = 0xFF0A0A0A.toInt()
            surfaceContainerHigh = 0xFF141414.toInt()
        } else {
            surface = s.surface
            surfaceContainerLowest = s.surfaceContainerLowest
            surfaceContainerLow = s.surfaceContainerLow
            surfaceContainerHigh = s.surfaceContainerHigh
        }

        // Light cards use the lowest container tone (near white); dark cards
        // stay on the low container tone so they remain a step above the
        // background and keep the elevation hierarchy visible.
        card = if (dark) surfaceContainerLow else surfaceContainerLowest
        // The page sits one step deeper than the cards, so the two layers
        // read clearly against each other in both light and dark mode. The
        // chroma cap keeps the background a clean, quiet tint of the theme
        // hue instead of a muddy wash.
        pageBg = if (amoled && dark) {
            surface
        } else {
            cleanTone(if (dark) surfaceContainerLowest else surfaceContainerLow)
        }
        iconTint = onSurfaceVariant
        hint = outlineVariant
        // The overflow menu wears the page colour, exactly like the About
        // dropdown (which builds its panel from pageBg). surfaceContainerHigh is
        // never passed through cleanTone, so it carries far more of the seed
        // hue than the page does — next to the page it reads as a muddy tint
        // rather than as a menu. The popup keeps its own elevation shadow, so
        // it still lifts off the page without needing a different fill.
        popupBg = pageBg
        tooltipBg = withAlpha(inverseSurface, 0xE6)
        tooltipText = inverseOnSurface
        ripple = withAlpha(primary, 0x3D)
    }

    private companion object {
        private fun withAlpha(argb: Int, alpha: Int): Int {
            return (alpha shl 24) or (argb and 0x00FFFFFF)
        }

        /**
         * Cap the chroma of a surface tone at a whisper of the theme hue, keeping
         * the tone (lightness) untouched, so the background stays clean and
         * consistent across pages no matter how saturated the seed is.
         */
        private fun cleanTone(argb: Int): Int {
            val hct = Hct.fromInt(argb)
            val chroma = min(hct.chroma, 4.0)
            return Hct.from(hct.hue, chroma, hct.tone).toInt()
        }
    }
}
