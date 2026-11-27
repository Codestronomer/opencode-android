package ai.opencode.core.ui.gl

import ai.opencode.core.log.AppLog
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Off-screen GLES 3.0 rendering for the Android 12 shader ports (docs/android-12-support.md
 * §10.2): Android 12 has no `RuntimeShader` (AGSL is API 33), so the ported GLSL runs here —
 * one shared EGL context on its own thread, one FBO per [GlPass] sized to the draw, pixels
 * read back into a reusable premultiplied `ARGB_8888` bitmap that a Compose draw composites
 * like the AGSL brush did. GLES 3.0 is guaranteed on every device this fork installs on.
 *
 * All GL work happens on the shared thread; [GlPass.render] blocks the caller for the frame
 * (the AGSL path is equally synchronous inside the draw). A pass's FBO texture stays alive
 * after the readback, so parent passes can sample child passes' output
 * (`uniform shader` ports) by name through [GlPass.texture].
 */
object GlPixel {
    private val thread = HandlerThread("shader-gl").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var ready = false

    @Volatile
    private var failed = false

    private const val GLOBAL_TIMEOUT_SECONDS = 5L

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    /** Runs [block] with the shared context current; null once GL is unusable. */
    fun <T> run(block: () -> T): T? {
        if (failed) return null
        if (!ready) {
            val init = CountDownLatch(1)
            var ok = false
            if (!handler.post {
                    ok = initEgl()
                    init.countDown()
                }
            ) {
                failed = true
                return null
            }
            if (!init.await(GLOBAL_TIMEOUT_SECONDS, TimeUnit.SECONDS) || !ok) {
                AppLog.app.error("gl_unavailable init_failed")
                failed = true
                return null
            }
            ready = true
        }
        val latch = CountDownLatch(1)
        var result: T? = null
        if (!handler.post {
                try {
                    result = block()
                } catch (error: Throwable) {
                    // One throwing GL call must not take the shared thread down with it:
                    // every later call would then block the draw thread forever.
                    failed = true
                    AppLog.app.error("gl_call_failed type=${error.javaClass.simpleName}")
                } finally {
                    latch.countDown()
                }
            }
        ) {
            failed = true
            return null
        }
        if (!latch.await(GLOBAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            failed = true
            AppLog.app.error("gl_call_timeout")
            return null
        }
        return result
    }

    private fun initEgl(): Boolean = runCatching {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false
        val configAttribs = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
            // EGL_OPENGL_ES3_BIT (0x40): EGL14 doesn't expose the constant name on all builds.
            EGL14.EGL_RENDERABLE_TYPE, 0x40,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val counts = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, counts, 0)) return false
        val config = configs[0] ?: return false
        this.config = config
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return false
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        if (pbuffer == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) return false
        true
    }.getOrDefault(false)
}

/**
 * One compiled GLSL ES 3.0 program and its FBO. [fragmentSource] is the ported AGSL
 * (`vec4 main(vec2 position)` → `out fragColor`; coordinates flip Y in the shader's
 * `main`). [render] draws the fullscreen quad, reads the pixels back and returns a
 * premultiplied bitmap the caller draws; returns null (and the caller falls back) when
 * GL is unusable or the program fails to compile.
 */
class GlPass(private val fragmentSource: String) {
    private var program = 0
    private var failed = false
    private var fbo = 0
    private var texture = 0
    private var targetWidth = 0
    private var targetHeight = 0
    private val locations = mutableMapOf<String, Int>()
    private var pixels: ByteBuffer? = null
    private var flipBuffer: ByteBuffer? = null
    private var flipBitmap: Bitmap? = null
    private var quad = 0
    private val ownedTextures = mutableSetOf<Int>()

    /** The FBO texture (valid on the shared GL context), for parent passes sampling children. */
    fun texture(): Int = texture

    /** Location of [name], cached; -1 when absent (AGSL ports keep unused uniform names out). */
    fun location(name: String): Int = locations.getOrPut(name) {
        GLES30.glGetUniformLocation(program, name)
    }

    /**
     * Uploads [bitmap] as a linear-filtered RGBA texture on the shared context (GL thread);
     * the caller samples it (`AGSL uniform shader` ports) and releases nothing — the texture
     * lives until the pass's context dies with the process. Pass a texture from a previous
     * call as [replace] to update it in place instead of generating a new object per frame
     * (the CPU bitmaps behind these children change every frame). Returns 0 when GL is
     * unusable.
     */
    fun uploadTexture(bitmap: Bitmap, replace: Int = 0): Int = GlPixel.run {
        val name = if (replace != 0) intArrayOf(replace) else IntArray(1).also {
            GLES30.glGenTextures(1, it, 0)
            ownedTextures.add(it[0])
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        if (bitmap.config == Bitmap.Config.RGBA_F16) {
            // Raw data, as the AGSL setInputBuffer children are: the half-float bits the
            // shader reads as values, not colour — GL_RGBA16F keeps them byte-exact.
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
                bitmap.width, bitmap.height, 0,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buffer,
            )
        } else {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                bitmap.width, bitmap.height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer,
            )
        }
        name[0]
    } ?: 0

    /**
     * Renders at [width]×[height] device pixels with [setUniforms] wiring this frame's
     * uniform values (call [location] then `GLES30.glUniform*`), and reads the result back
     * into [pixels]. The GL thread does nothing but GL; the caller turns the buffer into a
     * bitmap on its own thread. Returns false when GL is unusable — the caller falls back.
     */
    fun render(width: Int, height: Int, setUniforms: (GlPass) -> Unit): Boolean {
        return GlPixel.run {
            if (!drawFrame(width, height, setUniforms)) return@run false
            val buffer = pixels!!
            // The GLES JNI advances the buffer's position after the read; a frame that
            // renders without the bitmap step would leave it at capacity. Rewind first.
            buffer.rewind()
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            true
        } ?: false
    }

    /** The frame's pixels, as the bitmap the caller composites. UI thread only.
     *
     * The ported shaders flip Y to the AGSL top-left origin; `glReadPixels` returns the
     * FBO's bottom row first, so the rows reverse here. The bitmap is reused across frames
     * of the same size — draw it, don't cache it.
     */
    fun bitmap(width: Int, height: Int): Bitmap? {
        val buffer = pixels ?: return null
        buffer.rewind()
        val rowBytes = width * 4
        val flipped = flipBuffer?.takeIf { it.capacity() == rowBytes * height }
            ?: ByteBuffer.allocateDirect(rowBytes * height).order(ByteOrder.nativeOrder())
                .also { flipBuffer = it }
        for (row in 0 until height) {
            flipped.position(row * rowBytes)
            val src = (height - 1 - row) * rowBytes
            val dup = buffer.duplicate()
            dup.position(src)
            dup.limit(src + rowBytes)
            flipped.put(dup)
        }
        flipped.position(0)
        val out = flipBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { flipBitmap = it }
        out.copyPixelsFromBuffer(flipped)
        return out
    }

    /** Releases this pass's GL objects on the shared context. Safe to call once, from any thread. */
    fun close() {
        GlPixel.run {
            if (program != 0) GLES30.glDeleteProgram(program)
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            if (quad != 0) GLES30.glDeleteBuffers(1, intArrayOf(quad), 0)
            if (ownedTextures.isNotEmpty()) {
                GLES30.glDeleteTextures(ownedTextures.size, ownedTextures.toIntArray(), 0)
                ownedTextures.clear()
            }
            true
        }
        program = 0
        fbo = 0
        texture = 0
        quad = 0
        pixels = null
        flipBuffer = null
        flipBitmap = null
        locations.clear()
    }
    /**
     * The readback variant of [render] with persistent triple-buffered slots: no per-frame
     * allocation, no row-flip copy. The returned bitmap's rows run BOTTOM-UP (glReadPixels
     * order); the caller draws it flipped about its vertical center (a free transform).
     * Triple-buffering keeps the slot two frames away from the last display list that
     * sampled it — the standard back-buffer contract. The rows run bottom-up because the
     * ported shaders flip Y to the AGSL origin and `glReadPixels` returns the FBO's bottom
     * row first: with the row order preserved, the flipped draw lands the image upright.
     */
    fun renderPersisted(width: Int, height: Int, setUniforms: (GlPass) -> Unit): Bitmap? {
        val readSlot = slotIndex
        val ok = GlPixel.run {
            if (!drawFrame(width, height, setUniforms)) return@run false
            val slot = ensureSlots(width, height)[readSlot]
            val buffer = slot.first
            buffer.rewind()
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            slotIndex = (readSlot + 1) % slots.size
            true
        } ?: return null
        if (!ok) return null
        val (buffer, bitmap) = slots[readSlot]
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    /**
     * Binds this pass's FBO and draws the fullscreen quad. Call on the shared GL thread,
     * inside [GlPixel.run]; false means GL or the program is unusable.
     */
    private fun drawFrame(width: Int, height: Int, setUniforms: (GlPass) -> Unit): Boolean {
        if (failed || width <= 0 || height <= 0) return false
        if (program == 0) {
            program = compile()
            if (program == 0) {
                AppLog.app.error("gl_shader_compile_failed")
                failed = true
                return false
            }
        }
        if (width != targetWidth || height != targetHeight) resize(width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        val aPos = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, 0)
        setUniforms(this)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glDisableVertexAttribArray(aPos)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return true
    }

    private var slots: List<Pair<ByteBuffer, Bitmap>> = emptyList()
    private var slotIndex = 0
    private var slotSize: Pair<Int, Int>? = null

    private fun ensureSlots(width: Int, height: Int): List<Pair<ByteBuffer, Bitmap>> {
        if (slotSize?.first == width && slotSize?.second == height) return slots
        slots = (0 until 3).map {
            ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()) to
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        slotSize = width to height
        slotIndex = 0
        return slots
    }


    private fun compile(): Int {
        val vertex = shader(GLES30.GL_VERTEX_SHADER, VERTEX) ?: return 0
        val fragment = shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource) ?: return 0
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (linked[0] != GLES30.GL_TRUE) {
            AppLog.app.error("gl_link_error ${GLES30.glGetProgramInfoLog(program).trim()}")
            return 0
        }
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source.trim())
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES30.GL_TRUE) {
            AppLog.app.error("gl_compile_error ${GLES30.glGetShaderInfoLog(shader).trim()}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun resize(width: Int, height: Int) {
        if (texture == 0) {
            val names = IntArray(1)
            GLES30.glGenTextures(1, names, 0)
            texture = names[0]
            GLES30.glGenFramebuffers(1, names, 0)
            fbo = names[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (quad == 0) {
            val names = IntArray(1)
            GLES30.glGenBuffers(1, names, 0)
            quad = names[0]
            // The GLES JNI checks Buffer.remaining() against the byte size, so the VBO
            // data ships as a byte buffer even though it holds floats.
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD_BYTES.remaining(), QUAD_BYTES, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
        targetWidth = width
        targetHeight = height
        pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }
}

/** Two triangles covering the viewport in clip space (attribute-less quads are fragile on some drivers). */
private val QUAD_BYTES = ByteBuffer
    .allocateDirect(12 * 4)
    .order(java.nio.ByteOrder.nativeOrder())
    .apply {
        asFloatBuffer().put(
            floatArrayOf(
                -1f, -1f, 1f, -1f, -1f, 1f,
                1f, -1f, 1f, 1f, -1f, 1f,
            ),
        )
    }

private const val VERTEX = """
#version 300 es
in vec2 aPos;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
