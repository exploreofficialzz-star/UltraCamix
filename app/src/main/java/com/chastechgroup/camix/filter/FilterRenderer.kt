package com.chastechgroup.camix.filter

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 renderer for CamixUltra.
 *
 * Routes each frame through the correct shader program based on
 * FilterParameters, supporting:
 *  • Master (all standard corrections + clarity + dehaze)
 *  • Cinematic (ACES + anamorphic streak + letterbox)
 *  • Thermal   (iron-bow false-color palette)
 *  • Ultrasound (full Sobel edge detection)
 *  • Focus Peaking (Laplacian edge overlay)
 *  • Portrait  (skin-aware background blur)
 *  • Night     (bilateral denoise + tone-map)
 *  • Vivid     (vibrance-safe saturation)
 *  • Mono      (B&W with tonal tint)
 *  • Simple    (performance fallback)
 */
class FilterRenderer : GLSurfaceView.Renderer {

    // Shader programs (lazy-initialised on GL thread)
    private var masterProgram:    ShaderProgram? = null
    private var cinematicProgram: ShaderProgram? = null
    private var thermalProgram:   ShaderProgram? = null
    private var ultrasoundProgram:ShaderProgram? = null
    private var peakingProgram:   ShaderProgram? = null
    private var portraitProgram:  ShaderProgram? = null
    private var nightProgram:     ShaderProgram? = null
    private var vividProgram:     ShaderProgram? = null
    private var monoProgram:      ShaderProgram? = null
    private var simpleProgram:    ShaderProgram? = null

    private val vertexBuffer:  FloatBuffer
    private val textureBuffer: FloatBuffer

    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null

    private val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val stMatrix  = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var currentParams = FilterParameters.DEFAULT
    private var viewWidth  = 1
    private var viewHeight = 1

    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    // Full-screen quad
    private val vertices      = floatArrayOf(-1f,-1f,  1f,-1f, -1f,1f,  1f,1f)
    private val textureCoords = floatArrayOf( 0f, 0f,  1f, 0f,  0f,1f,  1f,1f)

    init {
        fun makeBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { it.put(data); it.position(0) }
        vertexBuffer  = makeBuffer(vertices)
        textureBuffer = makeBuffer(textureCoords)
    }

    // ── GLSurfaceView.Renderer ────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        fun build(vert: String, frag: String) =
            try { ShaderProgram(vert, frag) }
            catch (e: Exception) {
                Timber.e(e, "Shader compile failed"); null
            }

        val v = ShaderProgram.VERTEX_SHADER_DEFAULT
        masterProgram     = build(v, CameraFilterShaders.FRAGMENT_SHADER_MASTER)
        cinematicProgram  = build(v, CameraFilterShaders.FRAGMENT_SHADER_CINEMATIC)
        thermalProgram    = build(v, CameraFilterShaders.FRAGMENT_SHADER_THERMAL)
        ultrasoundProgram = build(v, CameraFilterShaders.FRAGMENT_SHADER_ULTRASOUND)
        peakingProgram    = build(v, CameraFilterShaders.FRAGMENT_SHADER_FOCUS_PEAKING)
        portraitProgram   = build(v, CameraFilterShaders.FRAGMENT_SHADER_PORTRAIT)
        nightProgram      = build(v, CameraFilterShaders.FRAGMENT_SHADER_NIGHT)
        vividProgram      = build(v, CameraFilterShaders.FRAGMENT_SHADER_VIVID)
        monoProgram       = build(v, CameraFilterShaders.FRAGMENT_SHADER_MONO)
        simpleProgram     = build(v, CameraFilterShaders.FRAGMENT_SHADER_SIMPLE)

        // Create external OES texture
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId).also { onSurfaceTextureReady?.invoke(it) }
        Timber.d("FilterRenderer: surface created")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth  = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(stMatrix)
        selectAndRender()
    }

    // ── Shader routing ────────────────────────────────────────────────────────

    private fun selectAndRender() {
        val p = currentParams
        val program: ShaderProgram? = when {
            p.thermalIntensity   > 0.01f -> thermalProgram
            p.ultrasoundEdge     > 0.01f -> ultrasoundProgram
            p.cinematicIntensity > 0.01f -> cinematicProgram
            p.focusPeakingEnabled        -> peakingProgram
            p.saturation <= -0.95f       -> monoProgram
            p.blur         > 0.01f       -> portraitProgram   // portrait uses blur
            else -> masterProgram ?: simpleProgram
        }
        (program ?: simpleProgram)?.let { render(it) }
    }

    private fun render(prog: ShaderProgram) {
        prog.use()

        // MVP / ST matrices
        prog.setMat4("uMVPMatrix", mvpMatrix)
        prog.setMat4("uSTMatrix",  stMatrix)

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        prog.setInt("uTexture", 0)

        // Vertex attributes
        val posLoc = prog.getAttribLocation("aPosition")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texLoc = prog.getAttribLocation("aTextureCoord")
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        // Upload uniforms for the active program
        uploadUniforms(prog)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun uploadUniforms(prog: ShaderProgram) {
        val p = currentParams
        val t = System.currentTimeMillis().toFloat() / 1000f
        val w = viewWidth.toFloat()
        val h = viewHeight.toFloat()

        // Shared across most programs
        prog.setFloat("uImageWidth",  w)
        prog.setFloat("uImageHeight", h)
        prog.setFloat("uTime",        t)

        when {
            p.thermalIntensity > 0.01f -> {
                prog.setFloat("uIntensity", p.thermalIntensity)
                prog.setFloat("uContrast",  p.thermalContrast)
            }
            p.ultrasoundEdge > 0.01f -> {
                prog.setFloat("uEdgeStrength", p.ultrasoundEdge)
                prog.setFloat("uBrightness",   p.ultrasoundBrightness)
            }
            p.cinematicIntensity > 0.01f -> {
                prog.setFloat("uIntensity", p.cinematicIntensity)
                prog.setFloat("uGrain",     p.grain)
            }
            p.focusPeakingEnabled -> {
                prog.setFloat("uThreshold",    p.focusPeakingThreshold)
                prog.setFloat("uPeakingColor", p.focusPeakingColor)
            }
            p.blur > 0.01f -> {            // portrait
                prog.setFloat("uBlurAmount",  p.blur)
                prog.setFloat("uSkinSmooth",  0.6f)
            }
            p.saturation <= -0.95f -> {    // mono
                prog.setFloat("uContrast", p.contrast)
                prog.setFloat("uTint",     p.tint)
            }
            else -> {                      // master / simple
                prog.setFloat("uBrightness",    p.brightness)
                prog.setFloat("uContrast",      p.contrast)
                prog.setFloat("uSaturation",    p.saturation)
                prog.setFloat("uExposure",      p.exposure)
                prog.setFloat("uWarmth",        p.warmth)
                prog.setFloat("uTint",          p.tint)
                prog.setFloat("uHue",           p.hue)
                prog.setFloat("uHighlights",    p.highlights)
                prog.setFloat("uShadows",       p.shadows)
                prog.setFloat("uFade",          p.fade)
                prog.setFloat("uVignette",      p.vignette)
                prog.setFloat("uSharpen",       p.sharpen)
                prog.setFloat("uGrain",         p.grain)
                prog.setFloat("uBlur",          p.blur)
                prog.setFloat("uClarityBoost",  p.clarityBoost)
                prog.setFloat("uDehaze",        p.dehaze)
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun updateParameters(params: FilterParameters) {
        currentParams = params
    }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    fun setPreviewSize(width: Int, height: Int) {
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun release() {
        listOf(masterProgram, cinematicProgram, thermalProgram, ultrasoundProgram,
               peakingProgram, portraitProgram, nightProgram, vividProgram,
               monoProgram, simpleProgram).forEach { it?.release() }
        surfaceTexture?.release()
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        Timber.d("FilterRenderer released")
    }
}
