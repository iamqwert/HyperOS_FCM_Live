package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import io.github.howard20181.hyperos.fcmlive.theme.AppPalette
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import io.github.howard20181.hyperos.fcmlive.theme.ThemeSupport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class AppListAdapter(
    context: Context,
    private val apps: List<AppEntry>,
    private val listener: OnCardListener?
) : BaseAdapter() {

    interface OnCardListener {
        /** Normal mode: toggle whitelist. Multi-select mode is handled by the adapter. */
        fun onToggleAllowlist(packageName: String, checked: Boolean)

        /** Long-press outside multi-select: enter selection mode with this package. */
        fun onEnterMultiSelect(packageName: String)

        /** Multi-select mode: selection set changed (count for title bar). */
        fun onSelectionChanged(count: Int)
    }

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

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val pm: PackageManager = context.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconLoader: ExecutorService = Executors.newFixedThreadPool(4)
    private val context: Context = context.applicationContext

    private val enabledColor: Int
    private val disabledColor: Int
    private val cardColor: Int
    private val cardSelectedColor: Int
    private val rippleColor: Int
    private val density: Float

    private var multiSelectMode = false
    private val selectedPkgs: MutableSet<String> = HashSet()

    /** True while a coalesced icon refresh is already posted to the main handler. */
    private var refreshPosted = false

    init {
        val palette: AppPalette = ThemeEngine.palette(context)
        this.enabledColor = palette.primary
        this.disabledColor = palette.onSurfaceVariant
        this.cardColor = palette.card
        this.cardSelectedColor = palette.primaryContainer
        this.rippleColor = palette.ripple
        this.density = context.resources.displayMetrics.density
    }

    fun setMultiSelectMode(enabled: Boolean) {
        if (multiSelectMode == enabled) {
            return
        }
        multiSelectMode = enabled
        if (!enabled) {
            selectedPkgs.clear()
        }
        notifyDataSetChanged()
        listener?.onSelectionChanged(selectedPkgs.size)
    }

    fun setSelectedPackages(packages: Set<String>?) {
        selectedPkgs.clear()
        if (packages != null) {
            selectedPkgs.addAll(packages)
        }
        notifyDataSetChanged()
    }

    fun getSelectedPackages(): Set<String> = HashSet(selectedPkgs)

    override fun getCount(): Int = apps.size

    override fun getItem(position: Int): AppEntry = apps[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var convertView = convertView
        val holder: ViewHolder
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_app, parent, false)
            holder = ViewHolder()
            holder.icon = convertView.findViewById(R.id.app_icon)
            holder.label = convertView.findViewById(R.id.app_label)
            holder.pkg = convertView.findViewById(R.id.app_pkg)
            holder.status = convertView.findViewById(R.id.app_status)
            holder.mipushBadge = convertView.findViewById(R.id.app_mipush_badge)
            holder.titleRow = convertView.findViewById(R.id.app_title_row)
            convertView.tag = holder
        } else {
            holder = convertView.tag as ViewHolder
        }
        val app = getItem(position)
        holder.label!!.text = app.label
        holder.pkg!!.text = app.packageName
        val icon = app.icon
        if (icon != null) {
            holder.icon!!.setImageDrawable(icon)
        } else {
            holder.icon!!.setImageResource(android.R.drawable.sym_def_app_icon)
            loadIcon(app)
        }

        bindStatus(holder.status!!, app.checked)
        bindMiPushTag(holder, app.supportMiPush)
        clearIconTooltip(convertView)
        clearIconTooltip(holder.icon)
        clearIconTooltip(holder.status)

        val selected = multiSelectMode && selectedPkgs.contains(app.packageName)
        // Fresh mutate() instance per bind — never share a RippleDrawable across
        // recycled rows (that paints the press ripple on the wrong bounds).
        convertView!!.background = newSolidCardBg(selected)
        ensurePressRipple(convertView)

        val pkg = app.packageName
        convertView.setOnClickListener { v ->
            UiUtils.tapFeedback(v)
            if (multiSelectMode) {
                toggleSelection(pkg)
                return@setOnClickListener
            }
            val current = findByPackage(pkg) ?: return@setOnClickListener
            val next = !current.checked
            current.checked = next
            bindStatus(holder.status!!, next)
            listener?.onToggleAllowlist(pkg, next)
        }

        convertView.setOnLongClickListener {
            if (!multiSelectMode) {
                listener?.onEnterMultiSelect(pkg)
            } else {
                toggleSelection(pkg)
            }
            true
        }
        return convertView
    }

    private fun newSolidCardBg(selected: Boolean): Drawable {
        return ThemeSupport.cardBackground(
            context,
            if (selected) cardSelectedColor else cardColor, CARD_RADIUS_DP
        )
    }

    /**
     * Row press ripple lives on foreground (inflated per item view). Background
     * stays a solid shape so multi-select can swap colors without ripple state bugs.
     */
    private fun ensurePressRipple(row: View?) {
        if (row == null || row.foreground != null) {
            return
        }
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadius = CARD_RADIUS_DP * density
        row.foreground = RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
    }

    private fun findByPackage(packageName: String?): AppEntry? {
        if (packageName == null) {
            return null
        }
        for (e in apps) {
            if (packageName == e.packageName) {
                return e
            }
        }
        return null
    }

    private fun toggleSelection(packageName: String?) {
        if (packageName == null) {
            return
        }
        if (selectedPkgs.contains(packageName)) {
            selectedPkgs.remove(packageName)
        } else {
            selectedPkgs.add(packageName)
        }
        notifyDataSetChanged()
        listener?.onSelectionChanged(selectedPkgs.size)
    }

    private fun clearIconTooltip(view: View?) {
        if (view == null) {
            return
        }
        view.tooltipText = null
        view.isLongClickable = false
        if (view is ImageView) {
            view.contentDescription = null
        }
    }

    private fun bindStatus(status: ImageView, checked: Boolean) {
        status.setImageResource(
            if (checked) R.drawable.ic_status_enabled else R.drawable.ic_status_disabled
        )
        status.setColorFilter(
            if (checked) enabledColor else disabledColor, PorterDuff.Mode.SRC_IN
        )
    }

    /**
     * Shows the MiPush tag beside the app name — and pays for it, because the
     * two pull against each other.
     *
     * The name has to be wrap_content for the tag to sit against it, but a
     * wrap_content child is measured before the view behind it and takes what is
     * left of the row: a long name then claims the whole line and the tag is
     * clipped away, which is worse than a name without a tag. Giving the name a
     * ceiling of "row minus tag" restores the ellipsis it already asks for with
     * `maxLines` and `ellipsize`.
     *
     * The width has to come from the row itself, so a row that has not been
     * laid out yet keeps no ceiling for one pass; every later bind (a scroll, or
     * any `notifyDataSetChanged`) sets it.
     */
    private fun bindMiPushTag(holder: ViewHolder, visible: Boolean) {
        val badge = holder.mipushBadge ?: return
        val label = holder.label ?: return
        badge.visibility = if (visible) View.VISIBLE else View.GONE
        val rowWidth = holder.titleRow?.width ?: 0
        if (!visible || rowWidth <= 0) {
            label.maxWidth = Integer.MAX_VALUE
            return
        }
        if (holder.tagWidth == 0) {
            holder.tagWidth = measureWidth(badge, rowWidth) + startMargin(badge)
        }
        label.maxWidth = Math.max(0, rowWidth - holder.tagWidth)
    }

    private fun loadIcon(app: AppEntry) {
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
     * Every loaded icon is kept on its [AppEntry] for the whole session,
     * and a few hundred of them at full resolution is real memory the list never
     * uses: the row is 44dp, so anything larger is stored at a size that can only
     * ever be drawn scaled down. Shrinking to the row size caps that.
     *
     * Only [BitmapDrawable] sources are touched — a plain downscale,
     * which is pixel-identical to what the view was already drawing. Adaptive
     * icons are left alone on purpose: they carry a safe zone that a flat
     * rescale would break, and they hold no oversized bitmap of their own.
     */
    private fun shrinkToRowSize(source: Drawable): Drawable {
        val size = Math.round(ICON_SIZE_DP * density)
        if (size <= 0) {
            return source
        }
        if (source !is BitmapDrawable) {
            return source
        }
        val bitmap = source.bitmap
            ?: return source
        if (bitmap.width <= size && bitmap.height <= size) {
            return source
        }
        val scaled: Bitmap = try {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        } catch (t: Throwable) {
            // Allocation failed: keep the original rather than show a blank row.
            return source
        }
        return BitmapDrawable(context.resources, scaled)
    }

    /** Coalesce icon-load refreshes so one frame does not spam notifyDataSetChanged. */
    private fun scheduleIconRefresh() {
        if (refreshPosted) {
            return
        }
        refreshPosted = true
        mainHandler.postDelayed({
            refreshPosted = false
            notifyDataSetChanged()
        }, 50L)
    }

    /** Release icon worker threads (call from Activity.onDestroy). */
    fun shutdown() {
        iconLoader.shutdown()
        // Also drops the pending coalesced refresh post.
        mainHandler.removeCallbacksAndMessages(null)
    }

    private class ViewHolder {
        var icon: ImageView? = null
        var label: TextView? = null
        var pkg: TextView? = null
        var status: ImageView? = null
        var mipushBadge: TextView? = null
        /** Name row, whose measured width is what the name gets capped against. */
        var titleRow: View? = null
        /** Cached tag width (its own + its start margin); it never changes here. */
        var tagWidth = 0
    }

    companion object {
        /** Row corner radius in dp; matches bg_card / bg_card_selected. */
        private const val CARD_RADIUS_DP = 24f

        /** Row icon size in dp; matches `@+id/app_icon` in item_app.xml. */
        private const val ICON_SIZE_DP = 44

        private fun measureWidth(view: View, available: Int): Int {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return view.measuredWidth
        }

        private fun startMargin(view: View): Int {
            val lp = view.layoutParams
            return if (lp is ViewGroup.MarginLayoutParams) lp.marginStart else 0
        }
    }
}
