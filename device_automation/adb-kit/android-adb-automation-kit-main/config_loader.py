"""
Unified configuration loader for the automation toolkit.

Reads config.json in the project root, falls back to environment variables.
"""

import json
import os

_CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config.json")


def load_config():
    """Load configuration with sensible defaults."""
    defaults = {
        "device_serial": os.getenv("ADB_DEVICE_SERIAL", ""),
        "screen_width": int(os.getenv("SCREEN_WIDTH", "1200")),
        "screen_height": int(os.getenv("SCREEN_HEIGHT", "2670")),
        "screenshot_dir": os.getenv("SCREENSHOT_DIR", "./screenshots"),
        "default_ime": os.getenv("DEFAULT_IME", ""),
        "adb_keyboard_ime": os.getenv("ADB_KEYBOARD_IME", ""),
    }
    if os.path.exists(_CONFIG_PATH):
        with open(_CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
        defaults.update(cfg)
    return defaults


CONFIG = load_config()
