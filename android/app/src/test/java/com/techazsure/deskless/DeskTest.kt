package com.techazsure.deskless

import com.techazsure.deskless.account.Desk
import com.techazsure.deskless.account.DeskJob
import com.techazsure.deskless.account.Letter
import com.techazsure.deskless.account.Person
import com.techazsure.deskless.account.Step
import com.techazsure.deskless.structure.DeskAsk
import com.techazsure.deskless.structure.DeskCommands
import com.techazsure.deskless.structure.DeskPlan
import com.techazsure.deskless.structure.DueDates
import com.techazsure.deskless.structure.iso
import com.techazsure.deskless.structure.words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskTest {
    private val arjun = Person("t1", "Arjun Das", "Technical")
    private val kavya = Person("t2", "Kavya Nair", "Technical")
    private val ravi = Person("m1", "Ravi Kumar", "Manager")
    private val farah = Person("d1", "Farah Qureshi", "Managing Director")

    private fun job(id: String, title: String, next: Step, needsMe: Boolean = true, letter: Letter? = null) =
        DeskJob(id, title, "City Hospital", "High", "open", "now", listOf("2 days past its day"), next, needsMe, letter)

    /** A manager's desk: one issue to give out, one message to answer, one repair to look at, one that waits on others. */
    private val lead = Desk(
        at = 0, name = "Ravi Kumar", role = "manager", roleName = "Manager", mayDirect = true,
        jobs = listOf(
            job("a", "Oxygen cylinder not chained", Step("assign", "Give it to Arjun Das, due 12 Oct 2026", "t1", "Arjun Das", "2026-10-12", "Arjun, please take this up.")),
            job("b", "Lift stuck on third floor", Step("send", "Answer Farah Qureshi", "d1", "Farah Qureshi", text = "It is with Arjun, due Monday."), letter = Letter("Farah Qureshi", "Managing Director", "What is happening with the lift?")),
            job("c", "Leaking tap in ward two", Step("check", "Compare the two pictures, then close it or send it back")),
            job("d", "Broken tile at the entrance", Step("wait", "With Kavya Nair, due 15 Oct 2026"), needsMe = false),
        ),
    )

    /** A technician's desk: one job to repair. */
    private val hand = Desk(
        at = 0, name = "Arjun Das", role = "technical", roleName = "Technical", mayDirect = false,
        jobs = listOf(job("a", "Oxygen cylinder not chained", Step("fix", "Repair it, then mark it fixed with a photo, due 12 Oct 2026"))),
    )

    private fun plan(desk: Desk, ask: DeskAsk, said: String, technicians: List<Person> = listOf(arjun, kavya), sendTo: List<Person> = listOf(farah, arjun, kavya)): DeskPlan {
        val (picked, why) = DeskCommands.pick(ask, said, desk)
        val (number, job) = picked ?: return DeskPlan.Refused(why.orEmpty())
        return DeskCommands.plan(ask, said, desk, number, job, technicians, sendTo)
    }

    @Test
    fun saysWhatWaitsInPlainWords() {
        val said = lead.plain()
        assertTrue(said.startsWith("3 things wait for you, Ravi. First: Oxygen cylinder not chained, at City Hospital, high. Give it to Arjun Das, due 12 Oct 2026."))
        assertTrue(said.endsWith("And 1 more."))
        assertEquals("One thing waits for you, Arjun. First: Oxygen cylinder not chained, at City Hospital, high. Repair it, then mark it fixed with a photo, due 12 Oct 2026.", hand.plain())
        assertEquals("Nothing waits for you at the office right now, Meena.", Desk(0, "Meena Iyer", "inspector", "Inspector", false, emptyList()).plain())
        assertEquals("New at your desk: Oxygen cylinder not chained, at City Hospital, high.", hand.announce(hand.jobs[0]))
    }

    @Test
    fun theModelIsShownTheJobsNumberedInTheOfficesOrder() {
        val lines = lead.lines().lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("Job 1: Oxygen cylinder not chained; at City Hospital; severity High; status open; act now; because: 2 days past its day; for you to do: Give it to Arjun Das"))
        assertTrue(lines[1].contains("Farah Qureshi (Managing Director) wrote: \"What is happening with the lift?\""))
        assertTrue(lines[3].contains("waits on someone else: With Kavya Nair"))
    }

    @Test
    fun readsWhichJobIsMeant() {
        assertEquals(listOf(1), DeskCommands.numbersIn("give the first one to Arjun", 4))
        assertEquals(listOf(2), DeskCommands.numbersIn("do job 2", 4))
        assertEquals(listOf(3), DeskCommands.numbersIn("close number three", 4))
        assertEquals(listOf(4), DeskCommands.numbersIn("read me the last one", 4))
        assertTrue(DeskCommands.numbersIn("tell the manager it takes 2 days", 4).isEmpty())
        assertTrue(DeskCommands.wantsBrief("Brief me"))
        assertTrue(DeskCommands.wantsBrief("what's waiting"))
        assertFalse(DeskCommands.wantsBrief("give the first one to Arjun"))
    }

    @Test
    fun doItCarriesOutWhatTheOfficeProposes() {
        val act = plan(lead, DeskAsk("do"), "Do the first one") as DeskPlan.Act
        assertEquals("assign", act.kind)
        assertEquals("t1", act.to?.id)
        assertEquals("2026-10-12", act.dueOn)
        assertEquals("Give job 1 to Arjun Das, due 12 Oct 2026", act.words)
        assertEquals("Arjun, please take this up.", act.text)

        val answer = plan(lead, DeskAsk("do"), "go ahead with job 2") as DeskPlan.Act
        assertEquals("send", answer.kind)
        assertEquals("d1", answer.to?.id)
        assertEquals("It is with Arjun, due Monday.", answer.text)

        assertTrue(plan(lead, DeskAsk("do"), "do job 3") is DeskPlan.Refused)
        assertTrue(plan(lead, DeskAsk("do"), "do job 4") is DeskPlan.Refused)
        assertEquals("fixed", (plan(hand, DeskAsk("do"), "do it") as DeskPlan.Act).kind)
    }

    @Test
    fun givesAJobToTheTechnicianWhoWasNamed() {
        val act = plan(lead, DeskAsk("assign", person = "Kavya", due = "Friday"), "Give the first one to Kavya by Friday") as DeskPlan.Act
        assertEquals("t2", act.to?.id)
        assertEquals(DueDates.iso(DueDates.resolve("Friday")), act.dueOn)
        assertEquals(DueDates.resolve("Friday"), act.dueWords)
        // The words drafted for Arjun are not sent to Kavya.
        assertEquals("", act.text)

        // The model wrote the name out in full and handed the whole instruction back as the message.
        val full = plan(lead, DeskAsk("assign", person = "Kavya Nair", due = "Monday", message = "Give the first one to Kavya by Monday"), "Give the first one to Kavya by Monday") as DeskPlan.Act
        assertEquals("t2", full.to?.id)
        assertEquals("", full.text)
        assertTrue(full.leftOut.isEmpty())
        // A surname the model added does not pick a person: only the word that was said does.
        val wrong = plan(lead, DeskAsk("assign", person = "Arjun Nair"), "give job 1 to Arjun") as DeskPlan.Act
        assertEquals("t1", wrong.to?.id)

        val refused = plan(lead, DeskAsk("assign", person = "Suresh"), "give job 1 to Suresh") as DeskPlan.Refused
        assertTrue(refused.why.contains("Arjun Das, Kavya Nair"))
        // A name the model added that nobody said falls back to what the office proposes.
        assertEquals("t1", (plan(lead, DeskAsk("assign", person = "Kavya"), "assign the first one") as DeskPlan.Act).to?.id)
        assertTrue(plan(hand, DeskAsk("assign", person = "Kavya"), "give it to Kavya") is DeskPlan.Refused)
    }

    @Test
    fun aDayThatIsNotADateIsLeftOut() {
        val act = plan(lead, DeskAsk("assign", person = "Arjun", due = "quickly"), "Give job 1 to Arjun quickly") as DeskPlan.Act
        assertEquals("2026-10-12", act.dueOn)
        assertEquals(1, act.leftOut.size)
        assertEquals("2026-10-16", DueDates.iso("16 Oct 2026"))
        assertNull(DueDates.iso("As soon as possible"))
        assertEquals("16 Oct 2026", DueDates.words("2026-10-16"))
    }

    @Test
    fun sendsOnlyToWhomTheOfficeAllowsAndOnlyWhatWasSaid() {
        val act = plan(hand, DeskAsk("send", person = "manager", message = "I will start on it today"), "Tell the manager I will start on it today", sendTo = listOf(ravi)) as DeskPlan.Act
        assertEquals("m1", act.to?.id)
        assertEquals("I will start on it today", act.text)
        assertEquals("Send job 1 to Ravi Kumar", act.words)

        // With nobody named it goes to the first the office offers; with nothing to say it is refused.
        assertEquals("m1", (plan(hand, DeskAsk("send", message = "I need a ladder"), "Say I need a ladder", sendTo = listOf(ravi)) as DeskPlan.Act).to?.id)
        assertTrue(plan(hand, DeskAsk("send"), "send it on", sendTo = listOf(ravi)) is DeskPlan.Refused)
        assertTrue(plan(hand, DeskAsk("send", person = "Farah", message = "I will start today"), "Tell Farah I will start today", sendTo = listOf(ravi)) is DeskPlan.Refused)
        // A message the model made up is not sent.
        assertTrue(plan(hand, DeskAsk("send", person = "manager", message = "The part is on order"), "tell the manager", sendTo = listOf(ravi)) is DeskPlan.Refused)
    }

    @Test
    fun onlyTheRightPersonMarksFixedClosesOrSendsBack() {
        val fixed = plan(hand, DeskAsk("fixed", message = "replaced the chain"), "I fixed it, replaced the chain") as DeskPlan.Act
        assertEquals("fixed", fixed.kind)
        assertEquals("Replaced the chain", fixed.text)
        assertTrue(plan(lead, DeskAsk("fixed"), "I fixed job 1") is DeskPlan.Refused)
        // The model handed the whole sentence back as the message: what follows the comma is the note.
        assertEquals("Taped the wire", (plan(hand, DeskAsk("fixed", message = "I fixed the first one, taped the wire"), "I fixed the first one, taped the wire") as DeskPlan.Act).text)
        assertEquals("", (plan(hand, DeskAsk("fixed", message = "I fixed the first one"), "I fixed the first one") as DeskPlan.Act).text)

        assertEquals("Accept the repair and close job 3", (plan(lead, DeskAsk("close"), "close job 3") as DeskPlan.Act).words)
        assertEquals("back", (plan(lead, DeskAsk("back", message = "still dripping"), "send job 3 back, still dripping") as DeskPlan.Act).kind)
        assertTrue(plan(hand, DeskAsk("close"), "close it") is DeskPlan.Refused)
    }

    @Test
    fun aJobThatIsNotThereIsRefusedAndAQuestionIsAQuestion() {
        assertTrue((plan(lead, DeskAsk("close"), "close job 9") as DeskPlan.Refused).why.contains("no job 9"))
        assertTrue(plan(lead, DeskAsk("close"), "close it") is DeskPlan.Refused)
        // A description leaves the choice to the model; a single job needs no number.
        assertEquals(2, (plan(lead, DeskAsk("read", jobs = listOf(2)), "tell me about the lift") as DeskPlan.Read).number)
        assertEquals(1, (plan(hand, DeskAsk("read"), "tell me more") as DeskPlan.Read).number)
        assertEquals(DeskPlan.Question, plan(lead, DeskAsk("question", jobs = listOf(1)), "which of the first two is worse"))
    }

    @Test
    fun matchesAPersonByNameOrByWhatTheyAre() {
        val people = listOf(ravi, farah, arjun, kavya)
        assertEquals(arjun, DeskCommands.match("Arjun", people))
        assertEquals(kavya, DeskCommands.match("kavya nair", people))
        assertEquals(ravi, DeskCommands.match("the manager", people))
        assertEquals(farah, DeskCommands.match("managing director", people))
        assertNull(DeskCommands.match("the technician", people))
        assertNull(DeskCommands.match("Suresh", people))
    }
}
