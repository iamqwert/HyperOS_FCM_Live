package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.LicensesScreen

/**
 * Open-source license list, opened from the About page.
 *
 * Compose owns the list, the group cards and the license dialog, so this class
 * is only the finish callback — the row data, the raw license files and the
 * 「查看源代码」 links all live in [LicensesScreen].
 *
 * It still extends [AppCompatActivity] because [ThemeSupport.attach] expects
 * one.
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
                    LicensesScreen(onBack = { finish() })
                }
            }
        }
        setContentView(composeView)
    }
}
