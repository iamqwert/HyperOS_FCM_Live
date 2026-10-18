package io.github.howard20181.hyperos.fcmlive.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.requiredSize
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.howard20181.hyperos.fcmlive.AppLocale
import io.github.howard20181.hyperos.fcmlive.LauncherIcon
import io.github.howard20181.hyperos.fcmlive.Prefs
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.UpdateChecker
import io.github.howard20181.hyperos.fcmlive.mcu.Hct
import io.github.howard20181.hyperos.fcmlive.mcu.Scheme
import io.github.howard20181.hyperos.fcmlive.theme.AppearanceState
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppShapes
import io.github.howard20181.hyperos.fcmlive.theme.LocalAppSurfaces
import io.github.howard20181.hyperos.fcmlive.theme.LocalUiStyle
import io.github.howard20181.hyperos.fcmlive.theme.MIUIX_ACCENT_OPTIONS
import io.github.howard20181.hyperos.fcmlive.theme.UiStyle
import io.github.howard20181.hyperos.fcmlive.theme.ThemePrefs
import kotlinx.coroutines.launch

/**
 * Settings: appearance, allowlist backup, links, update check.
 *
 * Everything that used to be a hand-built View lives here now — the three
 * appearance menus were a ~190 line in-window overlay (positioned, outlined and
 * animated by hand because a popup window clipped its own overshoot), and the
 * seed swatches a custom [android.graphics.drawable.Drawable]. Compose has a
 * menu and a canvas, so both collapse to a few lines each.
 *
 * Side effects the screen cannot own stay in [AboutActions]: the SAF
 * import/export needs an `ActivityResultRegistry`, and the theme's View-side
 * half — window chrome and the back-preview snapshot — is the Activity's
 * `applyAppearanceChange`. The palette itself never reaches the Activity: the
 * appearance rows write through [AppearanceState], whose setters invalidate
 * the engine, and the theme re-skins in place.
 */
data class AboutActions(
    val onViewSource: () -> Unit,
    /** Opens the GPL-3.0 licence text — the 「许可证」 row. */
    val onLicense: () -> Unit,
    val onLicenses: () -> Unit,
    val onExperiment: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
    /** Runs the update check; [report] carries back which answer to show. */
    val onCheckUpdate: (report: (UpdateOutcome) -> Unit) -> Unit,
    /** Opens the release page for the update just offered, and clears the badge. */
    val onUpdateOpen: () -> Unit,
    /** Version line, read from PackageManager — the update check's own source. */
    val versionLine: String,
    /**
     * The hero's release tag, read from PackageManager in the reference about
     * page's format ("v4.1.1 (53)").
     */
    val versionTag: String,
    /**
     * Repaints the View-side chrome a theme change must move but no
     * recomposition reaches: the window background and the back-preview
     * snapshot. The palette itself is invalidated by the [AppearanceState]
     * setters — this callback never touches it.
     */
    val onAppearanceChange: () -> Unit
)

/**
 * What a manual update check answered, in the terms the screen shows it.
 *
 * Three answers, not two: "no newer release" and "the check itself failed" read
 * very differently to someone who is wondering whether they are up to date, and
 * a failed check must not be allowed to look like a clean bill of health.
 */
sealed interface UpdateOutcome {
    /** Already on the newest release. */
    data object None : UpdateOutcome

    /** The check did not complete — no network, or nothing parseable came back. */
    data object Error : UpdateOutcome

    /** A newer release exists; [version] is what the offer names. */
    data class Available(val version: String) : UpdateOutcome
}

/** Theme modes in array order, i.e. [ThemePrefs.MODE_SYSTEM]..[ThemePrefs.MODE_DARK]. */
private val ALL_THEME_MODES = listOf(0, 1, 2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    actions: AboutActions,
    modifier: Modifier = Modifier,
    /** False hides the back control: the bottom-nav tab hosts this page. */
    showBack: Boolean = true,
    /**
     * False drops the「关于」section (update check, source, licences)
     * from the page. On the M3 home that section lives on its own「关于」tab
     * ([AboutTabScreen]) — carrying it on both tabs would be two copies of one
     * card; the Miuix AboutActivity keeps it, because there is no other home
     * for it there.
     */
    showProjectSection: Boolean = true,
    /**
     * The M3 feedback surface. Owned by the caller — the messages come from
     * there — and hosted here, because this is the tree on screen.
     */
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    PageScaffold(
        titleRes = R.string.settings,
        onBack = onBack,
        modifier = modifier,
        showBack = showBack,
        snackbarHostState = snackbarHostState,
        // A bare bar on both looks: the help entry used to sit here as a lone
        // Material icon, but on the M3 home it now lives on the about page
        // with the project rows; the Miuix branch never had a bar icon.
        actions = {}
    ) { innerPadding ->
        AboutBody(
            actions = actions,
            snackbarHostState = snackbarHostState,
            showProjectSection = showProjectSection,
            // The list spans the full body and takes the bar's height as
            // contentPadding, so rows scroll UNDER the title bar's glass
            // instead of stopping at its edge (see PageScaffold).
            topPadding = innerPadding.calculateTopPadding(),
            bottomPadding = innerPadding.calculateBottomPadding()
        )
    }
}

/**
 * The about page: the project section pulled out of the settings page and
 * given a page of its own — source, licence, the component list, and the
 * user guide that used to ride the M3 settings bar. Reached from the module
 * tab's about card; the settings tab drops the same section, so the card
 * exists exactly once either way.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalScrollBarApi::class)
@Composable
fun AboutTabScreen(
    onBack: () -> Unit,
    actions: AboutActions,
    modifier: Modifier = Modifier,
    /**
     * The M3 feedback surface. Owned by the caller — the messages come from
     * there — and hosted here, because this is the tree on screen.
     */
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val miuix = LocalUiStyle.current.isMiuixBased
    // The page's dynamic background is also what its glass is made of: the
    // gradient is recorded into this layer, the background fills it, and
    // everything drawn on top of it samples it — the hero's wordmark, the
    // section's cards. Handed to the whole page rather than to one item: the
    // hero rides in the list, and it is made of the backdrop just the same.
    val glassBackdrop = rememberLayerBackdrop()
    // The reference page's layout (the Example's AboutPage), element for
    // element: the hero is NOT a list item — it is the content box's first
    // child, drawn beneath the list and never moving, while the list above it
    // carries a transparent spacer (the reference's `logoSpacer`, keyed and
    // measured the same way) and then the cards. Scrolling slides the cards up
    // OVER the standing hero; the hero answers with the reference's own fade
    // clock — one [scrollProgress] over the spacer's height, the release tag
    // leaving first (0.05–0.20), then the wordmark (0.20–0.35), then the mark
    // (0.35–0.50), each with a 5% shrink. The card item then fills the rest of
    // the viewport (`fillParentMaxHeight`), which is what makes the page
    // scrollable on every screen even though it carries only two cards.
    val listState = rememberPageListState()
    val scrollProgress by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f

                else -> {
                    val spacer = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == LOGO_SPACER_KEY }
                    if (spacer != null && spacer.size > 0) {
                        (listState.firstVisibleItemScrollOffset.toFloat() / spacer.size)
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }
    // The fixed hero's logo column, measured live — the spacer's base (the
    // reference's `logoHeightDp`, the same 300dp stand-in until the first
    // layout pass reports).
    var logoHeightDp by remember { mutableStateOf(300.dp) }

    CompositionLocalProvider(
        LocalMiuixGlassBackdrop provides if (miuix) glassBackdrop else null
    ) {
        PageScaffold(
            titleRes = R.string.nav_about,
            onBack = onBack,
            modifier = modifier,
            snackbarHostState = snackbarHostState,
            // The look's own dynamic background — the OS3 mesh the system paints
            // behind its about pages, Miuix look only. It fades out with the
            // hero, the reference's own ramp: the mesh and the mark are one
            // picture, and the picture is the hero.
            background = if (miuix) {
                {
                    MiuixBgEffectBackground(
                        modifier = Modifier.layerBackdrop(glassBackdrop),
                        alpha = { 1f - scrollProgress }
                    )
                }
            } else {
                null
            },
            // Miuix only, and only because the hero above IS this page's title:
            // the compact bar, its title walking in as the hero walks out.
            barScrollProgress = if (miuix) scrollProgress else null
        ) { innerPadding ->
            val barTop = innerPadding.calculateTopPadding()
            Box(modifier = Modifier.fillMaxSize()) {
                // The reference's standing hero, drawn as the box's first child:
                // beneath the list, fixed for the life of the page. The list
                // slides its cards up over it; the hero's own fade clock answers.
                if (miuix) {
                    AboutHeroMiuix(
                        versionTag = actions.versionTag,
                        scrollProgressProvider = { scrollProgress },
                        topPadding = barTop,
                        onColumnHeight = { logoHeightDp = it }
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .listScrollEndHaptic()
                        // The reference leaves the list inset-free — its cards
                        // carry the page's 12dp margin themselves (the [Card]s
                        // below). The Material look keeps its own inset at the
                        // list, whose rows are full-width group members.
                        .padding(
                            start = if (miuix) 0.dp else pageHorizontalInset(),
                            end = if (miuix) 0.dp else pageHorizontalInset()
                        ),
                    state = listState,
                    // No bottom padding: the card column below owns its own foot
                    // (and the floating-bar clearance), the way the reference
                    // does — putting it on the list would double it.
                    contentPadding = PaddingValues(top = barTop)
                ) {
                    if (miuix) {
                        // The reference's transparent spacer — the list's first
                        // item, holding the standing hero's air open: its own
                        // measured height plus the 40+52dp over the logo column
                        // (the reference's `logoPaddingTop − scrollPaddingTop`
                        // term, with the bar's height cancelling) and the 126dp
                        // under the release tag. The fade clock measures this
                        // item; the cards scroll over the hero through it.
                        item(key = LOGO_SPACER_KEY) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(logoHeightDp + 52.dp + 40.dp + 126.dp)
                            )
                        }
                    } else {
                        // The Material hero stays an ordinary item: this look has
                        // its own collapsing large title, no compact-bar trick.
                        item {
                            AboutHeroMaterial(versionTag = actions.versionTag)
                        }
                    }
                    // One viewport of column under the cards: the guarantee that
                    // the list is the hero taller than the viewport on any
                    // screen, so the collapse is always reachable. On the Miuix
                    // look the cards are made of the page's own gradient —
                    // [textureBlur] blurs the recorded backdrop through each
                    // card instead of covering it, the reference's about page.
                    item(key = ABOUT_SECTION_KEY) {
                        Box {
                            Spacer(Modifier.fillParentMaxHeight())
                            Column(
                                modifier = Modifier.padding(
                                    bottom = innerPadding.calculateBottomPadding() +
                                        if (miuix) 0.dp else 12.dp
                                )
                            ) {
                                AboutProjectSection(
                                    actions = actions,
                                    snackbarHostState = snackbarHostState,
                                    aboutOnly = true
                                )
                            }
                        }
                    }
                }
                if (miuix) {
                    // The reference's scroll indicator, riding the same list.
                    VerticalScrollBar(
                        adapter = rememberScrollBarAdapter(listState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        trackPadding = PaddingValues(
                            top = barTop,
                            bottom = innerPadding.calculateBottomPadding()
                        )
                    )
                }
            }
        }
    }
}

/**
 * The about hero under the Miuix look — the reference Miuix app's about page
 * composition (the Example's AboutPage), element for element: the full-colour
 * launcher icon at 88dp (no plate behind it — the app's icon is full-bleed,
 * unlike the reference's inset one), the app name in 35sp bold (12dp over, 5dp
 * under) and the release tag in 14sp beneath, centred. No card, no section
 * title, no subtitle.
 *
 * The hero STANDS — it is the content box's first child, beneath the scrolling
 * list, and never moves; the cards slide up over it through the transparent
 * spacer item, and it answers with the reference's fade clock. The fades
 * themselves are this page's own zero-layer pipeline, though — NOT the
 * reference's deferred `graphicsLayer` lambdas: every layer-bearing node this
 * hero ever wore mis-drew (the mark offset left, then absent until a scroll
 * forced a redraw), while ink-alpha fades have never missed a frame. The tag
 * leaves first (0.05–0.20), then the wordmark (0.20–0.35), then the mark
 * (0.35–0.50) of the spacer's height; the 5% shrink rides the layer and is
 * gone with it — five percent during a fade is beyond noticing.
 *
 * The wordmark is made of the page's own dynamic gradient: [textureBlur] with
 * `DstIn` keeps the blurred backdrop where the text's alpha is, and the
 * reference's blend passes push that sample to the vivid, moving ink the look
 * is known for. With no backdrop there is nothing to sample and the plain ink
 * stands. The mark itself wears no glass — the reference shows it as the
 * launcher icon the home screen shows, white plate and all.
 *
 * @param topPadding The bar's height: the reference's hero rides `topBar + 40dp`
 *   of air over the logo column, with 52dp more inside the column itself.
 * @param onColumnHeight Reports the logo column's own height, air excluded —
 *   the base the spacer's height is built from (the reference's
 *   `logoHeightDp`, measured by `onSizeChanged` the same way).
 */
@Composable
private fun AboutHeroMiuix(
    versionTag: String,
    /** The page's one clock: the scroll fraction over the spacer's height. */
    scrollProgressProvider: () -> Float,
    topPadding: Dp,
    onColumnHeight: (Dp) -> Unit
) {
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The reference's own air: the bar's height, 40dp over the logo
            // column, and 52dp more inside it. The 126dp under the release tag
            // is NOT here — it belongs to the list's spacer item, the reference
            // keeps the two apart and so does the spacer arithmetic above.
            .padding(
                top = topPadding + 40.dp + 52.dp,
                start = WindowInsets.displayCutout.asPaddingValues()
                    .calculateLeftPadding(LayoutDirection.Ltr),
                end = WindowInsets.displayCutout.asPaddingValues()
                    .calculateRightPadding(LayoutDirection.Ltr)
            )
            // The reference's measurement: the column's own extent, its air
            // excluded — the spacer's base.
            .onSizeChanged { size ->
                with(density) { onColumnHeight(size.height.toDp()) }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The page's gradient, or null on a page with none to sample — the
        // same local the about page's cards are made of.
        val backdrop = LocalMiuixGlassBackdrop.current
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val nameGlass = if (backdrop == null) {
            Modifier
        } else {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RoundedCornerShape(16.dp),
                blurRadius = NAME_BLUR_RADIUS,
                colors = BlurColors(
                    blendColors = if (isDark) LOGO_BLEND_DARK else LOGO_BLEND_LIGHT
                ),
                contentBlendMode = ComposeBlendMode.DstIn,
                enabled = true
            )
        }
        // The launcher icon alone — no plate behind it: the app's adaptive
        // icon is full-bleed (the home screen masks it round itself), so a
        // white backing would only ring it with a frame. The rounding is
        // baked into the raster itself ([rememberLauncherIconPainter]). The
        // fade rides the Image's own alpha, read in composition — no
        // graphicsLayer anywhere on this node: every layer-bearing variant of
        // the mark mis-drew in this overlay (offset left, then absent until a
        // scroll forced a redraw).
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val iconProgress =
                ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
            Image(
                painter = rememberLauncherIconPainter(),
                contentDescription = null,
                alpha = 1f - iconProgress,
                modifier = Modifier.size(88.dp)
            )
        }
        // The wordmark fades through its INK, not a layer: with the glass on,
        // the DstIn pass multiplies the blur sample by the text's own alpha,
        // so an in-ink fade fades the whole glass. The node is made to match
        // the glass cards' proven shape in every respect the blur can see —
        // full-width, no graphicsLayer ahead of the blur.
        val nameProgress =
            ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
        MiuixText(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 5.dp)
                .then(nameGlass),
            text = stringResource(R.string.launcher_name),
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 1f - nameProgress),
            fontWeight = FontWeight.Bold,
            fontSize = 35.sp,
            textAlign = TextAlign.Center
        )
        // "v4.1.1 (53)" — the release tag, not a sentence about versions.
        // First of the three stages to leave, through its ink like the
        // wordmark above: no deferred layer on the node.
        //
        // The ink is the reference's own — colorScheme.onSurfaceVariantSummary,
        // never interpolated — and the reference never rewrites its alpha:
        // that token is 0x80FFFFFF, a colour that CARRIES its half alpha, so
        // the tag is the soft grey beside the wordmark the moment it is
        // painted. KernelSU fades it with a LAYER over that ink
        // (graphicsLayer { alpha = 1 - versionCodeProgress }, plus a 5%
        // shrink), which multiplies layer × ink and leaves the 50% intact at
        // rest.
        //
        // This hero wears no layers (see the note above the wordmark), so the
        // fade multiplies the token's own alpha in ink instead —
        // tagInk.alpha * (1 - tagProgress) — the same product the reference's
        // layer produces. What it must never do is REPLACE that alpha:
        // copy(alpha = 1f - progress) paints the tag 0xFFFFFFFF at rest, a
        // second headline under the wordmark instead of the reference's grey.
        val tagProgress =
            ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
        val tagInk = MiuixTheme.colorScheme.onSurfaceVariantSummary
        MiuixText(
            modifier = Modifier.fillMaxWidth(),
            text = versionTag,
            color = tagInk.copy(alpha = tagInk.alpha * (1f - tagProgress)),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

/** The key of the list's transparent spacer — [scrollProgress]'s denominator. */
private const val LOGO_SPACER_KEY = "logoSpacer"

/** The key of the cards' item, the one viewport of column under the hero. */
private const val ABOUT_SECTION_KEY = "about"

/** The wordmark glass's blur radius, in the blur library's own units. */
private const val NAME_BLUR_RADIUS = 150f

/**
 * The ink the wordmark's glass is pushed to, copied from the reference: a
 * colour-burn, a linear light and a saturated Lab pass on light pages, a
 * colour-dodge, a linear light and the same Lab pass on dark ones. The Lab
 * passes are what inject the colour the text would otherwise not have.
 */
private val LOGO_BLEND_LIGHT = listOf(
    BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
    BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
    BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab)
)
private val LOGO_BLEND_DARK = listOf(
    BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
    BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
    BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab)
)

/**
 * The adaptive launcher icon rasterised once — [painterResource] refuses an
 * [android.graphics.drawable.AdaptiveIconDrawable] — so the hero can wear the
 * full-colour icon the home screen shows, not just its monochrome foreground.
 *
 * The home screen's 24dp-on-88dp corner rounding is baked into the raster
 * itself (the same 24/88 ratio the Miuix hero wears): masking here, in the
 * canvas, keeps every consumer of this painter layer-free — a Compose-side
 * clip on the hero's mark mis-drew its first frame in the overlay.
 */
@Composable
private fun rememberLauncherIconPainter(): androidx.compose.ui.graphics.painter.Painter {
    val context = LocalContext.current
    return remember {
        val drawable = androidx.core.content.ContextCompat.getDrawable(
            context, R.mipmap.ic_launcher
        ) ?: error("no launcher icon")
        val side = 432 // 108dp at 4x: plenty for a 74dp mark
        val bitmap = android.graphics.Bitmap.createBitmap(
            side, side, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        // 24dp rounding on an 88dp mark, scaled to this raster.
        val radius = side * (24f / 88f)
        // The FULL adaptive canvas, drawn as-is. A launcher-viewport crop
        // (middle 72dp of the 108dp canvas) was tried and rejected: this
        // icon's foreground is drawn past the AOSP safe zone, and the ROM's
        // own mask is wider than 72dp anyway, so the crop clipped the mark
        // mid-shape. The home screen's exact mask is the launcher's private
        // geometry — not recoverable from the public APIs — so the raster
        // shows every pixel the icon's designer drew, and the rounding below
        // is the only mask applied.
        drawable.setBounds(0, 0, side, side)
        drawable.draw(canvas)
        // The rounding goes in as an anti-aliased alpha mask, not a
        // `clipPath`: a software-canvas clip is aliased — every edge pixel is
        // either kept whole or dropped — and against the page's moving
        // gradient that hard edge read as a rough outline around the mark.
        // Painting the rounded rect through DST_IN keeps the icon wherever the
        // mask is opaque and scales the boundary pixels' alpha by the mask's
        // own anti-aliased coverage, so the edge dissolves into the page.
        val mask = android.graphics.Path().apply {
            addRoundRect(
                android.graphics.RectF(0f, 0f, side.toFloat(), side.toFloat()),
                radius, radius, android.graphics.Path.Direction.CW
            )
        }
        val maskPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            @Suppress("DEPRECATION")
            xfermode = android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN
            )
        }
        canvas.drawPath(mask, maskPaint)
        androidx.compose.ui.graphics.painter.BitmapPainter(bitmap.asImageBitmap())
    }
}

/**
 * The same hero under the Material look: the launcher icon as the home screen
 * shows it (rasterised once by [rememberLauncherIconPainter]), name and release
 * tag in M3 type.
 */
@Composable
private fun AboutHeroMaterial(versionTag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The bottom is the hero-to-card gap, the same 126dp the reference
            // about page keeps under the release tag: the air between the tag
            // and the page's one card is the look's own, not each hero's
            // business, so the two looks keep the card at the same line.
            .padding(top = 32.dp, bottom = 126.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = rememberLauncherIconPainter(),
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = stringResource(R.string.launcher_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = versionTag,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The「关于」card. Self-contained — it carries the update state the settings
 * body used to hold, because on the M3 home it is the whole「关于」tab.
 *
 * In [aboutOnly] mode it is exactly the three rows the reference about page
 * keeps: 「查看源代码」 and 「许可证」, both to the GPL-3.0 licence text, and
 * 「开放源代码许可」 to the component list. The full settings page keeps the
 * update check and its badge as well; the privacy page is gone everywhere.
 */
@Composable
private fun AboutProjectSection(
    actions: AboutActions,
    snackbarHostState: SnackbarHostState,
    aboutOnly: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateBadge by remember { mutableStateOf(UpdateChecker.isUpdateAvailable(context)) }
    // The offer, while it is on screen. Keeping it in the section rather than
    // the Activity is what makes the dialog a Compose dialog: it is screen
    // state, and the Activity only supplies the URL it would open.
    var updateOffer by remember { mutableStateOf<UpdateOutcome.Available?>(null) }

    val checkingText = stringResource(R.string.update_checking)
    val noneText = stringResource(R.string.update_none)
    val errorText = stringResource(R.string.update_error)
    val updateOpenLabel = stringResource(R.string.update_open)
    val cancelLabel = stringResource(android.R.string.cancel)
    // Read here, in composition, not inside show(): LocalUiStyle.current is a
    // @Composable read and show() is a plain function.
    val miuixNow = LocalUiStyle.current.isMiuixBased

    fun show(text: String) {
        if (miuixNow) {
            // This look's one-line acknowledgment is the system toast, not the
            // Material bar at the page's foot.
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            // One line at a time: the message replacing the current one should
            // not have to wait out its predecessor's four seconds first.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    updateOffer?.let { offer ->
        AppDialog(
            onDismissRequest = { updateOffer = null },
            title = stringResource(R.string.update_found_title),
            // The badge is cleared where the URL is opened, so the stored flag
            // and what is on screen can only move together.
            text = stringResource(R.string.update_found, offer.version),
            confirmLabel = updateOpenLabel,
            onConfirm = {
                updateBadge = false
                actions.onUpdateOpen()
            },
            dismissLabel = cancelLabel
        )
    }

    // No section title above the rows: the reference about page reads as one
    // hero + one list, and the page's own title bar already names the place.
    //
    // Under Miuix the body IS the reference about page's own, verbatim: two
    // separate cards with the page's 12dp margin and a 12dp seam between them,
    // rows of title + right-end value text — no icons, no summary lines. The
    // Material look keeps its own group card, the same three rows single-line.
    if (LocalUiStyle.current.isMiuixBased) {
        Column {
            MiuixCardSurface(
                shape = connectedGroupShape(first = true, last = true),
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                ArrowPreference(
                    title = stringResource(R.string.view_source_code),
                    onClick = actions.onViewSource,
                    endActions = { AboutRowValue("GitHub") }
                )
            }
            MiuixCardSurface(
                shape = connectedGroupShape(first = true, last = true),
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
            ) {
                // [MiuixCardSurface]'s [Surface] is a box — its children stack
                // rather than arrange — so the two rows of this card must sit
                // in a column, exactly as the reference's own [Card] (whose
                // content is a column scope) would lay them out.
                Column(Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.about_license),
                        onClick = actions.onLicense,
                        endActions = { AboutRowValue("GPL-3.0") }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.open_source_licenses),
                        onClick = actions.onLicenses
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        return
    }
    SettingsGroup {
        if (!aboutOnly) {
            NavCard(
                iconRes = R.drawable.ic_check_update,
                title = stringResource(R.string.check_for_updates),
                subtitle = stringResource(R.string.check_for_updates_sub),
                onClick = {
                    show(checkingText)
                    actions.onCheckUpdate { outcome ->
                        updateBadge = outcome is UpdateOutcome.Available
                        when (outcome) {
                            UpdateOutcome.None -> show(noneText)
                            UpdateOutcome.Error -> show(errorText)
                            is UpdateOutcome.Available -> updateOffer = outcome
                        }
                    }
                },
                first = true,
                last = false,
                trailing = {
                    if (updateBadge) {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                    }
                }
            )
            NavCard(
                iconRes = R.drawable.ic_view_source,
                title = stringResource(R.string.view_source_code),
                subtitle = "",
                onClick = actions.onViewSource,
                first = false,
                last = false,
                trailing = { AboutRowValue("GitHub") }
            )
        } else {
            NavCard(
                iconRes = R.drawable.ic_view_source,
                title = stringResource(R.string.view_source_code),
                subtitle = "",
                onClick = actions.onViewSource,
                first = true,
                last = false,
                trailing = { AboutRowValue("GitHub") }
            )
        }
        NavCard(
            iconRes = R.drawable.ic_license,
            title = stringResource(R.string.about_license),
            subtitle = "",
            onClick = actions.onLicense,
            first = false,
            last = false,
            trailing = { AboutRowValue("GPL-3.0") }
        )
        NavCard(
            iconRes = R.drawable.ic_description,
            title = stringResource(R.string.open_source_licenses),
            subtitle = "",
            onClick = actions.onLicenses,
            first = false,
            last = true
        )
    }
}

/**
 * The right-end value of an about row — the reference about page's own quiet
 * grey ("GitHub", "Apache-2.0"), the library's body2 in the summary ink under
 * the Miuix look, M3's bodyMedium in the variant ink under Material.
 */
@Composable
private fun AboutRowValue(text: String) {
    if (LocalUiStyle.current.isMiuixBased) {
        MiuixText(
            text = text,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions
        )
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutBody(
    actions: AboutActions,
    snackbarHostState: SnackbarHostState,
    showProjectSection: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Everything appearance-related reads its live value straight from
    // [AppearanceState] — the Compose state layer over ThemePrefs — so there is
    // no remembered mirror to keep in step: a setter writes the pref, the state
    // every reader subscribes to, and the palette engine in one call. The
    // non-appearance values keep local state: the launcher-icon visibility is a
    // system-side setting with no palette behind it, and the update badge is
    // screen state by definition.
    var hideIcon by remember { mutableStateOf(LauncherIcon.isHidden(context)) }
    // Read once per screen: the hook side owns the live value, and the mirror
    // is only the answer the UI last left behind (same rule as the experiment
    // page's mirrors).
    var wechatDozeKeepout by remember { mutableStateOf(Prefs.readLocalWechatDozeKeepout(context)) }
    var sleepKeepalive by remember { mutableStateOf(Prefs.readLocalSleepKeepalive(context)) }
    var sleepKeepaliveData by remember {
        mutableStateOf(Prefs.readLocalSleepKeepaliveData(context))
    }
    var debugLog by remember {
        mutableStateOf(Prefs.readLocalDebugLog(context))
    }
    val themeMode by AppearanceState.themeMode
    val uiStyle by AppearanceState.uiStyle
    val dynamic by AppearanceState.dynamicColor
    val seed by AppearanceState.seedColor
    val miuixMonet by AppearanceState.miuixMonet
    val miuixAccent by AppearanceState.miuixAccent
    val floatingBar by AppearanceState.floatingBar
    val paletteStyle by AppearanceState.paletteStyle
    val colorSpec by AppearanceState.colorSpec
    // The whole theme section is scoped by this: 动态颜色 / 调色风格 / 颜色规格 are
    // the app's own colour engine's controls, and the Miuix look does not go
    // through that engine at all. A row that changes nothing has no business being
    // on screen, and a row that is hidden but still feeding the look is worse —
    // see MiuixStyleTheme, which stops reading them for exactly that reason.
    // Both Miuix-dressed looks, not just [ThemePrefs.UI_STYLE_MIUIX]: 柔光玻璃 is
    // the same screens and must see the same rows, or half of its settings would
    // go missing the moment the material changed.
    val miuixStyle = uiStyle != ThemePrefs.UI_STYLE_MATERIAL

    val modeEntries = stringArrayResource(R.array.theme_mode_entries)
    val uiStyleEntries = stringArrayResource(R.array.ui_style_entries)
    val floatingBarEntries = stringArrayResource(R.array.floating_bar_entries)
    val styleEntries = stringArrayResource(R.array.palette_style_entries)
    val specEntries = stringArrayResource(R.array.color_spec_entries)
    // 强调色's menu: entry 0 is the stored 0 (跟随壁纸), the rest are
    // [MIUIX_ACCENT_OPTIONS] in order. One list for labels, one for values, the
    // same index over both — the mapping the pick writes through.
    val accentEntries = stringArrayResource(R.array.miuix_accent_entries)
    val accentValues = remember { listOf(0) + MIUIX_ACCENT_OPTIONS.toList() }
    // 语言下拉：选项是各自的原生名（简体中文 / English），不翻译，所以这份
    // 数组只有 values/ 一份定义。
    val languageEntries = stringArrayResource(R.array.language_entries)

    // The two appearance menus are scoped by each other, because the settings
    // are not independent: the 2025 spec covers four palette styles and nothing
    // else (`Scheme.Variant.supportsExpressive2025`), and the 2026 spec covers
    // exactly one style — CMF, whose scheme rejects every other spec version.
    //
    // The spec menu stays complete — every spec is always listed, because it
    // is the primary setting and the way *out* of any pairing: picking a spec
    // migrates the style to one that can run it (see ThemePrefs.setSpecVersion;
    // 2026 ⇒ CMF and the palette row locks, 2025 ⇒ an incompatible style moves
    // to Tonal Spot). The palette menu, the subordinate one, lists only what
    // the spec in effect can render, and locks entirely under 2026.
    //
    // Note neither list can strand the current value: an in-effect 2025 implies
    // by construction a style that supports it (the write side migrates), so
    // that style is always in the filtered list; CMF implies 2026.
    val paletteVariants = Scheme.Variant.entries.filter {
        when (colorSpec) {
            ThemePrefs.SPEC_2025 -> it.supportsExpressive2025
            // The row is locked here; the full list keeps CMF, its shown value,
            // indexable.
            ThemePrefs.SPEC_2026 -> true
            // 2021 runs everything except CMF — that style belongs to 2026
            // alone, and the spec menu above is its only door.
            else -> it != Scheme.Variant.CMF
        }
    }
    val paletteStyleNow = variantAt(paletteStyle)
    val specValues = listOf(ThemePrefs.SPEC_2021, ThemePrefs.SPEC_2025, ThemePrefs.SPEC_2026)

    val hideText = stringResource(R.string.hide_icon_toast)
    val showText = stringResource(R.string.show_icon_toast)
    // Read here, in composition, not inside show(): LocalUiStyle.current is a
    // @Composable read and show() is a plain function.
    val miuixNow = LocalUiStyle.current.isMiuixBased

    fun show(text: String) {
        if (miuixNow) {
            // This look's one-line acknowledgment is the system toast, not the
            // Material bar at the page's foot.
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            // One line at a time: the message replacing the current one should
            // not have to wait out its predecessor's four seconds first.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    val inset = pageHorizontalInset()
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .listScrollEndHaptic()
            .padding(start = inset, end = inset),
        // The four appearance menus all re-skin the app on a pick, so this page is
        // the one place a menu could visibly throw the reader back to the start.
        state = rememberPageListState(),
        // The gesture-hint strip is scroll-through, not a hard stop: rows pass
        // under it while scrolling and only rest that far above the edge. The
        // top inset rides here (not on the modifier) so rows pass under the
        // glass bar while scrolling.
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding)
    ) {
        item {
            SectionTitle(R.string.about_section_display, first = true)
            SettingsGroup {
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_hide_icon,
                    title = stringResource(R.string.hide_launcher_icon),
                    description = "",
                    stateLine = stringResource(R.string.about_sub_hide_icon_on),
                    stateLineOff = stringResource(R.string.about_sub_hide_icon_off),
                    checked = hideIcon,
                    onCheckedChange = { checked ->
                        LauncherIcon.setHidden(context, checked)
                        show(if (checked) hideText else showText)
                        hideIcon = checked
                    },
                    first = true,
                    last = false
                )
                // 语言选择交给平台的 LocaleManager：写下去系统自己持久化并重建
                // 界面，本应用不落偏好。未选择时的默认（ currentIndex 的取值）
                // 走资源回退——values/ 英文、values-zh/ 简体中文，任意中文变体
                // 的系统都读到简体。
                PopupCard(
                    iconRes = R.drawable.ic_language,
                    title = stringResource(R.string.language),
                    subtitle = stringResource(R.string.about_sub_language),
                    entries = languageEntries.toList(),
                    currentIndex = AppLocale.current(context),
                    first = false,
                    last = true,
                    onPick = { index -> AppLocale.apply(context, index) }
                )
            }
        }

        item {
            SectionTitle(R.string.about_section_theme)
            val visibleModes = ALL_THEME_MODES
            SettingsGroup {
                PopupCard(
                    iconRes = R.drawable.ic_theme_mode,
                    title = stringResource(R.string.theme_mode),
                    subtitle = stringResource(R.string.about_sub_theme_mode),
                    entries = visibleModes.map { modeEntries[it] },
                    currentIndex = visibleModes.indexOf(themeMode),
                    first = true,
                    last = false,
                    onPick = { index ->
                        AppearanceState.setThemeMode(context, visibleModes[index])
                        actions.onAppearanceChange()
                    }
                )
                PopupCard(
                    iconRes = R.drawable.ic_ui_style,
                    title = stringResource(R.string.ui_style),
                    subtitle = stringResource(R.string.about_sub_ui_style),
                    entries = uiStyleEntries.toList(),
                    currentIndex = uiStyle,
                    first = false,
                    last = false,
                    onPick = { index ->
                        AppearanceState.setUiStyle(context, index)
                        actions.onAppearanceChange()
                    }
                )
                // Only the Miuix look reads this. Under the app's own look the colours
                // come from its engine and 动态颜色 below is the switch for them, so a
                // second Monet row would offer a choice that changes nothing — and the
                // one that does change something would be a row away from it.
                if (miuixStyle) {
                    // The floating-bar row belongs to the Miuix look alone: the
                    // SoftGlass look always floats its own glass tab bar, so the
                    // switch would name a choice the home frame is not offering.
                    // Whatever rows precede it close the group when it is gone.
                    val barRowVisible = uiStyle == ThemePrefs.UI_STYLE_MIUIX
                    SettingsSwitchCard(
                        iconRes = R.drawable.ic_dynamic_color,
                        title = stringResource(R.string.miuix_monet),
                        description = stringResource(R.string.about_sub_miuix_monet),
                        checked = miuixMonet,
                        onCheckedChange = { checked ->
                            AppearanceState.setMiuixMonet(context, checked)
                            actions.onAppearanceChange()
                        },
                        first = false,
                        // Closes the group only when nothing follows: no 强调色
                        // row (Monet off) and no floating-bar row (SoftGlass).
                        last = !barRowVisible && !miuixMonet
                    )
                    // The key colour the Monet derivation starts from, offered as the
                    // reference manager (KernelSU's colour palette) offers its own:
                    // one menu, "follow the wallpaper" first, the preset swatches
                    // behind it. The row means nothing with Monet off — the palette
                    // is then Miuix's own HyperOS scheme, which no key colour
                    // reaches — so it only exists while the switch above is on,
                    // exactly the way the Material body hides its seed grid while
                    // dynamic colour runs.
                    if (miuixMonet) {
                        PopupCard(
                            iconRes = R.drawable.ic_palette_style,
                            title = stringResource(R.string.miuix_accent),
                            subtitle = stringResource(R.string.about_sub_miuix_accent),
                            entries = accentEntries.toList(),
                            currentIndex = accentValues.indexOf(miuixAccent)
                                .takeIf { it >= 0 } ?: 0,
                            first = false,
                            last = !barRowVisible,
                            onPick = { index ->
                                AppearanceState.setMiuixAccent(context, accentValues[index])
                                actions.onAppearanceChange()
                            }
                        )
                    }
                    // The Example's "Enable blur effect" is not offered as a row:
                    // the glass is this look's default dressing — title bar and
                    // bottom bar are always frosted — so a switch would only
                    // offer the way back to a look the Example never ships.
                    //
                    // What *is* a taste is whether the foot floats: off is the
                    // library's docked pane, on is the texture-blur glass capsule
                    // hovered over the list. Miuix look only — the SoftGlass look
                    // always floats its own glass tab bar, so under it this row
                    // would name a choice the home frame is not offering.
                    if (barRowVisible) {
                        PopupCard(
                            iconRes = R.drawable.ic_ui_style,
                            title = stringResource(R.string.miuix_floating_bar),
                            subtitle = stringResource(R.string.about_sub_miuix_floating_bar),
                            entries = floatingBarEntries.toList(),
                            currentIndex = if (floatingBar) 1 else 0,
                            first = false,
                            last = true,
                            onPick = { index ->
                                AppearanceState.setFloatingBar(context, index == 1)
                                actions.onAppearanceChange()
                            }
                        )
                    }
                }
                // Material only, on screen: these are the app's own colour engine's
                // controls and the Miuix look is not dressed by that engine. Hidden here
                // but still read by it, so a seed or a palette family picked while in
                // Material carries across instead of being silently dropped — the why is
                // argued at MiuixStyleTheme, where the reads live.
                if (!miuixStyle) {
                    DynamicColorCard(
                        dynamic = dynamic,
                        seed = seed,
                        first = false,
                        last = false,
                        onDynamicChange = { checked ->
                            AppearanceState.setDynamicColor(context, checked)
                            actions.onAppearanceChange()
                        },
                        onSeedChange = { color ->
                            AppearanceState.setSeedColor(context, color)
                            actions.onAppearanceChange()
                        }
                    )
                    PopupCard(
                        iconRes = R.drawable.ic_palette_style,
                        title = stringResource(R.string.palette_style),
                        subtitle = stringResource(R.string.about_sub_palette_style),
                        // Entry index == Scheme.Variant.ordinal, which is how the
                        // string array is written; [paletteVariants] is that list minus
                        // whatever the spec in effect cannot render.
                        entries = paletteVariants.map { styleEntries[it.ordinal] },
                        currentIndex = paletteVariants.indexOf(paletteStyleNow),
                        first = false,
                        last = false,
                        // Spec 2026 adopted CMF: the row shows the locked pair, dimmed
                        // and inert — the spec menu is the only way to move either.
                        enabled = colorSpec != ThemePrefs.SPEC_2026,
                        onPick = { index ->
                            // Mapping through the filtered list is what keeps the write
                            // honest: the index is a position in *this* menu, not an
                            // ordinal. The setter refreshes the resolved spec with it —
                            // changing the style can put the spec out of reach (see
                            // ThemePrefs.specVersion), so the spec row re-reads the
                            // resolved value instead of keeping its own copy — the two
                            // rows can only ever move together.
                            AppearanceState.setPaletteStyle(context, paletteVariants[index])
                            actions.onAppearanceChange()
                        }
                    )
                    PopupCard(
                        iconRes = R.drawable.ic_color_spec,
                        title = stringResource(R.string.color_spec),
                        subtitle = stringResource(R.string.about_sub_color_spec),
                        entries = specValues.map { specEntries[it] },
                        currentIndex = specValues.indexOf(colorSpec),
                        first = false,
                        last = true,
                        onPick = { index ->
                            AppearanceState.setSpecVersion(context, specValues[index])
                            actions.onAppearanceChange()
                        }
                    )
                }
            }
        }

        item {
            // The battery switch is a plain runtime switch like any appearance
            // toggle — it never qualified as an experiment (no probe, no gate,
            // one hook reading one flag), so it lives on this page instead of
            // behind the experiment gateway. Beside it sits a plain entry into
            // the AOSP battery-usage page: the switch above moves WeChat out of
            // the optimization whitelist, and this is where the user checks the
            // state that page shows.
            SectionTitle(R.string.about_section_battery)
            SettingsGroup {
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_energy_program_time_used,
                    title = stringResource(R.string.experiment_wechat_doze_keepout),
                    description = stringResource(R.string.experiment_wechat_doze_keepout_desc),
                    checked = wechatDozeKeepout,
                    onCheckedChange = { checked ->
                        Prefs.writeWechatDozeKeepout(context, Prefs.remote(), checked)
                        wechatDozeKeepout = checked
                    },
                    first = true,
                    last = false
                )
                NavCard(
                    iconRes = R.drawable.ic_battery_profile,
                    title = stringResource(R.string.about_battery_aosp_usage),
                    subtitle = stringResource(R.string.about_battery_aosp_usage_desc),
                    onClick = {
                        try {
                            context.startActivity(
                                Intent().setClassName(
                                    "com.android.settings",
                                    "com.android.settings.Settings\$AppBatteryUsageActivity"
                                )
                            )
                        } catch (ignored: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                R.string.about_battery_aosp_usage_missing,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    first = false,
                    last = true
                )
            }
        }

        item {
            // Same rationale as the battery row: two runtime switches, one
            // hook flag each, no probe and no gate — sleep mode's network
            // cutoff is a user-facing behaviour, not an experiment. Two
            // radios, two switches, no master: whichever is on is the half of
            // the network that survives the night, and the other half is left
            // to the ROM either way, so both rows are always on screen.
            SectionTitle(R.string.experiment_section_sleep)
            SettingsGroup {
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_signal_wifi_3_bar_lock,
                    title = stringResource(R.string.experiment_sleep_keepalive),
                    description = stringResource(R.string.experiment_sleep_keepalive_desc),
                    checked = sleepKeepalive,
                    onCheckedChange = { checked ->
                        Prefs.writeSleepKeepalive(context, Prefs.remote(), checked)
                        sleepKeepalive = checked
                    },
                    first = true,
                    last = false
                )
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_network_locked,
                    title = stringResource(R.string.experiment_sleep_keepalive_data),
                    description = stringResource(R.string.experiment_sleep_keepalive_data_desc),
                    checked = sleepKeepaliveData,
                    onCheckedChange = { checked ->
                        Prefs.writeSleepKeepaliveData(context, Prefs.remote(), checked)
                        sleepKeepaliveData = checked
                    },
                    first = false,
                    last = true
                )
            }
        }

        item {
            // One merged section: the debug switch on top (a pure emission
            // toggle — nothing here changes what a hook decides, only whether
            // the log line describing it is written out), the experiment
            // gateway below. No confirm dialog, no allowlist dependency in
            // either row.
            SectionTitle(R.string.about_section_experiment_debug)
            SettingsGroup {
                SettingsSwitchCard(
                    // Style-dependent icon: under Material 3 the row carries the
                    // filled bug_report glyph — the "log a defect report" reading —
                    // while the MIUIX style keeps the diagnostics glyph it shares
                    // with the module-status page.
                    iconRes = if (AppearanceState.uiStyle.intValue == ThemePrefs.UI_STYLE_MATERIAL) {
                        R.drawable.ic_bug_report
                    } else {
                        R.drawable.ic_fcm_diagnostics
                    },
                    title = stringResource(R.string.experiment_debug_log),
                    description = stringResource(R.string.experiment_debug_log_desc),
                    checked = debugLog,
                    onCheckedChange = { checked ->
                        Prefs.writeDebugLog(context, Prefs.remote(), checked)
                        debugLog = checked
                    },
                    first = true,
                    last = false
                )
                NavCard(
                    iconRes = R.drawable.ic_science,
                    title = stringResource(R.string.experiment),
                    subtitle = stringResource(R.string.about_sub_experiment),
                    onClick = actions.onExperiment,
                    first = false,
                    last = true
                )
            }
        }

        item {
            // The data section sits directly above 关于, keeping the project
            // rows as the closing group the way the system's own settings park
            // theirs at the foot.
            SectionTitle(R.string.about_section_backup)
            SettingsGroup {
                NavCard(
                    iconRes = R.drawable.ic_export,
                    title = stringResource(R.string.export_allowlist),
                    subtitle = stringResource(R.string.about_sub_export),
                    onClick = actions.onExport,
                    first = true,
                    last = false
                )
                NavCard(
                    iconRes = R.drawable.ic_import,
                    title = stringResource(R.string.import_allowlist),
                    subtitle = stringResource(R.string.about_sub_import),
                    onClick = actions.onImport,
                    first = false,
                    last = true
                )
            }
        }

        if (showProjectSection) {
            item {
                AboutProjectSection(
                    actions = actions,
                    snackbarHostState = snackbarHostState
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * A row whose tap opens a menu: the current value sits on the right in the
 * accent, and the menu marks it with the primary container tone. The View
 * version built the panel by hand inside the window; a [DropdownMenu] does the
 * positioning.
 *
 * The menu must stay as narrow as its widest label. A [Modifier.fillMaxWidth]
 * row inside it makes the popup as wide as the window, and a window-wide menu
 * no longer fits beside its anchor — the position provider then falls back to
 * the window's own left margin, which is what put the panel out beside the
 * card instead of under it.
 */
@Composable
private fun PopupCard(
    iconRes: Int,
    title: String,
    subtitle: String,
    entries: List<String>,
    currentIndex: Int,
    first: Boolean,
    last: Boolean,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // False dims the row to M3's disabled ink and kills the tap (ripple and
    // haptic included, via [GroupRow]'s null onClick). Used by the palette
    // row while Spec 2026 is in effect: the spec *is* CMF, so the row states
    // a fact instead of offering a choice — the way out is the spec menu
    // (see the caller). No such case exists under the Miuix look, whose
    // appearance rows are a different set; the delegation above is always
    // reached enabled.
    enabled: Boolean = true
) {
    // Under the Miuix look this row is the shared menu row: the value, the
    // up-down arrow beside it, and Miuix's own popup. The Material body below
    // differs only in chrome, so nothing is lost by delegating.
    if (LocalUiStyle.current.isMiuixBased) {
        SettingsMenuCard(
            iconRes = iconRes,
            title = title,
            description = subtitle,
            entries = entries,
            currentIndex = currentIndex,
            onPick = onPick,
            modifier = modifier,
            first = first,
            last = last
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val shapes = LocalAppShapes.current
    val current = entries.getOrNull(currentIndex) ?: ""

    Box(modifier = modifier.fillMaxWidth()) {
        GroupRow(
            first = first,
            last = last,
            // Locked rows lose the click affordance entirely — a disabled M3
            // control is inert, not a button that swallows taps.
            onClick = if (enabled) ({ expanded = true }) else null
        ) {
            Row(
                // M3's disabled ink: onSurface at 38%, applied over the whole
                // row — title, subtitle and the current-value accent together.
                modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RowIcon(painter = painterResource(iconRes))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // The accent, like the XML's `md_primary`: this is the one
                // place on the page the active choice is named, so it has to
                // move with the seed the rest of the page took.
                Text(
                    modifier = Modifier.padding(start = 12.dp),
                    text = current,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        // A DropdownMenu is a popup, and its anchor is the zero-size node the
        // popup leaves behind — so *where inside this Box that node lands* is
        // what the panel is measured from. Bottom-end puts it at the card's
        // bottom-right corner, which is where the hand-built panel was: the
        // position provider then prefers "menu end aligned to anchor end", so
        // the panel sits back from the card's right edge. Left at the default
        // top-start it aligned to the card's left edge and opened over the
        // card's own top rows.
        Box(
            modifier = Modifier
                .matchParentSize()
                .wrapContentSize(Alignment.BottomEnd)
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // Intrinsic width + full-width rows: the menu measures from its
                // widest label, and every row then stretches to that same
                // width. Left to size themselves, short options draw a ripple
                // and a selected plate narrower than the panel — the two
                // "颜色规格" rows were visibly not the same size.
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .defaultMinSize(minWidth = MENU_MIN_WIDTH),
                // The group radius, not the menu's own: the panel is drawn on
                // the page's card language, and M3's default menu corner is
                // much tighter than anything else on this screen.
                shape = shapes.menu,
                // Popup level, as every dropdown — see LocalAppSurfaces. This
                // is M3's own menu default (`MenuTokens.ContainerColor` =
                // `surfaceContainer`), the same level as the page; the panel's
                // own tone is what separates it from the card it lands on,
                // never an outline.
                containerColor = LocalAppSurfaces.current.popup,
                // Negative on purpose: the provider *adds* the offset to the
                // chosen candidate, and the candidate we land on is
                // "menu end at anchor end", so a negative X is what pulls the
                // panel back off the card's right edge.
                offset = DpOffset(x = -MENU_EDGE_INSET, y = MENU_ANCHOR_GAP)
            ) {
                entries.forEachIndexed { index, label ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(MENU_ITEM_GAP))
                    }
                    MenuItemRow(
                        label = label,
                        modifier = Modifier.fillMaxWidth(),
                        minWidth = MENU_MIN_WIDTH,
                        selected = index == currentIndex,
                        onClick = {
                            expanded = false
                            onPick(index)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Dynamic color plus its seed grid. While the switch is on the palette follows
 * the wallpaper, so the seeds would be meaningless — they are hidden, exactly
 * as the XML did. The grid joins and leaves with an expand/collapse so the
 * appearance reads as this card opening up rather than a block teleporting in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DynamicColorCard(
    dynamic: Boolean,
    seed: Int,
    first: Boolean,
    last: Boolean,
    onDynamicChange: (Boolean) -> Unit,
    onSeedChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = connectedGroupShape(first, last)
    val motion = MaterialTheme.motionScheme
    val stateLine = stringResource(
        if (dynamic) R.string.about_sub_dynamic_color_on
        else R.string.about_sub_dynamic_color_off
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LocalAppSurfaces.current.card,
        shape = shape
    ) {
        val view = LocalView.current
        // Same contract as [SettingsSwitchCard]: one toggle path for the row
        // and the switch itself, haptic exactly once per flip. `toggleable`
        // like the shared card too, so the row announces its state.
        val toggle: () -> Unit = {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onDynamicChange(!dynamic)
        }
        Column {
            Row(
                modifier = Modifier
                    // See [MenuItemRow]: the clip has to come before
                    // the click, or the ripple is a rectangle.
                    .clip(shape)
                    .toggleable(
                        value = dynamic,
                        role = Role.Switch,
                        onValueChange = { toggle() }
                    )
                    .height(72.dp)
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RowIcon(painter = painterResource(R.drawable.ic_dynamic_color))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dynamic_color),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stateLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    modifier = Modifier.padding(start = 12.dp),
                    checked = dynamic,
                    onCheckedChange = { toggle() },
                    colors = appSwitchColors(),
                    thumbContent = { SwitchThumbMark(checked = dynamic) }
                )
            }
            AnimatedVisibility(
                visible = !dynamic,
                // The theme's own motion, not a hard-coded 250/200ms pair. On
                // the Expressive spec these are the springs the surrounding
                // library components already move with, so the grid opens the
                // way the switch itself flips; on the 2021 spec they are the
                // standard curves. Pin the durations and this card becomes the
                // one place on the page that still animates like the old theme.
                enter = expandVertically(animationSpec = motion.defaultSpatialSpec()) +
                    fadeIn(animationSpec = motion.defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = motion.defaultSpatialSpec()) +
                    fadeOut(animationSpec = motion.defaultEffectsSpec())
            ) {
                SeedGrid(
                    selected = seed,
                    onPick = onSeedChange,
                    modifier = Modifier.padding(
                        start = 16.dp, end = 16.dp, bottom = 16.dp
                    )
                )
            }
        }
    }
}

/**
 * The preset seeds, six per row. The cell is the touch target so it stays at
 * least 36dp even when a narrow screen shrinks the dot inside it.
 */
@Composable
private fun SeedGrid(selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cell = maxOf(36.dp, maxWidth / SEED_COLUMNS)
        val dot = minOf(44.dp, cell - 8.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SEED_COLORS.toList().chunked(SEED_COLUMNS).forEach { row ->
                Row {
                    row.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(cell)
                                .clip(CircleShape)
                                .clickable { onPick(color) },
                            contentAlignment = Alignment.Center
                        ) {
                            SeedDot(seed = color, selected = color == selected, dotSize = dot)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A seed preview: a circle split into the tonal ramp the seed generates — dark
 * on the left, light on the right, mid tone in the bottom-right quadrant — so
 * each option hints at the palette it produces. The active one wears a primary
 * ring and a check on its dark half, where it stays readable.
 */
@Composable
private fun SeedDot(seed: Int, selected: Boolean, dotSize: androidx.compose.ui.unit.Dp) {
    val ringColor = MaterialTheme.colorScheme.primary
    val tones = remember(seed) { seedTones(seed) }
    Canvas(modifier = Modifier.size(dotSize)) {
        val stroke = 2.dp.toPx()
        val radius = size.minDimension / 2f - stroke
        val cx = size.width / 2f
        val cy = size.height / 2f
        val box = Size(radius * 2f, radius * 2f)
        val corner = Offset(cx - radius, cy - radius)
        drawCircle(color = tones.light, radius = radius, center = Offset(cx, cy))
        drawArc(
            color = tones.mid, startAngle = 0f, sweepAngle = 90f, useCenter = true,
            topLeft = corner, size = box
        )
        drawArc(
            color = tones.dark, startAngle = 90f, sweepAngle = 180f, useCenter = true,
            topLeft = corner, size = box
        )
        if (selected) {
            drawCircle(
                color = ringColor, radius = radius + stroke / 2f,
                center = Offset(cx, cy), style = Stroke(width = stroke)
            )
            val check = Path().apply {
                val kx = cx - radius * 0.45f
                moveTo(kx - radius * 0.28f, cy)
                lineTo(kx - radius * 0.05f, cy + radius * 0.23f)
                lineTo(kx + radius * 0.36f, cy - radius * 0.26f)
            }
            drawPath(
                path = check, color = Color.White,
                style = Stroke(
                    width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round
                )
            )
        }
    }
}

private class SeedTones(val dark: Color, val mid: Color, val light: Color)

/**
 * The three tones of a seed's ramp. Chroma is floored so near-grey seeds still
 * read as a ramp instead of three identical greys.
 */
private fun seedTones(seed: Int): SeedTones {
    val hct = Hct.fromInt(seed)
    val hue = hct.hue
    val chroma = maxOf(hct.chroma, 8.0)
    return SeedTones(
        dark = Color(Hct.from(hue, chroma, 40.0).toInt()),
        mid = Color(Hct.from(hue, chroma, 80.0).toInt()),
        light = Color(Hct.from(hue, chroma, 90.0).toInt())
    )
}

private fun variantAt(index: Int): Scheme.Variant {
    val values = Scheme.Variant.values()
    return if (index >= 0 && index < values.size) values[index] else Scheme.Variant.TONAL_SPOT
}

/** Preset seeds offered when dynamic color is off (MD3-friendly hues). */
private val SEED_COLORS = intArrayOf(
    0xFF6750A4.toInt(), // Material baseline purple (default)
    0xFFB3261E.toInt(), // red
    0xFFBF360C.toInt(), // deep orange
    0xFFE65100.toInt(), // orange
    0xFF9A6200.toInt(), // amber
    0xFF827717.toInt(), // olive
    0xFF558B2F.toInt(), // lime
    0xFF2E7D32.toInt(), // green
    0xFF006A6A.toInt(), // teal
    0xFF00838F.toInt(), // cyan
    0xFF0277BD.toInt(), // light blue
    0xFF0B57D0.toInt(), // blue
    0xFF3949AB.toInt(), // indigo
    0xFF5E35B1.toInt(), // deep purple
    0xFFAD1457.toInt(), // pink
    0xFF5F5E62.toInt(), // neutral grey
)

/** Six keeps the block to three rows on a phone. */
private const val SEED_COLUMNS = 6

@Preview(name = "About — light", showBackground = true)
@Preview(
    name = "About — dark intent",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun AboutScreenPreview() {
    HyperFCMLiveTheme {
        Surface {
            AboutScreen(
                onBack = {},
                actions = AboutActions(
                    onViewSource = {},
                    onLicense = {},
                    onLicenses = {},
                    onExperiment = {},
                    onExport = {},
                    onImport = {},
                    onCheckUpdate = {},
                    onUpdateOpen = {},
                    versionLine = "当前版本 3.7.0 (45)",
                    versionTag = "v3.7.0 (45)",
                    onAppearanceChange = {}
                )
            )
        }
    }
}
