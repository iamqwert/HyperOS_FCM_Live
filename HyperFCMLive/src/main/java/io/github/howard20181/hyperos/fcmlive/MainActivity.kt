package io.github.howard20181.hyperos.fcmlive

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.github.howard20181.hyperos.fcmlive.theme.AppPalette
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Settings screen: pick which apps FCM may wake / auto-launch.
 * MD3-inspired card list; search + overflow (system apps / hide icon) in the
 * top bar; FAB opens GMS FCM diagnostics.
 */
class MainActivity : Activity(), SearchView.OnQueryTextListener {

    private val allApps = ArrayList<AppListAdapter.AppEntry>()
    private val filteredApps = ArrayList<AppListAdapter.AppEntry>()
    private var allowlist: Set<String> = HashSet()
    private var adapter: AppListAdapter? = null
    private var titleView: TextView? = null
    private var searchView: SearchView? = null
    private var btnSearch: ImageButton? = null
    private var btnBack: ImageButton? = null
    private var btnMore: ImageButton? = null
    private var btnBatchAdd: ImageButton? = null
    private var btnBatchRemove: ImageButton? = null
    private var btnSelectAll: ImageButton? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var searching = false
    private var multiSelectMode = false

    /**
     * Set once the multi-select gesture is known — either because the tip below
     * was shown, or because the user already used multi-select. Either way the
     * gesture needs no advertising again, so the tip can never nag.
     */
    private var multiSelectKnown = false

    /** Start of the current burst of adds, and how many it holds (see RAPID_CHECK_*). */
    private var rapidCheckStartMs: Long = 0
    private var rapidCheckCount = 0

    /** True only after the first full package scan — blocks empty-list toasts while loading. */
    private var packagesReady = false
    private var currentQuery = ""
    private var showSystemApps = false

    /** Overflow: when true, list only apps whose Manifest has FCM-style receivers. */
    private var showFcmSupportedOnly = false

    /**
     * Overflow: when true, apps that carry MiPush are left out of the list.
     * Purely a filter, like [showFcmSupportedOnly] — it decides what is
     * offered, never what the module does with an app the user already checked.
     */
    private var excludeMiPushApps = false

    /**
     * Overflow: when true, the module leaves unchecked apps to the system once
     * at least one app is checked (`Hooker#shouldApply`). Read from the
     * local mirror here; the live copy the hooks read lives in remote prefs.
     */
    private var strictMode = false
    private var xposedService: XposedService? = null
    private var activeTooltip: PopupWindow? = null

    /** Overflow menu, dismissed on destroy so a rotation cannot leak the window. */
    private var activeOverflowMenu: PopupWindow? = null
    private val dismissTooltipRunnable = Runnable { dismissActiveTooltip() }

    /** Palette this activity was painted with; a mismatch on resume = repaint. */
    private var appliedPalette: AppPalette? = null

    /** Main-thread handler used to coalesce filtering while the user types. */
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingFilter: Runnable? = null

    /**
     * Which scan is the newest one.
     *
     * Every [loadApps] call used to race its own thread, and a slow
     * earlier scan could finish after a fast later one and overwrite the list
     * with stale contents — the visible symptom is a refresh that appears to
     * revert. Each scan now claims a number and anything it posts afterwards is
     * dropped unless it is still the latest.
     */
    private val appScanGeneration = AtomicInteger()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        appliedPalette = ThemeEngine.palette(this)
        try {
            setContentView(R.layout.activity_main)
        } catch (t: Throwable) {
            Log.e(TAG_UI, "setContentView failed", t)
            finish()
            return
        }

        applySystemBarInsets()

        titleView = findViewById<TextView?>(R.id.toolbar_title)?.also {
            it.setText(R.string.settings_title)
        }

        // Seed UI order from the local cache so allowlisted apps sit on top
        // immediately, before libxposed remote prefs bind.
        allowlist = Prefs.readLocalAllowlist(this)

        // First launch: show FCM-supported apps by default. After the user
        // toggles the overflow option, their stored preference wins.
        showFcmSupportedOnly = getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
            .getBoolean(Prefs.KEY_SHOW_FCM_ONLY, true)
        // Off by default: an existing install must not start leaving unchecked
        // apps to the system just because it was upgraded.
        strictMode = Prefs.readLocalStrictMode(this)
        // Off by default for the same reason, and because the point of the
        // MiPush tag is to be seen — a filter that is on from the start hides
        // the very apps it is meant to explain.
        excludeMiPushApps = getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
            .getBoolean(Prefs.KEY_EXCLUDE_MIPUSH, false)

        initXposedService()

        adapter = AppListAdapter(this, filteredApps, object : AppListAdapter.OnCardListener {
            override fun onToggleAllowlist(packageName: String, checked: Boolean) {
                if (checked) {
                    allowlist = HashSet(allowlist).also { it.add(packageName) }
                    perhapsAdvertiseMultiSelect()
                } else {
                    allowlist = HashSet(allowlist).also { it.remove(packageName) }
                }
                updateAllowlist()
                // Stay in place on tap. Order refreshes on pull-to-refresh / reopen.
                for (app in allApps) {
                    if (app.packageName == packageName) {
                        app.checked = checked
                        break
                    }
                }
            }

            override fun onEnterMultiSelect(packageName: String) {
                enterMultiSelect(packageName)
            }

            override fun onSelectionChanged(count: Int) {
                if (multiSelectMode) {
                    updateSelectionTitle(count)
                    updateSelectAllIcon()
                }
            }
        })

        searchView = findViewById<SearchView?>(R.id.search_view)?.also {
            it.setOnQueryTextListener(this)
            styleSearchView(it)
        }
        btnBack = findViewById(R.id.btn_back)
        btnSearch = findViewById(R.id.btn_search)
        btnMore = findViewById(R.id.btn_more)
        btnBatchAdd = findViewById(R.id.btn_batch_add)
        btnBatchRemove = findViewById(R.id.btn_batch_remove)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnSearch?.let {
            it.setOnClickListener { enterSearch() }
            attachTip(it, R.string.tooltip_search)
        }
        btnBack?.let {
            it.setOnClickListener {
                if (multiSelectMode) exitMultiSelect() else exitSearch()
            }
            attachTip(it, R.string.exit_search)
        }
        btnMore?.let {
            it.setOnClickListener(this::showOverflowMenu)
            attachTip(it, R.string.more_menu)
        }
        btnBatchAdd?.let {
            it.setOnClickListener { applyBatchAllowlist(true) }
            attachTip(it, R.string.batch_add_allowlist)
        }
        btnBatchRemove?.let {
            it.setOnClickListener { applyBatchAllowlist(false) }
            attachTip(it, R.string.batch_remove_allowlist)
        }
        btnSelectAll?.let {
            it.setOnClickListener { toggleSelectAllVisible() }
            attachTip(it, R.string.select_all)
        }

        // Material / Android standard pull-to-refresh (SwipeRefreshLayout).
        swipeRefresh = findViewById<SwipeRefreshLayout?>(R.id.refresh_layout)?.also {
            try {
                it.setColorSchemeColors(ThemeEngine.palette(this).primary)
            } catch (ignored: Throwable) {
            }
            it.setOnRefreshListener { loadApps() }
            // Only at the top of the list; default Material trigger distance.
            it.isEnabled = true
        }

        findViewById<View>(R.id.fab_fcm_diagnostics)?.let {
            it.setOnClickListener { openFcmDiagnostics() }
            attachTip(it, R.string.fcm_diagnostics)
        }

        val list = findViewById<View>(R.id.app_list)
        if (list is ListView) {
            list.adapter = adapter
        }

        // Idle at startup, so this is a no-op; kept for symmetry with the state
        // changes below (enterSearch / enterMultiSelect ...).
        updateBackCallback()

        // Read back what a configuration change would otherwise drop — search
        // text and an in-progress batch selection — before the list is built.
        restoreUiState(savedInstanceState)

        // Wait for HyperOS app-list grant when needed; other ROMs load immediately.
        if (!requestInstalledAppsPermissionIfNeeded()) {
            if (!restoreAppListFromCache()) {
                loadApps()
            }
        }
        startUpdateCheck()
    }

    /** Launch-time update check: Toast only (About keeps its badge). */
    private fun startUpdateCheck() {
        UpdateChecker.checkAutoAsync(this, object : UpdateChecker.Callback {
            override fun onResult(
                updateAvailable: Boolean,
                latestVersion: String,
                downloadUrl: String
            ) {
                if (updateAvailable) {
                    runOnUiThreadSafe {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.update_found, latestVersion),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
    }

    /**
     * MIUI 13 / HyperOS only: request GET_INSTALLED_APPS when the permission
     * exists and is owned by Xiaomi's security center. Returns true when a
     * runtime request was launched and loading should wait for the result.
     */
    private fun requestInstalledAppsPermissionIfNeeded(): Boolean {
        if (!hasAppListGate()) {
            return false
        }
        if (checkSelfPermission(GET_INSTALLED_APPS_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            return false
        }
        requestPermissions(arrayOf(GET_INSTALLED_APPS_PERMISSION), REQUEST_GET_INSTALLED_APPS)
        return true
    }

    /**
     * Whether this ROM gates the package list behind a runtime permission.
     * MIUI 13 / HyperOS declare `GET_INSTALLED_APPS`, owned by the
     * security centre; AOSP and every other ROM do not.
     */
    private fun hasAppListGate(): Boolean {
        return try {
            val info = packageManager.getPermissionInfo(GET_INSTALLED_APPS_PERMISSION, 0)
            info != null && MIUI_SECURITY_PACKAGE == info.packageName
        } catch (ignored: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Whether `getInstalledPackages()` may be trusted to return the whole
     * list. Until the app-list permission is granted the query comes back nearly
     * empty, which is indistinguishable from a device that genuinely has no FCM
     * app — so anything that reacts to an empty scan has to ask this first.
     *
     * This is deliberately a live check rather than a flag set from
     * `onRequestPermissionsResult`: the Xposed service can bind and kick
     * off a scan before the user has even answered the dialog.
     */
    private fun isAppListReadable(): Boolean {
        return !hasAppListGate() ||
            checkSelfPermission(GET_INSTALLED_APPS_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_GET_INSTALLED_APPS) {
            return
        }
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // Partial package list is still usable — do not block the screen.
            Toast.makeText(this, R.string.installed_apps_permission_denied, Toast.LENGTH_LONG).show()
        }
        loadApps()
    }

    /**
     * Long-press tooltip that never covers the anchor icon.
     * HyperOS (and some AOSP builds) place the system bubble on top of the
     * control; we show a custom MD-style popup with an explicit gap instead.
     */
    private fun attachTip(view: View?, tooltipRes: Int) {
        if (view == null) {
            return
        }
        val tip: CharSequence = getText(tooltipRes)
        view.contentDescription = tip
        // Suppress framework / HyperOS bubbles that sit on the icon.
        view.tooltipText = null
        view.isLongClickable = true
        view.setOnLongClickListener { v ->
            showAnchorTooltip(v, tip)
            true
        }
    }

    /** Show a short bubble below (or above when needed) the given anchor. */
    private fun showAnchorTooltip(anchor: View?, text: CharSequence?) {
        dismissActiveTooltip()
        if (anchor == null || text.isNullOrEmpty() || isFinishing) {
            return
        }

        // HyperOS may re-surface contentDescription as a covering bubble on
        // long-press. Hide it while our offset tooltip is visible, restore for
        // accessibility after dismiss.
        val restoredCd: CharSequence = anchor.contentDescription ?: text
        anchor.contentDescription = null

        val tipView = TextView(this)
        tipView.text = text
        val tooltipPalette = ThemeEngine.palette(this)
        tipView.setTextColor(tooltipPalette.tooltipText)
        tipView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tipView.gravity = Gravity.CENTER
        tipView.background = ThemeSupport.cardBackground(this, tooltipPalette.tooltipBg, 4f)
        val padH = dp(12)
        val padV = dp(6)
        tipView.setPadding(padH, padV, padH, padV)
        tipView.setSingleLine(true)
        tipView.includeFontPadding = false

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val maxTextW = Math.max(dp(64), Math.min(dp(240), screenW - dp(48)))
        tipView.maxWidth = maxTextW
        tipView.measure(
            View.MeasureSpec.makeMeasureSpec(maxTextW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(64), View.MeasureSpec.AT_MOST)
        )
        val tipW = Math.max(tipView.measuredWidth, padH * 2 + dp(24))
        val tipH = Math.max(tipView.measuredHeight, dp(28))

        val popup = PopupWindow(tipView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.contentView = tipView
        popup.width = tipW
        popup.height = tipH
        popup.isOutsideTouchable = true
        popup.isFocusable = false
        popup.isTouchable = true
        popup.isClippingEnabled = true
        try {
            popup.elevation = dp(6).toFloat()
        } catch (ignored: Throwable) {
        }
        // Transparent so the rounded shape is not clipped by a default frame.
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.setOnDismissListener {
            tipView.removeCallbacks(dismissTooltipRunnable)
            if (activeTooltip === popup) {
                activeTooltip = null
            }
            try {
                anchor.contentDescription = restoredCd
            } catch (ignored: Throwable) {
            }
        }

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val gap = dp(8)
        val edge = dp(8)

        // Horizontal: center on the anchor, then clamp into the screen.
        var screenX = loc[0] + (anchor.width - tipW) / 2
        if (screenX < edge) {
            screenX = edge
        }
        if (screenX + tipW > screenW - edge) {
            screenX = Math.max(edge, screenW - edge - tipW)
        }

        // Vertical: prefer a clear gap under the icon; flip above when tight
        // (toolbar icons near the status bar, FAB near the nav bar).
        val yBelow = loc[1] + anchor.height + gap
        val yAbove = loc[1] - gap - tipH
        val roomBelow = screenH - edge - (loc[1] + anchor.height)
        val roomAbove = loc[1] - edge
        val fitsBelow = roomBelow >= tipH + gap
        val fitsAbove = roomAbove >= tipH + gap
        var screenY = when {
            fitsBelow -> yBelow
            fitsAbove -> yAbove
            roomBelow >= roomAbove -> Math.min(yBelow, screenH - edge - tipH)
            else -> Math.max(yAbove, edge)
        }
        if (screenY < edge) {
            screenY = edge
        }
        if (screenY + tipH > screenH - edge) {
            screenY = Math.max(edge, screenH - edge - tipH)
        }

        // PopupWindow coordinates are window-relative.
        val decor = window?.decorView
        var decorX = 0
        var decorY = 0
        if (decor != null) {
            val decorLoc = IntArray(2)
            decor.getLocationOnScreen(decorLoc)
            decorX = decorLoc[0]
            decorY = decorLoc[1]
        }
        val winX = screenX - decorX
        val winY = screenY - decorY

        try {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, winX, winY)
            activeTooltip = popup
            tipView.postDelayed(dismissTooltipRunnable, 2200)
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

    /**
     * Predictive back (Android 13+, opt-in via enableOnBackInvokedCallback):
     * register only while there is an internal state to unwind. In the idle
     * state no callback is registered, which is what lets the system run its
     * own "back to home" preview animation instead of a plain activity finish.
     * Call this whenever `searching` or `multiSelectMode` changes.
     */
    private fun updateBackCallback() {
        // Predictive back is always on: the gesture is only intercepted while
        // an internal state (search / multi-select) needs unwinding; otherwise
        // the system shows its own back-to-home preview animation.
        val intercept = multiSelectMode || searching
        try {
            if (intercept && backInvokedCallback == null) {
                val cb = OnBackInvokedCallback { handleBack() }
                backInvokedCallback = cb
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb
                )
            } else if (!intercept && backInvokedCallback != null) {
                backInvokedCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
                backInvokedCallback = null
            }
        } catch (ignored: Throwable) {
            // Dispatcher unavailable on this ROM build: nothing to unwind.
        }
    }

    /**
     * Search: first back closes IME (system), next back exits search — not home.
     * Only reached when the callback is registered, i.e. an internal state is
     * active; the idle case is handled by the system default (finish).
     */
    private fun handleBack() {
        when {
            multiSelectMode -> exitMultiSelect()
            searching -> exitSearch()
            else -> finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Appearance settings may have changed while the settings screen was
        // on top (palette style, theme mode, seed color...). ThemeEngine was
        // invalidated there, so a fresh instance here means we are showing
        // stale colors: rebuild the whole activity to repaint everything.
        if (appliedPalette != null && ThemeEngine.palette(this) !== appliedPalette) {
            recreate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE_QUERY, currentQuery)
        outState.putBoolean(KEY_STATE_SEARCHING, searching)
        outState.putBoolean(KEY_STATE_MULTI_SELECT, multiSelectMode)
        outState.putBoolean(KEY_STATE_SHOW_SYSTEM, showSystemApps)
        if (multiSelectMode && adapter != null) {
            outState.putStringArrayList(
                KEY_STATE_SELECTION,
                ArrayList(adapter!!.getSelectedPackages())
            )
        }
    }

    /**
     * Re-apply what a rebuild would otherwise drop. Rotation and the
     * theme/language rebuild both recreate this screen, and losing a batch
     * selection the user just made is exactly the kind of thing that reads as
     * "the app is broken" rather than "the screen rotated".
     */
    private fun restoreUiState(saved: Bundle?) {
        if (saved == null) {
            return
        }
        showSystemApps = saved.getBoolean(KEY_STATE_SHOW_SYSTEM, showSystemApps)
        currentQuery = saved.getString(KEY_STATE_QUERY) ?: ""
        if (saved.getBoolean(KEY_STATE_MULTI_SELECT, false)) {
            enterMultiSelect(null)
            val selection = saved.getStringArrayList(KEY_STATE_SELECTION)
            val ad = adapter
            if (ad != null && selection != null) {
                ad.setSelectedPackages(HashSet(selection))
                updateSelectionTitle(ad.getSelectedPackages().size)
                updateSelectAllIcon()
            }
            return
        }
        if (saved.getBoolean(KEY_STATE_SEARCHING, false)) {
            enterSearch()
            searchView?.setQuery(currentQuery, false)
        }
        filterApps(currentQuery)
    }

    /**
     * Reuse the previous package scan after a configuration change instead of
     * querying PackageManager for every installed package again. Returns false
     * when there is nothing to reuse (first open, or the system-app filter
     * changed since the scan), which is when a real scan runs.
     */
    private fun restoreAppListFromCache(): Boolean {
        val cached = sAppScanCache ?: return false
        if (sAppScanCacheShowSystemApps != showSystemApps) {
            return false
        }
        applyAppSnapshot(ArrayList(cached), true)
        reloadAllowlist()
        return true
    }

    /**
     * Post to the UI thread only while this activity can still be used. Both the
     * Xposed service callback and the package scan outlive a screen the user has
     * already left; running them against a destroyed activity touches dead views
     * and pins the whole hierarchy for nothing.
     */
    private fun runOnUiThreadSafe(action: Runnable) {
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                return@runOnUiThread
            }
            action.run()
        }
    }

    override fun onDestroy() {
        dismissActiveTooltip()
        dismissOverflowMenu()
        pendingFilter?.let {
            uiHandler.removeCallbacks(it)
            pendingFilter = null
        }
        adapter?.shutdown()
        if (isFinishing) {
            // Leaving for real rather than being rebuilt: drop the scan cache so
            // the icons it pins are released with the screen.
            sAppScanCache = null
        }
        backInvokedCallback?.let {
            try {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            } catch (ignored: Throwable) {
            }
        }
        super.onDestroy()
    }

    /**
     * Pad the top bar by the real window inset so it sits just below the status
     * bar (no fitsSystemWindows — that stacked with dimen padding and pushed
     * the title too far down).
     */
    private fun applySystemBarInsets() {
        val topBar = findViewById<View>(R.id.top_bar) ?: return
        val fab = findViewById<View>(R.id.fab_fcm_diagnostics)
        UiUtils.applyBarInsets(this, topBar, findViewById(R.id.app_list), 88) { _, bottom ->
            applyFabBottomMargin(fab, bottom)
        }
        applyFabBottomMargin(fab, 0)
    }

    /**
     * Keep a fixed visual gap under the FAB: at least `base` dp from the
     * window bottom, or nav-bar height + extra when a system bar occupies the
     * edge — so large-corner devices are not clipped, without leaving a huge
     * hole on small-corner screens.
     */
    private fun applyFabBottomMargin(fab: View?, systemBottomInset: Int) {
        if (fab == null || fab.layoutParams !is FrameLayout.LayoutParams) {
            return
        }
        val base = dp(26)
        val extra = dp(14)
        var margin = Math.max(base, systemBottomInset + extra)
        // If insets missing, still lift a bit on gesture/button nav devices.
        if (systemBottomInset <= 0) {
            val nav = navigationBarHeight()
            margin = Math.max(base, nav + extra)
        }
        val lp = fab.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin
            lp.rightMargin = dp(20)
            fab.layoutParams = lp
        }
    }

    private fun navigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(value: Int): Int = UiUtils.dp(this, value)

    /** Lighter query hint + no underline so inline search does not shift the bar. */
    private fun styleSearchView(sv: SearchView?) {
        if (sv == null) {
            return
        }
        sv.setBackgroundColor(Color.TRANSPARENT)
        val hintColor = getColor(R.color.md_hint_light)
        val textColor = getColor(R.color.md_on_surface)
        val ids = intArrayOf(
            resources.getIdentifier("search_src_text", "id", "android"),
            resources.getIdentifier("search_edit_text", "id", "android"),
        )
        for (id in ids) {
            if (id == 0) continue
            val inner = sv.findViewById<View>(id)
            if (inner is TextView) {
                inner.setHintTextColor(hintColor)
                inner.setTextColor(textColor)
                inner.setBackgroundColor(Color.TRANSPARENT)
                inner.setSingleLine(true)
            }
        }
        for (name in arrayOf("search_plate", "search_edit_frame", "search_bar")) {
            val id = resources.getIdentifier(name, "id", "android")
            if (id == 0) continue
            val plate = sv.findViewById<View>(id)
            if (plate != null) {
                plate.background = null
                plate.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    /** Title becomes an inline search field; more-menu stays visible. */
    private fun enterSearch() {
        if (multiSelectMode) {
            exitMultiSelect()
        }
        dismissActiveTooltip()
        searching = true
        updateBackCallback()
        // Keep ListView height stable so the scrollbar does not jump when IME opens.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        titleView?.visibility = View.GONE
        searchView?.let {
            it.visibility = View.VISIBLE
            it.isIconified = false
            it.requestFocus()
        }
        btnSearch?.visibility = View.GONE
        btnBack?.let {
            it.visibility = View.VISIBLE
            attachTip(it, R.string.exit_search)
        }
        btnMore?.visibility = View.VISIBLE
        btnBatchAdd?.visibility = View.GONE
        btnBatchRemove?.visibility = View.GONE
        btnSelectAll?.visibility = View.GONE
    }

    private fun exitSearch() {
        dismissActiveTooltip()
        searching = false
        updateBackCallback()
        searchView?.let {
            it.setQuery("", false)
            it.clearFocus()
            it.visibility = View.GONE
        }
        titleView?.let {
            it.visibility = View.VISIBLE
            if (!multiSelectMode) {
                it.setText(R.string.settings_title)
            }
        }
        btnSearch?.visibility = View.VISIBLE
        btnBack?.visibility = View.GONE
        btnBatchAdd?.visibility = View.GONE
        btnBatchRemove?.visibility = View.GONE
        btnSelectAll?.visibility = View.GONE
        currentQuery = ""
        filterApps("")
    }

    /**
     * Advertise the long-press multi-select gesture when the user is visibly
     * checking apps one at a time: [RAPID_CHECK_HINT_AT] apps added
     * inside [RAPID_CHECK_WINDOW_MS] is exactly the manual work
     * multi-select does in one go. Shown at most once per visit — and never
     * again once the gesture has been used.
     */
    private fun perhapsAdvertiseMultiSelect() {
        if (multiSelectKnown) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - rapidCheckStartMs > RAPID_CHECK_WINDOW_MS) {
            rapidCheckStartMs = now
            rapidCheckCount = 0
        }
        rapidCheckCount++
        if (rapidCheckCount >= RAPID_CHECK_HINT_AT) {
            multiSelectKnown = true
            Toast.makeText(this, R.string.multi_select_tip, Toast.LENGTH_LONG).show()
        }
    }

    private fun enterMultiSelect(firstPackage: String?) {
        // The gesture is known from here on: stop advertising it.
        multiSelectKnown = true
        dismissActiveTooltip()
        if (searching) {
            searching = false
            searchView?.let {
                it.setQuery("", false)
                it.clearFocus()
                it.visibility = View.GONE
            }
            currentQuery = ""
            filterApps("")
        }
        multiSelectMode = true
        updateBackCallback()
        adapter?.let {
            it.setMultiSelectMode(true)
            val seed = HashSet<String>()
            if (firstPackage != null) {
                seed.add(firstPackage)
            }
            it.setSelectedPackages(seed)
        }
        applyMultiSelectBar()
    }

    private fun exitMultiSelect() {
        multiSelectMode = false
        updateBackCallback()
        adapter?.setMultiSelectMode(false)
        titleView?.let {
            it.visibility = View.VISIBLE
            it.setText(R.string.settings_title)
        }
        searchView?.visibility = View.GONE
        btnSearch?.visibility = View.VISIBLE
        btnBack?.visibility = View.GONE
        btnMore?.visibility = View.VISIBLE
        btnBatchAdd?.visibility = View.GONE
        btnBatchRemove?.visibility = View.GONE
        btnSelectAll?.visibility = View.GONE
    }

    private fun applyMultiSelectBar() {
        if (!multiSelectMode) {
            return
        }
        titleView?.visibility = View.VISIBLE
        searchView?.visibility = View.GONE
        btnBack?.let {
            it.visibility = View.VISIBLE
            attachTip(it, R.string.exit_multi_select)
        }
        btnSearch?.visibility = View.GONE
        btnMore?.visibility = View.GONE
        btnBatchAdd?.visibility = View.VISIBLE
        btnBatchRemove?.visibility = View.VISIBLE
        btnSelectAll?.visibility = View.VISIBLE
        val count = adapter?.getSelectedPackages()?.size ?: 0
        updateSelectionTitle(count)
        updateSelectAllIcon()
    }

    private fun updateSelectionTitle(count: Int) {
        if (titleView != null && multiSelectMode) {
            titleView!!.text = getString(R.string.selected_count, count)
        }
    }

    /** All currently visible (filtered) rows are selected → show deselect-all icon. */
    private fun isAllVisibleSelected(): Boolean {
        val ad = adapter ?: return false
        if (filteredApps.isEmpty()) {
            return false
        }
        val selected = ad.getSelectedPackages()
        for (app in filteredApps) {
            if (!selected.contains(app.packageName)) {
                return false
            }
        }
        return true
    }

    /**
     * One control, two states: select-all icon → tap selects every visible row;
     * when all are selected the icon flips to deselect-all.
     */
    private fun updateSelectAllIcon() {
        if (btnSelectAll == null || !multiSelectMode) {
            return
        }
        val all = isAllVisibleSelected()
        btnSelectAll!!.setImageResource(if (all) R.drawable.ic_deselect_all else R.drawable.ic_select_all)
        attachTip(btnSelectAll, if (all) R.string.deselect_all else R.string.select_all)
    }

    private fun toggleSelectAllVisible() {
        if (!multiSelectMode || adapter == null) {
            return
        }
        val ad = adapter!!
        val next = HashSet(ad.getSelectedPackages())
        if (isAllVisibleSelected()) {
            for (app in filteredApps) {
                next.remove(app.packageName)
            }
        } else {
            for (app in filteredApps) {
                next.add(app.packageName)
            }
        }
        ad.setSelectedPackages(next)
        updateSelectionTitle(ad.getSelectedPackages().size)
        updateSelectAllIcon()
    }

    /**
     * Apply selected packages to the allowlist. Selection is a staging set;
     * the whitelist only changes when the user taps a batch action.
     */
    private fun applyBatchAllowlist(add: Boolean) {
        if (!multiSelectMode || adapter == null) {
            return
        }
        val selected = adapter!!.getSelectedPackages()
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.batch_nothing_selected, Toast.LENGTH_SHORT).show()
            return
        }
        var changed = false
        val newAllow = HashSet(allowlist)
        for (app in allApps) {
            if (!selected.contains(app.packageName)) {
                continue
            }
            if (add && !app.checked) {
                app.checked = true
                newAllow.add(app.packageName)
                changed = true
            } else if (!add && app.checked) {
                app.checked = false
                newAllow.remove(app.packageName)
                changed = true
            }
        }
        if (changed) {
            allowlist = newAllow
            updateAllowlist()
        }
        adapter?.notifyDataSetChanged()
        Toast.makeText(this, R.string.batch_added, Toast.LENGTH_SHORT).show()
        exitMultiSelect()
    }

    /** Close the overflow menu if one is showing; safe to call at any time. */
    private fun dismissOverflowMenu() {
        val popup = activeOverflowMenu
        activeOverflowMenu = null
        if (popup != null) {
            try {
                popup.dismiss()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun bindMd3Check(box: ImageView?, checked: Boolean) {
        box?.setImageResource(if (checked) R.drawable.md3_check_on else R.drawable.md3_check_off)
    }

    override fun onQueryTextSubmit(query: String?): Boolean = false

    override fun onQueryTextChange(newText: String?): Boolean {
        currentQuery = newText ?: ""
        // Coalesce: one pass walks every app twice, and the list cannot usefully
        // change faster than the user reads it, so a burst of keystrokes costs
        // one filter instead of one per character.
        pendingFilter?.let { uiHandler.removeCallbacks(it) }
        val query = currentQuery
        val filter = Runnable {
            pendingFilter = null
            filterApps(query)
        }
        pendingFilter = filter
        uiHandler.postDelayed(filter, FILTER_DEBOUNCE_MS)
        return true
    }

    private fun showOverflowMenu(anchor: View) {
        dismissActiveTooltip()
        dismissOverflowMenu()
        val content = layoutInflater.inflate(R.layout.popup_overflow, null)
        val sysCheck = content.findViewById<ImageView>(R.id.menu_show_system_check)
        val fcmCheck = content.findViewById<ImageView>(R.id.menu_show_fcm_check)
        val mipushCheck = content.findViewById<ImageView>(R.id.menu_exclude_mipush_check)
        val strictCheck = content.findViewById<ImageView>(R.id.menu_strict_mode_check)
        bindMd3Check(sysCheck, showSystemApps)
        bindMd3Check(fcmCheck, showFcmSupportedOnly)
        bindMd3Check(mipushCheck, excludeMiPushApps)
        bindMd3Check(strictCheck, strictMode)

        // Line the check boxes up on one vertical line. Each row lays out as
        // [label][12dp][check box], so a wrap_content label parks its check box
        // wherever the text happens to end — invisible while both Chinese labels
        // are the same length, but "Show system apps" and "Show FCM supported
        // apps" differ in English and the boxes drifted apart. All four
        // toggle labels get the width of the widest one, which fixes that; in
        // Chinese they already measure alike, so nothing moves there. Done
        // before the measure pass so the popup width stays exactly what it was.
        val labelIds = intArrayOf(
            R.id.menu_show_system_label, R.id.menu_show_fcm_label,
            R.id.menu_exclude_mipush_label, R.id.menu_strict_mode_label
        )
        var widestLabel = 0
        for (id in labelIds) {
            val label = content.findViewById<TextView>(id)
            if (label != null) {
                label.measure(0, 0)
                widestLabel = Math.max(widestLabel, label.measuredWidth)
            }
        }
        if (widestLabel > 0) {
            for (id in labelIds) {
                val label = content.findViewById<View>(id)
                label?.layoutParams?.width = widestLabel
            }
        }

        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        // Tracked so a rotation or a back press while it is open cannot leave the
        // window attached to a destroyed activity (WindowLeaked).
        activeOverflowMenu = popup
        popup.setOnDismissListener {
            if (activeOverflowMenu === popup) {
                activeOverflowMenu = null
            }
        }
        popup.elevation = dp(6).toFloat()
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.isTouchable = true

        // Measure wrap_content only — NEVER force a fixed width.
        // Width = padding + longest(label + 12dp + checkbox); no right void,
        // and checkbox stays ~12dp from the text (no layout_weight).
        content.measure(
            View.MeasureSpec.makeMeasureSpec(
                resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(
                resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST
            )
        )
        val popupW = content.measuredWidth
        val popupH = content.measuredHeight
        if (popupW > 0) {
            popup.width = popupW
        }
        if (popupH > 0) {
            popup.height = popupH
        }

        val rowSystem = content.findViewById<View>(R.id.menu_show_system)
        // All rows must fill the popup width so the ripple covers the full
        // clickable area; wrap_content rows would stop at their own content
        // width and leave a gap on the right.
        rowSystem.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        rowSystem.setOnClickListener {
            showSystemApps = !showSystemApps
            bindMd3Check(sysCheck, showSystemApps)
            loadApps()
            popup.dismiss()
        }

        val rowFcm = content.findViewById<View>(R.id.menu_show_fcm)
        rowFcm.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        rowFcm.setOnClickListener {
            showFcmSupportedOnly = !showFcmSupportedOnly
            bindMd3Check(fcmCheck, showFcmSupportedOnly)
            getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(Prefs.KEY_SHOW_FCM_ONLY, showFcmSupportedOnly)
                .apply()
            // Filter only — keep package scan; toggle just hides non-FCM rows.
            filterApps(currentQuery)
            popup.dismiss()
        }

        val rowMiPush = content.findViewById<View>(R.id.menu_exclude_mipush)
        rowMiPush.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        rowMiPush.setOnClickListener {
            excludeMiPushApps = !excludeMiPushApps
            bindMd3Check(mipushCheck, excludeMiPushApps)
            getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(Prefs.KEY_EXCLUDE_MIPUSH, excludeMiPushApps)
                .apply()
            // Filter only — the package scan stands, and so does every
            // allowlist entry: this toggle decides what is offered, not what the
            // module already does for an app.
            filterApps(currentQuery)
            popup.dismiss()
        }

        val rowStrict = content.findViewById<View>(R.id.menu_strict_mode)
        rowStrict.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        rowStrict.setOnClickListener {
            strictMode = !strictMode
            bindMd3Check(strictCheck, strictMode)
            // Written to the remote group the hooks read, then announced with
            // the same broadcast as a list edit, so it is live at once. Nothing
            // in the list changes: the toggle only decides what the module does
            // for apps that are not checked.
            Prefs.writeStrictMode(this, remotePrefs(), strictMode)
            popup.dismiss()
        }

        val rowStatus = content.findViewById<View>(R.id.menu_status)
        if (rowStatus != null) {
            rowStatus.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            rowStatus.setOnClickListener {
                // No haptic here: this row navigates away, and the buzz it used
                // to fire read as a stray vibration before the next screen
                // appeared.
                popup.dismiss()
                startActivity(Intent(this, StatusActivity::class.java))
            }
        }

        val rowAbout = content.findViewById<View>(R.id.menu_about)
        if (rowAbout != null) {
            rowAbout.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            rowAbout.setOnClickListener {
                popup.dismiss()
                startActivity(Intent(this, AboutActivity::class.java))
            }
        }

        // Keep the popup fully on-screen; width already equals content.
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        val margin = dp(8)
        var xOff = anchor.width - popupW
        if (loc[0] + xOff < margin) {
            xOff = margin - loc[0]
        }
        if (loc[0] + xOff + popupW > screenW - margin) {
            xOff = screenW - margin - loc[0] - popupW
        }
        popup.showAsDropDown(anchor, xOff, dp(4))
    }

    private fun filterApps(query: String?) {
        filteredApps.clear()
        // Locale.ROOT: the default locale would fold "I" to "ı" under a Turkish
        // locale and silently stop matching package names that contain it.
        val lower = if (!query.isNullOrEmpty()) query.lowercase(Locale.ROOT) else null
        val fcmOnly = showFcmSupportedOnly
        val dropMiPush = excludeMiPushApps
        for (app in allApps) {
            if (fcmOnly && !app.supportFcm) {
                continue
            }
            // Already checked apps stay: dropping them would hide a choice the
            // user has already made — it would remain in the allowlist, still
            // costing what the filter is meant to save, with no row left to undo
            // it from. They keep the tag, so the reason to uncheck them shows.
            if (dropMiPush && app.supportMiPush && !app.checked) {
                continue
            }
            if (lower == null ||
                app.label.lowercase(Locale.ROOT).contains(lower) ||
                app.packageName.lowercase(Locale.ROOT).contains(lower)
            ) {
                filteredApps.add(app)
            }
        }
        adapter?.notifyDataSetChanged()
        maybeToastNoFcmApps()
    }

    /**
     * Empty list after a full package scan (no search query), where one of the
     * two overflow filters is what emptied it.
     */
    private fun maybeToastNoFcmApps() {
        if (!packagesReady) {
            return
        }
        if (currentQuery.isNotEmpty()) {
            return
        }
        if (filteredApps.isNotEmpty()) {
            return
        }
        // With both filters off an empty list means the scan found nothing, and
        // blaming a filter for that would be wrong.
        if (!showFcmSupportedOnly && !excludeMiPushApps) {
            return
        }
        // Empty only means "the filter hid them" when the package list itself was
        // readable. On HyperOS the very first launch scans while the app-list
        // permission is still unanswered, the query returns almost nothing, and
        // saying "no supported apps" then would blame the device for a question
        // the user has not been asked yet.
        if (!isAppListReadable()) {
            return
        }
        // The FCM wording stays for the case that existed before; the excluded
        // one names the filters, since either can be what emptied the list.
        Toast.makeText(
            this,
            if (showFcmSupportedOnly) R.string.no_fcm_apps_found else R.string.no_apps_found,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openFcmDiagnostics() {
        val intent = Intent()
        intent.setClassName("com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                fallback.data = Uri.parse("package:com.google.android.gms")
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
            } catch (t2: Throwable) {
                Toast.makeText(this, R.string.fcm_diagnostics_not_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sortApps() {
        allApps.sortWith { a, b -> compareEntries(a, b) }
    }

    /**
     * An app the user cannot remove: preinstalled on the system image.
     *
     * `FLAG_SYSTEM` alone is the whole test. An updated system app is
     * still a system app — updating it only replaces its APK under /data, and
     * it stays uninstallable; "uninstall updates" only takes it back to the
     * factory version. So `FLAG_UPDATED_SYSTEM_APP` is deliberately not
     * excluded here. Excluding it used to hide the contradiction, until the
     * Play Store family made it visible: Google Play services and Google Play
     * Store are preinstalled and update themselves, so they carried the flag
     * and showed up with system apps hidden — while the preinstalled apps the
     * user never touched stayed hidden. Treating "was updated" as "became a
     * user app" inverts what people expect this toggle to mean.
     */
    private fun isSystemApp(ai: ApplicationInfo): Boolean {
        return (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private fun initXposedService() {
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    xposedService = service
                    runOnUiThreadSafe {
                        // Publish for other screens (About import/export).
                        Prefs.setRemote(remotePrefs())
                        // Remote prefs are the source of truth once bound.
                        reloadAllowlist()
                        loadApps()
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    if (xposedService === service) {
                        xposedService = null
                        Prefs.setRemote(null)
                    }
                }
            })
        } catch (ignored: Throwable) {
        }
    }

    private fun remotePrefs(): SharedPreferences? {
        val service = xposedService ?: return null
        return try {
            service.getRemotePreferences(Prefs.GROUP_CONFIG)
        } catch (e: Throwable) {
            null
        }
    }

    private fun reloadAllowlist() {
        val prefs = remotePrefs() ?: return
        // Same repair as the allowlist below, for the strict-mode flag: a toggle
        // made before the service bound lives only in the mirror, and adopting
        // the older remote value here would silently revert it.
        if (Prefs.hasPendingStrictPush(this)) {
            Prefs.writeStrictMode(this, prefs, strictMode)
        }
        if (Prefs.hasPendingPush(this)) {
            // A check made before the service bound is newer than the remote set:
            // push it up (the write broadcasts, so system_server re-reads too)
            // instead of adopting the stale value, which used to silently revert
            // the user's selection.
            Prefs.writeAllowlist(this, prefs, allowlist)
        } else {
            var next = Prefs.readAllowlist(prefs)
            // Remote is authoritative, but an empty remote with a populated local
            // mirror means the allowlist was imported before the service bound
            // (About screen): push the mirror up instead of wiping it.
            if (next.isEmpty()) {
                val local = Prefs.readLocalAllowlist(this)
                if (local.isNotEmpty()) {
                    Prefs.writeAllowlist(this, prefs, local)
                    next = local
                }
            } else {
                Prefs.writeLocalAllowlist(this, next)
            }
            allowlist = next
            // This runs on every app open, so it also repairs a system_server copy
            // that missed its broadcast (e.g. one sent during early boot).
            Prefs.broadcastAllowlistChanged(this)
        }
        for (app in allApps) {
            app.checked = allowlist.contains(app.packageName)
        }
        sortApps()
        filterApps(currentQuery)
    }

    private fun updateAllowlist() {
        // Remote prefs are what the hooks read, so this write plus the broadcast it
        // sends is the whole point: a check takes effect immediately, with no
        // refresh and no restart. When the module service is not bound yet,
        // writeAllowlist keeps the change in the mirror and flags it so the next
        // bind pushes it up rather than losing it.
        Prefs.writeAllowlist(this, remotePrefs(), allowlist)
    }

    /** True while `generation` is still the newest scan. */
    private fun isLatestScan(generation: Int): Boolean {
        return appScanGeneration.get() == generation
    }

    private fun loadApps() {
        val showSys = showSystemApps
        val allow = HashSet(allowlist)
        val emptyUi = allApps.isEmpty()
        // Read on this thread: it gates whether the scan may be cached below.
        val readable = isAppListReadable()
        val generation = appScanGeneration.incrementAndGet()
        Thread {
            val pm = packageManager
            try {
                val selected = ArrayList<AppListAdapter.AppEntry>()
                for (pkg in allow) {
                    val ai = try {
                        pm.getApplicationInfo(pkg, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        continue
                    }
                    val entry = AppListAdapter.AppEntry(pkg, ai.loadLabel(pm).toString())
                    entry.checked = true
                    selected.add(entry)
                }
                selected.sortWith { a, b -> compareEntries(a, b) }
                // First open only: show allowlisted apps before the full query returns.
                if (emptyUi && selected.isNotEmpty() && isLatestScan(generation)) {
                    runOnUiThreadSafe { applyAppSnapshot(selected, false) }
                }

                // No GET_RECEIVERS / GET_SERVICES: the FCM question is answered
                // by two device-wide queries plus a per-package class lookup
                // below, and without those flags the scan marshals a lot less.
                val installed = pm.getInstalledPackages(0)
                val scannedPackages = ArrayList<String>(installed.size)
                for (pi in installed) {
                    scannedPackages.add(pi.packageName)
                }
                val support = scanPushSupport(pm, scannedPackages)
                // A newer scan started while this one was querying; its answer
                // is already on the way, so abandon the rest of the work.
                if (!isLatestScan(generation)) {
                    return@Thread
                }
                val result = ArrayList<AppListAdapter.AppEntry>()
                for (pi in installed) {
                    val ai = pi.applicationInfo
                    if (ai == null || ai.packageName == packageName) {
                        continue
                    }
                    if (!showSys && isSystemApp(ai)) {
                        continue
                    }
                    val entry = AppListAdapter.AppEntry(ai.packageName, ai.loadLabel(pm).toString())
                    entry.supportFcm = support.fcm.contains(ai.packageName)
                    entry.supportMiPush = support.miPush.contains(ai.packageName)
                    result.add(entry)
                }
                for (app in result) {
                    app.checked = allow.contains(app.packageName)
                }
                result.sortWith { a, b -> compareEntries(a, b) }
                // Remember the scan for a configuration change. Only a readable
                // scan counts: on HyperOS the first query runs before the
                // app-list permission is answered and returns almost nothing, and
                // caching that would make a rotation show an empty list forever.
                if (readable && result.isNotEmpty() && isLatestScan(generation)) {
                    sAppScanCache = ArrayList(result)
                    sAppScanCacheShowSystemApps = showSys
                }
                runOnUiThreadSafe {
                    if (!isLatestScan(generation)) {
                        return@runOnUiThreadSafe
                    }
                    applyAppSnapshot(result, true)
                    // Xposed remote prefs may bind after the first package query;
                    // re-read allowlist so checked apps stay on top after update.
                    reloadAllowlist()
                }
            } catch (t: Throwable) {
                // The package query is a binder call on the whole installed set;
                // it can fail (a very large package list is one documented
                // cause). Swallowed here would leave the refresh spinner running
                // forever with no list and no way back but killing the app, so
                // the failure is logged and the spinner is stopped below.
                Log.w(TAG_UI, "Failed to load the app list", t)
            } finally {
                runOnUiThreadSafe {
                    // A newer scan is still running and owns the spinner.
                    if (isLatestScan(generation) && swipeRefresh != null) {
                        swipeRefresh!!.isRefreshing = false
                    }
                }
            }
        }.start()
    }

    /**
     * Swap the visible list once. Skips notify when nothing changed, and keeps
     * scroll position so refresh does not "flash" or jump.
     */
    private fun applyAppSnapshot(next: List<AppListAdapter.AppEntry>, stopRefresh: Boolean) {
        val listView = findViewById<ListView>(R.id.app_list)
        var firstPos = 0
        var firstTop = 0
        if (listView != null) {
            firstPos = listView.firstVisiblePosition
            val child = listView.getChildAt(0)
            firstTop = child?.top ?: 0
        }

        // Always re-sync from the live allowlist — loadApps may have started
        // before libxposed bound and read remote prefs.
        val live = allowlist
        for (app in next) {
            app.checked = live.contains(app.packageName)
        }
        val ordered = ArrayList(next)
        ordered.sortWith { a, b -> compareEntries(a, b) }

        val changed = !sameAppSnapshot(allApps, ordered)
        if (stopRefresh) {
            packagesReady = true
        }
        if (changed) {
            allApps.clear()
            allApps.addAll(ordered)
            filterApps(currentQuery)
            listView?.setSelectionFromTop(firstPos, firstTop)
        } else if (stopRefresh) {
            maybeToastNoFcmApps()
        }

        if (stopRefresh && swipeRefresh != null) {
            swipeRefresh!!.isRefreshing = false
        }
    }

    companion object {
        private const val TAG_UI = "HyperFCMLive"
        /** MIUI 13 / HyperOS runtime gate on top of QUERY_ALL_PACKAGES. */
        private const val GET_INSTALLED_APPS_PERMISSION =
            "com.android.permission.GET_INSTALLED_APPS"
        private const val MIUI_SECURITY_PACKAGE = "com.lbe.security.miui"
        private const val REQUEST_GET_INSTALLED_APPS = 1001

        /**
         * Tapping apps one by one into the allowlist looks like this: fill the
         * window with adds, then tell the user the long-press multi-select exists —
         * that is the gesture they were doing by hand. A gap longer than the window
         * restarts the count, so a slow browse never triggers the tip.
         */
        private const val RAPID_CHECK_WINDOW_MS = 12000L
        private const val RAPID_CHECK_HINT_AT = 4

        private const val KEY_STATE_QUERY = "state_query"
        private const val KEY_STATE_SEARCHING = "state_searching"
        private const val KEY_STATE_MULTI_SELECT = "state_multi_select"
        private const val KEY_STATE_SELECTION = "state_selection"
        private const val KEY_STATE_SHOW_SYSTEM = "state_show_system"

        /** Typing is coalesced over this window: one filter pass per burst, not per key. */
        private const val FILTER_DEBOUNCE_MS = 150L

        /**
         * The service the MiPush SDK merges into the host Manifest. Its presence —
         * enabled or not — is the MiPush test: no device-wide query can see it (it
         * declares no intent-filter), so packages are asked one by one in
         * [declaresMiPushService], with disabled components included.
         */
        private const val MIPUSH_SERVICE_CLASS = "com.xiaomi.push.service.XMPushService"

        /**
         * Last full package scan, reused across a configuration change. A rotation or
         * a theme/language rebuild recreates this activity, and re-running
         * `getInstalledPackages()` plus a label load per app to produce a
         * result that cannot have changed is the most expensive thing this screen
         * does. Dropped in [onDestroy] when the screen is really finishing, so
         * the icons it pins go with it and a fresh open always re-scans.
         */
        @Volatile
        private var sAppScanCache: List<AppListAdapter.AppEntry>? = null

        @Volatile
        private var sAppScanCacheShowSystemApps = false

        private fun compareEntries(a: AppListAdapter.AppEntry, b: AppListAdapter.AppEntry): Int {
            if (a.checked != b.checked) {
                return if (a.checked) -1 else 1
            }
            val c = a.label.compareTo(b.label, ignoreCase = true)
            return if (c != 0) c else a.packageName.compareTo(b.packageName)
        }

        /**
         * Packages that look FCM-capable: any one of four Manifest markers is
         * enough.
         *
         * 1. `com.google.firebase.messaging.FirebaseMessagingService` — a
         *    declared service (class name).
         * 2. `com.google.firebase.iid.FirebaseInstanceIdReceiver` — a
         *    declared receiver (class name).
         * 3. `com.google.firebase.MESSAGING_EVENT` — an intent-filter
         *    action, normally on the messaging service.
         * 4. `com.google.android.c2dm.intent.RECEIVE` — an intent-filter
         *    action, normally on the instance-id receiver.
         *
         * The names are `Hooker`'s constants, and the module asks the same
         * four questions in system_server (`Hooker#declaresFcmComponent`) —
         * asking it the same way is the point.
         */
        private fun scanPushSupport(
            pm: PackageManager,
            candidates: Collection<String>?
        ): PushSupport {
            val support = PushSupport()
            val packages = support.fcm
            try {
                val services = pm.queryIntentServices(
                    Intent(Hooker.ACTION_MESSAGING_EVENT),
                    PackageManager.ResolveInfoFlags.of(0)
                )
                for (ri in services) {
                    // Services resolve into serviceInfo; activityInfo is the
                    // receiver/activity field and stays null here.
                    packages.add(ri.serviceInfo.packageName)
                }
            } catch (t: Throwable) {
                Log.w(TAG_UI, "Failed to query FCM messaging services", t)
            }
            try {
                val receivers = pm.queryBroadcastReceivers(
                    Intent(Hooker.ACTION_REMOTE_INTENT),
                    PackageManager.ResolveInfoFlags.of(0)
                )
                for (ri in receivers) {
                    packages.add(ri.activityInfo.packageName)
                }
            } catch (t: Throwable) {
                Log.w(TAG_UI, "Failed to query C2DM receivers", t)
            }
            if (candidates != null) {
                for (pkg in candidates) {
                    if (!packages.contains(pkg) && declaresFcmComponent(pm, pkg)) {
                        packages.add(pkg)
                    }
                    if (declaresMiPushService(pm, pkg)) {
                        support.miPush.add(pkg)
                    }
                }
            }
            return support
        }

        /**
         * Whether `pkg` ships MiPush: it declares the SDK's push service.
         *
         * MiPush is a system-channel push, so an app that has it does not need
         * this module to keep a second (FCM) route alive — the list tags those apps
         * and the overflow menu can leave them out.
         *
         * The lookup has to ask for disabled components, because on MIUI /
         * HyperOS a disabled `XMPushService` means the opposite of what it
         * looks like: MiPush is not an SDK that keeps its own connection, and a
         * disabled state is the sign that MiPush is *live* for that app.
         */
        private fun declaresMiPushService(pm: PackageManager, pkg: String): Boolean {
            return try {
                pm.getServiceInfo(
                    ComponentName(pkg, MIPUSH_SERVICE_CLASS),
                    PackageManager.MATCH_DISABLED_COMPONENTS or
                        PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                )
                true
            } catch (ignored: Throwable) {
                // Not declared (or not visible to us): no MiPush.
                false
            }
        }

        /**
         * Whether `pkg` declares either Firebase class under its own name.
         *
         * `getServiceInfo` / `getReceiverInfo` resolve a component
         * directly, with no intent-filter involved — which is the whole reason this
         * exists: the action queries above cannot see a class that ships without
         * one. Absence is reported by `NameNotFoundException`, so a throw is
         * an answer, not a failure.
         */
        private fun declaresFcmComponent(pm: PackageManager, pkg: String): Boolean {
            try {
                pm.getServiceInfo(ComponentName(pkg, Hooker.FCM_MESSAGING_SERVICE_CLASS), 0)
                return true
            } catch (ignored: Throwable) {
                // Not declared (or not visible to us): fall through.
            }
            try {
                pm.getReceiverInfo(ComponentName(pkg, Hooker.FCM_IID_RECEIVER_CLASS), 0)
                return true
            } catch (ignored: Throwable) {
                // Not declared.
            }
            return false
        }

        private fun sameAppSnapshot(
            a: List<AppListAdapter.AppEntry>,
            b: List<AppListAdapter.AppEntry>
        ): Boolean {
            if (a.size != b.size) {
                return false
            }
            for (i in a.indices) {
                val x = a[i]
                val y = b[i]
                if (x.packageName != y.packageName || x.checked != y.checked ||
                    x.supportFcm != y.supportFcm ||
                    x.supportMiPush != y.supportMiPush ||
                    x.label != y.label
                ) {
                    return false
                }
            }
            return true
        }
    }

    /** One package scan: which packages carry which push route. */
    private class PushSupport {
        /** FCM-capable: any of the four Firebase markers. */
        val fcm = HashSet<String>()

        /** Declares [MIPUSH_SERVICE_CLASS]. */
        val miPush = HashSet<String>()
    }
}
