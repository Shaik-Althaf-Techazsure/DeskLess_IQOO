package com.techazsure.deskless.report

import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.structure.DueDates
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The working files that travel with the PDF to the laptop: a spreadsheet of the issues, a calendar of the
 * deadlines and a task list for each owner. A PDF is read; these are acted on.
 */
object ReportPack {
    private val stamp get() = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH)
    private val dueFormat get() = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).apply { isLenient = false }

    /** The files that belong to [pdf] and exist, the PDF first. */
    fun files(pdf: File): List<File> {
        val base = pdf.nameWithoutExtension
        return listOf(pdf, sibling(pdf, "$base.csv"), sibling(pdf, "$base.ics"), sibling(pdf, "$base-owners.txt"))
            .filter { it.isFile }
    }

    /** Writes the working files beside [pdf]. The calendar is skipped when no issue has a datable deadline. */
    fun write(pdf: File, inspection: Inspection, issues: List<Issue>, now: Date = Date()) {
        val base = pdf.nameWithoutExtension
        // The byte-order mark makes Excel read the file as UTF-8 rather than guess.
        sibling(pdf, "$base.csv").writeText("\uFEFF" + csv(inspection, issues))
        calendar(inspection, issues, now)?.let { sibling(pdf, "$base.ics").writeText(it) }
        sibling(pdf, "$base-owners.txt").writeText(ownerLists(inspection, issues))
    }

    fun csv(inspection: Inspection, issues: List<Issue>): String {
        val header = listOf(
            "No", "Issue", "Type", "Severity", "Location", inspection.template.ownerLabel, "Due", "Status",
            "AI check", "Latitude", "Longitude", "Captured", "Note",
        )
        val rows = issues.mapIndexed { i, issue ->
            listOf(
                "${i + 1}",
                issue.title,
                issue.category,
                issue.severity.label,
                issue.location,
                issue.owner,
                issue.dueDate,
                if (issue.resolved) "Fixed" else "Open",
                checkWords(issue.check),
                issue.lat?.let { String.format(Locale.ENGLISH, "%.6f", it) }.orEmpty(),
                issue.lon?.let { String.format(Locale.ENGLISH, "%.6f", it) }.orEmpty(),
                stamp.format(Date(issue.createdAt)),
                issue.note,
            )
        }
        return (listOf(header) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::cell) }
    }

    /** One all-day entry per open issue whose deadline is a date. Null when there is none. */
    fun calendar(inspection: Inspection, issues: List<Issue>, now: Date = Date()): String? {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        val made = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(now)
        val events = issues.mapIndexedNotNull { i, issue ->
            if (issue.resolved) return@mapIndexedNotNull null
            val due = when (issue.dueDate) {
                DueDates.AS_SOON_AS_POSSIBLE -> now
                else -> runCatching { dueFormat.parse(issue.dueDate) }.getOrNull()
            } ?: return@mapIndexedNotNull null
            val next = Date(due.time + 24L * 60 * 60 * 1000)
            listOf(
                "BEGIN:VEVENT",
                "UID:${issue.id}-${inspection.startedAt}@deskless",
                "DTSTAMP:$made",
                "DTSTART;VALUE=DATE:${day.format(due)}",
                "DTEND;VALUE=DATE:${day.format(next)}",
                "SUMMARY:" + text("[${issue.severity.label}] ${issue.title.ifBlank { "Issue ${i + 1}" }}" +
                    if (issue.owner.isNotBlank()) " - ${issue.owner}" else ""),
                "DESCRIPTION:" + text(
                    listOf(inspection.site, issue.location, issue.note).filter { it.isNotBlank() }.joinToString(". ")
                ),
                "END:VEVENT",
            ).joinToString("\r\n")
        }
        if (events.isEmpty()) return null
        return (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//DeskLess//Report pack//EN") + events + "END:VCALENDAR")
            .joinToString("\r\n", postfix = "\r\n")
    }

    /** What each owner has to do, most severe first, as plain text that can be pasted into any message. */
    fun ownerLists(inspection: Inspection, issues: List<Issue>): String {
        val open = issues.withIndex().filter { !it.value.resolved }
        val head = listOf(
            inspection.template.title + (if (inspection.site.isNotBlank()) ": ${inspection.site}" else ""),
            "Open issues by ${inspection.template.ownerLabel.lowercase(Locale.ENGLISH)}, as of ${stamp.format(Date())}",
        )
        if (open.isEmpty()) return (head + "" + "No open issues.").joinToString("\n") + "\n"
        val groups = open.groupBy { it.value.owner.ifBlank { "Not assigned" } }
            .toSortedMap(compareBy<String> { it == "Not assigned" }.thenBy { it.lowercase(Locale.ENGLISH) })
        val body = groups.flatMap { (owner, items) ->
            listOf("", "$owner (${items.size})") + items.sortedByDescending { it.value.severity.ordinal }.map { (i, issue) ->
                listOfNotNull(
                    "  ${i + 1}. [${issue.severity.label}] ${issue.title.ifBlank { "Untitled issue" }}",
                    issue.location.takeIf { it.isNotBlank() },
                    issue.dueDate.takeIf { it.isNotBlank() }?.let { "due $it" },
                ).joinToString(" | ")
            }
        }
        return (head + body).joinToString("\n") + "\n"
    }

    private fun sibling(pdf: File, name: String) = File(pdf.parentFile, name)

    private fun checkWords(check: Check): String = when (check) {
        Check.NONE -> ""
        Check.MATCH -> "Picture matches"
        Check.CONFIRMED -> "Confirmed on site"
        Check.UNSURE -> "Not confirmed"
        Check.MISMATCH -> "May not match"
    }

    /** A spreadsheet cell: quoted when it holds a comma, a quote or a line break. */
    private fun cell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    /** Calendar text: commas, semicolons, backslashes and line breaks are escaped. */
    private fun text(value: String): String =
        value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n")
}
