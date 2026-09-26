package io.github.howard20181.hyperos.fcmlive.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import io.github.howard20181.hyperos.fcmlive.R

/**
 * Applies the runtime [AppPalette] to views as they are inflated.
 *
 * Android resolves `@color/` and `@drawable/` references inside
 * XML natively, so a color resource cannot be swapped at runtime. Instead we
 * read the raw resource ids from the [AttributeSet] — before resolution —
 * and repaint the freshly created view. Inflation itself is untouched: any tag
 * we cannot build simply falls through to the platform's own path.
 */
internal class ThemeFactory(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val inflater: LayoutInflater,
    private val palette: AppPalette
) : LayoutInflater.Factory2 {

    private val colorRoles: MutableMap<Int, Int> = HashMap()
    private val drawableRoles: MutableMap<Int, Int> = HashMap()

    init {
        buildRoleMaps()
    }

    private fun buildRoleMaps() {
        val p = palette
        colorRoles[R.color.md_primary] = p.primary
        colorRoles[R.color.md_primary_container] = p.primaryContainer
        colorRoles[R.color.md_surface] = p.surface
        colorRoles[R.color.md_page_bg] = p.pageBg
        colorRoles[R.color.md_on_surface] = p.onSurface
        colorRoles[R.color.md_on_surface_variant] = p.onSurfaceVariant
        colorRoles[R.color.md_hint_light] = p.hint
        colorRoles[R.color.md_outline] = p.outline
        colorRoles[R.color.md_card] = p.card
        colorRoles[R.color.md_icon_tint] = p.iconTint
        colorRoles[R.color.md_tooltip_bg] = p.tooltipBg
        colorRoles[R.color.md_tooltip_text] = p.tooltipText
        colorRoles[R.color.md_popup_menu_bg] = p.popupBg
        colorRoles[R.color.md_popup_item_ripple] = p.ripple

        putDrawable(R.drawable.bg_card, p.card)
        putDrawable(R.drawable.bg_card_group, p.card)
        putDrawable(R.drawable.bg_card_ripple, p.card)
        putDrawable(R.drawable.bg_card_single_ripple, p.card)
        putDrawable(R.drawable.bg_card_group_top_ripple, p.card)
        putDrawable(R.drawable.bg_card_group_middle_ripple, p.card)
        putDrawable(R.drawable.bg_card_group_bottom_ripple, p.card)
        putDrawable(R.drawable.bg_card_selected, p.primaryContainer)
        putDrawable(R.drawable.bg_fab, p.primaryContainer)
        putDrawable(R.drawable.bg_popup_menu, p.popupBg)
        putDrawable(R.drawable.bg_tooltip, p.tooltipBg)
        putDrawable(R.drawable.md3_check_on, p.primary)
    }

    private fun putDrawable(resId: Int, color: Int) {
        drawableRoles[resId] = color
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        return onCreateView(null, name, context, attrs)
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        var view: View? = null
        for (prefix in PREFIXES) {
            try {
                view = inflater.createView(name, prefix, attrs)
            } catch (ignored: Throwable) {
                view = null
            }
            if (view != null) {
                break
            }
        }
        if (view == null) {
            // Unknown / framework-private tag: let the platform build it.
            return null
        }
        try {
            bind(view, attrs)
        } catch (ignored: Throwable) {
            // Theming is best effort; a broken binding must never break inflation.
        }
        return view
    }

    private fun bind(view: View, attrs: AttributeSet) {
        val background = attrs.getAttributeResourceValue(NS, "background", 0)
        if (background != 0) {
            val solid = colorRoles[background]
            if (solid != null) {
                view.setBackgroundColor(solid)
            } else {
                val tint = drawableRoles[background]
                if (tint != null) {
                    recolor(view.background, tint)
                }
            }
        }

        val backgroundTint = attrs.getAttributeResourceValue(NS, "backgroundTint", 0)
        if (backgroundTint != 0) {
            val color = colorRoles[backgroundTint]
            if (color != null) {
                view.backgroundTintList = ColorStateList.valueOf(color)
            }
        }

        if (view is TextView) {
            val textColor = colorRoles[attrs.getAttributeResourceValue(NS, "textColor", 0)]
            if (textColor != null) {
                view.setTextColor(textColor)
            }
            val hintColor = colorRoles[attrs.getAttributeResourceValue(NS, "textColorHint", 0)]
            if (hintColor != null) {
                view.setHintTextColor(hintColor)
            }
        }

        if (view is ImageView) {
            val tint = colorRoles[attrs.getAttributeResourceValue(NS, "tint", 0)]
            if (tint != null) {
                view.imageTintList = ColorStateList.valueOf(tint)
            }
            val src = drawableRoles[attrs.getAttributeResourceValue(NS, "src", 0)]
            if (src != null) {
                recolor(view.drawable, src)
            }
        }
    }

    /** Repaint every solid shape inside a drawable without touching its geometry. */
    private fun recolor(drawable: Drawable?, color: Int) {
        if (drawable == null) {
            return
        }
        drawable.mutate()
        if (drawable is GradientDrawable) {
            drawable.setColor(color)
            return
        }
        if (drawable is ColorDrawable) {
            drawable.color = color
            return
        }
        if (drawable is LayerDrawable) {
            for (i in 0 until drawable.numberOfLayers) {
                recolor(drawable.getDrawable(i), color)
            }
        }
    }

    companion object {
        private const val NS = "http://schemas.android.com/apk/res/android"
        private val PREFIXES = arrayOf(
            "android.widget.", "android.view.", "android.webkit.", "android.app.",
        )

        /** Solid rounded rectangle, used for card backgrounds built in code. */
        @JvmStatic
        fun roundRect(context: Context, color: Int, radiusDp: Float): Drawable {
            val shape = GradientDrawable()
            shape.setColor(color)
            shape.cornerRadius = radiusDp * context.resources.displayMetrics.density
            return shape
        }
    }
}
