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
    /** The deadline as the model copied it from the note ("Monday"), before [dueDate] was worked out from it. */
    val dueSaid: String = "",
)

/** What the model made of a spoken instruction, as it gave it; [Commands] checks every part before anything is done. */
data class Command(
    val action: String,
    val issues: List<Int> = emptyList(),
    val severity: String = "",
    val owner: String = "",
    val due: String = "",
    val location: String = "",
    val category: String = "",
    val show: String = "",
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
     * Reads the inspector's spoken [answer] to a [question] the app asked about an issue, and gives back
     * only the place, the person and the deadline the answer names. The caller checks each value against
     * the answer before using it.
     */
    @Synchronized
    fun followUp(question: String, answer: String, template: Template): Fields = withFallback {
        val system = listOf(
            "${template.reporter.replaceFirstChar { it.uppercase(Locale.ENGLISH) }} was asked a follow-up question about one problem and answered aloud. Take from the answer only what it says.",
            "Reply with a single JSON object and nothing else, with exactly these keys:",
            "\"location\": where the problem is, as the answer says, or \"\".",
            "\"owner\": the person, team or department the answer says must deal with it, or \"\".",
            "\"due_date\": the deadline exactly as the answer says it, such as \"Monday\", \"tomorrow\" or \"15 October\", or \"\".",
            "If the answer does not say one of them, or says it is not known, use \"\". Never invent a name, place or date.",
        ).joinToString("\n")
        parse(converse(system, emptyList(), "Question: $question\nAnswer: $answer", maxTokens = 120), checked = false)
    }

    /**
     * Turns a spoken instruction about the walk into one of the few things the app can do. [issues] is
     * one line per issue. The model only picks the action and copies the values out of [said]; whether
     * the issue exists, whether each value was really said and what the change comes to is the caller's
     * to check, and nothing is changed until the person agrees.
     */
    @Synchronized
    fun command(said: String, issues: String, template: Template): Command = withFallback {
        val types = template.categories.joinToString(", ") { "\"$it\"" }
        val system = listOf(
            "You turn a spoken instruction about the walk-round of ${template.reporter} into one action for the app. You are given one line per issue and the instruction.",
            "Reply with a single JSON object and nothing else, with exactly these keys:",
            "\"action\": \"change\" to edit issues, \"show\" to list only some issues, \"open\" to open one issue, \"question\" if the instruction asks something and changes nothing.",
            "\"issues\": the numbers of the issues the instruction is about, as a list such as [3] or [1, 4]. If it names them by what or where they are, give the numbers of the issues in the list that fit. [] if it names none.",
            "\"severity\": the new severity if the instruction sets one, one of \"Low\", \"Medium\", \"High\", \"Critical\"; otherwise \"\".",
            "\"owner\": the person or team the instruction gives the issues to, exactly as said; otherwise \"\".",
            "\"due_date\": the new deadline exactly as said, such as \"Friday\" or \"tomorrow\"; otherwise \"\".",
            "\"location\": the new location if the instruction sets one; otherwise \"\".",
            "\"category\": the new type if the instruction sets one, one of $types; otherwise \"\".",
            "\"show\": for the action \"show\" only, which issues to list: one of \"all\", \"open\", \"fixed\", \"needs a look\", \"critical\", \"high\", \"medium\", \"low\"; otherwise \"\".",
            "Copy only what the instruction says. Never invent a name, place or date, and leave a key \"\" when the instruction does not set it.",
        ).joinToString("\n")
        val json = jsonIn(converse(system, emptyList(), "$issues\n\nInstruction: $said", maxTokens = 150))
        fun text(key: String): String = json.optString(key).trim().takeUnless { it.lowercase(Locale.ENGLISH) in EMPTY_WORDS }.orEmpty()
        val numbers = json.optJSONArray("issues")?.let { list -> (0 until list.length()).mapNotNull { list.optString(it).trim().toIntOrNull() } }
            ?: listOfNotNull(json.optString("issues").trim().toIntOrNull())
        Command(
            action = text("action").lowercase(Locale.ENGLISH),
            issues = numbers,
            severity = text("severity"),
            owner = text("owner"),
            due = text("due_date"),
            location = text("location"),
            category = text("category"),
            show = text("show").lowercase(Locale.ENGLISH),
        )
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

    /**
     * Answers a question about the walk from its issues alone, briefly enough to be said aloud. [issues]
     * is one line per issue. What the issues do not hold, the model is told to say it cannot see.
     */
    @Synchronized
    fun answer(question: String, issues: String, template: Template): String = withFallback {
        val system = listOf(
            "You answer a question about the walk-round of ${template.reporter}. You are given the site and one line per issue found on it.",
            "Reply with a single JSON object and nothing else, with exactly this key:",
            "\"answer\": the answer in one to three short sentences, as you would say it aloud. Name the issue numbers you mean.",
            "Use only the issues given. If they do not hold the answer, say that this walk does not show it. Do not invent names, places or dates.",
            // Asked what to fix first, the model on the phone said it "depends on the priority". The priority is the severity it is given.
            "When asked what to do or fix first, go by severity among the issues whose status is open: Critical before High, High before Medium, Medium before Low. Name the issues of the highest level by number and say what each is.",
            template.caution,
        ).filter { it.isNotBlank() }.joinToString("\n")
        val json = jsonIn(converse(system, emptyList(), "$issues\n\nQuestion: $question", maxTokens = 170))
        val said = json.optString("answer").trim()
        require(said.length > 3) { "The answer came back empty." }
        said
    }

    /**
     * Words the spoken briefing on what waits at the office. [jobs] is one numbered line per job, already
     * in the order the office ranks them: which jobs and in what order is not the model's to decide.
     * [waiting] is how many of them need this person.
     */
    @Synchronized
    fun deskBrief(jobs: String, name: String, role: String, waiting: Int): String = withFallback {
        val system = listOf(
            "You are the voice of the DeskLess app, telling $name, who works as $role, what waits for them at work. You are given the jobs, numbered, most pressing first.",
            "Reply with a single JSON object and nothing else, with exactly this key:",
            "\"briefing\": what to say aloud, in at most four short sentences. Greet $name by name, say that $waiting ${if (waiting == 1) "job needs" else "jobs need"} them, then take the first one or two in order: its number, what it is, where, and what there is to do about it.",
            "Use only the facts given, in the order given. Do not invent names, places, dates or advice on how to repair anything, and add no closing remark.",
        ).joinToString("\n")
        val said = jsonIn(converse(system, emptyList(), jobs, maxTokens = 190)).optString("briefing").trim()
        require(said.length > 20) { "The briefing came back empty." }
        said
    }

    /**
     * Turns something said about the work waiting at the office into a form: which of a few actions, which
     * job, who, by when, with what words. [jobs] is one numbered line per job. [DeskCommands] checks every
     * part of the form, and nothing is done until the person agrees.
     */
    @Synchronized
    fun deskAsk(said: String, jobs: String, role: String): DeskAsk = withFallback {
        val system = listOf(
            "You turn what a person who works as $role says to the DeskLess app, about the jobs waiting for them, into one action. You are given the jobs, numbered, and what was said.",
            "Reply with a single JSON object and nothing else, with exactly these keys:",
            "\"action\": \"do\" to carry out what is suggested for a job as it stands (\"do it\", \"go ahead with the first one\"); \"assign\" to give a job to a technician; \"send\" to pass a job to someone with a message, or to answer someone; \"fixed\" when the speaker says they have repaired a job; \"close\" to accept a repair and close a job; \"back\" to send a repair back as not good enough; \"read\" to hear more about one job; \"brief\" to hear the list of what is waiting; \"question\" for anything else that asks something.",
            "\"jobs\": the numbers of the jobs meant, as a list such as [1]. If a job is named by what or where it is, give its number from the list. [] if none is named.",
            "\"person\": the person or role it is to be given or sent to, exactly as said; otherwise \"\".",
            "\"due_date\": a deadline exactly as said, such as \"Friday\" or \"tomorrow\"; otherwise \"\".",
            "\"message\": the words to pass on, exactly as said, without the name of the person they are for; otherwise \"\".",
            "Copy only what was said. Never invent a name, a date or a message, and leave a key \"\" when it was not said.",
        ).joinToString("\n")
        val json = jsonIn(converse(system, emptyList(), "$jobs\n\nSaid: $said", maxTokens = 150))
        fun text(key: String): String = json.optString(key).trim().takeUnless { it.lowercase(Locale.ENGLISH) in EMPTY_WORDS }.orEmpty()
        val numbers = json.optJSONArray("jobs")?.let { list -> (0 until list.length()).mapNotNull { list.optString(it).trim().toIntOrNull() } }
            ?: listOfNotNull(json.optString("jobs").trim().toIntOrNull())
        DeskAsk(action = text("action").lowercase(Locale.ENGLISH), jobs = numbers, person = text("person"), due = text("due_date"), message = text("message"))
    }

    /** Answers a question about the work waiting at the office from the numbered [jobs] alone, briefly enough to be said aloud. */
    @Synchronized
    fun deskAnswer(question: String, jobs: String, role: String): String = withFallback {
        val system = listOf(
            "You answer a question from a person who works as $role about the jobs waiting for them. You are given one numbered line per job, most pressing first.",
            "Reply with a single JSON object and nothing else, with exactly this key:",
            "\"answer\": the answer in one to three short sentences, as you would say it aloud. Name the job numbers you mean.",
            "Use only the jobs given. If they do not hold the answer, say that the desk does not show it. Do not invent names, places or dates, and give no advice on how to repair anything.",
        ).joinToString("\n")
        val said = jsonIn(converse(system, emptyList(), "$jobs\n\nQuestion: $question", maxTokens = 170)).optString("answer").trim()
        require(said.length > 3) { "The answer came back empty." }
        said
    }

    /**
     * One question to the model under the brief [system], answered as a JSON object. For forms whose
     * wording and checking are kept beside what they are for (see [UserAsks]).
     */
    @Synchronized
    fun form(system: String, text: String, maxTokens: Int = 240): JSONObject = withFallback { jsonIn(converse(system, emptyList(), text, maxTokens)) }

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
                dueSaid = text("due_date"),
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
