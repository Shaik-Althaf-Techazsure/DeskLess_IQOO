package com.techazsure.deskless

import com.techazsure.deskless.account.Sent
import com.techazsure.deskless.account.Word
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.structure.Ask
import com.techazsure.deskless.structure.UserAsks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a user of a place says to the phone after it has read their entry back, and what is kept of the model's wording. */
class UserAsksTest {
    private val leak = Ask(
        kind = "issue", title = "Water leaking on the stairs", summary = "Water is leaking on the stairs of block B since morning.",
        location = "Block B stairs", severity = Severity.MEDIUM, category = "Water and plumbing", said = "Water is leaking on the stairs of block B since morning.",
    )

    @Test
    fun agreeingSendsAndNothingElseDoes() {
        listOf("Send it", "send", "Yes", "yes, send it", "Okay send it now", "That's right", "it is right", "go ahead", "Please send it to them").forEach {
            assertTrue(it, UserAsks.wantsSend(it))
        }
        // Something the person adds, or a refusal, is never taken for a yes.
        listOf("It is on the right side of the gate", "No", "Don't send it", "Yes but change the floor", "Send the plumber quickly please today itself now", "Wait", "The post box is broken", "").forEach {
            assertFalse(it, UserAsks.wantsSend(it))
        }
    }

    @Test
    fun givingUpDropsItAndAStoryAboutCancellingDoesNot() {
        listOf("Cancel", "cancel it", "Forget it", "Never mind", "Don't send it", "do not send", "delete it").forEach { assertTrue(it, UserAsks.wantsCancel(it)) }
        listOf("The class was cancelled without telling us", "No", "Send it", "They forget to lock the gate").forEach { assertFalse(it, UserAsks.wantsCancel(it)) }
        assertTrue(UserAsks.onlyRefuses("No"))
        assertTrue(UserAsks.onlyRefuses("wait, not yet"))
        assertFalse(UserAsks.onlyRefuses("No, it is on the third floor"))
    }

    @Test
    fun aPlainChangeNeedsNoModel() {
        assertEquals(Severity.HIGH, UserAsks.quickChange(leak, "Make it high")!!.severity)
        assertEquals(Severity.CRITICAL, UserAsks.quickChange(leak, "it is an emergency")!!.severity)
        val asked = UserAsks.quickChange(leak, "It is a question")!!
        assertEquals("enquiry", asked.kind)
        assertEquals(Severity.LOW, asked.severity)
        // What is said about the thing itself goes to the model, however short.
        assertNull(UserAsks.quickChange(leak, "The water pressure is low"))
        assertNull(UserAsks.quickChange(leak, "It is by the lift on the third floor"))
        assertTrue(UserAsks.wantsPhoto("Add a photo"))
        assertFalse(UserAsks.wantsPhoto("The photo on the notice board is torn"))
    }

    /** A reply as the model gives it: what it wrote under each key, nothing under the rest. */
    private fun reply(vararg written: Pair<String, String>): (String) -> String = mapOf(*written).let { map -> { key -> map[key].orEmpty() } }

    @Test
    fun onlyWhatThePersonSaidIsKeptOfTheModelsWording() {
        val said = "The lift in block C has been stuck since nine and two people are inside"
        val made = UserAsks.fromModel(
            reply(
                "kind" to "issue", "title" to "lift stuck with people inside", "summary" to "The lift in block C is stuck with two people inside.",
                "location" to "Block C", "severity" to "Critical", "category" to "lifts and doors",
            ),
            said,
        )
        assertEquals("Lift stuck with people inside", made.title)
        assertEquals("Block C", made.location)
        assertEquals(Severity.CRITICAL, made.severity)
        assertEquals("Lifts and doors", made.category)

        // A place nobody said, a kind and a severity the app does not know: none is taken.
        val wild = UserAsks.fromModel(reply("kind" to "emergency", "title" to "Lift", "location" to "Tower nine basement", "severity" to "Severe", "category" to "Rockets"), said)
        assertEquals("", wild.location)
        assertEquals("issue", wild.kind)
        assertEquals("Other", wild.category)

        // Written about the person and not in their voice, the summary is dropped for their own words.
        val told = UserAsks.fromModel(reply("kind" to "enquiry", "title" to "Cafeteria open on Sunday?", "summary" to "The person is asking if the cafeteria is open on Sunday."), "Is the cafeteria open on Sunday")
        assertEquals("Is the cafeteria open on Sunday", told.summary)

        // A question is never more than low, and is not sorted as a repair.
        val asked = UserAsks.fromModel(reply("kind" to "enquiry", "title" to "Is the pool open on Sunday?", "severity" to "High", "category" to "Other"), "Is the pool open on Sunday?")
        assertEquals(Severity.LOW, asked.severity)
        assertEquals("", asked.category)

        // Changing an entry keeps what the model drops or gets wrong, and takes a place now said.
        val changed = UserAsks.fromModel(
            reply("kind" to "issue", "title" to "", "location" to "third floor near the lift", "severity" to "high"),
            leak.said + " It is on the third floor near the lift",
            leak,
        )
        assertEquals(leak.title, changed.title)
        assertEquals("Third floor near the lift", changed.location)
        assertEquals(Severity.HIGH, changed.severity)
        assertEquals(leak.category, changed.category)
    }

    @Test
    fun withNoModelThePersonsOwnWordsStand() {
        val asked = UserAsks.plain("When will the water tank be cleaned")
        assertEquals("enquiry", asked.kind)
        assertEquals(Severity.LOW, asked.severity)
        val told = UserAsks.plain("There is a gas leak near the kitchen. Please come quickly.")
        assertEquals("issue", told.kind)
        assertEquals(Severity.CRITICAL, told.severity)
        assertEquals("There is a gas leak near the kitchen", told.title)
        assertEquals("There is a gas leak near the kitchen. Please come quickly.", told.summary)
    }

    @Test
    fun theReadBackSaysWhatWillBeSentAndWhatToSayNext() {
        val words = UserAsks.readBack(leak, "Green Park Apartments")
        assertEquals(
            "I have this as an issue for Green Park Apartments: Water leaking on the stairs. Where: Block B stairs. Marked medium. " +
                "Water is leaking on the stairs of block B since morning. Say send it, or tell me what to change.",
            words,
        )
        val question = Ask("enquiry", "Is the pool open on Sunday?", "Is the pool open on Sunday?", "", Severity.LOW, "", "Is the pool open on Sunday?")
        assertEquals("I have this as a question for Green Park Apartments: Is the pool open on Sunday? Say send it, or tell me what to change.", UserAsks.readBack(question, "Green Park Apartments"))
    }

    @Test
    fun whatComesBackIsSaidOnceAndOnlyWhatIsNew() {
        fun sent(status: String, standing: String, vararg said: Word) = Sent(
            id = "i1", clientId = "c1", place = "Green Park Apartments", kind = "issue", title = "Water leaking on the stairs.", note = "", location = "",
            severity = "HIGH", status = status, standing = standing, sentTo = "Meena Iyer", hasPhoto = false, hasFixPhoto = false, thread = said.toList(),
        )
        val first = Word(1, "Nisha Rao", "User", "Water is leaking.", mine = true, unheard = false)
        val before = listOf(sent("open", "With Meena Iyer. Nobody has taken it up yet.", first))

        // Nothing is said about what the phone sees for the first time, or that has not changed.
        assertTrue(Sent.news(emptyList(), before).isEmpty())
        assertTrue(Sent.news(before, before).isEmpty())
        // An answer is read out with who gave it and what it is about.
        val answered = listOf(sent("open", "Meena Iyer (Inspector) has answered.", first, Word(2, "Meena Iyer", "Inspector", "A plumber is coming today.", mine = false, unheard = true)))
        assertEquals(listOf("Meena Iyer, inspector at Green Park Apartments, about Water leaking on the stairs: A plumber is coming today."), Sent.news(before, answered).map { it.second })
        // A step with nobody writing is said as where it now stands.
        val given = listOf(sent("assigned", "Given to Arjun Das to put right, by 12 Oct.", first))
        assertEquals(listOf("About Water leaking on the stairs at Green Park Apartments: Given to Arjun Das to put right, by 12 Oct."), Sent.news(before, given).map { it.second })
        // An answer that also moved it on is said once, as the answer.
        val closed = listOf(sent("closed", "Answered and closed.", first, Word(3, "Meena Iyer", "Inspector", "Yes, from six.", mine = false, unheard = true)))
        assertEquals(1, Sent.news(before, closed).size)
    }
}
