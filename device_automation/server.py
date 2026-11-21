#!/usr/bin/env python3
"""
AUREX Device Control Server.

A self-contained (stdlib-only) HTTP server that drives a connected Android
device over ADB. AUREX on the phone reaches it via:
    adb reverse tcp:8765 tcp:8765
and then calls http://127.0.0.1:8765/...

This is the "deep OS control" layer: it uses the same `adb shell` privileges
that `svc wifi/bluetooth`, `settings put`, and `am start` expose — no root.
"""
import subprocess
import json
import os
import shlex
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

ADB = os.environ.get("ADB_PATH", r"C:\Users\maham\Android\Sdk\platform-tools\adb.exe")
PORT = int(os.environ.get("AUREX_CTRL_PORT", "8765"))
DEVICE_SERIAL = os.environ.get("ADB_DEVICE_SERIAL", "")


def adb(cmd: str, timeout: int = 30) -> str:
    flag = ["-s", DEVICE_SERIAL] if DEVICE_SERIAL else []
    full = [ADB] + flag + ["shell"] + cmd.split()
    try:
        r = subprocess.run(full, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or "").strip() + (("\n" + r.stderr.strip()) if r.stderr and r.stderr.strip() else "")
    except Exception as e:
        return f"ERR: {e}"


def adb_host(cmd: str, timeout: int = 30) -> str:
    flag = ["-s", DEVICE_SERIAL] if DEVICE_SERIAL else []
    full = [ADB] + flag + cmd.split()
    try:
        r = subprocess.run(full, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or "").strip()
    except Exception as e:
        return f"ERR: {e}"


def handle(path: str, query: dict) -> dict:
    parts = [p for p in path.strip("/").split("/") if p]
    if not parts:
        return {"ok": True, "service": "AUREX Device Control Server"}

    action = parts[0].lower()

    # ── connectivity ──
    if action == "wifi":
        on = parts[1].lower() == "on" if len(parts) > 1 else None
        out = adb(f"svc wifi {'enable' if on else 'disable'}")
        return {"ok": True, "wifi": on, "out": out}
    if action == "bluetooth":
        on = parts[1].lower() == "on" if len(parts) > 1 else None
        out = adb(f"svc bluetooth {'enable' if on else 'disable'}")
        return {"ok": True, "bluetooth": on, "out": out}
    if action == "airplane":
        on = parts[1].lower() == "on" if len(parts) > 1 else None
        val = 1 if on else 0
        adb(f"settings put global airplane_mode_on {val}")
        out = adb("am broadcast -a android.intent.action.AIRPLANE_MODE")
        return {"ok": True, "airplane": on, "out": out}
    if action == "brightness":
        lvl = int(parts[1]) if len(parts) > 1 else 128
        out = adb(f"settings put system screen_brightness {max(0, min(255, lvl))}")
        return {"ok": True, "level": lvl, "out": out}
    if action == "dnd":
        on = parts[1].lower() == "on" if len(parts) > 1 else None
        # values: 1=all off, 2=priority only, 3=total silence, 4=alarms only
        mode = "3" if on else "1"
        out = adb(f"settings put global zen_mode {mode}")
        return {"ok": True, "dnd": on, "out": out}

    # ── alarm / intents ──
    if action == "alarm":
        hour = int(query.get("hour", ["7"])[0])
        minute = int(query.get("minute", ["0"])[0])
        label = query.get("label", ["Alarm"])[0]
        out = adb(
            f"am start -a android.intent.action.SET_ALARM "
            f"--ei android.intent.extra.alarm.HOUR {hour} "
            f"--ei android.intent.extra.alarm.MINUTES {minute} "
            f"--es android.intent.extra.alarm.MESSAGE \"{label}\" "
            f"--ez android.intent.extra.alarm.SKIP_UI false"
        )
        return {"ok": True, "alarm": f"{hour:02d}:{minute:02d}", "out": out}

    # ── raw gestures (px) ──
    if action in ("tap", "swipe", "longpress", "keyevent", "text", "launch", "home", "back", "recent", "screenshot", "dump", "status"):
        return handle_device(action, parts, query)

    return {"ok": False, "error": f"unknown action {action}"}


def handle_device(action: str, parts: list, query: dict) -> dict:
    if action == "tap":
        x, y = int(query.get("x", ["0"])[0]), int(query.get("y", ["0"])[0])
        return {"ok": True, "out": adb(f"input tap {x} {y}")}
    if action == "longpress":
        x, y = int(query.get("x", ["0"])[0]), int(query.get("y", ["0"])[0])
        ms = int(query.get("ms", ["800"])[0])
        return {"ok": True, "out": adb(f"input swipe {x} {y} {x} {y} {ms}")}
    if action == "swipe":
        x1, y1 = int(query.get("x1", ["0"])[0]), int(query.get("y1", ["0"])[0])
        x2, y2 = int(query.get("x2", ["0"])[0]), int(query.get("y2", ["0"])[0])
        ms = int(query.get("ms", ["300"])[0])
        return {"ok": True, "out": adb(f"input swipe {x1} {y1} {x2} {y2} {ms}")}
    if action == "keyevent":
        code = int(query.get("code", ["3"])[0])
        return {"ok": True, "out": adb(f"input keyevent {code}")}
    if action == "text":
        t = query.get("t", [" "])[0].replace(" ", "%s")
        return {"ok": True, "out": adb(f"input text {t}")}
    if action == "launch":
        pkg = query.get("pkg", [""])[0]
        return {"ok": True, "out": adb(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")}
    if action == "home":
        return {"ok": True, "out": adb("input keyevent 3")}
    if action == "back":
        return {"ok": True, "out": adb("input keyevent 4")}
    if action == "recent":
        return {"ok": True, "out": adb("input keyevent 187")}
    if action == "screenshot":
        remote = "/sdcard/aurex_shot.png"
        adb(f"screencap -p {remote}")
        return {"ok": True, "remote": remote}
    if action == "dump":
        out = adb("uiautomator dump /sdcard/aurex_dump.xml && cat /sdcard/aurex_dump.xml")
        return {"ok": True, "xml": out}
    if action == "status":
        wifi = adb("settings get global wifi_on")
        bt = adb_host("-s " + DEVICE_SERIAL if DEVICE_SERIAL else "") if False else ""
        return {"ok": True, "wifi_on": wifi, "device": DEVICE_SERIAL or "default"}
    return {"ok": False, "error": f"unhandled {action}"}


class Handler(BaseHTTPRequestHandler):
    def _json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        q = parse_qs(parsed.query)
        if parsed.path == "/ping":
            self._json({"ok": True, "service": "aurex-device-control", "device": DEVICE_SERIAL})
            return
        self._json(handle(parsed.path, q))

    def do_POST(self):
        parsed = urlparse(self.path)
        q = parse_qs(parsed.query)
        try:
            ln = int(self.headers.get("Content-Length", 0))
            if ln > 0:
                body = json.loads(self.rfile.read(ln).decode() or "{}")
                if isinstance(body, dict):
                    q.update({k: [str(v)] for k, v in body.items()})
        except Exception:
            pass
        self._json(handle(parsed.path, q))

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    print(f"AUREX Device Control Server on :{PORT}")
    print(f"ADB = {ADB} | device = {DEVICE_SERIAL or 'default'}")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()