package com.stemit.app.separation

import android.content.Context
import android.net.Uri
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min

/**
 * Memory-bounded vocal separation.
 * Decoded source and WAV stems stream through disk; inference uses fixed-size overlapping windows.
 */
class PolarFormerEngine(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val N_FFT = 2_048
        private const val HOP = 512
        private const val WIN = 2_048
        private const val CHUNK = 131_072
        private const val HALF_CHUNK = CHUNK / 2
        private const val FREQ = N_FFT / 2 + 1
        private const val FEATURES = FREQ * 4
        private const val MODEL_INPUT = "stft_features"
    }

    private val modelManager = ModelManager(context)
    private val decoder = AudioDecoder(context)
    private val fft = RealFft(N_FFT)
    private val hann = FloatArray(WIN) { i ->
        (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * i / WIN)).toFloat()
    }
    private val ortEnv by lazy { OrtEnvironment.getEnvironment() }

    fun modelInstalled(): Boolean = modelManager.isInstalled()
    fun removeModel(): Boolean = modelManager.deleteInstalled()
    fun downloadModel(onProgress: (Float) -> Unit = {}): File = modelManager.ensureInstalled(onProgress)

    fun separate(
        uri: Uri,
        outputDirectory: File,
        listener: SeparationProgressListener = SeparationProgressListener { }
    ): SeparationResult {
        outputDirectory.mkdirs()
        val rawFile = File(outputDirectory, "source-44100.pcm")
        val vocalsFile = File(outputDirectory, "vocals.wav")
        val instrumentalFile = File(outputDirectory, "instrumental.wav")
        vocalsFile.delete()
        instrumentalFile.delete()

        try {
            listener.onProgress(SeparationProgress(0.01f, "Preparing separation model"))
            val modelFile = modelManager.ensureInstalled { p ->
                listener.onProgress(SeparationProgress(0.02f + p * 0.16f, "Downloading model " + (p * 100).toInt() + "%"))
            }

            listener.onProgress(SeparationProgress(0.19f, "Decoding audio to temporary storage"))
            val totalSamples = decoder.decodeToPcm16(uri, rawFile, SAMPLE_RATE)
            require(totalSamples > 0L) { "No audio samples could be decoded from this file." }

            val options = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(1)
                setIntraOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            ortEnv.createSession(modelFile.absolutePath, options).use { session ->
                RandomAccessFile(rawFile, "r").use { source ->
                    WavStreamWriter(vocalsFile, totalSamples, SAMPLE_RATE).use { vocalOut ->
                        WavStreamWriter(instrumentalFile, totalSamples, SAMPLE_RATE).use { instrumentalOut ->
                            val chunkCount = if (totalSamples <= CHUNK) 1
                                else ceil((totalSamples - CHUNK).toDouble() / HALF_CHUNK.toDouble()).toInt() + 1

                            var heldVocalsL: FloatArray? = null
                            var heldVocalsR: FloatArray? = null
                            var heldOriginalL: FloatArray? = null
                            var heldOriginalR: FloatArray? = null
                            var lastStart = 0L
                            val inputL = FloatArray(CHUNK)
                            val inputR = FloatArray(CHUNK)

                            for (chunkIndex in 0 until chunkCount) {
                                val start = chunkIndex.toLong() * HALF_CHUNK.toLong()
                                lastStart = start
                                readPcmChunk(source, start, totalSamples, inputL, inputR)
                                val stems = inferChunk(session, inputL, inputR)

                                if (chunkIndex == 0 && totalSamples <= CHUNK) {
                                    writeStemPair(vocalOut, instrumentalOut, stems.first, stems.second, inputL, inputR, totalSamples.toInt())
                                } else if (chunkIndex == 0) {
                                    writeStemPair(vocalOut, instrumentalOut, stems.first, stems.second, inputL, inputR, HALF_CHUNK)
                                    heldVocalsL = stems.first.copyOfRange(HALF_CHUNK, CHUNK)
                                    heldVocalsR = stems.second.copyOfRange(HALF_CHUNK, CHUNK)
                                    heldOriginalL = inputL.copyOfRange(HALF_CHUNK, CHUNK)
                                    heldOriginalR = inputR.copyOfRange(HALF_CHUNK, CHUNK)
                                } else {
                                    val oldL = heldVocalsL ?: error("Missing overlap audio.")
                                    val oldR = heldVocalsR ?: error("Missing overlap audio.")
                                    val oldOriginalL = heldOriginalL ?: error("Missing source overlap.")
                                    val oldOriginalR = heldOriginalR ?: error("Missing source overlap.")
                                    val blendL = FloatArray(HALF_CHUNK)
                                    val blendR = FloatArray(HALF_CHUNK)
                                    for (i in 0 until HALF_CHUNK) {
                                        val amount = (i + 1).toFloat() / (HALF_CHUNK + 1).toFloat()
                                        blendL[i] = oldL[i] * (1f - amount) + stems.first[i] * amount
                                        blendR[i] = oldR[i] * (1f - amount) + stems.second[i] * amount
                                    }
                                    writeStemPair(vocalOut, instrumentalOut, blendL, blendR, oldOriginalL, oldOriginalR, HALF_CHUNK)
                                    heldVocalsL = stems.first.copyOfRange(HALF_CHUNK, CHUNK)
                                    heldVocalsR = stems.second.copyOfRange(HALF_CHUNK, CHUNK)
                                    heldOriginalL = inputL.copyOfRange(HALF_CHUNK, CHUNK)
                                    heldOriginalR = inputR.copyOfRange(HALF_CHUNK, CHUNK)
                                }

                                listener.onProgress(
                                    SeparationProgress(
                                        0.20f + 0.76f * ((chunkIndex + 1).toFloat() / chunkCount.toFloat()),
                                        "Separating audio " + (chunkIndex + 1) + " of " + chunkCount
                                    )
                                )
                            }

                            if (totalSamples > CHUNK) {
                                val tailCount = (totalSamples - (lastStart + HALF_CHUNK)).coerceIn(0L, HALF_CHUNK.toLong()).toInt()
                                if (tailCount > 0) {
                                    writeStemPair(
                                        vocalOut,
                                        instrumentalOut,
                                        heldVocalsL ?: error("Missing final vocal segment."),
                                        heldVocalsR ?: error("Missing final vocal segment."),
                                        heldOriginalL ?: error("Missing final source segment."),
                                        heldOriginalR ?: error("Missing final source segment."),
                                        tailCount
                                    )
                                }
                            }
                        }
                    }
                }
            }

            listener.onProgress(SeparationProgress(1f, "Separation complete"))
            return SeparationResult(vocalsFile, instrumentalFile, totalSamples.toFloat() / SAMPLE_RATE.toFloat())
        } catch (e: Exception) {
            vocalsFile.delete()
            instrumentalFile.delete()
            throw e
        } finally {
            rawFile.delete()
        }
    }

    private fun readPcmChunk(
        file: RandomAccessFile,
        start: Long,
        total: Long,
        left: FloatArray,
        right: FloatArray
    ) {
        java.util.Arrays.fill(left, 0f)
        java.util.Arrays.fill(right, 0f)
        val count = min(CHUNK.toLong(), total - start).toInt()
        if (count <= 0) return
        file.seek(start * 4L)
        val bytes = ByteArray(count * 4)
        file.readFully(bytes)
        var offset = 0
        for (i in 0 until count) {
            val l = ((bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)).toShort()
            val r = ((bytes[offset + 2].toInt() and 0xff) or ((bytes[offset + 3].toInt() and 0xff) shl 8)).toShort()
            left[i] = l.toFloat() / 32768f
            right[i] = r.toFloat() / 32768f
            offset += 4
        }
    }

    private fun writeStemPair(
        vocals: WavStreamWriter,
        instrumental: WavStreamWriter,
        vocalL: FloatArray,
        vocalR: FloatArray,
        originalL: FloatArray,
        originalR: FloatArray,
        count: Int
    ) {
        val instrumentalL = FloatArray(count)
        val instrumentalR = FloatArray(count)
        for (i in 0 until count) {
            instrumentalL[i] = (originalL[i] - vocalL[i]).coerceIn(-1f, 1f)
            instrumentalR[i] = (originalR[i] - vocalR[i]).coerceIn(-1f, 1f)
        }
        vocals.write(vocalL, vocalR, count)
        instrumental.write(instrumentalL, instrumentalR, count)
    }

    private fun inferChunk(session: OrtSession, left: FloatArray, right: FloatArray): Pair<FloatArray, FloatArray> {
        val frames = CHUNK / HOP + 1
        val stft = FloatArray(2 * FREQ * frames * 2)
        computeStft(left, 0, stft, frames)
        computeStft(right, 1, stft, frames)

        val features = FloatArray(frames * FEATURES)
        var dst = 0
        for (t in 0 until frames) {
            for (f in 0 until FREQ) {
                val baseL = (f * frames + t) * 2
                val baseR = ((FREQ + f) * frames + t) * 2
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
                    val expectedMaskValues = FREQ * 2 * frames * 2
                    require(mask.size == expectedMaskValues) { "The separation model returned an unexpected output shape." }
                    return reconstruct(stft, mask, frames)
                }
            }
        }
    }

    private fun computeStft(source: FloatArray, channel: Int, out: FloatArray, frames: Int) {
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

    private fun reconstruct(stft: FloatArray, mask: FloatArray, frames: Int): Pair<FloatArray, FloatArray> {
        val paddedLength = CHUNK + N_FFT
        val left = FloatArray(CHUNK)
        val right = FloatArray(CHUNK)
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
                    val maskIndex = maskFlat * frames * 2 + t * 2
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
                        val window = hann[n]
                        acc[pos] += workR[n] * window
                        windowNorm[pos] += window * window
                    }
                }
            }
            for (i in 0 until CHUNK) {
                val position = i + pad
                target[i] = if (position < acc.size && windowNorm[position] > 1e-8f) {
                    acc[position] / windowNorm[position]
                } else 0f
            }
        }

        reconstructChannel(0, left)
        reconstructChannel(1, right)
        return left to right
    }

    private fun reflectIndex(index: Int, length: Int): Int {
        if (length <= 1) return 0
        var value = index
        while (value < 0 || value >= length) {
            value = if (value < 0) -value else 2 * length - 2 - value
        }
        return value
    }
}

private class WavStreamWriter(
    file: File,
    frameCount: Long,
    sampleRate: Int
) : Closeable {
    private val output = BufferedOutputStream(FileOutputStream(file), 128 * 1024)

    init {
        val dataBytes = frameCount * 4L
        require(dataBytes <= 0xffff_ffffL - 36L) { "This audio is too long for the WAV export format." }
        output.write("RIFF".toByteArray())
        writeInt(36L + dataBytes)
        output.write("WAVE".toByteArray())
        output.write("fmt ".toByteArray())
        writeInt(16L)
        writeShort(1)
        writeShort(2)
        writeInt(sampleRate.toLong())
        writeInt(sampleRate * 4L)
        writeShort(4)
        writeShort(16)
        output.write("data".toByteArray())
        writeInt(dataBytes)
    }

    fun write(left: FloatArray, right: FloatArray, count: Int) {
        require(count <= left.size && count <= right.size)
        for (i in 0 until count) {
            writeShort((left[i].coerceIn(-1f, 1f) * 32767f).toInt())
            writeShort((right[i].coerceIn(-1f, 1f) * 32767f).toInt())
        }
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeInt(value: Long) {
        output.write((value and 0xff).toInt())
        output.write(((value ushr 8) and 0xff).toInt())
        output.write(((value ushr 16) and 0xff).toInt())
        output.write(((value ushr 24) and 0xff).toInt())
    }

    override fun close() {
        output.flush()
        output.close()
    }
}
