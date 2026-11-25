package com.stemit.app.online

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import java.util.zip.ZipInputStream

/** Synchronous: call on an IO/background thread only, after explicit upload consent. */
class OnlineSeparator(private val endpoint: String, private val bearerToken: String) {
    data class Stems(val vocals: File, val instrumental: File)

    companion object {
        private const val MAX_UPLOAD = 64L * 1024 * 1024
        private const val MAX_DOWNLOAD = 128L * 1024 * 1024
        private const val MAX_ENTRY = 64L * 1024 * 1024
    }

    /** input must be 16-bit PCM WAV, 44.1/48 kHz, mono/stereo, <=5 min.
     * Returns app-private files in a fresh directory. Caller owns their lifecycle.
     */
    fun separate(context: Context, input: Uri): Stems {
        val url = URI(endpoint)
        require(url.scheme == "https" && !url.host.isNullOrEmpty() && url.userInfo == null &&
            url.query == null && url.fragment == null && url.path == "/v1/separate") {
            "Configure a trusted HTTPS /v1/separate endpoint"
        }
        require(bearerToken.length >= 32 && bearerToken.all { it.code in 33..126 }) {
            "A non-whitespace ASCII bearer token is required"
        }
        require(input.scheme == "content") { "Use a user-selected content URI" }
        val work = File(context.cacheDir, "online-" + java.util.UUID.randomUUID())
        check(work.mkdir()) { "Unable to create private working directory" }
        var success = false
        try {
            val upload = File(work, "upload.wav")
            context.contentResolver.openInputStream(input)?.use { source ->
                upload.outputStream().use { destination -> copyLimited(source, destination, MAX_UPLOAD) }
            } ?: throw IOException("Cannot read selected audio")
            val conn = url.toURL().openConnection() as HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 30_000
                conn.readTimeout = 930_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $bearerToken")
                conn.setRequestProperty("Content-Type", "audio/wav")
                conn.setRequestProperty("Accept", "application/zip")
                conn.setFixedLengthStreamingMode(upload.length())
                conn.outputStream.use { out -> upload.inputStream().use { it.copyTo(out) } }
                upload.delete()
                if (conn.responseCode != 200) throw IOException("Online separation failed (HTTP ${conn.responseCode})")
                if (conn.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/zip")
                    throw IOException("Unexpected response type")
                val declared = conn.contentLengthLong
                if (declared < 0 || declared > MAX_DOWNLOAD) throw IOException("Invalid response size")
                val zip = File(work, "response.zip")
                conn.inputStream.use { source -> zip.outputStream().use { copyLimited(source, it, MAX_DOWNLOAD) } }
                if (zip.length() != declared) throw IOException("Incomplete download")
                val names = setOf("vocals.wav", "no_vocals.wav")
                val found = mutableSetOf<String>()
                ZipInputStream(zip.inputStream()).use { stream ->
                    while (true) {
                        val entry = stream.nextEntry ?: break
                        if (entry.isDirectory || entry.name !in names || !found.add(entry.name))
                            throw IOException("Unexpected archive entry")
                        File(work, entry.name).outputStream().use { copyLimited(stream, it, MAX_ENTRY) }
                        stream.closeEntry()
                    }
                }
                if (found != names || names.any { File(work, it).length() < 44 })
                    throw IOException("Incomplete stems")
                zip.delete()
                success = true
                return Stems(File(work, "vocals.wav"), File(work, "no_vocals.wav"))
            } finally { conn.disconnect() }
        } finally { if (!success) work.deleteRecursively() }
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Transfer exceeds limit")
            output.write(buffer, 0, count)
        }
    }
}
