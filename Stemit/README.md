# Stemit — Android vocal separation

This build contains a real on-device vocal-separation engine rather than a demo/mock result.

## Engine

- **Model:** BS PolarFormer ONNX, FP16 weights (~103 MB)
- **Runtime:** ONNX Runtime for Android 1.30.0
- **Sample rate:** 44.1 kHz stereo
- **DSP:** centered Hann STFT (2048 FFT / 512 hop), complex mask multiplication, inverse STFT and overlap averaging
- **Outputs:** `vocals.wav` and `instrumental.wav`
- **Network:** only needed the first time to download the model; once cached, separation is local/offline

The model is not bundled into this ZIP because it is about 103 MB. Stemit downloads it into the app's private model directory on the first separation. The model repository states an MIT license and documents the model as a 44.1 kHz stereo vocal/instrumental separator.

## First run

1. Open the project in Android Studio/another Android Gradle environment.
2. Build and install the app.
3. Pick an audio file.
4. Tap **Start separation**.
5. Wait for the one-time model download.
6. The engine decodes the track, resamples it to 44.1 kHz, runs ONNX inference in overlapping chunks, reconstructs the vocals and creates the instrumental as `original - vocals`.
7. Future separations use the cached model and do not need network access.

## Important device note

This is a substantial neural audio model. Low-memory or older phones may take several minutes per track and may need more storage/RAM. The app processes the track in 20-second overlapping chunks to limit peak memory use.

## Model URL

`https://huggingface.co/bgkb/bs_polarformer/resolve/main/bs_polarformer_fp16.onnx`

## Sources / attribution

- BS PolarFormer model by `bgkb`, based on the Music-Source-Separation-Training project.
- ONNX Runtime Android by Microsoft.

Before commercial distribution, keep the model attribution/license notices with the release and verify the current upstream license terms.
