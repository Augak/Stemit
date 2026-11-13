# Stemit v1.0.0 — First Stable Release

> **Release draft.** Publish only after the release APK passes the end-to-end separation, offline, crash and device-compatibility checks in the repository's release checklist.

Stemit is a privacy-focused Android audio separation app for musicians, editors, podcasters and independent creators.

## What's included

- Native Android application built with Kotlin.
- BS PolarFormer FP16 model running through ONNX Runtime Android.
- On-device vocal and instrumental separation.
- Audio selection through Android's system document picker.
- 44.1 kHz stereo processing.
- Vocal and instrumental output as 16-bit stereo WAV.
- First-run model download with local caching for subsequent offline inference.
- In-app FAQ, About, Settings, developer contact and GitHub release-check links.

## Privacy and offline operation

Audio inference runs locally on the device. The first model download requires an internet connection. Once the model is cached, separation can run offline. The app also accesses the network when the user manually checks for updates.

## Install

1. Download the APK from the Assets section.
2. Install it on Android 7.0 (API 24) or newer.
3. Open Stemit and choose an audio file.
4. On first use, wait for the model download.
5. Start separation and open the vocals or instrumental WAV output.

## Known limitations

- Performance and supported input codecs vary by Android device.
- The 103 MB neural model and inference tensors require substantial memory; 4 GB RAM or more is recommended as practical guidance, not a guarantee.
- Current output files are stored in the app cache; save or copy them elsewhere if you need to keep them.
- The first model download requires internet access.

## Feedback

Report problems at https://github.com/Augak/Stemit/issues.

Maintained by Kastine Augak — https://github.com/Augak
