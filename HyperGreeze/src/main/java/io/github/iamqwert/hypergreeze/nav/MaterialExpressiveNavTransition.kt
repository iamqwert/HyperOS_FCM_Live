package io.github.iamqwert.hypergreeze.nav

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import io.github.iamqwert.hypergreeze.theme.LocalIsExpressive
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSettle
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navDirectionalTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Material 3 Expressive in-app navigation & predictive back transition.
 *
 * Follows Google's Material 3 Expressive motion & Predictive Back specifications:
 * - **Motion physics (`MaterialTheme.motionScheme`)**: all settle phases (programmatic push/pop,
 *   predictive-back commit, and gesture cancel) are pure spring-driven (`NavSettleSpec.Spring`)
 *   rather than fixed-duration tweens, so releases carry finger velocity continuously and
 *   mid-flight interruptions never stall. Expressive (2025/2026) vs Standard (2021) schemes
 *   automatically adapt stiffness and Expressive commit bounce.
 * - **Programmatic push / pop**: Material 3 Shared Axis X (RTL-mirrored 48dp drift with fast
 *   effects alpha crossfade, no scrim).
 * - **Predictive back / interactive edge swipe**:
 *   - Pre-commit: scales the outgoing surface down to 0.90x with `BackGestureEasing`, shifts
 *     symmetrically away from the swiped edge to preserve an 8dp display margin, tracks vertical
 *     finger movement with damped clamping, and scales the revealed surface in sync behind it.
 *   - Commit: velocity-seeded spatial spring settle; under Expressive (`LocalIsExpressive`),
 *     adds a subtle theme-derived spring bounce overlay on the revealed surface's scale.
 *   - Cancel: crisp fast-spatial spring snap-back to rest.
 *
 * Pair with `NavDisplayEffects(cornerClipMode = NavCornerClipMode.All, cornerClipRadius = ...)`
 * so all four corners round smoothly during the predictive-back card preview.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberMaterialExpressiveNavTransition(): NavTransition {
    val defaultSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fastSpatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val isExpressive = LocalIsExpressive.current
    return remember(defaultSpatial, fastSpatial, isExpressive) {
        val defaultSpring = defaultSpatial as? SpringSpec<Float>
        val fastSpring = fastSpatial as? SpringSpec<Float>
        val spatialStiffness = defaultSpring?.stiffness ?: Spring.StiffnessMediumLow
        val cancelStiffness = fastSpring?.stiffness ?: Spring.StiffnessMedium
        val bounceDamping = if (isExpressive) {
            (defaultSpring?.dampingRatio ?: 0.8f).coerceIn(0.6f, 0.95f)
        } else {
            1f
        }
        materialExpressiveNavTransition(
            spatialStiffness = spatialStiffness,
            cancelStiffness = cancelStiffness,
            bounceDamping = bounceDamping,
            bounceStiffness = spatialStiffness,
        )
    }
}

private fun materialExpressiveNavTransition(
    spatialStiffness: Float,
    cancelStiffness: Float,
    bounceDamping: Float,
    bounceStiffness: Float,
): NavTransition {
    // Critically damped on the depth axis so animatedTop never crosses d=0 or d=-1
    // regime boundaries, while keeping velocity-continuous spring pacing from motionScheme.
    val spatialSettle = NavSettleSpec.Spring(
        dampingRatio = 1f,
        stiffness = spatialStiffness,
        clampOvershoot = true,
    )
    val cancelSettle = NavSettleSpec.Spring(
        dampingRatio = 1f,
        stiffness = cancelStiffness,
        clampOvershoot = true,
    )
    val programmaticMotion = NavMotion(
        commit = spatialSettle,
        cancel = cancelSettle,
        programmatic = spatialSettle,
    )
    val predictiveMotion = NavMotion(
        commit = spatialSettle,
        cancel = cancelSettle,
        programmatic = spatialSettle,
    )

    val sharedAxisPush: NavTransition = navGraphicsTransition(
        opaqueDepth = 1f,
        motion = programmaticMotion,
        scrim = { 0f },
    ) { scope ->
        val d = scope.relativeDepth
        val driftPx = with(scope.density) { M3SharedAxisDrift.toPx() }
        val dir = if (scope.layoutDirection == LayoutDirection.Rtl) -1f else 1f
        if (d <= 0f) {
            val p = topProgress(d)
            translationX = (dir * (1f - p) * driftPx).fastRoundToInt().toFloat()
            alpha = if (scope.role == NavRole.Incoming) {
                ((p - PUSH_FADE_IN_START) / PUSH_FADE_IN_SPAN).coerceIn(0f, 1f)
            } else {
                1f
            }
        } else {
            translationX = (-dir * coverProgress(d) * driftPx).fastRoundToInt().toFloat()
            alpha = 1f
        }
    }

    val sharedAxisPop: NavTransition = navGraphicsTransition(
        opaqueDepth = 1f,
        motion = programmaticMotion,
        scrim = { 0f },
    ) { scope ->
        val d = scope.relativeDepth
        val driftPx = with(scope.density) { M3SharedAxisDrift.toPx() }
        val dir = if (scope.layoutDirection == LayoutDirection.Rtl) -1f else 1f
        if (d <= 0f) {
            val p = topProgress(d)
            translationX = (dir * (1f - p) * driftPx).fastRoundToInt().toFloat()
            alpha = if (scope.role == NavRole.Outgoing) {
                ((p - POP_FADE_OUT_END) / POP_FADE_OUT_SPAN).coerceIn(0f, 1f)
            } else {
                1f
            }
        } else {
            translationX = (-dir * coverProgress(d) * driftPx).fastRoundToInt().toFloat()
            alpha = 1f
        }
    }

    val predictivePop: NavTransition = navGraphicsTransition(
        opaqueDepth = 1f,
        motion = predictiveMotion,
        scrim = { scope ->
            val s = scope.settle
            val g = scope.gesture
            when {
                // Spring-driven commit: fade scrim smoothly along the spring's depth progress.
                s?.phase == NavSettlePhase.Commit -> scope.relativeDepth.coerceIn(0f, 1f)
                // Finger driving / cancel: hold scrim at full strength while peeking.
                g != null -> (scope.relativeDepth.coerceIn(0f, 1f) / (1f - g.progress).coerceAtLeast(MIN_RELEASE_SPAN)).coerceIn(0f, 1f)
                else -> scope.relativeDepth.coerceIn(0f, 1f)
            }
        },
    ) { scope ->
        val d = scope.relativeDepth
        val gesture = scope.gesture
        val settle = scope.settle
        val committing = settle?.phase == NavSettlePhase.Commit
        val widthPx = scope.layoutSize.width.toFloat()
        val heightPx = scope.layoutSize.height.toFloat()
        val driftPx = with(scope.density) { M3PredictiveDrift.toPx() }
        val edgeSign = predictiveEdgeSign(gesture, scope.layoutDirection)
        val bounce = expressiveBounceScale(
            settle = settle,
            gesture = gesture,
            dampingRatio = bounceDamping,
            stiffness = bounceStiffness,
        )
        val hugMax = (
            widthPx * (1f - PREDICTIVE_MIN_SCALE) / 2f -
                with(scope.density) { PREDICTIVE_EDGE_MARGIN.toPx() }
            ).coerceAtLeast(0f)

        if (d <= 0f) {
            val p = topProgress(d)
            if (scope.role == NavRole.Outgoing && committing && gesture != null) {
                val releaseP = (1f - gesture.progress).coerceAtLeast(MIN_RELEASE_SPAN)
                val post = (1f - p / releaseP).coerceIn(0f, 1f)
                val releasePE = shapedTopProgress(releaseP, gesture)
                val committedScale = PREDICTIVE_MIN_SCALE + (1f - PREDICTIVE_MIN_SCALE) * releasePE
                // Outgoing card drifts out in the swipe direction while fading rapidly so
                // the revealed destination takes focus immediately.
                val commitScale = committedScale + (1f - committedScale) * post * 0.35f
                scaleX = snapScaleToPixelWidth(commitScale, widthPx)
                scaleY = scaleX
                val tx = edgeSign * ((1f - releasePE) * hugMax + post * driftPx)
                alpha = (1f - post / COMMIT_CARD_FADE_SPAN).coerceIn(0f, 1f)
                translationX = snapEdgeTranslation(tx, scaleX, widthPx)
                translationY = snapEdgeTranslation(
                    predictiveYShift(gesture, heightPx, scaleX, scope.density),
                    scaleX,
                    heightPx,
                )
            } else {
                val pE = shapedTopProgress(p, gesture)
                scaleX = snapScaleToPixelWidth(
                    PREDICTIVE_MIN_SCALE + (1f - PREDICTIVE_MIN_SCALE) * pE,
                    widthPx,
                )
                scaleY = scaleX
                translationX = snapEdgeTranslation(edgeSign * (1f - pE) * hugMax, scaleX, widthPx)
                alpha = when {
                    scope.role == NavRole.Outgoing && gesture != null -> {
                        val releaseP = (1f - gesture.progress).coerceAtLeast(MIN_RELEASE_SPAN)
                        (1f - (1f - p / releaseP).coerceIn(0f, 1f) / COMMIT_CARD_FADE_SPAN).coerceIn(0f, 1f)
                    }
                    gesture != null -> 1f
                    else -> (p / 0.25f).coerceIn(0f, 1f)
                }
                translationY = snapEdgeTranslation(
                    predictiveYShift(gesture, heightPx, scaleX, scope.density),
                    scaleX,
                    heightPx,
                )
            }
        } else {
            val dc = coverProgress(d)
            val post = if (gesture != null) {
                val releaseProgress = gesture.progress
                if (releaseProgress >= 1f) 1f else (((1f - dc) - releaseProgress) / (1f - releaseProgress)).coerceIn(0f, 1f)
            } else {
                1f - dc
            }
            if (gesture != null) {
                val travel = if (committing) gesture.progress else (1f - dc)
                val eased = BackGestureEasing.transform(travel.coerceIn(0f, 1f))
                val liveScale = PREDICTIVE_MIN_SCALE + (1f - PREDICTIVE_MIN_SCALE) * (1f - eased)
                scaleX = snapScaleToPixelWidth((liveScale + (1f - liveScale) * post) * bounce, widthPx)
                scaleY = scaleX
            }
            translationX = snapEdgeTranslation(-edgeSign * (1f - post) * driftPx, scaleX, widthPx)
            translationY = snapEdgeTranslation(
                predictiveYShift(gesture, heightPx, scaleX, scope.density),
                scaleX,
                heightPx,
            )
        }
    }

    return navDirectionalTransition(
        push = sharedAxisPush,
        pop = sharedAxisPop,
        predictivePop = predictivePop,
    )
}

/**
 * Symmetric direction sign for predictive-back card shift:
 * - Left-edge swipe pushes card right (`+1f`, hugging right edge with 8dp margin).
 * - Right-edge swipe pushes card left (`-1f`, hugging left edge with 8dp margin).
 * - In-app swipe-back (no system edge) follows layout direction.
 */
private fun predictiveEdgeSign(gesture: NavGesture?, layoutDirection: LayoutDirection): Float = when (gesture?.swipeEdge) {
    NavSwipeEdge.Left -> 1f
    NavSwipeEdge.Right -> -1f
    else -> if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
}

/**
 * Subtle Expressive spring-bounce overlay on the revealed surface's scale during a commit
 * settle, parameterized directly from `MaterialTheme.motionScheme.defaultSpatialSpec()`.
 * Inactive (`1f`) when `dampingRatio >= 1f` (Standard / 2021 scheme).
 */
private fun expressiveBounceScale(
    settle: NavSettle?,
    gesture: NavGesture?,
    dampingRatio: Float,
    stiffness: Float,
): Float {
    if (dampingRatio >= 1f || settle == null || settle.phase != NavSettlePhase.Commit || gesture == null) {
        return 1f
    }
    val factor = if (gesture.swipeEdge != NavSwipeEdge.None) 1.5f else 1f
    val floorKick = if (gesture.progress < 0.12f) BOUNCE_MIN_KICK else 0f
    val kick = (abs(settle.releaseVelocity) * 100f * (1f - PREDICTIVE_MIN_SCALE) * factor)
        .coerceIn(floorKick, BOUNCE_MAX_KICK)
    if (kick <= 0f) return 1f
    val omega = sqrt(stiffness)
    val omegaD = omega * sqrt(1f - dampingRatio * dampingRatio)
    val t = settle.elapsedMillis / 1000f
    val overlay = -(kick / omegaD) * exp(-dampingRatio * omega * t) * sin(omegaD * t)
    return ((100f + overlay) / 100f).coerceAtMost(1f)
}

private fun shapedTopProgress(p: Float, gesture: NavGesture?): Float =
    if (gesture == null) p else 1f - BackGestureEasing.transform((1f - p).coerceIn(0f, 1f))

private fun predictiveYShift(
    gesture: NavGesture?,
    height: Float,
    scale: Float,
    density: Density,
): Float {
    if (gesture == null || height <= 0f) return 0f
    val rawDelta = gesture.touchY - gesture.initialTouchY
    val half = height / 2f
    val ratio = min(half, abs(rawDelta)) / half
    val damped = 1f - (1f - ratio) * (1f - ratio)
    val marginPx = with(density) { PREDICTIVE_EDGE_MARGIN.toPx() }
    val maxShift = ((height - height * scale) / 2f - marginPx).coerceAtLeast(0f)
    return maxShift * damped * (if (rawDelta < 0f) -1f else 1f)
}

private fun snapScaleToPixelWidth(scale: Float, width: Float): Float =
    if (width <= 0f) scale else (scale * width).fastRoundToInt() / width

private fun snapEdgeTranslation(translation: Float, scale: Float, extent: Float): Float {
    val inset = extent * (1f - scale) / 2f
    return (translation + inset).fastRoundToInt() - inset
}

private fun topProgress(d: Float): Float = (1f + d).coerceIn(0f, 1f)

private fun coverProgress(d: Float): Float = d.coerceIn(0f, 1f)

/** Official Material 3 predictive back pre-commit gesture curve. */
private val BackGestureEasing: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/** Official Material 3 predictive back minimum scale (90%). */
private const val PREDICTIVE_MIN_SCALE = 0.9f

/** Official Material 3 predictive back display-bounds margin (8dp). */
private val PREDICTIVE_EDGE_MARGIN = 8.dp

/** Material 3 Shared Axis X horizontal shift distance. */
private val M3SharedAxisDrift = 48.dp

/** Revealed layer start offset & post-commit fly-out drift for predictive back. */
private val M3PredictiveDrift = 48.dp

private const val PUSH_FADE_IN_START = 0.08f
private const val PUSH_FADE_IN_SPAN = 0.47f
private const val POP_FADE_OUT_END = 0.55f
private const val POP_FADE_OUT_SPAN = 0.45f
private const val COMMIT_CARD_FADE_SPAN = 0.35f

private const val BOUNCE_MIN_KICK = 90f
private const val BOUNCE_MAX_KICK = 600f
private const val MIN_RELEASE_SPAN = 0.001f
