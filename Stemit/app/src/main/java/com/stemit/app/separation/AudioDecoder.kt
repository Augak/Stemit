package com.stemit.app.separation

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Decodes directly to a temporary interleaved PCM16 file.
 * Audio samples are streamed to disk; the complete song is never held in heap memory.
 */
class AudioDecoder(private val context: Context) {
    fun decodeToPcm16(uri: Uri, output: File, targetRate: Int = 44_100): Long {
        val extractor = MediaExtractor()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open the selected audio file.")
        var codec: MediaCodec? = null
        var writer: StreamingResampler? = null
        try {
            extractor.setDataSource(descriptor.fileDescriptor)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            require(track >= 0) { "The selected file does not contain an audio track." }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Audio format is not supported.")
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sourceChannels >= 1) { "The audio file has no channels." }
            writer = StreamingResampler(output, sourceRate, targetRate)

            // PCM WAV files are already decoded. Passing audio/raw to MediaCodec is
            // not supported by every Android vendor, so stream these samples directly.
            if (mime == "audio/raw") {
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                val rawBuffer = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    rawBuffer.clear()
                    val size = extractor.readSampleData(rawBuffer, 0)
                    if (size < 0) break
                    rawBuffer.position(0)
                    rawBuffer.limit(size)
                    streamPcm(rawBuffer, sourceChannels, encoding, writer)
                    extractor.advance()
                }
                return writer.finish()
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: error("Audio decoder input buffer is unavailable.")
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
                        val decodedFormat = codec.outputFormat
                        if (decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                                ?: error("Audio decoder output buffer is unavailable.")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            streamPcm(buffer, sourceChannels, encoding, writer)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            return writer.finish()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            writer?.closeQuietly()
            extractor.release()
            descriptor.close()
        }
    }

    private fun streamPcm(
        buffer: ByteBuffer,
        channels: Int,
        encoding: Int,
        sink: StreamingResampler
    ) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val samples = buffer.asFloatBuffer()
            while (samples.remaining() >= channels) {
                val left = samples.get()
                val right = if (channels > 1) samples.get() else left
                repeat(max(0, channels - 2)) { samples.get() }
                sink.accept(left, right)
            }
        } else {
            while (buffer.remaining() >= channels * 2) {
                val left = buffer.short.toFloat() / 32768f
                val right = if (channels > 1) buffer.short.toFloat() / 32768f else left
                repeat(max(0, channels - 2)) { buffer.short }
                sink.accept(left, right)
            }
        }
    }

    private class StreamingResampler(file: File, fromRate: Int, toRate: Int) {
        private val output = BufferedOutputStream(FileOutputStream(file), 128 * 1024)
        private val step = fromRate.toDouble() / toRate.toDouble()
        private var inputIndex = 0L
        private var nextOutputPosition = 0.0
        private var previousLeft = 0f
        private var previousRight = 0f
        private var outputFrames = 0L
        private var finished = false

        fun accept(leftValue: Float, rightValue: Float) {
            val left = leftValue.coerceIn(-1f, 1f)
            val right = rightValue.coerceIn(-1f, 1f)
            if (inputIndex == 0L) {
                writeFrame(left, right)
                nextOutputPosition += step
            } else {
                while (nextOutputPosition <= inputIndex.toDouble()) {
                    val fraction = (nextOutputPosition - (inputIndex - 1).toDouble()).toFloat().coerceIn(0f, 1f)
                    writeFrame(
                        previousLeft * (1f - fraction) + left * fraction,
                        previousRight * (1f - fraction) + right * fraction
                    )
                    nextOutputPosition += step
                }
            }
            previousLeft = left
            previousRight = right
            inputIndex++
        }

        private fun writeFrame(left: Float, right: Float) {
            writeShort((left.coerceIn(-1f, 1f) * 32767f).toInt())
            writeShort((right.coerceIn(-1f, 1f) * 32767f).toInt())
            outputFrames++
        }

        private fun writeShort(value: Int) {
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
        }

        fun finish(): Long {
            if (!finished) {
                output.flush()
                output.close()
                finished = true
            }
            return outputFrames
        }

        fun closeQuietly() {
            if (!finished) {
                try { output.close() } catch (_: Exception) {}
                finished = true
            }
        }
    }
}
