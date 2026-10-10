package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.LicensesScreen
import io.github.howard20181.hyperos.fcmlive.ui.PredictiveBackContainer
import io.github.howard20181.hyperos.fcmlive.ui.WindowSnapshot
import io.github.howard20181.hyperos.fcmlive.ui.finishPredictiveBack

/**
 * Open-source license list, opened from the About page.
 *
 * Compose owns the list, the group cards and the license dialog, so this class
 * is only the finish callback — the row data, the raw license files and the
 * 「查看源代码」 links all live in [LicensesScreen].
 *
 * It still extends [AppCompatActivity] because [ThemeSupport.attach] expects
 * one.
 *
 * Wrapped in [PredictiveBackContainer]: the system's predictive back gesture
 * (or the back key) plays the Miuix-style slide-out preview before this
 * Activity finishes.
 */
class LicensesActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
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
                        background = { WindowSnapshot.forParent(AboutActivity::class.java) }
                    ) {
                        LicensesScreen(onBack = { finishPredictiveBack(alreadySlidOut = false) })
                    }
                }
            }
        }
        setContentView(composeView)
    }
}
