package com.techazsure.deskless.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.techazsure.deskless.capture.WavRecorder
import com.techazsure.deskless.models.ModelStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hindi-English speech to English text, on the phone: Whisper small through sherpa-onnx, task "translate".
 * Whisper only reads 30 seconds at a time, so longer notes are first cut at pauses by Silero VAD.
 */
class Transcriber(private val models: ModelStore) {
    private var recognizer: OfflineRecognizer? = null

    val ready: Boolean
        get() = models.speechReady

    /** Blocking and slow (seconds); call it off the main thread. One call at a time. */
    @Synchronized
    fun toEnglish(wav: File): String {
        val samples = readSamples(wav)
        if (samples.size < WavRecorder.SAMPLE_RATE / 4) return ""
        val recognizer = recognizer ?: load().also { recognizer = it }

        if (samples.size <= DIRECT_LIMIT) return decode(recognizer, samples)

        val vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = path(ModelStore.VAD),
                    minSilenceDuration = 0.5f,
                    maxSpeechDuration = 25f,
                ),
                sampleRate = WavRecorder.SAMPLE_RATE,
            ),
        )
        try {
            val text = StringBuilder()
            fun drain() {
                while (!vad.empty()) {
                    text.append(decode(recognizer, vad.front().samples)).append(' ')
                    vad.pop()
                }
            }
            // Zero-pad the tail so the last partial window is not dropped.
            val padded = samples.copyOf((samples.size + VAD_WINDOW - 1) / VAD_WINDOW * VAD_WINDOW)
            var at = 0
            while (at < padded.size) {
                vad.acceptWaveform(padded.copyOfRange(at, at + VAD_WINDOW))
                drain()
                at += VAD_WINDOW
            }
            vad.flush()
            drain()
            return text.toString().trim()
        } finally {
            vad.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
    }

    private fun load() = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = WavRecorder.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path(ModelStore.WHISPER_ENCODER),
                    decoder = path(ModelStore.WHISPER_DECODER),
                    language = "hi",
                    task = "translate",
                ),
                tokens = path(ModelStore.WHISPER_TOKENS),
                modelType = "whisper",
                numThreads = 4,
                provider = "cpu",
            ),
        ),
    )

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate = WavRecorder.SAMPLE_RATE)
            recognizer.decode(stream)
            // Whisper writes tags such as "[Pause]" or "[BLANK_AUDIO]" for silence; they are not speech.
            return recognizer.getResult(stream).text.replace(NON_SPEECH, " ").replace(SPACES, " ").trim()
        } finally {
            stream.release()
        }
    }

    private fun path(name: String): String =
        models.find(name)?.absolutePath ?: error("Speech model file $name is not on this phone.")

    private fun readSamples(wav: File): FloatArray {
        val bytes = wav.readBytes()
        if (bytes.size <= WavRecorder.HEADER_BYTES) return FloatArray(0)
        val pcm = ByteBuffer.wrap(bytes, WavRecorder.HEADER_BYTES, bytes.size - WavRecorder.HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        return FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
    }

    private companion object {
        const val VAD_WINDOW = 512

        /** Up to 28 s goes to Whisper in one piece; that keeps short notes whole. */
        const val DIRECT_LIMIT = 28 * WavRecorder.SAMPLE_RATE

        val NON_SPEECH = Regex("""\[[^\]]{1,30}]|\([^)]{1,30}\)""")
        val SPACES = Regex("""\s+""")
    }
}
