package com.stemit.app.separation

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the licensed MIT BS PolarFormer ONNX model once and keeps it locally.
 * After the first successful download, separation can run without network access.
 */
class ModelManager(private val context: Context) {
    companion object {
        const val MODEL_NAME = "bs_polarformer_fp16.onnx"
        const val MODEL_BYTES = 103_000_000L
        private const val MIN_FREE_BYTES = MODEL_BYTES + 100L * 1024L * 1024L
        const val MODEL_URL =
            "https://huggingface.co/bgkb/bs_polarformer/resolve/main/bs_polarformer_fp16.onnx?download=true"
    }

    private val modelDir = File(context.filesDir, "models").apply { mkdirs() }
    val modelFile: File get() = File(modelDir, MODEL_NAME)

    fun isInstalled(): Boolean = modelFile.exists() && modelFile.length() > 50_000_000L

    fun deleteInstalled(): Boolean = !modelFile.exists() || modelFile.delete()

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
            if (modelFile.exists() && !modelFile.delete()) {
                throw IllegalStateException("Unable to replace the old model file.")
            }
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
                if (!temp.delete()) temp.deleteOnExit()
            }
            completed = true
            return modelFile
        } finally {
            connection.disconnect()
            // Never leave a partial download behind after an error.
            if (!completed && temp.exists()) temp.delete()
        }
    }
}
