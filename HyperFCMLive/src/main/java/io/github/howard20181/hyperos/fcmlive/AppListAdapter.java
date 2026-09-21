package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppListAdapter extends BaseAdapter {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(String packageName, boolean checked);
    }

    public static class AppEntry {
        public final String packageName;
        public final String label;
        public Drawable icon;
        public volatile boolean iconLoading;
        public boolean checked;

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
    private final OnCheckedChangeListener listener;
    private final int enabledColor;
    private final int disabledColor;

    public AppListAdapter(Context context, List<AppEntry> apps, OnCheckedChangeListener listener) {
        this.inflater = LayoutInflater.from(context);
        this.pm = context.getPackageManager();
        this.apps = apps;
        this.listener = listener;
        this.enabledColor = context.getColor(R.color.md_primary);
        this.disabledColor = context.getColor(R.color.md_on_surface_variant);
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
        convertView.setOnClickListener(v -> {
            boolean next = !app.checked;
            app.checked = next;
            bindStatus(holder.status, next);
            if (listener != null) {
                listener.onCheckedChanged(app.packageName, next);
            }
        });
        return convertView;
    }

    private void bindStatus(ImageView status, boolean checked) {
        status.setImageResource(checked
                ? R.drawable.ic_status_enabled
                : R.drawable.ic_status_disabled);
        status.setColorFilter(checked ? enabledColor : disabledColor, PorterDuff.Mode.SRC_IN);
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
            final Drawable loaded = d;
            app.iconLoading = false;
            if (loaded != null) {
                app.icon = loaded;
                mainHandler.post(AppListAdapter.this::notifyDataSetChanged);
            }
        });
    }

    private static class ViewHolder {
        ImageView icon;
        TextView label;
        TextView pkg;
        ImageView status;
    }
}
