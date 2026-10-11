package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.launch

/**
 * Predictive back with the Miuix navigation look — the same treatment `miuix-nav`
 * applies through its `NavTransitions.MiuixDefault` transition plus
 * `NavDisplayEffects` (corner clip + dim), which is what KernelSU and
 * InstallerX-Revived ship as their "MIUIX" return animation:
 *
 * - the page in use slides out full-width, corner-rounded, following the
 *   system's predictive-back progress 1:1;
 * - the page underneath parallaxes a quarter width the other way, with a light
 *   alpha falloff — depth, not just motion;
 * - a dim scrim lies over the whole revealed layer, deepest when the gesture
 *   starts (0.5, the miuix default `dimAmount`) and lifting linearly to nothing
 *   as the page is uncovered.
 *
 * The gesture itself is **the system's**: this container never touches the
 * pointer. `androidx.navigationevent` bridges the platform's predictive-back
 * stream (edge swipe on gesture navigation, a discrete back key otherwise) into
 * the callbacks below, and `enableOnBackInvokedCallback` in the manifest opts
 * the process in. Replacing the previous in-house drag recognizer — and the
 * touch-slop / overscroll arbitration it needed — with the OS's own gesture
 * recognition is the whole point of this shape.
 *
 * What is **not** portable from `miuix-nav` is the layer underneath. There, a
 * `NavDisplay` keeps the previous entry composed and the reveal shows a live
 * page. This module is separate `Activity`s with no navigation graph, so the
 * previous window is already gone; [background] supplies its photograph, taken
 * before it was covered (see [WindowSnapshot]). Returning `null` falls back to
 * the container colour — the animation still runs, the revealed layer is simply
 * flat.
 *
 * A commit plays the slide-out under an eased tween, then hands over; the host
 * is told the page has already slid off screen so it can suppress the system's
 * own exit animation instead of replaying a second transition over an empty
 * frame ([finishPredictiveBack] handles that). A cancel springs the page back to
 * rest; the system does not report a release velocity for a cancelled gesture,
 * so the spring starts from rest.
 *
 * The back key on a three-button navigation bar arrives as a *discrete* commit
 * — no progress stream — and gets the same slide-out animation from rest, which
 * is what makes the keyboard path look like the gesture path.
 *
 * @param onBack Commits the back action — for an `Activity` host, `finish()`.
 *   The `Boolean` says whether the page has **already slid off screen**: `true`
 *   from a committed back (gesture or key), which must suppress the system exit
 *   animation, or the platform plays a second, unrelated transition over an
 *   already-empty frame.
 * @param background Supplies the snapshot of the parent window, drawn beneath
 *   [content]. A **provider**, read during this composable's own composition,
 *   so a picture taken under an older palette is re-read (and refused) on a
 *   theme switch instead of being captured once by the call site.
 * @param content The page itself.
 */
@Composable
fun PredictiveBackContainer(
    onBack: (alreadySlidOut: Boolean) -> Unit,
    background: () -> ImageBitmap? = { null },
    content: @Composable () -> Unit
) {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    if (dispatcher == null) {
        // No back-event source: render the page plain and let the system's
        // default back path finish it. Unreachable on the hosts this app runs
        // on (every ComponentActivity from androidx.activity 1.12 owns a
        // dispatcher), but the page must survive the absence either way.
        content()
        return
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()

    // The parent's picture, read here rather than handed in — same reason as
    // ever: the read must be part of this composable's own recomposition scope,
    // so a theme change re-reads it instead of serving a stale photograph.
    val backgroundBitmap = background()

    // flushForPreview() retakes the parent picture into a plain, non-observable
    // cache at the very start of the gesture. The read above is a composition-
    // time capture, so it would still hold the stale null taken just after the
    // appearance pick and never notice the cache being repaired. Hold the fresh
    // picture in observable state so the preview layer recomposes onto it.
    var gestureBackground by remember { mutableStateOf<ImageBitmap?>(null) }
    val previewBitmap = gestureBackground ?: backgroundBitmap

    // +1 when the dismissing direction is toward the end edge (LTR rightward),
    // -1 under RTL. Every sign below is written in screen terms so the file
    // needs no second code path for RTL.
    val forward = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f

    val backdropColor = MaterialTheme.colorScheme.background

    // Displacement of the page from its resting place, in pixels, 0..width.
    // Driven by the system's predictive-back progress; the commit/cancel
    // settles animate this same value, so gesture and key paths share one
    // motion.
    val offsetPx = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    // Corner radius of the sheet while it is displaced, as a fraction of the
    // screen width — measured off the reference implementation (KernelSU,
    // miuix-nav), whose revealed card traces a quarter circle spanning 14.33%
    // of the panel width. The `widthPx > 0` guard matters: before the first
    // layout pass the width is 0, and a radius of 0 would draw square corners
    // for one frame.
    val cardCornerRadius = if (widthPx > 0f) {
        with(density) { (widthPx * CARD_CORNER_FRACTION).toDp() }
    } else {
        0.dp
    }

    // One driver per page. `settled` makes a commit terminal: the slide-out
    // tween then owns the value, and a second back event arriving during it
    // (the key can repeat faster than the tween finishes) must not re-enter.
    class Driver : NavigationEventHandler<NavigationEventInfo>(
        initialInfo = NavigationEventInfo.None,
        isBackEnabled = true
    ) {
        var settled = false

        override fun onBackStarted(event: NavigationEvent) {
            // The picture underneath may be one palette behind, or still
            // carrying the press that left the page: an appearance change and a
            // launch both defer their retake so the tap that caused them does
            // not pay for a full-screen redraw. The gesture's start is the last
            // moment that picture can be fixed before it is needed, so everything
            // pending is run through now — before anything moves.
            WindowSnapshot.flushForPreview()
            // Pick up the picture just retaken into the non-observable cache;
            // writing observable state recomposes the preview onto it before the
            // first progress frame.
            gestureBackground = background()
            onGesture(event)
        }

        override fun onBackProgressed(event: NavigationEvent) {
            onGesture(event)
        }

        override fun onBackCompleted() {
            if (settled) return
            settled = true
            // Slide out under the tween from wherever the finger left the page,
            // then hand over. `alreadySlidOut = true` lets the host suppress
            // its own exit transition — the page is off screen already.
            scope.launch {
                offsetPx.animateTo(widthPx, tween(durationMillis = EXIT_MS))
                onBack(true)
            }
        }

        override fun onBackCancelled() {
            if (settled) return
            // Drop the gesture-time capture so the resting read takes over again;
            // the state write itself recomposes this body, re-reading background()
            // from the now-current cache.
            gestureBackground = null
            // Spring back to rest from wherever the finger left it. The system
            // reports no release velocity for a cancelled gesture, so the
            // spring starts from rest; a non-bouncy spec cannot overshoot and
            // flash the layer behind in the wrong direction.
            scope.launch {
                offsetPx.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }

        private fun onGesture(event: NavigationEvent) {
            if (settled) return
            val extent = widthPx
            if (extent <= 0f) return
            // Devices have been seen misreporting progress past 1; the visual
            // math below assumes 0..1.
            val progress = event.progress.coerceIn(0f, 1f)
            scope.launch { offsetPx.snapTo(progress * extent) }
        }
    }

    DisposableEffect(dispatcher) {
        val driver = Driver()
        dispatcher.addHandler(driver)
        onDispose { driver.remove() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
    ) {
        // The parent — 上一层页面, the page being returned to.
        //
        // THE SHADOW LIVES HERE AND NOWHERE ELSE. It is a flat wash over this
        // whole layer, and it LIFTS as the gesture progresses:
        //
        //   gesture start, page fully covered -> deepest (0.5)
        //   dragged to the edge, page revealed -> gone
        //
        // That direction is the point of the effect: the page underneath starts
        // buried in shade and comes up to its own brightness as it is
        // uncovered. Deriving everything from `offsetPx` inside
        // `graphicsLayer`/`drawWithContent` keeps it on the same per-frame clock
        // as the moving sheet — a shadow computed in composition would run on
        // the recomposition clock instead, and that drift is exactly what
        // flashed in an earlier version of this file.
        //
        // The parallax and the alpha falloff are the MiuixDefault treatment:
        // the covered layer sits a quarter width toward the leading edge and
        // slides home as it is uncovered, dimmed 10% while it is still buried.
        // The snapshot image slides inside a backdrop of the page colour, so
        // the strip vacated by the parallax reads as the background the system
        // transition would show.
        //
        // The layer is NOT clipped to the sheet's rounded corners: the rounded
        // outline belongs to the page that slides (drawn below); the page being
        // returned to is a full-bleed rectangle underneath it, corners
        // included.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backdropColor)
                .graphicsLayer {
                    val travel = (offsetPx.value / widthPx).coerceIn(0f, 1f)
                    translationX = -forward * PARALLAX_FRACTION * (1f - travel) * widthPx
                    alpha = 1f - COVER_ALPHA_FALLOFF * (1f - travel)
                }
                .drawWithContent {
                    drawContent()
                    // `travel > 0f` guards rest: at rest the layer is hidden
                    // behind the page, and without the guard a wash at its
                    // deepest would show for one frame before the page
                    // settles.
                    if (widthPx > 0f) {
                        val travel = (offsetPx.value / widthPx).coerceIn(0f, 1f)
                        if (travel > 0f) {
                            // Pure black, not the theme's `scrim` role. That role
                            // is Miuix's `windowDimming`, which is *already* an
                            // alpha-carrying black (0.3 light, 0.6 dark) for
                            // dialogs and menus; multiplying it by this dim left
                            // the wash at 15% black in light mode against the
                            // reference's 50%, which is exactly what "the shadow
                            // is not deep enough" looked like. miuix-nav draws
                            // this scrim as Color.Black at `dimAmount` (0.5), and
                            // so does this.
                            drawRect(
                                color = Color.Black,
                                alpha = MAX_SCRIM_ALPHA * (1f - travel)
                            )
                        }
                    }
                }
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap,
                    contentDescription = null,
                    // FillBounds, not Crop: the snapshot is already exactly the
                    // window's size, so it must be placed 1:1.
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // The page on top — the one the user is looking at. It slides out full
        // width with the system's progress, and that is ALL it does.
        //
        // No wash, no gradient, no elevation shadow on this layer. The shadow
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
 * Peak of the shadow laid over 上一层页面 as it is revealed — miuix-nav's default
 * `dimAmount`, applied to an opaque black and lifting linearly as the page is
 * uncovered, which is that library's own curve (`scrimFraction` follows the
 * cover depth).
 */
private const val MAX_SCRIM_ALPHA = 0.5f

/**
 * How far the covered layer sits away from its resting place while buried, as a
 * fraction of the screen width — the MiuixDefault covered parallax.
 */
private const val PARALLAX_FRACTION = 0.25f

/** Alpha the covered layer loses while fully buried — the MiuixDefault falloff. */
private const val COVER_ALPHA_FALLOFF = 0.1f

/** Duration of the commit slide. The M3 "medium" expressive duration. */
private const val EXIT_MS = 220

/**
 * Corner radius of the sheet, as a fraction of the screen width.
 *
 * Measured off the reference implementation rather than chosen: KernelSU's
 * settings page (miuix-nav) traces a quarter circle whose horizontal travel is
 * 172 px on a 1200 px panel — 14.33%, fitted with an rms error of 1.7 px. A
 * fraction rather than a dp constant, so it tracks the reference on any panel;
 * a fixed dp was measured to fail because the arc's apex falls above the top of
 * the window and only its near-vertical tail is visible.
 */
private const val CARD_CORNER_FRACTION = 0.1433f
