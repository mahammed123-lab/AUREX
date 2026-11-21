#!/usr/bin/env python3
"""
Screen Tap Calibration and Verification System
===============================================
Purpose: Verify that adb input tap lands exactly at the expected pixel.
Requires: Android device with USB debugging enabled.
Tip: Enable "Pointer location" in Developer options to see red crosshairs.
"""

import argparse
import subprocess
import time
import sys

from config_loader import CONFIG

DEVICE = CONFIG.get("device_serial", "")
W = CONFIG.get("screen_width", 1200)
H = CONFIG.get("screen_height", 2670)


def adb(cmd: str) -> str:
    serial_flag = f"-s {DEVICE} " if DEVICE else ""
    r = subprocess.run(
        f"adb {serial_flag}{cmd}",
        shell=True, capture_output=True, text=True, timeout=30
    )
    return r.stdout.strip()


def tap(x: int, y: int, duration_ms: int = 0):
    """Safe tap with boundary protection."""
    x = max(0, min(x, W - 1))
    y = max(0, min(y, H - 1))
    serial_flag = f"-s {DEVICE} " if DEVICE else ""
    if duration_ms > 0:
        adb(f"shell input swipe {x} {y} {x} {y} {duration_ms}")
    else:
        adb(f"shell input tap {x} {y}")
    return x, y


def screenshot(path: str):
    serial_flag = f"-s {DEVICE} " if DEVICE else ""
    adb(f"exec-out screencap -p > {path}")


def grid_test(points_x: list, points_y: list, delay: float = 0.4):
    """
    Grid tap test. Taps each coordinate and pauses so pointer location
    leaves a red dot. Verify visually that dots align with target coords.
    """
    print(f"[*] Grid test: {len(points_x)} x {len(points_y)} = {len(points_x)*len(points_y)} points")
    print(f"[*] Delay between taps: {delay}s")
    print("[*] Make sure 'Pointer location' is ON in Developer options")
    input("[>] Press Enter to start...")

    for y in points_y:
        for x in points_x:
            tap(x, y)
            time.sleep(delay)
    print("[*] Done. Check whether red dots align with expected coordinates.")


def boundary_test():
    """Tap four corners + center."""
    print("[*] Boundary test: 5 key points")
    input("[>] Press Enter to start...")
    corners = [(0, 0), (W-1, 0), (0, H-1), (W-1, H-1), (W//2, H//2)]
    for x, y in corners:
        print(f"    tap ({x}, {y})")
        tap(x, y)
        time.sleep(0.5)
    print("[*] Boundary test complete")


def crosshair_test(center_x: int, center_y: int, radius: int = 100):
    """Crosshair test: tap center, left, right, top, bottom."""
    print(f"[*] Crosshair: center ({center_x}, {center_y}), radius {radius}")
    input("[>] Press Enter to start...")
    points = [
        (center_x, center_y),
        (center_x - radius, center_y),
        (center_x + radius, center_y),
        (center_x, center_y - radius),
        (center_x, center_y + radius),
    ]
    for x, y in points:
        print(f"    tap ({x}, {y})")
        tap(x, y)
        time.sleep(0.5)
    print("[*] Crosshair test complete")


def verify_resolution():
    """Verify that the device resolution matches the configured values."""
    out = adb("shell wm size")
    print(f"[*] Screen resolution: {out}")
    expected = f"{W}x{H}"
    if expected not in out:
        print(f"[!] Warning: expected {expected} but got different output.")
        print(f"    Coordinates in this script may be off. Update config.json.")
        return False
    print("[+] Resolution matches config")
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Screen tap calibration")
    parser.add_argument("--grid", action="store_true", help="Grid test")
    parser.add_argument("--boundary", action="store_true", help="Boundary test")
    parser.add_argument("--cross", nargs=2, type=int, metavar=("X", "Y"), help="Crosshair test")
    parser.add_argument("--tap", nargs=2, type=int, metavar=("X", "Y"), help="Single tap")
    parser.add_argument("--verify", action="store_true", help="Verify resolution")
    args = parser.parse_args()

    if args.verify:
        verify_resolution()
    elif args.grid:
        grid_test([100, 400, 700, 1000], [500, 1000, 1500, 2000, 2500])
    elif args.boundary:
        boundary_test()
    elif args.cross:
        crosshair_test(args.cross[0], args.cross[1])
    elif args.tap:
        tap(args.tap[0], args.tap[1])
    else:
        verify_resolution()
        print("\nUsage:")
        print("  python3 tap_calibrator.py --grid      # Grid test")
        print("  python3 tap_calibrator.py --boundary  # Boundary test")
        print("  python3 tap_calibrator.py --tap 500 500 # Single tap")
        print("  python3 tap_calibrator.py --cross 600 1500 # Crosshair")
