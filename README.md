# Stemit

**Private, on-device vocal and instrumental separation for Android creators.**

Stemit is a native Android application for music creators, editors and podcasters. It uses the BS PolarFormer ONNX model to separate a stereo track into two WAV files: **vocals** and **instrumental**.

> **Offline clarification:** the first use needs an internet connection to download the approximately 103 MB model. After the model has been downloaded successfully and cached on the device, audio inference is performed locally. Stemit does not upload selected audio for separation.

## Project status

- Android application source: `Stemit/`
- Current source version: **1.0.1** (as configured in Gradle)
- Application ID: `com.stemit.app`
- Minimum Android version: **Android 7.0 (API 24)**
- Target / compile SDK: **34**
- Release readiness: testing and model-pipeline verification are still required before calling this a stable release.

## What it does

- Select an audio file through Android's system document picker.
- Download and cache the BS PolarFormer FP16 ONNX model on first use.
- Decode audio using Android `MediaExtractor` and `MediaCodec`.
- Resample audio to 44.1 kHz stereo.
- Run ONNX inference on the device.
- Write `vocals.wav` and `instrumental.wav` into the app's temporary output directory.
- Open the resulting WAV files in an installed audio player.

## Technology

| Component | Actual project configuration |
| --- | --- |
| Platform / language | Native Android / Kotlin |
| Android Gradle Plugin | 8.2.2 |
| Kotlin Gradle plugin | 1.9.22 |
| Gradle compatibility | 8.2.1 or newer compatible Gradle 8.x |
| Java toolchain | JDK 17 |
| Compile / target SDK | 34 / 34 |
| Minimum SDK | 24 (Android 7.0) |
| Inference runtime | ONNX Runtime Android 1.30.0 |
| Model | BS PolarFormer FP16 ONNX |
| Processing sample rate | 44,100 Hz, stereo |
| Export | 16-bit PCM WAV, stereo |

## System requirements

### Minimum supported Android version

- Android 7.0 (API 24) or newer.
- An ARM or x86 Android device with a compatible Android media decoder and ONNX Runtime native library.
- Internet access for the first model download.
- At least 150 MB of free app-private storage for the model; additional space is needed for temporary audio and output files.

### Practical device guidance

The model is approximately 103 MB and the inference pipeline allocates sizeable audio and tensor buffers. A phone with **4 GB RAM or more is recommended**, but this is guidance, not a tested guarantee. Devices with 2 GB RAM may fail on longer tracks or may be unable to run inference reliably. Try a short audio clip, close other apps and keep the device cool.

Processing time depends on the device, audio duration and available memory. Stemit does not currently promise real-time processing.

## Supported audio formats

Stemit uses Android's system media stack rather than bundling a universal decoder. The picker accepts `audio/*`; actual decoding depends on the codecs provided by the Android device.

Common formats to test include:

- MP3
- WAV / PCM
- M4A / AAC
- FLAC
- Ogg Vorbis
- Opus

**Compatibility note:** this is not a guarantee that every device decodes every listed format. Codec support varies by Android version and device manufacturer. If a file fails, try a standard 44.1 kHz stereo PCM WAV or MP3 file.

## Install the app

When an official APK is published:

1. Open [Stemit Releases](https://github.com/Augak/Stemit/releases).
2. Download the APK attached to the release.
3. Install it on Android. If prompted, allow installation from the browser or file manager you used.
4. Open Stemit and select an audio file.
5. On first use, allow the model download to finish.
6. Start separation and wait for the two WAV outputs.

Only install APKs published by the official `Augak/Stemit` repository.

## Build from source

### Requirements

- Android Studio with Android SDK Platform 34 and Build Tools 34.0.0.
- JDK 17.
- Gradle 8.2.1 (the project currently does not include a Gradle wrapper).
- Git.
- An Android device or emulator running Android 7.0 or newer.

### 1. Clone the repository

```bash
git clone https://github.com/Augak/Stemit.git
cd Stemit/Stemit
```

The Android Gradle project is in the nested `Stemit/` directory.

### 2. Verify Java and Gradle

```bash
java -version
gradle --version
```

Use JDK 17 and Gradle 8.2.1 or a compatible Gradle 8.x release supported by Android Gradle Plugin 8.2.2.

### 3. Build the debug APK

Linux / macOS:

```bash
gradle :app:assembleDebug
```

Windows PowerShell:

```powershell
gradle.bat :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The debug application ID has the `.debug` suffix: `com.stemit.app.debug`.

### 4. Install on a connected Android device

Enable USB debugging, connect the phone and confirm that ADB can see it:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 5. Run checks

```bash
gradle :app:testDebugUnitTest
gradle :app:lintDebug
```

These commands are the project's available Gradle test and lint tasks. A successful build alone does not prove that model inference works; perform the end-to-end audio test described below.

## How to use Stemit

1. Open Stemit.
2. Select **Choose audio file** and pick an audio track.
3. Tap **Separate audio**.
4. On first use, wait for the model download to complete.
5. Keep the app open while inference runs.
6. When processing finishes, choose **Open vocals** or **Open instrumental**.

The current implementation writes the output files into the app's cache directory. They are temporary app files, not yet a permanent user-selected export location. Do not treat them as long-term storage.

## Privacy

- Selected audio is decoded and processed on the Android device.
- The app uses internet access to download the model on first use and to check GitHub for releases when the user requests an update check.
- Audio files are not intentionally uploaded to a processing server.
- The current app uses app-private cache storage for separation outputs.

## Model attribution

Stemit uses the **BS PolarFormer FP16 ONNX** model published by [bgkb on Hugging Face](https://huggingface.co/bgkb/bs_polarformer), based on the Music-Source-Separation-Training project. The model card identifies the model repository license as MIT. Review the upstream model card and include the required attribution and license notice with redistributed model files.

Model download URL configured by the app:

```text
https://huggingface.co/bgkb/bs_polarformer/resolve/main/bs_polarformer_fp16.onnx
```

## Troubleshooting

**The first separation takes a long time**
: The app downloads an approximately 103 MB model. Use a stable connection and keep Stemit open.

**Separation stops or the app closes**
: The model is memory-intensive. Try a shorter file, close other apps and retry. Please report the Android version, device model, audio duration and relevant logcat output in a GitHub issue.

**An audio file cannot be opened**
: The supported codecs depend on Android's system media decoder. Try a standard PCM WAV or MP3 file.

**The output does not appear in Downloads**
: The current implementation writes results to the app's cache directory and opens them through Android's file provider. A persistent export-to-folder workflow is a future improvement.

## Contributing and support

Bug reports and feature requests are welcome:

- [Open an issue](https://github.com/Augak/Stemit/issues)
- [Browse the source](https://github.com/Augak/Stemit)
- [More from the developer](https://github.com/Augak)
- Email: [kastineaugak@gmail.com](mailto:kastineaugak@gmail.com)

When reporting a bug, include your device model, Android version, audio format and duration, whether the model had already been downloaded, and a sanitized crash log.

## License

The repository's application license must be stated in a root-level `LICENSE` file before public distribution. The upstream model has its own separate license and attribution requirements; do not assume the model license automatically licenses Stemit's application code.

---

**Stemit — Your sound. Your stems.**
