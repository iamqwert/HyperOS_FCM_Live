package io.github.howard20181.hyperos.fcmlive;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Open-source license list. Deps show version on the right; this project and
 * reference projects use name + license with a vertically centered link icon.
 */
public class LicensesActivity extends Activity {

    private static final String REPO_URL = "https://github.com/iamqwert/HyperOS_FCM_Live";
    private static final String ANDROIDX_URL = "https://github.com/androidx/androidx";
    private static final String AOSP_URL = "https://android.googlesource.com/platform/frameworks/base";
    private static final String OPENJDK_URL = "https://github.com/openjdk/jdk";

    /** name, version ("" if none), license label, project URL. */
    private static final String[][] DEPS = {
            {"AndroidX Annotation", "1.10.0", "Apache License 2.0", ANDROIDX_URL},
            {"AndroidX Collection", "1.0.0", "Apache License 2.0", ANDROIDX_URL},
            {"AndroidX Core", "1.1.0", "Apache License 2.0", ANDROIDX_URL},
            {"AndroidX Interpolator", "1.0.0", "Apache License 2.0", ANDROIDX_URL},
            // Build-only stubs vendored under hiddenapi/stubs; kept for attribution.
            {"AOSP Framework Annotations", "", "Apache License 2.0", AOSP_URL},
            {"Arch Core Common", "2.0.0", "Apache License 2.0", ANDROIDX_URL},
            {"JetBrains Annotations", "13.0", "Apache License 2.0", "https://github.com/JetBrains/java-annotations"},
            {"Kotlin Stdlib", "2.2.10", "Apache License 2.0", "https://github.com/JetBrains/kotlin"},
            {"libxposed API", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/api"},
            {"libxposed Interface", "102.0.0", "Apache License 2.0", "https://github.com/libxposed"},
            {"libxposed Service", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/service"},
            {"Lifecycle Common", "2.0.0", "Apache License 2.0", ANDROIDX_URL},
            {"Lifecycle Runtime", "2.0.0", "Apache License 2.0", ANDROIDX_URL},
            {"OpenJDK Unsafe", "", "GPL-2.0 with Classpath Exception", OPENJDK_URL},
            {"SwipeRefreshLayout", "1.1.0", "Apache License 2.0", ANDROIDX_URL},
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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        LayoutInflater inflater = LayoutInflater.from(this);

        addSectionHeader(list, inflater, getString(R.string.licenses_section_this_app));
        View appRow = inflater.inflate(R.layout.item_license_ref, list, false);
        bindRow(appRow, getString(R.string.app_name), "GPL-3.0");
        appRow.setOnClickListener(v -> openUrl(REPO_URL));
        addRow(list, appRow);

        addSectionHeader(list, inflater, getString(R.string.licenses_section_licenses));
        String licenseHint = getString(R.string.license_view_full_text);
        String[] licenseNames = {
                getString(R.string.license_apache_2),
                getString(R.string.license_gpl_3),
                getString(R.string.license_gpl_2_ce),
        };
        int[] licenseRaw = {
                R.raw.license_apache2,
                R.raw.license_gpl3,
                R.raw.license_gpl2ce,
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
            addRow(list, row);
        }

        addSectionHeader(list, inflater, getString(R.string.licenses_section_deps));
        for (String[] dep : DEPS) {
            View row = inflater.inflate(R.layout.item_license_dep, list, false);
            bindRow(row, dep[0], dep[1], dep[2]);
            final String url = dep[3];
            row.setOnClickListener(v -> openUrl(url));
            addRow(list, row);
        }

        addSectionHeader(list, inflater, getString(R.string.licenses_section_refs));
        for (String[] ref : REFERENCES) {
            View row = inflater.inflate(R.layout.item_license_ref, list, false);
            bindRow(row, ref[0], ref[1]);
            final String url = ref[2];
            row.setOnClickListener(v -> openUrl(url));
            addRow(list, row);
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

    private void addSectionHeader(LinearLayout list, LayoutInflater inflater, String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(getColor(R.color.md_on_surface_variant));
        header.setTextSize(13f);
        header.setPadding(dp(8), dp(16), dp(8), dp(4));
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        list.addView(header);
    }

    private void addRow(LinearLayout list, View row) {
        if (row.getLayoutParams() instanceof LinearLayout.LayoutParams lp) {
            lp.topMargin = dp(8);
        }
        list.addView(row);
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

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(18f);
        titleView.setTextColor(getColor(R.color.md_on_surface));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = pad;
        wrap.addView(titleView, titleLp);

        TextView body = new TextView(this);
        body.setText(readRawText(rawRes));
        body.setTextSize(12f);
        body.setTextColor(getColor(R.color.md_on_surface_variant));
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
        final View topBar = findViewById(R.id.top_bar);
        final View list = findViewById(R.id.licenses_list);
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int barPad = dp(12);
            if (topBar != null) {
                topBar.setPadding(topBar.getPaddingLeft(), top + barPad,
                        topBar.getPaddingRight(), barPad);
            }
            if (list instanceof android.view.ViewGroup) {
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(),
                        list.getPaddingRight(), bottom + dp(16));
                ((android.view.ViewGroup) list).setClipToPadding(false);
            }
            return insets.consumeSystemWindowInsets();
        });
        root.requestApplyInsets();
        int statusBar = statusBarHeight();
        if (topBar != null && statusBar > 0 && topBar.getPaddingTop() <= statusBar) {
            topBar.setPadding(topBar.getPaddingLeft(), statusBar + dp(12),
                    topBar.getPaddingRight(), dp(12));
        }
    }

    private int statusBarHeight() {
        return UiUtils.statusBarHeight(this);
    }

    private int dp(int value) {
        return UiUtils.dp(this, value);
    }
}
