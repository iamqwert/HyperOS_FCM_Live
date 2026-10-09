package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.RuntimeStatus
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces

/**
 * "运行状态" header shown at the top of the wake-allowlist screen.
 *
 * It is a read-only summary of [RuntimeStatus.probe]: whether the framework has
 * actually injected this module into its two hosts and whether the GMS
 * no-restrict write is in place — i.e. the same facts that previously required
 * `adb logcat` / root to confirm — plus the one footgun the 3.7.2 allowlist model
 * created: an empty list means only the GMS channel is helped, and nothing said
 * so before.
 *
 * The card is deliberately inert: no click, no ripple. It reports, it does not
 * act — refreshing follows the screen's existing pull-to-refresh.
 */
@Composable
fun RuntimeStatusHeader(
    snapshot: RuntimeStatus.Snapshot?,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The Miuix card radius under that look — the card sits in the list among
    // the app rows, which carry the same radius there.
    val shape = if (LocalUiStyle.current == UiStyle.Miuix) {
        androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    } else {
        LocalAppShapes.current.card
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LocalAppSurfaces.current.card,
        shape = shape
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // The verdict sentence lives in the model (headlineRes) so the card and
            // the screen-reader announcement never diverge; the icon + tint are just
            // a visual mapping off that same resource.
            val headlineRes = snapshot?.headlineRes ?: R.string.status_detecting
            val (iconRes, iconTint) = when (headlineRes) {
                R.string.status_active ->
                    R.drawable.ic_status_enabled to MaterialTheme.colorScheme.primary
                R.string.status_pending_reload ->
                    R.drawable.ic_status_locked to MaterialTheme.colorScheme.tertiary
                R.string.status_detecting ->
                    R.drawable.ic_status_locked to MaterialTheme.colorScheme.onSurfaceVariant
                else ->
                    R.drawable.ic_status_disabled to MaterialTheme.colorScheme.error
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(headlineRes),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // Per-host lines only when the framework can report them; otherwise the
            // GMS-settings signal is the only concrete fact to show.
            if (snapshot != null && snapshot.targetsSupported) {
                HostLine(
                    label = stringResource(R.string.status_host_framework),
                    host = snapshot.systemHost
                )
                HostLine(
                    label = stringResource(R.string.status_host_powerkeeper),
                    host = snapshot.powerkeeperHost
                )
            }
            if (snapshot != null) {
                DetailLine(
                    text = stringResource(
                        if (snapshot.gmsUnrestricted) R.string.status_gms_on
                        else R.string.status_gms_off
                    ),
                    caution = !snapshot.gmsUnrestricted
                )
                if (snapshot.allowlistEmpty) {
                    DetailLine(
                        text = stringResource(R.string.status_allowlist_empty),
                        caution = true
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (LocalUiStyle.current == UiStyle.Miuix) {
                        MiuixTextButton(
                            text = stringResource(R.string.status_recheck),
                            onClick = onRetry
                        )
                    } else {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.status_recheck))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostLine(label: String, host: RuntimeStatus.Snapshot.Host?) {
    val stateText = stringResource(
        when (host?.state) {
            RuntimeStatus.Snapshot.HostState.UP_TO_DATE -> R.string.status_host_up_to_date
            RuntimeStatus.Snapshot.HostState.STALE -> R.string.status_host_stale
            RuntimeStatus.Snapshot.HostState.RELOADING -> R.string.status_host_reloading
            RuntimeStatus.Snapshot.HostState.FAILED -> R.string.status_host_failed
            else -> if (host?.present == true) R.string.status_host_injected
            else R.string.status_host_missing
        }
    )
    DetailLine(
        text = "$label · $stateText",
        caution = host == null || host.state != RuntimeStatus.Snapshot.HostState.UP_TO_DATE
    )
}

@Composable
private fun DetailLine(text: String, caution: Boolean) {
    Text(
        text = text,
        color = if (caution) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

