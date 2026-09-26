package io.github.howard20181.hyperos.fcmlive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.Toast

/** Small shared UI helpers (open URL, px conversion, safe-area insets). */
object UiUtils {

    @JvmStatic
    fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show()
        }
    }

    @JvmStatic
    fun dp(context: Context, value: Int): Int {
        return Math.round(value * context.resources.displayMetrics.density)
    }

    /**
     * Safe area at the top of the window.
     *
     * Android 15 (API 35) enforces edge-to-edge for apps targeting 35+: the
     * window always extends behind the system bars, and the status/navigation
     * bar colour APIs have no effect. Every screen therefore has to apply its
     * own insets, which is what these helpers are for. The display cutout is
     * included so the title never slides under a notch in landscape.
     */
    @JvmStatic
    fun topInset(insets: WindowInsets): Int {
        return insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        ).top
    }

    /**
     * Safe area at the bottom of the window — the union of the navigation-bar
     * and the gesture insets, so the value is right for both navigation modes:
     * with gesture navigation the navigation-bar inset alone can be zero and
     * the home-indicator area is only reported through `systemGestures`,
     * which is exactly how the last list item ends up under the indicator.
     */
    @JvmStatic
    fun bottomInset(insets: WindowInsets): Int {
        return insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.systemGestures()
        ).bottom
    }

    /**
     * One tick of haptic feedback for a tap on a card.
     *
     * `performHapticFeedback` rather than the vibrator service: it
     * needs no permission and is already gated on the user's own touch-feedback
     * setting, so switching haptics off in the system silences the app too. On
     * a device without a vibrator — or a ROM that ignores the request — it just
     * returns false, and there is nothing to clean up: no service handle, no
     * permission to revoke.
     */
    @JvmStatic
    fun tapFeedback(view: View?) {
        if (view == null) {
            return
        }
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (ignored: Throwable) {
            // No vibrator / no haptics on this device: a tap is still a tap.
        }
    }

    @JvmStatic
    fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    /** Extra work a screen needs once its insets are known (FAB margin, cached values). */
    fun interface InsetSink {
        fun onInsets(top: Int, bottom: Int)
    }

    /**
     * Applies the window insets every screen shares: the top bar is pushed below
     * the status bar, and `content` gets `contentBottomExtraDp` plus
     * the real bottom safe area on top of whatever padding the layout already
     * declares. `sink` — when non-null — runs inside the listener, for the
     * few screens that need the raw values as well.
     *
     * The status-bar fallback at the end is not redundant: some ROMs never
     * dispatch insets to the listener, and without it the top bar would sit under
     * the status bar on exactly those devices.
     */
    @JvmStatic
    @JvmOverloads
    fun applyBarInsets(
        activity: Activity,
        topBar: View?,
        content: View?,
        contentBottomExtraDp: Int,
        sink: InsetSink? = null
    ) {
        val root = activity.findViewById<View>(android.R.id.content)
        if (root != null) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val top = topInset(insets)
                val bottom = bottomInset(insets)
                val barPad = dp(activity, 12)
                topBar?.setPadding(topBar.paddingLeft, top + barPad, topBar.paddingRight, barPad)
                content?.setPadding(
                    content.paddingLeft,
                    content.paddingTop,
                    content.paddingRight,
                    bottom + dp(activity, contentBottomExtraDp)
                )
                sink?.onInsets(top, bottom)
                insets
            }
            root.requestApplyInsets()
        }
        val statusBar = statusBarHeight(activity)
        if (statusBar > 0 && topBar != null && topBar.paddingTop <= statusBar) {
            val barPad = dp(activity, 12)
            topBar.setPadding(topBar.paddingLeft, statusBar + barPad, topBar.paddingRight, barPad)
        }
    }
}
