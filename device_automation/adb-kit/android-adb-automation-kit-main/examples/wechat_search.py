#!/usr/bin/env python3
"""
Example: search a contact in a chat app that uses custom (non-native) input.

This is the hard case the toolkit is built for: apps like WeChat render their
search box as a custom View with no native EditText, so set_text() does not
work and we must fall back to the ADB Keyboard for input, plus vision/OCR to
locate the on-screen search entry point.

Configure the target package and the search-icon template in the variables
below before running:

    python examples/wechat_search.py "Alice"
"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from device_controller import DeviceController
from ime_controller import IMEController
from vision_controller import VisionController

# --- adjust for your target app ---------------------------------------------
APP_PACKAGE = "com.tencent.mm"          # the chat app to drive
SEARCH_ICON_TEMPLATE = "icons/search.png"  # cropped screenshot of its search icon
# ----------------------------------------------------------------------------


def main(query: str):
    dev = DeviceController()
    vis = VisionController()
    ime = IMEController()

    print(f"[1] Launching {APP_PACKAGE}...")
    dev.launch_app(APP_PACKAGE)
    vis.wait_for_change(timeout=6)
    time.sleep(1.5)

    print("[2] Tapping the search entry...")
    tapped = False
    # Prefer a template if the user provided one, else fall back to OCR.
    if Path(SEARCH_ICON_TEMPLATE).exists():
        tapped = vis.tap_template(SEARCH_ICON_TEMPLATE, threshold=0.75)
    if not tapped:
        tapped = vis.tap_text("Search") or vis.tap_text("搜索")
    if not tapped:
        print("    Could not locate the search entry. Visible text:")
        for t in vis.list_texts(min_conf=60)[:15]:
            print(f"      - {t.label}")
        return

    vis.wait_for_change(timeout=4)
    time.sleep(1)

    print(f"[3] Typing query '{query}' via ADB Keyboard (custom input field)...")
    # force_adb because the search box is not a native EditText
    ime.type_text(query, force_adb=True)
    time.sleep(1.5)

    print("[4] Tapping the first result...")
    if not vis.tap_text(query):
        print("    Exact match not visible; first OCR row will be the closest result.")
    time.sleep(1)
    print("Done.")


if __name__ == "__main__":
    q = sys.argv[1] if len(sys.argv) > 1 else "Alice"
    main(q)
