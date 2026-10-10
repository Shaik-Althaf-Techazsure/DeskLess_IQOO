package com.techazsure.deskless.structure

import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Template
import com.techazsure.deskless.models.ModelStore
import com.techazsure.deskless.report.Verdict
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** The report fields worked out from a note. A null severity means "could not tell". */
data class Fields(
    val title: String,
    val location: String,
    val severity: Severity?,
    val owner: String,
    val dueDate: String,
    /** One sentence on what the picture shows, and whether that is the problem the note describes. */
    val seen: String = "",
    val check: Check = Check.NONE,
    /** One of the template's types, or blank when the model named none of them. */
    val category: String = "",
    /** An expiry date read off a pack in the picture, as printed; only asked for in medication audits. */
    val expiry: String = "",
)

/**
 * Turns the English note and the picture into report fields with Gemma 4 E2B running on the phone through
 * LiteRT-LM, and has the same model say whether the picture really shows what the note describes. The
 * engine is loaded on first use (several seconds) and kept.
 */
class Structurer(private val models: ModelStore, private val cacheRoot: File) {
    private var engine: Engine? = null

    /** "GPU" or "CPU" once the model is loaded; shown to the user. */
    @Volatile
    var backend: String? = null
        private set

    /** How long the last answer took, not counting the one-off loading of the model. */
    @Volatile
    var lastSeconds = 0f
        private set

    private var cpuOnly = false

    /**
     * [pictures] is one photo, or a few stills from one video. [readings] is what the phone's sensors
     * measured at the spot, one per line, or blank. Blocking and slow; call it off the main thread. One
     * call at a time.
     */
    @Synchronized
    fun structure(note: String, pictures: List<File>, template: Template, readings: String = ""): Fields {
        val shown = pictures.filter { it.isFile }.take(MAX_PICTURES)
        val measured = readings.isNotBlank()
        // The note comes last: it is what the fields are filled from, and the readings only back it up.
        val said = (if (measured) "Phone readings at the spot:\n$readings\n" else "") + "Spoken note: $note"
        // Parsing is inside the retry: a reply that is not the asked-for JSON is how a bad GPU shows itself.
        return withFallback {
            val reply = converse(instructions(shown.size, template, measured), shown, said, maxTokens = 280)
            parse(reply, checked = shown.isNotEmpty(), categories = template.categories)
        }
    }

    /**
     * Compares the picture of a reported problem with one taken after the repair and says whether the
     * problem is gone. Returns the verdict and one sentence on what the second picture shows.
     */
    @Synchronized
    fun verifyFix(problem: String, before: File, after: File): Pair<Check, String> = withFallback {
        val system = listOf(
            "Two photos of the same spot are attached. The first was taken when a problem was reported. The second was taken after the repair.",
            "Reply with a single JSON object and nothing else, with exactly these keys:",
            "\"after_shows\": one short sentence saying what the second photo shows.",
            "\"fixed\": \"yes\" if the reported problem is no longer visible in the second photo, \"no\" if it is still there, \"unsure\" if you cannot tell or the photos are not of the same spot.",
            "Judge only from the photos.",
        ).joinToString("\n")
        val reply = converse(system, listOf(before, after), "Reported problem: $problem", maxTokens = 140)
        val json = jsonIn(reply)
        val verdict = json.optString("fixed").trim().lowercase(Locale.ENGLISH)
        val check = when {
            verdict.startsWith("yes") -> Check.MATCH
            NO.containsMatchIn(verdict) -> Check.MISMATCH
            else -> Check.UNSURE
        }
        check to json.optString("after_shows").trim().replaceFirstChar { it.uppercase(Locale.ENGLISH) }
    }

    /**
     * Writes the few lines a busy manager reads first: the overall state and what to do first. [issues] is
     * one line per open issue. [first] are the numbers of the issues to act on first, already in order:
     * which ones and in what order is the caller's decision, and the model only words each line.
     */
    @Synchronized
    fun brief(issues: String, first: List<Int>, template: Template): String = withFallback {
        val keys = first.joinToString(", ") { "\"issue_$it\"" }
        val system = listOf(
            "You brief the person in charge after the walk-round of ${template.reporter}.",
            "Reply with a single JSON object and nothing else, with exactly these keys:",
            "\"overall\": one sentence on the overall state of what was found, at most 25 words.",
            "$keys: for each of those issues, one short line saying what to do and who should do it, at most 18 words.",
            "Use only the facts given. Do not invent names, places or dates.",
            template.caution,
        ).filter { it.isNotBlank() }.joinToString("\n")
        val json = jsonIn(converse(system, emptyList(), "Open issues:\n$issues", maxTokens = 240))
        val overall = json.optString("overall").trim()
        require(overall.length > 10) { "The briefing came back empty." }
        val lines = first.mapNotNull { number ->
            json.optString("issue_$number").trim().takeIf { it.isNotEmpty() }?.let { "Issue $number: $it" }
        }
        (listOf(overall) + if (lines.isEmpty()) emptyList() else listOf("Do first:") + lines).joinToString("\n")
    }

    /**
     * Reads the evidence of a whole report and says whether it hangs together, with one sentence of reason.
     * The caller holds the answer against its own fixed checks; the model does not decide alone.
     */
    @Synchronized
    fun review(evidence: String, template: Template): Pair<Verdict, String> = withFallback {
        val system = listOf(
            "You audit the walk-round report of ${template.reporter} before it is sent on. You are given what the app checked and one line per issue.",
            "Reply with a single JSON object and nothing else, with exactly these keys:",
            "\"verdict\": \"genuine\" if the evidence hangs together, \"doubtful\" if something does not fit and a person should look, \"fake\" if pictures or readings plainly contradict the notes.",
            "\"reason\": one sentence, at most 25 words, naming the issue numbers that decided it.",
            "Use only the facts given. A line marked PROBLEM means the verdict cannot be \"genuine\".",
        ).joinToString("\n")
        val json = jsonIn(converse(system, emptyList(), evidence, maxTokens = 120))
        val said = json.optString("verdict").trim().lowercase(Locale.ENGLISH)
        val verdict = when {
            said.startsWith("genuine") -> Verdict.GENUINE
            said.startsWith("fake") -> Verdict.FAKE
            else -> Verdict.DOUBTFUL
        }
        verdict to json.optString("reason").trim().replaceFirstChar { it.uppercase(Locale.ENGLISH) }
    }

    private fun <T> withFallback(work: () -> T): T =
        try {
            work()
        } catch (e: Exception) {
            if (backend != "GPU") throw e
            // Some phone GPUs return garbled text. The CPU is slower but dependable, so use it from now on.
            Log.w("DeskLess", "Gemma failed on the GPU; retrying on the CPU", e)
            release()
            cpuOnly = true
            work()
        }

    /** One question, one answer, in a fresh conversation so nothing leaks from one issue into the next. */
    private fun converse(system: String, pictures: List<File>, text: String, maxTokens: Int): String {
        val engine = engine ?: load().also { engine = it }
        val started = SystemClock.elapsedRealtime()
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.1),
            maxOutputToken = maxTokens,
        )
        engine.createConversation(config).use { conversation ->
            val parts = ArrayList<Content>()
            pictures.forEach { parts.add(Content.ImageFile(it.absolutePath)) }
            parts.add(Content.Text(text))
            val reply = conversation.sendMessage(Contents.of(parts))
                .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            lastSeconds = (SystemClock.elapsedRealtime() - started) / 1000f
            Log.d("DeskLess", "Gemma ($backend) replied in $lastSeconds s: $reply")
            return reply
        }
    }

    @Synchronized
    fun release() {
        engine?.close()
        engine = null
        backend = null
    }

    private fun load(): Engine {
        val model = models.gemma ?: error("The Gemma model file is not on this phone.")
        // GPU first where the phone has OpenCL; CPU always works and is the fallback.
        val choices = buildList {
            if (!cpuOnly && OPENCL_PATHS.any { File(it).exists() }) add("GPU")
            add("CPU")
        }
        var failure: Throwable? = null
        for (choice in choices) {
            try {
                fun make(): Backend = if (choice == "GPU") Backend.GPU() else Backend.CPU()
                val loaded = Engine(
                    EngineConfig(
                        modelPath = model.absolutePath,
                        backend = make(),
                        visionBackend = make(),
                        maxNumTokens = 4096,
                        maxNumImages = MAX_PICTURES,
                        cacheDir = File(cacheRoot, "litertlm-${choice.lowercase(Locale.US)}").apply { mkdirs() }.absolutePath,
                    )
                )
                loaded.initialize()
                backend = choice
                return loaded
            } catch (e: Throwable) {
                failure = e
            }
        }
        throw IllegalStateException("The model could not be loaded: ${failure?.message}", failure)
    }

    private fun instructions(pictures: Int, template: Template, measured: Boolean): String {
        val picture = when (pictures) {
            0 -> "There is no photo; answer the two photo keys with \"\" and \"unsure\"."
            1 -> "One photo of the spot is attached."
            else -> "The $pictures photos attached are stills from one short video of the same spot; treat them as one view."
        }
        val types = template.categories.joinToString(", ") { "\"$it\"" }
        // Built line by line: the template's own sentences are long and would break an indented block.
        return listOf(
            "You turn the spoken note and photo of ${template.reporter} into one report entry, and you check the photo against the note. $picture",
            "Reply with a single JSON object and nothing else, with exactly these keys:",
            "\"issue\": a short title for the problem, at most 8 words.",
            "\"category\": the one that fits best of $types.",
            "\"location\": where it is, as the note says, or \"\".",
            "\"severity\": one of \"Low\", \"Medium\", \"High\", \"Critical\".",
            "\"owner\": the person, team or department the note says must deal with it, or \"\".",
            "\"due_date\": the deadline exactly as the note says it, such as \"Monday\", \"tomorrow\" or \"15 October\", or \"\".",
            "\"photo_shows\": one short sentence saying what the photo actually shows.",
            "\"photo_matches\": \"yes\" if the photo shows the problem the note describes, \"no\" if it clearly shows something else, \"unsure\" if you cannot tell from the photo.",
            if (template.readsExpiry && pictures > 0) {
                "\"expiry_seen\": the expiry date printed on the pack in the photo, copied exactly as printed, or \"\" if you cannot read one."
            } else {
                ""
            },
            "Use only what the note says and what the photo shows. Never invent a name, place or date.",
            if (measured) {
                "The phone readings were measured at the spot by the phone's own sensors. Let them support the title and the severity: a dark or dim light reading supports a lighting problem, a slope reading an uneven surface, a loud noise reading a noise hazard. Never invent a reading or change its number. A reading cannot be seen in a photo: answer \"photo_shows\" and \"photo_matches\" from the photo alone."
            } else {
                ""
            },
            "Severity guide: ${template.severityGuide}",
            template.caution,
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    companion object {
        /** Stills sent to the model for a video; each costs time, and three cover a short clip. */
        const val MAX_PICTURES = 3

        private val OPENCL_PATHS = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
        )
        private val EMPTY_WORDS = setOf("null", "none", "unknown", "n/a", "not specified", "not mentioned")
        private val NO = Regex("""^no\b""")

        /** The JSON object in a reply, whatever text or code fence the model wrapped round it. */
        private fun jsonIn(reply: String): JSONObject {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            require(start in 0 until end) { "The model did not answer in the form asked for." }
            return JSONObject(reply.substring(start, end + 1))
        }

        /**
         * Reads the model's reply, tolerating text or code fences around the JSON object. [checked] is
         * false when no picture was sent, so there is nothing to have compared.
         */
        fun parse(reply: String, checked: Boolean = true, categories: List<String> = emptyList()): Fields {
            val json = jsonIn(reply)
            fun text(key: String): String =
                json.optString(key).trim().takeUnless { it.lowercase(Locale.ENGLISH) in EMPTY_WORDS }.orEmpty()
                    .replaceFirstChar { it.uppercase(Locale.ENGLISH) }
            val verdict = text("photo_matches").lowercase(Locale.ENGLISH)
            // Keep the type only if it is one of the template's own, however the model cased it.
            val said = text("category")
            val category = categories.firstOrNull { it.equals(said, ignoreCase = true) }
                ?: categories.firstOrNull { said.isNotEmpty() && (it.contains(said, ignoreCase = true) || said.contains(it, ignoreCase = true)) }
                ?: ""
            return Fields(
                category = category,
                expiry = if (checked) json.optString("expiry_seen").trim() else "",
                title = text("issue"),
                location = text("location"),
                severity = Severity.entries.firstOrNull { it.label.equals(text("severity"), ignoreCase = true) },
                owner = text("owner"),
                dueDate = DueDates.resolve(text("due_date")),
                seen = if (checked) text("photo_shows") else "",
                check = when {
                    !checked -> Check.NONE
                    verdict.startsWith("yes") -> Check.MATCH
                    // "no", "no." or "no, it shows…" but not "not sure".
                    NO.containsMatchIn(verdict) -> Check.MISMATCH
                    else -> Check.UNSURE
                },
            )
        }
    }
}
