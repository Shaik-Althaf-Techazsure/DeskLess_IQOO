package com.techazsure.deskless

import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Template
import com.techazsure.deskless.structure.Asking
import com.techazsure.deskless.structure.Command
import com.techazsure.deskless.structure.Commands
import com.techazsure.deskless.structure.DueDates
import com.techazsure.deskless.structure.Field
import com.techazsure.deskless.structure.Gap
import com.techazsure.deskless.structure.Memory
import com.techazsure.deskless.structure.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    private val issues = listOf(
        Issue(id = "a", createdAt = 1, photoFile = "a.jpg", title = "Cracked wall", severity = Severity.MEDIUM),
        Issue(id = "b", createdAt = 2, photoFile = "b.jpg", title = "Loose wire", severity = Severity.HIGH, owner = "Anil"),
        Issue(id = "c", createdAt = 3, photoFile = "c.jpg", title = "Blocked exit", severity = Severity.HIGH),
    )
    private val site = Template.SITE

    private fun plan(command: Command, said: String, memory: Memory? = null) = Commands.plan(command, said, issues, site, memory)

    @Test
    fun tellsAnInstructionFromAQuestion() {
        assertTrue(Commands.looksLikeOne("Mark issue 3 critical and give it to Ravi"))
        assertTrue(Commands.looksLikeOne("Please show the open ones"))
        assertTrue(Commands.looksLikeOne("can you assign issue two to the electrical team"))
        assertFalse(Commands.looksLikeOne("What should be fixed first?"))
        assertFalse(Commands.looksLikeOne("Which issues did I mark critical"))
        assertFalse(Commands.looksLikeOne("Is anything here a danger to people"))
        assertTrue(Commands.wantsCapture("New issue"))
        assertTrue(Commands.wantsCapture("please take a picture"))
        assertFalse(Commands.wantsCapture("how do I add an issue"))
    }

    @Test
    fun readsIssueNumbersOutOfWhatWasSaid() {
        assertEquals(listOf(3), Commands.numbersIn("mark issue 3 critical due in 2 days"))
        assertEquals(listOf(2, 5), Commands.numbersIn("give issues two and five to Ravi"))
        assertEquals(listOf(1, 4), Commands.numbersIn("the first issue and issue number 4"))
        assertEquals(listOf(7), Commands.numbersIn("open the last issue", count = 7))
        assertTrue(Commands.numbersIn("give the electrical ones to Ravi by Friday").isEmpty())
    }

    @Test
    fun aSpokenInstructionBecomesAListOfChanges() {
        val made = plan(
            Command(action = "change", issues = listOf(3), severity = "Critical", owner = "Ravi", due = "Friday"),
            "Mark issue 3 critical, give it to Ravi, due Friday",
        ) as Plan.Changes
        assertEquals(listOf(Field.SEVERITY, Field.OWNER, Field.DUE), made.list.map { it.field })
        assertTrue(made.list.all { it.issueId == "c" && it.number == 3 })
        assertEquals("Critical", made.list[0].to)
        assertEquals("High", made.list[0].from)
        assertEquals("Ravi", made.list[1].to)
        assertEquals(DueDates.resolve("Friday"), made.list[2].to)
        assertTrue(made.leftOut.isEmpty())
        assertTrue(Commands.spoken(made).startsWith("Issue 3: severity Critical, owner Ravi"))
    }

    @Test
    fun theNumbersThePersonSaidWinOverTheModels() {
        val made = plan(Command(action = "change", issues = listOf(1), severity = "Low"), "make issue 2 low") as Plan.Changes
        assertEquals(listOf("b"), made.list.map { it.issueId })
    }

    @Test
    fun aValueTheModelAddedIsLeftOutAndSaidSo() {
        val made = plan(
            Command(action = "change", issues = listOf(1), severity = "High", owner = "Suresh", due = "Monday"),
            "mark issue 1 high",
        ) as Plan.Changes
        assertEquals(listOf(Field.SEVERITY), made.list.map { it.field })
        assertEquals(2, made.leftOut.size)
    }

    @Test
    fun anIssueThatDoesNotExistIsRefused() {
        val refused = plan(Command(action = "change", issues = listOf(9), severity = "High"), "mark issue 9 high") as Plan.Refused
        assertTrue(refused.why.contains("no issue 9"))
        assertTrue(plan(Command(action = "change", severity = "High"), "mark it high") is Plan.Refused)
        assertTrue(plan(Command(action = "change", issues = listOf(2)), "change issue 2") is Plan.Refused)
    }

    @Test
    fun whatAlreadyStandsIsNotAChange() {
        assertTrue(plan(Command(action = "change", issues = listOf(2), severity = "High", owner = "Anil"), "mark issue 2 high for Anil") is Plan.Refused)
    }

    @Test
    fun aDescriptionLeavesTheChoiceOfIssuesToTheModel() {
        val made = plan(Command(action = "change", issues = listOf(1, 3), owner = "Structural team"), "give the wall and the exit to the structural team") as Plan.Changes
        assertEquals(listOf("a", "c"), made.list.map { it.issueId })
        assertEquals("Issues 1 and 3: owner Structural team.", Commands.spoken(made))
    }

    @Test
    fun aKnownNameIsWrittenInFull() {
        val memory = Memory().apply { learn("x", "", "Ravi Kumar", "") }
        val made = plan(Command(action = "change", issues = listOf(1), owner = "Ravi"), "give issue 1 to Ravi", memory) as Plan.Changes
        assertEquals("Ravi Kumar", made.list.single().to)
    }

    @Test
    fun showingOpeningAndAskingAreNotChanges() {
        assertEquals(Filter.Open, (plan(Command(action = "show", show = "open"), "show the open ones") as Plan.Show).filter)
        assertEquals(Filter.BySeverity(Severity.CRITICAL), (plan(Command(action = "show", show = "critical"), "show the critical ones") as Plan.Show).filter)
        assertEquals("b", (plan(Command(action = "open", issues = listOf(2)), "open issue 2") as Plan.Open).issueId)
        assertEquals(Plan.Question, plan(Command(action = "question"), "mark my words, what is open"))
    }

    @Test
    fun asksOneQuestionForWhateverIsMissing() {
        assertEquals(listOf(Gap.WHERE, Gap.WHO, Gap.WHEN), Asking.gaps(placed = false, owner = "", due = ""))
        assertEquals(listOf(Gap.WHEN), Asking.gaps(placed = true, owner = "Ravi", due = ""))
        assertEquals("Where is it, who should deal with it, and by when?", Asking.question(listOf(Gap.WHERE, Gap.WHO, Gap.WHEN)))
        assertEquals("Who should deal with it, and by when?", Asking.question(listOf(Gap.WHO, Gap.WHEN)))
        assertEquals("By when should it be done?", Asking.question(listOf(Gap.WHEN)))
        assertEquals("Where is it?", Asking.question(listOf(Gap.WHERE)))
        assertEquals("Ravi Kumar", Asking.direct("It is Ravi Kumar."))
        assertEquals("Friday", Asking.direct("by Friday"))
    }
}
