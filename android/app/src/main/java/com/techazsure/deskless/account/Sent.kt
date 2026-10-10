package com.techazsure.deskless.account

import org.json.JSONArray
import org.json.JSONObject

/** A kind of place a user can look under, with how many places of that kind take what their users send in. */
data class PlaceKind(val key: String, val name: String, val places: Int)

/** A place that has opened its door to the people who use it. */
data class Place(val id: String, val name: String, val kind: String, val kindName: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("kind_name", kindName)

    companion object {
        fun from(json: JSONObject): Place = Place(json.optString("id"), json.optString("name"), json.optString("kind"), json.optString("kind_name"))

        /** The kinds and the places out of the server's answer; the kinds that have no place yet come last. */
        fun listed(answer: JSONObject): Pair<List<PlaceKind>, List<Place>> {
            val kinds = answer.optJSONArray("kinds").objects().map { PlaceKind(it.optString("key"), it.optString("name"), it.optInt("places")) }
            val places = answer.optJSONArray("places").objects().map(::from).filter { it.id.isNotBlank() }
            return kinds.sortedByDescending { it.places > 0 } to places
        }
    }
}

/** One thing said about what was sent in: by the sender, or to them by someone at the place. */
data class Word(val id: Int, val from: String, val fromRole: String, val text: String, val mine: Boolean, val unheard: Boolean)

/** Something this person sent to a place, as the place's server now has it. */
data class Sent(
    val id: String,
    /** The name the phone gave it before it was sent, by which a retry is known for the same thing. */
    val clientId: String,
    val place: String,
    /** "issue", "report" or "enquiry". */
    val kind: String,
    val title: String,
    val note: String,
    val location: String,
    val severity: String,
    /** "open", "assigned", "fixed" or "closed". */
    val status: String,
    /** Where it stands, in a sentence the phone can say. */
    val standing: String,
    val sentTo: String,
    val hasPhoto: Boolean,
    val hasFixPhoto: Boolean,
    val thread: List<Word>,
) {
    /** What the place wrote that this person has not been read yet. */
    val unheard: List<Word> get() = thread.filter { it.unheard }

    /** What the phone says when the place writes back. */
    fun answerAloud(word: Word): String =
        "${word.from}${if (word.fromRole.isNotBlank()) ", ${word.fromRole.lowercase()}" else ""} at $place, about ${title.trimEnd('.', '?')}: ${word.text}"

    /** What the phone says when it moves on with nobody writing: given out, repaired, closed. */
    fun movedAloud(): String = "About ${title.trimEnd('.', '?')} at $place: $standing"

    companion object {
        fun from(json: JSONObject): Sent = Sent(
            id = json.optString("id"),
            clientId = json.optString("client_id"),
            place = json.optString("place"),
            kind = json.optString("kind"),
            title = json.optString("title"),
            note = json.optString("note"),
            location = json.optString("location"),
            severity = json.optString("severity"),
            status = json.optString("status"),
            standing = json.optString("standing"),
            sentTo = json.optString("sent_to"),
            hasPhoto = json.optBoolean("has_photo"),
            hasFixPhoto = json.optBoolean("has_fix_photo"),
            thread = json.optJSONArray("thread").objects().map {
                Word(it.optInt("id"), it.optString("from"), it.optString("from_role"), it.optString("text"), it.optBoolean("mine"), it.optBoolean("unheard"))
            },
        )

        fun listed(answer: JSONObject): List<Sent> = answer.optJSONArray("requests").objects().map(::from).filter { it.id.isNotBlank() }

        /**
         * What is worth saying aloud when the list comes back changed: each thing the place wrote that has
         * not been read out, and each thing that has moved on since [before] with nobody writing. Nothing
         * is said about what the phone sees for the first time.
         */
        fun news(before: List<Sent>, now: List<Sent>): List<Pair<Sent, String>> {
            val known = before.associateBy { it.id }
            val lines = ArrayList<Pair<Sent, String>>()
            for (one in now) {
                val was = known[one.id]
                val written = one.unheard
                written.forEach { lines.add(one to one.answerAloud(it)) }
                if (was != null && written.isEmpty() && was.status != one.status) lines.add(one to one.movedAloud())
            }
            return lines
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this!!.optJSONObject(it) }
