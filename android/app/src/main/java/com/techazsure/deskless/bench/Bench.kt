package com.techazsure.deskless.bench

import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.structure.DueDates
import com.techazsure.deskless.structure.Fields
import com.techazsure.deskless.structure.Grounding
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/** One fixed note, and what a careful reader would take from it. */
class Case(
    val note: String,
    /** The words a right location must hold. */
    val location: String,
    /** Who the note gives it to; blank when it names nobody, and then any name is a made-up one. */
    val owner: String,
    /** The deadline as the note says it; blank when it gives none. */
    val due: String,
    /** The levels a careful reader could fairly choose. */
    val severity: Set<Severity>,
    /** The types that fit; empty where no type clearly does, and then the type is not marked. */
    val types: Set<String> = emptySet(),
)

/** One field of one note, marked. */
data class Mark(
    val field: String,
    val right: Boolean,
    val expected: String,
    val got: String,
    /** True when the note gave nothing for this field and the app wrote something all the same. */
    val madeUp: Boolean = false,
)

/** How one note came out. */
data class Outcome(
    val number: Int,
    val note: String,
    /** What the speech model wrote down, when the note was spoken; null when the text went straight in. */
    val heard: String?,
    /** The share of the note's words found in [heard], 0 to 1. */
    val wordsRight: Float?,
    val marks: List<Mark>,
    val speechSeconds: Float,
    val fieldSeconds: Float,
    /** Values the model offered that nobody said, kept out by the guard. */
    val keptOut: Int,
    /** Why the note could not be run, when it could not. */
    val failed: String? = null,
)

/** The measurements of one run on one phone. */
data class Scorecard(
    val at: Long,
    val phone: String,
    val processor: String,
    val language: String,
    val spoken: Boolean,
    val outcomes: List<Outcome>,
    /** Charge used by the whole run in milliamp-hours by the phone's own gauge; null when it could not be read. */
    val usedMah: Float?,
    val charging: Boolean,
) {
    private val marks: List<Mark> get() = outcomes.flatMap { it.marks }
    val notes: Int get() = outcomes.count { it.failed == null }
    val fieldsRight: Float get() = share(marks)
    fun right(field: String): Float = share(marks.filter { it.field == field })

    /** Fields the notes left empty, and how many of them the app filled all the same. */
    val chances: Int get() = marks.count { it.expected.isEmpty() }
    val madeUp: Int get() = marks.count { it.madeUp }
    val keptOut: Int get() = outcomes.sumOf { it.keptOut }
    val wordsRight: Float? get() = outcomes.mapNotNull { it.wordsRight }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val speechSeconds: Float? get() = outcomes.filter { it.heard != null && it.failed == null }.map { it.speechSeconds }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val fieldSeconds: Float get() = outcomes.filter { it.failed == null }.map { it.fieldSeconds }.ifEmpty { listOf(0f) }.average().toFloat()
    val slowest: Float get() = outcomes.maxOfOrNull { it.speechSeconds + it.fieldSeconds } ?: 0f
    val mahPerNote: Float? get() = usedMah?.takeIf { it > 0f && notes > 0 && !charging }?.let { it / notes }

    private fun share(of: List<Mark>): Float = if (of.isEmpty()) 0f else of.count { it.right } / of.size.toFloat()

    fun toJson(): JSONObject = JSONObject()
        .put("at", at).put("phone", phone).put("processor", processor).put("language", language).put("spoken", spoken)
        .put("usedMah", usedMah?.toDouble() ?: JSONObject.NULL).put("charging", charging)
        .put("outcomes", JSONArray().apply {
            outcomes.forEach { one ->
                put(
                    JSONObject().put("number", one.number).put("note", one.note).put("heard", one.heard ?: JSONObject.NULL)
                        .put("wordsRight", one.wordsRight?.toDouble() ?: JSONObject.NULL)
                        .put("speechSeconds", one.speechSeconds.toDouble()).put("fieldSeconds", one.fieldSeconds.toDouble())
                        .put("keptOut", one.keptOut).put("failed", one.failed ?: JSONObject.NULL)
                        .put("marks", JSONArray().apply {
                            one.marks.forEach { put(JSONObject().put("field", it.field).put("right", it.right).put("expected", it.expected).put("got", it.got).put("madeUp", it.madeUp)) }
                        })
                )
            }
        })

    companion object {
        fun from(json: JSONObject): Scorecard = Scorecard(
            at = json.optLong("at"),
            phone = json.optString("phone"),
            processor = json.optString("processor"),
            language = json.optString("language"),
            spoken = json.optBoolean("spoken"),
            usedMah = if (json.isNull("usedMah")) null else json.optDouble("usedMah").toFloat(),
            charging = json.optBoolean("charging"),
            outcomes = json.optJSONArray("outcomes").let { list ->
                (0 until (list?.length() ?: 0)).map { at ->
                    val one = list!!.getJSONObject(at)
                    val marks = one.optJSONArray("marks")
                    Outcome(
                        number = one.optInt("number"),
                        note = one.optString("note"),
                        heard = if (one.isNull("heard")) null else one.optString("heard"),
                        wordsRight = if (one.isNull("wordsRight")) null else one.optDouble("wordsRight").toFloat(),
                        marks = (0 until (marks?.length() ?: 0)).map { i ->
                            marks!!.getJSONObject(i).let { Mark(it.optString("field"), it.optBoolean("right"), it.optString("expected"), it.optString("got"), it.optBoolean("madeUp")) }
                        },
                        speechSeconds = one.optDouble("speechSeconds", 0.0).toFloat(),
                        fieldSeconds = one.optDouble("fieldSeconds", 0.0).toFloat(),
                        keptOut = one.optInt("keptOut"),
                        failed = if (one.isNull("failed")) null else one.optString("failed"),
                    )
                }
            },
        )
    }
}

/**
 * A fixed test the app sets itself on the phone it runs on: twenty notes of the kind an inspector speaks,
 * each with the answers a careful reader would give, run through the same models and the same guard as a
 * real note. It is a small test of our own making, not a published benchmark: it shows how this build
 * does on this phone, and it shows the same thing again after every change.
 */
object Bench {
    const val LOCATION = "Location"
    const val OWNER = "Owner"
    const val DUE = "Due"
    const val SEVERITY = "Severity"
    const val TYPE = "Type"
    val FIELDS = listOf(LOCATION, OWNER, DUE, SEVERITY, TYPE)

    private val low = setOf(Severity.LOW)
    private val lowish = setOf(Severity.LOW, Severity.MEDIUM)
    private val middling = setOf(Severity.MEDIUM, Severity.HIGH)
    private val serious = setOf(Severity.HIGH, Severity.CRITICAL)

    /** Written for the Site inspection type, whose issue types they use. */
    val cases = listOf(
        Case("There is a long crack in the wall on the second floor near the staircase. Ravi Kumar has to repair it by Monday.", "second floor", "Ravi Kumar", "Monday", middling + Severity.CRITICAL, setOf("Structural")),
        Case("Exposed live wire hanging from the ceiling in the basement parking. The electrical team must fix it today.", "basement parking", "electrical team", "today", serious, setOf("Electrical")),
        Case("Water is leaking from the pipe under the sink in the third floor washroom. Tell the plumber Suresh to fix it by tomorrow.", "washroom", "Suresh", "tomorrow", middling, setOf("Plumbing")),
        Case("The fire extinguisher near the main gate is empty.", "main gate", "", "", serious, setOf("Fire safety")),
        Case("Paint is peeling off the wall in the lobby. It is a minor thing.", "lobby", "", "", low, setOf("Housekeeping", "Structural", "Other")),
        Case("An unknown person was walking around the store room on the ground floor. Security supervisor Imran should check the cameras immediately.", "store room", "Imran", "immediately", serious, setOf("Security")),
        Case("Loose scaffolding on the east side of block B. Stop the work there. Site engineer Priya must make it safe in two days.", "block B", "Priya", "in 2 days", serious, setOf("Structural")),
        Case("Garbage and cement bags are lying in the corridor of the first floor. Housekeeping should clear it by Friday.", "first floor", "Housekeeping", "Friday", lowish, setOf("Housekeeping")),
        Case("The emergency exit door on the fourth floor is blocked with cartons.", "fourth floor", "", "", serious, setOf("Fire safety", "Housekeeping")),
        Case("The main switch board in the pump room is sparking. Call the electrician Anil right now.", "pump room", "Anil", "right now", serious, setOf("Electrical")),
        Case("Tiles are broken at the entrance of tower two. The flooring contractor has to replace them next week.", "tower two", "flooring contractor", "next week", lowish, setOf("Structural", "Housekeeping", "Other")),
        Case("There is no safety net on the open edge of the terrace. A worker can fall. Safety officer Deepak must put a barricade today.", "terrace", "Deepak", "today", serious),
        Case("The lift in block A is not working since morning. Inform the maintenance team.", "block A", "maintenance team", "", middling),
        Case("The drain behind the canteen is choked and dirty water is collecting. The plumbing contractor has to clear it by Wednesday.", "canteen", "plumbing contractor", "Wednesday", middling, setOf("Plumbing")),
        Case("There is a small scratch on the glass door of the reception.", "reception", "", "", low, setOf("Housekeeping", "Other")),
        Case("The smoke detector in the server room on the fifth floor is not blinking. The fire safety vendor should test it by Thursday.", "server room", "fire safety vendor", "Thursday", middling + Severity.CRITICAL, setOf("Fire safety")),
        Case("Steel rods are kept across the walkway near gate number three. People can trip on them. Store keeper Ramesh should move them by tomorrow.", "gate three", "Ramesh", "tomorrow", middling, setOf("Housekeeping", "Other")),
        Case("The boundary wall behind the generator room has a gap and anyone can enter.", "generator room", "", "", middling + Severity.CRITICAL, setOf("Security", "Structural")),
        Case("The ceiling of the conference room on the sixth floor is damp and plaster is falling. The waterproofing team has to inspect it within 3 days.", "conference room", "waterproofing team", "within 3 days", middling, setOf("Structural", "Plumbing")),
        Case("The earthing wire is missing on the welding machine in the fabrication yard. Nobody should use it until electrical supervisor Kiran checks it.", "fabrication yard", "Kiran", "", serious, setOf("Electrical")),
    )

    /** A short run for a quick look: one note in four, spread over the kinds of note. */
    val quick: List<Int> = listOf(0, 3, 6, 9, 13)

    /**
     * Marks what the app made of [case]. [fields] is what would be written into the report, after the
     * guard. A field the note left empty is right only when it stays empty.
     */
    fun mark(case: Case, fields: Fields, today: Date = Date()): List<Mark> {
        fun named(field: String, expected: String, got: String): Mark = when {
            expected.isEmpty() -> Mark(field, got.isBlank(), "", got, madeUp = got.isNotBlank())
            // Right when it names the place or the person; more words around the name do no harm.
            else -> Mark(field, got.isNotBlank() && Grounding.share(expected, got) >= 0.99f, expected, got)
        }
        val due = DueDates.resolve(case.due, today)
        return listOfNotNull(
            named(LOCATION, case.location, fields.location),
            named(OWNER, case.owner, fields.owner),
            Mark(DUE, fields.dueDate == due, due, fields.dueDate, madeUp = due.isEmpty() && fields.dueDate.isNotBlank()),
            Mark(SEVERITY, fields.severity in case.severity, case.severity.joinToString(" or ") { it.label }, fields.severity?.label.orEmpty()),
            if (case.types.isEmpty()) null else Mark(TYPE, fields.category in case.types, case.types.joinToString(" or "), fields.category),
        )
    }

    /** The share of the words of [note] that the speech model wrote down, 0 to 1. */
    fun wordsRight(note: String, heard: String): Float {
        val wanted = Grounding.words(note)
        if (wanted.isEmpty()) return 0f
        val got = HashMap<String, Int>()
        Grounding.words(heard).forEach { got[it] = (got[it] ?: 0) + 1 }
        var found = 0
        for (word in wanted) {
            val left = got[word] ?: 0
            if (left > 0) {
                found++
                got[word] = left - 1
            }
        }
        return found / wanted.size.toFloat()
    }

    /** The scorecard in a few lines, for a slide or a message. */
    fun summary(card: Scorecard, date: String): String = listOfNotNull(
        "DeskLess, measured on the ${card.phone} itself, $date",
        "${card.notes} fixed notes" + (if (card.spoken) ", read aloud by the phone's own voice and listened to by the speech model" else ", given as text") + ", no network",
        "Report fields right: ${percent(card.fieldsRight)} (" + FIELDS.joinToString(" · ") { "${it.lowercase()} ${percent(card.right(it))}" } + ")",
        "Names, places or dates made up: ${card.madeUp} in ${card.chances} chances; the guard kept out ${card.keptOut}",
        card.speechSeconds?.let { "Speech to English: %.1f s a note".format(it) + (card.wordsRight?.let { right -> ", ${percent(right)} of the words" } ?: "") },
        "Note to report fields: %.1f s a note on the ${card.processor.ifBlank { "phone" }}".format(card.fieldSeconds),
        card.mahPerNote?.let { "Battery: %.1f mAh a note by the phone's own gauge".format(it) },
    ).joinToString("\n")

    fun percent(share: Float): String = "${(share * 100).toInt()}%"
}
