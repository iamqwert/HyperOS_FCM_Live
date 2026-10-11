package io.github.howard20181.hyperos.fcmlive.theme

/**
 * The 强调色 candidates offered under the Miuix look while 莫奈颜色 is on.
 *
 * The list is the same set KernelSU's colour palette offers for its own Monet
 * derivation — the Material 500 tones plus its sakura — because that manager is
 * where the pick-a-key-colour row was borrowed from, and a reader moving
 * between the two apps should meet the same swatches in the same order. The
 * first menu entry ("跟随壁纸") is not a colour: it is the stored 0 that tells
 * [ThemePrefs] no pick has been made, and the menu builds it in front of this
 * array rather than the array carrying it.
 *
 * Consumed in two places that must stay in step: the appearance page builds
 * the menu from it, and [MiuixStyleTheme] feeds the picked value to Miuix's
 * Monet engine as its key colour.
 */
val MIUIX_ACCENT_OPTIONS: IntArray = intArrayOf(
    0xFFF44336.toInt(), // red
    0xFFE91E63.toInt(), // pink
    0xFF9C27B0.toInt(), // purple
    0xFF673AB7.toInt(), // deep purple
    0xFF3F51B5.toInt(), // indigo
    0xFF2196F3.toInt(), // blue
    0xFF00BCD4.toInt(), // cyan
    0xFF009688.toInt(), // teal
    0xFF4FAF50.toInt(), // green
    0xFFFFEB3B.toInt(), // yellow
    0xFFFFC107.toInt(), // amber
    0xFFFF9800.toInt(), // orange
    0xFF795548.toInt(), // brown
    0xFF607D8F.toInt(), // blue grey
    0xFFFF9CA8.toInt(), // sakura
)
