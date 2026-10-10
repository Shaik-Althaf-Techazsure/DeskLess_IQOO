package com.techazsure.deskless.capture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** The arithmetic behind the noise and vibration meters, kept apart from the sensors so it can be tested. */
object Signal {
    /** How strongly something shook, in m/s², and the frequency that stood out, or 0 when none did. */
    data class Vibration(val rms: Float, val hertz: Float)

    /**
     * Sound level from 16-bit microphone samples. Android asks phone makers to tune the voice-recognition
     * microphone so that 90 dB at 1 kHz reads an RMS of 2500; this turns that rule round. Indicative only:
     * not every phone follows it, and nothing here is weighted the way a sound level meter is.
     */
    fun decibels(rms: Double): Float = (90.0 + 20.0 * log10(max(rms, 1.0) / 2500.0)).toFloat()

    /**
     * Works out a vibration from accelerometer samples taken at [rate] per second while the phone rested on
     * the thing being checked. Gravity and any fixed offset are removed as the average of each axis.
     */
    fun vibration(x: FloatArray, y: FloatArray, z: FloatArray, rate: Float): Vibration {
        val n = minOf(x.size, y.size, z.size)
        if (n < 16 || rate <= 0f) return Vibration(0f, 0f)
        val axes = listOf(x, y, z).map { axis ->
            val mean = axis.take(n).average().toFloat()
            FloatArray(n) { axis[it] - mean }
        }
        var squares = 0.0
        for (axis in axes) for (value in axis) squares += value * value
        val rms = sqrt(squares / n).toFloat()

        // The spectrum of the last power-of-two samples, the three axes added together.
        var size = 1
        while (size * 2 <= n) size *= 2
        val power = FloatArray(size / 2)
        for (axis in axes) {
            val re = FloatArray(size) { i ->
                // A Hann window, so a tone between two bins does not smear across the spectrum.
                axis[n - size + i] * (0.5f - 0.5f * cos(2.0 * PI * i / (size - 1)).toFloat())
            }
            val im = FloatArray(size)
            fft(re, im)
            for (k in power.indices) power[k] += re[k] * re[k] + im[k] * im[k]
        }
        val step = rate / size
        // Below 2 Hz it is the phone settling or being nudged, not the machine.
        val first = max(1, (2f / step).toInt() + 1)
        if (first >= power.size) return Vibration(rms, 0f)
        var peak = first
        for (k in first until power.size) if (power[k] > power[peak]) peak = k
        val typical = power.copyOfRange(first, power.size).sorted().let { it[it.size / 2] }
        // A frequency is named only when it clearly rises out of the rest.
        val stands = power[peak] > 8f * max(typical, 1e-9f)
        return Vibration(rms, if (stands) peak * step else 0f)
    }

    /** An in-place fast Fourier transform; the arrays' length must be a power of two. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepRe = cos(angle).toFloat()
            val stepIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tRe = re[b] * wRe - im[b] * wIm
                    val tIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val next = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe
                    wRe = next
                }
                start += length
            }
            length *= 2
        }
    }
}
