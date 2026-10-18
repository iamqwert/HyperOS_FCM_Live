package io.github.howard20181.hyperos.fcmlive.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import io.github.howard20181.hyperos.fcmlive.AppLocale
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme
import io.github.howard20181.hyperos.fcmlive.ui.softglass.LocalSoftGlassSpec
import io.github.howard20181.hyperos.fcmlive.ui.softglass.SoftGlassFineTuning
import io.github.howard20181.hyperos.fcmlive.ui.softglass.SoftGlassTuning
import io.github.howard20181.hyperos.fcmlive.ui.softglass.createSoftGlassSpec
import top.yukonga.miuix.kmp.utils.MiuixIndication
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

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
/**
 * The Miuix look's **light-mode** primary, published apart from the active
 * palette.
 *
 * HyperOS does not mute a floating-action plate when the theme turns dark —
 * the FAB's blue is one colour all day — but the palette the active
 * [MiuixTheme] hands out in the dark is the dark one, so a surface that keeps
 * its light dress cannot read its colour from `MiuixTheme.colorScheme` without
 * shifting shade at dusk. [MiuixStyleTheme] derives the light palette's primary
 * for exactly one consumer and provides it here; the fallback is the library's
 * own light primary, so a @Preview with no prefs behind it still renders the
 * plate the look defaults to.
 */
val LocalMiuixLightPrimary = staticCompositionLocalOf { Color(0xFF3482FF) }

@Composable
fun HyperFCMLiveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // The language generation, read bare: a pick applied in place
    // (configChanges — no recreate) bumps [AppLocale.generation], this read
    // re-subscribes the root scope, and the tree recomposes with every
    // stringResource re-resolving against the activity's updated resources.
    // Without it the framework's in-place update would reach the View world
    // and never this one — Compose reads strings at composition time.
    AppLocale.generation.intValue
    // Read straight off the pref rather than through remember: the generation
    // read on this line is what subscribes this scope, so a bump re-runs it and
    // the very next line re-reads the style — no cached value sitting between the
    // write and the read, which is how a style switch could land one screen late.
    val generation = ThemeEngine.generation
    val style = UiStyle.of(ThemePrefs.uiStyle(context))
    // Each look gets its own saveable scope. The two style branches are
    // different composables at the same source position, so a switch replaces
    // the whole subtree — and without a provider here the switcher's own page
    // (设置) loses its scroll position, because plain positional saveable keys
    // no longer match across the branch boundary. Scoped per style, the state
    // each look's pages saved is retained by the holder and restored when that
    // look comes back: switching 界面风格 reads as re-dressing the same page,
    // not as landing on a fresh one.
    val styleScope = rememberSaveableStateHolder()
    styleScope.SaveableStateProvider(style) {
        when (style) {
            // One renderer for both Miuix-dressed looks, told apart only by the
            // style it hands down: 柔光玻璃 is the same screens wearing a different
            // material, so it must not become a second copy of these screens.
            UiStyle.Miuix, UiStyle.SoftGlass -> MiuixStyleTheme(style, content)
            UiStyle.Material -> MaterialStyleTheme(content)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MaterialStyleTheme(content: @Composable () -> Unit) {
    // isSystemInDarkTheme is not consulted here: ThemeEngine.palette already
    // resolves the user's theme mode.
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
 * 动态颜色 / 调色风格 / 颜色规格 are not offered here — they name Material concepts and
 * this is not a Material look — but they are still *read*, and they still steer
 * it. Deliberate, and worth a second before it gets "fixed": see the note in the
 * body.
 *
 * The three-level surface ramp is Miuix's own: `surface` is the page,
 * `surfaceVariant` the cards (white in light, `#242424` in dark), and the top
 * container the popup level.
 *
 * Miuix also supplies its own press indication and overscroll through its
 * theme. The overscroll factory survives the MaterialTheme wrapper untouched;
 * the indication does not — MaterialTheme re-provides `LocalIndication` with an
 * M3 ripple — so the indication is restated in the provider block below, with
 * the construction MiuixTheme itself uses.
 */
@Composable
private fun MiuixStyleTheme(style: UiStyle, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Keyed on the generation the same way the Material branch keys its palette:
    // ThemeEngine.invalidate() bumps it, this re-reads, and the appearance changes
    // in place.
    val generation = ThemeEngine.generation
    val dark = remember(context, generation) { ThemePrefs.isDark(context) }
    val monet = remember(context, generation) { ThemePrefs.miuixMonet(context) }
    // Read but not offered. The three below are the app's own colour engine's
    // controls and they are hidden in this look, yet they keep feeding it, because
    // Miuix's Monet derivation takes exactly those three arguments (a key colour, a
    // palette family, a spec) — so a person's colour decisions follow them across a
    // style switch instead of the screen changing face under their hands, which is
    // the outcome they were making those decisions to avoid.
    //
    // What that trades away is honesty on one screen: these values are changeable
    // only by switching back to Material, where the rows reappear, and while Miuix
    // is on there is nothing on screen saying the seed or the style matter at all.
    // Stated here because the alternative reading — that the reads were left behind
    // by mistake — ends with someone deleting them and the look quietly changing
    // again.
    val dynamic = remember(context, generation) { ThemePrefs.dynamicColor(context) }
    val seed = remember(context, generation) { ThemePrefs.seedColor(context) }
    val spec = remember(context, generation) { ThemePrefs.specVersion(context) }
    val variant = remember(context, generation) { ThemePrefs.paletteStyle(context) }
    // 莫奈颜色 on with 动态颜色 off means "from my seed, not from the wallpaper" —
    // the only way Miuix can be told that is keyColor; left null it reads the
    // platform accent, which is the wallpaper.
    //
    // The 强调色 row sits in front of that linkage now: an explicit pick made on
    // this look's own appearance page wins, 0 (follow the wallpaper) falls
    // through to the seed above so a colour decision made under Material keeps
    // steering this look exactly as before the row existed. Without the pick
    // winning, the row would be able to switch colours on and then watch the
    // stored seed override it — a choice that changes nothing.
    val accent = remember(context, generation) { ThemePrefs.miuixAccent(context) }
    val keyColor = when {
        accent != 0 -> Color(accent)
        !dynamic && seed != 0 -> Color(seed)
        else -> null
    }

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
        isDark = dark,
        keyColor = keyColor,
        colorSpec = if (spec == ThemePrefs.SPEC_2025) {
            ThemeColorSpec.Spec2025
        } else {
            ThemeColorSpec.Spec2021
        },
        paletteStyle = miuixPaletteStyle(variant)
    )

    // The light anchor: this look's own colour derivation run once more with
    // the light mode forced. The active palette cannot serve surfaces that
    // keep their light dress in the dark (the FAB plate — see
    // [LocalMiuixLightPrimary]), and there is no shortcut: the light primary
    // is a product of keyColor, spec and style, so it is derived, not stored.
    // Same inputs, same derivation, one mode pinned — and the anchor is a
    // colour source, never mounted as a theme.
    val lightAnchor = ThemeController(
        colorSchemeMode = if (monet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light,
        isDark = false,
        keyColor = keyColor,
        colorSpec = if (spec == ThemePrefs.SPEC_2025) {
            ThemeColorSpec.Spec2025
        } else {
            ThemeColorSpec.Spec2021
        },
        paletteStyle = miuixPaletteStyle(variant)
    )

    MiuixTheme(controller = controller) {
        val colors = MiuixTheme.colorScheme
        // Which ramp the surfaces are dressed in follows 颜色规格 as well — hidden,
        // but read, for the reason argued above.
        val expressive = spec == ThemePrefs.SPEC_2025
        // Both Material bridges key on `generation` as well as `colors`, and that
        // second key is load-bearing: Miuix's `Colors` is a singleton whose roles
        // mutate in place (state-backed fields, updated by updateColorsFrom), so
        // its object identity never changes — an identity-keyed remember holds the
        // old palette forever, which is exactly why a 莫奈颜色 flip used to land
        // only after the screen was reopened. The generation bump (every
        // appearance change invalidates) is what retires the stale mapping.
        val colorScheme = remember(colors, dark, generation) { colors.toMaterialColorScheme(dark) }
        val appSurfaces = remember(colors, colorScheme, generation) {
            AppSurfaces(
                card = colors.surfaceVariant,
                popup = if (dark) colors.surfaceContainerHighest else colors.surfaceContainer
            )
        }
        val appShapes = remember(expressive) { appShapesFor(expressive) }
        // The window chrome follows, too: it is View-side state no recomposition
        // reaches, and under this look the page colour is Miuix's.
        val pageArgb = colors.surface.toArgb()
        val pageDark = dark
        val windowActivity = (context as? android.app.Activity)
        // Guarded, not bare: this block runs after *every* recomposition of
        // this scope, and repainting the window (a fresh background drawable
        // each time) on an unchanged palette was a needless full-window redraw
        // queued behind unrelated state changes. Apply only when a value
        // actually moved; the plain array carries no snapshot state, so the
        // write cannot re-trigger composition.
        val lastPainted = remember { IntArray(2) }
        SideEffect {
            val darkBit = if (pageDark) 1 else 0
            if (windowActivity != null && (lastPainted[0] != pageArgb || lastPainted[1] != darkBit)) {
                ThemeSupport.reapplyWindow(windowActivity, pageArgb, pageDark)
                lastPainted[0] = pageArgb
                lastPainted[1] = darkBit
            }
        }
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HyperFCMLiveTypography,
            shapes = HyperFCMLiveShapes,
            motionScheme = motionSchemeFor(expressive),
        ) {
            CompositionLocalProvider(
                // MaterialTheme re-provides `LocalIndication` with an M3 ripple
                // (verified in the 1.5.0-alpha29 bytecode: the provider block at
                // MaterialTheme.kt:96 carries LocalIndication → ripple()), and it
                // sits *inside* MiuixTheme here, so every press under this look
                // — the overflow menu's rows, the dropdown-preference popups,
                // the app rows' tap and long-press — read its default indication
                // from M3, not from Miuix. Restate Miuix's own indication with
                // the exact construction MiuixTheme itself uses (keyed on
                // onBackground, the colour its overlay tints with), so the
                // press feedback is the library's spring-dimmed overlay again.
                LocalIndication provides remember(colors.onBackground) {
                    MiuixIndication(color = colors.onBackground)
                },
                LocalMiuixLightPrimary provides lightAnchor.lightColors.primary,
                LocalAppShapes provides appShapes,
                LocalAppSurfaces provides appSurfaces,
                // The 柔光玻璃 material, resolved once for the whole look. It is
                // provided to *both* Miuix-dressed styles rather than to 柔光玻璃
                // alone, because the bar-material row is its own decision: a person
                // on plain Miuix can still lift the foot onto the glass pill, and
                // the material would then degrade to its opaque fallback if this
                // provider had gated on the style. What stops the glass from
                // appearing on pages that did not ask for it is the call sites —
                // each one decides for itself whether to wear it, and none of them
                // runs under Material, which is the only place this provider does
                // not reach.
                LocalSoftGlassSpec provides MIUIX_SOFT_GLASS,
                LocalUiStyle provides style
            ) {
                content()
            }
        }
    }
}

/**
 * The 柔光玻璃 material this look reads: the recipes' own defaults, at the radius
 * they were tuned around, switched on. One value rather than one per surface —
 * the material is a fact about the look, and the surfaces that wear it are
 * expected to agree on it. Only the recipe changes per surface (a pill is not a
 * dialog), and the recipe is taken at the call site.
 */
private val MIUIX_SOFT_GLASS = createSoftGlassSpec(
    enabled = true,
    blurRadius = SoftGlassTuning.DefaultBlurRadius,
    fineTuning = SoftGlassFineTuning.Default,
)

/**
 * Miuix's palette styles are the same nine Material families the app offers, under
 * different names. Matched by writing them out: an ordinal mapping would be a
 * silent lie the first time either list grows, and it is exactly the kind of
 * coupling a hidden row cannot warn about.
 */
private fun miuixPaletteStyle(variant: Scheme.Variant): ThemePaletteStyle = when (variant) {
    Scheme.Variant.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
    Scheme.Variant.NEUTRAL -> ThemePaletteStyle.Neutral
    Scheme.Variant.VIBRANT -> ThemePaletteStyle.Vibrant
    Scheme.Variant.EXPRESSIVE -> ThemePaletteStyle.Expressive
    Scheme.Variant.RAINBOW -> ThemePaletteStyle.Rainbow
    Scheme.Variant.FRUIT_SALAD -> ThemePaletteStyle.FruitSalad
    Scheme.Variant.MONOCHROME -> ThemePaletteStyle.Monochrome
    Scheme.Variant.FIDELITY -> ThemePaletteStyle.Fidelity
    Scheme.Variant.CONTENT -> ThemePaletteStyle.Content
    // CMF has no Miuix-side equivalent — the Miuix look always colors through the 2021 spec
    // (see ThemePrefs.specVersion), so CMF falls back to its nearest neutral-tinted sibling.
    Scheme.Variant.CMF -> ThemePaletteStyle.TonalSpot
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
 * Every role is mapped verbatim. App-side surface policy — the three-level
 * page/card/popup mapping — lives in [AppPalette], so this bridge stays a
 * translation table instead of a second place the palette is decided. The
 * raised container and the popup level travel beside the scheme through
 * [LocalAppSurfaces] (see [AppSurfaces]).
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
