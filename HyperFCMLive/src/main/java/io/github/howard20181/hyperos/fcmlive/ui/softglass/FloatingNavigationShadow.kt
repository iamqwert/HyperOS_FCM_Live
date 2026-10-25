package io.github.howard20181.hyperos.fcmlive.ui.softglass

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

internal fun Modifier.floatingNavigationShadow(
    shape: Shape,
    isDark: Boolean,
): Modifier {
    return softGlassShadow(
        shape = shape,
        isDark = isDark,
    )
}
