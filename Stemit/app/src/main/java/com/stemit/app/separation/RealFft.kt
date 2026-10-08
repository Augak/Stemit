package com.stemit.app.separation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small allocation-free radix-2 FFT used by the on-device separator. */
internal class RealFft(private val n: Int) {
    private val cosTable = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val bitReverse = IntArray(n)

    init {
        var j = 0
        for (i in 0 until n) {
            bitReverse[i] = j
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
        }
    }

    fun forward(real: FloatArray, imag: FloatArray) = transform(real, imag, inverse = false)
    fun inverse(real: FloatArray, imag: FloatArray) = transform(real, imag, inverse = true)

    private fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                for (j in 0 until half) {
                    val idx = j * step
                    val c = cosTable[idx]
                    val s = if (inverse) sinTable[idx] else -sinTable[idx]
                    val p = i + j
                    val q = p + half
                    val tr = real[q] * c - imag[q] * s
                    val ti = real[q] * s + imag[q] * c
                    val ur = real[p]
                    val ui = imag[p]
                    real[p] = ur + tr
                    imag[p] = ui + ti
                    real[q] = ur - tr
                    imag[q] = ui - ti
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val scale = 1f / n
            for (i in 0 until n) {
                real[i] *= scale
                imag[i] *= scale
            }
        }
    }
}
