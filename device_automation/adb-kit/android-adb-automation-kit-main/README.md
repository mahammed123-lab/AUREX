<h1 align="center">Android ADB Automation Kit</h1>

<p align="center">
  <strong>Drive any Android phone from Python — no root, no Appium, no app instrumentation.</strong>
</p>

<p align="center">
  Pixel-perfect tapping · OpenCV + OCR element finding · text input that actually works in WeChat & Flutter.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/license-MIT-green.svg" alt="MIT License">
  <img src="https://img.shields.io/badge/python-3.8%2B-blue.svg" alt="Python 3.8+">
  <img src="https://img.shields.io/badge/root-not%20required-brightgreen.svg" alt="No root required">
  <img src="https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-lightgrey.svg" alt="Platform">
</p>

<p align="center">
  <a href="./README.zh-CN.md">简体中文</a> · <a href="#features">Features</a> · <a href="#installation">Installation</a> · <a href="#quick-start">Quick Start</a> · <a href="#modules">Modules</a>
</p>

---

## Why this kit?

Most Android automation breaks on the same two walls: **`adb shell input text` mangles or drops characters** the moment a real IME is active, and **custom-rendered UIs (WeChat, Flutter, WebView, games) expose no accessibility tree**, so `uiautomator2` selectors find nothing.

This kit solves both. It uses `set_text()` where a native `EditText` exists and falls back to an ADB-Keyboard base64 broadcast where it doesn't, and it locates elements by **template matching + OCR** when there is no view hierarchy to query. Everything runs over plain ADB, with no root and nothing installed inside the target app.

---

## Features

- **Precision Tap Calibration** — Verify that `adb shell input tap` maps 1:1 to physical screen pixels using grid, boundary, and crosshair tests.
- **Universal Tap Controller** — Click, long-press, swipe, double-tap, pinch, and scroll with boundary protection and relative/absolute coordinates.
- **Device Controller** — System-level control: volume, brightness, navigation, notifications, app launch, WiFi/Bluetooth/airplane mode, battery and storage queries.
- **Vision Controller** — Template matching (OpenCV), OCR text detection (Tesseract), color region detection, scroll-until-found, screenshot diffing, and wait-for-change.
- **IME Controller** — Dual-strategy text input: `uiautomator2.set_text()` for native EditText, ADB Keyboard base64 broadcast for custom inputs (WeChat, Flutter, WebView).
- **App Scanner** — Batch-scan multiple apps to detect EditText availability and OCR readability.

All modules work without root access. A USB-debugging-enabled Android device and `adb` are sufficient.

---

## Table of Contents

- [Installation](#installation)
- [Quick Start](#quick-start)
- [Modules](#modules)
- [Configuration](#configuration)
- [Project Structure](#project-structure)
- [Troubleshooting](#troubleshooting)
- [License](#license)

---

## Installation

### 1. System Dependencies

```bash
# macOS
brew install android-platform-tools tesseract opencv

# Ubuntu/Debian
sudo apt-get update
sudo apt-get install adb tesseract-ocr libtesseract-dev python3-opencv

# Windows (via Chocolatey)
choco install adb tesseract opencv
```

### 2. Python Dependencies

```bash
pip install -r requirements.txt
```

Core Python packages:

| Package | Purpose |
|---------|---------|
| `uiautomator2` | UI hierarchy access and gesture simulation |
| `opencv-python` | Template matching, image processing |
| `pytesseract` | OCR text recognition |
| `numpy` | Numerical operations |

### 3. Enable USB Debugging on Your Device

1. Open **Settings → About phone**
2. Tap **Build number** 7 times to enable Developer options
3. Go to **Settings → System → Developer options**
4. Turn on **USB debugging**
5. Connect your phone via USB and authorize the computer

Verify connection:

```bash
adb devices
# Expected output:
# xxxxxxxx    device
```

### 4. Configure Your Device

Copy the example configuration and edit it with your device serial and screen resolution:

```bash
cp config.example.json config.json
```

Edit `config.json`:

```json
{
  "device_serial": "your_device_serial_here",
  "screen_width": 1200,
  "screen_height": 2670,
  "screenshot_dir": "./screenshots",
  "default_ime": "com.android.inputmethod.latin/.LatinIME",
  "adb_keyboard_ime": "com.android.adbkeyboard/.AdbIME"
}
```

> **Tip:** Find your device serial with `adb devices`. Find screen resolution with `adb shell wm size`.

---

## Quick Start

### Calibrate Screen Tapping

```bash
python tap_calibrator.py --verify   # Check resolution
python tap_calibrator.py --grid     # Grid test (requires Pointer location ON)
python tap_calibrator.py --boundary # Corner + center test
python tap_calibrator.py --cross 600 1335  # Crosshair around center
```

### Control Taps from Python

```python
from tap_controller import tap, swipe, scroll_down, double_tap

# Simple tap
tap(500, 1000)

# Long press (1 second)
tap(500, 1000, duration_ms=1000)

# Swipe
swipe(100, 1500, 100, 500, duration_ms=300)

# Scroll down
scroll_down()

# Relative tap (50% across, 80% down)
from tap_controller import tap_relative
tap_relative(0.5, 0.8)
```

### Automate Text Input

```python
from ime_controller import IMEController

ctrl = IMEController()

# Auto-detects native EditText vs custom input
ctrl.type_text("Hello, world!")

# Force ADB Keyboard for WeChat/Flutter/WebView
ctrl.type_text("Custom input", force_adb=True)

# Clear input
ctrl.clear()
```

### Find and Tap Visual Elements

```python
from vision_controller import VisionController

vis = VisionController()

# Tap a template image
vis.tap_template("icons/search_button.png")

# Tap text via OCR
vis.tap_text("Settings")

# Scroll down until text appears
vis.scroll_find_text("Load more", max_scrolls=10)

# Wait for screen to change after a tap
vis.wait_for_change(timeout=5)
```

### Control System Functions

```python
from device_controller import DeviceController

dev = DeviceController()

dev.press_home()
dev.set_brightness(128)
dev.set_media_volume(10)
dev.launch_app("com.android.settings")
print(dev.get_battery())
```

---

## Modules

### `tap_calibrator.py`

Diagnostic tool to confirm ADB tap coordinates map 1:1 to screen pixels.

| Flag | Description |
|------|-------------|
| `--verify` | Check that device resolution matches config |
| `--grid` | Tap a grid of points; use with "Pointer location" developer option to verify red dots align |
| `--boundary` | Tap four corners + center |
| `--cross X Y` | Tap center, left, right, top, bottom around (X,Y) |
| `--tap X Y` | Single tap at coordinate |

### `tap_controller.py`

Core tapping primitives. All coordinates are absolute pixels from top-left (0,0).

Key functions:

- `tap(x, y, duration_ms=0)` — precise tap or long-press
- `tap_safe(x, y, ...)` — tap with margin protection to avoid system gestures
- `swipe(x1, y1, x2, y2, duration_ms)` — linear swipe
- `double_tap(x, y)` — two rapid taps
- `pinch(center_x, center_y, ...)` — pinch gesture via uiautomator2
- `scroll_down/up/left/right()` — convenience scroll wrappers
- `tap_relative(px, py)` — proportional coordinates (0.0–1.0)
- `CalibrationSet` — save/load named calibration points as JSON

### `device_controller.py`

System-level Android control via ADB keyevents and service calls.

Categories:
- **Navigation** — back, home, recent, lock, wake
- **Volume** — media, ring, mute
- **Brightness** — manual level, auto-toggle
- **Screenshot / Screenrecord**
- **Notifications** — expand, collapse, clear
- **Apps** — launch, force-stop, clear data, get foreground app
- **Connectivity** — WiFi, Bluetooth, airplane mode
- **Media** — play/pause, next, prev
- **Phone / SMS** — dial, compose
- **IME** — list, switch
- **Info** — battery, storage, memory

### `vision_controller.py`

Computer-vision-based screen interaction for elements that uiautomator2 cannot access.

Capabilities:
- `find_template()` / `tap_template()` — OpenCV template matching with NMS deduplication
- `find_text()` / `tap_text()` — Tesseract OCR with confidence filtering
- `list_texts()` — enumerate all recognized text regions
- `find_color()` — detect regions by BGR color range
- `scroll_find_text()` / `scroll_find_template()` — scroll until target appears
- `compare_screenshots()` — pixel-level diff with threshold
- `wait_for_change()` — poll screen until a region changes

### `ime_controller.py`

Text input abstraction that auto-selects the best strategy.

**Strategy A — `set_text()` (preferred):**
Uses uiautomator2 to directly set text on native `EditText` elements. Bypasses the IME entirely. Works for SMS, standard apps.

**Strategy B — ADB Keyboard (fallback):**
Switches to `com.android.adbkeyboard` and broadcasts base64-encoded text. Required for WeChat custom input fields, Flutter, WebView, and other non-native text containers.

**Why not `adb shell input text`?**
Because when an IME is active, `input text` causes character reordering or crashes on many devices. This toolkit avoids it entirely.

Additional features:
- `clear()` — empty the input field
- `delete(n)` — send N backspace keyevents
- `select_all()` / `copy()` / `paste()` — clipboard operations (native EditText only)
- `get_text()` — read current input content

### `app_scanner.py`

Batch diagnostic tool. Launches a list of apps, counts native EditText elements, runs OCR, and reports which apps are easy to automate vs. which need vision/ADB-Keyboard strategies.

---

## Configuration

The toolkit reads device settings from `config.json` in the working directory. If the file is missing, it falls back to environment variables:

| Config Key | Environment Variable | Default |
|------------|----------------------|---------|
| `device_serial` | `ADB_DEVICE_SERIAL` | First device in `adb devices` |
| `screen_width` | `SCREEN_WIDTH` | 1200 |
| `screen_height` | `SCREEN_HEIGHT` | 2670 |
| `screenshot_dir` | `SCREENSHOT_DIR` | `./screenshots` |
| `default_ime` | `DEFAULT_IME` | (none) |
| `adb_keyboard_ime` | `ADB_KEYBOARD_IME` | (none) |

---

## Project Structure

```
android-adb-automation-kit/
├── README.md
├── README.zh-CN.md
├── LICENSE
├── requirements.txt
├── config.example.json
├── tap_calibrator.py      # Calibration & verification CLI
├── tap_controller.py      # Core tap/swipe/scroll API
├── device_controller.py   # System control API
├── vision_controller.py   # CV-based element detection
├── ime_controller.py      # Text input abstraction
├── app_scanner.py         # Batch app diagnostic
├── extended_verify.py     # 25-point precision grid test
└── examples/
    ├── wechat_search.py   # Example: search a contact via vision+tap
    └── settings_flow.py   # Example: navigate system settings
```

---

## Troubleshooting

### `adb devices` shows "unauthorized"

Unlock your phone and confirm the "Allow USB debugging?" dialog. Check "Always allow from this computer."

### Taps do not land where expected

Run `tap_calibrator.py --grid` with **Pointer location** enabled (Developer options). If red dots are offset, your device may have a non-standard coordinate mapping. Some devices with curved edges or display cutouts require margin adjustments — use `tap_safe()` with custom margins.

### OCR returns garbage

Install the correct Tesseract language pack:

```bash
# macOS
brew install tesseract-lang

# Ubuntu
sudo apt-get install tesseract-ocr-chi-sim tesseract-ocr-eng
```

Then set `lang="chi_sim+eng"` in VisionController methods.

### ADB Keyboard not installed

Download and install [ADB Keyboard](https://github.com/senzhk/ADBKeyBoard) on your device:

```bash
adb install adbkeyboard.apk
```

Then set its IME ID in `config.json` under `adb_keyboard_ime`.

### uiautomator2 connection fails

```bash
# Re-init uiautomator2 on the device
python -m uiautomator2 init
```

---

## License

MIT
