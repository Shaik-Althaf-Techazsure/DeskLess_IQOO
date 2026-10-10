package com.techazsure.deskless

import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.data.Instrument
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Reading
import com.techazsure.deskless.data.Readings
import com.techazsure.deskless.data.Stamp
import com.techazsure.deskless.report.Mark
import com.techazsure.deskless.report.Reviewer
import com.techazsure.deskless.report.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTest {
    private val start = 1_760_000_000_000L
    private val walk = Inspection(site = "Tower B", inspector = "Asha", startedAt = start)

    /** An issue as a careful inspector leaves it: picture matches, readings stamped, a position on site. */
    private fun issue(n: Int, check: Check = Check.MATCH, note: String = "Crack in the wall", stamp: Stamp? = Stamp(lux = 120f, pitch = 0f)) = Issue(
        id = "i$n", createdAt = start + n * 60_000L, photoFile = "p$n.jpg", note = note, check = check, stamp = stamp,
        lat = 12.9700 + n * 0.00005, lon = 77.5900,
    )

    private fun review(issues: List<Issue>, model: Pair<Verdict, String>? = null) =
        Reviewer.decide(Reviewer.findings(walk, issues), issues.size, model)

    @Test
    fun aCleanReportIsApproved() {
        val made = review((1..4).map { issue(it) })
        assertEquals(Verdict.GENUINE, made.verdict)
        assertTrue(made.approved)
        assertTrue(made.findings.none { it.mark == Mark.FAIL })
        assertFalse(made.byModel)
    }

    @Test
    fun oneDoubtedPictureHoldsTheReport() {
        val made = review(listOf(issue(1), issue(2), issue(3, check = Check.MISMATCH), issue(4)))
        assertEquals(Verdict.DOUBTFUL, made.verdict)
        assertFalse(made.approved)
        assertTrue(made.reason.contains("issue 3"))
    }

    @Test
    fun mostPicturesWrongDoesNotHoldTogether() {
        val made = review(listOf(issue(1, Check.MISMATCH), issue(2, Check.MISMATCH), issue(3, Check.MISMATCH), issue(4)))
        assertEquals(Verdict.FAKE, made.verdict)
    }

    @Test
    fun aNoteTheSensorsContradictIsCaught() {
        val bright = issue(2, note = "It is too dark to see the valves here", stamp = Stamp(lux = 800f, pitch = 0f))
        val clashes = Reviewer.contradictions(2, bright)
        assertEquals(listOf("Issue 2 says it is dark, but the phone measured 800 lux there."), clashes)
        assertEquals(Verdict.DOUBTFUL, review(listOf(issue(1), bright, issue(3))).verdict)
        // The same words with a dim reading are not a contradiction: they agree.
        val dim = issue(2, note = "It is too dark to see the valves here", stamp = Stamp(lux = 20f, pitch = 0f))
        assertTrue(Reviewer.contradictions(2, dim).isEmpty())
        assertEquals(Verdict.GENUINE, review(listOf(issue(1), dim, issue(3))).verdict)
    }

    @Test
    fun aimAndInstrumentsAreHeldAgainstTheNote() {
        val up = issue(1, note = "Water stain on the ceiling", stamp = Stamp(pitch = -70f))
        assertEquals(1, Reviewer.contradictions(1, up).size)
        val quiet = issue(2, note = "The generator is very loud").copy(readings = listOf(Reading(Instrument.NOISE, 38f)))
        assertEquals(1, Reviewer.contradictions(2, quiet).size)
        val flat = issue(3, note = "The floor is sloping").copy(readings = listOf(Reading(Instrument.LEVEL, 0.1f)))
        assertEquals(1, Reviewer.contradictions(3, flat).size)
    }

    @Test
    fun positionsFarApartAreNotOneSite() {
        val far = issue(2).copy(lat = 13.10, lon = 77.59)
        val made = review(listOf(issue(1), far, issue(3)))
        assertEquals(Verdict.DOUBTFUL, made.verdict)
        assertTrue(made.findings.any { it.mark == Mark.FAIL && it.text.contains("km apart") })
    }

    @Test
    fun theModelCanTightenButNeverLoosen() {
        val clean = (1..3).map { issue(it) }
        val doubted = listOf(issue(1), issue(2, Check.UNSURE), issue(3))
        // A clean report the model distrusts is held, but it takes a failed check to call it fake.
        assertEquals(Verdict.DOUBTFUL, review(clean, Verdict.FAKE to "Issue 2 looks staged to me.").verdict)
        assertEquals(Verdict.GENUINE, review(clean, Verdict.GENUINE to "The pictures match the notes throughout.").verdict)
        // A failed check stands whatever the model says.
        assertEquals(Verdict.DOUBTFUL, review(doubted, Verdict.GENUINE to "Everything looks consistent.").verdict)
        assertEquals(Verdict.FAKE, review(doubted, Verdict.FAKE to "Issue 2 does not show the problem.").verdict)
        assertTrue(review(clean, Verdict.GENUINE to "The pictures match the notes throughout.").byModel)
    }

    @Test
    fun locationIsFilledFromWhatThePhoneKnows() {
        assertEquals("Pump room, overhead, facing north-east, at 12.97160° N, 77.59460° E", Readings.whereKnown("Pump room", Stamp(heading = 47f, pitch = 70f), 12.9716, 77.5946))
        assertEquals("Facing south", Readings.whereKnown("", Stamp(heading = 180f, pitch = 0f), null, null))
        assertEquals("At 12.97160° N, 77.59460° E", Readings.whereKnown("", null, 12.9716, 77.5946))
        assertEquals("", Readings.whereKnown("", null, null, null))
    }
}
