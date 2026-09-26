package io.github.howard20181.hyperos.fcmlive

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.howard20181.hyperos.fcmlive.mcu.Hct
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme
import io.github.howard20181.hyperos.fcmlive.theme.AppPalette
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemePrefs
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.util.Locale

/** About: source, licenses, allowlist backup, update check with red badge. */
class AboutActivity : Activity() {

    private val eggTaps = ArrayList<Long>(EGG_TAP_COUNT)
    private var lastEggAtMs: Long = 0
    private var hideIconState: TextView? = null
    private var hideIconSwitch: MdSwitch? = null
    private var dynamicColorState: TextView? = null
    private var dynamicColorSwitch: MdSwitch? = null
    private var dynamicColorSwatches: ViewGroup? = null
    private var themeModeValue: TextView? = null
    private var paletteStyleValue: TextView? = null
    private var colorSpecValue: TextView? = null
    private var languageValue: TextView? = null

    /** The open menu: a full-screen overlay inside this window, or null. */
    private var menuOverlay: View? = null
    private var menuPanel: View? = null
    private var menuAbove = false
    private var menuRtl = false
    private var menuBackCallback: OnBackInvokedCallback? = null
    private var activeTooltip: PopupWindow? = null

    /**
     * The window's safe area, kept up to date by [applySystemBarInsets].
     * The menu overlay is positioned by hand in screen coordinates, so it has
     * to know where the status bar / gesture indicator actually are.
     */
    private var insetTop = 0
    private var insetBottom = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        setContentView(R.layout.activity_about)
        applySystemBarInsets()

        val back = findViewById<View>(R.id.btn_back)
        if (back != null) {
            back.setOnClickListener { finish() }
            attachTip(back, R.string.back)
        }

        val help = findViewById<View>(R.id.btn_help)
        if (help != null) {
            help.setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }
            attachTip(help, R.string.help)
        }

        bindRow(R.id.row_view_source) { openUrl(REPO_URL) }
        bindRow(R.id.row_open_source_licenses) {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
        bindRow(R.id.row_export_allowlist, this::exportAllowlist)
        bindRow(R.id.row_import_allowlist, this::importAllowlist)
        bindRow(R.id.row_check_update, this::checkForUpdates)

        val versionRow = findViewById<View>(R.id.row_current_version)
        if (versionRow != null) {
            versionRow.setOnClickListener { onVersionRowTapped() }
            // Long press must stay silent as well: consume it, do nothing.
            versionRow.setOnLongClickListener { true }
        }

        hideIconState = findViewById(R.id.about_hide_icon_state)
        themeModeValue = findViewById(R.id.about_theme_mode_value)
        paletteStyleValue = findViewById(R.id.about_palette_style_value)
        colorSpecValue = findViewById(R.id.about_color_spec_value)
        languageValue = findViewById(R.id.about_language_value)
        bindHideIconRow()
        bindDynamicColorRow()
        bindLanguageRow()
        refreshHideIconState()
        refreshAppearanceState()

        // The value label keeps no listener of its own: it stays non-clickable
        // so a tap falls through to the row and plays the row's own ripple,
        // exactly like a tap anywhere else on the card.
        bindPopupRow(
            R.id.row_theme_mode, R.string.theme_mode, R.array.theme_mode_entries,
            { ThemePrefs.themeMode(this) },
            { index -> ThemePrefs.setThemeMode(this, index) }
        )
        bindPopupRow(
            R.id.row_palette_style, R.string.palette_style,
            R.array.palette_style_entries,
            { ThemePrefs.paletteStyle(this).ordinal },
            { index -> ThemePrefs.setPaletteStyle(this, variantAt(index)) }
        )
        bindPopupRow(
            R.id.row_color_spec, R.string.color_spec, R.array.color_spec_entries,
            { ThemePrefs.specVersion(this) },
            { index -> ThemePrefs.setSpecVersion(this, index) }
        )

        // Version name (version code), shown under the "Current version" row.
        val version = findViewById<TextView>(R.id.about_version)
        version?.text = moduleVersion()

        showUpdateBadge(UpdateChecker.isUpdateAvailable(this))
    }

    private fun checkForUpdates() {
        toastShort(R.string.update_checking)
        UpdateChecker.checkAsync(this, object : UpdateChecker.Callback {
            override fun onResult(
                updateAvailable: Boolean,
                latestVersion: String,
                downloadUrl: String
            ) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    showUpdateBadge(updateAvailable)
                    if (!updateAvailable) {
                        Toast.makeText(
                            this@AboutActivity,
                            R.string.update_none, Toast.LENGTH_SHORT
                        ).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this@AboutActivity)
                        .setMessage(getString(R.string.update_found, latestVersion))
                        .setPositiveButton(R.string.update_open) { _, _ ->
                            UpdateChecker.clearBadge(this@AboutActivity)
                            showUpdateBadge(false)
                            openUrl(downloadUrl)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            override fun onError() {
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    Toast.makeText(
                        this@AboutActivity,
                        R.string.update_error, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    /**
     * The version row is inert: no clipboard write, no toast. The single output
     * is the joke toast after a rapid burst of taps, and even then it stays
     * quiet for a while so it cannot be spammed.
     */
    private fun onVersionRowTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEggAtMs < EGG_COOLDOWN_MS) {
            // Still inside the cooldown: swallow the tap, no feedback at all.
            eggTaps.clear()
            return
        }
        eggTaps.removeAll { tap -> now - tap > EGG_WINDOW_MS }
        eggTaps.add(now)
        if (eggTaps.size >= EGG_TAP_COUNT) {
            eggTaps.clear()
            lastEggAtMs = now
            toastShort(R.string.no_developer_options)
        }
    }

    /** The whole row is the touch target; the switch itself stays authoritative. */
    private fun bindHideIconRow() {
        hideIconSwitch = findViewById(R.id.hide_icon_switch)
        val hideRow = findViewById<View>(R.id.row_hide_icon)
        hideRow?.setOnClickListener(rowClick {
            hideIconSwitch?.let { it.setCheckedImmediate(!it.isChecked) }
        })
        val switchView = hideIconSwitch ?: return
        val palette = ThemeEngine.palette(this)
        switchView.applyPalette(palette)
        // PackageManager is the source of truth. Bind the value with the
        // non-animated setter (and before the listener attaches) so restoring
        // it never fires a spurious write, and so a screen rebuild cannot
        // leave a half-slid thumb behind: a slide we start here would be
        // aborted by the very next frame.
        switchView.setCheckedImmediate(LauncherIcon.isHidden(this))
        switchView.setOnCheckedChangeListener { _, checked -> applyLauncherIcon(checked) }
    }

    /**
     * Dynamic color: the switch decides between the wallpaper seed and a
     * hand-picked one. While it is off, a row of preset seed swatches appears
     * inside the card; tapping one applies it as the scheme seed immediately.
     */
    private fun bindDynamicColorRow() {
        dynamicColorSwitch = findViewById(R.id.dynamic_color_switch)
        dynamicColorState = findViewById(R.id.about_dynamic_color_state)
        dynamicColorSwatches = findViewById(R.id.dynamic_color_swatches)
        val row = findViewById<View>(R.id.row_dynamic_color)
        row?.setOnClickListener(rowClick {
            // Same reasoning as the listener below: the state change
            // rebuilds the screen, so jump straight to the new state.
            dynamicColorSwitch?.setCheckedImmediate(!(dynamicColorSwitch?.isChecked ?: false))
        })
        val switchView = dynamicColorSwitch ?: return
        switchView.applyPalette(ThemeEngine.palette(this))
        val dynamic = ThemePrefs.dynamicColor(this)
        // Restore before the listener attaches, so no spurious write fires —
        // and without a slide, because this view is rebuilt on every theme
        // change (see setCheckedImmediate).
        switchView.setCheckedImmediate(dynamic)
        switchView.setOnCheckedChangeListener { _, checked ->
            ThemePrefs.setDynamicColor(this, checked)
            // Flipping this switch rebuilds the whole screen in the same frame
            // (the palette is resolved while the layout is inflated). A thumb
            // slide therefore can never play out: snap it to the end position
            // and let the rebuild paint the final state. Sitting on a
            // half-finished animation is what the user sees as a twitch.
            switchView.setCheckedImmediate(checked)
            applyAppearanceChange()
        }
        dynamicColorState?.setText(
            if (dynamic) R.string.about_sub_dynamic_color_on
            else R.string.about_sub_dynamic_color_off
        )
        buildSeedSwatches(dynamic)
    }

    /** Fills the swatch grid; hidden entirely while dynamic color is on. */
    private fun buildSeedSwatches(dynamic: Boolean) {
        val swatches = dynamicColorSwatches ?: return
        swatches.visibility = if (dynamic) View.GONE else View.VISIBLE
        swatches.removeAllViews()
        if (dynamic) {
            return
        }
        val palette = ThemeEngine.palette(this)
        val selected = ThemePrefs.seedColor(this)
        // The grid is MATCH_PARENT, so its width depends on the page padding, the
        // card padding and its own indent: read all three from the views rather
        // than hard-coding them, otherwise a padding tweak elsewhere would
        // silently push the sixth swatch onto a row of its own.
        val card = findViewById<View>(R.id.row_dynamic_color)
        val content = findViewById<View>(R.id.about_content)
        val gridLp = swatches.layoutParams as ViewGroup.MarginLayoutParams
        val pagePad = content?.let { it.paddingStart + it.paddingEnd } ?: 0
        val cardPad = card?.let { it.paddingStart + it.paddingEnd } ?: 0
        val available = resources.displayMetrics.widthPixels -
            pagePad - cardPad - gridLp.marginStart - gridLp.marginEnd
        // Every swatch gets a square cell; the touch target is the cell, so it
        // stays at or above 48dp even when a narrow screen shrinks the dot.
        val cell = Math.max(dp(36), available / SEED_COLUMNS)
        val dot = Math.min(dp(SEED_DOT_MAX_DP), cell - dp(SEED_GAP_DP))
        for (color in SEED_COLORS) {
            val swatch = View(this)
            val lp = GridLayout.LayoutParams()
            lp.width = cell
            lp.height = cell
            swatch.layoutParams = lp
            swatch.background = SeedSwatchDrawable(
                color, color == selected, palette.primary, dp(2).toFloat(), dot
            )
            swatch.isClickable = true
            swatch.setOnClickListener {
                ThemePrefs.setSeedColor(this, color)
                applyAppearanceChange()
            }
            swatches.addView(swatch)
        }
    }

    /** Desktop icon: apply the requested state and confirm it with a toast. */
    private fun applyLauncherIcon(hidden: Boolean) {
        LauncherIcon.setHidden(this, hidden)
        Toast.makeText(
            this,
            if (hidden) R.string.hide_icon_toast else R.string.show_icon_toast,
            Toast.LENGTH_LONG
        ).show()
        refreshHideIconState()
    }

    /** Wires one settings row: the standard tap feedback, then the action. */
    private fun bindRow(rowId: Int, action: Runnable) {
        val row = findViewById<View>(rowId) ?: return
        row.setOnClickListener(rowClick(action))
    }

    /** Wires a row whose tap opens one of the appearance popup menus. */
    private fun bindPopupRow(
        rowId: Int, titleRes: Int, entriesRes: Int,
        currentIndex: () -> Int, onPick: (Int) -> Unit
    ) {
        val row = findViewById<View>(rowId) ?: return
        row.setOnClickListener(rowClick {
            showPopupMenu(row, titleRes, entriesRes, currentIndex(), onPick)
        })
    }

    /**
     * Language row: an in-app override of the device language. The menu lists
     * only the two languages the app ships, and picking one re-runs the screen
     * through [ThemeSupport.attach] — the same configuration rewrite that
     * forces light/dark, so a single recreate applies both.
     */
    private fun bindLanguageRow() {
        val row = findViewById<View>(R.id.row_language) ?: return
        row.setOnClickListener(rowClick {
            showPopupMenu(
                row,
                R.string.language, R.array.language_entries,
                effectiveLanguage(),
                { index -> ThemePrefs.setLanguage(this, index) }
            )
        })
    }

    /**
     * Which entry the Language row shows: the pinned choice, or — while the app
     * still follows the device — whichever of the two is in effect right now, so
     * the row never sits empty.
     */
    private fun effectiveLanguage(): Int {
        val pinned = ThemePrefs.language(this)
        if (pinned >= 0) {
            return pinned
        }
        val current = resources.configuration.locales[0]
        return if (current.language == "zh") 0 else 1
    }

    private fun applyAppearanceChange() {
        ThemeEngine.invalidate()
        recreate()
    }

    private fun refreshAppearanceState() {
        val modes = resources.getStringArray(R.array.theme_mode_entries)
        themeModeValue?.text = modes[ThemePrefs.themeMode(this)]
        val styles = resources.getStringArray(R.array.palette_style_entries)
        paletteStyleValue?.text = styles[ThemePrefs.paletteStyle(this).ordinal]
        val specs = resources.getStringArray(R.array.color_spec_entries)
        colorSpecValue?.text = specs[ThemePrefs.specVersion(this)]
        val languages = resources.getStringArray(R.array.language_entries)
        languageValue?.text = languages[effectiveLanguage()]
    }

    private fun refreshHideIconState() {
        hideIconState?.setText(
            if (LauncherIcon.isHidden(this)) R.string.about_sub_hide_icon_on
            else R.string.about_sub_hide_icon_off
        )
    }

    private fun moduleVersion(): String {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pi.versionName + " (" + pi.longVersionCode + ")"
        } catch (t: Throwable) {
            "unknown"
        }
    }

    private fun currentAllowlist(): Set<String> {
        val allow = Prefs.readAllowlist(Prefs.remote())
        return if (allow.isEmpty()) Prefs.readLocalAllowlist(this) else allow
    }

    private fun exportAllowlist() {
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "text/plain"
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            intent.putExtra(Intent.EXTRA_TITLE, "fcmlive-allowlist.txt")
            startActivityForResult(intent, REQ_EXPORT)
        } catch (t: Throwable) {
            toastShort(R.string.allowlist_export_failed)
        }
    }

    private fun importAllowlist() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "text/plain"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(intent, REQ_IMPORT)
        } catch (t: Throwable) {
            toastShort(R.string.allowlist_import_failed)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            return
        }
        val uri = data.data ?: return
        when (requestCode) {
            REQ_EXPORT -> writeAllowlistTo(uri)
            REQ_IMPORT -> readAllowlistFrom(uri)
        }
    }

    private fun writeAllowlistTo(uri: Uri) {
        val sorted = currentAllowlist().toMutableList()
        java.util.Collections.sort(sorted)

        // ACTION_CREATE_DOCUMENT returns an externally supplied URI. Require a
        // content URI and an explicit write grant before resolving it. This
        // prevents an arbitrary caller/provider URI from being used as a
        // ContentResolver target.
        if (!isGrantedContentUri(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            toastShort(R.string.allowlist_export_failed)
            return
        }
        // The authority decides which provider answers; the *path* is what the
        // provider itself interprets, and a provider is free to mean
        // "/data/data/<app>/..." by it. Resolving such a URI is what turns a
        // document picker into a way to overwrite this app's own private files.
        // Normalising first is what makes the prefix test mean anything:
        // "/safe/../../data/data/<app>/x" names the /data path it normalises to.
        val path = uri.path
        if (path == null) {
            toastShort(R.string.allowlist_export_failed)
            return
        }
        val normalized = FileSystems.getDefault().getPath(path).normalize()
        if (normalized.startsWith("/data")) {
            toastShort(R.string.allowlist_export_failed)
            return
        }
        val resolved = normalized.toString()
        // A leftover ".." means the path still escapes upwards: normalize()
        // keeps it when there is nothing above it to collapse into.
        if (resolved.contains("..") ||
            resolved.startsWith("/system") ||
            resolved.startsWith("/vendor") ||
            resolved.startsWith("/proc") ||
            resolved.startsWith("/dev")
        ) {
            toastShort(R.string.allowlist_export_failed)
            return
        }

        try {
            val out: OutputStream = contentResolver.openOutputStream(uri)
                ?: throw IOException("null stream")
            out.use { stream ->
                val sb = StringBuilder()
                for (pkg in sorted) {
                    sb.append(pkg).append('\n')
                }
                stream.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                Toast.makeText(
                    this, getString(R.string.allowlist_export_done, sorted.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (t: Throwable) {
            toastShort(R.string.allowlist_export_failed)
        }
    }

    private fun readAllowlistFrom(uri: Uri) {
        val allow = HashSet<String>()

        // ACTION_OPEN_DOCUMENT returns an externally supplied URI. Require a
        // content URI and an explicit read grant before resolving it. This is
        // the security boundary for the ContentResolver operation.
        if (!isGrantedContentUri(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            toastShort(R.string.allowlist_import_failed)
            return
        }
        // Same path check as the export side: the provider interprets the path,
        // so it is normalised and the private roots are refused before a stream
        // is opened — otherwise a provider can answer with this app's own files.
        val path = uri.path
        if (path == null) {
            toastShort(R.string.allowlist_import_failed)
            return
        }
        val normalized = FileSystems.getDefault().getPath(path).normalize()
        if (normalized.startsWith("/data")) {
            toastShort(R.string.allowlist_import_failed)
            return
        }
        val resolved = normalized.toString()
        if (resolved.contains("..") ||
            resolved.startsWith("/system") ||
            resolved.startsWith("/vendor") ||
            resolved.startsWith("/proc") ||
            resolved.startsWith("/dev")
        ) {
            toastShort(R.string.allowlist_import_failed)
            return
        }

        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("null stream")
            input.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val pkg = line.trim()
                        if (pkg.isNotEmpty() && !pkg.startsWith("#")) {
                            allow.add(pkg)
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (t: Throwable) {
            toastShort(R.string.allowlist_import_failed)
            return
        }
        if (allow.isEmpty()) {
            toastShort(R.string.allowlist_import_empty)
            return
        }
        Prefs.writeAllowlist(this, Prefs.remote(), allow)
        Toast.makeText(
            this, getString(R.string.allowlist_import_done, allow.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Accept only content:// URIs for which this process currently has the
     * requested explicit URI permission. The permission check is performed
     * against this process UID/PID, so an arbitrary URI supplied by another
     * component cannot be resolved unless Android has actually granted access.
     */
    private fun isGrantedContentUri(uri: Uri?, grantFlag: Int): Boolean {
        if (uri == null || uri.scheme != "content" ||
            uri.authority.isNullOrEmpty()
        ) {
            return false
        }
        return checkUriPermission(uri, Process.myPid(), Process.myUid(), grantFlag) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun toastShort(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /** Offset tooltip below the anchor so HyperOS does not cover the icon. */
    private fun attachTip(view: View?, textRes: Int) {
        if (view == null) {
            return
        }
        val tip: CharSequence = getText(textRes)
        view.contentDescription = tip
        view.tooltipText = null
        view.isLongClickable = true
        view.setOnLongClickListener { v ->
            showAnchorTooltip(v, tip)
            true
        }
    }

    private fun showAnchorTooltip(anchor: View, text: CharSequence) {
        dismissActiveTooltip()
        val tipView = TextView(this)
        val palette = ThemeEngine.palette(this)
        tipView.text = text
        tipView.setTextColor(palette.tooltipText)
        tipView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tipView.background = ThemeSupport.cardBackground(this, palette.tooltipBg, 4f)
        val padH = dp(12)
        val padV = dp(6)
        tipView.setPadding(padH, padV, padH, padV)
        tipView.setSingleLine(true)
        tipView.measure(
            View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.AT_MOST)
        )

        val popup = PopupWindow(
            tipView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        popup.isOutsideTouchable = true
        popup.isFocusable = false
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.setOnDismissListener {
            if (activeTooltip === popup) {
                activeTooltip = null
            }
        }

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val gap = dp(8)
        val x = loc[0] + (anchor.width - tipView.measuredWidth) / 2
        val y = loc[1] + anchor.height + gap
        val decor = window?.decorView
        val decorLoc = IntArray(2)
        decor?.getLocationOnScreen(decorLoc)
        try {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x - decorLoc[0], y - decorLoc[1])
            activeTooltip = popup
        } catch (ignored: Throwable) {
        }
    }

    private fun dismissActiveTooltip() {
        val popup = activeTooltip
        activeTooltip = null
        if (popup != null) {
            try {
                popup.dismiss()
            } catch (ignored: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        dismissActiveTooltip()
        // Drop any open menu (and its back callback) with the window.
        dismissMenu()
        super.onDestroy()
    }

    private fun showUpdateBadge(show: Boolean) {
        val badge = findViewById<View>(R.id.badge_update)
        badge?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun openUrl(url: String) {
        UiUtils.openUrl(this, url)
    }

    /**
     * Edge-to-edge insets for the About screen.
     *
     * Android 15 (API 35) forces every window to draw behind the system
     * bars, so the page owns the full screen: the root background reaches the
     * bottom edge — over the gesture home indicator — instead of stopping
     * above it, which is what left the blank, disconnected strip at the bottom.
     *
     * The bottom safe area is therefore applied to the *scrollable
     * content* rather than to the window, the Android equivalent of CSS
     * `env(safe-area-inset-bottom)`: at the end of the scroll the last
     * card comes to rest clear of the indicator and stays tappable, while the
     * background behind it is still full-bleed.
     */
    private fun applySystemBarInsets() {
        // The insets are also remembered: the menu overlay positions itself in
        // screen coordinates and must stay inside the safe area.
        UiUtils.applyBarInsets(
            this, findViewById(R.id.top_bar),
            findViewById(R.id.about_content), 16,
            { top, bottom ->
                insetTop = top
                insetBottom = bottom
            }
        )
        // Fallback for ROMs that never dispatch insets to the listener above.
        val statusBar = UiUtils.statusBarHeight(this)
        if (statusBar > 0 && insetTop <= 0) {
            insetTop = statusBar
        }
    }

    private fun dp(value: Int): Int = UiUtils.dp(this, value)

    /**
     * Material 3 dropdown menu for one card: anchored under the card, right
     * aligned with its edge, as wide as its widest option, wearing the page
     * background and highlighting the current option with a primary container.
     *
     * The menu is a plain view inside the Activity window rather than a
     * popup window, for two reasons that both showed up on device:
     * - **Origin.** A window animation transforms the popup's own
     *   surface, so the transform origin is resolved against the window
     *   (not the panel) and the ROM layers its own panel animation on top —
     *   which is why the menu appeared to grow out of its middle instead of
     *   the corner nearest the card.
     * - **Overshoot.** A window surface clips whatever leaves its
     *   bounds, and that surface is exactly the size of the menu, so an
     *   ease-out-back overshoot would be sliced off.
     *
     * As an in-window view the panel scales around a real corner, nothing else
     * animates it, and the full-screen overlay it sits in gives the overshoot
     * somewhere to go.
     */
    private fun showPopupMenu(
        anchor: View, titleRes: Int, entriesRes: Int, current: Int,
        onPick: (Int) -> Unit
    ) {
        dismissMenu()
        val items = resources.getStringArray(entriesRes)
        val checked = clamp(current, 0, items.size - 1)
        val palette = ThemeEngine.palette(this)

        // Content-adaptive width: measure the widest label, add the horizontal
        // padding a row needs, then a fixed slack so labels get right-side
        // breathing room. Rows still span the full panel, so the ripple and
        // the rounded clipping cover the whitespace exactly the same.
        val measure = android.text.TextPaint()
        measure.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            MENU_ITEM_TEXT_SP.toFloat(), resources.displayMetrics
        )
        var widest = 0f
        for (item in items) {
            widest = Math.max(widest, measure.measureText(item))
        }
        val panelW = Math.ceil(widest.toDouble()).toInt() +
            dp(MENU_ITEM_PAD_H_DP) * 2 + dp(MENU_OUTER_PAD_DP) * 2 +
            dp(MENU_EXTRA_WIDTH_DP)

        // Geometry up front: rows have fixed heights, so the panel height is
        // known before it is shown. The panel always keeps its natural size —
        // the nine palette styles are the same size as the four theme modes —
        // and the room for that is found by where the panel is placed rather
        // than by shrinking it:
        //   1. below the card, when it fits there,
        //   2. above the card, when it fits there instead,
        //   3. otherwise as low as the screen allows, which for a menu this
        //      tall means sitting over the card it belongs to. Covering the
        //      card's current-value text is a fair price for showing every
        //      option at full size; hiding options behind a scroll is not.
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val anchorGap = dp(MENU_ANCHOR_GAP_DP)
        val screenH = resources.displayMetrics.heightPixels
        val outer = dp(MENU_OUTER_PAD_DP)
        val rowH = dp(MENU_ITEM_HEIGHT_DP)
        val itemGap = dp(MENU_ITEM_GAP_DP)
        val backCurve = easeOutBack(MENU_TENSION_FULL)
        val naturalH = naturalHeight(rowH, itemGap, outer, items.size)
        val topLimit = insetTop + dp(MENU_SCREEN_PAD_DP)
        val bottomLimit = screenH - insetBottom - dp(MENU_SCREEN_PAD_DP)
        // Only a menu that cannot fit the screen at all is capped, and then the
        // scroll inside the panel takes over: no menus ship like that today.
        val panelH = Math.min(naturalH, Math.max(rowH, bottomLimit - topLimit))
        val below = anchorLoc[1] + anchor.height + anchorGap
        val above = anchorLoc[1] - anchorGap - panelH
        val panelTop = when {
            below + panelH <= bottomLimit -> below
            above >= topLimit -> above
            else -> clamp(below, topLimit, Math.max(topLimit, bottomLimit - panelH))
        }
        // The panel unfolds from whichever of its corners is nearest the card:
        // its bottom corner when the panel sits above the card's centre, its top
        // corner when the panel hangs below it.
        val opensAbove = panelTop + panelH / 2 < anchorLoc[1] + anchor.height / 2

        // An appearance change rebuilds this Activity (ThemeEngine.invalidate
        // + recreate). Doing that while the menu is still on screen leaves a
        // stale frame of the old menu over the new content, so the rebuild
        // waits until the menu has folded away.
        val rebuildPending = booleanArrayOf(false)
        val rebuildIfPending = Runnable {
            if (rebuildPending[0]) {
                rebuildPending[0] = false
                applyAppearanceChange()
            }
        }

        val rows = LinearLayout(this)
        rows.orientation = LinearLayout.VERTICAL
        for (i in items.indices) {
            val position = i
            rows.addView(
                buildMenuRow(
                    items[i], i == checked, i == 0, i == items.size - 1,
                    rowH, itemGap, palette,
                    Runnable {
                        onPick(position)
                        rebuildPending[0] = true
                        dismissMenu()
                        anchor.postDelayed(
                            rebuildIfPending,
                            MENU_EXIT_MS + POPUP_DISMISS_GRACE_MS
                        )
                    })
            )
        }

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(
            rows,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val panel = FrameLayout(this)
        panel.clipChildren = false
        // The panel is the same flat surface as the page behind it — no outline
        // — so a shadow is what separates the menu from the page: a soft ring
        // all around it, at the M3 menu elevation (level 2 = 3dp).
        //
        // The elevation shadow is generated from the view's outline, so the
        // outline is declared explicitly as the plain rounded rectangle the
        // panel actually is. Leaving it to the background is what makes a
        // shadow read as a smudge at the corners instead of an even halo:
        // any background whose outline is not a clean round rect (a ripple or
        // layer list, per-corner radii) yields a path silhouette with uneven
        // blur, and the arcs are where that shows first.
        panel.background = ThemeSupport.cardBackground(
            this, palette.pageBg,
            MENU_CONTAINER_RADIUS_DP.toFloat()
        )
        panel.elevation = dp(MENU_ELEVATION_DP).toFloat()
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height,
                    dp(MENU_CONTAINER_RADIUS_DP).toFloat()
                )
            }
        }
        panel.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelH)
        )

        val decor = window?.decorView
        val decorLoc = IntArray(2)
        decor?.getLocationOnScreen(decorLoc)
        // Pull the panel in from the card's edge: flush against it, the menu
        // ends up brushing the screen edge, which looks cramped.
        val inset = dp(MENU_EDGE_INSET_DP)
        val panelStart = if (rtl) {
            anchorLoc[0] + inset
        } else {
            anchorLoc[0] + anchor.width - panelW - inset
        }

        val panelLp = FrameLayout.LayoutParams(panelW, panelH)
        panelLp.leftMargin = panelStart - decorLoc[0]
        panelLp.topMargin = panelTop - decorLoc[1]

        val overlay = FrameLayout(this)
        overlay.clipChildren = false
        // A tap anywhere outside the panel dismisses, exactly like an outside
        // tap on a popup window would.
        overlay.isClickable = true
        overlay.setOnClickListener { dismissMenu() }
        overlay.addView(panel, panelLp)

        val content = findViewById<ViewGroup>(android.R.id.content)
        content.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        menuOverlay = overlay
        menuPanel = panel
        menuAbove = opensAbove
        menuRtl = rtl
        // Back has to close the menu before it can leave the screen.
        val backCallback = OnBackInvokedCallback { dismissMenu() }
        menuBackCallback = backCallback
        try {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback
            )
        } catch (ignored: Throwable) {
            // No dispatcher on this build: the overlay still dismisses on tap.
        }
        playMenuEnter(panel, opensAbove, rtl, backCurve)
    }

    /**
     * Close the open menu, folding the panel back into the corner it grew from
     * before taking the overlay down.
     */
    private fun dismissMenu() {
        val overlay = menuOverlay
        val panel = menuPanel
        val opensAbove = menuAbove
        val rtl = menuRtl
        menuOverlay = null
        menuPanel = null
        if (overlay == null) {
            return
        }
        menuBackCallback?.let { cb ->
            try {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            } catch (ignored: Throwable) {
            }
        }
        menuBackCallback = null
        if (panel == null) {
            detachMenuOverlay(overlay)
            return
        }
        val out = AnimationSet(false)
        val shrink = ScaleAnimation(
            1f, MENU_ENTER_FROM, 1f, MENU_ENTER_FROM,
            Animation.RELATIVE_TO_SELF, if (rtl) 0f else 1f,
            Animation.RELATIVE_TO_SELF, if (opensAbove) 1f else 0f
        )
        shrink.duration = MENU_EXIT_MS
        shrink.interpolator = AnimationUtils.loadInterpolator(
            this,
            R.interpolator.m3_emphasized_accelerate
        )
        val fade = AlphaAnimation(1f, 0f)
        fade.duration = MENU_EXIT_MS
        fade.interpolator = shrink.interpolator
        out.addAnimation(shrink)
        out.addAnimation(fade)
        out.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}
            override fun onAnimationRepeat(animation: Animation) {}
            override fun onAnimationEnd(animation: Animation) {
                detachMenuOverlay(overlay)
            }
        })
        panel.startAnimation(out)
        // Safety net: if the animation never reports its end (view detached,
        // animator duration scale set to 0), still take the overlay down.
        panel.postDelayed(
            { detachMenuOverlay(overlay) },
            MENU_EXIT_MS + POPUP_DISMISS_GRACE_MS
        )
    }

    private fun detachMenuOverlay(overlay: View?) {
        if (overlay == null || overlay.parent == null) {
            return
        }
        (overlay.parent as ViewGroup).removeView(overlay)
    }

    /**
     * M3 menu entrance: fade in while the panel grows out of the corner nearest
     * the card — its bottom-right while it hangs below the card, top-right when
     * it had to flip above it. `curve` is the ease-out-back that gives
     * the panel its single overshoot, i.e. the spring feel.
     */
    private fun playMenuEnter(
        panel: View, opensAbove: Boolean, rtl: Boolean, curve: Interpolator
    ) {
        val set = AnimationSet(false)
        val grow = ScaleAnimation(
            MENU_ENTER_FROM, 1f, MENU_ENTER_FROM, 1f,
            Animation.RELATIVE_TO_SELF, if (rtl) 0f else 1f,
            Animation.RELATIVE_TO_SELF, if (opensAbove) 1f else 0f
        )
        grow.duration = MENU_ENTER_MS
        grow.interpolator = curve
        val fade = AlphaAnimation(0f, 1f)
        fade.duration = MENU_ENTER_MS
        fade.interpolator = curve
        set.addAnimation(grow)
        set.addAnimation(fade)
        panel.startAnimation(set)
    }

    /**
     * One dropdown option: a rounded row inset from the rounded menu container.
     * The ripple mask is the row shape itself, so the press feedback is clipped
     * to that rounded rectangle instead of a full-width rectangle. The label
     * is a single text starting at the common left edge; the panel's extra
     * width leaves a calm whitespace on the right.
     *
     * `rowH` and `itemGap` are the shared menu metrics, so a
     * nine-option menu and a two-option one are built from the same rows.
     */
    private fun buildMenuRow(
        text: String, selected: Boolean, first: Boolean, last: Boolean,
        rowH: Int, itemGap: Int, palette: AppPalette, onPick: Runnable
    ): View {
        val outer = dp(MENU_OUTER_PAD_DP)
        // Half the gap above and half below, so two consecutive options end up
        // exactly itemGap apart and every row keeps the same height.
        val half = itemGap / 2
        val wrapper = FrameLayout(this)
        wrapper.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        wrapper.setPadding(outer, if (first) outer else half, outer, if (last) outer else half)

        val row = LinearLayout(this)
        row.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, rowH
        )
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        // A fixed height (not just a minimum) keeps every option identical.
        row.minimumHeight = rowH
        row.setPadding(dp(MENU_ITEM_PAD_H_DP), 0, dp(MENU_ITEM_PAD_H_DP), 0)

        val label = menuLabel(text, palette)
        row.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Clickable so the ripple reacts to the press even inside a ListView.
        row.isClickable = true
        row.isFocusable = false
        row.background = menuItemBackground(palette, selected)
        row.setOnClickListener { onPick.run() }
        wrapper.addView(row)
        return wrapper
    }

    /** One menu label with the shared type, colour and single-line behaviour. */
    private fun menuLabel(text: String, palette: AppPalette): TextView {
        val tv = TextView(this)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, MENU_ITEM_TEXT_SP.toFloat())
        tv.includeFontPadding = false
        tv.setSingleLine(true)
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.text = text
        // Every label keeps the standard on-surface colour so it stays
        // readable; only the background marks the selection.
        tv.setTextColor(palette.onSurface)
        return tv
    }

    /** Rounded fill plus a ripple masked to the very same rounded shape. */
    private fun menuItemBackground(palette: AppPalette, selected: Boolean): Drawable {
        val radius = dp(MENU_ITEM_RADIUS_DP).toFloat()
        val content = GradientDrawable()
        content.shape = GradientDrawable.RECTANGLE
        content.cornerRadius = radius
        // Unselected rows carry the page background, exactly like the panel
        // behind them, so the menu stays one flat surface; only the current
        // option is lifted with the primary container tone.
        content.setColor(if (selected) palette.primaryContainer else palette.pageBg)
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.RECTANGLE
        mask.cornerRadius = radius
        mask.setColor(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(palette.ripple), content, mask)
    }

    /**
     * One seed swatch: a circle that previews the seed's own tonal ramp —
     * the left half in the dark tone, the right half split into two light
     * tones — instead of a flat dot, so every option hints at the palette it
     * generates. The active seed gets a primary ring and a white check mark
     * on its dark half.
     */
    private class SeedSwatchDrawable(
        seed: Int,
        private val selected: Boolean,
        private val ringColor: Int,
        private val stroke: Float,
        /** Diameter of the drawn dot; it is centred inside the swatch's cell. */
        private val dotSize: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val circle = RectF()
        private val check = Path()
        private val darkTone: Int
        private val midTone: Int
        private val lightTone: Int

        init {
            val hct = Hct.fromInt(seed)
            val hue = hct.hue
            // Floor the chroma so near-grey seeds still read as tonal ramps.
            val chroma = Math.max(hct.chroma, 8.0)
            darkTone = Hct.from(hue, chroma, 40.0).toInt()
            midTone = Hct.from(hue, chroma, 80.0).toInt()
            lightTone = Hct.from(hue, chroma, 90.0).toInt()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val radius = Math.min(dotSize, Math.min(b.width(), b.height())) / 2f - stroke
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            circle.set(cx - radius, cy - radius, cx + radius, cy + radius)

            paint.style = Paint.Style.FILL
            // Base: the lightest tone fills the whole circle...
            paint.color = lightTone
            canvas.drawCircle(cx, cy, radius, paint)
            // ...the bottom-right quadrant takes the mid tone...
            paint.color = midTone
            canvas.drawArc(circle, 0f, 90f, true, paint)
            // ...and the left half takes the dark tone.
            paint.color = darkTone
            canvas.drawArc(circle, 90f, 180f, true, paint)

            if (selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.color = ringColor
                canvas.drawCircle(cx, cy, radius + stroke / 2f, paint)
                // White check on the dark half, where it stays readable.
                paint.color = Color.WHITE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                val kx = cx - radius * 0.45f
                check.reset()
                check.moveTo(kx - radius * 0.28f, cy)
                check.lineTo(kx - radius * 0.05f, cy + radius * 0.23f)
                check.lineTo(kx + radius * 0.36f, cy - radius * 0.26f)
                canvas.drawPath(check, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val REPO_URL = "https://github.com/iamqwert/HyperOS_FCM_Live"
        private const val REQ_EXPORT = 2001
        private const val REQ_IMPORT = 2002

        /** Easter egg: 7 taps on the version row within 2s, then silent for 10s. */
        private const val EGG_TAP_COUNT = 7
        private const val EGG_WINDOW_MS = 2000L
        private const val EGG_COOLDOWN_MS = 10000L

        private const val MENU_CONTAINER_RADIUS_DP = 16
        private const val MENU_ITEM_RADIUS_DP = 12
        private const val MENU_OUTER_PAD_DP = 6
        private const val MENU_ITEM_GAP_DP = 10
        private const val MENU_ITEM_PAD_H_DP = 12
        private const val MENU_ITEM_HEIGHT_DP = 40
        private const val MENU_ITEM_TEXT_SP = 16
        private const val MENU_EXTRA_WIDTH_DP = 28
        private const val MENU_ANCHOR_GAP_DP = 4
        private const val MENU_EDGE_INSET_DP = 12
        private const val MENU_SCREEN_PAD_DP = 12
        private const val MENU_ELEVATION_DP = 3
        private const val POPUP_DISMISS_GRACE_MS = 64L
        private const val MENU_ENTER_FROM = 0.8f
        private const val MENU_ENTER_MS = 250L
        private const val MENU_EXIT_MS = 150L
        private const val MENU_TENSION_FULL = 1.70158f

        /** Preset seeds offered when dynamic color is off (MD3-friendly hues). */
        private val SEED_COLORS = intArrayOf(
            0xFF6750A4.toInt(), // Material baseline purple (default)
            0xFFB3261E.toInt(), // red
            0xFFBF360C.toInt(), // deep orange
            0xFFE65100.toInt(), // orange
            0xFF9A6200.toInt(), // amber
            0xFF827717.toInt(), // olive
            0xFF558B2F.toInt(), // lime
            0xFF2E7D32.toInt(), // green
            0xFF006A6A.toInt(), // teal
            0xFF00838F.toInt(), // cyan
            0xFF0277BD.toInt(), // light blue
            0xFF0B57D0.toInt(), // blue
            0xFF3949AB.toInt(), // indigo
            0xFF5E35B1.toInt(), // deep purple
            0xFFAD1457.toInt(), // pink
            0xFF5F5E62.toInt(), // neutral grey
        )

        /** Swatches per row. Six keeps the block to three rows on a phone. */
        private const val SEED_COLUMNS = 6

        /** Gap between two neighbouring swatches; the dot is centred in its cell. */
        private const val SEED_GAP_DP = 8

        /** Largest dot. A narrow screen shrinks it so that six still fit per row. */
        private const val SEED_DOT_MAX_DP = 44

        /**
         * CSS `ease-out-back` with a tunable overshoot: the value shoots past
         * the target once and then settles back onto it, which is what makes the
         * menu read as a spring rather than as a plain ease-out. `tension`
         * is the classic 1.70158 for a 10% overshoot; smaller values give a gentler
         * bounce (1.2 is about 5%).
         */
        private fun easeOutBack(tension: Float): Interpolator {
            val c3 = tension + 1f
            return Interpolator { t ->
                val u = t - 1f
                1f + c3 * u * u * u + tension * u * u
            }
        }

        private fun clamp(value: Int, min: Int, max: Int): Int {
            return if (value < min) min else Math.min(value, max)
        }

        /**
         * A card tap: one tick of haptic feedback, then whatever the row does.
         *
         * Only the list cards get it — not the version row (it is deliberately
         * inert, and a buzz on every tap would give the easter egg away) and not
         * the open-source licenses screen.
         */
        private fun rowClick(action: Runnable): View.OnClickListener {
            return View.OnClickListener { v ->
                UiUtils.tapFeedback(v)
                action.run()
            }
        }

        private fun variantAt(index: Int): Scheme.Variant {
            val values = Scheme.Variant.values()
            return if (index >= 0 && index < values.size) values[index] else Scheme.Variant.TONAL_SPOT
        }

        /** Panel height for a given row height, gap and item count. */
        private fun naturalHeight(rowH: Int, itemGap: Int, outer: Int, count: Int): Int {
            return rowH * count + itemGap * Math.max(0, count - 1) + outer * 2
        }
    }
}
