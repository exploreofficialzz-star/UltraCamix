package com.chastechgroup.camix.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.chastechgroup.camix.audio.AudioProcessor
import com.chastechgroup.camix.camera.UltraCameraManager
import com.chastechgroup.camix.filter.FilterParameters
import com.chastechgroup.camix.filter.FilterPreset
import com.chastechgroup.camix.filter.SceneType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()

    val cameraManager = UltraCameraManager(ctx)
    val audioProcessor = AudioProcessor(ctx)

    // ── Derived flows from managers ────────────────────────────────────────────

    val cameraState  = cameraManager.cameraState
    val detectedScene: StateFlow<SceneType> = cameraManager.detectedScene
    val filterParams: StateFlow<FilterParameters> = cameraManager.filterParams
    val isRecording  = cameraManager.isRecording
    val flashMode    = cameraManager.flashMode
    val zoomLevel    = cameraManager.zoomLevel
    val aeAfLocked   = cameraManager.aeAfLocked
    val isTimelapse  = cameraManager.isTimelapse
    val timelapseCaptured = cameraManager.timelapseCaptured

    // Audio metrics
    val rmsLevel     = audioProcessor.rmsLevel
    val peakLevel    = audioProcessor.peakLevel
    val waveform     = audioProcessor.waveform
    val noiseFloorDb = audioProcessor.noiseFloorDb

    // ── UI State ──────────────────────────────────────────────────────────────

    private val _currentMode         = MutableStateFlow(CaptureMode.PHOTO)
    val currentMode: StateFlow<CaptureMode> = _currentMode.asStateFlow()

    private val _currentFilter       = MutableStateFlow(FilterPreset.NONE)
    val currentFilter: StateFlow<FilterPreset> = _currentFilter.asStateFlow()

    private val _isFilterPanelOpen   = MutableStateFlow(false)
    val isFilterPanelOpen: StateFlow<Boolean> = _isFilterPanelOpen.asStateFlow()

    private val _isAdjustmentOpen    = MutableStateFlow(false)
    val isAdjustmentPanelOpen: StateFlow<Boolean> = _isAdjustmentOpen.asStateFlow()

    private val _isAutoEnhance       = MutableStateFlow(true)
    val isAutoEnhanceEnabled: StateFlow<Boolean> = _isAutoEnhance.asStateFlow()

    private val _isGridEnabled       = MutableStateFlow(false)
    val isGridEnabled: StateFlow<Boolean> = _isGridEnabled.asStateFlow()

    private val _isHistogramEnabled  = MutableStateFlow(false)
    val isHistogramEnabled: StateFlow<Boolean> = _isHistogramEnabled.asStateFlow()

    private val _isAudioVisualizerOn = MutableStateFlow(false)
    val isAudioVisualizerOn: StateFlow<Boolean> = _isAudioVisualizerOn.asStateFlow()

    private val _manualParams        = MutableStateFlow(FilterParameters.DEFAULT)
    val manualParams: StateFlow<FilterParameters> = _manualParams.asStateFlow()

    private val _timelapsInterval    = MutableStateFlow(2000L)
    val timelapseInterval: StateFlow<Long> = _timelapsInterval.asStateFlow()

    private val _capturedMedia       = MutableStateFlow<List<Uri>>(emptyList())
    val capturedMedia: StateFlow<List<Uri>> = _capturedMedia.asStateFlow()

    private val _message             = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _proExposureIndex    = MutableStateFlow(0)
    val proExposureIndex: StateFlow<Int> = _proExposureIndex.asStateFlow()

    // Output directory
    private val outputDirectory: File by lazy {
        File(ctx.getExternalFilesDir(null), "CamixUltra").apply { if (!exists()) mkdirs() }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        // Feed audio noise floor back into camera for low-light boost
        viewModelScope.launch {
            audioProcessor.rmsLevel.collect { rms ->
                cameraManager.updateAudioLevel(rms)
            }
        }
        // Auto-start audio processing
        audioProcessor.setupAudioProcessing()
        audioProcessor.startProcessing()
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    fun startCamera(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        cameraManager.startCamera(owner, surface) { err ->
            _message.value = "Camera error: ${err.message}"
        }
    }

    fun switchCamera(owner: LifecycleOwner, surface: Preview.SurfaceProvider) =
        cameraManager.switchCamera(owner, surface)

    fun toggleFlash() = cameraManager.toggleFlash()

    fun setZoom(zoom: Float)  = cameraManager.setZoom(zoom)
    fun pinchZoom(scale: Float) = cameraManager.pinchZoom(scale)

    fun tapToFocus(x: Float, y: Float) {
        if (aeAfLocked.value) return
        cameraManager.tapToFocus(x, y)
    }

    fun lockAeAf(x: Float, y: Float) = cameraManager.lockAeAf(x, y)
    fun unlockAeAf() = cameraManager.unlockAeAf()

    fun setProExposure(index: Int) {
        _proExposureIndex.value = index
        cameraManager.setExposureCompensation(index)
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    fun capturePhoto() {
        cameraManager.capturePhoto(outputDirectory,
            onSaved = { _message.value = "Photo saved ✓"; loadMedia() },
            onError = { _message.value = "Capture failed: ${it.message}" }
        )
    }

    fun toggleRecording() {
        if (isRecording.value) {
            cameraManager.stopRecording()
        } else {
            cameraManager.startRecording(outputDirectory,
                onStarted  = { _message.value = "● Recording" },
                onFinished = { _message.value = "Video saved ✓"; loadMedia() },
                onError    = { _message.value = "Recording error: ${it.message}" }
            )
        }
    }

    // ── Time-lapse ────────────────────────────────────────────────────────────

    fun startTimelapse() {
        cameraManager.startTimelapse(outputDirectory, _timelapsInterval.value,
            onFrameCaptured = { n -> _message.value = "Time-lapse frame $n" },
            onError         = { _message.value = "Timelapse error: ${it.message}" }
        )
    }

    fun stopTimelapse() {
        cameraManager.stopTimelapse()
        _message.value = "Time-lapse saved (${timelapseCaptured.value} frames)"
        loadMedia()
    }

    fun setTimelapseInterval(ms: Long) { _timelapsInterval.value = ms }

    // ── Filters ───────────────────────────────────────────────────────────────

    fun applyFilterPreset(preset: FilterPreset) {
        _currentFilter.value = preset
        val params = preset.toParameters()
        _manualParams.value  = params
        cameraManager.updateFilterParameters(params)
        if (preset != FilterPreset.AUTO) _isAutoEnhance.value = false
    }

    fun updateManualParameter(updater: (FilterParameters) -> FilterParameters) {
        val newParams = updater(_manualParams.value)
        _manualParams.value = newParams
        cameraManager.updateFilterParameters(newParams)
        _isAutoEnhance.value = false
    }

    fun toggleAutoEnhance() {
        _isAutoEnhance.value = !_isAutoEnhance.value
        cameraManager.setAutoEnhance(_isAutoEnhance.value)
        if (_isAutoEnhance.value) _currentFilter.value = FilterPreset.AUTO
    }

    // ── UI toggles ────────────────────────────────────────────────────────────

    fun toggleFilterPanel()     { _isFilterPanelOpen.value = !_isFilterPanelOpen.value; _isAdjustmentOpen.value = false }
    fun toggleAdjustmentPanel() { _isAdjustmentOpen.value  = !_isAdjustmentOpen.value;  _isFilterPanelOpen.value = false }
    fun toggleGrid()            { _isGridEnabled.value = !_isGridEnabled.value }
    fun toggleHistogram()       { _isHistogramEnabled.value = !_isHistogramEnabled.value }
    fun toggleAudioVisualizer() { _isAudioVisualizerOn.value = !_isAudioVisualizerOn.value }

    fun setCaptureMode(mode: CaptureMode) {
        _currentMode.value = mode
        // Auto-apply scene-appropriate preset on mode switch
        when (mode) {
            CaptureMode.PORTRAIT  -> applyFilterPreset(FilterPreset.PORTRAIT)
            CaptureMode.NIGHT     -> applyFilterPreset(FilterPreset.NIGHT)
            CaptureMode.CINEMATIC -> applyFilterPreset(FilterPreset.CINEMATIC)
            CaptureMode.THERMAL   -> applyFilterPreset(FilterPreset.THERMAL)
            CaptureMode.PRO       -> { /* user controls everything */ }
            else                  -> if (_isAutoEnhance.value) applyFilterPreset(FilterPreset.AUTO)
        }
    }

    fun clearMessage() { _message.value = null }

    // ── Audio config ──────────────────────────────────────────────────────────

    fun configureAudio(
        noiseSuppression: Boolean = true,
        echoCancellation: Boolean = true,
        autoGain: Boolean         = true,
        windReduction: Boolean    = true,
        clarity: Float            = 0.55f
    ) {
        audioProcessor.configure(noiseSuppression, echoCancellation, autoGain, windReduction, clarity)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun loadMedia() {
        viewModelScope.launch {
            val files = outputDirectory.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            _capturedMedia.value = files.take(50).map { Uri.fromFile(it) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioProcessor.release()
        cameraManager.release()
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    enum class CaptureMode {
        PHOTO, VIDEO, PORTRAIT, NIGHT, PRO, CINEMATIC, THERMAL, TIMELAPSE
    }
}
