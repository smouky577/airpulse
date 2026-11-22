package com.airpulse.app.pulse

/**
 * GLSL ES 3.00 sources for the Pulse Overlay pipeline (Eulerian colour magnification,
 * same math as AirPulse's PulseStream::magnify, but on a grayscale base and on the GPU).
 *
 * Pyramid levels and filter states are RGBA32F holding YIQ (NTSC) in .rgb.
 *
 * Coordinate conventions: vUv is the *display-oriented* frame coordinate in [0,1] (y up);
 * vTex is the same point mapped into the camera buffer (rotation, mirror and the
 * SurfaceTexture transform applied by uTexMatrix).
 */
object Shaders {

    /** Full-screen quad generated from gl_VertexID (draw 4 vertices as a triangle strip, no VBO). */
    const val VERTEX = """#version 300 es
uniform mat4 uTexMatrix;
out vec2 vUv;
out vec2 vTex;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vUv = p;
    vTex = (uTexMatrix * vec4(p, 0.0, 1.0)).xy;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    private const val YIQ = """
vec3 rgb2yiq(vec3 c) {
    return vec3(dot(c, vec3(0.299, 0.587, 0.114)),
                dot(c, vec3(0.596, -0.274, -0.322)),
                dot(c, vec3(0.211, -0.523, 0.312)));
}
vec3 yiq2rgb(vec3 c) {
    return vec3(dot(c, vec3(1.0, 0.956, 0.621)),
                dot(c, vec3(1.0, -0.272, -0.647)),
                dot(c, vec3(1.0, -1.106, 1.703)));
}
"""

    /**
     * Camera (full res) -> first pyramid level, YIQ.
     * 3x3 bilinear taps with [1 2 1] weights centred between source texels give an
     * effective [1 3 3 1]/8 binomial kernel per axis = Gaussian blur + 2x decimation.
     */
    const val DOWN_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform vec2 uStepX;
uniform vec2 uStepY;
in vec2 vUv;
in vec2 vTex;
out vec4 o;
""" + YIQ + """
vec3 t(vec2 c) { return texture(uTex, c).rgb; }
void main() {
    vec3 s = 4.0 * t(vTex)
        + 2.0 * (t(vTex + uStepX) + t(vTex - uStepX) + t(vTex + uStepY) + t(vTex - uStepY))
        + t(vTex + uStepX + uStepY) + t(vTex + uStepX - uStepY)
        + t(vTex - uStepX + uStepY) + t(vTex - uStepX - uStepY);
    o = vec4(rgb2yiq(s / 16.0), 1.0);
}
"""

    /** pyrDown like AirPulse/OpenCV: separable [1 4 6 4 1]/16, reflect-101 borders, keep even samples. */
    const val DOWN = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uSrc;
uniform ivec2 uSrcSize;
out vec4 o;
int refl(int i, int n) {
    if (n == 1) return 0;
    if (i < 0) i = -i;
    if (i >= n) i = 2 * n - 2 - i;
    return clamp(i, 0, n - 1);
}
void main() {
    const float w[5] = float[5](1.0, 4.0, 6.0, 4.0, 1.0);
    ivec2 c = ivec2(gl_FragCoord.xy) * 2;
    vec3 acc = vec3(0.0);
    for (int j = 0; j < 5; j++) {
        int y = refl(c.y + j - 2, uSrcSize.y);
        for (int i = 0; i < 5; i++) {
            int x = refl(c.x + i - 2, uSrcSize.x);
            acc += w[i] * w[j] * texelFetch(uSrc, ivec2(x, y), 0).rgb;
        }
    }
    o = vec4(acc / 256.0, 1.0);
}
"""

    /** One first-order temporal low-pass step on every pixel: lp += a * (x - lp). */
    const val IIR = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uLevel;
uniform sampler2D uPrev;
uniform float uA;
uniform int uInit;
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 x = texelFetch(uLevel, p, 0).rgb;
    vec3 lp = uInit == 1 ? x : texelFetch(uPrev, p, 0).rgb;
    lp += uA * (x - lp);
    o = vec4(lp, 1.0);
}
"""

    /**
     * Per-pixel lock-in demodulation of the band-passed I (red/blue) chroma at f0,
     * exponential averaging (k = 1 - exp(-dt/tau)). State texture (RG32F): r = I, g = Q.
     */
    const val LOCKIN = """#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uHigh;
uniform sampler2D uLow;
uniform sampler2D uLock;
uniform float uCos;
uniform float uSin;
uniform float uK;
uniform int uReset;
out vec4 o;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float s = texelFetch(uHigh, p, 0).g - texelFetch(uLow, p, 0).g;
    vec2 iq = uReset == 1 ? vec2(0.0) : texelFetch(uLock, p, 0).rg;
    iq += uK * (vec2(s * uCos, s * uSin) - iq);
    o = vec4(iq, 0.0, 1.0);
}
"""

    /**
     * Final composite at full resolution.
     * Base = Y of the camera frame (grayscale, chroma removed). The band-passed YIQ signal
     * from the pyramid top is upsampled bilinearly on the GPU, amplified (Y by uLumaGain,
     * I/Q by uAlpha) and *added*, exactly like AirPulse - so the pulse shows up as a red/blue
     * (I) and magenta/green (Q) tint that breathes with the heartbeat, on a gray picture.
     *
     * uMode: 0 = magnified pulse, 1 = lock-in amplitude heatmap, 2 = lock-in phase hue.
     */
    const val COMPOSITE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp sampler2D;
uniform samplerExternalOES uCam;
uniform sampler2D uHigh;
uniform sampler2D uLow;
uniform sampler2D uLock;
uniform ivec2 uLevelSize;
uniform vec2 uFrameSize;
uniform float uLevelDiv;
uniform int uMode;
uniform float uAlpha;
uniform float uLumaGain;
uniform float uThr;
uniform float uHeatScale;
uniform vec3 uRoi;
in vec2 vUv;
in vec2 vTex;
out vec4 o;
""" + YIQ + """
struct Taps { ivec2 a; ivec2 b; ivec2 c; ivec2 d; vec2 f; };

Taps taps(vec2 pos) {
    pos = clamp(pos, vec2(0.0), vec2(uLevelSize - 1));
    Taps t;
    t.a = ivec2(floor(pos));
    ivec2 i1 = min(t.a + 1, uLevelSize - 1);
    t.b = ivec2(i1.x, t.a.y);
    t.c = ivec2(t.a.x, i1.y);
    t.d = i1;
    t.f = pos - vec2(t.a);
    return t;
}

vec4 bilin(sampler2D s, Taps t) {
    return mix(mix(texelFetch(s, t.a, 0), texelFetch(s, t.b, 0), t.f.x),
               mix(texelFetch(s, t.c, 0), texelFetch(s, t.d, 0), t.f.x), t.f.y);
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

vec3 heat(float t) {
    return clamp(vec3(3.0 * t, 3.0 * t - 1.0, 3.0 * t - 2.0), 0.0, 1.0);
}

void main() {
    vec3 cam = texture(uCam, vTex).rgb;
    float y = dot(cam, vec3(0.299, 0.587, 0.114));
    vec2 px = vUv * uFrameSize;
    Taps t = taps(px / uLevelDiv - 0.5);
    vec3 col;

    if (uMode == 0) {
        vec3 band = bilin(uHigh, t).rgb - bilin(uLow, t).rgb;
        vec2 chroma = uAlpha * band.yz;
        // Soft threshold: magnitudes below uThr fade to nothing (transparent), no hard edges.
        float m = length(chroma);
        chroma *= m > 1e-6 ? max(m - uThr, 0.0) / m : 0.0;
        col = clamp(yiq2rgb(vec3(y + uLumaGain * band.x, chroma)), 0.0, 1.0);
    } else {
        vec2 iq = bilin(uLock, t).rg;
        float A = uAlpha * 2.0 * length(iq);
        float a = clamp((A - uThr) / uHeatScale, 0.0, 1.0);
        vec3 c = uMode == 2
            ? hsv2rgb(vec3(atan(iq.y, iq.x) / 6.2831853 + 0.5, 1.0, 1.0))
            : heat(0.35 + 0.65 * a);
        col = mix(vec3(y), c, a);
    }

    if (uRoi.z > 0.0) {
        float d = abs(length(px - uRoi.xy) - uRoi.z);
        col = mix(col, vec3(1.0), smoothstep(3.0, 1.0, d) * 0.5);
    }
    o = vec4(col, 1.0);
}
"""
}
