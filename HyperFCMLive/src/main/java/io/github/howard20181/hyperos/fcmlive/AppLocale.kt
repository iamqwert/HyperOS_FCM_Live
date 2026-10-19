package io.github.howard20181.hyperos.fcmlive

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.runtime.mutableIntStateOf

/**
 * Per-app language, backed by the platform's own [LocaleManager].
 *
 * Two choices only, matching `R.array.language_entries`: Simplified Chinese
 * and English. The default — before the user picks anything — needs no stored
 * state at all: `values/` holds English and `values-zh/` holds Simplified
 * Chinese, so the resource fallback alone makes any Chinese system locale
 * (simplified, traditional, Cantonese) read Simplified Chinese and everything
 * else read English.
 *
 * A pick is written through [LocaleManager.setApplicationLocales]; the system
 * persists it across restarts and relaunches the activities itself, so there
 * is no module-side preference to keep in step. Clearing is unnecessary by
 * design — with two options, "follow system" and "the other language" are the
 * same tap.
 *
 * **No relaunch, in fact.** Every activity declares
 * `android:configChanges="locale|layoutDirection"`, so the system applies a
 * pick in place — `onConfigurationChanged` instead of destroy-and-recreate.
 * The relaunch was the black flash: the old window came down before the new
 * one had drawn, and the task void behind it showed for a frame. In place,
 * there is no window swap at all — but Compose strings are read during
 * composition, not observed, so [generation] counts the in-place changes and
 * [HyperFCMLiveTheme] reads it: one bump recomposes every screen onto the
 * re-read strings. Activities forward their `onConfigurationChanged` here.
 */
object AppLocale {
    /** Index into `R.array.language_entries`. */
    const val INDEX_CHINESE = 0
    const val INDEX_ENGLISH = 1

    private const val TAG_CHINESE = "zh-CN"
    private const val TAG_ENGLISH = "en"

    /**
     * Bumped on every in-place locale change. Read in composition (the theme
     * root), so each bump invalidates the tree and every `stringResource`
     * re-resolves against the activity's updated resources.
     */
    val generation = mutableIntStateOf(0)

    /** Call from an activity's `onConfigurationChanged`. */
    fun onLocaleConfigChanged() {
        generation.intValue++
    }

    /**
     * The language the UI is showing right now: the applied per-app locale if
     * the user has picked one, otherwise the resolved system locale. Reads the
     * actual state on every call — no cached value to go stale after a
     * system-initiated relaunch.
     */
    fun current(context: Context): Int {
        val manager = context.getSystemService(LocaleManager::class.java)
        val applied = manager?.applicationLocales
        if (applied != null && !applied.isEmpty) {
            return indexOf(applied[0].language)
        }
        val resolved = context.resources.configuration.locales[0]
        return indexOf(resolved.language)
    }

    /** Applies a pick from the dropdown; the system persists and applies in place. */
    fun apply(context: Context, index: Int) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val tag = if (index == INDEX_CHINESE) TAG_CHINESE else TAG_ENGLISH
        manager.setApplicationLocales(LocaleList.forLanguageTags(tag))
    }

    private fun indexOf(language: String): Int =
        if (language == "zh") INDEX_CHINESE else INDEX_ENGLISH
}
