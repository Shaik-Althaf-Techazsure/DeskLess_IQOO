package com.techazsure.deskless

import com.techazsure.deskless.capture.Geo
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.report.ReportPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReportPackTest {
    private val inspection = Inspection(site = "Tower B", inspector = "Asha")
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 10, 9, 0, 0) }.time

    private fun issue(id: String, title: String, owner: String = "", due: String = "", note: String = "") =
        Issue(id = id, createdAt = now.time, photoFile = "p.jpg", title = title, owner = owner, dueDate = due, note = note, severity = Severity.HIGH)

    @Test
    fun spreadsheetQuotesWhatNeedsIt() {
        val csv = ReportPack.csv(inspection, listOf(issue("a", "Crack, wide", note = "He said \"urgent\"")))
        val lines = csv.trim().split("\r\n")
        assertTrue(lines[0].startsWith("No,Issue,Type,Severity,Location,Owner,Due,Status"))
        assertTrue(lines[1].startsWith("1,\"Crack, wide\",,High,"))
        assertTrue(lines[1].endsWith("\"He said \"\"urgent\"\"\""))
    }

    @Test
    fun calendarHoldsOnlyDatedDeadlines() {
        val calendar = ReportPack.calendar(
            inspection,
            listOf(issue("a", "Crack", owner = "Ravi", due = "12 Oct 2026"), issue("b", "Leak", due = "before handover")),
            now,
        )!!
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(calendar).count())
        assertTrue(calendar.contains("DTSTART;VALUE=DATE:20261012"))
        assertTrue(calendar.contains("SUMMARY:[High] Crack - Ravi"))
        assertNull(ReportPack.calendar(inspection, listOf(issue("b", "Leak")), now))
    }

    @Test
    fun ownersGetTheirOwnList() {
        val text = ReportPack.ownerLists(inspection, listOf(issue("a", "Crack", owner = "Ravi"), issue("b", "Leak")))
        assertTrue(text.contains("Ravi (1)"))
        assertTrue(text.contains("Not assigned (1)"))
        assertTrue(text.indexOf("Ravi (1)") < text.indexOf("Not assigned (1)"))
    }

    @Test
    fun mapArithmetic() {
        // Two points about 111 m apart north to south.
        val points = Geo.project(listOf(12.9700 to 77.5900, 12.9710 to 77.5900))
        assertEquals(110.5f, points[1].north - points[0].north, 1f)
        assertEquals(0f, points[1].east - points[0].east, 0.01f)
        assertEquals(20, Geo.scaleBar(Geo.span(points)))
        assertEquals("12.97000° N, 77.59000° E", Geo.label(12.97, 77.59))
    }
}
