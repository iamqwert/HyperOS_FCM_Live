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
    var sleepKeepalive by remember { mutableStateOf(Prefs.readLocalSleepKeepalive(context)) }
    var sleepKeepaliveData by remember {
        mutableStateOf(Prefs.readLocalSleepKeepaliveData(context))
    }
    var sleepKeepaliveCharging by remember {
        mutableStateOf(Prefs.readLocalSleepKeepaliveCharging(context))
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
            SectionTitle(R.string.experiment_section_sleep)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_wifi,
                title = stringResource(R.string.experiment_sleep_keepalive),
                description = stringResource(R.string.experiment_sleep_keepalive_desc),
                checked = sleepKeepalive,
                onCheckedChange = { checked ->
                    Prefs.writeSleepKeepalive(context, Prefs.remote(), checked)
                    sleepKeepalive = checked
                }
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
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchCard(
                        iconRes = R.drawable.ic_signal_cellular_alt,
                        title = stringResource(R.string.experiment_sleep_keepalive_data),
                        description = stringResource(
                            R.string.experiment_sleep_keepalive_data_desc
                        ),
                        checked = sleepKeepaliveData,
                        onCheckedChange = { checked ->
                            Prefs.writeSleepKeepaliveData(context, Prefs.remote(), checked)
                            sleepKeepaliveData = checked
                        }
                    )
                    // Third member of the same set: this one narrows both
                    // radios, so it sits beside the data sub-switch rather than
                    // under it.
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsSwitchCard(
                        iconRes = R.drawable.ic_battery_charging_full,
                        title = stringResource(R.string.experiment_sleep_keepalive_charging),
                        description = stringResource(
                            R.string.experiment_sleep_keepalive_charging_desc
                        ),
                        checked = sleepKeepaliveCharging,
                        onCheckedChange = { checked ->
                            Prefs.writeSleepKeepaliveCharging(context, Prefs.remote(), checked)
                            sleepKeepaliveCharging = checked
                        }
                    )
                }
            }
        }
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
