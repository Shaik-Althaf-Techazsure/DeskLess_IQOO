package com.techazsure.deskless.structure

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Turns a spoken deadline such as "Monday" or "in 3 days" into a calendar date. The model is asked to copy
 * the phrase as it was said; the date arithmetic is done here, where it cannot go wrong.
 */
object DueDates {
    private val weekdays = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    // "in 3 days", "within 3 days", and the bare "3 days" a model sometimes shortens them to; weeks likewise.
    private val inDays = Regex("""^(\d+)\s+(days?|weeks?)\b|\b(?:in|within|after)\s+(\d+)\s+(days?|weeks?)\b""")
    private val digit = Regex("""\d""")

    /** A count of days is as often heard in words ("in two days") as in figures. */
    private val counts = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private val countWord = Regex("""\b(${counts.joinToString("|")}|an?)\b(?=\s+(?:days?|weeks?)\b)""")

    /** Words that ask for speed without naming a day ("jaldi" comes through as "quickly"). */
    private val urgent = setOf(
        "quickly", "quick", "fast", "soon", "asap", "as soon as possible", "immediately", "urgent", "urgently",
        "right away", "right now", "at the earliest",
    )
    const val AS_SOON_AS_POSSIBLE = "As soon as possible"

    /** True when [note] asks for speed in any of the ways that become "as soon as possible". */
    fun asksForSpeed(note: String): Boolean {
        val text = " " + note.lowercase(Locale.ENGLISH).replace(Regex("""[^a-z]+"""), " ") + " "
        return urgent.any { " $it " in text }
    }

    /** Returns a date like "12 Oct 2026", or the phrase unchanged when it is not a relative day. */
    fun resolve(phrase: String, today: Date = Date()): String {
        val said = phrase.trim()
        val text = said.lowercase(Locale.ENGLISH).trimEnd('.')
            .replace(countWord) { if (it.value.startsWith("a")) "1" else (counts.indexOf(it.value) + 1).toString() }
        if (text.isEmpty()) return ""
        if (text in urgent) return AS_SOON_AS_POSSIBLE
        // Already a calendar date, e.g. "15 October" or "Mon 12 Oct 2026".
        if (digit.containsMatchIn(text) && months.any { it in text }) return said

        val calendar = Calendar.getInstance().apply { time = today }
        val days = when {
            "day after tomorrow" in text -> 2
            "tomorrow" in text -> 1
            "today" in text || "tonight" in text || "end of day" in text -> 0
            else -> inDays.find(text)?.groupValues?.let { found ->
                val count = found[1].ifEmpty { found[3] }.toIntOrNull() ?: return@let null
                if ((found[2] + found[4]).startsWith("week")) count * 7 else count
            }
                ?: weekdays.indexOfFirst { it in text }.takeIf { it >= 0 }?.let { wanted ->
                    val ahead = (wanted - (calendar.get(Calendar.DAY_OF_WEEK) - 1) + 7) % 7
                    if (ahead == 0) 7 else ahead
                }
                ?: if ("next week" in text) 7 else null
        } ?: return said
        calendar.add(Calendar.DAY_OF_YEAR, days)
        return SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(calendar.time)
    }
}
