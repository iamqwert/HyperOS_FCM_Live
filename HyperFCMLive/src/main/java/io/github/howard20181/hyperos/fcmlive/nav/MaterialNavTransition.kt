package io.github.howard20181.hyperos.fcmlive.nav

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/**
 * The Material look's page transition.
 *
 * Under the Miuix look the pages run on the library's own `NavTransitions.MiuixDefault`
 * — the slide, the parallax and the settle that library ships, which is what its
 * reference app does. Material keeps the look it had before the stack existed:
 * the two full-bleed pages of the platform's own cross-activity animation, the
 * page on top sliding in from the trailing edge as a rounded rectangle over the
 * page it covers.
 *
 * That geometry is the one this module used to hand-roll for the M3 case of its
 * predictive-back container, restated here as a pure function of the depth axis
 * so the same numbers drive the gesture, the commit and the push alike:
 *
 * - the entering / leaving page slides a full width, linear in depth, so a
 *   finger-driven depth follows the finger 1:1 (deliberately NOT rounded to
 *   whole pixels here, unlike the Miuix slide: M3 ran on the raw float);
 * - it is clipped with a plain rectangle — [CARD_CORNER_FRACTION] of the host
 *   width — only while it is off rest, which is also the whole reason M3 does
 *   not take the library's `enableCornerClip` effect: this mask is not the
 *   device's screen corner and not a squircle;
 * - the page underneath parallaxes a quarter width toward the leading edge with
 *   a light alpha falloff. That treatment is `MiuixDefault`'s covered branch —
 *   it is what the old M3 back preview showed, so it stays.
 *
 * The dark wash over the covered page is not here: it is the orthogonal
 * `NavDisplayEffects` dim, capped at 0.5, drawn as pure black, with the curve
 * along the motion left to this transition ([NavTransition.scrimFraction], whose
 * depth-linear default is the curve M3 had) — the same treatment, and therefore
 * the same setting, that both looks pass to `NavDisplay`.
 *
 * Everything the *timing* does comes from the theme's own motion scheme (see
 * [materialMotion]) rather than from a number written here. Built per
 * composition because of that: the scheme is a theme value, and it has to be the
 * live one.
 */
@Composable
internal fun rememberMaterialNavTransition(): NavTransition {
    val motionScheme = MaterialTheme.motionScheme
    return remember(motionScheme) {
        navGraphicsTransition(opaqueDepth = 1f, motion = materialMotion(motionScheme)) { scope ->
            val width = scope.layoutSize.width.toFloat()
            val depth = scope.relativeDepth
            val rtl = scope.layoutDirection == LayoutDirection.Rtl
            if (depth <= 0f) {
                // Crossing the front edge: either arriving from the trailing edge
                // or leaving toward it, which is the same motion read in opposite
                // directions.
                val offset = (-depth).coerceIn(0f, 1f) * width
                translationX = if (rtl) -offset else offset
                if (offset > 0f) {
                    clip = true
                    // Rounded from a pixel width through the host's own density, so
                    // the radius is the same fraction of the screen on every device.
                    shape =
                        RoundedCornerShape(with(scope.density) { (width * CARD_CORNER_FRACTION).toDp() })
                }
            } else {
                // Covered: a quarter width toward the leading edge, fading a tenth
                // while fully buried and sliding home as it is revealed.
                val cover = depth.coerceIn(0f, 1f)
                translationX = (if (rtl) 1f else -1f) * cover * width * PARALLAX_FRACTION
                alpha = 1f - COVER_ALPHA_FALLOFF * cover
            }
        }
    }
}

/**
 * The M3 settle, in every phase the driver can be released into: the theme's own
 * motion scheme, carried over as-is.
 *
 * The scheme is the right token source here, and the reason is on the M3 side
 * rather than this module's: a page slide changes the layer's *bounds*, which is
 * exactly the case the scheme's spatial specs are for, and M3's expressive
 * update moved spatial motion onto the physics system these specs are built
 * from. The theme already carries both — `MotionScheme.standard()` for the 2021
 * colour spec and `MotionScheme.expressive()` for the 2025 one (see
 * `theme/ComposeTokens.kt`) — so the two looks of this app move the way their
 * own generation moves, instead of one hand-picked duration serving both.
 *
 * The scheme's specs are springs, and a spring is what a *release* wants: a
 * gesture ends with the depth already moving, and only a spring can be seeded
 * with that velocity — a tween restarts from zero, which is a visible stop at
 * the exact moment the finger lets go (the runtime says as much on
 * [NavSettleSpec.Tween]).
 *
 * All three phases take the default spatial spec, one feel for a whole step:
 * - commit and cancel are the same release read twice, so they cannot disagree;
 * - programmatic push/pop is a full step from rest, which the scheme's default
 *   settles in a few hundred milliseconds — the order of M3's own row for a
 *   transition that begins and ends on screen (300 ms standard, 500 ms
 *   emphasized), rather than the exit-only figure this used to run both
 *   directions on — and, under the expressive scheme, with its overshoot.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun materialMotion(scheme: MotionScheme): NavMotion {
    val spatial = scheme.defaultSpatialSpec<Float>().asDepthSettle()
    return NavMotion(commit = spatial, cancel = spatial, programmatic = spatial)
}

/**
 * Carries a theme spec across to the depth axis, tokens intact.
 *
 * The damping ratio is what decides whether the bounce has to be acknowledged:
 * an expressive spatial spec is underdamped on purpose, and the runtime refuses
 * that unless the caller says so — the undershoot past rest *is* the M3
 * Expressive feel, not an accident to be clamped away.
 *
 * Anything that is not a spring (no scheme ships one today) falls back to the
 * library's own navigation feel — critically damped, about half a second for a
 * full step — because a spec the runtime cannot seed or measure would otherwise
 * have to be guessed at from here.
 */
private fun FiniteAnimationSpec<Float>.asDepthSettle(): NavSettleSpec = when (this) {
    is SpringSpec -> NavSettleSpec.Spring(
        dampingRatio = dampingRatio,
        stiffness = stiffness,
        clampOvershoot = dampingRatio >= 1f,
    )
    else -> NavSettleSpec.Spring()
}

/**
 * Corner radius of the sliding page, as a fraction of the host width — the
 * platform's cross-activity card corner, expressed as a fraction because the
 * radius it stands in for is one.
 */
private const val CARD_CORNER_FRACTION = 0.1433f

/**
 * How far the covered page sits from its resting place while buried, as a
 * fraction of the host width — `MiuixDefault`'s covered parallax.
 */
private const val PARALLAX_FRACTION = 0.25f

/** Alpha the covered page loses while fully buried — `MiuixDefault`'s falloff. */
private const val COVER_ALPHA_FALLOFF = 0.1f
