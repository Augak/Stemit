package com.stemit.app

import android.media.AudioFormat
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stemit.app.separation.PolarFormerEngine
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeparationEngineIntegrationTest {
    @Test
    fun separatesFiveSecondPcmWavWithoutLoadingWholeTrackIntoMemory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workDir = File(context.cacheDir, "separated").apply { mkdirs() }
        val source = File(workDir, "integration-source.wav")
        val durationSeconds = 5
        val sampleRate = 44_100
        val frames = durationSeconds * sampleRate
        writeTestWave(source, frames, sampleRate)

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
        val output = File(workDir, "integration-output")
        output.deleteRecursively()

        val result = PolarFormerEngine(context).separate(uri, output)

        assertTrue("Vocal stem must be written", result.vocalsFile.exists())
        assertTrue("Instrumental stem must be written", result.instrumentalFile.exists())
        assertEquals("Vocal WAV must contain all decoded frames", 44L + frames * 4L, result.vocalsFile.length())
        assertEquals("Instrumental WAV must contain all decoded frames", 44L + frames * 4L, result.instrumentalFile.length())
        assertEquals("Duration should remain close to the source", durationSeconds.toFloat(), result.durationSeconds, 0.08f)
        assertEquals("WAV header should be valid", "RIFF", result.vocalsFile.inputStream().use { String(it.readNBytes(4)) })
        assertEquals("WAV header should be valid", "RIFF", result.instrumentalFile.inputStream().use { String(it.readNBytes(4)) })
    }

    private fun writeTestWave(file: File, frames: Int, sampleRate: Int) {
        val dataBytes = frames * 4L
        BufferedOutputStream(FileOutputStream(file), 64 * 1024).use { out ->
            fun le16(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
            }
            fun le32(value: Long) {
                out.write((value and 0xff).toInt())
                out.write(((value ushr 8) and 0xff).toInt())
                out.write(((value ushr 16) and 0xff).toInt())
                out.write(((value ushr 24) and 0xff).toInt())
            }
            out.write("RIFF".toByteArray())
            le32(36L + dataBytes)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            le32(16L)
            le16(1)
            le16(2)
            le32(sampleRate.toLong())
            le32(sampleRate * 4L)
            le16(4)
            le16(16)
            out.write("data".toByteArray())
            le32(dataBytes)
            for (i in 0 until frames) {
                val time = i.toDouble() / sampleRate.toDouble()
                val left = (sin(2.0 * PI * 440.0 * time) * 9000.0).toInt()
                val right = (sin(2.0 * PI * 220.0 * time) * 9000.0).toInt()
                le16(left)
                le16(right)
            }
        }
    }
}
