package com.techazsure.deskless.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Severity(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    CRITICAL("Critical"),
}

/** What the on-device model made of the picture when it compared it with the spoken note. */
enum class Check {
    /** Not checked: no note yet, or no model on the phone. */
    NONE,
    MATCH,
    UNSURE,
    MISMATCH,

    /** The model had doubts and the inspector said the picture is right. */
    CONFIRMED,
}

data class Issue(
    val id: String,
    val createdAt: Long,
    /** File name inside the store folder. For a video issue this is the frame used in the report. */
    val photoFile: String,
    val voiceFile: String? = null,
    val voiceSeconds: Int = 0,
    val note: String = "",
    val title: String = "",
    val location: String = "",
    val severity: Severity = Severity.MEDIUM,
    val owner: String = "",
    val dueDate: String = "",
    /** Set when the issue was filmed rather than photographed. */
    val videoFile: String? = null,
    /** Stills taken from the video for the model to look at; empty for a photo issue. */
    val frames: List<String> = emptyList(),
    val check: Check = Check.NONE,
    /** One sentence from the model on what the picture shows. */
    val seen: String = "",
    /** How long the model took to fill the fields, and on which processor; 0 when it has not run. */
    val aiSeconds: Float = 0f,
    val aiBackend: String = "",
    /** One of the template's types, e.g. "Electrical"; blank until the model or the user picks one. */
    val category: String = "",
    /** Where the phone was when the issue was captured; null when it had no position. */
    val lat: Double? = null,
    val lon: Double? = null,
    /** The radius, in metres, that the position is good to. */
    val accuracy: Float = 0f,
    /** The picture taken later to show the repair, when it was taken, and what the model made of it. */
    val fixPhoto: String? = null,
    val fixedAt: Long = 0,
    val fixCheck: Check = Check.NONE,
    val fixSeen: String = "",
    /** An expiry date the model read off the pack in a medication audit, as printed; blank when none. */
    val expiry: String = "",
    /** What the phone's sensors read when the picture was taken; null for an issue captured without them. */
    val stamp: Stamp? = null,
    /** Readings the inspector took with the phone's instruments, oldest first. */
    val readings: List<Reading> = emptyList(),
    /** The place or asset whose NFC tag the phone was tapped on; blank when none. */
    val place: String = "",
) {
    /** True once a follow-up picture exists and the model or the inspector says the problem is gone. */
    val resolved: Boolean
        get() = fixPhoto != null && (fixCheck == Check.MATCH || fixCheck == Check.CONFIRMED)

    val located: Boolean
        get() = lat != null && lon != null
}

data class Inspection(
    val site: String = "",
    val inspector: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val template: Template = Template.GENERAL,
)

/** One inspection at a time: a JSON index plus the photo and voice files, all in app storage. */
class IssueStore(context: Context) {
    private val dir = File(context.filesDir, "inspection").apply { mkdirs() }
    private val index = File(dir, "inspection.json")

    fun file(name: String) = File(dir, name)

    fun load(): Pair<Inspection, List<Issue>> {
        if (!index.exists()) return Inspection() to emptyList()
        return try {
            val root = JSONObject(index.readText())
            val inspection = Inspection(
                site = root.optString("site"),
                inspector = root.optString("inspector"),
                startedAt = root.optLong("startedAt", System.currentTimeMillis()),
                template = Template.named(root.optString("template").ifBlank { "SITE" }),
            )
            val array = root.optJSONArray("issues") ?: JSONArray()
            inspection to (0 until array.length()).map { issueFrom(array.getJSONObject(it)) }
        } catch (e: Exception) {
            Inspection() to emptyList()
        }
    }

    fun save(inspection: Inspection, issues: List<Issue>) {
        val root = JSONObject()
            .put("site", inspection.site)
            .put("inspector", inspection.inspector)
            .put("startedAt", inspection.startedAt)
            .put("template", inspection.template.name)
            .put("issues", JSONArray().apply { issues.forEach { put(toJson(it)) } })
        val tmp = File(dir, index.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(index)
    }

    private fun toJson(issue: Issue) = JSONObject()
        .put("id", issue.id)
        .put("createdAt", issue.createdAt)
        .put("photoFile", issue.photoFile)
        .put("voiceFile", issue.voiceFile ?: "")
        .put("voiceSeconds", issue.voiceSeconds)
        .put("note", issue.note)
        .put("title", issue.title)
        .put("location", issue.location)
        .put("severity", issue.severity.name)
        .put("owner", issue.owner)
        .put("dueDate", issue.dueDate)
        .put("videoFile", issue.videoFile ?: "")
        .put("frames", JSONArray(issue.frames))
        .put("check", issue.check.name)
        .put("seen", issue.seen)
        .put("aiSeconds", issue.aiSeconds.toDouble())
        .put("aiBackend", issue.aiBackend)
        .put("category", issue.category)
        .put("lat", issue.lat ?: JSONObject.NULL)
        .put("lon", issue.lon ?: JSONObject.NULL)
        .put("accuracy", issue.accuracy.toDouble())
        .put("fixPhoto", issue.fixPhoto ?: "")
        .put("fixedAt", issue.fixedAt)
        .put("fixCheck", issue.fixCheck.name)
        .put("fixSeen", issue.fixSeen)
        .put("expiry", issue.expiry)
        .put("stamp", issue.stamp?.let { toJson(it) } ?: JSONObject.NULL)
        .put("readings", JSONArray().apply { issue.readings.forEach { put(toJson(it)) } })
        .put("place", issue.place)

    private fun toJson(stamp: Stamp) = JSONObject()
        .put("heading", stamp.heading?.toDouble() ?: JSONObject.NULL)
        .put("pitch", stamp.pitch?.toDouble() ?: JSONObject.NULL)
        .put("lux", stamp.lux?.toDouble() ?: JSONObject.NULL)
        .put("steps", stamp.steps ?: JSONObject.NULL)
        .put("zoom", stamp.zoom.toDouble())
        .put("torch", stamp.torch)

    private fun toJson(reading: Reading) = JSONObject()
        .put("kind", reading.kind.name)
        .put("value", reading.value.toDouble())
        .put("extra", reading.extra.toDouble())
        .put("takenAt", reading.takenAt)

    private fun stampFrom(json: JSONObject): Stamp {
        fun number(key: String): Float? = if (json.isNull(key)) null else json.optDouble(key).takeUnless { it.isNaN() }?.toFloat()
        return Stamp(
            heading = number("heading"),
            pitch = number("pitch"),
            lux = number("lux"),
            steps = if (json.isNull("steps")) null else json.optInt("steps"),
            zoom = json.optDouble("zoom", 1.0).toFloat(),
            torch = json.optBoolean("torch"),
        )
    }

    private fun readingsFrom(array: JSONArray?): List<Reading> =
        (0 until (array?.length() ?: 0)).mapNotNull { at ->
            val json = array!!.getJSONObject(at)
            val kind = Instrument.entries.firstOrNull { it.name == json.optString("kind") } ?: return@mapNotNull null
            Reading(kind, json.optDouble("value", 0.0).toFloat(), json.optDouble("extra", 0.0).toFloat(), json.optLong("takenAt"))
        }

    private fun issueFrom(json: JSONObject) = Issue(
        id = json.getString("id"),
        createdAt = json.optLong("createdAt"),
        photoFile = json.getString("photoFile"),
        voiceFile = json.optString("voiceFile").takeIf { it.isNotEmpty() },
        voiceSeconds = json.optInt("voiceSeconds"),
        note = json.optString("note"),
        title = json.optString("title"),
        location = json.optString("location"),
        severity = Severity.entries.firstOrNull { it.name == json.optString("severity") } ?: Severity.MEDIUM,
        owner = json.optString("owner"),
        dueDate = json.optString("dueDate"),
        videoFile = json.optString("videoFile").takeIf { it.isNotEmpty() },
        frames = json.optJSONArray("frames")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty(),
        check = Check.entries.firstOrNull { it.name == json.optString("check") } ?: Check.NONE,
        seen = json.optString("seen"),
        aiSeconds = json.optDouble("aiSeconds", 0.0).toFloat(),
        aiBackend = json.optString("aiBackend"),
        category = json.optString("category"),
        lat = if (json.isNull("lat")) null else json.optDouble("lat").takeUnless { it.isNaN() },
        lon = if (json.isNull("lon")) null else json.optDouble("lon").takeUnless { it.isNaN() },
        accuracy = json.optDouble("accuracy", 0.0).toFloat(),
        fixPhoto = json.optString("fixPhoto").takeIf { it.isNotEmpty() },
        fixedAt = json.optLong("fixedAt"),
        fixCheck = Check.entries.firstOrNull { it.name == json.optString("fixCheck") } ?: Check.NONE,
        fixSeen = json.optString("fixSeen"),
        expiry = json.optString("expiry"),
        stamp = json.optJSONObject("stamp")?.let(::stampFrom),
        readings = readingsFrom(json.optJSONArray("readings")),
        place = json.optString("place"),
    )
}
