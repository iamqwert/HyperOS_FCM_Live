package io.github.howard20181.hyperos.fcmlive.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.UiUtils
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

/**
 * Open-source license list. Deps show version next to the name; this project
 * and reference projects use name + license.
 *
 * What the View version needed ~250 lines of dialog plumbing for collapses
 * here: no scroll indicators to strip, no button bar to re-lay out, and the
 * body is selectable through [SelectionContainer] instead of a flag on a
 * TextView. The rows keep the connected-group shape, now from
 * [LocalAppShapes] rather than a `GradientDrawable` built per row.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var openSheet by remember { mutableStateOf<LicenseSheet?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { AppTopBar(titleRes = R.string.licenses_title, onBack = onBack) }
    ) { innerPadding ->
        LicensesBody(
            bottomPadding = innerPadding.calculateBottomPadding(),
            onOpenSheet = { openSheet = it },
            modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
        )
    }

    openSheet?.let { sheet ->
        LicenseDialog(
            sheet = sheet,
            onDismiss = { openSheet = null },
            onOpenSource = { url ->
                // The dialog is dismissed first, as it always was — a snackbar
                // raised while it is up would be drawn behind a dialog window
                // and never seen. Only if nothing can take the URL is there
                // something to say, and then the URL itself is the message.
                openSheet = null
                if (!UiUtils.openUrl(context, url)) {
                    scope.launch { snackbarHostState.showSnackbar(url) }
                }
            }
        )
    }
}

/** One license body waiting to be shown, with the repository that ships it. */
private data class LicenseSheet(val title: String, val rawRes: Int, val url: String?)

@Composable
private fun LicensesBody(
    bottomPadding: Dp,
    onOpenSheet: (LicenseSheet) -> Unit,
    modifier: Modifier = Modifier
) {
    val hint = stringResource(R.string.license_view_full_text)
    val plainLicenses = listOf(
        stringResource(R.string.license_apache_2) to R.raw.license_apache2,
        stringResource(R.string.license_gpl_3) to R.raw.license_gpl3,
        stringResource(R.string.license_mit) to R.raw.license_mit,
    )

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp),
        // The gesture-hint strip is scroll-through, not a hard stop.
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        item {
            SectionTitle(R.string.licenses_section_licenses, first = true)
            plainLicenses.forEachIndexed { index, (name, rawRes) ->
                val first = index == 0
                val last = index == plainLicenses.lastIndex
                GroupRow(first = first, last = last, onClick = {
                    onOpenSheet(LicenseSheet(name, rawRes, url = null))
                }) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = name,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Text(
                        text = hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!last) Spacer(modifier = Modifier.height(2.dp))
            }
        }

        item {
            SectionTitle(R.string.licenses_section_deps)
            DEPS.forEachIndexed { index, dep ->
                val name = dep[0]
                val version = dep[1]
                val license = dep[2]
                val url = dep[3]
                val first = index == 0
                val last = index == DEPS.lastIndex
                GroupRow(first = first, last = last, onClick = {
                    onOpenSheet(LicenseSheet(name, licenseRawFor(name, license), url))
                }) {
                    // A name that would crowd the version truncates instead of
                    // wrapping; the version sits on its own line, flush under
                    // the name, with the license following.
                    Text(
                        text = name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row {
                        if (version.isNotEmpty()) {
                            Text(
                                text = version,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = license,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (!last) Spacer(modifier = Modifier.height(2.dp))
            }
        }

        item {
            SectionTitle(R.string.licenses_section_refs)
            REFERENCES.forEachIndexed { index, ref ->
                val name = ref[0]
                val license = ref[1]
                val url = ref[2]
                val first = index == 0
                val last = index == REFERENCES.lastIndex
                // 「—」 marks a repository that ships no LICENSE file: there is
                // no text to show, so the tap keeps its feedback but opens
                // nothing.
                val noLicense = license == "—"
                GroupRow(first = first, last = last, onClick = if (noLicense) {
                    {}
                } else {
                    {
                        onOpenSheet(LicenseSheet(name, licenseRawFor(name, license), url))
                    }
                }) {
                    Text(
                        text = name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = license,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!last) Spacer(modifier = Modifier.height(2.dp))
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * Full license text. The buttons are laid out as an equal pair — outlined
 * 「查看源代码」 left, filled 「关闭」 right — which on the View side needed
 * `layoutDialogButtons` to rebuild the framework's button bar.
 */
@Composable
private fun LicenseDialog(
    sheet: LicenseSheet,
    onDismiss: () -> Unit,
    onOpenSource: (String) -> Unit
) {
    val context = LocalContext.current
    val body = remember(sheet.rawRes) { readLicenseText(context, sheet.rawRes) }
    val sourceLabel = stringResource(R.string.view_source_code)
    val closeLabel = stringResource(R.string.dialog_close)

    AlertDialog(
        onDismissRequest = onDismiss,
        // Raised container, as everywhere else — see LocalAppSurfaces. M3's
        // dialog role sits a tone below the page in this pairing.
        containerColor = LocalAppSurfaces.current.card,
        title = {
            Text(text = sheet.title, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            // Without a ceiling the body grows to the dialog's full height and
            // the dialog itself takes over the screen — these files run to
            // hundreds of lines. Capping at part of the screen keeps the title
            // and buttons reachable with the text scrolling inside.
            //
            // The cap is measured from the window the dialog is actually in,
            // not from `Configuration.screenHeightDp`: the configuration value
            // does not move when the window is resized, so in split screen the
            // dialog would cap itself against the height of the full screen it
            // is not occupying. Zero only means the host window has not been
            // measured yet — the ceiling is left off for that one frame rather
            // than clamping the text to nothing.
            val density = LocalDensity.current
            val bodyHeight = with(density) {
                val px = LocalWindowInfo.current.containerSize.height
                if (px <= 0) Dp.Infinity else (px * 0.55f).toDp()
            }
            SelectionContainer {
                Text(
                    modifier = Modifier
                        .heightIn(max = bodyHeight)
                        .verticalScroll(rememberScrollState()),
                    text = body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (sheet.url != null) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenSource(sheet.url) }
                    ) {
                        Text(sourceLabel)
                    }
                }
                Button(modifier = Modifier.weight(1f), onClick = onDismiss) {
                    Text(closeLabel)
                }
            }
        }
    )
}

/**
 * LICENSE files are hard-wrapped at ~70 columns on disk. Shown on a phone
 * those wraps read as random mid-sentence breaks, so keep the blank-line
 * paragraph structure and join the lines inside each paragraph. The BOM goes
 * too, or the first glyph is a zero-width space.
 */
private fun readLicenseText(context: Context, rawRes: Int): String {
    val raw = try {
        context.resources.openRawResource(rawRes).use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        }
    } catch (t: Throwable) {
        return ""
    }
    val text = raw.trimStart('\uFEFF').replace("\r\n", "\n").replace('\r', '\n')
    return text
        .split(Regex("\n[ \t]*\n"))
        .joinToString("\n\n") { block ->
            block.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }
        .trim() + "\n"
}

/**
 * Maps a row to the LICENSE body as published on that project's repository.
 * Shared SPDX texts live in one raw file each; the two MIT reference projects
 * ship their own copyright line, so they keep a dedicated copy. Repositories
 * carrying no LICENSE file are shown as 「—」 and never reach this function.
 */
private fun licenseRawFor(name: String, licenseLabel: String): Int = when {
    name == "Kr328/HyperOSFCMFix" -> R.raw.license_mit_hyperosfcmfix
    name == "ReedGAOOO/FCMGuard-HyperOS" -> R.raw.license_mit_fcmguard
    licenseLabel.contains("Apache") -> R.raw.license_apache2
    licenseLabel.contains("GPL") -> R.raw.license_gpl3
    licenseLabel.contains("MIT") -> R.raw.license_mit
    // JUnit is EPL-1.0, and the fallback below is Apache — without this branch the
    // row would have shown the wrong licence text under a correct-looking label.
    licenseLabel.contains("Eclipse") -> R.raw.license_epl1
    else -> R.raw.license_apache2
}

@Preview(name = "Licenses — light", showBackground = true)
@Preview(
    name = "Licenses — dark intent",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun LicensesScreenPreview() {
    HyperFCMLiveTheme {
        Surface {
            LicensesScreen(onBack = {})
        }
    }
}

private const val ANDROIDX_URL = "https://github.com/androidx/androidx"
private const val AOSP_URL = "https://android.googlesource.com/platform/frameworks/base"
private const val JSPECIFY_URL = "https://github.com/jspecify/jspecify"
private const val MCU_URL = "https://github.com/material-foundation/material-color-utilities"

/**
 * name, version ("" if none), license label, project URL.
 *
 * One row per third-party **project**, not per Gradle module: AndroidX is a
 * single project with a single license and a single repository, so it is shown
 * by the modules this app is built on rather than by all ~39 families that
 * material and appcompat drag in behind it. Versions are the *resolved* ones,
 * not the declared ones.
 *
 * Audited against
 * `./gradlew :HyperFCMLive:dependencies --configuration releaseRuntimeClasspath`
 * on 2026-10-04, and re-audited on 2026-10-05 by reading the class definitions
 * back out of the release dex (what actually ships, not what resolves). Two
 * things that resolution lists are not rows here: BOMs and
 * dependency-management patches (`compose-bom`, `kotlin-bom`, the kotlinx
 * ones), and `com.google.guava:listenablefuture` — an empty marker artifact
 * with no classes to attribute (confirmed: no `com.google.guava` class is
 * defined in the APK at all). `libxposed api`, the AOSP stubs and Material
 * Color Utilities are compile-time or vendored, so they are never on the
 * runtime classpath and are listed for attribution.
 *
 * The 2026-10-05 pass added the two rows that were missing:
 * - **AndroidX ConstraintLayout** is the one AndroidX family here that is *not*
 *   in the `androidx/androidx` monorepo, so the shared ANDROIDX_URL row cannot
 *   stand in for it. Its code does ship (3 classes survive R8, pulled in behind
 *   Material Components).
 * - **JUnit** is test-scope: it is in *no* APK, and it is here only as a licence
 *   statement, the same way the annotation-only rows are. It exists because
 *   `testImplementation` is back in build.gradle after Release 3.5.3 dropped it
 *   and orphaned src/test.
 */
private val DEPS = arrayOf(
    arrayOf("AndroidX Activity", "1.8.2", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Annotation", "1.9.1", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX AppCompat", "1.8.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Arch Core", "2.2.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Collection", "1.5.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Compose Foundation", "1.12.1", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Compose Material3", "1.5.0-alpha27", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Compose UI", "1.12.1", "Apache License 2.0", ANDROIDX_URL),
    // Separate repository, so ANDROIDX_URL does not cover it. Only on the
    // classpath behind Material Components, but its code does survive R8.
    arrayOf(
        "AndroidX ConstraintLayout",
        "2.2.1",
        "Apache License 2.0",
        "https://github.com/androidx/constraintlayout"
    ),
    arrayOf("AndroidX Core", "1.16.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Interpolator", "1.0.0", "Apache License 2.0", ANDROIDX_URL),
    // Build-only stubs vendored under hiddenapi/stubs; kept for attribution.
    arrayOf("AOSP Framework Annotations", "", "Apache License 2.0", AOSP_URL),
    // Pulled in by Material Components at runtime scope; annotations only, so
    // there is no code in the APK — the row is a licence statement, like
    // JSpecify below.
    arrayOf(
        "Error Prone Annotations",
        "2.15.0",
        "Apache License 2.0",
        "https://github.com/google/error-prone"
    ),
    arrayOf(
        "JetBrains Annotations",
        "23.0.0",
        "Apache License 2.0",
        "https://github.com/JetBrains/java-annotations"
    ),
    arrayOf("JSpecify", "1.0.0", "Apache License 2.0", JSPECIFY_URL),
    // Test scope only — nothing from it is in any APK. Listed for the same
    // reason as the annotation-only rows: it is a third-party project in the
    // build with its own licence. EPL-1.0 (see licenseRawFor).
    arrayOf("JUnit", "4.13.2", "Eclipse Public License 1.0", "https://github.com/junit-team/junit4"),
    arrayOf("Kotlin Stdlib", "2.2.10", "Apache License 2.0", "https://github.com/JetBrains/kotlin"),
    arrayOf(
        "Kotlinx Coroutines",
        "1.9.0",
        "Apache License 2.0",
        "https://github.com/Kotlin/kotlinx.coroutines"
    ),
    arrayOf(
        "Kotlinx Serialization",
        "1.7.3",
        "Apache License 2.0",
        "https://github.com/Kotlin/kotlinx.serialization"
    ),
    arrayOf("libxposed API", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/api"),
    arrayOf("libxposed Interface", "102.0.0", "Apache License 2.0", "https://github.com/libxposed"),
    arrayOf("libxposed Service", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/service"),
    arrayOf("Lifecycle Common", "2.9.4", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("Lifecycle Runtime", "2.9.4", "Apache License 2.0", ANDROIDX_URL),
    arrayOf(
        "Material Components",
        "1.14.0",
        "Apache License 2.0",
        "https://github.com/material-components/material-components-android"
    ),
    // Vendored source under mcu/ (no Gradle artifact) — listed for attribution.
    arrayOf("Material Color Utilities", "", "Apache License 2.0", MCU_URL),
    arrayOf("VersionedParcelable", "1.1.1", "Apache License 2.0", ANDROIDX_URL),
)

/** name, license label, project URL. */
private val REFERENCES = arrayOf(
    arrayOf("250king/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/250king/HyperOS_FCM_Live"),
    arrayOf("billtv/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/billtv/HyperOS_FCM_Live"),
    arrayOf("dingwen07/hyperos-fcm-fix", "GPL-3.0", "https://github.com/dingwen07/hyperos-fcm-fix"),
    arrayOf(
        "google/material-design-icons",
        "Apache License 2.0",
        "https://github.com/google/material-design-icons"
    ),
    arrayOf("HappyMax0/FCMPushViewer", "Apache License 2.0", "https://github.com/HappyMax0/FCMPushViewer"),
    arrayOf("Howard20181/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/Howard20181/HyperOS_FCM_Live"),
    arrayOf("Kr328/HyperOSFCMFix", "MIT License", "https://github.com/Kr328/HyperOSFCMFix"),
    arrayOf("Kwensiu/DPIS", "GPL-3.0", "https://github.com/Kwensiu/DPIS"),
    // No LICENSE file in the repository — shown as 「—」, row opens nothing.
    arrayOf("onijiang0/fcmfix", "—", "https://github.com/onijiang0/fcmfix"),
    arrayOf("ReedGAOOO/FCMGuard-HyperOS", "MIT License", "https://github.com/ReedGAOOO/FCMGuard-HyperOS"),
    arrayOf("wxxsfxyzm/InstallerX-Revived", "GPL-3.0", "https://github.com/wxxsfxyzm/InstallerX-Revived"),
    arrayOf("zuohl/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/zuohl/HyperOS_FCM_Live"),
)
