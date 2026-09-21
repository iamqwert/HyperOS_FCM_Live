package io.github.howard20181.hyperos.fcmlive;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Settings screen: pick which apps FCM may wake / auto-launch.
 * MD3-inspired card list; search + overflow (system apps / hide icon) in the
 * top bar; FAB opens GMS FCM diagnostics.
 */
public class MainActivity extends Activity implements SearchView.OnQueryTextListener {

    private static final String LAUNCHER_ALIAS =
            "io.github.howard20181.hyperos.fcmlive.LauncherAlias";
    private static final String TAG_UI = "HyperFCMLive";
    private static final int MENU_SHOW_SYSTEM = 1001;
    private static final int MENU_HIDE_ICON = 1002;

    private final List<AppListAdapter.AppEntry> allApps = new ArrayList<>();
    private final List<AppListAdapter.AppEntry> filteredApps = new ArrayList<>();
    private Set<String> allowlist = new HashSet<>();
    private AppListAdapter adapter;
    private TextView titleView;
    private SearchView searchView;
    private ImageButton btnSearch;
    private ImageButton btnBack;
    private SwipeRefreshLayout swipeRefresh;
    private Object backInvokedCallback;
    private boolean searching = false;
    private String currentQuery = "";
    private boolean showSystemApps = false;
    /** UI intent for launcher icon; do not infer toggle direction from PM cache. */
    private boolean launcherIconHidden = false;
    private XposedService xposedService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable t) {
            android.util.Log.e(TAG_UI, "setContentView failed", t);
            finish();
            return;
        }

        applySystemBarInsets();

        titleView = findViewById(R.id.toolbar_title);
        if (titleView != null) {
            titleView.setText(R.string.settings_title);
        }

        // Seed UI order from the local cache so allowlisted apps sit on top
        // immediately, before libxposed remote prefs bind.
        allowlist = Prefs.readLocalAllowlist(this);
        launcherIconHidden = !isLauncherIconVisible();

        initXposedService();

        adapter = new AppListAdapter(this, filteredApps, (pkg, checked) -> {
            if (checked) {
                allowlist.add(pkg);
            } else {
                allowlist.remove(pkg);
            }
            updateAllowlist();
            // Stay in place on tap. Order refreshes on pull-to-refresh / reopen.
            for (AppListAdapter.AppEntry app : allApps) {
                if (app.packageName.equals(pkg)) {
                    app.checked = checked;
                    break;
                }
            }
        });

        searchView = findViewById(R.id.search_view);
        if (searchView != null) {
            searchView.setOnQueryTextListener(this);
            styleSearchView(searchView);
        }
        btnBack = findViewById(R.id.btn_back);
        btnSearch = findViewById(R.id.btn_search);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> enterSearch());
            attachTip(btnSearch, R.string.tooltip_search);
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> exitSearch());
            attachTip(btnBack, R.string.exit_search);
        }

        ImageButton btnMore = findViewById(R.id.btn_more);
        if (btnMore != null) {
            btnMore.setOnClickListener(this::showOverflowMenu);
            attachTip(btnMore, R.string.more_menu);
        }

        // Material / Android standard pull-to-refresh (SwipeRefreshLayout).
        swipeRefresh = findViewById(R.id.refresh_layout);
        if (swipeRefresh != null) {
            try {
                swipeRefresh.setColorSchemeColors(getColor(R.color.md_primary));
            } catch (Throwable ignored) {
            }
            swipeRefresh.setOnRefreshListener(this::loadApps);
            // Only at the top of the list; default Material trigger distance.
            swipeRefresh.setEnabled(true);
        }

        View fab = findViewById(R.id.fab_fcm_diagnostics);
        if (fab != null) {
            fab.setOnClickListener(v -> openFcmDiagnostics());
            attachTip(fab, R.string.fcm_diagnostics);
        }

        View list = findViewById(R.id.app_list);
        if (list instanceof ListView listView) {
            listView.setAdapter(adapter);
        }

        registerBackCallback();
        loadApps();
    }

    /** Long-press: short system tooltip only (e.g. 「搜索」「更多选项」). */
    private void attachTip(View view, int tooltipRes) {
        if (view == null) {
            return;
        }
        CharSequence t = getText(tooltipRes);
        view.setTooltipText(t);
        view.setContentDescription(t);
    }

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            OnBackInvokedCallback cb = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backInvokedCallback = cb;
        } catch (Throwable ignored) {
        }
    }

    /** Search: first back closes IME (system), next back exits search — not home. */
    private void handleBack() {
        if (searching) {
            exitSearch();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback instanceof OnBackInvokedCallback cb) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(cb);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    /**
     * Pad the top bar by the real window inset so it sits just below the status
     * bar (no fitsSystemWindows — that stacked with dimen padding and pushed
     * the title too far down).
     */
    private void applySystemBarInsets() {
        final View root = findViewById(android.R.id.content);
        final View topBar = findViewById(R.id.top_bar);
        final View list = findViewById(R.id.app_list);
        final View fab = findViewById(R.id.fab_fcm_diagnostics);
        if (topBar == null) {
            return;
        }
        if (root != null) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                int barPad = dp(12);
                topBar.setPadding(topBar.getPaddingLeft(), top + barPad,
                        topBar.getPaddingRight(), barPad);
                if (list != null) {
                    list.setPadding(list.getPaddingLeft(), list.getPaddingTop(),
                            list.getPaddingRight(), bottom + dp(88));
                }
                applyFabBottomMargin(fab, bottom);
                return insets.consumeSystemWindowInsets();
            });
            root.requestApplyInsets();
        }
        // Fallback if insets never fire on this ROM.
        int statusBar = statusBarHeight();
        if (statusBar > 0 && topBar.getPaddingTop() <= statusBar) {
            int barPad = dp(12);
            topBar.setPadding(topBar.getPaddingLeft(), statusBar + barPad,
                    topBar.getPaddingRight(), barPad);
        }
        applyFabBottomMargin(fab, 0);
    }

    /**
     * Keep a fixed visual gap under the FAB: at least {@code base} dp from the
     * window bottom, or nav-bar height + extra when a system bar occupies the
     * edge — so large-corner devices are not clipped, without leaving a huge
     * hole on small-corner screens.
     */
    private void applyFabBottomMargin(View fab, int systemBottomInset) {
        if (!(fab.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams)) {
            return;
        }
        int base = dp(26);
        int extra = dp(14);
        int margin = Math.max(base, systemBottomInset + extra);
        // If insets missing, still lift a bit on gesture/button nav devices.
        if (systemBottomInset <= 0) {
            int nav = navigationBarHeight();
            margin = Math.max(base, nav + extra);
        }
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) fab.getLayoutParams();
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin;
            lp.rightMargin = dp(20);
            fab.setLayoutParams(lp);
        }
    }

    private int navigationBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Lighter query hint + no underline so inline search does not shift the bar. */
    private void styleSearchView(SearchView sv) {
        if (sv == null) {
            return;
        }
        sv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        int hintColor = getColor(R.color.md_hint_light);
        int textColor = getColor(R.color.md_on_surface);
        int[] ids = new int[]{
                getResources().getIdentifier("search_src_text", "id", "android"),
                getResources().getIdentifier("search_edit_text", "id", "android"),
        };
        for (int id : ids) {
            if (id == 0) {
                continue;
            }
            View inner = sv.findViewById(id);
            if (inner instanceof TextView tv) {
                tv.setHintTextColor(hintColor);
                tv.setTextColor(textColor);
                tv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                tv.setSingleLine(true);
            }
        }
        String[] plates = new String[]{"search_plate", "search_edit_frame", "search_bar"};
        for (String name : plates) {
            int id = getResources().getIdentifier(name, "id", "android");
            if (id == 0) {
                continue;
            }
            View plate = sv.findViewById(id);
            if (plate != null) {
                plate.setBackground(null);
                plate.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        }
    }

    /** Title becomes an inline search field; more-menu stays visible. */
    private void enterSearch() {
        searching = true;
        // Keep ListView height stable so the scrollbar does not jump when IME opens.
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        if (titleView != null) {
            titleView.setVisibility(View.GONE);
        }
        if (searchView != null) {
            searchView.setVisibility(View.VISIBLE);
            searchView.setIconified(false);
            searchView.requestFocus();
        }
        if (btnSearch != null) {
            btnSearch.setVisibility(View.GONE);
        }
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
        }
    }

    private void exitSearch() {
        searching = false;
        if (searchView != null) {
            searchView.setQuery("", false);
            searchView.clearFocus();
            searchView.setVisibility(View.GONE);
        }
        if (titleView != null) {
            titleView.setVisibility(View.VISIBLE);
        }
        if (btnSearch != null) {
            btnSearch.setVisibility(View.VISIBLE);
        }
        if (btnBack != null) {
            btnBack.setVisibility(View.GONE);
        }
        currentQuery = "";
        filterApps("");
    }

    /**
     * MD3-style overflow: custom popup with rounded-square checkboxes
     * (primary fill + check when on; outline when off) — not system PopupMenu.
     * Click toggles; long-press does nothing (only the row ripple).
     */
    private void showOverflowMenu(View anchor) {
        View content = getLayoutInflater().inflate(R.layout.popup_overflow, null);
        ImageView sysCheck = content.findViewById(R.id.menu_show_system_check);
        ImageView hideCheck = content.findViewById(R.id.menu_hide_icon_check);
        bindMd3Check(sysCheck, showSystemApps);
        bindMd3Check(hideCheck, launcherIconHidden);

        final PopupWindow popup = new PopupWindow(
                content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setElevation(dp(6));
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setTouchable(true);

        // Measure wrap_content only — NEVER force a fixed width.
        // Width = padding + longest(label + 12dp + checkbox); no right void,
        // and checkbox stays ~12dp from the text (no layout_weight).
        content.measure(
                View.MeasureSpec.makeMeasureSpec(
                        getResources().getDisplayMetrics().widthPixels,
                        View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(
                        getResources().getDisplayMetrics().heightPixels,
                        View.MeasureSpec.AT_MOST));
        int popupW = content.getMeasuredWidth();
        int popupH = content.getMeasuredHeight();
        if (popupW > 0) {
            popup.setWidth(popupW);
        }
        if (popupH > 0) {
            popup.setHeight(popupH);
        }

        View rowSystem = content.findViewById(R.id.menu_show_system);
        rowSystem.setOnClickListener(v -> {
            showSystemApps = !showSystemApps;
            bindMd3Check(sysCheck, showSystemApps);
            loadApps();
            popup.dismiss();
        });

        View rowHide = content.findViewById(R.id.menu_hide_icon);
        rowHide.setOnClickListener(v -> {
            // Toggle by intended UI state — PM may be stale on HyperOS and
            // used to invert the action (always toast "已恢复").
            boolean target = !launcherIconHidden;
            boolean ok = setLauncherIconHidden(target);
            if (ok) {
                launcherIconHidden = target;
            }
            bindMd3Check(hideCheck, launcherIconHidden);
            popup.dismiss();
        });

        // Keep the popup fully on-screen; width already equals content.
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int margin = dp(8);
        int xOff = anchor.getWidth() - popupW;
        if (loc[0] + xOff < margin) {
            xOff = margin - loc[0];
        }
        if (loc[0] + xOff + popupW > screenW - margin) {
            xOff = screenW - margin - loc[0] - popupW;
        }
        popup.showAsDropDown(anchor, xOff, dp(4));
    }

    private void bindMd3Check(ImageView box, boolean checked) {
        if (box == null) {
            return;
        }
        box.setImageResource(checked ? R.drawable.md3_check_on : R.drawable.md3_check_off);
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        currentQuery = newText != null ? newText : "";
        filterApps(currentQuery);
        return true;
    }

    private void filterApps(String query) {
        filteredApps.clear();
        if (TextUtils.isEmpty(query)) {
            filteredApps.addAll(allApps);
        } else {
            String lower = query.toLowerCase();
            for (AppListAdapter.AppEntry app : allApps) {
                if (app.label.toLowerCase().contains(lower)
                        || app.packageName.toLowerCase().contains(lower)) {
                    filteredApps.add(app);
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private ComponentName launcherAliasComponent() {
        return new ComponentName(getPackageName(),
                getPackageName() + ".LauncherAlias");
    }

    private boolean isLauncherIconVisible() {
        try {
            int state = getPackageManager()
                    .getComponentEnabledSetting(launcherAliasComponent());
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Apply launcher-alias visibility. Toast follows the requested action
     * (hide → 已隐藏, show → 已恢复). Returns whether PackageManager accepted
     * the write.
     */
    private boolean setLauncherIconHidden(boolean hidden) {
        PackageManager pm = getPackageManager();
        ComponentName alias = launcherAliasComponent();
        boolean applied = false;
        try {
            pm.setComponentEnabledSetting(
                    alias,
                    hidden
                            ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            applied = true;
        } catch (Throwable t) {
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_LAUNCHER);
                home.setPackage(getPackageName());
                java.util.List<android.content.pm.ResolveInfo> list =
                        pm.queryIntentActivities(home, 0);
                for (android.content.pm.ResolveInfo ri : list) {
                    if (ri.activityInfo == null) {
                        continue;
                    }
                    ComponentName cn = new ComponentName(
                            ri.activityInfo.packageName, ri.activityInfo.name);
                    pm.setComponentEnabledSetting(
                            cn,
                            hidden
                                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP);
                    applied = true;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            Intent changed = new Intent(Intent.ACTION_PACKAGE_CHANGED,
                    android.net.Uri.parse("package:" + getPackageName()));
            changed.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME, alias.getClassName());
            sendBroadcast(changed);
        } catch (Throwable ignored) {
        }

        Toast.makeText(this,
                hidden ? R.string.hide_icon_toast : R.string.show_icon_toast,
                Toast.LENGTH_LONG).show();
        return applied;
    }

    private void openFcmDiagnostics() {
        Intent intent = new Intent();
        intent.setClassName("com.google.android.gms",
                "com.google.android.gms.gcm.GcmDiagnostics");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallback.setData(android.net.Uri.parse("package:com.google.android.gms"));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Throwable t2) {
                Toast.makeText(this, R.string.fcm_diagnostics_not_found, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void sortApps() {
        allApps.sort(MainActivity::compareEntries);
    }

    private static int compareEntries(AppListAdapter.AppEntry a, AppListAdapter.AppEntry b) {
        if (a.checked != b.checked) {
            return a.checked ? -1 : 1;
        }
        int c = a.label.compareToIgnoreCase(b.label);
        return c != 0 ? c : a.packageName.compareTo(b.packageName);
    }

    private boolean isSystemApp(ApplicationInfo ai) {
        return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    private void initXposedService() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService service) {
                    xposedService = service;
                    runOnUiThread(() -> {
                        // Remote prefs are the source of truth once bound.
                        reloadAllowlist();
                        loadApps();
                    });
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    if (xposedService == service) {
                        xposedService = null;
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private SharedPreferences remotePrefs() {
        if (xposedService == null) {
            return null;
        }
        try {
            return xposedService.getRemotePreferences(Prefs.GROUP_CONFIG);
        } catch (Throwable e) {
            return null;
        }
    }

    private void reloadAllowlist() {
        SharedPreferences prefs = remotePrefs();
        if (prefs == null) {
            // Keep local cache if remote is not ready yet.
            return;
        }
        allowlist = Prefs.readAllowlist(prefs);
        Prefs.writeLocalAllowlist(this, allowlist);
        for (AppListAdapter.AppEntry app : allApps) {
            app.checked = allowlist.contains(app.packageName);
        }
        sortApps();
        filterApps(currentQuery);
    }

    private void updateAllowlist() {
        SharedPreferences prefs = remotePrefs();
        if (prefs == null) {
            // Still mirror locally so the next launch can sort immediately.
            Prefs.writeLocalAllowlist(this, allowlist);
            return;
        }
        Prefs.writeAllowlist(this, prefs, allowlist);
    }

    private void loadApps() {
        final boolean showSys = showSystemApps;
        final Set<String> allow = new HashSet<>(allowlist);
        final boolean emptyUi = allApps.isEmpty();
        new Thread(() -> {
            PackageManager pm = getPackageManager();

            List<AppListAdapter.AppEntry> selected = new ArrayList<>();
            for (String pkg : allow) {
                ApplicationInfo ai;
                try {
                    ai = pm.getApplicationInfo(pkg, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                AppListAdapter.AppEntry entry =
                        new AppListAdapter.AppEntry(pkg, ai.loadLabel(pm).toString());
                entry.checked = true;
                selected.add(entry);
            }
            selected.sort(MainActivity::compareEntries);
            // First open only: show allowlisted apps before the full query returns.
            if (emptyUi && !selected.isEmpty()) {
                runOnUiThread(() -> applyAppSnapshot(selected, false));
            }

            List<android.content.pm.PackageInfo> installed = pm.getInstalledPackages(0);
            List<AppListAdapter.AppEntry> result = new ArrayList<>();
            for (android.content.pm.PackageInfo pi : installed) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai.packageName.equals(getPackageName())) {
                    continue;
                }
                if (!showSys && isSystemApp(ai)) {
                    continue;
                }
                result.add(new AppListAdapter.AppEntry(
                        ai.packageName, ai.loadLabel(pm).toString()));
            }
            for (AppListAdapter.AppEntry app : result) {
                app.checked = allow.contains(app.packageName);
            }
            result.sort(MainActivity::compareEntries);
            runOnUiThread(() -> {
                applyAppSnapshot(result, true);
                // Xposed remote prefs may bind after the first package query;
                // re-read allowlist so checked apps stay on top after update.
                reloadAllowlist();
            });
        }).start();
    }

    /**
     * Swap the visible list once. Skips notify when nothing changed, and keeps
     * scroll position so refresh does not “flash” or jump.
     */
    private void applyAppSnapshot(List<AppListAdapter.AppEntry> next, boolean stopRefresh) {
        ListView listView = findViewById(R.id.app_list);
        int firstPos = 0;
        int firstTop = 0;
        if (listView != null) {
            firstPos = listView.getFirstVisiblePosition();
            View child = listView.getChildAt(0);
            firstTop = child != null ? child.getTop() : 0;
        }

        // Always re-sync from the live allowlist — loadApps may have started
        // before libxposed bound and read remote prefs.
        Set<String> live = allowlist != null ? allowlist : Collections.emptySet();
        for (AppListAdapter.AppEntry app : next) {
            app.checked = live.contains(app.packageName);
        }
        List<AppListAdapter.AppEntry> ordered = new ArrayList<>(next);
        ordered.sort(MainActivity::compareEntries);

        boolean changed = !sameAppSnapshot(allApps, ordered);
        if (changed) {
            allApps.clear();
            allApps.addAll(ordered);
            filterApps(currentQuery);
            if (listView != null) {
                listView.setSelectionFromTop(firstPos, firstTop);
            }
        }

        if (stopRefresh && swipeRefresh != null) {
            swipeRefresh.setRefreshing(false);
        }
    }

    private static boolean sameAppSnapshot(List<AppListAdapter.AppEntry> a,
                                           List<AppListAdapter.AppEntry> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            AppListAdapter.AppEntry x = a.get(i);
            AppListAdapter.AppEntry y = b.get(i);
            if (!x.packageName.equals(y.packageName) || x.checked != y.checked
                    || !x.label.equals(y.label)) {
                return false;
            }
        }
        return true;
    }
}
