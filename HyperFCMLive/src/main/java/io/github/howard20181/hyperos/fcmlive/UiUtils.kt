package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Handing a link to the system, which is the one thing on these screens that has
 * to leave the app.
 *
 * This used to be a toolbox: px conversion, safe-area insets, code-built row
 * haptics and a bar-inset applier for the View screens. Compose covers all of it
 * now — a dp is a unit, insets come from the Scaffold, and a tap's feedback
 * comes from the modifier handling the tap — so what is left is the part
 * Compose cannot do.
 */
object UiUtils {

    /**
     * Hands [url] to whatever the system resolves it to. `false` means nothing
     * could take it — a device with no browser, or a URL no app claims — and the
     * caller is expected to show it, because the URL itself is the message.
     */
    @JvmStatic
    fun openUrl(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
