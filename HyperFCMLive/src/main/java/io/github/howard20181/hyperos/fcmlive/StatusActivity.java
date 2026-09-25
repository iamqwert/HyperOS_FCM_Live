package io.github.howard20181.hyperos.fcmlive;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport;

/**
 * Whether the module can actually do anything on this ROM.
 *
 * <p>The question this screen answers is not "is the module enabled" — it is
 * "does this ROM still have the methods my hooks are written against". That is
 * the usual reason a module looks fine and does nothing: the ROM moved on, the
 * target method is gone, and nothing anywhere says so.
 *
 * <p>Both hosts are reachable from here, so this is a real check and not an
 * inference: PowerKeeper ships as an installed package and system_server's
 * classes ship as readable framework jars, so every target on the list is
 * actually loaded here and asked whether it is still there.
 *
 * <p>The one thing it still does not claim is whether a hook is installed right
 * now. Hooks run in system_server and PowerKeeper and neither can report back
 * to this process, so anything promising that would be guessing — it is left to
 * the log command at the bottom, the only place it shows up.
 *
 * <p>The GMS connection half matters for a different reason: most "push stopped
 * overnight" reports turn out to be the connection, not the module, and the
 * network type decides how long GMS waits between heartbeats. Showing it here
 * means the first thing a user checks is the thing most likely to be at fault.
 */
public class StatusActivity extends Activity {

    private static final String DIAG_LOG =
            "adb logcat -d -b all | grep -i LSPosedLogDaemon";
    private static final String DIAG_GCM =
            "adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService";
    private static final String GMS_PACKAGE = "com.google.android.gms";

    private LinearLayout content;
    private LinearLayout hookGroup;
    private TextView hookSummary;
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeSupport.onCreate(this);
        setContentView(R.layout.activity_status);
        applySystemBarInsets();

        View back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }

        content = findViewById(R.id.status_content);
        if (content == null) {
            return;
        }
        content.addView(networkCard());
        content.addView(hookSection());
        content.addView(sectionLabel(getString(R.string.status_diag_title), true));
        content.addView(diagnosticCard());
        probeHooks();
    }

    @Override
    protected void onDestroy() {
        probeExecutor.shutdownNow();
        super.onDestroy();
    }

    private void applySystemBarInsets() {
        // Passed as the field, not a fresh findViewById: onCreate resolves it
        // after this call, so whether the list gets its bottom padding depends on
        // when the insets are actually dispatched. Preserved as-is.
        UiUtils.applyBarInsets(this, findViewById(R.id.top_bar), content, 16);
    }

    /**
     * Current network, because it decides the GMS heartbeat interval — the thing
     * most often behind "push stopped while the screen was off".
     */
    private View networkCard() {
        LinearLayout card = card();
        card.addView(cardTitle(getString(R.string.status_network_title)));
        card.addView(keyValue(R.string.status_gms, describeGms()));
        card.addView(keyValue(R.string.status_network_active, describeNetwork()));
        // Wi-Fi is the good case and needs no advice; only the case that costs
        // the user push reliability earns a line.
        if (isOnCellular()) {
            card.addView(cardBody(getString(R.string.status_network_cell_hint)));
        }
        return card;
    }

    /** GMS is the thing being kept alive; if it is not installed, nothing below matters. */
    private String describeGms() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(GMS_PACKAGE, 0);
            return pi.versionName != null ? pi.versionName : getString(R.string.status_unknown);
        } catch (Throwable t) {
            return getString(R.string.status_gms_absent);
        }
    }

    /** Section whose contents arrive once the probe finishes. */
    private View hookSection() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wrapper.addView(sectionLabel(getString(R.string.status_hooks_title), false));

        hookSummary = new TextView(this);
        hookSummary.setText(getString(R.string.status_probing));
        hookSummary.setTextSize(12f);
        hookSummary.setTextColor(getColor(R.color.md_on_surface_variant));
        hookSummary.setPadding(dp(8), dp(4), dp(8), dp(12));
        wrapper.addView(hookSummary);

        // No background of its own: each host renders as its own card, so the
        // container only stacks them.
        hookGroup = new LinearLayout(this);
        hookGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hookGroup.setLayoutParams(lp);
        wrapper.addView(hookGroup);

        return wrapper;
    }

    private void probeHooks() {
        Context appContext = getApplicationContext();
        probeExecutor.execute(() -> {
            List<HookStatus.Item> items = HookStatus.probe(appContext);
            mainHandler.post(() -> renderHooks(items));
        });
    }

    private void renderHooks(List<HookStatus.Item> items) {
        if (hookGroup == null || hookSummary == null) {
            return;
        }
        hookGroup.removeAllViews();
        int present = 0;
        int unexpected = 0;
        int unknown = 0;
        for (HookStatus.Item item : items) {
            if (item.state == HookStatus.State.PRESENT) {
                present++;
            }
            if (item.isUnexpectedAbsence()) {
                unexpected++;
            }
            if (item.state == HookStatus.State.UNKNOWN) {
                unknown++;
            }
        }
        if (unknown == items.size()) {
            hookSummary.setText(getString(R.string.status_hooks_unreachable));
            return;
        }
        hookSummary.setText(unexpected == 0
                ? getString(R.string.status_hooks_ok, present, items.size())
                : getString(R.string.status_hooks_missing, unexpected, present,
                items.size()));

        // One card per host. The two hosts are separate processes holding
        // separate classes, so the list is cut at the same line the module
        // itself is cut at — a single card with a divider inside read as one
        // continuous list rather than as two different pieces of software.
        LinearLayout card = null;
        HookStatus.Side side = null;
        boolean firstRow = true;
        for (HookStatus.Item item : items) {
            if (item.side != side) {
                side = item.side;
                card = hostCard(card == null);
                card.addView(groupHeader(getString(side.labelRes)));
                hookGroup.addView(card);
                firstRow = true;
            } else if (!firstRow) {
                card.addView(divider());
            }
            card.addView(hookRow(item));
            firstRow = false;
        }
    }

    private View hookRow(HookStatus.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginEnd(dp(12));

        TextView name = new TextView(this);
        name.setText(item.target);
        name.setTextSize(13f);
        // Method names are identifiers, not prose: monospaced they line up and
        // stay readable where a proportional face makes them run together.
        name.setTypeface(Typeface.MONOSPACE);
        name.setTextColor(getColor(R.color.md_on_surface));
        text.addView(name);

        int note = noteFor(item);
        if (note != 0) {
            TextView noteView = new TextView(this);
            noteView.setText(getString(note));
            noteView.setTextSize(11f);
            noteView.setTextColor(getColor(R.color.md_hint_light));
            text.addView(noteView);
        }
        row.addView(text, textLp);

        TextView state = new TextView(this);
        state.setText(stateText(item));
        state.setTextSize(12f);
        state.setTextColor(stateColor(item));
        row.addView(state);
        return row;
    }

    private String stateText(HookStatus.Item item) {
        switch (item.state) {
            case PRESENT:
                return getString(R.string.status_state_present);
            case ABSENT:
                return getString(item.isUnexpectedAbsence()
                        ? R.string.status_state_absent_unexpected
                        : R.string.status_state_absent_expected);
            default:
                return getString(R.string.status_state_unknown);
        }
    }

    private int stateColor(HookStatus.Item item) {
        if (item.state == HookStatus.State.PRESENT) {
            return getColor(R.color.md_primary);
        }
        if (item.state == HookStatus.State.ABSENT) {
            // An expected absence is a ROM difference, not a problem, so it stays
            // in the quiet secondary tone; only an unexpected one gets the
            // emphasis of the accent colour.
            return getColor(item.isUnexpectedAbsence()
                    ? R.color.md_primary
                    : R.color.md_on_surface_variant);
        }
        return getColor(R.color.md_hint_light);
    }

    /** Small line saying which ROM generation a target belongs to, if not all. */
    private int noteFor(HookStatus.Item item) {
        if (item.expect == HookStatus.Expect.HYPEROS_3) {
            return R.string.status_note_hyperos3;
        }
        if (item.expect == HookStatus.Expect.HYPEROS_4) {
            return R.string.status_note_hyperos4;
        }
        if (item.expect == HookStatus.Expect.NONE) {
            return R.string.status_note_never;
        }
        if (item.expect == HookStatus.Expect.OPTIONAL) {
            return R.string.status_note_optional;
        }
        return 0;
    }

    private View diagnosticCard() {
        LinearLayout card = card();
        card.addView(commandRow(DIAG_LOG));
        card.addView(commandRow(DIAG_GCM));
        return card;
    }

    private View commandRow(String command) {
        TextView tv = new TextView(this);
        tv.setText(command);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(getColor(R.color.md_on_surface));
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setBackgroundResource(R.drawable.bg_card_press_ripple);
        tv.setOnClickListener(v -> {
            UiUtils.tapFeedback(v);
            copyToClipboard(command);
        });
        return tv;
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("command", text));
                Toast.makeText(this, R.string.status_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        }
    }

    // ---- small builders -------------------------------------------------

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    /**
     * A card holding one host's targets. A real card edge per host, with a gap
     * between them, rather than one long card split by dividers.
     */
    private LinearLayout hostCard(boolean firstCard) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!firstCard) {
            lp.topMargin = dp(14);
        }
        card.setLayoutParams(lp);
        return card;
    }

    private TextView cardTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setTypeface(Typeface.create("sans-medium", Typeface.NORMAL));
        tv.setTextColor(getColor(R.color.md_on_surface));
        tv.setPadding(0, 0, 0, dp(10));
        return tv;
    }

    private TextView cardBody(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(getColor(R.color.md_on_surface_variant));
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    /**
     * A section heading, so the two sections below the cards read as sections
     * rather than as more cards. The first one keeps the top margin; anything
     * that follows gets extra space above it so it is not read as a continuation
     * of the list it sits under.
     */
    private TextView sectionLabel(String text, boolean spaced) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(getColor(R.color.md_primary));
        tv.setTypeface(Typeface.create("sans-medium", Typeface.NORMAL));
        tv.setPadding(dp(8), spaced ? dp(32) : dp(20), dp(8), dp(6));
        return tv;
    }

    /**
     * Name of the process a group of targets belongs to. It sits inside a card
     * of its own, so it also carries the host's scope name — the same word
     * LSPosed shows in the module's scope list.
     */
    private TextView groupHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.create("sans-medium", Typeface.NORMAL));
        tv.setTextColor(getColor(R.color.md_on_surface));
        tv.setPadding(dp(16), dp(10), dp(16), dp(6));
        return tv;
    }

    private LinearLayout keyValue(int keyRes, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView key = new TextView(this);
        key.setText(getString(keyRes));
        key.setTextSize(14f);
        key.setTextColor(getColor(R.color.md_on_surface_variant));
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        keyLp.setMarginEnd(dp(12));

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(14f);
        val.setTextColor(getColor(R.color.md_on_surface));
        val.setTypeface(Typeface.create("sans-medium", Typeface.NORMAL));

        row.addView(key, keyLp);
        row.addView(val);
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.md_state_hover));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.setMarginStart(dp(16));
        lp.setMarginEnd(dp(16));
        v.setLayoutParams(lp);
        return v;
    }

    private boolean isOnCellular() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) {
                return false;
            }
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable t) {
            return false;
        }
    }

    private String describeNetwork() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) {
                return getString(R.string.status_unknown);
            }
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return getString(R.string.status_network_none);
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) {
                return getString(R.string.status_unknown);
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return getString(R.string.status_network_wifi);
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return getString(R.string.status_network_cellular);
            }
            return getString(R.string.status_network_other);
        } catch (Throwable t) {
            return getString(R.string.status_unknown);
        }
    }

    private int dp(int value) {
        return UiUtils.dp(this, value);
    }
}
