package com.techazsure.deskless.structure

import com.techazsure.deskless.data.Severity
import java.util.Locale

/** Where the value of a report field came from. An issue remembers it by [key]. */
enum class Source(val key: String, val tag: String, val meaning: String, val inReport: String) {
    HEARD("heard", "HEARD", "the words are in your note", "said in the note"),
    SEEN("seen", "SEEN", "read from the picture", "seen in the picture"),
    PHONE("phone", "PHONE", "from the phone itself: its position, its sensors or a tapped tag", "from the phone"),
    KNOWN("known", "KNOWN NAME", "heard, and spelt as a name this phone has met before", "said, spelt as a known name"),
    ASKED("asked", "ASKED", "from your answer when DeskLess asked", "answered when asked"),
    TOLD("told", "BY VOICE", "set by a spoken instruction", "set by voice"),
    JUDGED("judged", "AI'S JUDGEMENT", "worked out by the model on this phone, not said in so many words", "the AI's judgement"),
    TYPED("typed", "YOU", "typed or picked by you", "entered by hand");

    companion object {
        fun of(key: String?): Source? = entries.firstOrNull { it.key == key }
    }
}

/** The fields of an issue that carry a source, by the names they are stored under. */
object Field {
    const val TITLE = "title"
    const val LOCATION = "location"
    const val SEVERITY = "severity"
    const val OWNER = "owner"
    const val DUE = "dueDate"
    const val CATEGORY = "category"
}

/**
 * Whether words the model wrote can be found in what was actually said. A model this small will now and
 * then write a name or a day nobody spoke; this is how the app tells, without trusting the model about it.
 */
object Grounding {
    private val WORD = Regex("""[\p{L}\p{N}]+""")

    /** Words that carry no meaning of their own, so they neither prove nor disprove anything. */
    private val FILLER = setOf(
        "the", "a", "an", "of", "in", "on", "at", "to", "by", "for", "and", "or", "is", "it", "its", "this", "that",
        "near", "next", "mr", "mrs", "ms", "sir", "madam", "ji", "sahab", "please", "should", "must", "has", "have",
    )
    private val NUMBERS = mapOf(
        "one" to "1", "first" to "1", "1st" to "1", "two" to "2", "second" to "2", "2nd" to "2",
        "three" to "3", "third" to "3", "3rd" to "3", "four" to "4", "fourth" to "4", "4th" to "4",
        "five" to "5", "fifth" to "5", "5th" to "5", "six" to "6", "sixth" to "6", "6th" to "6",
        "seven" to "7", "seventh" to "7", "7th" to "7", "eight" to "8", "eighth" to "8", "8th" to "8",
        "nine" to "9", "ninth" to "9", "9th" to "9", "ten" to "10", "tenth" to "10", "10th" to "10",
    )

    /** The words of a text in small letters, with "second", "2nd" and "two" all read as "2". */
    fun words(text: String): List<String> =
        WORD.findAll(text.lowercase(Locale.ENGLISH)).map { NUMBERS[it.value] ?: it.value }.toList()

    /** The words that mean something; all of them when none does. */
    fun keyWords(text: String): List<String> = words(text).let { all -> all.filter { it !in FILLER }.ifEmpty { all } }

    /** True when two words are the same word allowing for an ending or a slip of spelling. */
    fun alike(a: String, b: String): Boolean {
        if (a == b) return true
        val short = minOf(a.length, b.length)
        if (short < 4) return false
        // "floor" and "floors", "electrical" and "electrician".
        val shared = a.commonPrefixWith(b).length
        if (shared >= 4 && shared * 10 >= short * 7) return true
        return slip(a, b)
    }

    /**
     * True when two words are one word with a slip of spelling: "Kumar" and "Kumaar". Stricter than
     * [alike], for telling names apart: "Ramesh" and "Rameshwar" are two people.
     */
    fun slip(a: String, b: String): Boolean {
        if (a == b) return true
        val short = minOf(a.length, b.length)
        return distance(a, b) <= if (short >= 8) 2 else if (short >= 5) 1 else 0
    }

    /** How much of [value] can be found in [said]: the share of its meaningful words, 0 to 1. */
    fun share(value: String, said: String): Float {
        val wanted = keyWords(value)
        if (wanted.isEmpty()) return 0f
        val spoken = words(said)
        return wanted.count { word -> spoken.any { alike(word, it) } } / wanted.size.toFloat()
    }

    /** True when [value] was said: most of its words are in [said]. A name of two words needs both. */
    fun heard(value: String, said: String): Boolean = share(value, said) >= 0.6f

    /** The number of single-letter changes that turn [a] into [b]. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val swap = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(swap, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}

/** What the guard let through of the model's fields, where each came from, and what it kept out. */
data class Grounded(
    val fields: Fields,
    /** By [Field] name, for every field in [fields] that holds something. */
    val sources: Map<String, Source>,
    /** By [Field] name: what the model offered that nobody said, left out of the report. */
    val held: Map<String, String>,
)

/**
 * The rule that the model may word things but may not make facts up. A place, a person or a deadline is
 * written into the report only if its words are in the note (or, for a place, in what the picture
 * shows); otherwise it is kept aside for the inspector to accept or ignore. A title, a severity and a
 * type are the model's to judge, and are marked as its judgement when the note does not say them.
 */
object Guard {
    /**
     * [note] is everything that was said. [memory] spells a heard name the way the phone has met it
     * before; null leaves names as heard. [site] is the place being walked.
     */
    fun check(fields: Fields, note: String, memory: Memory? = null, site: String = ""): Grounded {
        val sources = HashMap<String, Source>()
        val held = HashMap<String, String>()

        if (fields.title.isNotBlank()) {
            sources[Field.TITLE] = when {
                Grounding.heard(fields.title, note) -> Source.HEARD
                Grounding.heard(fields.title, fields.seen) -> Source.SEEN
                else -> Source.JUDGED
            }
        }
        fields.severity?.let { severity ->
            sources[Field.SEVERITY] = if (saysSeverity(note, severity)) Source.HEARD else Source.JUDGED
        }
        if (fields.category.isNotBlank()) sources[Field.CATEGORY] = Source.JUDGED

        var location = fields.location
        if (location.isNotBlank()) {
            when {
                Grounding.heard(location, note) -> {
                    val known = memory?.place(location, site)
                    sources[Field.LOCATION] = if (known != null && known != location) Source.KNOWN else Source.HEARD
                    location = known ?: location
                }
                Grounding.heard(location, fields.seen) -> sources[Field.LOCATION] = Source.SEEN
                else -> {
                    held[Field.LOCATION] = location
                    location = ""
                }
            }
        }

        var owner = fields.owner
        if (owner.isNotBlank()) {
            if (Grounding.heard(owner, note)) {
                val known = memory?.person(owner)
                sources[Field.OWNER] = if (known != null && known != owner) Source.KNOWN else Source.HEARD
                owner = known ?: owner
            } else {
                held[Field.OWNER] = owner
                owner = ""
            }
        }

        var due = fields.dueDate
        if (due.isNotBlank()) {
            // The deadline is checked as it was said ("Monday"), not as the date worked out from it.
            val said = fields.dueSaid.ifBlank { due }
            val urgent = due == DueDates.AS_SOON_AS_POSSIBLE && DueDates.asksForSpeed(note)
            if (urgent || Grounding.heard(said, note)) {
                sources[Field.DUE] = Source.HEARD
            } else {
                held[Field.DUE] = due
                due = ""
            }
        }
        return Grounded(fields.copy(location = location, owner = owner, dueDate = due), sources, held)
    }

    private fun saysSeverity(note: String, severity: Severity): Boolean {
        val words = Grounding.words(note)
        return severity.label.lowercase(Locale.ENGLISH) in words
    }
}
