package io.github.howard20181.hyperos.fcmlive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

/** About: source, licenses, update check with red badge. */
public class AboutActivity extends Activity {

    private static final String REPO_URL = "https://github.com/iamqwert/HyperOS_FCM_Live";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        applySystemBarInsets();

        View back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
            attachTip(back, R.string.back);
        }

        View sourceRow = findViewById(R.id.row_view_source);
        if (sourceRow != null) {
            sourceRow.setOnClickListener(v -> openUrl(REPO_URL));
        }

        View licensesRow = findViewById(R.id.row_open_source_licenses);
        if (licensesRow != null) {
            licensesRow.setOnClickListener(v ->
                    startActivity(new Intent(this, LicensesActivity.class)));
        }

        View updateRow = findViewById(R.id.row_check_update);
        if (updateRow != null) {
            updateRow.setOnClickListener(v -> checkForUpdates());
        }

        TextView version = findViewById(R.id.about_version);
        if (version != null) {
            try {
                version.setText(getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName);
            } catch (Throwable ignored) {
                version.setText(R.string.app_name);
            }
        }

        showUpdateBadge(UpdateChecker.isUpdateAvailable(this));
    }

    private void checkForUpdates() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show();
        UpdateChecker.checkAsync(this, (available, latest, url) ->
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    showUpdateBadge(available);
                    if (!available) {
                        Toast.makeText(this, R.string.update_none, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setMessage(getString(R.string.update_found, latest))
                            .setPositiveButton(R.string.update_open, (d, w) -> {
                                UpdateChecker.clearBadge(this);
                                showUpdateBadge(false);
                                openUrl(url);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }));
    }

    /** Offset tooltip below the anchor so HyperOS does not cover the icon. */
    private void attachTip(View view, int textRes) {
        if (view == null) {
            return;
        }
        final CharSequence tip = getText(textRes);
        view.setContentDescription(tip);
        view.setTooltipText(null);
        view.setLongClickable(true);
        view.setOnLongClickListener(v -> {
            showAnchorTooltip(v, tip);
            return true;
        });
    }

    private PopupWindow activeTooltip;

    private void showAnchorTooltip(View anchor, CharSequence text) {
        dismissActiveTooltip();
        TextView tipView = new TextView(this);
        tipView.setText(text);
        tipView.setTextColor(getColor(R.color.md_tooltip_text));
        tipView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        tipView.setBackgroundResource(R.drawable.bg_tooltip);
        int padH = dp(12);
        int padV = dp(6);
        tipView.setPadding(padH, padV, padH, padV);
        tipView.setSingleLine(true);
        tipView.measure(
                View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.AT_MOST));

        PopupWindow popup = new PopupWindow(tipView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setOutsideTouchable(true);
        popup.setFocusable(false);
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setOnDismissListener(() -> {
            if (activeTooltip == popup) {
                activeTooltip = null;
            }
        });

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int gap = dp(8);
        int x = loc[0] + (anchor.getWidth() - tipView.getMeasuredWidth()) / 2;
        int y = loc[1] + anchor.getHeight() + gap;
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        int[] decorLoc = new int[2];
        if (decor != null) {
            decor.getLocationOnScreen(decorLoc);
        }
        try {
            popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY,
                    x - decorLoc[0], y - decorLoc[1]);
            activeTooltip = popup;
        } catch (Throwable ignored) {
        }
    }

    private void dismissActiveTooltip() {
        PopupWindow popup = activeTooltip;
        activeTooltip = null;
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        dismissActiveTooltip();
        super.onDestroy();
    }

    private void showUpdateBadge(boolean show) {
        View badge = findViewById(R.id.badge_update);
        if (badge != null) {
            badge.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void openUrl(String url) {
        UiUtils.openUrl(this, url);
    }

    /** Same top-bar inset as MainActivity so the title sits under the status bar. */
    private void applySystemBarInsets() {
        final View topBar = findViewById(R.id.top_bar);
        if (topBar == null) {
            return;
        }
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int barPad = dp(12);
            topBar.setPadding(topBar.getPaddingLeft(), top + barPad,
                    topBar.getPaddingRight(), barPad);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), bottom);
            return insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
        int statusBar = statusBarHeight();
        if (statusBar > 0 && topBar.getPaddingTop() <= statusBar) {
            topBar.setPadding(topBar.getPaddingLeft(), statusBar + dp(12),
                    topBar.getPaddingRight(), dp(12));
        }
    }

    private int dp(int value) {
        return UiUtils.dp(this, value);
    }

    private int statusBarHeight() {
        return UiUtils.statusBarHeight(this);
    }
}
