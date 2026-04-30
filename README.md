# 🎥 CamixUltra

**The most ambitious Android camera app ever built** — a full fusion of both Camix Ultra-Realistic and Camix Ultra-Clear packages, rebuilt from scratch with every possible ambitious feature.

---

## ✨ What's New vs Original Packages

| Feature | Pkg1 (Ultra-Realistic) | Pkg2 (Ultra-Clear) | CamixUltra |
|---|---|---|---|
| GLSL Shaders | OpenGL base + placeholder Sobel | 6 full shader modes | **17 full shader programs** |
| Cinematic Mode | ❌ | ❌ | ✅ ACES filmic + anamorphic streaks + letterbox |
| Thermal Vision | ❌ | ❌ | ✅ Iron-bow false-color palette |
| Focus Peaking | ❌ | ❌ | ✅ Laplacian edge overlay (red/white/yellow) |
| Ultrasound / Edge | Placeholder stub | ❌ | ✅ Full Sobel 3×3 kernel |
| Dehaze | ❌ | ❌ | ✅ Dark-channel-prior atmospheric dehaze |
| Clarity Boost | ❌ | ❌ | ✅ Micro-contrast midtone masking |
| Scene Detection | Environment analyzer | Scene detector | **13 scenes** + macro + action |
| Audio Processing | Concept only | NoiseSuppressor + AGC + AEC | ✅ + Wind reduction + Voice clarity + Waveform |
| Audio Waveform UI | ❌ | ❌ | ✅ Real-time 128-sample animated overlay |
| AE/AF Lock | ❌ | ❌ | ✅ Long-press to lock, tap to unlock |
| Pinch-to-Zoom | ❌ | ❌ | ✅ Gesture-native |
| Time-lapse | ❌ | ❌ | ✅ Configurable 1s–30s interval |
| Pro Mode | ❌ | ❌ | ✅ Manual EV compensation slider |
| Histogram | ❌ | ❌ | ✅ RGB channel histogram overlay |
| Grid Overlay | ❌ | ❌ | ✅ Rule-of-thirds |
| Focus Ring | ❌ | ❌ | ✅ Animated bracket-style ring + lock state |
| Capture Modes | 3 | 4 | **8** (Photo/Video/Portrait/Night/Cinematic/Thermal/Timelapse/Pro) |
| Filter Presets | 6 | 8 | **17** |
| Audio → Camera | ❌ | ❌ | ✅ Noise floor → low-light gain boost |
| Scene Stability | None | None | ✅ 3-frame stability gate (no flickering) |
| DI / Architecture | None | Hilt | Hilt + MVVM + StateFlow |
| CI/CD | ❌ | ❌ | ✅ GitHub Actions |

---

## 🏗️ Architecture

```
CamixUltra/
├── audio/
│   └── AudioProcessor.kt       # Full DSP pipeline: wind, voice clarity, AGC, waveform
├── camera/
│   └── UltraCameraManager.kt   # CameraX + AE/AF lock + timelapse + zoom + scene feed
├── filter/
│   ├── CameraFilterShaders.kt  # 17 GLSL fragment shaders
│   ├── FilterParameters.kt     # Typed parameter data class + 17 presets
│   ├── FilterPreset.kt         # Enum driving filter panel chips
│   ├── FilterRenderer.kt       # OpenGL ES 2.0 renderer, shader routing
│   ├── SceneDetector.kt        # 13-scene AI detector + IIR smoothing
│   └── ShaderProgram.kt        # GLSL compile/link/uniform helper
├── di/
│   └── CameraModule.kt         # Hilt singleton providers
├── ui/
│   ├── screens/
│   │   └── CamixCameraScreen.kt # Full Compose UI — every overlay, panel, chip
│   ├── theme/
│   │   ├── Color.kt            # Cinematic dark palette
│   │   ├── Theme.kt            # MaterialTheme dark scheme
│   │   └── Type.kt             # Typography
│   └── viewmodel/
│       └── CameraViewModel.kt  # All state, 8 capture modes, audio/filter bridge
├── CamixApp.kt                 # Hilt application
└── MainActivity.kt             # Permissions, immersive fullscreen
```

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34
- Physical Android device (API 26+) for best results

### Build & Run
```bash
git clone https://github.com/yourorg/CamixUltra.git
cd CamixUltra
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Push to your repo
```bash
git init
git remote add origin https://github.com/yourorg/CamixUltra.git
git add .
git commit -m "feat: CamixUltra initial release"
git push -u origin main
```

---

## 🎨 Shader Modes

| Shader | Description |
|---|---|
| **Master** | All corrections: brightness, contrast, saturation, exposure, warmth, tint, hue, highlights, shadows, vignette, sharpen, grain, fade, clarity, dehaze |
| **Cinematic** | ACES filmic tone-map + anamorphic horizontal lens streaks + 2.39:1 letterbox |
| **Thermal** | Iron-bow false-color palette (black→blue→cyan→green→yellow→red→white) |
| **Ultrasound** | Full Sobel 3×3 edge detection over greyscale background |
| **Focus Peaking** | Laplacian edge overlay in red / white / yellow over live preview |
| **Portrait** | Skin-tone-aware bilateral blur + background defocus |
| **Night** | Bilateral denoising + gamma lift + Reinhard tone-mapping |
| **Vivid** | Vibrance-safe saturation with skin-tone protection |
| **Mono** | B&W with optional warm/cool tonal tint |
| **Simple** | Performance fallback (sub-60fps devices) |

---

## 🎤 Audio DSP Pipeline

1. **Hardware**: NoiseSuppressor → AcousticEchoCanceler → AutomaticGainControl (device-native)
2. **Wind Reduction**: 1st-order IIR high-pass at 200 Hz
3. **Voice Clarity**: Band-emphasis 1–4 kHz via sharpened moving average
4. **Low-Light Boost**: Raises gain when scene detector reports dark scene
5. **Peak Limiter**: Prevents clipping above 90 % of full-scale
6. **Metering**: RMS, peak, rolling noise-floor estimate, 128-sample waveform snapshot → UI overlay

---

## 📋 Capture Modes

| Mode | Auto Preset | Special Behaviour |
|---|---|---|
| Photo | AI Auto | Single JPEG (quality 95) |
| Video | AI Auto | CameraX VideoCapture, pause/resume |
| Portrait | Portrait | Skin-smooth shader auto-applied |
| Night | Night | Bilateral denoise + lifted exposure |
| Cinematic | Cinematic | ACES + letterbox + anamorphic |
| Thermal | Thermal | False-color iron-bow palette |
| Timelapse | — | Configurable 1s–30s interval; counts frames |
| Pro | — | Manual EV ±6 stops; all adjustment sliders |

---

## 🤝 Contributing

PRs welcome. Please run `./gradlew test` before submitting.

---

## 📄 License

MIT © ChasTechGroup
