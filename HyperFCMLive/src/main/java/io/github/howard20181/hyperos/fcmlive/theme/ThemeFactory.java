package io.github.howard20181.hyperos.fcmlive.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

import io.github.howard20181.hyperos.fcmlive.R;

/**
 * Applies the runtime {@link AppPalette} to views as they are inflated.
 *
 * <p>Android resolves {@code @color/} and {@code @drawable/} references inside
 * XML natively, so a color resource cannot be swapped at runtime. Instead we
 * read the raw resource ids from the {@link AttributeSet} — before resolution —
 * and repaint the freshly created view. Inflation itself is untouched: any tag
 * we cannot build simply falls through to the platform's own path.
 */
final class ThemeFactory implements LayoutInflater.Factory2 {

    private static final String NS = "http://schemas.android.com/apk/res/android";
    private static final String[] PREFIXES = {
            "android.widget.", "android.view.", "android.webkit.", "android.app.",
    };

    private final LayoutInflater mInflater;
    private final AppPalette mPalette;
    private final float mDensity;
    private final Map<Integer, Integer> mColorRoles = new HashMap<>();
    private final Map<Integer, Integer> mDrawableRoles = new HashMap<>();

    ThemeFactory(Context context, LayoutInflater inflater, AppPalette palette) {
        mInflater = inflater;
        mPalette = palette;
        mDensity = context.getResources().getDisplayMetrics().density;
        buildRoleMaps();
    }

    private void buildRoleMaps() {
        AppPalette p = mPalette;
        mColorRoles.put(R.color.md_primary, p.primary);
        mColorRoles.put(R.color.md_primary_container, p.primaryContainer);
        mColorRoles.put(R.color.md_surface, p.surface);
        mColorRoles.put(R.color.md_page_bg, p.pageBg);
        mColorRoles.put(R.color.md_on_surface, p.onSurface);
        mColorRoles.put(R.color.md_on_surface_variant, p.onSurfaceVariant);
        mColorRoles.put(R.color.md_hint_light, p.hint);
        mColorRoles.put(R.color.md_outline, p.outline);
        mColorRoles.put(R.color.md_card, p.card);
        mColorRoles.put(R.color.md_icon_tint, p.iconTint);
        mColorRoles.put(R.color.md_tooltip_bg, p.tooltipBg);
        mColorRoles.put(R.color.md_tooltip_text, p.tooltipText);
        mColorRoles.put(R.color.md_popup_menu_bg, p.popupBg);
        mColorRoles.put(R.color.md_popup_item_ripple, p.ripple);

        putDrawable(mDrawableRoles, R.drawable.bg_card, p.card);
        putDrawable(mDrawableRoles, R.drawable.bg_card_group, p.card);
        putDrawable(mDrawableRoles, R.drawable.bg_card_ripple, p.card);
        putDrawable(mDrawableRoles, R.drawable.bg_card_single_ripple, p.card);
        putDrawable(mDrawableRoles, R.drawable.bg_card_group_top_ripple, p.card);
        putDrawable(mDrawableRoles, R.drawable.bg_card_group_middle_ripple, p.card);
        putDrawable(mDrawableRoles, R.drawable.bg_card_group_bottom_ripple, p.card);
        putDrawable(mDrawableRoles, R.drawable.bg_card_selected, p.primaryContainer);
        putDrawable(mDrawableRoles, R.drawable.bg_fab, p.primaryContainer);
        putDrawable(mDrawableRoles, R.drawable.bg_popup_menu, p.popupBg);
        putDrawable(mDrawableRoles, R.drawable.bg_tooltip, p.tooltipBg);
        putDrawable(mDrawableRoles, R.drawable.md3_check_on, p.primary);
    }

    private static void putDrawable(Map<Integer, Integer> map, int resId, int color) {
        map.put(resId, color);
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        return onCreateView(null, name, context, attrs);
    }

    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        View view = null;
        for (String prefix : PREFIXES) {
            try {
                view = mInflater.createView(name, prefix, attrs);
            } catch (Throwable ignored) {
                view = null;
            }
            if (view != null) {
                break;
            }
        }
        if (view == null) {
            // Unknown / framework-private tag: let the platform build it.
            return null;
        }
        try {
            bind(view, attrs);
        } catch (Throwable ignored) {
            // Theming is best effort; a broken binding must never break inflation.
        }
        return view;
    }

    private void bind(View view, AttributeSet attrs) {
        int background = attrs.getAttributeResourceValue(NS, "background", 0);
        if (background != 0) {
            Integer solid = mColorRoles.get(background);
            if (solid != null) {
                view.setBackgroundColor(solid);
            } else {
                Integer tint = mDrawableRoles.get(background);
                if (tint != null) {
                    recolor(view.getBackground(), tint);
                }
            }
        }

        int backgroundTint = attrs.getAttributeResourceValue(NS, "backgroundTint", 0);
        if (backgroundTint != 0) {
            Integer color = mColorRoles.get(backgroundTint);
            if (color != null) {
                view.setBackgroundTintList(ColorStateList.valueOf(color));
            }
        }

        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Integer textColor = mColorRoles.get(attrs.getAttributeResourceValue(NS, "textColor", 0));
            if (textColor != null) {
                text.setTextColor(textColor);
            }
            Integer hintColor =
                    mColorRoles.get(attrs.getAttributeResourceValue(NS, "textColorHint", 0));
            if (hintColor != null) {
                text.setHintTextColor(hintColor);
            }
        }

        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            Integer tint = mColorRoles.get(attrs.getAttributeResourceValue(NS, "tint", 0));
            if (tint != null) {
                image.setImageTintList(ColorStateList.valueOf(tint));
            }
            Integer src = mDrawableRoles.get(attrs.getAttributeResourceValue(NS, "src", 0));
            if (src != null) {
                recolor(image.getDrawable(), src);
            }
        }
    }

    /** Repaint every solid shape inside a drawable without touching its geometry. */
    private void recolor(Drawable drawable, int color) {
        if (drawable == null) {
            return;
        }
        drawable.mutate();
        if (drawable instanceof GradientDrawable) {
            ((GradientDrawable) drawable).setColor(color);
            return;
        }
        if (drawable instanceof ColorDrawable) {
            ((ColorDrawable) drawable).setColor(color);
            return;
        }
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                recolor(layers.getDrawable(i), color);
            }
        }
    }

    /** Solid rounded rectangle, used for card backgrounds built in code. */
    static Drawable roundRect(Context context, int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radiusDp * context.getResources().getDisplayMetrics().density);
        return shape;
    }
}
