package com.techazsure.deskless.report

import android.content.Context
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Template
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What is remembered about one built report, so the library can list, filter and sort without opening PDFs. */
data class ReportEntry(
    val fileName: String,
    /** Null for a report built before the library existed; its kind is not known. */
    val template: Template?,
    val place: String,
    val person: String,
    val builtAt: Long,
    val issues: Int,
    /** Issues rated High or Critical. */
    val serious: Int,
    val flagged: Int,
    /** The AI review's verdict, as the name of a [Verdict]; blank for a report built before there was one. */
    val review: String = "",
    val reviewReason: String = "",
    /** The name of the phone this report came from over the link; blank for a report built on this phone. */
    val from: String = "",
    /** When it arrived here, for a report from another phone; 0 for one built on this phone. */
    val receivedAt: Long = 0,
    /** True for a received report nobody has opened on this phone yet. */
    val unread: Boolean = false,
) {
    /** When the report came to be on this phone: built here, or arrived. The reports list is ordered by it. */
    val since: Long get() = maxOf(builtAt, receivedAt)
}

/** The PDFs built on this phone, with a small index beside them. */
class ReportLibrary(context: Context) {
    private val dir = File(context.filesDir, "reports").apply { mkdirs() }
    private val index = File(dir, "index.json")

    /** Where the reports are kept; a report received from another phone is unpacked here. */
    val folder: File get() = dir

    fun file(entry: ReportEntry) = File(dir, entry.fileName)

    /** Every PDF still on the phone, newest first. Reports made before the index are listed from the file alone. */
    fun load(): List<ReportEntry> {
        val indexed = try {
            val array = if (index.exists()) JSONArray(index.readText()) else JSONArray()
            (0 until array.length()).map { entryFrom(array.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        val known = indexed.associateBy { it.fileName }

        return dir.listFiles { file -> file.name.endsWith(".pdf") }.orEmpty()
            .map { file ->
                known[file.name] ?: ReportEntry(
                    fileName = file.name,
                    template = null,
                    place = file.name.removePrefix("DeskLess-").removeSuffix(".pdf").replace(TIMESTAMP, "").replace('-', ' ').trim(),
                    person = "",
                    builtAt = file.lastModified(),
                    issues = 0,
                    serious = 0,
                    flagged = 0,
                )
            }
            .sortedByDescending { it.since }
    }

    fun record(file: File, inspection: Inspection, issues: List<Issue>, flagged: Int, review: Review? = null): ReportEntry =
        add(
            ReportEntry(
                fileName = file.name,
                template = inspection.template,
                place = inspection.site,
                person = inspection.inspector,
                builtAt = System.currentTimeMillis(),
                issues = issues.size,
                serious = issues.count { it.severity == Severity.HIGH || it.severity == Severity.CRITICAL },
                flagged = flagged,
                review = review?.verdict?.name.orEmpty(),
                reviewReason = review?.reason.orEmpty(),
            )
        )

    /** Lists a report that arrived from another phone, from what that phone said about it. */
    fun receive(file: File, about: JSONObject, from: String): ReportEntry =
        add(entryFrom(about, file.name).copy(from = from, receivedAt = System.currentTimeMillis(), unread = true))

    /** Notes that a received report has been opened. */
    fun markRead(entry: ReportEntry): ReportEntry = add(entry.copy(unread = false))

    /** What the phone at the other end needs to list this report as this phone does. */
    fun describe(entry: ReportEntry): JSONObject = toJson(entry)

    @Synchronized
    private fun add(entry: ReportEntry): ReportEntry {
        val all = load().filter { it.template != null && it.fileName != entry.fileName } + entry
        val tmp = File(dir, index.name + ".tmp")
        tmp.writeText(JSONArray().apply { all.forEach { put(toJson(it)) } }.toString())
        tmp.renameTo(index)
        return entry
    }

    private fun toJson(entry: ReportEntry) = JSONObject()
        .put("fileName", entry.fileName)
        .put("template", entry.template?.name ?: "")
        .put("place", entry.place)
        .put("person", entry.person)
        .put("builtAt", entry.builtAt)
        .put("issues", entry.issues)
        .put("serious", entry.serious)
        .put("flagged", entry.flagged)
        .put("review", entry.review)
        .put("reviewReason", entry.reviewReason)
        .put("from", entry.from)
        .put("receivedAt", entry.receivedAt)
        .put("unread", entry.unread)

    /** [fileName] stands in when the entry does not name its file: a report from another phone is named here. */
    private fun entryFrom(json: JSONObject, fileName: String = json.getString("fileName")) = ReportEntry(
        fileName = fileName,
        template = Template.entries.firstOrNull { it.name == json.optString("template") },
        place = json.optString("place"),
        person = json.optString("person"),
        builtAt = json.optLong("builtAt"),
        issues = json.optInt("issues"),
        serious = json.optInt("serious"),
        flagged = json.optInt("flagged"),
        review = json.optString("review"),
        reviewReason = json.optString("reviewReason"),
        from = json.optString("from"),
        receivedAt = json.optLong("receivedAt"),
        unread = json.optBoolean("unread"),
    )

    private companion object {
        val TIMESTAMP = Regex("""\d{8}-\d{4}$""")
    }
}
