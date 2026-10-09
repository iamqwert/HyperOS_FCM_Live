package io.github.howard20181.hyperos.fcmlive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Horizontal swipe-to-go-back that reveals the real layer underneath.
 *
 * The gesture and its physics follow the design of `miuix-nav`
 * (`compose-miuix-ui/miuix`, Apache-2.0), whose swipe-dismiss implementation is
 * the reference for what this file does. Three of its ideas are load-bearing
 * here, and each one is why a naive `draggable`+`offset` falls short:
 *
 * 1. **Child-first arbitration.** A page whose body scrolls or whose rows are
 *    clickable must keep those gestures. Ownership is decided by watching what
 *    the *descendants* consumed rather than by locking the axis up front, so a
 *    horizontal drag inside a `LazyColumn` row still wins for the row, while a
 *    drag on dead space wins for navigation.
 *
 * 2. **Pre-claim travel is preserved.** The finger has to move past touch slop
 *    before navigation may claim the gesture, and by then the page has not moved
 *    yet. Carrying `towardTravel - touchSlop` into the offset at claim time is
 *    what stops the page from lagging a slop's width behind the finger for the
 *    rest of the swipe.
 *
 * 3. **Release is velocity-first.** Distance alone makes a quick flick feel
 *    dead; a real `VelocityTracker` sampled from the touch-down makes it commit
 *    the way a flick should, with position as the fallback.
 *
 * What is **not** portable is the part that actually produces the reveal. In
 * `miuix-nav` the previous entry stays composed under the top one because a
 * `NavDisplay` drives a real back stack. This module is five separate
 * `Activity`s with no navigation graph, so when a sub-page is on screen nothing
 * is composed beneath it — the previous window is already gone. The reveal is
 * therefore supplied by the caller through [background], which is a snapshot of
 * the parent window taken before it was left. Only its leading strip is ever
 * visible, and it is standing still, which is exactly the cue the gesture
 * needs. Drawing a gradient instead is what a previous attempt did, and it is
 * why the result read as a blank panel rather than as a page being pushed
 * aside.
 *
 * The shadow belongs to [background] — 上一层页面, the page being returned to —
 * and to nothing else. At the moment the gesture starts, that page is still
 * fully covered, so it sits under the deepest shade; as it is dragged into view
 * the shade lifts off it, until a fully revealed page is at its own brightness.
 * The shadow therefore comes *off* the previous page rather than being applied
 * to anything.
 *
 * [content] — the page in use — carries no shadow of any kind, and no wash. It
 * translates, and while it is displaced its corners are rounded. Three earlier
 * attempts put some shading near its edge, and every one of them produced the
 * same complaint: a grey band pinned to the edge of the page being used, which
 * darkens the surface under the user's attention and reads as a stripe rather
 * than as depth. A shadow at the seam is assigned by the eye to whichever layer
 * it lands on, so it must be a wash over the whole of [background] instead —
 * that cannot be misread as belonging to the sliding page.
 *
 * Deliberately separate from the system's **predictive back**: that is
 * dispatched only from the screen's own edge strip and unregisters this
 * component's `BackHandler` while idle. This handles the body drag the system
 * never sees.
 *
 * @param onBack Commits the back action — for an `Activity` host, `finish()`.
 *   The `Boolean` says whether the page has **already slid off screen**: `true`
 *   from a committed swipe, `false` from a back press that never moved the
 *   page. An `Activity` host must suppress its own exit animation when it is
 *   `true`, or the system plays a second, unrelated transition over an empty
 *   frame — which is what a duplicate "return" looks like.
 * @param background Supplies the snapshot of the parent window, drawn beneath
 *   [content]. It stays put while [content] slides away. A **provider**, called
 *   during this composable's own composition, so a picture taken under an older
 *   palette is re-read (and refused) on a theme switch instead of being captured
 *   once by the call site. Returning `null` falls back to the page container
 *   colour, which is derived from the live theme: the gesture still works and
 *   the strip is flat rather than wrong.
 * @param enabled `true` unless the host wants the tree untouched.
 * @param content The page itself.
 */
@Composable
fun SwipeBackContainer(
    onBack: (alreadySlidOut: Boolean) -> Unit,
    background: () -> ImageBitmap? = { null },
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()

    // The parent's picture, read here rather than handed in.
    //
    // A provider instead of a value, so the read happens inside this
    // composable's own scope. A value would be captured once by whichever call
    // site passed it and could keep pointing at a photograph from before the
    // user changed the theme — the layer revealed by a swipe-back would then
    // come up wearing the old palette. Reading it here means the read is part
    // of this recomposition scope and re-runs whenever the palette does.
    val backgroundBitmap = background()

    // +1 when the dismissing direction is toward the end edge (LTR rightward),
    // -1 under RTL. Every sign test below is written in screen terms so the
    // file needs no second code path for RTL.
    val forward = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f

    val touchSlopPx = with(density) { TOUCH_SLOP.toPx() }
    val commitVelocityPx = with(density) { COMMIT_VELOCITY_DP_PER_SEC.dp.toPx() }
    val scrimColor = MaterialTheme.colorScheme.scrim

    // Offset of the page from its resting place, in pixels, always >= 0. Kept
    // as an Animatable so the release can seed it with the finger's velocity
    // and let the spring finish the move instead of restarting from rest.
    //
    // Everything the shadow does is derived from this one value, on every
    // frame — there is deliberately no second piece of state for it. A shadow
    // that is frozen or animated on its own clock drifts out of step with the
    // page it is painted on, and that drift is exactly what flashes.
    val offsetPx = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    // How far the page has been pushed aside, 0f (rest, covering everything) to
    // 1f (fully off screen). Derived, not stored — see the note above.
    val revealFraction = if (widthPx > 0f) {
        (offsetPx.value / widthPx).coerceIn(0f, 1f)
    } else {
        0f
    }

    // Corner radius of the sheet, derived from the measured width rather than
    // fixed in dp.
    //
    // The value comes from measuring the reference implementation (KernelSU's
    // settings page, whose swipe-back runs on `miuix-nav`): its revealed card
    // traces a quarter circle whose horizontal travel is 172 px on a 1200 px
    // panel — 14.33% of the width. Expressed as a fraction it survives any
    // panel size and density, which is what a fixed dp cannot do.
    //
    // The `widthPx > 0` guard matters: before the first layout pass the width
    // is 0, and a radius of 0 would draw square corners for one frame.
    val cardCornerRadius = if (widthPx > 0f) {
        with(density) { (widthPx * CARD_CORNER_FRACTION).toDp() }
    } else {
        0.dp
    }

    // Strength of the wash laid over 上一层页面, 0f..1f.
    //
    // Deepest while the page above still covers the card, lifting to nothing as
    // the card is uncovered: the shadow comes OFF the previous page as it is
    // returned to, rather than being added to it.
    //
    // Derived from `offsetPx` on every frame with no second piece of state. A
    // shadow frozen or animated on its own clock drifts out of step with the
    // page moving above it, and that drift is what flashed in an earlier
    // version — the shadow stopped while the page kept tweening.
    val shadowAlpha = MAX_SCRIM_ALPHA * (1f - revealFraction)

    // A back press mid-gesture (or mid-settle) must finish at once rather than
    // stack a second transition on the one already running. `true` because the
    // page is already displaced: the system's exit animation is authored for a
    // window at rest, and playing it from a half-slid position reads as a jump.
    BackHandler(enabled = offsetPx.value != 0f) { onBack(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(forward, widthPx, touchSlopPx) {
                val extent = widthPx
                if (extent <= 0f) return@pointerInput

                awaitEachGesture {
                    // `requireUnconsumed = false`: a descendant that already
                    // took the down (a button press) must not hide the gesture
                    // from the arbiter — the arbiter is what decides.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    // The picture of the page underneath may be one palette
                    // behind: an appearance change defers its re-render so the
                    // tap that made it does not pay for a full-screen redraw, and
                    // that delay is what keeps the menu row's ripple smooth — see
                    // WindowSnapshot.refreshForTheme. This is the first moment
                    // that picture might be needed, so any pending re-render is
                    // forced through now, on the frame the finger lands and
                    // before anything moves.
                    //
                    // Here rather than at claim: claim happens after the pointer
                    // crosses touch slop, so a redraw there would land on a frame
                    // the drag is already using. A finger-down is still, and the
                    // cost is only paid at all when something is actually
                    // pending — every other down is a single map probe.
                    WindowSnapshot.flushForTheme()

                    // Phase 1 — engagement. Watch without consuming, on the
                    // Final pass, i.e. after every descendant has had its say
                    // on the Main pass. What a descendant consumed here is the
                    // only input the ownership decision needs.
                    var towardTravel = 0f
                    var crossTravel = 0f
                    var claimed = false

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.isEmpty()) break

                        var towardDelta = 0f
                        var crossDelta = 0f
                        for (c in event.changes) {
                            if (!c.pressed) continue
                            tracker.addPosition(c.uptimeMillis, c.position)
                            val d = c.positionChange()
                            towardDelta += d.x * forward
                            crossDelta += d.y
                        }
                        if (towardDelta == 0f && crossDelta == 0f) {
                            if (event.changes.none { it.pressed }) break
                            continue
                        }

                        // Content direction: the pointer is heading away from
                        // the dismissing direction, or is more vertical than
                        // horizontal. Either way this is not our gesture.
                        if (towardTravel + towardDelta < -touchSlopPx ||
                            (abs(crossTravel + crossDelta) > touchSlopPx &&
                                abs(crossTravel + crossDelta) > abs(towardTravel + towardDelta))
                        ) {
                            break
                        }

                        // A descendant consuming a position change means some
                        // inner scrollable is following the finger. Let it.
                        if (event.changes.any { it.isConsumed }) break

                        towardTravel += towardDelta
                        crossTravel += crossDelta

                        if (towardTravel > touchSlopPx &&
                            towardTravel >= abs(crossTravel)
                        ) {
                            // Claim. The travel accumulated while the pointer
                            // was crossing slop is carried into the offset, so
                            // the page meets the finger instead of starting a
                            // slop's width behind it.
                            val initial = (towardTravel - touchSlopPx).coerceAtLeast(0f)
                            // `awaitEachGesture` runs in a restricted suspension
                            // scope, so the Animatable cannot be driven from
                            // here directly. Hopping back onto the composition
                            // scope is the supported way, and it is what keeps
                            // the snapping out of the pointer pipeline.
                            scope.launch { offsetPx.snapTo(initial.coerceIn(0f, extent)) }
                            claimed = true
                            event.changes.forEach { it.consume() }
                            break
                        }

                        if (event.changes.none { it.pressed }) break
                    }

                    if (!claimed) return@awaitEachGesture

                    // Phase 2 — follow. From here the gesture is ours: consume
                    // every change on both axes so nothing behind us starts
                    // reacting mid-swipe, and end only when the finger lifts.
                    var dismissTravel = offsetPx.value
                    drag(down.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val d = change.positionChangeIgnoreConsumed()
                        dismissTravel = (dismissTravel + d.x * forward).coerceIn(0f, extent)
                        change.consume()
                        scope.launch { offsetPx.snapTo(dismissTravel) }
                    }

                    // Release — velocity first, distance as the fallback.
                    val velocityX = tracker.calculateVelocity().x * forward
                    val progress = (dismissTravel / extent).coerceIn(0f, 1f)
                    val committed = progress >= COMMIT_DISTANCE_FRACTION ||
                        velocityX >= commitVelocityPx

                    if (committed) {
                        // Slide out under the finger's own momentum, then hand
                        // over. The finish lands while the page is already off
                        // screen, so the exit is not a hard cut — and the host
                        // is told the page is gone, so it does not add a
                        // transition of its own on top of this one.
                        scope.launch {
                            offsetPx.animateTo(
                                targetValue = extent,
                                animationSpec = tween(durationMillis = EXIT_MS)
                            )
                            onBack(true)
                        }
                    } else {
                        scope.launch {
                            // Seed the return with the finger's velocity, but
                            // never a positive one: a value that still pushes
                            // outward would overshoot rest and flash the layer
                            // behind in the wrong direction.
                            offsetPx.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                initialVelocity = velocityX.coerceAtMost(0f)
                            )
                        }
                    }
                }
            }
    ) {
        // The parent — 上一层页面, the page being returned to.
        //
        // THE SHADOW LIVES HERE AND NOWHERE ELSE. It is a flat wash over this
        // whole card, and it LIFTS as the gesture progresses:
        //
        //   gesture start, card fully covered -> deepest
        //   dragged to the edge, card revealed -> gone
        //
        // That direction is the point of the whole effect: the page underneath
        // starts buried in shade and comes up to its own brightness as it is
        // uncovered, which is what "the previous page had a shadow over it"
        // means. Deriving it from `offsetPx` on every frame keeps it in step
        // with the finger; freezing it is what flashed in an earlier version.
        //
        // Deliberately a wash over the WHOLE card rather than a gradient strip
        // near the seam. A strip is what three previous attempts did, and every
        // one of them read as the shadow belonging to the sliding page's edge
        // instead of to the page being revealed — the strip sits against the
        // seam, so the eye assigns it to whichever layer it happens to fall on.
        // A uniform wash cannot be misattributed: it is simply this card, dark.
        //
        // **The layer is NOT clipped to the card's rounded corners.** It used
        // to be, and that left the four corner wedges outside the arc showing
        // the container colour — a bare strip of page background with no shade
        // on it, which reads as the shadow having failed to cover the screen.
        // The rounded outline belongs to the sheet that slides (drawn below);
        // the page being returned to is a full-bleed rectangle underneath it,
        // corners included. So the snapshot fills the whole box and the wash
        // covers the whole box, and neither is cut.
        //
        // Nothing is drawn on the page above (see below), so nothing can darken
        // the page the user is actually looking at.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .drawWithContent {
                    drawContent()
                    // `offsetPx > 0f` guards rest: at rest the card is hidden
                    // behind the page, and without the guard a wash at its
                    // deepest would show for one frame before the page settles.
                    if (shadowAlpha > 0f && offsetPx.value > 0f) {
                        drawRect(color = scrimColor, alpha = shadowAlpha)
                    }
                }
        ) {
            if (backgroundBitmap != null) {
                Image(
                    bitmap = backgroundBitmap,
                    contentDescription = null,
                    // FillBounds, not Crop: the snapshot is already exactly the
                    // window's size, so it must be placed 1:1. Crop scales it to
                    // cover, which makes the revealed strip show a slightly
                    // enlarged copy — the content sits off from where the real
                    // page had it, which reads as the layer shifting as it is
                    // uncovered.
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // The page on top — the one the user is looking at. It moves, and that
        // is ALL it does.
        //
        // No wash, no gradient, no elevation shadow. Every earlier attempt put
        // something on this layer, and each time the result was a grey band
        // pinned to the edge of the page in use — which is precisely backwards:
        // it darkens the surface under the user's attention and makes the
        // effect read as a stray stripe rather than as depth. The shadow
        // belongs to the card below, and it is drawn there.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetPx.value * forward
                    // Round the sheet only while it is off its rest position. At
                    // rest it is full-bleed, and rounding it there would cut
                    // visible notches out of the page everyone looks at.
                    clip = offsetPx.value > 0f
                    shape = RoundedCornerShape(cardCornerRadius)
                }
        ) {
            content()
        }
    }
}

/**
 * How far the page must travel before releasing commits, as a fraction of the
 * width.
 *
 * A fraction rather than a fixed dp: on a tablet the same intent covers more
 * pixels, and an absolute threshold would make the gesture progressively harder
 * to complete as screens get wider.
 */
private const val COMMIT_DISTANCE_FRACTION = 0.28f

/** A quick flick commits even when it fell short of the distance threshold. */
private val COMMIT_VELOCITY_DP_PER_SEC = 700f

/** Duration of the finish slide. The M3 "medium" expressive duration. */
private const val EXIT_MS = 220

/** Peak of the shadow laid over 上一层页面 as it is revealed. */
private const val MAX_SCRIM_ALPHA = 0.32f

/**
 * Corner radius of the sheet, as a fraction of the screen width.
 *
 * Measured off the reference implementation rather than chosen. KernelSU's
 * settings page uses `miuix-nav` for its swipe-back, and its revealed card
 * traces a quarter circle whose horizontal travel is 172 px on a 1200 px
 * panel — 14.33%. Fitting that arc gives an rms error of 1.7 px, so the number
 * is solid.
 *
 * A fraction, not a dp constant, for two reasons. The reference is itself
 * proportional — its arc reaches exactly one radius across as it falls one
 * radius down — so this tracks it on any panel. And a fixed dp was measured to
 * fail here: 28dp on this device came out *larger* than the reference in
 * proportion (1.32x) yet still read as square, because at that size the arc's
 * apex falls above the top of the window and only its near-vertical tail is
 * visible. Matching the reference's proportion is what puts the whole curve
 * back on screen.
 *
 * This supersedes an earlier note in this file claiming the value could not be
 * derived from the composition: the *hardware* screen radius indeed cannot be
 * read, but this is not that — it is the radius of the card the gesture draws,
 * which is ours to choose and is measured from a reference instead.
 */
private const val CARD_CORNER_FRACTION = 0.1433f

/**
 * The distance the finger must cover before navigation may claim the gesture —
 * mirrored from `ViewConfiguration.getScaledTouchSlop()`, which is 8dp at the
 * default density. A dead zone rather than a threshold to be subtracted and
 * forgotten: the travel inside it is carried into the offset instead (see the
 * claim branch above).
 */
private val TOUCH_SLOP = 8.dp
