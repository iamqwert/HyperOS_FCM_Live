package io.github.howard20181.hyperos.fcmlive.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle

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
 * The autostart section is the counter-example worth reading before copying the
 * shape: its runtime rows answer different ROM checkpoints, and each hook reads
 * only its own flag — not the master's — so each rung can be measured alone.
 * They are still hidden under the master, because unlike the retired write
 * switch they answer gates in memory only: nothing they control outlives the
 * master being off, so hiding them hides nothing that is still in force.
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
    // Hoisted out of the body so the look's scroll indicator can be mounted
    // beside the list in the scaffold's own body scope — the same shape
    // [ModuleStatusScreen] uses. The list keeps its position across a rebuild
    // through this state, exactly as it did when the body owned it.
    val listState = rememberPageListState()
    PageScaffold(
        titleRes = R.string.experiment,
        onBack = onBack,
        modifier = modifier
    ) { innerPadding ->
        ExperimentBody(
            // The list spans the full body and takes the bar's height as
            // contentPadding, so rows scroll UNDER the title bar's glass
            // instead of stopping at its edge (see PageScaffold).
            topPadding = innerPadding.calculateTopPadding(),
            bottomPadding = innerPadding.calculateBottomPadding(),
            listState = listState
        )
        MiuixScrollBar(
            listState = listState,
            modifier = Modifier.align(Alignment.CenterEnd),
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding()
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExperimentBody(
    topPadding: Dp,
    bottomPadding: Dp,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // The theme's motion, the same source AboutScreen's seed grid opens with:
    // on the 2025 spec these are the springs the library switches flip with,
    // on 2021 the standard curves. The three reveal chains below used to run
    // the default tweens and were the only cards on the page that did not
    // move like the rest of the theme.
    val motion = MaterialTheme.motionScheme
    // Read once per screen: the hook side owns the live value, and the mirror
    // is only the answer the UI last left behind.
    var wifiWeakSignalRelaxed by remember {
        mutableStateOf(Prefs.readLocalWifiWeakSignalSwitchRelaxed(context))
    }
    var wifiWeakSignalFloor by remember {
        mutableIntStateOf(Prefs.readLocalWifiWeakSignalFloor(context))
    }
    val floorLabels = Prefs.WIFI_WEAK_SIGNAL_FLOORS.map {
        stringResource(R.string.experiment_wifi_weak_signal_floor_item, it)
    }
    var greezeNetRelease by remember {
        mutableStateOf(Prefs.readLocalGreezeNetRelease(context))
    }
    // The two autostart checkpoints, each its own switch (2026-11-01): service
    // start and the restart / preload verdict. No master above them — either
    // half is independent, which is why they were split.
    var autostartServiceRelease by remember {
        mutableStateOf(Prefs.readLocalAutostartServiceRelease(context))
    }
    var autostartProcessRelease by remember {
        mutableStateOf(Prefs.readLocalAutostartProcessRelease(context))
    }
    var smartPowerRelease by remember {
        mutableStateOf(Prefs.readLocalSmartPowerRelease(context))
    }
    // Smart Power 只在 OS3 上存在（OS4 已移除拦截决策，hook 侧连目标方法都
    // 找不到），所以整节按世代隐藏，而不是显示一个永远无效的开关。读一次即
    // 可：系统属性在进程生命周期内不变。
    val os3Generation = remember { isOs3Generation() }
    // The remaining experiment rows that answer a per-app gate do nothing on an
    // empty FCM wake allowlist, and an empty list means "no app" — the same as
    // everywhere else now that the allowlist is the sole control. Read once,
    // like the switch values above, and shown in both switch positions because
    // the row does the same nothing either way.
    val emptyAllowlist = remember { Prefs.readLocalAllowlist(context).isEmpty() }
    val emptyAllowlistHint = if (emptyAllowlist) {
        stringResource(R.string.experiment_autostart_empty_list)
    } else {
        null
    }

    val inset = pageHorizontalInset()
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            // Every section here is a couple of rows, so on a tall screen the
            // page does not overflow — and an edge effect that waits for a
            // boundary never answers a pull. See [pageOverscroll].
            .pageOverscroll()
            .listScrollEndHaptic()
            .padding(start = inset, end = inset),
        // The signal-floor menu, and every menu added hereafter, must leave the
        // reader where they were. See [rememberPageListState].
        state = listState,
        // The other half of [pageOverscroll]: never two springs on one drag.
        overscrollEffect = rememberPageOverscrollEffect(),
        // The gesture-hint strip is scroll-through, not a hard stop. The top
        // inset rides here (not on the modifier) so rows pass under the glass
        // bar while scrolling.
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding)
    ) {
        item {
            SectionTitle(R.string.experiment_section_network, first = true)
            // Not a [SettingsGroup]: the floor row lives under an
            // AnimatedVisibility, and spacedBy would hold the seam at full
            // width while the row below has already shrunk away. The seam sits
            // inside the animated content instead, so it collapses with the
            // row it belongs to (see [GroupRowGap]).
            Column {
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_network_manage,
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
                    enter = expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = motion.defaultSpatialSpec()
                    ) + fadeIn(animationSpec = motion.defaultEffectsSpec()),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = motion.defaultSpatialSpec()
                    ) + fadeOut(animationSpec = motion.defaultEffectsSpec())
                ) {
                    Column {
                        GroupRowGap()
                        // 档位选择在两种观感里不是一个控件。Miuix 系照系统设置的
                        // 写法：值落在带档位点的滑杆上（见 [MiuixSliderCard]），
                        // 一眼能看见当前值在量程里的位置；Material 保持菜单，
                        // M3 的列表行里没有带档位的滑杆这回事。
                        if (LocalUiStyle.current.isMiuixBased) {
                            MiuixSliderCard(
                                title = stringResource(R.string.experiment_wifi_weak_signal_floor),
                                summary = stringResource(
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
                                first = false,
                                last = true
                            )
                        } else {
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
            }
        }
        item {
            // A section of its own: every row here works on the MIUI autostart
            // verdict, which is a different ROM mechanism from the AOSP stopped
            // state the shipped GMS→c2dm hop opens in memory.
            //
            // One switch per ROM checkpoint (2026-11-01): the service-bind
            // verdict and the restart / preload verdict. They were one switch
            // before, which forced a single description to cover two different
            // ROM questions and gave the reader no way to turn either off.
            //
            // [SettingsGroup] keeps the section one card: the two switch rows
            // share it and the manager entry closes it. Lone cards on a zero
            // gap cut a lens of page colour at the seam their opposing corners
            // leave, which is what the group container exists to prevent.
            SectionTitle(R.string.experiment_section_autostart)
            SettingsGroup {
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_door_open,
                    title = stringResource(R.string.experiment_autostart_service_release),
                    description = stringResource(
                        R.string.experiment_autostart_service_release_desc
                    ),
                    checked = autostartServiceRelease,
                    onCheckedChange = { checked ->
                        Prefs.writeAutostartServiceRelease(context, Prefs.remote(), checked)
                        autostartServiceRelease = checked
                    },
                    // Inert until at least one app is checked; the hint text is
                    // generic and never says "autostart".
                    stateLine = emptyAllowlistHint,
                    stateLineOff = emptyAllowlistHint,
                    first = true,
                    last = false
                )
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_power_settings_new,
                    title = stringResource(R.string.experiment_autostart_process_release),
                    description = stringResource(
                        R.string.experiment_autostart_process_release_desc
                    ),
                    checked = autostartProcessRelease,
                    onCheckedChange = { checked ->
                        Prefs.writeAutostartProcessRelease(context, Prefs.remote(), checked)
                        autostartProcessRelease = checked
                    },
                    stateLine = emptyAllowlistHint,
                    stateLineOff = emptyAllowlistHint,
                    first = false,
                    last = false
                )
                NavCard(
                    iconRes = R.drawable.ic_autostart_manage,
                    title = stringResource(R.string.experiment_wake_autostart_write),
                    subtitle = stringResource(R.string.experiment_wake_autostart_write_desc),
                    onClick = {
                        try {
                            context.startActivity(
                                Intent().setClassName(
                                    "com.miui.securitycenter",
                                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                                )
                            )
                        } catch (ignored: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                R.string.experiment_autostart_entry_missing,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    // The entry closes the group the switch rows opened.
                    first = false,
                    last = true
                )
            }
        }
        item {
            // A section of its own: this row answers the freeze-time network
            // cut (greeze), not the autostart verdict the section above works
            // on — same freeze machinery, a different half of it. Nothing is
            // written and nothing hides behind anything: one row, always on
            // screen, so no first/last juggling.
            SectionTitle(R.string.experiment_section_background_net)
            SettingsSwitchCard(
                iconRes = R.drawable.ic_greeze_net_release,
                title = stringResource(R.string.experiment_greeze_net_release),
                description = stringResource(R.string.experiment_greeze_net_release_desc),
                checked = greezeNetRelease,
                onCheckedChange = { checked ->
                    Prefs.writeGreezeNetRelease(context, Prefs.remote(), checked)
                    greezeNetRelease = checked
                },
                // Same empty-allowlist hint the autostart rows carry: the text
                // is generic (it never mentions autostart), and this switch is
                // just as inert until at least one app is checked.
                stateLine = emptyAllowlistHint,
                stateLineOff = emptyAllowlistHint
            )
        }
        if (os3Generation) {
            item {
                // Smart Power 节：OS3 世代的系统侧实验。hook 装不装由目标方法
                // 在不在决定（OS4 上天然不装），这一节只负责把开关亮给 OS3 的
                // 用户；开关实时生效，无需重启。
                SectionTitle(R.string.experiment_section_smart_power)
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_science,
                    title = stringResource(R.string.experiment_smart_power_release),
                    description = stringResource(R.string.experiment_smart_power_release_desc),
                    checked = smartPowerRelease,
                    onCheckedChange = { checked ->
                        Prefs.writeSmartPowerRelease(context, Prefs.remote(), checked)
                        smartPowerRelease = checked
                    },
                    // 同样的空白名单提示：开关只作用于已勾选应用。
                    stateLine = emptyAllowlistHint,
                    stateLineOff = emptyAllowlistHint
                )
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
