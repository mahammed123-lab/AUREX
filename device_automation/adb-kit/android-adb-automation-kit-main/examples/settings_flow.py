#!/usr/bin/env python3
"""
Example: navigate the system Settings app using vision + device control.

Demonstrates launching an app, OCR-locating a menu item, scrolling to find an
entry that is off-screen, and waiting for the screen to settle between steps.

Run from the project root:
    python examples/settings_flow.py
"""

import sys
import time
from pathlib import Path

# Allow running directly from the examples/ directory
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from device_controller import DeviceController
from vision_controller import VisionController


def main():
    dev = DeviceController()
    vis = VisionController()

    print("[1] Launching Settings...")
    dev.launch_app("com.android.settings")
    vis.wait_for_change(timeout=5)
    time.sleep(1)

    print("[2] Looking for a 'Display' entry via OCR...")
    if vis.tap_text("Display"):
        print("    tapped 'Display'")
        vis.wait_for_change(timeout=5)
    else:
        print("    'Display' not visible — scrolling to find it")
        match = vis.scroll_find_text("Display", max_scrolls=8)
        if match:
            cx, cy = match.center
            from tap_controller import tap_safe
            tap_safe(cx, cy)
            print("    tapped 'Display' after scrolling")
        else:
            print("    'Display' not found; listing visible text instead:")
            for t in vis.list_texts(min_conf=60)[:15]:
                print(f"      - {t.label}")

    time.sleep(1)
    print("[3] Returning home")
    dev.press_home()
    print("Done.")


if __name__ == "__main__":
    main()
