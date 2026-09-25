package io.github.howard20181.hyperos.fcmlive;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.howard20181.hyperos.fcmlive.theme.AppPalette;
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine;
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport;

/**
 * Open-source license list. Deps show version on the right; this project and
 * reference projects use name + license with a vertically centered link icon.
 */
public class LicensesActivity extends Activity {

    private static final String REPO_URL = "https://github.com/iamqwert/HyperOS_FCM_Live";
    private static final String ANDROIDX_URL = "https://github.com/androidx/androidx";
    private static final String AOSP_URL = "https://android.googlesource.com/platform/frameworks/base";
    private static final String JSPECIFY_URL = "https://github.com/jspecify/jspecify";
    private static final String MCU_URL =
            "https://github.com/material-foundation/material-color-utilities";

    /** name, version ("" if none), license label, project URL. */
    private static final String[][] DEPS = {
            {"AndroidX Annotation", "1.10.0", "Apache License 2.0", ANDROIDX_URL},
            {"AndroidX Arch Core", "2.0.0", "Apache License 2.0", ANDROIDX_URL},
            {"AndroidX Collection", "1.0.0", "Apache License 2.0", ANDROIDX_URL},
            {"AndroidX Core", "1.1.0", "Apache License 2.0", ANDROIDX_URL},
            {"AndroidX Interpolator", "1.0.0", "Apache License 2.0", ANDROIDX_URL},
            // Build-only stubs vendored under hiddenapi/stubs; kept for attribution.
            {"AOSP Framework Annotations", "", "Apache License 2.0", AOSP_URL},
            {"JetBrains Annotations", "13.0", "Apache License 2.0", "https://github.com/JetBrains/java-annotations"},
            {"JSpecify", "1.0.0", "Apache License 2.0", JSPECIFY_URL},
            {"Kotlin Stdlib", "2.2.10", "Apache License 2.0", "https://github.com/JetBrains/kotlin"},
            {"libxposed API", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/api"},
            {"libxposed Interface", "102.0.0", "Apache License 2.0", "https://github.com/libxposed"},
            {"libxposed Service", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/service"},
            {"Lifecycle Common", "2.0.0", "Apache License 2.0", ANDROIDX_URL},
            {"Lifecycle Runtime", "2.0.0", "Apache License 2.0", ANDROIDX_URL},
            // Vendored source under mcu/ (no Gradle artifact) — listed for attribution.
            {"Material Color Utilities", "", "Apache License 2.0", MCU_URL},
            {"SwipeRefreshLayout", "1.2.0", "Apache License 2.0", ANDROIDX_URL},
            {"VersionedParcelable", "1.1.0", "Apache License 2.0", ANDROIDX_URL},
    };

    /** name, license label, project URL. */
    private static final String[][] REFERENCES = {
            {"250king/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/250king/HyperOS_FCM_Live"},
            {"billtv/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/billtv/HyperOS_FCM_Live"},
            {"HappyMax0/FCMPushViewer", "Apache License 2.0", "https://github.com/HappyMax0/FCMPushViewer"},
            {"Howard20181/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/Howard20181/HyperOS_FCM_Live"},
            {"zuohl/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/zuohl/HyperOS_FCM_Live"},
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeSupport.attach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeSupport.onCreate(this);
        setContentView(R.layout.activity_licenses);
        applySystemBarInsets();

        View back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }

        LinearLayout list = findViewById(R.id.licenses_list);
        if (list == null) {
            return;
        }
        // Was applied on every inset dispatch; once is enough — the bottom padding
        // itself still tracks the navigation mode.
        list.setClipToPadding(false);
        LayoutInflater inflater = LayoutInflater.from(this);

        addSectionHeader(list, inflater, getString(R.string.licenses_section_this_app));
        View appRow = inflater.inflate(R.layout.item_license_ref, list, false);
        bindRow(appRow, getString(R.string.app_name), "GPL-3.0");
        appRow.setOnClickListener(v -> openUrl(REPO_URL));
        addRow(list, appRow, true, true);

        addSectionHeader(list, inflater, getString(R.string.licenses_section_licenses));
        String licenseHint = getString(R.string.license_view_full_text);
        String[] licenseNames = {
                getString(R.string.license_apache_2),
                getString(R.string.license_gpl_3),
        };
        int[] licenseRaw = {
                R.raw.license_apache2,
                R.raw.license_gpl3,
        };
        for (int i = 0; i < licenseNames.length; i++) {
            View row = inflater.inflate(R.layout.item_license_dep, list, false);
            bindRow(row, licenseNames[i], licenseHint);
            View licenseLink = row.findViewById(R.id.dep_link);
            if (licenseLink != null) {
                licenseLink.setVisibility(View.GONE);
            }
            final String title = licenseNames[i];
            final int rawRes = licenseRaw[i];
            row.setOnClickListener(v -> showLicenseDialog(title, rawRes));
            addRow(list, row, i == 0, i == licenseNames.length - 1);
        }

        addSectionHeader(list, inflater, getString(R.string.licenses_section_deps));
        for (int i = 0; i < DEPS.length; i++) {
            String[] dep = DEPS[i];
            View row = inflater.inflate(R.layout.item_license_dep, list, false);
            bindRow(row, dep[0], dep[1], dep[2]);
            final String url = dep[3];
            row.setOnClickListener(v -> openUrl(url));
            addRow(list, row, i == 0, i == DEPS.length - 1);
        }

        addSectionHeader(list, inflater, getString(R.string.licenses_section_refs));
        for (int i = 0; i < REFERENCES.length; i++) {
            String[] ref = REFERENCES[i];
            View row = inflater.inflate(R.layout.item_license_ref, list, false);
            bindRow(row, ref[0], ref[1]);
            final String url = ref[2];
            row.setOnClickListener(v -> openUrl(url));
            addRow(list, row, i == 0, i == REFERENCES.length - 1);
        }
    }

    /** Two-line row (name + license) for this project / references. */
    private void bindRow(View row, String name, String license) {
        TextView nameView = row.findViewById(R.id.dep_name);
        TextView licenseView = row.findViewById(R.id.dep_license);
        if (nameView != null) {
            nameView.setText(name);
        }
        if (licenseView != null) {
            licenseView.setText(license);
        }
    }

    /** Dep row: name + version on the right, license below. */
    private void bindRow(View row, String name, String version, String license) {
        bindRow(row, name, license);
        TextView versionView = row.findViewById(R.id.dep_version);
        if (versionView != null) {
            versionView.setText(version != null ? version : "");
        }
    }

    /** Section header styled exactly like the About page groups. */
    private void addSectionHeader(LinearLayout list, LayoutInflater inflater, String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(ThemeEngine.palette(this).primary);
        header.setTextSize(14f);
        header.setTypeface(android.graphics.Typeface.create(
                "sans-medium", android.graphics.Typeface.NORMAL));
        header.setPadding(dp(8), dp(28), dp(8), dp(12));
        list.addView(header);
    }

    /**
     * Adds one card to a section with the M3 connected-group look used on the
     * About page: 2dp gaps between cards, 16dp outer corners, 4dp inner
     * corners, flat (no elevation), and a ripple masked to the same shape.
     */
    private void addRow(LinearLayout list, View row, boolean first, boolean last) {
        row.setBackground(groupRowBackground(first, last));
        if (row.getLayoutParams() instanceof LinearLayout.LayoutParams lp) {
            lp.topMargin = first ? 0 : dp(2);
        }
        list.addView(row);
    }

    /** Position-aware rounded ripple matching the About page card groups. */
    private android.graphics.drawable.Drawable groupRowBackground(boolean first, boolean last) {
        AppPalette palette = ThemeEngine.palette(this);
        float top = first ? dp(16) : dp(4);
        float bottom = last ? dp(16) : dp(4);
        float[] radii = {top, top, top, top, bottom, bottom, bottom, bottom};
        android.graphics.drawable.GradientDrawable content =
                new android.graphics.drawable.GradientDrawable();
        content.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        content.setCornerRadii(radii);
        content.setColor(palette.card);
        android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
        mask.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        mask.setCornerRadii(radii);
        mask.setColor(android.graphics.Color.WHITE);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(palette.ripple), content, mask);
    }

    private void openUrl(String url) {
        UiUtils.openUrl(this, url);
    }

    /** Full license text in a scrollable in-app dialog (no browser). */
    private void showLicenseDialog(String title, int rawRes) {
        int pad = dp(24);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, pad, pad, pad);

        AppPalette palette = ThemeEngine.palette(this);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(18f);
        titleView.setTextColor(palette.onSurface);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = pad;
        wrap.addView(titleView, titleLp);

        TextView body = new TextView(this);
        body.setText(readRawText(rawRes));
        body.setTextSize(12f);
        body.setTextColor(palette.onSurfaceVariant);
        body.setTextIsSelectable(true);
        body.setLineSpacing(0, 1.15f);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
        wrap.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new android.app.AlertDialog.Builder(this)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String readRawText(int rawRes) {
        try (java.io.InputStream in = getResources().openRawResource(rawRes)) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Same top-bar inset as MainActivity; list clears the gesture nav bar. */
    private void applySystemBarInsets() {
        UiUtils.applyBarInsets(this, findViewById(R.id.top_bar),
                findViewById(R.id.licenses_list), 16);
    }

    private int dp(int value) {
        return UiUtils.dp(this, value);
    }
}
