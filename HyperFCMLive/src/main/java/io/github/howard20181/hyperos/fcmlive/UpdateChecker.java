package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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

        /**
         * Invoked instead of {@link #onResult} when the request failed (network,
         * non-200 status, or an empty tag). Optional so existing lambdas compile.
         */
        default void onError() {
        }
    }

    private static final String TAG = "UpdateChecker";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/iamqwert/HyperOS_FCM_Live/releases/latest";
    /** Fallback source: no API rate limit and no User-Agent requirement. */
    private static final String LATEST_RELEASE_ATOM =
            "https://github.com/iamqwert/HyperOS_FCM_Live/releases.atom";
    private static final String RELEASES_PAGE =
            "https://github.com/iamqwert/HyperOS_FCM_Live/releases";
    /** GitHub rejects requests with no User-Agent header (HTTP 403). */
    private static final String USER_AGENT = "HyperOS-FCM-Live";
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
            String htmlUrl = RELEASES_PAGE;
            boolean ok = false;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent",
                        USER_AGENT + "/" + localVersionName(app));
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                int code = conn.getResponseCode();
                if (code == 200) {
                    JSONObject json = new JSONObject(readStream(conn.getInputStream()));
                    String tag = json.optString("tag_name", "").replaceFirst("^v", "");
                    if (tag.length() > 0) {
                        latest = tag;
                        ok = true;
                    }
                    String page = json.optString("html_url", "");
                    if (isTrustedReleasePage(page)) {
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
            }
            if (!ok) {
                // API blocked (rate limit / no User-Agent / network): fall back to
                // the public Atom feed, which is neither rate limited nor picky
                // about headers. Its <title> is the release name, so read the tag
                // from <id> and the page from the alternate <link>.
                String[] atom = fetchLatestFromAtom(USER_AGENT + "/" + localVersionName(app));
                if (atom != null && atom[0] != null && atom[0].length() > 0) {
                    latest = atom[0];
                    ok = true;
                    if (isTrustedReleasePage(atom[1])) {
                        htmlUrl = atom[1];
                    }
                }
            }
            if (ok) {
                // Only a successful lookup consumes the 24h auto-check budget.
                prefs(app).edit()
                        .putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis())
                        .apply();
            } else {
                // A failed check is not proof that there is no update: keep the
                // cached badge and report the failure instead of "up to date".
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(callback::onError);
                }
                return;
            }
            // CI tags releases as v{versionName}.{versionCode}, so compare
            // against the same shape to avoid a permanent false positive.
            boolean available = compareVersions(latest, localVersionTag(app)) > 0;
            final String version = latest;
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
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name != null ? name : "0";
        } catch (Throwable t) {
            Log.w(TAG, "localVersionName", t);
            return "0";
        }
    }

    /**
     * Local version in the same shape as the release tag
     * ({@code versionName.versionCode}), e.g. {@code 1.7.0.17}. CI tags releases
     * as {@code v{versionName}.{versionCode}}, so comparing against the plain
     * versionName would make the extra tag segment look like a newer build
     * forever.
     */
    public static String localVersionTag(Context context) {
        return localVersionName(context) + "." + localVersionCode(context);
    }

    private static long localVersionCode(Context context) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return pi.getLongVersionCode();
        } catch (Throwable t) {
            Log.w(TAG, "localVersionCode", t);
            return 0L;
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

    /**
     * Fallback latest-release lookup over the public Atom feed.
     * Returns {@code {version, pageUrl}} or null when the feed is unreachable or
     * has no entry. String parsing keeps this dependency-free; the feed layout is
     * stable (&lt;id&gt; ends with the tag, first href is the release page).
     */
    private static String[] fetchLatestFromAtom(String userAgent) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LATEST_RELEASE_ATOM).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", userAgent);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String feed = readStream(conn.getInputStream());
            int entry = feed.indexOf("<entry>");
            if (entry < 0) {
                return null;
            }
            String first = feed.substring(entry);
            String id = between(first, "<id>", "</id>");
            if (id == null || id.indexOf('/') < 0) {
                return null;
            }
            // tag:github.com,2008:Repository/<id>/v1.8.0.18
            String tag = id.substring(id.lastIndexOf('/') + 1).replaceFirst("^v", "");
            if (tag.length() == 0) {
                return null;
            }
            String page = between(first, "href=\"", "\"");
            if (!isTrustedReleasePage(page)) {
                page = RELEASES_PAGE;
            }
            return new String[]{tag, page};
        } catch (Throwable t) {
            Log.w(TAG, "atom fallback failed", t);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Whether a page from the release feed may be opened for the user.
     *
     * <p>The update flow hands this URL to an implicit ACTION_VIEW, so an
     * unexpected value — a hijacked repository, a tampered response — would turn
     * "a new version is available" into a phishing redirect. Only https pages on
     * github.com under /releases/ are accepted; anything else falls back to the
     * fixed releases page.
     */
    private static boolean isTrustedReleasePage(String url) {
        if (url == null || url.length() == 0) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || !"github.com".equalsIgnoreCase(host)) {
                return false;
            }
            String path = uri.getPath();
            return path != null && path.contains("/releases/");
        } catch (Throwable t) {
            return false;
        }
    }

    private static String between(String src, String start, String end) {
        int from = src.indexOf(start);
        if (from < 0) {
            return null;
        }
        from += start.length();
        int to = src.indexOf(end, from);
        return to < 0 ? null : src.substring(from, to);
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
