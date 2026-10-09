package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.LicensesScreen
import io.github.howard20181.hyperos.fcmlive.ui.SwipeBackContainer
import io.github.howard20181.hyperos.fcmlive.ui.WindowSnapshot
import io.github.howard20181.hyperos.fcmlive.ui.finishSwipeBack

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
 * Wrapped in [SwipeBackContainer] so a rightward drag on the screen body
 * finishes the page while it follows the finger — separate from the system's
 * edge-only predictive back.
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
                    SwipeBackContainer(
                        // A committed swipe has already slid the page out, so
                        // the exit transition is suppressed for that path only;
                        // a back press that never moved the page still animates.
                        onBack = { alreadySlidOut -> finishSwipeBack(alreadySlidOut) },
                        background = { WindowSnapshot.forParent(AboutActivity::class.java) }
                    ) {
                        LicensesScreen(onBack = { finishSwipeBack(alreadySlidOut = false) })
                    }
                }
            }
        }
        setContentView(composeView)
    }
}
