package io.github.howard20181.hyperos.fcmlive.nav

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/**
 * The M3 look's settle, in each phase the driver can be released into.
 *
 * Declared above the transition that reads it: top-level properties initialize in
 * file order.
 *
 * ### Programmatic — [PROGRAMMATIC_MS] = M3's own figure
 *
 * A push or a pop that starts from rest is a transition between two screens
 * *both* of which are on screen while it runs, and that is a row M3 answers
 * directly: 300 ms under the standard easing set, 500 ms under the emphasized
 * one. The 220 ms this used to run was the platform's *exit-only* figure
 * (Emphasized accelerate, 200 ms) — the cost of a page leaving, applied to both
 * directions of a step that mostly consists of a page arriving. That is what made
 * a tap on a row read as clipped. 300 ms is the standard-set row; it is the
 * faster of M3's two and the one that keeps this look as tight as it was, with
 * the curve unchanged.
 *
 * ### Commit — [COMMIT_MS] stays short, because a finger is already moving
 *
 * A gesture release is not the same event: the depth is travelling when the
 * finger lifts, and M3's table has no row for it because it is not a from-rest
 * transition. Measured against the old behaviour, 220 ms is the settled figure
 * for a released swipe, so it stays.
 *
 * ### Cancel — critically damped, and it has to be
 *
 * A cancel is the one phase that *must not overshoot*: it returns to the state
 * the user was looking at, and the layer below is watching depth 0 as its rest
 * position — an underdamped cancel swings a page past its own resting place and
 * back, which is motion nobody asked for. Damping ratio 1 and medium-low
 * stiffness, exactly as this module's M3 preview always fell back.
 *
 * None of the three is a theme motion-scheme spec. The scheme's spatial specs are
 * springs, and its expressive member is underdamped by design: on a full-bleed
 * page slide that undershoot is not a flourish, it is the whole screen swinging.
 * A fixed-duration tween says what it does and stops when it says it will.
 */
private val MaterialMotion: NavMotion = NavMotion(
    commit = NavSettleSpec.Tween(durationMillis = COMMIT_MS, easing = FastOutSlowInEasing),
    cancel = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
    programmatic = NavSettleSpec.Tween(durationMillis = PROGRAMMATIC_MS, easing = FastOutSlowInEasing),
)

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
 * The timing is fixed-duration and takes the curves this module's M3 look has
 * always run on — see [MaterialMotion] for why each of the three phases is what
 * it is, and why a spring is the wrong instrument for two of them.
 */
internal val MaterialNavTransition: NavTransition = navGraphicsTransition(
    opaqueDepth = 1f,
    motion = MaterialMotion,
) { scope ->
    val width = scope.layoutSize.width.toFloat()
    val depth = scope.relativeDepth
    val rtl = scope.layoutDirection == LayoutDirection.Rtl
    if (depth <= 0f) {
        // Crossing the front edge: either arriving from the trailing edge or
        // leaving toward it, which is the same motion read in opposite directions.
        val offset = (-depth).coerceIn(0f, 1f) * width
        translationX = if (rtl) -offset else offset
        if (offset > 0f) {
            clip = true
            // Rounded from a pixel width through the host's own density, so the
            // radius is the same fraction of the screen on every device.
            shape = RoundedCornerShape(with(scope.density) { (width * CARD_CORNER_FRACTION).toDp() })
        }
    } else {
        // Covered: a quarter width toward the leading edge, fading a tenth while
        // fully buried and sliding home as it is revealed.
        val cover = depth.coerceIn(0f, 1f)
        translationX = (if (rtl) 1f else -1f) * cover * width * PARALLAX_FRACTION
        alpha = 1f - COVER_ALPHA_FALLOFF * cover
    }
}

/**
 * Duration of a `programmatic` step — a tap on a row, or on a back arrow.
 * M3's standard-set figure for a transition that begins and ends on screen.
 */
private const val PROGRAMMATIC_MS = 300

/**
 * Duration of a `commit` — a released swipe. The figure this transition has
 * always settled at, kept: the finger has already paid for most of the distance.
 */
private const val COMMIT_MS = 220

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
