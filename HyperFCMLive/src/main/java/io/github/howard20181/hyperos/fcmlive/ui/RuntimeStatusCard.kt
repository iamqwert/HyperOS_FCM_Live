package io.github.howard20181.hyperos.fcmlive.ui

import android.os.SystemClock
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val shape = LocalAppShapes.current.card
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LocalAppSurfaces.current.card,
        shape = shape
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val headline: String
            val iconRes: Int
            val iconTint: Color
            when {
                snapshot == null -> {
                    headline = stringResource(R.string.status_detecting)
                    iconRes = R.drawable.ic_status_locked
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                }
                !snapshot.serviceConnected -> {
                    headline = stringResource(R.string.status_not_connected)
                    iconRes = R.drawable.ic_status_disabled
                    iconTint = MaterialTheme.colorScheme.error
                }
                !snapshot.targetsSupported -> {
                    headline = stringResource(
                        if (snapshot.gmsUnrestricted) R.string.status_active
                        else R.string.status_api_unsupported
                    )
                    iconRes = if (snapshot.gmsUnrestricted) R.drawable.ic_status_enabled
                    else R.drawable.ic_status_disabled
                    iconTint = if (snapshot.gmsUnrestricted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                }
                snapshot.frameworkActive -> {
                    headline = stringResource(R.string.status_active)
                    iconRes = R.drawable.ic_status_enabled
                    iconTint = MaterialTheme.colorScheme.primary
                }
                snapshot.systemPendingReload -> {
                    headline = stringResource(R.string.status_pending_reload)
                    iconRes = R.drawable.ic_status_locked
                    iconTint = MaterialTheme.colorScheme.tertiary
                }
                else -> {
                    headline = stringResource(R.string.status_not_injected)
                    iconRes = R.drawable.ic_status_disabled
                    iconTint = MaterialTheme.colorScheme.error
                }
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
                    text = headline,
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
                if (snapshot.hooksInstalled >= 0) {
                    DetailLine(
                        text = stringResource(
                            R.string.status_hooks_count,
                            snapshot.hooksInstalled,
                            snapshot.hooksAbsent.coerceAtLeast(0)
                        ),
                        caution = false
                    )
                }
                if (snapshot.probeElapsedRealtime > 0L) {
                    DetailLine(
                        text = stringResource(
                            R.string.status_session_counters,
                            probeAgeLabel(snapshot.probeElapsedRealtime),
                            snapshot.pushAllowed,
                            snapshot.autostartReleased
                        ),
                        caution = false
                    )
                }
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
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.status_recheck))
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

/** "刚刚 / N 秒前 / N 分钟前", measured against the shared boot clock. */
@Composable
private fun probeAgeLabel(probeElapsed: Long): String {
    val sec = ((SystemClock.elapsedRealtime() - probeElapsed) / 1000L).coerceAtLeast(0L)
    return when {
        sec < 5 -> stringResource(R.string.status_probe_now)
        sec < 120 -> stringResource(R.string.status_probe_seconds, sec)
        else -> stringResource(R.string.status_probe_minutes, sec / 60)
    }
}

