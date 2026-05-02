package com.chastechgroup.camix.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.camera.core.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.chastechgroup.camix.audio.AudioProcessor
import com.chastechgroup.camix.audio.SoundManager
import com.chastechgroup.camix.camera.UltraCameraManager
import com.chastechgroup.camix.filter.FilterParameters
import com.chastechgroup.camix.filter.FilterPreset
import com.chastechgroup.camix.filter.SceneType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()

    val cameraManager  = UltraCameraManager(ctx)
    val audioProcessor = AudioProcessor(ctx)
    val soundManager   = SoundManager(ctx)

    // ── Flows from managers ────────────────────────────────────────────────────
    val cameraState       = cameraManager.cameraState
    val detectedScene     = cameraManager.detectedScene
    val filterParams      = cameraManager.filterParams
    val isRecording       = cameraManager.isRecording
    val flashMode         = cameraManager.flashMode
    val zoomLevel         = cameraManager.zoomLevel
    val aeAfLocked        = cameraManager.aeAfLocked
    val isTimelapse       = cameraManager.isTimelapse
    val timelapseCaptured = cameraManager.timelapseCaptured
    val exposureLabel     = cameraManager.exposureLabel
    val isClipping        = cameraManager.isClipping
    val isProcessing      = cameraManager.isProcessing
    val processingStatus  = cameraManager.processingStatus
    val evIndex           = cameraManager.autoExposure.evIndex

    val rmsLevel     = audioProcessor.rmsLevel
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

    private val _timelapseInterval   = MutableStateFlow(2000L)
    val timelapseInterval: StateFlow<Long> = _timelapseInterval.asStateFlow()

    private val _message             = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _proExposureIndex    = MutableStateFlow(0)
    val proExposureIndex: StateFlow<Int> = _proExposureIndex.asStateFlow()

    private var cameraStarted = false

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        viewModelScope.launch {
            audioProcessor.rmsLevel.collect { rms ->
                cameraManager.updateAudioLevel(rms)
            }
        }
        audioProcessor.setupAudioProcessing()
        audioProcessor.startProcessing()
    }

    // ── Camera ────────────────────────────────────────────────────────────────
    fun startCamera(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        if (cameraStarted) return
        cameraStarted = true
        cameraManager.startCamera(owner, surface) { err ->
            _message.value = "Camera error: ${err.message}"
            cameraStarted = false
            Timber.e(err)
        }
    }

    fun switchCamera(owner: LifecycleOwner, surface: Preview.SurfaceProvider) {
        soundManager.playModeSwitch()
        cameraStarted = false; cameraStarted = true
        cameraManager.switchCamera(owner, surface)
    }

    fun setPreviewSize(w: Float, h: Float) = cameraManager.setPreviewSize(w, h)

    fun toggleFlash() {
        soundManager.playModeSwitch()
        cameraManager.toggleFlash()
    }

    fun setZoom(zoom: Float)    = cameraManager.setZoom(zoom)
    fun pinchZoom(scale: Float) = cameraManager.pinchZoom(scale)

    fun tapToFocus(x: Float, y: Float) {
        if (aeAfLocked.value) return
        soundManager.playFocusLock()
        cameraManager.tapToFocus(x, y)
    }

    fun lockAeAf(x: Float, y: Float) {
        soundManager.playAeAfLock()
        cameraManager.lockAeAf(x, y)
    }

    fun unlockAeAf() = cameraManager.unlockAeAf()

    fun setProExposure(index: Int) {
        _proExposureIndex.value = index
        cameraManager.setExposureCompensation(index)
    }

    // ── Capture ───────────────────────────────────────────────────────────────
    fun capturePhoto() {
        if (isProcessing.value) { _message.value = "Still processing last photo…"; return }
        soundManager.playShutter()
        cameraManager.capturePhoto(
            onSaved    = { _message.value = it },
            onProgress = { _message.value = it },
            onDone     = { soundManager.playPhotoSaved(); _message.value = it },
            onError    = { _message.value = "Capture failed: ${it.message}" }
        )
    }

    fun toggleRecording() {
        if (isRecording.value) {
            soundManager.playVideoStop()
            cameraManager.stopRecording()
        } else {
            soundManager.playVideoStart()
            cameraManager.startRecording(
                onStarted  = { _message.value = "● Recording to gallery" },
                onFinished = { _message.value = it },
                onError    = { _message.value = "Recording error: ${it.message}" }
            )
        }
    }

    fun pauseRecording()  = cameraManager.pauseRecording()
    fun resumeRecording() = cameraManager.resumeRecording()

    // ── Time-lapse ────────────────────────────────────────────────────────────
    fun startTimelapse() {
        soundManager.playVideoStart()
        cameraManager.startTimelapse(
            intervalMs       = _timelapseInterval.value,
            onFrameCaptured  = { n ->
                soundManager.playTimelapseFrame()
                _message.value = "⏱ Frame $n saved"
            },
            onError = { _message.value = "Timelapse error: ${it.message}" }
        )
    }

    fun stopTimelapse() {
        soundManager.playVideoStop()
        cameraManager.stopTimelapse()
        _message.value = "Timelapse done — ${timelapseCaptured.value} frames in gallery"
    }

    fun setTimelapseInterval(ms: Long) { _timelapseInterval.value = ms }

    // ── Filters ───────────────────────────────────────────────────────────────
    fun applyFilterPreset(preset: FilterPreset) {
        soundManager.playModeSwitch()
        _currentFilter.value = preset
        val params = preset.toParameters()
        _manualParams.value  = params
        cameraManager.updateFilterParameters(params)
        if (preset != FilterPreset.AUTO) _isAutoEnhance.value = false
    }

    fun updateManualParameter(updater: (FilterParameters) -> FilterParameters) {
        val new = updater(_manualParams.value)
        _manualParams.value = new
        cameraManager.updateFilterParameters(new)
        _isAutoEnhance.value = false
    }

    fun toggleAutoEnhance() {
        soundManager.playModeSwitch()
        _isAutoEnhance.value = !_isAutoEnhance.value
        cameraManager.setAutoEnhance(_isAutoEnhance.value)
        if (_isAutoEnhance.value) _currentFilter.value = FilterPreset.AUTO
    }

    // ── UI Toggles ────────────────────────────────────────────────────────────
    fun toggleFilterPanel() {
        _isFilterPanelOpen.value = !_isFilterPanelOpen.value
        _isAdjustmentOpen.value  = false
    }

    fun toggleAdjustmentPanel() {
        _isAdjustmentOpen.value  = !_isAdjustmentOpen.value
        _isFilterPanelOpen.value = false
    }

    fun toggleGrid()            { _isGridEnabled.value        = !_isGridEnabled.value        }
    fun toggleHistogram()       { _isHistogramEnabled.value   = !_isHistogramEnabled.value   }
    fun toggleAudioVisualizer() { _isAudioVisualizerOn.value  = !_isAudioVisualizerOn.value  }

    fun setCaptureMode(mode: CaptureMode) {
        soundManager.playModeSwitch()
        _currentMode.value = mode
        when (mode) {
            CaptureMode.PORTRAIT  -> applyFilterPreset(FilterPreset.PORTRAIT)
            CaptureMode.NIGHT     -> applyFilterPreset(FilterPreset.NIGHT)
            CaptureMode.CINEMATIC -> applyFilterPreset(FilterPreset.CINEMATIC)
            CaptureMode.THERMAL   -> applyFilterPreset(FilterPreset.THERMAL)
            CaptureMode.PRO       -> { /* user controls */ }
            else -> if (_isAutoEnhance.value) applyFilterPreset(FilterPreset.AUTO)
        }
    }

    fun openGallery() {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            _message.value = "Could not open gallery"
        }
    }

    fun clearMessage() { _message.value = null }

    override fun onCleared() {
        super.onCleared()
        audioProcessor.release()
        soundManager.release()
        cameraManager.release()
    }

    enum class CaptureMode {
        PHOTO, VIDEO, PORTRAIT, NIGHT, PRO, CINEMATIC, THERMAL, TIMELAPSE
    }
}
