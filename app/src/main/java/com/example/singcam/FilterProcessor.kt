package com.example.singcam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/** Camera effect that routes both the preview and the recorded video through our filter shader. */
class FilterEffect(processor: FilterProcessor, onError: (Throwable) -> Unit) :
    CameraEffect(
        CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
        processor.executor,
        processor,
        Consumer<Throwable> { onError(it) }
    )

/**
 * Draws every camera frame through an OpenGL fragment shader.
 * filterId: 0 None, 1 Lo-Res, 2 Handheld, 3 Rio de Janeiro, 4 Soft Light, 5 Zoom Blur,
 *           6 Hyper, 7 Gritty, 8 Grainy, 9 Midnight, 10 Black & White, 11 Fade
 */
class FilterProcessor(private val onFatal: (Throwable) -> Unit) : SurfaceProcessor {

    private class Input(val st: SurfaceTexture, val tex: Int, val surface: Surface)
    private class Out(val output: SurfaceOutput, val surf: EGLSurface, val w: Int, val h: Int)

    @Volatile var filterId = 0
    @Volatile private var released = false
    @Volatile private var ready = false

    private val thread = HandlerThread("SingCamGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var cfg: EGLConfig

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uFilter = 0
    private var uTime = 0
    private var uRes = 0
    private var sTex = 0

    private var input: Input? = null
    private val outputs = ArrayList<Out>()
    private val texMat = FloatArray(16)
    private val outMat = FloatArray(16)

    private val posBuf = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texBuf = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    init {
        handler.post {
            try {
                initGl()
                ready = true
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // ---------------- SurfaceProcessor ----------------

    override fun onInputSurface(request: SurfaceRequest) {
        if (!ready || released) {
            request.willNotProvideSurface()
            return
        }
        try {
            makePbufferCurrent()
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val tex = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(tex)
            st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val surface = Surface(st)
            val inp = Input(st, tex, surface)
            input = inp
            st.setOnFrameAvailableListener(
                SurfaceTexture.OnFrameAvailableListener { onFrame(inp) },
                handler
            )
            request.provideSurface(surface, executor) {
                try {
                    st.setOnFrameAvailableListener(null)
                    surface.release()
                    st.release()
                    if (!released) {
                        makePbufferCurrent()
                        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
                    }
                } catch (_: Throwable) {
                }
                if (input === inp) input = null
            }
        } catch (t: Throwable) {
            request.willNotProvideSurface()
            fail(t)
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (!ready || released) {
            surfaceOutput.close()
            return
        }
        try {
            val surface = surfaceOutput.getSurface(executor) { removeOutput(surfaceOutput) }
            val es = EGL14.eglCreateWindowSurface(display, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (es == null || es == EGL14.EGL_NO_SURFACE) throw RuntimeException("Could not create EGL surface")
            outputs.add(Out(surfaceOutput, es, surfaceOutput.size.width, surfaceOutput.size.height))
        } catch (t: Throwable) {
            surfaceOutput.close()
            fail(t)
        }
    }

    // ---------------- Rendering ----------------

    private fun onFrame(inp: Input) {
        if (released || input !== inp) return
        try {
            makePbufferCurrent()
            inp.st.updateTexImage()
            if (outputs.isEmpty()) return
            inp.st.getTransformMatrix(texMat)
            val ts = inp.st.timestamp
            val time = (SystemClock.uptimeMillis() % 1_000_000L) / 1000f
            for (o in outputs.toList()) draw(o, inp, ts, time)
            makePbufferCurrent()
        } catch (t: Throwable) {
            if (input === inp && !released) fail(t)
        }
    }

    private fun draw(o: Out, inp: Input, ts: Long, time: Float) {
        EGL14.eglMakeCurrent(display, o.surf, o.surf, context)
        GLES20.glViewport(0, 0, o.w, o.h)
        o.output.updateTransformMatrix(outMat, texMat)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, outMat, 0)
        GLES20.glUniform1i(uFilter, filterId)
        GLES20.glUniform1f(uTime, time)
        GLES20.glUniform2f(uRes, o.w.toFloat(), o.h.toFloat())

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inp.tex)
        GLES20.glUniform1i(sTex, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        EGLExt.eglPresentationTimeANDROID(display, o.surf, ts)
        EGL14.eglSwapBuffers(display, o.surf)
    }

    private fun removeOutput(output: SurfaceOutput) {
        val o = outputs.firstOrNull { it.output === output }
        if (o != null) {
            outputs.remove(o)
            if (!released) {
                try {
                    makePbufferCurrent()
                    EGL14.eglDestroySurface(display, o.surf)
                } catch (_: Throwable) {
                }
            }
        }
        output.close()
    }

    // ---------------- Setup / teardown ----------------

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "EGL init failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(64)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 64, num, 0) && num[0] > 0) { "No EGL config" }
        var chosen: EGLConfig? = configs[0]
        val rec = IntArray(1)
        for (i in 0 until num[0]) {
            val c = configs[i] ?: continue
            if (EGL14.eglGetConfigAttrib(display, c, EGLExt.EGL_RECORDABLE_ANDROID, rec, 0) && rec[0] == 1) {
                chosen = c
                break
            }
        }
        cfg = chosen!!

        context = EGL14.eglCreateContext(
            display, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL context failed" }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, cfg,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "EGL pbuffer failed" }
        makePbufferCurrent()

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uFilter = GLES20.glGetUniformLocation(program, "uFilter")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uRes = GLES20.glGetUniformLocation(program, "uRes")
        sTex = GLES20.glGetUniformLocation(program, "sTex")
    }

    fun release() {
        handler.post {
            if (released) return@post
            released = true
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    if (pbuffer != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                    }
                    for (o in outputs.toList()) {
                        EGL14.eglDestroySurface(display, o.surf)
                        o.output.close()
                    }
                    outputs.clear()
                    input?.let {
                        it.surface.release()
                        it.st.release()
                    }
                    input = null
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {
            }
            thread.quitSafely()
        }
    }

    private fun makePbufferCurrent() {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    private fun fail(t: Throwable) {
        if (!released) onFatal(t)
    }

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a)
            position(0)
        }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Shader link failed: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Shader compile failed: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vP;
void main() {
    gl_Position = aPosition;
    vP = aTexCoord.xy;
}
"""

        private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vP;
uniform samplerExternalOES sTex;
uniform mat4 uTexMatrix;
uniform int uFilter;
uniform float uTime;
uniform vec2 uRes;

vec3 tx(vec2 p) {
    p = clamp(p, 0.0, 1.0);
    vec2 t = (uTexMatrix * vec4(p, 0.0, 1.0)).xy;
    return texture2D(sTex, t).rgb;
}
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
vec3 sat(vec3 c, float s) { return mix(vec3(luma(c)), c, s); }
float hash(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
float vg(vec2 p) { return smoothstep(0.30, 0.90, distance(p, vec2(0.5))); }

void main() {
    vec2 p = vP;
    vec3 c;

    if (uFilter == 1) {
        // Lo-Res: chunky pixels, reduced colours
        vec2 cell = vec2(9.0) / uRes;
        p = (floor(p / cell) + 0.5) * cell;
        c = tx(p);
        c = floor(c * 12.0 + 0.5) / 12.0;
        c = sat(c, 1.15);
    } else if (uFilter == 2) {
        // Handheld: gentle camera shake
        float t = uTime;
        vec2 shake = vec2(
            sin(t * 9.0) + 0.6 * sin(t * 23.0 + 1.3) + 0.4 * sin(t * 4.1),
            cos(t * 8.0) + 0.6 * cos(t * 19.0 + 0.7) + 0.4 * cos(t * 3.3)
        ) * 0.0045;
        p = (p - 0.5) / 1.08 + 0.5 + shake;
        c = tx(p);
    } else if (uFilter == 3) {
        // Rio de Janeiro: warm, golden, vivid
        c = tx(p);
        c = sat(c, 1.4);
        c *= vec3(1.12, 1.02, 0.84);
        c = (c - 0.5) * 1.12 + 0.5 + vec3(0.04, 0.01, -0.02);
        c *= 1.0 - 0.25 * vg(p);
    } else if (uFilter == 4) {
        // Soft Light: dreamy glow
        c = tx(p);
        vec3 s = c;
        for (int i = 0; i < 8; i++) {
            float a = 6.2831853 * float(i) / 8.0;
            s += tx(p + vec2(cos(a), sin(a)) * 14.0 / uRes);
        }
        s /= 9.0;
        c = mix(c, s, 0.55);
        c = 1.0 - (1.0 - c) * (1.0 - s * 0.3);
        c = sat(c, 0.92);
        c = c * 0.95 + 0.05;
        c *= vec3(1.03, 1.0, 0.97);
    } else if (uFilter == 5) {
        // Zoom Blur: radial blur from the centre
        vec2 d = p - vec2(0.5);
        float amt = 0.10 + 0.04 * sin(uTime * 2.5);
        c = vec3(0.0);
        for (int i = 0; i < 12; i++) {
            float k = float(i) / 11.0;
            c += tx(vec2(0.5) + d * (1.0 - amt * k));
        }
        c /= 12.0;
    } else if (uFilter == 6) {
        // Hyper: neon colours, RGB split, punchy contrast
        float o = 0.006 + 0.002 * sin(uTime * 6.0);
        c = vec3(tx(p + vec2(o, 0.0)).r, tx(p).g, tx(p - vec2(o, 0.0)).b);
        c = sat(c, 1.8);
        c = (c - 0.5) * 1.25 + 0.5;
    } else if (uFilter == 7) {
        // Gritty: dark, harsh, noisy
        c = tx(p);
        c = sat(c, 0.45);
        c = (c - 0.5) * 1.4 + 0.5;
        c *= vec3(0.95, 1.0, 1.05);
        float g = hash(p * uRes + fract(uTime) * 37.0) - 0.5;
        c += g * 0.22;
        c *= 1.0 - 0.45 * vg(p);
    } else if (uFilter == 8) {
        // Grainy: film grain
        c = tx(p);
        float g = hash(p * uRes + fract(uTime * 1.7) * 91.0) - 0.5;
        c += g * 0.16;
        c = sat(c, 0.92);
        c = c * 0.97 + 0.02;
    } else if (uFilter == 9) {
        // Midnight: deep blue night look
        c = tx(p);
        c = sat(c, 0.75);
        c *= vec3(0.50, 0.62, 1.0);
        c = pow(c, vec3(1.1));
        c = (c - 0.5) * 1.1 + 0.5;
        c *= 1.0 - 0.5 * vg(p);
        c += vec3(0.0, 0.01, 0.04);
    } else if (uFilter == 10) {
        // Black & White
        float l = luma(tx(p));
        l = (l - 0.5) * 1.12 + 0.5;
        c = vec3(l);
    } else if (uFilter == 11) {
        // Fade: lifted blacks, washed-out colours
        c = tx(p);
        c = sat(c, 0.7);
        c = c * 0.82 + 0.14;
        c *= vec3(1.03, 1.0, 0.96);
    } else {
        c = tx(p);
    }

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
