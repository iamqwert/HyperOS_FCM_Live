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
import kotlin.math.roundToInt

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
 * [onChanged] is how the composables learn that an icon arrived: the rows read
 * [AppEntry.icon] directly, so something has to tell them it changed.
 */
class AppListStore(context: Context, private val onChanged: () -> Unit) {

    /**
     * One app. Deliberately a plain mutable class rather than a snapshot state:
     * these are produced by a background package scan and matched back against
     * the allowlist by identity, and every mutation is followed by a fresh list
     * instance reaching the composables, which is what triggers recomposition.
     */
    class AppEntry(
        @JvmField val packageName: String,
        @JvmField val label: String
    ) {
        @JvmField
        var icon: Drawable? = null

        @Volatile
        @JvmField
        var iconLoading: Boolean = false

        @JvmField
        var checked: Boolean = false

        /** Manifest components (Firebase service / receiver, or their actions). */
        @JvmField
        var supportFcm: Boolean = false

        /**
         * Declares the MiPush service: the app has its own system-channel push
         * route, so waking FCM for it is usually unnecessary. Shown as a tag.
         */
        @JvmField
        var supportMiPush: Boolean = false
    }

    private val appContext: Context = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconLoader: ExecutorService = Executors.newFixedThreadPool(4)
    private val density: Float = appContext.resources.displayMetrics.density

    /** True while a coalesced icon refresh is already posted to the main handler. */
    private var refreshPosted = false

    fun loadIcon(app: AppEntry) {
        if (app.iconLoading) {
            return
        }
        app.iconLoading = true
        iconLoader.execute {
            val d: Drawable? = try {
                pm.getApplicationIcon(app.packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            val loaded = d?.let { shrinkToRowSize(it) }
            app.iconLoading = false
            if (loaded != null) {
                app.icon = loaded
                scheduleIconRefresh()
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

    /** Coalesce icon-load refreshes so one frame does not recompose the list N times. */
    private fun scheduleIconRefresh() {
        if (refreshPosted) {
            return
        }
        refreshPosted = true
        mainHandler.postDelayed({
            refreshPosted = false
            onChanged()
        }, 50L)
    }

    /** Release icon worker threads (call from Activity.onDestroy). */
    fun shutdown() {
        iconLoader.shutdown()
        // Also drops the pending coalesced refresh post.
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        /** Row icon size in dp, matching the row icon in AppRow. */
        private const val ICON_SIZE_DP = 44
    }
}
