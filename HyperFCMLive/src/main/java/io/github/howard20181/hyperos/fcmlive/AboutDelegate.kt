package io.github.howard20181.hyperos.fcmlive

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.howard20181.hyperos.fcmlive.ui.UpdateOutcome
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import io.github.howard20181.hyperos.fcmlive.ui.WindowSnapshot
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems

/**
 * Everything the settings screen needs from an Activity, in one object.
 *
 * The screen ([AboutScreen]) is pure Compose: the rows, menus and the update
 * offer all live there. But some of what it asks for only an Activity can do —
 * the SAF import/export contracts need an `ActivityResultRegistry`, the update
 * check is a network call whose release it offers must open with `startActivity`,
 * and a theme change has a View-side half no recomposition reaches. That half
 * used to live only in [AboutActivity]; the M3 home now hosts the same screen
 * as a bottom-nav tab ([MainActivity]), so the capability moved here and both
 * hosts hold one instance.
 *
 * Feedback routes through the [showMessage] lambda: each host acknowledges in
 * its own shell — AboutActivity toasts under Miuix and snackbar-hosts under
 * Material; the main page's tab carries its own bar.
 */
class AboutDelegate(
    private val activity: ComponentActivity,
    private val showMessage: (String) -> Unit
) {

    /**
     * Where the release the check just offered lives. Held here rather than on
     * the screen because opening it launches another app, which only the host
     * Activity can do.
     */
    private var pendingDownloadUrl: String? = null

    /**
     * SAF export/import via Activity Result API (no startActivityForResult).
     * The returned URI is externally supplied, so both paths are re-checked
     * before any stream is opened — see [isGrantedContentUri].
     */
    private val createAllowlistDoc =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                writeAllowlistTo(uri)
            }
        }
    private val openAllowlistDoc =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                readAllowlistFrom(uri)
            }
        }

    /**
     * The version line is bound from PackageManager — the same source the
     * update check compares against — so it cannot drift the way a hard-coded
     * string did.
     */
    fun versionLine(): String {
        val info = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot read own package info", t)
            null
        }
        val name = info?.versionName ?: return ""
        return activity.getString(R.string.about_sub_check_update, name, info.longVersionCode)
    }

    /**
     * The hero's release tag in the reference about page's format:
     * "v4.1.1 (53)" — version name plus the version code in parentheses.
     */
    fun versionTag(): String = try {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        "v${info.versionName} (${info.longVersionCode})"
    } catch (t: Throwable) {
        Log.w(TAG, "Cannot read own package info", t)
        ""
    }

    /**
     * Runs the check and hands the answer straight back to the screen, which
     * decides what it looks like. This side keeps only the two things the
     * screen cannot hold: the URL to open, and the badge flag that is cleared
     * when that URL is opened.
     */
    fun checkForUpdates(report: (UpdateOutcome) -> Unit) {
        UpdateChecker.checkAsync(activity, object : UpdateChecker.Callback {
            override fun onResult(
                updateAvailable: Boolean,
                latestVersion: String,
                downloadUrl: String
            ) {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    if (!updateAvailable) {
                        report(UpdateOutcome.None)
                        return@runOnUiThread
                    }
                    pendingDownloadUrl = downloadUrl
                    report(UpdateOutcome.Available(latestVersion))
                }
            }

            override fun onError() {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        return@runOnUiThread
                    }
                    report(UpdateOutcome.Error)
                }
            }
        })
    }

    /** Opens the release the check offered, and drops the badge with it. */
    fun openPendingUpdate() {
        val url = pendingDownloadUrl ?: return
        pendingDownloadUrl = null
        UpdateChecker.clearBadge(activity)
        openLink(url)
    }

    /**
     * Opens a link, and says so in the page when nothing can take it. The URL
     * itself is the message: a device with no browser can still be shown where
     * it was meant to go.
     */
    fun openLink(url: String) {
        if (!UiUtils.openUrl(activity, url)) {
            showMessage(url)
        }
    }

    /**
     * In-place re-skin. The palette invalidation itself lives in the
     * [AppearanceState] setters — each appearance write bumps ThemeEngine's
     * generation counter and the Compose tree follows it — so what is left here
     * is the View-side half no recomposition reaches: the window chrome repaint
     * and the back-preview snapshot. The old path was `recreate()` — a
     * whole-window rebuild that read as a visible jump every time an appearance
     * menu option was picked.
     *
     * The back-preview snapshot is refreshed too. It is a photograph of the
     * window *below* this one taken on the way in, so it is wearing the old
     * palette the moment this runs; left alone, returning would reveal the
     * previous theme. The pages below are stopped but still alive and still
     * laid out, so they can be redrawn here even though they are not on
     * screen. See [WindowSnapshot.refreshForTheme].
     */
    fun applyAppearanceChange() {
        ThemeSupport.reapplyWindow(activity)
        WindowSnapshot.refreshForTheme()
    }

    fun exportAllowlist() {
        try {
            createAllowlistDoc.launch("fcmlive-allowlist.txt")
        } catch (t: Throwable) {
            showMessage(activity.getString(R.string.allowlist_export_failed))
        }
    }

    fun importAllowlist() {
        try {
            openAllowlistDoc.launch(arrayOf("text/plain"))
        } catch (t: Throwable) {
            showMessage(activity.getString(R.string.allowlist_import_failed))
        }
    }

    private fun currentAllowlist(): Set<String> {
        val allow = Prefs.readAllowlist(Prefs.remote())
        return if (allow.isEmpty()) Prefs.readLocalAllowlist(activity) else allow
    }

    private fun writeAllowlistTo(uri: Uri) {
        val sorted = currentAllowlist().toMutableList()
        java.util.Collections.sort(sorted)

        // ACTION_CREATE_DOCUMENT returns an externally supplied URI. Require a
        // content URI and an explicit write grant before resolving it. This
        // prevents an arbitrary caller/provider URI from being used as a
        // ContentResolver target.
        if (!isGrantedContentUri(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            showMessage(activity.getString(R.string.allowlist_export_failed))
            return
        }
        // The authority decides which provider answers; the *path* is what the
        // provider itself interprets, and a provider is free to mean
        // "/data/data/<app>/..." by it. Resolving such a URI is what turns a
        // document picker into a way to overwrite this app's own private files.
        // Normalising first is what makes the prefix test mean anything:
        // "/safe/../../data/data/<app>/x" names the /data path it normalises to.
        if (!isSafeDocumentPath(uri)) {
            showMessage(activity.getString(R.string.allowlist_export_failed))
            return
        }

        try {
            val out: OutputStream = activity.contentResolver.openOutputStream(uri)
                ?: throw IOException("null stream")
            out.use { stream ->
                val sb = StringBuilder()
                for (pkg in sorted) {
                    sb.append(pkg).append('\n')
                }
                stream.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                showMessage(activity.getString(R.string.allowlist_export_done, sorted.size))
            }
        } catch (t: Throwable) {
            showMessage(activity.getString(R.string.allowlist_export_failed))
        }
    }

    private fun readAllowlistFrom(uri: Uri) {
        val allow = HashSet<String>()

        // Same checks as the export side: the provider interprets the path, so
        // it is normalised and the private roots are refused before a stream is
        // opened — otherwise a provider can answer with this app's own files.
        if (!isGrantedContentUri(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            showMessage(activity.getString(R.string.allowlist_import_failed))
            return
        }
        if (!isSafeDocumentPath(uri)) {
            showMessage(activity.getString(R.string.allowlist_import_failed))
            return
        }

        try {
            val input = activity.contentResolver.openInputStream(uri)
                ?: throw IOException("null stream")
            input.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val pkg = line.trim()
                        if (pkg.isNotEmpty() && !pkg.startsWith("#")) {
                            allow.add(pkg)
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (t: Throwable) {
            showMessage(activity.getString(R.string.allowlist_import_failed))
            return
        }
        if (allow.isEmpty()) {
            showMessage(activity.getString(R.string.allowlist_import_empty))
            return
        }
        Prefs.writeAllowlist(activity, Prefs.remote(), allow)
        showMessage(activity.getString(R.string.allowlist_import_done, allow.size))
    }

    /**
     * Refuses paths that escape upwards or name a root we must never write to.
     * A leftover ".." means the path still escapes: `normalize()` keeps it when
     * there is nothing above it to collapse into.
     */
    private fun isSafeDocumentPath(uri: Uri): Boolean {
        val path = uri.path ?: return false
        val normalized = FileSystems.getDefault().getPath(path).normalize()
        if (normalized.startsWith("/data")) {
            return false
        }
        val resolved = normalized.toString()
        return !resolved.contains("..") &&
            !resolved.startsWith("/system") &&
            !resolved.startsWith("/vendor") &&
            !resolved.startsWith("/proc") &&
            !resolved.startsWith("/dev")
    }

    /**
     * Accept only content:// URIs for which this process currently has the
     * requested explicit URI permission. The permission check is performed
     * against this process UID/PID, so an arbitrary URI supplied by another
     * component cannot be resolved unless Android has actually granted access.
     */
    private fun isGrantedContentUri(uri: Uri?, grantFlag: Int): Boolean {
        if (uri == null || uri.scheme != "content" ||
            uri.authority.isNullOrEmpty()
        ) {
            return false
        }
        return activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), grantFlag) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "AboutDelegate"

        const val REPO_URL = "https://github.com/iamqwert/HyperOS_FCM_Live"

        /** The GPL-3.0 licence text — the about page's licence rows open it. */
        const val GPL3_URL = "https://www.gnu.org/licenses/gpl-3.0.txt"

        /**
         * Online help, opened in the browser from the top-bar help icon and
         * from the "help" launcher shortcut (see [ShortcutPublisher]).
         *
         * The in-app help page was removed on purpose: its text had to be kept
         * in sync with `Hooker` by hand, which drifted more than once. Keeping
         * a single copy in the repository means a wording fix ships without an
         * APK release. Point this at a URL that renders Markdown.
         */
        private const val HELP_URL_ZH = "https://github.com/iamqwert/HyperOS_FCM_Live/blob/main/HELP.md"
        private const val HELP_URL_EN = "https://github.com/iamqwert/HyperOS_FCM_Live/blob/main/HELP_EN.md"

        /**
         * The help doc in the app's current display language — the guide is
         * bilingual, and the entry point must match what the reader is reading
         * the rest of the UI in.
         */
        fun helpUrl(context: android.content.Context): String =
            if (AppLocale.current(context) == AppLocale.INDEX_ENGLISH) HELP_URL_EN else HELP_URL_ZH
    }
}
