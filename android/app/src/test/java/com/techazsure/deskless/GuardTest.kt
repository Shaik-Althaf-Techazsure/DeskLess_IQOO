package com.techazsure.deskless

import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.structure.DueDates
import com.techazsure.deskless.structure.Field
import com.techazsure.deskless.structure.Fields
import com.techazsure.deskless.structure.Grounding
import com.techazsure.deskless.structure.Guard
import com.techazsure.deskless.structure.Memory
import com.techazsure.deskless.structure.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTest {
    private val note = "There are many cracks on the second floor near the lift. Tell Ravi Kumar to fix it by Monday."

    private fun fields(location: String = "", owner: String = "", due: String = "", title: String = "Cracks in the wall", seen: String = "") =
        Fields(title = title, location = location, severity = Severity.HIGH, owner = owner, dueDate = DueDates.resolve(due), seen = seen, dueSaid = due)

    @Test
    fun wordsAreFoundWhateverTheirEndingOrFigure() {
        assertTrue(Grounding.heard("2nd floor", note))
        assertTrue(Grounding.heard("Second floor, near the lift", note))
        assertTrue(Grounding.heard("crack", note))
        assertFalse(Grounding.heard("Third floor", note))
        assertFalse(Grounding.heard("Basement", note))
    }

    @Test
    fun aNameOfTwoWordsNeedsBoth() {
        assertTrue(Grounding.heard("Ravi Kumar", note))
        assertFalse(Grounding.heard("Ravi Khan", note))
        assertFalse(Grounding.heard("Suresh", note))
    }

    @Test
    fun whatWasSaidGoesThroughAndIsMarkedHeard() {
        val checked = Guard.check(fields(location = "Second floor", owner = "Ravi Kumar", due = "Monday"), note)
        assertEquals("Second floor", checked.fields.location)
        assertEquals("Ravi Kumar", checked.fields.owner)
        assertEquals(DueDates.resolve("Monday"), checked.fields.dueDate)
        assertEquals(Source.HEARD, checked.sources[Field.LOCATION])
        assertEquals(Source.HEARD, checked.sources[Field.OWNER])
        assertEquals(Source.HEARD, checked.sources[Field.DUE])
        assertTrue(checked.held.isEmpty())
    }

    @Test
    fun aNameAPlaceAndADateNobodySaidAreKeptOut() {
        val checked = Guard.check(fields(location = "Block C", owner = "Electrical team", due = "Friday"), note)
        assertEquals("", checked.fields.location)
        assertEquals("", checked.fields.owner)
        assertEquals("", checked.fields.dueDate)
        assertEquals("Block C", checked.held[Field.LOCATION])
        assertEquals("Electrical team", checked.held[Field.OWNER])
        assertEquals(DueDates.resolve("Friday"), checked.held[Field.DUE])
        assertNull(checked.sources[Field.OWNER])
    }

    @Test
    fun aTitleIsHeardSeenOrTheModelsOwn() {
        assertEquals(Source.HEARD, Guard.check(fields(title = "Cracks on second floor"), note).sources[Field.TITLE])
        assertEquals(Source.SEEN, Guard.check(fields(title = "Exposed wiring", seen = "The photo shows exposed wiring on a wall."), note).sources[Field.TITLE])
        assertEquals(Source.JUDGED, Guard.check(fields(title = "Structural damage"), note).sources[Field.TITLE])
    }

    @Test
    fun severityIsTheModelsJudgementUnlessTheLevelWasSpoken() {
        assertEquals(Source.JUDGED, Guard.check(fields(), note).sources[Field.SEVERITY])
        assertEquals(Source.HEARD, Guard.check(fields(), "This is high priority, cracks everywhere.").sources[Field.SEVERITY])
    }

    @Test
    fun aPlaceReadOffThePictureIsKeptAndMarkedSeen() {
        val checked = Guard.check(fields(location = "Pump room", seen = "A door with a sign reading pump room."), note)
        assertEquals("Pump room", checked.fields.location)
        assertEquals(Source.SEEN, checked.sources[Field.LOCATION])
    }

    @Test
    fun askingForSpeedIsADeadlineThatWasSaid() {
        val checked = Guard.check(fields(due = "immediately"), "Water is leaking, fix it quickly.")
        assertEquals(DueDates.AS_SOON_AS_POSSIBLE, checked.fields.dueDate)
        assertEquals(Source.HEARD, checked.sources[Field.DUE])
        assertEquals("", Guard.check(fields(due = "immediately"), "Water is leaking under the sink.").fields.dueDate)
    }

    @Test
    fun aDeadlineInWordsIsWorkedOutLikeOneInFigures() {
        assertEquals(DueDates.resolve("in 2 days"), DueDates.resolve("in two days"))
        assertEquals(DueDates.resolve("within 3 days"), DueDates.resolve("within three days"))
        // A model sometimes shortens "in two days" to "Two days".
        assertEquals(DueDates.resolve("in 2 days"), DueDates.resolve("Two days"))
        assertEquals(DueDates.resolve("in 7 days"), DueDates.resolve("in a week"))
        assertEquals(DueDates.resolve("in 14 days"), DueDates.resolve("2 weeks"))
        assertEquals(DueDates.resolve("in 1 days"), DueDates.resolve("a day"))
        // A count that is not a deadline by itself is left as it was said.
        assertEquals("the work of 2 days", DueDates.resolve("the work of 2 days"))
        assertEquals("15 October", DueDates.resolve("15 October"))
    }

    @Test
    fun aHeardNameIsSpeltTheWayThePhoneKnowsIt() {
        val memory = Memory()
        memory.learn("a", "Tower A", "Ravi Kumar", "Second floor lobby")
        val checked = Guard.check(fields(owner = "Ravi Kumaar"), "Tell Ravi Kumaar to fix the cracks.", memory, "Tower A")
        assertEquals("Ravi Kumar", checked.fields.owner)
        assertEquals(Source.KNOWN, checked.sources[Field.OWNER])
    }
}

class MemoryTest {
    @Test
    fun learnsWhoAndWhereAndRanksByUse() {
        val memory = Memory()
        memory.learn("a", "Tower A", "Ravi Kumar", "Pump room", now = 1)
        memory.learn("b", "Tower A", "Electrical team", "Pump room", now = 2)
        memory.learn("c", "Tower A", "ravi kumar", "", now = 3)
        assertEquals(listOf("ravi kumar", "Electrical team"), memory.people())
        assertEquals(listOf("Pump room"), memory.places("tower a"))
        assertTrue(memory.places("Another site").isEmpty())
    }

    @Test
    fun aCorrectedNameReplacesTheMisheardOne() {
        val memory = Memory()
        memory.learn("a", "", "Robbie Kumar", "")
        memory.learn("a", "", "Ravi Kumar", "")
        assertEquals(listOf("Ravi Kumar"), memory.people())
    }

    @Test
    fun theSameMishearingIsPutRightNextTime() {
        val memory = Memory()
        memory.learn("a", "", "Ravi Kumar", "")
        memory.correctedPerson("Robbie Kumar", "Ravi Kumar")
        assertEquals("Ravi Kumar", memory.person("robbie kumar"))
        assertNull(memory.person("Bobby Kumar"))
    }

    @Test
    fun aFirstNameMeansThePersonOnlyWhenOnePersonCarriesIt() {
        val memory = Memory()
        memory.learn("a", "", "Ravi Kumar", "")
        assertEquals("Ravi Kumar", memory.person("Ravi"))
        memory.learn("b", "", "Ravi Shankar", "")
        assertNull(memory.person("Ravi"))
    }

    @Test
    fun differentPeopleAndPlacesAreNotMixedUp() {
        val memory = Memory()
        memory.learn("a", "Tower A", "Ramesh", "Block B")
        assertNull(memory.person("Rameshwar"))
        assertNull(memory.person("Suresh"))
        assertNull(memory.place("Block C", "Tower A"))
        assertNull(memory.place("Block", "Tower A"))
        assertEquals("Block B", memory.place("block b", "Tower A"))
        assertNull(memory.place("Block B", "Tower Z"))
    }

    @Test
    fun forgettingAnIssueForgetsWhatOnlyItTaught() {
        val memory = Memory()
        memory.learn("a", "", "Ravi Kumar", "")
        memory.learn("b", "", "Ravi Kumar", "")
        memory.unlearn("a")
        assertEquals(listOf("Ravi Kumar"), memory.people())
        memory.unlearn("b")
        assertTrue(memory.people().isEmpty())
    }

    @Test
    fun thePlaceIsTakenWithoutWhereTheCameraWasAimed() {
        assertEquals("Second floor", Memory.placeIn("Second floor, overhead"))
        assertEquals("Lobby", Memory.placeIn("Lobby, at ground level"))
    }
}
