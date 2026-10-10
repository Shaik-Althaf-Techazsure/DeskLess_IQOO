package com.techazsure.deskless

import com.techazsure.deskless.data.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateTest {
    @Test
    fun thereIsAReportTypeForEveryKindOfPlace() {
        // Names are what a stored walk and the server remember a report type by: they are added to, never changed.
        assertEquals(
            listOf("GENERAL", "SITE", "FACTORY", "WAREHOUSE", "CAMPUS", "OFFICE", "RESIDENTIAL", "RETAIL", "HOTEL", "HOSPITAL", "MEDICATION", "COMMUNITY"),
            Template.entries.map { it.name },
        )
        // A new walk starts with the type that belongs to no trade, and so does a name this phone does not know.
        assertEquals("GENERAL", com.techazsure.deskless.data.Inspection().template.name)
        assertEquals(Template.GENERAL, Template.named("NO-SUCH-TYPE"))
        assertEquals(Template.HOSPITAL, Template.named("HOSPITAL"))
        assertEquals(Template.entries.size, Template.entries.map { it.title }.toSet().size)
    }

    @Test
    fun everyReportTypeTellsTheModelAndThePersonWhatTheyNeed() {
        for (template in Template.entries) {
            val name = template.name
            // The server keeps the name in twenty characters.
            assertTrue(name, name.length <= 20)
            assertTrue(name, template.reportTitle == template.reportTitle.uppercase() && template.reportTitle.endsWith("REPORT"))
            // The model is told who is speaking and how to rate what they say, at each of the four levels.
            assertTrue(name, template.reporter.startsWith("a"))
            for (level in listOf("Critical is", "High is", "Medium", "Low is")) assertTrue("$name: $level", level in template.severityGuide)
            // An issue can always be sorted somewhere, and no type is offered twice.
            assertEquals(name, "Other", template.categories.last())
            assertTrue(name, template.categories.size in 5..8)
            assertEquals(name, template.categories.size, template.categories.toSet().size)
            assertTrue(name, !template.custom)
            for (text in listOf(template.blurb, template.placeLabel, template.personLabel, template.ownerLabel, template.callToAction, template.placeHint, template.personHint, template.guide)) {
                assertTrue(name, text.isNotBlank())
            }
        }
    }

    @Test
    fun aCompanyCanMakeAReportTypeOfItsOwn() {
        val made = Template.made(
            key = "OWN-1A2B3C4D", title = "Bus depot check", checks = "buses, bays and the fuel point of a bus depot.",
            place = "Depot", kinds = listOf("Tyres", " Brakes ", "tyres", "Other", "Fuel point", ""), serious = "a fuel leak or a bus with failed brakes.",
        )
        assertTrue(made.custom)
        assertEquals("OWN-1A2B3C4D", made.name)
        assertEquals("BUS DEPOT CHECK REPORT", made.reportTitle)
        assertEquals("Depot", made.placeLabel)
        assertEquals("Name the depot", made.placeHint)
        assertEquals("a person inspecting buses, bays and the fuel point of a bus depot", made.reporter)
        // The kinds of issue are tidied, and an issue can always be sorted under Other.
        assertEquals(listOf("Tyres", "Brakes", "Fuel point", "Other"), made.categories)
        assertTrue(made.severityGuide.startsWith(Template.GENERAL.severityGuide))
        assertTrue(made.severityGuide.endsWith("In this kind of place, treat as serious: a fuel leak or a bus with failed brakes."))
        // With nothing but a name it still works, worded as the general type is.
        val bare = Template.made("OWN-2", "Lift check", "", "", emptyList())
        assertEquals(listOf("Other"), bare.categories)
        assertEquals("Place", bare.placeLabel)
        assertEquals("a person inspecting Lift check", bare.reporter)
        assertEquals(Template.GENERAL.severityGuide, bare.severityGuide)
    }
}
