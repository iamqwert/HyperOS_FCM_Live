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
 * the target app, and the rules around enabling the module and rebooting.
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
        final View topBar = findViewById(R.id.top_bar);
        final View content = findViewById(R.id.help_content);
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = UiUtils.topInset(insets);
            int bottom = UiUtils.bottomInset(insets);
            int barPad = UiUtils.dp(this, 12);
            if (topBar != null) {
                topBar.setPadding(topBar.getPaddingLeft(), top + barPad,
                        topBar.getPaddingRight(), barPad);
            }
            if (content != null) {
                content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                        content.getPaddingRight(), UiUtils.dp(this, 16) + bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
        // Fallback for ROMs that never dispatch insets to this listener.
        int statusBar = UiUtils.statusBarHeight(this);
        if (topBar != null && statusBar > 0 && topBar.getPaddingTop() <= statusBar) {
            topBar.setPadding(topBar.getPaddingLeft(), statusBar + UiUtils.dp(this, 12),
                    topBar.getPaddingRight(), UiUtils.dp(this, 12));
        }
    }
}
