"""GridScreen — pixel-exact BOS pattern for a Windows monitor.

DPI-aware so Windows scaling does not blur the cells. The pattern is drawn
1:1 from a numpy buffer into a Tk PhotoImage; nothing is resampled.
"""

from __future__ import annotations

import argparse
import base64
import ctypes
import struct
import sys
import tkinter as tk
import zlib
from ctypes import wintypes
from pathlib import Path

import numpy as np

CELL_MIN, CELL_MAX, CELL_DEFAULT = 1, 16, 8
KINDS = ("checker", "dots", "lines")


def enable_dpi_awareness() -> None:
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)  # PER_MONITOR_AWARE
        return
    except Exception:
        pass
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass


class MONITORINFO(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("rcMonitor", wintypes.RECT),
        ("rcWork", wintypes.RECT),
        ("dwFlags", wintypes.DWORD),
    ]


def list_monitors() -> list[dict]:
    monitors: list[dict] = []

    def callback(hmonitor, hdc, lprect, lparam):
        info = MONITORINFO()
        info.cbSize = ctypes.sizeof(MONITORINFO)
        ctypes.windll.user32.GetMonitorInfoW(hmonitor, ctypes.byref(info))
        r = info.rcMonitor
        monitors.append(
            {
                "x": r.left,
                "y": r.top,
                "w": r.right - r.left,
                "h": r.bottom - r.top,
                "primary": bool(info.dwFlags & 1),
            }
        )
        return 1

    MONITORENUMPROC = ctypes.WINFUNCTYPE(
        ctypes.c_int, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(wintypes.RECT), wintypes.LPARAM
    )
    ctypes.windll.user32.EnumDisplayMonitors(0, 0, MONITORENUMPROC(callback), 0)
    monitors.sort(key=lambda m: (not m["primary"], m["x"], m["y"]))
    return monitors


def make_pattern(
    w: int,
    h: int,
    cell: int,
    kind: str,
    invert: bool,
    white: int,
    seed: int = 1,
    shift_x: int = 0,
    shift_y: int = 0,
) -> np.ndarray:
    cell = max(1, int(cell))
    white = int(np.clip(white, 0, 255))
    yy, xx = np.indices((h, w))
    xx = xx + int(shift_x)
    yy = yy + int(shift_y)
    if kind == "lines":
        mask = (xx % cell == 0) | (yy % cell == 0)
    elif kind == "dots":
        rng = np.random.default_rng(seed)
        cy = (h + abs(shift_y) + cell) // cell + 2
        cx = (w + abs(shift_x) + cell) // cell + 2
        board = rng.integers(0, 2, size=(cy, cx), dtype=np.uint8)
        ix = np.clip(xx // cell, 0, cx - 1)
        iy = np.clip(yy // cell, 0, cy - 1)
        mask = board[iy, ix] == 1
    else:
        mask = ((xx // cell) + (yy // cell)) % 2 == 0
    on = 0 if invert else white
    off = white if invert else 0
    gray = np.where(mask, on, off).astype(np.uint8)
    return np.dstack([gray, gray, gray])


def rgb_to_png_bytes(rgb: np.ndarray) -> bytes:
    h, w, _ = rgb.shape
    rows = rgb.reshape(h, w * 3)
    raw = np.concatenate([np.zeros((h, 1), dtype=np.uint8), rows], axis=1).tobytes()

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 1))
        + chunk(b"IEND", b"")
    )


def rgb_to_photo(root: tk.Misc, rgb: np.ndarray) -> tk.PhotoImage:
    payload = base64.b64encode(rgb_to_png_bytes(rgb)).decode("ascii")
    return tk.PhotoImage(master=root, data=payload)


def write_png(path: Path, rgb: np.ndarray) -> None:
    path.write_bytes(rgb_to_png_bytes(rgb))


def run_self_test() -> int:
    board = make_pattern(8, 8, 2, "checker", False, 255)
    assert board.shape == (8, 8, 3)
    assert np.array_equal(board[0, 0], [255, 255, 255])
    assert np.array_equal(board[0, 2], [0, 0, 0])
    assert np.array_equal(board[2, 0], [0, 0, 0])
    assert np.array_equal(board[2, 2], [255, 255, 255])
    inv = make_pattern(8, 8, 2, "checker", True, 200)
    assert np.array_equal(inv[0, 0], [0, 0, 0])
    assert np.array_equal(inv[0, 2], [200, 200, 200])
    dim = make_pattern(4, 4, 1, "checker", False, 80)
    assert dim.max() == 80
    lines = make_pattern(5, 5, 2, "lines", False, 255)
    assert lines[0, 0, 0] == 255
    assert lines[1, 1, 0] == 0
    dots = make_pattern(16, 16, 2, "dots", False, 255, seed=3)
    assert dots.min() == 0 and dots.max() == 255
    tmp = Path(__file__).with_name("_test_grid.png")
    write_png(tmp, board)
    assert tmp.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"
    tmp.unlink()
    enable_dpi_awareness()
    root = tk.Tk()
    root.withdraw()
    photo = rgb_to_photo(root, board)
    assert photo.width() == 8 and photo.height() == 8
    root.destroy()
    print("GridScreen tests: ok")
    return 0


class GridScreen:
    def __init__(self) -> None:
        enable_dpi_awareness()
        self.monitors = list_monitors() or [{"x": 0, "y": 0, "w": 1280, "h": 720, "primary": True}]
        self.root = tk.Tk()
        self.root.title("GridScreen")
        self.root.configure(bg="#111")
        self.root.tk.call("tk", "scaling", 1.0)
        self.cell = tk.IntVar(value=CELL_DEFAULT)
        self.kind = tk.StringVar(value="checker")
        self.invert = tk.BooleanVar(value=False)
        self.white = tk.IntVar(value=220)
        self.monitor = tk.IntVar(value=0)
        self.shift = tk.BooleanVar(value=False)
        self.photo: tk.PhotoImage | None = None
        self.view: tk.Toplevel | None = None
        self.view_label: tk.Label | None = None
        self.shift_xy = (0, 0)
        self._build_controls()
        self.root.bind("<Escape>", lambda e: self.close_view() if self.view else self.root.destroy())
        self.root.bind("<F11>", lambda e: self.toggle_view())
        self.root.protocol("WM_DELETE_WINDOW", self.quit)

    def _build_controls(self) -> None:
        pad = {"padx": 8, "pady": 4}
        tk.Label(self.root, text="Решетка за въздух (BOS)", fg="white", bg="#111", font=("Segoe UI", 14)).pack(**pad)

        row = tk.Frame(self.root, bg="#111")
        row.pack(fill="x", **pad)
        tk.Label(row, text="Монитор", fg="white", bg="#111").pack(side="left")
        names = [
            f"{i + 1}: {m['w']}×{m['h']}" + ("  (основен)" if m["primary"] else "")
            for i, m in enumerate(self.monitors)
        ]
        self.monitor_name = tk.StringVar(value=names[0])

        def pick_monitor(name: str) -> None:
            self.monitor.set(names.index(name))
            self.refresh()

        tk.OptionMenu(row, self.monitor_name, *names, command=pick_monitor).pack(side="left", padx=6)

        row = tk.Frame(self.root, bg="#111")
        row.pack(fill="x", **pad)
        tk.Label(row, text="Шаблон", fg="white", bg="#111").pack(side="left")
        for value, label in (("checker", "Шахмат"), ("dots", "Точки"), ("lines", "Линии")):
            tk.Radiobutton(
                row, text=label, variable=self.kind, value=value, command=self.refresh,
                fg="white", bg="#111", selectcolor="#333", activebackground="#111", activeforeground="white",
            ).pack(side="left", padx=4)

        row = tk.Frame(self.root, bg="#111")
        row.pack(fill="x", **pad)
        tk.Label(row, text="Клетка, px", fg="white", bg="#111").pack(side="left")
        tk.Scale(
            row, from_=CELL_MIN, to=CELL_MAX, orient="horizontal", variable=self.cell,
            command=lambda _: self.refresh(), length=220, bg="#111", fg="white",
            troughcolor="#333", highlightthickness=0,
        ).pack(side="left", padx=8)

        row = tk.Frame(self.root, bg="#111")
        row.pack(fill="x", **pad)
        tk.Label(row, text="Бяло", fg="white", bg="#111").pack(side="left")
        tk.Scale(
            row, from_=40, to=255, orient="horizontal", variable=self.white,
            command=lambda _: self.refresh(), length=220, bg="#111", fg="white",
            troughcolor="#333", highlightthickness=0,
        ).pack(side="left", padx=8)

        row = tk.Frame(self.root, bg="#111")
        row.pack(fill="x", **pad)
        tk.Checkbutton(
            row, text="Обърни цветовете", variable=self.invert, command=self.refresh,
            fg="white", bg="#111", selectcolor="#333", activebackground="#111", activeforeground="white",
        ).pack(side="left")
        tk.Checkbutton(
            row, text="Леко отместване (shift)", variable=self.shift, command=self._on_shift,
            fg="white", bg="#111", selectcolor="#333", activebackground="#111", activeforeground="white",
        ).pack(side="left", padx=12)

        row = tk.Frame(self.root, bg="#111")
        row.pack(fill="x", **pad)
        tk.Button(row, text="Цял екран  (F11)", command=self.show_view, width=18).pack(side="left", padx=4)
        tk.Button(row, text="Затвори екрана  (Esc)", command=self.close_view, width=20).pack(side="left", padx=4)
        tk.Button(row, text="Запиши PNG", command=self.export_png, width=14).pack(side="left", padx=4)

        help_text = (
            "Насочи телефона към монитора, фокус върху шаблона. Клетка 2–4 px е добра за начало.\n"
            "Ако камерата вижда сиво вместо квадрати — увеличи клетката. Ако прегаря бялото — намалили яркостта.\n"
            "Клавиши на решетката: Esc затваря, F11 превключва, 1–9 клетка, C/T/L шаблон, I обръщане."
        )
        tk.Label(self.root, text=help_text, fg="#bbb", bg="#111", justify="left", wraplength=560).pack(**pad)
        self.status = tk.Label(self.root, text="", fg="#9EE6B8", bg="#111")
        self.status.pack(**pad)

    def _monitor(self) -> dict:
        i = max(0, min(int(self.monitor.get()), len(self.monitors) - 1))
        return self.monitors[i]

    def _rgb(self) -> np.ndarray:
        m = self._monitor()
        sx, sy = self.shift_xy if self.shift.get() else (0, 0)
        return make_pattern(m["w"], m["h"], self.cell.get(), self.kind.get(), self.invert.get(), self.white.get(), shift_x=sx, shift_y=sy)

    def refresh(self) -> None:
        if self.view is None:
            return
        rgb = self._rgb()
        photo = rgb_to_photo(self.view, rgb)
        self.photo = photo
        if self.view_label is not None:
            self.view_label.configure(image=photo)
        m = self._monitor()
        self.status.configure(text=f"{m['w']}×{m['h']}  клетка {self.cell.get()} px  {self.kind.get()}")

    def show_view(self) -> None:
        m = self._monitor()
        if self.view is None or not self.view.winfo_exists():
            win = tk.Toplevel(self.root)
            win.overrideredirect(True)
            win.configure(bg="black", cursor="none")
            win.geometry(f"{m['w']}x{m['h']}+{m['x']}+{m['y']}")
            win.attributes("-topmost", True)
            label = tk.Label(win, bd=0, highlightthickness=0, bg="black")
            label.pack(fill="both", expand=True)
            self.view = win
            self.view_label = label
            win.bind("<Escape>", lambda e: self.close_view())
            win.bind("<F11>", lambda e: self.close_view())
            win.bind("<Key>", self._on_key)
            win.focus_force()
        else:
            self.view.geometry(f"{m['w']}x{m['h']}+{m['x']}+{m['y']}")
        self.refresh()

    def close_view(self) -> None:
        if self.view is not None:
            try:
                self.view.destroy()
            except tk.TclError:
                pass
        self.view = None
        self.view_label = None
        self.photo = None

    def toggle_view(self) -> None:
        if self.view is None:
            self.show_view()
        else:
            self.close_view()

    def _on_key(self, event: tk.Event) -> None:
        key = event.keysym
        if key in ("Escape", "F11"):
            self.close_view()
        elif key.isdigit() and key != "0":
            self.cell.set(min(CELL_MAX, int(key)))
            self.refresh()
        elif key.lower() == "c":
            self.kind.set("checker")
            self.refresh()
        elif key.lower() == "t":
            self.kind.set("dots")
            self.refresh()
        elif key.lower() == "l":
            self.kind.set("lines")
            self.refresh()
        elif key.lower() == "i":
            self.invert.set(not self.invert.get())
            self.refresh()

    def _on_shift(self) -> None:
        if self.shift.get():
            self._tick_shift()
        self.refresh()

    def _tick_shift(self) -> None:
        if not self.shift.get():
            return
        x, y = self.shift_xy
        self.shift_xy = ((x + 1) % max(1, self.cell.get()), (y + (1 if x == 0 else 0)) % max(1, self.cell.get()))
        self.refresh()
        self.root.after(800, self._tick_shift)

    def export_png(self) -> None:
        rgb = self._rgb()
        path = Path(__file__).with_name(f"grid-{rgb.shape[1]}x{rgb.shape[0]}-c{self.cell.get()}-{self.kind.get()}.png")
        write_png(path, rgb)
        self.status.configure(text=f"Записано: {path.name}")

    def quit(self) -> None:
        self.close_view()
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


def main() -> int:
    parser = argparse.ArgumentParser(description="GridScreen — решетка за монитор")
    parser.add_argument("--test", action="store_true")
    args = parser.parse_args()
    if args.test:
        return run_self_test()
    GridScreen().run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
