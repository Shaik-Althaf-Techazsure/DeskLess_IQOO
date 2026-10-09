package com.techazsure.deskless.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/** Records the microphone to a 16 kHz mono 16-bit WAV file, the input format Whisper expects. */
class WavRecorder {
    @Volatile
    private var recording = false
    private var worker: Thread? = null

    /** Peak of the latest buffer, 0..1, for a level meter. */
    @Volatile
    var level = 0f
        private set

    /** Returns false if the microphone could not be opened. The caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun start(out: File): Boolean {
        if (recording) return false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            max(minBuffer, SAMPLE_RATE),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        recording = true
        worker = thread(name = "wav-recorder") {
            var dataBytes = 0L
            try {
                RandomAccessFile(out, "rw").use { file ->
                    file.setLength(0)
                    file.write(ByteArray(HEADER_BYTES))
                    val samples = ShortArray(SAMPLE_RATE / 10)
                    val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    record.startRecording()
                    while (recording) {
                        val read = record.read(samples, 0, samples.size)
                        if (read <= 0) break
                        bytes.clear()
                        var peak = 0
                        for (i in 0 until read) {
                            bytes.putShort(samples[i])
                            peak = max(peak, abs(samples[i].toInt()))
                        }
                        file.write(bytes.array(), 0, read * 2)
                        dataBytes += read * 2
                        level = peak / 32768f
                    }
                    file.seek(0)
                    file.write(header(dataBytes))
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                recording = false
                level = 0f
            }
        }
        return true
    }

    /** Stops and waits for the file to be finalised. */
    fun stop() {
        recording = false
        worker?.join(2000)
        worker = null
    }

    private fun header(dataBytes: Long): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2) // byte rate
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val HEADER_BYTES = 44
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        fun seconds(wav: File): Int =
            (max(0L, wav.length() - HEADER_BYTES) / (SAMPLE_RATE * 2)).toInt()
    }
}
