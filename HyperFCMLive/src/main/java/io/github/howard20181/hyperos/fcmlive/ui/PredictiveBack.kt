package io.github.howard20181.hyperos.fcmlive.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastRoundToInt
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.gesture.navSwipeDismiss
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.squircle.absoluteSquircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Predictive back with the Miuix navigation look — the same treatment `miuix-nav`
 * applies through its `NavTransitions.MiuixDefault` transition plus
 * `NavDisplayEffects` (corner clip + dim + input blocking), which is what KernelSU
 * and InstallerX-Revived ship as their "MIUIX" return animation:
 *
 * - the page in use slides out full-width, corner-rounded, following the
 *   system's predictive-back progress 1:1;
 * - the page underneath parallaxes a quarter width the other way, with a light
 *   alpha falloff — depth, not just motion;
 * - a dim scrim lies over the whole revealed layer, deepest when the gesture
 *   starts (0.5, the miuix default `dimAmount`) and lifting linearly to nothing
 *   as the page is uncovered.
 *
 * Under the **Miuix look** ([UiStyle.Miuix]) the settle physics and the clip
 * treatment are lifted from `miuix-nav`'s driver, constant for constant:
 *
 * - a **commit** converges on the shared settle spring — critically damped,
 *   stiffness 146, visibility threshold 0.0025 on the depth axis — seeded with
 *   the release velocity estimated from the gesture's last two timestamped
 *   events, floored at the exact no-overshoot bound (`√stiffness · remaining`)
 *   so even a hard fling cannot bounce back past the fully-popped position;
 * - a **discrete back key** (no progress stream, from rest) plays the
 *   fixed-duration programmatic curve instead — 500 ms of `NavProgrammaticEasing`,
 *   the underdamped step response baked into an easing that pins the total
 *   duration;
 * - a **cancel** runs the same spring back to rest with the rest position pinned
 *   as a bound (`settleCancel`), so the page can never overshoot into the wrong
 *   direction;
 * - while a transition owns the page it is squircle-clipped on its **leading**
 *   corner pair at the physical screen corner radius (`NavCornerClipMode.Leading`
 *   with `rememberNavSystemCornerRadius`), and every pointer event is consumed on
 *   the Initial pass (the block-input bucket) — both lifted verbatim from
 *   `NavDisplay`;
 * - the sheet rides whole pixels (`fastRoundToInt` on `translationX`) so the
 *   squircle mask's edge cannot shimmer between two pixel alignments.
 *
 * Under the **Material look** the previous behaviour stands: an eased 220 ms
 * tween commit, a non-bouncy `StiffnessMediumLow` cancel from rest, a
 * full-corner rounded-rectangle clip at 14.33% of the panel width, no input
 * blocking.
 *
 * Two input sources drive that one motion.
 *
 * `androidx.navigationevent` bridges the platform's predictive-back stream (edge
 * swipe on gesture navigation, a discrete back key otherwise) into the callbacks
 * below, and `enableOnBackInvokedCallback` in the manifest opts the process in.
 *
 * On top of it sits `miuix-nav`'s own app-level swipe-back gesture — the one the
 * Example and KernelSU hand to every `entry(swipeDismiss = …)`. This app has no
 * `NavDisplay` to attach it to (separate `Activity`s, no navigation graph), so
 * the library's `Modifier.navSwipeDismiss` is attached straight to this
 * container and drives the same depth axis through its own two-phase pointer
 * arbitration: a gesture starting on a pager, a list or any other child that
 * claims the finger is left to that child. It is taken as the library's own code
 * rather than re-created around `detectHorizontalDragGestures` — that approach
 * loses to a nested horizontal consumer and cancels mid-way, which is exactly
 * what the arbitration exists to avoid.
 *
 * The two sources are told about each other. While the library's gesture owns
 * the pointer, the platform's progress stream is ignored (`SwipeRun.owns`); the
 * library stands down — cancelling its live sequence without a settle, as it does
 * upstream when the ownership flag turns — while the platform is carrying a
 * progress-bearing gesture of its own (`platformGesture`). One axis, one writer
 * per sequence, in both directions.
 *
 * What is **not** portable from `miuix-nav` is the layer underneath. There, a
 * `NavDisplay` keeps the previous entry composed and the reveal shows a live
 * page. This module is separate `Activity`s with no navigation graph, so the
 * previous window is already gone; [background] supplies its photograph, taken
 * before it was covered (see [WindowSnapshot]). Returning `null` falls back to
 * the container colour — the animation still runs, the revealed layer is simply
 * flat.
 *
 * After a commit the host is told the page has already slid off screen so it can
 * suppress the system's own exit animation instead of replaying a second
 * transition over an empty frame ([finishPredictiveBack] handles that).
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

    // The settle treatment is keyed to the active 界面风格: the Miuix look runs the
    // miuix-nav driver physics; the Material look keeps this file's original,
    // simpler motion. Read in composition so a style switch rebuilds the driver
    // with the right branch (the DisposableEffect below is keyed on it too).
    val isMiuix = LocalUiStyle.current.isMiuixBased

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

    // MiuixDefault paints the revealed layer in the theme's surface (what the
    // Example hands `NavDisplay` as `backdropColor`); the Material look keeps
    // its own background role.
    val backdropColor = if (isMiuix) {
        MiuixTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.background
    }

    // The page's depth on miuix-nav's axis: `topIndex` — 1 for every host of this
    // container, each is a single pushed page — at rest, one less once the page
    // has fully left. It IS the Animatable `Modifier.navSwipeDismiss` drives, and
    // the three layers below derive their pixel displacement from it, so the
    // library's gesture and the platform's progress stream move one single value:
    // no second motion to reconcile, no jump where one source hands the page to
    // the other. Every constant translated in this file is in depth units
    // already — the pixel axis this file worked in before was the same motion
    // with the width and the sign factored out.
    val depth = remember { Animatable(TOP_INDEX.toFloat()) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    // The app-level swipe, as the references resolve it: the edge the swipe must
    // start from, chosen by layout direction — the same `when` the Example and
    // KernelSU run before handing the value to every entry. It is always on: the
    // two of them carry it as an appearance-page switch, this app carries it as a
    // behaviour, with nothing left to switch.
    // True while the platform carries a progress-bearing gesture of its own —
    // the system's edge swipe, which competes for the pointer and is driving this
    // axis already. State because the direction gate below reads it in
    // composition; the library's gesture stands down for as long as it holds.
    var platformGesture by remember { mutableStateOf(false) }
    val swipeDirection = when {
        platformGesture -> NavSwipeDirection.None
        layoutDirection == LayoutDirection.Rtl -> NavSwipeDirection.RightToLeft
        else -> NavSwipeDirection.LeftToRight
    }

    // The app-level swipe's own run state: whether the library's gesture owns the
    // pointer for this sequence, whether the parent picture has been borrowed for
    // it, and whether a commit's settle is still running. A plain object rather
    // than composition state on purpose — the library's gesture and this file's
    // pointer handlers read and write it per event, and a snapshot write per
    // event would recompose the page at pointer rate. The one flag composition
    // does read, whether the platform is driving, is `platformGesture` above.
    //
    // The picture is borrowed on the first live follow event rather than at the
    // decide stage: the library reports only once it has claimed the sequence,
    // and a claim that ends with a child keeping the finger never gets here, so
    // no gesture that a pager or a list wins pays for a snapshot retake.
    class SwipeRun {
        var owns = false
        var pictureTaken = false
        var settling = false
    }
    val swipe = remember { SwipeRun() }

    // True from the moment a back gesture/key claims the page until a cancel
    // settle has landed — both sources claim it: the platform's `onBackStarted`,
    // and the library's gesture on the first frame it has moved the depth.
    // Drives the two transition-time treatments miuix-nav applies through its
    // depth buckets — the leading squircle clip and the input blackout — exactly
    // while the page is off its rest position (at rest the sheet is full-bleed
    // and interactive, as there).
    var transitionActive by remember { mutableStateOf(false) }

    // Corner radius of the sheet while it is displaced, as a fraction of the
    // screen width — the **Material** look's treatment, measured off KernelSU,
    // whose revealed card traces a quarter circle spanning 14.33% of the panel
    // width. The `widthPx > 0` guard matters: before the first layout pass the
    // width is 0, and a radius of 0 would draw square corners for one frame.
    val cardCornerRadius = if (widthPx > 0f) {
        with(density) { (widthPx * CARD_CORNER_FRACTION).toDp() }
    } else {
        0.dp
    }

    // The **Miuix** look clips at the physical screen corner instead — read the
    // way miuix-nav's `rememberNavSystemCornerRadius` does. Consumed only while
    // a transition owns the page, on the leading corner pair.
    val screenCornerRadius = rememberSystemScreenCornerRadius()

    // Corner clip, straight out of NavDisplay's `NavCornerClipMode.Leading` arm:
    // the squircle mask rides only the transitioning sheet, and only on the
    // corners that meet the screen edge as the page slides out — left pair for
    // LTR, right pair for RTL. `absoluteSquircleClip` takes physical corners
    // (never flipped by layout direction), so the side is picked here.
    val miuixClipModifier = if (isMiuix && transitionActive) {
        if (layoutDirection == LayoutDirection.Ltr) {
            Modifier.absoluteSquircleClip(
                topLeft = screenCornerRadius,
                topRight = 0.dp,
                bottomRight = 0.dp,
                bottomLeft = screenCornerRadius
            )
        } else {
            Modifier.absoluteSquircleClip(
                topLeft = 0.dp,
                topRight = screenCornerRadius,
                bottomRight = screenCornerRadius,
                bottomLeft = 0.dp
            )
        }
    } else {
        Modifier
    }

    // Input blackout while a transition owns the page — NavDisplay's block-input
    // bucket, verbatim: an Initial-pass sink that consumes the pointer DOWN before
    // any child can see it, so taps launched mid-transition cannot land on a page
    // that is sliding out (or on the one being revealed). Every child gesture
    // starts at a down, so a consumed down is what "blocked" means for all of
    // them.
    //
    // Only downs, deliberately — not the moves that follow. The library's swipe
    // arbiter judges a candidate sequence by the `isConsumed` flag of its
    // non-zero position changes, and two consumed moves in a row make it yield
    // the finger to "child content". A sink that ate moves would therefore talk
    // a swipe started during a settle out of its own gesture, and the library
    // supports grabbing exactly that settling page.
    val blockInputModifier = if (isMiuix && transitionActive) {
        Modifier.pointerInput(BLOCK_INPUT_KEY) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    event.changes.fastForEach {
                        if (it.changedToDownIgnoreConsumed()) it.consume()
                    }
                }
            }
        }
    } else {
        Modifier
    }

    // One driver per page (rebuilt on a style switch). `settled` makes a commit
    // terminal: the settle animation then owns the value, and a second back
    // event arriving during it (the key can repeat faster than the settle
    // finishes) must not re-enter. `swipe.owns` is its mirror for the other
    // source: the container's swipe gesture raises it for as long as it carries
    // the pointer, and the platform's stream is ignored while it holds.
    class Driver : NavigationEventHandler<NavigationEventInfo>(
        initialInfo = NavigationEventInfo.None,
        isBackEnabled = true
    ) {
        var settled = false

        // Per-gesture trackers — a straight lift of NavDisplay's onProgress
        // bookkeeping: the last two timestamped events estimate the release
        // velocity (progress-units per second, positive toward the exit edge),
        // which the commit spring is seeded with so the snap -> spring boundary
        // is velocity-continuous. Reset at every gesture start.
        var sawProgress = false
        var lastProgress = 0f
        var lastTimeMillis = 0L
        var progressVelocity = 0f

        override fun onBackStarted(event: NavigationEvent) {
            sawProgress = false
            lastProgress = 0f
            lastTimeMillis = 0L
            progressVelocity = 0f
            transitionActive = true
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
            // A progress event is only ever delivered for a gesture the platform
            // is animating itself, which makes this the first moment the platform
            // is known to be driving the axis. The container's own swipe stands
            // down for as long as it does — the gate reads this through
            // `platformGesture` in composition, so the library cancels a live
            // sequence of its own rather than the two sources trading writes.
            platformGesture = true
            sawProgress = true
            onGesture(event)
        }

        override fun onBackCompleted() {
            if (settled) return
            // navBackCompletionDecision: the platform already classified this
            // terminal callback as a completion and it is accepted regardless of
            // progress — unless the finger was flung hard back inward, the one
            // motion strong enough to override an OEM completion. That release
            // cancels instead of committing.
            if (isMiuix && sawProgress && progressVelocity <= -MIUIX_COMMIT_VELOCITY_THRESHOLD) {
                cancelBack()
                return
            }
            settled = true
            scope.launch {
                if (isMiuix) {
                    if (sawProgress) {
                        // Velocity-continuous commit: the shared settle spring
                        // (critical damping, stiffness 146, threshold 0.0025 of
                        // the depth axis = a screen width here), seeded with the
                        // release velocity. The no-overshoot cap mirrors
                        // `commitVelocityFloor`: a critically damped spring
                        // crosses its target once when the seed exceeds
                        // `√stiffness · remaining`, so only that excess — the
                        // speed that could ONLY become a visible bounce-back —
                        // is dropped. The remaining distance is read from the
                        // value the settle actually starts at: the launches queue
                        // FIFO on the main dispatcher, so the last finger snap
                        // has landed before this runs.
                        val remaining = (depth.value - EXIT_DEPTH).coerceAtLeast(0f)
                        val noOvershootCap = if (remaining <= 0f) {
                            0f
                        } else {
                            sqrt(MIUIX_SETTLE_STIFFNESS) * remaining
                        }
                        depth.animateTo(
                            targetValue = EXIT_DEPTH,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = MIUIX_SETTLE_STIFFNESS,
                                visibilityThreshold = MIUIX_DEPTH_VISIBILITY_THRESHOLD
                            ),
                            initialVelocity = progressVelocity.coerceAtMost(noOvershootCap)
                        )
                    } else {
                        // Discrete back key: no progress stream, so no velocity
                        // to continue — exactly the "from-rest, full-step
                        // programmatic" case, which settles on the fixed-duration
                        // curve (500 ms of the baked underdamped step response)
                        // rather than the live spring, pinning the total duration
                        // the way `settleProgrammatic` does.
                        depth.animateTo(
                            targetValue = EXIT_DEPTH,
                            animationSpec = tween(
                                durationMillis = MIUIX_PROGRAMMATIC_DURATION_MILLIS,
                                easing = MiuixNavProgrammaticEasing
                            )
                        )
                    }
                } else {
                    // Slide out under the tween from wherever the finger left the
                    // page, then hand over. `alreadySlidOut = true` lets the host
                    // suppress its own exit transition — the page is off screen
                    // already.
                    depth.animateTo(EXIT_DEPTH, tween(durationMillis = EXIT_MS))
                }
                onBack(true)
            }
        }

        override fun onBackCancelled() {
            if (settled) return
            // The platform's gesture is over — whatever drove it, the finger or
            // the key. The gate may open again for the container's own swipe from
            // here, including over the cancel settle below, which is exactly the
            // settling page the library is built to be grabbed on.
            platformGesture = false
            cancelBack()
        }

        private fun cancelBack() {
            // Drop the gesture-time capture so the resting read takes over again;
            // the state write itself recomposes this body, re-reading background()
            // from the now-current cache.
            gestureBackground = null
            scope.launch {
                if (isMiuix) {
                    // `settleCancel`: the same shared spring back to rest, with
                    // the rest position pinned as a bound so even this spring can
                    // never carry the page past rest into the wrong direction;
                    // bounds are cleared afterwards even if the settle is
                    // interrupted. No explicit velocity seed — the finger snaps
                    // zeroed the Animatable's own velocity, exactly as the
                    // driver's `snapToFinger` stream does upstream.
                    depth.updateBounds(lowerBound = null, upperBound = TOP_INDEX.toFloat())
                    try {
                        depth.animateTo(
                            targetValue = TOP_INDEX.toFloat(),
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = MIUIX_SETTLE_STIFFNESS,
                                visibilityThreshold = MIUIX_DEPTH_VISIBILITY_THRESHOLD
                            )
                        )
                    } finally {
                        depth.updateBounds(lowerBound = null, upperBound = null)
                    }
                    transitionActive = false
                } else {
                    // Spring back to rest from wherever the finger left it. The
                    // system reports no release velocity for a cancelled gesture,
                    // so the spring starts from rest; a non-bouncy spec cannot
                    // overshoot and flash the layer behind in the wrong direction.
                    depth.animateTo(
                        targetValue = TOP_INDEX.toFloat(),
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }
            }
        }

        private fun onGesture(event: NavigationEvent) {
            if (settled) return
            // The container's own swipe gesture is carrying this page: the
            // platform's events are another writer on the same axis, so they are
            // dropped for as long as the library owns the finger. They arrive on
            // the main thread, `swipe.owns` is written there, and this whole
            // path is main-thread bookkeeping already. Model — none upstream:
            // NavDisplay never runs both of its progress sources at once, it
            // turns the platform branch off while the gesture path is live.
            if (swipe.owns) return
            val extent = widthPx
            if (extent <= 0f) return
            // Release-velocity estimation, verbatim from NavDisplay's event
            // filter: the last two timestamped events divide their progress
            // delta by their frame delta. Platforms without usable frame timing
            // deliver `frameTimeMillis == 0` and are skipped, leaving the seed
            // at 0 — still a commit, just without momentum.
            if (event.frameTimeMillis > lastTimeMillis && lastTimeMillis != 0L) {
                progressVelocity =
                    (event.progress - lastProgress) * 1000f / (event.frameTimeMillis - lastTimeMillis)
            }
            if (event.frameTimeMillis != 0L) {
                lastProgress = event.progress
                lastTimeMillis = event.frameTimeMillis
            }
            // Devices have been seen misreporting progress past 1; the visual
            // math below assumes 0..1. Progress counts inward down the depth
            // axis, so it is subtracted from this page's index.
            val progress = event.progress.coerceIn(0f, 1f)
            snapDepth(TOP_INDEX - progress)
        }

        /**
         * Snaps [value] — the depth axis — to the frame beat, on the main thread
         * that the pointer gestures and the settle launches already share. A
         * `snapTo` arriving while one is still launching is dropped by the
         * Animatable itself, which is the behaviour wanted: the pointer stream
         * can outrun the frame it is being applied to.
         */
        private fun snapDepth(value: Float) {
            scope.launch { depth.snapTo(value) }
        }
    }

    DisposableEffect(dispatcher, isMiuix) {
        val driver = Driver()
        dispatcher.addHandler(driver)
        onDispose { driver.remove() }
    }

    // The library's commit settles the axis itself and reports the classification
    // at the release, not at rest; the pop is owed when the axis has actually got
    // there, which is also the order the platform path runs in — settle the slide
    // out, then hand the host the finished result, so the host can suppress its
    // own exit transition (`alreadySlidOut = true`). A platform-driven commit is
    // untouched by this: it dispatches from its own handler and never sets
    // `settling`.
    LaunchedEffect(depth) {
        snapshotFlow { depth.value }.collect { value ->
            if (swipe.settling && value <= EXIT_DEPTH + MIUIX_DEPTH_VISIBILITY_THRESHOLD) {
                swipe.settling = false
                swipe.owns = false
                onBack(true)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The app-level swipe, outermost so it reads the raw stream: the
            // container's own transition-time blackout consumes after it, and a
            // child that owns the finger is decided by the library's arbitration,
            // not by modifier order. `enabled` and `direction` both close when the
            // platform is driving — the gate above — and the direction is read from
            // the layout at the moment of the swipe, as the references do.
            .navSwipeDismiss(
                enabled = swipeDirection != NavSwipeDirection.None,
                direction = swipeDirection,
                animatedTop = depth,
                topIndex = TOP_INDEX,
                onCommit = {
                    // Synchronous with the release. The library's spring is the
                    // settle from here, so the pop waits for the axis (see the
                    // watcher above) instead of racing it.
                    swipe.settling = true
                },
                onCancel = {
                    // Fired once, after the library's cancel spring is back at
                    // rest — the mirror of the platform path's cancel: the
                    // transition-time treatments come off and the axis is
                    // released to the platform again.
                    swipe.owns = false
                    swipe.pictureTaken = false
                    swipe.settling = false
                    transitionActive = false
                },
                onGesture = { gesture ->
                    if (gesture != null) {
                        // A live follow event: this finger is moving this page, so
                        // the platform's stream stands down and the transition-time
                        // treatments are owed. The parent picture is borrowed here,
                        // once per gesture, rather than at the decide stage — the
                        // library reports only after it has claimed the sequence,
                        // so a gesture a pager or a list wins never pays for a
                        // snapshot retake. `flushForPreview` before the read: an
                        // appearance change defers its retake, and this is the last
                        // moment the picture can be fixed before it is shown.
                        if (!swipe.pictureTaken) {
                            WindowSnapshot.flushForPreview()
                            gestureBackground = background()
                            swipe.pictureTaken = true
                        }
                        swipe.owns = true
                        transitionActive = true
                    } else {
                        // The library's sequence has fully resolved. The commit
                        // branch resolves through the settle above, so this is a
                        // cancel or a claim that never moved; either way the axis
                        // is the platform's again.
                        swipe.owns = false
                    }
                }
            )
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .then(blockInputModifier)
    ) {
        // One frame's worth of the depth axis, read once and shared by every
        // layer below: `step` is the fraction of a full pop travelled — 0 at
        // rest, 1 with the resigning page clear of the edge — `progress` the
        // same value clamped, which is what the alphas and the shadow are keyed
        // on, and `offset` the unclamped displacement in pixels, the value these
        // layers have always skated on, left unclamped so a settle may still
        // round the edge with it. Read here rather than inside the layers
        // because the layers must agree on one frame's number.
        val step = TOP_INDEX - depth.value
        val progress = step.coerceIn(0f, 1f)
        val offset = step * widthPx

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
        // uncovered. Deriving everything from the depth axis inside
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
                    translationX = -forward * PARALLAX_FRACTION * (1f - progress) * widthPx
                    alpha = 1f - COVER_ALPHA_FALLOFF * (1f - progress)
                }
                .drawWithContent {
                    drawContent()
                    // `progress > 0f` guards rest: at rest the layer is hidden
                    // behind the page, and without the guard a wash at its
                    // deepest would show for one frame before the page
                    // settles.
                    if (progress > 0f) {
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
                            alpha = MAX_SCRIM_ALPHA * (1f - progress)
                        )
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
        // width with the depth axis — the system's progress or the swipe
        // gesture, whichever is driving this sequence — and that is ALL it does.
        //
        // No wash, no gradient, no elevation shadow on this layer. The shadow
        // belongs to the card below, and it is drawn there. Under the Miuix look
        // the sheet rides whole pixels — miuix-nav rounds `translationX` the same
        // way so the squircle mask's edge cannot shimmer between two pixel
        // alignments frame to frame; the M3 look keeps the raw value.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = if (isMiuix) {
                        offset.fastRoundToInt().toFloat() * forward
                    } else {
                        offset * forward
                    }
                    // The M3 look rounds the sheet with its own rectangle shape,
                    // only while off rest. The Miuix look clips through the
                    // squircle modifier appended below instead — same "only while
                    // transitioning" rule, a different mask.
                    if (!isMiuix) {
                        clip = offset > 0f
                        shape = RoundedCornerShape(cardCornerRadius)
                    }
                }
                .then(miuixClipModifier)
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

/** Duration of the commit slide under the Material look. The M3 "medium" expressive duration. */
private const val EXIT_MS = 220

/**
 * Corner radius of the sheet under the Material look, as a fraction of the screen
 * width.
 *
 * Measured off the reference implementation rather than chosen: KernelSU's
 * settings page (miuix-nav) traces a quarter circle whose horizontal travel is
 * 172 px on a 1200 px panel — 14.33%, fitted with an rms error of 1.7 px. A
 * fraction rather than a dp constant, so it tracks the reference on any panel;
 * a fixed dp was measured to fail because the arc's apex falls above the top of
 * the window and only its near-vertical tail is visible. The Miuix look clips at
 * the physical screen corner instead and never consumes this.
 */
private const val CARD_CORNER_FRACTION = 0.1433f

/**
 * Depth of every host of this container on miuix-nav's axis: `topIndex`, the
 * index of a single pushed page. This file works in that unit rather than in
 * pixels, because it is the unit the library's own swipe gesture drives — the
 * `Animatable` the settle physics animates is the same one the gesture snaps.
 */
private const val TOP_INDEX = 1

/**
 * Where a finished exit lands: on the previous page's index, one step down the
 * axis. In the pixel currency this file ran before, that was `offset → width`,
 * the departing page clear of the window edge; the layers below read this value
 * back into pixels unchanged.
 */
private const val EXIT_DEPTH = TOP_INDEX - 1f

/**
 * Canonical constants of miuix-nav's shared driver (`NavDriverSpec`), consumed by
 * the Miuix look's settle physics:
 *
 * - [MIUIX_SETTLE_STIFFNESS]: low stiffness on the depth scale. With critical
 *   damping and the tight threshold, a full one-step pop settles in roughly half
 *   a second — the established miuix navigation feel.
 * - [MIUIX_DEPTH_VISIBILITY_THRESHOLD]: the depth axis converges once within a
 *   few ten-thousandths of a step — the axis the settles run on here, so it is
 *   taken unscaled.
 * - [MIUIX_COMMIT_VELOCITY_THRESHOLD]: release velocity (progress-units per
 *   second) above which velocity alone decides; only a return fling stronger
 *   than this overrides an OEM completion.
 * - [MIUIX_PROGRAMMATIC_DURATION_MILLIS]: the fixed duration of the
 *   from-rest programmatic settle, identical for any distance.
 */
private const val MIUIX_SETTLE_STIFFNESS = 146f
private const val MIUIX_DEPTH_VISIBILITY_THRESHOLD = 0.0025f
private const val MIUIX_COMMIT_VELOCITY_THRESHOLD = 1.0f
private const val MIUIX_PROGRAMMATIC_DURATION_MILLIS = 500

/** Key of the transition-time input blackout, matching NavDisplay's `BLOCK_INPUT_KEY` role. */
private const val BLOCK_INPUT_KEY = "miuix-block-input-during-transition"

/**
 * The step response of an underdamped spring baked into an [Easing], played over
 * a fixed tween duration — a lift of miuix-nav's `NavSettleEasing`.
 *
 * This reproduces, point for point, the established enter/exit curve of miuix
 * navigation, so a discrete back-key commit keeps the exact same pacing: a brisk
 * middle (≈48% at a fifth of the duration) and a long, gentle tail. The curve is
 * monotone within `0..1` for the shipped parameters, so it never bounces;
 * `transform(1f)` lands at ≈0.99933 and the tween's final frame snaps the
 * residue. Gesture handoffs use the live spring instead because they must seed a
 * release velocity, which a tween cannot do.
 *
 * @param response oscillation period of the underlying spring, in units of the
 *   played duration.
 * @param damping damping ratio of the underlying spring (`< 1`, underdamped).
 */
private class MiuixSettleEasing(
    response: Float,
    damping: Float,
) : Easing {
    private val r: Float
    private val w: Float
    private val c2: Float

    init {
        val omega = 2.0 * PI / response
        val k = omega * omega
        val c = damping * 4.0 * PI / response

        w = (sqrt(4.0 * k - c * c) / 2.0).toFloat()
        r = (-c / 2.0).toFloat()
        c2 = r / w
    }

    override fun transform(fraction: Float): Float {
        val t = fraction.toDouble()
        val decay = exp(r * t)
        return (decay * (-cos(w * t) + c2 * sin(w * t)) + 1.0).toFloat()
    }
}

/**
 * The single programmatic settle curve (`response = 0.8`, `damping = 0.95`) —
 * miuix-nav's `NavProgrammaticEasing`, the default curve of a from-rest,
 * full-step programmatic pop.
 */
private val MiuixNavProgrammaticEasing: Easing =
    MiuixSettleEasing(response = 0.8f, damping = 0.95f)

/**
 * Reads the device screen corner radius — a lift of miuix-nav's
 * `rememberNavSystemCornerRadius`. Prefers the standard [RoundedCorner]
 * window-insets API (API 31+, bottom-left position); otherwise falls back to the
 * framework `rounded_corner_radius_bottom` dimen, and finally to `0.dp` for
 * flat-corner screens.
 */
@Composable
private fun rememberSystemScreenCornerRadius(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val view = LocalView.current
    val insets = view.rootWindowInsets
    val radiusPx = remember(context, view, insets) {
        val fromInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.takeIf { it > 0 }
        } else {
            null
        }
        fromInsets ?: bottomCornerRadiusFromResources(context)
    }
    return (radiusPx / density).dp
}

/** Framework `android`-package dimen fallback for the bottom screen corner radius; 0 when absent. */
@SuppressLint("DiscouragedApi")
private fun bottomCornerRadiusFromResources(context: Context): Int {
    val id = context.resources.getIdentifier("rounded_corner_radius_bottom", "dimen", "android")
    return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
}
