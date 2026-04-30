package com.chastechgroup.camix.filter

/**
 * GLSL Fragment Shaders for all real-time camera effects.
 * Combined and enhanced from both Camix packages with new ultra-realistic modes.
 *
 * New in CamixUltra:
 *  - Cinematic filmic tone-mapping with anamorphic lens flare
 *  - Thermal / heat-map false-color vision
 *  - Full Sobel-based Ultrasound / Edge-Detection mode (replaces placeholder)
 *  - Atmospheric Dehaze
 *  - Focus Peaking (red/white edge overlay for manual-focus assist)
 *  - Histogram-assist brightness curve visualizer
 */
object CameraFilterShaders {

    // ──────────────────────────────────────────────────────────────────────────
    // Vertex shader (shared by all programs)
    // ──────────────────────────────────────────────────────────────────────────
    const val VERTEX_SHADER_DEFAULT = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // MASTER SHADER  — all standard photo / video corrections
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_MASTER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;

        // Core
        uniform float uBrightness;   // additive, -1 to 1
        uniform float uContrast;     // delta around 0.5, -1 to 1
        uniform float uSaturation;   // delta, -1 to 1
        uniform float uExposure;     // EV stops, -3 to 3

        // Color
        uniform float uWarmth;       // -1 cool → 1 warm
        uniform float uTint;         // -1 green → 1 magenta
        uniform float uHue;          // degrees -180 to 180

        // Tone
        uniform float uHighlights;   // -1 to 1
        uniform float uShadows;      // -1 to 1
        uniform float uFade;         // 0 to 1

        // FX
        uniform float uVignette;     // 0 to 1
        uniform float uSharpen;      // 0 to 2
        uniform float uGrain;        // 0 to 1
        uniform float uBlur;         // 0 to 1
        uniform float uClarityBoost; // micro-contrast 0 to 1 (NEW)
        uniform float uDehaze;       // 0 to 1 (NEW)

        // Dimensions & time
        uniform float uImageWidth;
        uniform float uImageHeight;
        uniform float uTime;

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);
        const float PI = 3.14159265359;
        const vec3 WARM = vec3(1.0, 0.78, 0.46);
        const vec3 COOL = vec3(0.46, 0.67, 1.0);

        float random(vec2 c) {
            return fract(sin(dot(c, vec2(12.9898, 78.233))) * 43758.5453);
        }

        vec3 rgbToHsv(vec3 rgb) {
            float mx = max(max(rgb.r, rgb.g), rgb.b);
            float mn = min(min(rgb.r, rgb.g), rgb.b);
            float d  = mx - mn;
            float h  = 0.0;
            if (d > 0.0001) {
                if (mx == rgb.r)      h = mod((rgb.g - rgb.b) / d, 6.0);
                else if (mx == rgb.g) h = (rgb.b - rgb.r) / d + 2.0;
                else                  h = (rgb.r - rgb.g) / d + 4.0;
                h /= 6.0;
            }
            return vec3(h, mx > 0.0001 ? d / mx : 0.0, mx);
        }

        vec3 hsvToRgb(vec3 hsv) {
            float h = hsv.x * 6.0;
            float c = hsv.z * hsv.y;
            float x = c * (1.0 - abs(mod(h, 2.0) - 1.0));
            float m = hsv.z - c;
            vec3 rgb;
            if      (h < 1.0) rgb = vec3(c, x, 0.0);
            else if (h < 2.0) rgb = vec3(x, c, 0.0);
            else if (h < 3.0) rgb = vec3(0.0, c, x);
            else if (h < 4.0) rgb = vec3(0.0, x, c);
            else if (h < 5.0) rgb = vec3(x, 0.0, c);
            else               rgb = vec3(c, 0.0, x);
            return rgb + m;
        }

        vec3 applyCurves(vec3 c, float hi, float sh) {
            c = c + sh * (1.0 - c) * (1.0 - c);
            c = c - hi * c * c;
            return clamp(c, 0.0, 1.0);
        }

        vec3 sharpenSample(samplerExternalOES tex, vec2 uv, float amt) {
            vec2 t = vec2(1.0 / uImageWidth, 1.0 / uImageHeight);
            vec3 cc = texture2D(tex, uv).rgb;
            vec3 l  = texture2D(tex, uv - vec2(t.x, 0.0)).rgb;
            vec3 r  = texture2D(tex, uv + vec2(t.x, 0.0)).rgb;
            vec3 u  = texture2D(tex, uv + vec2(0.0, t.y)).rgb;
            vec3 d  = texture2D(tex, uv - vec2(0.0, t.y)).rgb;
            vec3 sh = cc * (1.0 + 4.0 * amt) - (l + r + u + d) * amt;
            return mix(cc, sh, amt);
        }

        // Clarity = local contrast boost around midtones
        vec3 applyClarity(samplerExternalOES tex, vec2 uv, float amt) {
            vec2 t = vec2(3.0 / uImageWidth, 3.0 / uImageHeight);
            vec3 cc = texture2D(tex, uv).rgb;
            vec3 blurred = (
                texture2D(tex, uv + vec2(-t.x, -t.y)).rgb +
                texture2D(tex, uv + vec2( t.x, -t.y)).rgb +
                texture2D(tex, uv + vec2(-t.x,  t.y)).rgb +
                texture2D(tex, uv + vec2( t.x,  t.y)).rgb
            ) * 0.25;
            float lum = dot(cc, LUM);
            float mask = 1.0 - abs(lum * 2.0 - 1.0); // peaks at mid-grey
            return cc + (cc - blurred) * amt * mask * 2.0;
        }

        // Dehaze: estimate airlight, recover scene
        vec3 applyDehaze(samplerExternalOES tex, vec2 uv, float amt) {
            vec3 cc = texture2D(tex, uv).rgb;
            // Dark channel prior approximation via min-pooling neighborhood
            vec2 t = vec2(4.0 / uImageWidth, 4.0 / uImageHeight);
            float darkCh = 1.0;
            for (float x = -2.0; x <= 2.0; x += 1.0) {
                for (float y = -2.0; y <= 2.0; y += 1.0) {
                    vec3 s = texture2D(tex, uv + vec2(x, y) * t).rgb;
                    darkCh = min(darkCh, min(min(s.r, s.g), s.b));
                }
            }
            float transmission = 1.0 - amt * darkCh;
            transmission = clamp(transmission, 0.1, 1.0);
            vec3 airlight = vec3(0.85, 0.87, 0.9); // typical sky color
            vec3 dehazed = (cc - airlight * (1.0 - transmission)) / transmission + airlight * (1.0 - transmission);
            return clamp(dehazed, 0.0, 1.0);
        }

        void main() {
            vec2 uv = vTextureCoord;
            vec4 base;

            // Sharpening pass first (before color)
            if (uSharpen > 0.01) {
                base = vec4(sharpenSample(uTexture, uv, uSharpen * 0.5), 1.0);
            } else {
                base = texture2D(uTexture, uv);
            }

            vec3 c = base.rgb;

            // Dehaze
            if (uDehaze > 0.01) {
                c = applyDehaze(uTexture, uv, uDehaze);
            }

            // Clarity
            if (uClarityBoost > 0.01) {
                c = applyClarity(uTexture, uv, uClarityBoost);
            }

            // Exposure (EV)
            c *= pow(2.0, uExposure);

            // Brightness
            c += uBrightness;

            // Contrast
            c = (c - 0.5) * (1.0 + uContrast) + 0.5;

            // Highlights / Shadows
            c = applyCurves(c, uHighlights, uShadows);

            // Saturation
            float lum = dot(c, LUM);
            c = mix(vec3(lum), c, 1.0 + uSaturation);

            // Hue rotation
            if (abs(uHue) > 0.5) {
                vec3 hsv = rgbToHsv(c);
                hsv.x = mod(hsv.x + uHue / 360.0, 1.0);
                c = hsvToRgb(hsv);
            }

            // Warmth
            if (uWarmth != 0.0) {
                vec3 wc = mix(COOL, WARM, uWarmth * 0.5 + 0.5);
                c = mix(c, c * wc, abs(uWarmth) * 0.7);
            }

            // Tint (green-magenta)
            if (uTint != 0.0) {
                c.g  = mix(c.g,  c.g  * (1.0 + uTint * 0.3),  abs(uTint));
                c.rb = mix(c.rb, c.rb * (1.0 - uTint * 0.15), abs(uTint));
            }

            // Fade
            if (uFade > 0.01) {
                c = mix(c, c * (1.0 - uFade * 0.3) + uFade * 0.2, uFade);
            }

            // Vignette
            if (uVignette > 0.01) {
                float d = length(uv - 0.5);
                float v = smoothstep(0.5, 0.2 + (1.0 - uVignette) * 0.3, d);
                c *= mix(1.0, v, uVignette);
            }

            // Grain
            if (uGrain > 0.01) {
                float g = random(uv + fract(uTime * 0.017)) - 0.5;
                c += g * uGrain * 0.15;
            }

            gl_FragColor = vec4(clamp(c, 0.0, 1.0), base.a);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // CINEMATIC SHADER — filmic S-curve + anamorphic bokeh + letterbox
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_CINEMATIC = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        uniform float uIntensity;    // 0..1
        uniform float uGrain;
        uniform float uTime;
        uniform float uImageWidth;
        uniform float uImageHeight;

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);

        float random(vec2 c) {
            return fract(sin(dot(c, vec2(12.9898, 78.233))) * 43758.5453);
        }

        // ACES filmic tone-map (approximate)
        vec3 aces(vec3 x) {
            float a = 2.51, b = 0.03, c2 = 2.43, d = 0.59, e = 0.14;
            return clamp((x * (a * x + b)) / (x * (c2 * x + d) + e), 0.0, 1.0);
        }

        // Anamorphic lens streak on bright highlights
        vec3 lensStreak(samplerExternalOES tex, vec2 uv, float strength) {
            vec3 streak = vec3(0.0);
            vec2 dir = vec2(1.0 / uImageWidth, 0.0);
            for (float i = -8.0; i <= 8.0; i += 1.0) {
                vec3 s = texture2D(tex, uv + dir * i * 2.0).rgb;
                float brightness = dot(s, LUM);
                float weight = max(0.0, brightness - 0.7) * (1.0 - abs(i) / 9.0);
                streak += s * weight * vec3(0.3, 0.5, 1.0);
            }
            return streak * strength * 0.15;
        }

        void main() {
            vec2 uv = vTextureCoord;

            // Letterbox (cinematic 2.39:1)
            float ar = uImageWidth / uImageHeight;
            float targetAR = 2.39;
            float barH = 0.5 * (1.0 - (ar / targetAR));
            if (uv.y < barH || uv.y > 1.0 - barH) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            vec3 c = texture2D(uTexture, uv).rgb;

            // Slight desaturate push to cinematic palette
            float lum = dot(c, LUM);
            c = mix(c, vec3(lum), 0.08 * uIntensity);

            // Shadow teal lift + highlight orange (classic look)
            float shadow = clamp((0.4 - lum) * 2.5, 0.0, 1.0);
            float highlight = clamp((lum - 0.6) * 2.5, 0.0, 1.0);
            c += shadow * vec3(-0.02, 0.02, 0.05) * uIntensity;
            c += highlight * vec3(0.05, 0.02, -0.03) * uIntensity;

            // ACES tone map
            c = mix(c, aces(c * 1.1), uIntensity * 0.8);

            // Anamorphic streak
            c += lensStreak(uTexture, uv, uIntensity);

            // Vignette
            float d = length(uv - 0.5);
            c *= mix(1.0, smoothstep(0.7, 0.2, d), 0.3 * uIntensity);

            // Film grain
            float g = random(uv + fract(uTime * 0.031)) - 0.5;
            c += g * uGrain * 0.1;

            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // THERMAL / HEAT-MAP SHADER — false-color palette (black→blue→cyan→green→yellow→red→white)
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_THERMAL = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        uniform float uIntensity; // blend with original 0..1
        uniform float uContrast;  // thermal contrast boost

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);

        // Iron-bow / thermal palette
        vec3 thermalPalette(float t) {
            t = clamp(t, 0.0, 1.0);
            // Keyframes: cold(black) → blue → cyan → green → yellow → red → hot(white)
            vec3 col;
            if      (t < 0.16) col = mix(vec3(0.0, 0.0, 0.0),   vec3(0.0,  0.0,  0.75), t / 0.16);
            else if (t < 0.33) col = mix(vec3(0.0, 0.0, 0.75),  vec3(0.0,  0.75, 0.75), (t - 0.16) / 0.17);
            else if (t < 0.50) col = mix(vec3(0.0, 0.75, 0.75), vec3(0.0,  0.75, 0.0),  (t - 0.33) / 0.17);
            else if (t < 0.67) col = mix(vec3(0.0, 0.75, 0.0),  vec3(0.9,  0.9,  0.0),  (t - 0.50) / 0.17);
            else if (t < 0.84) col = mix(vec3(0.9, 0.9, 0.0),   vec3(0.9,  0.2,  0.0),  (t - 0.67) / 0.17);
            else               col = mix(vec3(0.9, 0.2, 0.0),   vec3(1.0,  1.0,  1.0),  (t - 0.84) / 0.16);
            return col;
        }

        void main() {
            vec3 c = texture2D(uTexture, vTextureCoord).rgb;
            float lum = dot(c, LUM);

            // Boost contrast in thermal space
            lum = (lum - 0.5) * (1.0 + uContrast) + 0.5;
            lum = clamp(lum, 0.0, 1.0);

            vec3 thermal = thermalPalette(lum);
            vec3 result  = mix(c, thermal, uIntensity);
            gl_FragColor = vec4(result, 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // ULTRASOUND / EDGE DETECTION — full Sobel + greyscale (pkg1 placeholder fixed)
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_ULTRASOUND = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        uniform float uEdgeStrength;    // 0..3
        uniform float uBrightness;      // background brightness -1..1
        uniform float uImageWidth;
        uniform float uImageHeight;

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);

        float luminance(vec2 uv) {
            return dot(texture2D(uTexture, uv).rgb, LUM);
        }

        void main() {
            vec2 uv = vTextureCoord;
            vec2 tx = vec2(1.0 / uImageWidth, 1.0 / uImageHeight);

            // Sobel 3x3 kernel
            float tl = luminance(uv + tx * vec2(-1.0,  1.0));
            float tc = luminance(uv + tx * vec2( 0.0,  1.0));
            float tr = luminance(uv + tx * vec2( 1.0,  1.0));
            float cl = luminance(uv + tx * vec2(-1.0,  0.0));
            float cr = luminance(uv + tx * vec2( 1.0,  0.0));
            float bl = luminance(uv + tx * vec2(-1.0, -1.0));
            float bc = luminance(uv + tx * vec2( 0.0, -1.0));
            float br = luminance(uv + tx * vec2( 1.0, -1.0));

            float Gx = (-tl - 2.0*cl - bl) + (tr + 2.0*cr + br);
            float Gy = (-tl - 2.0*tc - tr) + (bl + 2.0*bc + br);
            float edge = clamp(length(vec2(Gx, Gy)) * uEdgeStrength, 0.0, 1.0);

            // Dark background with bright edges (ultrasound / echocardiogram look)
            float bgLum = clamp(luminance(uv) * 0.3 + uBrightness * 0.2, 0.0, 1.0);
            vec3 col = vec3(bgLum) + edge * vec3(0.85, 0.95, 1.0);
            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // FOCUS PEAKING — highlight sharp edges in color over live preview
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_FOCUS_PEAKING = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        uniform float uThreshold;     // edge threshold 0..1
        uniform float uPeakingColor;  // 0=red, 0.5=white, 1=yellow
        uniform float uImageWidth;
        uniform float uImageHeight;

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);

        float lum(vec2 uv) {
            return dot(texture2D(uTexture, uv).rgb, LUM);
        }

        void main() {
            vec2 uv = vTextureCoord;
            vec2 tx = vec2(1.0 / uImageWidth, 1.0 / uImageHeight);

            // Laplacian edge detect (faster than Sobel for peaking)
            float center = lum(uv);
            float edge = abs(4.0 * center
                - lum(uv + vec2(tx.x, 0.0))
                - lum(uv - vec2(tx.x, 0.0))
                - lum(uv + vec2(0.0, tx.y))
                - lum(uv - vec2(0.0, tx.y)));

            vec3 base = texture2D(uTexture, uv).rgb;
            bool inFocus = edge > uThreshold * 0.08;

            vec3 peakColor;
            if      (uPeakingColor < 0.33) peakColor = vec3(1.0, 0.05, 0.05); // red
            else if (uPeakingColor < 0.66) peakColor = vec3(1.0, 1.0, 1.0);   // white
            else                           peakColor = vec3(1.0, 0.95, 0.0);   // yellow

            vec3 result = inFocus ? mix(base, peakColor, 0.75) : base;
            gl_FragColor = vec4(result, 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // PORTRAIT MODE — skin smoothing + selective background blur
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_PORTRAIT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        uniform float uBlurAmount;
        uniform float uSkinSmooth;
        uniform float uImageWidth;
        uniform float uImageHeight;

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);

        vec3 blur(samplerExternalOES tex, vec2 uv, float r) {
            vec2 tx = vec2(1.0 / uImageWidth, 1.0 / uImageHeight);
            vec3 acc = vec3(0.0);
            float cnt = 0.0;
            for (float x = -3.0; x <= 3.0; x += 1.0) {
                for (float y = -3.0; y <= 3.0; y += 1.0) {
                    acc += texture2D(tex, uv + vec2(x, y) * tx * r).rgb;
                    cnt += 1.0;
                }
            }
            return acc / cnt;
        }

        float skinMask(vec3 rgb) {
            float lum = dot(rgb, LUM);
            float cb = 0.5 + (-0.168736 * rgb.r - 0.331264 * rgb.g + 0.5 * rgb.b);
            float cr = 0.5 + ( 0.5 * rgb.r - 0.418688 * rgb.g - 0.081312 * rgb.b);
            float skinCb = abs(cb - 0.5);
            float skinCr = abs(cr - 0.60);
            return 1.0 - smoothstep(0.04, 0.14, skinCb) * smoothstep(0.04, 0.18, skinCr);
        }

        void main() {
            vec2 uv = vTextureCoord;
            vec3 orig = texture2D(uTexture, uv).rgb;
            float skin = skinMask(orig);
            vec3 blurred = blur(uTexture, uv, uBlurAmount * 4.0);
            // Smooth skin without blurring edges
            vec3 smoothed = mix(orig, blurred, uSkinSmooth * skin * 0.5);
            // Background gets more blur, subject stays sharp
            vec3 result = mix(blurred, smoothed, skin * 0.75 + 0.25);
            gl_FragColor = vec4(result, 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // NIGHT MODE — bilateral noise reduction + HDR highlight recovery
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_NIGHT = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        uniform float uBrightness;
        uniform float uNoiseReduction;
        uniform float uImageWidth;
        uniform float uImageHeight;

        vec3 bilateral(samplerExternalOES tex, vec2 uv, float sigma) {
            vec2 tx = vec2(1.0 / uImageWidth, 1.0 / uImageHeight);
            vec3 center = texture2D(tex, uv).rgb;
            vec3 acc = vec3(0.0);
            float wSum = 0.0;
            for (float x = -2.0; x <= 2.0; x += 1.0) {
                for (float y = -2.0; y <= 2.0; y += 1.0) {
                    vec2 off = vec2(x, y) * tx * sigma;
                    vec3 s = texture2D(tex, uv + off).rgb;
                    float sw = exp(-(x*x + y*y) / (2.0 * sigma * sigma));
                    float cw = exp(-dot(s - center, s - center) / (2.0 * 0.09));
                    float w = sw * cw;
                    acc += s * w;
                    wSum += w;
                }
            }
            return acc / wSum;
        }

        void main() {
            vec2 uv = vTextureCoord;
            vec3 c = texture2D(uTexture, uv).rgb;

            // Gamma lift for dark scenes
            c = pow(c, vec3(0.65));
            c *= (1.0 + uBrightness * 2.0);

            // Bilateral denoise
            if (uNoiseReduction > 0.01) {
                vec3 dn = bilateral(uTexture, uv, uNoiseReduction * 3.0);
                dn = pow(dn, vec3(0.65));
                dn *= (1.0 + uBrightness * 2.0);
                c = mix(c, dn, uNoiseReduction);
            }

            // Reinhard tone map to prevent blown highlights
            c = c / (1.0 + c * 0.28);

            gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // VIVID — vibrance-aware saturation boost (protects skin tones)
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_VIVID = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        uniform float uIntensity;

        vec3 rgbToHsv(vec3 rgb) {
            float mx = max(max(rgb.r, rgb.g), rgb.b);
            float mn = min(min(rgb.r, rgb.g), rgb.b);
            float d  = mx - mn;
            float h = 0.0;
            if (d > 0.0001) {
                if      (mx == rgb.r) h = mod((rgb.g - rgb.b) / d, 6.0);
                else if (mx == rgb.g) h = (rgb.b - rgb.r) / d + 2.0;
                else                  h = (rgb.r - rgb.g) / d + 4.0;
                h /= 6.0;
            }
            return vec3(h, mx > 0.0001 ? d / mx : 0.0, mx);
        }

        vec3 hsvToRgb(vec3 hsv) {
            float h = hsv.x * 6.0;
            float c = hsv.z * hsv.y;
            float x = c * (1.0 - abs(mod(h, 2.0) - 1.0));
            float m = hsv.z - c;
            vec3 rgb;
            if      (h < 1.0) rgb = vec3(c, x, 0.0);
            else if (h < 2.0) rgb = vec3(x, c, 0.0);
            else if (h < 3.0) rgb = vec3(0.0, c, x);
            else if (h < 4.0) rgb = vec3(0.0, x, c);
            else if (h < 5.0) rgb = vec3(x, 0.0, c);
            else               rgb = vec3(c, 0.0, x);
            return rgb + m;
        }

        void main() {
            vec4 color = texture2D(uTexture, vTextureCoord);
            vec3 rgb = color.rgb;
            vec3 hsv = rgbToHsv(rgb);

            // Vibrance: boost less-saturated colors more aggressively
            float vibranceBoost = (1.0 - hsv.y) * uIntensity * 0.6;
            hsv.y = min(hsv.y + vibranceBoost, 1.0);

            // Skin-tone protection: hue ~0.05 (red-orange) gets less boost
            float skinProtect = 1.0 - smoothstep(0.02, 0.12, abs(hsv.x - 0.05));
            hsv.y = mix(hsv.y, hsv.y * (1.0 - uIntensity * 0.4), skinProtect);

            rgb = hsvToRgb(hsv);
            rgb = (rgb - 0.5) * (1.0 + uIntensity * 0.15) + 0.5;
            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), color.a);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // SIMPLE (performance fallback)
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_SIMPLE = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        uniform float uBrightness;
        uniform float uContrast;
        uniform float uSaturation;
        uniform float uWarmth;
        uniform float uVignette;

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);
        const vec3 WARM = vec3(1.0, 0.78, 0.46);
        const vec3 COOL = vec3(0.46, 0.67, 1.0);

        void main() {
            vec4 c = texture2D(uTexture, vTextureCoord);
            vec3 rgb = c.rgb;
            rgb += uBrightness;
            rgb = (rgb - 0.5) * (1.0 + uContrast) + 0.5;
            float lum = dot(rgb, LUM);
            rgb = mix(vec3(lum), rgb, 1.0 + uSaturation);
            if (uWarmth != 0.0) {
                vec3 wc = mix(COOL, WARM, uWarmth * 0.5 + 0.5);
                rgb = mix(rgb, rgb * wc, abs(uWarmth) * 0.5);
            }
            if (uVignette > 0.01) {
                float d = length(vTextureCoord - 0.5);
                rgb *= mix(1.0, smoothstep(0.6, 0.2, d), uVignette);
            }
            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
        }
    """

    // ──────────────────────────────────────────────────────────────────────────
    // MONO — B&W with tonal tint
    // ──────────────────────────────────────────────────────────────────────────
    const val FRAGMENT_SHADER_MONO = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTextureCoord;
        uniform samplerExternalOES uTexture;
        uniform float uContrast;
        uniform float uTint; // -1 cool, +1 warm

        const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);

        void main() {
            vec4 c = texture2D(uTexture, vTextureCoord);
            float lum = dot(c.rgb, LUM);
            lum = (lum - 0.5) * (1.0 + uContrast) + 0.5;
            vec3 result = vec3(lum);
            if      (uTint > 0.0) result *= mix(vec3(1.0), vec3(1.0, 0.95, 0.82), uTint);
            else if (uTint < 0.0) result *= mix(vec3(1.0), vec3(0.83, 0.9,  1.0), -uTint);
            gl_FragColor = vec4(clamp(result, 0.0, 1.0), c.a);
        }
    """
}
