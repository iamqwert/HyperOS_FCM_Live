package io.github.howard20181.hyperos.fcmlive.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Launches [intent] from [activity], first photographing [activity]'s window so
 * [PredictiveBackContainer] on the page being opened has a previous layer to reveal.
 *
 * This is the single entry point every sub-page launch goes through, which is
 * what keeps the capture from being forgotten at one of the four call sites.
 * The launch always happens: the capture completes on the main thread in every
 * case (see [WindowSnapshot.capture]), and a failed capture only costs the
 * revealed strip its detail.
 */
internal fun Activity.startActivityWithSnapshot(intent: Intent) {
    // Whoever launches a child is that child's "parent" for reveal purposes —
    // recorded, not guessed: some pages (experiment features) open from more
    // than one window, and a hardcoded parent list goes stale the moment an
    // entry point moves.
    intent.component?.className?.let { child ->
        WindowSnapshot.recordParent(child, this.javaClass)
    }
    WindowSnapshot.capture(this) {
        startActivity(intent)
        // The photograph just taken was taken *during* the tap — that is what
        // launching from a tap means — so it carries the tap's own motion: the
        // press feedback still animating on the row, and the dimmed, half
        // dismissed panel of any menu the tap came out of. Both live on the
        // layer a predictive back reveals, so the page appears to come back
        // wearing its own press. Retake it once that motion has finished.
        WindowSnapshot.settle(this.javaClass)
    }
}

/**
 * Finishes a sub-page, choosing whether the system gets to animate the exit.
 *
 * [alreadySlidOut] comes from [PredictiveBackContainer]: after a committed back
 * the page is already off screen — its slide-out was drawn by the predictive
 * preview — so the platform's exit animation, which is authored for a window at
 * rest, would replay a second, unrelated "return" over an empty frame. Killing
 * it for that case (and only that case) is what removes the duplicate. A finish
 * that comes from the top-bar arrow while the page is still at rest still gets
 * the normal transition, because there the page really is still there to
 * animate.
 *
 * `overridePendingTransition(0, 0)` is deprecated from API 34 in favour of
 * `overrideActivityTransition`, which is why both are attempted: the old call
 * is what works on the older ROMs this module still supports, and the new one
 * is the only one honoured on 34+. Neither is fatal if a ROM ignores it — the
 * page simply keeps the platform default.
 */
internal fun Activity.finishPredictiveBack(alreadySlidOut: Boolean) {
    if (alreadySlidOut) {
        suppressExitTransition()
    }
    finish()
}

/**
 * Turns off this window's own enter/exit animation, on both the old and the new
 * API. Used for the case where the slide has already been drawn by hand.
 */
private fun Activity.suppressExitTransition() {
    @Suppress("DEPRECATION")
    overridePendingTransition(0, 0)
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        try {
            // OVERRIDE_TRANSITION_CLOSE is the "leaving" side. Entering the new
            // window is left alone, so arriving at the parent still fades in
            // normally instead of hard-cutting behind the outgoing page.
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "overrideActivityTransition unavailable", t)
        }
    }
}

/** One tag for the file: both the snapshot helper and the finish helper log. */
private const val TAG = "WindowSnapshot"

/**
 * How long a pending re-render waits before it runs, in milliseconds.
 *
 * Long enough to swallow a run of rapid menu picks and land after the row's
 * ripple has had its frames (M3's is about 300 ms end to end), short enough
 * that a user who picks once and immediately goes back still finds the new
 * picture ready. A back gesture cannot begin inside this window anyway: it starts
 * from the screen edge, a later input event than the tap that scheduled
 * this.
 */
private const val REVIVE_DELAY_MS = 320L

/**
 * How long after an appearance change a retaken picture is drawn again to
 * confirm it settled, in milliseconds.
 *
 * Switching the *look* (Miuix ↔ Material) composes a whole new branch inside
 * the stopped parent window, and the Miuix branch is far heavier to build
 * (first-time component init in that window) than the Material one. The first
 * retake at [REVIVE_DELAY_MS] can therefore photograph the swap mid-flight and
 * stamp a half-dressed picture as current — exactly what a back preview then
 * reveals. While inside this window, [revive] redraws every retaken entry once
 * more per pass, so the cache lands on the fully dressed tree a few hundred
 * milliseconds later. Palette-only changes ride the same passes harmlessly:
 * redrawing a settled page is idempotent.
 */
private const val CONFIRM_WINDOW_MS = 1200L

/**
 * How long the read-back of an offscreen GPU render may take before the retake
 * gives up and falls back to the software draw, in milliseconds. The frame is
 * already queued when the copy starts (the render request waited for present),
 * so this only bounds a GPU readback, not composition work.
 */
private const val COPY_TIMEOUT_MS = 400L

/**
 * How long after a child is launched the parent's photograph is retaken, in
 * milliseconds.
 *
 * Long enough to clear the two things the first photograph catches mid-flight:
 * the press feedback on the row that was tapped (the longest of them run a few
 * hundred milliseconds) and the dismissal of any popup the tap came out of.
 * Short enough that a user who launches a page and comes straight back still
 * finds the retaken picture — the back gesture takes longer to start than this.
 */
private const val SETTLE_DELAY_MS = 480L

/**
 * Snapshots of a page, kept so the page *above* it has something to reveal
 * while a predictive back is in flight.
 *
 * Why this exists at all: the preview in [PredictiveBackContainer] is supposed
 * to show the previous page sliding out from under the current one, and the
 * navigation layer this module models it on gets that for free — its navigation
 * keeps the previous entry composed. Here every page is its own
 * `Activity`, so the moment the next one is resumed the previous window is
 * gone and there is nothing left to reveal. The only way to have a previous
 * layer at that point is to have photographed it beforehand.
 *
 * **When to photograph.** Not on `onPause`: by then the window may already be
 * covered, so the capture would come back blank or half-drawn. The snapshot is
 * taken on *demand*, immediately before a child page is launched, while the
 * parent is still the visible, fully-drawn window. [capture] does exactly that
 * and caches the result against the Activity that asked for it.
 *
 * **Failure is not fatal.** `PixelCopy` needs a real surface and a live window;
 * on the wrong thread, on a hardware surface that refuses the copy, or when the
 * window is going away it returns an error. Every path here falls back to
 * "no snapshot" rather than throwing, and [PredictiveBackContainer] draws its
 * container colour in that case — the animation still works, the revealed layer
 * is simply flat.
 *
 * **One slot per parent, not one slot overall.** The pages nest — Main opens
 * About, About opens Licenses — and a single slot loses the outer page's
 * picture the moment the inner page takes a new one: going back from Licenses to
 * About worked, and then going back from About to Main revealed nothing,
 * because the only cached picture was About's, taken when Licenses was opened,
 * and it did not match Main. Keeping a slot per Activity class means every level
 * of the stack keeps its own picture for as long as something can still return
 * to it, which is what the nesting actually requires.
 */
internal object WindowSnapshot {

    /**
     * The previous page's window, as it looked when a child was launched,
     * keyed by that page's Activity class name.
     *
     * A map rather than a single entry because the pages stack (see the note
     * above): each level needs its own picture, and a shared slot would have the
     * inner page overwrite the outer one's.
     */
    private val cache = HashMap<String, Entry>()

    /**
     * Which Activity last launched which child, child class name → parent
     * class name. The reveal's "previous page" is whoever actually opened the
     * current one — recorded at launch time by [startActivityWithSnapshot],
     * because a page can be reachable from more than one window and no static
     * parent list survives those entry points moving.
     */
    private val parentOfChild = HashMap<String, String>()

    /** Guards [parentOfChild]; the map itself lives on the main thread only. */
    private val parentOfLock = Any()

    /**
     * The picture of whoever launched [childClass], taken on the way in — the
     * layer a predictive back on that child reveals. Null when the child was
     * not launched through [startActivityWithSnapshot] or nothing is cached.
     */
    fun forLaunchingParent(childClass: Class<out Activity>): ImageBitmap? {
        val parentName = synchronized(parentOfLock) { parentOfChild[childClass.name] }
            ?: return null
        val entry = cache[parentName] ?: return null
        if (entry.generation != ThemeEngine.generation) return null
        return entry.bitmap
    }

    /** Records that [parentClass] launched the child named [childClassName]. */
    fun recordParent(childClassName: String, parentClass: Class<out Activity>) {
        synchronized(parentOfLock) { parentOfChild[childClassName] = parentClass.name }
    }

    /**
     * Where the coalesced re-render is posted. Main looper, because the redraw
     * touches a live view tree and must not run on a background thread.
     */
    private val handler = Handler(Looper.getMainLooper())

    /** One parent's photograph, plus what is needed to remake it. */
    private class Entry(
        /** The parent's window as it looked when a child was launched. */
        var bitmap: ImageBitmap,
        /**
         * The window the picture was taken from, held weakly, so it can be
         * *retaken* when the palette changes rather than thrown away.
         *
         * Discarding on a theme change was the first fix here and it was wrong:
         * the revealed strip then fell back to the container colour, which is
         * this page's own background, so a back preview showed a flat panel with
         * no sign of the page being returned to. Holding the parent is what
         * allows the picture to be brought up to date instead of dropped.
         *
         * Weak, because this object outlives every page and a strong reference
         * would pin a finished Activity's whole view tree. A collected parent
         * simply means no re-render — the same fallback as before.
         */
        val parent: WeakReference<Activity>,
        /**
         * [ThemeEngine.generation] at the moment the picture was taken.
         *
         * A photograph records the colours that were on screen, not a colour
         * scheme, so it goes stale the instant the theme changes (see
         * [ThemeEngine.generation]). Reading this back is what tells
         * [forParent] a picture needs retaking before it is handed over.
         */
        var generation: Int,
        /**
         * Set by [refreshForTheme] when an appearance change invalidates this
         * picture, and consumed by [flushForPreview] at the start of the next
         * back gesture.
         *
         * It exists because the coalesced [revive] retake runs on a fixed timer
         * and can photograph the stopped page while its recomposition / layer
         * re-record is still in flight on the global frames, which left the
         * large title blank yet stamped the entry as current — so a plain
         * generation check would skip it at gesture start and lock the blank in.
         * The flag forces one more, by-then-settled retake exactly when the
         * picture is about to be revealed. Also re-set by [rerender]'s failure
         * paths, so a retake that could not run (unlaid-out window, failed
         * allocation) is retried at the next gesture start instead of being
         * locked in as "current". Not part of any identity comparison.
         */
        var wantsForcedFlush: Boolean = false,
        /**
         * Set by [revive] while inside the confirmation window after an
         * appearance change: the pass just redrew this entry, and one more pass
         * should follow even though the generation check now passes — the first
         * draw after a look swap can photograph the branch mid-swap (see
         * [revive]), and only a second, later draw is guaranteed settled.
         */
        var wantsConfirmation: Boolean = false
    )

    /**
     * What the child page reads when composing.
     *
     * Keyed by class name rather than by instance on purpose: the child is a
     * separate `Activity`, so it cannot hold a reference to the parent object
     * without leaking it. All it needs to know is "is there a cached picture of
     * the page I came from", and a class name answers that exactly — including
     * across a configuration change, where the Activity instance is new but the
     * picture on screen is still the right one.
     *
     * The retake itself is **not** done here: this runs during composition, and
     * forcing a full `View.draw` from inside composition is both expensive on
     * the frame it lands and reentrancy-prone. [refreshForTheme] does it, from
     * the moment the theme actually changes. This only guards the handover, so a
     * picture that somehow outran the refresh is refused rather than served
     * stale.
     */
    fun forParent(parentClass: Class<out Activity>?): ImageBitmap? {
        if (parentClass == null) return null
        val entry = cache[parentClass.name] ?: return null
        if (entry.generation != ThemeEngine.generation) return null
        return entry.bitmap
    }

    /**
     * Retakes every cached picture in the current palette. Call after
     * [ThemeEngine.invalidate].
     *
     * Remaking the pictures instead of discarding them is the whole point: the
     * revealed strip must keep showing the page being returned to, and it must
     * do so in the colours the user just chose. Dropping them was the first
     * attempt here and it produced a flat panel — the fallback colour is this
     * page's own background, so the strip went blank and the gesture lost its
     * meaning.
     *
     * All slots, not just one: the user can change the theme from a page that
     * has several pages beneath it, and every one of them has to come back in
     * the new palette — the next back preview only reveals the immediate parent, but
     * the one after that reveals the one below it.
     *
     * Called from the one place a theme change is initiated rather than from
     * [ThemeEngine] itself, which would invert the layering (theme reaching into
     * ui). A no-op when nothing is cached, so every appearance switch can call
     * it unconditionally.
     *
     * **The work is posted, not done here.** Re-rendering is a synchronous
     * `View.draw` of a full-screen bitmap — tens of milliseconds of main-thread
     * rasterisation plus a detach/re-attach of the whole view tree. This is
     * reached from a menu row's `onClick`, so doing it inline spends that time
     * inside the tap callback and starves the row's own ripple animation: the
     * user picks options quickly and the ripple visibly fails to keep up. The
     * picture is only needed the next time a back preview starts, which is at
     * earliest after all input has settled, so there is nothing to gain by
     * finishing it here.
     *
     * **And coalesced.** Flicking through the appearance menus fires this once
     * per choice; each call cancels the pending one so a rapid run costs a
     * single re-render at the end rather than one per tap. What is drawn is
     * therefore the palette the user actually stopped on.
     */
    fun refreshForTheme() {
        if (cache.isEmpty()) return
        var stale = false
        for (entry in cache.values) {
            if (entry.generation != ThemeEngine.generation) {
                // Remember this picture must be re-taken once more at gesture
                // start even if the timed revive below stamps it current early.
                entry.wantsForcedFlush = true
                // Let the stopped page run frames now: its appearance colours are
                // animated (the top-bar title colour tweens), and a stopped
                // window's frame clock is paused, freezing those animations at
                // the old value. Resuming lets the foreground-driven frames play
                // them out during the wait below, so the retake records the new
                // palette whole rather than a title in the old colour.
                entry.parent.get()?.let { resumeFrameClock(it) }
                stale = true
            }
        }
        if (!stale) return
        lastSwitchAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(revive)
        handler.postDelayed(revive, REVIVE_DELAY_MS)
    }

    /**
     * Retakes the page that was just left, once the tap that left it has settled.
     *
     * The photograph taken on the way out is taken *during* the tap, so it
     * catches whatever the tap set in motion — the press feedback on the row
     * itself, and the panel of a menu mid-dismissal with the page already dimmed
     * behind it. Those are transient; the page is not. A few hundred
     * milliseconds later the same redraw a theme change uses produces the page
     * and nothing else, from the retained tree ([rerender]'s stopped-window
     * case, which is exactly what the parent is by then).
     *
     * One target at a time: a launch is a single event, and the last one is the
     * only one whose picture can still be revealed without another launch
     * between.
     */
    fun settle(parentClass: Class<out Activity>) {
        val name = parentClass.name
        if (!cache.containsKey(name)) return
        settleTarget = name
        handler.removeCallbacks(settleRun)
        handler.postDelayed(settleRun, SETTLE_DELAY_MS)
    }

    /** [settle]'s target — the entry the pending retake belongs to. */
    private var settleTarget: String? = null

    /**
     * When the most recent appearance change was marked, in uptime millis.
     *
     * [revive] compares this against now to decide whether a retaken picture
     * still owes a confirmation pass (see [CONFIRM_WINDOW_MS]).
     */
    private var lastSwitchAt = 0L

    /** The body of [settle]; runs once the tap's motion has finished. */
    private val settleRun = Runnable {
        val name = settleTarget ?: return@Runnable
        settleTarget = null
        val entry = cache[name] ?: return@Runnable
        // The tap that launched the child left this page's press feedback
        // mid-fade, and the window's frame clock is paused while the page is
        // stopped (see [resumeFrameClock]) — the fade froze at a visible alpha,
        // which is what the first photograph and the retained window's recorded
        // display list still hold. Resume the clock so the fade finishes on the
        // foreground-driven frames, then retake once it has: the re-recorded
        // display list is what gets photographed here, and what the live window
        // replays when the user comes back. Same resume-then-wait the appearance
        // change uses ([refreshForTheme]).
        val parent = entry.parent.get()
        if (parent != null) resumeFrameClock(parent)
        // Push one real frame into the retained surface while the tap's motion
        // has finished. The buffer the system re-presents on return is the last
        // frame this window drew — the tap itself, on ROMs that stop drawing a
        // window the moment a child covers it — so the reveal opened on a press
        // frozen at full bloom over a mesh phase older than the preview's
        // photograph: the live page visibly jumping backward for a frame or two
        // before its first real draw. This invalidation asks the stopped window
        // for exactly one more frame, now that the press has faded and the mesh
        // is settled: where the ROM honours it, the retained buffer becomes the
        // very frame the preview shows and the takeover is seamless; where it
        // refuses to draw covered windows, this is a no-op and nothing is lost.
        if (parent != null) {
            try {
                parent.window?.decorView?.invalidate()
            } catch (_: Throwable) {
            }
        }
        handler.postDelayed({ rerender(name, entry, dropIfVisible = false) }, REVIVE_DELAY_MS)
    }

    /**
     * The coalesced body of [refreshForTheme]; runs once input has settled.
     *
     * Beyond refreshing stale entries this is also the confirmation pass for a
     * look switch: swapping the style composes a whole new branch inside the
     * stopped window, and the branch swapped *into* MIUIX is far heavier to
     * build than the one swapped into Material (first-time Miuix component
     * init in that window) — the first retake at [REVIVE_DELAY_MS] can catch
     * the swap mid-flight and stamp a half-dressed picture as current. While
     * inside [CONFIRM_WINDOW_MS] of the switch, every retaken entry is drawn
     * again one pass later, so the cache settles on the fully dressed tree.
     */
    private val revive: Runnable = Runnable {
        if (cache.isEmpty()) return@Runnable
        // Snapshot the keys first: a failed re-render removes its own entry.
        for (name in cache.keys.toList()) {
            val entry = cache[name] ?: continue
            val current = entry.generation == ThemeEngine.generation
            if (current && !entry.wantsConfirmation) continue
            val retaken = rerender(name, entry)
            entry.wantsConfirmation = false
            if (SystemClock.uptimeMillis() - lastSwitchAt < CONFIRM_WINDOW_MS) {
                // Within the window the next pass redraws again regardless of
                // generation, so a mid-swap picture cannot be the last word.
                entry.wantsConfirmation = true
            } else if (retaken) {
                // A settled pass, past the window in which the swap could have
                // been caught mid-flight: this picture is already the one
                // [flushForPreview]'s forced pass exists to produce, so the next
                // gesture no longer owes it. Leaving the flag set here cost a
                // full-screen render on the gesture's first frame — one that
                // reproduced, pixel for pixel, a picture this pass had just made.
                // A failed retake keeps the flag, which is the retry it is for.
                entry.wantsForcedFlush = false
            }
        }
        if (cache.values.any { it.wantsConfirmation }) {
            handler.removeCallbacks(revive)
            handler.postDelayed(revive, REVIVE_DELAY_MS)
        }
    }

    /**
     * Puts every picture in its best state **now**, on the frame a reveal starts.
     *
     * For the one caller that cannot wait: a page is about to be left, and the
     * picture of the page underneath is about to be needed for the back preview.
     * [refreshForTheme] and [settle] deliberately defer their work so a tap does
     * not pay for it, but "later" must not become "after the reveal has already
     * started" — a strip one palette behind, or still carrying the press, is the
     * whole bug those two exist to fix.
     *
     * The stale sweep at the end is the repair of last resort. A retake whose
     * timer fired and failed, or that had not been scheduled at all, leaves a
     * picture behind the current palette; [forParent] refuses those, so the
     * preview would come up flat. This is the last moment before that picture is
     * needed, so it is retaken here instead of being refused.
     *
     * A no-op when everything is already current, so callers may run it
     * unconditionally; cheap in that case: two `hasCallbacks` and a map walk over
     * at most four entries.
     */
    fun flushForPreview() {
        val startedAt = SystemClock.uptimeMillis()
        // A pass still on its timer is run here rather than left to it, because it
        // is the batched form of the retakes the loop below would otherwise do one
        // entry at a time — running it first is what keeps an entry from being
        // drawn twice for one gesture.
        val drained = handler.hasCallbacks(revive) || handler.hasCallbacks(settleRun)
        if (handler.hasCallbacks(revive)) {
            handler.removeCallbacks(revive)
            revive.run()
        }
        if (handler.hasCallbacks(settleRun)) {
            handler.removeCallbacks(settleRun)
            settleRun.run()
        }
        var retaken = 0
        for (name in cache.keys.toList()) {
            val entry = cache[name] ?: continue
            // A picture invalidated by an appearance pick is re-taken here even
            // when the timed revive already stamped it current: that pass can
            // have caught the stopped page mid-recompose with its title layer
            // blank. By gesture start the composition is settled, so the forced
            // retake records it whole. The flag is one-shot.
            val force = entry.wantsForcedFlush
            entry.wantsForcedFlush = false
            val current = entry.generation == ThemeEngine.generation
            if (!force && current && !entry.wantsConfirmation) continue
            // Whatever the reason for the retake, the stopped page's clock must
            // run for its composition to be current before the draw below.
            entry.parent.get()?.let { resumeFrameClock(it) }
            entry.wantsConfirmation = false
            if (rerender(name, entry)) retaken++
        }
        // Everything retaken here is a full-screen render — a GPU record, a read
        // back and a double measure/layout — run synchronously inside the gesture
        // callback, before the page has moved a pixel. This one line is how that
        // cost is told apart from the travel itself: silent means the gesture's
        // first frame was free, and the numbers say how much it paid when not.
        if (retaken > 0 || drained) {
            Log.i(
                TAG,
                "Gesture-start flush retook $retaken picture(s) in ${
                    SystemClock.uptimeMillis() - startedAt
                } ms (drained pass: $drained)"
            )
        }
    }

    /**
     * Resumes [activity]'s composition frame clock if it has been paused.
     *
     * A stopped Compose window pauses its Recomposer frame clock (see
     * `createLifecycleAwareWindowRecomposer`'s `ON_STOP`), so appearance
     * animations such as the top-bar title colour tween freeze at their old
     * value and a software retake would record the title in the old colour —
     * near-invisible against the new background. The Recomposer is keyed off the
     * window root; resuming lets the (foreground-driven, shared main-thread)
     * Choreographer frames advance those animations while the page stays
     * stopped. Idempotent and failure-tolerant: anything unexpected just leaves
     * the clock as the lifecycle left it.
     */
    private fun resumeFrameClock(activity: Activity) {
        try {
            val root = activity.window?.decorView ?: return
            // The Recomposer tag is installed on the window's *content child*
            // (a descendant of the decor view), and findViewTreeCompositionContext
            // only searches upward, so starting at decorView misses it. Walk the
            // tree until a view exposes one.
            val recomposer = findRecomposer(root) ?: return
            recomposer.resumeCompositionFrameClock()
            Log.i(TAG, "Resumed stopped composition frame clock")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resume the composition frame clock", t)
        }
    }

    /** Depth-first search for the window's [Recomposer], starting at [view]. */
    private fun findRecomposer(view: View): Recomposer? {
        (view.findViewTreeCompositionContext() as? Recomposer)?.let { return it }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findRecomposer(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * Redraws the retained parent's window in the current palette.
     *
     * Returns `false` when there is nothing to redraw from — the parent has been
     * collected, or its view has no size — in which case the cached picture is
     * dropped so [forParent] falls back to a flat strip rather than serving the
     * old palette.
     *
     * `view.draw(Canvas)` is the mechanism, deliberately, and not `PixelCopy`:
     * the parent is stopped and its surface is gone, so `PixelCopy` has nothing
     * to read. The software redraw does not need a surface — the view tree is
     * still laid out and still holds the composed Compose nodes, so it paints
     * the page as it stands.
     *
     * **The retained composition is already current when this runs.** The
     * generation bump invalidates the stopped page, and — unlike rendering —
     * recomposition keeps running on the global, shared Recomposer while the
     * page is stopped (the foreground page drives those frames). Re-measuring the
     * still-attached view rebuilds its display list from that recomposed state,
     * and the draw that follows records the new colours without ever detaching the
     * tree. It runs only when an appearance setting actually changes and only
     * while a snapshot exists to refresh.
     */
    private fun rerender(name: String, entry: Entry, dropIfVisible: Boolean = true): Boolean {
        val parent = entry.parent.get()
        val view = parent?.window?.decorView
        if (parent == null || view == null) {
            // Gone for good, so this slot can never be refreshed again. Dropping
            // it lets forParent fall back to a flat strip instead of serving a
            // picture in the old palette.
            cache.remove(name)
            return false
        }
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) {
            // Alive but not laid out — a stopped window between passes, or one a
            // previous retake left detached before it could be measured. There is
            // nothing to measure against *yet* and the page is still there, so
            // the picture is kept and stamped as the best available rather than
            // dropped: dropping here is how a preview went missing for good.
            // The stamp is not a clean bill of health, though — ask for one more
            // forced retake at the next gesture start, by which time the window
            // will have been measured at least once.
            entry.generation = ThemeEngine.generation
            entry.wantsForcedFlush = true
            return false
        }
        // Never redraw a page the user is standing on.
        //
        // A cached picture is only ever *revealed* by a child page's back preview,
        // and while its own page is on screen no child can be up there — so this
        // slot is unreachable right now, and it is re-photographed by [capture]
        // the next time a child is launched from it. Dropping it is free.
        //
        // Redrawing it is not. Even a non-destructive re-measure on a live window
        // costs a blank frame (the flash someone sees on the way back from a
        // third-level page) and can fight the gesture for the main thread; an
        // earlier destructive version also reset every plain `remember` on the
        // page — including, once the rebuild landed under the finger, where the
        // list is scrolled to. It is a stopped window's tree that can be brought
        // forward and painted on demand; a resumed one has no reason to be
        // touched at all.
        if (isOnScreen(parent)) {
            // The settle retake does not get to make this call: it runs while
            // the page it is retaking has only just been left, and dropping the
            // picture there would trade a photograph that is one press too
            // lively for no photograph at all. It keeps what it has.
            if (!dropIfVisible) {
                entry.generation = ThemeEngine.generation
                return false
            }
            cache.remove(name)
            return false
        }
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot allocate snapshot bitmap for retake", t)
            // Keep the picture we have and let the preview use it: a page in last
            // week's colours beats a flat panel, and the next capture replaces it.
            // Ask for another forced retake at the next gesture start — the
            // allocation may well succeed there.
            entry.generation = ThemeEngine.generation
            entry.wantsForcedFlush = true
            return false
        }
        // Guarded end to end. This runs from a main-looper Runnable, and an
        // exception escaping one does not "fail a retake", it kills the app —
        // the one thing this file promises a snapshot may never do. A failed
        // redraw also must not be *stored*: a freshly allocated bitmap is
        // transparent, and storing one is how the preview turned into a blank
        // panel, which reads as "there is no previous page" rather than as a
        // picture that needs another try.
        return try {
            // No detach/reattach: the generation bump already invalidated the
            // retained composition, and a stopped Compose view keeps recompose on
            // the *global* frame loop (the Recomposer is shared and the foreground
            // page drives those frames), so by the time this runs its colours are
            // current. Re-measuring and laying out here only rebuilds the display
            // list from that already-recomposed state; drawing an unmeasured tree
            // would paint nothing.
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            // Two passes to converge: the first layout can itself fire
            // onSizeChanged (the large-title height is reported during placement)
            // and re-dirty the bar; the second pass lays the settled tree out, so
            // the title layer is whole when recorded. Idempotent, and this whole
            // function only runs on an appearance change.
            view.measure(widthSpec, heightSpec)
            view.layout(view.left, view.top, view.left + width, view.top + height)
            view.measure(widthSpec, heightSpec)
            view.layout(view.left, view.top, view.left + width, view.top + height)
            if (!redraw(view, bitmap)) {
                Log.w(TAG, "Retake drew nothing; keeping the previous picture")
                entry.generation = ThemeEngine.generation
                entry.wantsForcedFlush = true
                return false
            }
            entry.bitmap = bitmap.asImageBitmap()
            entry.generation = ThemeEngine.generation
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Retake failed; keeping the previous picture", t)
            entry.generation = ThemeEngine.generation
            entry.wantsForcedFlush = true
            false
        }
    }

    /**
     * True while [activity] is the page the user is looking at.
     *
     * `RESUMED`, not `STARTED`: the sub-page windows are translucent
     * (Theme.HyperFCMLive.Translucent), so a parent under a child page is
     * visible to the window manager and sits at `STARTED` — but it is not the
     * page in use, and a parent under a child is exactly the one this file is
     * allowed to repaint. Before those windows were translucent the covered
     * parent dropped to `CREATED`, which is the state this check used to name.
     * A host this cannot classify (no `LifecycleOwner`, which only a
     * non-ComponentActivity would be) counts as *not* on screen, so the
     * ordinary redraw still happens for it rather than silently losing the
     * strip.
     */
    private fun isOnScreen(activity: Activity): Boolean {
        val state = (activity as? LifecycleOwner)?.lifecycle?.currentState ?: return false
        return state.isAtLeast(Lifecycle.State.RESUMED)
    }

    // No force-recompose step here any more. An earlier version detached and
    // re-attached the retained content view to force a synchronous recompose of a
    // stopped page. That is both unnecessary and unsafe: a stopped Compose view
    // keeps recompose on the global, shared Recomposer (the foreground page drives
    // the frames), so the generation bump has already brought its colours current
    // by the time [rerender] measures — and a detach/reattach disposes the whole
    // composition, discarding every plain `remember` (e.g. the list's scroll
    // position when it is not held saveably) and racing the platform back gesture,
    // which produced a missing preview and, on some ROMs, a native crash during the
    // forced re-attach/draw. Re-measuring the still-attached view rebuilds its
    // display list from the already-recomposed state without any of that.

    /**
     * Photograph [activity]'s window and cache it, then run [onCaptured] on the
     * main thread.
     *
     * Called right before the child is launched, so the parent is still
     * resumed and drawn. The callback runs on every path — success, failure, or
     * a window that cannot be copied — so the caller can launch the child
     * regardless; the only difference is whether the child has a strip to
     * reveal.
     */
    fun capture(activity: Activity, onCaptured: () -> Unit) {
        val view: View = activity.window?.decorView ?: run {
            onCaptured()
            return
        }
        if (view.width <= 0 || view.height <= 0) {
            onCaptured()
            return
        }

        val bitmap = try {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot allocate snapshot bitmap", t)
            onCaptured()
            return
        }

        // PixelCopy is the supported way to read a window's surface; a plain
        // `view.draw(Canvas)` misses anything the surface holds that the view
        // hierarchy does not paint itself. Both are attempted, because a
        // hardware-backed window can refuse PixelCopy while the software
        // redraw still succeeds.
        val handler = Handler(Looper.getMainLooper())
        try {
            PixelCopy.request(activity.window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    store(activity, bitmap)
                } else {
                    Log.w(TAG, "PixelCopy failed with result=$result; redrawing instead")
                    if (redraw(view, bitmap)) {
                        store(activity, bitmap)
                    }
                }
                onCaptured()
            }, handler)
        } catch (t: Throwable) {
            // Thrown when the window has no surface to copy (already finishing,
            // no hardware renderer, ...). The redraw is a real fallback, not a
            // placeholder — it is what the old View-based screenshot did.
            Log.w(TAG, "PixelCopy unavailable; redrawing instead", t)
            if (redraw(view, bitmap)) {
                store(activity, bitmap)
            }
            onCaptured()
        }
    }

    /**
     * Forgets every snapshot, or just one Activity class's if one is named.
     *
     * The named form is what a page calls from its own `onDestroy` when it is
     * finishing for real: nothing will open from it again, so its picture is
     * dead weight pinning its view tree. Pages that stay on the stack keep
     * theirs, which is the whole point of the per-class slots.
     */
    fun clear(parentClass: Class<out Activity>? = null) {
        if (parentClass == null) {
            cache.clear()
        } else {
            cache.remove(parentClass.name)
        }
    }

    private fun store(activity: Activity, bitmap: Bitmap) {
        cache[activity.javaClass.name] = Entry(
            bitmap = bitmap.asImageBitmap(),
            parent = WeakReference(activity),
            // Stamped with the palette in force right now, so forParent can tell
            // a current picture from one that needs retaking.
            generation = ThemeEngine.generation
        )
    }

    /**
     * Paints [view] into [bitmap], and says whether anything came of it.
     *
     * The flag matters: a caller that stores a bitmap it did not manage to draw
     * into is storing a transparent rectangle, and a transparent rectangle on the
     * layer a back preview reveals is indistinguishable from "there is no page
     * underneath" — a flat panel. Failure has to leave the previous picture in
     * place instead.
     *
     * The primary path is GPU, not software: the view tree is recorded into a
     * [RenderNode] and replayed by a [HardwareRenderer] into an [ImageReader],
     * whose RGBA pixels fill [bitmap]. This is not an optimisation — it is the
     * only path that can draw the app at all. The views run AGSL [android.graphics.RuntimeShader]s
     * (the title bars' Haze progressive mask, the home glass), and a software
     * canvas throws `IllegalArgumentException: Software rendering doesn't
     * support RuntimeShader` the moment one of them is on the recorded branch:
     * the whole retake failed, the stale picture survived every pass, and a
     * look switch surfaced the *previous* look in the back preview. Recording
     * into a RenderNode and replaying on the GPU runs every shader the way the
     * real window runs it. The software `view.draw` remains as a fallback for
     * the device classes where the hardware pipeline is unavailable, since a
     * last-week-colours page still beats a flat panel.
     */
    private fun redraw(view: View, bitmap: Bitmap): Boolean {
        if (renderHardware(view, bitmap)) return true
        return try {
            view.draw(Canvas(bitmap))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Redraw fallback failed too", t)
            false
        }
    }

    /**
     * The GPU half of [redraw]: record, replay, read back.
     *
     * Everything is confined to the calling (main-looper) thread up to the
     * render; the read back runs through [PixelCopy], which waits the frame
     * fence itself, converts the pixel format, and fills the software bitmap —
     * the manual plane-buffer copy this replaced produced garbage on device
     * (whole-frame noise, success reported). The copy completes on a private
     * background looper and this thread blocks on the latch, bounded by
     * [COPY_TIMEOUT_MS]: the frame is already queued (that is what
     * `setWaitForPresent(true)` on the render request buys), so the copy is a
     * short GPU readback, not a wait on composition work that needs the main
     * thread.
     */
    private fun renderHardware(view: View, bitmap: Bitmap): Boolean {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return false
        val reader = try {
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                1,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Hardware retake: cannot create ImageReader", t)
            return false
        }
        val renderer = HardwareRenderer()
        return try {
            // Recording, not drawing: `view.draw(recordingCanvas)` captures the
            // view's paint ops (shaders included) into the node; the renderer
            // replays them on the GPU exactly as the window's own pipeline does.
            val node = RenderNode("WindowSnapshot")
            node.setPosition(0, 0, width, height)
            val canvas = node.beginRecording()
            view.draw(canvas)
            node.endRecording()

            renderer.setLightSourceAlpha(0f, 0f)
            renderer.setLightSourceGeometry(0f, 0f, 0f, 0f)
            renderer.setContentRoot(node)
            renderer.setSurface(reader.surface)
            val request = renderer.createRenderRequest()
            request.setWaitForPresent(true)
            if (request.syncAndDraw() != HardwareRenderer.SYNC_OK) {
                Log.w(TAG, "Hardware retake: render did not succeed")
                return false
            }
            // Read the queued frame back through PixelCopy from the reader's
            // own surface: "the most recently queued buffer", fenced and
            // converted into the software bitmap by the framework.
            val latch = CountDownLatch(1)
            val status = intArrayOf(PixelCopy.ERROR_UNKNOWN)
            PixelCopy.request(
                reader.surface,
                bitmap,
                { result ->
                    status[0] = result
                    latch.countDown()
                },
                Handler(copyThread.looper)
            )
            if (!latch.await(COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Hardware retake: pixel copy timed out")
                return false
            }
            if (status[0] != PixelCopy.SUCCESS) {
                Log.w(TAG, "Hardware retake: pixel copy failed with status=${status[0]}")
                return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Hardware retake failed", t)
            return false
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    /** One idle worker thread for PixelCopy completion callbacks; never quits. */
    private val copyThread: HandlerThread by lazy {
        HandlerThread("WindowSnapshotCopy").apply { start() }
    }
}
