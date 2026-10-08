package com.stemit.app.separation

import android.content.Context
import android.net.Uri
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real on-device vocal separation engine.
 *
 * Pipeline:
 * MediaCodec decode -> 44.1 kHz stereo -> centered Hann STFT ->
 * BS PolarFormer ONNX -> complex mask -> iSTFT -> overlap averaging -> WAV.
 */
class PolarFormerEngine(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val N_FFT = 2_048
        private const val HOP = 512
        private const val WIN = 2_048
        private const val CHUNK = 882_000
        private const val OVERLAP = 2
        private const val FREQ = N_FFT / 2 + 1
        private const val FEATURES = FREQ * 2 * 2
        private const val MODEL_INPUT = "stft_features"
        private const val MODEL_OUTPUT = "mask"
    }

    private val modelManager = ModelManager(context)
    private val decoder = AudioDecoder(context)
    private val fft = RealFft(N_FFT)
    private val hann = FloatArray(WIN) { i ->
        (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * i / WIN)).toFloat()
    }
    private val ortEnv by lazy { OrtEnvironment.getEnvironment() }

    fun modelInstalled(): Boolean = modelManager.isInstalled()

    fun downloadModel(onProgress: (Float) -> Unit = {}): File =
        modelManager.ensureInstalled(onProgress)

    fun separate(
        uri: Uri,
        outputDirectory: File,
        listener: SeparationProgressListener = SeparationProgressListener { }
    ): SeparationResult {
        outputDirectory.mkdirs()
        listener.onProgress(SeparationProgress(0f, "Reading audio"))
        val audio = decoder.decode(uri)
        require(audio.sampleRate == SAMPLE_RATE) { "Audio must be 44.1 kHz after resampling" }

        listener.onProgress(SeparationProgress(0.02f, "Preparing separation engine"))
        val modelFile = modelManager.ensureInstalled { p ->
            listener.onProgress(SeparationProgress(0.02f + p * 0.18f, "Downloading separation model ${(p * 100).toInt()}%"))
        }

        val options = OrtSession.SessionOptions().apply {
            setInterOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
            setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors()))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        ortEnv.createSession(modelFile.absolutePath, options).use { session ->
            val total = audio.length
            val step = CHUNK / OVERLAP
            val resultL = FloatArray(total)
            val resultR = FloatArray(total)
            val count = FloatArray(total)
            var start = 0
            var chunkIndex = 0
            val chunkCount = max(1, ceil(total.toDouble() / step.toDouble()).toInt())

            while (start < total) {
                val end = min(start + CHUNK, total)
                val chunkL = FloatArray(CHUNK)
                val chunkR = FloatArray(CHUNK)
                audio.left.copyInto(chunkL, 0, start, end)
                audio.right.copyInto(chunkR, 0, start, end)

                val stems = inferChunk(session, chunkL, chunkR)
                val actual = end - start
                for (i in 0 until actual) {
                    resultL[start + i] += stems.first[i]
                    resultR[start + i] += stems.second[i]
                    count[start + i] += 1f
                }
                chunkIndex++
                listener.onProgress(
                    SeparationProgress(
                        0.20f + 0.72f * (chunkIndex.toFloat() / chunkCount.toFloat()),
                        "Separating track ${chunkIndex.coerceAtMost(chunkCount)} of $chunkCount"
                    )
                )
                start += step
            }

            val vocalsL = FloatArray(total)
            val vocalsR = FloatArray(total)
            for (i in 0 until total) {
                val c = if (count[i] == 0f) 1f else count[i]
                vocalsL[i] = (resultL[i] / c).coerceIn(-1f, 1f)
                vocalsR[i] = (resultR[i] / c).coerceIn(-1f, 1f)
            }

            val instrumentalL = FloatArray(total)
            val instrumentalR = FloatArray(total)
            for (i in 0 until total) {
                instrumentalL[i] = (audio.left[i] - vocalsL[i]).coerceIn(-1f, 1f)
                instrumentalR[i] = (audio.right[i] - vocalsR[i]).coerceIn(-1f, 1f)
            }

            listener.onProgress(SeparationProgress(0.94f, "Writing vocals"))
            val vocalsFile = File(outputDirectory, "vocals.wav")
            val instrumentalFile = File(outputDirectory, "instrumental.wav")
            WavWriter.writeStereo16(vocalsFile, vocalsL, vocalsR, SAMPLE_RATE)
            listener.onProgress(SeparationProgress(0.98f, "Writing instrumental"))
            WavWriter.writeStereo16(instrumentalFile, instrumentalL, instrumentalR, SAMPLE_RATE)
            listener.onProgress(SeparationProgress(1f, "Separation complete"))
            return SeparationResult(vocalsFile, instrumentalFile, total.toFloat() / SAMPLE_RATE)
        }
    }

    private fun inferChunk(session: OrtSession, left: FloatArray, right: FloatArray): Pair<FloatArray, FloatArray> {
        val paddedLength = CHUNK + N_FFT
        val frames = CHUNK / HOP + 1
        val stft = FloatArray(2 * FREQ * frames * 2)
        computeStft(left, 0, stft, 0, frames)
        computeStft(right, 1, stft, 0, frames)

        val features = FloatArray(frames * FEATURES)
        var dst = 0
        for (t in 0 until frames) {
            for (f in 0 until FREQ) {
                val baseL = ((0 * FREQ + f) * frames + t) * 2
                val baseR = ((1 * FREQ + f) * frames + t) * 2
                features[dst++] = stft[baseL]
                features[dst++] = stft[baseL + 1]
                features[dst++] = stft[baseR]
                features[dst++] = stft[baseR + 1]
            }
        }

        val input = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(features),
            longArrayOf(1, frames.toLong(), FEATURES.toLong())
        )
        input.use {
            session.run(mapOf(MODEL_INPUT to input)).use { result ->
                val output = result[0] as OnnxTensor
                output.use {
                    val buffer = it.floatBuffer
                    val mask = FloatArray(buffer.remaining())
                    buffer.get(mask)
                    return reconstruct(stft, mask, frames, paddedLength)
                }
            }
        }
    }

    private fun computeStft(
        source: FloatArray,
        channel: Int,
        out: FloatArray,
        unused: Int,
        frames: Int
    ) {
        val pad = N_FFT / 2
        val real = FloatArray(N_FFT)
        val imag = FloatArray(N_FFT)
        for (t in 0 until frames) {
            val frameStart = t * HOP - pad
            for (n in 0 until N_FFT) {
                val sourceIndex = reflectIndex(frameStart + n, source.size)
                real[n] = source[sourceIndex] * hann[n]
                imag[n] = 0f
            }
            fft.forward(real, imag)
            for (f in 0 until FREQ) {
                val idx = ((channel * FREQ + f) * frames + t) * 2
                out[idx] = real[f]
                out[idx + 1] = imag[f]
            }
        }
    }

    private fun reconstruct(stft: FloatArray, mask: FloatArray, frames: Int, paddedLength: Int): Pair<FloatArray, FloatArray> {
        val left = FloatArray(CHUNK)
        val right = FloatArray(CHUNK)
        val norm = FloatArray(CHUNK + N_FFT)
        val workR = FloatArray(N_FFT)
        val workI = FloatArray(N_FFT)
        val pad = N_FFT / 2

        fun reconstructChannel(channel: Int, target: FloatArray) {
            val acc = FloatArray(paddedLength)
            val windowNorm = FloatArray(paddedLength)
            for (t in 0 until frames) {
                java.util.Arrays.fill(workR, 0f)
                java.util.Arrays.fill(workI, 0f)
                for (f in 0 until FREQ) {
                    val stftIndex = ((channel * FREQ + f) * frames + t) * 2
                    val maskFlat = f * 2 + channel
                    val maskIndex = (((maskFlat * frames + t) * 2))
                    var mr = mask[maskIndex]
                    var mi = mask[maskIndex + 1]
                    if (f == 0) { mr = 0f; mi = 0f }
                    val ar = stft[stftIndex]
                    val ai = stft[stftIndex + 1]
                    val yr = ar * mr - ai * mi
                    val yi = ar * mi + ai * mr
                    workR[f] = yr
                    workI[f] = yi
                    if (f != 0 && f != N_FFT / 2) {
                        val mirror = N_FFT - f
                        workR[mirror] = yr
                        workI[mirror] = -yi
                    }
                }
                fft.inverse(workR, workI)
                val start = t * HOP
                for (n in 0 until N_FFT) {
                    val pos = start + n
                    if (pos < paddedLength) {
                        val w = hann[n]
                        acc[pos] += workR[n] * w
                        windowNorm[pos] += w * w
                    }
                }
            }
            for (i in 0 until CHUNK) {
                val p = i + pad
                target[i] = if (p < acc.size && windowNorm[p] > 1e-8f) {
                    acc[p] / windowNorm[p]
                } else 0f
            }
        }

        reconstructChannel(0, left)
        reconstructChannel(1, right)
        return left to right
    }

    private fun reflectIndex(index: Int, length: Int): Int {
        if (length <= 1) return 0
        var i = index
        while (i < 0 || i >= length) {
            i = if (i < 0) -i else 2 * length - 2 - i
        }
        return i
    }
}

object WavWriter {
    fun writeStereo16(file: File, left: FloatArray, right: FloatArray, sampleRate: Int) {
        require(left.size == right.size)
        val dataBytes = left.size * 4L
        BufferedOutputStream(FileOutputStream(file), 64 * 1024).use { out ->
            fun le16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
            fun le32(v: Long) {
                out.write((v and 0xff).toInt()); out.write(((v ushr 8) and 0xff).toInt())
                out.write(((v ushr 16) and 0xff).toInt()); out.write(((v ushr 24) and 0xff).toInt())
            }
            out.write("RIFF".toByteArray()); le32(36 + dataBytes); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(2); le32(sampleRate.toLong())
            le32((sampleRate * 4L)); le16(4); le16(16)
            out.write("data".toByteArray()); le32(dataBytes)
            for (i in left.indices) {
                val l = (left[i].coerceIn(-1f, 1f) * 32767f).toInt()
                val r = (right[i].coerceIn(-1f, 1f) * 32767f).toInt()
                le16(l); le16(r)
            }
        }
    }
}
