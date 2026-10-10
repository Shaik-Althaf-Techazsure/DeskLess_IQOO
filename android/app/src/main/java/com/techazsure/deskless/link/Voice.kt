package com.techazsure.deskless.link

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * The phone saying what happened, so a hand-off needs no look at the screen: "Report sent", "Report
 * received". It uses the speech engine already on the phone, which works with no network. Silent if the
 * phone has none.
 */
class Voice(context: Context) {
    /** True once the phone's speech engine has started; false on a phone that has none. */
    var ready by mutableStateOf(false)
        private set
    private var waiting: String? = null
    private var engine: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private val counter = AtomicInteger()

    /** What to do when a piece of speech ends, by its id; told whether it ended well. */
    private val endings = ConcurrentHashMap<String, (Boolean) -> Unit>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) = Unit
        override fun onDone(id: String?) = ended(id, true)

        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) = ended(id, false)
        override fun onError(id: String?, code: Int) = ended(id, false)
        override fun onStop(id: String?, interrupted: Boolean) = ended(id, false)
    }

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { engine?.language = Locale.ENGLISH }
                runCatching { engine?.setOnUtteranceProgressListener(listener) }
                waiting?.let(::say)
            }
            waiting = null
        }
    }

    private fun ended(id: String?, well: Boolean) {
        val ending = endings.remove(id ?: return) ?: return
        main.post { ending(well) }
    }

    fun say(words: String) {
        if (!ready) {
            // Asked before the engine has started: it is said as soon as it has.
            waiting = words
            return
        }
        runCatching { engine?.speak(words, TextToSpeech.QUEUE_ADD, null, words) }
    }

    /**
     * Says [words] in place of anything still being said, and comes back when the phone has finished
     * saying them, so that listening can start in the quiet after. Comes back at once on a phone with
     * no speech engine, and after [limitMs] whatever happens.
     */
    suspend fun sayAndWait(words: String, limitMs: Long = 8_000) {
        if (!ready) return
        val id = "wait-${counter.incrementAndGet()}"
        withTimeoutOrNull(limitMs) {
            suspendCancellableCoroutine { waiter ->
                endings[id] = { if (waiter.isActive) waiter.resume(Unit) }
                waiter.invokeOnCancellation { endings.remove(id) }
                val asked = runCatching { engine?.speak(words, TextToSpeech.QUEUE_FLUSH, null, id) }.getOrNull()
                if (asked != TextToSpeech.SUCCESS) {
                    endings.remove(id)
                    if (waiter.isActive) waiter.resume(Unit)
                }
            }
        }
        endings.remove(id)
    }

    /** Stops whatever is being said. */
    fun hush() {
        runCatching { engine?.stop() }
    }

    /**
     * Has the phone's speech engine say [words] into a sound file at [out] and not aloud. True when the
     * file was written. Used to give the speech model a spoken note to listen to when measuring it.
     */
    suspend fun toFile(words: String, out: File, limitMs: Long = 20_000): Boolean {
        if (!ready) return false
        out.delete()
        val id = "file-${counter.incrementAndGet()}"
        val written = withTimeoutOrNull(limitMs) {
            suspendCancellableCoroutine { waiter ->
                endings[id] = { well -> if (waiter.isActive) waiter.resume(well) }
                waiter.invokeOnCancellation { endings.remove(id) }
                val asked = runCatching { engine?.synthesizeToFile(words, Bundle(), out, id) }.getOrNull()
                if (asked != TextToSpeech.SUCCESS) {
                    endings.remove(id)
                    if (waiter.isActive) waiter.resume(false)
                }
            }
        } ?: false
        endings.remove(id)
        return written && out.length() > 1000
    }

    fun release() {
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}
