package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R

/**
 * The back bar every secondary page shares.
 *
 * The M3 Expressive form of the Miuix branch's large-title bar: the page's
 * title opens at the library's expanded size and, as the list scrolls up
 * under it, `exitUntilCollapsed` settles it into the 64dp collapsed bar — the
 * exact height the fixed bar this replaced used to occupy, so the scrolled
 * state is pixel-identical to the old one and only the resting state grows.
 *
 * Both states read as the page's own `background` tone, and the PAINT is the
 * bar's own: the M3 look wears no glass (the Haze treatment is the Miuix
 * branch's own — see `PageScaffold`), so the containers are pinned opaque to
 * the page background and rows that scroll under the bar are occluded, the
 * way the pre-glass treatment worked.
 *
 * The leading control is a **filled icon button** — `surfaceContainerHighest`
 * at [TOP_BAR_PLATE_ALPHA], and that is both reference apps to the letter:
 * InstallerX's `ExpressiveBackButton` is this very line, and DPIS hand-rolls
 * the same role and the same alpha behind a transparent `IconButton`. It is
 * neither the accent (the arrow leads the page but is not its primary action)
 * nor the card role: a card is the surface the *content* sits on, and this
 * plate is chrome drawn over it, so borrowing the card's fill made the two
 * read as one material. The Miuix look's own back control stays a bare
 * `IconButton` (`PageScaffold`) — every floating-plate experiment there ended
 * in mud, so the styles now disagree on purpose: M3 keeps the filled plate as
 * a constant, a settled large-title bar reading as chrome from the first
 * frame, while the Miuix bar keeps the library's own naked arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    titleRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** False hides the back control: the bottom-nav tabs are top-level pages. */
    showBack: Boolean = true,
    /**
     * Overrides the string-resource title — the batch mode's live count
     * (「已选择 N 项」) is composed text, not a fixed resource.
     */
    title: String? = null,
    /**
     * Overrides the leading glyph (the app page's batch mode exits through a
     * ✕, not an arrow) with its own description in [navDescRes].
     */
    navIconRes: Int? = null,
    navDescRes: Int? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        modifier = modifier,
        windowInsets = TopAppBarDefaults.windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(
            // Opaque page background on both states — no glass paints this
            // bar (the M3 look dropped it; see PageScaffold's Material
            // branch). `background`, not the default `surface`: the mapped
            // page tone, and the bar must not read as a second material
            // against the page.
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        navigationIcon = {
            if (showBack) {
                val description = stringResource(navDescRes ?: R.string.back)
                TooltipBox(
                    modifier = Modifier
                        // TopAppBar pads the nav-icon slot 4dp from the edge and
                        // the button centers its 40dp plate in a 48dp touch
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
                        // A filled icon button: circular plate under the glyph, the
                        // way M3 Expressive toolbars dress their leading control.
                        // The alpha is the shared [TOP_BAR_PLATE_ALPHA], the same
                        // fill the Miuix bar's plates wear.
                        FilledIconButton(
                            onClick = onBack,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    .copy(alpha = TOP_BAR_PLATE_ALPHA),
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                painter = painterResource(navIconRes ?: R.drawable.ic_arrow_back),
                                contentDescription = description
                            )
                        }
                    }
                )
            }
        },
        title = {
            // No explicit style: the bar itself crossfades the title between
            // the expanded headline size and the collapsed titleLarge with the
            // collapse fraction — a pinned style here would freeze the small
            // size into the expanded state.
            //
            // The heading role: a screen reader's heading jump is how a user
            // reaches a page's start, and the page's own title is the heading
            // every section title (`SectionTitle` marks itself the same way)
            // nests under.
            Text(
                text = title ?: stringResource(titleRes),
                modifier = Modifier.semantics { heading() }
            )
        },
        actions = actions,
        scrollBehavior = scrollBehavior
    )
}
