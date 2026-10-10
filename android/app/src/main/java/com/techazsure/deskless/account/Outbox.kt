package com.techazsure.deskless.account

import android.content.Context
import com.techazsure.deskless.capture.normalizeJpeg
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Readings
import com.techazsure.deskless.report.Review
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A finished report that waits to be sent to the office: what it holds, its PDF and a picture for each issue. */
class WaitingReport(val folder: File, val about: JSONObject, val pdf: File, val pictures: List<File>)

/**
 * Reports on their way to the office. A report is put here the moment it is built, network or not, with
 * everything the office needs copied beside it, so it can still be sent after the walk has been cleared
 * from the phone. It leaves when the server has taken it.
 */
class Outbox(context: Context) {
    private val dir = File(context.filesDir, "outbox").apply { mkdirs() }
    private val sentList = File(dir, "sent.txt")

    /**
     * Queues the report in [pdf]. Each issue's picture is copied at a size fit to send; [fileOf] finds it
     * in the walk's own storage. A report built again under the same name takes the earlier one's place.
     */
    @Synchronized
    fun add(pdf: File, inspection: Inspection, issues: List<Issue>, review: Review?, signed: Boolean, fileOf: (String) -> File) {
        val folder = File(dir, pdf.nameWithoutExtension)
        folder.deleteRecursively()
        folder.mkdirs()
        val listed = JSONArray()
        for (issue in issues) {
            val measured = issue.stamp?.let(Readings::describe).orEmpty() + issue.readings.map(Readings::describe)
            listed.put(
                JSONObject()
                    .put("id", issue.id)
                    .put("title", issue.title)
                    .put("note", issue.note)
                    .put("location", issue.location.ifBlank { issue.place })
                    .put("category", issue.category)
                    .put("severity", issue.severity.name)
                    .put("owner", issue.owner)
                    .put("due", issue.dueDate)
                    .put("check", issue.check.name)
                    .put("seen", issue.seen)
                    .put("measured", measured.joinToString(" · "))
                    .put("lat", issue.lat ?: JSONObject.NULL)
                    .put("lon", issue.lon ?: JSONObject.NULL)
                    .put("captured_at", issue.createdAt)
                    .put("resolved", issue.resolved)
                    .put("fixed_at", issue.fixedAt)
                    .put("photo", copy(fileOf(issue.photoFile), File(folder, "${issue.id}.jpg")))
                    .put("fix_photo", issue.fixPhoto?.let { copy(fileOf(it), File(folder, "${issue.id}-fix.jpg")) }.orEmpty()),
            )
        }
        val about = JSONObject()
            // One walk is one report at the office: built again, it replaces what was sent before.
            .put("walk", inspection.startedAt.toString())
            .put("file", pdf.name)
            .put("template", inspection.template.name)
            .put("title", inspection.template.title + " report")
            .put("place", inspection.site)
            .put("person", inspection.inspector)
            .put("built_at", System.currentTimeMillis())
            .put("review", review?.verdict?.name.orEmpty())
            .put("review_reason", review?.reason.orEmpty())
            .put("signed", signed)
            .put("issues", listed)
        // Written last: a folder without it was interrupted and is not sent.
        File(folder, ABOUT).writeText(about.toString())
        sentList.writeText((sent() - pdf.name).joinToString("\n"))
    }

    /** The reports still to send, oldest first. [pdfOf] finds a report's PDF by its file name. */
    @Synchronized
    fun waiting(pdfOf: (String) -> File): List<WaitingReport> =
        dir.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.lastModified() }.mapNotNull { folder ->
            val about = runCatching { JSONObject(File(folder, ABOUT).readText()) }.getOrNull() ?: return@mapNotNull null
            val pdf = pdfOf(about.optString("file"))
            if (!pdf.isFile) {
                // The report was removed from the phone before it could be sent.
                folder.deleteRecursively()
                return@mapNotNull null
            }
            WaitingReport(folder, about, pdf, folder.listFiles { file -> file.name.endsWith(".jpg") }.orEmpty().toList())
        }

    /** File names of the reports that wait to be sent, and of those the office has. */
    @Synchronized
    fun waitingNames(): Set<String> =
        dir.listFiles { file -> file.isDirectory && File(file, ABOUT).isFile }.orEmpty().map { it.name + ".pdf" }.toSet()

    @Synchronized
    fun sent(): Set<String> = runCatching { sentList.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    /** The server has the report. */
    @Synchronized
    fun done(report: WaitingReport) {
        sentList.writeText((sent() + report.pdf.name).joinToString("\n"))
        report.folder.deleteRecursively()
    }

    /** The server will never take this report as it is; it stops holding up the ones behind it. */
    @Synchronized
    fun drop(report: WaitingReport) {
        report.folder.deleteRecursively()
    }

    /** Copies a picture at a size fit to send. Returns the copy's name, or blank when there is no picture. */
    private fun copy(from: File, to: File): String = runCatching {
        from.copyTo(to, overwrite = true)
        normalizeJpeg(to, PICTURE_EDGE)
        to.name
    }.getOrElse {
        to.delete()
        ""
    }

    private companion object {
        const val ABOUT = "about.json"
        const val PICTURE_EDGE = 1600
    }
}
