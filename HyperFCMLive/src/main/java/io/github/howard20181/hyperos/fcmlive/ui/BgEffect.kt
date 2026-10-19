package io.github.howard20181.hyperos.fcmlive.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Miuix look's dynamic background — the OS3「布料」mesh the system paints
 * behind its own about/hero pages, mirrored from the compose-miuix-ui example
 * (the same source KernelSU's manager mirrors): an AGSL shader blending four
 * drifting colour blobs over a Perlin field, re-drawn every frame.
 *
 * Phone presets only — this app is a phone module. Supported from T (AGSL);
 * the composable draws nothing older, leaving the scaffold's plain colour.
 */

internal object BgEffectConfig {

    internal class Config(
        val points: FloatArray,
        val colors1: FloatArray,
        val colors2: FloatArray,
        val colors3: FloatArray,
        val colorInterpPeriod: Float,
        val lightOffset: Float,
        val saturateOffset: Float,
        val pointOffset: Float,
    )

    private val PHONE_LIGHT = Config(
        points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
        colors1 = floatArrayOf(1.0f, 0.9f, 0.94f, 1.0f, 1.0f, 0.84f, 0.89f, 1.0f, 0.97f, 0.73f, 0.82f, 1.0f, 0.64f, 0.65f, 0.98f, 1.0f),
        colors2 = floatArrayOf(0.58f, 0.74f, 1.0f, 1.0f, 1.0f, 0.9f, 0.93f, 1.0f, 0.74f, 0.76f, 1.0f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f),
        colors3 = floatArrayOf(0.98f, 0.86f, 0.9f, 1.0f, 0.6f, 0.73f, 0.98f, 1.0f, 0.92f, 0.93f, 1.0f, 1.0f, 0.56f, 0.69f, 1.0f, 1.0f),
        colorInterpPeriod = 5.0f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.2f,
    )

    private val PHONE_DARK = Config(
        points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
        colors1 = floatArrayOf(0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f, 0.0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f),
        colors2 = floatArrayOf(0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0.0f, 0.2f, 0.78f, 0.5f),
        colors3 = floatArrayOf(0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f),
        colorInterpPeriod = 8.0f,
        lightOffset = 0.0f,
        saturateOffset = 0.17f,
        pointOffset = 0.4f,
    )

    internal fun get(isDark: Boolean): Config = if (!isDark) PHONE_LIGHT else PHONE_DARK
}

private const val OS3_BG_FRAG = """
    uniform vec2 uResolution;
    uniform float uAnimTime;
    uniform vec4 uBound;
    uniform float uTranslateY;
    uniform vec3 uPoints[4];
    uniform vec2 uPointsAnim[4];
    uniform vec4 uColors[4];
    uniform float uAlphaMulti;
    uniform float uNoiseScale;
    uniform float uPointRadiusMulti;
    uniform float uSaturateOffset;
    uniform float uLightOffset;

    vec3 rgb2hsv(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }

    vec3 hsv2rgb(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }

    float hash(vec2 p) {
        vec3 p3 = fract(vec3(p.xyx) * 0.13);
        p3 += dot(p3, p3.yzx + 3.333);
        return fract((p3.x + p3.y) * p3.z);
    }

    float perlin(vec2 x) {
        vec2 i = floor(x); vec2 f = fract(x);

        float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
        float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));

        vec2 u = f * f * (3.0 - 2.0 * f);
        return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
    }

    float gradientNoise(in vec2 uv) {
        return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
    }

    vec4 main(vec2 fragCoord){
        vec2 vUv = fragCoord/uResolution;
        vUv.y = 1.0-vUv.y;
        vec2 uv = vUv;
        uv -= vec2(0., uTranslateY);
        uv.xy -= uBound.xy;
        uv.xy /= uBound.zw;

        vec4 color = vec4(0.0);
        float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));

        for (int i = 0; i < 4; i++){
            vec4 pointColor = uColors[i];
            pointColor.rgb *= pointColor.a;
            vec2 point = uPointsAnim[i];
            float rad = uPoints[i].z * uPointRadiusMulti;

            float d = distance(uv, point);
            float pct = smoothstep(rad, 0., d);
            color.rgb = mix(color.rgb, pointColor.rgb, pct);
            color.a = mix(color.a, pointColor.a, pct);
        }

        float oppositeNoise = smoothstep(0., 1., noiseValue);
        color.rgb /= color.a;
        vec3 hsv = rgb2hsv(color.rgb);
        hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
        color.rgb = hsv2rgb(hsv);
        color.rgb += oppositeNoise * uLightOffset;

        color.a = clamp(color.a, 0., 1.);
        color.a *= uAlphaMulti;

        color += (10.0 / 255.0) * gradientNoise(fragCoord.xy) - (5.0 / 255.0);
        return vec4(color.rgb * color.a, color.a);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class BgEffectPainter {

    val runtimeShader by lazy {
        RuntimeShader(OS3_BG_FRAG).also {
            it.setFloatUniform("uTranslateY", 0f)
            it.setFloatUniform("uNoiseScale", 1.5f)
            it.setFloatUniform("uPointRadiusMulti", 1f)
            it.setFloatUniform("uAlphaMulti", 1f)
        }
    }

    val brush: Brush get() = PainterBrush(runtimeShader)

    private val resolution = FloatArray(2)
    private val bound = FloatArray(4)
    private val colorsBuffer = FloatArray(16)
    private val pointsAnimBuffer = FloatArray(8)

    private var animTime = Float.NaN
    private var isDarkCached: Boolean? = null

    private var presetApplied = false

    private var cachedDrawHeight = Float.NaN
    private var cachedTotalHeight = Float.NaN
    private var cachedTotalWidth = Float.NaN

    private var cachedColorStage = Float.NaN
    private var cachedColorsPreset: BgEffectConfig.Config? = null

    private var cachedPointsAnimTime = Float.NaN
    private var cachedPointsAnimPreset: BgEffectConfig.Config? = null

    fun updateResolution(width: Float, height: Float) {
        if (resolution[0] == width && resolution[1] == height) return
        resolution[0] = width
        resolution[1] = height
        runtimeShader.setFloatUniform("uResolution", resolution)
    }

    fun updateAnimTime(time: Float) {
        if (animTime == time) return
        animTime = time
        runtimeShader.setFloatUniform("uAnimTime", animTime)
    }

    fun updatePointsAnim(time: Float, preset: BgEffectConfig.Config) {
        if (cachedPointsAnimTime == time && cachedPointsAnimPreset === preset) return

        val offset = preset.pointOffset
        var i = 0
        while (i < 4) {
            val srcX = preset.points[i * 3]
            val srcY = preset.points[i * 3 + 1]
            val animX = srcX + sin(time + srcY) * offset
            val animY = srcY + cos(time + animX) * offset
            pointsAnimBuffer[i * 2] = animX
            pointsAnimBuffer[i * 2 + 1] = animY
            i++
        }
        runtimeShader.setFloatUniform("uPointsAnim", pointsAnimBuffer)

        cachedPointsAnimTime = time
        cachedPointsAnimPreset = preset
    }

    fun updateColors(preset: BgEffectConfig.Config, stage: Float) {
        if (cachedColorsPreset === preset && cachedColorStage == stage) return

        val base = stage.toInt()
        val fraction = stage - base
        val start = colorsForCycleIndex(preset, base)
        val end = colorsForCycleIndex(preset, base + 1)
        for (i in 0 until 16) {
            colorsBuffer[i] = start[i] + (end[i] - start[i]) * fraction
        }
        runtimeShader.setFloatUniform("uColors", colorsBuffer)

        cachedColorsPreset = preset
        cachedColorStage = stage
    }

    private fun colorsForCycleIndex(preset: BgEffectConfig.Config, index: Int): FloatArray = when (index.mod(4)) {
        1 -> preset.colors1
        3 -> preset.colors3
        else -> preset.colors2
    }

    fun updateBoundIfNeeded(drawHeight: Float, totalHeight: Float, totalWidth: Float) {
        if (cachedDrawHeight == drawHeight &&
            cachedTotalHeight == totalHeight &&
            cachedTotalWidth == totalWidth
        ) {
            return
        }

        val heightRatio = drawHeight / totalHeight
        if (totalWidth <= totalHeight) {
            bound[0] = 0f
            bound[1] = 1f - heightRatio
            bound[2] = 1f
            bound[3] = heightRatio
        } else {
            val aspectRatio = totalWidth / totalHeight
            val contentCenterY = 1f - heightRatio / 2f
            bound[0] = 0f
            bound[1] = contentCenterY - aspectRatio / 2f
            bound[2] = 1f
            bound[3] = aspectRatio
        }
        runtimeShader.setFloatUniform("uBound", bound)

        cachedDrawHeight = drawHeight
        cachedTotalHeight = totalHeight
        cachedTotalWidth = totalWidth
    }

    fun updatePresetIfNeeded(isDark: Boolean) {
        if (presetApplied && isDarkCached == isDark) return

        val preset = BgEffectConfig.get(isDark)
        runtimeShader.setFloatUniform("uPoints", preset.points)
        runtimeShader.setFloatUniform("uLightOffset", preset.lightOffset)
        runtimeShader.setFloatUniform("uSaturateOffset", preset.saturateOffset)

        isDarkCached = isDark
        presetApplied = true
    }
}

/** The shader as a compose brush — the shader's own uniforms carry the size. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class PainterBrush(private val shader: RuntimeShader) : ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): Shader = shader
}

/**
 * The longest frame the mesh will believe in. Anything past it is not a frame
 * but a gap — the window was covered and its frame callbacks stopped — and
 * letting the mesh count it would be counting the time the page spent off
 * screen as animation it never showed. 100ms: a drop to 10fps, well past the
 * worst real frame, and far short of a visit to another page.
 */
private const val MAX_FRAME_STEP_NANOS = 100_000_000L

/**
 * The mesh's phase, held **outside any composition**.
 *
 * Everything that makes the mesh where-it-is lives here: the drift time and
 * the colour-blend spring (value, velocity, target, switch timer). They used
 * to be per-composition state — `remember`ed next to the painter — and that
 * was the reload: the About window can lose its composition while it is
 * stopped under a child page (the system collects stopped activities under
 * memory pressure, and a retake's re-measure can run a rebuild either way),
 * and the next composition started the mesh over — drift at zero, colours
 * snapped back to the first arrangement — while the page around it came back
 * exactly as it left. Against the snapshot the back preview had just shown,
 * the fresh mesh read as the wallpaper being loaded again.
 *
 * Snapshot-state fields, not plain vars: the draw scope reads them (a state
 * read inside a draw invalidates on write — the clock keeping that power
 * means the loops below still need no explicit invalidate), and snapshot
 * objects are plain heap objects that outlive every composition that touches
 * them. A rebuilt About page resumes from the very phase the old composition
 * froze at — which is the phase its snapshot photographed — so the live mesh
 * continues the photograph frame-for-frame.
 */
internal object BgEffectClock {

    /** Seconds of drift since this process first drew the mesh. */
    val animTime = mutableFloatStateOf(0f)

    /** The colour blend's stage — whole steps are settled arrangements. */
    val colorStage = mutableFloatStateOf(0f)

    /** The blend spring's velocity, carried across compositions. */
    var stageVelocity: Float = 0f

    /**
     * The pending blend target. Initialised to `floor(stage) + 1` wherever a
     * loop takes the clock up, which is exactly where the previous loop left
     * it: a settled stage has no pending half-step, a mid-blend stage is one
     * step ahead.
     */
    var stageTarget: Float = 1f

    /** Nanoseconds accumulated toward the next target switch. */
    var sinceSwitch: Long = 0L
}

internal fun Modifier.bgEffectDraw(
    painter: BgEffectPainter,
    preset: BgEffectConfig.Config,
    isDarkTheme: Boolean,
    surface: Color,
    playing: Boolean,
    started: () -> Boolean,
    colorStage: () -> Float,
    alpha: () -> Float,
): Modifier = this then BgEffectElement(
    painter = painter,
    preset = preset,
    isDarkTheme = isDarkTheme,
    surface = surface,
    playing = playing,
    started = started,
    colorStage = colorStage,
    alpha = alpha,
)

private data class BgEffectElement(
    val painter: BgEffectPainter,
    val preset: BgEffectConfig.Config,
    val isDarkTheme: Boolean,
    val surface: Color,
    val playing: Boolean,
    val started: () -> Boolean,
    val colorStage: () -> Float,
    val alpha: () -> Float,
) : ModifierNodeElement<BgEffectNode>() {

    override fun create(): BgEffectNode = BgEffectNode(
        painter = painter,
        preset = preset,
        isDarkTheme = isDarkTheme,
        surface = surface,
        playing = playing,
        started = started,
        colorStage = colorStage,
        alpha = alpha,
    )

    override fun update(node: BgEffectNode) {
        node.update(painter, preset, isDarkTheme, surface, playing, started, colorStage, alpha)
    }
}

private class BgEffectNode(
    private var painter: BgEffectPainter,
    private var preset: BgEffectConfig.Config,
    private var isDarkTheme: Boolean,
    private var surface: Color,
    private var playing: Boolean,
    private var started: () -> Boolean,
    private var colorStage: () -> Float,
    private var alpha: () -> Float,
) : Modifier.Node(),
    DrawModifierNode {

    private var animationJob: Job? = null

    override fun onAttach() {
        if (playing) startAnimation()
    }

    override fun onDetach() {
        animationJob?.cancel()
        animationJob = null
    }

    fun update(
        painter: BgEffectPainter,
        preset: BgEffectConfig.Config,
        isDarkTheme: Boolean,
        surface: Color,
        playing: Boolean,
        started: () -> Boolean,
        colorStage: () -> Float,
        alpha: () -> Float,
    ) {
        this.painter = painter
        this.preset = preset
        this.isDarkTheme = isDarkTheme
        this.surface = surface
        this.started = started
        this.colorStage = colorStage
        this.alpha = alpha

        if (this.playing != playing) {
            this.playing = playing
            if (playing) {
                startAnimation()
            } else {
                animationJob?.cancel()
                animationJob = null
            }
        }
        invalidateDraw()
    }

    private fun startAnimation() {
        animationJob?.cancel()
        animationJob = coroutineScope.launch {
            val minDeltaNanos = 1_000_000_000L / 60L
            // The phase is accumulated from the frame clock's own deltas, one
            // frame at a time, instead of being read off an absolute clock. That
            // is not a style choice: the frame clock stops when the window is
            // stopped — the app leaves the foreground — and hands back ONE
            // delta spanning the whole absence when it returns. Read
            // absolutely, that delta teleports the mesh to wherever it would
            // have drifted to, and the page comes back showing a colour
            // arrangement that was never on screen: the flash this page used to
            // end on. Clamped, the clock's re-entry costs the mesh nothing and
            // it resumes mid-drift, exactly as it left.
            var last = withFrameNanos { it }
            var sinceDraw = 0L
            while (isActive) {
                val now = withFrameNanos { it }
                val delta = (now - last).coerceIn(0L, MAX_FRAME_STEP_NANOS)
                last = now
                // Off the foreground — hold. Cancellation alone cannot be
                // trusted to stop this loop while the window sits under a child
                // page: the playing=false update travels through a
                // recomposition, and the reveal would then photograph a phase
                // the user never saw. Read through the state delegate — same
                // trick as the colour spring — so the gate engages the moment
                // the lifecycle writes, no recomposition needed.
                if (!started()) continue
                sinceDraw += delta
                BgEffectClock.animTime.floatValue += delta / 1_000_000_000f
                if (sinceDraw < minDeltaNanos) continue
                sinceDraw = 0L
                invalidateDraw()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun ContentDrawScope.draw() {
        drawRect(surface)
        val alphaValue = alpha()
        if (alphaValue > 0f) {
            val drawHeight = size.height * 0.8f

            painter.updateResolution(size.width, size.height)
            painter.updateBoundIfNeeded(drawHeight, size.height, size.width)
            painter.updatePresetIfNeeded(isDarkTheme)
            painter.updateColors(preset, BgEffectClock.colorStage.floatValue)
            painter.updateAnimTime(BgEffectClock.animTime.floatValue)
            painter.updatePointsAnim(BgEffectClock.animTime.floatValue, preset)

            drawRect(painter.brush, alpha = alphaValue)
        }
        drawContent()
    }
}

/**
 * The full-size dynamic background. Dark read off the page colour — the app's
 * own palette owns darkness (it can force dark against the system), so the
 * background's luminance is the honest read, the same rule [MiuixStatusHero]
 * runs on. Nothing draws below T (AGSL), leaving the scaffold's plain colour.
 */
@Composable
fun MiuixBgEffectBackground(
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val surface = MiuixTheme.colorScheme.surface
    val painter = remember { BgEffectPainter() }
    val preset = remember(isDark) { BgEffectConfig.get(isDark) }

    // The mesh holds whenever this window is not the one the user is in front
    // of. `RESUMED`, not `STARTED`, is what that means now: the sub-page windows
    // are translucent (Theme.HyperFCMLive.Translucent), so a child page on top
    // leaves this one PAUSED — still STARTED, still holding its surface, its
    // recorded display list and its frame clock. Gated on STARTED, an uncovered
    // mesh would keep drifting behind that child: a full-screen shader render
    // per frame over a window the compositor holds but nobody can see. Frozen
    // for as long as the window is off the foreground, the live mesh and the
    // photograph the back preview reveals are one and the same frame, and the
    // reveal continues the mesh exactly where the preview left it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState(lifecycle.currentState)
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    // The colour stage steps its spring by hand, on the same clamped frame
    // deltas the mesh's own drift runs on ([BgEffectDrawer.startAnimation]'s
    // loop). `Animatable.animateTo` would read the frame clock directly, and
    // the clock stops while this window is covered — the licences and privacy
    // pages are separate windows on top of it. On return it hands back ONE
    // delta spanning the whole visit; an Animatable integrates that in a
    // single step, the spring teleports through it, and the colours land on an
    // arrangement they were never seen leaving — the flash the page used to
    // end on when coming back from the licences page. Stepped by hand, the
    // re-entry costs the spring one clamped tick and the blend resumes exactly
    // where the window left it.
    //
    // The spring's state is [BgEffectClock]'s, not this composition's: this
    // composable can be rebuilt while its window sits stopped under a child
    // page, and a composition-owned spring would restart the blend from stage
    // zero — the wallpaper reload. From the clock, a rebuilt page picks up the
    // blend mid-flight, at the same phase the back preview's snapshot shows.
    LaunchedEffect(preset) {
        // The same spring Animatable ran (dampingRatio 0.9, stiffness 35),
        // integrated analytically over each clamped delta; the stage target
        // advances every interp period, the way the old delay/animateTo loop
        // walked it.
        val omega = sqrt(35f)
        val zeta = 0.9f
        val omegaD = omega * sqrt(1f - zeta * zeta)
        var target = floor(BgEffectClock.colorStage.floatValue) + 1f
        var last = withFrameNanos { it }
        val switchNanos = (preset.colorInterpPeriod * 500L * 1_000_000L).toLong()
        while (isActive) {
            val now = withFrameNanos { it }
            val deltaNanos = (now - last).coerceIn(0L, MAX_FRAME_STEP_NANOS)
            last = now
            // Off the foreground: hold everything — spring, target switch,
            // velocity — so the colours stay the ones the window is frozen at.
            // `last` keeps up above, so the real resume hands back no giant
            // delta. Read through the state delegate, so the loop observes the
            // lifecycle flipping back without a recomposition of its own.
            if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) continue
            BgEffectClock.sinceSwitch += deltaNanos
            if (BgEffectClock.sinceSwitch >= switchNanos) {
                BgEffectClock.sinceSwitch = 0L
                target += 1f
                BgEffectClock.stageTarget = target
            }
            val dt = deltaNanos / 1_000_000_000f
            val value = BgEffectClock.colorStage.floatValue
            val velocity = BgEffectClock.stageVelocity
            // Settled springs stay written off: no state writes, no redraws.
            if (dt > 0f && (value != target || velocity != 0f)) {
                val x0 = value - target
                val decay = exp(-zeta * omega * dt)
                val cosT = cos(omegaD * dt)
                val sinT = sin(omegaD * dt)
                val envelope = decay * (x0 * cosT + (velocity + zeta * omega * x0) / omegaD * sinT)
                var newVelocity = decay * (
                    -zeta * omega * (x0 * cosT + (velocity + zeta * omega * x0) / omegaD * sinT) -
                        x0 * omegaD * sinT +
                        (velocity + zeta * omega * x0) / omegaD * omegaD * cosT
                    )
                var newValue = target + envelope
                if (abs(newValue - target) < 0.01f && abs(newVelocity) < 0.01f) {
                    newValue = target
                    newVelocity = 0f
                }
                BgEffectClock.stageVelocity = newVelocity
                BgEffectClock.colorStage.floatValue = newValue
            }
        }
    }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .bgEffectDraw(
                painter = painter,
                preset = preset,
                isDarkTheme = isDark,
                surface = surface,
                playing = foreground,
                started = { lifecycleState.isAtLeast(Lifecycle.State.RESUMED) },
                colorStage = { BgEffectClock.colorStage.floatValue },
                alpha = alpha,
            ),
    )
}
