package io.github.howard20181.hyperos.fcmlive.ui

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
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
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
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
@OptIn(ExperimentalScrollBarApi::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var openSheet by remember { mutableStateOf<LicenseSheet?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Read here, in composition — LocalUiStyle.current is a @Composable read
    // and the dialog's callback below is a plain lambda.
    val miuixNow = LocalUiStyle.current.isMiuixBased

    // Opens a repository, with the page's fallback when nothing can take the
    // URL: a system toast under Miuix, a snackbar under Material. It backs both
    // a licence dialog's 查看源代码 and, directly, the rows that ship no licence
    // text yet still link to their repository (onijiang0/fcmfix).
    val openUrl: (String) -> Unit = { url ->
        // Dismiss any open dialog first: a toast/snackbar raised while it is up
        // would be drawn behind the dialog window and never seen.
        openSheet = null
        if (!UiUtils.openUrl(context, url)) {
            if (miuixNow) {
                Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
            } else {
                scope.launch { snackbarHostState.showSnackbar(url) }
            }
        }
    }

    PageScaffold(
        titleRes = R.string.licenses_title,
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState
    ) { innerPadding ->
        val listState = rememberPageListState()
        LicensesBody(
            // The list spans the full body and takes the bar's height as
            // contentPadding, so rows scroll UNDER the title bar's glass
            // instead of stopping at its edge (see PageScaffold).
            topPadding = innerPadding.calculateTopPadding(),
            bottomPadding = innerPadding.calculateBottomPadding(),
            listState = listState,
            onOpenSheet = { openSheet = it },
            onOpenUrl = openUrl
        )
        // The reference's scroll indicator beside the list — every list page
        // in the reference ships one (LicensePage hangs its scrollbar as a
        // sibling of the list through the same scaffold scope), and the state
        // is hoisted here for it the way AboutPage hoists its own.
        if (LocalUiStyle.current.isMiuixBased) {
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(listState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
                trackPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding()
                )
            )
        }
        // Inside the scaffold's subtree, deliberately: the Miuix licence sheet
        // is an OverlayDialog, and those render through the enclosing
        // MiuixScaffold's popup host — composed outside it (where this sat
        // while the sheet was a WindowDialog) the host is never found and the
        // tap raises nothing. The M3 dialog does not care where it sits, so
        // both branches live here.
        openSheet?.let { sheet ->
            LicenseDialog(
                sheet = sheet,
                onDismiss = { openSheet = null },
                onOpenSource = openUrl
            )
        }
    }
}

/** One license body waiting to be shown, with the repository that ships it. */
private data class LicenseSheet(val title: String, val rawRes: Int, val url: String?)

@Composable
private fun LicensesBody(
    topPadding: Dp,
    bottomPadding: Dp,
    listState: LazyListState,
    onOpenSheet: (LicenseSheet) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hint = stringResource(R.string.license_view_full_text)
    // The Miuix look marks every row whose tap leads somewhere — a licence sheet
    // or a repository — with the library's own chevron, the way its
    // ArrowPreference does. The plain licence texts open a sheet like any dep
    // row does, so they wear the mark too.
    val miuixRows = LocalUiStyle.current.isMiuixBased
    // No gap between a row's title and its summary on either look. M3's 72dp
    // two-line item is bodyLarge's 24dp over bodyMedium's 20dp against 28dp of
    // padding — the line heights close it — and Miuix spaces its own lines. The
    // 4dp this page used to add was propping up a 12sp summary and now only
    // pushes the row 4dp past the token.
    val plainLicenses = listOf(
        stringResource(R.string.license_apache_2) to R.raw.license_apache2,
        stringResource(R.string.license_gpl_3) to R.raw.license_gpl3,
        stringResource(R.string.license_mit) to R.raw.license_mit,
    )

    val inset = pageHorizontalInset()
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .listScrollEndHaptic()
            .padding(start = inset, end = inset),
        state = listState,
        // The gesture-hint strip is scroll-through, not a hard stop. The top
        // inset rides here (not on the modifier) so rows pass under the glass
        // bar while scrolling.
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding)
    ) {
        item {
            SectionTitle(R.string.licenses_section_licenses, first = true)
            SettingsGroup {
                plainLicenses.forEachIndexed { index, (name, rawRes) ->
                    val first = index == 0
                    val last = index == plainLicenses.lastIndex
                    GroupRow(first = first, last = last, onClick = {
                        onOpenSheet(LicenseSheet(name, rawRes, url = null))
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                AppText(
                                    text = name,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = pageBodyStyle()
                                )
                                AppText(
                                    text = hint,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = pageSecondaryStyle()
                                )
                            }
                            if (miuixRows) {
                                MiuixChevron()
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(R.string.licenses_section_deps)
            SettingsGroup {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                // A name that would crowd the version truncates instead of
                                // wrapping; the version sits on its own line, flush under
                                // the name, with the license following.
                                AppText(
                                    text = name,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = pageBodyStyle(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row {
                                    if (version.isNotEmpty()) {
                                        AppText(
                                            text = version,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = pageSecondaryStyle()
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    AppText(
                                        text = license,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = pageSecondaryStyle()
                                    )
                                }
                            }
                            if (miuixRows) {
                                MiuixChevron()
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(R.string.licenses_section_refs)
            SettingsGroup {
                REFERENCES.forEachIndexed { index, ref ->
                    val name = ref[0]
                    val license = ref[1]
                    val url = ref[2]
                    val first = index == 0
                    val last = index == REFERENCES.lastIndex
                    // 「—」 marks a repository that ships no LICENSE file, so there
                    // is no text to open a sheet for. Such a row is inert only when
                    // it also has no link; if it carries a URL (onijiang0/fcmfix)
                    // the tap opens that repository directly, same as a 查看源代码.
                    val noLicense = license == "—"
                    val hasUrl = url.isNotEmpty()
                    val click: () -> Unit = when {
                        noLicense && hasUrl -> ({ onOpenUrl(url) })
                        noLicense -> ({})
                        else -> ({
                            onOpenSheet(LicenseSheet(name, licenseRawFor(name, license), url))
                        })
                    }
                    GroupRow(first = first, last = last, onClick = click) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                AppText(
                                    text = name,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = pageBodyStyle()
                                )
                                AppText(
                                    text = license,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = pageSecondaryStyle()
                                )
                            }
                            // The door mark shows whenever the tap leads somewhere —
                            // a licence sheet, or the repository link on a
                            // no-licence row. A truly inert (no link, no licence)
                            // row wears none.
                            if (miuixRows && (!noLicense || hasUrl)) {
                                MiuixChevron()
                            }
                        }
                    }
                }
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

    // Under Miuix this is InstallerX's licence dialog, not the generic one: the
    // text rides in a card on the dialog's raised container, scrolls inside it
    // with Miuix's own overscroll, and the actions are two full-width Miuix
    // buttons at the foot. Under Material it stays the M3 dialog it always was.
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixLicenseDialog(
            sheet = sheet,
            body = body,
            sourceLabel = sourceLabel,
            closeLabel = closeLabel,
            onDismiss = onDismiss,
            onOpenSource = onOpenSource
        )
        return
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = sheet.title,
        confirmLabel = closeLabel,
        onConfirm = onDismiss,
        // 查看源代码 lives on the dialog's bottom action row, leading of
        // 关闭 — it used to ride at the top of the body slot, which read as
        // a stray button above the prose. null when there is no repository.
        neutralButton = sheet.url?.let { url ->
            {
                TextButton(onClick = { onOpenSource(url) }) {
                    Text(sourceLabel)
                }
            }
        },
        content = {
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
                AppText(
                    modifier = Modifier
                        .heightIn(max = bodyHeight)
                        .verticalScroll(rememberScrollState()),
                    text = body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = pageProseStyle(),
                    textAlign = TextAlign.Start
                )
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
 * Shared SPDX texts live in one raw file each; the three MIT reference projects
 * ship their own copyright line, so they keep a dedicated copy. Repositories
 * carrying no LICENSE file are shown as 「—」 and never reach this function.
 *
 * `internal` so the unit tests can pin the mapping down — the branch order here
 * has bitten once already (see the Eclipse note below).
 */
internal fun licenseRawFor(name: String, licenseLabel: String): Int = when {
    name == "Kr328/HyperOSFCMFix" -> R.raw.license_mit_hyperosfcmfix
    name == "ReedGAOOO/FCMGuard-HyperOS" -> R.raw.license_mit_fcmguard
    name == "1812z/HyperIsland" -> R.raw.license_mit_hyperisland
    licenseLabel.contains("Apache") -> R.raw.license_apache2
    licenseLabel.contains("GPL") -> R.raw.license_gpl3
    licenseLabel.contains("MIT") -> R.raw.license_mit
    // JUnit is EPL-1.0, and the fallback below is Apache — without this branch the
    // row would have shown the wrong licence text under a correct-looking label.
    // Both spellings are accepted because SPDX ships the short form ("EPL-1.0",
    // "EPL-2.0") that carries no "Eclipse" substring.
    licenseLabel.contains("Eclipse") || licenseLabel.contains("EPL-") -> R.raw.license_epl1
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
 * Re-audited again on 2026-10-09, this time against **both** the resolved
 * graph and the surviving classes in `mapping/release/mapping.txt`. Four rows
 * had drifted and were corrected:
 * - AndroidX Compose UI / Compose Foundation: 1.12.1 → **1.13.0-alpha01**
 * - AndroidX Compose Material3: 1.5.0-alpha27 → **1.5.0-alpha29**
 * - Kotlin Stdlib: 2.2.10 → **2.2.20**
 * - Lifecycle Common / Runtime: 2.9.4 → **2.10.0**
 * The Compose bump is not a BOM move: `compose-bom:2026.09.00` still pins the
 * 1.12.x line, but `material3:1.5.0-alpha29` is declared above it and its
 * `material3-android` POM constrains every `androidx.compose.*` module forward
 * to 1.13.0-alpha01. That is also why the row for the BOM itself stays off:
 * the number a reader would want is the one that wins, and the BOM is not it.
 *
 * The 2026-10-11 pass changed two versions and one status:
 * - AndroidX Activity: 1.8.2 → **1.13.0** (the build.gradle bump that installed
 *   the NavigationEventDispatcher owner Miuix's overlay popups need; the row
 *   had been left behind at 1.8.2 by the previous pass).
 * - Kotlinx Coroutines: 1.9.0 → **1.10.2**, and it stops being a licence-only
 *   row: UpdateChecker's network check now runs through an explicitly declared
 *   `kotlinx-coroutines-android` dependency, so its classes ship in the APK.
 *
 * The 2026-10-15 pass moved the toolchain and pinned one library that used to
 * be inherited: Kotlin now compiles through the standalone KGP 2.4.20 (AGP's
 * built-in Kotlin is opted out — it is pinned at 2.2.10, see build.gradle and
 * gradle.properties) and Lifecycle is declared explicitly at 2.11.0:
 * - Kotlin Stdlib: 2.2.20 → **2.4.20**
 * - Lifecycle Common / Runtime: 2.10.0 → **2.11.0**
 *
 * The 2026-10-19 pass re-audited the resolved graph plus the classes surviving
 * in that day's `mapping/release/mapping.txt`. Five versions had drifted and
 * one row changed sides:
 * - AndroidX Annotation: 1.9.1 → **1.10.0**
 * - AndroidX Collection: 1.5.0 → **1.6.0**
 * - AndroidX Compose UI / Compose Foundation: 1.13.0-alpha01 → **1.12.1** —
 *   material3's promotion to 1.5.0-beta01 stopped dragging the 1.13.0-alpha
 *   line forward, so the compose-bom's 1.12.1 wins again (the BOM row stays
 *   off for the same reason as before: the number a reader wants is the one
 *   that wins, and the BOM is not it).
 * - AndroidX Core: 1.16.0 → **1.19.1**
 * - Material Kolor Color Utilities: 4.1.1 → **5.0.1**, and it moved
 *   coordinates: JitPack's `com.github.jordond.materialkolor` is gone, the
 *   artifact now publishes as `com.materialkolor:material-color-utilities`.
 * - **Kotlinx Serialization** joins as a real row: the `@Serializable` route
 *   keys (nav/Route.kt) made the runtime load-bearing, and 127
 *   kotlinx.serialization classes survive in mapping.txt — the 2026-10-09
 *   "R8 removes all of it" note below no longer holds.
 *
 * No rows were added or removed in the 2026-10-09 pass. Several families that
 * do ship classes (androidx.graphics, androidx.savedstate, androidx.window,
 * androidx.emoji2, androidx.fragment, androidx.recyclerview, androidx.transition
 * and the rest) are transitive, exactly like the ~39 families the policy note
 * above already covers — they arrive behind material3/appcompat and get one row
 * only when this app is *built on* them, not when it merely inherits them.
 * `kotlinx.serialization` was left off back then for the same reason `JUnit`
 * was kept: it resolved onto the classpath but R8 removed all of it (225
 * classes in usage.txt, 0 in mapping.txt). That verdict stood until the
 * serialization-based navigation landed; see the 2026-10-19 pass above.
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
 *
 * The 2026-10-25 pass re-audited the resolved graph against
 * `releaseRuntimeClasspath` plus the classes left in `mapping/release/mapping.txt`.
 * Two rows had drifted, both by being a version behind what was declared:
 * - Kotlin Stdlib: 2.4.20 → **2.4.21** — the root build pins the KGP at 2.4.21,
 *   and 2.4.21 wins over every 2.4.20 request in the graph.
 * - Kotlinx Serialization: 1.11.0 → **1.12.0** — 1.12.0 is what build.gradle
 *   declares; 1.11.0 is only what miuix-nav asks for, and it loses. Its
 *   surviving-class count is 150 today (127 was the 2026-10-19 reading).
 * Nothing else moved: every other row still resolves to the version it states.
 * ConstraintLayout's surviving-class count is 55 now — the 3 recorded above was
 * a reading of a thinner graph, and the reason that row exists has not changed.
 */
private val DEPS = arrayOf(
    arrayOf("AndroidX Activity", "1.13.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Annotation", "1.10.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX AppCompat", "1.8.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Arch Core", "2.2.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Collection", "1.6.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Compose Foundation", "1.12.1", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Compose Material3", "1.5.0-beta01", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Compose UI", "1.12.1", "Apache License 2.0", ANDROIDX_URL),
    // Separate repository, so ANDROIDX_URL does not cover it. Only on the
    // classpath behind Material Components, but its code does survive R8.
    arrayOf(
        "AndroidX ConstraintLayout",
        "2.2.1",
        "Apache License 2.0",
        "https://github.com/androidx/constraintlayout"
    ),
    arrayOf("AndroidX Core", "1.19.1", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("AndroidX Interpolator", "1.0.0", "Apache License 2.0", ANDROIDX_URL),
    // Miuix's responsive-layout chain (material3-window-size-class): real code
    // in the APK, 12 classes of it (window + window-core), verified against the
    // R8 mapping.
    arrayOf("AndroidX Window", "1.5.0", "Apache License 2.0", ANDROIDX_URL),
    // Build-only stubs vendored under hiddenapi/stubs; kept for attribution.
    arrayOf("AOSP Framework Annotations", "", "Apache License 2.0", AOSP_URL),
    // The title-bar glass's blur engine (the constant-radius, alpha-masked
    // treatment the stain reports drove us to) — haze + haze-blur.
    arrayOf(
        "Haze",
        "2.0.1",
        "Apache License 2.0",
        "https://github.com/chrisbanes/haze"
    ),
    // Test scope only — nothing from it is in any APK. Listed for the same
    // reason as the annotation-only rows: it is a third-party project in the
    // build with its own licence. EPL-1.0 (see licenseRawFor).
    arrayOf("JUnit", "4.13.2", "Eclipse Public License 1.0", "https://github.com/junit-team/junit4"),
    arrayOf("Kotlin Stdlib", "2.4.21", "Apache License 2.0", "https://github.com/JetBrains/kotlin"),
    // Declared explicitly in build.gradle since UpdateChecker's network check
    // moved onto Dispatchers.IO: real runtime code in the APK now, not a
    // licence statement for a transitive leftover.
    arrayOf(
        "Kotlinx Coroutines",
        "1.11.0",
        "Apache License 2.0",
        "https://github.com/Kotlin/kotlinx.coroutines"
    ),
    // Route keys are @Serializable (nav/Route.kt) and the back stack is persisted
    // by serializing them, so the runtime is load-bearing: 127 kotlinx.serialization
    // classes survive in mapping/release/mapping.txt (the 2026-10-09 "R8 removes
    // all of it" note below no longer holds).
    arrayOf(
        "Kotlinx Serialization",
        "1.12.0",
        "Apache License 2.0",
        "https://github.com/Kotlin/kotlinx.serialization"
    ),
    arrayOf("libxposed API", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/api"),
    arrayOf("libxposed Interface", "102.0.0", "Apache License 2.0", "https://github.com/libxposed"),
    arrayOf("libxposed Service", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/service"),
    arrayOf("Lifecycle Common", "2.11.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("Lifecycle Runtime", "2.11.0", "Apache License 2.0", ANDROIDX_URL),
    // Vendored source under mcu/ (no Gradle artifact) — listed for attribution.
    arrayOf("Material Color Utilities", "", "Apache License 2.0", MCU_URL),
    arrayOf(
        "Material Components",
        "1.14.0",
        "Apache License 2.0",
        "https://github.com/material-components/material-components-android"
    ),
    // What Miuix derives its Monet schemes from (莫奈颜色). Same upstream colour
    // utilities as the vendored copy above, published as an artifact; both are in
    // the APK and each dresses a different half of the UI.
    arrayOf(
        "Material Kolor Color Utilities",
        "5.0.1",
        "Apache License 2.0",
        "https://github.com/jordond/materialkolor"
    ),
    // The Miuix look (界面风格 = Miuix). Bumped 0.9.1 → 0.9.4 on 2026-10-15, once
    // the standalone KGP 2.4.20 lift removed the 0.9.1 metadata ceiling — the
    // toolchain comment in build.gradle has the audit. One row for the project,
    // though it ships as miuix-ui, miuix-core, miuix-preference, miuix-icons,
    // miuix-blur (back since the floating bottom bar — the title-bar glass stayed
    // on Haze), miuix-shader and miuix-squircle behind it, and miuix-nav (the
    // navigation runtime the pages run through).
    arrayOf(
        "Miuix",
        "0.9.4",
        "Apache License 2.0",
        "https://github.com/compose-miuix-ui/miuix"
    ),
    // What Miuix hooks predictive back through on Android.
    arrayOf("NavigationEvent", "1.2.0", "Apache License 2.0", ANDROIDX_URL),
    arrayOf("VersionedParcelable", "1.1.1", "Apache License 2.0", ANDROIDX_URL),
)

/** name, license label, project URL. */
private val REFERENCES = arrayOf(
    // The batch mode's bar and row language — the entry beside the overflow,
    // close/select-all/overflow on the selection bar, a checkbox on the row —
    // follows this app's design.
    arrayOf("1812z/HyperIsland", "MIT License", "https://github.com/1812z/HyperIsland"),
    arrayOf("250king/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/250king/HyperOS_FCM_Live"),
    // Same MIUIX / soft-glass family — the row links to its releases page.
    arrayOf("AritxOnly/F1Info", "GPL-3.0", "https://github.com/AritxOnly/F1Info/releases"),
    arrayOf("billtv/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/billtv/HyperOS_FCM_Live"),
    // A sibling repair in the same family as the ColorOS row at the foot of this
    // list: FCM tuned for ColorOS 16/17 (国行), derived from kooritea/fcmfix. No
    // LICENSE file in the repository — shown as 「—」, so a tap opens the project
    // itself like the other unlicensed rows.
    arrayOf("ByteJuno/fcmfix-coloros", "—", "https://github.com/ByteJuno/fcmfix-coloros"),
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
    // The retired GCM reconnect repair (4.1.1) followed this module's catch-up
    // shape: a stale-connection check after the heartbeat timer's countdown
    // expires, then a GCM_RECONNECT broadcast. No LICENSE file in the
    // repository — shown as 「—」, the row links to the project like the other
    // unlicensed ones.
    arrayOf("kooritea/fcmfix", "—", "https://github.com/kooritea/fcmfix"),
    // The title-bar glass both looks wear ([HazeGlassBar]) is this app's app
    // bar blur construction: the list underlaps the bar, and that underlap is
    // what the blur samples.
    arrayOf("LibChecker/LibChecker", "GPL-3.0", "https://github.com/LibChecker/LibChecker"),
    arrayOf("tiann/KernelSU", "GPL-3.0", "https://github.com/tiann/KernelSU"),
    // No LICENSE file in the repository — shown as 「—」. There is no text to
    // open a sheet for, but the row links to the project, so a tap opens that
    // URL directly and it still wears the right-side chevron.
    arrayOf("onijiang0/fcmfix", "—", "https://github.com/onijiang0/fcmfix"),
    arrayOf("ReedGAOOO/FCMGuard-HyperOS", "MIT License", "https://github.com/ReedGAOOO/FCMGuard-HyperOS"),
    // The idea to put the settings icon at the home bar's leading edge came
    // from this manager's own leading-edge placement.
    arrayOf("Replica0110/Lyrico", "Apache License 2.0", "https://github.com/Replica0110/Lyrico"),
    arrayOf("wxxsfxyzm/InstallerX-Revived", "GPL-3.0", "https://github.com/wxxsfxyzm/InstallerX-Revived"),
    // The home shell's bottom-bar tab typography — the icon-and-label pill the
    // Miuix docked bar wears — is this client's PillTabItem recipe, ported.
    arrayOf("YuKongA/Mishka", "GPL-3.0", "https://github.com/YuKongA/Mishka"),
    // A sibling repair in the same family: FCM tuned for ColorOS 17. No
    // LICENSE file in the repository — shown as 「—」, the row links to the
    // project like the other unlicensed ones.
    arrayOf("zopulus/fcmfix-coloros17", "—", "https://github.com/zopulus/fcmfix-coloros17"),
    arrayOf("zuohl/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/zuohl/HyperOS_FCM_Live"),
)

/**
 * The Miuix 「this leads somewhere」 mark, verbatim from the library's own
 * [top.yukonga.miuix.kmp.preference.ArrowPreference]: its chevron, at its
 * 10×16 footprint, in its quiet grey — so a row that opens a repository wears
 * the same door mark the settings rows do.
 */
@Composable
private fun MiuixChevron() {
    Image(
        modifier = Modifier.size(width = 10.dp, height = 16.dp),
        imageVector = MiuixIcons.Basic.ArrowRight,
        contentDescription = null,
        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions)
    )
}

/**
 * The licence text under the Miuix look, after InstallerX's manager: the body is
 * a card on the dialog's raised container level, capped at the same share of the
 * host window and scrolling inside; 查看源代码 and 关闭 are full-width Miuix text
 * buttons in one row, the closing one in the accent.
 *
 * No [SelectionContainer] here, deliberately. Selecting text in this sheet —
 * long-pressing into the selection, then tapping it again — paints a stray dark
 * blob at the foot of the screen (the platform selection toolbar/handles
 * rendering into the dialog window), and the reference settings sheets offer no
 * text copy of their own either. The text is static; drop the machinery rather
 * than ship the artifact.
 */
@Composable
private fun MiuixLicenseDialog(
    sheet: LicenseSheet,
    body: String,
    sourceLabel: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    onOpenSource: (String) -> Unit
) {
    OverlayDialog(
        show = true,
        title = sheet.title,
        onDismissRequest = onDismiss
    ) {
        val density = LocalDensity.current
        val bodyHeight = with(density) {
            val px = LocalWindowInfo.current.containerSize.height
            if (px <= 0) Dp.Infinity else (px * 0.45f).toDp()
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            // Same ceiling idea as the Material dialog, measured from the window
            // the dialog is actually in; 0.45 rather than 0.55 because the Miuix
            // sheet is anchored to the foot and the buttons must stay below it.
            MiuixCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyHeight),
                colors = MiuixCardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                AppText(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    text = body,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    style = pageProseStyle(),
                    textAlign = TextAlign.Start
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (sheet.url != null) {
                    MiuixTextButton(
                        text = sourceLabel,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenSource(sheet.url) }
                    )
                }
                MiuixTextButton(
                    text = closeLabel,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                    colors = MiuixButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}
