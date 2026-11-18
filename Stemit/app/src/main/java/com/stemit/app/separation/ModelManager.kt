package com.stemit.app.separation

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the licensed MIT BS PolarFormer ONNX model once and keeps it locally.
 * After the first successful download, separation can run without network access.
 */
class ModelManager(private val context: Context) {
    companion object {
        const val MODEL_NAME = "bs_polarformer_fp16.onnx"
        const val MODEL_BYTES = 108_000_000L
        const val MODEL_SHA256 = "76424289ea586bae4bbdb289383b0269b099416471e2b05068d02aa0b0c01467"
        private const val MIN_FREE_BYTES = MODEL_BYTES + 100L * 1024L * 1024L
        const val MODEL_URL =
            "https://huggingface.co/bgkb/bs_polarformer/resolve/9158719ee2173edd480a735764627526506fe4af/bs_polarformer_fp16.onnx?download=true"
    }

    private val modelDir = File(context.filesDir, "models").apply { mkdirs() }
    val modelFile: File get() = File(modelDir, MODEL_NAME)
    private val verifiedMarker = File(modelDir, "$MODEL_NAME.sha256")

    fun isInstalled(): Boolean =
        modelFile.exists() && modelFile.length() > 50_000_000L &&
            verifiedMarker.exists() && verifiedMarker.readText().trim().equals(MODEL_SHA256, ignoreCase = true)

    fun deleteInstalled(): Boolean {
        val modelRemoved = !modelFile.exists() || modelFile.delete()
        verifiedMarker.delete()
        return modelRemoved
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun markVerified() {
        try {
            verifiedMarker.writeText(MODEL_SHA256)
        } catch (_: Exception) {
            // The model remains valid; a future launch can verify it again.
        }
    }

    /**
     * Fail early with an actionable message instead of filling the user's
     * internal storage with an incomplete model download.
     */
    private fun requireDownloadSpace() {
        val stats = StatFs(modelDir.absolutePath)
        val available = stats.availableBytes
        check(available >= MIN_FREE_BYTES) {
            "Not enough free storage to download the separation model. " +
                "Stemit needs about 103 MB for the model plus at least 100 MB " +
                "of working space. Free storage and try again."
        }
    }

    fun ensureInstalled(progress: (Float) -> Unit = {}): File {
        if (isInstalled()) return modelFile
        if (modelFile.exists() && modelFile.length() > 50_000_000L) {
            if (sha256(modelFile).equals(MODEL_SHA256, ignoreCase = true)) {
                markVerified()
                return modelFile
            }
            modelFile.delete()
            verifiedMarker.delete()
        }
        requireDownloadSpace()

        val temp = File(modelDir, "$MODEL_NAME.part")
        var completed = false
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Model download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buffer, 0, n)
                        done += n
                        progress((done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat())
                    }
                    output.fd.sync()
                }
            }
            if (temp.length() < 50_000_000L) {
                throw IllegalStateException("Downloaded model is incomplete (${temp.length()} bytes).")
            }
            val actualSha256 = sha256(temp)
            if (!actualSha256.equals(MODEL_SHA256, ignoreCase = true)) {
                throw IllegalStateException("The downloaded model failed its SHA-256 integrity check. Please retry on a stable connection.")
            }
            if (modelFile.exists() && !modelFile.delete()) {
                throw IllegalStateException("Unable to replace the old model file.")
            }
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
                if (!temp.delete()) temp.deleteOnExit()
            }
            markVerified()
            completed = true
            return modelFile
        } finally {
            connection.disconnect()
            // Never leave a partial download behind after an error.
            if (!completed && temp.exists()) temp.delete()
        }
    }
}
