# Stemit

**Stemit** is a focused Android app for separating one audio track into two WAV files: **vocals** and **instrumental**. It is designed as a simple, on-device workflow for Android phones, including entry-level devices.

> **Release status:** v1.1.1 is a preview release. Automated builds and emulator tests are not proof that separation will succeed on every physical phone, especially every 2 GB RAM device.

## What the app does

1. Select an audio file with Android's system file picker.
2. Download the separation model on first use (approximately 103 MB).
3. Separate the track into vocals and instrumental audio on the device.
4. Preview either stem, then save or share the WAV files.
5. Find completed stems in the on-device Library.

The app keeps the original source file unchanged. There is no account, subscription screen, search tab, generic audio editor, converter, or unrelated studio feature.

## Download

Open [GitHub Releases](https://github.com/Augak/Stemit/releases) and choose the latest release.

- **app-debug.apk** is signed by the GitHub Actions debug key and intended for direct installation and testing. Android may ask you to allow installation from the browser or file manager.
- ***-release-unsigned.apk** files are optimized release builds for the listed CPU architectures. They are unsigned and are not intended for direct installation until signed with the publisher's release key.
- The Actions → Build Stemit Android APK → Artifacts section also retains the APK bundle for 30 days.

For production distribution, protect a publisher signing key, sign the release APKs or Android App Bundle, and verify the model's redistribution licence before publishing.

## Device and compatibility notes

- Android minimum version: Android 7.0 (API 24).
- CPU builds: ARM64, ARMv7 and x86_64/universal variants as configured by Gradle.
- Audio decoding uses Android media codecs and streams decoded PCM to temporary storage.
- Separation runs inference over fixed-size overlapping audio windows. The complete decoded song is not retained as one large in-memory PCM array.
- ONNX Runtime and the separation model still require substantial memory and compute. Chunking bounds audio-buffer growth, but it does **not** guarantee that every 2 GB device can load the model or process every file.
- Processing time depends on the phone, available memory, source format, track duration and thermal state. Keep sufficient free storage for temporary PCM and exported WAV stems.

## Build from source

Requirements: JDK 17, Android SDK, and Gradle 8.2.1.

    gradle -p Stemit :app:assembleDebug
    gradle -p Stemit :app:assembleRelease

Install the debug build with Android Debug Bridge:

    adb install -r Stemit/app/build/outputs/apk/debug/app-debug.apk

## Tests

Run the Android instrumentation tests on a running emulator or connected device:

    gradle -p Stemit :app:connectedDebugAndroidTest

The instrumentation suite covers:
- Welcome, Home, Library and Settings navigation.
- The file-based separation pipeline using a generated five-second WAV and the configured ONNX model.
- Output-file existence, WAV headers, output lengths and preserved duration.

GitHub Actions runs emulator checks with a 2 GB emulator configuration. This is a useful regression check, not a substitute for testing on the actual target phone.

## Privacy

- Audio is selected by the user through Android's system document picker.
- Selected audio is decoded and processed locally; Stemit's separation code does not upload it to a Stemit server.
- The model is downloaded from its configured upstream host on first use.
- Saved stems stay in app-private storage until the user saves, shares or deletes them.
- The optional update check contacts the public GitHub Releases API.
- Notification permission is optional and requested only when the user enables completion alerts.

See [PRIVACY.md](PRIVACY.md) for the privacy summary.

## Project layout

    Stemit/
      app/
        src/main/java/com/stemit/app/
          DashboardActivity.kt
          separation/
            AudioDecoder.kt
            ModelManager.kt
            PolarFormerEngine.kt
            RealFft.kt
        src/androidTest/java/com/stemit/app/
          StemitNavigationSmokeTest.kt
          SeparationEngineIntegrationTest.kt
      PRIVACY.md
      THIRD_PARTY_NOTICES.md

## Model and third-party notices

Stemit uses ONNX Runtime for Android and the BS PolarFormer model. Read [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and independently verify current upstream licences and model redistribution terms before distributing model weights or making a commercial release.

## Support

For support or bug reports, email **kastineaugak@gmail.com**. Include your phone model, Android version, audio format and approximate track length. Do not send private audio unless you intend to share it.
