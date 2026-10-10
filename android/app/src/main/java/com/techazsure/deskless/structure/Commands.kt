package com.techazsure.deskless.structure

import com.techazsure.deskless.Filter
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Template
import java.util.Date
import java.util.Locale

/** One change a spoken instruction asks for, shown to the person before it is made. */
data class Change(
    /** The issue's number on the walk, from 1. */
    val number: Int,
    val issueId: String,
    /** Which field, by its [Field] name, and what a person calls it. */
    val field: String,
    val label: String,
    val from: String,
    val to: String,
)

/** What the app will do about a spoken instruction. */
sealed interface Plan {
    /** Changes that wait for the person's yes. [leftOut] says what was asked for and will not be done, and why. */
    data class Changes(val list: List<Change>, val leftOut: List<String> = emptyList()) : Plan

    /** List only some of the issues. [words] says which, for the screen and the voice. */
    data class Show(val filter: Filter, val words: String) : Plan
    data class Open(val number: Int, val issueId: String) : Plan

    /** Start a new issue with the camera. */
    data object Capture : Plan

    /** Not an instruction after all: it is answered as a question. */
    data object Question : Plan
    data class Refused(val why: String) : Plan
}

/**
 * Spoken instructions. The model on the phone reads the instruction and fills a form: which action, which
 * issues, which values. Everything after that is decided here, in plain code: whether those issues exist,
 * whether each value was really said, what the date is, and what exactly will change. Nothing changes
 * until the person has seen that list and agreed.
 */
object Commands {
    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val POLITE = Regex("""^(?:\s*(?:please|ok|okay|hey|deskless|desk less|can you|could you|will you|would you|kindly)\b[\s,]*)+""")
    private val ASKING = setOf(
        "what", "whats", "which", "who", "whom", "whose", "when", "where", "why", "how", "is", "are", "was", "were", "does", "do", "did",
        "can", "could", "should", "will", "would", "has", "have", "any", "anything", "tell",
    )
    private val VERB = Regex("""\b(?:mark|set|make|change|assign|reassign|give|hand|move|put|update|shift|rename|show|filter|list only|open|go to)\b""")
    private val CAPTURE = Regex("""\b(?:new issue|add (?:an? |one more |another )?(?:new )?issue|another issue|take (?:a |another )?(?:photo|picture)|open (?:the )?camera|start (?:the )?camera)\b""")
    private val COUNTS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    )
    private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth")
    private val LEVEL_WORDS = mapOf(
        Severity.CRITICAL to listOf("critical", "emergency"),
        Severity.HIGH to listOf("high", "serious", "urgent", "major"),
        Severity.MEDIUM to listOf("medium", "moderate", "normal"),
        Severity.LOW to listOf("low", "minor", "small"),
    )

    private fun small(said: String): String = said.lowercase(Locale.ENGLISH).replace(POLITE, "").trim()

    /** True when [said] reads as something to do, not something to answer. Decides only which way it is sent first. */
    fun looksLikeOne(said: String): Boolean {
        val text = small(said)
        val first = WORD.find(text)?.value ?: return false
        if (first in ASKING || text.endsWith("?")) return false
        return VERB.containsMatchIn(text) || CAPTURE.containsMatchIn(text)
    }

    /** True for "new issue", "take a picture" and the like: these need no model to understand. */
    fun wantsCapture(said: String): Boolean = small(said).let { text ->
        CAPTURE.containsMatchIn(text) && WORD.find(text)?.value !in ASKING
    }

    /**
     * The issue numbers [said] names outright: "issue 3", "issues two and five", "the first issue", "the
     * last issue" of [count]. A number said for anything else ("in 2 days") is not one of them.
     */
    fun numbersIn(said: String, count: Int = 0): List<Int> {
        val words = WORD.findAll(said.lowercase(Locale.ENGLISH)).map { it.value }.toList()
        fun number(word: String): Int? = word.toIntOrNull() ?: (COUNTS.indexOf(word) + 1).takeIf { it > 0 }
        val found = ArrayList<Int>()
        words.forEachIndexed { at, word ->
            if (word != "issue" && word != "issues" && word != "number" && word != "numbers") return@forEachIndexed
            // "the second issue", "the last issue".
            words.getOrNull(at - 1)?.let { before ->
                (ORDINALS.indexOf(before) + 1).takeIf { it > 0 }?.let(found::add)
                if (before == "last" && count > 0) found.add(count)
            }
            // "issue 3", "issues 2, 4 and five".
            var next = at + 1
            while (next < words.size) {
                val value = number(words[next])
                if (value != null) found.add(value) else if (words[next] != "and" && words[next] != "number") break
                next++
            }
        }
        return found.distinct()
    }

    /**
     * Works out what will be done. [command] is the model's reading of [said]; [issues] is the walk in its
     * own order. A value the model gives that was not in what was said is left out and named in the plan.
     */
    fun plan(
        command: Command,
        said: String,
        issues: List<Issue>,
        template: Template,
        memory: Memory? = null,
        site: String = "",
        today: Date = Date(),
    ): Plan {
        val action = command.action
        if (action.startsWith("question")) return Plan.Question
        if (action.startsWith("show")) return show(command, template) ?: Plan.Refused("I did not catch which issues to show. Try: \"Show the open ones\" or \"Show the critical ones\".")

        // Numbers the person said outright are taken from their words; only a description is left to the model.
        val named = numbersIn(said, issues.size).ifEmpty { command.issues.distinct() }
        val missing = named.filter { it !in 1..issues.size }
        val numbers = named.filter { it in 1..issues.size }
        val noSuch = missing.joinToString(" or ") { "issue $it" }.takeIf { it.isNotEmpty() }
            ?.let { "There is no $it: this walk has ${issues.size} issue${if (issues.size == 1) "" else "s"}." }

        if (action.startsWith("open")) {
            return when {
                numbers.size == 1 -> Plan.Open(numbers[0], issues[numbers[0] - 1].id)
                else -> Plan.Refused(noSuch ?: "Say which issue to open, by its number.")
            }
        }
        if (!action.startsWith("change")) return Plan.Question
        if (numbers.isEmpty()) return Plan.Refused(noSuch ?: "Say which issue, by its number. For example: \"Mark issue 2 high and give it to Ravi.\"")

        val leftOut = ArrayList<String>()
        noSuch?.let(leftOut::add)

        val severity = Severity.entries.firstOrNull { it.label.equals(command.severity, ignoreCase = true) }?.takeIf { level ->
            val words = Grounding.words(said)
            LEVEL_WORDS.getValue(level).any { it in words }.also { heard ->
                if (!heard) leftOut.add("Severity ${level.label}: you did not name a level.")
            }
        }
        val owner = command.owner.takeIf { it.isNotBlank() }?.let { name ->
            if (Grounding.heard(name, said)) memory?.person(name) ?: name.replaceFirstChar { it.uppercase(Locale.ENGLISH) }
            else null.also { leftOut.add("${template.ownerLabel} \"$name\" was not in what you said.") }
        }
        val due = command.due.takeIf { it.isNotBlank() }?.let { phrase ->
            val date = DueDates.resolve(phrase, today)
            if (Grounding.heard(phrase, said) || date == DueDates.AS_SOON_AS_POSSIBLE && DueDates.asksForSpeed(said)) date
            else null.also { leftOut.add("Deadline \"$phrase\" was not in what you said.") }
        }
        val location = command.location.takeIf { it.isNotBlank() }?.let { place ->
            if (Grounding.heard(place, said)) memory?.place(place, site) ?: place.replaceFirstChar { it.uppercase(Locale.ENGLISH) }
            else null.also { leftOut.add("Location \"$place\" was not in what you said.") }
        }
        val category = command.category.takeIf { it.isNotBlank() }?.let { type ->
            template.categories.firstOrNull { it.equals(type, ignoreCase = true) }?.takeIf { Grounding.heard(it, said) }
        }

        val changes = numbers.flatMap { number ->
            val issue = issues[number - 1]
            listOfNotNull(
                severity?.let { Change(number, issue.id, Field.SEVERITY, "Severity", issue.severity.label, it.label) },
                owner?.let { Change(number, issue.id, Field.OWNER, template.ownerLabel, issue.owner, it) },
                due?.let { Change(number, issue.id, Field.DUE, "Due", issue.dueDate, it) },
                location?.let { Change(number, issue.id, Field.LOCATION, "Location", issue.location, it) },
                category?.let { Change(number, issue.id, Field.CATEGORY, "Type", issue.category, it) },
            ).filter { it.from != it.to }
        }
        if (changes.isEmpty()) {
            return Plan.Refused(
                leftOut.firstOrNull()
                    ?: if (severity != null || owner != null || due != null || location != null || category != null) "That is how it already stands: nothing to change."
                    else "I heard which issue, but not what to change. For example: \"Mark issue 2 high and give it to Ravi.\""
            )
        }
        return Plan.Changes(changes, leftOut)
    }

    private fun show(command: Command, template: Template): Plan.Show? {
        val wanted = command.show
        Severity.entries.firstOrNull { it.label.equals(wanted, ignoreCase = true) }?.let {
            return Plan.Show(Filter.BySeverity(it), "the ${it.label.lowercase(Locale.ENGLISH)} issues")
        }
        template.categories.firstOrNull { it.equals(command.category, ignoreCase = true) }?.let {
            return Plan.Show(Filter.ByCategory(it), "the ${it.lowercase(Locale.ENGLISH)} issues")
        }
        return when {
            wanted.startsWith("all") -> Plan.Show(Filter.All, "every issue")
            wanted.startsWith("open") -> Plan.Show(Filter.Open, "the issues still open")
            wanted.startsWith("fixed") -> Plan.Show(Filter.Fixed, "the issues already fixed")
            wanted.startsWith("needs") -> Plan.Show(Filter.NeedsALook, "the issues that need a look")
            else -> null
        }
    }

    /** The plan in one breath, for the voice: "Issue 3: severity Critical, owner Ravi Kumar." */
    fun spoken(plan: Plan.Changes): String {
        val byWhat = plan.list.groupBy { change -> plan.list.filter { it.number == change.number }.map { it.field to it.to } }
        return byWhat.entries.joinToString(" ") { (what, changes) ->
            val numbers = changes.map { it.number }.distinct()
            val whom = if (numbers.size == 1) "Issue ${numbers[0]}" else "Issues " + numbers.dropLast(1).joinToString(", ") + " and " + numbers.last()
            whom + ": " + what.joinToString(", ") { (field, to) -> "${plan.list.first { it.field == field }.label.lowercase(Locale.ENGLISH)} $to" } + "."
        }
    }
}

/** What a note left unsaid that a report needs. */
enum class Gap(val field: String, val word: String) {
    WHERE(Field.LOCATION, "where"),
    WHO(Field.OWNER, "who"),
    WHEN(Field.DUE, "by when"),
}

/** The question the phone asks back when a note leaves out where, who or by when. */
object Asking {
    /** [placed] is true when somebody has said, typed or tagged where the issue is. */
    fun gaps(placed: Boolean, owner: String, due: String): List<Gap> = listOfNotNull(
        Gap.WHERE.takeIf { !placed },
        Gap.WHO.takeIf { owner.isBlank() },
        Gap.WHEN.takeIf { due.isBlank() },
    )

    /** One question for everything that is missing, short enough to be said aloud. */
    fun question(gaps: List<Gap>): String {
        val parts = gaps.map {
            when (it) {
                Gap.WHERE -> "where is it"
                Gap.WHO -> "who should deal with it"
                Gap.WHEN -> "by when"
            }
        }
        val asked = when (parts.size) {
            0 -> return ""
            1 -> if (gaps[0] == Gap.WHEN) "by when should it be done" else parts[0]
            2 -> parts[0] + ", and " + parts[1]
            else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
        }
        return asked.replaceFirstChar { it.uppercase(Locale.ENGLISH) } + "?"
    }

    private val LEAD = Regex("""^(?:it is|it's|its|that is|that's|by|to|at|in|on|the answer is|i think|maybe|tell|ask|give it to|assign it to)\s+""", RegexOption.IGNORE_CASE)

    /**
     * A one-part answer taken as it stands, for when the model is not there to read it: the lead-in
     * words and the full stop go, the rest is the value.
     */
    fun direct(answer: String): String =
        answer.trim().trimEnd('.', '!', ',').replace(LEAD, "").trim().replaceFirstChar { it.uppercase(Locale.ENGLISH) }
}
