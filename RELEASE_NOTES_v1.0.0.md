# Stemit v1.0.0 — Release Notes Draft

> **Not approved for publication yet.** This is a release-note draft, not evidence that v1.0.0 has passed testing. Publish only after the exact release APK passes the end-to-end separation, offline, crash and device-compatibility checks in `RELEASE_AUDIT.md`.

Stemit is a privacy-focused Android audio separation app for musicians, editors, podcasters and independent creators.

## What's included in the inspected source

- Native Android application built with Kotlin.
- BS PolarFormer FP16 model running through ONNX Runtime Android.
- On-device vocal and instrumental separation pipeline.
- Audio selection through Android's system document picker.
- 44.1 kHz stereo processing.
- Vocal and instrumental output as 16-bit stereo WAV.
- First-run model download with local caching for subsequent offline inference.

## Privacy and offline operation

Audio inference is designed to run locally on the device. The first model download requires an internet connection. After the model has been cached successfully, inference is intended to work offline. The app also accesses the network when the user manually checks for updates.

## Installation

1. Download the APK attached to the published GitHub release.
2. Install it on Android 7.0 (API 24) or newer.
3. Open Stemit and choose an audio file.
4. On first use, wait for the model download.
5. Start separation and open the vocals or instrumental WAV output.

## Known limitations to disclose

- Performance and supported input codecs vary by Android device.
- The 103 MB neural model and inference tensors require substantial memory; 4 GB RAM or more is practical guidance, not a guarantee.
- Current output files are stored in the app cache; they may be removed by Android and should be copied elsewhere if they need to be kept.
- The first model download requires internet access.
- Long-file reliability and broad device compatibility must be validated before describing this version as stable.

## Feedback

Report problems at https://github.com/Augak/Stemit/issues.

Maintained by Kastine Augak — https://github.com/Augak
