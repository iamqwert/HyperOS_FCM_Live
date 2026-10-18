package io.github.howard20181.hyperos.fcmlive.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which look the app is dressed in.
 *
 * [Material] is the app's own look: Material 3 cards on the palette the app's
 * colour engine derives, each settings row led by an icon. [Miuix] dresses the
 * same screens in the system settings' language — the colours come from Miuix
 * (its own fixed scheme, or Monet), rows carry no leading icon, and their text
 * starts at the margin the icon used to stand behind. [SoftGlass] is [Miuix]
 * with the OS4 material: every surface the Miuix look already drew in its own
 * colour is milled from the 柔光玻璃 stack (`ui/softglass/`) — refracted
 * backdrop, edge light, floating chrome — while the layout, the motion and the
 * tokens underneath stay exactly the Miuix ones.
 *
 * The difference the layouts actually care about is one bit, [showRowIcons], and
 * it is read through [LocalUiStyle] rather than passed down per row: a settings
 * row is written once and reused on every page, so a style that had to be argued
 * at each call site would be honoured by the pages that remembered it and quietly
 * ignored by the next one added.
 *
 * That is also why [SoftGlass] is **not** a third branch at the call sites. It
 * answers yes to [isMiuixBased], so every layout question the Miuix look already
 * answers is answered the same way here, and only the material — a handful of
 * named places — asks [SoftGlass] specifically. A style that had to be spelled
 * out everywhere the Miuix one is would drift from it in a week.
 */
enum class UiStyle(val prefValue: Int, val showRowIcons: Boolean) {
    Material(0, true),
    Miuix(1, false),
    SoftGlass(2, false);

    /**
     * Whether this style is rendered by the Miuix screens. True for [Miuix] and
     * [SoftGlass]: the two differ in material only, never in layout, motion or
     * composition. Everything that used to ask `== UiStyle.Miuix` asks this
     * instead, so adding a material that rides the Miuix tree cannot leave half
     * the screens behind on the Material one (which is what the dispatches that
     * spelled `Miuix` out would have done).
     */
    val isMiuixBased: Boolean get() = this != Material

    companion object {
        /** Unknown values fall back to [Material], which is what shipped before. */
        fun of(value: Int): UiStyle = entries.firstOrNull { it.prefValue == value } ?: Material
    }
}

/**
 * The style in force. Defaults to [Material] so any composable drawn outside a
 * theme — a preview, a dialog composed against the application context — looks
 * the way it did before this existed.
 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.Material }
