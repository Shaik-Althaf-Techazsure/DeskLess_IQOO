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
import java.security.MessageDigest

/** What the office is told about a walk, and the copying of pictures at a size fit to send. */
object WalkNotes {
    const val PICTURE_EDGE = 1600

    /**
     * A walk and its issues as the server takes them. One walk is one report at the office: sent again,
     * it replaces what was sent before. Each picture is named after its issue; the server keeps the one
     * it has when none is sent along.
     */
    fun describe(inspection: Inspection, issues: List<Issue>, file: String = "", review: Review? = null, signed: Boolean = false): JSONObject {
        val listed = JSONArray()
        for (issue in issues) {
            val measured = issue.stamp?.let { Readings.describe(it) }.orEmpty() + issue.readings.map { Readings.describe(it) }
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
                    .put("photo", photoName(issue))
                    .put("fix_photo", if (issue.fixPhoto != null) fixName(issue) else ""),
            )
        }
        return JSONObject()
            .put("walk", inspection.startedAt.toString())
            .put("file", file)
            .put("template", inspection.template.name)
            .put("title", inspection.template.title + " report")
            .put("place", inspection.site)
            .put("person", inspection.inspector)
            .put("built_at", System.currentTimeMillis())
            .put("review", review?.verdict?.name.orEmpty())
            .put("review_reason", review?.reason.orEmpty())
            .put("signed", signed)
            .put("issues", listed)
    }

    fun photoName(issue: Issue) = "${issue.id}.jpg"

    fun fixName(issue: Issue) = "${issue.id}-fix.jpg"

    /** Copies a picture at a size fit to send. Returns the copy, or null when there is no picture to copy. */
    fun copy(from: File, to: File): File? = runCatching {
        from.copyTo(to, overwrite = true)
        normalizeJpeg(to, PICTURE_EDGE)
        to
    }.getOrElse {
        to.delete()
        null
    }

    /** A short mark of what a walk holds, to tell whether it has changed since it was last sent. */
    fun mark(about: JSONObject): String {
        val told = listOf("walk", "template", "place", "person", "issues").joinToString("|") { about.opt(it).toString() }
        return MessageDigest.getInstance("SHA-256").digest(told.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

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
        for (issue in issues) {
            WalkNotes.copy(fileOf(issue.photoFile), File(folder, WalkNotes.photoName(issue)))
            issue.fixPhoto?.let { WalkNotes.copy(fileOf(it), File(folder, WalkNotes.fixName(issue))) }
        }
        // Written last: a folder without it was interrupted and is not sent.
        File(folder, ABOUT).writeText(WalkNotes.describe(inspection, issues, pdf.name, review, signed).toString())
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

    private companion object {
        const val ABOUT = "about.json"
    }
}

/**
 * The walk that is going on, sent to the office as it grows: an issue is on the dashboards moments after
 * it is raised, without waiting for the report to be built. A picture is sent once; after that only the
 * words travel. With no network nothing is lost: the next change, or the network coming back, sends the
 * walk as it then stands.
 */
class LiveWalk(context: Context) {
    private val copies = File(context.cacheDir, "live").apply { mkdirs() }
    private val notes = File(context.filesDir, "outbox").apply { mkdirs() }
    private val sentPictures = File(notes, "live-pictures.txt")
    private val lastMark = File(notes, "live-mark.txt")

    /** What to send now: the walk in words, and the pictures the office does not have yet. */
    @Synchronized
    fun prepare(inspection: Inspection, issues: List<Issue>, fileOf: (String) -> File): Pair<JSONObject, List<File>> {
        val have = have()
        copies.listFiles().orEmpty().forEach { it.delete() }
        val pictures = issues.flatMap { issue ->
            listOfNotNull(
                issue.photoFile.takeIf { key(inspection, issue, it) !in have }?.let { WalkNotes.copy(fileOf(it), File(copies, WalkNotes.photoName(issue))) },
                issue.fixPhoto?.takeIf { key(inspection, issue, it) !in have }?.let { WalkNotes.copy(fileOf(it), File(copies, WalkNotes.fixName(issue))) },
            )
        }
        return WalkNotes.describe(inspection, issues) to pictures
    }

    /** True when the office has been sent something of this walk. */
    @Synchronized
    fun knows(inspection: Inspection): Boolean = have().any { it.startsWith("${inspection.startedAt}/") }

    /** True when the walk has not changed since the office last had it. */
    @Synchronized
    fun unchanged(about: JSONObject): Boolean = runCatching { lastMark.readText() }.getOrNull() == WalkNotes.mark(about)

    /** The office has the walk as [about] tells it, and every picture of [issues]. */
    @Synchronized
    fun done(inspection: Inspection, issues: List<Issue>, about: JSONObject) {
        // Only this walk's pictures are remembered; a new walk starts the list afresh.
        val mine = have().filter { it.startsWith("${inspection.startedAt}/") }.toSet() +
            issues.flatMap { issue -> listOfNotNull(issue.photoFile, issue.fixPhoto).map { key(inspection, issue, it) } }
        sentPictures.writeText(mine.joinToString("\n"))
        remember(about)
    }

    /** Notes what the office has, also when it came as a built report rather than as a walk in progress. */
    @Synchronized
    fun remember(about: JSONObject) {
        lastMark.writeText(WalkNotes.mark(about))
    }

    private fun have(): Set<String> = runCatching { sentPictures.readLines().toSet() }.getOrDefault(emptySet())

    /** A picture is known by its walk, its issue and its file: a picture taken again is a new one. */
    private fun key(inspection: Inspection, issue: Issue, file: String) = "${inspection.startedAt}/${issue.id}/$file"
}
