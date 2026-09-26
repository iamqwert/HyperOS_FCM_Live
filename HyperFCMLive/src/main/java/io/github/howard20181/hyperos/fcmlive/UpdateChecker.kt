package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * GitHub latest-release check. Auto-check is throttled to 24h (anonymous API
 * is 60/hour per IP); manual check always goes to the network.
 */
object UpdateChecker {

    /**
     * Note: `onError` is a JVM default method so existing Java lambdas that
     * only implement [onResult] keep compiling as functional interfaces.
     */
    interface Callback {
        fun onResult(updateAvailable: Boolean, latestVersion: String, downloadUrl: String)

        /**
         * Invoked instead of [onResult] when the request failed (network,
         * non-200 status, or an empty tag). Optional so existing lambdas compile.
         */
        fun onError() {
        }
    }

    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/iamqwert/HyperOS_FCM_Live/releases/latest"
    /** Fallback source: no API rate limit and no User-Agent requirement. */
    private const val LATEST_RELEASE_ATOM =
        "https://github.com/iamqwert/HyperOS_FCM_Live/releases.atom"
    private const val RELEASES_PAGE =
        "https://github.com/iamqwert/HyperOS_FCM_Live/releases"
    /** GitHub rejects requests with no User-Agent header (HTTP 403). */
    private const val USER_AGENT = "HyperOS-FCM-Live"
    private const val KEY_UPDATE_AVAILABLE = "update_available"
    private const val KEY_UPDATE_VERSION = "update_version"
    private const val KEY_UPDATE_URL = "update_url"
    private const val KEY_LAST_AUTO_CHECK = "update_last_auto_check"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    /** Manual check: always hits the network (user-initiated). */
    @JvmStatic
    fun checkAsync(context: Context, callback: Callback?) {
        checkInternal(context, callback, true)
    }

    /** Launch-time check: at most once per 24 hours. */
    @JvmStatic
    fun checkAutoAsync(context: Context, callback: Callback?) {
        checkInternal(context, callback, false)
    }

    private fun checkInternal(context: Context, callback: Callback?, force: Boolean) {
        val app = context.applicationContext
        if (!force) {
            val last = prefs(app).getLong(KEY_LAST_AUTO_CHECK, 0L)
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) {
                callback?.onResult(
                    isUpdateAvailable(app),
                    cachedVersion(app),
                    cachedUrl(app)
                )
                return
            }
        }
        Thread {
            var latest: String? = null
            var htmlUrl = RELEASES_PAGE
            var ok = false
            var conn: HttpURLConnection? = null
            try {
                conn = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty(
                    "User-Agent",
                    USER_AGENT + "/" + localVersionName(app)
                )
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val code = conn.responseCode
                if (code == 200) {
                    val json = JSONObject(readStream(conn.inputStream))
                    val tag = json.optString("tag_name", "").replaceFirst("^v", "")
                    if (tag.isNotEmpty()) {
                        latest = tag
                        ok = true
                    }
                    val page = json.optString("html_url", "")
                    if (isTrustedReleasePage(page)) {
                        htmlUrl = page
                    }
                } else {
                    Log.w(TAG, "HTTP $code")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "check failed", t)
            } finally {
                conn?.disconnect()
            }
            if (!ok) {
                // API blocked (rate limit / no User-Agent / network): fall back to
                // the public Atom feed, which is neither rate limited nor picky
                // about headers. Its <title> is the release name, so read the tag
                // from <id> and the page from the alternate <link>.
                val atom = fetchLatestFromAtom(USER_AGENT + "/" + localVersionName(app))
                if (atom != null && atom[0] != null && atom[0]!!.isNotEmpty()) {
                    latest = atom[0]
                    ok = true
                    if (isTrustedReleasePage(atom[1])) {
                        htmlUrl = atom[1]!!
                    }
                }
            }
            if (ok) {
                // Only a successful lookup consumes the 24h auto-check budget.
                prefs(app).edit()
                    .putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis())
                    .apply()
            } else {
                // A failed check is not proof that there is no update: keep the
                // cached badge and report the failure instead of "up to date".
                callback?.let { Handler(Looper.getMainLooper()).post(it::onError) }
                return@Thread
            }
            // CI tags releases as v{versionName}.{versionCode}, so compare
            // against the same shape to avoid a permanent false positive.
            // ok==true always came with a non-empty latest; keep the same
            // assumption as the Java original (NPE if that invariant breaks).
            val resolved = latest!!
            val available = compareVersions(resolved, localVersionTag(app)) > 0
            val version = resolved
            val url = htmlUrl
            saveState(app, available, version, url)
            callback?.let { cb ->
                val result = available
                Handler(Looper.getMainLooper())
                    .post { cb.onResult(result, version, url) }
            }
        }.start()
    }

    @JvmStatic
    fun isUpdateAvailable(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_UPDATE_AVAILABLE, false)
    }

    @JvmStatic
    fun cachedVersion(context: Context): String {
        return prefs(context).getString(KEY_UPDATE_VERSION, "") ?: ""
    }

    @JvmStatic
    fun cachedUrl(context: Context): String {
        return prefs(context).getString(KEY_UPDATE_URL, "") ?: ""
    }

    @JvmStatic
    fun clearBadge(context: Context) {
        saveState(context, false, "", "")
    }

    private fun saveState(context: Context, available: Boolean, version: String?, url: String?) {
        prefs(context).edit()
            .putBoolean(KEY_UPDATE_AVAILABLE, available)
            .putString(KEY_UPDATE_VERSION, version ?: "")
            .putString(KEY_UPDATE_URL, url ?: "")
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(Prefs.LOCAL_PREFS, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun localVersionName(context: Context): String {
        return try {
            context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (t: Throwable) {
            Log.w(TAG, "localVersionName", t)
            "0"
        }
    }

    /**
     * Local version in the same shape as the release tag
     * (`versionName.versionCode`), e.g. `1.7.0.17`. CI tags releases
     * as `v{versionName}.{versionCode}`, so comparing against the plain
     * versionName would make the extra tag segment look like a newer build
     * forever.
     */
    @JvmStatic
    fun localVersionTag(context: Context): String {
        return localVersionName(context) + "." + localVersionCode(context)
    }

    private fun localVersionCode(context: Context): Long {
        return try {
            val pi = context.packageManager
                .getPackageInfo(context.packageName, 0)
            pi.longVersionCode
        } catch (t: Throwable) {
            Log.w(TAG, "localVersionCode", t)
            0L
        }
    }

    /**
     * Numeric segments compared left to right; a pre-release suffix ranks
     * lower than the same numeric version without a suffix (1.6.0-rc1 < 1.6.0).
     */
    @JvmStatic
    fun compareVersions(a: String, b: String): Int {
        val pa = splitVersion(a)
        val pb = splitVersion(b)
        val n = Math.max(pa.size, pb.size)
        for (i in 0 until n) {
            val va = if (i < pa.size) parsePart(pa[i]) else 0
            val vb = if (i < pb.size) parsePart(pb[i]) else 0
            if (va != vb) {
                return Integer.compare(va, vb)
            }
        }
        val aPre = hasPrereleaseSuffix(a)
        val bPre = hasPrereleaseSuffix(b)
        if (aPre != bPre) {
            return if (aPre) -1 else 1
        }
        return 0
    }

    private fun splitVersion(v: String): Array<String> {
        return v.replaceFirst("^v", "").split("[.-]").toTypedArray()
    }

    private fun hasPrereleaseSuffix(v: String): Boolean {
        val s = v.replaceFirst("^v", "")
        val dash = s.indexOf('-')
        return dash >= 0 && dash < s.length - 1
    }

    private fun parsePart(s: String): Int {
        return try {
            Integer.parseInt(s.trim())
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * Fallback latest-release lookup over the public Atom feed.
     * Returns `{version, pageUrl}` or null when the feed is unreachable or
     * has no entry. String parsing keeps this dependency-free; the feed layout is
     * stable (`<id>` ends with the tag, first href is the release page).
     */
    private fun fetchLatestFromAtom(userAgent: String): Array<String?>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(LATEST_RELEASE_ATOM).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode != 200) {
                return null
            }
            val feed = readStream(conn.inputStream)
            val entry = feed.indexOf("<entry>")
            if (entry < 0) {
                return null
            }
            val first = feed.substring(entry)
            val id = between(first, "<id>", "</id>")
            if (id == null || id.indexOf('/') < 0) {
                return null
            }
            // tag:github.com,2008:Repository/<id>/v1.8.0.18
            val tag = id.substring(id.lastIndexOf('/') + 1).replaceFirst("^v", "")
            if (tag.isEmpty()) {
                return null
            }
            var page = between(first, "href=\"", "\"")
            if (!isTrustedReleasePage(page)) {
                page = RELEASES_PAGE
            }
            arrayOf(tag, page)
        } catch (t: Throwable) {
            Log.w(TAG, "atom fallback failed", t)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Whether a page from the release feed may be opened for the user.
     *
     * The update flow hands this URL to an implicit ACTION_VIEW, so an
     * unexpected value — a hijacked repository, a tampered response — would turn
     * "a new version is available" into a phishing redirect. Only https pages on
     * github.com under /releases/ are accepted; anything else falls back to the
     * fixed releases page.
     */
    private fun isTrustedReleasePage(url: String?): Boolean {
        if (url.isNullOrEmpty()) {
            return false
        }
        return try {
            val uri = URI(url)
            if (!"https".equals(uri.scheme, ignoreCase = true)) {
                return false
            }
            val host = uri.host
            if (host == null || !"github.com".equals(host, ignoreCase = true)) {
                return false
            }
            val path = uri.path
            path != null && path.contains("/releases/")
        } catch (t: Throwable) {
            false
        }
    }

    private fun between(src: String, start: String, end: String): String? {
        var from = src.indexOf(start)
        if (from < 0) {
            return null
        }
        from += start.length
        val to = src.indexOf(end, from)
        return if (to < 0) null else src.substring(from, to)
    }

    private fun readStream(input: InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                sb.append(line)
                line = reader.readLine()
            }
        }
        return sb.toString()
    }
}
