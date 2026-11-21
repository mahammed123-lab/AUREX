#!/usr/bin/env python3
"""
Device Controller — Android System Automation

System-level control via ADB commands. No root required.
Covers navigation, volume, brightness, screenshot, notifications,
app management, connectivity, media, and device info.
"""

import subprocess
import time
import os
from typing import Optional

from config_loader import CONFIG

DEVICE_SERIAL = CONFIG.get("device_serial", "")


def _serial_flag() -> str:
    return f"-s {DEVICE_SERIAL} " if DEVICE_SERIAL else ""


def adb(cmd: str, timeout: int = 30) -> str:
    """Execute ADB command and return stdout."""
    full_cmd = f"adb {_serial_flag()}{cmd}"
    result = subprocess.run(
        full_cmd, shell=True, capture_output=True, text=True, timeout=timeout
    )
    return result.stdout.strip() + result.stderr.strip()


class DeviceController:
    """Android device system controller."""

    def __init__(self, serial: str = ""):
        self.serial = serial or DEVICE_SERIAL

    # ── Navigation ──────────────────────────────────────────

    def press_back(self):
        adb("shell input keyevent 4")

    def press_home(self):
        adb("shell input keyevent 3")

    def press_recent(self):
        adb("shell input keyevent 187")

    def lock_screen(self):
        adb("shell input keyevent 26")

    def wake_screen(self):
        adb("shell input keyevent 26")

    def is_screen_on(self) -> bool:
        out = adb("shell dumpsys power | grep 'mWakefulness'")
        return "Awake" in out or "ON" in out

    # ── Volume ──────────────────────────────────────────────

    def set_media_volume(self, level: int):
        """Set media volume (0-15 range typical)."""
        adb(f"shell media volume --show --stream 3 --set {level}")

    def set_ring_volume(self, level: int):
        """Set ring volume (0-7 range typical)."""
        adb(f"shell cmd audio set-stream-volume 2 {level}")

    def volume_up(self, times: int = 1):
        for _ in range(times):
            adb("shell input keyevent 24")
            time.sleep(0.1)

    def volume_down(self, times: int = 1):
        for _ in range(times):
            adb("shell input keyevent 25")
            time.sleep(0.1)

    def mute(self):
        adb("shell cmd audio set-master-mute true")

    def unmute(self):
        adb("shell cmd audio set-master-mute false")

    # ── Brightness ──────────────────────────────────────────

    def set_brightness(self, level: int):
        """
        Set screen brightness (0-255).
        Disable auto-brightness first: set_auto_brightness(false)
        """
        adb(f"shell settings put system screen_brightness {level}")

    def get_brightness(self) -> int:
        out = adb("shell settings get system screen_brightness")
        try:
            return int(out)
        except ValueError:
            return -1

    def set_auto_brightness(self, enabled: bool):
        val = 1 if enabled else 0
        adb(f"shell settings put system screen_brightness_mode {val}")

    # ── Screenshot & Screenrecord ───────────────────────────

    def screenshot(self, path: Optional[str] = None) -> str:
        """Screenshot and pull to local. Returns local path."""
        remote = "/sdcard/automation_screenshot.png"
        adb(f"shell screencap -p {remote}")
        if path is None:
            path = f"screenshot_{int(time.time())}.png"
        adb(f"pull {remote} {path}")
        return path

    def start_screenrecord(self, path: str = "/sdcard/automation_record.mp4"):
        """Start screenrecord (background, max 3 min)."""
        adb("shell screenrecord --time-limit 180 " + path, timeout=5)

    def stop_screenrecord(self):
        adb("shell pkill -INT screenrecord")

    # ── Notifications ───────────────────────────────────────

    def open_notification(self):
        adb("shell cmd statusbar expand-notifications")

    def open_quick_settings(self):
        adb("shell cmd statusbar expand-settings")

    def collapse_statusbar(self):
        adb("shell cmd statusbar collapse")

    def clear_all_notifications(self):
        adb("shell service call notification 1")

    # ── App management ──────────────────────────────────────

    def launch_app(self, package: str, activity: Optional[str] = None):
        if activity:
            adb(f"shell am start -n {package}/{activity}")
        else:
            adb(f"shell monkey -p {package} -c android.intent.category.LAUNCHER 1")

    def force_stop(self, package: str):
        adb(f"shell am force-stop {package}")

    def clear_app_data(self, package: str):
        adb(f"shell pm clear {package}")

    def get_current_app(self) -> dict:
        out = adb("shell dumpsys activity activities | grep mResumedActivity")
        result = {"package": "", "activity": ""}
        if "packageName=" in out:
            result["package"] = out.split("packageName=")[1].split(",")[0].strip()
        if "mResumedActivity" in out:
            parts = out.split(" ")
            for p in parts:
                if "/" in p and "." in p:
                    result["activity"] = p.strip()
                    break
        return result

    def list_running_apps(self) -> list:
        out = adb("shell ps -A | awk '{print $9}' | sort | uniq")
        return [line for line in out.splitlines() if "." in line]

    # ── Connectivity ────────────────────────────────────────

    def set_wifi(self, enabled: bool):
        val = "enable" if enabled else "disable"
        adb(f"shell svc wifi {val}")

    def set_bluetooth(self, enabled: bool):
        val = "enable" if enabled else "disable"
        adb(f"shell svc bluetooth {val}")

    def set_airplane_mode(self, enabled: bool):
        val = 1 if enabled else 0
        adb(f"shell settings put global airplane_mode_on {val}")
        adb("shell am broadcast -a android.intent.action.AIRPLANE_MODE")

    # ── Media ───────────────────────────────────────────────

    def media_play_pause(self):
        adb("shell input keyevent 85")

    def media_next(self):
        adb("shell input keyevent 87")

    def media_prev(self):
        adb("shell input keyevent 88")

    # ── Phone & SMS ─────────────────────────────────────────

    def dial_number(self, number: str):
        """Open dialer (does not auto-call)."""
        safe = number.replace(" ", "").replace("-", "")
        adb(f'shell am start -a android.intent.action.DIAL -d "tel:{safe}"')

    def open_sms_compose(self, number: str, body: str = ""):
        safe = number.replace(" ", "").replace("-", "")
        cmd = f'shell am start -a android.intent.action.SENDTO -d "sms:{safe}"'
        if body:
            cmd += f' --es sms_body "{body}"'
        adb(cmd)

    # ── IME ─────────────────────────────────────────────────

    def set_ime(self, ime_id: str):
        adb(f"shell ime set {ime_id}")

    def get_current_ime(self) -> str:
        return adb("shell settings get secure default_input_method")

    def list_imes(self) -> list:
        out = adb("shell ime list -a -s")
        return [line.strip() for line in out.splitlines() if line.strip()]

    # ── Device info ─────────────────────────────────────────

    def get_battery(self) -> dict:
        out = adb("shell dumpsys battery")
        info = {}
        for line in out.splitlines():
            if "level:" in line:
                info["level"] = int(line.split(":")[1].strip())
            elif "status:" in line:
                info["status"] = line.split(":")[1].strip()
            elif "temperature:" in line:
                info["temperature"] = int(line.split(":")[1].strip()) / 10
        return info

    def get_storage(self) -> dict:
        out = adb("shell df /data | tail -1")
        parts = out.split()
        if len(parts) >= 4:
            return {
                "total": parts[1],
                "used": parts[2],
                "available": parts[3],
            }
        return {}

    def get_memory(self) -> dict:
        out = adb("shell cat /proc/meminfo | head -4")
        info = {}
        for line in out.splitlines():
            if "MemTotal" in line:
                info["total"] = line.split(":")[1].strip()
            elif "MemFree" in line:
                info["free"] = line.split(":")[1].strip()
            elif "MemAvailable" in line:
                info["available"] = line.split(":")[1].strip()
        return info


# ── Self-test ─────────────────────────────────────────────


def self_test():
    print("=" * 50)
    print("Device Controller Self-Test")
    print("=" * 50)

    ctrl = DeviceController()

    print("\n[Test 1] Device info")
    print(f"  Battery: {ctrl.get_battery()}")
    print(f"  Storage: {ctrl.get_storage()}")
    print(f"  Memory:  {ctrl.get_memory()}")
    print(f"  Screen:  {'On' if ctrl.is_screen_on() else 'Off'}")
    print("[Test 1] OK")

    print("\n[Test 2] IME list")
    print(f"  Current: {ctrl.get_current_ime()}")
    print(f"  Available: {ctrl.list_imes()}")
    print("[Test 2] OK")

    print("\n[Test 3] Screenshot")
    path = ctrl.screenshot("device_test_screenshot.png")
    print(f"  Saved to: {path}")
    print("[Test 3] OK")

    print("\n" + "=" * 50)
    print("All tests complete")
    print("=" * 50)


if __name__ == "__main__":
    self_test()
