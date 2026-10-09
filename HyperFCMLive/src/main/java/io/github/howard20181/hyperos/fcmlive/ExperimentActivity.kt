package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.ExperimentScreen
import io.github.howard20181.hyperos.fcmlive.ui.SwipeBackContainer
import io.github.howard20181.hyperos.fcmlive.ui.WindowSnapshot
import io.github.howard20181.hyperos.fcmlive.ui.finishSwipeBack

/**
 * Experiment switches, opened from the About page.
 *
 * Compose owns the whole screen — bar, groups and rows — so this class only
 * supplies the finish callback; the switch rows read and publish through
 * [Prefs] themselves. It still extends [AppCompatActivity] because
 * [ThemeSupport.attach] expects one.
 *
 * The page is wrapped in [SwipeBackContainer]: a rightward drag started on the
 * screen body finishes it, with the page following the finger — distinct from
 * the system's edge-only predictive back, which leaves the window.
 */
class ExperimentActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)

        val composeView = ComposeView(this).apply {
            setContent {
                HyperFCMLiveTheme {
                    SwipeBackContainer(
                        // A committed swipe has already slid the page out, so
                        // the exit transition is suppressed for that path only;
                        // a back press that never moved the page still animates.
                        onBack = { alreadySlidOut -> finishSwipeBack(alreadySlidOut) },
                        background = { WindowSnapshot.forParent(AboutActivity::class.java) }
                    ) {
                        ExperimentScreen(onBack = { finishSwipeBack(alreadySlidOut = false) })
                    }
                }
            }
        }
        setContentView(composeView)
    }
}
