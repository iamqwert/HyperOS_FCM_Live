package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A group card's corners, as the Miuix look draws them: **continuous curvature**,
 * not a circular arc.
 *
 * The library's own `Card` is filled through `Modifier.squircleSurface`, whose
 * silhouette is the superellipse HyperOS draws everywhere — a squircle reaches
 * nearer the corner point than a `RoundedCornerShape` of the same radius, so at
 * 16dp a corner cut as an arc looks shaved next to a library card on the same
 * screen. Every card this app drew by hand was an arc, which made the app's own
 * rows the odd ones out on a page of `ArrowPreference`s.
 *
 * It is done in [createOutline] rather than by applying the squircle modifiers
 * at the call sites, and that is the load-bearing decision:
 *
 * - The same value has to reach `Surface`, `Modifier.clip` **and**
 *   `Modifier.textureBlur`, which only takes a `Shape`. A shape that *is* the
 *   squircle makes all three agree by construction; a modifier applied around
 *   them would mask the result but leave the blur's own fill an arc.
 * - A path costs no render layer. The glass cards already pay for one layer
 *   each to sample the backdrop, and a list keeps a card per visible row — a
 *   second mask layer per row would be the expensive way to move an edge
 *   0.6dp outward.
 *
 * [squircle] is the caller's reading of the library's own global switch
 * (`LocalSquircleEnabled` && the platform's runtime-shader check) — a path can
 * be drawn without a shader, but on a build where the library itself falls back
 * to arcs, matching it matters more than being prettier than it.
 */
@Immutable
class MiuixGroupShape(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
    private val squircle: Boolean = true,
) : Shape {

    private val rounded = RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)

    /**
     * Every corner square — an inner row of a connected group. There is no
     * silhouette to shave, so this is a rectangle and no path is built.
     */
    val isSquare: Boolean
        get() = topStart == 0.dp && topEnd == 0.dp && bottomEnd == 0.dp && bottomStart == 0.dp

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        if (!squircle || isSquare || size.width <= 0f || size.height <= 0f) {
            return rounded.createOutline(size, layoutDirection, density)
        }
        // The radii follow the start/end sides, exactly as RoundedCornerShape's
        // own outline does: an RTL layout mirrors the two that are not equal.
        val mirrored = layoutDirection == LayoutDirection.Rtl
        val topLeft: Float
        val topRight: Float
        val bottomRight: Float
        val bottomLeft: Float
        with(density) {
            topLeft = (if (mirrored) topEnd else topStart).toPx()
            topRight = (if (mirrored) topStart else topEnd).toPx()
            bottomRight = (if (mirrored) bottomStart else bottomEnd).toPx()
            bottomLeft = (if (mirrored) bottomEnd else bottomStart).toPx()
        }
        return Outline.Generic(
            squirclePath(
                width = size.width,
                height = size.height,
                topLeft = topLeft,
                topRight = topRight,
                bottomRight = bottomRight,
                bottomLeft = bottomLeft,
            )
        )
    }

    // `Shape` is compared by identity everywhere it is remembered, and a fresh
    // instance is built on every recomposition of a row — without these two the
    // call sites would see a new shape each time and rebuild their outline.
    override fun equals(other: Any?): Boolean = other is MiuixGroupShape &&
        other.topStart == topStart && other.topEnd == topEnd &&
        other.bottomEnd == bottomEnd && other.bottomStart == bottomStart &&
        other.squircle == squircle

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        result = 31 * result + squircle.hashCode()
        return result
    }
}

/**
 * The Bézier handle ratio that turns a circular arc into a continuous corner,
 * as the library's own path builder uses it (`SQUIRCLE_CONTROL` in
 * `miuix-squircle`'s `SquirclePath.kt`).
 *
 * Stated here rather than imported because the library keeps it `internal`, and
 * it is the one number this app has to share with it: the value is chosen so a
 * path-drawn corner and a shader-drawn one line up, and a different ratio would
 * make this app's cards visibly rounder or squarer than the library's own rows
 * on the same page. Two values are mirrored, not one — the control point offset
 * is `1 - ratio`, which is what the curve below needs.
 */
private const val SQUIRCLE_CONTROL = 0.643f

private const val SQUIRCLE_HANDLE = 1f - SQUIRCLE_CONTROL

/**
 * How far a corner's tile reaches along each edge, as a multiple of the corner
 * radius (`SquircleDefaults.Extension`).
 *
 * This is **not** decoration: `Path.addSquircleRect` builds its tile as
 * `cornerRadius * Extension`, so a continuous corner is 10% longer than the arc
 * it replaces. Drawing the tile at the plain radius instead tightens every
 * corner relative to a library `Card` beside it — small at 16dp, but the exact
 * "shaved" mismatch this shape exists to remove.
 */
private const val SQUIRCLE_EXTENSION = 1.1f

/**
 * A rounded rectangle whose four corners are continuous (superelliptical)
 * curves, each with its own radius in pixels. A zero radius leaves that corner
 * square.
 *
 * Drawn clockwise from the top edge; every corner is one cubic whose control
 * points sit [SQUIRCLE_HANDLE] of the tile in from the corner point, and whose
 * tile is the radius grown by [SQUIRCLE_EXTENSION] — the shape
 * `Path.addSquircleRect` builds for the uniform case, corner by corner.
 */
private fun squirclePath(
    width: Float,
    height: Float,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
): Path {
    val halfMin = minOf(width, height) * 0.5f
    // One corner's tile: the radius grown by the extension, then capped — a tile
    // can never be more than half the shorter side, or two corners on the same
    // edge would overlap and the outline would fold over itself.
    fun tile(radius: Float): Float =
        (radius * SQUIRCLE_EXTENSION).coerceIn(0f, halfMin)
    val tl = tile(topLeft)
    val tr = tile(topRight)
    val br = tile(bottomRight)
    val bl = tile(bottomLeft)
    val path = Path()
    path.moveTo(tl, 0f)
    path.lineTo(width - tr, 0f)
    if (tr > 0f) {
        val h = tr * SQUIRCLE_HANDLE
        path.cubicTo(width - h, 0f, width, h, width, tr)
    }
    path.lineTo(width, height - br)
    if (br > 0f) {
        val h = br * SQUIRCLE_HANDLE
        path.cubicTo(width, height - h, width - h, height, width - br, height)
    }
    path.lineTo(bl, height)
    if (bl > 0f) {
        val h = bl * SQUIRCLE_HANDLE
        path.cubicTo(h, height, 0f, height - h, 0f, height - bl)
    }
    path.lineTo(0f, tl)
    if (tl > 0f) {
        val h = tl * SQUIRCLE_HANDLE
        path.cubicTo(0f, h, h, 0f, tl, 0f)
    }
    path.close()
    return path
}
