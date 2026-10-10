package com.techazsure.deskless.link

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

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

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { engine?.language = Locale.ENGLISH }
                waiting?.let(::say)
            }
            waiting = null
        }
    }

    fun say(words: String) {
        if (!ready) {
            // Asked before the engine has started: it is said as soon as it has.
            waiting = words
            return
        }
        runCatching { engine?.speak(words, TextToSpeech.QUEUE_ADD, null, words) }
    }

    fun release() {
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}
