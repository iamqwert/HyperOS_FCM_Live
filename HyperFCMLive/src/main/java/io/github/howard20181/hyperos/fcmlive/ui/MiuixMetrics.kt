package io.github.howard20181.hyperos.fcmlive.ui

import androidx.compose.ui.unit.dp

/**
 * The Miuix look's own numbers, in one place.
 *
 * Every value here is a reading of the library or of the system settings it
 * imitates — `CardDefaults.CornerRadius`, `BasicComponentDefaults.InsideMargin`,
 * the margin the reference apps leave around a card — and each was previously
 * spelled inline at the call site it was needed, with a comment naming the
 * source. A comment does not follow the library: when 0.9.4 moved the inside
 * margin, every one of those literals stayed behind and the look quietly drifted
 * away from the row it was imitating.
 *
 * Material's numbers are NOT here on purpose. They come from `MaterialTheme`'s
 * tokens and `LocalAppShapes`, which the framework keeps in step; a second table
 * of M3 values in this file would be the same drift with a smaller blast radius.
 */
object MiuixMetrics {

    /** `CardDefaults.CornerRadius` — the radius a group's outer ends take. */
    val CardRadius = 16.dp

    /**
     * The margin a settings page leaves around its cards. The reference apps
     * (and the system settings they imitate) run 12dp; M3's guideline is 16dp,
     * which is why [pageHorizontalInset] branches at all.
     */
    val PageInset = 12.dp

    /**
     * `BasicComponentDefaults.InsideMargin`'s horizontal value, and the vertical
     * margin a Miuix row is built on. The Material row sits 14dp — its own token
     * — so only the Miuix branch reads this.
     */
    val RowInsideMargin = 16.dp

    /** Gap between two blocks of one page: the reference's list spacing. */
    val PageGap = 12.dp

    /**
     * The gap the library's own switch rows leave between the switch and the
     * card's inside margin (`SwitchPreference`'s end padding). A hand-built row
     * that omits it sits tighter to the edge than every library row beside it.
     */
    val SwitchEndGap = 8.dp

    /**
     * The band the library's refresh header reserves but does not draw.
     *
     * `RefreshHeader` lays itself out at `circleSize + 36dp` (56dp at the
     * defaults) and fills that band with the circle (20dp), 6dp of air and one
     * line of 14sp bold text — 46dp or so, so ~10-13dp of the reserved height
     * is empty tail, the exact figure moving with the font's line box. The
     * list's first item starts where the RESERVED band ends, which is why a
     * pulled page shows that tail as an extra gap under the label; anchoring
     * the header one tail lower puts the label back on the content's own edge.
     * Erring high is safe: whatever is left over lands on the first block's own
     * opening air, where a few dp cannot be seen.
     *
     * The library exposes neither number (the 36dp is inline in
     * `RefreshHeader`), so this is a reading, not a reference — if a version
     * bump redraws the header, re-measure it instead of trusting the value.
     */
    val RefreshHeaderTail = 13.dp

    /** `SmallTitle`'s own inside margin: what a section header is drawn inside. */
    val SectionTitleStart = 16.dp
    val SectionTitleEnd = 8.dp

    /** Below the previous section's card, and below the top bar for the first. */
    val SectionTitleTop = 20.dp
    val SectionTitleTopFirst = 12.dp

    /** Above the cards the heading names. At 8 it read flush with them. */
    val SectionTitleBottom = 8.dp

    /**
     * The air a pinned chrome leaves between itself and the page content under
     * it, on the Miuix looks: the apps page's search capsule and the records
     * page's capsule strips both reserve it INSIDE the bar, which paints over
     * the rows travelling beneath them.
     *
     * Kept in the bar rather than in the list's content padding, because a
     * padding is only visible where the list comes to rest: as soon as it
     * scrolls under the bar the gap the eye reads is the bar's own tail, and
     * the app list used to keep 11dp of its opening air OUTSIDE the bar — its
     * gap collapsed from 17dp to 6dp the moment the list travelled. One number
     * inside the bar reads the same at rest and mid-scroll.
     *
     * 16dp is the gap the lists already keep at their foot (the clearance every
     * page is handed), so both ends of a page read alike; the records strips
     * used to breathe 12dp here, and the two pages' first lines did not meet.
     */
    val ChromeBreath = 16.dp

    /**
     * The six record categories under a DOCKED MIUIX bar, worn by the
     * library's `TabRowWithContour`. The floor is the strip's ONLY override:
     * the library clamps the row to its own 62dp minimum, six two-glyph labels
     * then run past one phone width and the strip turns into a scrolling
     * LazyRow, each slot far wider than its label needs.
     *
     * 40dp is the floor a two-glyph `body2` label needs plus air (14sp × a 1.1
     * font scale ≈ 30.8dp of glyph), not a width anyone should expect to see:
     * the row divides whatever it is given, which on a 400dp screen is ~56dp a
     * slot. The floor only decides when the strip starts scrolling instead of
     * squeezing, and scrolling is the graceful end of that road — the contour
     * row has no per-slot padding, so a squeezed slot clips the label rather
     * than the outline. The 5dp item gap and every other value stay the
     * library's own.
     *
     * Only the second-level strip reads it; the two 状态/事件 tabs keep the
     * library's own defaults, where the clamp is no hardship.
     */
    val CategoryTabMinWidth = 40.dp
}
