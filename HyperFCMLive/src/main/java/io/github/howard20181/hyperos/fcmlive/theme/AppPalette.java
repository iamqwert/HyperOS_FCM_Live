package io.github.howard20181.hyperos.fcmlive.theme;

import io.github.howard20181.hyperos.fcmlive.mcu.Hct;
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme;

/**
 * The app's semantic colors for one appearance configuration. Every field is
 * resolved once, from a {@link Scheme}, and then read by the UI.
 */
public final class AppPalette {

    /** Raw Material roles, kept for callers that need the full set. */
    public final int primary;
    public final int onPrimary;
    public final int primaryContainer;
    public final int surface;
    public final int surfaceVariant;
    public final int surfaceContainerLowest;
    public final int surfaceContainerLow;
    public final int surfaceContainerHigh;
    public final int onSurface;
    public final int onSurfaceVariant;
    public final int outline;
    public final int outlineVariant;
    public final int inverseSurface;
    public final int inverseOnSurface;

    /** Semantic aliases used by the layouts. */
    public final int card;
    /** Page background behind the cards: a step deeper than {@link #card}. */
    public final int pageBg;
    public final int iconTint;
    public final int hint;
    public final int popupBg;
    public final int tooltipBg;
    public final int tooltipText;
    /** Accent ripple: primary at 24% alpha, matching the old md_popup_item_ripple. */
    public final int ripple;

    public final boolean dark;

    AppPalette(Scheme s, boolean dark, boolean amoled) {
        this.dark = dark;
        primary = s.primary;
        onPrimary = s.onPrimary;
        primaryContainer = s.primaryContainer;
        surfaceVariant = s.surfaceVariant;
        onSurface = s.onSurface;
        onSurfaceVariant = s.onSurfaceVariant;
        outline = s.outline;
        outlineVariant = s.outlineVariant;
        inverseSurface = s.inverseSurface;
        inverseOnSurface = s.inverseOnSurface;

        if (amoled && dark) {
            // AMOLED: keep the accent roles, but force the surfaces to a
            // near-black ramp so the background and cards stay readable on
            // an OLED panel without wasting power.
            surface = 0xFF000000;
            surfaceContainerLowest = 0xFF000000;
            surfaceContainerLow = 0xFF0A0A0A;
            surfaceContainerHigh = 0xFF141414;
        } else {
            surface = s.surface;
            surfaceContainerLowest = s.surfaceContainerLowest;
            surfaceContainerLow = s.surfaceContainerLow;
            surfaceContainerHigh = s.surfaceContainerHigh;
        }

        // Light cards use the lowest container tone (near white); dark cards
        // stay on the low container tone so they remain a step above the
        // background and keep the elevation hierarchy visible.
        card = dark ? surfaceContainerLow : surfaceContainerLowest;
        // The page sits one step deeper than the cards, so the two layers
        // read clearly against each other in both light and dark mode. The
        // chroma cap keeps the background a clean, quiet tint of the theme
        // hue instead of a muddy wash.
        pageBg = (amoled && dark) ? surface
                : cleanTone(dark ? surfaceContainerLowest : surfaceContainerLow);
        iconTint = onSurfaceVariant;
        hint = outlineVariant;
        // The overflow menu wears the page colour, exactly like the About
        // dropdown (which builds its panel from pageBg). surfaceContainerHigh is
        // never passed through cleanTone, so it carries far more of the seed
        // hue than the page does — next to the page it reads as a muddy tint
        // rather than as a menu. The popup keeps its own elevation shadow, so
        // it still lifts off the page without needing a different fill.
        popupBg = pageBg;
        tooltipBg = withAlpha(inverseSurface, 0xE6);
        tooltipText = inverseOnSurface;
        ripple = withAlpha(primary, 0x3D);
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Cap the chroma of a surface tone at a whisper of the theme hue, keeping
     * the tone (lightness) untouched, so the background stays clean and
     * consistent across pages no matter how saturated the seed is.
     */
    private static int cleanTone(int argb) {
        Hct hct = Hct.fromInt(argb);
        double chroma = Math.min(hct.getChroma(), 4.0);
        return Hct.from(hct.getHue(), chroma, hct.getTone()).toInt();
    }
}
