<p align="center">
  <strong>Android ADB Automation Kit</strong>
</p>

<p align="center">
  基于 ADB 的像素级屏幕点击、视觉元素定位和输入法自动化工具集
</p>

<p align="center">
  <a href="./README.md">English</a> · <a href="#功能特性">功能特性</a> · <a href="#安装">安装</a> · <a href="#快速开始">快速开始</a> · <a href="#模块说明">模块说明</a>
</p>

---

## 功能特性

- **点击精度校准** — 通过网格、边界、十字准星测试验证 `adb shell input tap` 与屏幕像素是否1:1对应。
- **通用点击控制** — 单击、长按、滑动、双击、捏合、滚动，支持边界保护和相对/绝对坐标。
- **设备控制器** — 系统级控制：音量、亮度、导航、通知、应用启动、WiFi/蓝牙/飞行模式、电池和存储查询。
- **视觉控制器** — 模板匹配（OpenCV）、OCR文字识别（Tesseract）、颜色区域检测、滚动查找、截图对比、变化等待。
- **输入法控制器** — 双策略文本输入：`uiautomator2.set_text()` 用于原生EditText，ADB Keyboard Base64广播用于自定义输入框（微信、Flutter、WebView）。
- **应用扫描器** — 批量扫描多个应用，检测EditText可用性和OCR可读性。

所有模块无需root权限，仅需开启USB调试的Android设备和`adb`即可。

---

## 目录

- [安装](#安装)
- [快速开始](#快速开始)
- [模块说明](#模块说明)
- [配置](#配置)
- [项目结构](#项目结构)
- [故障排查](#故障排查)
- [开源协议](#开源协议)

---

## 安装

### 1. 系统依赖

```bash
# macOS
brew install android-platform-tools tesseract opencv

# Ubuntu/Debian
sudo apt-get update
sudo apt-get install adb tesseract-ocr libtesseract-dev python3-opencv

# Windows (Chocolatey)
choco install adb tesseract opencv
```

### 2. Python依赖

```bash
pip install -r requirements.txt
```

核心Python包：

| 包名 | 用途 |
|------|------|
| `uiautomator2` | UI层级访问和手势模拟 |
| `opencv-python` | 模板匹配、图像处理 |
| `pytesseract` | OCR文字识别 |
| `numpy` | 数值运算 |

### 3. 开启设备的USB调试

1. 打开**设置 → 关于手机**
2. 连续点击**版本号**7次，开启开发者选项
3. 进入**设置 → 系统 → 开发者选项**
4. 打开**USB调试**
5. 用USB连接电脑，在手机上授权

验证连接：

```bash
adb devices
# 预期输出：
# xxxxxxxx    device
```

### 4. 配置你的设备

复制示例配置文件并编辑：

```bash
cp config.example.json config.json
```

编辑 `config.json`：

```json
{
  "device_serial": "你的设备序列号",
  "screen_width": 1200,
  "screen_height": 2670,
  "screenshot_dir": "./screenshots",
  "default_ime": "com.android.inputmethod.latin/.LatinIME",
  "adb_keyboard_ime": "com.android.adbkeyboard/.AdbIME"
}
```

> **提示：** 用 `adb devices` 查看设备序列号。用 `adb shell wm size` 查看屏幕分辨率。

---

## 快速开始

### 校准屏幕点击

```bash
python tap_calibrator.py --verify   # 检查分辨率
python tap_calibrator.py --grid     # 网格测试（需开启指针位置）
python tap_calibrator.py --boundary # 四角+中心测试
python tap_calibrator.py --cross 600 1335  # 围绕中心十字测试
```

### 从Python控制点击

```python
from tap_controller import tap, swipe, scroll_down, double_tap

# 简单点击
tap(500, 1000)

# 长按1秒
tap(500, 1000, duration_ms=1000)

# 滑动
swipe(100, 1500, 100, 500, duration_ms=300)

# 向下滚动
scroll_down()

# 相对坐标点击（横向50%，纵向80%）
from tap_controller import tap_relative
tap_relative(0.5, 0.8)
```

### 自动化文本输入

```python
from ime_controller import IMEController

ctrl = IMEController()

# 自动检测原生EditText vs自定义输入框
ctrl.type_text("Hello, world!")

# 强制使用ADB Keyboard（微信/Flutter/WebView）
ctrl.type_text("自定义输入", force_adb=True)

# 清空输入框
ctrl.clear()
```

### 查找并点击视觉元素

```python
from vision_controller import VisionController

vis = VisionController()

# 点击模板图像
vis.tap_template("icons/search_button.png")

# OCR查找文字并点击
vis.tap_text("设置")

# 向下滚动直到文字出现
vis.scroll_find_text("加载更多", max_scrolls=10)

# 点击后等待屏幕变化
vis.wait_for_change(timeout=5)
```

### 控制系统功能

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

## 模块说明

### `tap_calibrator.py`

诊断工具，确认ADB点击坐标与屏幕像素1:1对应。

| 参数 | 说明 |
|------|------|
| `--verify` | 检查设备分辨率是否与配置一致 |
| `--grid` | 网格点击测试（需开启开发者选项"指针位置"） |
| `--boundary` | 四角+中心点击测试 |
| `--cross X Y` | 围绕(X,Y)的十字点击测试 |
| `--tap X Y` | 单点点击 |

### `tap_controller.py`

核心点击原语。所有坐标均为从左上角(0,0)开始的绝对像素值。

关键函数：

- `tap(x, y, duration_ms=0)` — 精确点击或长按
- `tap_safe(x, y, ...)` — 带边距保护的安全点击，避免触发系统手势
- `swipe(x1, y1, x2, y2, duration_ms)` — 直线滑动
- `double_tap(x, y)` — 快速双击
- `pinch(center_x, center_y, ...)` — 捏合手势（通过uiautomator2）
- `scroll_down/up/left/right()` — 便捷滚动封装
- `tap_relative(px, py)` — 相对坐标(0.0–1.0)
- `CalibrationSet` — 以JSON格式保存/加载命名校准点

### `device_controller.py`

通过ADB keyevent和service call实现的系统级Android控制。

分类：
- **导航** — 返回、主页、最近任务、锁屏、唤醒
- **音量** — 媒体音量、铃声音量、静音
- **亮度** — 手动调节、自动切换
- **截图 / 录屏**
- **通知** — 展开、收起、清除
- **应用** — 启动、强制停止、清除数据、获取前台应用
- **连接性** — WiFi、蓝牙、飞行模式
- **媒体** — 播放/暂停、下一首、上一首
- **电话 / 短信** — 拨号、编辑短信
- **输入法** — 列出、切换
- **信息** — 电池、存储、内存

### `vision_controller.py`

针对uiautomator2无法访问的元素，基于计算机视觉的屏幕交互。

能力：
- `find_template()` / `tap_template()` — OpenCV模板匹配，含NMS去重
- `find_text()` / `tap_text()` — Tesseract OCR，带置信度过滤
- `list_texts()` — 枚举所有识别到的文字区域
- `find_color()` — 按BGR颜色范围检测区域
- `scroll_find_text()` / `scroll_find_template()` — 滚动直到目标出现
- `compare_screenshots()` — 像素级差异对比，带阈值
- `wait_for_change()` — 轮询屏幕直到区域变化

### `ime_controller.py`

自动选择最佳策略的文本输入抽象层。

**策略A — `set_text()`（首选）：**
使用uiautomator2直接设置原生`EditText`元素的文本。完全绕过输入法。适用于短信、标准应用。

**策略B — ADB Keyboard（后备）：**
切换到`com.android.adbkeyboard`，通过广播发送base64编码的文本。适用于微信自定义输入框、Flutter、WebView等非原生文本容器。

**为什么不用 `adb shell input text`？**
因为IME激活时，`input text`会导致字符乱序或崩溃。本工具集完全避免使用它。

附加功能：
- `clear()` — 清空输入框
- `delete(n)` — 发送N次退格键事件
- `select_all()` / `copy()` / `paste()` — 剪贴板操作（仅原生EditText）
- `get_text()` — 读取当前输入内容

### `app_scanner.py`

批量诊断工具。依次启动应用列表，统计原生EditText元素数量，运行OCR，报告哪些应用易于自动化、哪些需要视觉/ADB Keyboard策略。

---

## 配置

工具集从工作目录下的`config.json`读取设备设置。如果文件缺失，则回退到环境变量：

| 配置键 | 环境变量 | 默认值 |
|--------|----------|--------|
| `device_serial` | `ADB_DEVICE_SERIAL` | `adb devices`中的第一个设备 |
| `screen_width` | `SCREEN_WIDTH` | 1200 |
| `screen_height` | `SCREEN_HEIGHT` | 2670 |
| `screenshot_dir` | `SCREENSHOT_DIR` | `./screenshots` |
| `default_ime` | `DEFAULT_IME` | （无） |
| `adb_keyboard_ime` | `ADB_KEYBOARD_IME` | （无） |

---

## 项目结构

```
android-adb-automation-kit/
├── README.md
├── README.zh-CN.md
├── LICENSE
├── requirements.txt
├── config.example.json
├── tap_calibrator.py      # 校准与验证CLI
├── tap_controller.py      # 核心点击/滑动/滚动API
├── device_controller.py   # 系统控制API
├── vision_controller.py   # 基于CV的元素检测
├── ime_controller.py      # 文本输入抽象层
├── app_scanner.py         # 批量应用诊断
├── extended_verify.py     # 25点精度网格测试
└── examples/
    ├── wechat_search.py   # 示例：通过视觉+点击搜索联系人
    └── settings_flow.py   # 示例：导航系统设置
```

---

## 故障排查

### `adb devices` 显示 "unauthorized"

解锁手机并确认"允许USB调试？"对话框。勾选"始终允许使用这台计算机进行调试"。

### 点击位置不准确

开启开发者选项中的**指针位置**，运行 `tap_calibrator.py --grid`。如果红点与目标坐标有偏移，说明你的设备可能存在非标准坐标映射。部分带曲面屏或刘海屏的设备需要调整边距 —— 使用 `tap_safe()` 并自定义边距。

### OCR识别结果乱码

安装正确的Tesseract语言包：

```bash
# macOS
brew install tesseract-lang

# Ubuntu
sudo apt-get install tesseract-ocr-chi-sim tesseract-ocr-eng
```

然后在VisionController方法中设置 `lang="chi_sim+eng"`。

### ADB Keyboard未安装

下载并安装 [ADB Keyboard](https://github.com/senzhk/ADBKeyBoard)：

```bash
adb install adbkeyboard.apk
```

然后将其IME ID填入 `config.json` 的 `adb_keyboard_ime` 字段。

### uiautomator2连接失败

```bash
# 重新初始化设备上的uiautomator2
python -m uiautomator2 init
```

---

## 开源协议

MIT
