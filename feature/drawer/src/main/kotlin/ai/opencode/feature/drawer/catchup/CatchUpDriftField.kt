package ai.opencode.feature.drawer.catchup

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.gl.GlSurfacePass
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.opengl.GLES30
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The calm dot-matrix Drift field behind the Catch Up stack that blooms as the last card goes, an
 * AGSL [RuntimeShader]. Reduce Motion holds one still frame; the field pauses while the screen
 * isn't resumed. Draw it edge to edge: the stage centre comes from the system-bar and cutout insets
 * plus the header and footer.
 *
 * `RuntimeShader` (API 33) doesn't exist below Android 13, so [CatchUpDriftRenderer] is a seam in
 * the `AgentRevealGlow` shape: on 33+ the AGSL pass is unchanged, below 33 the pass ported to
 * GLSL ES 3.0 (`SHADER_GLSL`, docs/android-12-support.md §10.2) animates through the shared GLES
 * context at the same 30 fps pacing, and only when GLES is unusable the Reduce-Motion still
 * frame is rendered once by Canvas into a cached bitmap.
 */
@Composable
fun CatchUpDriftFieldView(isFinished: Boolean, modifier: Modifier = Modifier) {
    val isStill = rememberReduceMotion() || rememberTouchExplorationEnabled()
    val isDark = OpenCodeTheme.colors.isDark
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isPaused = !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val density = LocalDensity.current
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val safeTop = insets.getTop(density)
    val safeBottom = insets.getBottom(density)

    val renderer = remember { CatchUpDriftRenderer() }
    var finishStart by remember { mutableStateOf<Double?>(null) }
    val now = remember { mutableDoubleStateOf(CatchUpDriftRenderer.clock()) }

    LaunchedEffect(isFinished) {
        finishStart = if (isFinished) CatchUpDriftRenderer.clock() else null
    }
    LaunchedEffect(isStill, isPaused) {
        if (isStill || isPaused) return@LaunchedEffect
        // A slow drift: 30 frames a second look the same as 60, so the field redraws only every
        // other frame or so (a little early, so 60 Hz lands on every second).
        val frameSeconds = 1.0 / CatchUpDriftRenderer.FRAMES_PER_SECOND
        while (true) {
            withFrameNanos { frameNanos ->
                val time = frameNanos / 1e9
                if (time - now.doubleValue >= frameSeconds - FRAME_SLACK_SECONDS) now.doubleValue = time
            }
        }
    }

    Canvas(modifier.clearAndSetSemantics {}) {
        with(renderer) {
            draw(
                widthPx = size.width,
                heightPx = size.height,
                scale = density.density,
                now = now.doubleValue,
                finishStart = finishStart,
                isFinished = isFinished,
                isStill = isStill,
                isDark = isDark,
                safeTopPx = safeTop.toFloat(),
                safeBottomPx = safeBottom.toFloat(),
            )
        }
    }
}

private const val FRAME_SLACK_SECONDS = 0.002


@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        enabled = manager.isTouchExplorationEnabled
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
/** Owns the field's renderer and its uniforms. */
internal class CatchUpDriftRenderer {
    private val renderer: FieldRenderer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslField() else GlField()
    private val startTime = clock()

    internal fun DrawScope.draw(
        widthPx: Float,
        heightPx: Float,
        scale: Float,
        now: Double,
        finishStart: Double?,
        isFinished: Boolean,
        isStill: Boolean,
        isDark: Boolean,
        safeTopPx: Float,
        safeBottomPx: Float,
    ) {
        val uniforms = frame(
            widthPx, heightPx, scale, now, finishStart, isFinished,
            still = isStill || renderer.isStill, isDark, safeTopPx, safeBottomPx,
        ) ?: return
        with(renderer) { draw(uniforms) }
    }

    private interface FieldRenderer {
        /** True when this renderer holds one still frame, so the look never animates. */
        val isStill: Boolean

        fun DrawScope.draw(frame: Frame)
    }

    /** One frame's values, the shader's uniforms shared by both renderers. */
    private class Frame(
        val pitchPx: Float,
        val cols: Float,
        val rows: Float,
        val scale: Float,
        val widthPx: Float,
        val originXPx: Float,
        val originYPx: Float,
        val heightPx: Float,
        val dark: Boolean,
        val time: Float,
        val level: Float,
        val swell: Float,
        val finishTime: Float,
        val centreXPt: Float,
        val centreYPt: Float,
        val palette: FloatArray,
    )

    private fun frame(
        widthPx: Float,
        heightPx: Float,
        scale: Float,
        now: Double,
        finishStart: Double?,
        isFinished: Boolean,
        still: Boolean,
        isDark: Boolean,
        safeTopPx: Float,
        safeBottomPx: Float,
    ): Frame? {
        if (widthPx < 1 || heightPx < 1 || scale <= 0) return null
        val widthPt = widthPx / scale
        val heightPt = heightPx / scale
        val cols = floor(widthPt / PITCH_PT)
        val rows = floor(heightPt / PITCH_PT)
        val originXPt = (widthPt - cols * PITCH_PT) * 0.5f
        val originYPt = (heightPt - rows * PITCH_PT) * 0.5f

        val time = if (still) STILL_TIME else max(0.0, now - startTime).toFloat()
        val level: Float
        val swell: Float
        val finishTime: Float
        if (isFinished) {
            if (still) {
                level = FINISH_LEVEL
                swell = 0.65f
                finishTime = 0.6f
            } else {
                val tf = max(0.0, now - (finishStart ?: now)).toFloat()
                val k = min(1f, tf / 0.65f)
                val smooth = k * k * (3 - 2 * k)
                level = STACK_LEVEL + (FINISH_LEVEL - STACK_LEVEL) * smooth
                swell = min(1f, tf / 0.55f)
                finishTime = tf
            }
        } else {
            level = STACK_LEVEL
            swell = 0f
            finishTime = -1f
        }

        val stageTop = safeTopPx / scale + CatchUpMetrics.headerHeight.value
        val stageBottom = heightPt - (safeBottomPx / scale + CatchUpMetrics.footerHeight.value)
        val centreX = widthPt * 0.5f
        val centreY = (stageTop + stageBottom) * 0.5f
        return Frame(
            pitchPx = PITCH_PT * scale,
            cols = cols,
            rows = rows,
            scale = scale,
            widthPx = widthPx,
            originXPx = originXPt * scale,
            originYPx = originYPt * scale,
            heightPx = heightPx,
            dark = isDark,
            time = time,
            level = level,
            swell = swell,
            finishTime = finishTime,
            centreXPt = centreX,
            centreYPt = centreY,
            palette = if (isDark) DARK else LIGHT,
        )
    }

    /** The AGSL pass, API 33+, unchanged: set the uniforms, then one rect. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class AgslField : FieldRenderer {
        override val isStill = false

        private val shader = RuntimeShader(SHADER)
        private val brush = ShaderBrush(shader)

        override fun DrawScope.draw(frame: Frame) {
            shader.setFloatUniform("grid", frame.pitchPx, frame.cols, frame.rows, frame.scale)
            shader.setFloatUniform("origin", frame.originXPx, frame.originYPx, frame.heightPx, if (frame.dark) 1f else 0f)
            shader.setFloatUniform("look", frame.time, frame.level, frame.swell, frame.finishTime)
            shader.setFloatUniform("centre", frame.centreXPt, frame.centreYPt, 82f, 42f)
            shader.setFloatUniform("you", frame.palette[0], frame.palette[1], frame.palette[2], 1f)
            shader.setFloatUniform("youCore", frame.palette[3], frame.palette[4], frame.palette[5], 1f)
            shader.setFloatUniform("agent", frame.palette[6], frame.palette[7], frame.palette[8], 1f)
            shader.setFloatUniform("agentCore", frame.palette[9], frame.palette[10], frame.palette[11], 1f)
            drawRect(brush)
        }
    }

    /**
     * Below 33: the pass ported to GLSL ES 3.0 (docs/android-12-support.md §10.2), animated on
     * the shared GLES context at the same 30 fps pacing — the [Frame] feeds the uniforms exactly
     * as the AGSL pass reads it, Reduce Motion's still values included. The rendered bitmap
     * composites where the AGSL brush drew. Only if GLES is unusable there does the field fall
     * back to [StillField].
     */
    private class GlField : FieldRenderer {
        override val isStill = false

        private val pass = GlSurfacePass(SHADER_GLSL)
        private val still by lazy { StillField() }

        override fun DrawScope.draw(frame: Frame) {
            val width = ceil(frame.widthPx).toInt().coerceAtLeast(1)
            val height = ceil(frame.heightPx).toInt().coerceAtLeast(1)
            val image = pass.render(width, height) { gl ->
                GLES30.glUniform4f(gl.location("grid"), frame.pitchPx, frame.cols, frame.rows, frame.scale)
                GLES30.glUniform4f(gl.location("origin"), frame.originXPx, frame.originYPx, frame.heightPx, if (frame.dark) 1f else 0f)
                GLES30.glUniform4f(gl.location("look"), frame.time, frame.level, frame.swell, frame.finishTime)
                GLES30.glUniform4f(gl.location("centre"), frame.centreXPt, frame.centreYPt, 82f, 42f)
                GLES30.glUniform4f(gl.location("you"), frame.palette[0], frame.palette[1], frame.palette[2], 1f)
                GLES30.glUniform4f(gl.location("youCore"), frame.palette[3], frame.palette[4], frame.palette[5], 1f)
                GLES30.glUniform4f(gl.location("agent"), frame.palette[6], frame.palette[7], frame.palette[8], 1f)
                GLES30.glUniform4f(gl.location("agentCore"), frame.palette[9], frame.palette[10], frame.palette[11], 1f)
                GLES30.glUniform2f(gl.location("uSize"), width.toFloat(), height.toFloat())
            }
            if (image == null) {
                with(still) { draw(frame) }
            } else {
                drawImage(image)
            }
        }
    }

    /**
     * The GLES-unusable fallback: the Reduce-Motion still frame, rendered once by Canvas into a
     * cached bitmap — the same per-cell field as the shader (drift noise, pulse aura, title
     * clearing) at the still
     * values, the per-dot halos not drawn. Re-rendered only when the view's size, scale, palette
     * or finish state changes; every frame after draws the bitmap. Main thread only.
     */
    private class StillField : FieldRenderer {
        override val isStill = true

        private var bitmap: ImageBitmap? = null
        private var key: Key? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** Everything the still frame depends on; the insets ride in through the stage centre. */
        private class Key(
            val width: Int,
            val height: Int,
            val scale: Float,
            val dark: Boolean,
            val finished: Boolean,
            val centreYPt: Float,
        )

        override fun DrawScope.draw(frame: Frame) {
            val frameKey = Key(
                width = frame.widthPx.roundToInt(),
                height = frame.heightPx.roundToInt(),
                scale = frame.scale,
                dark = frame.dark,
                finished = frame.finishTime >= 0f,
                centreYPt = frame.centreYPt,
            )
            if (frameKey != key) {
                bitmap = render(frame).asImageBitmap()
                key = frameKey
            }
            drawImage(bitmap!!)
        }

        private fun render(frame: Frame): Bitmap {
            val width = frame.widthPx.roundToInt().coerceAtLeast(1)
            val height = frame.heightPx.roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val pitch = frame.pitchPx
            val t = frame.time
            val breath = 0.5f + 0.5f * sin(6.2831853f * t / 3.8f)
            val clearStrength = if (frame.finishTime < 0f) 0f else (frame.finishTime / 0.3f).coerceIn(0f, 1f)
            val a0 = if (frame.dark) 0.022f else 0.032f
            val top = if (frame.dark) 0.36f else 0.40f
            val alphaPow = if (frame.dark) 1.05f else 0.95f
            val palette = frame.palette
            for (row in 0 until frame.rows.toInt()) {
                val y = frame.originYPx + (row + 0.5f) * pitch
                for (column in 0 until frame.cols.toInt()) {
                    val x = frame.originXPx + (column + 0.5f) * pitch
                    val ptx = x / frame.scale - frame.centreXPt
                    val pty = y / frame.scale - frame.centreYPt
                    val dist = sqrt(ptx * ptx + pty * pty)

                    val qx = ptx / 105f + 3.7f
                    val qy = pty / 105f + 1.9f
                    val w1 = driftFbm(qx, qy + t * 0.04f)
                    val w2 = driftFbm(qx + 5.2f, qy - t * 0.035f)
                    val n = driftFbm(qx + 1.4f * w1 + t * 0.045f, qy + 1.4f * w2 - t * 0.025f)
                    var intensity = 0.05f + 0.28f * max(n, 0f).pow(2f) * (0.85f + 0.15f * breath)

                    // A calm, gently pulsating radial aura on the All Caught Up screen.
                    val pulse = frame.swell * (0.55f + 0.45f * breath) * exp(-dist * dist / (2f * 195f * 195f))
                    intensity += 0.24f * pulse

                    val e = sqrt(
                        (ptx / (82f + 32f)) * (ptx / (82f + 32f)) + (pty / (42f + 24f)) * (pty / (42f + 24f)),
                    )
                    val clearing = mix(1f, mix(0.55f, 1f, smoothstep(0.45f, 1.15f, e)), clearStrength)
                    val P = (intensity * frame.level * clearing).coerceIn(0f, 1f)
                    val ratio = mix(w1, 0.68f, (pulse * 0.8f).coerceIn(0f, 0.55f)).coerceIn(0f, 1f)

                    val alpha = a0 + (top - a0) * max(P, 1e-5f).pow(alphaPow)
                    val rad = (0.24f + 0.13f * max(P, 1e-5f).pow(0.65f)) * pitch
                    val core = (P * P * 1.1f).coerceIn(0f, 0.9f) * 0.5f
                    val youR = palette[0] + (palette[3] - palette[0]) * core
                    val youG = palette[1] + (palette[4] - palette[1]) * core
                    val youB = palette[2] + (palette[5] - palette[2]) * core
                    val agentR = palette[6] + (palette[9] - palette[6]) * core
                    val agentG = palette[7] + (palette[10] - palette[7]) * core
                    val agentB = palette[8] + (palette[11] - palette[8]) * core
                    paint.color = Color.argb(
                        (alpha * 255f).roundToInt().coerceIn(0, 255),
                        ((agentR + (youR - agentR) * ratio) * 255f).roundToInt().coerceIn(0, 255),
                        ((agentG + (youG - agentG) * ratio) * 255f).roundToInt().coerceIn(0, 255),
                        ((agentB + (youB - agentB) * ratio) * 255f).roundToInt().coerceIn(0, 255),
                    )
                    canvas.drawCircle(x, y, rad, paint)
                }
            }
            return bitmap
        }

        private fun driftHash(x: Float, y: Float): Float {
            val s = sin(x * 127.1f + y * 311.7f) * 43758.5453f
            return s - floor(s)
        }

        private fun driftNoise(x: Float, y: Float): Float {
            val ix = floor(x)
            val iy = floor(y)
            val fx = x - ix
            val fy = y - iy
            val ux = fx * fx * (3f - 2f * fx)
            val uy = fy * fy * (3f - 2f * fy)
            val a = driftHash(ix, iy)
            val b = driftHash(ix + 1f, iy)
            val c = driftHash(ix, iy + 1f)
            val d = driftHash(ix + 1f, iy + 1f)
            return mix(mix(a, b, ux), mix(c, d, ux), uy)
        }

        private fun driftFbm(x: Float, y: Float): Float =
            0.62f * driftNoise(x, y) + 0.38f * driftNoise(x * 2.03f + 7.1f, y * 2.03f + 7.1f)

        private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun smoothstep(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }

    companion object {
        /**
         * The field drifts over seconds, so it's drawn 30 times a second. Android draws it at the
         * screen's own density: a [RuntimeShader] brush has no cheaper lower-resolution target
         * short of an offscreen hardware renderer.
         */
        const val FRAMES_PER_SECOND = 30
        const val STACK_LEVEL = 0.28f
        const val FINISH_LEVEL = 0.56f
        const val STILL_TIME = 1.3f
        const val PITCH_PT = 3.5f

        /** The frame clock's base (`System.nanoTime`) in seconds. */
        fun clock(): Double = System.nanoTime() / 1e9

        /** Warm for you, cool for the agent (base, core). */
        val DARK = floatArrayOf(
            0.86f, 0.83f, 0.77f, 0.98f, 0.96f, 0.92f,
            0.70f, 0.75f, 0.84f, 0.92f, 0.95f, 1.0f,
        )
        val LIGHT = floatArrayOf(
            0.62f, 0.53f, 0.40f, 0.47f, 0.38f, 0.26f,
            0.40f, 0.47f, 0.58f, 0.24f, 0.31f, 0.43f,
        )

        /** `catchUpDriftFragment` (DotScopeShaders.metal), line for line in AGSL. */
        val SHADER = """
            uniform float4 grid;    // pitch (px), columns, rows, scale (px per pt)
            uniform float4 origin;  // first cell (px), view height (px), dark (0 or 1)
            uniform float4 look;    // time (s), level (0…1), swell (0…1), finish time (s, -1 before)
            uniform float4 centre;  // stage centre (pt.x, pt.y), title half-size (pt.w, pt.h)
            uniform float4 you;
            uniform float4 youCore;
            uniform float4 agent;
            uniform float4 agentCore;

            float driftHash(float2 p) {
              return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
            }

            float driftNoise(float2 p) {
              float2 i = floor(p);
              float2 f = fract(p);
              float2 u = f * f * (3.0 - 2.0 * f);
              float a = driftHash(i);
              float b = driftHash(i + float2(1.0, 0.0));
              float c = driftHash(i + float2(0.0, 1.0));
              float d = driftHash(i + float2(1.0, 1.0));
              return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            float driftFbm(float2 p) {
              return 0.62 * driftNoise(p) + 0.38 * driftNoise(p * 2.03 + 7.1);
            }

            half4 main(float2 position) {
              float pitch = grid.x;
              float cols = grid.y;
              float rows = grid.z;
              float sc = grid.w;
              float2 g = (position - origin.xy) / pitch;
              float2 cell = floor(g);
              if (cell.x < 0.0 || cell.y < 0.0 || cell.x >= cols || cell.y >= rows) return half4(0.0);
              float2 ctr = origin.xy + (cell + 0.5) * pitch;
              float d = length(position - ctr);
              if (d > (look.w < 0.0 ? 0.36 : 0.52) * pitch + 0.5) return half4(0.0);
              bool dark = origin.w > 0.5;

              float2 pt = ctr / sc - centre.xy;
              float dist = length(pt);
              float t = look.x;
              float level = look.y;
              float swell = look.z;
              float tf = look.w;
              float breath = 0.5 + 0.5 * sin(6.2831853 * t / 3.8);

              // Offset from (0, 0) so the stage centre never sits in sin(0) = 0's trough.
              float2 q = pt / 105.0 + float2(3.7, 1.9);
              float2 w = float2(driftFbm(q + float2(0.0, t * 0.04)), driftFbm(q + float2(5.2, -t * 0.035)));
              float n = driftFbm(q + 1.4 * w + float2(t * 0.045, -t * 0.025));
              float I = 0.05 + 0.28 * pow(max(n, 0.0), 2.0) * (0.85 + 0.15 * breath);

              // A calm, gently pulsating radial aura on the All Caught Up screen.
              float pulse = swell * (0.55 + 0.45 * breath) * exp(-dist * dist / (2.0 * 195.0 * 195.0));
              I += 0.24 * pulse;

              float clearStrength = tf < 0.0 ? 0.0 : clamp(tf / 0.3, 0.0, 1.0);
              float e = length(pt / (centre.zw + float2(32.0, 24.0)));
              float clearing = mix(1.0, mix(0.55, 1.0, smoothstep(0.45, 1.15, e)), clearStrength);
              float P = clamp(I * level * clearing, 0.0, 1.0);
              float ratio = clamp(mix(w.x, 0.68, clamp(pulse * 0.8, 0.0, 0.55)), 0.0, 1.0);

              float a0 = dark ? 0.022 : 0.032;
              float top = dark ? 0.36 : 0.40;
              float alpha = a0 + (top - a0) * pow(max(P, 1e-5), dark ? 1.05 : 0.95);
              float rad = (0.24 + 0.13 * pow(max(P, 1e-5), 0.65)) * pitch;
              float cov = clamp((rad - d) + 0.5, 0.0, 1.0);
              float halo = 0.10 * P * P * exp(-max(d - rad, 0.0) / (0.12 * pitch)) * (1.0 - cov);
              alpha = alpha * cov + halo;

              float core = clamp(P * P * 1.1, 0.0, 0.9) * 0.5;
              float3 cy = mix(you.rgb, youCore.rgb, core);
              float3 ca = mix(agent.rgb, agentCore.rgb, core);
              float3 col = clamp(mix(ca, cy, ratio), 0.0, 1.0);
              return half4(half3(col * alpha), half(alpha));
            }
        """.trimIndent()

        /**
         * The pass above ported to GLSL ES 3.0 for Android 12 (docs/android-12-support.md §10.2):
         * identical maths after the mechanical type renames (`float2/3/4` → `vec2/3/4`,
         * `half` → `float`), the entry point reads `gl_FragCoord` with the Y flipped to the AGSL's
         * top-left origin, and the output stays premultiplied.
         */
        val SHADER_GLSL = """
            #version 300 es
            precision highp float;

            uniform vec4 grid;    // pitch (px), columns, rows, scale (px per pt)
            uniform vec4 origin;  // first cell (px), view height (px), dark (0 or 1)
            uniform vec4 look;    // time (s), level (0…1), swell (0…1), finish time (s, -1 before)
            uniform vec4 centre;  // stage centre (pt.x, pt.y), title half-size (pt.w, pt.h)
            uniform vec4 you;
            uniform vec4 youCore;
            uniform vec4 agent;
            uniform vec4 agentCore;
            uniform vec2 uSize;   // render target size in px (for the y-flip)

            out vec4 fragColor;

            float driftHash(vec2 p) {
              return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            float driftNoise(vec2 p) {
              vec2 i = floor(p);
              vec2 f = fract(p);
              vec2 u = f * f * (3.0 - 2.0 * f);
              float a = driftHash(i);
              float b = driftHash(i + vec2(1.0, 0.0));
              float c = driftHash(i + vec2(0.0, 1.0));
              float d = driftHash(i + vec2(1.0, 1.0));
              return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            float driftFbm(vec2 p) {
              return 0.62 * driftNoise(p) + 0.38 * driftNoise(p * 2.03 + 7.1);
            }

            vec4 driftField(vec2 position) {
              float pitch = grid.x;
              float cols = grid.y;
              float rows = grid.z;
              float sc = grid.w;
              vec2 g = (position - origin.xy) / pitch;
              vec2 cell = floor(g);
              if (cell.x < 0.0 || cell.y < 0.0 || cell.x >= cols || cell.y >= rows) return vec4(0.0);
              vec2 ctr = origin.xy + (cell + 0.5) * pitch;
              float d = length(position - ctr);
              if (d > (look.w < 0.0 ? 0.36 : 0.52) * pitch + 0.5) return vec4(0.0);
              bool dark = origin.w > 0.5;

              vec2 pt = ctr / sc - centre.xy;
              float dist = length(pt);
              float t = look.x;
              float level = look.y;
              float swell = look.z;
              float tf = look.w;
              float breath = 0.5 + 0.5 * sin(6.2831853 * t / 3.8);

              // Offset from (0, 0) so the stage centre never sits in sin(0) = 0's trough.
              vec2 q = pt / 105.0 + vec2(3.7, 1.9);
              vec2 w = vec2(driftFbm(q + vec2(0.0, t * 0.04)), driftFbm(q + vec2(5.2, -t * 0.035)));
              float n = driftFbm(q + 1.4 * w + vec2(t * 0.045, -t * 0.025));
              float I = 0.05 + 0.28 * pow(max(n, 0.0), 2.0) * (0.85 + 0.15 * breath);

              // A calm, gently pulsating radial aura on the All Caught Up screen.
              float pulse = swell * (0.55 + 0.45 * breath) * exp(-dist * dist / (2.0 * 195.0 * 195.0));
              I += 0.24 * pulse;

              float clearStrength = tf < 0.0 ? 0.0 : clamp(tf / 0.3, 0.0, 1.0);
              float e = length(pt / (centre.zw + vec2(32.0, 24.0)));
              float clearing = mix(1.0, mix(0.55, 1.0, smoothstep(0.45, 1.15, e)), clearStrength);
              float P = clamp(I * level * clearing, 0.0, 1.0);
              float ratio = clamp(mix(w.x, 0.68, clamp(pulse * 0.8, 0.0, 0.55)), 0.0, 1.0);

              float a0 = dark ? 0.022 : 0.032;
              float top = dark ? 0.36 : 0.40;
              float alpha = a0 + (top - a0) * pow(max(P, 1e-5), dark ? 1.05 : 0.95);
              float rad = (0.24 + 0.13 * pow(max(P, 1e-5), 0.65)) * pitch;
              float cov = clamp((rad - d) + 0.5, 0.0, 1.0);
              float halo = 0.10 * P * P * exp(-max(d - rad, 0.0) / (0.12 * pitch)) * (1.0 - cov);
              alpha = alpha * cov + halo;

              float core = clamp(P * P * 1.1, 0.0, 0.9) * 0.5;
              vec3 cy = mix(you.rgb, youCore.rgb, core);
              vec3 ca = mix(agent.rgb, agentCore.rgb, core);
              vec3 col = clamp(mix(ca, cy, ratio), 0.0, 1.0);
              return vec4(col * alpha, alpha);
            }

            void main() {
              vec2 position = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y); // AGSL y-origin is top-left
              fragColor = driftField(position);
            }
        """.trimIndent()
    }
}
