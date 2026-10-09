package com.techazsure.deskless

import com.techazsure.deskless.structure.DueDates
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Date

class DueDatesTest {
    // Saturday 10 Oct 2026.
    private val today: Date = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 10, 9, 0, 0) }.time

    @Test
    fun weekdayMeansTheNextOne() {
        assertEquals("12 Oct 2026", DueDates.resolve("Monday", today))
        assertEquals("12 Oct 2026", DueDates.resolve("by Monday", today))
        assertEquals("16 Oct 2026", DueDates.resolve("this Friday", today))
        assertEquals("17 Oct 2026", DueDates.resolve("Saturday", today))
    }

    @Test
    fun relativeDays() {
        assertEquals("10 Oct 2026", DueDates.resolve("today", today))
        assertEquals("11 Oct 2026", DueDates.resolve("tomorrow", today))
        assertEquals("12 Oct 2026", DueDates.resolve("day after tomorrow", today))
        assertEquals("13 Oct 2026", DueDates.resolve("within 3 days", today))
        assertEquals("17 Oct 2026", DueDates.resolve("next week", today))
    }

    @Test
    fun urgencyWithoutADayIsSpeltOut() {
        assertEquals("As soon as possible", DueDates.resolve("Quickly", today))
        assertEquals("As soon as possible", DueDates.resolve("immediately.", today))
        assertEquals("As soon as possible", DueDates.resolve("ASAP", today))
    }

    @Test
    fun calendarDatesAndOtherPhrasesAreKept() {
        assertEquals("15 October", DueDates.resolve("15 October", today))
        assertEquals("Mon 12 Oct 2026", DueDates.resolve("Mon 12 Oct 2026", today))
        assertEquals("before handover", DueDates.resolve("before handover", today))
        assertEquals("", DueDates.resolve("  ", today))
    }
}
