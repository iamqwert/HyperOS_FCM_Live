package io.github.howard20181.hyperos.fcmlive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.CompoundButton
import io.github.howard20181.hyperos.fcmlive.theme.AppPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Material 3 switch: a fully rounded capsule whose thumb is a circle that stays
 * evenly inset on all four sides in both the on and off positions.
 *
 * The stock [android.widget.Switch] derives its own width from
 * `switchMinWidth` and the thumb width, then right-aligns that width
 * inside the view; whenever the view is narrower than that internal width the
 * left part is drawn outside the view and gets clipped. This view owns its
 * geometry instead: [onMeasure] reports exactly the capsule size, so the
 * control is always rendered in full inside its parent.
 *
 * Colours come from the runtime palette through [applyPalette]; the
 * resource fallbacks are only used if the palette is never applied.
 */
class MdSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CompoundButton(context, attrs, 0) {

    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mTrack = RectF()

    private var mIconOn: Drawable? = null
    private var mIconOff: Drawable? = null

    private var mTrackW = 0
    private var mTrackH = 0
    private var mThumb = 0
    private var mInset = 0
    private var mStroke = 0
    private var mIconSize = 0
    private var mStateGrow = 0

    private var mTrackOnColor = 0
    private var mTrackOffColor = 0
    private var mStrokeColor = 0
    private var mThumbOnColor = 0
    private var mThumbOffColor = 0
    private var mStateOnColor = 0
    private var mStateOffColor = 0

    /** 0 = off, 1 = on. Animated so the thumb slides between the two ends. */
    private var mProgress = 0f
    private var mAnimator: ValueAnimator? = null

    private var mDownX = 0f
    private var mDragging = false
    private val mTouchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    init {
        init(context)
    }

    private fun init(context: Context) {
        val res = context.resources
        val density = res.displayMetrics.density
        mTrackW = Math.round(TRACK_W_DP * density)
        mTrackH = Math.round(TRACK_H_DP * density)
        mThumb = Math.round(THUMB_DP * density)
        mInset = Math.round(INSET_DP * density)
        mStroke = Math.round(STROKE_DP * density)
        mIconSize = Math.round(ICON_DP * density)
        mStateGrow = Math.round(STATE_LAYER_GROW_DP * density)

        // No Button style is requested (defStyleAttr 0), but clear everything a
        // button would bring so the capsule is the only thing on screen.
        background = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setIncludeFontPadding(false)
        isClickable = true
        isFocusable = true
        stateListAnimator = null
        elevation = 0f

        mTrackOnColor = res.getColor(R.color.md_primary, context.theme)
        mTrackOffColor = res.getColor(R.color.md_card, context.theme)
        mStrokeColor = res.getColor(R.color.md_outline, context.theme)
        mThumbOnColor = res.getColor(R.color.md_surface, context.theme)
        mThumbOffColor = res.getColor(R.color.md_outline, context.theme)
        mStateOnColor = mTrackOnColor
        mStateOffColor = res.getColor(R.color.md_on_surface, context.theme)

        mIconOn = icon(context, R.drawable.ic_switch_check, mTrackOnColor)
        mIconOff = icon(context, R.drawable.ic_switch_close, mTrackOffColor)

        mProgress = if (isChecked) 1f else 0f
    }

    /** Repaint with the current runtime palette (primary / surface roles). */
    fun applyPalette(palette: AppPalette?) {
        if (palette == null) {
            return
        }
        mTrackOnColor = palette.primary
        mTrackOffColor = palette.surfaceVariant
        mStrokeColor = palette.outline
        mThumbOnColor = palette.onPrimary
        mThumbOffColor = palette.outline
        mStateOnColor = palette.primary
        mStateOffColor = palette.onSurface
        mIconOn?.setTint(palette.primary)
        mIconOff?.setTint(palette.surface)
        invalidate()
    }

    /**
     * Assign the checked state *without* animating the thumb.
     *
     * This is the entry point for every programmatic sync: restoring the
     * value from storage while binding the view, and any change that is
     * immediately followed by a screen rebuild. A slide started on those paths
     * can never complete — the next frame is a relayout or a full Activity
     * rebuild that detaches the view and kills the animator, leaving a
     * half-travelled thumb behind. That aborted slide is exactly what reads on
     * screen as the control "twitching" during a theme change.
     */
    fun setCheckedImmediate(checked: Boolean) {
        super.setChecked(checked)
        snapTo(if (checked) 1f else 0f)
    }

    /** Interactive / state-change path: the thumb slides to the new end. */
    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        animateTo(if (checked) 1f else 0f)
    }

    /** Jump the thumb straight to a position, dropping any in-flight slide. */
    private fun snapTo(target: Float) {
        cancelAnimator()
        mProgress = target
        invalidate()
    }

    private fun animateTo(target: Float) {
        cancelAnimator()
        if (abs(target - mProgress) < 0.001f) {
            mProgress = target
            invalidate()
            return
        }
        // Nothing can be animated before the view is attached and laid out —
        // that is the activity (re)build path, where the state has just been
        // bound and any slide would be aborted on the very next frame.
        if (!isAttachedToWindow || !isLaidOut) {
            mProgress = target
            invalidate()
            return
        }
        val animator = ValueAnimator.ofFloat(mProgress, target)
        animator.duration = ANIM_MS
        animator.interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        animator.addUpdateListener { animation ->
            mProgress = animation.animatedValue as Float
            invalidate()
        }
        mAnimator = animator
        animator.start()
    }

    /** Drop the running slide, if any, so it can never repaint a stale thumb. */
    private fun cancelAnimator() {
        mAnimator?.cancel()
        mAnimator = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Own the size: never shrink below the capsule, so nothing is clipped.
        setMeasuredDimension(
            mTrackW + paddingLeft + paddingRight,
            mTrackH + paddingTop + paddingBottom
        )
    }

    override fun onDraw(canvas: Canvas) {
        val checked = isChecked
        val alpha = if (isEnabled) 1f else DISABLED_ALPHA
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val radius = mTrackH / 2f
        val cx = thumbCenterX()
        val cy = top + mTrackH / 2f

        mTrack.set(left, top, left + mTrackW, top + mTrackH)

        // Track: capsule, filled with primary when on, surface variant when off.
        mPaint.style = Paint.Style.FILL
        mPaint.color = if (checked) mTrackOnColor else mTrackOffColor
        mPaint.alpha = Math.round(255 * alpha)
        canvas.drawRoundRect(mTrack, radius, radius, mPaint)

        // Unchecked outline, drawn inside the capsule edge.
        if (!checked) {
            mPaint.style = Paint.Style.STROKE
            mPaint.strokeWidth = mStroke.toFloat()
            mPaint.color = mStrokeColor
            mPaint.alpha = Math.round(255 * alpha)
            mTrack.inset(mStroke / 2f, mStroke / 2f)
            canvas.drawRoundRect(mTrack, radius - mStroke / 2f, radius - mStroke / 2f, mPaint)
            mTrack.inset(-mStroke / 2f, -mStroke / 2f)
        }

        // Hover / press state layer around the thumb.
        if (isEnabled && (isPressed || isHovered)) {
            mPaint.style = Paint.Style.FILL
            mPaint.color = if (checked) mStateOnColor else mStateOffColor
            mPaint.alpha = Math.round(
                255 * (if (isPressed) PRESSED_STATE_ALPHA else HOVER_STATE_ALPHA) * alpha
            )
            canvas.drawCircle(cx, cy, mThumb / 2f + mStateGrow, mPaint)
        }

        // Thumb.
        mPaint.style = Paint.Style.FILL
        mPaint.color = if (checked) mThumbOnColor else mThumbOffColor
        mPaint.alpha = Math.round(255 * alpha)
        canvas.drawCircle(cx, cy, mThumb / 2f, mPaint)

        val icon = if (checked) mIconOn else mIconOff
        if (icon != null) {
            val half = mIconSize / 2
            icon.alpha = Math.round(255 * alpha)
            icon.setBounds(
                Math.round(cx - half), Math.round(cy - half),
                Math.round(cx + half), Math.round(cy + half)
            )
            icon.draw(canvas)
        }
        mPaint.alpha = 255
    }

    /** Thumb centre for the current progress; both ends keep the same inset. */
    private fun thumbCenterX(): Float {
        val travel = mTrackW - mThumb - 2f * mInset
        return paddingLeft + mInset + mThumb / 2f + travel * mProgress
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mDownX = event.x
                mDragging = false
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!mDragging && abs(event.x - mDownX) > mTouchSlop) {
                    mDragging = true
                    isPressed = false
                }
                if (mDragging) {
                    setChecked(event.x > width / 2f)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (mDragging) {
                    mDragging = false
                    return true
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mDragging = false
                isPressed = false
                return true
            }
            else -> return super.onTouchEvent(event)
        }
    }

    override fun onDetachedFromWindow() {
        cancelAnimator()
        super.onDetachedFromWindow()
    }

    companion object {
        /**
         * Compact M3 geometry (dp): capsule 48x28, thumb 20, even 4dp inset on all
         * four sides. Roughly 15-20% larger than the previous 40x24 capsule so the
         * control reads at the same weight as the neighbouring rows.
         */
        private const val TRACK_W_DP = 48
        private const val TRACK_H_DP = 28
        private const val THUMB_DP = 20
        private const val INSET_DP = 4
        private const val STROKE_DP = 2
        private const val ICON_DP = 12
        /** How far the hover / press state layer reaches past the thumb. */
        private const val STATE_LAYER_GROW_DP = 7

        private const val ANIM_MS = 220L
        private const val DISABLED_ALPHA = 0.38f
        private const val HOVER_STATE_ALPHA = 0.08f
        private const val PRESSED_STATE_ALPHA = 0.12f

        private fun icon(context: Context, resId: Int, tint: Int): Drawable? {
            val d = context.resources.getDrawable(resId, context.theme)?.mutate() ?: return null
            d.setTint(tint)
            return d
        }
    }
}
