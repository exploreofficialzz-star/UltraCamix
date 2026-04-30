package com.chastechgroup.camix.filter

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Complete filter parameter set for CamixUltra.
 * Merges both packages and adds: Cinematic, Thermal, Ultrasound,
 * Focus Peaking, Clarity, Dehaze, and audio-reactive metadata.
 */
@Parcelize
data class FilterParameters(
    // Core
    val brightness: Float  = 0f,
    val contrast: Float    = 0f,
    val saturation: Float  = 0f,
    val exposure: Float    = 0f,

    // Color
    val warmth: Float      = 0f,   // -1 cool … 1 warm
    val tint: Float        = 0f,   // -1 green … 1 magenta
    val hue: Float         = 0f,   // -180 … 180 deg

    // Tone
    val highlights: Float  = 0f,
    val shadows: Float     = 0f,
    val fade: Float        = 0f,

    // Standard FX
    val vignette: Float    = 0f,
    val sharpen: Float     = 0f,
    val grain: Float       = 0f,
    val blur: Float        = 0f,

    // New FX
    val clarityBoost: Float = 0f,   // micro-contrast 0..1
    val dehaze: Float       = 0f,   // atmospheric dehaze 0..1

    // Cinematic mode
    val cinematicIntensity: Float = 0f,   // 0..1

    // Thermal mode
    val thermalIntensity: Float   = 0f,   // 0..1
    val thermalContrast: Float    = 0.3f,

    // Ultrasound / edge
    val ultrasoundEdge: Float     = 0f,   // 0 = off; >0 = edge strength
    val ultrasoundBrightness: Float = 0f,

    // Focus peaking
    val focusPeakingEnabled: Boolean = false,
    val focusPeakingThreshold: Float = 0.5f,
    val focusPeakingColor: Float     = 0f,   // 0=red, 0.5=white, 1=yellow

    // AI / scene meta
    val isAutoEnabled: Boolean  = true,
    val detectedScene: SceneType = SceneType.AUTO,
    val confidence: Float       = 0f
) : Parcelable {

    companion object {
        val DEFAULT = FilterParameters()

        // ── Standard presets ───────────────────────────────────────────────
        val PORTRAIT = FilterParameters(
            brightness = 0.08f, contrast = 0.15f, saturation = 0.1f,
            warmth = 0.12f, highlights = 0.08f, shadows = 0.12f,
            vignette = 0.2f, sharpen = 0.3f, clarityBoost = 0.1f
        )
        val LANDSCAPE = FilterParameters(
            contrast = 0.22f, saturation = 0.28f, exposure = 0.05f,
            highlights = -0.12f, shadows = 0.18f,
            sharpen = 0.45f, vignette = 0.1f,
            dehaze = 0.15f, clarityBoost = 0.25f
        )
        val NIGHT = FilterParameters(
            brightness = 0.35f, contrast = 0.12f, saturation = -0.08f,
            exposure = 0.45f, warmth = 0.05f,
            highlights = -0.22f, shadows = 0.32f, grain = 0.04f, sharpen = 0.15f
        )
        val VIVID = FilterParameters(
            contrast = 0.18f, saturation = 0.38f, exposure = 0.05f,
            sharpen = 0.3f, highlights = -0.05f, clarityBoost = 0.2f
        )
        val WARM = FilterParameters(
            warmth = 0.42f, saturation = 0.12f, tint = 0.05f, contrast = 0.05f
        )
        val COOL = FilterParameters(
            warmth = -0.3f, saturation = 0.06f, tint = -0.05f, contrast = 0.1f
        )
        val DRAMATIC = FilterParameters(
            contrast = 0.42f, saturation = -0.1f, vignette = 0.45f,
            shadows = 0.22f, highlights = -0.18f, sharpen = 0.55f, clarityBoost = 0.3f
        )
        val FADE = FilterParameters(
            fade = 0.42f, contrast = -0.15f, saturation = -0.22f,
            highlights = 0.1f, grain = 0.1f
        )
        val MONO = FilterParameters(saturation = -1f, contrast = 0.22f, grain = 0.05f)
        val SEPIA = FilterParameters(
            saturation = -0.5f, warmth = 0.62f, contrast = 0.1f, fade = 0.15f, tint = 0.1f
        )

        // ── Ultra-new presets ──────────────────────────────────────────────
        val CINEMATIC = FilterParameters(
            cinematicIntensity = 0.85f, grain = 0.12f,
            saturation = -0.05f, clarityBoost = 0.15f
        )
        val THERMAL = FilterParameters(
            thermalIntensity = 1f, thermalContrast = 0.4f
        )
        val ULTRASOUND = FilterParameters(
            ultrasoundEdge = 1.8f, ultrasoundBrightness = 0.1f
        )
        val FOCUS_PEAK = FilterParameters(
            focusPeakingEnabled = true, focusPeakingThreshold = 0.5f, focusPeakingColor = 0f
        )
        val DEHAZE = FilterParameters(
            dehaze = 0.5f, contrast = 0.1f, saturation = 0.1f, sharpen = 0.2f
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Scene types
// ──────────────────────────────────────────────────────────────────────────────
enum class SceneType {
    AUTO, PORTRAIT, LANDSCAPE, NIGHT, LOW_LIGHT, BACKLIT,
    TEXT, FOOD, SUNSET, INDOOR, OUTDOOR, MACRO, ACTION
}

// ──────────────────────────────────────────────────────────────────────────────
// Named presets (drives FilterPanel chips)
// ──────────────────────────────────────────────────────────────────────────────
enum class FilterPreset(val displayName: String) {
    NONE("Original"),
    AUTO("AI Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    NIGHT("Night"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    DRAMATIC("Dramatic"),
    FADE("Fade"),
    MONO("Mono"),
    SEPIA("Sepia"),
    CINEMATIC("Cinematic"),
    THERMAL("Thermal"),
    ULTRASOUND("Ultrasound"),
    FOCUS_PEAK("Focus Peak"),
    DEHAZE("Dehaze");

    fun toParameters(): FilterParameters = when (this) {
        NONE       -> FilterParameters.DEFAULT
        AUTO       -> FilterParameters.DEFAULT.copy(isAutoEnabled = true)
        PORTRAIT   -> FilterParameters.PORTRAIT
        LANDSCAPE  -> FilterParameters.LANDSCAPE
        NIGHT      -> FilterParameters.NIGHT
        VIVID      -> FilterParameters.VIVID
        WARM       -> FilterParameters.WARM
        COOL       -> FilterParameters.COOL
        DRAMATIC   -> FilterParameters.DRAMATIC
        FADE       -> FilterParameters.FADE
        MONO       -> FilterParameters.MONO
        SEPIA      -> FilterParameters.SEPIA
        CINEMATIC  -> FilterParameters.CINEMATIC
        THERMAL    -> FilterParameters.THERMAL
        ULTRASOUND -> FilterParameters.ULTRASOUND
        FOCUS_PEAK -> FilterParameters.FOCUS_PEAK
        DEHAZE     -> FilterParameters.DEHAZE
    }
}
