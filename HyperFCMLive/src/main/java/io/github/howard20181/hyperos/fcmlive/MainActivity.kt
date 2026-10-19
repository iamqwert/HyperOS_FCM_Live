package io.github.howard20181.hyperos.fcmlive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.appcompat.app.AppCompatActivity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
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
import io.github.howard20181.hyperos.fcmlive.nav.Navigator
import io.github.howard20181.hyperos.fcmlive.nav.Route
import io.github.howard20181.hyperos.fcmlive.theme.AppPalette
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemePrefs
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.AboutActions
import io.github.howard20181.hyperos.fcmlive.ui.AboutScreen
import io.github.howard20181.hyperos.fcmlive.ui.AboutTabScreen
import io.github.howard20181.hyperos.fcmlive.ui.AppListStore
import io.github.howard20181.hyperos.fcmlive.ui.ExperimentScreen
import io.github.howard20181.hyperos.fcmlive.ui.HomeShell
import io.github.howard20181.hyperos.fcmlive.ui.HomeTab
import io.github.howard20181.hyperos.fcmlive.ui.LicensesScreen
import io.github.howard20181.hyperos.fcmlive.ui.MiuixHomeShell
import io.github.howard20181.hyperos.fcmlive.ui.MainActions
import io.github.howard20181.hyperos.fcmlive.ui.MainScreen
import io.github.howard20181.hyperos.fcmlive.ui.MainTopBarState
import io.github.howard20181.hyperos.fcmlive.ui.ModuleStatusScreen
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
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Settings screen: pick which apps FCM may wake / auto-launch.
 * MD3-inspired card list; search + overflow (FCM-supported / MiPush / system
 * apps) in the top bar; FAB opens GMS FCM diagnostics.
 */
class MainActivity : AppCompatActivity() {

    private val allApps = ArrayList<AppListStore.AppEntry>()
    /**
     * What the list draws. A snapshot list rather than a plain ArrayList: the
     * rows are composed from it, so the mutations here are what recomposes them.
     */
    private val filteredApps = mutableStateListOf<AppListStore.AppEntry>()

    /**
     * Row icons keyed by package, filled asynchronously by [store]. A per-package
     * snapshot map (not a field on the entry) so an icon landing during a scroll
     * recomposes only its own row instead of rebuilding the whole visible list.
     */
    private val iconsByPkg = androidx.compose.runtime.mutableStateMapOf<String, android.graphics.drawable.Drawable?>()
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
     * True while the Compose tree is wearing the Miuix shell.
     *
     * Read in composition ([installContent]) and refreshed on every style
     * change; [showMessage] is a plain function, so this field is its one
     * bridge to the current look. Without it the Miuix look would swallow
     * every message: that shell hosts no snackbar, so the M3 bar silently
     * never appears.
     */
    private var miuixFeedbackNow = false

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
        if (!excludeMiPushApps) return@Runnable
        showMessage(getString(R.string.no_apps_found))
    }
    private var emptyListMessagePosted = false

    /** Overflow: when true, apps that carry MiPush are left out of the list. */
    private var excludeMiPushApps = false

    private val xposedService: XposedService?
        get() = boundService

    /**
     * The M3 bottom-nav verdict on the module: true once the libxposed service
     * has bound. LSPosed hands the service to an enabled module in scope and
     * to nothing else, so the binding *is* the activation signal — the same
     * answer the module-status tab shows.
     *
     * Reads the process-level cache: the libxposed helper hands its binder to a
     * listener exactly once and never re-delivers it, so an Activity recreated
     * by a configuration change (dark-mode toggle) that only re-registered its
     * own listener would wait forever and show 未激活. The cache restores the
     * verdict the instant the new instance composes.
     */
    private var moduleActive by mutableStateOf(boundService != null)

    /**
     * The libxposed service's API level once bound, zero before. The status
     * tab wears it as a badge; zero keeps the badge off — a pill with nothing
     * behind it is decoration, not information.
     */
    private var moduleApiVersion by mutableStateOf(boundService?.apiVersion ?: 0)

    /**
     * "x.y.z (build)" as the bound service reports its framework, or empty
     * while no service is bound. The home shows it in the device rows.
     */
    private var moduleManagerVersion by mutableStateOf(boundService.let { service ->
        if (service == null) "" else runCatching {
            "${service.frameworkVersion} (${service.frameworkVersionCode})"
        }.getOrDefault("")
    })

    /**
     * The bottom-nav selection, shared by both looks. The tab survives every
     * way the system can rebuild this screen while the user is still "in" the
     * app: an in-place rebuild (language switch) reads the process-level
     * [retainedTab], and a process death while backgrounded reads the saved
     * instance state — returning to a backgrounded app is not an entry, and
     * resetting the tab on it read as the app having lost the user's place.
     * Only a genuine cold start — or a swipe-away from Recents, which discards
     * both channels — lands on the home tab.
     */
    private var homeTab by mutableStateOf(HomeTab.Modules)

    /**
     * The one write path for [homeTab]: the property AND the process-level
     * [retainedTab] holder, so a system-driven recreation in-place (language
     * switch) can re-enter on the tab the user was reading.
     */
    private fun selectTab(tab: HomeTab) {
        homeTab = tab
        retainedTab = tab
    }

    /**
     * The settings tab's feedback line and its Activity-bound capabilities —
     * the SAF contracts, the update check, the release opening — in one
     * [AboutDelegate]. Created in [onCreate]: the SAF contracts must register
     * before the window is STARTED.
     */
    private val settingsSnackbarHostState = SnackbarHostState()
    private lateinit var aboutDelegate: AboutDelegate

    /**
     * The live page stack, for the one path that is not composition: a launcher
     * shortcut relaunch names a home tab, so a sub-page left over from last time
     * has to be dropped before the tab is switched. Written from composition
     * (see [AppPages]), where the stack is remembered and therefore saved — which
     * is also why it cannot simply be built here.
     */
    private var navigator: Navigator? = null

    /** The module's own version, for the status tab's hero card. */
    private var moduleVersionName = ""
    private var moduleVersionCode = 0L

    /** The settings tab's update-row subtitle, computed once at creation. */
    private var settingsVersionLine = ""

    /** The about page hero's release tag ("v4.1.1 (53)"), computed once. */
    private var settingsVersionTag = ""

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

    // A language pick lands here instead of a recreate (configChanges in the
    // manifest): the framework re-reads the configuration into this activity's
    // resources, and the locale bump makes the Compose tree re-resolve its
    // strings — no window swap, no flash.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.onLocaleConfigChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSupport.onCreate(this)
        appliedPalette = ThemeEngine.palette(this)
        // Land back on the tab the user was reading (see [homeTab]): the
        // saved instance state — what a backgrounded process death hands
        // back — is authoritative; without a bundle, the process-level
        // [retainedTab] covers an in-place rebuild. A cold start reads null
        // from both and keeps the default.
        val savedTab = savedInstanceState?.getString(KEY_STATE_TAB)
            ?.let { name -> HomeTab.entries.firstOrNull { it.name == name } }
        (savedTab ?: retainedTab)?.let { homeTab = it }

        // The settings tab's Activity-bound half (SAF contracts, update check,
        // links). Must exist before the window is STARTED.
        aboutDelegate = AboutDelegate(this, ::showSettingsMessage)
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            moduleVersionName = info.versionName ?: ""
            moduleVersionCode = info.longVersionCode
        } catch (t: Throwable) {
            Log.w(TAG_UI, "Cannot read own package info", t)
        }
        settingsVersionLine = aboutDelegate.versionLine()
        settingsVersionTag = aboutDelegate.versionTag()
        // Row icons are loaded off-thread by the store; each coalesced batch
        // lands in the per-package map, recomposing only the rows that got one.
        store = AppListStore(this) { batch ->
            batch.forEach { (pkg, drawable) -> iconsByPkg[pkg] = drawable }
        }

        // Seed UI order from the local cache so allowlisted apps sit on top
        // immediately, before libxposed remote prefs bind.
        allowlist = Prefs.readLocalAllowlist(this)

        // First launch: the list only ever holds push-capable apps (the scan
        // queries the manifest-declared push actions), so the former
        // "show supported apps" filter lost its object and is gone.
        // On by default: an app that carries MiPush is already reachable through
        // the vendor channel, so offering it for the FCM wake list by default
        // would push the user toward work the module does not need to do. The
        // MiPush badge is still shown on a row the user deliberately surfaces by
        // unchecking the filter, so the tag stays discoverable.
        excludeMiPushApps = getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
            .getBoolean(Prefs.KEY_EXCLUDE_MIPUSH, true)
        // Off by default. Must come from SharedPreferences, not only from
        // onSaveInstanceState: that bundle survives a config change or a
        // system-initiated process death, but **not** a swipe-away from
        // Recents, so the checkbox used to reset on every relaunch — and took
        // the GMS row with it, which is the one row the screen exists for.
        showSystemApps = getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
            .getBoolean(Prefs.KEY_SHOW_SYSTEM, false)

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

        if (!restoreAppListFromCache()) {
            loadApps()
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
                // The feedback style follows the shell: Miuix acknowledges with
                // the system toast (its Scaffold hosts no snackbar), Material
                // with the page's own bar. Read here, in composition —
                // [showMessage] is a plain function and cannot read this itself.
                miuixFeedbackNow = LocalUiStyle.current.isMiuixBased
                AppPages()
            }
        }
    }

    /**
     * Every page of the app, in one stack, at one composition location.
     *
     * The home and whatever is open on top of it are entries in the same
     * [NavDisplay], so the page underneath stays composed and drawn instead of
     * being a stopped window — which is exactly what the back gesture and the
     * settle after its release move. One *location* matters as much as one stack:
     * the appearance switches what is composed around this, and rebuilding the
     * display there would drop the page the user is on.
     *
     * The transition is the library's own preset in both looks — the slide, the
     * quarter-width parallax and the settle the library ships — and the effects
     * are the ones KernelSU's manager passes with it (the clip radius read off the
     * device's screen corner, everything else at the library's defaults). KernelSU
     * is the reference for this: its manager hands `NavDisplay` no transition at
     * all and takes the default in *both* of its looks, so the look this display
     * wears no longer changes how a page moves.
     *
     * The two knobs that do still differ are not the motion: the colour behind the
     * layers is the surface of whichever theme is in force, and input is closed for
     * the duration of a transition under Miuix only.
     */
    @Composable
    private fun AppPages() {
        val backStack = rememberNavBackStack<Route>(Route.Main)
        val pages = remember { Navigator(backStack) }
        SideEffect { navigator = pages }

        val miuix = LocalUiStyle.current.isMiuixBased
        // What fills the strip the covered page's parallax vacates — the colour
        // the system's own window transition would have shown through, read off
        // the surrounding theme, which is this display's parent.
        val backdrop =
            if (miuix) MiuixTheme.colorScheme.surface else MaterialTheme.colorScheme.background
        // Read off the window insets, like the library's own effect does.
        val systemCorner = rememberNavSystemCornerRadius()
        NavDisplay(
            backStack = backStack,
            onBack = { pages.pop() },
            // The library's default preset — the same one KernelSU's manager runs,
            // and in both of its looks; nothing here overrides it.
            transition = NavTransitions.MiuixDefault,
            effects = NavDisplayEffects(
                // KernelSU passes exactly this: the leading-edge clip at the
                // device's own screen corner. The remaining switches stay at the
                // library defaults — clip on, leading edge, 0.5 dim — except the
                // two noted below.
                cornerClipRadius = systemCorner,
                blockInputDuringTransition = miuix,
                backdropColor = backdrop,
            ),
        ) {
            entry<Route.Main> { HomeRoot(pages) }
            entry<Route.About> { AboutPage(pages) }
            entry<Route.Licenses> { LicensesScreen(onBack = { pages.pop() }) }
            entry<Route.Experiment> { ExperimentScreen(onBack = { pages.pop() }) }
        }
    }

    /**
     * The whole home, in whichever shape the current look wears: the bottom
     * navigation with its three tabs, on the library's own bar under the Miuix
     * look and on the M3 Expressive bar under the Material one. The tab
     * content is shared — both shells host the same three pages, and a style
     * switch re-skins in place.
     */
    @Composable
    private fun HomeRoot(pages: Navigator) {
        val tabContent: @Composable (HomeTab) -> Unit = { tab ->
            when (tab) {
                HomeTab.Modules -> ModuleStatusScreen(
                    active = moduleActive,
                    apiVersion = moduleApiVersion,
                    managerVersion = moduleManagerVersion,
                    versionName = moduleVersionName,
                    versionCode = moduleVersionCode,
                    onDiagnostics = { openFcmDiagnostics() },
                    onHelp = {
                        // The guide lives on GitHub, in the app's own locale —
                        // the same URL the shortcut and the about page's help
                        // row open.
                        UiUtils.openUrl(this, AboutDelegate.helpUrl(this))
                    },
                    onAbout = {
                        // A real page, like licences: it slides in over the home
                        // and the home stays underneath it, so its predictive back
                        // reveals the page itself rather than a picture of one.
                        pages.push(Route.About)
                    },
                    onRecheck = { recheckModuleActive() }
                )
                HomeTab.Apps -> AppListTab()
                HomeTab.Settings -> SettingsTab(pages)
            }
        }
        if (LocalUiStyle.current.isMiuixBased) {
            MiuixHomeShell(
                selected = homeTab,
                onSelect = { selectTab(it) },
                content = tabContent
            )
            return
        }
        HomeShell(
            selected = homeTab,
            onSelect = { selectTab(it) },
            content = tabContent
        )
    }

    /** The allowlist page — the home's「应用」tab under either look. */
    @Composable
    private fun AppListTab() {
        MainScreen(
            topBarState = topBarState,
            actions = MainActions(
                onBack = { if (multiSelectMode) exitMultiSelect() else exitSearch() },
                onSearch = { enterSearch() },
                onStartMultiSelect = { enterMultiSelect(null) },
                onExitMultiSelect = { exitMultiSelect() },
                // Reconcile model: checkboxes show final membership, one
                // save applies the add/remove diff.
                onBatchSave = { saveBatchAllowlist() },
                onSelectAll = { toggleSelectAllVisible() },
                onToggleShowSystemApps = { toggleOverflowShowSystemApps() },
                onToggleExcludeMiPush = { toggleOverflowExcludeMiPush() }
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
            icons = iconsByPkg,
            refreshing = refreshing,
            onRefresh = { refreshing = true; loadApps() },
            snackbarHostState = snackbarHostState
        )
    }

    /**
     * The settings page as the home's「设置」tab: [AboutScreen] without the
     * back control — a tab is a top-level destination, not a page to leave —
     * and minus the「关于」
     * section, which on this home is the「关于」tab's whole page.
     */
    @Composable
    private fun SettingsTab(pages: Navigator) {
        AboutScreen(
            onBack = {},
            showBack = false,
            showProjectSection = false,
            actions = aboutActions(pages),
            snackbarHostState = settingsSnackbarHostState
        )
    }

    /**
     * The [AboutActions] both settings pages share: the callbacks need the
     * [AboutDelegate] and this Activity, and the two pages open the same
     * destinations — only where the「关于」card lives differs.
     */
    private fun aboutActions(pages: Navigator): AboutActions = AboutActions(
        onViewSource = { aboutDelegate.openLink(AboutDelegate.REPO_URL) },
        onLicense = { aboutDelegate.openLink(AboutDelegate.GPL3_URL) },
        onLicenses = { pages.push(Route.Licenses) },
        onExperiment = { pages.push(Route.Experiment) },
        onExport = aboutDelegate::exportAllowlist,
        onImport = aboutDelegate::importAllowlist,
        onCheckUpdate = aboutDelegate::checkForUpdates,
        onUpdateOpen = aboutDelegate::openPendingUpdate,
        versionLine = settingsVersionLine,
        versionTag = settingsVersionTag,
        onAppearanceChange = aboutDelegate::applyAppearanceChange
    )

    /**
     * [Route.About]: the page the home's「关于」row opens, which is [AboutScreen]
     * cut down to the hero and the project rows — the appearance rows and the
     * backup / update rows only exist on the settings tab itself.
     *
     * Its feedback line is the settings tab's, not one of its own: the messages
     * this page can raise come from [aboutDelegate], which writes to that state,
     * and the page hosting it is what puts them on screen.
     */
    @Composable
    private fun AboutPage(pages: Navigator) {
        AboutTabScreen(
            onBack = { pages.pop() },
            actions = AboutActions(
                onViewSource = { aboutDelegate.openLink(AboutDelegate.REPO_URL) },
                onLicense = { aboutDelegate.openLink(AboutDelegate.GPL3_URL) },
                onLicenses = { pages.push(Route.Licenses) },
                onExperiment = {},
                onExport = {},
                onImport = {},
                onCheckUpdate = {},
                onUpdateOpen = {},
                versionLine = settingsVersionLine,
                versionTag = settingsVersionTag,
                onAppearanceChange = aboutDelegate::applyAppearanceChange
            ),
            snackbarHostState = settingsSnackbarHostState
        )
    }

    /**
     * One transient line of feedback, in whichever shell the app is wearing.
     *
     * Every message this screen produced used to be a `Toast`: a second
     * feedback stack that no theme could reach, raised outside this window and
     * invisible to the tree around it. A snackbar is drawn by the page it
     * belongs to and follows that page's palette, which is the whole point —
     * but only the Material shell hosts one, so the Miuix shell acknowledges
     * with the system toast, the way the About and Licences pages already do.
     */
    private fun showMessage(text: String) {
        if (miuixFeedbackNow) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            return
        }
        uiScope.launch {
            // A burst of taps replaces the line rather than queueing behind it.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    /**
     * The settings tab's feedback line — the [AboutDelegate] for the home
     * lands its SAF/export and update messages here, drawn by the tab's own
     * host. Under Miuix this delegate is never driven (the settings page lives
     * in AboutActivity there), but the style can flip while a SAF result is in
     * flight, so the toast branch stands anyway.
     */
    private fun showSettingsMessage(text: String) {
        if (miuixFeedbackNow) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            return
        }
        uiScope.launch {
            settingsSnackbarHostState.currentSnackbarData?.dismiss()
            settingsSnackbarHostState.showSnackbar(text)
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
                showSystemApps, excludeMiPushApps
            )
        )
    }

    /**
     * Re-emit the visible rows so they recompose with a changed allowlist state.
     * `AppEntry.checked` is a plain field, so nothing else can tell the rows it
     * changed — this replaces the `notifyDataSetChanged` the adapter used to send.
     * Icons no longer route through here; they land in [iconsByPkg] per row.
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
        // An exempt row has no state to toggle — it is always acted for — so a
        // tap on it is swallowed here as well as being drawn inert. Guarding the
        // handler rather than only the composable keeps the rule true for every
        // caller, including anything added later.
        if (app.exempt) {
            return
        }
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
        // Same reason as [handleRowTap]: an exempt row cannot be staged for a
        // batch action, so it must not start a selection either.
        if (app.exempt) {
            return
        }
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
        getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(Prefs.KEY_SHOW_SYSTEM, showSystemApps)
            .apply()
        pushUiState()
        loadApps()
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
        // Appearance settings may have changed while the settings page was on
        // top (palette style, theme mode, seed color...). The Compose tree
        // follows ThemeEngine's generation counter and re-skins on its own;
        // what recomposes nowhere is the View-side window chrome. Repaint that
        // here instead of rebuilding the whole activity — the old `recreate()`
        // read as a jump on return from the settings screen.
        val palette = ThemeEngine.palette(this)
        if (appliedPalette != null && palette !== appliedPalette) {
            ThemeSupport.reapplyWindow(this)
            appliedPalette = palette
        }
        // A hot reload (scope toggle, module update) can have landed while the
        // window was backgrounded; the Compose tree re-skins and re-reads its
        // own state on return.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE_TAB, homeTab.name)
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
     * re-running the push queries. Returns false when there is nothing to reuse
     * (first open, or the system-app filter changed since the scan), which is
     * when a real scan runs.
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
            // Where batch mode is entered differs by look: long-press under
            // Material, the top-bar 批量操作 icon under Miuix, so the hint names
            // the door that actually exists.
            val tipRes = if (UiStyle.of(ThemePrefs.uiStyle(this)).isMiuixBased) {
                R.string.multi_select_tip_miuix
            } else {
                R.string.multi_select_tip
            }
            showMessage(getString(tipRes))
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
        // Reconcile model: the staging set starts as a mirror of the current
        // allowlist, so existing members are visible (checkboxes on) and the
        // user edits membership directly — check to add, uncheck to remove —
        // then taps save.
        for (app in allApps) {
            if (!app.exempt && app.checked) {
                selectedPkgs.add(app.packageName)
            }
        }
        if (firstPackage != null) {
            // Entered via a long-press: stage the pressed row too.
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
                // Exempt rows stay out of the staging set: a batch would either
                // add a package that is already unconditionally active, or
                // remove one whose removal changes nothing.
                if (app.exempt) {
                    continue
                }
                selectedPkgs.add(app.packageName)
            }
        }
        pushUiState()
    }

    /**
     * Save the batch edit in the reconcile ("what you see is what you get")
     * model. The staging set is the *desired* membership: for every non-exempt
     * app, a checked row that was off is added and an unchecked row that was on
     * is removed. An empty set is a valid "remove all". The stored allowlist
     * changes only here, on save.
     */
    private fun saveBatchAllowlist() {
        if (!multiSelectMode) {
            return
        }
        val desired = HashSet(selectedPkgs)
        var changed = false
        val newAllow = HashSet(allowlist)
        for (app in allApps) {
            // Exempt rows are neither members nor stageable; leave them.
            if (app.exempt) {
                continue
            }
            val shouldBe = desired.contains(app.packageName)
            if (shouldBe && !app.checked) {
                app.checked = true
                newAllow.add(app.packageName)
                changed = true
            } else if (!shouldBe && app.checked) {
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
        if (changed) {
            showMessage(getString(R.string.batch_added))
        }
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
        val dropMiPush = excludeMiPushApps
        for (app in allApps) {
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
     * Empty list after a full package scan (no search query), where the MiPush
     * filter is what emptied it.
     *
     * The toast is delayed: a rescan finishing an empty query first would say
     * "no apps" before the real list lands. Cancelled when a later snapshot
     * fills the list.
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
        // With the filter off an empty list means the scan found nothing, and
        // blaming the filter for that would be wrong.
        if (!excludeMiPushApps) {
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
        when (intent?.action) {
            ACTION_FCM_DIAGNOSTICS -> openFcmDiagnostics()
            // The settings shortcut lands on the home's 设置 tab — the full
            // shell (bottom bar, this look's chrome), never a bare page.
            // Covers both entries: cold start (onCreate's call, after the
            // saved tab would have won) and a relaunch over the live task
            // (onNewIntent — singleTask routes the second launch here).
            ACTION_OPEN_SETTINGS -> selectTab(HomeTab.Settings)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A shortcut relaunch names a home tab, and a tab is only reachable from
        // the home — so a sub-page left open from last time goes first, rather
        // than burying the tab the shortcut asked for.
        navigator?.popToMain()
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
            val listener = object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    boundService = service
                    moduleActive = true
                    moduleApiVersion = service.apiVersion
                    moduleManagerVersion = runCatching {
                        // Version alone: the row is already titled "LSPosed", the
                        // framework name would say it twice.
                        "${service.frameworkVersion} (${service.frameworkVersionCode})"
                    }.getOrDefault("")
                    runOnUiThreadSafe {
                        // Publish for other screens (About import/export).
                        Prefs.setRemote(remotePrefs())
                        // Remote prefs are the source of truth once bound.
                        reloadAllowlist()
                        loadApps()
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    if (boundService === service) {
                        boundService = null
                        moduleActive = false
                        moduleApiVersion = 0
                        moduleManagerVersion = ""
                        Prefs.setRemote(null)
                    }
                }
            }
            XposedServiceHelper.registerListener(listener)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * The status card's tap-to-recheck: registers the listener again, which
     * makes the helper re-run its bind handshake — the same path a fresh
     * process takes. The helper has no unregister, so the old listener stays
     * on the list; both fire on the next bind, and their side effects
     * (publish prefs, reload the list) are idempotent, so the only cost is
     * one redundant reload on the success path.
     */
    fun recheckModuleActive() {
        initXposedService()
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
        if (prefs == null) return
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
        if (Prefs.hasPendingGreezeNetReleasePush(this)) {
            // Same repair for the greeze net-release switch.
            Prefs.writeGreezeNetRelease(
                this, prefs, Prefs.readLocalGreezeNetRelease(this)
            )
        }
        if (Prefs.hasPendingDebugLogPush(this)) {
            // Same repair for the debug-log switch; without it a flip made
            // before the service bound would never reach the gate in
            // system_server, which would keep logging (or staying silent)
            // against what the screen shows.
            Prefs.writeDebugLog(this, prefs, Prefs.readLocalDebugLog(this))
        }
        if (Prefs.hasPendingSmartPowerReleasePush(this)) {
            // Same repair for the Smart Power switch; the system_server-side
            // hook reads the remote group per decision, so a flip made before
            // the service bound would leave the ROM intercepting against what
            // the screen shows until the next app-side write.
            Prefs.writeSmartPowerRelease(this, prefs, Prefs.readLocalSmartPowerRelease(this))
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
        val generation = appScanGeneration.incrementAndGet()
        Thread {
            val pm = packageManager
            try {
                val selected = ArrayList<AppListStore.AppEntry>()
                for (pkg in allow) {
                    val ai = try {
                        pm.getApplicationInfo(pkg, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        // Not visible under the declared queries — an allowlist
                        // entry that declares no push component. It stays in the
                        // allowlist and keeps working; it just has no row.
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

                // No permission anywhere: the manifest declares the push actions
                // and visibility filtering returns exactly the apps that declare
                // them — the only apps an FCM wake allowlist can mean anything
                // for. See the <queries> block in AndroidManifest.xml.
                val support = scanPushSupport(pm)
                // A newer scan started while this one was querying; its answer
                // is already on the way, so abandon the rest of the work.
                if (!isLatestScan(generation)) {
                    return@Thread
                }
                val result = ArrayList<AppListStore.AppEntry>()
                for (pkg in support.fcm) {
                    val ai = try {
                        pm.getApplicationInfo(pkg, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        // Resolved by the query but not by name: query quirk,
                        // drop the row rather than guess at its label.
                        continue
                    }
                    if (ai.packageName == packageName) {
                        continue
                    }
                    if (!showSys && isSystemApp(ai)) {
                        continue
                    }
                    val entry = AppListStore.AppEntry(ai.packageName, ai.loadLabel(pm).toString())
                    entry.supportMiPush = support.miPush.contains(ai.packageName)
                    if (EXEMPT_PACKAGES.contains(ai.packageName)) {
                        entry.exempt = true
                    }
                    result.add(entry)
                }
                for (app in result) {
                    app.checked = allow.contains(app.packageName)
                }
                result.sortWith { a, b -> compareEntries(a, b) }
                // Remember the scan for a configuration change.
                if (result.isNotEmpty() && isLatestScan(generation)) {
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
                // The queries are binder calls; they can fail. Swallowed here
                // would leave the refresh spinner running forever with no list
                // and no way back but killing the app, so the failure is logged
                // and the spinner is stopped below.
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

        /**
         * The bound libxposed service, at process level: the helper delivers its
         * binder to a listener exactly once and never re-delivers, so the answer
         * must outlive the Activity that happened to receive it — an instance
         * recreated by a dark-mode toggle reads it back in the property
         * initialisers and keeps showing 已激活 instead of waiting for a bind
         * callback that can never come again.
         */
        private var boundService: XposedService? = null

        /**
         * The tab the current process was last showing, read back when the
         * SYSTEM rebuilds this Activity inside the SAME process — the per-app
         * language switch does exactly that (LocaleManager recreates the
         * activity, process untouched). Without it the rebuild reset the tab to
         * the default and the screen visibly jumped to 主页 mid-switch; dark
         * mode never showed this because its path re-skins without recreating.
         * Process death clears it by nature — a task rebuilt from storage still
         * lands on the default, which is the policy this holder preserves.
         */
        private var retainedTab: HomeTab? = null

        /** Launcher shortcut: open GMS FCM diagnostics. */
        const val ACTION_FCM_DIAGNOSTICS = "io.github.howard20181.hyperos.fcmlive.FCM_DIAGNOSTICS"

        /** The settings shortcut's action: open the home on the 设置 tab. */
        const val ACTION_OPEN_SETTINGS = "io.github.howard20181.hyperos.fcmlive.OPEN_SETTINGS"

        /**
         * Google Play services. Spelled out once here because the UI needs the
         * name for two different questions and they must agree: the row is inert
         * ([EXEMPT_PACKAGES]) *and* it is listed as push-capable whatever its
         * Manifest holds ([scanPushSupport]).
         *
         * The hook side has its own private copy (`HookerSupport.GMS_PACKAGE_NAME`) in
         * the `system_server` process, which this one cannot reach into.
         */
        private const val GMS_PACKAGE_NAME = "com.google.android.gms"

        /**
         * The packages the module is unconditionally exempt for — the same two
         * [io.github.howard20181.hyperos.fcmlive.Hooker] special-cases in
         * `AllowlistGate.moduleAppliesTo`. Duplicated here rather than shared because the hook
         * side's copy is private to its own companion and is loaded in
         * `system_server`, a process this one cannot reach into.
         *
         * They exist on the UI side only to decide that a row is inert. GMS is
         * always acted for whatever the allowlist holds, so offering a checkbox
         * for it would be offering a control that does nothing.
         */
        private val EXEMPT_PACKAGES = setOf(
            GMS_PACKAGE_NAME,
            "com.google.android.gms.persistent"
        )

        private const val RAPID_CHECK_WINDOW_MS = 12000L
        private const val RAPID_CHECK_HINT_AT = 4

        private const val KEY_STATE_TAB = "state_tab"
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
         * Packages that look FCM-capable, scored by four independent Manifest
         * markers and then judged by one rule — with GMS carved out in front.
         *
         * Every marker is a device-wide intent query; the manifest declares
         * these actions and visibility filtering returns exactly the apps that
         * answer to them, so the candidate set is the union of the query
         * results and no permission is involved anywhere.
         *
         * 2. `com.google.firebase.iid.FirebaseInstanceIdReceiver` — an
         *    intent-filter action (`INSTANCE_ID_EVENT`) on the receiver.
         * 3. `com.google.firebase.MESSAGING_EVENT` — an intent-filter
         *    action, normally on the messaging service.
         * 4. `com.google.android.c2dm.intent.RECEIVE` — an intent-filter
         *    action, normally on the instance-id receiver.
         * 5. — tracked alongside ④ — the RECEIVE receiver resolving to
         *    Google's notifications-platform PushReceiver.
         *
         * The former marker 1 (the `FirebaseMessagingService` *class*, asked
         * per package) is gone with the permission-free rework: it never
         * participated in the rule below — ② alone and ③ alone are accepted,
         * so any package that ① would have pushed over the line already
         * qualifies without it — and a class shipped without any of the
         * declared actions produces no query hit, leaving nothing to ask.
         *
         * **GMS is included unconditionally, before and independently of the
         * rule.** It is the one package the screen exists for, and a list of
         * "apps that support push" with no Google Play services in it reads as a
         * broken module rather than as a strict filter. The SDK lets an app
         * point `RECEIVE` at a receiver of its own, so a client need not
         * declare the components the rule looks for, and on-device GMS did not
         * turn up through them; the `<queries>` entry naming the package makes
         * the row resolvable regardless.
         *
         * **The rule, for everything else: any two of ②③④ is enough; ② or ③
         * alone is also enough; ④ alone, and nothing at all, are out.**
         *
         * Why ② is not required: the SDK's public receiver expects a *custom*
         * receiver class to hand the message to, so an app can receive `RECEIVE`
         * without ever declaring `FirebaseInstanceIdReceiver`. Tying acceptance
         * to ② would drop such clients.
         *
         * Why ④ alone is out: the pre-Firebase c2dm era left bare `RECEIVE`
         * actions behind on components that have nothing to do with messaging
         * (`framework-res` still carries one on its factory-reset receiver, a
         * remote-wipe leftover). ② alone and ③ alone stay in because an app
         * that ships one of the SDK's own components is a client whether or not
         * anything else in its Manifest corroborates it.
         *
         * The actions are `HookerSupport`'s constants, and the module asks the
         * same questions in system_server (`ProcessCleanerHooks#declaresFcmComponent`)
         * — asking it the same way is the point. GMS is the one deliberate
         * divergence there too: the hook exempts it before asking any of this
         * (see `AllowlistGate.moduleAppliesTo`), so it never depends on the answer.
         */
        private fun scanPushSupport(pm: PackageManager): PushSupport {
            val support = PushSupport()
            // GMS is listed unconditionally, ahead of the rule below and
            // regardless of what its Manifest says. The check is a plain name
            // test, not a marker count.
            support.fcm.add(GMS_PACKAGE_NAME)
            // Markers 2, 3, 4 and 5, device-wide. Kept as separate sets, not
            // one: the rule counts them separately. Marker 5 tracks which
            // packages' RECEIVE receiver is Google's notifications-platform
            // PushReceiver — the entrypoint shape of Google's own apps, which
            // ship no Firebase SDK push component.
            val byIidReceiver = HashSet<String>()
            val byMessagingEvent = HashSet<String>()
            val byReceive = HashSet<String>()
            val byGooglePushReceiver = HashSet<String>()
            try {
                val services = pm.queryIntentServices(
                    Intent(HookerSupport.ACTION_MESSAGING_EVENT),
                    PackageManager.ResolveInfoFlags.of(0)
                )
                for (ri in services) {
                    // Services resolve into serviceInfo; activityInfo is the
                    // receiver/activity field and stays null here.
                    byMessagingEvent.add(ri.serviceInfo.packageName)
                }
            } catch (t: Throwable) {
                Log.w(TAG_UI, "Failed to query FCM messaging services", t)
            }
            try {
                val receivers = pm.queryBroadcastReceivers(
                    Intent(HookerSupport.ACTION_INSTANCE_ID_EVENT),
                    PackageManager.ResolveInfoFlags.of(0)
                )
                for (ri in receivers) {
                    val info = ri.activityInfo ?: continue
                    byIidReceiver.add(info.packageName)
                }
            } catch (t: Throwable) {
                Log.w(TAG_UI, "Failed to query instance-id receivers", t)
            }
            try {
                val receivers = pm.queryBroadcastReceivers(
                    Intent(HookerSupport.ACTION_REMOTE_INTENT),
                    PackageManager.ResolveInfoFlags.of(0)
                )
                for (ri in receivers) {
                    val info = ri.activityInfo ?: continue
                    byReceive.add(info.packageName)
                    if (HookerSupport.isGooglePushReceiverClass(info.name)) {
                        byGooglePushReceiver.add(info.packageName)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG_UI, "Failed to query C2DM receivers", t)
            }
            // The candidate set: everything at least one query answered for.
            // A package gets in only after the one rule is applied to
            // everything known about it — the union is the *input*, not the
            // result.
            val candidates = HashSet<String>()
            candidates.addAll(byIidReceiver)
            candidates.addAll(byMessagingEvent)
            candidates.addAll(byReceive)
            for (pkg in candidates) {
                if (isFcmClient(
                        byIidReceiver.contains(pkg),      // ②
                        byMessagingEvent.contains(pkg),   // ③
                        byReceive.contains(pkg),          // ④
                        byGooglePushReceiver.contains(pkg) // ⑤
                    )
                ) {
                    support.fcm.add(pkg)
                }
                if (declaresMiPushService(pm, pkg)) {
                    support.miPush.add(pkg)
                }
            }
            return support
        }

        /**
         * The one rule, in one place: **any two of ②③④, or ② alone, ③ alone, or ⑤ alone.**
         *
         * Written out as an explicit table rather than as arithmetic on a count,
         * because the accepted shapes are not a count — "② alone" and "③ alone"
         * are each one marker while ②+③ is two, and `④ alone` has to be rejected
         * even though it is also one marker. A boolean table says that;
         * `sum >= 2 || has2 || has3` hides it.
         *
         * ⑤ — the RECEIVE receiver resolves to Google's notifications-platform
         * PushReceiver — is accepted alone: Google's own apps carry that
         * entrypoint and none of the Firebase SDK markers, and the class
         * namespace is a merged Google library that third-party apps do not
         * reproduce.
         */
        private fun isFcmClient(
            hasIidReceiver: Boolean,
            hasMessagingEvent: Boolean,
            hasReceive: Boolean,
            hasGooglePushReceiver: Boolean
        ): Boolean {
            // ②③④: any two.
            val amongActions =
                (hasIidReceiver && hasMessagingEvent) ||
                    (hasIidReceiver && hasReceive) ||
                    (hasMessagingEvent && hasReceive)
            if (amongActions) {
                return true
            }
            // ② alone or ③ alone.
            if (hasIidReceiver || hasMessagingEvent) {
                return true
            }
            // ⑤ alone: Google's own push entrypoint.
            if (hasGooglePushReceiver) {
                return true
            }
            // Everything else: ④ alone, or nothing.
            return false
        }

        /**
         * Whether `pkg` ships MiPush: it declares the SDK's push service.
         *
         * MiPush is a system-channel push, so an app that has it does not need
         * this module to keep a second (FCM) route alive — the list tags those apps
         * and the overflow menu can leave them out.
         *
         * A direct component lookup — fine here because every caller passes a
         * package the intent queries already made visible. No MiPush action is
         * declared in `<queries>`: the SDK's service often ships without an
         * intent-filter, so a query could not see it, while a component lookup
         * on a visible package can.
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
        /** FCM-capable: packages the rule accepted (GMS always among them). */
        val fcm = HashSet<String>()

        /** Declares [MIPUSH_SERVICE_CLASS]. */
        val miPush = HashSet<String>()
    }
}
