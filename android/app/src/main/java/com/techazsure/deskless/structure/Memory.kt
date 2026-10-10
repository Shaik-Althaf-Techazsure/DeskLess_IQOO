package com.techazsure.deskless.structure

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A name this phone has met: a person or team that was given work, or a place inside a site. */
data class Known(
    val name: String,
    /** The site a place belongs to; blank for a person, who may turn up on any site. */
    val site: String,
    /** The issues that used the name; how often it was used is how many there are. */
    val issues: Set<String>,
    val last: Long,
    /** What the phone once wrote in place of this name, before the inspector corrected it. */
    val heardAs: Set<String> = emptySet(),
)

/**
 * What the phone learns as it is used: who gets given work and what the places on a site are called. It
 * is learned from the fields as the inspector leaves them, so a correction made once is not needed again:
 * a name heard a little wrong next time is spelt the way it was corrected to. Kept in one small file in
 * the app's own storage; nothing of it leaves the phone.
 */
class Memory(private val file: File? = null) {
    private val people = ArrayList<Known>()
    private val places = ArrayList<Known>()

    init {
        runCatching {
            val root = JSONObject(file!!.readText())
            people.addAll(read(root.optJSONArray("people")))
            places.addAll(read(root.optJSONArray("places")))
        }
    }

    val size: Int
        get() = people.size + places.size

    /** People and teams, the most used and most recent first. */
    fun people(limit: Int = Int.MAX_VALUE): List<String> = ranked(people).take(limit).map { it.name }

    /** The places met on [site], the most used and most recent first. */
    fun places(site: String, limit: Int = Int.MAX_VALUE): List<String> =
        ranked(places.filter { same(it.site, site) }).take(limit).map { it.name }

    /** Every place, whatever the site, with the site it belongs to. */
    fun allPlaces(): List<Known> = ranked(places)

    /**
     * Notes how an issue's fields stand now. A name the issue used before and no longer does is let go
     * of, so a mishearing that was corrected does not linger.
     */
    fun learn(issueId: String, site: String, owner: String, place: String, now: Long = System.currentTimeMillis()) {
        note(people, issueId, "", owner, now)
        note(places, issueId, site.trim(), place, now)
        save()
    }

    /** Forgets what one issue taught, when the issue is deleted. */
    fun unlearn(issueId: String) {
        note(people, issueId, "", "", 0)
        note(places, issueId, "", "", 0)
        save()
    }

    /**
     * Notes a correction: the phone wrote [heard] for a person or team and the inspector changed it to
     * [name]. The next time [heard] turns up, [name] is written straight away.
     */
    fun correctedPerson(heard: String, name: String) = corrected(people, heard, name, "")

    /** The same for a place on [site]. */
    fun correctedPlace(heard: String, name: String, site: String) = corrected(places, heard, name, site.trim())

    private fun corrected(list: ArrayList<Known>, heard: String, name: String, site: String) {
        val was = heard.trim()
        val at = list.indexOfFirst { same(it.name, name) && same(it.site, site) }
        if (at < 0 || was.length < 2 || same(was, name)) return
        list[at] = list[at].copy(heardAs = (list[at].heardAs + was).takeLastSet(MAX_HEARD_AS))
        save()
    }

    fun forgetPerson(name: String) {
        people.removeAll { same(it.name, name) }
        save()
    }

    fun forgetPlace(name: String, site: String) {
        places.removeAll { same(it.name, name) && same(it.site, site) }
        save()
    }

    /**
     * The known person or team that [said] names: the same name in other letter case or with a slip of
     * spelling, or a single name that only one known person carries ("Ravi" for "Ravi Kumar"). Null when
     * nobody fits or more than one does.
     */
    fun person(said: String): String? = corrected(said, people) ?: closest(said, people.map { it.name }, expand = true)

    /** The known place on [site] that [said] names, allowing only for letter case and slips of spelling. */
    fun place(said: String, site: String): String? = places.filter { same(it.site, site) }.let { here ->
        corrected(said, here) ?: closest(said, here.map { it.name }, expand = false)
    }

    /** The name [said] was corrected to before, when it was. */
    private fun corrected(said: String, list: List<Known>): String? {
        val wanted = Grounding.words(said)
        if (wanted.isEmpty()) return null
        return list.filter { known -> known.heardAs.any { Grounding.words(it) == wanted } }.map { it.name }.distinct().singleOrNull()
    }

    private fun note(list: ArrayList<Known>, issueId: String, site: String, name: String, now: Long) {
        val wanted = name.trim().trimEnd('.', ',')
        val keep = wanted.length in 2..MAX_NAME && wanted.any { it.isLetter() }
        var found = false
        val each = list.listIterator()
        while (each.hasNext()) {
            val known = each.next()
            if (keep && same(known.name, wanted) && same(known.site, site)) {
                found = true
                // The newest spelling wins: it is the one the inspector last left standing.
                each.set(known.copy(name = wanted, issues = (known.issues + issueId).takeLastSet(MAX_ISSUES), last = now))
            } else if (issueId in known.issues) {
                val left = known.issues - issueId
                if (left.isEmpty()) each.remove() else each.set(known.copy(issues = left))
            }
        }
        if (keep && !found) list.add(Known(wanted, site, setOf(issueId), now))
        // The least used, oldest names make room when the list grows long.
        while (list.size > MAX_NAMES) list.remove(ranked(list).last())
    }

    private fun closest(said: String, names: List<String>, expand: Boolean): String? {
        val wanted = Grounding.words(said)
        if (wanted.isEmpty()) return null
        names.firstOrNull { Grounding.words(it) == wanted }?.let { return it }
        val near = names.filter { name ->
            val words = Grounding.words(name)
            words.size == wanted.size && words.indices.all { Grounding.slip(words[it], wanted[it]) && words[it].first() == wanted[it].first() }
        }
        if (near.size == 1) return near.single()
        if (near.size > 1 || !expand || wanted.size != 1) return null
        // One spoken name, carried by exactly one known person as a whole word.
        val carriers = names.filter { name -> Grounding.words(name).let { it.size > 1 && wanted[0] in it } }
        return carriers.singleOrNull()
    }

    private fun ranked(list: List<Known>): List<Known> = list.sortedWith(compareByDescending<Known> { it.issues.size }.thenByDescending { it.last })

    private fun same(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    private fun <T> Set<T>.takeLastSet(count: Int): Set<T> = if (size <= count) this else toList().takeLast(count).toSet()

    private fun read(array: JSONArray?): List<Known> = (0 until (array?.length() ?: 0)).mapNotNull { at ->
        val one = array!!.optJSONObject(at) ?: return@mapNotNull null
        val ids = one.optJSONArray("issues")?.let { list -> (0 until list.length()).map { list.optString(it) }.toSet() }.orEmpty()
        val heardAs = one.optJSONArray("heardAs")?.let { list -> (0 until list.length()).map { list.optString(it) }.toSet() }.orEmpty()
        Known(one.optString("name"), one.optString("site"), ids, one.optLong("last"), heardAs).takeIf { it.name.isNotBlank() && ids.isNotEmpty() }
    }

    private fun write(list: List<Known>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("name", it.name).put("site", it.site).put("issues", JSONArray(it.issues.toList())).put("last", it.last).put("heardAs", JSONArray(it.heardAs.toList()))) }
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            val text = JSONObject().put("people", write(people)).put("places", write(places)).toString()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(target)
        }
    }

    companion object {
        private const val MAX_NAME = 40
        private const val MAX_NAMES = 60
        private const val MAX_ISSUES = 30
        private const val MAX_HEARD_AS = 6

        /** The place a person named out of a Location field, without where the camera was aimed, which the phone adds. */
        fun placeIn(location: String): String =
            location.split(",").map { it.trim() }
                .filterNot { part -> part.lowercase(Locale.ENGLISH).let { it == "overhead" || it == "at ground level" || it.startsWith("facing ") } }
                .joinToString(", ")
    }
}
