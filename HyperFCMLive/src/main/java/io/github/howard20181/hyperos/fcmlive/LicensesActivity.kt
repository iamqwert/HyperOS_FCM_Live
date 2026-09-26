package io.github.howard20181.hyperos.fcmlive

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.howard20181.hyperos.fcmlive.theme.AppPalette
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Open-source license list. Deps show version on the right; this project and
 * reference projects use name + license with a vertically centered link icon.
 */
class LicensesActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        setContentView(R.layout.activity_licenses)
        applySystemBarInsets()

        val back = findViewById<View>(R.id.btn_back)
        back?.setOnClickListener { finish() }

        val list = findViewById<LinearLayout>(R.id.licenses_list) ?: return
        // Was applied on every inset dispatch; once is enough — the bottom padding
        // itself still tracks the navigation mode.
        list.clipToPadding = false
        val inflater = LayoutInflater.from(this)

        addSectionHeader(list, inflater, getString(R.string.licenses_section_this_app))
        val appRow = inflater.inflate(R.layout.item_license_ref, list, false)
        bindRow(appRow, getString(R.string.app_name), "GPL-3.0")
        appRow.setOnClickListener { openUrl(REPO_URL) }
        addRow(list, appRow, first = true, last = true)

        addSectionHeader(list, inflater, getString(R.string.licenses_section_licenses))
        val licenseHint = getString(R.string.license_view_full_text)
        val licenseNames = arrayOf(
            getString(R.string.license_apache_2),
            getString(R.string.license_gpl_3),
        )
        val licenseRaw = intArrayOf(
            R.raw.license_apache2,
            R.raw.license_gpl3,
        )
        for (i in licenseNames.indices) {
            val row = inflater.inflate(R.layout.item_license_dep, list, false)
            bindRow(row, licenseNames[i], licenseHint)
            val licenseLink = row.findViewById<View>(R.id.dep_link)
            licenseLink?.visibility = View.GONE
            val title = licenseNames[i]
            val rawRes = licenseRaw[i]
            row.setOnClickListener { showLicenseDialog(title, rawRes) }
            addRow(list, row, i == 0, i == licenseNames.size - 1)
        }

        addSectionHeader(list, inflater, getString(R.string.licenses_section_deps))
        for (i in DEPS.indices) {
            val dep = DEPS[i]
            val row = inflater.inflate(R.layout.item_license_dep, list, false)
            bindRow(row, dep[0], dep[1], dep[2])
            val url = dep[3]
            row.setOnClickListener { openUrl(url) }
            addRow(list, row, i == 0, i == DEPS.size - 1)
        }

        addSectionHeader(list, inflater, getString(R.string.licenses_section_refs))
        for (i in REFERENCES.indices) {
            val ref = REFERENCES[i]
            val row = inflater.inflate(R.layout.item_license_ref, list, false)
            bindRow(row, ref[0], ref[1])
            val url = ref[2]
            row.setOnClickListener { openUrl(url) }
            addRow(list, row, i == 0, i == REFERENCES.size - 1)
        }
    }

    /** Two-line row (name + license) for this project / references. */
    private fun bindRow(row: View, name: String, license: String) {
        val nameView = row.findViewById<TextView>(R.id.dep_name)
        val licenseView = row.findViewById<TextView>(R.id.dep_license)
        nameView?.text = name
        licenseView?.text = license
    }

    /** Dep row: name + version on the right, license below. */
    private fun bindRow(row: View, name: String, version: String?, license: String) {
        bindRow(row, name, license)
        val versionView = row.findViewById<TextView>(R.id.dep_version)
        versionView?.text = version ?: ""
    }

    /** Section header styled exactly like the About page groups. */
    private fun addSectionHeader(list: LinearLayout, inflater: LayoutInflater, title: String) {
        val header = TextView(this)
        header.text = title
        header.setTextColor(ThemeEngine.palette(this).primary)
        header.setTextSize(14f)
        header.typeface = Typeface.create("sans-medium", Typeface.NORMAL)
        header.setPadding(dp(8), dp(28), dp(8), dp(12))
        list.addView(header)
    }

    /**
     * Adds one card to a section with the M3 connected-group look used on the
     * About page: 2dp gaps between cards, 16dp outer corners, 4dp inner
     * corners, flat (no elevation), and a ripple masked to the same shape.
     */
    private fun addRow(list: LinearLayout, row: View, first: Boolean, last: Boolean) {
        row.background = groupRowBackground(first, last)
        val lp = row.layoutParams as LinearLayout.LayoutParams
        lp.topMargin = if (first) 0 else dp(2)
        list.addView(row)
    }

    /** Position-aware rounded ripple matching the About page card groups. */
    private fun groupRowBackground(first: Boolean, last: Boolean): android.graphics.drawable.Drawable {
        val palette = ThemeEngine.palette(this)
        val top = if (first) dp(16).toFloat() else dp(4).toFloat()
        val bottom = if (last) dp(16).toFloat() else dp(4).toFloat()
        val radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        val content = GradientDrawable()
        content.shape = GradientDrawable.RECTANGLE
        content.cornerRadii = radii
        content.setColor(palette.card)
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.RECTANGLE
        mask.cornerRadii = radii
        mask.setColor(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(palette.ripple), content, mask)
    }

    private fun openUrl(url: String) {
        UiUtils.openUrl(this, url)
    }

    /** Full license text in a scrollable in-app dialog (no browser). */
    private fun showLicenseDialog(title: String, rawRes: Int) {
        val pad = dp(24)
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(pad, pad, pad, pad)

        val palette = ThemeEngine.palette(this)
        val titleView = TextView(this)
        titleView.text = title
        titleView.setTextSize(18f)
        titleView.setTextColor(palette.onSurface)
        val titleLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        titleLp.bottomMargin = pad
        wrap.addView(titleView, titleLp)

        val body = TextView(this)
        body.text = readRawText(rawRes)
        body.setTextSize(12f)
        body.setTextColor(palette.onSurfaceVariant)
        body.setTextIsSelectable(true)
        body.setLineSpacing(0f, 1.15f)

        val scroll = ScrollView(this)
        scroll.addView(body)
        wrap.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(this)
            .setView(wrap)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun readRawText(rawRes: Int): String {
        return try {
            resources.openRawResource(rawRes).use { input: InputStream ->
                val buf = ByteArray(input.available())
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n, StandardCharsets.UTF_8) else ""
            }
        } catch (t: Throwable) {
            ""
        }
    }

    /** Same top-bar inset as MainActivity; list clears the gesture nav bar. */
    private fun applySystemBarInsets() {
        UiUtils.applyBarInsets(
            this, findViewById(R.id.top_bar),
            findViewById(R.id.licenses_list), 16
        )
    }

    private fun dp(value: Int): Int = UiUtils.dp(this, value)

    companion object {
        private const val REPO_URL = "https://github.com/iamqwert/HyperOS_FCM_Live"
        private const val ANDROIDX_URL = "https://github.com/androidx/androidx"
        private const val AOSP_URL = "https://android.googlesource.com/platform/frameworks/base"
        private const val JSPECIFY_URL = "https://github.com/jspecify/jspecify"
        private const val MCU_URL =
            "https://github.com/material-foundation/material-color-utilities"

        /** name, version ("" if none), license label, project URL. */
        private val DEPS = arrayOf(
            arrayOf("AndroidX Annotation", "1.10.0", "Apache License 2.0", ANDROIDX_URL),
            arrayOf("AndroidX Arch Core", "2.0.0", "Apache License 2.0", ANDROIDX_URL),
            arrayOf("AndroidX Collection", "1.0.0", "Apache License 2.0", ANDROIDX_URL),
            arrayOf("AndroidX Core", "1.1.0", "Apache License 2.0", ANDROIDX_URL),
            arrayOf("AndroidX Interpolator", "1.0.0", "Apache License 2.0", ANDROIDX_URL),
            // Build-only stubs vendored under hiddenapi/stubs; kept for attribution.
            arrayOf("AOSP Framework Annotations", "", "Apache License 2.0", AOSP_URL),
            arrayOf(
                "JetBrains Annotations",
                "13.0",
                "Apache License 2.0",
                "https://github.com/JetBrains/java-annotations"
            ),
            arrayOf("JSpecify", "1.0.0", "Apache License 2.0", JSPECIFY_URL),
            arrayOf("Kotlin Stdlib", "2.2.10", "Apache License 2.0", "https://github.com/JetBrains/kotlin"),
            arrayOf("libxposed API", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/api"),
            arrayOf("libxposed Interface", "102.0.0", "Apache License 2.0", "https://github.com/libxposed"),
            arrayOf("libxposed Service", "102.0.0", "Apache License 2.0", "https://github.com/libxposed/service"),
            arrayOf("Lifecycle Common", "2.0.0", "Apache License 2.0", ANDROIDX_URL),
            arrayOf("Lifecycle Runtime", "2.0.0", "Apache License 2.0", ANDROIDX_URL),
            // Vendored source under mcu/ (no Gradle artifact) — listed for attribution.
            arrayOf("Material Color Utilities", "", "Apache License 2.0", MCU_URL),
            arrayOf("SwipeRefreshLayout", "1.2.0", "Apache License 2.0", ANDROIDX_URL),
            arrayOf("VersionedParcelable", "1.1.0", "Apache License 2.0", ANDROIDX_URL),
        )

        /** name, license label, project URL. */
        private val REFERENCES = arrayOf(
            arrayOf("250king/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/250king/HyperOS_FCM_Live"),
            arrayOf("billtv/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/billtv/HyperOS_FCM_Live"),
            arrayOf("HappyMax0/FCMPushViewer", "Apache License 2.0", "https://github.com/HappyMax0/FCMPushViewer"),
            arrayOf("Howard20181/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/Howard20181/HyperOS_FCM_Live"),
            arrayOf("zuohl/HyperOS_FCM_Live", "GPL-3.0", "https://github.com/zuohl/HyperOS_FCM_Live"),
        )
    }
}
