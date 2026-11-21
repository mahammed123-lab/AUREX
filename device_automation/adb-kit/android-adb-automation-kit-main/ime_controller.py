#!/usr/bin/env python3
"""
IME Controller — Android text-input automation with a dual strategy.

Strategy A (preferred): uiautomator2 set_text() writes directly to a native
    EditText, bypassing the on-screen keyboard entirely. Reliable for SMS and
    standard apps.
Strategy B (fallback): ADB Keyboard base64 broadcast, for custom input fields
    (WeChat, Flutter, WebView) that have no native EditText.

`adb shell input text` is deliberately avoided — when an IME is active it
reorders characters or crashes on many devices.

Configure the two IME ids in config.json:
    "default_ime":      your normal keyboard, restored after ADB-Keyboard use
    "adb_keyboard_ime": the ADB Keyboard IME id (e.g. com.android.adbkeyboard/.AdbIME)
"""

import base64
import subprocess
import time
from typing import Optional

import uiautomator2 as u2

from config_loader import CONFIG

DEVICE_SERIAL = CONFIG.get("device_serial", "")
DEFAULT_IME = CONFIG.get("default_ime", "")
ADB_KEYBOARD_IME = CONFIG.get("adb_keyboard_ime", "com.android.adbkeyboard/.AdbIME")

# Context-menu button labels. Override for non-Chinese locales
# (e.g. "Select all" / "Copy" / "Paste").
LABEL_SELECT_ALL = CONFIG.get("label_select_all", "全选")
LABEL_COPY = CONFIG.get("label_copy", "复制")
LABEL_PASTE = CONFIG.get("label_paste", "粘贴")


def adb(cmd: str, timeout: int = 30) -> str:
    """Execute an ADB command and return its combined output."""
    serial_flag = f"-s {DEVICE_SERIAL} " if DEVICE_SERIAL else ""
    result = subprocess.run(
        f"adb {serial_flag}{cmd}",
        shell=True, capture_output=True, text=True, timeout=timeout,
    )
    return result.stdout.strip() + result.stderr.strip()


class IMEController:
    """Android text-input controller (set_text preferred, ADB Keyboard fallback)."""

    def __init__(self, serial: str = ""):
        self.serial = serial or DEVICE_SERIAL
        self.d = u2.connect(self.serial) if self.serial else u2.connect()

    # ── Target EditText resolution ────────────────────────

    def _get_edit(self, resource_id: Optional[str] = None, click_first: bool = True):
        """Resolve the target EditText, clicking it first to gain focus if needed."""
        if resource_id:
            edit = self.d(resourceId=resource_id)
        else:
            edit = self.d(className="android.widget.EditText", focused=True)
            if not edit.exists:
                edit = self.d(className="android.widget.EditText")
        if click_first and edit.exists:
            edit.click()
            time.sleep(0.2)
        return edit

    def has_native_edittext(self) -> bool:
        """Whether the current screen exposes a native EditText."""
        return self.d(className="android.widget.EditText").exists

    # ── Strategy selection ────────────────────────────────

    def _use_adb_keyboard(self) -> bool:
        return not self.has_native_edittext()

    def _switch_to_ime(self, ime_id: str):
        print(f"[IME] switching to {ime_id} ...")
        adb(f"shell ime set {ime_id}")
        time.sleep(0.8)

    def _restore_default_ime(self):
        if DEFAULT_IME:
            print("[IME] restoring default keyboard ...")
            adb(f"shell ime set {DEFAULT_IME}")
            time.sleep(0.5)

    # ── ADB Keyboard input ────────────────────────────────

    def _adb_keyboard_input(self, text: str):
        """Send text via the ADB Keyboard base64 broadcast."""
        if ADB_KEYBOARD_IME not in adb("shell settings get secure default_input_method"):
            self._switch_to_ime(ADB_KEYBOARD_IME)
        b64 = base64.b64encode(text.encode("utf-8")).decode()
        adb(f"shell am broadcast -a ADB_INPUT_B64 --es msg {b64}")
        time.sleep(0.3)

    def _adb_keyboard_clear(self):
        """Clear the field via ADB Keyboard (repeated backspace)."""
        if ADB_KEYBOARD_IME not in adb("shell settings get secure default_input_method"):
            self._switch_to_ime(ADB_KEYBOARD_IME)
        for _ in range(50):
            adb("shell am broadcast -a ADB_INPUT_CODE --ei code 67")
            time.sleep(0.02)
        time.sleep(0.3)

    # ── Unified text input ────────────────────────────────

    def type_text(self, text: str, resource_id: Optional[str] = None,
                  append: bool = False, force_adb: bool = False):
        """
        Input text, auto-selecting the best strategy.

        Args:
            text: text to type
            resource_id: optional resource-id of the target EditText
            append: append to existing content instead of replacing
            force_adb: force the ADB Keyboard path (skip EditText detection)
        """
        if not text:
            return

        if force_adb or self._use_adb_keyboard():
            print(f"[IME] ADB Keyboard input: {text[:50]}...")
            try:
                self._adb_keyboard_input(text)
            finally:
                self._restore_default_ime()
        else:
            edit = self._get_edit(resource_id)
            if not edit.exists:
                raise RuntimeError("No native EditText found (try force_adb=True)")
            if append:
                current = edit.get_text() or ""
                edit.set_text(current + text)
            else:
                edit.set_text(text)
            time.sleep(0.3)

    def append_text(self, text: str, resource_id: Optional[str] = None,
                    force_adb: bool = False):
        """Append text to the existing field content."""
        self.type_text(text, resource_id, append=True, force_adb=force_adb)

    # ── Editing ───────────────────────────────────────────

    def clear(self, resource_id: Optional[str] = None, force_adb: bool = False):
        """Clear the input field."""
        if force_adb or self._use_adb_keyboard():
            self._adb_keyboard_clear()
            self._restore_default_ime()
        else:
            edit = self._get_edit(resource_id)
            if edit.exists:
                edit.clear_text()
                time.sleep(0.3)
            else:
                raise RuntimeError("No editable EditText found")

    def delete(self, n: int = 1):
        """Delete n characters via keyevent 67 (IME-independent)."""
        for _ in range(n):
            adb("shell input keyevent 67")
            time.sleep(0.05)

    def select_all(self, resource_id: Optional[str] = None) -> bool:
        """Select all content (native EditText only)."""
        edit = self._get_edit(resource_id)
        if edit.exists:
            edit.long_click()
            time.sleep(0.5)
            btn = self.d(text=LABEL_SELECT_ALL)
            if btn.exists:
                btn.click()
                time.sleep(0.3)
                return True
        return False

    def copy(self, resource_id: Optional[str] = None) -> bool:
        """Copy field content to the system clipboard (native EditText only)."""
        if self._use_adb_keyboard():
            print("[IME] warning: custom input fields do not support copy()")
            return False
        if self.select_all(resource_id):
            btn = self.d(text=LABEL_COPY)
            if btn.exists:
                btn.click()
                time.sleep(0.3)
                return True
        return False

    def paste(self, resource_id: Optional[str] = None) -> bool:
        """Paste from the system clipboard (native EditText only)."""
        if self._use_adb_keyboard():
            print("[IME] warning: custom input fields do not support paste(); use type_text()")
            return False
        edit = self._get_edit(resource_id)
        if edit.exists:
            edit.long_click()
            time.sleep(0.5)
            btn = self.d(text=LABEL_PASTE)
            if btn.exists:
                btn.click()
                time.sleep(0.3)
                return True
        return False

    def get_text(self, resource_id: Optional[str] = None) -> str:
        """Read the current field content (native EditText only)."""
        edit = self._get_edit(resource_id, click_first=False)
        if edit.exists:
            return edit.get_text() or ""
        return ""


# ── Self-test ───────────────────────────────────────────

def self_test():
    """Interactive self-test (open a messaging app with an input field first)."""
    print("=" * 50)
    print("IME Controller self-test")
    print("=" * 50)

    ctrl = IMEController()

    input("Focus a text field, then press Enter to type 'HelloWorld123'...")
    ctrl.type_text("HelloWorld123")
    print("[Test 1] done")

    ctrl.type_text("自动化测试", append=True)
    print("[Test 2] done")

    print("\nAll tests complete")


if __name__ == "__main__":
    self_test()
