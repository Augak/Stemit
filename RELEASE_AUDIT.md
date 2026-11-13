# Stemit release audit

Audit date: 2026-11-13  
Reviewed branch: `main`  
Repository layout: Android Gradle project is nested under `Stemit/`.

## Release decision

**Do not publish a stable v1.0.0 APK from the currently inspected source until the inference and memory issues below are resolved and tested.**

The source currently configures `versionCode = 2` and `versionName = "1.0.1"` in `Stemit/app/build.gradle.kts`. Do not reset this to 1.0.0 merely to match an earlier release plan. Confirm whether a v1.0.0 tag/release already exists before choosing the next version.

## Verified project facts

- Android application ID: `com.stemit.app`; debug suffix: `.debug`.
- Minimum SDK 24; compile and target SDK 34.
- Android Gradle Plugin 8.2.2; Kotlin plugin 1.9.22; Java 17.
- ONNX Runtime Android 1.30.0.
- Model: `bgkb/bs_polarformer` FP16 ONNX, approximately 103 MB.
- First use downloads the model from Hugging Face. Therefore the installed app is **not offline from a clean install**; after a successful model download, inference is intended to run locally.
- The app uses Android's `MediaExtractor` / `MediaCodec` decoder and a system document picker.
- Output files are currently written to the app cache directory as `vocals.wav` and `instrumental.wav`, not directly to a user-selected permanent folder.
- The repository has no root README or root .gitignore in the inspected tree. The app project is under `Stemit/`.

## Critical inference issue

The current Kotlin engine uses `CHUNK = 882_000` samples (20 seconds at 44.1 kHz) and centered STFT framing, producing about 1,723 time frames per ONNX invocation.

The upstream BS PolarFormer reference implementation uses a 131,072-sample chunk and non-centered STFT framing, producing 253 frames. Its documented model input is `(batch, time_frames, 4100)` and its mask output is `(batch, 1, 2050, time_frames, 2)`.

This difference is significant. The model contains time-transformer attention, so increasing the time dimension from 253 to roughly 1,723 can dramatically increase intermediate memory and compute. This is a credible explanation for the reported app termination on phones, but must be confirmed by profiling and end-to-end tests.

**Required fix:** align the Kotlin STFT chunking and framing with the upstream reference (131,072 samples, 253 frames, no centered padding), then verify output shape, mask indexing, overlap-add reconstruction and audio quality against reference samples. Do not treat a successful session creation as proof of correct inference.

Reference: https://huggingface.co/bgkb/bs_polarformer

## High-priority decoder and model integrity issues

1. `AudioDecoder.decode()` opens a file descriptor and calls `extractor.setDataSource()` before entering its `try/finally`. If setup fails, the descriptor/extractor can leak. Move setup under structured cleanup.
2. PCM output handling supports float PCM and otherwise assumes 16-bit PCM. Android decoders can expose other PCM encodings; explicitly handle or reject unsupported encodings rather than silently reading them as 16-bit.
3. Multi-channel input is reduced to the first two channels. Document or implement a defined downmix policy for files with more than two channels.
4. The model download checks only a minimum file size. Pin an immutable model revision and verify a published SHA-256 digest before promoting the temporary file to the installed model.
5. The model URL currently uses the mutable `main` branch. A model update could silently change the model used by a released app.
6. Close `OrtSession.SessionOptions` after session creation and profile actual peak heap/native memory on supported devices.
7. Add cancellation/interruption checks between chunks and ensure temporary partial outputs are cleaned up on failure.

## Product and UX issues

- Results are placed in app cache and can be removed by Android. Add a Storage Access Framework save/export action before promising durable exports.
- The picker accepts `audio/*`, but decoder compatibility is device-dependent. Publish a tested compatibility matrix rather than an unconditional format list.
- Notification permission is requested even though the inspected app does not currently post processing notifications. Either implement a meaningful notification workflow or remove the permission request.
- The current processing workflow is foreground-only and advises users to keep the app open. Consider a foreground service / WorkManager design before claiming reliable long-running background processing.
- The update checker opens the GitHub release page; it does not silently install APK updates. Document this accurately.
- The app's About screen and release metadata should show the same version string as Gradle.

## Build and release checklist

- [ ] Add or generate and commit the Gradle wrapper (Gradle 8.2.1) so builds are reproducible.
- [ ] Build with JDK 17 and Android SDK 34.
- [ ] Run `:app:testDebugUnitTest` and `:app:lintDebug`.
- [ ] Add automated tests for STFT frame count, FFT/iFFT round-trip, mask layout, overlap reconstruction and WAV header correctness.
- [ ] Test real files: short and long MP3, PCM WAV, M4A/AAC and FLAC on multiple Android devices.
- [ ] Test model download interruption, retry, insufficient storage and offline-after-download operation.
- [ ] Profile inference on a low-memory phone and a 4 GB+ phone; record Android version, device, track duration, peak memory and processing time.
- [ ] Verify model SHA-256 and include upstream model attribution/license.
- [ ] Add the application LICENSE file and verify third-party license obligations.
- [ ] Configure production signing securely; never commit signing keys or passwords.
- [ ] Install and test the signed release APK, not only the debug APK.
- [ ] Publish the release notes and APK only after all critical checks pass.

## Documentation scope

The repository-level README added on the release-preparation branch documents the inspected stack, actual project path, build commands, first-run model download, local inference, temporary output location and device-dependent decoder support. It intentionally does not promise universal format support or fully offline operation from a fresh install.
