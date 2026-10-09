package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.PrivacyScreen
import io.github.howard20181.hyperos.fcmlive.ui.SwipeBackContainer
import io.github.howard20181.hyperos.fcmlive.ui.WindowSnapshot
import io.github.howard20181.hyperos.fcmlive.ui.finishSwipeBack

/**
 * Privacy & permissions page, opened from the About card between
 * 「检查更新」 and 「查看源代码」.
 *
 * Compose owns the whole screen now — the bar included — so this class only
 * supplies the finish callback and the safe-area contract. It still extends
 * [AppCompatActivity] because [ThemeSupport.attach] expects one.
 *
 * The page is wrapped in [SwipeBackContainer]: a rightward drag started on the
 * screen body finishes it, with the page following the finger. That is distinct
 * from the system's edge-only predictive back, which takes the window back to
 * the launcher. See the container's KDoc for the split.
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
                    // Swipe-back wraps the whole page: the drag is picked up on
                    // the body, which the system's edge-only gesture never sees.
                    SwipeBackContainer(
                        // A committed swipe has already slid the page out, so
                        // the exit transition is suppressed for that path only;
                        // a back press that never moved the page still animates.
                        onBack = { alreadySlidOut -> finishSwipeBack(alreadySlidOut) },
                        background = { WindowSnapshot.forParent(AboutActivity::class.java) }
                    ) {
                        PrivacyScreen(onBack = { finishSwipeBack(alreadySlidOut = false) })
                    }
                }
            }
        }
        setContentView(composeView)
    }
}
