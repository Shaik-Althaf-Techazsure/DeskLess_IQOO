package com.techazsure.deskless.account

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * What the office's assistant proposes this person do about an issue. [kind] is "assign" (give it to a
 * technician), "send" (pass it on or answer, with [text]), "fix" (repair it and show a picture), "check"
 * (look at a repair) or "wait" (nothing for this person to do now).
 */
data class Step(
    val kind: String,
    val label: String,
    val to: String = "",
    val toName: String = "",
    /** The day proposed for it, as YYYY-MM-DD; blank when none. */
    val dueOn: String = "",
    /** The words proposed to go with it. */
    val text: String = "",
)

/** A message about an issue that waits on this person. */
data class Letter(val from: String, val fromRole: String, val text: String)

/** Someone a job can be given or sent to, as the office names them. */
data class Person(val id: String, val name: String, val role: String = "")

/** One issue on this person's desk, as the office ranks it. */
data class DeskJob(
    val id: String,
    val title: String,
    val site: String,
    /** "Critical", "High", "Medium" or "Low"; blank when the office did not say. */
    val severity: String,
    /** "open", "assigned", "fixed" or "closed"; blank when the office did not say. */
    val status: String,
    /** How pressing the office's assistant finds it: "now", "soon" or "routine". */
    val band: String,
    val why: List<String>,
    val next: Step,
    /** True when it waits on this person; false when it is only in their view and waits on someone else. */
    val needsMe: Boolean,
    val letter: Letter? = null,
    /** Where it is, what was said about it and when it is due, in words; blank when the office did not send them along. */
    val location: String = "",
    val note: String = "",
    val due: String = "",
    /** The people the office lets this person pass the job to; null when it did not say, and has to be asked. */
    val sendTo: List<Person>? = null,
)

/**
 * What waits for the signed-in person at the office, as the server last gave it: the issues that need
 * them, most pressing first, then the most pressing of the others they can see. The server decides what
 * is on the list, in what order, and what step to propose; the phone only shows it, says it and acts on it.
 */
data class Desk(
    /** When the phone fetched it. */
    val at: Long,
    val name: String,
    val role: String,
    val roleName: String,
    /** True for a manager or managing director, who give out work and check repairs. */
    val mayDirect: Boolean,
    val jobs: List<DeskJob>,
    /**
     * A mark the server gives the desk: it changes when anything on the desk does, and the phone waits on
     * the server until it has. Blank from a server that does not give one.
     */
    val mark: String = "",
    /** The technicians this person may give work to; null when the office did not say, and has to be asked. */
    val technicians: List<Person>? = null,
) {
    val waiting: List<DeskJob> get() = jobs.filter { it.needsMe }
    val firstName: String get() = name.trim().substringBefore(' ')

    /**
     * One line for each job, numbered as the person refers to them: what the model is shown. With [mine]
     * only the jobs that need this person; they come first, so their numbers are the same either way.
     */
    fun lines(mine: Boolean = false): String = (if (mine) waiting else jobs).withIndex().joinToString("\n") { (at, job) ->
        listOfNotNull(
            "Job ${at + 1}: ${job.title}",
            job.site.takeIf { it.isNotBlank() }?.let { "at $it" },
            job.severity.takeIf { it.isNotBlank() }?.let { "severity $it" },
            job.status.takeIf { it.isNotBlank() }?.let { "status $it" },
            job.location.takeIf { it.isNotBlank() }?.let { "place in it: $it" },
            job.due.takeIf { it.isNotBlank() }?.let { "due $it" },
            when (job.band) {
                "now" -> "act now"
                "soon" -> "soon"
                else -> null
            },
            job.why.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "because: $it" },
            (if (job.needsMe) "for you to do: " else "waits on someone else: ") + job.next.label,
            job.letter?.let { "${it.from} (${it.fromRole}) wrote: \"${it.text.take(160)}\"" },
        ).joinToString("; ")
    }

    /** The briefing in plain words, made here with no model: what is said when the model is not on the phone. */
    fun plain(): String {
        val mine = waiting
        fun one(job: DeskJob): String = listOfNotNull(
            job.title.trimEnd('.'),
            job.site.takeIf { it.isNotBlank() }?.let { "at $it" },
            job.severity.takeIf { it.isNotBlank() }?.lowercase(Locale.ENGLISH),
        ).joinToString(", ") + ". " + job.next.label.trimEnd('.') + "."
        if (mine.isEmpty()) {
            val seen = jobs.firstOrNull() ?: return "Nothing waits for you at the office right now, $firstName."
            return "Nothing needs you right now, $firstName. The most pressing in your view: " + one(seen)
        }
        val count = if (mine.size == 1) "One thing waits for you, $firstName." else "${mine.size} things wait for you, $firstName."
        val rest = mine.size - 2
        return listOfNotNull(
            count,
            "First: " + one(mine[0]),
            mine.getOrNull(1)?.let { "Then: " + one(it) },
            if (rest > 0) "And $rest more." else null,
        ).joinToString(" ")
    }

    /** What a new arrival is announced with: one short sentence. */
    fun announce(job: DeskJob): String = listOfNotNull(
        "New at your desk: ${job.title.trimEnd('.')}",
        job.site.takeIf { it.isNotBlank() }?.let { "at $it" },
        job.severity.takeIf { it.isNotBlank() }?.lowercase(Locale.ENGLISH),
    ).joinToString(", ") + "."

    companion object {
        private val SEVERITY = mapOf("CRITICAL" to "Critical", "HIGH" to "High", "MEDIUM" to "Medium", "LOW" to "Low")

        /**
         * Reads the desk out of what the server gives a dashboard's first screen. Jobs that need this
         * person come first, in the server's order; after them, the most pressing of the rest in view.
         */
        fun from(dashboard: JSONObject, at: Long): Desk {
            val me = dashboard.optJSONObject("me") ?: JSONObject()
            val letters = HashMap<String, Letter>()
            dashboard.optJSONArray("inbox").each { one ->
                letters.putIfAbsent(one.optString("issue_id"), Letter(one.optString("from"), one.optString("from_role"), one.optString("text")))
            }
            val mine = ArrayList<DeskJob>()
            dashboard.optJSONArray("queue").each { one ->
                val next = one.optJSONObject("next") ?: JSONObject()
                mine.add(
                    DeskJob(
                        id = one.optString("id"),
                        title = one.optString("title"),
                        site = one.optString("site"),
                        severity = SEVERITY[one.optString("severity").uppercase(Locale.ENGLISH)].orEmpty(),
                        status = one.optString("status").lowercase(Locale.ENGLISH),
                        band = one.optString("band"),
                        why = one.optJSONArray("why").let { list -> (0 until (list?.length() ?: 0)).map { list!!.optString(it) } },
                        next = Step(next.optString("kind"), next.optString("label"), next.optString("to"), next.optString("to_name"), next.optString("due_on"), next.optString("text")),
                        needsMe = true,
                        letter = letters[one.optString("id")],
                        location = one.optString("location"),
                        note = one.optString("note"),
                        due = one.optString("due"),
                        sendTo = one.optJSONArray("send_to")?.let(::people),
                    )
                )
            }
            val taken = mine.map { it.id }.toSet()
            val others = ArrayList<DeskJob>()
            dashboard.optJSONArray("triage").each { one ->
                val id = one.optString("issue_id")
                if (id in taken || others.size >= MAX_OTHERS) return@each
                others.add(
                    DeskJob(
                        id = id, title = one.optString("title"), site = one.optString("site"), severity = "", status = "", band = one.optString("band"),
                        why = listOf(one.optString("why")).filter { it.isNotBlank() }, next = Step("wait", one.optString("then")), needsMe = false, letter = letters[id],
                    )
                )
            }
            return Desk(
                at = at,
                name = me.optString("name"),
                role = me.optString("role"),
                roleName = me.optString("role_name").ifBlank { me.optString("role") },
                mayDirect = dashboard.optBoolean("may_direct"),
                jobs = (mine + others).filter { it.id.isNotBlank() },
                mark = dashboard.optString("mark"),
                technicians = dashboard.optJSONArray("technicians")?.let(::people),
            )
        }

        /** People as the office lists them: who a job can go to. A technician's list gives no role, and needs none. */
        fun people(list: JSONArray): List<Person> = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .map { Person(it.optString("id"), it.optString("name"), it.optString("role_name").ifBlank { "Technical" }) }
            .filter { it.id.isNotBlank() }

        /** The part of the server's answer the desk is read from, small enough to keep on the phone for when there is no network. */
        fun keep(dashboard: JSONObject, at: Long): JSONObject = JSONObject()
            .put("at", at)
            .put("me", dashboard.optJSONObject("me") ?: JSONObject())
            .put("may_direct", dashboard.optBoolean("may_direct"))
            .put("queue", dashboard.optJSONArray("queue") ?: JSONArray())
            .put("triage", dashboard.optJSONArray("triage") ?: JSONArray())
            .put("inbox", dashboard.optJSONArray("inbox") ?: JSONArray())
            .put("mark", dashboard.optString("mark"))
            .apply { dashboard.optJSONArray("technicians")?.let { put("technicians", it) } }

        fun kept(text: String): Desk? = runCatching { JSONObject(text).let { from(it, it.optLong("at")) } }.getOrNull()

        /** How many issues that wait on others are listed after the person's own. */
        private const val MAX_OTHERS = 4

        private inline fun JSONArray?.each(use: (JSONObject) -> Unit) {
            for (at in 0 until (this?.length() ?: 0)) this!!.optJSONObject(at)?.let(use)
        }
    }
}
