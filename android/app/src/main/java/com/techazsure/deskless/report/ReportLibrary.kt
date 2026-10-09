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
)

/** The PDFs built on this phone, with a small index beside them. */
class ReportLibrary(context: Context) {
    private val dir = File(context.filesDir, "reports").apply { mkdirs() }
    private val index = File(dir, "index.json")

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
            .sortedByDescending { it.builtAt }
    }

    fun record(file: File, inspection: Inspection, issues: List<Issue>, flagged: Int): ReportEntry {
        val entry = ReportEntry(
            fileName = file.name,
            template = inspection.template,
            place = inspection.site,
            person = inspection.inspector,
            builtAt = System.currentTimeMillis(),
            issues = issues.size,
            serious = issues.count { it.severity == Severity.HIGH || it.severity == Severity.CRITICAL },
            flagged = flagged,
        )
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

    private fun entryFrom(json: JSONObject) = ReportEntry(
        fileName = json.getString("fileName"),
        template = Template.entries.firstOrNull { it.name == json.optString("template") },
        place = json.optString("place"),
        person = json.optString("person"),
        builtAt = json.optLong("builtAt"),
        issues = json.optInt("issues"),
        serious = json.optInt("serious"),
        flagged = json.optInt("flagged"),
    )

    private companion object {
        val TIMESTAMP = Regex("""\d{8}-\d{4}$""")
    }
}
