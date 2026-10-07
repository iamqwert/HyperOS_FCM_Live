package io.github.howard20181.hyperos.fcmlive

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.howard20181.hyperos.fcmlive.theme.AppPalette
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.AppListStore
import io.github.howard20181.hyperos.fcmlive.ui.MainActions
import io.github.howard20181.hyperos.fcmlive.ui.MainScreen
import io.github.howard20181.hyperos.fcmlive.ui.MainTopBarState
import io.github.howard20181.hyperos.fcmlive.ui.OverflowState
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Settings screen: pick which apps FCM may wake / auto-launch.
 * MD3-inspired card list; search + overflow (system apps / hide icon) in the
 * top bar; FAB opens GMS FCM diagnostics.
 */
class MainActivity : AppCompatActivity() {

    private val allApps = ArrayList<AppListStore.AppEntry>()
    /**
     * What the list draws. A snapshot list rather than a plain ArrayList: the
     * rows are composed from it, so the mutations here are what recomposes them.
     */
    private val filteredApps = mutableStateListOf<AppListStore.AppEntry>()
    private var allowlist: Set<String> = HashSet()

    /** Row icons: the only piece of the old adapter that survived. */
    private var store: AppListStore? = null

    /** Multi-select staging set. Applied to the allowlist only on a batch action. */
    private val selectedPkgs = LinkedHashSet<String>()

    /** Compose reads these; every UI change goes through [pushUiState]. */
    private var topBarState by mutableStateOf(MainTopBarState(title = ""))
    private var multiSelectUi by mutableStateOf(false)
    private var selectedUi by mutableStateOf<Set<String>>(emptySet())

    /**
     * The M3 feedback line. Owned here because every message on this screen
     * originates here; handed to the tree, which is what actually draws it.
     */
    private val snackbarHostState = SnackbarHostState()

    /**
     * Scope for the one asynchronous thing this screen owns outside the tree:
     * showing a message. Cancelled with the Activity, so a line that is still
     * queued cannot outlive the window it was going to be drawn in.
     */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Pull-to-refresh is showing its indicator; the scan owns when it stops. */
    private var refreshing by mutableStateOf(false)

    /**
     * The live search text. Compose state, not a plain field: the search field
     * reads it directly, so the text survives the field leaving composition —
     * which is what exit and multi-select do — and a restore re-composes the
     * field already holding it. Clearing it is just an assignment.
     */
    private var currentQuery by mutableStateOf("")
    /** The Compose host; TalkBack announcements need a real View. */
    private var appListHost: View? = null
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
    private var showSystemApps = false

    /** Empty-list message is delayed and cancelled if a rescan fills the list. */
    private val emptyListMessageRunnable = Runnable {
        emptyListMessagePosted = false
        if (!packagesReady || currentQuery.isNotEmpty()) return@Runnable
        if (filteredApps.isNotEmpty() || allApps.isEmpty()) return@Runnable
        if (!showFcmSupportedOnly && !excludeMiPushApps) return@Runnable
        if (!isAppListReadable()) return@Runnable
        showMessage(
            getString(
                if (showFcmSupportedOnly) R.string.no_fcm_apps_found else R.string.no_apps_found
            )
        )
    }
    private var emptyListMessagePosted = false

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
     * at least one app is checked (`Hooker#moduleAppliesTo`). Read from the
     * local mirror here; the live copy the hooks read lives in remote prefs.
     */
    private var strictMode = false
    private var xposedService: XposedService? = null
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

    /**
     * GET_INSTALLED_APPS (MIUI/HyperOS). Result is handled inline — the scan
     * always restarts after the dialog, granted or not, so a partial list is
     * still usable and the screen never blocks on the answer.
     */
    private val requestInstalledAppsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showMessage(getString(R.string.installed_apps_permission_denied))
            }
            loadApps()
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeSupport.attach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        appliedPalette = ThemeEngine.palette(this)
        // Row icons are loaded off-thread by the store; when a batch lands it
        // re-emits the visible list so the rows recompose with their icons.
        store = AppListStore(this) {
            runOnUiThreadSafe { refreshVisibleRows() }
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

        // Refresh launcher shortcut icons (MIUI caches static shortcuts).
        ShortcutPublisher.publish(this)

        // Launcher long-press shortcuts (see res/xml/shortcuts.xml).
        handleShortcutIntent(intent)

        installContent()
        // The bar used to get its title from the layout; nothing paints it until
        // the first state push, so do that before anything can be drawn empty.
        pushUiState()

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

    /**
     * Host the whole page in one Compose tree.
     *
     * Everything it shows comes from state and everything it does comes back
     * through these callbacks — so none of the multi-select / search logic
     * below had to change to feed it. The search text is `currentQuery` itself:
     * the field reads it, and every keystroke lands back in [onQueryTextChange].
     *
     * The list's `LazyListState` is deliberately not built here. Compose only
     * saves what a composition created, so a state handed in from the outside
     * is never restored — the list came back at the top after a rotation, the
     * opposite of what building it here was meant to achieve. The screen
     * remembers its own.
     */
    private fun installContent() {
        val host = ComposeView(this)
        appListHost = host
        setContentView(host)
        host.setContent {
            HyperFCMLiveTheme {
                MainScreen(
                    topBarState = topBarState,
                    actions = MainActions(
                        onBack = { if (multiSelectMode) exitMultiSelect() else exitSearch() },
                        onSearch = { enterSearch() },
                        onBatchAdd = { applyBatchAllowlist(true) },
                        onBatchRemove = { applyBatchAllowlist(false) },
                        onSelectAll = { toggleSelectAllVisible() },
                        onAbout = { startActivity(Intent(this, AboutActivity::class.java)) },
                        onToggleShowSystemApps = { toggleOverflowShowSystemApps() },
                        onToggleShowFcmOnly = { toggleOverflowShowFcmOnly() },
                        onToggleExcludeMiPush = { toggleOverflowExcludeMiPush() },
                        onToggleStrictMode = { toggleOverflowStrictMode() }
                    ),
                    // The field reads the query from this state, so no view
                    // hand-off is needed: whatever currentQuery holds when the
                    // bar composes (including a restore across a rotation) is
                    // what it shows.
                    query = currentQuery,
                    onQueryChange = { onQueryTextChange(it) },
                    apps = filteredApps,
                    multiSelect = multiSelectUi,
                    selected = selectedUi,
                    onRowClick = { handleRowTap(it) },
                    onRowLongClick = { handleRowLongPress(it) },
                    loadIcon = { store?.loadIcon(it) },
                    refreshing = refreshing,
                    onRefresh = { refreshing = true; loadApps() },
                    onDiagnostics = { openFcmDiagnostics() },
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }

    /**
     * One transient line of feedback, in the M3 way.
     *
     * Every message this screen produced used to be a `Toast`: a second
     * feedback stack that no theme could reach, raised outside this window and
     * invisible to the tree around it. A snackbar is drawn by the page it
     * belongs to and follows that page's palette, which is the whole point.
     */
    private fun showMessage(text: String) {
        uiScope.launch {
            // A burst of taps replaces the line rather than queueing behind it.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    /**
     * Publish everything the Compose half reads, after every change to the flags
     * below. Replaces what used to be per-View visibility calls: one state
     * object now decides what the bar looks like.
     */
    private fun pushUiState() {
        multiSelectUi = multiSelectMode
        selectedUi = HashSet(selectedPkgs)
        topBarState = MainTopBarState(
            title = if (multiSelectMode) {
                getString(R.string.selected_count, selectedPkgs.size)
            } else {
                getString(R.string.settings_title)
            },
            searching = searching,
            multiSelect = multiSelectMode,
            allVisibleSelected = isAllVisibleSelected(),
            overflow = OverflowState(
                showSystemApps, showFcmSupportedOnly, excludeMiPushApps, strictMode
            )
        )
    }

    /**
     * Re-emit the visible rows so they recompose with the icons that just landed.
     * `AppEntry.icon` is a plain field, so nothing else can tell the rows it
     * changed — this replaces the `notifyDataSetChanged` the adapter used to send.
     */
    private fun refreshVisibleRows() {
        if (filteredApps.isEmpty()) {
            return
        }
        val snapshot = filteredApps.toList()
        filteredApps.clear()
        filteredApps.addAll(snapshot)
    }

    /** Normal-mode tap toggles the allowlist; a multi-select tap only stages. */
    private fun handleRowTap(app: AppListStore.AppEntry) {
        if (multiSelectMode) {
            toggleSelected(app.packageName)
            return
        }
        val next = !app.checked
        app.checked = next
        refreshVisibleRows()
        A11yUtils.announce(
            appListHost,
            getText(if (next) R.string.status_yes else R.string.status_no)
        )
        if (next) {
            allowlist = HashSet(allowlist).also { it.add(app.packageName) }
            perhapsAdvertiseMultiSelect()
        } else {
            allowlist = HashSet(allowlist).also { it.remove(app.packageName) }
        }
        updateAllowlist()
        // Stay in place on tap. Order refreshes on pull-to-refresh / reopen.
        for (item in allApps) {
            if (item.packageName == app.packageName) {
                item.checked = next
                break
            }
        }
    }

    private fun handleRowLongPress(app: AppListStore.AppEntry) {
        if (multiSelectMode) {
            toggleSelected(app.packageName)
        } else {
            enterMultiSelect(app.packageName)
        }
    }

    private fun toggleSelected(packageName: String) {
        if (!selectedPkgs.add(packageName)) {
            selectedPkgs.remove(packageName)
        }
        pushUiState()
    }

    private fun toggleOverflowShowSystemApps() {
        showSystemApps = !showSystemApps
        pushUiState()
        loadApps()
    }

    private fun toggleOverflowShowFcmOnly() {
        showFcmSupportedOnly = !showFcmSupportedOnly
        getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(Prefs.KEY_SHOW_FCM_ONLY, showFcmSupportedOnly)
            .apply()
        // Filter only — keep package scan; toggle just hides non-FCM rows.
        filterApps(currentQuery)
    }

    private fun toggleOverflowExcludeMiPush() {
        excludeMiPushApps = !excludeMiPushApps
        getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(Prefs.KEY_EXCLUDE_MIPUSH, excludeMiPushApps)
            .apply()
        // Filter only — the package scan stands, and so does every allowlist
        // entry: this toggle decides what is offered, not what the module
        // already does for an app.
        filterApps(currentQuery)
    }

    private fun toggleOverflowStrictMode() {
        strictMode = !strictMode
        // Written to the remote group the hooks read, then announced with the
        // same broadcast as a list edit, so it is live at once. Nothing in the
        // list changes: the toggle only decides what the module does for apps
        // that are not checked.
        Prefs.writeStrictMode(this, remotePrefs(), strictMode)
        pushUiState()
    }

    /** Launch-time update check: a line of feedback only (About keeps its badge). */
    private fun startUpdateCheck() {
        UpdateChecker.checkAutoAsync(this, object : UpdateChecker.Callback {
            override fun onResult(
                updateAvailable: Boolean,
                latestVersion: String,
                downloadUrl: String
            ) {
                if (updateAvailable) {
                    runOnUiThreadSafe {
                        showMessage(getString(R.string.update_found, latestVersion))
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
        requestInstalledAppsLauncher.launch(GET_INSTALLED_APPS_PERMISSION)
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
     * This is deliberately a live check rather than a flag set from the
     * permission callback: the Xposed service can bind and kick
     * off a scan before the user has even answered the dialog.
     */
    private fun isAppListReadable(): Boolean {
        return !hasAppListGate() ||
            checkSelfPermission(GET_INSTALLED_APPS_PERMISSION) == PackageManager.PERMISSION_GRANTED
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
        // on top (palette style, theme mode, seed color...). The Compose tree
        // follows ThemeEngine's generation counter and re-skins on its own;
        // what recomposes nowhere is the View-side window chrome. Repaint that
        // here instead of rebuilding the whole activity — the old `recreate()`
        // read as a jump on return from the settings screen.
        val palette = ThemeEngine.palette(this)
        if (appliedPalette != null && palette !== appliedPalette) {
            ThemeSupport.reapplyWindow(this)
            appliedPalette = palette
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE_QUERY, currentQuery)
        outState.putBoolean(KEY_STATE_SEARCHING, searching)
        outState.putBoolean(KEY_STATE_MULTI_SELECT, multiSelectMode)
        outState.putBoolean(KEY_STATE_SHOW_SYSTEM, showSystemApps)
        if (multiSelectMode) {
            outState.putStringArrayList(KEY_STATE_SELECTION, ArrayList(selectedPkgs))
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
            saved.getStringArrayList(KEY_STATE_SELECTION)?.let { selection ->
                // Restored whole, as before: the list has not been scanned yet at
                // this point, so filtering by "is it loaded" would drop the
                // selection outright.
                selectedPkgs.clear()
                selectedPkgs.addAll(selection)
            }
            pushUiState()
            return
        }
        if (saved.getBoolean(KEY_STATE_SEARCHING, false)) {
            enterSearch()
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
        cancelEmptyListMessage()
        uiScope.cancel()
        pendingFilter?.let {
            uiHandler.removeCallbacks(it)
            pendingFilter = null
        }
        store?.shutdown()
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

    /** Title becomes an inline search field; everything else is state-driven. */
    private fun enterSearch() {
        if (multiSelectMode) {
            exitMultiSelect()
        }
        searching = true
        updateBackCallback()
        // Keep list height stable so the scrollbar does not jump when IME opens.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        pushUiState()
    }

    private fun exitSearch() {
        searching = false
        updateBackCallback()
        // Leaving search disposes the field, which drops focus and lowers the
        // IME on its own; clearing the state is all that is left to do.
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
            showMessage(getString(R.string.multi_select_tip))
        }
    }

    private fun enterMultiSelect(firstPackage: String?) {
        // The gesture is known from here on: stop advertising it.
        multiSelectKnown = true
        if (searching) {
            searching = false
            currentQuery = ""
            filterApps("")
        }
        multiSelectMode = true
        updateBackCallback()
        selectedPkgs.clear()
        if (firstPackage != null) {
            selectedPkgs.add(firstPackage)
        }
        pushUiState()
    }

    private fun exitMultiSelect() {
        multiSelectMode = false
        updateBackCallback()
        selectedPkgs.clear()
        pushUiState()
    }

    /** All currently visible (filtered) rows are selected → show deselect-all icon. */
    private fun isAllVisibleSelected(): Boolean {
        if (filteredApps.isEmpty()) {
            return false
        }
        for (app in filteredApps) {
            if (!selectedPkgs.contains(app.packageName)) {
                return false
            }
        }
        return true
    }

    /**
     * One control, two states: select-all → tap selects every visible row; when
     * all are selected the icon flips to deselect-all. Both the icon and its
     * description come from `allVisibleSelected` in [MainTopBarState].
     */
    private fun toggleSelectAllVisible() {
        if (!multiSelectMode) {
            return
        }
        if (isAllVisibleSelected()) {
            for (app in filteredApps) {
                selectedPkgs.remove(app.packageName)
            }
        } else {
            for (app in filteredApps) {
                selectedPkgs.add(app.packageName)
            }
        }
        pushUiState()
    }

    /**
     * Apply selected packages to the allowlist. Selection is a staging set;
     * the whitelist only changes when the user taps a batch action.
     */
    private fun applyBatchAllowlist(add: Boolean) {
        if (!multiSelectMode) {
            return
        }
        val selected = HashSet(selectedPkgs)
        if (selected.isEmpty()) {
            showMessage(getString(R.string.batch_nothing_selected))
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
        refreshVisibleRows()
        showMessage(getString(R.string.batch_added))
        exitMultiSelect()
    }

    /**
     * The search field's text callback. Coalesce: one pass walks every app
     * twice, and the list cannot usefully change faster than the user reads
     * it, so a burst of keystrokes costs one filter instead of one per
     * character.
     */
    private fun onQueryTextChange(newText: String?) {
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
        // filteredApps is a snapshot list: this is what recomposes the rows.
        maybeShowNoFcmApps()
        pushUiState()
    }

    /**
     * Empty list after a full package scan (no search query), where one of the
     * two overflow filters is what emptied it.
     *
     * The toast is delayed: a permission grant mid-scan can finish an empty
     * query first, and saying "no FCM apps" then would fire before the real
     * list lands. Cancelled when a later snapshot fills the list.
     */
    private fun maybeShowNoFcmApps() {
        cancelEmptyListMessage()
        if (!packagesReady) {
            return
        }
        if (currentQuery.isNotEmpty()) {
            return
        }
        if (filteredApps.isNotEmpty() || allApps.isEmpty()) {
            return
        }
        // With both filters off an empty list means the scan found nothing, and
        // blaming a filter for that would be wrong.
        if (!showFcmSupportedOnly && !excludeMiPushApps) {
            return
        }
        if (!isAppListReadable()) {
            return
        }
        emptyListMessagePosted = true
        uiHandler.postDelayed(emptyListMessageRunnable, 1500)
    }

    private fun cancelEmptyListMessage() {
        if (emptyListMessagePosted) {
            uiHandler.removeCallbacks(emptyListMessageRunnable)
            emptyListMessagePosted = false
        }
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
                showMessage(getString(R.string.fcm_diagnostics_not_found))
            }
        }
    }

    /** Launcher shortcut entry: open GMS diagnostics immediately when asked. */
    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action == ACTION_FCM_DIAGNOSTICS) {
            openFcmDiagnostics()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
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
        val prefs = remotePrefs()
        // Keys of experiments this version no longer has, dropped from both
        // copies before anything reads them — see [Prefs.dropRetiredConfigKeys].
        // Called with a null handle too, so the mirror is cleaned even when the
        // module service never binds.
        Prefs.dropRetiredConfigKeys(this, prefs)
        // Same repair as the allowlist below, for the strict-mode flag: a toggle
        // made before the service bound lives only in the mirror, and adopting
        // the older remote value here would silently revert it.
        if (prefs == null) return
        if (Prefs.hasPendingStrictPush(this)) {
            Prefs.writeStrictMode(this, prefs, strictMode)
        }
        if (Prefs.hasPendingWechatDozeKeepoutPush(this)) {
            // And for the WeChat keepout switch: a flip made before the
            // service bound lives only in the local mirror.
            Prefs.writeWechatDozeKeepout(
                this, prefs, Prefs.readLocalWechatDozeKeepout(this)
            )
        }
        if (Prefs.hasPendingWifiWeakSignalSwitchRelaxedPush(this)) {
            // And for the relaxed WiFi weak-signal switch, which the experiment
            // screen owns: a flip made before the service bound lives only in
            // the local mirror.
            Prefs.writeWifiWeakSignalSwitchRelaxed(
                this, prefs, Prefs.readLocalWifiWeakSignalSwitchRelaxed(this)
            )
        }
        if (Prefs.hasPendingWifiWeakSignalFloorPush(this)) {
            // Same repair for that switch's floor. Without it a choice made
            // before the service bound would stay local forever, and the hook
            // would go on using the default — silently wider or narrower than
            // what the screen says it is using.
            Prefs.writeWifiWeakSignalFloor(
                this, prefs, Prefs.readLocalWifiWeakSignalFloor(this)
            )
        }
        if (Prefs.hasPendingSleepKeepalivePush(this)) {
            // Same repair for the sleep-keepalive switch, which the experiment
            // screen owns; without this a flip made before the service bound
            // would be reverted here rather than pushed up.
            Prefs.writeSleepKeepalive(this, prefs, Prefs.readLocalSleepKeepalive(this))
        }
        if (Prefs.hasPendingSleepKeepaliveDataPush(this)) {
            // And for its mobile-data sub-switch.
            Prefs.writeSleepKeepaliveData(
                this, prefs, Prefs.readLocalSleepKeepaliveData(this)
            )
        }
        if (Prefs.hasPendingWakeWriteAutostartPush(this)) {
            // Same repair for the autostart-write switch, which the experiment
            // screen owns; without this a flip made before the service bound
            // would be reverted here rather than pushed up.
            Prefs.writeWakeWriteAutostart(
                this, prefs, Prefs.readLocalWakeWriteAutostart(this)
            )
        }
        if (Prefs.hasPendingAutostartGateReleasePush(this)) {
            // Same repair for the autostart-gate switch, which the experiment
            // screen owns; without this a flip made before the service bound
            // would be reverted here rather than pushed up.
            Prefs.writeAutostartGateRelease(
                this, prefs, Prefs.readLocalAutostartGateRelease(this)
            )
        }
        if (Prefs.hasPendingAutostartRestartReleasePush(this)) {
            // Same repair for the restart-gate rung.
            Prefs.writeAutostartRestartRelease(
                this, prefs, Prefs.readLocalAutostartRestartRelease(this)
            )
        }
        if (Prefs.hasPendingAutostartRootReleasePush(this)) {
            // Same repair for the AppOps root rung.
            Prefs.writeAutostartRootRelease(
                this, prefs, Prefs.readLocalAutostartRootRelease(this)
            )
        }
        if (Prefs.hasPendingWakeWriteAutostartSwitchPush(this)) {
            // Same repair for the switch-write rung, which rides the
            // autostart-write walk.
            Prefs.writeWakeWriteAutostartSwitch(
                this, prefs, Prefs.readLocalWakeWriteAutostartSwitch(this)
            )
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
                val selected = ArrayList<AppListStore.AppEntry>()
                for (pkg in allow) {
                    val ai = try {
                        pm.getApplicationInfo(pkg, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        continue
                    }
                    val entry = AppListStore.AppEntry(pkg, ai.loadLabel(pm).toString())
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
                val result = ArrayList<AppListStore.AppEntry>()
                for (pi in installed) {
                    val ai = pi.applicationInfo
                    if (ai == null || ai.packageName == packageName) {
                        continue
                    }
                    if (!showSys && isSystemApp(ai)) {
                        continue
                    }
                    val entry = AppListStore.AppEntry(ai.packageName, ai.loadLabel(pm).toString())
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
                    // A newer scan is still running and owns the indicator.
                    if (isLatestScan(generation)) {
                        refreshing = false
                    }
                }
            }
        }.start()
    }

    /**
     * Swap the visible list once. Skips pushing when nothing changed, so a
     * refresh does not recompose — and therefore does not "flash" — for nothing.
     *
     * Scroll position: rows are keyed by package name, and that is not the whole
     * story — a keyed `LazyColumn` keeps the first visible *row* in place when
     * the order changes, not the index, so a check that moves a row moves the
     * viewport with it. For a refresh that is exactly the wrong outcome (the
     * unchecked row drops to its alphabetical place and drags the page down);
     * the list therefore goes back to the top when the refresh ends. See the
     * `LaunchedEffect` in `MainScreen`.
     */
    private fun applyAppSnapshot(next: List<AppListStore.AppEntry>, stopRefresh: Boolean) {
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
        } else if (stopRefresh) {
            maybeShowNoFcmApps()
        }

        if (stopRefresh) {
            refreshing = false
            // TalkBack: the pull-to-refresh indicator is visual-only.
            A11yUtils.announce(appListHost, getString(R.string.app_list_refreshed))
        }
    }

    companion object {
        private const val TAG_UI = "HyperFCMLive"

        /** Launcher shortcut: open GMS FCM diagnostics. */
        const val ACTION_FCM_DIAGNOSTICS = "io.github.howard20181.hyperos.fcmlive.FCM_DIAGNOSTICS"
        /** MIUI 13 / HyperOS runtime gate on top of QUERY_ALL_PACKAGES. */
        private const val GET_INSTALLED_APPS_PERMISSION =
            "com.android.permission.GET_INSTALLED_APPS"
        private const val MIUI_SECURITY_PACKAGE = "com.lbe.security.miui"

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
        private var sAppScanCache: List<AppListStore.AppEntry>? = null

        @Volatile
        private var sAppScanCacheShowSystemApps = false

        private fun compareEntries(a: AppListStore.AppEntry, b: AppListStore.AppEntry): Int {
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
            a: List<AppListStore.AppEntry>,
            b: List<AppListStore.AppEntry>
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
