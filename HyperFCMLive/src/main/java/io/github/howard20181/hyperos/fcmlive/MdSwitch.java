package io.github.howard20181.hyperos.fcmlive;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.CompoundButton;

import io.github.howard20181.hyperos.fcmlive.theme.AppPalette;

/**
 * Material 3 switch: a fully rounded capsule whose thumb is a circle that stays
 * evenly inset on all four sides in both the on and off positions.
 *
 * <p>The stock {@link android.widget.Switch} derives its own width from
 * {@code switchMinWidth} and the thumb width, then right-aligns that width
 * inside the view; whenever the view is narrower than that internal width the
 * left part is drawn outside the view and gets clipped. This view owns its
 * geometry instead: {@link #onMeasure} reports exactly the capsule size, so the
 * control is always rendered in full inside its parent.
 *
 * <p>Colours come from the runtime palette through {@link #applyPalette}; the
 * resource fallbacks are only used if the palette is never applied.
 */
public final class MdSwitch extends CompoundButton {

    /**
     * Compact M3 geometry (dp): capsule 48x28, thumb 20, even 4dp inset on all
     * four sides. Roughly 15-20% larger than the previous 40x24 capsule so the
     * control reads at the same weight as the neighbouring rows.
     */
    private static final int TRACK_W_DP = 48;
    private static final int TRACK_H_DP = 28;
    private static final int THUMB_DP = 20;
    private static final int INSET_DP = 4;
    private static final int STROKE_DP = 2;
    private static final int ICON_DP = 12;
    /** How far the hover / press state layer reaches past the thumb. */
    private static final int STATE_LAYER_GROW_DP = 7;

    private static final long ANIM_MS = 220L;
    private static final float DISABLED_ALPHA = 0.38f;
    private static final float HOVER_STATE_ALPHA = 0.08f;
    private static final float PRESSED_STATE_ALPHA = 0.12f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTrack = new RectF();

    private Drawable mIconOn;
    private Drawable mIconOff;

    private int mTrackW;
    private int mTrackH;
    private int mThumb;
    private int mInset;
    private int mStroke;
    private int mIconSize;
    private int mStateGrow;

    private int mTrackOnColor;
    private int mTrackOffColor;
    private int mStrokeColor;
    private int mThumbOnColor;
    private int mThumbOffColor;
    private int mStateOnColor;
    private int mStateOffColor;

    /** 0 = off, 1 = on. Animated so the thumb slides between the two ends. */
    private float mProgress;
    private ValueAnimator mAnimator;

    private float mDownX;
    private boolean mDragging;
    private final int mTouchSlop;

    public MdSwitch(Context context) {
        this(context, null);
    }

    public MdSwitch(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        init(context);
    }

    private void init(Context context) {
        final Resources res = context.getResources();
        final float density = res.getDisplayMetrics().density;
        mTrackW = Math.round(TRACK_W_DP * density);
        mTrackH = Math.round(TRACK_H_DP * density);
        mThumb = Math.round(THUMB_DP * density);
        mInset = Math.round(INSET_DP * density);
        mStroke = Math.round(STROKE_DP * density);
        mIconSize = Math.round(ICON_DP * density);
        mStateGrow = Math.round(STATE_LAYER_GROW_DP * density);

        // No Button style is requested (defStyleAttr 0), but clear everything a
        // button would bring so the capsule is the only thing on screen.
        setBackground(null);
        setMinWidth(0);
        setMinimumWidth(0);
        setMinHeight(0);
        setMinimumHeight(0);
        setIncludeFontPadding(false);
        setClickable(true);
        setFocusable(true);
        setStateListAnimator(null);
        setElevation(0f);

        mTrackOnColor = res.getColor(R.color.md_primary, context.getTheme());
        mTrackOffColor = res.getColor(R.color.md_card, context.getTheme());
        mStrokeColor = res.getColor(R.color.md_outline, context.getTheme());
        mThumbOnColor = res.getColor(R.color.md_surface, context.getTheme());
        mThumbOffColor = res.getColor(R.color.md_outline, context.getTheme());
        mStateOnColor = mTrackOnColor;
        mStateOffColor = res.getColor(R.color.md_on_surface, context.getTheme());

        mIconOn = icon(context, R.drawable.ic_switch_check, mTrackOnColor);
        mIconOff = icon(context, R.drawable.ic_switch_close, mTrackOffColor);

        mProgress = isChecked() ? 1f : 0f;
    }

    private static Drawable icon(Context context, int resId, int tint) {
        Drawable d = context.getResources().getDrawable(resId, context.getTheme());
        if (d == null) {
            return null;
        }
        d = d.mutate();
        d.setTint(tint);
        return d;
    }

    /** Repaint with the current runtime palette (primary / surface roles). */
    public void applyPalette(AppPalette palette) {
        if (palette == null) {
            return;
        }
        mTrackOnColor = palette.primary;
        mTrackOffColor = palette.surfaceVariant;
        mStrokeColor = palette.outline;
        mThumbOnColor = palette.onPrimary;
        mThumbOffColor = palette.outline;
        mStateOnColor = palette.primary;
        mStateOffColor = palette.onSurface;
        if (mIconOn != null) {
            mIconOn.setTint(palette.primary);
        }
        if (mIconOff != null) {
            mIconOff.setTint(palette.surface);
        }
        invalidate();
    }

    /**
     * Assign the checked state <em>without</em> animating the thumb.
     *
     * <p>This is the entry point for every programmatic sync: restoring the
     * value from storage while binding the view, and any change that is
     * immediately followed by a screen rebuild. A slide started on those paths
     * can never complete — the next frame is a relayout or a full Activity
     * rebuild that detaches the view and kills the animator, leaving a
     * half-travelled thumb behind. That aborted slide is exactly what reads on
     * screen as the control "twitching" during a theme change.
     */
    public void setCheckedImmediate(boolean checked) {
        super.setChecked(checked);
        snapTo(checked ? 1f : 0f);
    }

    /** Interactive / state-change path: the thumb slides to the new end. */
    @Override
    public void setChecked(boolean checked) {
        super.setChecked(checked);
        animateTo(checked ? 1f : 0f);
    }

    /** Jump the thumb straight to a position, dropping any in-flight slide. */
    private void snapTo(float target) {
        cancelAnimator();
        mProgress = target;
        invalidate();
    }

    private void animateTo(float target) {
        cancelAnimator();
        if (Math.abs(target - mProgress) < 0.001f) {
            mProgress = target;
            invalidate();
            return;
        }
        // Nothing can be animated before the view is attached and laid out —
        // that is the activity (re)build path, where the state has just been
        // bound and any slide would be aborted on the very next frame.
        if (!isAttachedToWindow() || !isLaidOut()) {
            mProgress = target;
            invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mProgress, target);
        animator.setDuration(ANIM_MS);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        animator.addUpdateListener(animation -> {
            mProgress = (Float) animation.getAnimatedValue();
            invalidate();
        });
        mAnimator = animator;
        animator.start();
    }

    /** Drop the running slide, if any, so it can never repaint a stale thumb. */
    private void cancelAnimator() {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Own the size: never shrink below the capsule, so nothing is clipped.
        setMeasuredDimension(
                mTrackW + getPaddingLeft() + getPaddingRight(),
                mTrackH + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final boolean checked = isChecked();
        final float alpha = isEnabled() ? 1f : DISABLED_ALPHA;
        final float left = getPaddingLeft();
        final float top = getPaddingTop();
        final float radius = mTrackH / 2f;
        final float cx = thumbCenterX();
        final float cy = top + mTrackH / 2f;

        mTrack.set(left, top, left + mTrackW, top + mTrackH);

        // Track: capsule, filled with primary when on, surface variant when off.
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(checked ? mTrackOnColor : mTrackOffColor);
        mPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawRoundRect(mTrack, radius, radius, mPaint);

        // Unchecked outline, drawn inside the capsule edge.
        if (!checked) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(mStroke);
            mPaint.setColor(mStrokeColor);
            mPaint.setAlpha(Math.round(255 * alpha));
            mTrack.inset(mStroke / 2f, mStroke / 2f);
            canvas.drawRoundRect(mTrack, radius - mStroke / 2f, radius - mStroke / 2f, mPaint);
            mTrack.inset(-mStroke / 2f, -mStroke / 2f);
        }

        // Hover / press state layer around the thumb.
        if (isEnabled() && (isPressed() || isHovered())) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(checked ? mStateOnColor : mStateOffColor);
            mPaint.setAlpha(Math.round(255
                    * (isPressed() ? PRESSED_STATE_ALPHA : HOVER_STATE_ALPHA) * alpha));
            canvas.drawCircle(cx, cy, mThumb / 2f + mStateGrow, mPaint);
        }

        // Thumb.
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(checked ? mThumbOnColor : mThumbOffColor);
        mPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawCircle(cx, cy, mThumb / 2f, mPaint);

        Drawable icon = checked ? mIconOn : mIconOff;
        if (icon != null) {
            final int half = mIconSize / 2;
            icon.setAlpha(Math.round(255 * alpha));
            icon.setBounds(Math.round(cx - half), Math.round(cy - half),
                    Math.round(cx + half), Math.round(cy + half));
            icon.draw(canvas);
        }
        mPaint.setAlpha(255);
    }

    /** Thumb centre for the current progress; both ends keep the same inset. */
    private float thumbCenterX() {
        final float travel = mTrackW - mThumb - 2f * mInset;
        return getPaddingLeft() + mInset + mThumb / 2f + travel * mProgress;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDragging = false;
                setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging && Math.abs(event.getX() - mDownX) > mTouchSlop) {
                    mDragging = true;
                    setPressed(false);
                }
                if (mDragging) {
                    setChecked(event.getX() > getWidth() / 2f);
                }
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                if (mDragging) {
                    mDragging = false;
                    return true;
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                setPressed(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimator();
        super.onDetachedFromWindow();
    }
}
