package ai.opencode.feature.voice

import ai.opencode.core.log.AppLog
import ai.opencode.core.diagnostics.PerfTrace
import ai.opencode.core.ui.gl.GlPass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Session sets it while the session is covered (a browser page, Catch Up), so every scope
 * underneath stops drawing while keeping its simulation. Covered scopes run no frame loop at all.
 */
val LocalDotScopeCovered = staticCompositionLocalOf { false }

/** Composer's dictation meter, read once per frame. */
fun interface DotScopeLevelSource {
    fun level(): Float
}

sealed interface DotScopeMode {
    /** Both traces render in your warm pearl and follow [level] (Composer's dictation levels). */
    data class Dictation(val leadingInset: Float, val trailingInset: Float, val level: DotScopeLevelSource) : DotScopeMode

    /**
     * Settings → Voice: a simulated conversation, you then the agent, while [active]; at rest
     * otherwise. No microphone or call involved.
     */
    data class Preview(val active: Boolean) : DotScopeMode
}

/**
 * The dictation bar's visual: an LED dot-matrix oscilloscope where your voice (warm
 * pearl) and the agent's (cool pearl) are two braided traces leaving phosphor trails over a
 * soft glow behind each button. In dictation mode both traces render in your warm pearl and
 * follow the dictation level. Under Reduce Motion it is the quiet state, still; in the
 * background it stops drawing.
 *
 * Android: an AGSL [RuntimeShader] ([DotScopeShaders]) drawn by a Compose [Canvas] (on
 * Android 12 the same maths ported to GLSL ES 3.0, rendered off-screen — [DotScopeRaster]'s
 * Gl renderer — with the Canvas approximation only if GLES is unusable); a
 * `withFrameNanos` loop advances the plain-object [DotScopeRenderer] (never reducer actions) only
 * while the scope is visible: not [LocalDotScopeCovered], and the lifecycle resumed. A paused scope
 * keeps its last frame and resumes with a reset clock, so it never fast-forwards. Under Reduce
 * Motion it draws one still frame whenever its inputs change. After [DotScopeRenderer.QUIET_AFTER]
 * of silence only the slow carrier moves, so it draws every other frame.
 */
@Composable
fun DotScopeView(
    mode: DotScopeMode,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val isDark = OpenCodeTheme.colors.isDark
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isCovered = LocalDotScopeCovered.current
    val scope = remember { DotScopeRenderer.make() } ?: return
    DisposableEffect(scope) { onDispose { scope.close() } }
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableIntStateOf(0) }
    val currentMode by rememberUpdatedState(mode)

    // Each mounted surface owns its configuration: apply it at draw time.
    fun configure() {
        scope.isDark = isDark
        scope.isStill = reduceMotion
        scope.mode = currentMode
    }

    val isPaused = isCovered || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(scope, isPaused, reduceMotion, size, density, if (reduceMotion) mode else Unit, isDark) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        if (isPaused) return@LaunchedEffect
        scope.resetClock()
        if (reduceMotion) {
            // Still: draw only when something changes.
            configure()
            if (scope.render(nowSeconds = System.nanoTime() / 1e9, pixelWidth = size.width, pixelHeight = size.height, scale = density)) frame++
            return@LaunchedEffect
        }
        var frames = 0L
        while (true) {
            val nanos = withFrameNanos { it }
            frames++
            if (!DotScopeRenderer.drawsFrame(isQuiet = scope.isQuiet, frame = frames)) continue
            configure()
            if (scope.render(nowSeconds = nanos / 1e9, pixelWidth = size.width, pixelHeight = size.height, scale = density)) frame++
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .clearAndSetSemantics {},
    ) {
        @Suppress("UNUSED_EXPRESSION") frame
        with(scope) { draw() }
    }
}

/** The pearl palette shared by the dot scopes, `AgentActivityOrb` and Catch Up's drift field. */
object DotScopePalette {
    /** Dark-mode cool pearl base and core shared by the dot scopes and `AgentActivityOrb`. */
    val coolPearlDarkBase = floatArrayOf(0.70f, 0.75f, 0.84f)
    val coolPearlDarkCore = floatArrayOf(0.92f, 0.95f, 1.0f)

    data class Pearl(val you: FloatArray, val youCore: FloatArray, val agent: FloatArray, val agentCore: FloatArray)

    /** The pearl palette: warm for you, cool for the agent; cores are where a trace burns brightest. */
    fun pearlPalette(dark: Boolean): Pearl = if (dark) {
        Pearl(
            floatArrayOf(0.86f, 0.83f, 0.77f, 1f),
            floatArrayOf(0.98f, 0.96f, 0.92f, 1f),
            floatArrayOf(coolPearlDarkBase[0], coolPearlDarkBase[1], coolPearlDarkBase[2], 1f),
            floatArrayOf(coolPearlDarkCore[0], coolPearlDarkCore[1], coolPearlDarkCore[2], 1f),
        )
    } else {
        Pearl(
            floatArrayOf(0.62f, 0.53f, 0.40f, 1f),
            floatArrayOf(0.47f, 0.38f, 0.26f, 1f),
            floatArrayOf(0.40f, 0.47f, 0.58f, 1f),
            floatArrayOf(0.24f, 0.31f, 0.43f, 1f),
        )
    }
}

/**
 * Steps the scope's simulation at a fixed rate and draws the lit grid. A plain object, one per
 * mounted scope. Configuration and publication are main thread only; simulation and half-float
 * packing run on Default. The mutex covers both, so frames never advance the simulation or reuse
 * its buffer concurrently.
 */
class DotScopeRenderer private constructor() {
    private var simulation = DotScopeSimulation()
    private var lastFrame: Double? = null
    private val frameMutex = Mutex()
    private var clockGeneration = 0L // Main thread; snapshotted with each request.
    private var simulatedGeneration = -1L // Worker, under frameMutex.

    /** Dictation's window eases to new insets as the bar's buttons come and go. */
    private var insets: ScopeVector2? = null

    var mode: DotScopeMode = DotScopeMode.Preview(active = false)
    var isDark = true
    var isStill = false

    /** When a voice was last above [QUIET_LEVEL]. Worker, under frameMutex. */
    private var lastHeard = Double.NEGATIVE_INFINITY

    /** Nothing on screen moves faster than a slow breath: half the frame rate draws the same picture. */
    @Volatile
    var isQuiet = false
        private set

    private val raster = DotScopeRaster()

    fun close() {
        raster.close()
    }
    private var grid: DotScopeGrid? = null
    private var field = FloatArray(0)

    /** After a pause the scope resumes where it was instead of fast-forwarding. */
    fun resetClock() {
        clockGeneration++
    }

    private fun prepareGrid(pixelWidth: Int, pixelHeight: Int, scale: Float): DotScopeGrid? {
        if (pixelWidth < 1 || pixelHeight < 1 || scale <= 0f) return null
        val next = DotScopeGrid(pixelWidth = pixelWidth, pixelHeight = pixelHeight, scale = scale, requestedPitch = 3.5f)
        if (next != grid) {
            grid = next
            field = FloatArray(max(1, next.count) * 4)
            raster.prepare(next)
        }
        return grid
    }

    /** One awaited request per display frame, not a queue of work that can outlive its view. */
    suspend fun render(nowSeconds: Double, pixelWidth: Int, pixelHeight: Int, scale: Float): Boolean {
        // Snapshot this surface before waiting for the previous frame. No worker reads Compose
        // state, a level source, or mutable main-thread configuration.
        val mode = mode
        val isStill = isStill
        val isDark = isDark
        val generation = clockGeneration
        val snapshot = if (isStill) VoiceAudioLevels.Snapshot(0f, 0f) else when (mode) {
            is DotScopeMode.Dictation -> mode.level.level().let { VoiceAudioLevels.Snapshot(it, it * 0.85f) }
            is DotScopeMode.Preview -> (if (mode.active) previewLevels(nowSeconds) else 0f to 0f)
                .let { VoiceAudioLevels.Snapshot(it.first, it.second) }
        }
        return frameMutex.withLock {
            if (generation != clockGeneration) return@withLock false
            val grid = prepareGrid(pixelWidth, pixelHeight, scale) ?: return@withLock false
            val state = withContext(Dispatchers.Default) {
                PerfTrace.section("dot_scope_compute") {
                    if (simulatedGeneration != generation) {
                        lastFrame = null
                        simulatedGeneration = generation
                    }
                    simulate(grid, nowSeconds, mode, isStill, snapshot)
                    raster.pack(grid, field)
                    FrameState(simulation.live, simulation.glow, simulation.breath, insets)
                }
            }
            // Cancellation of the owning LaunchedEffect prevents publication on return from
            // withContext. A handoff/reset also rejects an old surface's completed frame.
            if (generation != clockGeneration) return@withLock false
            PerfTrace.section("dot_scope_publish") {
                raster.publish(grid, mode, isDark, state, field)
            }
            true
        }
    }

    fun DrawScope.draw() {
        with(raster) { draw() }
    }

    private fun simulate(
        grid: DotScopeGrid, nowSeconds: Double, mode: DotScopeMode, isStill: Boolean,
        snapshot: VoiceAudioLevels.Snapshot,
    ) {
        val target = when (mode) {
            is DotScopeMode.Preview -> ScopeVector2(DotScopeSimulation.windowInset, DotScopeSimulation.windowInset)
            is DotScopeMode.Dictation -> ScopeVector2(mode.leadingInset, mode.trailingInset)
        }
        if (isStill) {
            val connected = when (mode) {
                is DotScopeMode.Preview -> mode.active
                is DotScopeMode.Dictation -> true
            }
            simulation = DotScopeSimulation.still(connected)
            isQuiet = true
            insets = target
            simulation.windowInsets = target
        } else {
            val rawElapsed = (nowSeconds - (lastFrame ?: nowSeconds)).coerceIn(0.0, 0.1)
            // Real time here.
            val elapsed = rawElapsed
            lastFrame = nowSeconds
            val eased = insets?.let {
                val k = min(1f, elapsed.toFloat() * 12)
                ScopeVector2(it.x + (target.x - it.x) * k, it.y + (target.y - it.y) * k)
            } ?: target
            insets = eased
            simulation.windowInsets = eased
            val loudest: Float
            when (mode) {
                is DotScopeMode.Dictation -> {
                    loudest = snapshot.user
                    simulation.advance(elapsed, you = snapshot.user, agent = snapshot.assistant, connected = true)
                }
                is DotScopeMode.Preview -> {
                    val carrier = if (mode.active) 1f else 0f
                    // Connecting or disconnecting eases the carrier in and out.
                    loudest = if (abs(simulation.live - carrier) > 0.01f) 1f else max(snapshot.user, snapshot.assistant)
                    simulation.advance(elapsed, you = snapshot.user, agent = snapshot.assistant, connected = mode.active)
                }
            }
            if (loudest > QUIET_LEVEL) lastHeard = nowSeconds
            isQuiet = isQuiet(lastHeard = lastHeard, now = nowSeconds)
        }
        simulation.render(grid, field)
    }

    companion object {
        /**
         * Builds the shader once off the main thread, so the first scope (a held mic's bar) appears
         * at once. Android compiles AGSL lazily per program; this warms the process's shader cache,
         * at most once a launch.
         */
        fun prepare() {
            if (prepared) return
            prepared = true
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            Thread { runCatching { RuntimeShader(DotScopeShaders.dotScopeFragment) } }.start()
        }

        @Volatile
        private var prepared = false

        const val QUIET_LEVEL = 0.02f

        /** Long enough for the last syllable's trails to fade. */
        const val QUIET_AFTER = 1.5

        /** Silent long enough that only the slow carrier moves. */
        fun isQuiet(lastHeard: Double, now: Double): Boolean = now - lastHeard > QUIET_AFTER

        /** Whether display frame [frame] draws: all of them, or every other one while quiet. */
        fun drawsFrame(isQuiet: Boolean, frame: Long): Boolean = !isQuiet || frame % 2 == 0L

        fun make(): DotScopeRenderer? = runCatching { DotScopeRenderer() }
            .onFailure { AppLog.voice.error("dot_scope_shader_failed type=${it.javaClass.simpleName}") }
            .getOrNull()

        /**
         * A 6.4 s exchange: you speak, a beat, the agent answers, a beat. Each voice is a
         * syllable-paced envelope, like speech rather than a tone.
         */
        fun previewLevels(time: Double): Pair<Float, Float> {
            val cycle = time % 6.4
            fun turn(start: Double, length: Double, seed: Double): Float {
                val t = cycle - start
                if (t <= 0 || t >= length) return 0f
                val edge = min(1.0, min(t / 0.25, (length - t) / 0.35))
                val syllables = abs(sin(time * 8.3 + seed)) * (0.55 + 0.45 * abs(sin(time * 3.1 + seed * 2)))
                return (edge * (0.18 + 0.62 * syllables)).toFloat()
            }
            return turn(0.2, 2.3, 0.7) to turn(2.9, 3.0, 2.1)
        }
    }
}

/** The frame's live level, each voice's glow, the quiet breath, and the eased dictation insets. */
private data class FrameState(
    val live: Float, val glow: ScopeVector2, val breath: Float, val insets: ScopeVector2?,
)

/**
 * The scope's raster, one per API level: the AGSL pass on 33+, on Android 12 the same maths
 * ported to GLSL ES 3.0 and rendered off-screen by the shared GLES context ([Gl]) — the CPU-lit
 * field uploads as a texture the ported pass samples at the cell centres — and the Canvas
 * approximation ([CanvasDots]) only if GLES is unusable.
 */
private class DotScopeRaster {
    fun close() {
        renderer.close()
    }

    private val renderer: Renderer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Agsl() else Gl()

    fun prepare(grid: DotScopeGrid) = renderer.prepare(grid)

    fun pack(grid: DotScopeGrid, field: FloatArray) = renderer.pack(grid, field)

    fun publish(grid: DotScopeGrid, mode: DotScopeMode, isDark: Boolean, state: FrameState, field: FloatArray) =
        renderer.publish(grid, mode, isDark, state, field)

    fun DrawScope.draw() {
        with(renderer) { draw() }
    }

    private interface Renderer {
        fun prepare(grid: DotScopeGrid)
        fun pack(grid: DotScopeGrid, field: FloatArray)
        fun publish(grid: DotScopeGrid, mode: DotScopeMode, isDark: Boolean, state: FrameState, field: FloatArray)
        fun DrawScope.draw()
        fun close() {}
    }

    /** The AGSL pass, API 33+, unchanged. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class Agsl : Renderer {
        private val shader = RuntimeShader(DotScopeShaders.dotScopeFragment)
        private var bitmap: Bitmap? = null
        private var publishedBitmap: Bitmap? = null
        private var pixels: ByteBuffer? = null
        private var brush: ShaderBrush? = null

        override fun prepare(grid: DotScopeGrid) {
            val texture = createBitmap(grid.columns, grid.rows, Bitmap.Config.RGBA_F16, hasAlpha = true, colorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
            bitmap = texture
            pixels = ByteBuffer.allocateDirect(grid.columns * grid.rows * 8).order(ByteOrder.nativeOrder())
        }

        // The texture holds raw half-float bits (`Half.toHalf` → `putShort`), read by the shader as data.
        @SuppressLint("HalfFloat")
        override fun pack(grid: DotScopeGrid, field: FloatArray) {
            val buffer = pixels ?: return
            buffer.clear()
            val one = Half.toHalf(1f)
            for (n in 0 until grid.count) {
                val i = n * 4
                buffer.putShort(Half.toHalf(field[i]))
                buffer.putShort(Half.toHalf(field[i + 1]))
                buffer.putShort(Half.toHalf(field[i + 2]))
                buffer.putShort(one)
            }
            buffer.rewind()
        }

        override fun publish(grid: DotScopeGrid, mode: DotScopeMode, isDark: Boolean, state: FrameState, field: FloatArray) {
            pixels?.let { bitmap?.copyPixelsFromBuffer(it) }
            if (bitmap !== publishedBitmap) {
                val input = BitmapShader(checkNotNull(bitmap), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    .apply { filterMode = BitmapShader.FILTER_MODE_NEAREST }
                shader.setInputBuffer("field", input)
                publishedBitmap = bitmap
            }
            setUniforms(grid, mode, isDark, state)
            if (brush == null) brush = ShaderBrush(shader)
        }

        private fun setUniforms(grid: DotScopeGrid, mode: DotScopeMode, isDark: Boolean, state: FrameState) {
            val palette = DotScopePalette.pearlPalette(isDark)
            val isDictation: Boolean
            val window: FloatArray
            val bar = floatArrayOf(0f, grid.width, 0f, 0f)
            val shape = floatArrayOf(grid.height / 2, 0f, 0f, 0f)
            when (mode) {
                is DotScopeMode.Preview -> {
                    isDictation = false
                    window = floatArrayOf(DotScopeSimulation.windowInset, DotScopeSimulation.windowFade, 0f, 0f)
                }
                is DotScopeMode.Dictation -> {
                    isDictation = true
                    val eased = state.insets ?: ScopeVector2(mode.leadingInset, mode.trailingInset)
                    window = floatArrayOf(eased.x, 24f, eased.y, 0f)
                }
            }
            val glow = state.glow
            shader.setFloatUniform("grid", grid.pitch, grid.columns.toFloat(), grid.rows.toFloat(), grid.scale)
            shader.setFloatUniform("origin", grid.originX, grid.originY, grid.width, grid.height)
            shader.setFloatUniform("state", state.live, if (isDark) 1f else 0f, 1f, 1f)
            shader.setFloatUniform("window", window)
            shader.setFloatUniform("glow", glow.x, glow.y, state.breath, state.live)
            shader.setFloatUniform("you", palette.you)
            shader.setFloatUniform("youCore", palette.youCore)
            shader.setFloatUniform("agent", if (isDictation) palette.you else palette.agent)
            shader.setFloatUniform("agentCore", if (isDictation) palette.youCore else palette.agentCore)
            shader.setFloatUniform("bar", bar)
            shader.setFloatUniform("shape", shape)
        }

        override fun DrawScope.draw() {
            val brush = brush ?: return
            drawRect(brush)
        }
    }

    /**
     * Android 12: the AGSL pass above ported to GLSL ES 3.0 (docs/android-12-support.md §10.2),
     * rendered off-screen by the shared GLES context and composited as a bitmap. The CPU-lit
     * field uploads as a texture the ported pass samples at the cell centres (`texelFetch`,
     * the same texel `field.eval(cell + 0.5)` reads). The uniforms keep the AGSL pass's names.
     */
    private class Gl : Renderer {
        private val pass = GlPass(DotScopeShaders.dotScopeFragmentGlsl)

        override fun close() {
            pass.close()
        }
        // Warmed with every publish, so if the GL pass fails the Canvas approximation takes
        // over on the same frame's state, with no blank frames.
        private val canvas = CanvasDots()
        private var fieldBitmap: Bitmap? = null
        private var fieldPixels: ByteBuffer? = null
        private var fieldTexture = 0
        private var published: DotScopeGrid? = null

        // The frame's uniform values, computed at publish (main thread) and wired at draw.
        private val grid4 = FloatArray(4)
        private val origin4 = FloatArray(4)
        private val state4 = FloatArray(4)
        private val window4 = FloatArray(4)
        private val glow4 = FloatArray(4)
        private val you4 = FloatArray(4)
        private val youCore4 = FloatArray(4)
        private val agent4 = FloatArray(4)
        private val agentCore4 = FloatArray(4)
        private val bar4 = FloatArray(4)
        private val shape4 = FloatArray(4)

        override fun prepare(grid: DotScopeGrid) {
            fieldBitmap = createBitmap(grid.columns, grid.rows, Bitmap.Config.RGBA_F16, hasAlpha = true, colorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
            fieldPixels = ByteBuffer.allocateDirect(grid.columns * grid.rows * 8).order(ByteOrder.nativeOrder())
            canvas.prepare(grid)
        }

        // The texture holds raw half-float bits (`Half.toHalf` → `putShort`), read by the shader as data.
        @SuppressLint("HalfFloat")
        override fun pack(grid: DotScopeGrid, field: FloatArray) {
            val buffer = fieldPixels ?: return
            buffer.clear()
            val one = Half.toHalf(1f)
            for (n in 0 until grid.count) {
                val i = n * 4
                buffer.putShort(Half.toHalf(field[i]))
                buffer.putShort(Half.toHalf(field[i + 1]))
                buffer.putShort(Half.toHalf(field[i + 2]))
                buffer.putShort(one)
            }
            buffer.rewind()
        }

        override fun publish(grid: DotScopeGrid, mode: DotScopeMode, isDark: Boolean, state: FrameState, field: FloatArray) {
            canvas.publish(grid, mode, isDark, state, field)
            val bitmap = fieldBitmap ?: return
            fieldPixels?.let { bitmap.copyPixelsFromBuffer(it) }
            fieldTexture = pass.uploadTexture(bitmap, fieldTexture)
            published = grid
            grid4[0] = grid.pitch; grid4[1] = grid.columns.toFloat(); grid4[2] = grid.rows.toFloat(); grid4[3] = grid.scale
            origin4[0] = grid.originX; origin4[1] = grid.originY; origin4[2] = grid.width; origin4[3] = grid.height
            state4[0] = state.live; state4[1] = if (isDark) 1f else 0f; state4[2] = 1f; state4[3] = 1f
            when (mode) {
                is DotScopeMode.Preview -> {
                    window4[0] = DotScopeSimulation.windowInset
                    window4[1] = DotScopeSimulation.windowFade
                    window4[2] = 0f
                    window4[3] = 0f
                }
                is DotScopeMode.Dictation -> {
                    val eased = state.insets ?: ScopeVector2(mode.leadingInset, mode.trailingInset)
                    window4[0] = eased.x; window4[1] = 24f; window4[2] = eased.y; window4[3] = 0f
                }
            }
            glow4[0] = state.glow.x; glow4[1] = state.glow.y; glow4[2] = state.breath; glow4[3] = state.live
            val palette = DotScopePalette.pearlPalette(isDark)
            val isDictation = mode is DotScopeMode.Dictation
            palette.you.copyInto(you4)
            palette.youCore.copyInto(youCore4)
            (if (isDictation) palette.you else palette.agent).copyInto(agent4)
            (if (isDictation) palette.youCore else palette.agentCore).copyInto(agentCore4)
            bar4[0] = 0f; bar4[1] = grid.width; bar4[2] = 0f; bar4[3] = 0f
            shape4[0] = grid.height / 2; shape4[1] = 0f; shape4[2] = 0f; shape4[3] = 0f
        }

        override fun DrawScope.draw() {
            if (published == null || fieldTexture == 0) {
                with(canvas) { draw() }
                return
            }
            val width = ceil(size.width).toInt()
            val height = ceil(size.height).toInt()
            val frame = pass.renderPersisted(width, height) { gl ->
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTexture)
                GLES30.glUniform1i(gl.location("field"), 0)
                GLES30.glUniform4f(gl.location("grid"), grid4[0], grid4[1], grid4[2], grid4[3])
                GLES30.glUniform4f(gl.location("origin"), origin4[0], origin4[1], origin4[2], origin4[3])
                GLES30.glUniform4f(gl.location("state"), state4[0], state4[1], state4[2], state4[3])
                GLES30.glUniform4f(gl.location("window"), window4[0], window4[1], window4[2], window4[3])
                GLES30.glUniform4f(gl.location("glow"), glow4[0], glow4[1], glow4[2], glow4[3])
                GLES30.glUniform4f(gl.location("you"), you4[0], you4[1], you4[2], you4[3])
                GLES30.glUniform4f(gl.location("youCore"), youCore4[0], youCore4[1], youCore4[2], youCore4[3])
                GLES30.glUniform4f(gl.location("agent"), agent4[0], agent4[1], agent4[2], agent4[3])
                GLES30.glUniform4f(gl.location("agentCore"), agentCore4[0], agentCore4[1], agentCore4[2], agentCore4[3])
                GLES30.glUniform4f(gl.location("bar"), bar4[0], bar4[1], bar4[2], bar4[3])
                GLES30.glUniform4f(gl.location("shape"), shape4[0], shape4[1], shape4[2], shape4[3])
                GLES30.glUniform2f(gl.location("uSize"), width.toFloat(), height.toFloat())
            }
            if (frame == null) {
                with(canvas) { draw() }
            } else {
                // The readback's rows run bottom-up; the transform lands them upright.
                scale(1f, -1f, pivot = Offset(width / 2f, height / 2f)) { drawImage(frame.asImageBitmap()) }
            }
        }
    }

    /**
     * API < 33: the CPU-lit field as circles in the pearl palette — a dot's radius and alpha follow
     * its two voices' combined light, its colour their mix, over a faint unlit grid — plus the two
     * glows behind the buttons as gradient ellipses. Main thread only; the published field is a
     * snapshot, since the worker reuses the array for its next frame.
     */
    private class CanvasDots : Renderer {
        private var grid: DotScopeGrid? = null
        private var dark = true
        private var live = 0f
        private var glowYou = 0f
        private var glowAgent = 0f
        private var breath = 0f
        private var winLeft = DotScopeSimulation.windowInset
        private var winRight = DotScopeSimulation.windowInset
        private var winFade = DotScopeSimulation.windowFade
        private var field = FloatArray(0)
        private var palette: DotScopePalette.Pearl? = null

        // Cached: the glows' gradients rebuild only when the palette flips (never per frame);
        // their level rides the paint's alpha, and the capsule clip rebuilds with the grid.
        private var glows = Glows(Paint(), Paint())
        private var glowDark: Boolean? = null
        private val capsule = Path()
        private var capsuleGrid: DotScopeGrid? = null

        override fun prepare(grid: DotScopeGrid) {}

        override fun pack(grid: DotScopeGrid, field: FloatArray) {}

        override fun publish(grid: DotScopeGrid, mode: DotScopeMode, isDark: Boolean, state: FrameState, field: FloatArray) {
            this.grid = grid
            this.dark = isDark
            palette = DotScopePalette.pearlPalette(isDark)
            this.live = state.live
            this.glowYou = state.glow.x
            this.glowAgent = state.glow.y
            this.breath = state.breath
            when (mode) {
                is DotScopeMode.Dictation -> {
                    val eased = state.insets ?: ScopeVector2(mode.leadingInset, mode.trailingInset)
                    winLeft = eased.x
                    winRight = eased.y
                    winFade = 24f
                }
                is DotScopeMode.Preview -> {
                    winLeft = DotScopeSimulation.windowInset
                    winRight = DotScopeSimulation.windowInset
                    winFade = DotScopeSimulation.windowFade
                }
            }
            this.field = field.copyOf()
        }

        override fun DrawScope.draw() {
            val grid = grid ?: return
            clipPath(capsulePath(grid)) {
                drawGlows(grid)
                drawDots(grid)
            }
        }

        private fun DrawScope.capsulePath(grid: DotScopeGrid): Path {
            if (capsuleGrid != grid) {
                capsule.reset()
                capsule.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.height / 2)))
                capsuleGrid = grid
            }
            return capsule
        }

        private fun DrawScope.drawGlows(grid: DotScopeGrid) {
            val peak = (if (dark) 0.3f else 0.19f) * 0.55f
            val rest = 0.05f * (0.6f + 0.4f * breath) * live
            val gYou = glowYou + rest
            val gAgent = glowAgent + rest
            if (gYou <= 1e-4f && gAgent <= 1e-4f) return
            val glows = glowsFor(dark)
            val sc = grid.scale
            val r = grid.height * 0.5f
            val cy = r * sc
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                // Your glow sits `glowCentre` pt from the left end, the agent's from the right; each
                // lobe reaches `glowReachLoud` further in as it swells and spreads `glowHeight` of a
                // half bar height vertically. The ellipse's radii cover two Gaussian sigmas.
                drawLobe(nc, glows.you, gYou, peak, 28f * sc, cy, sc, r)
                drawLobe(nc, glows.agent, gAgent, peak, (grid.width - 28f) * sc, cy, sc, r)
            }
        }

        private fun drawLobe(
            nc: android.graphics.Canvas, paint: Paint, g: Float, peak: Float,
            cx: Float, cy: Float, sc: Float, r: Float,
        ) {
            val a = min(1f, g) * peak
            if (a <= 1e-4f) return
            paint.alpha = (a * 255f).toInt()
            nc.save()
            nc.translate(cx, cy)
            nc.scale((34f + 36f * g) * sc * 2f, 0.42f * (0.85f + 0.15f * g) * r * sc * 2f)
            nc.drawCircle(0f, 0f, 1f, paint)
            nc.restore()
        }

        private class Glows(val you: Paint, val agent: Paint)

        private fun glowsFor(dark: Boolean): Glows {
            if (glowDark == dark) return glows
            val palette = DotScopePalette.pearlPalette(dark)
            val you = gradientPaint(glowColour(palette.you, palette.youCore, dark))
            val agent = gradientPaint(glowColour(palette.agent, palette.agentCore, dark))
            glows = Glows(you, agent)
            glowDark = dark
            return glows
        }

        /** `glowAt`'s colour: both pearls mixed half toward their core, saturated, lifted pale in light. */
        private fun glowColour(pearl: FloatArray, core: FloatArray, dark: Boolean): Int {
            val mixed = mix(pearl, core, 0.5f)
            val r = if (dark) {
                saturated(mixed, 1.5f)
            } else {
                mix(saturated(mixed, 1.5f * 1.3f), floatArrayOf(1f, 1f, 1f), 0.15f)
            }
            return argb(r, 255)
        }

        private fun gradientPaint(colour: Int): Paint = Paint().apply {
            shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(colour, colour, colour, 0x00000000),
                floatArrayOf(0f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        private fun DrawScope.drawDots(grid: DotScopeGrid) {
            val f = field
            if (f.isEmpty()) return
            val sc = grid.scale
            val pitch = grid.pitch
            val w = grid.width
            val halfH = grid.height * 0.5f
            val dark = this@CanvasDots.dark
            val palette = checkNotNull(this@CanvasDots.palette)
            val you = palette.you
            val youCore = palette.youCore
            val agent = palette.agent
            val agentCore = palette.agentCore
            val a0Base = if (dark) 0.06f else 0.075f
            val top = if (dark) 0.96f else 1.0f
            val litExp = if (dark) 0.9f else 0.75f
            val live = this@CanvasDots.live
            val winLeft = this@CanvasDots.winLeft
            val winRight = this@CanvasDots.winRight
            val winFade = max(this@CanvasDots.winFade, 1f)
            for (j in 0 until grid.rows) {
                val row = j * grid.columns
                for (i in 0 until grid.columns) {
                    val idx = row + i
                    val iu = f[idx * 4].coerceIn(0f, 1f)
                    val ia = f[idx * 4 + 1].coerceIn(0f, 1f)
                    val fresh = f[idx * 4 + 2]
                    val ctrX = grid.originX + (i + 0.5f) * pitch
                    val ctrY = grid.originY + (j + 0.5f) * pitch
                    val x = ctrX / sc
                    val y = ctrY / sc
                    // Fade out toward the capsule's rim, so the glass stays the only edge.
                    val capEnd = max(abs(x - w * 0.5f) - (w * 0.5f - halfH), 0f)
                    val cap = halfH - sqrt(capEnd * capEnd + (y - halfH) * (y - halfH))
                    val mask = smooth(2f, 6.5f, cap)
                    if (mask <= 0f) continue
                    // The window between the leading end and the trailing controls.
                    val t = min(
                        ((x - winLeft) / winFade).coerceIn(0f, 1f),
                        ((w - x - winRight) / winFade).coerceIn(0f, 1f),
                    )
                    val win = t * t * (3f - 2f * t)
                    if (win <= 0f) continue
                    val yn = abs(y - halfH) / halfH
                    val p = 1f - (1f - iu) * (1f - ia)
                    val tot = iu + ia
                    val ratio = iu / max(tot, 1e-4f)
                    val a0 = a0Base * (1f - 0.7f * smooth(0.35f, 0.95f, yn)) * (0.5f + 0.5f * live)
                    val alpha = (a0 + (top - a0) * p.pow(litExp * (1.4f + (1f - 1.4f) * fresh))) * mask * win
                    if (alpha <= 0.004f) continue
                    val radius = (0.25f + 0.17f * p.pow(0.6f)) * pitch
                    val core = (p * p * 1.1f).coerceIn(0f, 0.9f) * (0.3f + (1f - 0.3f) * fresh)
                    // Unlit dots lean to their side's colour; lit ones follow the voices' mix.
                    val side = 1f - smooth(0.42f, 0.58f, x / w)
                    val own = side + (ratio - side) * smooth(0.02f, 0.12f, tot)
                    val youR = you[0] + (youCore[0] - you[0]) * core
                    val youG = you[1] + (youCore[1] - you[1]) * core
                    val youB = you[2] + (youCore[2] - you[2]) * core
                    val agentR = agent[0] + (agentCore[0] - agent[0]) * core
                    val agentG = agent[1] + (agentCore[1] - agent[1]) * core
                    val agentB = agent[2] + (agentCore[2] - agent[2]) * core
                    drawCircle(
                        Color(
                            (agentR + (youR - agentR) * own).coerceIn(0f, 1f),
                            (agentG + (youG - agentG) * own).coerceIn(0f, 1f),
                            (agentB + (youB - agentB) * own).coerceIn(0f, 1f),
                            alpha.coerceIn(0f, 1f),
                        ),
                        radius,
                        Offset(ctrX, ctrY),
                    )
                }
            }
        }

        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun mix(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(
            a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t,
        )

        /** `saturate3` (build lightness, not a grey shade), on the rgb channels. */
        private fun saturated(c: FloatArray, k: Float): FloatArray {
            val l = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]
            return floatArrayOf(
                (l + (c[0] - l) * k).coerceIn(0f, 1f),
                (l + (c[1] - l) * k).coerceIn(0f, 1f),
                (l + (c[2] - l) * k).coerceIn(0f, 1f),
            )
        }

        private fun argb(c: FloatArray, alpha: Int): Int =
            ((alpha shl 24) or ((c[0] * 255f).toInt() shl 16) or ((c[1] * 255f).toInt() shl 8) or (c[2] * 255f).toInt())
    }
}
