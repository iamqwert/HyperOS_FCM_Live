package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import io.github.howard20181.hyperos.fcmlive.ui.AboutActions
import io.github.howard20181.hyperos.fcmlive.ui.AboutScreen
import io.github.howard20181.hyperos.fcmlive.ui.AboutTabScreen
import io.github.howard20181.hyperos.fcmlive.ui.PredictiveBackContainer
import io.github.howard20181.hyperos.fcmlive.ui.WindowSnapshot
import io.github.howard20181.hyperos.fcmlive.ui.finishPredictiveBack
import io.github.howard20181.hyperos.fcmlive.ui.startActivityWithSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Settings: appearance, allowlist backup, links, update check — or, in
 * about-only mode, just the about page (hero + the three project rows).
 *
 * The whole screen is Compose. [AboutScreen] owns the full settings page —
 * the Miuix home's single-page layout; [AboutTabScreen] owns the about-only
 * mode the M3 home's about card opens. What stays here is only what a
 * composable cannot do, and both hosts hold an [AboutDelegate] for it: the
 * SAF contracts, the update check, the release opening, and the theme's
 * View-side half.
 *
 * The page is wrapped in [PredictiveBackContainer]: the system's predictive
 * back gesture (or the back key) plays the Miuix-style slide-out preview before
 * this Activity finishes.
 */
class AboutActivity : AppCompatActivity() {

    /**
     * The extra that picks the page: true renders the about page only (hero +
     * project rows — the M3 home's destination), absent renders the full
     * settings page (the Miuix home's destination).
     */
    companion object {
        const val EXTRA_ABOUT_ONLY = "about_only"
    }

    /** The M3 feedback line. Owned here, drawn by [AboutScreen]'s Scaffold. */
    private val snackbarHostState = SnackbarHostState()

    /**
     * True while the Compose tree is wearing the Miuix shell.
     *
     * Read in composition (see [onCreate]'s setContent) and refreshed on every
     * style change; [showMessage] is a plain function, so this field is its one
     * bridge to the current look. Without it the Miuix look would swallow every
     * message: that shell hosts no snackbar, so the M3 bar silently never
     * appears.
     */
    private var miuixFeedbackNow = false

    /**
     * The look this window opened in — every launch of the full settings page
     * is a Miuix launch (the M3 home's destinations are tabs and the
     * about-only mode), and the about-only page carries no appearance rows, so
     * nothing can change the style inside it. The full page compares this
     * against the live look: a style change made in its appearance rows
     * finishes the window, handing back to a home already dressed correctly.
     */
    private var openedMiuix = false

    /** Scope for showing a message; cancelled with the Activity. */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The Activity-bound half of the screen — created before the window is
     * STARTED, which is when the SAF contracts must register.
     */
    private val delegate = AboutDelegate(this, ::showMessage)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    // A language pick lands here instead of a recreate (configChanges in the
    // manifest): the framework re-reads the configuration into this activity's
    // resources, and the locale bump makes the Compose tree re-resolve its
    // strings — no window swap, no flash, and the dynamic background keeps its
    // composition (a recreate would rebuild the mesh from zero — the reload).
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.onLocaleConfigChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        // Keep launcher shortcut icons in sync when the settings page opens.
        ShortcutPublisher.publish(this)

        val versionLine = delegate.versionLine()
        val versionTag = delegate.versionTag()
        val aboutOnly = intent.getBooleanExtra(EXTRA_ABOUT_ONLY, false)
        openedMiuix = !aboutOnly
        val composeView = ComposeView(this).apply {
            setContent {
                HyperFCMLiveTheme {
                    // The feedback style follows the shell: Miuix acknowledges
                    // with the system toast (its Scaffold hosts no snackbar),
                    // Material with the page's own bar. Read here, in
                    // composition — [showMessage] is a plain function and cannot
                    // read this itself.
                    miuixFeedbackNow = LocalUiStyle.current.isMiuixBased
                    // The full settings page owns the appearance rows, so a
                    // style change made inside this window leaves it a foreign
                    // shell over a home wearing the other look: finishing hands
                    // the user back to MainActivity, already dressed correctly.
                    // The about-only page has no such rows — nothing can change
                    // the style inside it, so it never self-closes, whatever
                    // look the home that launched it wears.
                    if (!aboutOnly && miuixFeedbackNow != openedMiuix) {
                        LaunchedEffect(Unit) {
                            finishPredictiveBack(alreadySlidOut = false)
                        }
                    }
                    PredictiveBackContainer(
                        // A committed back has already slid the page out, so the
                        // exit transition is suppressed for that path only; a back
                        // press that never moved the page still animates.
                        onBack = { alreadySlidOut -> finishPredictiveBack(alreadySlidOut) },
                        // The page underneath, as it looked when this one was
                        // opened — whoever launched us (always MainActivity
                        // today, but read from the record, not assumed).
                        // Null here just means the strip is flat.
                        background = { WindowSnapshot.forLaunchingParent(AboutActivity::class.java) }
                    ) {
                        if (aboutOnly) {
                            AboutTabScreen(
                                onBack = { finishPredictiveBack(alreadySlidOut = false) },
                                actions = AboutActions(
                                    // 「查看源代码」 opens the repository, 「许可证」
                                    // the GPL-3.0 licence text — the reference
                                    // about page's own link treatment.
                                    onViewSource = { delegate.openLink(AboutDelegate.REPO_URL) },
                                    onLicense = { delegate.openLink(AboutDelegate.GPL3_URL) },
                                    onLicenses = {
                                        startActivityWithSnapshot(
                                            Intent(this@AboutActivity, LicensesActivity::class.java)
                                        )
                                    },
                                    onExperiment = {},
                                    onExport = {},
                                    onImport = {},
                                    onCheckUpdate = {},
                                    onUpdateOpen = {},
                                    versionLine = versionLine,
                                    versionTag = versionTag,
                                    onAppearanceChange = delegate::applyAppearanceChange
                                ),
                                snackbarHostState = snackbarHostState
                            )
                        } else {
                            AboutScreen(
                            onBack = { finishPredictiveBack(alreadySlidOut = false) },
                            actions = AboutActions(
                                onViewSource = { delegate.openLink(AboutDelegate.REPO_URL) },
                                onLicense = { delegate.openLink(AboutDelegate.GPL3_URL) },
                                onLicenses = {
                                    startActivityWithSnapshot(
                                        Intent(this@AboutActivity, LicensesActivity::class.java)
                                    )
                                },
                                onExperiment = {
                                    startActivityWithSnapshot(
                                        Intent(this@AboutActivity, ExperimentActivity::class.java)
                                    )
                                },
                                onExport = delegate::exportAllowlist,
                                onImport = delegate::importAllowlist,
                                onCheckUpdate = delegate::checkForUpdates,
                                onUpdateOpen = delegate::openPendingUpdate,
                                versionLine = versionLine,
                                versionTag = versionTag,
                                onAppearanceChange = delegate::applyAppearanceChange
                            ),
                            snackbarHostState = snackbarHostState
                        )
                        }
                    }
                }
            }
        }
        setContentView(composeView)
    }

    override fun onResume() {
        super.onResume()
        // Same forced redraw as MainActivity's: this page launches the
        // licences / experiment pages mid-tap too, so its surface
        // can come back showing a row's press feedback frozen at full bloom —
        // the tap's last frame, never replaced because nothing invalidates on
        // return. One draw puts the page back at rest.
        window.decorView.invalidate()
    }

    /**
     * One line of feedback, in whichever shell the app is wearing: a snackbar
     * drawn by the page it belongs to, in that page's colours, under Material —
     * and the system toast under Miuix, whose Scaffold hosts no snackbar.
     */
    private fun showMessage(text: String) {
        if (miuixFeedbackNow) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            return
        }
        uiScope.launch {
            // A second message replaces the first instead of queueing behind it.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    override fun onDestroy() {
        // A message still waiting its turn must not outlive the window it was
        // going to be drawn in.
        uiScope.cancel()
        super.onDestroy()
    }
}
