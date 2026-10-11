package com.techazsure.deskless

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.techazsure.deskless.account.Api
import com.techazsure.deskless.account.ApiError
import com.techazsure.deskless.account.Place
import com.techazsure.deskless.account.PlaceKind
import com.techazsure.deskless.account.Profile
import com.techazsure.deskless.account.Sent
import com.techazsure.deskless.capture.Haptics
import com.techazsure.deskless.capture.WavRecorder
import com.techazsure.deskless.capture.normalizeJpeg
import com.techazsure.deskless.link.Voice
import com.techazsure.deskless.speech.Transcriber
import com.techazsure.deskless.structure.Ask
import com.techazsure.deskless.structure.Structurer
import com.techazsure.deskless.structure.UserAsks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * The app as someone who uses a place has it: a resident, a student, a visitor, a member of staff. They
 * pick the place, say what they noticed or want to know, and the models on the phone turn that into a
 * short entry and read it back. "Make it high", "it is on the third floor", "send it": the phone listens
 * after it has spoken, changes the entry, reads it again, and sends it only when told to. The place's
 * server takes it to one of the place's inspectors; whatever they answer, and each step until it is put
 * right, is said aloud here.
 *
 * Wording is done on the phone with no network. Sending needs one: without it the entry waits on the
 * phone and goes by itself when the network is back.
 */
class UserSide(
    context: Context,
    private val scope: CoroutineScope,
    private val modelWork: CoroutineDispatcher,
    private val transcriber: Transcriber,
    private val structurer: Structurer,
    private val voice: Voice,
    private val haptics: Haptics,
    private val api: Api,
    private val token: () -> String,
    private val who: () -> Profile?,
    private val language: () -> String,
    private val speechReady: () -> Boolean,
    private val gemmaReady: () -> Boolean,
    private val cacheDir: File,
    /** Hands an entry that found no network to the linked phones, and gives what to tell the person; null when there is nobody to hand it to. */
    private val handOver: suspend (fields: Map<String, String>, place: String, photo: File?) -> String? = { _, _, _ -> null },
    /** Where an entry handed to a linked phone has got, in words; null when it was not handed to one. */
    val handedLine: (clientId: String) -> String? = { null },
) {
    private val app = context.applicationContext
    private val settings = app.getSharedPreferences("user-side", Context.MODE_PRIVATE)
    private val folder = File(app.filesDir, "user").apply { mkdirs() }

    /** What the phone is doing with the person right now. */
    enum class Phase { IDLE, LISTENING, HEARING, WORDING, SPEAKING, SENDING }

    /** Something told and agreed that has not reached the place yet. */
    data class Queued(val owner: String, val clientId: String, val place: Place, val ask: Ask, val photo: String) {
        fun toJson(): JSONObject = JSONObject().put("owner", owner).put("client_id", clientId).put("place", place.toJson()).put("ask", ask.toJson()).put("photo", photo)

        companion object {
            fun from(json: JSONObject): Queued? {
                val place = json.optJSONObject("place")?.let(Place::from) ?: return null
                val ask = json.optJSONObject("ask")?.let(Ask::from) ?: return null
                return Queued(json.optString("owner"), json.optString("client_id"), place, ask, json.optString("photo"))
            }
        }
    }

    // ---------------------------------------------------------------- the place

    var kinds by mutableStateOf<List<PlaceKind>>(emptyList())
        private set
    var places by mutableStateOf<List<Place>>(emptyList())
        private set

    /** Why the places could not be fetched, when they could not. */
    var placesTrouble by mutableStateOf<String?>(null)
        private set

    /** The place things are sent to; it is remembered between one time and the next. */
    var place by mutableStateOf(runCatching { Place.from(JSONObject(settings.getString("place", "")!!)) }.getOrNull()?.takeIf { it.id.isNotBlank() })
        private set
    private var fetchingPlaces = false

    fun choose(chosen: Place?) {
        place = chosen
        settings.edit().putString("place", chosen?.toJson()?.toString().orEmpty()).apply()
    }

    fun refreshPlaces() {
        if (who()?.usesAPlace != true || token().isBlank() || fetchingPlaces) return
        fetchingPlaces = true
        scope.launch {
            val got = withContext(Dispatchers.IO) { runCatching { Place.listed(api.places(token())) } }
            fetchingPlaces = false
            got.onSuccess { (allKinds, all) ->
                kinds = allKinds
                places = all
                placesTrouble = null
                // A place that has shut its door since is no longer the one things go to.
                place?.let { now -> if (all.none { it.id == now.id }) choose(null) }
            }.onFailure { placesTrouble = why(it) }
        }
    }

    // ---------------------------------------------------------------- telling, hearing it back, changing it

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** How loud the microphone is right now, 0 to 1. */
    var level by mutableStateOf(0f)
        private set

    /** The entry as it stands, waiting for "send it"; null when nothing is being told. */
    var draft by mutableStateOf<Ask?>(null)
        private set

    /** The picture that goes with the entry, and a number above zero while there is one. */
    val photo = File(folder, "draft.jpg")
    var photoVersion by mutableStateOf(0)
        private set

    /** What the person last said, as the phone heard it, and what the phone last said. */
    var heard by mutableStateOf("")
        private set
    var said by mutableStateOf("")
        private set

    /** How long the model took over the last wording. */
    var seconds by mutableStateOf(0f)
        private set

    /** Something the person has to be told that is not part of the entry. */
    var trouble by mutableStateOf<String?>(null)
        private set

    /** Set when the person asks aloud to add a picture; the screen opens the camera and clears it. */
    var wantsCamera by mutableStateOf(false)

    /** Whether the phone reads back and listens by itself, or only shows and waits for a tap. */
    var speaks by mutableStateOf(settings.getBoolean("speaks", true))
        private set

    fun letSpeak(on: Boolean) {
        speaks = on
        settings.edit().putBoolean("speaks", on).apply()
        if (!on) {
            voice.hush()
            if (phase == Phase.SPEAKING) quiet()
        }
    }

    val busy: Boolean get() = phase != Phase.IDLE

    private val recorder = WavRecorder()
    private var ended = false
    private var given: String? = null
    private var work: Job? = null

    /** How often in a row the phone has listened by itself; a tap starts the count again. */
    private var rounds = 0

    /** The microphone button: starts listening, or, while listening, says the person has finished. */
    fun tell() {
        when (phase) {
            Phase.LISTENING -> ended = true
            Phase.IDLE, Phase.SPEAKING -> {
                quiet()
                rounds = 0
                work = scope.launch { listenAndTake(first = true) }
            }
            else -> Unit
        }
    }

    /** What the person typed in place of speaking. */
    fun type(words: String) {
        if (words.isBlank() || phase == Phase.WORDING || phase == Phase.SENDING || phase == Phase.HEARING) return
        quiet()
        rounds = 0
        work = scope.launch { take(words.trim()) }
    }

    /** For a test build on an emulator, which has no voice: [words] are taken as what was said. */
    fun pretend(words: String) {
        if (phase != Phase.LISTENING) return
        given = words
        ended = true
    }

    /** Stops whatever the phone is saying or hearing; the entry stays as it is. */
    fun quiet() {
        work?.cancel()
        work = null
        voice.hush()
        recorder.stop()
        level = 0f
        replyingTo = null
        if (phase != Phase.SENDING) phase = Phase.IDLE
    }

    fun discard() {
        quiet()
        clear()
    }

    private fun clear() {
        draft = null
        dropPhoto()
        heard = ""
        said = ""
        trouble = null
    }

    /** The person changes a part of the entry by hand. */
    fun edit(change: (Ask) -> Ask) {
        draft = draft?.let(change)
    }

    /** A picture just taken or chosen is at [from]: it is kept with the entry, upright and of a modest size. */
    fun addPhoto(from: File) {
        scope.launch {
            val kept = withContext(Dispatchers.IO) {
                runCatching {
                    from.copyTo(photo, overwrite = true)
                    normalizeJpeg(photo, 1600)
                }.isSuccess && photo.length() > 0
            }
            if (kept) photoVersion++ else trouble = "That picture could not be used. Try another."
        }
    }

    fun dropPhoto() {
        photo.delete()
        photoVersion = 0
    }

    /**
     * Opens the microphone and waits until the person has spoken and gone quiet, or says nothing, or it
     * has gone on too long. Gives what was said in English; blank when nothing was; null when the
     * microphone could not be opened.
     */
    private suspend fun hear(waitMs: Long, pauseMs: Long, limitMs: Long): String? {
        val file = File(cacheDir, "user-said.wav")
        val allowed = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!allowed || !recorder.start(file)) return null
        ended = false
        given = null
        phase = Phase.LISTENING
        haptics.listening()
        try {
            val started = SystemClock.elapsedRealtime()
            var spokeAt = 0L
            var lastLoud = 0L
            var floor = 1f
            while (!ended) {
                delay(80)
                val now = SystemClock.elapsedRealtime()
                val loud = recorder.level
                level = loud
                // The room's own noise is the quietest the microphone has been; a voice stands well above it.
                if (now - started > 200) floor = minOf(floor, loud)
                if (loud > maxOf(VOICE_LEVEL, floor * 3f)) {
                    if (spokeAt == 0L) spokeAt = now
                    lastLoud = now
                }
                val waited = now - started
                if (spokeAt == 0L && waited > waitMs) break
                if (spokeAt != 0L && now - lastLoud > pauseMs) break
                if (waited > limitMs) break
            }
        } finally {
            recorder.stop()
            level = 0f
        }
        haptics.heard()
        phase = Phase.HEARING
        given?.let { return it }
        // A speech model handed silence makes a sentence up: a recording with no voice in it is not given to it.
        if (withContext(Dispatchers.IO) { loudness(file) } < QUIET) return ""
        val code = language()
        return withContext(modelWork) { runCatching { transcriber.toEnglish(file, code) } }.getOrNull().orEmpty().trim()
    }

    private suspend fun listenAndTake(first: Boolean) {
        if (!speechReady()) {
            phase = Phase.IDLE
            if (first) trouble = "The speech model is not on this phone yet. Type it instead."
            return
        }
        val fresh = draft == null
        val words = hear(waitMs = if (first) 8_000 else 6_000, pauseMs = if (fresh) 2_000 else 1_500, limitMs = if (fresh) 45_000 else 20_000)
        when {
            words == null -> {
                phase = Phase.IDLE
                trouble = "The microphone could not be opened. Type it instead."
            }
            words.isBlank() -> {
                phase = Phase.IDLE
                // After a read-back, silence simply leaves the entry waiting for a tap.
                if (first) trouble = "Nothing was heard. Tap the microphone and say it again, a little closer to the phone."
            }
            else -> take(words)
        }
    }

    /** What was said or typed is acted on: the first telling makes the entry; after that it is an answer to the read-back. */
    private suspend fun take(words: String) {
        trouble = null
        heard = words
        val now = draft
        val target = place
        if (target == null) {
            phase = Phase.IDLE
            trouble = "Choose the place first: the kind of place, then its name."
            return
        }
        if (now == null) {
            phase = Phase.WORDING
            draft = word(words, null, target)
            readBack()
            return
        }
        when {
            UserAsks.wantsSend(words) -> send()
            UserAsks.wantsCancel(words) -> {
                clear()
                phase = Phase.IDLE
                speak("Dropped. Nothing was sent.")
            }
            UserAsks.onlyRefuses(words) -> speakThenListen("Not sent. Tell me what to change.")
            UserAsks.wantsPhoto(words) -> {
                phase = Phase.IDLE
                wantsCamera = true
            }
            else -> {
                phase = Phase.WORDING
                draft = UserAsks.quickChange(now, words) ?: word(words, now, target)
                readBack()
            }
        }
    }

    /**
     * The entry made from what was said, or [before] changed by it. The model on the phone words it and
     * [UserAsks] decides what of its answer is kept. With no model, or when it fails, the person's own
     * words stand.
     */
    private suspend fun word(words: String, before: Ask?, target: Place): Ask {
        val all = if (before == null) words else before.said + " " + words
        val reply = if (!gemmaReady()) null else withContext(modelWork) {
            runCatching {
                if (before == null) structurer.form(UserAsks.brief(target.kindName, target.name), "The person said: $words")
                else structurer.form(UserAsks.changeBrief(), "Draft:\n${UserAsks.lines(before)}\n\nThe person now says: $words")
            }.getOrNull()
        }
        seconds = if (reply != null) structurer.lastSeconds else 0f
        return when {
            reply != null -> UserAsks.fromModel(reply::optString, all, before)
            before != null -> before.copy(summary = (before.summary.trimEnd() + " " + words).trim(), said = all)
            else -> UserAsks.plain(words)
        }
    }

    /** Reads the entry back and, when the phone speaks, listens for what the person says to it. */
    private suspend fun readBack() {
        val now = draft ?: return
        speakThenListen(UserAsks.readBack(now, place?.name ?: "the place"))
    }

    private suspend fun speakThenListen(words: String) {
        said = words
        if (!speaks || !voice.ready || rounds >= MAX_ROUNDS) {
            // Shown, and left for a tap: in a noisy room the phone must not go on answering the room.
            phase = Phase.IDLE
            return
        }
        rounds++
        phase = Phase.SPEAKING
        voice.sayAndWait(words, limitMs = 30_000)
        // A moment for the room to go quiet, so the phone does not hear itself.
        delay(250)
        listenAndTake(first = false)
    }

    private fun speak(words: String) {
        said = words
        if (speaks) voice.say(words)
    }

    // ---------------------------------------------------------------- sending

    /** What is told and agreed but has not reached its place, the newest first. */
    val waiting = mutableStateListOf<Queued>()
    private val outboxFile = File(folder, "outbox.json")
    private var flushing = false

    /** The Send button. */
    fun sendNow() {
        if (draft == null || phase == Phase.SENDING || phase == Phase.WORDING) return
        quiet()
        work = scope.launch { send() }
    }

    private suspend fun send() {
        val now = draft ?: return
        val target = place ?: return
        val me = who() ?: return
        phase = Phase.SENDING
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val picture = if (photoVersion > 0 && photo.isFile) {
            withContext(Dispatchers.IO) { runCatching { File(folder, "out-$id.jpg").also { photo.copyTo(it, overwrite = true) } }.getOrNull() }
        } else {
            null
        }
        // Kept on the phone before anything is tried, so that nothing agreed is lost to a dropped connection.
        val queued = Queued(me.uniqueId, id, target, now, picture?.name.orEmpty())
        waiting.add(0, queued)
        keepOutbox()
        draft = null
        dropPhoto()
        heard = ""
        val outcome = push(queued)
        phase = Phase.IDLE
        speak(outcome)
    }

    /** Tries to bring one entry to its place; gives what to tell the person. */
    private suspend fun push(one: Queued): String {
        val picture = one.photo.takeIf { it.isNotBlank() }?.let { File(folder, it) }?.takeIf { it.isFile }
        val fields = mapOf(
            "place" to one.place.id, "kind" to one.ask.kind, "title" to one.ask.title, "note" to one.ask.summary.ifBlank { one.ask.said },
            "location" to one.ask.location, "category" to one.ask.category, "severity" to one.ask.severity.name, "client_id" to one.clientId,
        )
        val sentWith = token()
        val result = withContext(Dispatchers.IO) { runCatching { Sent.from(api.sendIn(sentWith, fields, picture)) } }
        result.onSuccess { row ->
            waiting.remove(one)
            keepOutbox()
            picture?.delete()
            if (who()?.uniqueId == one.owner) {
                sent.removeAll { it.id == row.id }
                sent.add(0, row)
            }
            return "Sent to ${row.sentTo.ifBlank { "the people" }} at ${row.place}. I will tell you when they answer."
        }
        val error = result.exceptionOrNull() as? ApiError
        // Only the DeskLess server's own no takes it off the phone; no network, or a wrong address, leaves it waiting.
        if (error != null && error.refused && error.status in setOf(404, 409, 413, 422, 429)) {
            waiting.remove(one)
            keepOutbox()
            picture?.delete()
            return "It was not sent. ${error.message}"
        }
        if (error?.status == 401) return "Sign in again and it will be sent."
        // With no network a linked phone can take it, sealed, and send it on. It also stays here, and goes
        // by itself if this phone finds a network first: the place takes it once, whichever way it comes.
        val handed = handOver(fields, one.place.name, picture)
        return "There is no network. " + (handed ?: "It will go to ${one.place.name} by itself when there is one.")
    }

    /** Sends whatever of this person's still waits; called when the app comes to the front or finds a network. */
    private fun flush() {
        val me = who() ?: return
        if (flushing || token().isBlank() || phase == Phase.SENDING) return
        val mine = waiting.filter { it.owner == me.uniqueId }
        if (mine.isEmpty()) return
        flushing = true
        scope.launch {
            var went = 0
            for (one in mine.reversed()) if (push(one).startsWith("Sent")) went++
            flushing = false
            if (went > 0) news = if (went == 1) "What was waiting has been sent." else "$went things that were waiting have been sent."
        }
    }

    private fun keepOutbox() {
        val list = JSONArray().apply { waiting.forEach { put(it.toJson()) } }
        runCatching { outboxFile.writeText(list.toString()) }
    }

    // ---------------------------------------------------------------- what was sent, and what comes back

    /** Everything this person has sent in, newest first, as the server last gave it. */
    val sent = mutableStateListOf<Sent>()

    /** The last thing that came back, shown at the top until the next. */
    var news by mutableStateOf("")
        private set
    private var fetching = false
    private var watching: Job? = null

    /** The thing sent in that the person is answering aloud right now, if any. */
    var replyingTo by mutableStateOf<String?>(null)
        private set

    private fun sentFile(): File = File(folder, "sent-${who()?.uniqueId.orEmpty()}.json")

    init {
        runCatching { JSONArray(outboxFile.readText()) }.getOrNull()?.let { list ->
            for (at in 0 until list.length()) list.optJSONObject(at)?.let(Queued::from)?.let(waiting::add)
        }
        runCatching { Sent.listed(JSONObject(sentFile().readText())) }.getOrNull()?.let(sent::addAll)
    }

    /**
     * Asks the server where everything stands. What the place wrote back, and each step taken since the
     * last look, is said aloud and then marked as heard. Left alone while the person is speaking or being
     * spoken to; the next look catches up.
     */
    fun refresh() {
        val me = who() ?: return
        if (!me.usesAPlace || token().isBlank() || fetching || busy) return
        fetching = true
        val asked = token()
        scope.launch {
            val got = withContext(Dispatchers.IO) { runCatching { api.sentIn(asked) } }
            fetching = false
            val answer = got.getOrNull() ?: return@launch
            // Asked for someone who has since handed the phone on, or the person began to speak meanwhile.
            if (asked != token() || busy) return@launch
            val now = Sent.listed(answer)
            val lines = Sent.news(sent.toList(), now)
            sent.clear()
            sent.addAll(now)
            withContext(Dispatchers.IO) { runCatching { sentFile().writeText(answer.toString()) } }
            if (lines.isEmpty()) return@launch
            news = lines.first().second + if (lines.size > 1) " And ${lines.size - 1} more." else ""
            haptics.filled()
            if (speaks) lines.take(3).forEach { voice.say(it.second) }
            for (id in lines.map { it.first }.filter { it.unheard.isNotEmpty() }.map { it.id }.distinct()) {
                withContext(Dispatchers.IO) { runCatching { api.heard(asked, id) } }
            }
        }
    }

    /** While the app is in front the phone looks every few seconds for what has come back. */
    fun watch(on: Boolean) {
        watching?.cancel()
        watching = null
        if (!on) {
            if (phase != Phase.SENDING) quiet()
            return
        }
        watching = scope.launch {
            while (true) {
                refresh()
                delay(LOOK_EVERY_MS)
            }
        }
    }

    /** The app has come to the front or found a network. */
    fun comeOnline() {
        if (who()?.usesAPlace != true) return
        flush()
        if (places.isEmpty()) refreshPlaces()
        refresh()
    }

    /** Adds a typed word to something sent in. */
    fun sayMore(id: String, words: String) {
        if (words.isBlank() || token().isBlank()) return
        scope.launch { post(id, words.trim()) }
    }

    /** Adds a spoken word to something sent in: listens, writes it down, sends it. */
    fun sayMoreAloud(id: String) {
        if (phase == Phase.LISTENING && replyingTo == id) {
            ended = true
            return
        }
        if (busy) return
        work = scope.launch {
            replyingTo = id
            val words = if (speechReady()) hear(waitMs = 8_000, pauseMs = 1_800, limitMs = 30_000) else null
            replyingTo = null
            phase = Phase.IDLE
            when {
                words == null -> trouble = "The microphone could not be used. Type it instead."
                words.isBlank() -> trouble = "Nothing was heard."
                else -> {
                    heard = words
                    post(id, words)
                }
            }
        }
    }

    private suspend fun post(id: String, words: String) {
        val asked = token()
        withContext(Dispatchers.IO) { runCatching { Sent.from(api.sayMore(asked, id, words)) } }
            .onSuccess { row ->
                val at = sent.indexOfFirst { it.id == row.id }
                if (at >= 0) sent[at] = row
                speak("Your word has gone to ${row.place}.")
            }
            .onFailure { trouble = why(it) }
    }

    /** The phone has changed hands: nothing told by one person is left for the next. */
    fun changedHands() {
        quiet()
        clear()
        news = ""
        sent.clear()
        runCatching { Sent.listed(JSONObject(sentFile().readText())) }.getOrNull()?.let(sent::addAll)
        if (who()?.usesAPlace == true) {
            refreshPlaces()
            refresh()
        }
    }

    private fun why(error: Throwable): String = (error as? ApiError)?.message ?: "Something went wrong. Try again."

    /** How loud a recording is at its loudest half second, as the root mean square of its samples (0 to 32768). */
    private fun loudness(wav: File): Double {
        val bytes = runCatching { wav.readBytes() }.getOrNull() ?: return 0.0
        val window = 8000 * 2
        var loudest = 0.0
        var at = 44
        while (at + window <= bytes.size) {
            var sum = 0.0
            for (i in at until at + window step 2) {
                val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
                sum += sample.toDouble() * sample
            }
            loudest = maxOf(loudest, sqrt(sum / (window / 2)))
            at += window
        }
        return loudest
    }

    companion object {
        /** Below this, a recording holds no voice worth giving to the speech model. */
        private const val QUIET = 80.0

        /** How loud the microphone has to be, 0 to 1, to count as someone speaking. */
        private const val VOICE_LEVEL = 0.03f

        /** How often the phone listens by itself before it waits for a tap. */
        private const val MAX_ROUNDS = 4
        private const val LOOK_EVERY_MS = 8_000L
    }
}
