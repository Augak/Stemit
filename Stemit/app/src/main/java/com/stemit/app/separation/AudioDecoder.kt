package com.stemit.app.separation

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class StereoAudio(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int
) {
    val length: Int get() = left.size
}

/** Decodes common Android-supported audio containers/codecs through MediaCodec. */
class AudioDecoder(private val context: Context) {
    fun decode(uri: Uri): StereoAudio {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r")?.use {
            extractor.setDataSource(it.fileDescriptor)
        } ?: error("Unable to open audio file")

        var track = -1
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                break
            }
        }
        require(track >= 0) { "No audio track found" }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME type")
        val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val left = ArrayList<Float>(sourceRate * 30)
        val right = ArrayList<Float>(sourceRate * 30)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var outputEncoding = AudioFormat.ENCODING_PCM_16BIT

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)!!
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        outputEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            decodePcm(buffer, sourceChannels, outputEncoding, left, right)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }

        require(left.isNotEmpty()) { "Audio decoder returned no samples" }
        val l = left.toFloatArray()
        val r = right.toFloatArray()
        return if (sourceRate == 44_100) {
            StereoAudio(l, r, sourceRate)
        } else {
            val rsL = resample(l, sourceRate, 44_100)
            val rsR = resample(r, sourceRate, 44_100)
            StereoAudio(rsL, rsR, 44_100)
        }
    }

    private fun decodePcm(
        buffer: ByteBuffer,
        channels: Int,
        encoding: Int,
        left: MutableList<Float>,
        right: MutableList<Float>
    ) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val samples = buffer.asFloatBuffer()
                while (samples.remaining() >= channels) {
                    val l = samples.get()
                    val r = if (channels > 1) samples.get() else l
                    for (skip in 2 until channels) samples.get()
                    left.add(l.coerceIn(-1f, 1f))
                    right.add(r.coerceIn(-1f, 1f))
                }
            }
            else -> {
                while (buffer.remaining() >= channels * 2) {
                    val l = buffer.short.toFloat() / 32768f
                    val r = if (channels > 1) buffer.short.toFloat() / 32768f else l
                    repeat(max(0, channels - 2)) { buffer.short }
                    left.add(l)
                    right.add(r)
                }
            }
        }
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return input
        val outLength = max(1, (input.size.toLong() * to / from).toInt())
        val output = FloatArray(outLength)
        val scale = from.toDouble() / to.toDouble()
        for (i in output.indices) {
            val p = i * scale
            val a = p.toInt().coerceIn(0, input.lastIndex)
            val b = min(a + 1, input.lastIndex)
            val t = (p - a).toFloat()
            output[i] = input[a] * (1f - t) + input[b] * t
        }
        return output
    }
}
