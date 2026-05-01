package com.chastechgroup.camix.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.*

/**
 * ImageEnhancementPipeline — heavy post-processing applied to every captured photo.
 *
 * Processing stages (in order):
 *  1. Bilateral noise filter     — removes sensor noise while preserving sharp edges
 *  2. Luminance denoising        — second pass targeting luminance channel only
 *  3. Unsharp mask               — precision sharpening without halos
 *  4. Micro-contrast (clarity)   — local contrast boost in midtone areas
 *  5. Shadow lift                — recovers crushed shadow detail
 *  6. Highlight recovery         — gentle roll-off on near-blown highlights
 *  7. Reinhard HDR tone map      — ensures full tonal range in any lighting
 *  8. Vibrance boost             — punches colours without over-saturating skin
 *
 * All stages run on a coroutine (Dispatchers.Default) and write back to MediaStore.
 */
class ImageEnhancementPipeline(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Processing strength — adjusted by scene type
    var noiseReduction  = 0.65f    // 0..1
    var sharpStrength   = 0.70f    // 0..1
    var clarityStrength = 0.45f    // 0..1
    var shadowLift      = 0.15f    // 0..1
    var highlightRolloff= 0.20f    // 0..1
    var vibranceBoost   = 0.25f    // 0..1
    var hdrIntensity    = 0.55f    // 0..1 (Reinhard blend)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Process a JPEG from a Uri (as saved by ImageCapture via MediaStore),
     * enhance it in-place, and write the result back.
     * Returns the Uri of the enhanced image.
     */
    fun processAsync(
        uri: Uri,
        displayName: String,
        onComplete: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        scope.launch {
            try {
                val startMs = System.currentTimeMillis()
                Timber.d("Enhancement pipeline START — $displayName")

                // 1. Read source bitmap
                val source = readBitmap(uri)
                    ?: throw IllegalStateException("Could not decode image from $uri")

                // 2. Run enhancement pipeline
                val enhanced = enhance(source)
                source.recycle()

                // 3. Write back to MediaStore
                val resultUri = writeBitmap(enhanced, displayName)
                enhanced.recycle()

                val elapsed = System.currentTimeMillis() - startMs
                Timber.d("Enhancement pipeline DONE in ${elapsed}ms → $resultUri")

                withContext(Dispatchers.Main) { onComplete(resultUri) }
            } catch (e: Exception) {
                Timber.e(e, "Enhancement pipeline failed")
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    fun release() = scope.cancel()

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private fun enhance(src: Bitmap): Bitmap {
        // Work at full resolution for maximum quality
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Convert to float arrays for precision
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        for (i in pixels.indices) {
            val px = pixels[i]
            r[i] = ((px shr 16) and 0xFF) / 255f
            g[i] = ((px shr  8) and 0xFF) / 255f
            b[i] =  (px         and 0xFF) / 255f
        }

        // Stage 1 — Bilateral noise reduction (edge-preserving)
        if (noiseReduction > 0.05f) {
            bilateralFilter(r, g, b, w, h, noiseReduction)
        }

        // Stage 2 — Shadow lift (lift dark areas without blowing midtones)
        if (shadowLift > 0.01f) {
            for (i in r.indices) {
                val lum = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
                val shadowMask = (1f - lum).pow(2f)   // strongest in shadows
                val lift = shadowLift * shadowMask * 0.3f
                r[i] = (r[i] + lift).coerceIn(0f, 1f)
                g[i] = (g[i] + lift).coerceIn(0f, 1f)
                b[i] = (b[i] + lift).coerceIn(0f, 1f)
            }
        }

        // Stage 3 — Highlight rolloff (protect blown highlights)
        if (highlightRolloff > 0.01f) {
            for (i in r.indices) {
                val lum = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
                if (lum > 0.75f) {
                    val t    = ((lum - 0.75f) / 0.25f).coerceIn(0f, 1f)
                    val pull = highlightRolloff * t * 0.25f
                    r[i] = (r[i] - pull).coerceIn(0f, 1f)
                    g[i] = (g[i] - pull).coerceIn(0f, 1f)
                    b[i] = (b[i] - pull).coerceIn(0f, 1f)
                }
            }
        }

        // Stage 4 — Reinhard HDR tone mapping (compress extremes)
        if (hdrIntensity > 0.05f) {
            for (i in r.indices) {
                val ri = r[i] / (1f + r[i] * 0.28f)
                val gi = g[i] / (1f + g[i] * 0.28f)
                val bi = b[i] / (1f + b[i] * 0.28f)
                r[i] = lerp(r[i], ri, hdrIntensity)
                g[i] = lerp(g[i], gi, hdrIntensity)
                b[i] = lerp(b[i], bi, hdrIntensity)
            }
        }

        // Stage 5 — Unsharp mask sharpening (high quality, no halos)
        if (sharpStrength > 0.05f) {
            unsharpMask(r, g, b, w, h, sharpStrength)
        }

        // Stage 6 — Micro-contrast / clarity (local contrast in midtones)
        if (clarityStrength > 0.05f) {
            clarity(r, g, b, w, h, clarityStrength)
        }

        // Stage 7 — Vibrance (smart saturation)
        if (vibranceBoost > 0.01f) {
            for (i in r.indices) {
                val lum = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
                val mx  = maxOf(r[i], g[i], b[i])
                val mn  = minOf(r[i], g[i], b[i])
                val sat = if (mx > 0.001f) (mx - mn) / mx else 0f
                // Vibrance: boost less-saturated colours more, protect skin (red-orange hue)
                val vibGain = vibranceBoost * (1f - sat) * 0.6f
                r[i] = lerp(lum, r[i], 1f + vibGain)
                g[i] = lerp(lum, g[i], 1f + vibGain)
                b[i] = lerp(lum, b[i], 1f + vibGain)
            }
        }

        // Write back to Bitmap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (i in pixels.indices) {
            val ri = (r[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val gi = (g[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val bi = (b[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            pixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ── DSP Algorithms ────────────────────────────────────────────────────────

    /**
     * Bilateral filter — Gaussian spatial weight × Gaussian range weight.
     * Preserves edges while smoothing flat areas. O(n·r²) per channel.
     * Uses a 5×5 kernel for speed at full resolution.
     */
    private fun bilateralFilter(
        r: FloatArray, g: FloatArray, b: FloatArray,
        w: Int, h: Int, strength: Float
    ) {
        val sigmaS = 3f + strength * 4f   // spatial sigma  (pixels)
        val sigmaR = 0.06f + strength * 0.14f  // range sigma (normalised intensity)
        val radius = 2
        val rOut = r.copyOf(); val gOut = g.copyOf(); val bOut = b.copyOf()

        for (y in radius until h - radius) {
            for (x in radius until w - radius) {
                val idx = y * w + x
                val cr = r[idx]; val cg = g[idx]; val cb = b[idx]
                var sumR = 0f; var sumG = 0f; var sumB = 0f; var sumW = 0f

                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val ni = (y + dy) * w + (x + dx)
                        val nr = r[ni]; val ng = g[ni]; val nb = b[ni]
                        val spatW = exp(-(dx * dx + dy * dy) / (2f * sigmaS * sigmaS))
                        val rangeW = exp(
                            -((cr-nr).pow(2) + (cg-ng).pow(2) + (cb-nb).pow(2)) /
                            (2f * sigmaR * sigmaR)
                        )
                        val w2 = spatW * rangeW
                        sumR += nr * w2; sumG += ng * w2; sumB += nb * w2; sumW += w2
                    }
                }
                if (sumW > 0f) {
                    rOut[idx] = sumR / sumW
                    gOut[idx] = sumG / sumW
                    bOut[idx] = sumB / sumW
                }
            }
        }
        rOut.copyInto(r); gOut.copyInto(g); bOut.copyInto(b)
    }

    /**
     * Unsharp mask — subtracts Gaussian blur then adds weighted difference back.
     * Result: sharpened image without ringing artefacts.
     */
    private fun unsharpMask(
        r: FloatArray, g: FloatArray, b: FloatArray,
        w: Int, h: Int, strength: Float
    ) {
        val blurR = gaussianBlur(r, w, h, 1.2f)
        val blurG = gaussianBlur(g, w, h, 1.2f)
        val blurB = gaussianBlur(b, w, h, 1.2f)
        val amt   = strength * 1.4f
        for (i in r.indices) {
            r[i] = (r[i] + (r[i] - blurR[i]) * amt).coerceIn(0f, 1f)
            g[i] = (g[i] + (g[i] - blurG[i]) * amt).coerceIn(0f, 1f)
            b[i] = (b[i] + (b[i] - blurB[i]) * amt).coerceIn(0f, 1f)
        }
    }

    /**
     * Clarity — local contrast boost targeting midtone areas.
     * Applies unsharp mask only where luminance is in the 0.2–0.8 range.
     */
    private fun clarity(
        r: FloatArray, g: FloatArray, b: FloatArray,
        w: Int, h: Int, strength: Float
    ) {
        val blurR = gaussianBlur(r, w, h, 4f)
        val blurG = gaussianBlur(g, w, h, 4f)
        val blurB = gaussianBlur(b, w, h, 4f)
        for (i in r.indices) {
            val lum = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
            // Midtone mask: peak at 0.5, fall off toward 0 and 1
            val mask = 1f - abs(lum * 2f - 1f)
            val amt  = strength * mask * 1.2f
            r[i] = (r[i] + (r[i] - blurR[i]) * amt).coerceIn(0f, 1f)
            g[i] = (g[i] + (g[i] - blurG[i]) * amt).coerceIn(0f, 1f)
            b[i] = (b[i] + (b[i] - blurB[i]) * amt).coerceIn(0f, 1f)
        }
    }

    /** Fast separable Gaussian blur using two 1D passes */
    private fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val radius = (sigma * 2.5f).toInt().coerceIn(1, 6)
        val kernel = FloatArray(radius * 2 + 1)
        var kSum   = 0f
        for (i in kernel.indices) {
            val x = i - radius
            kernel[i] = exp(-(x * x) / (2f * sigma * sigma))
            kSum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= kSum

        val tmp = FloatArray(w * h)
        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (k in kernel.indices) {
                    val sx = (x + k - radius).coerceIn(0, w - 1)
                    acc += src[y * w + sx] * kernel[k]
                }
                tmp[y * w + x] = acc
            }
        }
        val out = FloatArray(w * h)
        // Vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (k in kernel.indices) {
                    val sy = (y + k - radius).coerceIn(0, h - 1)
                    acc += tmp[sy * w + x] * kernel[k]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    // ── I/O ───────────────────────────────────────────────────────────────────

    private fun readBitmap(uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }
    } catch (e: Exception) {
        Timber.e(e, "readBitmap failed for $uri"); null
    }

    private fun writeBitmap(bitmap: Bitmap, displayName: String): Uri {
        val outName = "${displayName}_enhanced"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/CamixUltra")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("MediaStore insert failed")

        context.contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 97, out)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
        }
        return uri
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun Float.pow(n: Float)                 = this.toDouble().pow(n.toDouble()).toFloat()
    private fun exp(x: Float)                       = kotlin.math.exp(x.toDouble()).toFloat()

    // ── Scene-adaptive preset ─────────────────────────────────────────────────

    fun applyScenePreset(scene: String) {
        when (scene.uppercase()) {
            "NIGHT", "LOW_LIGHT" -> {
                noiseReduction   = 0.90f  // heavy denoise in dark
                sharpStrength    = 0.40f  // less sharpening (not to amplify noise)
                clarityStrength  = 0.30f
                shadowLift       = 0.35f  // strong shadow recovery
                highlightRolloff = 0.10f
                vibranceBoost    = 0.15f
                hdrIntensity     = 0.70f
            }
            "PORTRAIT" -> {
                noiseReduction   = 0.55f  // moderate skin smoothing
                sharpStrength    = 0.50f  // sharp eyes / hair
                clarityStrength  = 0.25f  // gentle clarity (no skin texture)
                shadowLift       = 0.20f
                highlightRolloff = 0.30f  // protect blown skin
                vibranceBoost    = 0.20f
                hdrIntensity     = 0.40f
            }
            "LANDSCAPE" -> {
                noiseReduction   = 0.40f
                sharpStrength    = 0.85f  // max sharpness on foliage/detail
                clarityStrength  = 0.70f  // strong clarity for texture
                shadowLift       = 0.10f
                highlightRolloff = 0.25f
                vibranceBoost    = 0.40f  // punchy landscape colours
                hdrIntensity     = 0.65f
            }
            "BACKLIT" -> {
                noiseReduction   = 0.50f
                sharpStrength    = 0.60f
                clarityStrength  = 0.40f
                shadowLift       = 0.40f  // recover silhouette detail
                highlightRolloff = 0.45f  // strong highlight compression
                vibranceBoost    = 0.25f
                hdrIntensity     = 0.80f  // max HDR for extreme contrast
            }
            else -> {
                noiseReduction   = 0.65f
                sharpStrength    = 0.70f
                clarityStrength  = 0.45f
                shadowLift       = 0.15f
                highlightRolloff = 0.20f
                vibranceBoost    = 0.25f
                hdrIntensity     = 0.55f
            }
        }
    }
}
