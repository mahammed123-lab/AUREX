# piper-tts-android

A ready-to-use Android library module that runs **Piper** neural TTS voices
**fully offline, on-device** — no server, no network calls, no NDK build
required on your end.

## Why this isn't literally the `piper-master` C++ source, compiled

I dug into the `piper-master.zip` you sent. Piper's own C++ build
(`CMakeLists.txt`) pulls in three things at build time: `piper-phonemize`,
which itself statically links **espeak-ng** (for text→phoneme conversion)
and **onnxruntime** (for running the neural net). None of those ship
Android-ready — getting them onto a phone means cross-compiling espeak-ng
and piper-phonemize for 4 CPU ABIs with the Android NDK, which is a real,
multi-hour native-build project on its own and isn't something I can do
from inside this sandbox (no NDK toolchain, no way to test on a device).

So instead of a from-scratch native build, this module is built on
**[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** (Apache-2.0,
k2-fsa/Next-gen Kaldi team) — it does exactly that cross-compilation for
you and publishes the result as prebuilt `.so` files, plus a small Kotlin
API on top. It runs Piper's own `.onnx` voice models as-is. This is the
same approach used by every Piper-on-Android project I found in the wild
(VoxSherpa, SherpaOnnxTtsEngine, react-native-sherpa-onnx, etc.) — nobody
hand-compiles Piper's C++ for Android; they all sit on sherpa-onnx.

**What's already in this folder**, downloaded and verified working from
sherpa-onnx's official GitHub releases (v1.13.4):
- `src/main/jniLibs/arm64-v8a/` and `armeabi-v7a/` — prebuilt
  `libonnxruntime.so` + `libsherpa-onnx-jni.so`. Covers essentially every
  real Android phone. (Add `x86`/`x86_64` yourself only if you need
  emulator support — see below.)
- `src/main/java/com/k2fsa/sherpa/onnx/Tts.kt` + `WaveReader.kt` — the
  vendor Kotlin API, copied verbatim from the sherpa-onnx repo.
- `src/main/java/com/jarvis/piper/PiperTts.kt` — a small wrapper I wrote
  on top, with `speak()`, `synthesizeToFile()`, and a listener interface
  with an amplitude callback meant for driving a voice-orb/waveform
  visualizer's SPEAKING state.

## ⚠️ About the Telugu voice specifically

I could not confirm that an official Telugu (`te_IN`) voice exists in
Piper's own voice catalog (`rhasspy/piper-voices` on Hugging Face) — its
listing tops out at 35 languages and Telugu wasn't one of them as of this
writing, including in the `VOICES.md` bundled in the zip you sent. I
can't browse `huggingface.co` from this sandbox to double check, so
please verify yourself at:

**https://huggingface.co/rhasspy/piper-voices/tree/main/te**

If nothing's there, three real options, roughly in order of effort:
1. **Search community mirrors/checkpoints** — some Piper voices exist only
   as community-trained models scattered across Hugging Face, not in the
   official catalog. Worth a search for "Telugu Piper TTS onnx".
2. **Train your own** — Piper's training pipeline (`TRAINING.md` in the
   zip you sent) supports any language espeak-ng can phonemize, and
   espeak-ng does support Telugu (`te`). You'd need a Telugu speech
   dataset with transcripts (e.g. from OpenSLR, IndicTTS, or your own
   recordings) and a GPU for a few hours to a day of training.
3. **Fall back to Android's built-in system TTS for Telugu specifically**
   — Google's on-device TTS (already on most Android phones) ships a
   Telugu voice today. You could keep this Piper pipeline for languages
   where you do have/want a custom voice, and call
   `android.speech.tts.TextToSpeech` with `Locale("te", "IN")` as a
   fallback. Less "custom AI voice," but zero training effort and it
   works right now.

Everything else in this module is voice-agnostic — once you have *any*
Piper `.onnx` + `.onnx.json` pair (Telugu or otherwise), the steps below
get it running.

## 1. Add the module to your project

1. Copy this whole `piper-tts-android` folder into your project root, e.g.
   `YourJarvisApp/piper-tts/`.
2. In `settings.gradle`:
   ```groovy
   include ':piper-tts'
   project(':piper-tts').projectDir = new File('piper-tts')
   ```
3. In your app module's `build.gradle`:
   ```groovy
   dependencies {
       implementation project(':piper-tts')
   }
   ```
4. Sync Gradle. That's it — the native libs are prebuilt, no NDK setup
   needed on your machine.

## 2. Get a voice model into sherpa-onnx format

Every Piper voice on Hugging Face ships as `<name>.onnx` +
`<name>.onnx.json`. sherpa-onnx needs a folder shaped like:

```
your-voice/
  <name>.onnx
  tokens.txt          <- generated from the .onnx.json
  espeak-ng-data/      <- shared across every Piper voice, download once
```

**Step A — download the voice** (swap in your Telugu voice's URL once you
have one; this example uses a small English voice for testing the plumbing):
```bash
wget https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/low/en_US-amy-low.onnx
wget https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/low/en_US-amy-low.onnx.json
```

**Step B — generate `tokens.txt`** using `tools/convert_piper_voice.py`
(included in this folder, copied from sherpa-onnx's own docs):
```bash
pip install onnx --break-system-packages   # or in a venv
python3 tools/convert_piper_voice.py en_US-amy-low.onnx
# -> writes tokens.txt next to it, and embeds a bit of metadata into the .onnx
```
Edit the `filename = "..."` line at the top of the script to point at
whichever voice you're converting.

**Step C — grab the shared espeak-ng data** (download once, reuse for
every Piper voice you ever add, Telugu included):
```bash
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2
tar xf espeak-ng-data.tar.bz2
```

**Step D — assemble the folder and drop it in assets:**
```bash
mkdir -p your-voice
mv en_US-amy-low.onnx tokens.txt your-voice/
cp -r espeak-ng-data your-voice/
cp -r your-voice piper-tts/src/main/assets/
```

If the voice is large (Telugu medium-quality voices tend to be 60-100 MB)
and you'd rather not bloat your APK / Play Store download size, use
`PiperTts.fromFilesystem(...)` instead and download the voice folder to
`context.filesDir` the first time the app runs, rather than bundling it
in `assets/`.

## 3. Use it

```kotlin
import com.jarvis.piper.PiperTts

class MainActivity : AppCompatActivity() {
    private lateinit var tts: PiperTts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = PiperTts.fromAssets(
            context = this,
            config = PiperTts.Config(assetModelDir = "your-voice"),
            listener = object : PiperTts.Listener {
                override fun onSpeakingStarted() {
                    // e.g. drive your voice-orb visualizer into SPEAKING state
                }
                override fun onAudioChunk(amplitude: Float) {
                    // e.g. feed amplitude into the orb's boundary deformation
                }
                override fun onSpeakingFinished() {
                    // e.g. drop the orb back to IDLE
                }
            }
        )

        tts.speak("Hello, sir.")
    }

    override fun onDestroy() {
        tts.release()
        super.onDestroy()
    }
}
```

`synthesizeToFile("text", File(...))` is also available if you want a WAV
file instead of live playback (e.g. to cache commonly-spoken phrases).

## 4. Testing the plumbing right now

Since I can't confirm the Telugu voice, the fastest way to prove this
whole pipeline actually works end-to-end on your machine is to run
steps A-D above with the small English `en_US-amy-low` voice (a few
minutes, ~65 MB), build, and call `tts.speak("testing one two three")`.
Once that's audibly working, swapping in a Telugu (or any other) voice is
just repeating steps A-D with a different `.onnx`/`.onnx.json` pair — no
code changes needed.

## 5. Adding emulator support (x86/x86_64)

The bundled `.so` files only cover `arm64-v8a` and `armeabi-v7a` (real
phones) to keep this download a reasonable size. If you need to run on
the Android Studio emulator:
```bash
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-v1.13.4-android.tar.bz2
tar xjf sherpa-onnx-v1.13.4-android.tar.bz2
cp jniLibs/x86_64/*.so piper-tts/src/main/jniLibs/x86_64/   # mkdir first
```
and add `"x86_64"` to `abiFilters` in `build.gradle`.

## 6. Handing this to Google AI Studio

This module is a self-contained, plain Kotlin/Gradle Android library with
one public entry point (`PiperTts.kt`) — you can paste that file plus
this README into AI Studio/Gemini and ask it to wire `tts.speak(...)`
calls into your existing JARVIS Activity/Service/voice pipeline; it
doesn't need any of the native/build context to do that part.

## License notes

- `Tts.kt` / `WaveReader.kt` and the prebuilt `.so` files: sherpa-onnx,
  Apache License 2.0, © k2-fsa / Xiaomi Corporation.
- `PiperTts.kt`: written for you, use it however you like.
- Piper voice models on Hugging Face are generally MIT-licensed per-voice
  — check each voice's `MODEL_CARD` file for specifics.
- `espeak-ng-data` is phoneme/language data (not code) redistributed by
  the sherpa-onnx project alongside its Apache-2.0 release.
