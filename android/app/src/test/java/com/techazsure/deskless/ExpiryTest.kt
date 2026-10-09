package com.techazsure.deskless

import com.techazsure.deskless.structure.Expiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

class ExpiryTest {
    // 10 Oct 2026.
    private val today: Date = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 10, 9, 0, 0) }.time

    @Test
    fun readsTheWaysPacksPrintIt() {
        assertEquals(Expiry.Read(2026, 8, null), Expiry.parse("EXP 08/2026"))
        assertEquals(Expiry.Read(2026, 8, null), Expiry.parse("08/26"))
        assertEquals(Expiry.Read(2027, 3, null), Expiry.parse("Exp. MAR 2027"))
        assertEquals(Expiry.Read(2027, 3, null), Expiry.parse("March-27"))
        assertEquals(Expiry.Read(2026, 12, null), Expiry.parse("2026-12"))
        assertEquals(Expiry.Read(2026, 8, 31), Expiry.parse("31.08.2026"))
    }

    @Test
    fun aMonthRunsToItsLastDay() {
        assertTrue(Expiry.isExpired(Expiry.Read(2026, 9, null), today))
        assertFalse(Expiry.isExpired(Expiry.Read(2026, 10, null), today))
        assertTrue(Expiry.isExpired(Expiry.Read(2026, 10, 9), today))
        assertFalse(Expiry.isExpired(Expiry.Read(2026, 10, 10), today))
    }

    @Test
    fun nonsenseIsNotADate() {
        assertNull(Expiry.parse(""))
        assertNull(Expiry.parse("batch B1234"))
        assertNull(Expiry.parse("13/2026"))
    }

    @Test
    fun wordsForTheScreen() {
        assertEquals("Aug 2026", Expiry.words(Expiry.Read(2026, 8, null)))
        assertEquals("31 Aug 2026", Expiry.words(Expiry.Read(2026, 8, 31)))
    }
}
