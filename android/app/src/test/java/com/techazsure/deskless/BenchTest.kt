package com.techazsure.deskless

import com.techazsure.deskless.bench.Bench
import com.techazsure.deskless.bench.Outcome
import com.techazsure.deskless.bench.Scorecard
import com.techazsure.deskless.bench.Wav
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Template
import com.techazsure.deskless.speech.Spoken
import com.techazsure.deskless.structure.DueDates
import com.techazsure.deskless.structure.Fields
import com.techazsure.deskless.structure.Guard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BenchTest {
    @Test
    fun everyNoteHoldsItsOwnAnswers() {
        assertEquals(20, Bench.cases.size)
        Bench.cases.forEachIndexed { at, case ->
            // A perfect reading of the note is one the guard lets through and the marking calls right.
            val perfect = Fields(
                title = "x", location = case.location, severity = case.severity.first(), owner = case.owner,
                dueDate = DueDates.resolve(case.due), category = case.types.firstOrNull().orEmpty(), dueSaid = case.due,
            )
            val checked = Guard.check(perfect, case.note)
            assertTrue("note ${at + 1} does not say its own answers: ${checked.held}", checked.held.isEmpty())
            assertTrue("note ${at + 1}", Bench.mark(case, checked.fields).all { it.right })
            assertTrue("note ${at + 1} uses a type the site template lacks", Template.SITE.categories.containsAll(case.types))
        }
        assertTrue(Bench.quick.all { it in Bench.cases.indices })
    }

    @Test
    fun marksWhatIsWrongAndWhatIsMadeUp() {
        val nobody = Bench.cases[3]
        val marks = Bench.mark(nobody, Fields("Empty extinguisher", "Main gate", Severity.LOW, "Ramesh", "", category = "Fire safety"))
        assertTrue(marks.first { it.field == Bench.LOCATION }.right)
        assertTrue(marks.first { it.field == Bench.OWNER }.madeUp)
        assertFalse(marks.first { it.field == Bench.OWNER }.right)
        assertTrue(marks.first { it.field == Bench.DUE }.right)
        assertFalse(marks.first { it.field == Bench.SEVERITY }.right)
        assertTrue(marks.first { it.field == Bench.TYPE }.right)
        // More words around the place do no harm; a different place does.
        val first = Bench.cases[0]
        assertTrue(Bench.mark(first, Fields("x", "Second floor near the staircase", Severity.HIGH, "Mr Ravi Kumar", DueDates.resolve("Monday"))).take(3).all { it.right })
        assertFalse(Bench.mark(first, Fields("x", "Third floor", Severity.HIGH, "Ravi", "")).take(3).any { it.right })
    }

    @Test
    fun countsTheWordsTheSpeechModelGotRight() {
        assertEquals(1f, Bench.wordsRight("The lift in block A is not working.", "the lift in Block A is not working"), 0.001f)
        assertEquals(0.75f, Bench.wordsRight("fix it by Monday", "fix it by Sunday"), 0.001f)
        assertEquals(0f, Bench.wordsRight("fix it", ""), 0.001f)
    }

    @Test
    fun addsUpAScorecard() {
        val outcomes = Bench.cases.take(2).mapIndexed { at, case ->
            val got = if (at == 0) Fields("x", case.location, case.severity.first(), case.owner, DueDates.resolve(case.due), category = case.types.first())
            else Fields("x", "", Severity.LOW, "Somebody", "")
            Outcome(at + 1, case.note, heard = case.note, wordsRight = 1f, marks = Bench.mark(case, got), speechSeconds = 1f, fieldSeconds = 2f + at, keptOut = at)
        }
        val card = Scorecard(at = 0, phone = "Test phone", processor = "GPU", language = Spoken.HINDI.label, spoken = true, outcomes = outcomes, usedMah = 6f, charging = false)
        assertEquals(2, card.notes)
        assertEquals(0.5f, card.fieldsRight, 0.001f)
        assertEquals(1f, card.right(Bench.TYPE) + card.right(Bench.LOCATION), 0.001f)
        assertEquals(2.5f, card.fieldSeconds, 0.001f)
        assertEquals(3f, card.mahPerNote!!, 0.001f)
        assertEquals(1, card.keptOut)
        assertNull(card.copy(charging = true).mahPerNote)
        assertTrue(Bench.summary(card, "today").contains("Report fields right: 50%"))
        // The line on the card that opens the measurements: it once crashed the screen, a per cent sign in a formatted text.
        assertEquals("Last run: 50% of fields right over 2 notes, 3.5 s a note.", Bench.lastRun(card))
        assertEquals("Last run: 100% of fields right over 1 note, 2.0 s a note.", Bench.lastRun(card.copy(spoken = false, outcomes = outcomes.take(1).map { it.copy(heard = null, wordsRight = null) })))
    }

    @Test
    fun readsAVoiceFileAtAnotherRate() {
        // Half a second of 24 kHz sound in two channels, as a speech engine might write it.
        val frames = 12_000
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray())
        bytes.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(24_000).putInt(96_000).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { bytes.putShort((it % 100).toShort()).putShort(0) }
        val samples = Wav.to16k(bytes.array())
        assertNotNull(samples)
        assertEquals(8_000, samples!!.size)
        assertEquals(3, samples[2].toInt())
        assertNull(Wav.to16k(ByteArray(100)))
        assertEquals(listOf<Short>(0, 5, 10), Wav.resample(shortArrayOf(0, 10, 20), 2, 4).take(3))
    }

    @Test
    fun knowsTheLanguagesByTheSpeechModelsOwnCodes() {
        assertEquals(listOf("hi", "kn", "ta", "te", "mr", "bn", "en", ""), Spoken.entries.map { it.code })
        assertEquals(Spoken.HINDI, Spoken.named(null))
        assertEquals(Spoken.KANNADA, Spoken.named("KANNADA"))
    }
}
