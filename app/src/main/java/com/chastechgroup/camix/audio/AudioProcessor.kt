package com.chastechgroup.camix.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.*

/**
 * CamixUltra AudioProcessor — fuses both packages' audio systems.
 *
 * Capabilities:
 *  • Hardware effects: NoiseSuppressor, AcousticEchoCanceler, AutomaticGainControl
 *  • DSP pipeline:
 *      1. Wind noise reduction (1st-order high-pass, fc = 200 Hz)
 *      2. Voice clarity enhancement (band-pass emphasis 1–4 kHz)
 *      3. Dynamic normalisation (peak limiter)
 *      4. Low-light audio boost (raises gain when scene is dark)
 *  • Real-time metering: RMS level, peak level, waveform snapshot (128 samples)
 *  • EnvironmentAnalyzer integration: noise floor dB estimate → SceneDetector
 *  • Supports CAMCORDER source for optimal camera-sync
 */
class AudioProcessor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── State flows ───────────────────────────────────────────────────────────

    private val _isProcessing  = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _rmsLevel      = MutableStateFlow(0f)   // 0..1 linear
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _peakLevel     = MutableStateFlow(0f)
    val peakLevel: StateFlow<Float> = _peakLevel.asStateFlow()

    private val _noiseFloorDb  = MutableStateFlow(-60f) // negative dBFS
    val noiseFloorDb: StateFlow<Float> = _noiseFloorDb.asStateFlow()

    private val _waveform      = MutableStateFlow(FloatArray(128))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    // ── Hardware effects ──────────────────────────────────────────────────────

    private var noiseSuppressor:       NoiseSuppressor?       = null
    private var echoCanceler:          AcousticEchoCanceler?  = null
    private var automaticGainControl:  AutomaticGainControl?  = null

    // ── DSP settings ──────────────────────────────────────────────────────────

    var noiseSuppressionEnabled  = true
    var echoCancellationEnabled  = true
    var autoGainEnabled          = true
    var windReductionEnabled     = true
    var voiceClarityLevel        = 0.55f   // 0..1
    var lowLightGainBoost        = 0f      // set by CameraViewModel (0..1)

    // ── Audio record ─────────────────────────────────────────────────────────

    private var audioRecord: AudioRecord? = null
    private var processingJob: Job?       = null

    private val sampleRate    = 48_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding      = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding) * 4
    }

    // Noise-floor estimation (rolling min RMS over 2-second window)
    private val rmsRing = ArrayDeque<Float>(60)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun setupAudioProcessing() {
        try {
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(MediaRecorder.AudioSource.CAMCORDER,
                    sampleRate, channelConfig, encoding, bufferSize)
            }

            audioRecord?.audioSessionId?.let { initHardwareEffects(it) }
            Timber.d("AudioProcessor: AudioRecord ready (session=${audioRecord?.audioSessionId})")
        } catch (e: Exception) {
            Timber.e(e, "AudioProcessor: setup failed")
        }
    }

    fun initHardwareEffects(sessionId: Int) {
        if (NoiseSuppressor.isAvailable() && noiseSuppressionEnabled) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            Timber.d("NoiseSuppressor enabled")
        }
        if (AcousticEchoCanceler.isAvailable() && echoCancellationEnabled) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            Timber.d("AcousticEchoCanceler enabled")
        }
        if (AutomaticGainControl.isAvailable() && autoGainEnabled) {
            automaticGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            Timber.d("AutomaticGainControl enabled")
        }
    }

    fun startProcessing() {
        if (_isProcessing.value) return
        _isProcessing.value = true

        processingJob = scope.launch {
            try {
                audioRecord?.startRecording()
                val buf = ShortArray(bufferSize / 2)
                while (isActive && _isProcessing.value) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: break
                    if (read > 0) {
                        val processed = applyDspPipeline(buf.copyOf(), read)
                        updateMetrics(processed, read)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "AudioProcessor: processing loop error")
            }
        }
    }

    fun stopProcessing() {
        _isProcessing.value = false
        processingJob?.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
    }

    fun release() {
        stopProcessing()
        scope.cancel()
        noiseSuppressor?.release()
        echoCanceler?.release()
        automaticGainControl?.release()
        audioRecord?.release()
        noiseSuppressor       = null
        echoCanceler          = null
        automaticGainControl  = null
        audioRecord           = null
        Timber.d("AudioProcessor released")
    }

    // ── DSP Pipeline ─────────────────────────────────────────────────────────

    private fun applyDspPipeline(buf: ShortArray, size: Int): ShortArray {
        var out = buf
        if (windReductionEnabled)   out = reduceWind(out, size)
        if (voiceClarityLevel > 0f) out = enhanceVoice(out, size)
        out = applyLowLightBoost(out, size)
        out = normalize(out, size)
        return out
    }

    /** 1st-order IIR high-pass at 200 Hz to kill wind rumble */
    private fun reduceWind(buf: ShortArray, size: Int): ShortArray {
        val rc    = 1.0 / (2.0 * PI * 200.0)
        val dt    = 1.0 / sampleRate
        val alpha = (rc / (rc + dt)).toFloat()
        var prevIn = 0f; var prevOut = 0f
        for (i in 0 until size) {
            val x    = buf[i].toFloat()
            val y    = alpha * (prevOut + x - prevIn)
            buf[i]   = y.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            prevIn   = x; prevOut = y
        }
        return buf
    }

    /** Band-emphasis for speech frequencies (1–4 kHz) via sharpened moving average */
    private fun enhanceVoice(buf: ShortArray, size: Int): ShortArray {
        val gain   = 1f + voiceClarityLevel * 0.55f
        val win    = (sampleRate / 2000).coerceIn(2, 22)
        for (i in win until size - win) {
            var sum = 0f
            for (j in -win..win) sum += buf[i + j]
            val avg  = sum / (win * 2 + 1)
            val diff = buf[i] - avg
            buf[i]   = (avg + diff * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return buf
    }

    /** Raise gain slightly when scene is dark (user is likely recording in low-light) */
    private fun applyLowLightBoost(buf: ShortArray, size: Int): ShortArray {
        if (lowLightGainBoost <= 0.01f) return buf
        val gain = 1f + lowLightGainBoost * 0.5f
        for (i in 0 until size) {
            buf[i] = (buf[i] * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return buf
    }

    /** Peak limiter: prevent clipping above 90 % */
    private fun normalize(buf: ShortArray, size: Int): ShortArray {
        var peak = 0
        for (i in 0 until size) peak = max(peak, abs(buf[i].toInt()))
        val threshold = Short.MAX_VALUE * 0.90
        if (peak > threshold) {
            val gain = threshold / peak.toFloat()
            for (i in 0 until size) buf[i] = (buf[i] * gain).toInt().toShort()
        }
        return buf
    }

    // ── Metering ──────────────────────────────────────────────────────────────

    private fun updateMetrics(buf: ShortArray, size: Int) {
        // RMS
        var sum = 0.0
        var peak = 0
        for (i in 0 until size) {
            val v = buf[i].toInt()
            sum  += v.toDouble().pow(2)
            peak  = max(peak, abs(v))
        }
        val rms = (sqrt(sum / size) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
        val pk  = (peak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
        _rmsLevel.value  = rms
        _peakLevel.value = pk

        // Noise-floor estimation (rolling 2-s min of RMS)
        rmsRing.addLast(rms)
        if (rmsRing.size > 60) rmsRing.removeFirst()
        val floorLinear = rmsRing.min()
        val floorDb = if (floorLinear > 0f) 20f * log10(floorLinear) else -90f
        _noiseFloorDb.value = floorDb

        // Waveform snapshot (downsample to 128 bins)
        val wf = FloatArray(128)
        val step = max(1, size / 128)
        for (i in wf.indices) {
            wf[i] = buf[min(i * step, size - 1)].toFloat() / Short.MAX_VALUE
        }
        _waveform.value = wf
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    fun configure(
        noiseSuppression: Boolean = true,
        echoCancellation: Boolean = true,
        autoGain: Boolean         = true,
        windReduction: Boolean    = true,
        clarity: Float            = 0.55f
    ) {
        noiseSuppressionEnabled  = noiseSuppression
        echoCancellationEnabled  = echoCancellation
        autoGainEnabled          = autoGain
        windReductionEnabled     = windReduction
        voiceClarityLevel        = clarity.coerceIn(0f, 1f)
    }
}
