package io.github.iamqwert.hypergreeze.ui

// The structure is MIUIX's GlassTopAppBar (miuix-glass,
// top.yukonga.miuix.kmp.glass.GlassTopAppBar) in its two shapes: 柔光玻璃 wears
// the DEFAULT progressive texture-blur pane, plain Miuix with its floating-bar
// row ON wears the non-progressive BAND (page colour, 28dp overhang, 40dp fade
// tail). Either way the chrome rests at nothing over the page and builds over
// 350ms once content scrolls under it; the controls above it ride their own
// glass discs.
//
// Two dressings, one frame — split by look so neither is changed behind its
// back:
//  - 柔光玻璃 wears the library's DEFAULT shape: the progressive texture-blur
//    pane (80dp, surface blended at 0.3) stretched over the title bar and the
//    pinned strips by `(statusBar + 52dp + bottomContent + 20dp) × 1.2`, fading
//    in over 32dp of content offset. That is the demo's default — its
//    `glassTopBarProgressiveBlur = true`, `enableBlur = true` — and it is why
//    the records page's strips ride inside the pane as `bottomContent`, exactly
//    the way IconPage mounts its segmented tab row.
//  - Plain Miuix with its floating-bar row ON wears the non-progressive shape:
//    the page colour held solid down the bar and faded across the last 40dp
//    (28dp overhang + 12dp tail, the 17-stop smoothstep curve). Its band is
//    opaque from the first pixel of overlap, so a hard fling never shows
//    unbanded rows through the bar.
//
// Both wear [MiuixBlurTopAppBar] for the large title at the library's 12dp
// radius. Plain Miuix with the floating bar OFF wears the FROSTED bar instead:
// the demo's BlurredBar recipe, the page's recorded layer blurred 25dp behind
// a transparent large-title bar (see [PageScaffold]). The apps and records
// pages are no longer exceptions: the apps page's search field now scrolls
// with the list, and the records page's two tab strips ride INSIDE the pane as
// `bottomContent` so one surface covers title bar and strips alike.
//
// What is NOT carried over, and why:
//  - `glass` over a backdrop as the library draws it: that is miuix-glass,
//    which has no 0.9.4 artifact (miuix-glass-android/0.9.4/*.pom → 404). The
//    band needs no backdrop; the controls wear 柔光玻璃's own glass disc over
//    the page's recorded layer, the closest stand-in for the library's
//    `puredThinGlass` + bloom stroke that this classpath can build.
//  - `MiuixIcons.Glass` (the 28dp glass glyphs, a separate unpublished
//    artifact): the bar keeps the 24dp `MiuixIcons.Back` every page wears.

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.iamqwert.hypergreeze.ui.softglass.LocalSoftGlassBackdrop
import io.github.iamqwert.hypergreeze.ui.softglass.LocalSoftGlassSpec
import io.github.iamqwert.hypergreeze.ui.softglass.SoftGlassIconButton
import io.github.iamqwert.hypergreeze.ui.softglass.SoftGlassRecipes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MIUIX's GlassTopAppBar numbers for the band. One constant per value, so a
 * later library release can be diffed against this table instead of a screen.
 */
object MiuixGlassTopAppBarDefaults {

    /** Start/end inset of the bar's controls — the page's own card inset. */
    val HorizontalPadding = 12.dp

    /** How far the band reaches past the bottom of the bar. */
    val BandOverhang = 28.dp

    /**
     * How far content has to scroll under the bar before the band reaches full
     * strength — the library's `RampDistance`, read against the content offset.
     */
    val BandRampDistance = 32.dp

    /** Full strength of the OS 4 progressive pane — the library's 80dp. */
    val ProgressiveBlurRadius = 80.dp

    /** Air the progressive pane keeps under the pinned chrome. */
    val ProgressivePaneExtra = 20.dp

    /** How far the progressive pane is stretched past its covered height. */
    const val ProgressivePaneScale = 1.2f

    /** How far the large title blurs out as it collapses — `GlassTopAppBarDefaults.TitleBlurRadius`. */
    val LargeTitleBlurRadius = 8.dp

    /** Progressive blur curve matching `GlassTopAppBar.kt`'s `TopBarProgressiveGradient`. */
    val ProgressiveGradient: ProgressiveBlur = ProgressiveBlur.Top.copy(curve = 0.25f)

    /** Glyph opacity while a control is held. */
    const val PressedContentAlpha = 0.6f

    /** The band fades in over this long once content is under the bar. */
    private const val BAND_RAMP_MS = 100

    /**
     * Fallback gradient band when no recorded page backdrop is available (e.g.
     * in a preview or outside [PageScaffold]).
     */
    fun bandBrush(color: Color): Brush = Brush.verticalGradient(
        0.00f to color,
        0.45f to color,
        0.58f to color.copy(alpha = 0.82f),
        0.72f to color.copy(alpha = 0.58f),
        0.86f to color.copy(alpha = 0.34f),
        1.00f to color.copy(alpha = 0f)
    )

    internal fun bandRamp(): FiniteAnimationSpec<Float> =
        tween(durationMillis = BAND_RAMP_MS, easing = LinearEasing)
}

/**
 * The Miuix title bar in MIUIX's GlassTopAppBar style, progressive blur OFF.
 *
 * Worn by 柔光玻璃 on every glass page, and by plain Miuix when its floating-bar
 * row is on. Both keep the band and the 28dp overhang; they differ in the band's
 * curve and the title — 柔光玻璃 wears its original gradient under the plain
 * large-title bar, while plain Miuix wears the library's current fade-brush
 * band under a large title that blurs out as it collapses
 * ([MiuixBlurTopAppBar], 12dp). Nothing samples the backdrop: the library's
 * `progressiveBlur` stays off, so the bar needs no recorded source and no
 * shader. The bar's control sits on a plate that builds with the same travel,
 * so a page at its top shows a bare glyph and nothing else.
 *
 * MIUIX's DEFAULT top app bar covers the remaining pages: plain Miuix with the
 * floating bar off wears it behind the frost described in [PageScaffold].
 *
 * @param scrolled whether content has begun to pass under the bar; drives both
 *   the band's fade and the plate's material.
 * @param navigationIcon overrides the leading control — the apps page's batch
 *   ✕. Null keeps [showBack]'s back arrow, or no control at all.
 * @param bottomContent chrome mounted in the SAME band as the title bar: the
 *   records page's two tab strips. The band is measured over the whole column,
 *   so content travelling under a pinned strip fades out with the same gradient
 *   instead of meeting a hard opaque row.
 * @param compactTitleAlpha non-null puts the compact (centred, single-line) bar
 *   up instead of the large-title one — the about page, whose hero is its
 *   title. The lambda is that title's opacity, and the page's own travel is its
 *   input.
 * @param scrollProgress Miuix only, and only meaningful under 柔光玻璃: the
 *   page's own travel, `0f` at the top and `1f` once its hero is gone. Supplied
 *   by the two pages whose bar is not driven by the scroll behavior (the about
 *   page's compact bar is pinned flat, and the records page hands its own
 *   reading in) — without it the progressive pane would only read
 *   `contentOffset` and stay hidden on exactly those pages. Null keeps the
 *   bar's own content-offset ramp.
 */
@Composable
fun MiuixGlassTopAppBar(
    title: String,
    scrolled: Boolean,
    showBack: Boolean,
    backDescription: String,
    onBack: () -> Unit,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    navigationIcon: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable ColumnScope.() -> Unit)? = null,
    compactTitleAlpha: (() -> Float)? = null,
    scrollProgress: (() -> Float)? = null
) {
    val bandAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = MiuixGlassTopAppBarDefaults.bandRamp(),
        label = "glassTopBarBand"
    )
    // Read here, not inside the layer: a colour is a value, not draw state.
    val bandColor = MiuixTheme.colorScheme.surface
    val useCollapseProgress = compactTitleAlpha == null && scrollProgress == null
    val bandRamp = remember(scrollBehavior, scrollProgress, useCollapseProgress, bandAlpha) {
        derivedStateOf {
            when {
                scrollProgress != null -> softGlassBarMaterialProgress(scrollProgress())
                useCollapseProgress -> {
                    val f = scrollBehavior.state.collapsedFraction
                    if (f.isNaN()) 0f else f.coerceIn(0f, 1f)
                }
                else -> bandAlpha
            }
        }
    }
    val overhangPx = with(LocalDensity.current) {
        MiuixGlassTopAppBarDefaults.BandOverhang.toPx()
    }
    val defaultControl: @Composable () -> Unit = if (showBack) {
        {
            MiuixGlassTopBarButton(
                onClick = onBack
            ) { tint, alpha ->
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = backDescription,
                    tint = tint,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { this.alpha = alpha }
                )
            }
        }
    } else {
        {}
    }
    val control = navigationIcon ?: defaultControl
    val measurements = remember { GlassTopBarMeasurements() }
    val barColumn: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (compactTitleAlpha != null) {
                SmallTopAppBar(
                    title = title,
                    titleColor = MiuixTheme.colorScheme.onSurface.copy(alpha = compactTitleAlpha()),
                    color = Color.Transparent,
                    navigationIcon = control,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                    navigationIconPadding = MiuixGlassTopAppBarDefaults.HorizontalPadding,
                    actionIconPadding = MiuixGlassTopAppBarDefaults.HorizontalPadding
                )
            } else {
                MiuixBlurTopAppBar(
                    title = title,
                    largeTitle = title,
                    largeTitleBlurRadius = MiuixGlassTopAppBarDefaults.LargeTitleBlurRadius,
                    color = Color.Transparent,
                    navigationIcon = control,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                    navigationIconPadding = MiuixGlassTopAppBarDefaults.HorizontalPadding,
                    actionIconPadding = MiuixGlassTopAppBarDefaults.HorizontalPadding
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        measurements.bottomContentHeight = placeable.height
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(0, 0)
                        }
                    }
            ) {
                Column { bottomContent?.invoke(this) }
            }
        }
    }
    // OS 4 wears MIUIX's `GlassTopAppBarLayout` + `progressiveTextureBlur`:
    // foreground measures first so `topBar` keeps its exact layout height,
    // while the progressive blur band overhangs downward by
    // `(systemBarsTop + CollapsedHeight + bottomContentHeight + 20dp) * 1.2`.
    val blurEnabled = LocalSoftGlassSpec.current.enabled
    val pageBackdrop = if (blurEnabled) LocalSoftGlassBackdrop.current else null
    val bandActive by remember(bandRamp) {
        derivedStateOf { bandRamp.value > 0.001f }
    }
    val topInsets = WindowInsets.systemBars
    Layout(
        modifier = modifier,
        content = {
            if (pageBackdrop != null) {
                val blendColors = remember(bandColor) {
                    listOf(BlendColorEntry(bandColor.copy(alpha = 0.3f)))
                }
                Box(
                    modifier = Modifier
                        .graphicsLayer { alpha = bandRamp.value }
                        .progressiveTextureBlur(
                            backdrop = pageBackdrop,
                            shape = RectangleShape,
                            gradient = MiuixGlassTopAppBarDefaults.ProgressiveGradient,
                            blurRadius = MiuixGlassTopAppBarDefaults.ProgressiveBlurRadius.value,
                            colors = BlurDefaults.blurColors(blendColors = blendColors),
                            enabled = bandActive,
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = bandAlpha
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            scaleY = if (size.height > 0f) {
                                (size.height + overhangPx) / size.height
                            } else {
                                1f
                            }
                        }
                        .background(MiuixGlassTopAppBarDefaults.bandBrush(bandColor))
                )
            }
            barColumn()
        }
    ) { measurables, constraints ->
        val foreground = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.constrainWidth(foreground.width)
        val height = constraints.constrainHeight(foreground.height)
        val bandHeight = if (pageBackdrop != null) {
            val coveredHeight = topInsets.getTop(this) +
                TopAppBarDefaults.CollapsedHeight.toPx() +
                measurements.bottomContentHeight
            ((coveredHeight + MiuixGlassTopAppBarDefaults.ProgressivePaneExtra.toPx()) *
                MiuixGlassTopAppBarDefaults.ProgressivePaneScale).roundToInt()
        } else {
            height
        }
        val background = measurables[0].measure(Constraints.fixed(width, bandHeight))
        layout(width, height) {
            background.placeRelative(0, 0)
            foreground.placeRelative(0, 0)
        }
    }
}

private class GlassTopBarMeasurements {
    var bottomContentHeight: Int = 0
}

/**
 * A bar control on the look's glass disc: the disc fades in with the page's
 * reveal, the glyph never does, and holding it drops the glyph to
 * [MiuixGlassTopAppBarDefaults.PressedContentAlpha] the way the library's own
 * button answers a finger.
 *
 * Both Miuix looks wear the same disc now — 柔光玻璃's own glass material over
 * the page's recorded layer, which is the closest thing this module can offer
 * to the library's `puredThinGlass` buttons (miuix-glass publishes no 0.9.4
 * artifact). The plain fallback plate (white/#2C2C2C fill, hairline rim) is
 * gone: next to the library's real glass it read as a flat chip.
 */
@Composable
fun MiuixGlassTopBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color, alpha: Float) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val contentAlpha by animateFloatAsState(
        targetValue = if (pressed) MiuixGlassTopAppBarDefaults.PressedContentAlpha else 1f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "glassTopBarButtonPress"
    )
    val reveal by animateFloatAsState(
        targetValue = LocalSoftGlassBarReveal.current,
        animationSpec = SOFT_GLASS_REVEAL_FADE,
        label = "glassTopBarButtonReveal"
    )
    SoftGlassIconButton(
        onClick = onClick,
        modifier = modifier,
        recipe = SoftGlassRecipes.floatingNavigation(LocalSoftGlassSpec.current.fineTuning),
        materialAlpha = reveal,
        content = { icon(MiuixTheme.colorScheme.onSurface, contentAlpha) }
    )
}
