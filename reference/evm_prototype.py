#!/usr/bin/env python3
"""
Прототип: два режима върху един и същ поток от кадри.

  pulse  - Eulerian color magnification (EVM) + оценка на пулса (rPPG, FFT)
  air    - Background Oriented Schlieren (BOS): референтен кадър + оптичен поток
  checkerboard - генерира решетка за пускане на цял екран на монитор
  demo   - прави синтетични записи, за да тестваш скрипта без камера

Зависимости: numpy, opencv-python (cv2). Нищо друго.

Примери:
  python evm_prototype.py checkerboard --cell 2 --out grid.png
  python evm_prototype.py pulse --input face.mp4 --output face_evm.avi
  python evm_prototype.py air   --input flame.mp4 --output flame_bos.avi --gain 40
  python evm_prototype.py demo
"""
import argparse
import sys
import numpy as np
import cv2

# ---------------------------------------------------------------- общи ----

def open_video(path, max_width):
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        sys.exit(f"Не мога да отворя {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    if max_width and w > max_width:
        s = max_width / w
        w, h = int(w * s), int(h * s)
    return cap, fps, (w - w % 2, h - h % 2)


def read_frames(cap, size):
    while True:
        ok, frame = cap.read()
        if not ok:
            return
        yield cv2.resize(frame, size, interpolation=cv2.INTER_AREA)


def open_writer(path, fps, size):
    fourcc = cv2.VideoWriter_fourcc(*("MJPG" if path.lower().endswith(".avi") else "mp4v"))
    wr = cv2.VideoWriter(path, fourcc, fps, size)
    if not wr.isOpened():
        sys.exit(f"Не мога да пиша в {path}")
    return wr


def iir_coeff(cutoff_hz, fps):
    """Коефициент на едно-полюсен нискочестотен филтър."""
    return 1.0 - np.exp(-2.0 * np.pi * cutoff_hz / fps)


# ------------------------------------------------------- режим: pulse ----

# BGR -> YIQ (хроматичните канали I и Q носят цветовата промяна)
_RGB2YIQ = np.array([[0.299, 0.587, 0.114],
                     [0.596, -0.274, -0.322],
                     [0.211, -0.523, 0.312]], dtype=np.float32)
_YIQ2RGB = np.linalg.inv(_RGB2YIQ).astype(np.float32)


def bgr_to_yiq(img):
    rgb = img[..., ::-1].astype(np.float32) / 255.0
    return rgb @ _RGB2YIQ.T


def yiq_to_bgr(yiq):
    rgb = yiq @ _YIQ2RGB.T
    return (np.clip(rgb, 0, 1)[..., ::-1] * 255).astype(np.uint8)


def spatial_lowpass(img, level):
    """Гаусова пирамида: сигналът за пулса е в големите области на кожата."""
    for _ in range(level):
        img = cv2.pyrDown(img)
    return img


def find_roi(frame, roi_arg, cascade):
    h, w = frame.shape[:2]
    if roi_arg:
        x, y, rw, rh = roi_arg
        return x, y, rw, rh
    if cascade is not None:
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = cascade.detectMultiScale(gray, 1.2, 5, minSize=(w // 8, h // 8))
        if len(faces):
            x, y, fw, fh = max(faces, key=lambda f: f[2] * f[3])
            # само вътрешната част на лицето (чело и бузи, без фона по краищата)
            return x + fw // 5, y + fh // 8, fw * 3 // 5, fh * 3 // 5
    # резервно: центърът на кадъра
    return w // 3, h // 3, w // 3, h // 3


def estimate_bpm(signal, fps, lo_hz, hi_hz):
    sig = np.asarray(signal, dtype=np.float64)
    sig = sig - sig.mean()
    # премахване на бавен тренд (светлина, дишане)
    k = max(3, int(fps))  # ~1 s плъзгаща средна
    trend = np.convolve(sig, np.ones(k) / k, mode="same")
    sig = sig - trend
    sig *= np.hanning(len(sig))
    n = 1 << 15  # zero-padding -> по-фина честотна резолюция
    spec = np.abs(np.fft.rfft(sig, n))
    freqs = np.fft.rfftfreq(n, 1.0 / fps)
    band = (freqs >= lo_hz) & (freqs <= hi_hz)
    if not band.any():
        return None
    f_peak = freqs[band][np.argmax(spec[band])]
    return f_peak * 60.0


def run_pulse(a):
    cap, fps, size = open_video(a.input, a.max_width)
    wr = open_writer(a.output, fps, size)
    cascade = None
    if not a.roi:
        cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
        if cascade.empty():
            cascade = None

    a_hi = iir_coeff(a.high, fps)
    a_lo = iir_coeff(a.low, fps)
    lp1 = lp2 = None
    green, roi, bpm = [], None, None
    win = int(a.window * fps)
    n_frames = 0

    for i, frame in enumerate(read_frames(cap, size)):
        yiq = bgr_to_yiq(frame)
        small = spatial_lowpass(yiq, a.level)
        if lp1 is None:
            lp1, lp2 = small.copy(), small.copy()
        lp1 += a_hi * (small - lp1)
        lp2 += a_lo * (small - lp2)
        band = lp1 - lp2  # bandpass = разлика на два lowpass-а

        gain = np.array([a.luma_gain, a.alpha, a.alpha], dtype=np.float32)
        amp = cv2.resize(band * gain, size, interpolation=cv2.INTER_LINEAR)
        out = yiq_to_bgr(yiq + amp)

        # ---- пулс: средна стойност на зеления канал в ROI (върху суровия кадър)
        if i % 15 == 0 or roi is None:
            roi = find_roi(frame, a.roi, cascade)
        x, y, rw, rh = roi
        green.append(frame[y:y + rh, x:x + rw, 1].mean())
        if len(green) >= int(6 * fps) and i % int(max(1, fps // 2)) == 0:
            bpm = estimate_bpm(green[-win:], fps, a.low, a.high)

        cv2.rectangle(out, (x, y), (x + rw, y + rh), (0, 255, 0), 1)
        label = f"{bpm:.0f} bpm" if bpm else "събирам данни..."
        cv2.putText(out, label if bpm else "collecting...", (10, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2, cv2.LINE_AA)
        wr.write(out)
        n_frames += 1

    cap.release()
    wr.release()
    final = estimate_bpm(green[-win:], fps, a.low, a.high) if len(green) > 4 * fps else None
    print(f"Кадри: {n_frames}, fps: {fps:.1f}, размер: {size}")
    print(f"Оценка на пулса: {final:.1f} bpm" if final else "Твърде кратък запис за оценка (трябват >4-6 s).")
    print(f"Записано: {a.output}")


# --------------------------------------------------------- режим: air ----

def run_air(a):
    cap, fps, size = open_video(a.input, a.max_width)
    wr = open_writer(a.output, fps, size)
    frames = read_frames(cap, size)

    # референтен кадър = медиана на първите кадри (без смущение пред екрана!)
    ref_stack = []
    first = []
    for frame in frames:
        first.append(frame)
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        ref_stack.append(gray)
        if len(ref_stack) >= a.ref_frames:
            break
    if not ref_stack:
        sys.exit("Празен запис.")
    ref = np.median(np.stack(ref_stack), axis=0).astype(np.uint8)
    ref = cv2.GaussianBlur(ref, (0, 0), a.blur) if a.blur > 0 else ref

    drift = None  # бавно движение (термичен дрейф, вибрации на статива)
    n = 0

    def process(frame):
        nonlocal drift
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        if a.blur > 0:
            gray = cv2.GaussianBlur(gray, (0, 0), a.blur)
        flow = cv2.calcOpticalFlowFarneback(
            ref, gray, None, pyr_scale=0.5, levels=3, winsize=a.win,
            iterations=3, poly_n=5, poly_sigma=1.1, flags=0)
        if a.highpass > 0:
            if drift is None:
                drift = flow.copy()
            drift += iir_coeff(a.highpass, fps) * (flow - drift)
            flow = flow - drift
        mag = np.sqrt(flow[..., 0] ** 2 + flow[..., 1] ** 2)
        vis = np.clip(mag * a.gain * 255.0, 0, 255).astype(np.uint8)
        return cv2.applyColorMap(vis, cv2.COLORMAP_INFERNO), mag

    peak = 0.0
    for frame in first + list(frames):
        img, mag = process(frame)
        peak = max(peak, float(np.percentile(mag, 99.5)))
        wr.write(img)
        n += 1

    cap.release()
    wr.release()
    print(f"Кадри: {n}, fps: {fps:.1f}, размер: {size}")
    print(f"Най-голямо изместване (99.5 перцентил): {peak:.3f} px. "
          f"Ако картината е тъмна, увеличи --gain (сега {a.gain}); ако е наситена, намали го.")
    print(f"Записано: {a.output}")


# ---------------------------------------------------- решетка за екран ----

def make_checkerboard(w, h, cell):
    yy, xx = np.indices((h, w))
    board = (((xx // cell) + (yy // cell)) % 2) * 255
    return board.astype(np.uint8)


def run_checkerboard(a):
    img = make_checkerboard(a.width, a.height, a.cell)
    cv2.imwrite(a.out, img)
    print(f"Записано: {a.out} ({a.width}x{a.height}, квадрат {a.cell}px)")
    print("Пусни я на цял екран при 100% мащаб (без zoom!). Камерата трябва да вижда квадратите "
          "като фина текстура, не като размазано сиво.")


# --------------------------------------------------------------- demo ----

def run_demo(a):
    rng = np.random.default_rng(0)
    fps, W, H = 30, 480, 360

    # --- въздух: решетка + движеща се нагоре "струя", която измества шарката
    grid = make_checkerboard(W, H, 3)
    grid = cv2.GaussianBlur(grid, (0, 0), 0.8)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    wr = open_writer("demo_air.avi", fps, (W, H))
    for t in range(150):
        if t < 30:
            amp = 0.0  # първите кадри са без смущение -> референт
        else:
            amp = 1.6
        cy = H - 40 - (t - 30) * 1.5
        cx = W / 2 + 25 * np.sin((t - 30) * 0.12)
        g = np.exp(-(((xx - cx) ** 2) / (2 * 28 ** 2) + ((yy - cy) ** 2) / (2 * 55 ** 2)))
        dx = amp * g * np.sin(yy * 0.07 + t * 0.3)
        dy = amp * g * np.cos(xx * 0.07 - t * 0.2)
        frame = cv2.remap(grid, (xx + dx).astype(np.float32), (yy + dy).astype(np.float32),
                          cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)
        frame = frame.astype(np.float32) + rng.normal(0, 1.0, frame.shape)
        frame = np.clip(frame, 0, 255).astype(np.uint8)
        wr.write(cv2.cvtColor(frame, cv2.COLOR_GRAY2BGR))
    wr.release()

    # --- пулс: "кожа" със зелена промяна 72 bpm (1.2 Hz) и малко шум
    wr = open_writer("demo_pulse.avi", fps, (W, H))
    bpm_true = 72.0
    for t in range(int(14 * fps)):
        base = np.zeros((H, W, 3), np.float32)
        base[...] = (120, 135, 190)  # BGR - тон на кожа
        yy_, xx_ = np.mgrid[0:H, 0:W]
        mask = ((xx_ - W / 2) ** 2 / (110 ** 2) + (yy_ - H / 2) ** 2 / (140 ** 2)) < 1
        pulse = 0.8 * np.sin(2 * np.pi * (bpm_true / 60.0) * t / fps)  # +-0.8 нива на яркост
        base[mask, 1] -= pulse  # зеленият канал реагира най-силно
        base[mask, 0] -= 0.3 * pulse
        base += rng.normal(0, 0.6, base.shape)
        wr.write(np.clip(base, 0, 255).astype(np.uint8))
    wr.release()
    print("Готово: demo_air.avi, demo_pulse.avi (истинският пулс в demo е 72 bpm)")
    print("Тествай:  python evm_prototype.py air --input demo_air.avi --output demo_air_bos.avi")
    print("          python evm_prototype.py pulse --input demo_pulse.avi --output demo_pulse_evm.avi")


# ---------------------------------------------------------------- CLI ----

def parse_roi(s):
    try:
        v = [int(p) for p in s.split(",")]
        assert len(v) == 4
        return tuple(v)
    except Exception:
        raise argparse.ArgumentTypeError("ROI трябва да е x,y,w,h")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("checkerboard", help="генерира решетка за монитор")
    c.add_argument("--width", type=int, default=1920)
    c.add_argument("--height", type=int, default=1080)
    c.add_argument("--cell", type=int, default=2, help="страна на квадрата в пиксели на екрана")
    c.add_argument("--out", default="grid.png")
    c.set_defaults(fn=run_checkerboard)

    pu = sub.add_parser("pulse", help="цветово усилване + пулс")
    pu.add_argument("--input", required=True)
    pu.add_argument("--output", required=True)
    pu.add_argument("--alpha", type=float, default=60, help="усилване на хроматичните канали")
    pu.add_argument("--luma-gain", type=float, default=0, help="усилване на яркостта (по избор)")
    pu.add_argument("--low", type=float, default=0.8, help="долна честота, Hz")
    pu.add_argument("--high", type=float, default=2.0, help="горна честота, Hz")
    pu.add_argument("--level", type=int, default=4, help="ниво на пирамидата (повече = по-силно размазване)")
    pu.add_argument("--window", type=float, default=12.0, help="прозорец за пулса, секунди")
    pu.add_argument("--roi", type=parse_roi, default=None, help="x,y,w,h (иначе: лице или център)")
    pu.add_argument("--max-width", type=int, default=640)
    pu.set_defaults(fn=run_pulse)

    ai = sub.add_parser("air", help="BOS: визуализация на въздух")
    ai.add_argument("--input", required=True)
    ai.add_argument("--output", required=True)
    ai.add_argument("--ref-frames", type=int, default=30, help="първите N кадъра без смущение = референт")
    ai.add_argument("--gain", type=float, default=3.0, help="1/gain = изместване в px, което дава бяло (3 => 0.33 px)")
    ai.add_argument("--blur", type=float, default=0.8, help="малко размазване срещу шум, px")
    ai.add_argument("--win", type=int, default=15, help="прозорец на оптичния поток")
    ai.add_argument("--highpass", type=float, default=0.0,
                    help="Hz; >0 маха бавния дрейф (напр. 0.3), но и бавно променящи се струи")
    ai.add_argument("--max-width", type=int, default=960)
    ai.set_defaults(fn=run_air)

    d = sub.add_parser("demo", help="синтетични записи за проба")
    d.set_defaults(fn=run_demo)

    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
