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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme

/**
 * Jetpack Compose theme bridge.
 *
 * Colors are **not** taken from Compose's own dynamic-color helpers. They are
 * mapped from [ThemeEngine]/[AppPalette] so the in-app palette style
 * (Tonal spot, Monochrome, …) and colour spec are the ones the settings page
 * offers, derived by the same Material colour utilities.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HyperFCMLiveTheme(content: @Composable () -> Unit) {
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
            LocalOverscrollFactory provides overscrollFactory
        ) {
            content()
        }
    }
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
