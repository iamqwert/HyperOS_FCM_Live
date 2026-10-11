package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.PrivacyScreen
import io.github.howard20181.hyperos.fcmlive.ui.PredictiveBackContainer
import io.github.howard20181.hyperos.fcmlive.ui.WindowSnapshot
import io.github.howard20181.hyperos.fcmlive.ui.finishPredictiveBack

/**
 * Privacy & permissions page, opened from the About card between
 * 「检查更新」 and 「查看源代码」.
 *
 * Compose owns the whole screen now — the bar included — so this class only
 * supplies the finish callback and the safe-area contract. It still extends
 * [AppCompatActivity] because [ThemeSupport.attach] expects one.
 *
 * The page is wrapped in [PredictiveBackContainer]: the system's predictive
 * back gesture (or the back key) plays the Miuix-style slide-out preview before
 * this Activity finishes.
 */
class PrivacyActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)

        // No id and no XML: Compose saves and restores its own scroll state
        // through `rememberSaveable`, so this view needs nothing from the View
        // side to survive a rotation.
        val composeView = ComposeView(this).apply {
            setContent {
                HyperFCMLiveTheme {
                    PredictiveBackContainer(
                        // A committed back has already slid the page out, so
                        // the exit transition is suppressed for that path only;
                        // a plain finish from the top-bar arrow still animates.
                        onBack = { alreadySlidOut -> finishPredictiveBack(alreadySlidOut) },
                        background = { WindowSnapshot.forParent(AboutActivity::class.java) }
                    ) {
                        PrivacyScreen(onBack = { finishPredictiveBack(alreadySlidOut = false) })
                    }
                }
            }
        }
        setContentView(composeView)
    }
}
