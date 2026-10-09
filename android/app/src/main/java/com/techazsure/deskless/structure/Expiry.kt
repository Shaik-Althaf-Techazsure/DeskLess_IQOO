package com.techazsure.deskless.structure

import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Makes sense of an expiry date as packs print it ("EXP 08/2026", "AUG 26", "31.08.2026") and says whether
 * it has passed. The reading of the print is the model's and can be wrong; the arithmetic is done here.
 */
object Expiry {
    /** [month] is 1 to 12. [day] is null when the pack gives only a month, which then runs to its last day. */
    data class Read(val year: Int, val month: Int, val day: Int?)

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val fullDate = Regex("""(\d{1,2})[./\- ](\d{1,2})[./\- ](\d{4})""")
    private val yearFirst = Regex("""(\d{4})[./\- ](\d{1,2})(?![./\- ]?\d)""")
    private val named = Regex("""([a-z]{3})[a-z]*[./\- ]*(\d{4}|\d{2})""")
    private val monthYear = Regex("""(\d{1,2})[./\- ](\d{4}|\d{2})""")

    fun parse(printed: String): Read? {
        val text = printed.lowercase(Locale.ENGLISH)
        fullDate.find(text)?.let { m ->
            return valid(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        yearFirst.find(text)?.let { m ->
            valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null)?.let { return it }
        }
        named.findAll(text).forEach { m ->
            val month = months.indexOf(m.groupValues[1]) + 1
            if (month > 0) return valid(year(m.groupValues[2]), month, null)
        }
        monthYear.find(text)?.let { m ->
            return valid(year(m.groupValues[2]), m.groupValues[1].toInt(), null)
        }
        return null
    }

    /** True when the last day the pack is good for is before [today]. */
    fun isExpired(read: Read, today: Date = Date()): Boolean {
        val last = Calendar.getInstance().apply {
            clear()
            set(read.year, read.month - 1, 1)
            set(Calendar.DAY_OF_MONTH, read.day ?: getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }
        return last.time.before(today)
    }

    /** "Aug 2026" or "31 Aug 2026", for showing next to what was printed. */
    fun words(read: Read): String {
        val month = months[read.month - 1].replaceFirstChar { it.uppercase(Locale.ENGLISH) }
        return listOfNotNull(read.day?.toString(), month, read.year.toString()).joinToString(" ")
    }

    private fun year(digits: String): Int = digits.toInt().let { if (it < 100) 2000 + it else it }

    private fun valid(year: Int, month: Int, day: Int?): Read? =
        if (year in 2000..2100 && month in 1..12 && (day == null || day in 1..31)) Read(year, month, day) else null
}
