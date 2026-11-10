package io.github.howard20181.hyperos.fcmlive.ui

// The structure is MIUIX's GlassTopAppBar (miuix-glass,
// top.yukonga.miuix.kmp.glass.GlassTopAppBar) in its DEFAULT shape: a BAND
// rather than a blurred pane. A vertical gradient of the page colour, still
// solid 45% of the way down, gone by the bottom edge, overhanging the bar by
// 28dp so its foot never shows a hard line. Invisible while the page is at its
// top, fading in over 100ms the moment content scrolls under the bar; the
// controls above it build their own material separately, over 350ms.
//
// Every Miuix-dressed page wears this band: 柔光玻璃 on every page, and plain
// Miuix when its floating-bar row is ON — that row picks the title bar as well
// as the foot. MIUIX's DEFAULT top app bar covers the rest. The apps and
// records pages are no longer exceptions: the apps page's search field now
// scrolls with the list, and the records page's two tab strips ride INSIDE the
// band as `bottomContent` so one gradient covers title bar and strips alike.
// The library's other shape, the progressive texture blur pane, is deliberately
// not here: nothing in this app needs a RuntimeShader-backed bar any more.
//
// What is NOT carried over, and why:
//  - `glass` over a backdrop as the library draws it: that is miuix-glass,
//    which has no 0.9.4 artifact (miuix-glass-android/0.9.4/*.pom → 404). The
//    band needs no backdrop at all; the control plate falls back to the
//    library's own `buttonFill()` — white on light, #2C2C2C on dark — and
//    柔光玻璃's own glass disc where that look is live.
//  - `BlurTopAppBar`'s large-title blur-out: 0.9.4 publishes no
//    `BlurTopAppBar`, and its `TopAppBar` has neither `largeTitleBlurRadius`
//    nor `titleAlpha`. The title therefore collapses exactly as it did before.
//  - `MiuixIcons.Glass` (the 28dp glass glyphs, a separate unpublished
//    artifact): the bar keeps the 24dp `MiuixIcons.Back` every page wears.

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.ui.softglass.LocalSoftGlassSpec
import io.github.howard20181.hyperos.fcmlive.ui.softglass.SoftGlassIconButton
import io.github.howard20181.hyperos.fcmlive.ui.softglass.SoftGlassRecipes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
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

    /** Diameter of the plate behind a bar control. */
    val ButtonSize = 44.dp

    /** Glyph opacity while a control is held. */
    const val PressedContentAlpha = 0.6f

    /** The band fades in over this long once content is under the bar. */
    private const val BAND_RAMP_MS = 100

    /** The controls' material builds over this long, and back out over the same. */
    private const val MATERIAL_RAMP_MS = 350

    /**
     * The band: the page colour, solid to 45% of the bar's height, then a
     * straight fade that is gone by the overhang's end.
     */
    fun bandBrush(color: Color): Brush = Brush.verticalGradient(
        0.00f to color,
        0.45f to color,
        0.58f to color.copy(alpha = 0.82f),
        0.72f to color.copy(alpha = 0.58f),
        0.86f to color.copy(alpha = 0.34f),
        1.00f to color.copy(alpha = 0f)
    )

    /** The plate's fill where there is no backdrop to sample — the library's own pair. */
    @Composable
    fun buttonFill(): Color = if (isDark()) Color(0xFF2C2C2C) else Color.White

    /** The hairline the library's smallest bloom stroke leaves on a bar control. */
    @Composable
    fun buttonStroke(): Color = Color.White.copy(alpha = if (isDark()) 0.16f else 0.55f)

    @Composable
    internal fun isDark(): Boolean = MiuixTheme.colorScheme.background.luminance() < 0.5f

    internal fun bandRamp(): FiniteAnimationSpec<Float> =
        tween(durationMillis = BAND_RAMP_MS, easing = LinearEasing)

    internal fun materialRamp(): FiniteAnimationSpec<Float> =
        tween(durationMillis = MATERIAL_RAMP_MS, easing = LinearEasing)
}

/**
 * The Miuix title bar in MIUIX's GlassTopAppBar band style.
 *
 * Worn by 柔光玻璃 on every glass page, and by plain Miuix when its floating-bar
 * row is on. Nothing is blurred, so the bar needs no recorded source and no
 * render effect; the gradient alone separates it from the rows travelling under
 * it. The bar's control sits on a plate that builds with the same travel, so a
 * page at its top shows a bare glyph and nothing else.
 *
 * MIUIX's DEFAULT top app bar covers the remaining pages: plain Miuix with the
 * floating bar off. See [PageScaffold].
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
    compactTitleAlpha: (() -> Float)? = null
) {
    val bandAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = MiuixGlassTopAppBarDefaults.bandRamp(),
        label = "glassTopBarBand"
    )
    val materialAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = MiuixGlassTopAppBarDefaults.materialRamp(),
        label = "glassTopBarMaterial"
    )
    // Read here, not inside the layer: a brush is a value, not draw state.
    val band = MiuixGlassTopAppBarDefaults.bandBrush(MiuixTheme.colorScheme.surface)
    val overhangPx = with(LocalDensity.current) {
        MiuixGlassTopAppBarDefaults.BandOverhang.toPx()
    }
    val defaultControl: @Composable () -> Unit = if (showBack) {
        {
            MiuixGlassTopBarButton(
                onClick = onBack,
                materialAlpha = materialAlpha
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
    Box(modifier = modifier.fillMaxWidth()) {
        // The band, scaled from the top edge so the gradient's last stop
        // lands on the overhang's end rather than on the chrome column's own
        // edge. `matchParentSize` measures the WHOLE column below, so a page
        // mounting strips in [bottomContent] gets one gradient over its entire
        // pinned chrome instead of a solid row meeting a fading bar.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = bandAlpha
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleY = if (size.height > 0f) {
                        (size.height + overhangPx) / size.height
                    } else {
                        1f
                    }
                }
                .background(band)
        )
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
                TopAppBar(
                    title = title,
                    largeTitle = title,
                    color = Color.Transparent,
                    navigationIcon = control,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                    navigationIconPadding = MiuixGlassTopAppBarDefaults.HorizontalPadding,
                    actionIconPadding = MiuixGlassTopAppBarDefaults.HorizontalPadding
                )
            }
            bottomContent?.invoke(this)
        }
    }
}

/**
 * A bar control on a plate: the plate fades in with [materialAlpha], the glyph
 * never does, and holding it drops the glyph to
 * [MiuixGlassTopAppBarDefaults.PressedContentAlpha] the way the library's own
 * button answers a finger.
 *
 * The plate is the one place the two looks still differ: 柔光玻璃 wears its own
 * glass disc over the page's recorded layer, plain Miuix the library's
 * no-backdrop fill — white on a light page, #2C2C2C on a dark one, with a
 * hairline rim and a soft shadow to lift it off the rows. Both branches are
 * live: the floating-bar row puts plain Miuix on this glass bar too.
 */
@Composable
fun MiuixGlassTopBarButton(
    onClick: () -> Unit,
    materialAlpha: Float,
    modifier: Modifier = Modifier,
    size: Dp = MiuixGlassTopAppBarDefaults.ButtonSize,
    icon: @Composable (tint: Color, alpha: Float) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val contentAlpha by animateFloatAsState(
        targetValue = if (pressed) MiuixGlassTopAppBarDefaults.PressedContentAlpha else 1f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "glassTopBarButtonPress"
    )
    if (softGlassChrome()) {
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
        return
    }
    val fill = MiuixGlassTopAppBarDefaults.buttonFill()
    val stroke = MiuixGlassTopAppBarDefaults.buttonStroke()
    val ramp = materialAlpha.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .dropShadow(
                shape = CircleShape,
                shadow = Shadow(
                    radius = 6.dp,
                    color = Color.Black,
                    alpha = 0.06f * ramp
                )
            )
            .background(color = fill.copy(alpha = fill.alpha * ramp), shape = CircleShape)
            .border(width = 1.dp, color = stroke.copy(alpha = stroke.alpha * ramp), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        icon(MiuixTheme.colorScheme.onSurface, contentAlpha)
    }
}
