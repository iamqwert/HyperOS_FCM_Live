package io.github.howard20181.hyperos.fcmlive.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What a row of the app list is made of, plus the one thing the rows cannot
 * produce for themselves: the app icon, which costs a package query.
 *
 * This was a `BaseAdapter` before the list moved to Compose. Only the icon
 * loading survives that move verbatim — the loader pool, the coalesced refresh
 * and the decision to leave non-bitmap icons alone are behaviour, not binding,
 * and re-deriving them would have risked regressions for no gain. What is gone
 * is the `getView` machinery: view holders, recycled rows, and the measure
 * pass that used to cap the app name against its tag.
 *
 * Icons are delivered through [onIconsReady] keyed by package rather than
 * written back onto the [AppEntry]: the caller keeps them in a per-package
 * snapshot map, so an icon arriving during a scroll recomposes only that one
 * row instead of rebuilding the whole visible list the way the old
 * `notifyDataSetChanged` did.
 */
class AppListStore(context: Context, private val onIconsReady: (Map<String, Drawable>) -> Unit) {

    /**
     * One app's *static* row data, produced by the background package scan.
     *
     * The mutable allowlist state (`checked`) and the async `icon` deliberately
     * do not live here: `checked` is owned by the Activity's allowlist set and
     * mirrored into a per-package snapshot map for the row to read, and the icon
     * arrives later through [onIconsReady]. Keeping them out of [AppEntry] lets
     * the row read exactly the one key it needs and recompose in isolation.
     */
    class AppEntry(
        @JvmField val packageName: String,
        @JvmField val label: String
    ) {
        /**
         * This row is exempt from the allowlist: the module always acts for it,
         * so its checkbox carries no meaning. Drawn greyed out and made
         * non-interactive by the row composable, which is why the flag lives
         * here rather than being recomputed at each of the gesture call sites.
         *
         * [exemptNote] is the string resource explaining *why* this particular
         * row is inert — the two cases have different reasons and a single
         * shared sentence would be wrong for one of them.
         */
        @JvmField
        var exempt: Boolean = false

        @JvmField
        var exemptNote: Int = 0

        /** Manifest components (Firebase service / receiver, or their actions). */
        @JvmField
        var supportFcm: Boolean = false

        /**
         * Declares the MiPush service: the app has its own system-channel push
         * route, so waking FCM for it is usually unnecessary. Shown as a tag.
         */
        @JvmField
        var supportMiPush: Boolean = false

        /** Allowlist membership, owned by the Activity and read back at scan time. */
        @JvmField
        var checked: Boolean = false
    }

    private val appContext: Context = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconLoader: ExecutorService = Executors.newFixedThreadPool(4)
    private val density: Float = appContext.resources.displayMetrics.density

    /** Packages with a load in flight, so a row re-composing does not re-queue it. */
    private val loading = HashSet<String>()
    /** Icons that landed since the last flush, coalesced to one delivery per frame. */
    private val pending = HashMap<String, Drawable>()
    private var flushPosted = false

    fun loadIcon(app: AppEntry) {
        if (!loading.add(app.packageName)) {
            return
        }
        val pkg = app.packageName
        iconLoader.execute {
            val d: Drawable? = try {
                pm.getApplicationIcon(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            val loaded = d?.let { shrinkToRowSize(it) }
            mainHandler.post {
                loading.remove(pkg)
                if (loaded != null) {
                    pending[pkg] = loaded
                    scheduleFlush()
                }
            }
        }
    }

    /**
     * Re-decode a bitmap icon at the size the row actually paints.
     *
     * Every loaded icon is kept on its [AppEntry] for the whole session, and a
     * few hundred of them at full resolution is real memory the list never
     * uses: the row is 44dp, so anything larger is stored at a size that can
     * only ever be drawn scaled down. Shrinking to the row size caps that.
     *
     * Only [BitmapDrawable] sources are touched — a plain downscale, which is
     * pixel-identical to what the row was already drawing. Adaptive icons are
     * left alone on purpose: they carry a safe zone that a flat rescale would
     * break, and they hold no oversized bitmap of their own.
     */
    private fun shrinkToRowSize(source: Drawable): Drawable {
        val size = Math.round(ICON_SIZE_DP * density)
        if (size <= 0) {
            return source
        }
        if (source !is BitmapDrawable) {
            return source
        }
        val bitmap = source.bitmap ?: return source
        if (bitmap.width <= size && bitmap.height <= size) {
            return source
        }
        val scaled: Bitmap = try {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        } catch (t: Throwable) {
            // Allocation failed: keep the original rather than show a blank row.
            return source
        }
        return BitmapDrawable(appContext.resources, scaled)
    }

    /** Coalesce icon deliveries so one frame flushes every icon that landed. */
    private fun scheduleFlush() {
        if (flushPosted) {
            return
        }
        flushPosted = true
        mainHandler.postDelayed({
            flushPosted = false
            if (pending.isNotEmpty()) {
                val batch = HashMap(pending)
                pending.clear()
                onIconsReady(batch)
            }
        }, 50L)
    }

    /** Release icon worker threads (call from Activity.onDestroy). */
    fun shutdown() {
        iconLoader.shutdown()
        // Also drops the pending coalesced flush post.
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        /** Row icon size in dp, matching the row icon in AppRow. */
        private const val ICON_SIZE_DP = 44
    }
}
