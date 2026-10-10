package com.techazsure.deskless.link

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the link is told a spoken message is called. It plays itself, so the link does not announce it as a report. */
const val VOICE_TITLE = "Voice message"

/** One spoken message: said on this phone, or heard from a linked one. */
data class TalkMessage(
    val id: String,
    /** The linked phone it came from; blank when it was said here. */
    val from: String,
    val name: String,
    val at: Long,
    val seconds: Int,
    /** For a message said here, the phones it was meant for. */
    val to: List<String> = emptyList(),
    val heard: Boolean = true,
) {
    val mine: Boolean get() = from.isBlank()

    /** What the link knows this message's sealed copies by. */
    val report: String get() = "talk-$id"
}

/**
 * Push to talk between linked phones, with no internet and no mobile signal. A message is recorded while
 * the button is held, squeezed to a few kilobytes a second, and sent through the same sealed link that
 * carries reports: over Bluetooth or Wi-Fi Direct to a linked phone in range, or carried by a DeskLess
 * phone in between. Only the phone it is for can open it.
 *
 * It is a walkie-talkie in how it is used, not a radio set: a phone has no long-range radio an app may
 * use, so the phones must be within Bluetooth or Wi-Fi reach of each other or of a phone that carries.
 */
class Talk(private val context: Context) {
    private val folder = File(context.filesDir, "talk").apply { mkdirs() }
    private val index = File(folder, "index.json")
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    /** Where the message being said is recorded to. */
    val recording = File(context.cacheDir, "talk.m4a")

    fun fileOf(message: TalkMessage) = File(folder, "${message.id}.m4a")

    /** Starts recording speech, small enough to cross a Bluetooth link in a moment. False if the microphone is not to be had. */
    fun start(): Boolean = runCatching {
        stop()
        recording.delete()
        @Suppress("DEPRECATION")
        val made = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        made.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        made.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        made.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        made.setAudioChannels(1)
        made.setAudioSamplingRate(SAMPLE_RATE)
        made.setAudioEncodingBitRate(BIT_RATE)
        made.setOutputFile(recording.absolutePath)
        made.prepare()
        made.start()
        recorder = made
        true
    }.getOrElse {
        recorder = null
        false
    }

    /** Stops recording. Returns false when nothing usable was recorded, such as a tap too short to hold a word. */
    fun stop(): Boolean {
        val made = recorder ?: return false
        recorder = null
        val kept = runCatching { made.stop() }.isSuccess
        runCatching { made.release() }
        return kept && recording.length() > 200
    }

    /** The message just recorded, wrapped for sending: a mark that says what it is, its length, then the sound. */
    fun wrap(seconds: Int): File {
        val out = File(context.cacheDir, "talk-out.bin")
        out.outputStream().use { stream ->
            stream.write(MARK)
            stream.write(seconds.coerceIn(0, 255))
            recording.inputStream().use { it.copyTo(stream) }
        }
        return out
    }

    /** Keeps the message just recorded under [id], for playing back on this phone. */
    fun keepOwn(id: String) {
        recording.copyTo(File(folder, "$id.m4a"), overwrite = true)
    }

    /**
     * If [pack] is a spoken message, takes its sound out into this phone's store under [id] and returns
     * its length in seconds; otherwise null, and the file is something else, such as a report.
     */
    fun unwrap(pack: File, id: String): Int? {
        pack.inputStream().use { stream ->
            val mark = ByteArray(MARK.size)
            if (stream.read(mark) != MARK.size || !mark.contentEquals(MARK)) return null
            val seconds = stream.read().coerceAtLeast(0)
            File(folder, "$id.m4a").outputStream().use { stream.copyTo(it) }
            return seconds
        }
    }

    /** Plays a message through the loudspeaker; whatever was playing stops. [done] is called when it ends. */
    fun play(message: TalkMessage, done: () -> Unit = {}) {
        quiet()
        val file = fileOf(message)
        if (!file.isFile) return done()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    quiet()
                    done()
                }
                prepare()
                start()
            }
        }.onFailure {
            quiet()
            done()
        }
    }

    fun quiet() {
        runCatching { player?.release() }
        player = null
    }

    fun load(): List<TalkMessage> = runCatching {
        val list = JSONArray(index.readText())
        (0 until list.length()).map { at ->
            val one = list.getJSONObject(at)
            val to = one.optJSONArray("to")
            TalkMessage(
                id = one.getString("id"), from = one.optString("from"), name = one.optString("name"), at = one.optLong("at"),
                seconds = one.optInt("seconds"), to = (0 until (to?.length() ?: 0)).map { to!!.getString(it) }, heard = one.optBoolean("heard", true),
            )
        }
    }.getOrDefault(emptyList())

    /** Writes the list down, keeping the newest [KEPT] and throwing the sound of older ones away. */
    fun save(messages: List<TalkMessage>): List<TalkMessage> {
        val kept = messages.takeLast(KEPT)
        messages.dropLast(KEPT).forEach { fileOf(it).delete() }
        val list = JSONArray()
        kept.forEach { message ->
            list.put(
                JSONObject().put("id", message.id).put("from", message.from).put("name", message.name).put("at", message.at)
                    .put("seconds", message.seconds).put("to", JSONArray(message.to)).put("heard", message.heard)
            )
        }
        runCatching { index.writeText(list.toString()) }
        return kept
    }

    companion object {
        /** The longest a message may be. */
        const val MAX_SECONDS = 30
        private const val SAMPLE_RATE = 16_000

        /** Bits a second: about 3 KB for each second of speech. */
        private const val BIT_RATE = 24_000
        private const val KEPT = 60

        /** What a sealed file begins with when it is a spoken message and not a report. */
        private val MARK = "DLTALK1\n".toByteArray(Charsets.US_ASCII)
    }
}
