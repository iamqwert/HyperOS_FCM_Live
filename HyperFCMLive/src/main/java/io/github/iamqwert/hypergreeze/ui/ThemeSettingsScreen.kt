package io.github.iamqwert.hypergreeze.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider as MaterialSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.iamqwert.hypergreeze.Prefs
import io.github.iamqwert.hypergreeze.R
import io.github.iamqwert.hypergreeze.mcu.Hct
import io.github.iamqwert.hypergreeze.mcu.Scheme
import io.github.iamqwert.hypergreeze.theme.AppearanceState
import io.github.iamqwert.hypergreeze.theme.LocalAppShapes
import io.github.iamqwert.hypergreeze.theme.LocalAppSurfaces
import io.github.iamqwert.hypergreeze.theme.LocalUiStyle
import io.github.iamqwert.hypergreeze.theme.MIUIX_ACCENT_OPTIONS
import io.github.iamqwert.hypergreeze.theme.ThemeEngine
import io.github.iamqwert.hypergreeze.theme.ThemePrefs
import io.github.iamqwert.hypergreeze.theme.toComposeColorScheme
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults as MiuixSliderDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Dedicated Theme Settings page aligned with MIUIX demo's `ThemeSettingsPage`:
 *  - Top: side-by-side Light / Dark preview cards + standalone Follow System switch;
 *  - Middle: Dynamic Color section (Miuix: Monet switch → Key Color → Palette Style / Color Spec;
 *    Material 3: DynamicColorCard with seed grid + Palette Style + Color Spec);
 *  - Bottom: Appearance & Interaction section (UI Style + Predictive Back).
 */
@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
    onAppearanceChange: () -> Unit,
    onPredictiveBackChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    PageScaffold(
        titleRes = R.string.theme_settings,
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        val listState = rememberPageListState()
        ThemeSettingsBody(
            onAppearanceChange = onAppearanceChange,
            onPredictiveBackChange = onPredictiveBackChange,
            listState = listState,
            topPadding = innerPadding.calculateTopPadding(),
            bottomPadding = innerPadding.calculateBottomPadding(),
        )
        MiuixScrollBar(
            listState = listState,
            modifier = Modifier.align(Alignment.CenterEnd),
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding(),
        )
    }
}

@Composable
private fun ThemeSettingsBody(
    onAppearanceChange: () -> Unit,
    onPredictiveBackChange: (Boolean) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val themeMode by AppearanceState.themeMode
    val uiStyle by AppearanceState.uiStyle
    val dynamic by AppearanceState.dynamicColor
    val seed by AppearanceState.seedColor
    val miuixMonet by AppearanceState.miuixMonet
    val miuixAccent by AppearanceState.miuixAccent
    val paletteStyle by AppearanceState.paletteStyle
    val colorSpec by AppearanceState.colorSpec
    val enableBlur by AppearanceState.enableBlur
    val enablePageUserScroll by AppearanceState.enablePageUserScroll
    val pagerGestureMode by AppearanceState.pagerGestureMode
    val densityScale by AppearanceState.densityScale
    val enableSwipeBack by AppearanceState.enableSwipeBack
    var predictiveBack by remember { mutableStateOf(Prefs.readPredictiveBack(context)) }

    val miuixStyle = uiStyle != ThemePrefs.UI_STYLE_MATERIAL
    val isSystem = themeMode == ThemePrefs.MODE_SYSTEM
    val isDark = remember(context, themeMode) { ThemePrefs.isDark(context) }

    val styleEntries = stringArrayResource(R.array.palette_style_entries)
    val specEntries = stringArrayResource(R.array.color_spec_entries)
    val accentEntries = stringArrayResource(R.array.miuix_accent_entries)
    val pagerGestureEntries = stringArrayResource(R.array.pager_gesture_mode_entries)
    val accentValues = remember { listOf(0) + MIUIX_ACCENT_OPTIONS.toList() }

    val paletteVariants = Scheme.Variant.entries.filter {
        when (colorSpec) {
            ThemePrefs.SPEC_2025 -> it.supportsExpressive2025
            ThemePrefs.SPEC_2026 -> true
            else -> it != Scheme.Variant.CMF
        }
    }
    val paletteStyleNow = variantAt(paletteStyle)
    val specValues = listOf(ThemePrefs.SPEC_2021, ThemePrefs.SPEC_2025, ThemePrefs.SPEC_2026)

    val inset = pageHorizontalInset()
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .listScrollEndHaptic()
            .padding(start = inset, end = inset),
        state = listState,
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
    ) {
        // 1. Light / Dark preview cards (aligned with MIUIX ThemeSettingsPage)
        item(key = "themePreview") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeModePreviewCard(
                    title = stringResource(R.string.light_mode),
                    dark = false,
                    isSelected = !isDark,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        AppearanceState.setThemeMode(context, ThemePrefs.MODE_LIGHT)
                        onAppearanceChange()
                    },
                    modifier = Modifier.weight(1f),
                )
                ThemeModePreviewCard(
                    title = stringResource(R.string.dark_mode),
                    dark = true,
                    isSelected = isDark,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        AppearanceState.setThemeMode(context, ThemePrefs.MODE_DARK)
                        onAppearanceChange()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 2. Standalone Follow System switch card
        item(key = "followSystem") {
            SettingsGroup {
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_theme_mode,
                    title = stringResource(R.string.follow_system),
                    description = "",
                    checked = isSystem,
                    onCheckedChange = { followSystem ->
                        val nextMode = if (followSystem) {
                            ThemePrefs.MODE_SYSTEM
                        } else if (isDark) {
                            ThemePrefs.MODE_DARK
                        } else {
                            ThemePrefs.MODE_LIGHT
                        }
                        AppearanceState.setThemeMode(context, nextMode)
                        onAppearanceChange()
                    },
                    first = true,
                    last = true,
                )
            }
        }

        // 3. Dynamic Color section
        item(key = "dynamicColorSection") {
            SectionTitle(R.string.theme_section_dynamic_color)
            SettingsGroup {
                if (miuixStyle) {
                    val showAdvanced = miuixMonet && miuixAccent != 0
                    SettingsSwitchCard(
                        iconRes = R.drawable.ic_dynamic_color,
                        title = stringResource(R.string.miuix_monet_dynamic_color),
                        description = stringResource(R.string.about_sub_miuix_monet),
                        checked = miuixMonet,
                        onCheckedChange = { checked ->
                            AppearanceState.setMiuixMonet(context, checked)
                            onAppearanceChange()
                        },
                        first = true,
                        last = !miuixMonet,
                    )
                    if (miuixMonet) {
                        PopupCard(
                            iconRes = R.drawable.ic_palette_style,
                            title = stringResource(R.string.miuix_accent),
                            subtitle = stringResource(R.string.about_sub_miuix_accent),
                            entries = accentEntries.toList(),
                            currentIndex = accentValues.indexOf(miuixAccent)
                                .takeIf { it >= 0 } ?: 0,
                            first = false,
                            last = !showAdvanced,
                            onPick = { index ->
                                AppearanceState.setMiuixAccent(context, accentValues[index])
                                onAppearanceChange()
                            },
                        )
                    }
                    if (showAdvanced) {
                        PopupCard(
                            iconRes = R.drawable.ic_palette_style,
                            title = stringResource(R.string.palette_style),
                            subtitle = stringResource(R.string.about_sub_palette_style),
                            entries = paletteVariants.map { styleEntries[it.ordinal] },
                            currentIndex = paletteVariants.indexOf(paletteStyleNow)
                                .coerceAtLeast(0),
                            first = false,
                            last = false,
                            enabled = colorSpec != ThemePrefs.SPEC_2026,
                            onPick = { index ->
                                AppearanceState.setPaletteStyle(context, paletteVariants[index])
                                onAppearanceChange()
                            },
                        )
                        PopupCard(
                            iconRes = R.drawable.ic_color_spec,
                            title = stringResource(R.string.color_spec),
                            subtitle = stringResource(R.string.about_sub_color_spec),
                            entries = specValues.map { specEntries[it] },
                            currentIndex = specValues.indexOf(colorSpec).coerceAtLeast(0),
                            first = false,
                            last = true,
                            onPick = { index ->
                                AppearanceState.setSpecVersion(context, specValues[index])
                                onAppearanceChange()
                            },
                        )
                    }
                } else {
                    DynamicColorCard(
                        dynamic = dynamic,
                        seed = seed,
                        first = true,
                        last = false,
                        onDynamicChange = { checked ->
                            AppearanceState.setDynamicColor(context, checked)
                            onAppearanceChange()
                        },
                        onSeedChange = { color ->
                            AppearanceState.setSeedColor(context, color)
                            onAppearanceChange()
                        },
                    )
                    PopupCard(
                        iconRes = R.drawable.ic_palette_style,
                        title = stringResource(R.string.palette_style),
                        subtitle = stringResource(R.string.about_sub_palette_style),
                        entries = paletteVariants.map { styleEntries[it.ordinal] },
                        currentIndex = paletteVariants.indexOf(paletteStyleNow).coerceAtLeast(0),
                        first = false,
                        last = false,
                        enabled = colorSpec != ThemePrefs.SPEC_2026,
                        onPick = { index ->
                            AppearanceState.setPaletteStyle(context, paletteVariants[index])
                            onAppearanceChange()
                        },
                    )
                    PopupCard(
                        iconRes = R.drawable.ic_color_spec,
                        title = stringResource(R.string.color_spec),
                        subtitle = stringResource(R.string.about_sub_color_spec),
                        entries = specValues.map { specEntries[it] },
                        currentIndex = specValues.indexOf(colorSpec).coerceAtLeast(0),
                        first = false,
                        last = true,
                        onPick = { index ->
                            AppearanceState.setSpecVersion(context, specValues[index])
                            onAppearanceChange()
                        },
                    )
                }
            }
        }

        // 4. Appearance & Interaction section (aligned with MIUIX ThemeSettingsPage)
        item(key = "appearanceSection") {
            SectionTitle(R.string.theme_section_appearance)
            SettingsGroup {
                if (miuixStyle) {
                    SettingsSwitchCard(
                        iconRes = R.drawable.ic_tune,
                        title = stringResource(R.string.enable_blur_effect),
                        description = "",
                        checked = enableBlur,
                        onCheckedChange = { checked ->
                            AppearanceState.setEnableBlur(context, checked)
                            onAppearanceChange()
                        },
                        first = true,
                        last = false,
                    )
                    SettingsSwitchCard(
                        iconRes = R.drawable.ic_swipe_left,
                        title = stringResource(R.string.enable_page_user_scroll),
                        description = "",
                        checked = enablePageUserScroll,
                        onCheckedChange = { checked ->
                            AppearanceState.setEnablePageUserScroll(context, checked)
                        },
                        first = false,
                        last = false,
                    )
                    if (enablePageUserScroll) {
                        PopupCard(
                            iconRes = R.drawable.ic_tune,
                            title = stringResource(R.string.pager_gesture_mode),
                            subtitle = "",
                            entries = pagerGestureEntries.toList(),
                            currentIndex = pagerGestureMode.coerceIn(0, pagerGestureEntries.lastIndex),
                            first = false,
                            last = false,
                            onPick = { index ->
                                AppearanceState.setPagerGestureMode(context, index)
                            },
                        )
                    }
                }
                GlobalScaleCard(
                    densityScale = densityScale,
                    onDensityScaleChange = { nextScale ->
                        AppearanceState.setDensityScale(context, nextScale)
                    },
                    first = !miuixStyle,
                    last = true,
                )
            }
        }

        // 5. Navigation section (Enable Swipe Back + Predictive Back)
        item(key = "navigationSection") {
            SectionTitle(R.string.theme_section_navigation)
            SettingsGroup {
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_swipe_left,
                    title = stringResource(R.string.enable_swipe_back),
                    description = stringResource(R.string.about_sub_enable_swipe_back),
                    checked = enableSwipeBack,
                    onCheckedChange = { checked ->
                        AppearanceState.setEnableSwipeBack(context, checked)
                    },
                    first = true,
                    last = false,
                )
                SettingsSwitchCard(
                    iconRes = R.drawable.ic_swipe_left,
                    title = stringResource(R.string.predictive_back),
                    description = stringResource(R.string.about_sub_predictive_back),
                    checked = predictiveBack,
                    onCheckedChange = { checked ->
                        Prefs.writePredictiveBack(context, checked)
                        predictiveBack = checked
                        onPredictiveBackChange(checked)
                    },
                    first = false,
                    last = true,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Light / Dark preview card:
 *  - MIUIX: matches MIUIX demo's `ThemeModePreview` (blue-cyan HyperOS wallpaper
 *    gradient with translucent glass cards and a 3dp primary border when selected).
 *  - Material 3 Expressive: live Tonal Surface miniature rendered directly from
 *    [ThemeEngine.paletteForDarkMode] so switching Dynamic Color, custom seed,
 *    palette style or color spec updates both light and dark thumbnails in real time.
 */
@Composable
private fun ThemeModePreviewCard(
    title: String,
    dark: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMiuix = LocalUiStyle.current.isMiuixBased
    if (!isMiuix) {
        MaterialThemeModePreviewCard(
            title = title,
            dark = dark,
            isSelected = isSelected,
            onClick = onClick,
            modifier = modifier,
        )
        return
    }
    val squircle = isSquircleEnabled()
    val outerShape = MiuixGroupShape(26.dp, 26.dp, 26.dp, 26.dp, squircle = squircle)
    val innerShape = MiuixGroupShape(20.dp, 20.dp, 20.dp, 20.dp, squircle = squircle)
    val borderColor = if (isSelected) MiuixTheme.colorScheme.primary else Color.Transparent
    val labelColor = if (isSelected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceSecondary
    }

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                selected = isSelected
            }
            .clip(outerShape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 3.dp, color = borderColor, shape = outerShape)
                .padding(6.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.15f)
                    .clip(innerShape),
            ) {
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color(0xFF406EFF), Color(0xFF55B9FA))),
                )
                val unitX = size.width / 680f
                val unitY = size.height / 680f
                val cornerUnit = minOf(unitX, unitY)
                val panelColor = if (dark) {
                    Color.Black.copy(alpha = 0.2f)
                } else {
                    Color.White.copy(alpha = 0.2f)
                }
                val lineColor = if (dark) {
                    Color.White.copy(alpha = 0.35f)
                } else {
                    Color(0xFF285ABC).copy(alpha = 0.6f)
                }
                for (top in listOf(148f, 356f)) {
                    drawRoundRect(
                        color = panelColor,
                        topLeft = Offset(48f * unitX, top * unitY),
                        size = Size(584f * unitX, 176f * unitY),
                        cornerRadius = CornerRadius(44f * cornerUnit),
                    )
                    for ((offset, width) in listOf(52f to 460f, 100f to 312f)) {
                        drawRoundRect(
                            color = lineColor,
                            topLeft = Offset(112f * unitX, (top + offset) * unitY),
                            size = Size(width * unitX, 24f * unitY),
                            cornerRadius = CornerRadius(12f * cornerUnit),
                        )
                    }
                }
            }
        }
        MiuixText(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            color = labelColor,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MaterialThemeModePreviewCard(
    title: String,
    dark: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val generation = ThemeEngine.generation
    val previewScheme = remember(context, dark, generation) {
        try {
            ThemeEngine.paletteForDarkMode(context, dark).toComposeColorScheme()
        } catch (_: Throwable) {
            if (dark) darkColorScheme() else lightColorScheme()
        }
    }
    val cardShape = LocalAppShapes.current.card
    val outerBorderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    val innerOutlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
    val labelColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                selected = isSelected
            }
            .clip(cardShape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 2.5.dp, color = outerBorderColor, shape = cardShape)
                .padding(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.15f)
                    .clip(cardShape)
                    .border(
                        width = if (isSelected) 0.dp else 1.dp,
                        color = if (isSelected) Color.Transparent else innerOutlineColor,
                        shape = cardShape,
                    ),
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val uX = size.width / 680f
                    val uY = size.height / 590f
                    val uR = minOf(uX, uY)

                    // M3E surface background
                    drawRect(color = previewScheme.surface)

                    // Top app bar title bar + trailing action dot
                    drawRoundRect(
                        color = previewScheme.onSurface.copy(alpha = 0.72f),
                        topLeft = Offset(52f * uX, 56f * uY),
                        size = Size(210f * uX, 30f * uY),
                        cornerRadius = CornerRadius(15f * uR),
                    )
                    drawCircle(
                        color = previewScheme.surfaceContainerHighest,
                        radius = 22f * uR,
                        center = Offset(604f * uX, 71f * uY),
                    )

                    // Primary hero / status container card (M3E tonal emphasis)
                    drawRoundRect(
                        color = previewScheme.primaryContainer,
                        topLeft = Offset(48f * uX, 126f * uY),
                        size = Size(584f * uX, 178f * uY),
                        cornerRadius = CornerRadius(52f * uR),
                    )
                    // Hero title + subtitle lines
                    drawRoundRect(
                        color = previewScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        topLeft = Offset(94f * uX, 174f * uY),
                        size = Size(280f * uX, 26f * uY),
                        cornerRadius = CornerRadius(13f * uR),
                    )
                    drawRoundRect(
                        color = previewScheme.onPrimaryContainer.copy(alpha = 0.45f),
                        topLeft = Offset(94f * uX, 224f * uY),
                        size = Size(196f * uX, 20f * uY),
                        cornerRadius = CornerRadius(10f * uR),
                    )
                    // Mini M3 switch on the right side of the hero card
                    drawRoundRect(
                        color = previewScheme.primary,
                        topLeft = Offset(492f * uX, 190f * uY),
                        size = Size(96f * uX, 52f * uY),
                        cornerRadius = CornerRadius(26f * uR),
                    )
                    drawCircle(
                        color = previewScheme.onPrimary,
                        radius = 19f * uR,
                        center = Offset(562f * uX, 216f * uY),
                    )

                    // Secondary settings/list card
                    drawRoundRect(
                        color = previewScheme.surfaceContainerHigh,
                        topLeft = Offset(48f * uX, 330f * uY),
                        size = Size(444f * uX, 148f * uY),
                        cornerRadius = CornerRadius(44f * uR),
                    )
                    // Leading tonal icon badge
                    drawCircle(
                        color = previewScheme.secondaryContainer,
                        radius = 30f * uR,
                        center = Offset(108f * uX, 404f * uY),
                    )
                    drawRoundRect(
                        color = previewScheme.onSurface.copy(alpha = 0.72f),
                        topLeft = Offset(164f * uX, 374f * uY),
                        size = Size(236f * uX, 24f * uY),
                        cornerRadius = CornerRadius(12f * uR),
                    )
                    drawRoundRect(
                        color = previewScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        topLeft = Offset(164f * uX, 418f * uY),
                        size = Size(164f * uX, 18f * uY),
                        cornerRadius = CornerRadius(9f * uR),
                    )

                    // M3E squircle-rounded FAB beside the lower card
                    drawRoundRect(
                        color = previewScheme.tertiaryContainer,
                        topLeft = Offset(516f * uX, 344f * uY),
                        size = Size(116f * uX, 116f * uY),
                        cornerRadius = CornerRadius(38f * uR),
                    )
                    drawRoundRect(
                        color = previewScheme.onTertiaryContainer,
                        topLeft = Offset(552f * uX, 396f * uY),
                        size = Size(44f * uX, 12f * uY),
                        cornerRadius = CornerRadius(6f * uR),
                    )
                    drawRoundRect(
                        color = previewScheme.onTertiaryContainer,
                        topLeft = Offset(568f * uX, 380f * uY),
                        size = Size(12f * uX, 44f * uY),
                        cornerRadius = CornerRadius(6f * uR),
                    )

                    // Bottom floating navigation pill indicator
                    drawRoundRect(
                        color = previewScheme.surfaceContainerHighest,
                        topLeft = Offset(140f * uX, 508f * uY),
                        size = Size(400f * uX, 52f * uY),
                        cornerRadius = CornerRadius(26f * uR),
                    )
                    drawRoundRect(
                        color = previewScheme.secondaryContainer,
                        topLeft = Offset(160f * uX, 518f * uY),
                        size = Size(112f * uX, 32f * uY),
                        cornerRadius = CornerRadius(16f * uR),
                    )
                }
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            color = labelColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Material 3 Dynamic Color switch + custom seed grid.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DynamicColorCard(
    dynamic: Boolean,
    seed: Int,
    first: Boolean,
    last: Boolean,
    onDynamicChange: (Boolean) -> Unit,
    onSeedChange: (Int) -> Unit,
) {
    val shape = connectedGroupShape(first, last)
    val motion = MaterialTheme.motionScheme
    val stateLine = stringResource(
        if (dynamic) R.string.about_sub_dynamic_color_on else R.string.about_sub_dynamic_color_off
    )
    val view = LocalView.current
    val toggle: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        onDynamicChange(!dynamic)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = LocalAppSurfaces.current.card,
        shape = shape,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .toggleable(
                        value = dynamic,
                        role = Role.Switch,
                        onValueChange = { toggle() },
                    )
                    .heightIn(min = 72.dp)
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowIcon(painter = painterResource(R.drawable.ic_dynamic_color))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dynamic_color),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stateLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    modifier = Modifier.padding(start = 12.dp),
                    checked = dynamic,
                    onCheckedChange = { toggle() },
                    colors = appSwitchColors(),
                    thumbContent = { SwitchThumbMark(checked = dynamic) },
                )
            }
            AnimatedVisibility(
                visible = !dynamic,
                enter = expandVertically(animationSpec = motion.defaultSpatialSpec()) +
                    fadeIn(animationSpec = motion.defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = motion.defaultSpatialSpec()) +
                    fadeOut(animationSpec = motion.defaultEffectsSpec()),
            ) {
                SeedGrid(
                    selected = seed,
                    onPick = onSeedChange,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SeedGrid(selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val names = stringArrayResource(R.array.seed_color_names)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = (maxWidth / SEED_MIN_TOUCH_TARGET).toInt().coerceIn(1, SEED_COLUMNS)
        val cell = maxOf(SEED_MIN_TOUCH_TARGET, maxWidth / columns)
        val dot = minOf(44.dp, cell - 8.dp)
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SEED_COLORS.withIndex().toList().chunked(columns).forEach { row ->
                Row {
                    row.forEach { (index, color) ->
                        val isSelected = color == selected
                        val label = names.getOrNull(index) ?: ""
                        Box(
                            modifier = Modifier
                                .size(cell)
                                .clip(CircleShape)
                                .semantics {
                                    if (label.isNotEmpty()) {
                                        contentDescription = label
                                    }
                                }
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { onPick(color) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            SeedDot(seed = color, selected = isSelected, dotSize = dot)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeedDot(seed: Int, selected: Boolean, dotSize: Dp) {
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
            topLeft = corner, size = box,
        )
        drawArc(
            color = tones.dark, startAngle = 90f, sweepAngle = 180f, useCenter = true,
            topLeft = corner, size = box,
        )
        if (selected) {
            drawCircle(
                color = ringColor, radius = radius + stroke / 2f,
                center = Offset(cx, cy), style = Stroke(width = stroke),
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
                    width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round,
                ),
            )
        }
    }
}

private class SeedTones(val dark: Color, val mid: Color, val light: Color)

private fun seedTones(seed: Int): SeedTones {
    val hct = Hct.fromInt(seed)
    val hue = hct.hue
    val chroma = maxOf(hct.chroma, 8.0)
    return SeedTones(
        dark = Color(Hct.from(hue, chroma, 40.0).toInt()),
        mid = Color(Hct.from(hue, chroma, 80.0).toInt()),
        light = Color(Hct.from(hue, chroma, 90.0).toInt()),
    )
}

private fun variantAt(index: Int): Scheme.Variant {
    val values = Scheme.Variant.entries
    return if (index >= 0 && index < values.size) values[index] else Scheme.Variant.TONAL_SPOT
}

private val SEED_COLORS = intArrayOf(
    0xFF6750A4.toInt(),
    0xFFB3261E.toInt(),
    0xFFBF360C.toInt(),
    0xFFE65100.toInt(),
    0xFF9A6200.toInt(),
    0xFF827717.toInt(),
    0xFF558B2F.toInt(),
    0xFF2E7D32.toInt(),
    0xFF006A6A.toInt(),
    0xFF00838F.toInt(),
    0xFF0277BD.toInt(),
    0xFF0B57D0.toInt(),
    0xFF3949AB.toInt(),
    0xFF5E35B1.toInt(),
    0xFFAD1457.toInt(),
    0xFF5F5E62.toInt(),
)

private const val SEED_COLUMNS = 6
private val SEED_MIN_TOUCH_TARGET = 48.dp
private val DENSITY_SCALE_KEY_POINTS = listOf(80f, 90f, 100f, 110f)

/**
 * Global density scale preference aligned with MIUIX demo's `GlobalScalePreference`:
 * slider with 80/90/100/110% key points plus tap-to-open numeric input dialog.
 */
@Composable
private fun GlobalScaleCard(
    densityScale: Float,
    onDensityScaleChange: (Float) -> Unit,
    first: Boolean,
    last: Boolean,
) {
    val minPct = (ThemePrefs.MIN_DENSITY_SCALE * 100f).roundToInt().toFloat()
    val maxPct = (ThemePrefs.MAX_DENSITY_SCALE * 100f).roundToInt().toFloat()
    var draftPercent by remember { mutableFloatStateOf((densityScale * 100f).roundToInt().toFloat()) }
    var showDialog by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(densityScale) {
        draftPercent = (densityScale * 100f).roundToInt().toFloat()
    }

    fun commitPercent(percent: Float) {
        val clamped = percent.roundToInt().coerceIn(minPct.toInt(), maxPct.toInt())
        val normalized = ThemePrefs.normalizeDensityScale(clamped / 100f)
        draftPercent = (normalized * 100f).roundToInt().toFloat()
        onDensityScaleChange(normalized)
    }

    val shape = connectedGroupShape(first, last)
    val label = "${draftPercent.roundToInt()}%"

    if (LocalUiStyle.current.isMiuixBased) {
        MiuixCardSurface(shape = shape) {
            ArrowPreference(
                title = stringResource(R.string.global_scale),
                endActions = {
                    MiuixText(
                        text = label,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
                bottomAction = {
                    MiuixSlider(
                        value = draftPercent.coerceIn(minPct, maxPct),
                        onValueChange = { draftPercent = it },
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = minPct..maxPct,
                        onValueChangeFinished = { commitPercent(draftPercent) },
                        showKeyPoints = true,
                        keyPoints = DENSITY_SCALE_KEY_POINTS,
                        magnetThreshold = 0.01f,
                        hapticEffect = MiuixSliderDefaults.SliderHapticEffect.Step,
                    )
                },
                onClick = {
                    inputText = draftPercent.roundToInt().toString()
                    showDialog = true
                },
                holdDownState = showDialog,
            )
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = LocalAppSurfaces.current.card,
            shape = shape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .clickable {
                        inputText = draftPercent.roundToInt().toString()
                        showDialog = true
                    }
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowIcon(painter = painterResource(R.drawable.ic_tune))
                    Text(
                        text = stringResource(R.string.global_scale),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                MaterialSlider(
                    value = draftPercent.coerceIn(minPct, maxPct),
                    onValueChange = { draftPercent = it },
                    valueRange = minPct..maxPct,
                    onValueChangeFinished = { commitPercent(draftPercent) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showDialog) {
        val isMiuix = LocalUiStyle.current.isMiuixBased
        val miuixScaleState = rememberTextFieldState(initialText = inputText)
        AppDialog(
            onDismissRequest = { showDialog = false },
            title = stringResource(R.string.global_scale),
            confirmLabel = stringResource(android.R.string.ok),
            onConfirm = {
                val raw = if (isMiuix) miuixScaleState.text.toString() else inputText
                val parsed = raw.toIntOrNull()?.toFloat() ?: draftPercent
                commitPercent(parsed)
                showDialog = false
            },
            dismissLabel = stringResource(android.R.string.cancel),
            onDismiss = { showDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppText(
                    text = stringResource(R.string.global_scale_summary),
                    color = if (isMiuix) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = pageProseStyle(),
                )
                if (isMiuix) {
                    GlassTextField(
                        state = miuixScaleState,
                        modifier = Modifier.fillMaxWidth(),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        inputTransformation = InputTransformation {
                            if (length > 3 || asCharSequence().any { !it.isDigit() }) {
                                revertAllChanges()
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            MiuixText(
                                text = "%",
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                    )
                } else {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { raw ->
                            if (raw.length <= 3 && raw.all { it.isDigit() }) {
                                inputText = raw
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        suffix = { Text("%") },
                    )
                }
            }
        }
    }
}
