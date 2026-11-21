#!/usr/bin/env python3
"""
Multi-App UI Structure Scanner

Batch-diagnose multiple Android apps: detect native EditText elements,
run OCR, and report automation difficulty per app.
"""

import uiautomator2 as u2
import time
import os

from config_loader import CONFIG
from vision_controller import VisionController
from tap_controller import adb

DEVICE_SERIAL = CONFIG.get("device_serial", "")
SCREENSHOT_DIR = CONFIG.get("screenshot_dir", "./screenshots")
os.makedirs(SCREENSHOT_DIR, exist_ok=True)


def _serial_flag() -> str:
    return f"-s {DEVICE_SERIAL} " if DEVICE_SERIAL else ""


def scan_app(package: str, wait_time: int = 4):
    """Scan a single app's UI structure."""
    print(f"\n{'='*50}")
    print(f"Scanning: {package}")
    print('='*50)

    adb(f"shell am force-stop {package}")
    time.sleep(0.5)
    adb(f"shell monkey -p {package} -c android.intent.category.LAUNCHER 1")
    time.sleep(wait_time)

    d = u2.connect(DEVICE_SERIAL) if DEVICE_SERIAL else u2.connect()

    eds = d(className='android.widget.EditText')
    n_eds = len(eds)
    print(f"  EditText count: {n_eds}")
    for i in range(min(n_eds, 3)):
        e = eds[i]
        print(f"    [{i}] text={e.text!r} hint={e.info.get('hint', '')!r} bounds={e.bounds}")

    vision = VisionController(DEVICE_SERIAL)
    try:
        texts = vision.list_texts(min_conf=50)
        print(f"  OCR text count: {len(texts)}")
        count = 0
        for t in texts:
            if t.y > 200 and count < 10:
                print(f'    "{t.label}" at ({t.x},{t.y})')
                count += 1
    except Exception as e:
        print(f"  OCR error: {e}")

    path = os.path.join(SCREENSHOT_DIR, f"scan_{package.split('.')[-1]}.png")
    adb(f"exec-out screencap -p > {path}")
    print(f"  Screenshot: {path}")

    return n_eds > 0


# Demo app list — customize as needed
APPS = [
    "com.taobao.taobao",
    "com.xunmeng.pinduoduo",
    "tv.danmaku.bili",
    "com.sina.weibo",
    "com.baidu.BaiduMap",
    "com.zhihu.android",
    "org.telegram.messenger",
]

if __name__ == "__main__":
    results = []
    for pkg in APPS:
        has_edit = scan_app(pkg)
        results.append((pkg, has_edit))
        time.sleep(1)

    print("\n" + "="*50)
    print("Scan Summary")
    print("="*50)
    for pkg, has_edit in results:
        status = "EditText found" if has_edit else "No EditText (needs vision / ADB Keyboard)"
        print(f"  {pkg}: {status}")
