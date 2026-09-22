package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** Small shared UI helpers (open URL, px conversion). */
public final class UiUtils {

    private UiUtils() {
    }

    public static void openUrl(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show();
        }
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int statusBarHeight(Context context) {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
    }
}
