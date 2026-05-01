package com.chastechgroup.camix.filter

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Real-time scene detector for CamixUltra.
 *
 * Merges the environment-analysis logic from both packages and adds:
 *  • Macro detection via high-frequency content ratio
 *  • Audio-level input for reactive adjustments (from pkg1 EnvironmentAnalyzer concept)
 *  • Smooth parameter blending (temporal IIR filter) to avoid flickering
 *  • Histogram-based exposure assessment
 */
class SceneDetector {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _detectedScene   = MutableStateFlow(SceneType.AUTO)
    val detectedScene: StateFlow<SceneType> = _detectedScene.asStateFlow()

    private val _suggestedParams = MutableStateFlow(FilterParameters.DEFAULT)
    val suggestedParams: StateFlow<FilterParameters> = _suggestedParams.asStateFlow()

    private val _audioNoiseFloor = MutableStateFlow(0f) // dB-normalized 0..1
    val audioNoiseFloor: StateFlow<Float> = _audioNoiseFloor.asStateFlow()

    // Raw image stats — fed into AutoExposureController
    private val _sceneStats = MutableStateFlow<ImageStats?>(null)
    val sceneStats: StateFlow<ImageStats?> = _sceneStats.asStateFlow()

    private var isProcessing   = false
    private var frameCounter   = 0
    private val SKIP_FRAMES    = 12          // ~2 Hz at 24 fps preview

    // IIR smoothing (α = 0.25 → blends 25 % new + 75 % old each tick)
    private var smoothBrightness = 0.5
    private var smoothContrast   = 0.5
    private var smoothSaturation = 0.3
    private var lastScene        = SceneType.AUTO
    private var sceneStability   = 0         // prevents rapid scene switches

    // ── Public API ────────────────────────────────────────────────────────────

    fun processFrame(imageProxy: ImageProxy) {
        frameCounter++
        if (frameCounter % SKIP_FRAMES != 0) { imageProxy.close(); return }
        if (isProcessing)                     { imageProxy.close(); return }

        isProcessing = true
        scope.launch {
            try {
                val bmp = imageProxy.toBitmap()
                if (bmp != null) { analyzeScene(bmp); bmp.recycle() }
            } catch (e: Exception) {
                Timber.e(e, "SceneDetector: analysis error")
            } finally {
                isProcessing = false
                imageProxy.close()
            }
        }
    }

    /** Inject real-time audio level from AudioProcessor (0..1 normalised) */
    fun updateAudioLevel(normalizedLevel: Float) {
        _audioNoiseFloor.value = normalizedLevel.coerceIn(0f, 1f)
    }

    fun release() = scope.cancel()

    // ── Analysis pipeline ─────────────────────────────────────────────────────

    private fun analyzeScene(bitmap: Bitmap) {
        val sample = Bitmap.createScaledBitmap(bitmap, bitmap.width / 6, bitmap.height / 6, true)
        val stats  = computeStats(sample)
        sample.recycle()

        // IIR smooth stats
        val α = 0.25
        smoothBrightness = α * stats.avgBrightness + (1 - α) * smoothBrightness
        smoothContrast   = α * stats.contrast       + (1 - α) * smoothContrast
        smoothSaturation = α * stats.avgSaturation  + (1 - α) * smoothSaturation

        val smoothed = stats.copy(
            avgBrightness = smoothBrightness,
            contrast      = smoothContrast,
            avgSaturation = smoothSaturation
        )

        val scene = detectScene(smoothed)

        // Scene stability gate: require 3 consecutive detections before committing
        if (scene == lastScene) {
            sceneStability++
        } else {
            sceneStability = 0
            lastScene = scene
        }
        val committedScene = if (sceneStability >= 3) scene else _detectedScene.value
        _detectedScene.value = committedScene
        _sceneStats.value    = smoothed
        _suggestedParams.value = computeParams(committedScene, smoothed)

        Timber.d("Scene=$committedScene  B=%.2f  C=%.2f  S=%.2f".format(
            smoothBrightness, smoothContrast, smoothSaturation))
    }

    // ── Image statistics ──────────────────────────────────────────────────────

    private fun computeStats(bmp: Bitmap): ImageStats {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val histogram = IntArray(256)
        var sumL = 0.0; var sumS = 0.0; var sumW = 0.0
        var minL = 255.0; var maxL = 0.0
        var darkPx = 0; var brightPx = 0; var skinPx = 0
        var hfEnergy = 0.0  // high-frequency (edge) energy for macro detection

        val N = pixels.size

        for (i in pixels.indices) {
            val px = pixels[i]
            val r  = (px shr 16) and 0xFF
            val g  = (px shr 8)  and 0xFF
            val b  =  px         and 0xFF
            val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val li  = lum.toInt().coerceIn(0, 255)
            histogram[li]++
            sumL += lum; minL = minOf(minL, lum); maxL = maxOf(maxL, lum)

            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            sumS += if (mx > 0) (mx - mn).toDouble() / mx else 0.0
            sumW += (r - b) / 255.0

            if (lum < 50)  darkPx++
            if (lum > 200) brightPx++

            // Skin tone (YCbCr heuristic)
            val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
            val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
            if (cr in 140.0..180.0 && cb in 90.0..130.0) skinPx++

            // High-frequency energy (simple row-diff Laplacian)
            if (i % w > 0) {
                val prev = pixels[i - 1]
                val diff = abs(lum - (0.2126 * ((prev shr 16) and 0xFF) +
                                     0.7152 * ((prev shr 8)  and 0xFF) +
                                     0.0722 * (prev           and 0xFF)))
                hfEnergy += diff
            }
        }

        val midtones = histogram.copyOfRange(85, 171).sum()

        return ImageStats(
            avgBrightness  = sumL / N,
            avgSaturation  = sumS / N,
            avgWarmth      = sumW / N,
            contrast       = maxL - minL,
            skinRatio      = skinPx.toDouble() / N,
            darkRatio      = darkPx.toDouble() / N,
            brightRatio    = brightPx.toDouble() / N,
            exposureBalance= midtones.toDouble() / N,
            minBrightness  = minL,
            maxBrightness  = maxL,
            hfEnergyRatio  = hfEnergy / N / 255.0
        )
    }

    // ── Scene classification ──────────────────────────────────────────────────

    private fun detectScene(s: ImageStats) = when {
        s.skinRatio    in 0.07..0.70
                                             -> SceneType.PORTRAIT
        s.avgBrightness < 30 && s.darkRatio > 0.65
                                             -> SceneType.NIGHT
        s.avgBrightness < 70 && s.darkRatio > 0.40
                                             -> SceneType.LOW_LIGHT
        s.brightRatio  > 0.30 && s.contrast > 145
                                             -> SceneType.BACKLIT
        s.avgWarmth    > 0.15 && s.avgBrightness in 55.0..155.0 && s.avgSaturation > 0.28
                                             -> SceneType.SUNSET
        s.avgSaturation > 0.32 && s.avgBrightness in 75.0..185.0 && s.hfEnergyRatio > 0.04
                                             -> SceneType.FOOD
        s.avgSaturation < 0.13 && s.contrast > 175
                                             -> SceneType.TEXT
        s.hfEnergyRatio > 0.12 && s.avgBrightness in 60.0..180.0
                                             -> SceneType.MACRO
        s.avgBrightness in 80.0..200.0 && s.contrast in 75.0..185.0 && s.avgSaturation > 0.22
                                             -> SceneType.LANDSCAPE
        s.avgBrightness in 45.0..125.0 && s.avgWarmth > 0.01
                                             -> SceneType.INDOOR
        s.avgBrightness > 145 && s.avgSaturation > 0.13
                                             -> SceneType.OUTDOOR
        s.contrast > 145                     -> SceneType.ACTION
        else                                 -> SceneType.AUTO
    }

    // ── Parameter computation ─────────────────────────────────────────────────

    private fun computeParams(scene: SceneType, s: ImageStats): FilterParameters {
        val b = s.avgBrightness; val sat = s.avgSaturation; val w = s.avgWarmth; val c = s.contrast
        return when (scene) {
            SceneType.PORTRAIT  -> FilterParameters(
                brightness = if (b < 100) 0.14f else 0.05f,
                contrast = 0.15f, saturation = if (sat < 0.3) 0.14f else 0f,
                warmth = if (w < 0) 0.1f else 0f,
                highlights = 0.1f, shadows = 0.1f,
                vignette = 0.2f, sharpen = 0.3f, clarityBoost = 0.08f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.88f)

            SceneType.LANDSCAPE -> FilterParameters(
                brightness = if (b < 120) 0.1f else 0f,
                contrast = 0.22f, saturation = if (sat < 0.35) 0.26f else 0.1f,
                exposure = if (b < 100) 0.1f else 0f,
                highlights = -0.12f, shadows = 0.16f,
                sharpen = 0.42f, vignette = 0.1f,
                dehaze = if (b > 140) 0.12f else 0f, clarityBoost = 0.22f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.82f)

            SceneType.NIGHT     -> FilterParameters(
                brightness = 0.38f, contrast = 0.14f, saturation = -0.1f, exposure = 0.48f,
                warmth = 0.05f, highlights = -0.24f, shadows = 0.34f,
                grain = 0.04f, sharpen = 0.15f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.92f)

            SceneType.LOW_LIGHT -> FilterParameters(
                brightness = 0.24f, contrast = 0.1f, exposure = 0.3f,
                shadows = 0.24f, highlights = -0.14f,
                saturation = if (sat < 0.2) 0.1f else 0f, grain = 0.03f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.86f)

            SceneType.BACKLIT   -> FilterParameters(
                brightness = 0.22f, contrast = 0.15f, exposure = 0.22f,
                highlights = -0.32f, shadows = 0.32f, saturation = 0.1f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.89f)

            SceneType.SUNSET    -> FilterParameters(
                brightness = 0.05f, contrast = 0.2f, saturation = 0.22f,
                warmth = 0.28f, highlights = -0.1f, shadows = 0.1f, vignette = 0.15f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.84f)

            SceneType.FOOD      -> FilterParameters(
                brightness = 0.1f, contrast = 0.16f, saturation = 0.32f,
                warmth = 0.14f, sharpen = 0.38f, highlights = -0.05f, clarityBoost = 0.15f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.78f)

            SceneType.TEXT      -> FilterParameters(
                contrast = 0.32f, sharpen = 0.62f,
                brightness = if (b < 100) 0.14f else 0f, saturation = -0.5f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.74f)

            SceneType.MACRO     -> FilterParameters(
                contrast = 0.22f, sharpen = 0.55f, saturation = 0.16f,
                brightness = 0.05f, vignette = 0.15f, clarityBoost = 0.3f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.76f)

            SceneType.INDOOR    -> FilterParameters(
                brightness = if (b < 100) 0.14f else 0f, contrast = 0.1f,
                warmth = if (w < 0) 0.14f else 0.05f,
                saturation = if (sat < 0.25) 0.1f else 0f, shadows = 0.1f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.76f)

            SceneType.OUTDOOR   -> FilterParameters(
                contrast = 0.16f, saturation = if (sat < 0.3) 0.14f else 0f,
                highlights = -0.1f, sharpen = 0.26f, clarityBoost = 0.1f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.80f)

            SceneType.ACTION    -> FilterParameters(
                contrast = 0.26f, sharpen = 0.52f, saturation = 0.1f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.72f)

            SceneType.AUTO      -> FilterParameters(
                brightness = if (b < 80) 0.14f else if (b > 180) -0.05f else 0f,
                contrast   = if (c < 80) 0.1f else 0f,
                saturation = if (sat < 0.2) 0.1f else 0f,
                isAutoEnabled = true, detectedScene = scene, confidence = 0.5f)
        }
    }

    // ── ImageProxy → Bitmap ───────────────────────────────────────────────────

    private fun ImageProxy.toBitmap(): Bitmap? = try {
        val buf   = planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val yuv   = YuvImage(bytes, ImageFormat.NV21, width, height, null)
        val out   = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 45, out)
        android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    } catch (e: Exception) { Timber.e(e, "ImageProxy→Bitmap failed"); null }

    // ── Data ──────────────────────────────────────────────────────────────────

    data class ImageStats(
        val avgBrightness: Double,
        val avgSaturation: Double,
        val avgWarmth: Double,
        val contrast: Double,
        val skinRatio: Double,
        val darkRatio: Double,
        val brightRatio: Double,
        val exposureBalance: Double,
        val minBrightness: Double,
        val maxBrightness: Double,
        val hfEnergyRatio: Double = 0.0
    )
}
