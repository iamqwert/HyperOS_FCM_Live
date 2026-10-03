package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.PrivacyScreen

/**
 * Privacy & permissions page, opened from the About card between
 * 「检查更新」 and 「查看源代码」.
 *
 * Compose owns the whole screen now — the bar included — so this class only
 * supplies the finish callback and the safe-area contract. It still extends
 * [AppCompatActivity] because [ThemeSupport.attach] expects one.
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
                    PrivacyScreen(onBack = { finish() })
                }
            }
        }
        setContentView(composeView)
    }
}
