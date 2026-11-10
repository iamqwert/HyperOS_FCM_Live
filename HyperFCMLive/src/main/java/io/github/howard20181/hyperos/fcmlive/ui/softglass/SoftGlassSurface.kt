package io.github.howard20181.hyperos.fcmlive.ui.softglass

// Migrated from Deadliner 210e4a2d4c6bf7df291b6c6852d39366bc1fee17.
// Integration changes: package and MIUIX semantic UI bindings only.
//
// The source let a caller hand in its own tint and a tint *style*; nothing in
// this app ever did, because the look is one neutral wash everywhere. Both are
// gone, and the tint is resolved here from the look's own tuning.

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

object SoftGlassDefaults {
    val Shape: Shape = RoundedCornerShape(percent = 50)
    val IconButtonSize: Dp = 44.dp
}

/**
 * Layout-neutral high-level entry point for the soft-glass material.
 *
 * The caller owns size and padding. This layer only resolves the active
 * material, backdrop and optical recipe before delegating to
 * [softGlassMaterial].
 */
@Composable
fun SoftGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = SoftGlassDefaults.Shape,
    recipe: GlassMaterialRecipe? = null,
    interaction: GlassMaterialInteraction = GlassMaterialInteraction(),
    materialAlpha: Float = 1f,
    backdrop: LayerBackdrop? = LocalSoftGlassBackdrop.current,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val softGlass = LocalSoftGlassSpec.current
    val isDark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f
    val resolvedRecipe = recipe ?: SoftGlassRecipes.floatingNavigation(
        softGlass.fineTuning,
    )
    // The look's one wash: the tuned grey at the navigation alpha while the
    // material is live, and a nearly opaque version of the same grey when the
    // device cannot run it at all — the plate still has to be a plate.
    val resolvedTint = softGlass.fineTuning
        .softGlassTintColor(isDark)
        .copy(
            alpha = if (softGlass.enabled) {
                softGlass.navigationTintAlpha
            } else {
                0.92f
            },
        )

    Box(
        modifier = modifier.clip(shape),
        contentAlignment = contentAlignment,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = materialAlpha.coerceIn(0f, 1f)
                }
                .softGlassMaterial(
                    softGlass = softGlass,
                    backdrop = backdrop,
                    shape = shape,
                    tint = resolvedTint,
                    isDark = isDark,
                    recipe = resolvedRecipe,
                    interaction = interaction,
                ),
        )
        content()
    }
}

/** A circular soft-glass host for icon-only top-bar actions. */
@Composable
fun SoftGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    recipe: GlassMaterialRecipe? = null,
    materialAlpha: Float = 1f,
    backdrop: LayerBackdrop? = LocalSoftGlassBackdrop.current,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "soft-glass-icon-press",
    )
    val isDark = MiuixTheme.colorScheme.onSurface.luminance() > 0.5f
    val highlightColor = MiuixTheme.colorScheme.onSurface.copy(
        alpha = softGlassPressHighlightAlpha(pressProgress, isDark),
    )

    SoftGlassSurface(
        modifier = modifier
            .size(SoftGlassDefaults.IconButtonSize)
            .softGlassShadow(
                shape = SoftGlassDefaults.Shape,
                isDark = isDark,
                alpha = materialAlpha,
            )
            .graphicsLayer {
                val scale = softGlassPressScale(pressProgress)
                scaleX = scale
                scaleY = scale
            },
        shape = SoftGlassDefaults.Shape,
        recipe = recipe,
        backdrop = backdrop,
        materialAlpha = materialAlpha,
        interaction = softGlassPressMaterialInteraction(pressProgress),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(highlightColor),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
