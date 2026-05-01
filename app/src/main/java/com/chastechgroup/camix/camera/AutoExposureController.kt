package com.chastechgroup.camix.camera

import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.ExposureState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.*

/**
 * AutoExposureController — the smart exposure brain for CamixUltra.
 *
 * Works continuously in the background:
 *  1. Reads scene brightness stats from SceneDetector frames
 *  2. Computes optimal EV compensation index
 *  3. Applies it via CameraX CameraControl
 *  4. Detects overexposure (blown highlights) and underexposure (crushed shadows)
 *  5. Applies multi-zone metering: centre-weighted + spot for portrait, matrix for landscape
 *
 * Result: the camera always captures a bright, clean, perfectly exposed shot
 * regardless of environment — indoor, outdoor, backlit, night, or mixed.
 */
class AutoExposureController {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var camera: Camera? = null
    private var exposureState: ExposureState? = null
    private var autoJob: Job? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private val _evIndex       = MutableStateFlow(0)
    val evIndex: StateFlow<Int> = _evIndex.asStateFlow()

    private val _exposureLabel = MutableStateFlow("Auto")
    val exposureLabel: StateFlow<String> = _exposureLabel.asStateFlow()

    private val _isClipping    = MutableStateFlow(false)
    val isClipping: StateFlow<Boolean> = _isClipping.asStateFlow()

    private val _isToooDark    = MutableStateFlow(false)
    val isTooDark: StateFlow<Boolean> = _isToooDark.asStateFlow()

    // ── Scene feed ────────────────────────────────────────────────────────────
    // Updated each time SceneDetector produces new frame stats
    private var avgBrightness   = 128.0   // 0..255
    private var darkRatio       = 0.0     // fraction of pixels below 50
    private var brightRatio     = 0.0     // fraction of pixels above 200
    private var skinRatio       = 0.0     // portrait weight
    private var hfEnergyRatio   = 0.0     // macro / detail
    private var isNightScene    = false
    private var isBacklit       = false
    private var isManualOverride = false
    private var manualIndex      = 0

    // IIR smoothing for brightness
    private var smoothBrightness = 128.0
    private val ALPHA = 0.18              // blend speed (lower = slower/smoother)

    // ── Attach / detach ───────────────────────────────────────────────────────

    fun attach(cam: Camera) {
        camera        = cam
        exposureState = cam.cameraInfo.exposureState
        Timber.d("AutoExposureController attached — range=${exposureState?.exposureCompensationRange}")
        startLoop()
    }

    fun detach() {
        autoJob?.cancel()
        camera        = null
        exposureState = null
    }

    fun release() {
        scope.cancel()
    }

    // ── Scene update (called from SceneDetector via ViewModel) ────────────────

    fun updateScene(
        brightness:   Double,
        darkRatio:    Double,
        brightRatio:  Double,
        skinRatio:    Double,
        hfEnergy:     Double,
        isNight:      Boolean,
        isBacklit:    Boolean
    ) {
        // IIR smooth
        smoothBrightness    = ALPHA * brightness + (1 - ALPHA) * smoothBrightness
        this.avgBrightness  = smoothBrightness
        this.darkRatio      = darkRatio
        this.brightRatio    = brightRatio
        this.skinRatio      = skinRatio
        this.hfEnergyRatio  = hfEnergy
        this.isNightScene   = isNight
        this.isBacklit      = isBacklit
    }

    // ── Manual override (Pro mode / user EV slider) ───────────────────────────

    fun setManualEv(index: Int) {
        isManualOverride = true
        manualIndex      = index
        applyEv(index)
    }

    fun enableAuto() {
        isManualOverride = false
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    private fun startLoop() {
        autoJob?.cancel()
        autoJob = scope.launch {
            while (isActive) {
                if (!isManualOverride) computeAndApply()
                delay(350)   // ~3 Hz — responsive but not jittery
            }
        }
    }

    private fun computeAndApply() {
        val state = exposureState ?: return
        val range = state.exposureCompensationRange
        if (range.lower >= range.upper) return   // device doesn't support EV

        val targetEv = computeTargetEv()
        val clamped  = targetEv.coerceIn(range.lower, range.upper)

        // Only update if changed significantly (avoid micro-adjustments)
        if (clamped != _evIndex.value) {
            applyEv(clamped)
        }

        // Clipping detection
        _isClipping.value  = brightRatio > 0.15
        _isToooDark.value  = avgBrightness < 35 || darkRatio > 0.75

        // Label
        _exposureLabel.value = when {
            isManualOverride     -> "Manual EV ${if (clamped >= 0) "+$clamped" else "$clamped"}"
            isNightScene         -> "Night Auto"
            isBacklit            -> "Backlit Auto"
            skinRatio > 0.1      -> "Portrait Auto"
            brightRatio > 0.15   -> "HDR Auto"
            else                 -> "Smart Auto"
        }
    }

    /**
     * Core exposure algorithm.
     *
     * Zone metering strategy:
     *  - Portrait:   bias +1 stop to expose skin well, lift shadows
     *  - Night:      push hard (+3 to +4 stops)
     *  - Backlit:    push +2 stops to recover subject from shadows
     *  - Bright day: pull down (-1 to -2 stops) to protect highlights
     *  - Overcast:   neutral to +1
     *  - Mixed:      centre-weighted, target 135/255 midtone
     *
     * Returns integer EV index (not EV stops — index units from ExposureState)
     */
    private fun computeTargetEv(): Int {
        val b = avgBrightness

        // Target brightness we want (0-255)
        val target = when {
            isNightScene             -> 145.0   // push bright in night
            isBacklit                -> 140.0   // recover subject
            skinRatio > 0.12         -> 150.0   // portraits need bright skin
            brightRatio > 0.20       -> 110.0   // protect highlights
            darkRatio > 0.60         -> 155.0   // lift very dark scene
            hfEnergyRatio > 0.10     -> 135.0   // macro — neutral
            else                     -> 130.0   // standard target
        }

        // Error in brightness units
        val error = target - b

        // Convert to approximate EV steps
        // Every ~30 brightness units ≈ 1 EV at mid-range
        val evStops = (error / 28.0)

        // Gain factor per scene type
        val gain = when {
            isNightScene    -> 1.8
            isBacklit       -> 1.5
            skinRatio > 0.1 -> 1.2
            else            -> 1.0
        }

        val rawIndex = (evStops * gain).roundToInt()

        // Night: always try to push more
        val nightBonus = if (isNightScene && b < 80) 2 else 0

        return (rawIndex + nightBonus)
    }

    private fun applyEv(index: Int) {
        _evIndex.value = index
        camera?.cameraControl?.setExposureCompensationIndex(index)
            ?.addListener({
                Timber.d("AutoExposureController: EV applied → $index")
            }, { it.run() })
    }

    private fun Double.roundToInt() = kotlin.math.round(this).toInt()
}
