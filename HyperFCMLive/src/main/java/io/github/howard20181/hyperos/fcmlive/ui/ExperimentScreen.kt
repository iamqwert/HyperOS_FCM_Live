package io.github.howard20181.hyperos.fcmlive.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.Prefs
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme

/**
 * Experiment switches: everything here is off by default and reaches into
 * behaviour the module otherwise leaves alone, which is why it sits one screen
 * deeper than the About entry.
 *
 * Each row is the switch; the row tap just flips it and the description states
 * what the feature does — the on/off mark lives on the switch thumb itself, no
 * separate state text. Every flag is read from the local mirror and published
 * through [Prefs], which broadcasts the change to the PowerKeeper hook — so a
 * flip takes effect on the next qualifying call without a reload or a reboot.
 *
 * A pair that only makes sense together is drawn as a master switch with its
 * sub-switch underneath, hidden until the master is on and animated in and out
 * on the flip. The hidden state and the hook agree, because the sub-switch's
 * hook reads the master flag as well.
 *
 * The layout is the settings pages' section pattern, deliberately: a
 * [SectionTitle] per section, and the rows of one section drawn as a connected
 * group — [GROUP_ROW_GAP] between rows, group corners on the ends only, a lone
 * row keeping the whole card radius. A revealed sub-option is the *second row
 * of its master's group*, not a card of its own, which is why the master's
 * [SettingsSwitchCard.last] follows the reveal: with the sub-row on screen the
 * two read as one block, and with it gone the master is a section of one row.
 */
@Composable
fun ExperimentScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(titleRes = R.string.experiment, onBack = onBack) }
    ) { innerPadding ->
        ExperimentBody(
            bottomPadding = innerPadding.calculateBottomPadding(),
            modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
        )
    }
}

@Composable
private fun ExperimentBody(
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Read once per screen: the hook side owns the live value, and the mirror
    // is only the answer the UI last left behind.
    var wechatDozeKeepout by remember { mutableStateOf(Prefs.readLocalWechatDozeKeepout(context)) }
    var wifiWeakSignalRelaxed by remember {
        mutableStateOf(Prefs.readLocalWifiWeakSignalSwitchRelaxed(context))
    }
    var wifiWeakSignalFloor by remember {
        mutableStateOf(Prefs.readLocalWifiWeakSignalFloor(context))
    }
    val floorLabels = Prefs.WIFI_WEAK_SIGNAL_FLOORS.map {
        stringResource(R.string.experiment_wifi_weak_signal_floor_item, it)
    }
    var sleepKeepalive by remember { mutableStateOf(Prefs.readLocalSleepKeepalive(context)) }
    var sleepKeepaliveData by remember {
        mutableStateOf(Prefs.readLocalSleepKeepaliveData(context))
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp),
        // The gesture-hint strip is scroll-through, not a hard stop.
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        item {
            SectionTitle(R.string.experiment_section_battery, first = true)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_block,
                title = stringResource(R.string.experiment_wechat_doze_keepout),
                description = stringResource(R.string.experiment_wechat_doze_keepout_desc),
                checked = wechatDozeKeepout,
                onCheckedChange = { checked ->
                    Prefs.writeWechatDozeKeepout(context, Prefs.remote(), checked)
                    wechatDozeKeepout = checked
                }
            )
        }
        item {
            SectionTitle(R.string.experiment_section_network)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_wifi_lock,
                title = stringResource(R.string.experiment_wifi_weak_signal_relaxed),
                description = stringResource(R.string.experiment_wifi_weak_signal_relaxed_desc),
                checked = wifiWeakSignalRelaxed,
                onCheckedChange = { checked ->
                    Prefs.writeWifiWeakSignalSwitchRelaxed(context, Prefs.remote(), checked)
                    wifiWeakSignalRelaxed = checked
                },
                // The floor row below is this row's group partner, so the master
                // gives up its bottom corners for exactly as long as that row is
                // on screen.
                last = !wifiWeakSignalRelaxed
            )
            // Master and sub-option, revealed the same way the sleep pair is.
            // This one narrows rather than widens — scores below the chosen
            // floor go back to the ROM — so there is nothing to show until the
            // master says the user wants the wider switch at all.
            AnimatedVisibility(
                visible = wifiWeakSignalRelaxed,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
                    SettingsMenuCard(
                        iconRes = R.drawable.ic_stars,
                        title = stringResource(R.string.experiment_wifi_weak_signal_floor),
                        description = stringResource(
                            R.string.experiment_wifi_weak_signal_floor_desc
                        ),
                        entries = floorLabels,
                        currentIndex = Prefs.WIFI_WEAK_SIGNAL_FLOORS
                            .indexOf(wifiWeakSignalFloor)
                            .coerceAtLeast(0),
                        onPick = { index ->
                            val picked = Prefs.WIFI_WEAK_SIGNAL_FLOORS[index]
                            Prefs.writeWifiWeakSignalFloor(context, Prefs.remote(), picked)
                            wifiWeakSignalFloor = picked
                        },
                        // The entries are bare numbers, so the panel takes the
                        // numeric floor instead of the two-character one; see
                        // [MENU_NUMERIC_MIN_WIDTH].
                        menuMinWidth = MENU_NUMERIC_MIN_WIDTH,
                        first = false,
                        last = true
                    )
                }
            }
        }
        item {
            SectionTitle(R.string.experiment_section_sleep)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_wifi,
                title = stringResource(R.string.experiment_sleep_keepalive),
                description = stringResource(R.string.experiment_sleep_keepalive_desc),
                checked = sleepKeepalive,
                onCheckedChange = { checked ->
                    Prefs.writeSleepKeepalive(context, Prefs.remote(), checked)
                    sleepKeepalive = checked
                },
                last = !sleepKeepalive
            )
            // The sub-switch only means anything while the master switch is on,
            // so the master switch reveals it rather than leaving a control
            // that does nothing sitting on screen.
            AnimatedVisibility(
                visible = sleepKeepalive,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
                    SettingsSwitchCard(
                        iconRes = R.drawable.ic_android_cell_4_bar,
                        title = stringResource(R.string.experiment_sleep_keepalive_data),
                        description = stringResource(
                            R.string.experiment_sleep_keepalive_data_desc
                        ),
                        checked = sleepKeepaliveData,
                        onCheckedChange = { checked ->
                            Prefs.writeSleepKeepaliveData(context, Prefs.remote(), checked)
                            sleepKeepaliveData = checked
                        },
                        first = false,
                        last = true
                    )
                }
            }
        }
        // The same 16dp tail the settings page ends on, so the last card does
        // not sit flush against the gesture strip on a fully scrolled page.
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Preview(name = "Experiment — light", showBackground = true)
@Preview(
    name = "Experiment — dark intent",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun ExperimentScreenPreview() {
    HyperFCMLiveTheme {
        Surface {
            ExperimentScreen(onBack = {})
        }
    }
}
