package io.github.howard20181.hyperos.fcmlive.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.rememberPlatformOverscrollFactory
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Jetpack Compose theme bridge.
 *
 * Two looks, one entry point. Under the app's own look, colors are **not** taken
 * from Compose's dynamic-color helpers: they are mapped from
 * [ThemeEngine]/[AppPalette] so the in-app palette style (Tonal spot, Monochrome,
 * …) and colour spec are the ones the settings page offers, derived by the same
 * Material colour utilities. Under the Miuix look the authority moves — see
 * [MiuixStyleTheme].
 *
 * Which one is in force is read here, off [ThemeEngine.generation], so a switch of
 * 界面风格 re-skins in place exactly as changing a colour does: no window rebuild,
 * no jump.
 */
@Composable
fun HyperFCMLiveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val style = remember(context, ThemeEngine.generation) {
        UiStyle.of(ThemePrefs.uiStyle(context))
    }
    when (style) {
        UiStyle.Miuix -> MiuixStyleTheme(content)
        UiStyle.Material -> MaterialStyleTheme(content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MaterialStyleTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme is only a fallback when no palette is applied yet;
    // ThemeEngine.palette already resolves the user's theme mode.
    // Keying on ThemeEngine.generation is what makes an in-place theme switch
    // work: invalidate() bumps the counter, this composable re-executes, and
    // the palette recomputes — no Activity recreate, no window jump.
    val palette = LocalContext.current.let { context ->
        remember(context, ThemeEngine.generation) {
            // Preview never has a usable Context behind it: no SharedPreferences
            // and no real resources. Falling back to the stock light scheme is
            // what keeps @Preview renderable — without it every annotated
            // function fails to inflate and the whole tool becomes useless.
            try {
                ThemeEngine.palette(context)
            } catch (t: Throwable) {
                null
            }
        }
    }
    val colorScheme = remember(palette) { palette?.toComposeColorScheme() ?: lightColorScheme() }
    // The raised container. `AppPalette` owns the value; without a palette
    // (preview, no usable Context) fall back to the stock scheme's `surface`,
    // which is the same near-neutral tone the light palette would hand over.
    val appSurfaces = remember(palette, colorScheme) {
        AppSurfaces(
            card = palette?.let { Color(it.cardBg) } ?: colorScheme.surface,
            popup = palette?.let { Color(it.popupBg) } ?: colorScheme.surface
        )
    }
    // Which Material generation we are dressing as. Driven by the colour spec
    // rather than a second preference: "Material You (2021)" and "Expressive
    // (2025)" differ in motion and shape as much as in colour, and letting them
    // disagree is what makes one generation feel like a skin over the other.
    val expressive = remember(palette) {
        palette?.scheme?.spec == Scheme.Spec.SPEC_2025
    }
    val motionScheme = remember(expressive) { motionSchemeFor(expressive) }
    val appShapes = remember(expressive) { appShapesFor(expressive) }
    // The edge effect: the platform `EdgeEffect` — stretch on API 31 and up, a
    // glow below that. Built as a factory rather than through the
    // (now deprecated) `LocalOverscrollConfiguration`, so the effect does not
    // depend on foundation resolving a default value for us: if that chain ever
    // yields null, every list in the app silently loses its overscroll, which
    // is exactly the symptom this line exists to prevent. The glow colour is
    // the accent, so the pre-31 fallback still reads as part of the theme.
    // Keyed via [key]: the factory internally remembers itself, so without a
    // key a theme switch would leave the pre-31 glow (and the cached factory)
    // in the old palette while everything else re-skins.
    val overscrollFactory = key(colorScheme.primary) {
        rememberPlatformOverscrollFactory(
            glowColor = colorScheme.primary
        )
    }
    // Typescale comes from ComposeTokens.kt, which is the single definition of
    // the app's type scale — there is no second copy left for it to drift from.
    MaterialTheme(
        colorScheme = colorScheme,
        typography = HyperFCMLiveTypography,
        shapes = HyperFCMLiveShapes,
        motionScheme = motionScheme,
    ) {
        CompositionLocalProvider(
            LocalAppShapes provides appShapes,
            LocalAppSurfaces provides appSurfaces,
            LocalOverscrollFactory provides overscrollFactory,
            LocalUiStyle provides UiStyle.Material
        ) {
            content()
        }
    }
}

/**
 * The Miuix look.
 *
 * Miuix owns the colours here: its fixed HyperOS scheme, or Monet when 莫奈颜色 is
 * on. Everything the screens actually draw still comes through MaterialTheme, so
 * the palettes are mapped across — two sources of truth on one page is exactly the
 * failure this branch has to avoid, and it keeps the M3 widgets that have no Miuix
 * counterpart wearing the same colours as anything Miuix draws.
 *
 * Only 主题模式 and 莫奈颜色 steer this look; see the note at the top of the body for
 * why the other three rows of the section are neither read nor shown here.
 *
 * The three-level surface ramp is Miuix's own: `surface` is the page,
 * `surfaceVariant` the cards (white in light, `#242424` in dark), and the top
 * container the popup level. AMOLED keeps Miuix's dark ramp for the cards but
 * drops the page to true black, which is the whole point of that mode.
 *
 * Miuix also supplies its own ripple and overscroll through its theme, and those
 * are deliberately *not* overwritten: the press feedback and the edge glow are
 * part of what makes the system's settings feel like the system's settings.
 */
@Composable
private fun MiuixStyleTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Keyed on the generation the same way the Material branch keys its palette:
    // ThemeEngine.invalidate() bumps it, this re-reads, and the appearance changes
    // in place.
    val generation = ThemeEngine.generation
    // Only 主题模式 and 莫奈颜色 are read here, because only those two are on screen
    // in this look. 动态颜色 / 调色风格 / 颜色规格 belong to the app's own colour
    // engine, are Material designs by construction, and are hidden while Miuix is
    // chosen — so they are deliberately not consulted either. A look that ignored a
    // setting on screen is a bug; a look quietly driven by a setting nobody can see
    // is worse, because the row then still holds a value, and it even still changes
    // this screen, from behind another screen's back.
    val dark = remember(context, generation) { ThemePrefs.isDark(context) }
    val amoled = remember(context, generation) { ThemePrefs.isAmoled(context) }
    val monet = remember(context, generation) { ThemePrefs.miuixMonet(context) }

    // ThemeController publishes its inputs as snapshot state but they are `val`s,
    // so a change is pushed by rebuilding it rather than by assignment. Cheap: the
    // colours it hands back are remembered inside MiuixTheme across a rebuild, so
    // components under it do not see a new scheme on an unrelated recomposition.
    val controller = ThemeController(
        colorSchemeMode = when {
            monet && dark -> ColorSchemeMode.MonetDark
            monet -> ColorSchemeMode.MonetLight
            dark -> ColorSchemeMode.Dark
            else -> ColorSchemeMode.Light
        },
        // Always resolved: the app can force light or dark independently of the
        // device, and isSystemInDarkTheme() (what null falls back to) is the device.
        isDark = dark
    )

    MiuixTheme(controller = controller) {
        val colors = MiuixTheme.colorScheme
        // The 2021 ramp, unconditionally: which ramp the app dresses as is a
        // Material spec decision, and the spec row is not part of this look.
        val colorScheme = remember(colors, dark) { colors.toMaterialColorScheme(dark) }
        val appSurfaces = remember(colors, colorScheme, amoled) {
            AppSurfaces(
                card = if (amoled) colors.surfaceContainerHigh else colors.surfaceVariant,
                popup = if (dark) colors.surfaceContainerHighest else colors.surfaceContainer
            )
        }
        val appShapes = remember { appShapesFor(false) }
        // The window chrome follows, too: it is View-side state no recomposition
        // reaches, and under this look the page colour is Miuix's.
        val pageArgb = (if (amoled) Color.Black else colors.surface).toArgb()
        val pageDark = dark
        val windowActivity = (context as? android.app.Activity)
        SideEffect {
            windowActivity?.let { ThemeSupport.reapplyWindow(it, pageArgb, pageDark) }
        }
        MaterialTheme(
            colorScheme = if (amoled) {
                colorScheme.copy(background = Color.Black, surface = Color.Black)
            } else {
                colorScheme
            },
            typography = HyperFCMLiveTypography,
            shapes = HyperFCMLiveShapes,
            motionScheme = motionSchemeFor(false),
        ) {
            CompositionLocalProvider(
                LocalAppShapes provides appShapes,
                LocalAppSurfaces provides appSurfaces,
                LocalUiStyle provides UiStyle.Miuix
            ) {
                content()
            }
        }
    }
}

/**
 * Miuix [Colors] onto Material 3's [ColorScheme].
 *
 * Miuix names its roles for the HyperOS settings it was written for, so the
 * mapping is by meaning, not by name: its `onSurfaceVariantSummary` is the
 * secondary text colour a Material `onSurfaceVariant` carries, its `dividerLine`
 * is `outlineVariant`, and `tertiaryContainer` is the accent-tinted plate a
 * `primaryContainer` is in Material's language.
 */
private fun Colors.toMaterialColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = tertiaryContainer,
        onPrimaryContainer = onTertiaryContainer,
        inversePrimary = primary,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = tertiaryContainer,
        onSecondaryContainer = onTertiaryContainer,
        tertiary = primary,
        onTertiary = onPrimary,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = surface,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariantSummary,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = outline,
        outlineVariant = dividerLine,
        scrim = windowDimming
    )
}

/**
 * Map the runtime [AppPalette] (full [Scheme]) onto Compose Material 3.
 *
 * Every role is mapped verbatim. App-side surface policy — the AMOLED
 * near-black ramp, and the three-level page/card/popup mapping — lives in
 * [AppPalette], so this bridge stays a translation table instead of a second
 * place the palette is decided. The raised container and the popup level travel
 * beside the scheme through [LocalAppSurfaces] (see [AppSurfaces] for why the
 * AMOLED ramp keeps them outside the roles).
 */
fun AppPalette.toComposeColorScheme(): ColorScheme {
    val s = scheme
    val surface = Color(pageBg)
    val onSurface = Color(this.onSurface)
    return if (dark) {
        darkColorScheme(
            primary = Color(primary),
            onPrimary = Color(onPrimary),
            primaryContainer = Color(primaryContainer),
            onPrimaryContainer = Color(s.onPrimaryContainer),
            inversePrimary = Color(s.inversePrimary),
            secondary = Color(s.secondary),
            onSecondary = Color(s.onSecondary),
            secondaryContainer = Color(s.secondaryContainer),
            onSecondaryContainer = Color(s.onSecondaryContainer),
            tertiary = Color(s.tertiary),
            onTertiary = Color(s.onTertiary),
            tertiaryContainer = Color(s.tertiaryContainer),
            onTertiaryContainer = Color(s.onTertiaryContainer),
            background = surface,
            onBackground = onSurface,
            surface = Color(this.surface),
            onSurface = onSurface,
            surfaceVariant = Color(this.surfaceVariant),
            onSurfaceVariant = Color(this.onSurfaceVariant),
            surfaceTint = Color(primary),
            inverseSurface = Color(this.inverseSurface),
            inverseOnSurface = Color(this.inverseOnSurface),
            outline = Color(this.outline),
            outlineVariant = Color(this.outlineVariant),
            scrim = Color.Black,
            error = Color(s.error),
            onError = Color(s.onError),
            errorContainer = Color(s.errorContainer),
            onErrorContainer = Color(s.onErrorContainer),
            surfaceBright = Color(s.surfaceBright),
            surfaceDim = Color(s.surfaceDim),
            surfaceContainer = Color(s.surfaceContainer),
            surfaceContainerHigh = Color(this.surfaceContainerHigh),
            surfaceContainerHighest = Color(this.surfaceContainerHighest),
            surfaceContainerLow = Color(this.surfaceContainerLow),
            surfaceContainerLowest = Color(this.surfaceContainerLowest),
        )
    } else {
        lightColorScheme(
            primary = Color(primary),
            onPrimary = Color(onPrimary),
            primaryContainer = Color(primaryContainer),
            onPrimaryContainer = Color(s.onPrimaryContainer),
            inversePrimary = Color(s.inversePrimary),
            secondary = Color(s.secondary),
            onSecondary = Color(s.onSecondary),
            secondaryContainer = Color(s.secondaryContainer),
            onSecondaryContainer = Color(s.onSecondaryContainer),
            tertiary = Color(s.tertiary),
            onTertiary = Color(s.onTertiary),
            tertiaryContainer = Color(s.tertiaryContainer),
            onTertiaryContainer = Color(s.onTertiaryContainer),
            background = surface,
            onBackground = onSurface,
            surface = Color(this.surface),
            onSurface = onSurface,
            surfaceVariant = Color(this.surfaceVariant),
            onSurfaceVariant = Color(this.onSurfaceVariant),
            surfaceTint = Color(primary),
            inverseSurface = Color(this.inverseSurface),
            inverseOnSurface = Color(this.inverseOnSurface),
            outline = Color(this.outline),
            outlineVariant = Color(this.outlineVariant),
            scrim = Color.Black,
            error = Color(s.error),
            onError = Color(s.onError),
            errorContainer = Color(s.errorContainer),
            onErrorContainer = Color(s.onErrorContainer),
            surfaceBright = Color(s.surfaceBright),
            surfaceDim = Color(s.surfaceDim),
            surfaceContainer = Color(s.surfaceContainer),
            surfaceContainerHigh = Color(this.surfaceContainerHigh),
            surfaceContainerHighest = Color(this.surfaceContainerHighest),
            surfaceContainerLow = Color(this.surfaceContainerLow),
            surfaceContainerLowest = Color(this.surfaceContainerLowest),
        )
    }
}
