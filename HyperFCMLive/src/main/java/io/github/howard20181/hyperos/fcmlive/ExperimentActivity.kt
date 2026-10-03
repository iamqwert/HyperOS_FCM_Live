package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.ExperimentScreen

/**
 * Experiment switches, opened from the About page.
 *
 * Compose owns the whole screen — bar, groups and rows — so this class only
 * supplies the finish callback; the switch rows read and publish through
 * [Prefs] themselves. It still extends [AppCompatActivity] because
 * [ThemeSupport.attach] expects one.
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
                    ExperimentScreen(onBack = { finish() })
                }
            }
        }
        setContentView(composeView)
    }
}
