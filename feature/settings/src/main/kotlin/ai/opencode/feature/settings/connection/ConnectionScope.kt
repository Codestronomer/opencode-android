package ai.opencode.feature.settings.connection

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.gl.GlPass
import ai.opencode.core.ui.gl.GlPixel
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.opengl.GLES30
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The connection card's EKG, an LED dot matrix drawing one beat per health check as a monitor
 * sweep, with an erase gap ahead of the pen and older beats fading. Green while connected, orange
 * while slow or unstable, pearl or ink while reconnecting or switching, and red only while offline.
 *
 * One AGSL [RuntimeShader] rect ([ConnectionScopeShaders]) over the CPU-lit field, ticked at 30
 * fps. It stops drawing off screen and when the app isn't resumed; under reduce motion it's a still
 * trace redrawn only when the status changes. On Android 12 (below API 33) the same pass runs as
 * its GLSL ES 3.0 port ([ConnectionScopeRenderer.GlScope]); only if GLES is unusable there do the
 * lit dots draw from the field on Canvas.
 */
@Composable
fun ConnectionScopeView(monitor: ConnectionMonitor, isHidden: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isPaused = isHidden || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val isDark = OpenCodeTheme.colors.isDark
    val highContrast = OpenCodeTheme.colors.highContrast
    val renderer = remember { ConnectionScopeRenderer() }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPaused, reduceMotion) {
        if (isPaused || reduceMotion) return@LaunchedEffect
        // After a pause the afterglow starts fresh instead of smearing the gap.
        renderer.resetClock()
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // The pen moves under a column per frame at 30 fps; afterglow is per frame.
                if (now - last >= FRAME_NANOS - FRAME_SLACK_NANOS) {
                    last = now
                    frame = now
                }
            }
        }
    }

    Canvas(modifier) {
        // Animated: each tick redraws. Still: only a status change (or a resize) does.
        if (reduceMotion) monitor.display else frame
        renderer.isDark = isDark
        renderer.isStill = reduceMotion
        renderer.highContrast = highContrast
        renderer.draw(this, monitor)
    }
}

private const val FRAME_NANOS = 1_000_000_000L / 30
private const val FRAME_SLACK_NANOS = 4_000_000L

/** The LED grid in device pixels. */
internal data class DotScopeGrid(
    /** Device pixels per dp. */
    val scale: Float,
    /** The box in dp. */
    val width: Float,
    val height: Float,
    /** Device pixels between dot centres. */
    val pitch: Float,
    val columns: Int,
    val rows: Int,
    /** The first dot cell's corner, in device pixels. */
    val originX: Float,
    val originY: Float,
) {
    val count: Int get() = columns * rows

    companion object {
        operator fun invoke(pixelWidth: Int, pixelHeight: Int, scale: Float, requestedPitch: Float = 3.5f): DotScopeGrid {
            val pitch = max(3f, (requestedPitch * scale).roundToInt().toFloat())
            val w = pixelWidth.toFloat()
            val h = pixelHeight.toFloat()
            // Odd, so the centre line sits on a row: a flat trace is one dot high, not two.
            val fit = max(5, floor(h / pitch).toInt() - 1)
            val rows = if (fit % 2 == 0) fit - 1 else fit
            val columns = max(1, floor(w / pitch).toInt())
            return DotScopeGrid(
                scale = scale,
                width = w / scale,
                height = h / scale,
                pitch = pitch,
                columns = columns,
                rows = rows,
                originX = floor((w - columns * pitch) / 2),
                originY = floor((h - rows * pitch) / 2),
            )
        }
    }
}

/** An RGBA colour as the shader takes it. */
internal data class ScopeColor(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    operator fun plus(o: ScopeColor) = ScopeColor(r + o.r, g + o.g, b + o.b, a + o.a)
    operator fun minus(o: ScopeColor) = ScopeColor(r - o.r, g - o.g, b - o.b, a - o.a)
    operator fun times(k: Float) = ScopeColor(r * k, g * k, b * k, a * k)
}

internal data class ScopePalette(val main: ScopeColor, val mainCore: ScopeColor, val alert: ScopeColor, val alertCore: ScopeColor)

/**
 * Rasterises the trace on the CPU each frame and draws the lit grid. A plain object owned by the
 * view (per-frame motion lives here, not in a reducer). [force] pins a renderer for the shader
 * lab's side-by-side golden diff; production callers take the default (null).
 */
internal class ConnectionScopeRenderer(private val force: Mode? = null) {
    enum class Mode { Agsl, Gl }

    companion object {
        /**
         * Where the trace fades in from each side of the box, and over how long (dp): its visible
         * edge meets the card's text column.
         */
        const val windowInset = 7f
        const val windowFade = 10f
        const val cornerRadius = 20f

        /** Light above 1 (the pen's splat reaches ~1.5) packed into a byte channel. */
        const val FIELD_SCALE = 1.6f

        /** Pearl on dark (the call scope's cool pearl), ink on light (the orb's), and red for Offline. */
        fun palette(dark: Boolean, highContrast: Boolean): ScopePalette = when {
            dark && !highContrast -> ScopePalette(
                ScopeColor(0.70f, 0.75f, 0.84f), ScopeColor(0.92f, 0.95f, 1.0f), ScopeColor(1.0f, 0.36f, 0.33f), ScopeColor(1.0f, 0.66f, 0.62f),
            )
            dark -> ScopePalette(
                ScopeColor(0.86f, 0.89f, 0.96f), ScopeColor(1.0f, 1.0f, 1.0f), ScopeColor(1.0f, 0.45f, 0.42f), ScopeColor(1.0f, 0.76f, 0.72f),
            )
            !highContrast -> ScopePalette(
                ScopeColor(0.27f, 0.32f, 0.42f), ScopeColor(0.09f, 0.12f, 0.20f), ScopeColor(0.85f, 0.17f, 0.12f), ScopeColor(0.66f, 0.08f, 0.05f),
            )
            else -> ScopePalette(
                ScopeColor(0.14f, 0.18f, 0.26f), ScopeColor(0.03f, 0.05f, 0.10f), ScopeColor(0.72f, 0.10f, 0.06f), ScopeColor(0.52f, 0.04f, 0.02f),
            )
        }
    }

    var isDark = true
    var isStill = false
    var highContrast = false

    private val raster = ConnectionTraceRaster()
    private val renderer: Renderer = when {
        force == Mode.Gl -> GlScope()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> AgslScope()
        else -> GlScope()
    }
    private var grid: DotScopeGrid? = null
    private var pixels = IntArray(0)
    private var lastFrame: Double? = null
    /** The trace's colour now, easing toward the status's ([ConnectionTraceTint]). */
    private var trace: Pair<ScopeColor, ScopeColor>? = null

    /** After a pause the afterglow starts fresh instead of smearing the gap. */
    fun resetClock() {
        lastFrame = null
        raster.reseed()
    }

    private fun prepareGrid(scope: DrawScope): DotScopeGrid? {
        val width = scope.size.width.toInt()
        val height = scope.size.height.toInt()
        if (width < 1 || height < 1 || scope.density <= 0f) return null
        val next = DotScopeGrid(width, height, scope.density)
        if (next != grid) {
            grid = next
            pixels = IntArray(next.count)
        }
        return grid
    }

    fun draw(scope: DrawScope, monitor: ConnectionMonitor) {
        val grid = prepareGrid(scope) ?: return

        val now = monitor.now()
        val elapsed = min(0.1, now - (lastFrame ?: now))
        lastFrame = now

        val palette = palette(isDark, highContrast)
        val target = ConnectionTraceTint.colors(monitor.health.display.status, isDark, highContrast)
            ?: (palette.main to palette.mainCore)
        val current = trace
        trace = if (current != null && !isStill && elapsed > 0) {
            // The whole trace takes the new status's colour over about a third of a second.
            val k = (1 - exp(-elapsed / 0.12)).toFloat()
            (current.first + (target.first - current.first) * k) to (current.second + (target.second - current.second) * k)
        } else {
            target
        }

        raster.render(monitor.health, grid, now, elapsed, isStill, highContrast, pixels)
        with(renderer) { scope.draw(pixels, grid, palette, trace!!, isDark, highContrast) }
    }

    /** Where the lit field draws: the AGSL pass on 33+, its GLSL port on 12, Canvas if GLES is unusable. */
    private interface Renderer {
        fun DrawScope.draw(field: IntArray, grid: DotScopeGrid, palette: ScopePalette, trace: Pair<ScopeColor, ScopeColor>, dark: Boolean, highContrast: Boolean)
    }

    /** The AGSL pass, API 33+, unchanged: the field as a `BitmapShader` child. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class AgslScope : Renderer {
        private val shader = RuntimeShader(ConnectionScopeShaders.CONNECTION_SCOPE_FRAGMENT)
        private val brush = ShaderBrush(shader)
        private var fieldGrid: DotScopeGrid? = null
        /** Two fields, alternated per frame, so the render thread never reads one being written. */
        private var fields: List<Pair<Bitmap, BitmapShader>> = emptyList()
        private var frameIndex = 0

        override fun DrawScope.draw(field: IntArray, grid: DotScopeGrid, palette: ScopePalette, trace: Pair<ScopeColor, ScopeColor>, dark: Boolean, highContrast: Boolean) {
            if (fieldGrid != grid) {
                fieldGrid = grid
                fields = List(2) {
                    val bitmap = Bitmap.createBitmap(grid.columns, grid.rows, Bitmap.Config.ARGB_8888)
                    bitmap to BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        filterMode = BitmapShader.FILTER_MODE_NEAREST
                    }
                }
                frameIndex = 0
            }
            val (bitmap, fieldShader) = fields[frameIndex]
            frameIndex = (frameIndex + 1) % fields.size

            bitmap.setPixels(field, 0, grid.columns, 0, 0, grid.columns, grid.rows)
            shader.setInputShader("field", fieldShader)

            val (main, mainCore) = trace
            shader.setFloatUniform("grid", grid.pitch, grid.columns.toFloat(), grid.rows.toFloat(), grid.scale)
            shader.setFloatUniform("origin", grid.originX, grid.originY, grid.width, grid.height)
            shader.setFloatUniform("state", if (dark) 1f else 0f, if (highContrast) 1.3f else 1f, FIELD_SCALE, 0f)
            shader.setFloatUniform("window", windowInset, windowFade, windowInset, cornerRadius)
            shader.setFloatUniform("mainTint", main.r, main.g, main.b, main.a)
            shader.setFloatUniform("mainCore", mainCore.r, mainCore.g, mainCore.b, mainCore.a)
            shader.setFloatUniform("alertTint", palette.alert.r, palette.alert.g, palette.alert.b, palette.alert.a)
            shader.setFloatUniform("alertCore", palette.alertCore.r, palette.alertCore.g, palette.alertCore.b, palette.alertCore.a)
            drawRect(brush)
        }
    }

    /**
     * The ported pass on GLES 3.0 (docs/android-12-support.md §10.2): the same lit-dot
     * composition the AGSL pass draws, with the field bitmap uploaded as the child texture
     * each frame. Canvas fallback if GL is unusable. Main thread only.
     */
    private class GlScope : Renderer {
        private val pass = GlPass(ConnectionScopeShaders.CONNECTION_SCOPE_GLSL)
        private val canvas by lazy { CanvasScope() }
        private var fieldBitmap: Bitmap? = null
        private var fieldGrid: DotScopeGrid? = null
        private var texture = 0

        override fun DrawScope.draw(field: IntArray, grid: DotScopeGrid, palette: ScopePalette, trace: Pair<ScopeColor, ScopeColor>, dark: Boolean, highContrast: Boolean) {
            val width = ceil(size.width).toInt()
            val height = ceil(size.height).toInt()
            if (width < 1 || height < 1) return
            val bitmap = bitmapFor(field, grid)
            val uploaded = pass.uploadTexture(bitmap)
            if (uploaded == 0) {
                with(canvas) { draw(field, grid, palette, trace, dark, highContrast) }
                return
            }
            val previous = texture
            texture = uploaded
            val rendered = pass.render(width, height) { gl ->
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, uploaded)
                GLES30.glUniform1i(gl.location("field"), 0)
                GLES30.glUniform4f(gl.location("grid"), grid.pitch, grid.columns.toFloat(), grid.rows.toFloat(), grid.scale)
                GLES30.glUniform4f(gl.location("origin"), grid.originX, grid.originY, grid.width, grid.height)
                GLES30.glUniform4f(gl.location("state"), if (dark) 1f else 0f, if (highContrast) 1.3f else 1f, FIELD_SCALE, 0f)
                GLES30.glUniform4f(gl.location("window"), windowInset, windowFade, windowInset, cornerRadius)
                val (main, mainCore) = trace
                GLES30.glUniform4f(gl.location("mainTint"), main.r, main.g, main.b, main.a)
                GLES30.glUniform4f(gl.location("mainCore"), mainCore.r, mainCore.g, mainCore.b, mainCore.a)
                GLES30.glUniform4f(gl.location("alertTint"), palette.alert.r, palette.alert.g, palette.alert.b, palette.alert.a)
                GLES30.glUniform4f(gl.location("alertCore"), palette.alertCore.r, palette.alertCore.g, palette.alertCore.b, palette.alertCore.a)
                GLES30.glUniform2f(gl.location("uSize"), size.width, size.height)
            }
            val output = if (rendered) pass.bitmap(width, height) else null
            if (output == null) {
                with(canvas) { draw(field, grid, palette, trace, dark, highContrast) }
            } else {
                drawImage(output.asImageBitmap())
            }
            if (previous != 0) GlPixel.run { GLES30.glDeleteTextures(1, intArrayOf(previous), 0) }
        }

        private fun bitmapFor(field: IntArray, grid: DotScopeGrid): Bitmap {
            val bitmap = if (fieldGrid != grid) {
                fieldGrid = grid
                Bitmap.createBitmap(grid.columns, grid.rows, Bitmap.Config.ARGB_8888).also { fieldBitmap = it }
            } else {
                fieldBitmap!!
            }
            bitmap.setPixels(field, 0, grid.columns, 0, 0, grid.columns, grid.rows)
            return bitmap
        }
    }

    /** GLES unusable: the field's lit dots on Canvas, the pass's per-dot curves without its unlit grid, halo and rim fade. Main thread only. */
    private class CanvasScope : Renderer {
        override fun DrawScope.draw(field: IntArray, grid: DotScopeGrid, palette: ScopePalette, trace: Pair<ScopeColor, ScopeColor>, dark: Boolean, highContrast: Boolean) {
            val (mainTint, mainCore, alertTint, alertCore) = palette
            val top = if (dark) 0.96f else 1.0f
            val powExp = if (dark) 0.9f else 0.75f
            for (column in 0 until grid.columns) {
                val x = (grid.originX + (column + 0.5f) * grid.pitch) / grid.scale
                val t = (min(x - windowInset, (grid.width - x) - windowInset) / max(windowFade, 1f)).coerceIn(0f, 1f)
                val win = t * t * (3f - 2f * t)
                if (win <= 0f) continue
                for (row in 0 until grid.rows) {
                    val pixel = field[row * grid.columns + column]
                    val alert = ((pixel ushr 16) and 0xFF) / 255f * FIELD_SCALE
                    val main = ((pixel ushr 8) and 0xFF) / 255f * FIELD_SCALE
                    if (alert <= 0f && main <= 0f) continue
                    val tot = alert + main
                    val p = 1f - (1f - min(alert, 1f)) * (1f - min(main, 1f))
                    val fresh = (pixel and 0xFF) / 255f
                    val alpha = top * max(p, 1e-5f).pow(powExp * mix(1.4f, 1f, fresh))
                    val radius = (0.25f + 0.17f * max(p, 1e-5f).pow(0.6f)) *
                        mix(1f, mix(0.82f, 1f, fresh), smoothstep(0.03f, 0.2f, p)) * grid.pitch
                    val core = (p * p * 1.1f).coerceIn(0f, 0.9f) * mix(0.3f, 1f, fresh)
                    val own = if (tot > 0.02f) alert / tot else 0f
                    val dot = mix(
                        mix(mainTint, mainCore, core),
                        mix(alertTint, alertCore, core),
                        own,
                    )
                    drawCircle(
                        Color(dot.r, dot.g, dot.b, dot.a),
                        (radius - 0.5f).coerceAtLeast(0f),
                        Offset(grid.originX + (column + 0.5f) * grid.pitch, grid.originY + (row + 0.5f) * grid.pitch),
                        alpha * win,
                    )
                }
            }
        }

        private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun mix(a: ScopeColor, b: ScopeColor, t: Float): ScopeColor = a + (b - a) * t

        private fun smoothstep(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/**
 * The monitor sweep on the dot grid (`prototypes/settings-connection/main.js`'s `sweep`): each
 * column is the trace at its moment, the pen at the newest with an erase gap ahead of it, older
 * beats dimmer, and per-dot afterglow.
 */
internal class ConnectionTraceRaster {
    private var grid: DotScopeGrid? = null
    private var light = FloatArray(0)
    private var inst = FloatArray(0)
    private var ys = FloatArray(0)
    private var main = FloatArray(0)
    private var alert = FloatArray(0)
    private var fresh = true
    /** Where Switching's wipe was last frame, so it sweeps every column it passes. */
    private var lastErase = -1.0

    companion object {
        /** Seconds for the phosphor's afterglow. */
        const val afterglow = 0.14

        private fun ease(p: Double): Double = 0.5 - 0.5 * cos(PI * min(1.0, max(0.0, p)))
    }

    fun reseed() {
        fresh = true
    }

    /** Lights [field] (one ARGB pixel per dot: R alert, G main, B fresh, light / [ConnectionScopeRenderer.FIELD_SCALE]). */
    fun render(
        health: ConnectionHealth,
        grid: DotScopeGrid,
        time: Double,
        elapsed: Double,
        still: Boolean,
        highContrast: Boolean,
        field: IntArray,
    ) {
        val t = time
        if (grid != this.grid) {
            this.grid = grid
            light = FloatArray(grid.count * 2)
            inst = FloatArray(grid.count * 2)
            ys = FloatArray(grid.columns)
            main = FloatArray(grid.columns)
            alert = FloatArray(grid.columns)
            fresh = true
        }
        inst.fill(0f)
        val n = grid.columns
        val rows = grid.rows
        val mid = rows * 0.6f
        val amp = rows * 0.42f
        val sweep = ConnectionHealth.sweep
        val span = sweep / n
        val gap = max(3, (n * 0.06f).roundToInt()).toDouble()

        val beats = if (still) {
            ConnectionTrace.stillBeats(health.display, t)
        } else {
            health.beats.filter { it.time > t - sweep * 1.1 - ConnectionTrace.beatLength }
        }
        val showsOffline = health.display.status == ConnectionHealth.Status.offline ||
            health.display.status == ConnectionHealth.Status.signInFailed
        val offline: (Double) -> Boolean = if (still) { _ -> showsOffline } else { x -> health.isOffline(x) }
        val red: (Double) -> Boolean = if (still) { _ -> showsOffline } else { x -> health.isRed(x) }

        var phase = ((t - health.origin(t)) / sweep) % 1.0
        if (phase < 0) phase += 1
        val head = phase * n
        var erase = -1.0
        val eraseStart = health.eraseStart
        if (!still && eraseStart != null && t < health.clearAt) {
            erase = ease((t - eraseStart) / ConnectionHealth.eraseDuration) * n
        }
        val floor = if (highContrast) 0.45 else 0.28

        for (i in 0 until n) {
            val behind = (head - i + n) % n.toDouble()
            val tw = t - behind / n * sweep
            val blank = behind > n - gap || (t >= health.clearAt && tw < health.clearAt) || (erase >= 0 && i < erase)
            ys[i] = mid - amp * (if (blank) 0f else ConnectionTrace.sample(tw, span, beats, offline).toFloat())
            val brightness = if (blank) 0f else (floor + (1 - floor) * exp(-(t - tw) / 2.2)).toFloat()
            val isRed = red(tw)
            main[i] = if (isRed) 0f else brightness
            alert[i] = if (isRed) brightness else 0f
        }
        // The wipe: a bar over every column it crossed since the last frame,
        // left to fade in the afterglow, so it reads as one sweep at any frame rate.
        if (erase >= 0) {
            val from = if (lastErase >= 0 && lastErase <= erase) lastErase else erase - 1
            val lo = max(0, ceil(from).toInt())
            val hi = min(n - 1, erase.toInt())
            if (lo <= hi) {
                for (i in lo..hi) {
                    for (r in (mid - amp * 0.7f).roundToInt()..(mid + amp * 0.3f).roundToInt()) add(i, r, 1, 0.75f)
                }
            }
        }
        lastErase = erase
        stroke(main, 1)
        stroke(alert, 0)
        if (!still && health.link.target == null) {
            val hx = min(n - 1, head.toInt())
            splat(hx.toFloat(), ys[hx], if (alert[hx] > 0) 0 else 1, 1.1f)
        }

        val keep = if (still || fresh) 0f else exp(-elapsed / afterglow).toFloat()
        fresh = false
        for (d in 0 until grid.count) {
            var now = 0f
            var total = 0f
            for (k in 0 until 2) {
                val m = d * 2 + k
                light[m] = max(inst[m], light[m] * keep)
                now += inst[m]
                total += light[m]
            }
            val freshness = min(1f, now / max(total, 1e-4f))
            field[d] = (0xFF shl 24) or
                (channel(light[d * 2]) shl 16) or
                (channel(light[d * 2 + 1]) shl 8) or
                (freshness * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    private fun channel(value: Float): Int = (value / ConnectionScopeRenderer.FIELD_SCALE * 255f).roundToInt().coerceIn(0, 255)

    /** Lights dot (i, j) on [channel] (0 alert, 1 main) to at least [v]. */
    private fun add(i: Int, j: Int, channel: Int, v: Float) {
        val grid = grid ?: return
        if (i < 0 || j < 0 || i >= grid.columns || j >= grid.rows) return
        val m = (j * grid.columns + i) * 2 + channel
        if (v > inst[m]) inst[m] = v
    }

    /** A trace: `ys[i]` is column i's row, [brightness] its light. */
    private fun stroke(brightness: FloatArray, channel: Int) {
        val grid = grid ?: return
        val cols = grid.columns
        val rows = grid.rows
        for (i in 0 until cols) {
            if (brightness[i] <= 0) continue
            val y = ys[i]
            val before = if (i > 0) (y + ys[i - 1]) / 2 else y
            val after = if (i < cols - 1) (y + ys[i + 1]) / 2 else y
            val lo = minOf(y, before, after)
            val hi = maxOf(y, before, after)
            val j0 = max(0, floor(lo).toInt() - 2)
            val j1 = min(rows - 1, ceil(hi).toInt() + 2)
            if (j0 > j1) continue
            for (j in j0..j1) {
                val fj = j.toFloat()
                val d = if (fj < lo) lo - fj else if (fj > hi) fj - hi else 0f
                val v = (exp(-(d * d) / 0.25f) + 0.45f * exp(-d / 0.8f)) * brightness[i]
                add(i, j, channel, min(1.2f, v))
            }
        }
    }

    /** The pen: a soft point at fractional (x, y). */
    private fun splat(x: Float, y: Float, channel: Int, v: Float) {
        val i0 = floor(x - 1).toInt()
        val j0 = floor(y - 1).toInt()
        for (i in i0..(i0 + 3)) {
            for (j in j0..(j0 + 3)) {
                val d2 = (i - x) * (i - x) + (j - y) * (j - y)
                add(i, j, channel, v * (exp(-d2 / 0.3f) + 0.4f * exp(-sqrt(d2) / 0.8f)))
            }
        }
    }
}

/**
 * The trace's colour by status: the system green while connected, orange while slow or unstable;
 * null keeps the neutral pearl or ink (reconnecting, switching), and offline draws in the alert
 * red. On light the colours are the system's increased-contrast variants: plain system green is
 * under 2:1 on the scope's well, too faint for single dots (3.9:1 this way; 9.2:1 on dark).
 */
internal object ConnectionTraceTint {
    // (light, high contrast), (dark), (dark, high contrast).
    private val greenHighContrastLight = ScopeColor(36 / 255f, 138 / 255f, 61 / 255f)
    private val greenDark = ScopeColor(48 / 255f, 209 / 255f, 88 / 255f)
    private val greenHighContrastDark = ScopeColor(48 / 255f, 219 / 255f, 91 / 255f)
    private val orangeHighContrastLight = ScopeColor(201 / 255f, 52 / 255f, 0f)
    private val orangeDark = ScopeColor(255 / 255f, 159 / 255f, 10 / 255f)
    private val orangeHighContrastDark = ScopeColor(255 / 255f, 179 / 255f, 64 / 255f)

    fun colors(status: ConnectionHealth.Status, dark: Boolean, highContrast: Boolean): Pair<ScopeColor, ScopeColor>? {
        val green = when (status) {
            ConnectionHealth.Status.connected -> true
            ConnectionHealth.Status.slow, ConnectionHealth.Status.unstable -> false
            else -> return null
        }
        val main = when {
            !dark -> if (green) greenHighContrastLight else orangeHighContrastLight
            highContrast -> if (green) greenHighContrastDark else orangeHighContrastDark
            else -> if (green) greenDark else orangeDark
        }
        // The pen's hot core: toward white on dark, deeper on light, like the neutral trace's.
        val toward = if (dark) ScopeColor(1f, 1f, 1f, 1f) else ScopeColor(0f, 0f, 0f, 1f)
        val core = main + (toward - main) * (if (dark) 0.45f else 0.3f)
        return main to core
    }
}
