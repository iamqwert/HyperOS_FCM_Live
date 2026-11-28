package io.github.iamqwert.hypergreeze.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which look the app is dressed in.
 *
 * [Material] is the app's own look: Material 3 cards on the palette the app's
 * colour engine derives, each settings row led by an icon. [Miuix] dresses the
 * same screens in HyperOS 4's Miuix language — Miuix's own colours (fixed or
 * Monet), no leading row icons, and the 柔光玻璃 stack (`ui/softglass/`) for
 * floating chrome, refracted backdrops and menus.
 *
 * The difference the layouts actually care about is one bit, [showRowIcons], and
 * it is read through [LocalUiStyle] rather than passed down per row: a settings
 * row is written once and reused on every page, so a style that had to be argued
 * at each call site would be honoured by the pages that remembered it and quietly
 * ignored by the next one added.
 */
enum class UiStyle(val prefValue: Int, val showRowIcons: Boolean) {
    Material(0, true),
    Miuix(1, false);

    /**
     * Whether this style is rendered by the Miuix screens.
     */
    val isMiuixBased: Boolean get() = this != Material

    companion object {
        /**
         * Unknown values fall back to [Material]. Legacy value `2`
         * (`UI_STYLE_SOFT_GLASS`) maps to [Miuix].
         */
        fun of(value: Int): UiStyle = when (value) {
            1, 2 -> Miuix
            else -> Material
        }
    }
}

/**
 * The style in force. Defaults to [Material] so any composable drawn outside a
 * theme — a preview, a dialog composed against the application context — looks
 * the way it did before this existed.
 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.Material }
