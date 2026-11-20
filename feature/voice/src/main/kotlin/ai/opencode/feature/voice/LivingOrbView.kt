package ai.opencode.feature.voice

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.ui.gl.GlPass
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.opengl.GLES30
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Half
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What a Living Orb draws. */
data class LivingOrbConfig(
    val mood: FloatingCallMood,
    val isMuted: Boolean,
    val isSpeaking: Boolean,
    val scene: FloatingCallScene? = null,
    /** Where the disc sits when the view is bigger than it (One Shape's card and sheet). Null centres it in the view. */
    val bloom: LivingOrbBloom? = null,
    /** The tool the agent is running, which picks Thinking's choreography. */
    val tool: String? = null,
) {
    /** Wisp's state for this call moment: speech first, then a request, work, and an open, listening call. */
    fun wispState(userLevel: Float): WispOrbState {
        if (scene != null) {
            return when (scene) {
                FloatingCallScene.idle, FloatingCallScene.listening -> WispOrbState.listening
                FloatingCallScene.agentSpeaking -> WispOrbState.speaking
                FloatingCallScene.bothSpeaking -> if (isMuted) WispOrbState.speaking else WispOrbState.bothSpeaking
                FloatingCallScene.thinking -> WispOrbState.thinking
                FloatingCallScene.needsInput -> WispOrbState.confirming
            }
        }
        if (mood == FloatingCallMood.connecting) return WispOrbState.connecting
        if (mood == FloatingCallMood.ended) return WispOrbState.sleeping
        val talking = !isMuted && userLevel > 0.06f
        if (isSpeaking) return if (talking) WispOrbState.bothSpeaking else WispOrbState.speaking
        if (mood == FloatingCallMood.needsInput) return WispOrbState.confirming
        if (mood == FloatingCallMood.thinking) return WispOrbState.thinking
        return WispOrbState.listening
    }
}

/** The disc inside a bigger field, in the view's dp. */
data class LivingOrbBloom(
    val center: Offset,
    val radius: Float,
    /** The dots below this fade out over [fade] dp above it. */
    val bottom: Float = Float.MAX_VALUE,
    val fade: Float = 0.5f,
    /** The glass the disc sits in, which it feathers into (the card or sheet). */
    val visible: Rect? = null,
) {
    /** What the placed dots feather into: the visible shape (or the disc), its corner, and how far in they fade. */
    data class Edge(val shape: Rect, val corner: Float, val feather: Float)

    fun edge(feather: Double): Edge {
        val shape = visible ?: Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
        val half = min(shape.width, shape.height) / 2
        val corner = FloatingCallStageTunables.cardRadius.value.toFloat()
        return Edge(shape, if (visible == null) radius else min(corner, half), min(feather.toFloat(), 0.18f * half))
    }
}

/**
 * The call's voice orb: Wisp's glass lens and phosphor dots ([WispOrbSim], [WispOrbShaders]).
 *
 * Android: an AGSL [RuntimeShader] drawn by a Compose [Canvas] (on Android 12 the same maths
 * ported to GLSL ES 3.0, rendered off-screen — [WispOrbRaster]'s Gl renderer — with the Canvas
 * approximation only if GLES is unusable); a `withFrameNanos` loop steps the
 * plain-object [WispOrbRenderer] only while it shows. Under Reduce Motion it draws once whenever
 * its inputs change. Wisp's glass looks the same in light and dark.
 *
 * @param isHidden faded out behind the panel: no drawing.
 * @param bloom where the bloom is this frame, read as each frame renders (overrides `config.bloom`).
 */
@Composable
fun LivingOrbView(config: LivingOrbConfig, modifier: Modifier = Modifier, isHidden: Boolean = false, bloom: (() -> LivingOrbBloom?)? = null) {
    LaunchedEffect(Unit) { WispOrbTunables.register() }
    val reduceMotion = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val renderer = remember { WispOrbRenderer.make() } ?: return
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableIntStateOf(0) }
    val currentConfig by rememberUpdatedState(config)
    val currentBloom by rememberUpdatedState(bloom)

    fun configure() {
        // A bloom that moves every frame is read here, each frame, rather than recomposing.
        val bloomNow = currentBloom
        renderer.config = if (bloomNow != null) currentConfig.copy(bloom = bloomNow()) else currentConfig
        renderer.tuning = WispOrbTuning.current
        renderer.isStill = reduceMotion
    }

    val isPaused = isHidden || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(renderer, isPaused, reduceMotion, size, density, if (reduceMotion) config else Unit) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        renderer.resetClock()
        if (isPaused) return@LaunchedEffect
        if (reduceMotion) {
            configure()
            if (renderer.render(System.nanoTime() / 1e9, size.width, size.height, density)) frame++
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { nanos ->
                configure()
                if (renderer.render(nanos / 1e9, size.width, size.height, density)) frame++
            }
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .clearAndSetSemantics {},
    ) {
        @Suppress("UNUSED_EXPRESSION") frame
        with(renderer) { draw() }
    }
}

/**
 * Steps [WispOrbSim] at display rate and draws a frame: [WispOrbCells] lights every LED, then
 * [WispOrbShaders] rasterises the lens. A plain object, one per mounted orb; main thread only.
 *
 * Android: the cell kernel runs on the CPU and its cells reach the shader as a half-float bitmap.
 */
class WispOrbRenderer private constructor() {
    private val raster = WispOrbRaster()
    private val sim = WispOrbSim()
    private var columns = 0
    /** Each cell's fast and slow phosphor for both voices, kept between frames. */
    private var phosphor = FloatArray(0)
    /** [WispOrbCells.floatsPerCell] floats per LED, written by the kernel and uploaded for the raster. */
    private var cells = FloatArray(0)
    /** The newest steps of both voices' wave history (`WISP_TRACE`). */
    private val traces = FloatArray(traceCount * 4)
    private var tool: String? = null
    private var last: Double? = null

    // Hot loop: the meter resolved once.
    private val levels = VoiceAudioLevels.shared

    var config = LivingOrbConfig(mood = FloatingCallMood.live, isMuted = false, isSpeaking = false)
    var tuning = WispOrbTuning()
    var isStill = false

    fun resetClock() {
        last = null
    }

    /** New buffers, every LED dark, only when the lattice changes. */
    private fun prepare(next: Int) {
        if (next == columns && raster.prepared) return
        val count = max(1, next * next)
        phosphor = FloatArray(count * 4)
        cells = FloatArray(count * WispOrbCells.floatsPerCell)
        raster.prepare(next)
        columns = next
    }

    /** Steps (or stills) the orb for the frame at [nowSeconds] and uploads it. */
    fun render(nowSeconds: Double, pixelWidth: Int, pixelHeight: Int, scale: Float): Boolean {
        if (pixelWidth < 1 || pixelHeight < 1 || scale <= 0f) return false
        val tuning = tuning.clamped()
        prepare(tuning.columns)
        val dt = AnimationTimeScale.shared.scaledDelta(max(0.0, min(0.05, nowSeconds - (last ?: nowSeconds))))
        last = if (isStill) null else nowSeconds

        val (user, agent) = voice(nowSeconds)
        if (config.tool != tool) {
            tool = config.tool
            sim.setWorkingTool(tool)
        }
        sim.setMuted(config.isMuted)
        sim.step(dt.toFloat(), user, agent, config.wispState(user), isStill, tuning)
        val uniforms = sim.frame(tuning, placement(pixelWidth, pixelHeight, scale, tuning))
        config.bloom?.let { bloom ->
            val clip = uniforms[WispOrbUniforms.clipShape]
            if (bloom.visible != null) {
                val edge = bloom.edge(feather = 8.0)
                uniforms[WispOrbUniforms.clipRect] = floatArrayOf(
                    edge.shape.left * scale, edge.shape.top * scale, edge.shape.right * scale, edge.shape.bottom * scale,
                )
                clip[0] = edge.corner * scale
                clip[1] = edge.feather * scale
            }
            clip[2] = min(bloom.bottom, 1e6f) * scale
            clip[3] = max(0.5f, bloom.fade) * scale
            uniforms[WispOrbUniforms.clipShape] = clip
        }
        for (i in 0 until traceCount) {
            traces[i * 4] = sim.userTrace.newestAmplitude(i)
            traces[i * 4 + 1] = sim.userTrace.newestHarmonic(i)
            traces[i * 4 + 2] = sim.agentTrace.newestAmplitude(i)
            traces[i * 4 + 3] = sim.agentTrace.newestHarmonic(i)
        }
        WispOrbCells.evaluate(uniforms.data, phosphor, traces, cells)
        raster.upload(cells)
        raster.publish(uniforms)
        return true
    }

    fun DrawScope.draw() {
        with(raster) { draw() }
    }

    /**
     * The disc's centre and scale in the drawable. A bloom's circle is the lens (the glass around it
     * clips the shadow); otherwise the lens and its shadow bleed fill the view, as Wisp's window does.
     */
    private fun placement(width: Int, height: Int, scale: Float, tuning: WispOrbTuning): WispOrbPlacement {
        config.bloom?.let { bloom ->
            return WispOrbPlacement(bloom.center.x * scale, bloom.center.y * scale, max(bloom.radius * scale / (0.5f * tuning.diameter), 0.1f))
        }
        val box = tuning.diameter + 2 * WispOrb.bleed
        return WispOrbPlacement(width / 2f, height / 2f, max(min(width, height) / box, 0.1f))
    }

    private fun voice(time: Double): Pair<Float, Float> {
        val scene = config.scene
        var levels: Pair<Float, Float> = if (scene != null) {
            val virtual = AnimationTimeScale.shared.now(time)
            val you = LivingOrbVoiceSignal.sample(virtual, seed = 0.4).level.toFloat()
            val agent = LivingOrbVoiceSignal.sample(virtual, seed = 1.9).level.toFloat()
            when (scene) {
                FloatingCallScene.listening -> you to 0f
                FloatingCallScene.agentSpeaking -> 0f to agent
                FloatingCallScene.bothSpeaking -> you to agent
                FloatingCallScene.idle, FloatingCallScene.thinking, FloatingCallScene.needsInput -> 0f to 0f
            }
        } else {
            val live = levels.snapshot()
            live.user to live.assistant
        }
        if (config.isMuted) levels = 0f to levels.second
        return levels
    }

    companion object {
        /** `WISP_TRACE`. */
        const val traceCount = 64

        fun make(): WispOrbRenderer? = runCatching { WispOrbRenderer() }.getOrNull()
    }
}

/**
 * The orb's raster, one per API level: the AGSL pass on 33+, on Android 12 the same maths ported
 * to GLSL ES 3.0 and rendered off-screen by the shared GLES context ([Gl]) — the CPU kernel's
 * cells upload as a texture the ported pass samples at the two texels per LED — and the Canvas
 * approximation ([CanvasLens]) only if GLES is unusable.
 */
private class WispOrbRaster {
    private val renderer: Renderer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Agsl() else Gl()

    val prepared: Boolean get() = renderer.prepared

    fun prepare(columns: Int) = renderer.prepare(columns)

    fun upload(cells: FloatArray) = renderer.upload(cells)

    fun publish(uniforms: WispOrbUniforms) = renderer.publish(uniforms)

    fun DrawScope.draw() {
        with(renderer) { draw() }
    }

    private interface Renderer {
        val prepared: Boolean
        fun prepare(columns: Int)
        fun upload(cells: FloatArray)
        fun publish(uniforms: WispOrbUniforms)
        fun DrawScope.draw()
    }

    /** The AGSL pass, API 33+, unchanged. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class Agsl : Renderer {
        private val shader = RuntimeShader(WispOrbShaders.wispOrbFragment)
        private var bitmap: Bitmap? = null
        private var pixels: ByteBuffer? = null
        private var columns = 0
        private var brush: ShaderBrush? = null

        override val prepared: Boolean get() = bitmap != null

        override fun prepare(next: Int) {
            val texture = createBitmap(max(1, 2 * next), max(1, next), Bitmap.Config.RGBA_F16, hasAlpha = true, colorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
            bitmap = texture
            pixels = ByteBuffer.allocateDirect(max(1, 2 * next) * max(1, next) * 8).order(ByteOrder.nativeOrder())
            val input = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }
            shader.setInputBuffer("cells", input)
            columns = next
            brush = null
        }

        // The texture holds raw half-float bits (`Half.toHalf` → `putShort`), read by the shader as data:
        // pixel 2i is (dot radius, dot alpha, halo alpha, 1), pixel 2i + 1 the colour.
        @SuppressLint("HalfFloat")
        override fun upload(cells: FloatArray) {
            val buffer = pixels ?: return
            val texture = bitmap ?: return
            buffer.clear()
            val one = Half.toHalf(1f)
            for (n in 0 until columns * columns) {
                val c = n * WispOrbCells.floatsPerCell
                buffer.putShort(Half.toHalf(cells[c]))
                buffer.putShort(Half.toHalf(cells[c + 2]))
                buffer.putShort(Half.toHalf(cells[c + 3]))
                buffer.putShort(one)
                buffer.putShort(Half.toHalf(cells[c + 4]))
                buffer.putShort(Half.toHalf(cells[c + 5]))
                buffer.putShort(Half.toHalf(cells[c + 6]))
                buffer.putShort(one)
            }
            buffer.rewind()
            texture.copyPixelsFromBuffer(buffer)
        }

        override fun publish(uniforms: WispOrbUniforms) {
            for ((name, slot) in WispOrbShaders.rasterSlots) {
                val o = slot * 4
                shader.setFloatUniform(name, uniforms.data[o], uniforms.data[o + 1], uniforms.data[o + 2], uniforms.data[o + 3])
            }
            if (brush == null) brush = ShaderBrush(shader)
        }

        override fun DrawScope.draw() {
            val brush = brush ?: return
            drawRect(brush)
        }
    }

    /**
     * Android 12: the AGSL pass above ported to GLSL ES 3.0 (docs/android-12-support.md §10.2),
     * rendered off-screen by the shared GLES context and composited as a bitmap. The CPU
     * kernel's cells upload as a texture the ported pass samples at the two texels per LED
     * (`texelFetch`, the same texels `cells.eval(2i + 0.5)` and `2i + 1.5` read). The uniform
     * names and values keep [WispOrbShaders.rasterSlots].
     */
    private class Gl : Renderer {
        private val pass = GlPass(WispOrbShaders.wispOrbFragmentGlsl)
        // Warmed with every upload/publish, so if the GL pass fails the Canvas approximation
        // takes over on the same frame's state, with no blank frames.
        private val canvas = CanvasLens()
        private var bitmap: Bitmap? = null
        private var pixels: ByteBuffer? = null
        private var columns = 0
        private var texture = 0
        private var data = FloatArray(0)

        override val prepared: Boolean get() = bitmap != null

        override fun prepare(next: Int) {
            bitmap = createBitmap(max(1, 2 * next), max(1, next), Bitmap.Config.RGBA_F16, hasAlpha = true, colorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
            pixels = ByteBuffer.allocateDirect(max(1, 2 * next) * max(1, next) * 8).order(ByteOrder.nativeOrder())
            columns = next
            canvas.prepare(next)
        }

        // The texture holds raw half-float bits (`Half.toHalf` → `putShort`), read by the shader as data:
        // pixel 2i is (dot radius, dot alpha, halo alpha, 1), pixel 2i + 1 the colour.
        @SuppressLint("HalfFloat")
        override fun upload(cells: FloatArray) {
            canvas.upload(cells)
            val buffer = pixels ?: return
            val textureBitmap = bitmap ?: return
            buffer.clear()
            val one = Half.toHalf(1f)
            for (n in 0 until columns * columns) {
                val c = n * WispOrbCells.floatsPerCell
                buffer.putShort(Half.toHalf(cells[c]))
                buffer.putShort(Half.toHalf(cells[c + 2]))
                buffer.putShort(Half.toHalf(cells[c + 3]))
                buffer.putShort(one)
                buffer.putShort(Half.toHalf(cells[c + 4]))
                buffer.putShort(Half.toHalf(cells[c + 5]))
                buffer.putShort(Half.toHalf(cells[c + 6]))
                buffer.putShort(one)
            }
            buffer.rewind()
            textureBitmap.copyPixelsFromBuffer(buffer)
        }

        override fun publish(uniforms: WispOrbUniforms) {
            canvas.publish(uniforms)
            val textureBitmap = bitmap ?: return
            texture = pass.uploadTexture(textureBitmap, texture)
            data = uniforms.data.copyOf()
        }

        override fun DrawScope.draw() {
            if (texture == 0 || data.isEmpty()) {
                with(canvas) { draw() }
                return
            }
            val width = ceil(size.width).toInt()
            val height = ceil(size.height).toInt()
            val d = data
            val rendered = pass.render(width, height) { gl ->
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                GLES30.glUniform1i(gl.location("cells"), 0)
                for ((name, slot) in WispOrbShaders.rasterSlots) {
                    val o = slot * 4
                    GLES30.glUniform4f(gl.location(name), d[o], d[o + 1], d[o + 2], d[o + 3])
                }
                GLES30.glUniform2f(gl.location("uSize"), width.toFloat(), height.toFloat())
            }
            val frame = if (rendered) pass.bitmap(width, height) else null
            if (frame == null) {
                with(canvas) { draw() }
            } else {
                drawImage(frame.asImageBitmap())
            }
        }
    }

    /**
     * API < 33: the plate, the glow and the kernel's lit LEDs as circles. Main thread only; the
     * kernel runs on the same thread, so the published cells are read in place.
     */
    private class CanvasLens : Renderer {
        override val prepared: Boolean get() = true

        override fun prepare(columns: Int) {}

        override fun upload(cells: FloatArray) {
            this.cells = cells
        }

        override fun publish(uniforms: WispOrbUniforms) {
            val d = uniforms.data
            fun slot(s: Int, i: Int) = d[s * 4 + i]
            cols = slot(WispOrbUniforms.grid, 0).toInt()
            pitch = slot(WispOrbUniforms.grid, 1)
            radius = slot(WispOrbUniforms.grid, 2)
            centreX = slot(WispOrbUniforms.view, 0)
            centreY = slot(WispOrbUniforms.view, 1)
            ppp = slot(WispOrbUniforms.view, 2)
            sleepMix = slot(WispOrbUniforms.mixA, 0)
            muted = slot(WispOrbUniforms.mixB, 1)
            tintR = slot(WispOrbUniforms.tintBase, 0)
            tintG = slot(WispOrbUniforms.tintBase, 1)
            tintB = slot(WispOrbUniforms.tintBase, 2)
            coreR = slot(WispOrbUniforms.tintCore, 0)
            coreG = slot(WispOrbUniforms.tintCore, 1)
            coreB = slot(WispOrbUniforms.tintCore, 2)
            glowStrength = slot(WispOrbUniforms.glow, 0)
            glowReach = slot(WispOrbUniforms.glow, 1)
            clipL = slot(WispOrbUniforms.clipRect, 0)
            clipT = slot(WispOrbUniforms.clipRect, 1)
            clipR = slot(WispOrbUniforms.clipRect, 2)
            clipB = slot(WispOrbUniforms.clipRect, 3)
            clipCorner = slot(WispOrbUniforms.clipShape, 0)
            clipBottom = slot(WispOrbUniforms.clipShape, 2)
            clipFade = slot(WispOrbUniforms.clipShape, 3)
            // The plate: the night glass shifted toward ember as it sleeps, the muted plate over it.
            val m = (sleepMix * 0.65f).coerceIn(0f, 1f)
            plateR = (15f + (44f - 15f) * m) / 255f
            plateG = (16f + (16f - 16f) * m) / 255f
            plateB = (20f + (18f - 20f) * m) / 255f
            plateR += (0.102f - plateR) * muted
            plateG += (0.055f - plateG) * muted
            plateB += (0.063f - plateB) * muted
        }

        private var cols = 0
        private var pitch = 0f
        private var radius = 0f
        private var centreX = 0f
        private var centreY = 0f
        private var ppp = 0f
        private var muted = 0f
        private var sleepMix = 0f
        private var tintR = 0f
        private var tintG = 0f
        private var tintB = 0f
        private var coreR = 0f
        private var coreG = 0f
        private var coreB = 0f
        private var glowStrength = 0f
        private var glowReach = 0f
        private var clipL = 0f
        private var clipT = 0f
        private var clipR = 0f
        private var clipB = 0f
        private var clipCorner = -1f
        private var clipBottom = 1e9f
        private var clipFade = 1f
        private var plateR = 0f
        private var plateG = 0f
        private var plateB = 0f
        private var cells = FloatArray(0)

        // Cached: the glow's white gradient and the mute ring's paint rebuild never per frame —
        // the glow's tint rides a colour filter and both levels ride alphas.
        private val glowPaint = Paint().apply {
            // The glow's alpha falls as (1 − gt)² from the lens's centre to `glowReach` away.
            shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(-1, 0x4DFFFFFF, 0x10FFFFFF, 0),
                floatArrayOf(0f, 0.45f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        private val mutePaint = Paint().apply { style = Paint.Style.STROKE }

        override fun DrawScope.draw() {
            if (cols <= 0 || ppp <= 0f) return
            val r = radius * ppp
            val cx = centreX
            val cy = centreY
            if (clipCorner >= 0f) {
                clipPath(glassPath()) { lens(cx, cy, r) }
            } else {
                lens(cx, cy, r)
            }
        }

        private fun DrawScope.lens(cx: Float, cy: Float, r: Float) {
            val fade = ((clipBottom - cy) / max(clipFade, 1e-3f)).coerceIn(0f, 1f)
            if (fade <= 0f) return
            // The glass plate.
            drawCircle(Color(plateR, plateG, plateB, 0.94f * fade), r, Offset(cx, cy))
            drawGlow(cx, cy, fade)
            drawDots(cx, cy)
            drawMuteRing(cx, cy, r, fade)
        }

        private fun DrawScope.drawGlow(cx: Float, cy: Float, fade: Float) {
            val strength = glowStrength
            if (strength <= 0.004f) return
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                val midR = (tintR + coreR) * 0.5f
                val midG = (tintG + coreG) * 0.5f
                val midB = (tintB + coreB) * 0.5f
                glowPaint.colorFilter = LightingColorFilter(
                    ((midR * 255f).toInt() shl 16) or ((midG * 255f).toInt() shl 8) or (midB * 255f).toInt(),
                    0,
                )
                glowPaint.alpha = ((strength.coerceIn(0f, 1f) * fade) * 255f).toInt()
                nc.save()
                nc.translate(cx, cy)
                nc.scale(glowReach * ppp, glowReach * ppp)
                nc.drawCircle(0f, 0f, 1f, glowPaint)
                nc.restore()
            }
        }

        private fun DrawScope.drawDots(cx: Float, cy: Float) {
            val c = cells
            if (c.isEmpty()) return
            val off = (cols - 1) * 0.5f
            val pitchPx = pitch * ppp
            for (j in 0 until cols) {
                val y = cy + (j - off) * pitchPx
                val fade = ((clipBottom - y) / max(clipFade, 1e-3f)).coerceIn(0f, 1f)
                if (fade <= 0f) continue
                for (i in 0 until cols) {
                    val c8 = (j * cols + i) * WispOrbCells.floatsPerCell
                    val dotA = c[c8 + 2]
                    if (dotA <= 0.02f) continue
                    val x = cx + (i - off) * pitchPx
                    val colour = Color(c[c8 + 4], c[c8 + 5], c[c8 + 6], 1f)
                    val haloA = c[c8 + 3]
                    if (haloA > 0f) drawCircle(colour.copy(alpha = (haloA * fade).coerceIn(0f, 1f)), c[c8 + 1] * ppp, Offset(x, y))
                    drawCircle(colour.copy(alpha = (dotA * fade).coerceIn(0f, 1f)), c[c8] * ppp, Offset(x, y))
                }
            }
        }

        private fun DrawScope.drawMuteRing(cx: Float, cy: Float, r: Float, fade: Float) {
            if (muted <= 1e-3f) return
            mutePaint.strokeWidth = 1.5f * ppp
            mutePaint.color = (((0.55f * muted * fade).coerceIn(0f, 1f) * 255f).toInt() shl 24) or 0xF87171
            drawIntoCanvas { it.nativeCanvas.drawCircle(cx, cy, r + 0.75f * ppp, mutePaint) }
        }

        private val glass = Path()
        private var glassL = 0f
        private var glassT = 0f
        private var glassR = 0f
        private var glassB = 0f
        private var glassCorner = 0f

        private fun glassPath(): Path {
            if (glassL != clipL || glassT != clipT || glassR != clipR || glassB != clipB || glassCorner != clipCorner) {
                glass.reset()
                glass.addRoundRect(RoundRect(clipL, clipT, clipR, clipB, CornerRadius(clipCorner.coerceAtLeast(0f))))
                glassL = clipL
                glassT = clipT
                glassR = clipR
                glassB = clipB
                glassCorner = clipCorner
            }
            return glass
        }
    }
}

/**
 * A made-up voice for scripted orbs (the Orb settings preview, the lab): phrases of syllables with
 * pauses between them, stressed syllables that make onsets, and a falling intonation with bright
 * consonants for Voice Tone. Levels only: nothing plays.
 */
object LivingOrbVoiceSignal {
    data class Sample(val level: Double, val tone: Double)

    fun sample(time: Double, seed: Double): Sample {
        val period = 3.6
        val clock = time + seed * 1.7
        val phrase = floor(clock / period)
        val local = clock - phrase * period
        val length = 1.7 + 1.1 * hash(phrase, seed)
        val contour = 0.62 - 0.3 * min(1.0, local / length) + 0.12 * sin(local * 2.3 + seed)
        if (local >= length) return Sample(0.0, contour)
        val edges = smooth(local / 0.12) * smooth((length - local) / 0.25)
        val beat = local * (4.2 + 0.8 * hash(phrase + 9, seed))
        val syllable = floor(beat)
        val within = beat - syllable
        val shape = sin(PI * within).pow(1.5)
        val stress = 0.5 + 0.5 * hash(syllable + phrase * 31, seed)
        val hiss = if (hash(syllable * 7 + phrase, seed + 3) > 0.72) 0.35 * (1 - shape) else 0.0
        val level = edges * (0.15 + 0.75 * shape * stress)
        return Sample(level, min(1.0, max(0.0, contour + hiss + 0.15 * (stress - 0.5))))
    }

    private fun hash(n: Double, seed: Double): Double {
        val x = sin(n * 12.9898 + seed * 78.233) * 43758.5453
        return x - floor(x)
    }

    private fun smooth(x: Double): Double {
        val t = max(0.0, min(1.0, x))
        return t * t * (3 - 2 * t)
    }
}
