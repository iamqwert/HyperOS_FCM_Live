package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * GitHub latest-release check. Auto-check is throttled to 24h (anonymous API
 * is 60/hour per IP); manual check always goes to the network.
 */
public final class UpdateChecker {

    public interface Callback {
        void onResult(boolean updateAvailable, String latestVersion, String downloadUrl);
    }

    private static final String TAG = "UpdateChecker";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/iamqwert/HyperOS_FCM_Live/releases/latest";
    private static final String KEY_UPDATE_AVAILABLE = "update_available";
    private static final String KEY_UPDATE_VERSION = "update_version";
    private static final String KEY_UPDATE_URL = "update_url";
    private static final String KEY_LAST_AUTO_CHECK = "update_last_auto_check";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private UpdateChecker() {
    }

    /** Manual check: always hits the network (user-initiated). */
    public static void checkAsync(Context context, Callback callback) {
        checkInternal(context, callback, true);
    }

    /** Launch-time check: at most once per 24 hours. */
    public static void checkAutoAsync(Context context, Callback callback) {
        checkInternal(context, callback, false);
    }

    private static void checkInternal(Context context, Callback callback, boolean force) {
        final Context app = context.getApplicationContext();
        if (!force) {
            long last = prefs(app).getLong(KEY_LAST_AUTO_CHECK, 0L);
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) {
                if (callback != null) {
                    callback.onResult(isUpdateAvailable(app),
                            cachedVersion(app), cachedUrl(app));
                }
                return;
            }
        }
        new Thread(() -> {
            String latest = null;
            String htmlUrl = "https://github.com/iamqwert/HyperOS_FCM_Live/releases";
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                int code = conn.getResponseCode();
                if (code == 200) {
                    JSONObject json = new JSONObject(readStream(conn.getInputStream()));
                    latest = json.optString("tag_name", "").replaceFirst("^v", "");
                    String page = json.optString("html_url", "");
                    if (page.length() > 0) {
                        htmlUrl = page;
                    }
                } else {
                    Log.w(TAG, "HTTP " + code);
                }
            } catch (Throwable t) {
                Log.w(TAG, "check failed", t);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
                prefs(app).edit()
                        .putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis())
                        .apply();
            }
            boolean available = latest != null && latest.length() > 0
                    && compareVersions(latest, localVersionName(app)) > 0;
            final String version = latest != null ? latest : "";
            final String url = htmlUrl;
            saveState(app, available, version, url);
            if (callback != null) {
                final boolean result = available;
                new Handler(Looper.getMainLooper())
                        .post(() -> callback.onResult(result, version, url));
            }
        }).start();
    }

    public static boolean isUpdateAvailable(Context context) {
        return prefs(context).getBoolean(KEY_UPDATE_AVAILABLE, false);
    }

    public static String cachedVersion(Context context) {
        return prefs(context).getString(KEY_UPDATE_VERSION, "");
    }

    public static String cachedUrl(Context context) {
        return prefs(context).getString(KEY_UPDATE_URL, "");
    }

    public static void clearBadge(Context context) {
        saveState(context, false, "", "");
    }

    private static void saveState(Context context, boolean available, String version, String url) {
        prefs(context).edit()
                .putBoolean(KEY_UPDATE_AVAILABLE, available)
                .putString(KEY_UPDATE_VERSION, version != null ? version : "")
                .putString(KEY_UPDATE_URL, url != null ? url : "")
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Prefs.LOCAL_PREFS, Context.MODE_PRIVATE);
    }

    public static String localVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            Log.w(TAG, "localVersionName", t);
            return "0";
        }
    }

    /**
     * Numeric segments compared left to right; a pre-release suffix ranks
     * lower than the same numeric version without a suffix (1.6.0-rc1 &lt; 1.6.0).
     */
    public static int compareVersions(String a, String b) {
        String[] pa = splitVersion(a);
        String[] pb = splitVersion(b);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parsePart(pa[i]) : 0;
            int vb = i < pb.length ? parsePart(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        boolean aPre = hasPrereleaseSuffix(a);
        boolean bPre = hasPrereleaseSuffix(b);
        if (aPre != bPre) {
            return aPre ? -1 : 1;
        }
        return 0;
    }

    private static String[] splitVersion(String v) {
        return v.replaceFirst("^v", "").split("[.-]");
    }

    private static boolean hasPrereleaseSuffix(String v) {
        String s = v.replaceFirst("^v", "");
        int dash = s.indexOf('-');
        return dash >= 0 && dash < s.length() - 1;
    }

    private static int parsePart(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String readStream(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
