package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.ExperimentScreen
import io.github.howard20181.hyperos.fcmlive.ui.PredictiveBackContainer
import io.github.howard20181.hyperos.fcmlive.ui.WindowSnapshot
import io.github.howard20181.hyperos.fcmlive.ui.finishPredictiveBack

/**
 * Experiment switches, opened from the About page.
 *
 * Compose owns the whole screen — bar, groups and rows — so this class only
 * supplies the finish callback; the switch rows read and publish through
 * [Prefs] themselves. It still extends [AppCompatActivity] because
 * [ThemeSupport.attach] expects one.
 *
 * The page is wrapped in [PredictiveBackContainer]: the system's predictive
 * back gesture (or the back key) plays the Miuix-style slide-out preview before
 * this Activity finishes.
 */
class ExperimentActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    // A language pick lands here instead of a recreate (configChanges in the
    // manifest) — no window swap, no flash.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.onLocaleConfigChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)

        val composeView = ComposeView(this).apply {
            setContent {
                HyperFCMLiveTheme {
                    PredictiveBackContainer(
                        // A committed back has already slid the page out, so
                        // the exit transition is suppressed for that path only;
                        // a plain finish from the top-bar arrow still animates.
                        onBack = { alreadySlidOut -> finishPredictiveBack(alreadySlidOut) },
                        // Whoever launched us is the layer the reveal shows —
                        // read from the launch-time record, not a static list
                        // (both the M3 settings tab and the Miuix about page
                        // open this window).
                        background = { WindowSnapshot.forLaunchingParent(ExperimentActivity::class.java) }
                    ) {
                        ExperimentScreen(onBack = { finishPredictiveBack(alreadySlidOut = false) })
                    }
                }
            }
        }
        setContentView(composeView)
    }
}
