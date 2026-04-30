package com.chastechgroup.camix.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.chastechgroup.camix.camera.UltraCameraManager
import com.chastechgroup.camix.filter.*
import com.chastechgroup.camix.ui.theme.*
import com.chastechgroup.camix.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

// ──────────────────────────────────────────────────────────────────────────────
// Root screen
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamixCameraScreen(viewModel: CameraViewModel = hiltViewModel()) {
    val context          = LocalContext.current
    val lifecycleOwner   = LocalLifecycleOwner.current
    val scope            = rememberCoroutineScope()
    val snackbarHost     = remember { SnackbarHostState() }

    // Collect all state
    val currentMode       by viewModel.currentMode.collectAsState()
    val isRecording       by viewModel.isRecording.collectAsState()
    val flashMode         by viewModel.flashMode.collectAsState()
    val zoomLevel         by viewModel.zoomLevel.collectAsState()
    val detectedScene     by viewModel.detectedScene.collectAsState()
    val isFilterOpen      by viewModel.isFilterPanelOpen.collectAsState()
    val isAdjOpen         by viewModel.isAdjustmentPanelOpen.collectAsState()
    val isAutoEnhance     by viewModel.isAutoEnhanceEnabled.collectAsState()
    val isGrid            by viewModel.isGridEnabled.collectAsState()
    val isHistogram       by viewModel.isHistogramEnabled.collectAsState()
    val isAudioVis        by viewModel.isAudioVisualizerOn.collectAsState()
    val currentFilter     by viewModel.currentFilter.collectAsState()
    val message           by viewModel.message.collectAsState()
    val filterParams      by viewModel.filterParams.collectAsState()
    val manualParams      by viewModel.manualParams.collectAsState()
    val aeAfLocked        by viewModel.aeAfLocked.collectAsState()
    val isTimelapse       by viewModel.isTimelapse.collectAsState()
    val timelapseCaptured by viewModel.timelapseCaptured.collectAsState()
    val rmsLevel          by viewModel.rmsLevel.collectAsState()
    val waveform          by viewModel.waveform.collectAsState()
    val noiseFloorDb      by viewModel.noiseFloorDb.collectAsState()

    // Focus ring state (shown on tap)
    var focusRingPos by remember { mutableStateOf<Offset?>(null) }
    var focusRingVisible by remember { mutableStateOf(false) }

    // Snackbar on message
    LaunchedEffect(message) {
        message?.let { snackbarHost.showSnackbar(it, duration = SnackbarDuration.Short); viewModel.clearMessage() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {

            // ── Camera Preview ──────────────────────────────────────────────
            CameraPreviewView(
                viewModel       = viewModel,
                lifecycleOwner  = lifecycleOwner,
                modifier        = Modifier.fillMaxSize(),
                onTap           = { x, y ->
                    focusRingPos     = Offset(x, y)
                    focusRingVisible = true
                    viewModel.tapToFocus(x, y)
                    scope.launch { delay(2_000); focusRingVisible = false }
                },
                onLongPress     = { x, y ->
                    viewModel.lockAeAf(x, y)
                    focusRingPos     = Offset(x, y)
                    focusRingVisible = true
                },
                onPinchZoom     = { scale -> viewModel.pinchZoom(scale) }
            )

            // ── Grid ────────────────────────────────────────────────────────
            if (isGrid) GridOverlay(Modifier.fillMaxSize())

            // ── Focus ring ──────────────────────────────────────────────────
            AnimatedVisibility(visible = focusRingVisible, modifier = Modifier.fillMaxSize()) {
                focusRingPos?.let { pos ->
                    FocusRing(pos = pos, locked = aeAfLocked, modifier = Modifier.fillMaxSize())
                }
            }

            // ── Audio Waveform overlay ──────────────────────────────────────
            AnimatedVisibility(
                visible = isAudioVis,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 120.dp)
            ) {
                AudioWaveformView(
                    waveform = waveform,
                    rmsLevel = rmsLevel,
                    noiseFloor = noiseFloorDb,
                    modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp)
                )
            }

            // ── Histogram overlay ───────────────────────────────────────────
            // (placeholder — real histogram would be computed from ImageAnalysis)
            AnimatedVisibility(
                visible = isHistogram,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 120.dp, end = 8.dp)
            ) {
                HistogramPlaceholder(modifier = Modifier.size(100.dp, 60.dp))
            }

            // ── Scene + AE/AF indicators ────────────────────────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isAutoEnhance) SceneChip(scene = detectedScene)
                if (aeAfLocked) AeAfLockChip { viewModel.unlockAeAf() }
                if (isTimelapse) TimelapseChip(count = timelapseCaptured)
            }

            // ── Top bar ─────────────────────────────────────────────────────
            TopBar(
                flashMode       = flashMode,
                isAutoEnhance   = isAutoEnhance,
                isGrid          = isGrid,
                isHistogram     = isHistogram,
                isAudioVis      = isAudioVis,
                onFlash         = { viewModel.toggleFlash() },
                onAutoEnhance   = { viewModel.toggleAutoEnhance() },
                onGrid          = { viewModel.toggleGrid() },
                onHistogram     = { viewModel.toggleHistogram() },
                onAudioVis      = { viewModel.toggleAudioVisualizer() },
                modifier        = Modifier.align(Alignment.TopCenter)
            )

            // ── Bottom controls stack ────────────────────────────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Zoom control
                ZoomBar(
                    zoom     = zoomLevel,
                    maxZoom  = viewModel.cameraManager.getMaxZoom(),
                    onChange = { viewModel.setZoom(it) },
                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 4.dp)
                )

                // Pro exposure bar
                if (currentMode == CameraViewModel.CaptureMode.PRO) {
                    ProExposureBar(
                        viewModel = viewModel,
                        modifier  = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
                    )
                }

                // Timelapse interval selector
                if (currentMode == CameraViewModel.CaptureMode.TIMELAPSE && !isTimelapse) {
                    TimelapseIntervalSelector(viewModel = viewModel)
                }

                // Filter panel
                AnimatedVisibility(visible = isFilterOpen) {
                    FilterPanel(current = currentFilter, onSelect = { viewModel.applyFilterPreset(it) })
                }

                // Adjustment panel
                AnimatedVisibility(visible = isAdjOpen) {
                    AdjustmentPanel(
                        params   = if (isAutoEnhance) filterParams else manualParams,
                        isAuto   = isAutoEnhance,
                        onChange = { updater -> viewModel.updateManualParameter(updater) }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Mode selector
                ModeSelector(current = currentMode, onSelect = { viewModel.setCaptureMode(it) })

                Spacer(modifier = Modifier.height(8.dp))

                // Main controls row
                CaptureControlsRow(
                    currentMode   = currentMode,
                    isRecording   = isRecording,
                    isTimelapse   = isTimelapse,
                    onCapture     = {
                        when (currentMode) {
                            CameraViewModel.CaptureMode.VIDEO      -> viewModel.toggleRecording()
                            CameraViewModel.CaptureMode.TIMELAPSE  -> if (isTimelapse) viewModel.stopTimelapse() else viewModel.startTimelapse()
                            else -> viewModel.capturePhoto()
                        }
                    },
                    onSwitchCam   = {
                        val pv = PreviewView(context)
                        viewModel.switchCamera(lifecycleOwner, pv.surfaceProvider)
                    },
                    onFilterClick = { viewModel.toggleFilterPanel() },
                    onAdjClick    = { viewModel.toggleAdjustmentPanel() },
                    onGallery     = { /* open gallery */ }
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Camera preview with gesture handling
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun CameraPreviewView(
    viewModel:      CameraViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    modifier:       Modifier = Modifier,
    onTap:          (Float, Float) -> Unit = { _, _ -> },
    onLongPress:    (Float, Float) -> Unit = { _, _ -> },
    onPinchZoom:    (Float) -> Unit        = {}
) {
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                scaleType          = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (abs(zoom - 1f) > 0.01f) onPinchZoom(zoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap       = { off -> onTap(off.x, off.y) },
                    onLongPress = { off -> onLongPress(off.x, off.y) }
                )
            },
        update = { pv ->
            viewModel.startCamera(lifecycleOwner, pv.surfaceProvider)
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Focus ring
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun FocusRing(pos: Offset, locked: Boolean, modifier: Modifier = Modifier) {
    val animRadius by animateFloatAsState(
        targetValue = if (locked) 34f else 44f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "focusRadius"
    )
    val color = if (locked) Color(0xFFFFD600) else Color.White

    Canvas(modifier = modifier) {
        drawCircle(
            color  = color,
            radius = animRadius.dp.toPx(),
            center = pos,
            style  = Stroke(width = 2.dp.toPx())
        )
        // Corner brackets
        val r = animRadius.dp.toPx()
        val arm = 8.dp.toPx()
        val bw  = 2.dp.toPx()
        listOf(
            Offset(-r, -r) to listOf(Offset(arm, 0f), Offset(0f, arm)),
            Offset( r, -r) to listOf(Offset(-arm, 0f), Offset(0f, arm)),
            Offset(-r,  r) to listOf(Offset(arm, 0f), Offset(0f, -arm)),
            Offset( r,  r) to listOf(Offset(-arm, 0f), Offset(0f, -arm))
        ).forEach { (corner, arms) ->
            val c = pos + corner
            arms.forEach { arm2 ->
                drawLine(color = color, start = c, end = c + arm2, strokeWidth = bw)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Grid overlay
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        val color = Color.White.copy(alpha = 0.28f)
        val sw = 1.dp.toPx()
        drawLine(color, Offset(w / 3, 0f), Offset(w / 3, h), sw)
        drawLine(color, Offset(w * 2 / 3, 0f), Offset(w * 2 / 3, h), sw)
        drawLine(color, Offset(0f, h / 3), Offset(w, h / 3), sw)
        drawLine(color, Offset(0f, h * 2 / 3), Offset(w, h * 2 / 3), sw)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Audio waveform overlay
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun AudioWaveformView(
    waveform:   FloatArray,
    rmsLevel:   Float,
    noiseFloor: Float,
    modifier:   Modifier = Modifier
) {
    Canvas(modifier = modifier
        .clip(RoundedCornerShape(8.dp))
        .background(Color.Black.copy(alpha = 0.5f))) {
        val w = size.width; val h = size.height
        val mid = h / 2

        // RMS bar (vertical)
        drawRect(
            color = lerp(Color(0xFF00E5FF), Color(0xFFFF1744), rmsLevel),
            topLeft = Offset(0f, mid - mid * rmsLevel),
            size = Size(4.dp.toPx(), mid * 2 * rmsLevel)
        )

        // Waveform
        val step = w / waveform.size
        for (i in waveform.indices) {
            val x = i * step
            val amp = waveform[i] * mid * 0.9f
            drawLine(
                color = Color(0xFF00E5FF).copy(alpha = 0.8f),
                start = Offset(x, mid - amp),
                end   = Offset(x, mid + amp),
                strokeWidth = max(1f, step - 1f)
            )
        }

        // Noise floor indicator
        val floorY = mid - (noiseFloor / -90f) * mid
        drawLine(
            color = Color(0xFFFFD600).copy(alpha = 0.5f),
            start = Offset(0f, floorY),
            end   = Offset(w, floorY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Histogram placeholder (labels only; real impl needs frame data)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun HistogramPlaceholder(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier
        .clip(RoundedCornerShape(6.dp))
        .background(Color.Black.copy(alpha = 0.6f))) {
        val w = size.width; val h = size.height
        // RGB mock curves
        listOf(Color.Red, Color(0xFF00E676), Color(0xFF40C4FF)).forEachIndexed { ci, color ->
            val path = Path()
            for (x in 0..w.toInt()) {
                val t = x / w
                val y = h - h * 0.6f * exp(-((t - (0.35f + ci * 0.15f)).pow(2)) / 0.02f)
                if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
            }
            drawPath(path, color = color.copy(alpha = 0.6f), style = Stroke(1.5f))
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Info chips
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun SceneChip(scene: SceneType) {
    Surface(
        color = PrimaryPurple.copy(alpha = 0.55f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AutoAwesome, null, tint = AccentTeal, modifier = Modifier.size(13.dp))
            Text(
                text = scene.name.replace("_", " "),
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
fun AeAfLockChip(onUnlock: () -> Unit) {
    Surface(
        color = Color(0xFFFFD600).copy(alpha = 0.2f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable { onUnlock() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Lock, null, tint = Color(0xFFFFD600), modifier = Modifier.size(12.dp))
            Text(
                text = "AE/AF LOCK  ✕",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFD600),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
fun TimelapseChip(count: Int) {
    Surface(
        color = Color(0xFFFF1744).copy(alpha = 0.3f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val inf by rememberInfiniteTransition(label = "tl").animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "tlAlpha"
            )
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFF1744).copy(alpha = inf)))
            Text(
                text = "  TIMELAPSE  $count",
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Top bar
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun TopBar(
    flashMode:    UltraCameraManager.FlashMode,
    isAutoEnhance: Boolean,
    isGrid:       Boolean,
    isHistogram:  Boolean,
    isAudioVis:   Boolean,
    onFlash:      () -> Unit,
    onAutoEnhance:() -> Unit,
    onGrid:       () -> Unit,
    onHistogram:  () -> Unit,
    onAudioVis:   () -> Unit,
    modifier:     Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flash
        TopIconBtn(
            onClick = onFlash,
            icon = when (flashMode) {
                UltraCameraManager.FlashMode.OFF  -> Icons.Outlined.FlashOff
                UltraCameraManager.FlashMode.ON   -> Icons.Filled.FlashOn
                UltraCameraManager.FlashMode.AUTO -> Icons.Outlined.FlashAuto
            },
            tint = if (flashMode == UltraCameraManager.FlashMode.OFF) TextSecondary else AccentTeal
        )
        // AI Auto
        TopIconBtn(
            onClick = onAutoEnhance,
            icon = if (isAutoEnhance) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
            tint = if (isAutoEnhance) AccentTeal else TextSecondary
        )
        // Grid
        TopIconBtn(onClick = onGrid,
            icon = if (isGrid) Icons.Filled.GridOn else Icons.Outlined.GridOn,
            tint = if (isGrid) AccentTeal else TextSecondary)
        // Histogram
        TopIconBtn(onClick = onHistogram,
            icon = if (isHistogram) Icons.Filled.BarChart else Icons.Outlined.BarChart,
            tint = if (isHistogram) Color(0xFFFFD600) else TextSecondary)
        // Audio visualizer
        TopIconBtn(onClick = onAudioVis,
            icon = if (isAudioVis) Icons.Filled.VolumeUp else Icons.Outlined.VolumeOff,
            tint = if (isAudioVis) Color(0xFF00E5FF) else TextSecondary)
    }
}

@Composable
fun TopIconBtn(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Zoom bar
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun ZoomBar(zoom: Float, maxZoom: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ZoomOut, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
        Slider(
            value = zoom, onValueChange = onChange,
            valueRange = 1f..maxZoom.coerceAtLeast(2f),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = PrimaryPurple, inactiveTrackColor = SurfaceLight)
        )
        Icon(Icons.Outlined.ZoomIn, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("${zoom.toInt()}×", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Pro exposure bar
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun ProExposureBar(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val idx by viewModel.proExposureIndex.collectAsState()
    Column(modifier = modifier) {
        Text("EV Compensation", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Slider(
            value = idx.toFloat(),
            onValueChange = { viewModel.setProExposure(it.toInt()) },
            valueRange = -6f..6f, steps = 11,
            colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD600), activeTrackColor = Color(0xFFFFD600))
        )
        Text("${if (idx >= 0) "+" else ""}$idx", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFD600))
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Timelapse interval selector
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun TimelapseIntervalSelector(viewModel: CameraViewModel) {
    val interval by viewModel.timelapseInterval.collectAsState()
    val opts = listOf(1_000L to "1s", 2_000L to "2s", 5_000L to "5s", 10_000L to "10s", 30_000L to "30s")
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Interval:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        opts.forEach { (ms, label) ->
            val sel = interval == ms
            Box(modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (sel) PrimaryPurple else SurfaceDark.copy(alpha = 0.8f))
                .clickable { viewModel.setTimelapseInterval(ms) }
                .padding(horizontal = 10.dp, vertical = 6.dp)
            ) { Text(label, style = MaterialTheme.typography.labelSmall, color = if (sel) TextPrimary else TextSecondary) }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Mode selector
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun ModeSelector(current: CameraViewModel.CaptureMode, onSelect: (CameraViewModel.CaptureMode) -> Unit) {
    val modes = listOf(
        CameraViewModel.CaptureMode.PHOTO      to "Photo",
        CameraViewModel.CaptureMode.VIDEO      to "Video",
        CameraViewModel.CaptureMode.PORTRAIT   to "Portrait",
        CameraViewModel.CaptureMode.NIGHT      to "Night",
        CameraViewModel.CaptureMode.CINEMATIC  to "Cinematic",
        CameraViewModel.CaptureMode.THERMAL    to "Thermal",
        CameraViewModel.CaptureMode.TIMELAPSE  to "Timelapse",
        CameraViewModel.CaptureMode.PRO        to "Pro"
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(modes) { (mode, label) ->
            val sel = current == mode
            Box(modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (sel) PrimaryPurple else ControlBackground)
                .clickable { onSelect(mode) }
                .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = if (sel) TextPrimary else TextSecondary)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Filter panel
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun FilterPanel(current: FilterPreset, onSelect: (FilterPreset) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(FilterPreset.entries) { preset ->
            val sel = current == preset
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) PrimaryPurple.copy(alpha = 0.3f) else SurfaceDark.copy(alpha = 0.85f))
                    .border(if (sel) 2.dp else 0.dp, if (sel) PrimaryPurple else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { onSelect(preset) }
                    .padding(6.dp)
            ) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(filterColor(preset)))
                Text(preset.displayName, style = MaterialTheme.typography.labelSmall,
                    color = if (sel) PrimaryPurple else TextSecondary,
                    textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

fun filterColor(f: FilterPreset): Color = when (f) {
    FilterPreset.NONE       -> Color.DarkGray
    FilterPreset.AUTO       -> PrimaryPurple
    FilterPreset.PORTRAIT   -> Color(0xFFFFC1CC)
    FilterPreset.LANDSCAPE  -> Color(0xFF388E3C)
    FilterPreset.NIGHT      -> Color(0xFF1A237E)
    FilterPreset.VIVID      -> Color(0xFFFF6D00)
    FilterPreset.WARM       -> Color(0xFFFF8F00)
    FilterPreset.COOL       -> Color(0xFF0277BD)
    FilterPreset.DRAMATIC   -> Color(0xFF212121)
    FilterPreset.FADE       -> Color(0xFFD7CCC8)
    FilterPreset.MONO       -> Color(0xFF616161)
    FilterPreset.SEPIA      -> Color(0xFF8D6E63)
    FilterPreset.CINEMATIC  -> Color(0xFF263238)
    FilterPreset.THERMAL    -> Color(0xFFBF360C)
    FilterPreset.ULTRASOUND -> Color(0xFF0D47A1)
    FilterPreset.FOCUS_PEAK -> Color(0xFFB71C1C)
    FilterPreset.DEHAZE     -> Color(0xFF00ACC1)
}

// ──────────────────────────────────────────────────────────────────────────────
// Adjustment panel (sliders)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun AdjustmentPanel(
    params:   FilterParameters,
    isAuto:   Boolean,
    onChange: ((FilterParameters) -> FilterParameters) -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(SurfaceDark.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(scroll)
    ) {
        if (isAuto) Text("AI Auto-Adjustment Active", style = MaterialTheme.typography.labelMedium, color = AccentTeal, modifier = Modifier.padding(bottom = 8.dp))

        adjSlider("Brightness",  params.brightness,  -1f, 1f)  { onChange { p -> p.copy(brightness  = it) } }
        adjSlider("Contrast",    params.contrast,    -1f, 1f)  { onChange { p -> p.copy(contrast    = it) } }
        adjSlider("Saturation",  params.saturation,  -1f, 1f)  { onChange { p -> p.copy(saturation  = it) } }
        adjSlider("Exposure",    params.exposure,    -2f, 2f)  { onChange { p -> p.copy(exposure    = it) } }
        adjSlider("Warmth",      params.warmth,      -1f, 1f)  { onChange { p -> p.copy(warmth      = it) } }
        adjSlider("Highlights",  params.highlights,  -1f, 1f)  { onChange { p -> p.copy(highlights  = it) } }
        adjSlider("Shadows",     params.shadows,     -1f, 1f)  { onChange { p -> p.copy(shadows     = it) } }
        adjSlider("Vignette",    params.vignette,     0f, 1f)  { onChange { p -> p.copy(vignette    = it) } }
        adjSlider("Sharpen",     params.sharpen,      0f, 2f)  { onChange { p -> p.copy(sharpen     = it) } }
        adjSlider("Grain",       params.grain,        0f, 1f)  { onChange { p -> p.copy(grain       = it) } }
        adjSlider("Fade",        params.fade,         0f, 1f)  { onChange { p -> p.copy(fade        = it) } }
        adjSlider("Clarity",     params.clarityBoost, 0f, 1f)  { onChange { p -> p.copy(clarityBoost= it) } }
        adjSlider("Dehaze",      params.dehaze,       0f, 1f)  { onChange { p -> p.copy(dehaze      = it) } }
        adjSlider("Hue",         params.hue,       -180f,180f) { onChange { p -> p.copy(hue         = it) } }
        adjSlider("Tint",        params.tint,        -1f, 1f)  { onChange { p -> p.copy(tint        = it) } }
    }
}

@Composable
fun adjSlider(label: String, value: Float, min: Float, max: Float, onVal: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        }
        Slider(value = value, onValueChange = onVal, valueRange = min..max,
            colors = SliderDefaults.colors(thumbColor = PrimaryPurple, activeTrackColor = PrimaryPurple, inactiveTrackColor = SurfaceLight),
            modifier = Modifier.height(28.dp))
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Capture controls row
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun CaptureControlsRow(
    currentMode:  CameraViewModel.CaptureMode,
    isRecording:  Boolean,
    isTimelapse:  Boolean,
    onCapture:    () -> Unit,
    onSwitchCam:  () -> Unit,
    onFilterClick:() -> Unit,
    onAdjClick:   () -> Unit,
    onGallery:    () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onGallery) {
            Icon(Icons.Outlined.PhotoLibrary, null, tint = TextPrimary, modifier = Modifier.size(26.dp))
        }
        IconButton(onClick = onFilterClick) {
            Icon(Icons.Outlined.PhotoFilter, null, tint = TextPrimary, modifier = Modifier.size(26.dp))
        }
        // Shutter
        ShutterBtn(
            mode        = currentMode,
            isRecording = isRecording,
            isTimelapse = isTimelapse,
            onClick     = onCapture
        )
        IconButton(onClick = onAdjClick) {
            Icon(Icons.Outlined.Tune, null, tint = TextPrimary, modifier = Modifier.size(26.dp))
        }
        IconButton(onClick = onSwitchCam) {
            Icon(Icons.Outlined.Cameraswitch, null, tint = TextPrimary, modifier = Modifier.size(26.dp))
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Shutter button
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun ShutterBtn(
    mode:        CameraViewModel.CaptureMode,
    isRecording: Boolean,
    isTimelapse: Boolean,
    onClick:     () -> Unit
) {
    val isVideoMode  = mode == CameraViewModel.CaptureMode.VIDEO
    val isTLMode     = mode == CameraViewModel.CaptureMode.TIMELAPSE
    val active       = (isVideoMode && isRecording) || (isTLMode && isTimelapse)

    val scale by animateFloatAsState(
        targetValue = if (active) 0.87f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "shutterScale"
    )

    val outerColor = when {
        active && isVideoMode  -> ShutterRed
        active && isTLMode     -> Color(0xFFFF6D00)
        isTLMode               -> Color(0xFFFF6D00)
        else                   -> TextPrimary
    }
    val innerColor = when {
        active  -> outerColor
        isVideoMode || isTLMode -> Color.Transparent
        else    -> TextPrimary
    }

    Box(
        modifier = Modifier
            .size(72.dp)
            .scale(scale)
            .border(4.dp, outerColor, CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(innerColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when {
            active && isVideoMode -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(TextPrimary))
            active && isTLMode   -> Icon(Icons.Filled.Pause, null, tint = TextPrimary, modifier = Modifier.size(24.dp))
            isTLMode             -> Icon(Icons.Filled.Timelapse, null, tint = TextPrimary, modifier = Modifier.size(28.dp))
            isVideoMode          -> Box(Modifier.size(22.dp).clip(CircleShape).background(ShutterRed))
        }
    }
}
