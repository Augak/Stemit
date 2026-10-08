package com.stemit.app.separation

import android.content.Context
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
        const val MODEL_URL =
            "https://huggingface.co/bgkb/bs_polarformer/resolve/main/bs_polarformer_fp16.onnx?download=true"
    }

    private val modelDir = File(context.filesDir, "models").apply { mkdirs() }
    val modelFile: File get() = File(modelDir, MODEL_NAME)

    fun isInstalled(): Boolean = modelFile.exists() && modelFile.length() > 50_000_000L

    fun ensureInstalled(progress: (Float) -> Unit = {}): File {
        if (isInstalled()) return modelFile
        val temp = File(modelDir, "$MODEL_NAME.part")
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
                    val buffer = ByteArray(1024 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        done += n
                        progress((done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat())
                    }
                }
            }
            if (temp.length() < 50_000_000L) {
                throw IllegalStateException("Downloaded model is incomplete (${temp.length()} bytes).")
            }
            if (modelFile.exists()) modelFile.delete()
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
                temp.delete()
            }
            return modelFile
        } finally {
            connection.disconnect()
            if (temp.exists() && !modelFile.exists()) temp.delete()
        }
    }
}
