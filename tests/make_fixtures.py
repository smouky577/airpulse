"""Build numerical fixtures from evm_prototype.py and OpenCV for the C++ core tests.

Outputs raw little-endian images: int32 width, height, channels, then tightly packed
samples (uint8 or float32). The demo clips are the prototype's synthetic videos,
decoded back the same way the prototype reads them.
"""
import os
import struct
import sys

import cv2
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(ROOT, "reference"))
from evm_prototype import (  # noqa: E402
    bgr_to_yiq,
    estimate_bpm,
    find_roi,
    iir_coeff,
    run_demo,
    spatial_lowpass,
    yiq_to_bgr,
)


def save_array(path, arr, dtype):
    arr = np.ascontiguousarray(arr, dtype=dtype)
    if arr.ndim == 2:
        h, w = arr.shape
        c = 1
    else:
        h, w, c = arr.shape
    with open(path, "wb") as f:
        f.write(struct.pack("<iii", w, h, c))
        f.write(arr.tobytes())


def main():
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "fixtures")
    os.makedirs(out, exist_ok=True)

    rng = np.random.default_rng(1)
    src = rng.random((30, 40, 3)).astype(np.float32)
    save_array(os.path.join(out, "pyr_in.bin"), src, np.float32)
    save_array(os.path.join(out, "pyr_out.bin"), cv2.pyrDown(src), np.float32)

    gsrc = (rng.random((28, 36)) * 200).astype(np.float32)
    save_array(os.path.join(out, "gauss0_in.bin"), gsrc, np.float32)
    save_array(os.path.join(out, "gauss0_out.bin"), cv2.GaussianBlur(gsrc, (3, 3), 0), np.float32)
    save_array(os.path.join(out, "gauss_in.bin"), gsrc, np.float32)
    save_array(os.path.join(out, "gauss_out.bin"), cv2.GaussianBlur(gsrc, (7, 7), 0.8), np.float32)

    rsrc = rng.random((17, 13)).astype(np.float32)
    save_array(os.path.join(out, "resize_in.bin"), rsrc, np.float32)
    save_array(
        os.path.join(out, "resize_out.bin"),
        cv2.resize(rsrc, (30, 20), interpolation=cv2.INTER_LINEAR),
        np.float32,
    )

    color = rng.integers(0, 256, (24, 32, 3), dtype=np.uint8)
    save_array(os.path.join(out, "gray_in.bin"), color, np.uint8)
    save_array(os.path.join(out, "gray_out.bin"), cv2.cvtColor(color, cv2.COLOR_BGR2GRAY), np.uint8)

    yy, xx = np.indices((96, 128))
    board = (((xx // 3) + (yy // 3)) % 2 * 200 + (xx * 3 + yy) % 17).astype(np.uint8)
    shifted = np.roll(np.roll(board, 1, axis=1), -1, axis=0)
    flow = cv2.calcOpticalFlowFarneback(board, shifted, None, 0.5, 3, 15, 3, 5, 1.1, 0)
    save_array(os.path.join(out, "flow_prev.bin"), board, np.uint8)
    save_array(os.path.join(out, "flow_next.bin"), shifted, np.uint8)
    save_array(os.path.join(out, "flow_out.bin"), flow, np.float32)

    fps = 30.0
    sine = np.sin(2 * np.pi * 1.2 * np.arange(12 * int(fps)) / fps)
    sine_bpm = estimate_bpm(sine, fps, 0.8, 2.0)
    with open(os.path.join(out, "sine_bpm.txt"), "w", encoding="utf-8") as f:
        f.write(f"{sine_bpm:.10f}\n")

    cwd = os.getcwd()
    os.chdir(out)
    try:
        run_demo(None)
    finally:
        os.chdir(cwd)

    def read_video(name):
        cap = cv2.VideoCapture(os.path.join(out, name))
        frames = []
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            frames.append(frame)
        video_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        cap.release()
        return frames, video_fps

    pulse_frames, pulse_fps = read_video("demo_pulse.avi")
    h, w = pulse_frames[0].shape[:2]
    stacked = np.stack(pulse_frames, axis=0)
    with open(os.path.join(out, "pulse_frames.bin"), "wb") as f:
        f.write(struct.pack("<iii", w, h, stacked.shape[0]))
        f.write(np.ascontiguousarray(stacked).tobytes())

    green = []
    roi = None
    a_hi = iir_coeff(2.0, pulse_fps)
    a_lo = iir_coeff(0.8, pulse_fps)
    lp1 = lp2 = None
    magnified = None
    for i, frame in enumerate(pulse_frames):
        yiq = bgr_to_yiq(frame)
        small = spatial_lowpass(yiq, 4)
        if lp1 is None:
            lp1, lp2 = small.copy(), small.copy()
        lp1 = lp1 + a_hi * (small - lp1)
        lp2 = lp2 + a_lo * (small - lp2)
        band = lp1 - lp2
        gain = np.array([0.0, 60.0, 60.0], np.float32)
        amp = cv2.resize(band * gain, (w, h), interpolation=cv2.INTER_LINEAR)
        out_frame = yiq_to_bgr(yiq + amp)
        if i == 200:
            magnified = out_frame
        if i % 15 == 0 or roi is None:
            roi = find_roi(frame, None, None)
        x, y, rw, rh = roi
        green.append(frame[y : y + rh, x : x + rw, 1].mean())
    win = int(12 * pulse_fps)
    pulse_bpm = estimate_bpm(green[-win:], pulse_fps, 0.8, 2.0)
    save_array(os.path.join(out, "pulse_mag_200.bin"), magnified, np.uint8)
    with open(os.path.join(out, "pulse_meta.txt"), "w", encoding="utf-8") as f:
        f.write(f"{w} {h} {len(pulse_frames)} {pulse_fps:.8f} {pulse_bpm:.10f}\n")
        f.write(f"{roi[0]} {roi[1]} {roi[2]} {roi[3]}\n")

    air_frames, air_fps = read_video("demo_air.avi")
    grays = [cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) for frame in air_frames]
    ref_n = 30
    ref = np.median(np.stack(grays[:ref_n]), axis=0).astype(np.uint8)
    ref = cv2.GaussianBlur(ref, (0, 0), 0.8)
    test_index = 80
    gray = cv2.GaussianBlur(grays[test_index], (0, 0), 0.8)
    flow = cv2.calcOpticalFlowFarneback(ref, gray, None, 0.5, 3, 15, 3, 5, 1.1, 0)
    mag = np.sqrt(flow[..., 0] ** 2 + flow[..., 1] ** 2).astype(np.float32)
    vis = np.clip(mag * 3.0 * 255.0, 0, 255).astype(np.uint8)
    heat = cv2.applyColorMap(vis, cv2.COLORMAP_INFERNO)
    p995 = float(np.percentile(mag, 99.5))
    save_array(os.path.join(out, "air_ref.bin"), ref, np.uint8)
    # Unblurred reference stack and the test frame, so C++ runs the same pre-blur.
    stack = np.ascontiguousarray(np.stack(grays[:ref_n]))
    with open(os.path.join(out, "air_stack.bin"), "wb") as f:
        f.write(struct.pack("<iii", stack.shape[2], stack.shape[1], stack.shape[0]))
        f.write(stack.tobytes())
    save_array(os.path.join(out, "air_frame.bin"), grays[test_index], np.uint8)
    save_array(os.path.join(out, "air_mag.bin"), mag, np.float32)
    save_array(os.path.join(out, "air_heat.bin"), heat, np.uint8)
    with open(os.path.join(out, "air_meta.txt"), "w", encoding="utf-8") as f:
        f.write(f"{air_fps:.8f} {test_index} {p995:.10f}\n")

    print(f"fixtures in {out}")
    print(f"sine bpm {sine_bpm:.4f}")
    print(f"demo pulse bpm {pulse_bpm:.4f}  frames {len(pulse_frames)} roi {roi}")
    print(f"demo air p99.5 {p995:.4f} px at frame {test_index}")


if __name__ == "__main__":
    main()
