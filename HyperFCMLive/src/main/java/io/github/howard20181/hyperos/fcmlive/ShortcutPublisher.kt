package io.github.howard20181.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri

/**
 * Push launcher shortcuts from code so icon/label updates reach the
 * launcher without relying only on static res/xml/shortcuts.xml caches.
 *
 * Every entry point here is API 25+, and minSdk is 35, so there is no version
 * gate: the guard that used to return early below N_MR1 could never fire.
 */
object ShortcutPublisher {

    private const val ID_SETTINGS = "settings"
    private const val ID_HELP = "help"
    private const val ID_FCM = "fcm_diagnostics"

    @JvmStatic
    fun publish(context: Context) {
        try {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return
            val settings = ShortcutInfo.Builder(context, ID_SETTINGS)
                .setShortLabel(context.getString(R.string.shortcut_settings))
                .setLongLabel(context.getString(R.string.shortcut_settings_long))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_settings))
                .setIntent(
                    Intent(Intent.ACTION_VIEW).setClassName(
                        context.packageName,
                        "io.github.howard20181.hyperos.fcmlive.AboutActivity"
                    )
                )
                .build()
            val help = ShortcutInfo.Builder(context, ID_HELP)
                .setShortLabel(context.getString(R.string.help))
                .setLongLabel(context.getString(R.string.shortcut_help_long))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_help))
                // Opens the online help in a browser; there is no in-app help
                // page any more (the text lives in HELP.md in the repository).
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.HELP_URL)))
                .build()
            val fcm = ShortcutInfo.Builder(context, ID_FCM)
                .setShortLabel(context.getString(R.string.fcm_diagnostics))
                .setLongLabel(context.getString(R.string.shortcut_fcm_diagnostics_long))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_fcm))
                .setIntent(
                    Intent(MainActivity.ACTION_FCM_DIAGNOSTICS).setClassName(
                        context.packageName,
                        "io.github.howard20181.hyperos.fcmlive.MainActivity"
                    )
                )
                .build()
            sm.dynamicShortcuts = listOf(settings, help, fcm)
        } catch (_: Throwable) {
        }
    }
}
