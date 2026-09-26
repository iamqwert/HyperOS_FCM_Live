package io.github.howard20181.hyperos.fcmlive

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport

/**
 * The help page, opened from the top bar of the About screen.
 *
 * It is static text on purpose: everything it claims is behaviour that lives
 * in `Hooker` — the allowlist semantics (an empty list lets every app
 * through, a non-empty one only the listed apps), what a wake actually does to
 * the target app, what strict mode does to an app left unchecked
 * (`Hooker#shouldApply`), and the rules around enabling the module.
 * Keep this text in sync when those hooks change.
 */
class HelpActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        setContentView(R.layout.activity_help)
        applySystemBarInsets()

        val back = findViewById<View>(R.id.btn_back)
        back?.setOnClickListener { finish() }
    }

    /**
     * The same safe-area handling as the About page: the status bar inset pads
     * the top bar, and the bottom inset pads the scrollable content so the last
     * card clears the home indicator while the page colour still reaches the
     * bottom edge of the screen.
     */
    private fun applySystemBarInsets() {
        UiUtils.applyBarInsets(
            this, findViewById(R.id.top_bar),
            findViewById(R.id.help_content), 16
        )
    }
}
