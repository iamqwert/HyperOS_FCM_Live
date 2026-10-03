package io.github.howard20181.hyperos.fcmlive.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Motion, per colour-spec generation.
 *
 * This is the pillar that separates the two specs on the component layer:
 * animations built into Material components — item reordering, settling
 * states, shape morphs — read `MaterialTheme.motionScheme` instead of
 * hard-coding a tween. [MotionScheme.expressive] gives them the springy
 * overshoot curve; [MotionScheme.standard] is the flatter 2021 curve.
 *
 * Binding it to the colour spec is what stops one generation from wearing the
 * other's skin: picking "Material You (2021)" and still moving like Expressive
 * is the one thing the user would actually feel.
 *
 * Requires material3 1.5.0-alpha or newer: 1.4.0 ships this exact API but
 * declares it Kotlin-internal, so it cannot be referenced there.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun motionSchemeFor(expressive: Boolean): MotionScheme =
    if (expressive) MotionScheme.expressive() else MotionScheme.standard()

/**
 * The two MaterialTheme pillars that [HyperFCMLiveTheme] used to leave at their
 * library defaults: typescale and shape scale.
 *
 * Both restate official M3 spec values rather than inventing numbers of their
 * own, and they are spelled out in full even where the library default would
 * already match. That is deliberate: this file stays the one place the app's
 * type and shape scale can be read and changed, instead of a dozen call sites
 * each carrying their own `.sp` and `.dp`.
 *
 * The XML half of the scale (`res/values/type.xml`, `dimens.xml`, `shape.xml`,
 * `motion.xml`) was deleted once the last View screen became Compose. It had no
 * runtime consumer left, and a second copy of the same numbers is not a
 * cross-check — it is a copy that drifts, and one that ships in the APK.
 */
val HyperFCMLiveTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
)

/**
 * The generic shape scale — the M3 corner-radius tokens, restated here in dp.
 *
 * Both generations deliberately share these five values. M3 Expressive did not
 * redefine the original scale — the May 2025 update *added* three higher steps
 * (`Large increased` 20dp, `Extra large increased` 32dp, `Extra extra large`
 * 48dp) and moved components onto them. The lower five steps still mean exactly
 * what they meant in 2021, so the token definitions stay put and the App roles
 * below are what actually shifts.
 */
val HyperFCMLiveShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Shape roles this app actually paints.
 *
 * [HyperFCMLiveShapes] carries the generic scale; this carries the *mapping*,
 * which is where the two screens differ by intent rather than by taste: a
 * standalone card is ExtraLarge while a grouped container is Large, even though
 * both are "a rounded surface". The 2021 values were read back off the drawable
 * the View layer used to inflate, before that layer was retired, so this is
 * what the screens already looked like rather than a fresh decision.
 *
 * Provided through [LocalAppShapes] because MaterialTheme has only the five
 * generic slots and no room for App-specific roles.
 */
class AppShapes(
    val card: CornerBasedShape,
    val menu: CornerBasedShape,
    val menuItem: CornerBasedShape,
    val tooltip: CornerBasedShape,
    val fab: CornerBasedShape,
    /**
     * Corners of a connected group — the About and Licenses card stacks, where
     * rows touch and only the ends keep the group radius.
     *
     * Both come from the same steps the other roles use: the ends are [menu]
     * and the seams are [tooltip] (extraSmall), so the pair moves with the spec
     * for free.
     */
    val groupOuter: CornerBasedShape,
    val groupInner: CornerBasedShape,
) {
    companion object {
        /**
         * Material You (2021): the mapping the retired View layer was using.
         *
         * | role     | dp | step        | why                                       |
         * |----------|----|-------------|-------------------------------------------|
         * | card     | 16 | large       | same radius the About card group shows    |
         * | menu     | 16 | large       | menus and grouped cards share the card    |
         * |          |    |             | radius so they read as one surface        |
         * | menuItem | 8  | —           | tighter than large: a 40dp row needs a    |
         * |          |    |             | corner that reads, not one that curves    |
         * | tooltip  | 4  | extraSmall  | tooltips stay small in both specs         |
         * | fab      | 16 | large       | matches the card it floats above          |
         *
         * `card` is pinned to the group radius on purpose: one corner value for
         * every card on every screen — the standalone privacy cards, the switch
         * cards and the main list rows all match the four cards under the About
         * heading, instead of each role drifting to its own number.
         */
        val Baseline = AppShapes(
            card = RoundedCornerShape(16.dp),
            menu = RoundedCornerShape(16.dp),
            menuItem = RoundedCornerShape(8.dp),
            tooltip = RoundedCornerShape(4.dp),
            fab = RoundedCornerShape(16.dp),
            groupOuter = RoundedCornerShape(16.dp),
            groupInner = RoundedCornerShape(4.dp),
        )

        /**
         * M3 Expressive (2025): every role steps up one notch, using only the
         * steps Expressive actually added — no invented numbers.
         *
         * | role     | 2021 | 2025 | step the 2025 value comes from |
         * |----------|------|------|--------------------------------|
         * | card     | 16   | 20   | largeIncreased (group radius)  |
         * | menu     | 16   | 20   | largeIncreased                 |
         * | menuItem | 8    | 8    | unchanged: see Baseline        |
         * | tooltip  | 4    | 4    | extraSmall (unchanged on purpose: |
         * |          |      |      |  tooltips stay small in both specs) |
         * | fab      | 16   | 20   | largeIncreased                 |
         */
        val Expressive = AppShapes(
            card = RoundedCornerShape(20.dp),
            menu = RoundedCornerShape(20.dp),
            menuItem = RoundedCornerShape(8.dp),
            tooltip = RoundedCornerShape(4.dp),
            fab = RoundedCornerShape(20.dp),
            groupOuter = RoundedCornerShape(20.dp),
            // Unchanged like tooltip: a seam between two rows is not a place
            // Expressive asks for more radius.
            groupInner = RoundedCornerShape(4.dp),
        )
    }
}

internal fun appShapesFor(expressive: Boolean): AppShapes =
    if (expressive) AppShapes.Expressive else AppShapes.Baseline

val LocalAppShapes = staticCompositionLocalOf { AppShapes.Baseline }
