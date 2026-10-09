package io.github.howard20181.hyperos.fcmlive.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.howard20181.hyperos.fcmlive.theme.ThemeEngine
import java.lang.ref.WeakReference

/**
 * Launches [intent] from [activity], first photographing [activity]'s window so
 * [SwipeBackContainer] on the page being opened has a previous layer to reveal.
 *
 * This is the single entry point every sub-page launch goes through, which is
 * what keeps the capture from being forgotten at one of the four call sites.
 * The launch always happens: the capture completes on the main thread in every
 * case (see [WindowSnapshot.capture]), and a failed capture only costs the
 * revealed strip its detail.
 */
internal fun Activity.startActivityWithSnapshot(intent: Intent) {
    WindowSnapshot.capture(this) { startActivity(intent) }
}

/**
 * Finishes a sub-page, choosing whether the system gets to animate the exit.
 *
 * [alreadySlidOut] comes from [SwipeBackContainer]: after a committed swipe the
 * page is already off screen, so the platform's exit animation — which is
 * authored for a window at rest — would replay a second, unrelated "return"
 * over an empty frame. Killing it for that case (and only that case) is what
 * removes the duplicate. A back press that never moved the page still gets the
 * normal transition, because there the page really is still there to animate.
 *
 * `overridePendingTransition(0, 0)` is deprecated from API 34 in favour of
 * `overrideActivityTransition`, which is why both are attempted: the old call
 * is what works on the older ROMs this module still supports, and the new one
 * is the only one honoured on 34+. Neither is fatal if a ROM ignores it — the
 * page simply keeps the platform default.
 */
internal fun Activity.finishSwipeBack(alreadySlidOut: Boolean) {
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
 * that a user who picks once and immediately swipes back still finds the new
 * picture ready. A swipe-back cannot start inside this window anyway: it begins
 * with a touch-down, which is a later input event than the tap that scheduled
 * this.
 */
private const val REVIVE_DELAY_MS = 320L

/**
 * Snapshots of a page, kept so the page *above* it has something to reveal
 * while a swipe-back is in flight.
 *
 * Why this exists at all: the gesture in [SwipeBackContainer] is supposed to
 * show the previous page sliding out from under the current one, and the
 * swipe-dismiss this module models it on gets that for free — its navigation
 * layer keeps the previous entry composed. Here every page is its own
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
 * "no snapshot" rather than throwing, and [SwipeBackContainer] draws its
 * container colour in that case — the gesture still works, the revealed strip
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
         * this page's own background, so a swipe-back showed a flat panel with
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
        var generation: Int
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
     * the new palette — the next swipe only reveals the immediate parent, but
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
     * picture is only needed the next time a swipe-back starts, which is at the
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
        if (cache.values.all { it.generation == ThemeEngine.generation }) return
        handler.removeCallbacks(revive)
        handler.postDelayed(revive, REVIVE_DELAY_MS)
    }

    /** The coalesced body of [refreshForTheme]; runs once input has settled. */
    private val revive = Runnable {
        if (cache.isEmpty()) return@Runnable
        // Snapshot the keys first: a failed re-render removes its own entry.
        for (name in cache.keys.toList()) {
            val entry = cache[name] ?: continue
            if (entry.generation == ThemeEngine.generation) continue
            rerender(name, entry)
        }
    }

    /**
     * Runs any pending re-render **now** rather than at the end of the delay.
     *
     * For the one caller that cannot wait: a page is being left, and the picture
     * of the page underneath is about to be needed for the swipe-back it is
     * leaving with. [refreshForTheme] deliberately defers the work so a tap does
     * not pay for it, but "later" must not become "after the reveal has already
     * started" — that would show the strip one palette behind, which is the
     * whole bug the refresh exists to fix.
     *
     * A no-op when nothing is pending, so callers may run it unconditionally.
     * Cheap in that case: one `removeCallbacks` and a map probe.
     */
    fun flushForTheme() {
        if (!handler.hasCallbacks(revive)) return
        handler.removeCallbacks(revive)
        revive.run()
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
     * **The tree has to be brought forward first.** A stopped `Activity` gets no
     * frames, so Compose's invalidation from the generation bump is still
     * pending when this runs; drawing straight away would photograph the *old*
     * palette and the retake would be pointless. Re-attaching the content view
     * is what forces a synchronous recomposition, and layout follows from it,
     * so the draw that comes after sees the new colours. Heavy for a routine
     * call, which is why it is not one: it runs only when an appearance setting
     * actually changes, and only while a snapshot exists to refresh.
     */
    private fun rerender(name: String, entry: Entry): Boolean {
        val parent = entry.parent.get()
        val view = parent?.window?.decorView
        if (parent == null || view == null || view.width <= 0 || view.height <= 0) {
            // The parent is gone, so this slot can never be refreshed again.
            // Dropping it lets forParent fall back to a flat strip instead of
            // serving a picture in the old palette.
            cache.remove(name)
            return false
        }
        val width = view.width
        val height = view.height
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot allocate snapshot bitmap for retake", t)
            cache.remove(name)
            return false
        }
        forceRecompose(view)
        // Re-measure and lay out: a re-attached view has no valid size of its
        // own until it is measured, and drawing an unmeasured tree paints
        // nothing.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(view.left, view.top, view.left + width, view.top + height)
        redrawFallback(view, bitmap)
        entry.bitmap = bitmap.asImageBitmap()
        entry.generation = ThemeEngine.generation
        return true
    }

    /**
     * Forces [view]'s subtree to rebuild its display list instead of reusing the
     * cached one, which is what a stopped window would otherwise hand to
     * `View.draw`.
     *
     * **Why not simply detach and re-attach.** That was the first shape of this,
     * and it works — re-attaching makes Compose dispose and rebuild, so the draw
     * that follows sees the new palette. What it also does is discard every
     * `remember` in the tree, including the `LazyColumn`'s scroll position: the
     * app list would jump back to the top because the user picked a colour. That
     * is a worse bug than the one being fixed.
     *
     * **How that is handled without this file knowing anything about the parent.**
     * The scroll position is held by `MainScreen` through `rememberSaveable`
     * rather than plain `remember`, so it is restored from the view's saveable
     * registry — which outlives the composition being disposed. Nothing has to be
     * carried across by hand here, and this stays free of any knowledge of what
     * the parent renders. The rule for anything else the parent wants to keep
     * across a retake is the same: hold it saveably, do not expect the
     * composition to survive.
     *
     * All of it is guarded: an unexpected view shape must cost the strip its
     * detail, never crash the settings screen the user is standing on.
     */
    private fun forceRecompose(view: View) {
        try {
            val content = (view as? android.view.ViewGroup)?.getChildAt(0) ?: return
            val parent = content.parent as? android.view.ViewGroup ?: return
            val index = parent.indexOfChild(content)
            if (index < 0) return
            parent.removeViewAt(index)
            parent.addView(content, index)
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot force recompose for snapshot retake", t)
        }
    }

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
                    redrawFallback(view, bitmap)
                    store(activity, bitmap)
                }
                onCaptured()
            }, handler)
        } catch (t: Throwable) {
            // Thrown when the window has no surface to copy (already finishing,
            // no hardware renderer, ...). The redraw is a real fallback, not a
            // placeholder — it is what the old View-based screenshot did.
            Log.w(TAG, "PixelCopy unavailable; redrawing instead", t)
            redrawFallback(view, bitmap)
            store(activity, bitmap)
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

    private fun redrawFallback(view: View, bitmap: Bitmap) {
        try {
            view.draw(Canvas(bitmap))
        } catch (t: Throwable) {
            Log.w(TAG, "Redraw fallback failed too", t)
        }
    }
}
