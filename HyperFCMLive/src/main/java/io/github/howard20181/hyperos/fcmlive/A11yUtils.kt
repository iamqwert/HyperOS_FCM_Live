package io.github.howard20181.hyperos.fcmlive

import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * The announcements the Compose tree cannot make for itself.
 *
 * Everything else this file used to hold — pane titles, tooltip nodes, menu-row
 * check states, decorative-image marking — is stated in the semantics of the
 * composables that own it now (`Role.Switch`, `Role.Checkbox`,
 * `contentDescription = null`, `heading()`). What is left is the feedback that
 * has no visual form at all: the allowlist state a row just moved to, and the
 * pull-to-refresh result, whose indicator is a drawing.
 */
object A11yUtils {

    /**
     * Android 16 throws IllegalStateException ("Accessibility off") when an
     * event is sent while the service is disabled. Always gate on the manager.
     */
    private fun a11yEnabled(view: View?): Boolean {
        val context = view?.context ?: return false
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.isEnabled
    }

    /** Announce a transient message (a row's new state, the list reloaded). */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun announce(view: View?, text: CharSequence?) {
        if (view == null || text.isNullOrEmpty() || !a11yEnabled(view)) {
            return
        }
        try {
            view.announceForAccessibility(text)
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            event.text.add(text)
            event.className = view.javaClass.name
            event.packageName = view.context?.packageName
            view.parent?.requestSendAccessibilityEvent(view, event) ?: view.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_ANNOUNCEMENT
            )
        } catch (ignored: Throwable) {
            // Accessibility must never take the UI down (Android 16 strictness).
        }
    }
}
