#!/usr/bin/env python3
"""
Extended Precision Verification — 25 Key Points

Covers full-screen grid + boundaries to comprehensively validate
1:1 ADB tap mapping.
"""

import subprocess
import time
import os

from config_loader import CONFIG

DEVICE = CONFIG.get("device_serial", "")
W = CONFIG.get("screen_width", 1200)
H = CONFIG.get("screen_height", 2670)
DIR = os.path.join(CONFIG.get("screenshot_dir", "./screenshots"), "calibration_shots")


def _serial_flag() -> str:
    return f"-s {DEVICE} " if DEVICE else ""


def adb(cmd):
    subprocess.run(f"adb {_serial_flag()}{cmd}", shell=True, capture_output=True)


def verify(x, y, name):
    path = os.path.join(DIR, f"ext_{name}_{x}_{y}.png")
    sf = _serial_flag()
    proc = subprocess.Popen(f"sleep 0.4 && adb {sf}exec-out screencap -p > {path}", shell=True)
    adb(f"shell input swipe {x} {y} {x} {y} 1500")
    proc.wait()
    return path


# 25 verification points: full grid + boundaries
points = [
    # Corners
    ("corner_tl", 0, 0),
    ("corner_tr", W - 1, 0),
    ("corner_bl", 0, H - 1),
    ("corner_br", W - 1, H - 1),
    # Edge midpoints
    ("edge_top", W // 2, 0),
    ("edge_bottom", W // 2, H - 1),
    ("edge_left", 0, H // 2),
    ("edge_right", W - 1, H // 2),
    # Center
    ("center", W // 2, H // 2),
    # 3x3 grid centers (upper)
    ("grid_1", W // 6, H // 7),
    ("grid_2", W // 2, H // 7),
    ("grid_3", W * 5 // 6, H // 7),
    ("grid_4", W // 6, H * 3 // 7),
    ("grid_5", W // 2, H * 3 // 7),
    ("grid_6", W * 5 // 6, H * 3 // 7),
    ("grid_7", W // 6, H * 5 // 7),
    ("grid_8", W // 2, H * 5 // 7),
    ("grid_9", W * 5 // 6, H * 5 // 7),
    # Lower region (common keyboard area)
    ("lower_1", W // 6, H * 6 // 7),
    ("lower_2", W // 2, H * 6 // 7),
    ("lower_3", W * 5 // 6, H * 6 // 7),
    # Near bottom nav
    ("near_nav", W // 2, H - 70),
]

os.makedirs(DIR, exist_ok=True)
print(f"[*] Extended verification: {len(points)} points")
print("="*60)

for i, (name, x, y) in enumerate(points, 1):
    print(f"[{i:2d}/{len(points)}] {name:12s} ({x:4d}, {y:4d}) ...", end="", flush=True)
    verify(x, y, name)
    print(" done")
    time.sleep(0.3)

print("="*60)
print(f"[+] Complete. All screenshots saved to: {DIR}")
print("[*] Check the status-bar X, Y values in each screenshot.")
