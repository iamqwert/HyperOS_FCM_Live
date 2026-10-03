package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R

/**
 * The back bar every secondary page shares.
 *
 * 64dp is the M3 top-app-bar height itself; [TopAppBarDefaults.windowInsets]
 * puts the status bar back on top of it. The earlier 76dp sat one step above
 * the spec and read as dead air between the status bar, the title and the body
 * — the bar now hugs both, the same way [MainTopBar] does on the home page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    titleRes: Int,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        expandedHeight = 64.dp,
        windowInsets = TopAppBarDefaults.windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(
            // `background` = the mapped `pageBg`, so the bar is the same tone
            // as the page it sits on — `surface` is one step off and the seam
            // showed under the status bar.
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        navigationIcon = {
            val description = stringResource(R.string.back)
            TooltipBox(
                modifier = Modifier
                    // TopAppBar pads the nav-icon slot 4dp from the edge and
                    // the tonal button centers its 40dp plate in a 48dp touch
                    // target — another 4dp. 4 + 8 + 4 = 16dp: the plate's left
                    // edge lands exactly on the cards' left edge below. The
                    // end padding puts the title a standard 8dp off the plate
                    // instead of the 4dp the bare slot leaves.
                    .padding(start = 8.dp, end = 8.dp)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                // Below, like MainScreen's icons and like the PopupWindow this
                // replaced: it sat under the anchor, not up against the status
                // bar.
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Below
                ),
                tooltip = { PlainTooltip { Text(text = description) } },
                state = rememberTooltipState(),
                content = {
                    // Contained icon button: a circular tonal plate under the
                    // glyph, the way M3 Expressive toolbars dress their
                    // leading control. The container is the neutral container
                    // step, not the accent — the back arrow leads the page but
                    // is not its primary action.
                    FilledTonalIconButton(
                        onClick = onBack,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = description
                        )
                    }
                }
            )
        },
        title = {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleLarge
            )
        },
        actions = actions
    )
}
