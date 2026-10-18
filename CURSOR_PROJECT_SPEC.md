# Project spec: "AirPulse" – live pulse + air-flow (BOS) camera for Huawei Pura 80 Pro, plus a Windows checkerboard app

Paste this whole file into Cursor (or save it in the repo root as `AGENTS.md` / `.cursor/rules`). Put `evm_prototype.py` next to it – it is the reference implementation of the algorithms.

## 0. Rules for the agent (read first)

1. Work in small milestones (section 8). After each one: build, run tests, summarize, wait for my feedback.
2. **Do not guess HarmonyOS APIs.** Before using any Camera Kit / ImageKit / NAPI / GPU API, check the current Huawei developer documentation (developer.huawei.com, HarmonyOS 5.x) and cite the page you used in a code comment. If an API does not exist or differs, say so and propose an alternative instead of inventing one.
3. The phone runs **HarmonyOS (not Android)**. Android APKs and Android-only libraries (including prebuilt OpenCV for Android) will not work. Write native HarmonyOS code: **ArkTS** for UI and camera, **C++ via NAPI/NDK** for the heavy math. If you want OpenCV in C++, verify it can be cross-compiled for HarmonyOS first; otherwise implement the few needed algorithms yourself (they are small, see section 4).
4. I can't test on a device inside Cursor. I will install builds with DevEco Studio and report back (fps, screenshots, logs). Make the app write a small on-screen debug overlay (fps, processing ms/frame, resolution) and a log file so I can send you real numbers.
5. Be honest about what is verified. The Python prototype was only tested on **synthetic** videos, never on a real camera, real face, or real monitor.
6. Keep the code readable and commented. Language of UI: Bulgarian (strings in a resource file, English fallback).

## 1. Goal

Two deliverables:

**A. Phone app (HarmonyOS, Huawei Pura 80 Pro)** with two modes sharing one camera pipeline:
- **Pulse mode:** amplify the tiny color change of skin with each heartbeat (Eulerian video magnification) and show heart rate in bpm. Works **live** and on **recorded** clips.
- **Air mode:** see air movement (warm air, breath, flame plume) by Background Oriented Schlieren (BOS): the camera films a fine checkerboard shown on a monitor behind the air; refraction shifts the pattern slightly; the app computes the shift against a reference frame and draws it as a heat-map. **Live** (as fast as the phone can manage) and on **recorded** clips.

**B. Windows app "GridScreen"** that shows the checkerboard (full screen, pixel-exact) on a monitor, with controls. It is separate from the phone app.

Out of scope: real sound visualization (needs thousands of fps, a phone can't do it), cloud, accounts, ads.

## 2. Phone app – features

### Common
- Camera preview (rear camera by default, front camera optional for pulse).
- Mode switch: Pulse / Air.
- **Camera control for measurement quality** (very important, see section 5): lock auto-exposure, auto-focus, auto-white-balance with one button; set exposure time manually; choose resolution and fps from what the device actually supports (query it, don't hard-code).
- **Record raw:** save frames **without lossy video compression** (e.g. NV12/YUV or PNG/JPEG-quality-100 sequence in chunks, with a header file containing fps, resolution, timestamps, camera settings). A high-bitrate H.265 fallback is allowed but must show a warning that compression destroys the signal. Add a recording length limit based on free storage.
- **Open recording:** process a saved raw clip offline with the same code path as live, and export a result video (MP4) + a JSON/CSV with numbers.
- Debug overlay toggle (fps, ms/frame, resolution, dropped frames).

### Pulse mode
- Face ROI (forehead + cheeks). Use HarmonyOS face detection if available (check Core Vision Kit docs); otherwise a draggable/resizable manual ROI box. Re-detect every ~0.5 s, smooth ROI position.
- Live view = camera image with amplified color (section 4.1) + green ROI box + bpm text + a small scrolling plot of the ROI signal.
- BPM updates every ~0.5 s once ≥ 6 s of data exist; use a sliding window of ~12 s. Show "collecting data…" before that and a quality hint (e.g. "too much motion", "light changing") when the signal is bad.
- Sliders: amplification (default 60), low/high cutoff (0.8–2.0 Hz), pyramid level (default 4).

### Air mode
- Step 1 "Capture reference": user keeps the air clear (nothing between camera and monitor); app takes the median of the first N (default 30) frames as reference.
- Step 2 live: for every frame compute displacement vs the reference (section 4.2) and show a color map (inferno) of displacement magnitude, optionally blended over the camera image. Slider for gain (default 3.0: displacement of 1/gain pixel = full scale), blur, optical-flow window, and an optional high-pass (drift removal, default off).
- "Re-capture reference" button (the phone or screen may drift).
- Show the 99.5th-percentile displacement in px on screen, so I can tune the gain.
- Resolution and fps trade-off: let the user pick (e.g. 480p/720p, 30/60 fps). Do not promise a number – measure it on the device and report.

## 3. Windows app "GridScreen" – features

- Shows a black/white **checkerboard** filling the whole selected monitor, **pixel-exact**: the app must be **DPI-aware** (no Windows scaling blur) and must not resample the pattern. Verify with a screenshot test.
- Controls (small window or hotkeys, hidden in fullscreen): cell size in screen pixels (1–16, default 2), choose monitor, fullscreen toggle, invert colors, brightness of the white squares (to avoid camera saturation), pattern type: checkerboard / random dots / horizontal+vertical lines.
- "Shift mode" (optional, milestone 6): periodically move the pattern by a sub-cell offset or switch between several patterns, so the phone app can later use several references and median them (this reduces systematic error in BOS).
- Option to export the pattern as a PNG at a chosen resolution (also useful for printing).
- Technology: your choice, but it must be a single-file/portable Windows build (e.g. Python + pygame/PySide6 packaged with PyInstaller, or C#/.NET). State your choice and why. Avoid anything that needs the user to install a runtime separately.

## 4. Algorithms (reference: `evm_prototype.py`)

### 4.1 Pulse – Eulerian color magnification (streaming, no whole-clip FFT)
Per frame:
1. Convert to float, to **YIQ** (matrix in the prototype).
2. Spatial low-pass: Gaussian pyramid `pyrDown` × `level` (default 4) on the working-resolution frame (max width 640).
3. **IIR band-pass** per pixel as the difference of two one-pole low-passes:
   `lp += a * (x - lp)` with `a = 1 - exp(-2π f / fps)`; run it for f_high (2.0 Hz) and f_low (0.8 Hz); `band = lp_high - lp_low`.
4. Multiply `band` by gain (alpha on the I and Q channels, 0 on Y unless the user enables luma gain), upsample to full size, add to the frame, convert back to RGB.
5. **BPM:** average the **green channel of the raw frame** in the ROI each frame; keep ~12 s; remove the 1-second moving-average trend; Hann window; zero-padded FFT (n = 2^15); peak in 0.8–2.0 Hz ×60 = bpm.
Memory use must stay constant (only a few frame-sized float buffers). The prototype's original problem (an `ideal temporal bandpass` FFT over a 4K clip needing 198 GiB) must not happen.

### 4.2 Air – BOS
1. Reference = per-pixel **median** of the first N grayscale frames (no disturbance).
2. Optional small Gaussian blur (σ ≈ 0.8 px) against sensor noise.
3. Displacement field between reference and current frame by **dense optical flow** (prototype uses Farneback, window 15, 3 pyramid levels). On the phone this is the heaviest part: first implement a straightforward C++ version at reduced resolution, measure ms/frame, then optimize (NEON, multi-thread, GPU compute if the HarmonyOS docs support it). If Farneback is too slow, evaluate **block matching with sub-pixel refinement** or demodulation of the checkerboard in the Fourier domain (published as "Fast Checkerboard Demodulation") – decide using measurements, not assumptions, and tell me what you chose.
4. Magnitude = sqrt(dx² + dy²); visualization = clip(mag × gain × 255), inferno colormap.
5. Optional running-mean subtraction of the displacement (high-pass) to remove slow drift.

## 5. Camera and capture requirements (these decide whether it works at all)

- **Lock AE, AF, AWB.** Auto adjustments are much larger than the pulse or air signal and get amplified.
- **Mains flicker:** I'm in Bulgaria (50 Hz). Use an exposure time that is a multiple of 10 ms (e.g. 1/100 s) when the light is artificial; flicker also from the monitor backlight can create banding – the app should let me change exposure time and show a flicker warning if the mean brightness oscillates at 100/120 Hz aliases.
- **Use raw frames from the camera preview/analysis stream (YUV buffer), not encoded video.** Lossy compression removes exactly the micro-signals we measure.
- **Stability:** tripod or fixed support. Add a simple global-motion check (median flow of the whole frame); if above a threshold show "phone is moving".
- **Air mode geometry:** camera focused on the monitor surface, pattern cell appears as ~2–4 camera pixels (the app should show a "pattern visible / too blurry" indicator based on local contrast of the checkerboard). Air/object sits between camera and monitor, closer to the camera or midway.
- Pulse mode needs 30 fps; air mode benefits from 60 fps if the device offers it at a usable resolution.

## 6. Project structure (suggested)

```
/airpulse-phone        HarmonyOS project (ArkTS UI + C++ native lib via NAPI)
  entry/src/main/ets/          UI, camera, ROI, recording, playback
  entry/src/main/cpp/          evm_pulse.cpp, bos_flow.cpp, utils, CMake
/gridscreen-windows    Windows checkerboard app
/reference             evm_prototype.py (+ sample scripts)
/tests                 desktop test harness for the C++ core (see below)
```

Make the C++ core (`evm_pulse`, `bos_flow`) **platform-independent** (plain C++17, no HarmonyOS headers) so it can be built and unit-tested on a desktop against the Python prototype's output.

## 7. Testing (do this on the desktop, without the phone)

- Use `evm_prototype.py demo` to generate `demo_pulse.avi` (true pulse 72 bpm) and `demo_air.avi` (simulated plume). The C++ core, run on the same files, must give: BPM within ±2 of 72, and a BOS heat-map that visually matches the prototype's output. Add automated comparison tests (numerical tolerance against the Python results on the same frames).
- Unit tests: IIR filter step response, BPM estimator on synthetic sinusoids of known frequency and with added noise, checkerboard generator (exact pixels), YIQ round-trip.
- On-device checks I will do by hand: preview fps, ms/frame per mode, battery/thermal behavior, recording and playback, real-face pulse vs a reference (smartwatch or finger pulse oximeter), real flame / breath in front of a monitor.

## 8. Milestones

1. **Desktop C++ core + tests** (pulse and BOS) matching the Python prototype on the demo files.
2. **GridScreen (Windows)**: checkerboard, DPI-aware, controls, PNG export. Pixel-exact screenshot test.
3. **Phone skeleton**: HarmonyOS project builds in DevEco Studio, camera preview works, camera setting lock, debug overlay. Report which resolutions/fps the device really offers.
4. **Pulse mode on phone**: live + bpm + ROI, then raw recording and offline processing.
5. **Air mode on phone**: reference capture, live heat-map at whatever fps is achievable, recording and offline processing. Report real ms/frame; propose optimizations based on measurements.
6. **Polish**: multiple-reference mode (GridScreen shift mode + median in phone app), quality hints, Bulgarian UI strings, settings persistence, README with a measurement guide (setup photos/diagrams, tripod, distances, lighting).

## 9. Known unknowns (tell me early if any of these block you)

- Whether HarmonyOS exposes the raw YUV preview/analysis stream at full quality, locking of AE/AF/AWB, manual exposure time, and 60 fps on the Pura 80 Pro. Check the docs and report.
- Whether OpenCV (or any CV library) can be built for HarmonyOS. If not, we use the small custom C++ implementations above.
- Real-time performance of optical flow on the phone: unknown until measured.
- Whether a phone camera sees a 2 px monitor checkerboard well enough (moiré, sensor pixel pattern). The cell size is therefore adjustable in GridScreen; expect to experiment.
- Pulse from a face by color is unreliable with dark skin, makeup, low light or motion; the app should say when the signal quality is poor instead of showing a confident but wrong number.
