package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.howard20181.hyperos.fcmlive.theme.AppPalette;
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine;
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport;

public class AppListAdapter extends BaseAdapter {

    public interface OnCardListener {
        /** Normal mode: toggle whitelist. Multi-select mode is handled by the adapter. */
        void onToggleAllowlist(String packageName, boolean checked);

        /** Long-press outside multi-select: enter selection mode with this package. */
        void onEnterMultiSelect(String packageName);

        /** Multi-select mode: selection set changed (count for title bar). */
        void onSelectionChanged(int count);
    }

    public static class AppEntry {
        public final String packageName;
        public final String label;
        public Drawable icon;
        public volatile boolean iconLoading;
        public boolean checked;
        /** Manifest components (Firebase service / receiver, or their actions). */
        public boolean supportFcm;
        /**
         * Declares the MiPush service: the app has its own system-channel push
         * route, so waking FCM for it is usually unnecessary. Shown as a tag.
         */
        public boolean supportMiPush;

        public AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private final LayoutInflater inflater;
    private final PackageManager pm;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService iconLoader = Executors.newFixedThreadPool(4);
    private final List<AppEntry> apps;
    private final OnCardListener listener;
    private final int enabledColor;
    private final int disabledColor;
    private final int cardColor;
    private final int cardSelectedColor;
    private final int rippleColor;
    private final float density;
    private final Context context;

    private boolean multiSelectMode = false;
    private final Set<String> selectedPkgs = new HashSet<>();

    public AppListAdapter(Context context, List<AppEntry> apps, OnCardListener listener) {
        this.context = context.getApplicationContext();
        this.inflater = LayoutInflater.from(context);
        this.pm = context.getPackageManager();
        this.apps = apps;
        this.listener = listener;
        AppPalette palette = ThemeEngine.palette(context);
        this.enabledColor = palette.primary;
        this.disabledColor = palette.onSurfaceVariant;
        this.cardColor = palette.card;
        this.cardSelectedColor = palette.primaryContainer;
        this.rippleColor = palette.ripple;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    public void setMultiSelectMode(boolean enabled) {
        if (multiSelectMode == enabled) {
            return;
        }
        multiSelectMode = enabled;
        if (!enabled) {
            selectedPkgs.clear();
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(selectedPkgs.size());
        }
    }

    public void setSelectedPackages(Set<String> packages) {
        selectedPkgs.clear();
        if (packages != null) {
            selectedPkgs.addAll(packages);
        }
        notifyDataSetChanged();
    }

    public Set<String> getSelectedPackages() {
        return new HashSet<>(selectedPkgs);
    }

    @Override
    public int getCount() {
        return apps.size();
    }

    @Override
    public AppEntry getItem(int position) {
        return apps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_app, parent, false);
            holder = new ViewHolder();
            holder.icon = convertView.findViewById(R.id.app_icon);
            holder.label = convertView.findViewById(R.id.app_label);
            holder.pkg = convertView.findViewById(R.id.app_pkg);
            holder.status = convertView.findViewById(R.id.app_status);
            holder.mipushBadge = convertView.findViewById(R.id.app_mipush_badge);
            holder.titleRow = convertView.findViewById(R.id.app_title_row);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        AppEntry app = getItem(position);
        holder.label.setText(app.label);
        holder.pkg.setText(app.packageName);
        if (app.icon != null) {
            holder.icon.setImageDrawable(app.icon);
        } else {
            holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            loadIcon(app);
        }

        bindStatus(holder.status, app.checked);
        bindMiPushTag(holder, app.supportMiPush);
        clearIconTooltip(convertView);
        clearIconTooltip(holder.icon);
        clearIconTooltip(holder.status);

        final boolean selected = multiSelectMode && selectedPkgs.contains(app.packageName);
        // Fresh mutate() instance per bind — never share a RippleDrawable across
        // recycled rows (that paints the press ripple on the wrong bounds).
        convertView.setBackground(newSolidCardBg(selected));
        ensurePressRipple(convertView);

        final String pkg = app.packageName;
        convertView.setOnClickListener(v -> {
            UiUtils.tapFeedback(v);
            if (multiSelectMode) {
                toggleSelection(pkg);
                return;
            }
            AppEntry current = findByPackage(pkg);
            if (current == null) {
                return;
            }
            boolean next = !current.checked;
            current.checked = next;
            bindStatus(holder.status, next);
            if (listener != null) {
                listener.onToggleAllowlist(pkg, next);
            }
        });

        convertView.setOnLongClickListener(v -> {
            if (!multiSelectMode && listener != null) {
                listener.onEnterMultiSelect(pkg);
            } else if (multiSelectMode) {
                toggleSelection(pkg);
            }
            return true;
        });
        return convertView;
    }

    /** Row corner radius in dp; matches bg_card / bg_card_selected. */
    private static final float CARD_RADIUS_DP = 24f;

    private Drawable newSolidCardBg(boolean selected) {
        return ThemeSupport.cardBackground(context,
                selected ? cardSelectedColor : cardColor, CARD_RADIUS_DP);
    }

    /**
     * Row press ripple lives on foreground (inflated per item view). Background
     * stays a solid shape so multi-select can swap colors without ripple state bugs.
     */
    private void ensurePressRipple(View row) {
        if (row == null || row.getForeground() != null) {
            return;
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(android.graphics.Color.WHITE);
        mask.setCornerRadius(CARD_RADIUS_DP * density);
        row.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleColor), null, mask));
    }

    private AppEntry findByPackage(String packageName) {
        if (packageName == null) {
            return null;
        }
        for (AppEntry e : apps) {
            if (packageName.equals(e.packageName)) {
                return e;
            }
        }
        return null;
    }

    private void toggleSelection(String packageName) {
        if (packageName == null) {
            return;
        }
        if (selectedPkgs.contains(packageName)) {
            selectedPkgs.remove(packageName);
        } else {
            selectedPkgs.add(packageName);
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(selectedPkgs.size());
        }
    }

    private void clearIconTooltip(View view) {
        if (view == null) {
            return;
        }
        view.setTooltipText(null);
        view.setLongClickable(false);
        if (view instanceof ImageView) {
            view.setContentDescription(null);
        }
    }

    private void bindStatus(ImageView status, boolean checked) {
        status.setImageResource(checked
                ? R.drawable.ic_status_enabled
                : R.drawable.ic_status_disabled);
        status.setColorFilter(checked ? enabledColor : disabledColor, PorterDuff.Mode.SRC_IN);
    }

    /**
     * Shows the MiPush tag beside the app name — and pays for it, because the
     * two pull against each other.
     *
     * <p>The name has to be wrap_content for the tag to sit against it, but a
     * wrap_content child is measured before the view behind it and takes what is
     * left of the row: a long name then claims the whole line and the tag is
     * clipped away, which is worse than a name without a tag. Giving the name a
     * ceiling of "row minus tag" restores the ellipsis it already asks for with
     * {@code maxLines} and {@code ellipsize}.
     *
     * <p>The width has to come from the row itself, so a row that has not been
     * laid out yet keeps no ceiling for one pass; every later bind (a scroll, or
     * any {@code notifyDataSetChanged}) sets it.
     */
    private void bindMiPushTag(ViewHolder holder, boolean visible) {
        if (holder.mipushBadge == null || holder.label == null) {
            return;
        }
        holder.mipushBadge.setVisibility(visible ? View.VISIBLE : View.GONE);
        int rowWidth = holder.titleRow != null ? holder.titleRow.getWidth() : 0;
        if (!visible || rowWidth <= 0) {
            holder.label.setMaxWidth(Integer.MAX_VALUE);
            return;
        }
        if (holder.tagWidth == 0) {
            holder.tagWidth = measureWidth(holder.mipushBadge, rowWidth) + startMargin(holder.mipushBadge);
        }
        holder.label.setMaxWidth(Math.max(0, rowWidth - holder.tagWidth));
    }

    private static int measureWidth(View view, int available) {
        view.measure(View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredWidth();
    }

    private static int startMargin(View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        return lp instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) lp).getMarginStart() : 0;
    }

    private void loadIcon(final AppEntry app) {
        if (app.iconLoading) {
            return;
        }
        app.iconLoading = true;
        iconLoader.execute(() -> {
            Drawable d;
            try {
                d = pm.getApplicationIcon(app.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                d = null;
            }
            final Drawable loaded = d != null ? shrinkToRowSize(d) : null;
            app.iconLoading = false;
            if (loaded != null) {
                app.icon = loaded;
                scheduleIconRefresh();
            }
        });
    }

    /** Row icon size in dp; matches {@code @+id/app_icon} in item_app.xml. */
    private static final int ICON_SIZE_DP = 44;

    /**
     * Re-decode a bitmap icon at the size the row actually paints.
     *
     * <p>Every loaded icon is kept on its {@link AppEntry} for the whole session,
     * and a few hundred of them at full resolution is real memory the list never
     * uses: the row is 44dp, so anything larger is stored at a size that can only
     * ever be drawn scaled down. Shrinking to the row size caps that.
     *
     * <p>Only {@link BitmapDrawable} sources are touched — a plain downscale,
     * which is pixel-identical to what the view was already drawing. Adaptive
     * icons are left alone on purpose: they carry a safe zone that a flat
     * rescale would break, and they hold no oversized bitmap of their own.
     */
    private Drawable shrinkToRowSize(Drawable source) {
        int size = Math.round(ICON_SIZE_DP * density);
        if (size <= 0) {
            return source;
        }
        if (!(source instanceof BitmapDrawable)) {
            return source;
        }
        Bitmap bitmap = ((BitmapDrawable) source).getBitmap();
        if (bitmap == null || (bitmap.getWidth() <= size && bitmap.getHeight() <= size)) {
            return source;
        }
        Bitmap scaled;
        try {
            scaled = Bitmap.createScaledBitmap(bitmap, size, size, true);
        } catch (Throwable t) {
            // Allocation failed: keep the original rather than show a blank row.
            return source;
        }
        return new BitmapDrawable(context.getResources(), scaled);
    }

    /** True while a coalesced icon refresh is already posted to the main handler. */
    private boolean refreshPosted;

    /** Coalesce icon-load refreshes so one frame does not spam notifyDataSetChanged. */
    private void scheduleIconRefresh() {
        if (refreshPosted) {
            return;
        }
        refreshPosted = true;
        mainHandler.postDelayed(() -> {
            refreshPosted = false;
            notifyDataSetChanged();
        }, 50L);
    }

    /** Release icon worker threads (call from Activity.onDestroy). */
    public void shutdown() {
        iconLoader.shutdown();
        // Also drops the pending coalesced refresh post.
        mainHandler.removeCallbacksAndMessages(null);
    }

    private static class ViewHolder {
        ImageView icon;
        TextView label;
        TextView pkg;
        ImageView status;
        TextView mipushBadge;
        /** Name row, whose measured width is what the name gets capped against. */
        View titleRow;
        /** Cached tag width (its own + its start margin); it never changes here. */
        int tagWidth;
    }
}
