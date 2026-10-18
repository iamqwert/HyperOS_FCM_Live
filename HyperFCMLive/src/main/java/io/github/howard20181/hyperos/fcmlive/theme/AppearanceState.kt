package io.github.howard20181.hyperos.fcmlive.theme

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme

/**
 * The appearance settings as Compose state, one layer above the screens.
 *
 * [ThemePrefs] stays the source of truth on disk; this object holds the same
 * nine values as [MutableState]s so a composable can read the live value
 * without keeping a `remember`ed copy of its own. That copy was the failure
 * mode this replaces: every row on the appearance page used to mirror its pref
 * into local state, and every writer had to do three things — write the pref,
 * update the mirror, ask the Activity to re-skin — for the page to stay honest.
 * A mirror with three steps per write is a mirror waiting to go stale.
 *
 * Here a write is one call: the setter persists through [ThemePrefs], updates
 * the state every reader subscribes to, and invalidates the palette engine so
 * the theme re-skins in place. The window chrome and the back-preview
 * snapshot stay the Activity's job (`onAppearanceChange`), because those are
 * View-side state no recomposition reaches.
 *
 * [hydrate] re-reads the disk values. Every Activity calls it from
 * [ThemeSupport.onCreate] before composing, so the state a screen reads is
 * never the residue of a previous process.
 *
 * The resolved colour spec ([colorSpec]) is maintained, not derived on read:
 * the request stored in prefs can demote (2025 asked on a style that cannot
 * render it renders 2021 — see [ThemePrefs.specVersion], which deliberately
 * never writes the demotion back), so the value a row displays is the
 * *resolved* one, refreshed by the two setters that can move it.
 */
object AppearanceState {

    /** [ThemePrefs.MODE_SYSTEM]..[ThemePrefs.MODE_DARK]. */
    val themeMode: MutableIntState = mutableIntStateOf(ThemePrefs.MODE_SYSTEM)

    /** [ThemePrefs.UI_STYLE_MATERIAL], [ThemePrefs.UI_STYLE_MIUIX] or [ThemePrefs.UI_STYLE_SOFT_GLASS]. */
    val uiStyle: MutableIntState = mutableIntStateOf(ThemePrefs.UI_STYLE_MATERIAL)

    /** Whether colors follow the wallpaper; when off, [seedColor] wins. */
    val dynamicColor: MutableState<Boolean> = mutableStateOf(true)

    /** Custom seed (ARGB) used while dynamic color is off; 0 means unset. */
    val seedColor: MutableIntState = mutableIntStateOf(0)

    /** [Scheme.Variant] ordinal as stored — the request, not a resolved pair. */
    val paletteStyle: MutableIntState = mutableIntStateOf(Scheme.Variant.TONAL_SPOT.ordinal)

    /** The spec **in force** — the stored request resolved against the style. */
    val colorSpec: MutableIntState = mutableIntStateOf(ThemePrefs.SPEC_2025)

    /** Monet inside the Miuix look only. */
    val miuixMonet: MutableState<Boolean> = mutableStateOf(false)

    /** The Miuix 强调色 as ARGB; 0 means "follow the wallpaper". */
    val miuixAccent: MutableIntState = mutableIntStateOf(0)

    /**
     * The material the Miuix home foot is milled from — [ThemePrefs.BAR_MATERIAL_MIUIX]
     * or [ThemePrefs.BAR_MATERIAL_SOFT_GLASS]. Held as the value **in force**, not
     * the stored one: with no pick on disk the style answers for it, so this is
     * what the appearance row shows and what the home frame builds.
     */
    val barMaterial: MutableIntState = mutableIntStateOf(ThemePrefs.BAR_MATERIAL_MIUIX)

    /**
     * Whether [barMaterial] came from the user's own pick rather than from the
     * style's default. The distinction only matters on a style switch: an
     * unpicked bar follows the look onto its default material (choosing 柔光玻璃
     * as a look must glass the foot without asking for a second decision),
     * while a picked one is a decision of its own and is left where it was put.
     */
    val barMaterialPicked: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Re-read every value from disk. Idempotent and cheap: assigning the value
     * a state already holds does not invalidate anything (structural equality
     * policy), so re-hydrating per Activity costs nothing when nothing moved.
     */
    @JvmStatic
    fun hydrate(context: Context) {
        themeMode.intValue = ThemePrefs.themeMode(context)
        uiStyle.intValue = ThemePrefs.uiStyle(context)
        dynamicColor.value = ThemePrefs.dynamicColor(context)
        seedColor.intValue = ThemePrefs.seedColor(context)
        paletteStyle.intValue = ThemePrefs.paletteStyle(context).ordinal
        colorSpec.intValue = ThemePrefs.specVersion(context)
        miuixMonet.value = ThemePrefs.miuixMonet(context)
        miuixAccent.intValue = ThemePrefs.miuixAccent(context)
        barMaterialPicked.value = ThemePrefs.barMaterialPicked(context)
        barMaterial.intValue = ThemePrefs.barMaterial(context)
    }

    @JvmStatic
    fun setThemeMode(context: Context, mode: Int) {
        ThemePrefs.setThemeMode(context, mode)
        themeMode.intValue = mode
        ThemeEngine.invalidate()
    }

    /**
     * Stores the style and, while the bar material has never been picked,
     * re-derives it from the new look — see [barMaterialPicked]. The two rows are
     * read together on the appearance page, so leaving the derived one behind on
     * a switch would show a material the frame is not building.
     */
    @JvmStatic
    fun setUiStyle(context: Context, style: Int) {
        ThemePrefs.setUiStyle(context, style)
        uiStyle.intValue = style
        if (!barMaterialPicked.value) {
            barMaterial.intValue = ThemePrefs.defaultBarMaterial(style)
        }
        ThemeEngine.invalidate()
    }

    @JvmStatic
    fun setDynamicColor(context: Context, enabled: Boolean) {
        ThemePrefs.setDynamicColor(context, enabled)
        dynamicColor.value = enabled
        ThemeEngine.invalidate()
    }

    @JvmStatic
    fun setSeedColor(context: Context, color: Int) {
        ThemePrefs.setSeedColor(context, color)
        seedColor.intValue = color
        ThemeEngine.invalidate()
    }

    /**
     * Stores the style request and refreshes [colorSpec]: the resolved spec is
     * a function of the style, so the two rows can only ever move together —
     * the same re-read the screen used to do by hand after every style pick.
     */
    @JvmStatic
    fun setPaletteStyle(context: Context, variant: Scheme.Variant) {
        ThemePrefs.setPaletteStyle(context, variant)
        paletteStyle.intValue = variant.ordinal
        colorSpec.intValue = ThemePrefs.specVersion(context)
        ThemeEngine.invalidate()
    }

    /**
     * Stores the spec request and refreshes [colorSpec] with the resolved one — and [paletteStyle]
     * with whatever the write-side coupling left on disk: picking 2026 adopts CMF, picking 2021
     * or 2025 while CMF is active leaves it (see [ThemePrefs.setSpecVersion]).
     */
    @JvmStatic
    fun setSpecVersion(context: Context, spec: Int) {
        ThemePrefs.setSpecVersion(context, spec)
        colorSpec.intValue = ThemePrefs.specVersion(context)
        paletteStyle.intValue = ThemePrefs.paletteStyle(context).ordinal
        ThemeEngine.invalidate()
    }

    @JvmStatic
    fun setMiuixMonet(context: Context, enabled: Boolean) {
        ThemePrefs.setMiuixMonet(context, enabled)
        miuixMonet.value = enabled
        ThemeEngine.invalidate()
    }

    @JvmStatic
    fun setMiuixAccent(context: Context, color: Int) {
        ThemePrefs.setMiuixAccent(context, color)
        miuixAccent.intValue = color
        ThemeEngine.invalidate()
    }

    @JvmStatic
    fun setBarMaterial(context: Context, material: Int) {
        ThemePrefs.setBarMaterial(context, material)
        barMaterial.intValue = material
        barMaterialPicked.value = true
        ThemeEngine.invalidate()
    }
}
