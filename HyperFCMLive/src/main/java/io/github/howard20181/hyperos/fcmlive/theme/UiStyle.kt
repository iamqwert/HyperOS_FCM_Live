package io.github.howard20181.hyperos.fcmlive.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which of the two looks the app is wearing.
 *
 * [Material] is the app's own look: Material 3 cards on the palette the app's
 * colour engine derives, each settings row led by an icon. [Miuix] dresses the
 * same screens in the system settings' language — the colours come from Miuix
 * (its own fixed scheme, or Monet), rows carry no leading icon, and their text
 * starts at the margin the icon used to stand behind.
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
