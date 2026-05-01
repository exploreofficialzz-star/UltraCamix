package com.chastechgroup.camix.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.chastechgroup.camix.filter.FilterParameters
import com.chastechgroup.camix.filter.FilterRenderer
import com.chastechgroup.camix.filter.SceneDetector
import com.chastechgroup.camix.filter.SceneType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UltraCameraManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var recording: Recording? = null

    val sceneDetector = SceneDetector()
    val autoExposure  = AutoExposureController()

    // Preview dimensions for accurate tap-to-focus
    private var previewWidth  = 1080f
    private var previewHeight = 1920f

    // ── State ─────────────────────────────────────────────────────────────────
    private val _cameraState       = MutableStateFlow(CamState.IDLE)
    val cameraState: StateFlow<CamState> = _cameraState.asStateFlow()

    private val _detectedScene     = MutableStateFlow(SceneType.AUTO)
    val detectedScene: StateFlow<SceneType> = _detectedScene.asStateFlow()

    private val _filterParams      = MutableStateFlow(FilterParameters.DEFAULT)
    val filterParams: StateFlow<FilterParameters> = _filterParams.asStateFlow()

    private val _flashMode         = MutableStateFlow(FlashMode.OFF)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    private val _isRecording       = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _zoomLevel         = MutableStateFlow(1f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private val _aeAfLocked        = MutableStateFlow(false)
    val aeAfLocked: StateFlow<Boolean> = _aeAfLocked.asStateFlow()

    private val _isTimelapse       = MutableStateFlow(false)
    val isTimelapse: StateFlow<Boolean> = _isTimelapse.asStateFlow()

    private val _timelapseCaptured = MutableStateFlow(0)
    val timelapseCaptured: StateFlow<Int> = _timelapseCaptured.asStateFlow()

    private val _exposureLabel     = MutableStateFlow("Auto")
    val exposureLabel: StateFlow<String> = _exposureLabel.asStateFlow()

    private val _isClipping        = MutableStateFlow(false)
    val isClipping: StateFlow<Boolean> = _isClipping.asStateFlow()

    // ── Settings ──────────────────────────────────────────────────────────────
    private var lensFacing           = CameraSelector.LENS_FACING_BACK
    private var isAutoEnhanceEnabled = true
    private var filterRenderer: FilterRenderer? = null
    private var timelapseJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        // Scene → filter params
        scope.launch {
            sceneDetector.suggestedParams.collect { params ->
                if (isAutoEnhanceEnabled) {
                    _filterParams.value = params
                    filterRenderer?.updateParameters(params)
                }
            }
        }
        scope.launch { sceneDetector.detectedScene.collect { _detectedScene.value = it } }

        // Auto-exposure label + clipping state
        scope.launch { autoExposure.exposureLabel.collect { _exposureLabel.value = it } }
        scope.launch { autoExposure.isClipping.collect   { _isClipping.value    = it } }

        // Feed scene stats into AutoExposureController
        scope.launch {
            sceneDetector.sceneStats.collect { stats ->
                if (stats != null) {
                    autoExposure.updateScene(
                        brightness  = stats.avgBrightness,
                        darkRatio   = stats.darkRatio,
                        brightRatio = stats.brightRatio,
                        skinRatio   = stats.skinRatio,
                        hfEnergy    = stats.hfEnergyRatio,
                        isNight     = _detectedScene.value == SceneType.NIGHT ||
                                      _detectedScene.value == SceneType.LOW_LIGHT,
                        isBacklit   = _detectedScene.value == SceneType.BACKLIT
                    )
                }
            }
        }
    }

    // ── Camera lifecycle ──────────────────────────────────────────────────────
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onError: (Exception) -> Unit
    ) {
        ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({
                try {
                    cameraProvider = future.get()
                    bindUseCases(lifecycleOwner, surfaceProvider)
                } catch (e: Exception) {
                    Timber.e(e, "Camera provider error"); onError(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun bindUseCases(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        autoExposure.detach()

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetRotation(Surface.ROTATION_0)
            .build().also { it.setSurfaceProvider(surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(_flashMode.value.toCX())
            .setJpegQuality(95)
            .build()

        val recorder = Recorder.Builder().setExecutor(cameraExecutor).build()
        videoCapture = VideoCapture.withOutput(recorder)

        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build().also { ia ->
                    ia.setAnalyzer(cameraExecutor) { proxy -> sceneDetector.processFrame(proxy) }
                }
        }

        try {
            val useCases = buildList {
                add(preview!!)
                add(imageCapture!!)
                add(videoCapture!!)
                imageAnalysis?.let { add(it) }
            }
            camera = provider.bindToLifecycle(owner, selector, *useCases.toTypedArray())
            setupControls()
            camera?.let { autoExposure.attach(it) }
            _cameraState.value = CamState.READY
            Timber.d("Camera bound OK — ${useCases.size} use-cases")
        } catch (e: Exception) {
            Timber.e(e, "Use-case binding failed")
            _cameraState.value = CamState.ERROR
        }
    }

    private fun setupControls() {
        camera?.cameraControl?.setZoomRatio(1f)
    }

    // ── Preview size ──────────────────────────────────────────────────────────
    fun setPreviewSize(width: Float, height: Float) {
        previewWidth  = width.coerceAtLeast(1f)
        previewHeight = height.coerceAtLeast(1f)
    }

    // ── Zoom ─────────────────────────────────────────────────────────────────
    fun setZoom(zoom: Float) {
        val z = zoom.coerceIn(1f, getMaxZoom())
        _zoomLevel.value = z
        camera?.cameraControl?.setZoomRatio(z)
    }

    fun pinchZoom(scaleFactor: Float) = setZoom(_zoomLevel.value * scaleFactor)

    fun getMaxZoom(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f

    // ── Focus & Exposure ──────────────────────────────────────────────────────
    fun tapToFocus(x: Float, y: Float) {
        if (_aeAfLocked.value) return
        val factory = SurfaceOrientedMeteringPointFactory(previewWidth, previewHeight)
        val pt: MeteringPoint = factory.createPoint(x, y, 0.1f)
        val action = FocusMeteringAction.Builder(pt,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
        Timber.d("Tap focus at ($x, $y)")
    }

    fun lockAeAf(x: Float, y: Float) {
        val factory = SurfaceOrientedMeteringPointFactory(previewWidth, previewHeight)
        val pt: MeteringPoint = factory.createPoint(x, y, 0.1f)
        val action = FocusMeteringAction.Builder(pt,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
        _aeAfLocked.value = true
        autoExposure.setManualEv(autoExposure.evIndex.value)
        Timber.d("AE/AF locked at ($x, $y)")
    }

    fun unlockAeAf() {
        camera?.cameraControl?.cancelFocusAndMetering()
        _aeAfLocked.value = false
        autoExposure.enableAuto()
    }

    fun setExposureCompensation(index: Int) {
        autoExposure.setManualEv(index)
    }

    // ── Flash ─────────────────────────────────────────────────────────────────
    fun toggleFlash() {
        val modes = FlashMode.entries.toTypedArray()
        _flashMode.value = modes[(modes.indexOf(_flashMode.value) + 1) % modes.size]
        imageCapture?.flashMode = _flashMode.value.toCX()
        Timber.d("Flash → ${_flashMode.value}")
    }

    // ── Photo capture ─────────────────────────────────────────────────────────
    fun capturePhoto(
        outputDir: File,
        onSaved:  (File) -> Unit,
        onError:  (Exception) -> Unit
    ) {
        val ic = imageCapture ?: return onError(IllegalStateException("Camera not ready"))
        _cameraState.value = CamState.CAPTURING
        val file = File(outputDir, "CamixUltra_${System.currentTimeMillis()}.jpg")
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                    _cameraState.value = CamState.READY; onSaved(file)
                }
                override fun onError(e: ImageCaptureException) {
                    _cameraState.value = CamState.READY; onError(e)
                }
            }
        )
    }

    // ── Video recording ───────────────────────────────────────────────────────
    fun startRecording(
        outputDir: File,
        onStarted:  () -> Unit,
        onFinished: (File) -> Unit,
        onError:    (Exception) -> Unit
    ) {
        val vc   = videoCapture ?: return onError(IllegalStateException("Video not ready"))
        val file = File(outputDir, "CamixUltra_${System.currentTimeMillis()}.mp4")
        recording = vc.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start    -> {
                        _isRecording.value = true
                        _cameraState.value = CamState.RECORDING
                        onStarted()
                    }
                    is VideoRecordEvent.Finalize -> {
                        _isRecording.value = false
                        _cameraState.value = CamState.READY
                        if (event.hasError()) onError(Exception("Recording error: ${event.cause?.message}"))
                        else onFinished(file)
                    }
                }
            }
    }

    fun stopRecording()   { recording?.stop();   recording = null }
    fun pauseRecording()  { recording?.pause();  _cameraState.value = CamState.PAUSED   }
    fun resumeRecording() { recording?.resume(); _cameraState.value = CamState.RECORDING }

    // ── Time-lapse ────────────────────────────────────────────────────────────
    fun startTimelapse(
        outputDir: File,
        intervalMs: Long = 2_000L,
        onFrameCaptured: (Int) -> Unit,
        onError: (Exception) -> Unit
    ) {
        _isTimelapse.value       = true
        _timelapseCaptured.value = 0
        timelapseJob = scope.launch {
            while (isActive && _isTimelapse.value) {
                capturePhoto(outputDir,
                    onSaved = { _timelapseCaptured.value++; onFrameCaptured(_timelapseCaptured.value) },
                    onError = onError)
                delay(intervalMs)
            }
        }
    }

    fun stopTimelapse() {
        _isTimelapse.value = false
        timelapseJob?.cancel()
        timelapseJob = null
    }

    // ── Camera switch ─────────────────────────────────────────────────────────
    fun switchCamera(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindUseCases(owner, surfaceProvider)
    }

    // ── Filters ───────────────────────────────────────────────────────────────
    fun updateFilterParameters(params: FilterParameters) {
        _filterParams.value = params
        filterRenderer?.updateParameters(params)
        isAutoEnhanceEnabled = params.isAutoEnabled
    }

    fun setAutoEnhance(enabled: Boolean) {
        isAutoEnhanceEnabled = enabled
        if (enabled) {
            autoExposure.enableAuto()
            filterRenderer?.updateParameters(sceneDetector.suggestedParams.value)
        }
    }

    fun setFilterRenderer(r: FilterRenderer) { filterRenderer = r }

    fun updateAudioLevel(normalizedLevel: Float) = sceneDetector.updateAudioLevel(normalizedLevel)

    // ── Device info ───────────────────────────────────────────────────────────
    fun hasFlash(): Boolean = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList[0]
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    } catch (_: Exception) { false }

    // ── Release ───────────────────────────────────────────────────────────────
    fun release() {
        stopTimelapse()
        recording?.stop(); recording = null
        autoExposure.detach()
        autoExposure.release()
        scope.cancel()
        cameraExecutor.shutdown()
        sceneDetector.release()
        cameraProvider?.unbindAll()
    }

    // ── Enums ─────────────────────────────────────────────────────────────────
    enum class CamState { IDLE, READY, CAPTURING, RECORDING, PAUSED, ERROR }

    enum class FlashMode {
        OFF, ON, AUTO;
        fun toCX() = when (this) {
            OFF  -> ImageCapture.FLASH_MODE_OFF
            ON   -> ImageCapture.FLASH_MODE_ON
            AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
    }
}
