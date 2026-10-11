package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

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

    /**
     * Opens [packageName]'s application-management page in the system manager.
     *
     * On HyperOS/MIUI the standard [Settings.ACTION_APPLICATION_DETAILS_SETTINGS]
     * is handled by 手机管家 (`com.miui.securitycenter`) and opens that app's
     * 应用信息 page — the 应用管理 entry the long-press menu asks for — so the
     * public intent is the primary route: it resolves across MIUI versions that
     * rename the underlying activity. Explicit security-center components are
     * tried only if it does not resolve. `false` means nothing could take it.
     */
    @JvmStatic
    fun openAppDetails(context: Context, packageName: String): Boolean {
        val standard =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (canStart(context, standard)) {
            return try {
                context.startActivity(standard)
                true
            } catch (t: Throwable) {
                false
            }
        }
        for (className in SECURITY_APP_INFO_ACTIVITIES) {
            val explicit =
                Intent()
                    .setClassName(SECURITY_CENTER, className)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .putExtra(EXTRA_PACKAGE_NAME, packageName)
            try {
                context.startActivity(explicit)
                return true
            } catch (t: Throwable) {
                // Try the next component / fall through.
            }
        }
        return false
    }

    /** True if some activity resolves [intent] (and it would not be a no-op). */
    private fun canStart(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, 0) != null

    private const val SECURITY_CENTER = "com.miui.securitycenter"
    private const val EXTRA_PACKAGE = "package"
    private const val EXTRA_PACKAGE_NAME = "extra_package_name"
    private val SECURITY_APP_INFO_ACTIVITIES =
        arrayOf(
            "com.miui.securitycenter.application.appinfo.AppInfoActivity",
            "com.miui.securitycenter.app.AppInfoActivity",
            "com.miui.permcenter.appinfo.AppInfoActivity"
        )
}
