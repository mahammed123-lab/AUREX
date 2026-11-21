#!/usr/bin/env python3
"""
Universal Screen Tap Controller
===============================
Precision screen tapping via ADB: click, long-press, swipe, batch sequences.

Verified: adb shell input tap X Y maps 1:1 to screen pixels on tested devices.
"""

import os
import subprocess
import time
import json
from dataclasses import dataclass, asdict
from typing import Optional, Tuple, List

from config_loader import CONFIG

DEVICE_SERIAL = CONFIG.get("device_serial", "")
SCREEN_W = CONFIG.get("screen_width", 1200)
SCREEN_H = CONFIG.get("screen_height", 2670)
SCREENSHOT_DIR = CONFIG.get("screenshot_dir", "./screenshots")
os.makedirs(SCREENSHOT_DIR, exist_ok=True)


def _serial_flag() -> str:
    return f"-s {DEVICE_SERIAL} " if DEVICE_SERIAL else ""


def adb(cmd: str, timeout: int = 30) -> str:
    """Execute ADB command and return stdout."""
    result = subprocess.run(
        f"adb {_serial_flag()}{cmd}",
        shell=True, capture_output=True, text=True, timeout=timeout
    )
    if result.returncode != 0 and result.stderr:
        if "no keycombo" not in result.stderr.lower():
            print(f"[ADB warning] {result.stderr.strip()}")
    return result.stdout.strip()


def check_device() -> bool:
    """Check whether the device is connected and authorized."""
    out = adb("devices")
    if DEVICE_SERIAL and DEVICE_SERIAL not in out:
        print(f"[!] Device {DEVICE_SERIAL} not connected")
        return False
    if "unauthorized" in out:
        print("[!] Device unauthorized. Allow USB debugging on the phone.")
        return False
    print(f"[+] Device ready")
    return True


def get_screen_size() -> Tuple[int, int]:
    """Query current screen resolution."""
    out = adb("shell wm size")
    for line in out.split("\n"):
        if "size:" in line.lower():
            parts = line.split(":")[-1].strip().split("x")
            return int(parts[0]), int(parts[1])
    return SCREEN_W, SCREEN_H


# ── Core tap primitives ────────────────────────────────────


def tap(x: int, y: int, duration_ms: int = 0) -> Tuple[int, int]:
    """
    Precise tap or long-press.

    Args:
        x, y: Screen coordinates from top-left (0,0).
        duration_ms: 0 = normal tap, >0 = long press (milliseconds).

    Returns:
        Actual coordinates used (after boundary clamping).
    """
    w, h = get_screen_size()
    x = max(0, min(x, w - 1))
    y = max(0, min(y, h - 1))

    if duration_ms > 0:
        adb(f"shell input swipe {x} {y} {x} {y} {duration_ms}")
    else:
        adb(f"shell input tap {x} {y}")

    return x, y


def tap_safe(x: int, y: int, duration_ms: int = 0,
             margin: int = 10, nav_bar_h: int = 60) -> Tuple[int, int]:
    """
    Safe tap: avoid edges and navigation bar to prevent system gestures.

    Args:
        margin: Horizontal margin to avoid edge swipes.
        nav_bar_h: Bottom nav bar height to avoid Home/Recents.
    """
    w, h = get_screen_size()
    x = max(margin, min(x, w - 1 - margin))
    y = max(margin, min(y, h - 1 - nav_bar_h))
    return tap(x, y, duration_ms)


def swipe(x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300):
    """Swipe gesture."""
    w, h = get_screen_size()
    x1 = max(0, min(x1, w - 1))
    y1 = max(0, min(y1, h - 1))
    x2 = max(0, min(x2, w - 1))
    y2 = max(0, min(y2, h - 1))
    adb(f"shell input swipe {x1} {y1} {x2} {y2} {duration_ms}")


def double_tap(x: int, y: int, interval_ms: int = 80):
    """Double tap."""
    tap(x, y)
    time.sleep(interval_ms / 1000.0)
    tap(x, y)


def tap_relative(px: float, py: float, duration_ms: int = 0) -> Tuple[int, int]:
    """
    Tap by relative position (0.0-1.0).

    Args:
        px: Horizontal ratio (0.0 = left edge, 1.0 = right edge).
        py: Vertical ratio (0.0 = top, 1.0 = bottom).
    """
    w, h = get_screen_size()
    x = int(px * w)
    y = int(py * h)
    return tap(x, y, duration_ms)


def pinch(center_x: int, center_y: int,
          distance: int = 300, duration_ms: int = 300,
          direction: str = "in"):
    """
    Pinch gesture via uiautomator2 (requires the package installed).

    Args:
        direction: "in" = zoom out (fingers close), "out" = zoom in.
    """
    import uiautomator2 as u2
    d = u2.connect(DEVICE_SERIAL) if DEVICE_SERIAL else u2.connect()

    if direction == "in":
        p1_s, p1_e = (center_x - distance, center_y), (center_x, center_y)
        p2_s, p2_e = (center_x + distance, center_y), (center_x, center_y)
    else:
        p1_s, p1_e = (center_x, center_y), (center_x - distance, center_y)
        p2_s, p2_e = (center_x, center_y), (center_x + distance, center_y)

    d().gesture(p1_s, p1_e, p2_s, p2_e)


def scroll_down(y_start: int = 1800, y_end: int = 1000, x: int = 600, duration_ms: int = 300):
    """Scroll down (content moves up)."""
    swipe(x, y_start, x, y_end, duration_ms)


def scroll_up(y_start: int = 1000, y_end: int = 1800, x: int = 600, duration_ms: int = 300):
    """Scroll up (content moves down)."""
    swipe(x, y_start, x, y_end, duration_ms)


def scroll_left(x_start: int = 1000, x_end: int = 200, y: int = 1335, duration_ms: int = 300):
    """Scroll left (content moves right)."""
    swipe(x_start, y, x_end, y, duration_ms)


def scroll_right(x_start: int = 200, x_end: int = 1000, y: int = 1335, duration_ms: int = 300):
    """Scroll right (content moves left)."""
    swipe(x_start, y, x_end, y, duration_ms)


# ── Screenshot utilities ───────────────────────────────────


def screenshot(filename: Optional[str] = None) -> str:
    """Take a screenshot and save it. Returns the path."""
    if filename is None:
        ts = time.strftime("%H%M%S")
        filename = f"shot_{ts}.png"
    path = os.path.join(SCREENSHOT_DIR, filename)
    adb(f"exec-out screencap -p > {path}")
    return path


def tap_and_verify(x: int, y: int, name: str = "", delay: float = 0.3) -> str:
    """Tap + screenshot for verification."""
    tap(x, y)
    time.sleep(delay)
    path = screenshot(f"verify_{name}_{x}_{y}.png")
    print(f"[+] Tapped ({x}, {y}) and saved: {path}")
    return path


# ── Batch sequences ────────────────────────────────────────


def sequence(actions: List[Tuple[int, int, float]],
             verify_each: bool = False) -> List[str]:
    """
    Execute a sequence of taps.

    Args:
        actions: [(x, y, delay_after), ...]
        verify_each: Whether to screenshot after each tap.

    Returns:
        List of screenshot paths (if verify_each=True).
    """
    shots = []
    for i, (x, y, d) in enumerate(actions):
        tap(x, y)
        time.sleep(d)
        if verify_each:
            shots.append(screenshot(f"seq_{i:03d}_{x}_{y}.png"))
    return shots


# ── Calibration system ─────────────────────────────────────


@dataclass
class CalibrationPoint:
    name: str
    x: int
    y: int
    description: str = ""

    @property
    def center(self) -> Tuple[int, int]:
        return self.x, self.y


class CalibrationSet:
    """A named collection of calibration points, serializable to JSON."""

    def __init__(self, name: str, points: Optional[List[CalibrationPoint]] = None):
        self.name = name
        self.points: List[CalibrationPoint] = points or []

    def add(self, name: str, x: int, y: int, description: str = ""):
        self.points.append(CalibrationPoint(name, x, y, description))

    def get(self, name: str) -> Optional[Tuple[int, int]]:
        for p in self.points:
            if p.name == name:
                return p.x, p.y
        return None

    def tap(self, name: str, duration_ms: int = 0):
        """Tap the calibration point by name."""
        pos = self.get(name)
        if pos is None:
            raise ValueError(f"Calibration point '{name}' not found")
        return tap(pos[0], pos[1], duration_ms)

    def save(self, path: str):
        data = {
            "name": self.name,
            "points": [asdict(p) for p in self.points]
        }
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        print(f"[+] Calibration set saved: {path}")

    @classmethod
    def load(cls, path: str) -> "CalibrationSet":
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        cs = cls(data["name"])
        for p in data["points"]:
            cs.add(p["name"], p["x"], p["y"], p.get("description", ""))
        return cs


# ── Calibration workflows ──────────────────────────────────


def calibrate_grid(step_x: int = 200, step_y: int = 300,
                   verify_each: bool = True) -> CalibrationSet:
    """
    Grid calibration: tap across the screen in a grid pattern.
    Requires "Pointer location" to be ON in Developer options.
    """
    print("=" * 50)
    print("Grid Calibration")
    print("=" * 50)
    print("[Required] Enable: Developer options → Pointer location")
    print("[Note] Red crosshair should land exactly on target coordinates.")
    input("\n[>] Confirm Pointer location is ON, then press Enter...\n")

    w, h = get_screen_size()
    cs = CalibrationSet(f"grid_{w}x{h}")

    xs = list(range(step_x, w, step_x))
    ys = list(range(step_y, h, step_y))
    total = len(xs) * len(ys)

    print(f"[*] Grid: {len(xs)} x {len(ys)} = {total} points")

    for i, y in enumerate(ys):
        for j, x in enumerate(xs):
            idx = i * len(xs) + j + 1
            print(f"\n[{idx}/{total}] Target: ({x}, {y})")
            tap(x, y)
            time.sleep(0.4)

            if verify_each:
                path = screenshot(f"cal_{idx:03d}_{x}_{y}.png")
                print(f"    Screenshot: {path}")
                ok = input("    Correct? [Enter=yes, n=skip, q=quit] ").strip().lower()
                if ok == "q":
                    print("[*] Calibration interrupted")
                    return cs
                if ok == "n":
                    print("    Skipped")
                    continue

            cs.add(f"grid_{x}_{y}", x, y, f"Grid point ({x}, {y})")

    print(f"\n[+] Calibration complete, {len(cs.points)} points recorded")
    return cs


def calibrate_crosshair(x: int, y: int, radius: int = 100) -> CalibrationSet:
    """Crosshair calibration around a target point."""
    print(f"[*] Crosshair: center ({x}, {y}), radius {radius}")
    input("[>] Press Enter to start...")

    cs = CalibrationSet(f"cross_{x}_{y}")
    points = [
        ("center", x, y),
        ("left", x - radius, y),
        ("right", x + radius, y),
        ("up", x, y - radius),
        ("down", x, y + radius),
    ]
    for name, px, py in points:
        print(f"\nTap: {name} ({px}, {py})")
        tap(px, py)
        time.sleep(0.5)
        screenshot(f"cross_{name}_{px}_{py}.png")
        cs.add(name, px, py, f"Crosshair {name}")

    return cs


# ── High-precision verification ────────────────────────────


def verify_with_longtap(target_x: int, target_y: int, name: str = "") -> str:
    """
    Long-press verification: hold for 1.5s and capture screenshot mid-hold.
    The status bar shows X,Y coordinates for direct comparison.
    """
    shot_path = os.path.join(SCREENSHOT_DIR, f"verify_lt_{name}_{target_x}_{target_y}.png")
    sf = _serial_flag()
    shot_cmd = f"sleep 0.5 && adb {sf}exec-out screencap -p > {shot_path}"
    proc = subprocess.Popen(shot_cmd, shell=True)
    tap(target_x, target_y, duration_ms=1500)
    proc.wait()
    return shot_path


def run_precision_report():
    """Generate a precision verification report with an HTML gallery."""
    import datetime

    report_dir = os.path.join(SCREENSHOT_DIR, "calibration_shots")
    os.makedirs(report_dir, exist_ok=True)

    points = [
        ("center", SCREEN_W // 2, SCREEN_H // 2),
        ("top_left", 100, 200),
        ("top_right", SCREEN_W - 100, 200),
        ("mid_left", 100, SCREEN_H // 2),
        ("mid_right", SCREEN_W - 100, SCREEN_H // 2),
        ("bottom_left", 100, SCREEN_H - 170),
        ("bottom_right", SCREEN_W - 100, SCREEN_H - 170),
        ("corner_tl", 0, 0),
        ("corner_br", SCREEN_W - 1, SCREEN_H - 1),
    ]

    print("=" * 60)
    print("ADB Tap Precision Report")
    print("=" * 60)
    print("Method: Long-press 1.5s + screenshot, read status-bar X,Y")
    print(f"Time: {datetime.datetime.now().isoformat()}")
    print()

    results = []
    for name, x, y in points:
        print(f"[*] {name}: ({x}, {y}) ...", end="", flush=True)
        path = verify_with_longtap(x, y, name)
        results.append((name, x, y, path))
        print(" done")
        time.sleep(0.5)

    html_path = os.path.join(report_dir, "report.html")
    with open(html_path, "w", encoding="utf-8") as f:
        f.write("<!DOCTYPE html>\n<html>\n<head>\n")
        f.write("<meta charset='utf-8'>\n")
        f.write("<title>Tap Precision Report</title>\n")
        f.write("<style>\n")
        f.write("body{font-family:system-ui;margin:20px;background:#1a1a1a;color:#eee}\n")
        f.write("h1{color:#4fc3f7}\n")
        f.write(".shot{display:inline-block;margin:10px;padding:10px;background:#2a2a2a;border-radius:8px}\n")
        f.write(".shot img{width:300px;height:auto;border-radius:4px}\n")
        f.write(".info{font-size:14px;margin-top:8px;color:#aaa}\n")
        f.write("</style>\n</head>\n<body>\n")
        f.write("<h1>Tap Precision Report</h1>\n")
        f.write(f"<p>Resolution: {SCREEN_W}x{SCREEN_H}<br>Time: {datetime.datetime.now().isoformat()}</p>\n")
        f.write("<p>Method: Long-press target → screenshot → read status-bar X,Y</p>\n")
        f.write("<hr>\n")

        for name, x, y, path in results:
            rel_path = os.path.basename(path)
            f.write(f"<div class='shot'>\n")
            f.write(f"  <img src='{rel_path}' alt='{name}'>\n")
            f.write(f"  <div class='info'>\n")
            f.write(f"    <strong>{name}</strong><br>\n")
            f.write(f"    Target: ({x}, {y})<br>\n")
            f.write(f"    Status bar should show: X: {x}.0, Y: {y}.0\n")
            f.write(f"  </div>\n")
            f.write(f"</div>\n")

        f.write("</body>\n</html>\n")

    print(f"\n[+] Report generated: {html_path}")
    print(f"[*] {len(points)} verification screenshots in: {report_dir}")


# ── Self-test ──────────────────────────────────────────────


def run_self_test():
    """Automated self-test of core functions."""
    print("=" * 50)
    print("Tap Controller Self-Test")
    print("=" * 50)

    print("\n[1/5] Device connection...")
    if not check_device():
        return False

    print("\n[2/5] Screen resolution...")
    w, h = get_screen_size()
    print(f"    Resolution: {w}x{h}")

    print("\n[3/5] Tap consistency (same point 5x)...")
    for i in range(5):
        tap(w // 2, h // 2)
        time.sleep(0.2)
    print("    [+] Done")

    print("\n[4/5] Boundary taps (4 corners + center)...")
    corners = [(0, 0), (w-1, 0), (0, h-1), (w-1, h-1), (w//2, h//2)]
    for x, y in corners:
        tap(x, y)
        time.sleep(0.3)
    print("    [+] Done")

    print("\n[5/5] Long-press test (500ms)...")
    tap(w//2, h//2, duration_ms=500)
    print("    [+] Done")

    print("\n" + "=" * 50)
    print("Self-test passed. Verify red dots visually.")
    print("=" * 50)
    return True


# ── CLI entry ──────────────────────────────────────────────

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Universal screen tap control")
    parser.add_argument("--check", action="store_true", help="Check device")
    parser.add_argument("--test", action="store_true", help="Run self-test")
    parser.add_argument("--tap", nargs=2, type=int, metavar=("X", "Y"), help="Single tap")
    parser.add_argument("--long-tap", nargs=2, type=int, metavar=("X", "Y"), help="Long press")
    parser.add_argument("--long-duration", type=int, default=500, help="Long-press duration (ms)")
    parser.add_argument("--swipe", nargs=4, type=int, metavar=("X1", "Y1", "X2", "Y2"), help="Swipe")
    parser.add_argument("--screenshot", action="store_true", help="Screenshot")
    parser.add_argument("--cal-grid", action="store_true", help="Grid calibration")
    parser.add_argument("--cal-cross", nargs=2, type=int, metavar=("X", "Y"), help="Crosshair calibration")
    parser.add_argument("--verify", nargs=3, type=str, metavar=("X", "Y", "NAME"), help="Tap and screenshot")
    parser.add_argument("--precision-report", action="store_true", help="Generate precision report")
    args = parser.parse_args()

    if args.check:
        check_device()
    elif args.test:
        run_self_test()
    elif args.tap:
        tap(args.tap[0], args.tap[1])
    elif args.long_tap:
        tap(args.long_tap[0], args.long_tap[1], args.long_duration)
    elif args.swipe:
        swipe(args.swipe[0], args.swipe[1], args.swipe[2], args.swipe[3])
    elif args.screenshot:
        path = screenshot()
        print(f"[+] {path}")
    elif args.cal_grid:
        cs = calibrate_grid()
        cs.save(os.path.join(SCREENSHOT_DIR, "calibration_grid.json"))
    elif args.cal_cross:
        cs = calibrate_crosshair(args.cal_cross[0], args.cal_cross[1])
        cs.save(os.path.join(SCREENSHOT_DIR, "calibration_cross.json"))
    elif args.verify:
        tap_and_verify(int(args.verify[0]), int(args.verify[1]), args.verify[2])
    elif args.precision_report:
        run_precision_report()
    else:
        parser.print_help()
