#!/usr/bin/env python3
"""
Vision Controller — computer-vision based screen element location and interaction.

Combines OpenCV template matching, Tesseract OCR text recognition, and color
detection to locate and act on elements that uiautomator2 cannot access.

Typical use cases:
- Custom Views with no accessibility info (WeChat, games, kiosk apps)
- Buttons / text inside Flutter or WebView containers
- Dynamic lists that must be scrolled until the target appears
"""

import subprocess
import tempfile
import time
from dataclasses import dataclass
from typing import List, Optional, Tuple

import cv2
import numpy as np
import pytesseract
import uiautomator2 as u2

from config_loader import CONFIG
from tap_controller import tap_safe, swipe

DEVICE_SERIAL = CONFIG.get("device_serial", "")
SCREEN_W = CONFIG.get("screen_width", 1200)
SCREEN_H = CONFIG.get("screen_height", 2670)


@dataclass
class MatchResult:
    """A located screen element."""
    x: int
    y: int
    width: int
    height: int
    confidence: float
    label: str = ""

    @property
    def center(self) -> Tuple[int, int]:
        return (self.x + self.width // 2, self.y + self.height // 2)


class VisionController:
    """Vision-based screen element controller."""

    def __init__(self, serial: str = ""):
        self.serial = serial or DEVICE_SERIAL
        self.d = u2.connect(self.serial) if self.serial else u2.connect()

    # ── Screenshot capture ────────────────────────────────

    def get_screenshot(self, save_path: Optional[str] = None) -> np.ndarray:
        """Capture the current screen as an OpenCV BGR image."""
        local_path = save_path or tempfile.mktemp(suffix=".png")

        # exec-out streams the PNG over stdout, avoiding sdcard permission issues
        serial_flag = f"-s {self.serial} " if self.serial else ""
        cmd = f"adb {serial_flag}exec-out screencap -p"
        result = subprocess.run(cmd, shell=True, capture_output=True)
        if result.returncode != 0:
            raise RuntimeError(f"Screenshot failed: {result.stderr.decode()}")

        with open(local_path, "wb") as f:
            f.write(result.stdout)

        img = cv2.imread(local_path)
        if img is None:
            raise RuntimeError(f"Cannot read screenshot: {local_path}")
        return img

    # ── Template matching ─────────────────────────────────

    def find_template(self, template_path: str, threshold: float = 0.8,
                      screenshot: Optional[np.ndarray] = None) -> Optional[MatchResult]:
        """
        Find a template image on screen and return the best match.

        Args:
            template_path: path to the template image
            threshold: match score threshold (0-1)
            screenshot: optional pre-captured screen to avoid re-capturing
        """
        templ = cv2.imread(template_path)
        if templ is None:
            raise ValueError(f"Cannot read template image: {template_path}")

        screen = screenshot if screenshot is not None else self.get_screenshot()
        result = cv2.matchTemplate(screen, templ, cv2.TM_CCOEFF_NORMED)
        _, max_val, _, max_loc = cv2.minMaxLoc(result)

        if max_val >= threshold:
            h, w = templ.shape[:2]
            return MatchResult(
                x=max_loc[0], y=max_loc[1],
                width=w, height=h,
                confidence=max_val,
                label=template_path,
            )
        return None

    def find_all_templates(self, template_path: str, threshold: float = 0.8,
                           screenshot: Optional[np.ndarray] = None) -> List[MatchResult]:
        """Find every matching instance of a template (NMS-deduplicated)."""
        templ = cv2.imread(template_path)
        if templ is None:
            raise ValueError(f"Cannot read template image: {template_path}")

        screen = screenshot if screenshot is not None else self.get_screenshot()
        result = cv2.matchTemplate(screen, templ, cv2.TM_CCOEFF_NORMED)

        loc = np.where(result >= threshold)
        h, w = templ.shape[:2]

        boxes = [[pt[0], pt[1], pt[0] + w, pt[1] + h] for pt in zip(*loc[::-1])]
        if not boxes:
            return []

        # Non-maximum suppression to drop overlapping detections
        indices = cv2.dnn.NMSBoxes(boxes, [1.0] * len(boxes), threshold, 0.3)

        results = []
        for i in indices.flatten() if len(indices) > 0 else []:
            x1, y1, x2, y2 = boxes[i]
            results.append(MatchResult(
                x=x1, y=y1, width=x2 - x1, height=y2 - y1,
                confidence=float(result[y1, x1]), label=template_path,
            ))
        return results

    def tap_template(self, template_path: str, threshold: float = 0.8) -> bool:
        """Find a template and tap its center. Returns whether it was found."""
        match = self.find_template(template_path, threshold)
        if match:
            cx, cy = match.center
            tap_safe(cx, cy)
            return True
        return False

    # ── OCR text location ─────────────────────────────────

    def find_text(self, target_text: str, lang: str = "chi_sim+eng",
                  screenshot: Optional[np.ndarray] = None) -> Optional[MatchResult]:
        """
        Locate text on screen via OCR and return its bounding box.

        Args:
            target_text: substring to search for
            lang: Tesseract language pack(s)
            screenshot: optional pre-captured screen
        """
        screen = screenshot if screenshot is not None else self.get_screenshot()
        rgb = cv2.cvtColor(screen, cv2.COLOR_BGR2RGB)

        data = pytesseract.image_to_data(rgb, lang=lang, output_type=pytesseract.Output.DICT)

        best_match = None
        best_conf = 0
        for i, text in enumerate(data["text"]):
            if target_text in text:
                conf = int(data["conf"][i])
                if conf > best_conf:
                    best_conf = conf
                    best_match = MatchResult(
                        x=data["left"][i], y=data["top"][i],
                        width=data["width"][i], height=data["height"][i],
                        confidence=conf / 100.0, label=text,
                    )
        return best_match

    def tap_text(self, target_text: str, lang: str = "chi_sim+eng") -> bool:
        """OCR-locate text and tap it."""
        match = self.find_text(target_text, lang)
        if match:
            cx, cy = match.center
            tap_safe(cx, cy)
            return True
        return False

    def list_texts(self, min_conf: int = 30, lang: str = "chi_sim+eng",
                   screenshot: Optional[np.ndarray] = None) -> List[MatchResult]:
        """List all recognized text regions and their positions."""
        screen = screenshot if screenshot is not None else self.get_screenshot()
        rgb = cv2.cvtColor(screen, cv2.COLOR_BGR2RGB)

        data = pytesseract.image_to_data(rgb, lang=lang, output_type=pytesseract.Output.DICT)

        results = []
        for i, text in enumerate(data["text"]):
            conf = int(data["conf"][i])
            if conf >= min_conf and text.strip():
                results.append(MatchResult(
                    x=data["left"][i], y=data["top"][i],
                    width=data["width"][i], height=data["height"][i],
                    confidence=conf / 100.0, label=text,
                ))
        return results

    # ── Color detection ───────────────────────────────────

    def find_color(self, lower_bgr: Tuple[int, int, int],
                   upper_bgr: Tuple[int, int, int],
                   min_area: int = 500,
                   screenshot: Optional[np.ndarray] = None) -> Optional[MatchResult]:
        """
        Find the largest screen region within a BGR color range.

        Args:
            lower_bgr: lower BGR bound (B, G, R)
            upper_bgr: upper BGR bound
            min_area: minimum contour area in pixels
        """
        screen = screenshot if screenshot is not None else self.get_screenshot()
        mask = cv2.inRange(screen, np.array(lower_bgr), np.array(upper_bgr))

        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        best = None
        best_area = 0
        for cnt in contours:
            area = cv2.contourArea(cnt)
            if area >= min_area and area > best_area:
                best_area = area
                x, y, w, h = cv2.boundingRect(cnt)
                best = MatchResult(x=x, y=y, width=w, height=h,
                                   confidence=min(area / 10000, 1.0),
                                   label=f"color_area_{int(area)}")
        return best

    # ── Scroll-until-found ────────────────────────────────

    def _scroll_vector(self, direction: str, distance: int):
        """Return (x1, y1, x2, y2) for a vertical scroll swipe centered on screen."""
        cx, cy = SCREEN_W // 2, SCREEN_H // 2
        if direction == "down":
            return cx, cy, cx, cy + distance
        return cx, cy, cx, cy - distance

    def scroll_find_text(self, target_text: str, scroll_direction: str = "down",
                         max_scrolls: int = 10, scroll_distance: int = 800,
                         lang: str = "chi_sim+eng") -> Optional[MatchResult]:
        """Scroll a page until the given text appears or max_scrolls is reached."""
        x1, y1, x2, y2 = self._scroll_vector(scroll_direction, scroll_distance)
        for _ in range(max_scrolls):
            screen = self.get_screenshot()
            match = self.find_text(target_text, lang, screenshot=screen)
            if match:
                return match
            swipe(x1, y1, x2, y2, 500)
            time.sleep(0.8)
        return None

    def scroll_find_template(self, template_path: str, scroll_direction: str = "down",
                             max_scrolls: int = 10, scroll_distance: int = 800,
                             threshold: float = 0.8) -> Optional[MatchResult]:
        """Scroll a page until the given template image appears."""
        x1, y1, x2, y2 = self._scroll_vector(scroll_direction, scroll_distance)
        for _ in range(max_scrolls):
            screen = self.get_screenshot()
            match = self.find_template(template_path, threshold, screenshot=screen)
            if match:
                return match
            swipe(x1, y1, x2, y2, 500)
            time.sleep(0.8)
        return None

    # ── Screenshot diffing ────────────────────────────────

    def compare_screenshots(self, path1: str, path2: str,
                            diff_threshold: int = 30) -> Tuple[bool, np.ndarray]:
        """
        Compare two screenshots and return a difference mask.

        Returns:
            (is_same, visualization) — pixels that differ are marked red.
        """
        img1 = cv2.imread(path1)
        img2 = cv2.imread(path2)
        if img1 is None or img2 is None:
            raise ValueError("Cannot read screenshot files")
        if img1.shape != img2.shape:
            return False, np.zeros_like(img1)

        diff = cv2.absdiff(img1, img2)
        gray = cv2.cvtColor(diff, cv2.COLOR_BGR2GRAY)
        _, mask = cv2.threshold(gray, diff_threshold, 255, cv2.THRESH_BINARY)

        diff_pixels = cv2.countNonZero(mask)
        is_same = diff_pixels / mask.size < 0.01  # < 1% difference counts as identical

        vis = img2.copy()
        vis[mask > 0] = [0, 0, 255]
        return is_same, vis

    def wait_for_change(self, timeout: int = 10, check_interval: float = 0.5,
                        region: Optional[Tuple[int, int, int, int]] = None) -> bool:
        """
        Wait until the screen changes (e.g. a load or navigation completes).

        Args:
            timeout: maximum seconds to wait
            check_interval: polling interval
            region: optional (x, y, w, h) to monitor only part of the screen
        """
        prev = self.get_screenshot()
        if region:
            x, y, w, h = region
            prev = prev[y:y + h, x:x + w]

        start = time.time()
        while time.time() - start < timeout:
            time.sleep(check_interval)
            curr = self.get_screenshot()
            if region:
                curr = curr[y:y + h, x:x + w]
            if prev.shape == curr.shape:
                diff = cv2.absdiff(prev, curr)
                gray = cv2.cvtColor(diff, cv2.COLOR_BGR2GRAY)
                _, mask = cv2.threshold(gray, 30, 255, cv2.THRESH_BINARY)
                if cv2.countNonZero(mask) > 100:
                    return True
            prev = curr
        return False


# ── Self-test ───────────────────────────────────────────

def self_test():
    """Run a basic self-test against the connected device."""
    print("=" * 50)
    print("Vision Controller self-test")
    print("=" * 50)

    ctrl = VisionController()

    print("\n[Test 1] OCR text recognition")
    texts = ctrl.list_texts(min_conf=50)
    print(f"  recognized {len(texts)} text elements")
    for t in texts[:10]:
        print(f"    '{t.label}' at ({t.x},{t.y}) conf={t.confidence:.2f}")

    print("\n[Test 2] Screenshot diff")
    ctrl.get_screenshot("/tmp/vision_test1.png")
    time.sleep(1)
    ctrl.get_screenshot("/tmp/vision_test2.png")
    is_same, _ = ctrl.compare_screenshots("/tmp/vision_test1.png", "/tmp/vision_test2.png")
    print(f"  screenshots identical: {is_same}")

    print("\nAll tests complete")


if __name__ == "__main__":
    self_test()
