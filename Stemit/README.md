# Stemit — Android audio studio

Stemit is an Android audio application with on-device vocal/instrumental separation.

## Current build

- Version: 1.0.2 (version code 3)
- Minimum Android: Android 7.0 (API 24)
- Separation runtime: ONNX Runtime for Android
- Model: BS PolarFormer FP16 (~103 MB), downloaded on first use
- Processing: local on-device inference after model download

## Storage and device requirements

The model is not bundled in the APK. It is downloaded into the app's private files directory when the user first starts separation. The downloader now checks available storage before starting, keeps the download temporary until validation succeeds, flushes the completed file, and removes incomplete temporary downloads after failures.

The release build enables R8 code shrinking and resource shrinking. ABI splits are configured for arm64-v8a, armeabi-v7a and x86_64, with a universal APK retained for compatibility/testing.

The current separation engine still holds decoded audio and multiple working arrays in memory. This release adds safer model-download storage handling and APK size optimisation; it does **not** yet claim streaming inference or guarantee operation on every 2 GB RAM device. Shorter source clips and closing other apps can still be necessary. Streaming/chunk-output processing is a separate engineering task that requires dedicated audio regression tests.

## Build

The repository's GitHub Actions workflow builds the debug APK. For a release build, use an Android Gradle environment with Java 17 and run:

```bash
gradle -p Stemit :app:assembleRelease
```

Release outputs include ABI-specific APKs and a universal APK.

## Model and attribution

- BS PolarFormer model by bgkb, based on Music-Source-Separation-Training.
- ONNX Runtime Android by Microsoft.

The upstream model repository currently identifies the model as MIT licensed. Verify upstream model and dependency license terms before redistribution or commercial use.
