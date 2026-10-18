package io.github.howard20181.hyperos.fcmlive.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * The「模块」tab of the M3 bottom navigation: what the module is and whether it
 * is working, in one glance.
 *
 * Two blocks: a hero card that answers "is it on?" — the libxposed service
 * binding is the activation signal, since LSPosed only hands it to an enabled
 * module — carrying the module version and the service's API badge on the
 * right, and a device section naming the machine the module runs on.
 *
 * M3 only: this screen is hosted by [HomeShell], which exists under the
 * Material look alone. The Miuix home keeps the single-page layout with
 * AboutActivity carrying the settings, so no Miuix branch is written here.
 */
@Composable
fun ModuleStatusScreen(
    /** True once the libxposed service has bound — LSPosed active + module enabled. */
    active: Boolean,
    /**
     * The libxposed service's API level, zero while no service is bound. The
     * hero wears it as a pill on the right only once it is real: a badge with
     * nothing behind it would be decoration, not information.
     */
    apiVersion: Int,
    /** "LSPosed x.y.z (build)" from the bound service, empty before it binds. */
    managerVersion: String,
    versionName: String,
    versionCode: Long,
    /** Opens the FCM diagnostics page — the reference app keeps its entry on the home too. */
    onDiagnostics: () -> Unit,
    /** Opens the user guide, in the browser. */
    onHelp: () -> Unit,
    /** Opens the about page. */
    onAbout: () -> Unit,
    /** Re-runs the activation check — the tap target when the module reads inactive. */
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    PageScaffold(
        titleRes = R.string.nav_modules,
        onBack = {},
        showBack = false,
        modifier = modifier
    ) { innerPadding ->
        val inset = pageHorizontalInset()
        // Card spacing: the reference runs its list on spacedBy(12.dp) end to
        // end, so the Miuix-based looks breathe that air between every block;
        // the M3 cut keeps its own 16dp rhythm.
        val pageGap = if (LocalUiStyle.current.isMiuixBased) 12.dp else 16.dp
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .listScrollEndHaptic()
                .padding(start = inset, end = inset),
            // A tab switch re-skins nothing, but the saveable registry still
            // keeps the scroll position across window re-creations — the same
            // rule every page in this app runs on (see [rememberPageListState]).
            state = rememberPageListState(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding()
            )
        ) {
            item {
                // The reference home breathes a 12dp gap between the bar and
                // the first card (its list's first item pads top 12dp) — the
                // Miuix look rides that air; the M3 cut keeps its own rhythm.
                Column(
                    modifier = Modifier.padding(
                        top = if (LocalUiStyle.current.isMiuixBased) 12.dp else 0.dp
                    )
                ) {
                    StatusHero(
                        active = active,
                        apiVersion = apiVersion,
                        versionName = versionName,
                        versionCode = versionCode,
                        onRecheck = onRecheck
                    )
                }
            }
            item {
                // No section title here — the home reads as one page, not a
                // settings form; a full gap separates hero from rows.
                Spacer(modifier = Modifier.height(pageGap))
                SettingsGroup {
                    InfoRow(
                        iconRes = R.drawable.ic_smartphone,
                        titleRes = R.string.status_device_name,
                        value = deviceName(),
                        first = true,
                        last = false
                    )
                    InfoRow(
                        iconRes = R.drawable.ic_memory,
                        titleRes = R.string.status_os_version,
                        value = osVersion(),
                        first = false,
                        last = false
                    )
                    InfoRow(
                        iconRes = R.drawable.ic_android,
                        titleRes = R.string.status_android_version,
                        value = androidVersion(),
                        first = false,
                        last = false
                    )
                    InfoRow(
                        iconRes = R.drawable.ic_extension,
                        titleRes = R.string.status_manager_version,
                        value = if (managerVersion.isEmpty()) {
                            stringResource(R.string.status_manager_missing)
                        } else {
                            managerVersion
                        },
                        first = false,
                        last = true
                    )
                }
            }
            // The three doors the home keeps at the foot of the page:
            // diagnostics, the user guide, and about — one group, three rows,
            // diagnostics heading them.
            item {
                Spacer(modifier = Modifier.height(pageGap))
                SettingsGroup {
                    NavCard(
                        iconRes = R.drawable.ic_fcm_diagnostics,
                        title = stringResource(R.string.shortcut_fcm_diagnostics_long),
                        subtitle = "",
                        onClick = onDiagnostics,
                        first = true,
                        last = false
                    )
                    NavCard(
                        iconRes = R.drawable.ic_help,
                        title = stringResource(R.string.help_guide),
                        subtitle = "",
                        onClick = onHelp,
                        first = false,
                        last = false
                    )
                    NavCard(
                        iconRes = R.drawable.ic_info,
                        title = stringResource(R.string.nav_about),
                        subtitle = "",
                        onClick = onAbout,
                        first = false,
                        last = true
                    )
                }
            }
        }
    }
}

/**
 * The activation card. The libxposed service binding is the whole verdict —
 * LSPosed binds it only for an enabled module in scope. One word answers it
 * (已激活 / 未激活), the version line sits underneath, and the service's API
 * level rides on the right as a pill.
 */
@Composable
private fun StatusHero(
    active: Boolean,
    apiVersion: Int,
    versionName: String,
    versionCode: Long,
    onRecheck: () -> Unit
) {
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixStatusHero(
            active = active,
            apiVersion = apiVersion,
            versionName = versionName,
            versionCode = versionCode,
            onRecheck = onRecheck
        )
        return
    }
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        animationSpec = motion.defaultSpatialSpec(),
        label = "statusHeroContainer"
    )
    val ink = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container,
        shape = LocalAppShapes.current.card,
        modifier = Modifier
            .fillMaxWidth()
            // Inactive is a question the user wants answered again, not a
            // verdict: tapping re-runs the bind handshake (LSPosed's own
            // answer can lag a just-enabled module). Active needs no retry,
            // so the card stays inert.
            .then(
                if (!active) {
                    Modifier.clickable(onClick = onRecheck)
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 88.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(
                    if (active) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
                ),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(
                        if (active) R.string.status_activated else R.string.status_not_activated
                    ),
                    color = ink,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        R.string.status_version_line, versionName, versionCode
                    ),
                    color = ink.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (apiVersion > 0) {
                // The service's API level: a quiet pill in the card's own ink,
                // not a colored chip — it is metadata, not an action.
                Surface(
                    color = ink.copy(alpha = 0.14f),
                    shape = CircleShape
                ) {
                    Text(
                        text = "API $apiVersion",
                        color = ink,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * The activation card under the Miuix look: the library's own [Card] wearing
 * the LSPosed manager's status-card recipe — a fixed green container when the
 * module works, the giant outlined circled tick bleeding off the card's
 * bottom-end corner as a watermark, the verdict in large ink at the top-start
 * and the version and API lines stacked beneath it as plain text.
 *
 * Two fidelity points the first cut got wrong:
 *  - every glyph and line is drawn or typeset by THIS look — the watermark is
 *    the reference's own circled-tick mark (androidx Rounded.CheckCircleOutline,
 *    painted through tint; the library's icon set has a bare tick and no
 *    circled one), and every text is the library's [MiuixText], which carries
 *    this look's font — a material3 Text here rendered the Material theme's
 *    family and the wrong weights.
 *  - Inactive keeps the same geometry but the card's default surface and a
 *    muted watermark, and the card answers a tap with the re-check — the same
 *    retry contract the M3 hero runs on the M3 branch above.
 */
@Composable
private fun MiuixStatusHero(
    active: Boolean,
    apiVersion: Int,
    versionName: String,
    versionCode: Long,
    onRecheck: () -> Unit
) {
    // Which side of the fixed green pair to wear: the app's own palette owns
    // darkness (it can force dark against the system), so the page background's
    // luminance is the honest read — not isSystemInDarkTheme().
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (active) {
                CardDefaults.defaultColors(
                    color = if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
                )
            } else {
                CardDefaults.defaultColors()
            },
            onClick = { if (!active) onRecheck() },
            showIndication = !active,
            pressFeedbackType = PressFeedbackType.Tilt
        ) {
            Box {
                // The watermark. Offset and size are the reference's own — the
                // glyph slides past the card's end corner and clips there.
                val watermarkTint = if (active) {
                    Color(0xFF36D167)
                } else {
                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    // The circled tick as a drawable — the reference's own
                    // mark (androidx Rounded.CheckCircleOutline) — tinted, not
                    // hand-stroked: the fill geometry is Google's, so the
                    // mark reads exactly as the reference's does.
                    Icon(
                        painter = painterResource(R.drawable.ic_check_circle_outline),
                        contentDescription = null,
                        tint = watermarkTint,
                        modifier = Modifier.size(110.dp)
                    )
                }
                // The text block, the reference's own split: verdict and
                // version anchored to the card's top-start inside one
                // padding(16, 14) layer, the API level dropped to a layer of
                // its own pinned bottom-start with padding(16, 10) — that is
                // how the reference seats its working-mode line, low in the
                // card at its own size, not stacked on the version's elbow.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    Column {
                        MiuixText(
                            text = stringResource(
                                if (active) R.string.status_activated else R.string.status_not_activated
                            ),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        // The reference's version line: plain 15sp, no weight
                        // of its own — a fact under the verdict, not a second
                        // headline. The prefixed form comes from the string.
                        MiuixText(
                            text = stringResource(
                                R.string.status_card_version, versionName, versionCode
                            ),
                            fontSize = 15.sp
                        )
                    }
                }
                if (apiVersion > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp, 10.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        // The reference's working-mode recipe verbatim: 16sp
                        // Medium, riding the card's lower-left corner.
                        MiuixText(
                            text = "API $apiVersion",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * One informative device row. Clickable with the standard ripple — the user
 * asked for rows that answer a press — but the click itself does nothing:
 * the information is the whole feature.
 */
@Composable
private fun InfoRow(
    iconRes: Int,
    titleRes: Int,
    value: String,
    first: Boolean,
    last: Boolean
) {
    GroupRow(first = first, last = last, onClick = {}, haptic = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(painter = painterResource(iconRes))
            Column(modifier = Modifier.weight(1f)) {
                if (LocalUiStyle.current.isMiuixBased) {
                    // The look's own [MiuixText]: a material3 Text renders the
                    // Material family even handed a Miuix style, so the rows
                    // wore a different typeface and weight from the rest of
                    // this look. Recipe is the reference's InfoText: the title
                    // at headline1's size / Medium, the value at body2's.
                    MiuixText(
                        text = stringResource(titleRes),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    MiuixText(
                        text = value,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } else {
                    Text(
                        text = stringResource(titleRes),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = pageBodyStyle()
                    )
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = value,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = pageSecondaryStyle()
                    )
                }
            }
        }
    }
}

/**
 * The device's market name — "REDMI K90 Pro Max", not the codename-model
 * `Build.MODEL` carries on many devices. The market name lives in a prop only
 * vendor builds set; a device without it falls back to `Build.MODEL` rather
 * than an empty row.
 */
private fun deviceName(): String =
    systemProperty("ro.product.marketname")
        .ifEmpty { systemProperty("ro.vendor.product.marketname") }
        .ifEmpty { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }

/**
 * The row states the build, not the generation: `4.0.2.0.XPMCNXM`, the same
 * string Settings shows under 系统版本.
 *
 * `ro.mi.os.version.name` is only the generation — a real device answers
 * `OS4.0` — so the full version lives in the incremental props, which carry the
 * `OS` prefix that the row drops (`ro.mi.os.version.incremental` and
 * `ro.build.version.incremental` agree on HyperOS; the MIUI-era name is the
 * last stand for a device that has neither). The `OS` strip happens once, on
 * whichever property answered, so every fallback lands in the same shape.
 */
private fun osVersion(): String =
    systemProperty("ro.mi.os.version.incremental")
        .ifEmpty { systemProperty("ro.build.version.incremental") }
        .ifEmpty { systemProperty("ro.mi.os.version.name") }
        .ifEmpty { systemProperty("ro.miui.ui.version.name") }
        .removePrefix("OS")
        .ifEmpty { "Android ${Build.VERSION.RELEASE}" }

private fun androidVersion(): String =
    "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

/** SystemProperties.get via reflection: hidden from the SDK, open to every process. */
private fun systemProperty(key: String): String = try {
    val get = Class.forName("android.os.SystemProperties")
        .getDeclaredMethod("get", String::class.java)
    (get.invoke(null, key) as? String)?.trim().orEmpty()
} catch (ignored: Throwable) {
    ""
}
