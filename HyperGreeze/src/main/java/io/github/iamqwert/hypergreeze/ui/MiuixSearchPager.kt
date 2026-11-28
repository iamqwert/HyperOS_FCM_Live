package io.github.iamqwert.hypergreeze.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Inert collapsed search capsule rendered as the app list's first item in the Miuix look.
 * Tapping it opens [top.yukonga.miuix.kmp.glass.GlassSearchOverlay] in [MainScreen].
 */
@Composable
fun SearchBarFake(
    label: String,
    searchBarTopPadding: () -> Dp = { 12.dp },
    capsuleColor: Color? = null,
    horizontalInset: Dp = MiuixMetrics.PageInset,
    bottomPadding: Dp = MiuixMetrics.ChromeBreath,
) {
    InputField(
        query = "",
        onQueryChange = {},
        label = label,
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = "Search",
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 16.dp, end = 8.dp),
                tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalInset)
            .padding(bottom = bottomPadding)
            .topInset(searchBarTopPadding),
        color = capsuleColor ?: MiuixTheme.colorScheme.surfaceContainerHigh,
        onSearch = {},
        enabled = false,
        expanded = false,
        onExpandedChange = {},
    )
}

/** Reserves [inset] above the content, read during layout so a changing value never recomposes the caller. */
private fun Modifier.topInset(inset: () -> Dp): Modifier = layout { measurable, constraints ->
    val insetPx = inset().roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = -insetPx))
    layout(placeable.width, placeable.height + insetPx) {
        placeable.placeRelative(0, insetPx)
    }
}
