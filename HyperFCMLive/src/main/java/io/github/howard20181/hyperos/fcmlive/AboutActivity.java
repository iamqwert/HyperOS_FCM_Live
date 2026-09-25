package io.github.howard20181.hyperos.fcmlive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import io.github.howard20181.hyperos.fcmlive.theme.AppPalette;
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine;
import io.github.howard20181.hyperos.fcmlive.theme.ThemePrefs;
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** About: source, licenses, allowlist backup, update check with red badge. */
public class AboutActivity extends Activity {

    private static final String REPO_URL = "https://github.com/iamqwert/HyperOS_FCM_Live";
    private static final int REQ_EXPORT = 2001;
    private static final int REQ_IMPORT = 2002;

    /** Easter egg: 7 taps on the version row within 2s, then silent for 10s. */
    private static final int EGG_TAP_COUNT = 7;
    private static final long EGG_WINDOW_MS = 2000L;
    private static final long EGG_COOLDOWN_MS = 10000L;

    /**
     * Dropdown menu metrics (dp/sp). Options are separated by a generous gap
     * and the label sits a step above the previous size, so the menu reads as
     * a calm list of choices rather than a dense stack. Every menu uses these
     * metrics unchanged, however many options it holds: a long menu is placed
     * so that it fits on screen — it is never squeezed, because shrinking the
     * rows not only costs legibility but also makes the menu look like a
     * different component than the short ones. The panel itself wears the page
     * background (see showPopupMenu).
     */
    private static final int MENU_CONTAINER_RADIUS_DP = 16;
    private static final int MENU_ITEM_RADIUS_DP = 12;
    private static final int MENU_OUTER_PAD_DP = 6;
    /** Vertical gap between two consecutive options. */
    private static final int MENU_ITEM_GAP_DP = 10;
    private static final int MENU_ITEM_PAD_H_DP = 12;
    private static final int MENU_ITEM_HEIGHT_DP = 40;
    /** M3 menu typography: md.sys.typescale.body-large = 16sp. */
    private static final int MENU_ITEM_TEXT_SP = 16;
    /** Extra panel width beyond the widest label: right-side breathing room. */
    private static final int MENU_EXTRA_WIDTH_DP = 28;
    /** Distance between the card and the menu panel. */
    private static final int MENU_ANCHOR_GAP_DP = 4;
    /** How far the panel is pulled in from the card's edge / the screen edge. */
    private static final int MENU_EDGE_INSET_DP = 12;
    /**
     * Breathing room the panel keeps inside the screen's safe area. It doubles
     * as the headroom the elevation shadow needs: the overlay is clipped by the
     * content view, so a panel flush with the safe area would have its shadow
     * cut off along that edge.
     */
    private static final int MENU_SCREEN_PAD_DP = 12;
    /**
     * Menu surface elevation. M3 puts menus at elevation level 2, i.e. 3dp:
     * enough for a soft, even halo around the panel, light enough that it does
     * not darken the page behind it.
     */
    private static final int MENU_ELEVATION_DP = 3;
    /** How long to wait for the menu teardown before rebuilding anyway (ms). */
    private static final long POPUP_DISMISS_GRACE_MS = 64L;
    /** Enter: 80% -> 100% over 250ms, overshooting on the way (see easeOutBack). */
    private static final float MENU_ENTER_FROM = 0.8f;
    private static final long MENU_ENTER_MS = 250L;
    /** Exit: the same shrink over 150ms, on the M3 accelerate curve. */
    private static final long MENU_EXIT_MS = 150L;
    /**
     * Peak overshoot of the ease-out-back curve, i.e. how far past its final
     * size the panel travels before settling — the ~10% the classic 1.70158
     * tension produces, which is the spring the panel lands with.
     */
    private static final float MENU_TENSION_FULL = 1.70158f;

    /**
     * CSS {@code ease-out-back} with a tunable overshoot: the value shoots past
     * the target once and then settles back onto it, which is what makes the
     * menu read as a spring rather than as a plain ease-out. {@code tension}
     * is the classic 1.70158 for a 10% overshoot; smaller values give a gentler
     * bounce (1.2 is about 5%).
     */
    private static Interpolator easeOutBack(final float tension) {
        final float c3 = tension + 1f;
        return t -> {
            final float u = t - 1f;
            return 1f + c3 * u * u * u + tension * u * u;
        };
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    /**
     * A card tap: one tick of haptic feedback, then whatever the row does.
     *
     * <p>Only the list cards get it — not the version row (it is deliberately
     * inert, and a buzz on every tap would give the easter egg away) and not
     * the open-source licenses screen.
     */
    private static View.OnClickListener rowClick(Runnable action) {
        return v -> {
            UiUtils.tapFeedback(v);
            action.run();
        };
    }

    /** Wires one settings row: the standard tap feedback, then the action. */
    private void bindRow(int rowId, Runnable action) {
        View row = findViewById(rowId);
        if (row != null) {
            row.setOnClickListener(rowClick(action));
        }
    }

    /** Wires a row whose tap opens one of the appearance popup menus. */
    private void bindPopupRow(int rowId, int titleRes, int entriesRes,
                              IntSupplier currentIndex, IntConsumer onPick) {
        View row = findViewById(rowId);
        if (row != null) {
            row.setOnClickListener(rowClick(() -> showPopupMenu(row, titleRes, entriesRes,
                    currentIndex.getAsInt(), onPick)));
        }
    }

    private final ArrayList<Long> eggTaps = new ArrayList<>(EGG_TAP_COUNT);
    private long lastEggAtMs;
    private TextView hideIconState;
    private MdSwitch hideIconSwitch;
    private TextView dynamicColorState;
    private MdSwitch dynamicColorSwitch;
    private ViewGroup dynamicColorSwatches;
    private TextView themeModeValue;
    private TextView paletteStyleValue;
    private TextView colorSpecValue;
    private TextView languageValue;

    /** The open menu: a full-screen overlay inside this window, or null. */
    private View menuOverlay;
    private View menuPanel;
    private boolean menuAbove;
    private boolean menuRtl;
    private OnBackInvokedCallback menuBackCallback;
    /**
     * The window's safe area, kept up to date by {@link #applySystemBarInsets}.
     * The menu overlay is positioned by hand in screen coordinates, so it has
     * to know where the status bar / gesture indicator actually are.
     */
    private int insetTop;
    private int insetBottom;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeSupport.attach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeSupport.onCreate(this);
        setContentView(R.layout.activity_about);
        applySystemBarInsets();

        View back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
            attachTip(back, R.string.back);
        }

        View help = findViewById(R.id.btn_help);
        if (help != null) {
            help.setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));
            attachTip(help, R.string.help);
        }

        bindRow(R.id.row_view_source, () -> openUrl(REPO_URL));
        bindRow(R.id.row_open_source_licenses,
                () -> startActivity(new Intent(this, LicensesActivity.class)));
        bindRow(R.id.row_export_allowlist, this::exportAllowlist);
        bindRow(R.id.row_import_allowlist, this::importAllowlist);
        bindRow(R.id.row_check_update, this::checkForUpdates);

        View versionRow = findViewById(R.id.row_current_version);
        if (versionRow != null) {
            versionRow.setOnClickListener(v -> onVersionRowTapped());
            // Long press must stay silent as well: consume it, do nothing.
            versionRow.setOnLongClickListener(v -> true);
        }

        hideIconState = findViewById(R.id.about_hide_icon_state);
        themeModeValue = findViewById(R.id.about_theme_mode_value);
        paletteStyleValue = findViewById(R.id.about_palette_style_value);
        colorSpecValue = findViewById(R.id.about_color_spec_value);
        languageValue = findViewById(R.id.about_language_value);
        bindHideIconRow();
        bindDynamicColorRow();
        bindLanguageRow();
        refreshHideIconState();
        refreshAppearanceState();

        // The value label keeps no listener of its own: it stays non-clickable
        // so a tap falls through to the row and plays the row's own ripple,
        // exactly like a tap anywhere else on the card.
        bindPopupRow(R.id.row_theme_mode, R.string.theme_mode, R.array.theme_mode_entries,
                () -> ThemePrefs.themeMode(this),
                index -> ThemePrefs.setThemeMode(this, index));
        bindPopupRow(R.id.row_palette_style, R.string.palette_style,
                R.array.palette_style_entries,
                () -> ThemePrefs.paletteStyle(this).ordinal(),
                index -> ThemePrefs.setPaletteStyle(this, variantAt(index)));
        bindPopupRow(R.id.row_color_spec, R.string.color_spec, R.array.color_spec_entries,
                () -> ThemePrefs.specVersion(this),
                index -> ThemePrefs.setSpecVersion(this, index));

        // Version name (version code), shown under the "Current version" row.
        TextView version = findViewById(R.id.about_version);
        if (version != null) {
            version.setText(moduleVersion());
        }

        showUpdateBadge(UpdateChecker.isUpdateAvailable(this));
    }

    private void checkForUpdates() {
        toastShort(R.string.update_checking);
        UpdateChecker.checkAsync(this, new UpdateChecker.Callback() {
            @Override
            public void onResult(boolean available, String latest, String url) {
                runOnUiThread(() -> {
                    if (AboutActivity.this.isFinishing() || AboutActivity.this.isDestroyed()) {
                        return;
                    }
                    showUpdateBadge(available);
                    if (!available) {
                        Toast.makeText(AboutActivity.this,
                                R.string.update_none, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new AlertDialog.Builder(AboutActivity.this)
                            .setMessage(getString(R.string.update_found, latest))
                            .setPositiveButton(R.string.update_open, (d, w) -> {
                                UpdateChecker.clearBadge(AboutActivity.this);
                                showUpdateBadge(false);
                                openUrl(url);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            }

            @Override
            public void onError() {
                runOnUiThread(() -> {
                    if (AboutActivity.this.isFinishing() || AboutActivity.this.isDestroyed()) {
                        return;
                    }
                    Toast.makeText(AboutActivity.this,
                            R.string.update_error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * The version row is inert: no clipboard write, no toast. The single output
     * is the joke toast after a rapid burst of taps, and even then it stays
     * quiet for a while so it cannot be spammed.
     */
    private void onVersionRowTapped() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastEggAtMs < EGG_COOLDOWN_MS) {
            // Still inside the cooldown: swallow the tap, no feedback at all.
            eggTaps.clear();
            return;
        }
        eggTaps.removeIf(tap -> now - tap > EGG_WINDOW_MS);
        eggTaps.add(now);
        if (eggTaps.size() >= EGG_TAP_COUNT) {
            eggTaps.clear();
            lastEggAtMs = now;
            toastShort(R.string.no_developer_options);
        }
    }

    /** The whole row is the touch target; the switch itself stays authoritative. */
    private void bindHideIconRow() {
        hideIconSwitch = findViewById(R.id.hide_icon_switch);
        View hideRow = findViewById(R.id.row_hide_icon);
        if (hideRow != null) {
            hideRow.setOnClickListener(rowClick(() -> {
                if (hideIconSwitch != null) {
                    hideIconSwitch.setChecked(!hideIconSwitch.isChecked());
                }
            }));
        }
        if (hideIconSwitch == null) {
            return;
        }
        AppPalette palette = ThemeEngine.palette(this);
        hideIconSwitch.applyPalette(palette);
        // PackageManager is the source of truth. Bind the value with the
        // non-animated setter (and before the listener attaches) so restoring
        // it never fires a spurious write, and so a screen rebuild cannot
        // leave a half-slid thumb behind: a slide we start here would be
        // aborted by the very next frame.
        hideIconSwitch.setCheckedImmediate(LauncherIcon.isHidden(this));
        hideIconSwitch.setOnCheckedChangeListener((button, checked) -> applyLauncherIcon(checked));
    }

    /**
     * Dynamic color: the switch decides between the wallpaper seed and a
     * hand-picked one. While it is off, a row of preset seed swatches appears
     * inside the card; tapping one applies it as the scheme seed immediately.
     */
    private void bindDynamicColorRow() {
        dynamicColorSwitch = findViewById(R.id.dynamic_color_switch);
        dynamicColorState = findViewById(R.id.about_dynamic_color_state);
        dynamicColorSwatches = findViewById(R.id.dynamic_color_swatches);
        View row = findViewById(R.id.row_dynamic_color);
        if (row != null) {
            row.setOnClickListener(rowClick(() -> {
                if (dynamicColorSwitch != null) {
                    // Same reasoning as the listener below: the state change
                    // rebuilds the screen, so jump straight to the new state.
                    dynamicColorSwitch.setCheckedImmediate(!dynamicColorSwitch.isChecked());
                }
            }));
        }
        if (dynamicColorSwitch == null) {
            return;
        }
        dynamicColorSwitch.applyPalette(ThemeEngine.palette(this));
        boolean dynamic = ThemePrefs.dynamicColor(this);
        // Restore before the listener attaches, so no spurious write fires —
        // and without a slide, because this view is rebuilt on every theme
        // change (see setCheckedImmediate).
        dynamicColorSwitch.setCheckedImmediate(dynamic);
        dynamicColorSwitch.setOnCheckedChangeListener((button, checked) -> {
            ThemePrefs.setDynamicColor(this, checked);
            // Flipping this switch rebuilds the whole screen in the same frame
            // (the palette is resolved while the layout is inflated). A thumb
            // slide therefore can never play out: snap it to the end position
            // and let the rebuild paint the final state. Sitting on a
            // half-finished animation is what the user sees as a twitch.
            dynamicColorSwitch.setCheckedImmediate(checked);
            applyAppearanceChange();
        });
        if (dynamicColorState != null) {
            dynamicColorState.setText(dynamic
                    ? R.string.about_sub_dynamic_color_on
                    : R.string.about_sub_dynamic_color_off);
        }
        buildSeedSwatches(dynamic);
    }

    /** Preset seeds offered when dynamic color is off (MD3-friendly hues). */
    private static final int[] SEED_COLORS = {
            0xFF6750A4, // Material baseline purple (default)
            0xFFB3261E, // red
            0xFFBF360C, // deep orange
            0xFFE65100, // orange
            0xFF9A6200, // amber
            0xFF827717, // olive
            0xFF558B2F, // lime
            0xFF2E7D32, // green
            0xFF006A6A, // teal
            0xFF00838F, // cyan
            0xFF0277BD, // light blue
            0xFF0B57D0, // blue
            0xFF3949AB, // indigo
            0xFF5E35B1, // deep purple
            0xFFAD1457, // pink
            0xFF5F5E62, // neutral grey
    };

    /** Swatches per row. Six keeps the block to three rows on a phone. */
    private static final int SEED_COLUMNS = 6;
    /** Gap between two neighbouring swatches; the dot is centred in its cell. */
    private static final int SEED_GAP_DP = 8;
    /** Largest dot. A narrow screen shrinks it so that six still fit per row. */
    private static final int SEED_DOT_MAX_DP = 44;

    /** Fills the swatch grid; hidden entirely while dynamic color is on. */
    private void buildSeedSwatches(boolean dynamic) {
        if (dynamicColorSwatches == null) {
            return;
        }
        dynamicColorSwatches.setVisibility(dynamic ? View.GONE : View.VISIBLE);
        dynamicColorSwatches.removeAllViews();
        if (dynamic) {
            return;
        }
        AppPalette palette = ThemeEngine.palette(this);
        int selected = ThemePrefs.seedColor(this);
        // The grid is MATCH_PARENT, so its width depends on the page padding, the
        // card padding and its own indent: read all three from the views rather
        // than hard-coding them, otherwise a padding tweak elsewhere would
        // silently push the sixth swatch onto a row of its own.
        View card = findViewById(R.id.row_dynamic_color);
        View content = findViewById(R.id.about_content);
        ViewGroup.MarginLayoutParams gridLp =
                (ViewGroup.MarginLayoutParams) dynamicColorSwatches.getLayoutParams();
        int pagePad = content != null
                ? content.getPaddingStart() + content.getPaddingEnd() : 0;
        int cardPad = card != null ? card.getPaddingStart() + card.getPaddingEnd() : 0;
        int available = getResources().getDisplayMetrics().widthPixels
                - pagePad - cardPad - gridLp.getMarginStart() - gridLp.getMarginEnd();
        // Every swatch gets a square cell; the touch target is the cell, so it
        // stays at or above 48dp even when a narrow screen shrinks the dot.
        int cell = Math.max(dp(36), available / SEED_COLUMNS);
        int dot = Math.min(dp(SEED_DOT_MAX_DP), cell - dp(SEED_GAP_DP));
        for (int color : SEED_COLORS) {
            View swatch = new View(this);
            android.widget.GridLayout.LayoutParams lp =
                    new android.widget.GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = cell;
            swatch.setLayoutParams(lp);
            swatch.setBackground(new SeedSwatchDrawable(
                    color, color == selected, palette.primary, dp(2), dot));
            swatch.setClickable(true);
            swatch.setOnClickListener(v -> {
                ThemePrefs.setSeedColor(this, color);
                applyAppearanceChange();
            });
            dynamicColorSwatches.addView(swatch);
        }
    }

    /**
     * One seed swatch: a circle that previews the seed's own tonal ramp —
     * the left half in the dark tone, the right half split into two light
     * tones — instead of a flat dot, so every option hints at the palette it
     * generates. The active seed gets a primary ring and a white check mark
     * on its dark half.
     */
    private static final class SeedSwatchDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF circle = new RectF();
        private final Path check = new Path();
        private final int darkTone;
        private final int midTone;
        private final int lightTone;
        private final boolean selected;
        private final int ringColor;
        private final float stroke;
        /** Diameter of the drawn dot; it is centred inside the swatch's cell. */
        private final int dotSize;

        SeedSwatchDrawable(int seed, boolean selected, int ringColor, float stroke, int dotSize) {
            io.github.howard20181.hyperos.fcmlive.mcu.Hct hct =
                    io.github.howard20181.hyperos.fcmlive.mcu.Hct.fromInt(seed);
            double hue = hct.getHue();
            // Floor the chroma so near-grey seeds still read as tonal ramps.
            double chroma = Math.max(hct.getChroma(), 8.0);
            darkTone = io.github.howard20181.hyperos.fcmlive.mcu.Hct.from(hue, chroma, 40).toInt();
            midTone = io.github.howard20181.hyperos.fcmlive.mcu.Hct.from(hue, chroma, 80).toInt();
            lightTone = io.github.howard20181.hyperos.fcmlive.mcu.Hct.from(hue, chroma, 90).toInt();
            this.selected = selected;
            this.ringColor = ringColor;
            this.stroke = stroke;
            this.dotSize = dotSize;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float radius = Math.min(dotSize, Math.min(b.width(), b.height())) / 2f - stroke;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            circle.set(cx - radius, cy - radius, cx + radius, cy + radius);

            paint.setStyle(Paint.Style.FILL);
            // Base: the lightest tone fills the whole circle...
            paint.setColor(lightTone);
            canvas.drawCircle(cx, cy, radius, paint);
            // ...the bottom-right quadrant takes the mid tone...
            paint.setColor(midTone);
            canvas.drawArc(circle, 0f, 90f, true, paint);
            // ...and the left half takes the dark tone.
            paint.setColor(darkTone);
            canvas.drawArc(circle, 90f, 180f, true, paint);

            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(ringColor);
                canvas.drawCircle(cx, cy, radius + stroke / 2f, paint);
                // White check on the dark half, where it stays readable.
                paint.setColor(Color.WHITE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                float kx = cx - radius * 0.45f;
                check.reset();
                check.moveTo(kx - radius * 0.28f, cy);
                check.lineTo(kx - radius * 0.05f, cy + radius * 0.23f);
                check.lineTo(kx + radius * 0.36f, cy - radius * 0.26f);
                canvas.drawPath(check, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Desktop icon: apply the requested state and confirm it with a toast. */
    private void applyLauncherIcon(boolean hidden) {
        LauncherIcon.setHidden(this, hidden);
        Toast.makeText(this,
                hidden ? R.string.hide_icon_toast : R.string.show_icon_toast,
                Toast.LENGTH_LONG).show();
        refreshHideIconState();
    }

    /**
     * Material 3 dropdown menu for one card: anchored under the card, right
     * aligned with its edge, as wide as its widest option, wearing the page
     * background and highlighting the current option with a primary container.
     *
     * <p>The menu is a plain view inside the Activity window rather than a
     * popup window, for two reasons that both showed up on device:
     * <ul>
     *   <li><b>Origin.</b> A window animation transforms the popup's own
     *       surface, so the transform origin is resolved against the window
     *       (not the panel) and the ROM layers its own panel animation on top —
     *       which is why the menu appeared to grow out of its middle instead of
     *       the corner nearest the card.</li>
     *   <li><b>Overshoot.</b> A window surface clips whatever leaves its
     *       bounds, and that surface is exactly the size of the menu, so an
     *       ease-out-back overshoot would be sliced off.</li>
     * </ul>
     * As an in-window view the panel scales around a real corner, nothing else
     * animates it, and the full-screen overlay it sits in gives the overshoot
     * somewhere to go.
     */
    private void showPopupMenu(View anchor, int titleRes, int entriesRes, int current,
                               java.util.function.IntConsumer onPick) {
        dismissMenu();
        String[] items = getResources().getStringArray(entriesRes);
        final int checked = clamp(current, 0, items.length - 1);
        final AppPalette palette = ThemeEngine.palette(this);

        // Content-adaptive width: measure the widest label, add the horizontal
        // padding a row needs, then a fixed slack so labels get right-side
        // breathing room. Rows still span the full panel, so the ripple and
        // the rounded clipping cover the whitespace exactly the same.
        android.text.TextPaint measure = new android.text.TextPaint();
        measure.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                MENU_ITEM_TEXT_SP, getResources().getDisplayMetrics()));
        float widest = 0f;
        for (String item : items) {
            widest = Math.max(widest, measure.measureText(item));
        }
        final int panelW = (int) Math.ceil(widest)
                + dp(MENU_ITEM_PAD_H_DP) * 2 + dp(MENU_OUTER_PAD_DP) * 2
                + dp(MENU_EXTRA_WIDTH_DP);

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
        final boolean rtl = getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        int anchorGap = dp(MENU_ANCHOR_GAP_DP);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int outer = dp(MENU_OUTER_PAD_DP);
        int rowH = dp(MENU_ITEM_HEIGHT_DP);
        int itemGap = dp(MENU_ITEM_GAP_DP);
        final Interpolator backCurve = easeOutBack(MENU_TENSION_FULL);
        int naturalH = naturalHeight(rowH, itemGap, outer, items.length);
        int topLimit = insetTop + dp(MENU_SCREEN_PAD_DP);
        int bottomLimit = screenH - insetBottom - dp(MENU_SCREEN_PAD_DP);
        // Only a menu that cannot fit the screen at all is capped, and then the
        // scroll inside the panel takes over: no menus ship like that today.
        int panelH = Math.min(naturalH, Math.max(rowH, bottomLimit - topLimit));
        int below = anchorLoc[1] + anchor.getHeight() + anchorGap;
        int above = anchorLoc[1] - anchorGap - panelH;
        int panelTop;
        if (below + panelH <= bottomLimit) {
            panelTop = below;
        } else if (above >= topLimit) {
            panelTop = above;
        } else {
            panelTop = clamp(below, topLimit, Math.max(topLimit, bottomLimit - panelH));
        }
        // The panel unfolds from whichever of its corners is nearest the card:
        // its bottom corner when the panel sits above the card's centre, its top
        // corner when the panel hangs below it.
        final boolean opensAbove =
                panelTop + panelH / 2 < anchorLoc[1] + anchor.getHeight() / 2;

        // An appearance change rebuilds this Activity (ThemeEngine.invalidate
        // + recreate). Doing that while the menu is still on screen leaves a
        // stale frame of the old menu over the new content, so the rebuild
        // waits until the menu has folded away.
        final boolean[] rebuildPending = {false};
        final Runnable rebuildIfPending = () -> {
            if (rebuildPending[0]) {
                rebuildPending[0] = false;
                applyAppearanceChange();
            }
        };

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.length; i++) {
            final int position = i;
            rows.addView(buildMenuRow(items[i], i == checked, i == 0, i == items.length - 1,
                    rowH, itemGap, palette, () -> {
                        onPick.accept(position);
                        rebuildPending[0] = true;
                        dismissMenu();
                        anchor.postDelayed(rebuildIfPending,
                                MENU_EXIT_MS + POPUP_DISMISS_GRACE_MS);
                    }));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(rows, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout panel = new FrameLayout(this);
        panel.setClipChildren(false);
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
        panel.setBackground(ThemeSupport.cardBackground(this, palette.pageBg,
                MENU_CONTAINER_RADIUS_DP));
        panel.setElevation(dp(MENU_ELEVATION_DP));
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        dp(MENU_CONTAINER_RADIUS_DP));
            }
        });
        panel.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelH));

        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        int[] decorLoc = new int[2];
        if (decor != null) {
            decor.getLocationOnScreen(decorLoc);
        }
        // Pull the panel in from the card's edge: flush against it, the menu
        // ends up brushing the screen edge, which looks cramped.
        int inset = dp(MENU_EDGE_INSET_DP);
        int panelStart = rtl
                ? anchorLoc[0] + inset
                : anchorLoc[0] + anchor.getWidth() - panelW - inset;

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(panelW, panelH);
        panelLp.leftMargin = panelStart - decorLoc[0];
        panelLp.topMargin = panelTop - decorLoc[1];

        FrameLayout overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        // A tap anywhere outside the panel dismisses, exactly like an outside
        // tap on a popup window would.
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> dismissMenu());
        overlay.addView(panel, panelLp);

        ViewGroup content = findViewById(android.R.id.content);
        content.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        menuOverlay = overlay;
        menuPanel = panel;
        menuAbove = opensAbove;
        menuRtl = rtl;
        // Back has to close the menu before it can leave the screen.
        menuBackCallback = this::dismissMenu;
        try {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, menuBackCallback);
        } catch (Throwable ignored) {
            // No dispatcher on this build: the overlay still dismisses on tap.
        }
        playMenuEnter(panel, opensAbove, rtl, backCurve);
    }

    /** Panel height for a given row height, gap and item count. */
    private int naturalHeight(int rowH, int itemGap, int outer, int count) {
        return rowH * count + itemGap * Math.max(0, count - 1) + outer * 2;
    }

    /**
     * Close the open menu, folding the panel back into the corner it grew from
     * before taking the overlay down.
     */
    private void dismissMenu() {
        final View overlay = menuOverlay;
        final View panel = menuPanel;
        final boolean opensAbove = menuAbove;
        final boolean rtl = menuRtl;
        menuOverlay = null;
        menuPanel = null;
        if (overlay == null) {
            return;
        }
        if (menuBackCallback != null) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(menuBackCallback);
            } catch (Throwable ignored) {
            }
            menuBackCallback = null;
        }
        if (panel == null) {
            detachMenuOverlay(overlay);
            return;
        }
        AnimationSet out = new AnimationSet(false);
        ScaleAnimation shrink = new ScaleAnimation(1f, MENU_ENTER_FROM, 1f, MENU_ENTER_FROM,
                Animation.RELATIVE_TO_SELF, rtl ? 0f : 1f,
                Animation.RELATIVE_TO_SELF, opensAbove ? 1f : 0f);
        shrink.setDuration(MENU_EXIT_MS);
        shrink.setInterpolator(AnimationUtils.loadInterpolator(this,
                R.interpolator.m3_emphasized_accelerate));
        AlphaAnimation fade = new AlphaAnimation(1f, 0f);
        fade.setDuration(MENU_EXIT_MS);
        fade.setInterpolator(shrink.getInterpolator());
        out.addAnimation(shrink);
        out.addAnimation(fade);
        out.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                detachMenuOverlay(overlay);
            }
        });
        panel.startAnimation(out);
        // Safety net: if the animation never reports its end (view detached,
        // animator duration scale set to 0), still take the overlay down.
        panel.postDelayed(() -> detachMenuOverlay(overlay),
                MENU_EXIT_MS + POPUP_DISMISS_GRACE_MS);
    }

    private void detachMenuOverlay(View overlay) {
        if (overlay == null || overlay.getParent() == null) {
            return;
        }
        ((ViewGroup) overlay.getParent()).removeView(overlay);
    }

    /**
     * M3 menu entrance: fade in while the panel grows out of the corner nearest
     * the card — its bottom-right while it hangs below the card, top-right when
     * it had to flip above it. {@code curve} is the ease-out-back that gives
     * the panel its single overshoot, i.e. the spring feel.
     */
    private void playMenuEnter(View panel, boolean opensAbove, boolean rtl, Interpolator curve) {
        AnimationSet set = new AnimationSet(false);
        ScaleAnimation grow = new ScaleAnimation(MENU_ENTER_FROM, 1f, MENU_ENTER_FROM, 1f,
                Animation.RELATIVE_TO_SELF, rtl ? 0f : 1f,
                Animation.RELATIVE_TO_SELF, opensAbove ? 1f : 0f);
        grow.setDuration(MENU_ENTER_MS);
        grow.setInterpolator(curve);
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(MENU_ENTER_MS);
        fade.setInterpolator(curve);
        set.addAnimation(grow);
        set.addAnimation(fade);
        panel.startAnimation(set);
    }

    /**
     * One dropdown option: a rounded row inset from the rounded menu container.
     * The ripple mask is the row shape itself, so the press feedback is clipped
     * to that rounded rectangle instead of a full-width rectangle. The label
     * is a single text starting at the common left edge; the panel's extra
     * width leaves a calm whitespace on the right.
     *
     * <p>{@code rowH} and {@code itemGap} are the shared menu metrics, so a
     * nine-option menu and a two-option one are built from the same rows.
     */
    private View buildMenuRow(String text, boolean selected, boolean first, boolean last,
                              int rowH, int itemGap, AppPalette palette, Runnable onPick) {
        int outer = dp(MENU_OUTER_PAD_DP);
        // Half the gap above and half below, so two consecutive options end up
        // exactly itemGap apart and every row keeps the same height.
        int half = itemGap / 2;
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrapper.setPadding(outer, first ? outer : half, outer, last ? outer : half);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // A fixed height (not just a minimum) keeps every option identical.
        row.setMinimumHeight(rowH);
        row.setPadding(dp(MENU_ITEM_PAD_H_DP), 0, dp(MENU_ITEM_PAD_H_DP), 0);

        TextView label = menuLabel(text, palette);
        row.addView(label, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Clickable so the ripple reacts to the press even inside a ListView.
        row.setClickable(true);
        row.setFocusable(false);
        row.setBackground(menuItemBackground(palette, selected));
        row.setOnClickListener(v -> onPick.run());
        wrapper.addView(row);
        return wrapper;
    }

    /** One menu label with the shared type, colour and single-line behaviour. */
    private TextView menuLabel(String text, AppPalette palette) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, MENU_ITEM_TEXT_SP);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setText(text);
        // Every label keeps the standard on-surface colour so it stays
        // readable; only the background marks the selection.
        tv.setTextColor(palette.onSurface);
        return tv;
    }

    /** Rounded fill plus a ripple masked to the very same rounded shape. */
    private Drawable menuItemBackground(AppPalette palette, boolean selected) {
        float radius = dp(MENU_ITEM_RADIUS_DP);
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.RECTANGLE);
        content.setCornerRadius(radius);
        // Unselected rows carry the page background, exactly like the panel
        // behind them, so the menu stays one flat surface; only the current
        // option is lifted with the primary container tone.
        content.setColor(selected ? palette.primaryContainer : palette.pageBg);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(radius);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(palette.ripple), content, mask);
    }

    private static io.github.howard20181.hyperos.fcmlive.mcu.Scheme.Variant variantAt(int index) {
        io.github.howard20181.hyperos.fcmlive.mcu.Scheme.Variant[] values =
                io.github.howard20181.hyperos.fcmlive.mcu.Scheme.Variant.values();
        return index >= 0 && index < values.length
                ? values[index]
                : io.github.howard20181.hyperos.fcmlive.mcu.Scheme.Variant.TONAL_SPOT;
    }

    /**
     * Language row: an in-app override of the device language. The menu lists
     * only the two languages the app ships, and picking one re-runs the screen
     * through {@link ThemeSupport#attach} — the same configuration rewrite that
     * forces light/dark, so a single recreate applies both.
     */
    private void bindLanguageRow() {
        View row = findViewById(R.id.row_language);
        if (row != null) {
            row.setOnClickListener(rowClick(() -> showPopupMenu(row,
                    R.string.language, R.array.language_entries,
                    effectiveLanguage(),
                    index -> ThemePrefs.setLanguage(this, index))));
        }
    }

    /**
     * Which entry the Language row shows: the pinned choice, or — while the app
     * still follows the device — whichever of the two is in effect right now, so
     * the row never sits empty.
     */
    private int effectiveLanguage() {
        int pinned = ThemePrefs.language(this);
        if (pinned >= 0) {
            return pinned;
        }
        java.util.Locale current = getResources().getConfiguration().getLocales().get(0);
        return "zh".equals(current.getLanguage()) ? 0 : 1;
    }

    private void applyAppearanceChange() {
        ThemeEngine.invalidate();
        recreate();
    }

    private void refreshAppearanceState() {
        String[] modes = getResources().getStringArray(R.array.theme_mode_entries);
        if (themeModeValue != null) {
            themeModeValue.setText(modes[ThemePrefs.themeMode(this)]);
        }
        String[] styles = getResources().getStringArray(R.array.palette_style_entries);
        if (paletteStyleValue != null) {
            paletteStyleValue.setText(styles[ThemePrefs.paletteStyle(this).ordinal()]);
        }
        String[] specs = getResources().getStringArray(R.array.color_spec_entries);
        if (colorSpecValue != null) {
            colorSpecValue.setText(specs[ThemePrefs.specVersion(this)]);
        }
        String[] languages = getResources().getStringArray(R.array.language_entries);
        if (languageValue != null) {
            languageValue.setText(languages[effectiveLanguage()]);
        }
    }

    private void refreshHideIconState() {
        if (hideIconState != null) {
            hideIconState.setText(LauncherIcon.isHidden(this)
                    ? R.string.about_sub_hide_icon_on
                    : R.string.about_sub_hide_icon_off);
        }
    }

    private String moduleVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private Set<String> currentAllowlist() {
        Set<String> allow = Prefs.readAllowlist(Prefs.remote());
        return allow.isEmpty() ? Prefs.readLocalAllowlist(this) : allow;
    }

    private void exportAllowlist() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_TITLE, "fcmlive-allowlist.txt");
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Throwable t) {
            toastShort(R.string.allowlist_export_failed);
        }
    }

    private void importAllowlist() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Throwable t) {
            toastShort(R.string.allowlist_import_failed);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        if (requestCode == REQ_EXPORT) {
            writeAllowlistTo(uri);
        } else if (requestCode == REQ_IMPORT) {
            readAllowlistFrom(uri);
        }
    }

    private void writeAllowlistTo(Uri uri) {
        List<String> sorted = new ArrayList<>(currentAllowlist());
        Collections.sort(sorted);

        // ACTION_CREATE_DOCUMENT returns an externally supplied URI. Require a
        // content URI and an explicit write grant before resolving it. This
        // prevents an arbitrary caller/provider URI from being used as a
        // ContentResolver target.
        if (!isGrantedContentUri(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            toastShort(R.string.allowlist_export_failed);
            return;
        }
        // The authority decides which provider answers; the *path* is what the
        // provider itself interprets, and a provider is free to mean
        // "/data/data/<app>/..." by it. Resolving such a URI is what turns a
        // document picker into a way to overwrite this app's own private files.
        // Normalising first is what makes the prefix test mean anything:
        // "/safe/../../data/data/<app>/x" names the /data path it normalises to.
        String path = uri.getPath();
        if (path == null) {
            toastShort(R.string.allowlist_export_failed);
            return;
        }
        java.nio.file.Path normalized =
                java.nio.file.FileSystems.getDefault().getPath(path).normalize();
        if (normalized.startsWith("/data")) {
            toastShort(R.string.allowlist_export_failed);
            return;
        }
        String resolved = normalized.toString();
        // A leftover ".." means the path still escapes upwards: normalize()
        // keeps it when there is nothing above it to collapse into.
        if (resolved.contains("..")
                || resolved.startsWith("/system")
                || resolved.startsWith("/vendor")
                || resolved.startsWith("/proc")
                || resolved.startsWith("/dev")) {
            toastShort(R.string.allowlist_export_failed);
            return;
        }

        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new java.io.IOException("null stream");
            }
            StringBuilder sb = new StringBuilder();
            for (String pkg : sorted) {
                sb.append(pkg).append('\n');
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, getString(R.string.allowlist_export_done, sorted.size()),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            toastShort(R.string.allowlist_export_failed);
        }
    }

    private void readAllowlistFrom(Uri uri) {
        Set<String> allow = new HashSet<>();

        // ACTION_OPEN_DOCUMENT returns an externally supplied URI. Require a
        // content URI and an explicit read grant before resolving it. This is
        // the security boundary for the ContentResolver operation.
        if (!isGrantedContentUri(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            toastShort(R.string.allowlist_import_failed);
            return;
        }
        // Same path check as the export side: the provider interprets the path,
        // so it is normalised and the private roots are refused before a stream
        // is opened — otherwise a provider can answer with this app's own files.
        String path = uri.getPath();
        if (path == null) {
            toastShort(R.string.allowlist_import_failed);
            return;
        }
        java.nio.file.Path normalized =
                java.nio.file.FileSystems.getDefault().getPath(path).normalize();
        if (normalized.startsWith("/data")) {
            toastShort(R.string.allowlist_import_failed);
            return;
        }
        String resolved = normalized.toString();
        if (resolved.contains("..")
                || resolved.startsWith("/system")
                || resolved.startsWith("/vendor")
                || resolved.startsWith("/proc")
                || resolved.startsWith("/dev")) {
            toastShort(R.string.allowlist_import_failed);
            return;
        }

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new java.io.IOException("null stream");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String pkg = line.trim();
                    if (pkg.length() > 0 && !pkg.startsWith("#")) {
                        allow.add(pkg);
                    }
                }
            }
        } catch (Throwable t) {
            toastShort(R.string.allowlist_import_failed);
            return;
        }
        if (allow.isEmpty()) {
            toastShort(R.string.allowlist_import_empty);
            return;
        }
        Prefs.writeAllowlist(this, Prefs.remote(), allow);
        Toast.makeText(this, getString(R.string.allowlist_import_done, allow.size()),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Accept only content:// URIs for which this process currently has the
     * requested explicit URI permission. The permission check is performed
     * against this process UID/PID, so an arbitrary URI supplied by another
     * component cannot be resolved unless Android has actually granted access.
     */
    private boolean isGrantedContentUri(Uri uri, int grantFlag) {
        if (uri == null || !"content".equals(uri.getScheme())
                || uri.getAuthority() == null || uri.getAuthority().isEmpty()) {
            return false;
        }
        return checkUriPermission(uri, Process.myPid(), Process.myUid(), grantFlag)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void toastShort(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    /** Offset tooltip below the anchor so HyperOS does not cover the icon. */
    private void attachTip(View view, int textRes) {
        if (view == null) {
            return;
        }
        final CharSequence tip = getText(textRes);
        view.setContentDescription(tip);
        view.setTooltipText(null);
        view.setLongClickable(true);
        view.setOnLongClickListener(v -> {
            showAnchorTooltip(v, tip);
            return true;
        });
    }

    private PopupWindow activeTooltip;

    private void showAnchorTooltip(View anchor, CharSequence text) {
        dismissActiveTooltip();
        TextView tipView = new TextView(this);
        AppPalette palette = ThemeEngine.palette(this);
        tipView.setText(text);
        tipView.setTextColor(palette.tooltipText);
        tipView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        tipView.setBackground(ThemeSupport.cardBackground(this, palette.tooltipBg, 4f));
        int padH = dp(12);
        int padV = dp(6);
        tipView.setPadding(padH, padV, padH, padV);
        tipView.setSingleLine(true);
        tipView.measure(
                View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.AT_MOST));

        PopupWindow popup = new PopupWindow(tipView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setOutsideTouchable(true);
        popup.setFocusable(false);
        popup.setBackgroundDrawable(
                new ColorDrawable(Color.TRANSPARENT));
        popup.setOnDismissListener(() -> {
            if (activeTooltip == popup) {
                activeTooltip = null;
            }
        });

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int gap = dp(8);
        int x = loc[0] + (anchor.getWidth() - tipView.getMeasuredWidth()) / 2;
        int y = loc[1] + anchor.getHeight() + gap;
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        int[] decorLoc = new int[2];
        if (decor != null) {
            decor.getLocationOnScreen(decorLoc);
        }
        try {
            popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY,
                    x - decorLoc[0], y - decorLoc[1]);
            activeTooltip = popup;
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

    @Override
    protected void onDestroy() {
        dismissActiveTooltip();
        // Drop any open menu (and its back callback) with the window.
        dismissMenu();
        super.onDestroy();
    }

    private void showUpdateBadge(boolean show) {
        View badge = findViewById(R.id.badge_update);
        if (badge != null) {
            badge.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void openUrl(String url) {
        UiUtils.openUrl(this, url);
    }

    /**
     * Edge-to-edge insets for the About screen.
     *
     * <p>Android 15 (API 35) forces every window to draw behind the system
     * bars, so the page owns the full screen: the root background reaches the
     * bottom edge — over the gesture home indicator — instead of stopping
     * above it, which is what left the blank, disconnected strip at the bottom.
     *
     * <p>The bottom safe area is therefore applied to the <em>scrollable
     * content</em> rather than to the window, the Android equivalent of CSS
     * {@code env(safe-area-inset-bottom)}: at the end of the scroll the last
     * card comes to rest clear of the indicator and stays tappable, while the
     * background behind it is still full-bleed.
     */
    private void applySystemBarInsets() {
        // The insets are also remembered: the menu overlay positions itself in
        // screen coordinates and must stay inside the safe area.
        UiUtils.applyBarInsets(this, findViewById(R.id.top_bar),
                findViewById(R.id.about_content), 16,
                (top, bottom) -> {
                    insetTop = top;
                    insetBottom = bottom;
                });
        // Fallback for ROMs that never dispatch insets to the listener above.
        int statusBar = UiUtils.statusBarHeight(this);
        if (statusBar > 0 && insetTop <= 0) {
            insetTop = statusBar;
        }
    }

    private int dp(int value) {
        return UiUtils.dp(this, value);
    }
}
