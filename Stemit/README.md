# Stemit — Android audio studio

Stemit separates a selected audio track into vocals and instrumental stems using on-device ONNX inference.

## Current development build

- Version: 1.0.3 (version code 4)
- Minimum Android: 7.0 (API 24)
- Separation model: BS PolarFormer FP16, downloaded on first use (~103 MB)
- Audio processing: on-device after the model download
- Library: persistent private storage for separated stems and imported audio
- Studio: WAV trim and gain editing for 16-bit PCM mono/stereo files
- Sharing: Android Sharesheet with temporary read access to the selected output

## Storage and memory

The model is stored in the app's private files directory and is not bundled into the APK. The Library stores saved audio in app-private storage. Deleting the app removes these private files unless Android backs them up.

The inference chunk size has been reduced, but the engine still holds the decoded source and multiple full-track PCM arrays. This build does **not** guarantee reliable separation on every 2 GB RAM phone. Try shorter tracks and close other apps if memory is low. A true streaming decoder/inference/writer pipeline remains future work.

## Features and limitations

- Home: choose an audio file and separate vocals/instrumental.
- Library: import audio, play supported formats, share, open in Studio, or delete saved items.
- Studio: trim and change gain on 16-bit PCM WAV. It does not currently convert compressed formats or merge tracks.
- Settings: light/dark appearance, notification permission, model storage controls, temporary cache cleanup and update check.
- Home-screen widget: quick access to Home and Library.
- Help/About: in-app FAQ, developer contact and privacy/licence summary.
- Premium: purchase and trial enforcement are not active; no payment is collected by this build.
- Language: the current UI is English-only; localization is not yet complete.

## Build

The GitHub Actions workflow builds debug APKs. For a release build with Java 17:

```bash
gradle -p Stemit :app:assembleRelease
```

Release outputs include ABI-specific APKs and a universal APK.

## Model and third-party attribution

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and verify upstream terms before redistribution or commercial use.
