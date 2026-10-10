package com.techazsure.deskless.structure

import com.techazsure.deskless.account.Desk
import com.techazsure.deskless.account.DeskJob
import com.techazsure.deskless.account.Person
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the model made of something said at the desk, as it gave it; [DeskCommands] checks every part. */
data class DeskAsk(
    val action: String,
    val jobs: List<Int> = emptyList(),
    val person: String = "",
    val due: String = "",
    val message: String = "",
)

/** What the app will do about something said at the desk. */
sealed interface DeskPlan {
    /**
     * One thing to do at the office about one job, shown before it is done. [kind] is "assign", "send",
     * "fixed" (a picture of the repair is taken first), "close" or "back" (a repair sent back).
     */
    data class Act(
        val number: Int,
        val job: DeskJob,
        val kind: String,
        val to: Person? = null,
        /** The day it is to be done by, as YYYY-MM-DD for the office and as words for the person; blank when none. */
        val dueOn: String = "",
        val dueWords: String = "",
        /** The words that go with it. */
        val text: String = "",
        /** What was asked for and will not be done, and why. */
        val leftOut: List<String> = emptyList(),
    ) : DeskPlan {
        /** The action in one line, for the screen and the voice. */
        val words: String
            get() = when (kind) {
                "assign" -> "Give job $number to ${to?.name}" + if (dueWords.isNotBlank()) ", due $dueWords" else ""
                "send" -> "Send job $number to ${to?.name}"
                "fixed" -> "Photograph the repair of job $number and mark it fixed"
                "close" -> "Accept the repair and close job $number"
                else -> "Send job $number back as not yet right"
            }
    }

    /** Say more about one job. */
    data class Read(val number: Int, val job: DeskJob) : DeskPlan

    /** Say again what is waiting. */
    data object Brief : DeskPlan

    /** Not an instruction: it is answered as a question about the desk. */
    data object Question : DeskPlan
    data class Refused(val why: String) : DeskPlan
}

/**
 * Spoken instructions about work that waits at the office. As with the walk's own instructions, the model
 * on the phone only fills a form (which action, which job, who, by when, what words) and everything else
 * is decided here: which job a number means, whether this person may do that, whether the person named is
 * one the office allows and was really named, what the date is. One job, one action, shown before it is
 * done; the office's server then checks it all again.
 */
object DeskCommands {
    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
    private val COUNTS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private val NAMES_A_JOB = setOf("job", "jobs", "issue", "issues", "number", "item", "task")
    private val BRIEF = Regex("""^(?:please\s+)?(?:brief me|what(?:'s| is) (?:waiting|on my desk|new)|what do i have|read (?:it|them|my desk|the list)(?: again)?|say (?:it|that) again|repeat(?: that| it)?|my desk)\b""")
    private val PRONOUNS = setOf("her", "him", "them", "he", "she", "they", "me", "us", "you", "someone", "somebody")
    private val ROLE_WORDS = mapOf(
        "manager" to "Manager", "director" to "Managing Director", "md" to "Managing Director", "boss" to "Managing Director",
        "administrator" to "Administrator", "admin" to "Administrator", "technician" to "Technical", "inspector" to "Inspector",
        "user" to "User", "resident" to "User", "customer" to "User", "sender" to "User",
    )

    /** How a sentence starts when it carries words for somebody, and the little words that may follow the name. */
    private val TELLING = Regex("""\b(?:tell|ask|answer|inform|message|remind|reply\s+to|say\s+to|let)\b""", RegexOption.IGNORE_CASE)
    private val AFTER_A_NAME = setOf("that", "know")
    private val SHORT_ANSWERS = setOf("yes", "no", "done", "okay", "ok", "thanks", "sorry")

    /** True for "brief me", "what is waiting" and the like, which need no model to understand. */
    fun wantsBrief(said: String): Boolean = BRIEF.containsMatchIn(said.trim().lowercase(Locale.ENGLISH))

    /**
     * The job numbers [said] names outright: "the first one", "job 2", "number three", "the last one" of
     * [count]. A number said for anything else is not one of them.
     */
    fun numbersIn(said: String, count: Int): List<Int> {
        val words = WORD.findAll(said.lowercase(Locale.ENGLISH)).map { it.value }.toList()
        val found = ArrayList<Int>()
        words.forEachIndexed { at, word ->
            val ordinal = ORDINALS.indexOf(word) + 1
            when {
                ordinal > 0 -> found.add(ordinal)
                word == "last" && count > 0 -> found.add(count)
                word in NAMES_A_JOB -> words.getOrNull(at + 1)?.let { next -> (next.toIntOrNull() ?: (COUNTS.indexOf(next) + 1).takeIf { it > 0 })?.let(found::add) }
            }
        }
        return found.distinct()
    }

    /**
     * The job [said] is about: the number the person said, else the only job there is, else the one the
     * model picked from a description. Null with a reason when it cannot be told or does not exist.
     */
    fun pick(ask: DeskAsk, said: String, desk: Desk): Pair<Pair<Int, DeskJob>?, String?> {
        val count = desk.jobs.size
        if (count == 0) return null to "Nothing is on your desk to do that with."
        val named = numbersIn(said, count).ifEmpty { if (count == 1) listOf(1) else ask.jobs.distinct() }
        val number = named.firstOrNull() ?: return null to "Say which one, by its number: \"the first one\" or \"job 2\"."
        if (number !in 1..count) return null to "There is no job $number: ${if (count == 1) "one is" else "$count are"} on your desk."
        return (number to desk.jobs[number - 1]) to null
    }

    /**
     * Works out the one thing to do. [technicians] are those this person may give work to (empty unless
     * they give out work); [sendTo] those the office lets them pass this job to, the likeliest first.
     */
    fun plan(
        ask: DeskAsk,
        said: String,
        desk: Desk,
        number: Int,
        job: DeskJob,
        technicians: List<Person> = emptyList(),
        sendTo: List<Person> = emptyList(),
        today: Date = Date(),
    ): DeskPlan {
        val leftOut = ArrayList<String>()
        // The model sometimes hands back the whole instruction as the message. What follows the
        // instruction's first comma or colon is then taken as the words, if anything does.
        val whole = ask.message.isNotBlank() && Grounding.share(said, ask.message) >= 0.99f
        val message = ask.message.trim().takeIf { it.isNotEmpty() }?.let { words ->
            when {
                whole -> said.substringAfter(',', "").ifBlank { said.substringAfter(':', "") }.trim().trimEnd('.')
                    .takeIf { it.split(' ').size >= 2 }?.replaceFirstChar { it.uppercase(Locale.ENGLISH) }
                Grounding.heard(words, said) -> words.replaceFirstChar { it.uppercase(Locale.ENGLISH) }
                else -> null.also { leftOut.add("The message \"$words\" was not in what you said.") }
            }
        }
        // The model may write a name out in full ("Arjun Das" for "Arjun"). Only the words of it that
        // were really said are used to find the person, so a name it added cannot choose anybody.
        // "Tell her", "ask him": that names nobody, and the model fills in who it takes it to be.
        val spoken = Grounding.words(said)
        val named = Grounding.keyWords(ask.person).filter { word -> word !in PRONOUNS && spoken.any { Grounding.slip(word, it) } }.joinToString(" ").takeIf { it.isNotEmpty() }
        // A name the model gave and nobody said is said so, unless it is the very person the job goes to anyway.
        fun unsaid(to: Person?) {
            val offered = ask.person.trim()
            if (named == null && offered.isNotEmpty() && Grounding.keyWords(offered).any { it !in PRONOUNS } && !(to != null && Grounding.heard(offered, to.name))) {
                leftOut.add("\"$offered\" was not in what you said.")
            }
        }
        // The action the office itself proposes for this job, carried out as it stands.
        val proposed = job.next
        val action = if (ask.action.startsWith("do")) {
            when (proposed.kind) {
                "assign" -> "assign"
                "send" -> "send"
                "fix" -> "fixed"
                "check" -> return DeskPlan.Refused("Job $number has a repair to look at. Say \"close job $number\" to accept it, or \"send job $number back\" with what is still wrong.")
                else -> return DeskPlan.Refused("Nothing is proposed for job $number right now: ${proposed.label}")
            }
        } else ask.action

        return when {
            action.startsWith("assign") -> {
                if (!desk.mayDirect) return DeskPlan.Refused("Only a manager or the managing director gives out work.")
                val to = when {
                    named != null -> match(named, technicians) ?: return DeskPlan.Refused(
                        if (technicians.isEmpty()) "You have no technician to give it to yet."
                        else "\"$named\" is not one of your technicians. They are: ${technicians.joinToString(", ") { it.name }}."
                    )
                    proposed.kind == "assign" && proposed.to.isNotBlank() -> Person(proposed.to, proposed.toName)
                    else -> return DeskPlan.Refused("Say who to give job $number to" + if (technicians.isEmpty()) "." else ": ${technicians.joinToString(", ") { it.name }}.")
                }
                val when_ = ask.due.trim().takeIf { it.isNotEmpty() && (Grounding.heard(it, said) || DueDates.asksForSpeed(said)) }
                val day = when_?.let { DueDates.resolve(it, today) }?.let { resolved -> DueDates.iso(resolved)?.let { it to resolved } }
                if (when_ != null && day == null) leftOut.add("\"$when_\" is not a day I can put a date to; the day it already has is kept.")
                val same = proposed.kind == "assign" && proposed.to == to.id
                unsaid(to)
                DeskPlan.Act(
                    number, job, "assign", to,
                    dueOn = day?.first ?: if (same) proposed.dueOn else "",
                    dueWords = day?.second ?: if (same) DueDates.words(proposed.dueOn) else "",
                    // The words drafted with the proposal name its person and its day: they go only with both.
                    text = message ?: if (same && (day == null || day.first == proposed.dueOn)) proposed.text else "",
                    leftOut = leftOut,
                )
            }
            action.startsWith("send") -> {
                val to = when {
                    named != null -> match(named, sendTo) ?: return DeskPlan.Refused(
                        if (sendTo.isEmpty()) "There is nobody you can send job $number to."
                        else "You cannot send job $number to \"$named\". It can go to: ${sendTo.joinToString(", ") { "${it.name} (${it.role})" }}."
                    )
                    proposed.kind == "send" && proposed.to.isNotBlank() -> sendTo.firstOrNull { it.id == proposed.to } ?: Person(proposed.to, proposed.toName)
                    else -> sendTo.firstOrNull() ?: return DeskPlan.Refused("There is nobody you can send job $number to.")
                }
                unsaid(to)
                // "Tell him the lift is serviced on Monday": the words for him are what follows his name,
                // whether the model picked them out, handed the whole sentence back, or gave none.
                val spokenWords = when {
                    whole -> wordsFor(said, to) ?: message
                    message != null -> wordsFor(message, to, fromItsStart = true) ?: message
                    else -> wordsFor(said, to)
                }
                val words = spokenWords ?: proposed.text.takeIf { proposed.kind == "send" && proposed.to == to.id && it.isNotBlank() }
                    ?: return DeskPlan.Refused(leftOut.firstOrNull() ?: "Say what to tell ${to.name}. For example: \"Tell ${to.name.substringBefore(' ')} \u2026\" and then your words.")
                DeskPlan.Act(number, job, "send", to, text = words, leftOut = leftOut)
            }
            action.startsWith("fixed") || action.startsWith("fix") -> {
                if (proposed.kind != "fix") return DeskPlan.Refused("Job $number is not yours to mark fixed: ${proposed.label}")
                DeskPlan.Act(number, job, "fixed", text = message.orEmpty(), leftOut = leftOut)
            }
            action.startsWith("close") -> {
                if (!desk.mayDirect) return DeskPlan.Refused("Only a manager or the managing director closes an issue.")
                DeskPlan.Act(number, job, "close", text = message.orEmpty(), leftOut = leftOut)
            }
            action.startsWith("back") -> {
                if (!desk.mayDirect) return DeskPlan.Refused("Only a manager or the managing director sends a repair back.")
                DeskPlan.Act(number, job, "back", text = message.orEmpty(), leftOut = leftOut)
            }
            action.startsWith("read") -> DeskPlan.Read(number, job)
            action.startsWith("brief") -> DeskPlan.Brief
            else -> DeskPlan.Question
        }
    }

    /**
     * The words meant for somebody, out of a sentence that also says who they are for: "tell him the lift
     * is serviced on Monday" gives "the lift is serviced on Monday", and "tell the manager that I need a
     * ladder" gives "I need a ladder". Null when the sentence does not name anybody that way, or says
     * nothing after the name.
     */
    fun wordsFor(said: String, to: Person?, fromItsStart: Boolean = false): String? {
        val verb = TELLING.find(said)?.takeIf { !fromItsStart || said.substring(0, it.range.first).isBlank() } ?: return null
        val tokens = said.substring(verb.range.last + 1).trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val name = to?.let { Grounding.words(it.name) }.orEmpty()
        fun bare(at: Int) = tokens.getOrNull(at)?.lowercase(Locale.ENGLISH)?.trim(',', ':', '.', ';').orEmpty()
        // A word that says who they are: part of their name, or what they are. Another role's name is somebody else.
        fun theirs(at: Int): Boolean {
            val word = bare(at)
            val role = if (word == "managing") "Managing Director" else ROLE_WORDS[word]
            return word.isNotEmpty() && (name.any { Grounding.slip(it, word) } || (role != null && (to == null || to.role.isBlank() || role.equals(to.role, ignoreCase = true))))
        }
        // "Send a message to Ravi that ...": the "to" before the name is no part of the words either.
        var at = if (bare(0) == "to") 1 else 0
        if (bare(at) == "the" && theirs(at + 1)) at++
        // "Tell him he is right": one word stands for the person, and the next "he" belongs to the message.
        if (bare(at) in PRONOUNS) at++ else {
            val from = at
            while (theirs(at)) at++
            if (at == from) return null
        }
        while (bare(at) in AFTER_A_NAME) at++
        val rest = tokens.drop(at)
        val first = rest.firstOrNull()?.lowercase(Locale.ENGLISH).orEmpty()
        val words = when {
            // "Tell him about job 2" says nothing to him; "ask him to come tomorrow" asks him to come tomorrow.
            first == "about" -> return null
            first == "to" && rest.size >= 2 -> listOf("please") + rest.drop(1)
            else -> rest
        }
        if (words.size < 2 && bare(at) !in SHORT_ANSWERS) return null
        return words.joinToString(" ").trim().trimEnd('.').replaceFirstChar { it.uppercase(Locale.ENGLISH) }
    }

    /**
     * The one of [people] that [said] names: by their name, a first name or a slip of spelling allowed, or
     * by what they are ("the manager"). Null when nobody fits or more than one does.
     */
    fun match(said: String, people: List<Person>): Person? {
        val wanted = Grounding.keyWords(said)
        if (wanted.isEmpty()) return null
        val byName = people.filter { person ->
            val words = Grounding.words(person.name)
            wanted.all { want -> words.any { Grounding.slip(it, want) } }
        }
        if (byName.size == 1) return byName.single()
        if (byName.size > 1) return null
        val role = wanted.firstNotNullOfOrNull { ROLE_WORDS[it] } ?: return null
        return people.filter { it.role.equals(role, ignoreCase = true) }.singleOrNull()
    }
}

private val RESOLVED = "d MMM yyyy"

/** A date as [DueDates.resolve] writes it ("16 Oct 2026") in the form the office takes ("2026-10-16"); null when it is not a date. */
fun DueDates.iso(resolved: String): String? = runCatching {
    val day = SimpleDateFormat(RESOLVED, Locale.ENGLISH).apply { isLenient = false }.parse(resolved.trim()) ?: return null
    SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(day)
}.getOrNull()

/** The office's form of a day ("2026-10-16") in words ("16 Oct 2026"); the text unchanged when it is not one. */
fun DueDates.words(iso: String): String = runCatching {
    SimpleDateFormat(RESOLVED, Locale.ENGLISH).format(SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { isLenient = false }.parse(iso.trim())!!)
}.getOrDefault(iso)
