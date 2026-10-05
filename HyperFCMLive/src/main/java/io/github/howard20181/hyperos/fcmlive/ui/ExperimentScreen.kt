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
import androidx.compose.runtime.mutableIntStateOf
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
 * A pair is not the only shape a section can take, and the sleep section is the
 * counter-example worth reading before adding one: its two switches answer the
 * same ROM decision (`PhoneSleepModeController#applySleepConfig` cutting the
 * radios) but one half of it each — WiFi and mobile data. Neither gates the
 * other in the hook and neither is hidden, so drawing one behind the other
 * would put a hierarchy on screen that the code does not have. Same heading,
 * same connected group, two peers.
 *
 * The layout is the settings pages' section pattern, deliberately: a
 * [SectionTitle] per section, and the rows of one section drawn as a connected
 * group — [GROUP_ROW_GAP] between rows, group corners on the ends only, a lone
 * row keeping the whole card radius. A revealed sub-option is the *second row
 * of its master's group*, not a card of its own, which is why the master's
 * [SettingsSwitchCard.last] follows the reveal: with the sub-row on screen the
 * two read as one block, and with it gone the master is a section of one row.
 *
 * One heading means one feature. Two switches that answer different ROM gates
 * get two headings even when they sit on the same code path — a shared heading
 * plus two open corners draws them as one connected block, which is a claim
 * about them being one thing, and the reader has no way to tell which of the
 * two claims on a row is the one that writes a system setting.
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
        mutableIntStateOf(Prefs.readLocalWifiWeakSignalFloor(context))
    }
    val floorLabels = Prefs.WIFI_WEAK_SIGNAL_FLOORS.map {
        stringResource(R.string.experiment_wifi_weak_signal_floor_item, it)
    }
    var sleepKeepalive by remember { mutableStateOf(Prefs.readLocalSleepKeepalive(context)) }
    var sleepKeepaliveData by remember {
        mutableStateOf(Prefs.readLocalSleepKeepaliveData(context))
    }
    var wakeStoppedPackages by remember {
        mutableStateOf(Prefs.readLocalWakeStoppedPackages(context))
    }
    var wakeAutostartRelaxed by remember {
        mutableStateOf(Prefs.readLocalWakeAutostartRelaxed(context))
    }
    var wakeWriteAutostart by remember {
        mutableStateOf(Prefs.readLocalWakeWriteAutostart(context))
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
                iconRes = R.drawable.ic_battery_saver,
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
                        iconRes = R.drawable.ic_tune,
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
            // Two radios, two switches, no master. Whichever switch is on is the
            // half of the network that survives the night, and the other half is
            // left to the ROM either way — so both rows are always on screen and
            // neither hides behind the other. They still share one heading and
            // one connected group, because they are one feature: sleep mode's
            // network cutoff.
            SettingsSwitchCard(
                iconRes = R.drawable.ic_wifi,
                title = stringResource(R.string.experiment_sleep_keepalive),
                description = stringResource(R.string.experiment_sleep_keepalive_desc),
                checked = sleepKeepalive,
                onCheckedChange = { checked ->
                    Prefs.writeSleepKeepalive(context, Prefs.remote(), checked)
                    sleepKeepalive = checked
                },
                first = true,
                last = false
            )
            Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
            SettingsSwitchCard(
                iconRes = R.drawable.ic_android_cell_4_bar,
                title = stringResource(R.string.experiment_sleep_keepalive_data),
                description = stringResource(R.string.experiment_sleep_keepalive_data_desc),
                checked = sleepKeepaliveData,
                onCheckedChange = { checked ->
                    Prefs.writeSleepKeepaliveData(context, Prefs.remote(), checked)
                    sleepKeepaliveData = checked
                },
                first = false,
                last = true
            )
        }
        item {
            SectionTitle(R.string.experiment_section_wake)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_campaign,
                title = stringResource(R.string.experiment_wake_stopped_packages),
                description = stringResource(R.string.experiment_wake_stopped_packages_desc),
                checked = wakeStoppedPackages,
                onCheckedChange = { checked ->
                    Prefs.writeWakeStoppedPackages(context, Prefs.remote(), checked)
                    wakeStoppedPackages = checked
                },
            )
        }
        item {
            // A section of its own rather than a second row under "唤醒". The two
            // answer different ROM gates on the same broadcast path — the AOSP
            // stopped state above, the MIUI autostart AppOp here — and they
            // disagree on every property a reader would group them by: what
            // triggers the persistent write, which broadcasts are reached, and
            // whether a user-visible setting ends up changed. Under one heading,
            // with their corners left open so the rows connect, they read as one
            // feature escalating in strength.
            SectionTitle(R.string.experiment_section_autostart)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_policy,
                title = stringResource(R.string.experiment_wake_autostart_relaxed),
                description = stringResource(R.string.experiment_wake_autostart_relaxed_desc),
                checked = wakeAutostartRelaxed,
                onCheckedChange = { checked ->
                    Prefs.writeWakeAutostartRelaxed(context, Prefs.remote(), checked)
                    wakeAutostartRelaxed = checked
                },
                last = !wakeAutostartRelaxed
            )
            AnimatedVisibility(
                visible = wakeAutostartRelaxed,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(GROUP_ROW_GAP))
                    SettingsSwitchCard(
                        iconRes = R.drawable.ic_key,
                        title = stringResource(R.string.experiment_wake_autostart_write),
                        description = stringResource(R.string.experiment_wake_autostart_write_desc),
                        checked = wakeWriteAutostart,
                        onCheckedChange = { checked ->
                            Prefs.writeWakeWriteAutostart(context, Prefs.remote(), checked)
                            wakeWriteAutostart = checked
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
