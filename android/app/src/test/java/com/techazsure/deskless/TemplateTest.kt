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
            listOf("SITE", "CAMPUS", "OFFICE", "RESIDENTIAL", "RETAIL", "HOSPITAL", "MEDICATION", "COMMUNITY"),
            Template.entries.map { it.name },
        )
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
            for (text in listOf(template.blurb, template.placeLabel, template.personLabel, template.ownerLabel, template.callToAction, template.placeHint, template.personHint, template.guide)) {
                assertTrue(name, text.isNotBlank())
            }
        }
    }
}
