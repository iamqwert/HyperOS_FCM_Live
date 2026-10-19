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
 * M3's settle, in every phase the driver can be released into.
 *
 * A commit runs the fixed 220 ms curve M3 always used — a tween, so a released
 * gesture continues from where the finger left it, not from a spring's
 * velocity-continuous handoff (that handoff is part of the Miuix feel, not this
 * one). A cancel springs back to rest, and here it is not Miuix's shared 146
 * spring either, but the medium-low one M3 fell back to.
 *
 * A from-rest push or pop has no previous M3 counterpart: back then the push was
 * the platform's own window animation and the pop was the tween above, so the
 * step is given the commit's curve to keep one feel for a whole step.
 *
 * Declared above the transition that reads it: top-level properties initialize in
 * file order.
 */
private val MaterialMotion: NavMotion = NavMotion(
    commit = NavSettleSpec.Tween(durationMillis = EXIT_MS, easing = FastOutSlowInEasing),
    cancel = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
    programmatic = NavSettleSpec.Tween(durationMillis = EXIT_MS, easing = FastOutSlowInEasing),
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
 * How long M3's commit settle runs, in milliseconds. Matched to the platform's
 * own cross-activity duration.
 */
private const val EXIT_MS = 220

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
