# Stemit — Vocal & Instrumental Separator

Stemit has one purpose: separate a selected song into two WAV files — vocals and instrumental.

## Focused app experience

- Choose one audio file using Android's system file picker.
- Separate it into vocals and instrumental stems.
- Play, save or share the resulting WAV files.
- Keep completed stems in a simple on-device Library.
- Change light/dark appearance and manage the downloaded model and temporary files.

There is no account, subscription screen, search tab, generic audio editor, converter, or unrelated studio feature in the focused app experience.

## Processing and memory

The audio engine decodes the selected track to a temporary PCM file instead of keeping the complete decoded song in memory. It runs inference on fixed-size overlapping windows and writes both WAV stems progressively. Working audio memory is therefore bounded by the inference window rather than growing with the full track duration.

The ONNX model is downloaded on first use (approximately 103 MB) and stored separately from the APK. The app uses one ONNX inference thread to reduce CPU and memory pressure on entry-level Android devices. Actual speed and memory use still depend on the device, Android version, model runtime and source audio. A successful build or emulator UI test does not prove reliable separation on every 2 GB phone; physical-device audio testing remains required.

## Privacy

Selected audio is processed locally. Stemit does not upload the selected song to a Stemit server. The model download and optional update check require internet access. Original audio is not overwritten.

## Build

The GitHub Actions workflow builds minified release APKs for ARM64 and ARMv7 devices:

```bash
gradle -p Stemit :app:assembleRelease
```

The Android emulator checks Home, Library and Settings navigation and runs a model-backed five-second audio separation test on a 2 GB emulator:

```bash
gradle -p Stemit :app:connectedDebugAndroidTest
```

## Model and third-party attribution

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Confirm the model's upstream licence before redistribution or commercial release.
