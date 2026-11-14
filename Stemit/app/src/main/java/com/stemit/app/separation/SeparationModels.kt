package com.stemit.app.separation

data class SeparationResult(
    val vocalsFile: java.io.File,
    val instrumentalFile: java.io.File,
    val durationSeconds: Float
)

data class SeparationProgress(val fraction: Float, val message: String)

fun interface SeparationProgressListener {
    fun onProgress(progress: SeparationProgress)
}
