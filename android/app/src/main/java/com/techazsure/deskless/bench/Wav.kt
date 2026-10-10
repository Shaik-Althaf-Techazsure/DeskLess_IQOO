package com.techazsure.deskless.bench

import com.techazsure.deskless.capture.WavRecorder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sound files as the phone's speech engine writes them, turned into what the speech model listens to:
 * 16 kHz, one channel, 16-bit, behind a 44-byte header.
 */
object Wav {
    /** Reads the 16-bit PCM in [bytes], whatever its rate and channels, as one channel at 16 kHz. Null if it is not such a file. */
    fun to16k(bytes: ByteArray): ShortArray? {
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (at + 8 <= bytes.size) {
            val name = String(bytes, at, 4, Charsets.US_ASCII)
            // An engine that streams the file out may leave the length of the sound as zero or as all ones.
            val stated = data.getInt(at + 4)
            val body = at + 8
            if (name == "fmt ") {
                if (data.getShort(body).toInt() != 1) return null
                channels = data.getShort(body + 2).toInt()
                rate = data.getInt(body + 4)
                bits = data.getShort(body + 14).toInt()
            } else if (name == "data") {
                if (channels < 1 || rate < 8000 || bits != 16) return null
                val length = if (stated <= 0 || body + stated > bytes.size) bytes.size - body else stated
                val frames = length / (2 * channels)
                val first = ShortArray(frames) { data.getShort(body + it * 2 * channels) }
                return resample(first, rate, WavRecorder.SAMPLE_RATE)
            }
            if (stated < 0) return null
            at = body + stated + (stated and 1)
        }
        return null
    }

    /** Changes the rate of [samples] by drawing a straight line between neighbours: plain, and enough for speech. */
    fun resample(samples: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || samples.isEmpty()) return samples
        val length = (samples.size.toLong() * to / from).toInt()
        return ShortArray(length) { i ->
            val place = i.toDouble() * from / to
            val left = place.toInt().coerceAtMost(samples.size - 1)
            val right = (left + 1).coerceAtMost(samples.size - 1)
            val part = place - left
            (samples[left] * (1 - part) + samples[right] * part).toInt().toShort()
        }
    }

    /** Rewrites the sound file [from] as 16 kHz mono into [out]. False when [from] is not a sound file this reads. */
    fun to16k(from: File, out: File): Boolean {
        val samples = runCatching { to16k(from.readBytes()) }.getOrNull() ?: return false
        if (samples.size < WavRecorder.SAMPLE_RATE / 4) return false
        val body = ByteBuffer.allocate(WavRecorder.HEADER_BYTES + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        body.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + samples.size * 2).put("WAVE".toByteArray(Charsets.US_ASCII))
        body.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
        body.putInt(WavRecorder.SAMPLE_RATE).putInt(WavRecorder.SAMPLE_RATE * 2).putShort(2).putShort(16)
        body.put("data".toByteArray(Charsets.US_ASCII)).putInt(samples.size * 2)
        samples.forEach { body.putShort(it) }
        out.writeBytes(body.array())
        return true
    }
}
