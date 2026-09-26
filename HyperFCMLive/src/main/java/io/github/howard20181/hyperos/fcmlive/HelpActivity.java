package io.github.howard20181.hyperos.fcmlive;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;

import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport;

/**
 * The help page, opened from the top bar of the About screen.
 *
 * <p>It is static text on purpose: everything it claims is behaviour that lives
 * in {@code Hooker} — the allowlist semantics (an empty list lets every app
 * through, a non-empty one only the listed apps), what a wake actually does to
 * the target app, what strict mode does to an app left unchecked
 * ({@code Hooker#shouldApply}), and the rules around enabling the module.
 * Keep this text in sync when those hooks change.
 */
public class HelpActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeSupport.attach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeSupport.onCreate(this);
        setContentView(R.layout.activity_help);
        applySystemBarInsets();

        View back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }
    }

    /**
     * The same safe-area handling as the About page: the status bar inset pads
     * the top bar, and the bottom inset pads the scrollable content so the last
     * card clears the home indicator while the page colour still reaches the
     * bottom edge of the screen.
     */
    private void applySystemBarInsets() {
        UiUtils.applyBarInsets(this, findViewById(R.id.top_bar),
                findViewById(R.id.help_content), 16);
    }
}
