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
    private ImageButton btnMore;
    private ImageButton btnBatchAdd;
    private ImageButton btnBatchRemove;
    private ImageButton btnSelectAll;
    private SwipeRefreshLayout swipeRefresh;
    private Object backInvokedCallback;
    private boolean searching = false;
    private boolean multiSelectMode = false;
    private String currentQuery = "";
    private boolean showSystemApps = false;
    /** Overflow: when true, list only apps whose Manifest has FCM-style receivers. */
    private boolean showFcmSupportedOnly = false;
    /** UI intent for launcher icon; do not infer toggle direction from PM cache. */
    private boolean launcherIconHidden = false;
    private XposedService xposedService;
    private PopupWindow activeTooltip;
    private final Runnable dismissTooltipRunnable = this::dismissActiveTooltip;

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
        // First launch: show FCM-supported apps by default. After the user
        // toggles the overflow option, their stored preference wins.
        showFcmSupportedOnly = getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
                .getBoolean(Prefs.KEY_SHOW_FCM_ONLY, true);

        initXposedService();

        adapter = new AppListAdapter(this, filteredApps, new AppListAdapter.OnCardListener() {
            @Override
            public void onToggleAllowlist(String pkg, boolean checked) {
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
            }

            @Override
            public void onEnterMultiSelect(String packageName) {
                enterMultiSelect(packageName);
            }

            @Override
            public void onSelectionChanged(int count) {
                if (multiSelectMode) {
                    updateSelectionTitle(count);
                    updateSelectAllIcon();
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
        btnMore = findViewById(R.id.btn_more);
        btnBatchAdd = findViewById(R.id.btn_batch_add);
        btnBatchRemove = findViewById(R.id.btn_batch_remove);
        btnSelectAll = findViewById(R.id.btn_select_all);
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> enterSearch());
            attachTip(btnSearch, R.string.tooltip_search);
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                if (multiSelectMode) {
                    exitMultiSelect();
                } else {
                    exitSearch();
                }
            });
            attachTip(btnBack, R.string.exit_search);
        }
        if (btnMore != null) {
            btnMore.setOnClickListener(this::showOverflowMenu);
            attachTip(btnMore, R.string.more_menu);
        }
        if (btnBatchAdd != null) {
            btnBatchAdd.setOnClickListener(v -> applyBatchAllowlist(true));
            attachTip(btnBatchAdd, R.string.batch_add_allowlist);
        }
        if (btnBatchRemove != null) {
            btnBatchRemove.setOnClickListener(v -> applyBatchAllowlist(false));
            attachTip(btnBatchRemove, R.string.batch_remove_allowlist);
        }
        if (btnSelectAll != null) {
            btnSelectAll.setOnClickListener(v -> toggleSelectAllVisible());
            attachTip(btnSelectAll, R.string.select_all);
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

    /**
     * Long-press tooltip that never covers the anchor icon.
     * HyperOS (and some AOSP builds) place the system bubble on top of the
     * control; we show a custom MD-style popup with an explicit gap instead.
     */
    private void attachTip(View view, int tooltipRes) {
        if (view == null) {
            return;
        }
        final CharSequence tip = getText(tooltipRes);
        view.setContentDescription(tip);
        // Suppress framework / HyperOS bubbles that sit on the icon.
        view.setTooltipText(null);
        view.setLongClickable(true);
        view.setOnLongClickListener(v -> {
            showAnchorTooltip(v, tip);
            return true;
        });
    }

    /** Show a short bubble below (or above when needed) the given anchor. */
    private void showAnchorTooltip(View anchor, CharSequence text) {
        dismissActiveTooltip();
        if (anchor == null || text == null || text.length() == 0 || isFinishing()) {
            return;
        }

        // HyperOS may re-surface contentDescription as a covering bubble on
        // long-press. Hide it while our offset tooltip is visible, restore for
        // accessibility after dismiss.
        final CharSequence restoredCd = anchor.getContentDescription() != null
                ? anchor.getContentDescription()
                : text;
        anchor.setContentDescription(null);

        TextView tipView = new TextView(this);
        tipView.setText(text);
        tipView.setTextColor(getColor(R.color.md_tooltip_text));
        tipView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        tipView.setGravity(android.view.Gravity.CENTER);
        tipView.setBackgroundResource(R.drawable.bg_tooltip);
        int padH = dp(12);
        int padV = dp(6);
        tipView.setPadding(padH, padV, padH, padV);
        tipView.setSingleLine(true);
        tipView.setIncludeFontPadding(false);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int maxTextW = Math.max(dp(64), Math.min(dp(240), screenW - dp(48)));
        tipView.setMaxWidth(maxTextW);
        tipView.measure(
                View.MeasureSpec.makeMeasureSpec(maxTextW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(64), View.MeasureSpec.AT_MOST));
        int tipW = Math.max(tipView.getMeasuredWidth(), padH * 2 + dp(24));
        int tipH = Math.max(tipView.getMeasuredHeight(), dp(28));

        PopupWindow popup = new PopupWindow(
                tipView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setContentView(tipView);
        popup.setWidth(tipW);
        popup.setHeight(tipH);
        popup.setOutsideTouchable(true);
        popup.setFocusable(false);
        popup.setTouchable(true);
        popup.setClippingEnabled(true);
        try {
            popup.setElevation(dp(6));
        } catch (Throwable ignored) {
        }
        // Transparent so the rounded shape is not clipped by a default frame.
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        final PopupWindow popupRef = popup;
        final View anchorRef = anchor;
        final CharSequence cdToRestore = restoredCd;
        popup.setOnDismissListener(() -> {
            tipView.removeCallbacks(dismissTooltipRunnable);
            if (activeTooltip == popupRef) {
                activeTooltip = null;
            }
            try {
                anchorRef.setContentDescription(cdToRestore);
            } catch (Throwable ignored) {
            }
        });

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int gap = dp(8);
        int edge = dp(8);

        // Horizontal: center on the anchor, then clamp into the screen.
        int screenX = loc[0] + (anchor.getWidth() - tipW) / 2;
        if (screenX < edge) {
            screenX = edge;
        }
        if (screenX + tipW > screenW - edge) {
            screenX = Math.max(edge, screenW - edge - tipW);
        }

        // Vertical: prefer a clear gap under the icon; flip above when tight
        // (toolbar icons near the status bar, FAB near the nav bar).
        int yBelow = loc[1] + anchor.getHeight() + gap;
        int yAbove = loc[1] - gap - tipH;
        int roomBelow = screenH - edge - (loc[1] + anchor.getHeight());
        int roomAbove = loc[1] - edge;
        boolean fitsBelow = roomBelow >= tipH + gap;
        boolean fitsAbove = roomAbove >= tipH + gap;
        int screenY;
        if (fitsBelow) {
            screenY = yBelow;
        } else if (fitsAbove) {
            screenY = yAbove;
        } else if (roomBelow >= roomAbove) {
            screenY = Math.min(yBelow, screenH - edge - tipH);
        } else {
            screenY = Math.max(yAbove, edge);
        }
        if (screenY < edge) {
            screenY = edge;
        }
        if (screenY + tipH > screenH - edge) {
            screenY = Math.max(edge, screenH - edge - tipH);
        }

        // PopupWindow coordinates are window-relative.
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        int decorX = 0;
        int decorY = 0;
        if (decor != null) {
            int[] decorLoc = new int[2];
            decor.getLocationOnScreen(decorLoc);
            decorX = decorLoc[0];
            decorY = decorLoc[1];
        }
        int winX = screenX - decorX;
        int winY = screenY - decorY;

        try {
            popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, winX, winY);
            activeTooltip = popup;
            tipView.postDelayed(dismissTooltipRunnable, 2200);
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
        if (multiSelectMode) {
            exitMultiSelect();
        } else if (searching) {
            exitSearch();
        } else {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        dismissActiveTooltip();
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
        if (multiSelectMode) {
            exitMultiSelect();
        }
        dismissActiveTooltip();
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
            attachTip(btnBack, R.string.exit_search);
        }
        if (btnMore != null) {
            btnMore.setVisibility(View.VISIBLE);
        }
        if (btnBatchAdd != null) {
            btnBatchAdd.setVisibility(View.GONE);
        }
        if (btnBatchRemove != null) {
            btnBatchRemove.setVisibility(View.GONE);
        }
        if (btnSelectAll != null) {
            btnSelectAll.setVisibility(View.GONE);
        }
    }

    private void exitSearch() {
        dismissActiveTooltip();
        searching = false;
        if (searchView != null) {
            searchView.setQuery("", false);
            searchView.clearFocus();
            searchView.setVisibility(View.GONE);
        }
        if (titleView != null) {
            titleView.setVisibility(View.VISIBLE);
            if (!multiSelectMode) {
                titleView.setText(R.string.settings_title);
            }
        }
        if (btnSearch != null) {
            btnSearch.setVisibility(View.VISIBLE);
        }
        if (btnBack != null) {
            btnBack.setVisibility(View.GONE);
        }
        if (btnBatchAdd != null) {
            btnBatchAdd.setVisibility(View.GONE);
        }
        if (btnBatchRemove != null) {
            btnBatchRemove.setVisibility(View.GONE);
        }
        if (btnSelectAll != null) {
            btnSelectAll.setVisibility(View.GONE);
        }
        currentQuery = "";
        filterApps("");
    }

    /**
     * Multi-select: long-press a card. Top bar mirrors search mode — back arrow
     * on the left, selection count as title, batch whitelist actions on the right.
     */
    private void enterMultiSelect(String firstPackage) {
        dismissActiveTooltip();
        if (searching) {
            searching = false;
            if (searchView != null) {
                searchView.setQuery("", false);
                searchView.clearFocus();
                searchView.setVisibility(View.GONE);
            }
            currentQuery = "";
            filterApps("");
        }
        multiSelectMode = true;
        if (adapter != null) {
            adapter.setMultiSelectMode(true);
            java.util.Set<String> seed = new java.util.HashSet<>();
            if (firstPackage != null) {
                seed.add(firstPackage);
            }
            adapter.setSelectedPackages(seed);
        }
        applyMultiSelectBar();
    }

    private void exitMultiSelect() {
        multiSelectMode = false;
        if (adapter != null) {
            adapter.setMultiSelectMode(false);
        }
        if (titleView != null) {
            titleView.setVisibility(View.VISIBLE);
            titleView.setText(R.string.settings_title);
        }
        if (searchView != null) {
            searchView.setVisibility(View.GONE);
        }
        if (btnSearch != null) {
            btnSearch.setVisibility(View.VISIBLE);
        }
        if (btnBack != null) {
            btnBack.setVisibility(View.GONE);
        }
        if (btnMore != null) {
            btnMore.setVisibility(View.VISIBLE);
        }
        if (btnBatchAdd != null) {
            btnBatchAdd.setVisibility(View.GONE);
        }
        if (btnBatchRemove != null) {
            btnBatchRemove.setVisibility(View.GONE);
        }
        if (btnSelectAll != null) {
            btnSelectAll.setVisibility(View.GONE);
        }
    }

    private void applyMultiSelectBar() {
        if (!multiSelectMode) {
            return;
        }
        if (titleView != null) {
            titleView.setVisibility(View.VISIBLE);
        }
        if (searchView != null) {
            searchView.setVisibility(View.GONE);
        }
        if (btnBack != null) {
            btnBack.setVisibility(View.VISIBLE);
            attachTip(btnBack, R.string.exit_multi_select);
        }
        if (btnSearch != null) {
            btnSearch.setVisibility(View.GONE);
        }
        if (btnMore != null) {
            btnMore.setVisibility(View.GONE);
        }
        if (btnBatchAdd != null) {
            btnBatchAdd.setVisibility(View.VISIBLE);
        }
        if (btnBatchRemove != null) {
            btnBatchRemove.setVisibility(View.VISIBLE);
        }
        if (btnSelectAll != null) {
            btnSelectAll.setVisibility(View.VISIBLE);
        }
        int count = adapter != null ? adapter.getSelectedPackages().size() : 0;
        updateSelectionTitle(count);
        updateSelectAllIcon();
    }

    private void updateSelectionTitle(int count) {
        if (titleView != null && multiSelectMode) {
            titleView.setText(getString(R.string.selected_count, count));
        }
    }

    /** All currently visible (filtered) rows are selected → show deselect-all icon. */
    private boolean isAllVisibleSelected() {
        if (adapter == null || filteredApps.isEmpty()) {
            return false;
        }
        java.util.Set<String> selected = adapter.getSelectedPackages();
        for (AppListAdapter.AppEntry app : filteredApps) {
            if (!selected.contains(app.packageName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One control, two states: select-all icon → tap selects every visible row;
     * when all are selected the icon flips to deselect-all.
     */
    private void updateSelectAllIcon() {
        if (btnSelectAll == null || !multiSelectMode) {
            return;
        }
        boolean all = isAllVisibleSelected();
        btnSelectAll.setImageResource(all
                ? R.drawable.ic_deselect_all
                : R.drawable.ic_select_all);
        attachTip(btnSelectAll, all ? R.string.deselect_all : R.string.select_all);
    }

    private void toggleSelectAllVisible() {
        if (!multiSelectMode || adapter == null) {
            return;
        }
        if (isAllVisibleSelected()) {
            java.util.Set<String> next = adapter.getSelectedPackages();
            for (AppListAdapter.AppEntry app : filteredApps) {
                next.remove(app.packageName);
            }
            adapter.setSelectedPackages(next);
        } else {
            java.util.Set<String> next = adapter.getSelectedPackages();
            for (AppListAdapter.AppEntry app : filteredApps) {
                next.add(app.packageName);
            }
            adapter.setSelectedPackages(next);
        }
        int count = adapter.getSelectedPackages().size();
        updateSelectionTitle(count);
        updateSelectAllIcon();
    }

    /**
     * Apply selected packages to the allowlist. Selection is a staging set;
     * the whitelist only changes when the user taps a batch action.
     */
    private void applyBatchAllowlist(boolean add) {
        if (!multiSelectMode || adapter == null) {
            return;
        }
        java.util.Set<String> selected = adapter.getSelectedPackages();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.batch_nothing_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean changed = false;
        for (AppListAdapter.AppEntry app : allApps) {
            if (!selected.contains(app.packageName)) {
                continue;
            }
            if (add && !app.checked) {
                app.checked = true;
                allowlist.add(app.packageName);
                changed = true;
            } else if (!add && app.checked) {
                app.checked = false;
                allowlist.remove(app.packageName);
                changed = true;
            }
        }
        if (changed) {
            updateAllowlist();
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        Toast.makeText(this, R.string.batch_added, Toast.LENGTH_SHORT).show();
        exitMultiSelect();
    }

    /**
     * MD3-style overflow: custom popup with rounded-square checkboxes
     * (primary fill + check when on; outline when off) — not system PopupMenu.
     * Click toggles; long-press does nothing (only the row ripple).
     */
    private void showOverflowMenu(View anchor) {
        dismissActiveTooltip();
        View content = getLayoutInflater().inflate(R.layout.popup_overflow, null);
        ImageView sysCheck = content.findViewById(R.id.menu_show_system_check);
        ImageView fcmCheck = content.findViewById(R.id.menu_show_fcm_check);
        ImageView hideCheck = content.findViewById(R.id.menu_hide_icon_check);
        bindMd3Check(sysCheck, showSystemApps);
        bindMd3Check(fcmCheck, showFcmSupportedOnly);
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

        View rowFcm = content.findViewById(R.id.menu_show_fcm);
        rowFcm.setOnClickListener(v -> {
            showFcmSupportedOnly = !showFcmSupportedOnly;
            bindMd3Check(fcmCheck, showFcmSupportedOnly);
            getSharedPreferences(Prefs.LOCAL_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(Prefs.KEY_SHOW_FCM_ONLY, showFcmSupportedOnly)
                    .apply();
            // Filter only — keep package scan; toggle just hides non-FCM rows.
            filterApps(currentQuery);
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
        String lower = query != null && query.length() > 0
                ? query.toLowerCase() : null;
        boolean fcmOnly = showFcmSupportedOnly;
        for (AppListAdapter.AppEntry app : allApps) {
            if (fcmOnly && !app.supportFcm) {
                continue;
            }
            if (lower == null
                    || app.label.toLowerCase().contains(lower)
                    || app.packageName.toLowerCase().contains(lower)) {
                filteredApps.add(app);
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

    /**
     * FCMPushViewer-style detection: scan Manifest receivers for well-known
     * Firebase / GCM component names.
     */
    private static boolean hasFcmStyleReceivers(android.content.pm.PackageInfo pi) {
        if (pi == null || pi.receivers == null) {
            return false;
        }
        for (android.content.pm.ActivityInfo ri : pi.receivers) {
            if (ri == null || ri.name == null) {
                continue;
            }
            String name = ri.name;
            // Matches HappyMax0/FCMPushViewer MainActivity.getAppList().
            if ("com.google.firebase.iid.FirebaseInstanceIdReceiver".equals(name)
                    || "com.google.android.gms.measurement.AppMeasurementReceiver".equals(name)) {
                return true;
            }
            // Same judgment (receiver class name); modern firebase-messaging SDK.
            if ("com.google.firebase.messaging.FirebaseMessagingReceiver".equals(name)) {
                return true;
            }
        }
        return false;
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

            List<android.content.pm.PackageInfo> installed =
                    pm.getInstalledPackages(PackageManager.GET_RECEIVERS);
            List<AppListAdapter.AppEntry> result = new ArrayList<>();
            for (android.content.pm.PackageInfo pi : installed) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai.packageName.equals(getPackageName())) {
                    continue;
                }
                if (!showSys && isSystemApp(ai)) {
                    continue;
                }
                AppListAdapter.AppEntry entry = new AppListAdapter.AppEntry(
                        ai.packageName, ai.loadLabel(pm).toString());
                entry.supportFcm = hasFcmStyleReceivers(pi);
                result.add(entry);
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
                    || x.supportFcm != y.supportFcm
                    || !x.label.equals(y.label)) {
                return false;
            }
        }
        return true;
    }
}
