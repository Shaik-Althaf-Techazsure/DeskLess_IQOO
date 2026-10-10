package com.techazsure.deskless.report

import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.data.Instrument
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Readings
import com.techazsure.deskless.structure.DueDates
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The working files that travel with the PDF to the laptop: a spreadsheet of the issues, a calendar of the
 * deadlines and a task list for each owner. A PDF is read; these are acted on.
 */
object ReportPack {
    private val stamp get() = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH)
    private val dueFormat get() = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).apply { isLenient = false }

    /** The files that belong to [pdf] and exist, the PDF first. A signed report brings its signature. */
    fun files(pdf: File): List<File> {
        val base = pdf.nameWithoutExtension
        return listOf(pdf, sibling(pdf, "$base.csv"), sibling(pdf, "$base.ics"), sibling(pdf, "$base-owners.txt"), SignOff.fileFor(pdf))
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
            "AI check", "Latitude", "Longitude", "Tagged place", "Facing", "Light (lux)", "Slope (deg)", "Noise (dB)",
            "Vibration (m/s2)", "Captured", "Note",
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
                issue.place,
                issue.stamp?.heading?.let(Readings::compassPoint).orEmpty(),
                issue.stamp?.lux?.let { String.format(Locale.ENGLISH, "%.0f", it) }.orEmpty(),
                measured(issue, Instrument.LEVEL, "%.1f"),
                measured(issue, Instrument.NOISE, "%.0f"),
                measured(issue, Instrument.VIBRATION, "%.2f"),
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

    /**
     * Packs a report and its working files into the one file that is sent to a linked phone. [about] is
     * what the receiving phone needs to list the report: its type, place, counts and review.
     */
    fun zip(pdf: File, about: JSONObject, out: File) {
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(ABOUT))
            zip.write(JSONObject(about.toString()).put("pdf", pdf.name).toString().toByteArray())
            zip.closeEntry()
            files(pdf).forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Unpacks a report that arrived from another phone into [folder]. [tag] is added to every file name, so
     * a received report can never overwrite one built here. Returns the PDF and what the sender said about
     * it, or null when the file is not a DeskLess pack; nothing but a pack's own kinds of file is written.
     */
    fun unzip(pack: File, folder: File, tag: String): Pair<File, JSONObject>? = runCatching {
        ZipFile(pack).use { zip ->
            val about = zip.getEntry(ABOUT)?.let { JSONObject(zip.getInputStream(it).readBytes().toString(Charsets.UTF_8)) } ?: return null
            val base = about.optString("pdf").removeSuffix(".pdf")
            if (!SAFE_NAME.matches(base) || zip.getEntry("$base.pdf") == null) return null
            val kept = base + "-" + tag.filter { it.isLetterOrDigit() }.take(12)
            for (entry in zip.entries()) {
                val ending = entry.name.removePrefix(base)
                // Only the files a pack is made of, named after its report, and of a sane size.
                if (entry.isDirectory || entry.name == ABOUT || !entry.name.startsWith(base) || ending !in ENDINGS) continue
                if (entry.size > MAX_FILE_BYTES) return null
                File(folder, kept + ending).outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
            }
            File(folder, "$kept.pdf") to about
        }
    }.getOrNull()

    private const val ABOUT = "about.json"
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,120}")
    private val ENDINGS = setOf(".pdf", ".csv", ".ics", "-owners.txt", ".sig.json")
    private const val MAX_FILE_BYTES = 60L * 1024 * 1024

    private fun sibling(pdf: File, name: String) = File(pdf.parentFile, name)

    /** The number an instrument read for this issue, or blank when it was not used. */
    private fun measured(issue: Issue, kind: Instrument, format: String): String =
        issue.readings.lastOrNull { it.kind == kind }?.let { String.format(Locale.ENGLISH, format, it.value) }.orEmpty()

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
