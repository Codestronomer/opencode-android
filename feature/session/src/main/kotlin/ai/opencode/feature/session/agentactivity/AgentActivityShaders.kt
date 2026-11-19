package ai.opencode.feature.session.agentactivity

import ai.opencode.core.ui.gl.GlPass
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.opengl.GLES30
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Single-pass analytical fragment shader for [AgentActivityOrb] (the activity glyph and the shell
 * glyph): the central Gaussian glow, pixel-snapped 7×7 grid, analytical exponential per-dot halo
 * (`0.22 · P² · exp(−d / (0.12 · pitch))`), and anti-aliased disc SDF in one draw call.
 *
 * Coordinates are the draw's local pixels (Metal's are points; [AgentActivityOrb.pack] works in
 * pixels and passes `scale = 1`, which is the same maths). AGSL (SkSL ES2) indexes uniform arrays
 * only by loop indices, so where Metal visits the pixel's 3 × 3 neighbourhood this walks all 49
 * cells in the same row-major order and skips any farther than a pitch, which composites the same
 * cells in the same order.
 *
 * `RuntimeShader` (API 33) doesn't exist below Android 13, so the draw goes through [orbRenderer],
 * a seam in the `AgentRevealGlow` shape: on 33+ the AGSL pass is unchanged, below 33 the pass
 * ported to GLSL ES 3.0 ([ACTIVITY_ORB_FIELD_GLSL], docs/android-12-support.md §10.2) renders
 * through the shared GLES context, and only when GLES is unusable a Canvas approximation draws
 * the same packed field.
 *
 * Uniforms:
 * - `header0`: originX, originY, pitch, scale
 * - `header1`: glowAmount, glowPeak, dark (0/1), unlitAlpha
 * - `glowRGB`, `neutral`, `neutralCore`, `accent`, `accentCore`
 * - `cells[49]`: (light, fresh, accent, dotSize); dotSize 0 marks a cell outside the disc mask.
 */
object AgentActivityShaders {
    const val ACTIVITY_ORB_FIELD = """
uniform float4 header0;
uniform float4 header1;
uniform float3 glowRGB;
uniform float3 neutral;
uniform float3 neutralCore;
uniform float3 accent;
uniform float3 accentCore;
uniform float4 cells[49];

half4 main(float2 position) {
  float2 origin = header0.xy;
  float pitch = header0.z;
  float scale = max(1.0, header0.w);
  float glowAmount = header1.x;
  float glowPeak = header1.y;
  float isDark = header1.z;
  float a0 = header1.w;

  float gridSide = 7.0 * pitch;
  float2 centre = origin + float2(gridSide * 0.5);

  float3 premul = float3(0.0);
  float alphaAcc = 0.0;

  // 1. Central Gaussian glow.
  if (glowAmount > 1e-3) {
    float sigma = 2.2;
    float reach = 3.6 * sigma;
    float distCells = length(position - centre) / pitch;
    if (distCells < reach) {
      float g = min(1.0, glowAmount * exp(-distCells * distCells / (2.0 * sigma * sigma))) * glowPeak;
      premul = glowRGB * g;
      alphaAcc = g;
    }
  }

  // 2. Dot cores and halos.
  float2 rel = (position - origin) / pitch;
  if (rel.x < -0.6 || rel.y < -0.6 || rel.x > 7.6 || rel.y > 7.6) {
    return half4(half3(premul), half(alphaAcc));
  }
  float halfPx = 0.5 / scale;
  float top = isDark > 0.5 ? 0.96 : 1.0;

  for (int i = 0; i < 49; i++) {
    float4 cell = cells[i];
    float dotSize = cell.w;
    if (dotSize <= 0.0) continue;
    float r = floor(float(i) / 7.0);
    float c = float(i) - r * 7.0;

    float2 dotCenter = origin + float2((c + 0.5) * pitch, (r + 0.5) * pitch);
    float dist = length(position - dotCenter);
    if (dist > pitch + halfPx) continue;

    float P = clamp(cell.x, 0.0, 1.0);
    float haloPeak = 0.22 * P * P;
    if (haloPeak <= 0.008 && dist > 0.48 * pitch + halfPx) continue;

    float fresh = clamp(cell.y, 0.0, 1.0);
    float dotAccent = clamp(cell.z, 0.0, 1.0);

    float sstep = smoothstep(0.03, 0.2, P);
    float shrink = 1.0 + ((0.82 + 0.18 * fresh) - 1.0) * sstep;
    float radius = (0.25 + 0.17 * pow(max(P, 1e-5), 0.6)) * shrink * pitch * dotSize;
    float outer = radius + 0.5 * pitch;
    if (dist > (haloPeak > 0.008 ? outer : radius) + halfPx) continue;

    float expPow = (isDark > 0.5 ? 0.9 : 0.75) * (1.4 + (1.0 - 1.4) * fresh);
    float dotAlpha = a0 + (top - a0) * pow(max(P, 1e-5), expPow);
    float core = clamp(P * P * 1.1, 0.0, 0.9) * (0.3 + 0.7 * fresh);
    float3 rgb = mix(mix(neutral, neutralCore, core), mix(accent, accentCore, core), dotAccent);

    // Analytical exponential halo outside the dot radius.
    if (haloPeak > 0.008) {
      float inner = max(0.0, radius - 0.01 * outer);
      if (dist >= inner && dist <= outer + halfPx) {
        float innerRamp = dist < radius ? clamp((dist - inner) / max(1e-4, radius - inner), 0.0, 1.0) : 1.0;
        float d = max(0.0, dist - radius);
        float outerCov = clamp((outer - dist) * scale + 0.5, 0.0, 1.0);
        float hAlpha = haloPeak * exp(-d / (0.12 * pitch)) * innerRamp * outerCov;
        premul = premul * (1.0 - hAlpha) + rgb * hAlpha;
        alphaAcc = alphaAcc * (1.0 - hAlpha) + hAlpha;
      }
    }

    // Anti-aliased dot disc.
    if (dist <= radius + halfPx) {
      float cov = clamp((radius - dist) * scale + 0.5, 0.0, 1.0);
      float a = dotAlpha * cov;
      premul = premul * (1.0 - a) + rgb * a;
      alphaAcc = alphaAcc * (1.0 - a) + a;
    }
  }

  return half4(half3(premul), half(alphaAcc));
}
"""

/**
 * The pass above ported to GLSL ES 3.0 for Android 12 (docs/android-12-support.md §10.2,
 * where it is validated under glslangValidator): identical maths after the mechanical type
 * renames (`float2/3/4` → `vec2/3/4`, `half` → `float`), the entry point reads `gl_FragCoord`
 * with the Y flipped to the AGSL's top-left origin, and the output stays premultiplied.
 */
private const val ACTIVITY_ORB_FIELD_GLSL = """
#version 300 es
precision highp float;

uniform vec4 header0;   // originX, originY, pitch, scale
uniform vec4 header1;   // glowAmount, glowPeak, dark, unlitAlpha
uniform vec3 glowRGB;
uniform vec3 neutral;
uniform vec3 neutralCore;
uniform vec3 accent;
uniform vec3 accentCore;
uniform vec4 cells[49];
uniform vec2 uSize;     // render target size in px (for the y-flip)

out vec4 fragColor;

vec4 orbField(vec2 position) {
  vec2 origin = header0.xy;
  float pitch = header0.z;
  float scale = max(1.0, header0.w);
  float glowAmount = header1.x;
  float glowPeak = header1.y;
  float isDark = header1.z;
  float a0 = header1.w;

  float gridSide = 7.0 * pitch;
  vec2 centre = origin + vec2(gridSide * 0.5);

  vec3 premul = vec3(0.0);
  float alphaAcc = 0.0;

  if (glowAmount > 1e-3) {
    float sigma = 2.2;
    float reach = 3.6 * sigma;
    float distCells = length(position - centre) / pitch;
    if (distCells < reach) {
      float g = min(1.0, glowAmount * exp(-distCells * distCells / (2.0 * sigma * sigma))) * glowPeak;
      premul = glowRGB * g;
      alphaAcc = g;
    }
  }

  vec2 rel = (position - origin) / pitch;
  if (rel.x < -0.6 || rel.y < -0.6 || rel.x > 7.6 || rel.y > 7.6) {
    return vec4(premul, alphaAcc);
  }
  float halfPx = 0.5 / scale;
  float top = isDark > 0.5 ? 0.96 : 1.0;

  for (int i = 0; i < 49; i++) {
    vec4 cell = cells[i];
    float dotSize = cell.w;
    if (dotSize <= 0.0) continue;
    float r = floor(float(i) / 7.0);
    float c = float(i) - r * 7.0;

    vec2 dotCenter = origin + vec2((c + 0.5) * pitch, (r + 0.5) * pitch);
    float dist = length(position - dotCenter);
    if (dist > pitch + halfPx) continue;

    float P = clamp(cell.x, 0.0, 1.0);
    float haloPeak = 0.22 * P * P;
    if (haloPeak <= 0.008 && dist > 0.48 * pitch + halfPx) continue;

    float fresh = clamp(cell.y, 0.0, 1.0);
    float dotAccent = clamp(cell.z, 0.0, 1.0);

    float sstep = smoothstep(0.03, 0.2, P);
    float shrink = 1.0 + ((0.82 + 0.18 * fresh) - 1.0) * sstep;
    float radius = (0.25 + 0.17 * pow(max(P, 1e-5), 0.6)) * shrink * pitch * dotSize;
    float outer = radius + 0.5 * pitch;
    if (dist > (haloPeak > 0.008 ? outer : radius) + halfPx) continue;

    float expPow = (isDark > 0.5 ? 0.9 : 0.75) * (1.4 + (1.0 - 1.4) * fresh);
    float dotAlpha = a0 + (top - a0) * pow(max(P, 1e-5), expPow);
    float core = clamp(P * P * 1.1, 0.0, 0.9) * (0.3 + 0.7 * fresh);
    vec3 rgb = mix(mix(neutral, neutralCore, core), mix(accent, accentCore, core), dotAccent);

    if (haloPeak > 0.008) {
      float inner = max(0.0, radius - 0.01 * outer);
      if (dist >= inner && dist <= outer + halfPx) {
        float innerRamp = dist < radius ? clamp((dist - inner) / max(1e-4, radius - inner), 0.0, 1.0) : 1.0;
        float d = max(0.0, dist - radius);
        float outerCov = clamp((outer - dist) * scale + 0.5, 0.0, 1.0);
        float hAlpha = haloPeak * exp(-d / (0.12 * pitch)) * innerRamp * outerCov;
        premul = premul * (1.0 - hAlpha) + rgb * hAlpha;
        alphaAcc = alphaAcc * (1.0 - hAlpha) + hAlpha;
      }
    }

    if (dist <= radius + halfPx) {
      float cov = clamp((radius - dist) * scale + 0.5, 0.0, 1.0);
      float a = dotAlpha * cov;
      premul = premul * (1.0 - a) + rgb * a;
      alphaAcc = alphaAcc * (1.0 - a) + a;
    }
  }

  return vec4(premul, alphaAcc);
}

void main() {
  vec2 position = vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y); // AGSL y-origin is top-left
  fragColor = orbField(position);
}
"""

    /**
     * The one shared orb renderer. Main thread only: a glyph sets its uniforms and draws at once;
     * a recorded draw keeps the native shader it was recorded with, since every uniform change
     * makes a new native instance. Below 33 [GlOrb] blocks the draw for its GL frame like the
     * AGSL path does inside the draw.
     */
    internal val orbRenderer: OrbRenderer by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslOrb() else GlOrb()
    }

    internal interface OrbRenderer {
        /** Draws [packed]'s field centred in the [side]-pixel square. */
        fun DrawScope.draw(packed: AgentActivityOrb.Packed, side: Float)
    }

    /** The AGSL pass, API 33+, unchanged: set the uniforms, then one drawRect. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class AgslOrb : OrbRenderer {
        private val shader = RuntimeShader(ACTIVITY_ORB_FIELD)
        private val paint = Paint().apply { shader = this@AgslOrb.shader }

        override fun DrawScope.draw(packed: AgentActivityOrb.Packed, side: Float) {
            shader.setFloatUniform("header0", packed.header0)
            shader.setFloatUniform("header1", packed.header1)
            shader.setFloatUniform("glowRGB", packed.glowRGB)
            shader.setFloatUniform("neutral", packed.neutral)
            shader.setFloatUniform("neutralCore", packed.neutralCore)
            shader.setFloatUniform("accent", packed.accent)
            shader.setFloatUniform("accentCore", packed.accentCore)
            shader.setFloatUniform("cells", packed.cells)
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, side, side, paint) }
        }
    }

    /**
     * Below 33: the ported pass on GLES 3.0 (docs/android-12-support.md §10.2). The same packed
     * field drives the uniforms — [AgentActivityOrb.Packed.cells] uploads as one
     * `glUniform4fv(49)` array — and the rendered bitmap composites where the AGSL brush drew.
     * Only if GLES is unusable there does the draw fall back to [CanvasOrb].
     */
    private class GlOrb : OrbRenderer {
        private val pass = GlPass(ACTIVITY_ORB_FIELD_GLSL)
        private val canvas by lazy { CanvasOrb() }

        override fun DrawScope.draw(packed: AgentActivityOrb.Packed, side: Float) {
            val size = ceil(side).toInt().coerceAtLeast(1)
            val rendered = pass.render(size, size) { gl ->
                GLES30.glUniform4f(
                    gl.location("header0"),
                    packed.header0[0], packed.header0[1], packed.header0[2], packed.header0[3],
                )
                GLES30.glUniform4f(
                    gl.location("header1"),
                    packed.header1[0], packed.header1[1], packed.header1[2], packed.header1[3],
                )
                GLES30.glUniform3f(gl.location("glowRGB"), packed.glowRGB[0], packed.glowRGB[1], packed.glowRGB[2])
                GLES30.glUniform3f(gl.location("neutral"), packed.neutral[0], packed.neutral[1], packed.neutral[2])
                GLES30.glUniform3f(gl.location("neutralCore"), packed.neutralCore[0], packed.neutralCore[1], packed.neutralCore[2])
                GLES30.glUniform3f(gl.location("accent"), packed.accent[0], packed.accent[1], packed.accent[2])
                GLES30.glUniform3f(gl.location("accentCore"), packed.accentCore[0], packed.accentCore[1], packed.accentCore[2])
                GLES30.glUniform4fv(gl.location("cells"), 49, packed.cells, 0)
                GLES30.glUniform2f(gl.location("uSize"), size.toFloat(), size.toFloat())
            }
            val bitmap = if (rendered) pass.bitmap(size, size) else null
            if (bitmap == null) {
                with(canvas) { draw(packed, side) }
            } else {
                drawImage(bitmap.asImageBitmap())
            }
        }
    }

    /**
     * The GLES-unusable fallback: the same packed field as Canvas circles — the central Gaussian
     * glow as a cached
     * per-pitch [RadialGradient] (a white Gaussian alpha profile, tinted per frame through a
     * colour filter and positioned with a reused matrix, since one renderer draws every glyph's
     * size) and each masked dot as an anti-aliased circle with the shader's radius, alpha and
     * mixed colour. The per-dot exponential halos are not drawn. Main thread only.
     */
    private class CanvasOrb : OrbRenderer {
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint()
        private val glowStops = HashMap<Int, RadialGradient>()
        private val glowTranslate = Matrix()
        private val glowColour = FloatArray(3)
        private val glowMatrix = ColorMatrix()
        private val glowMatrixArray = FloatArray(20)
        private var glowFilter: ColorMatrixColorFilter? = null
        private val mix = FloatArray(3)
        private val accent = FloatArray(3)

        override fun DrawScope.draw(packed: AgentActivityOrb.Packed, side: Float) {
            val canvas = drawContext.canvas.nativeCanvas
            val pitch = packed.header0[2]
            val origin = packed.header0[0]
            val dark = packed.header1[2] > 0.5f
            val a0 = packed.header1[3]
            val top = if (dark) 0.96f else 1f

            val glowAmount = packed.header1[0]
            val glowPeak = packed.header1[1]
            if (glowAmount > 1e-3f && glowPeak > 1e-3f) {
                val centre = origin + 7f * pitch * 0.5f
                val stops = stopsFor(pitch)
                glowTranslate.setTranslate(centre, centre)
                stops.setLocalMatrix(glowTranslate)
                glowPaint.shader = stops
                glowPaint.colorFilter = filterFor(packed.glowRGB)
                glowPaint.alpha = (min(1f, glowAmount) * glowPeak * 255f).roundToInt().coerceIn(0, 255)
                canvas.drawCircle(centre, centre, 3.6f * 2.2f * pitch, glowPaint)
            }

            for (i in 0 until 49) {
                val base = i * 4
                val dotSize = packed.cells[base + 3]
                if (dotSize <= 0f) continue
                val P = packed.cells[base].coerceIn(0f, 1f)
                val fresh = packed.cells[base + 1].coerceIn(0f, 1f)
                val dotAccent = packed.cells[base + 2].coerceIn(0f, 1f)
                val row = (i / 7).toFloat()
                val column = (i % 7).toFloat()

                val sstep = smoothstep(0.03f, 0.2f, P)
                val shrink = 1f + (0.82f + 0.18f * fresh - 1f) * sstep
                val radius = (0.25f + 0.17f * max(P, 1e-5f).pow(0.6f)) * shrink * pitch * dotSize
                val expPow = (if (dark) 0.9f else 0.75f) * (1.4f - 0.4f * fresh)
                val dotAlpha = a0 + (top - a0) * max(P, 1e-5f).pow(expPow)
                val core = (P * P * 1.1f).coerceIn(0f, 0.9f) * (0.3f + 0.7f * fresh)

                for (k in 0..2) {
                    mix[k] = packed.neutral[k] + (packed.neutralCore[k] - packed.neutral[k]) * core
                    accent[k] = packed.accent[k] + (packed.accentCore[k] - packed.accent[k]) * core
                    mix[k] += (accent[k] - mix[k]) * dotAccent
                }
                dotPaint.color = Color.rgb(
                    (mix[0] * 255f).roundToInt().coerceIn(0, 255),
                    (mix[1] * 255f).roundToInt().coerceIn(0, 255),
                    (mix[2] * 255f).roundToInt().coerceIn(0, 255),
                )
                dotPaint.alpha = (dotAlpha * 255f).roundToInt().coerceIn(0, 255)
                canvas.drawCircle(origin + (column + 0.5f) * pitch, origin + (row + 0.5f) * pitch, radius, dotPaint)
            }
        }

        /** The Gaussian profile is white and alpha-only, rebuilt per pitch; the tint is a colour
         *  filter so an animating glow colour or centre reuses the gradient. */
        private fun stopsFor(pitch: Float): RadialGradient {
            val cached = glowStops[pitch.toRawBits()]
            if (cached != null) return cached
            val stops = 8
            val positions = FloatArray(stops + 1)
            val colours = IntArray(stops + 1)
            for (k in 0..stops) {
                val f = k.toFloat() / stops
                positions[k] = f
                // The shader's Gaussian over its 3.6σ reach: exp(−f² · 3.6² / 2).
                val a = exp(-6.48f * f * f).coerceIn(0f, 1f)
                colours[k] = Color.argb((a * 255f).roundToInt(), 255, 255, 255)
            }
            val gradient = RadialGradient(0f, 0f, 3.6f * 2.2f * pitch, colours, positions, Shader.TileMode.CLAMP)
            if (glowStops.size > 8) glowStops.clear()
            glowStops[pitch.toRawBits()] = gradient
            return gradient
        }

        private fun filterFor(rgb: FloatArray): ColorMatrixColorFilter {
            val cached = glowFilter
            if (cached != null && abs(rgb[0] - glowColour[0]) <= 1e-3f &&
                abs(rgb[1] - glowColour[1]) <= 1e-3f && abs(rgb[2] - glowColour[2]) <= 1e-3f
            ) {
                return cached
            }
            for (k in 0..2) glowColour[k] = rgb[k]
            glowMatrixArray.fill(0f)
            glowMatrixArray[0] = rgb[0]
            glowMatrixArray[6] = rgb[1]
            glowMatrixArray[12] = rgb[2]
            glowMatrixArray[18] = 1f
            glowMatrix.set(glowMatrixArray)
            return ColorMatrixColorFilter(glowMatrix).also { glowFilter = it }
        }

        private fun smoothstep(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
