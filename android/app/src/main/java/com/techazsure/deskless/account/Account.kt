package com.techazsure.deskless.account

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The signed-in person, as the account server describes them. The Aadhaar number is only ever held masked. */
data class Profile(
    val uniqueId: String,
    val role: String,
    val name: String,
    val email: String,
    val phone: String,
    val organisation: String,
    val aadhaar: String,
    /** The role in words, e.g. "Managing Director". */
    val roleName: String,
    /** The name of the person this one answers to; blank for a managing director. */
    val reportsTo: String = "",
    /** The code a managing director or manager hands to the people who are to answer to them; blank for others. */
    val inviteCode: String = "",
) {
    /** True for someone who uses a place and works for nobody in it. */
    val usesAPlace: Boolean get() = role == USER

    companion object {
        /** The role of someone who uses a place: a resident, a student, a visitor, a member of staff. */
        const val USER = "member"

        /** The roles a person can sign up as, in the order the form offers them, with what each one does. */
        val ROLES = listOf(
            Triple(USER, "User", "You use a place: you live, study, work or shop there. You tell the people who look after it what you notice, and ask what you want to know."),
            Triple("inspector", "Inspector", "You walk the site and report what you find."),
            Triple("technical", "Technical", "You fix the issues your manager gives you, and show each repair with a photo."),
            Triple("manager", "Manager", "You give issues to your technicians and check their repairs."),
            Triple("md", "Managing Director", "You bring your company to DeskLess and see everything in it."),
        )

        fun from(json: JSONObject): Profile {
            val role = json.optString("role")
            return Profile(
                uniqueId = json.optString("unique_id"),
                role = role,
                name = json.optString("name"),
                email = json.optString("email"),
                phone = json.optString("phone"),
                organisation = json.optString("organisation"),
                aadhaar = json.optString("aadhaar"),
                roleName = json.optString("role_name").ifBlank { ROLES.firstOrNull { it.first == role }?.second ?: role.replaceFirstChar(Char::uppercase) },
                reportsTo = json.optString("reports_to"),
                inviteCode = json.optString("invite_code"),
            )
        }
    }
}

/** A sign-up that has been sent and is not finished: waiting for the emailed code, or for the person above to approve it. */
data class PendingSignUp(val email: String, val ticket: String, val name: String)

/**
 * What the phone remembers about the account: which server it talks to, the sign-in it holds and the
 * person's details, so that after signing in once the app goes on working with no network at all.
 */
class AccountStore(private val context: Context) {
    private val file = File(context.filesDir, "account.json")

    /** The profile photo, fetched once at sign-in. */
    val photo = File(context.filesDir, "account-photo.jpg")

    /**
     * An address somebody typed on the first screen; blank while the one the app was built with is in
     * use. A newer build with another address is then followed without anyone having to retype it.
     */
    private var chosenServer = ""
    val server: String get() = chosenServer.ifBlank { defaultServer() }
    var token: String = ""
        private set
    var profile: Profile? = null
        private set
    var pending: PendingSignUp? = null
        private set

    /**
     * Other people signed in on this phone, each with their own sign-in, the last used first. A phone
     * shared on a site is handed from a manager to a technician and back: it changes to one of them with
     * a tap and no password, until that one signs out.
     */
    private val kept = ArrayList<Pair<String, Profile>>()
    val others: List<Profile> get() = kept.map { it.second }

    init {
        runCatching {
            val json = JSONObject(file.readText())
            chosenServer = json.optString("server_chosen")
            token = json.optString("token")
            profile = json.optJSONObject("profile")?.let(Profile::from)?.takeIf { token.isNotBlank() }
            json.optJSONArray("others")?.let { list ->
                for (at in 0 until list.length()) {
                    val one = list.optJSONObject(at) ?: continue
                    val person = one.optJSONObject("profile")?.let(Profile::from) ?: continue
                    if (one.optString("token").isNotBlank()) kept.add(one.optString("token") to person)
                }
            }
            pending = json.optJSONObject("pending")?.let {
                PendingSignUp(it.getString("email"), it.getString("ticket"), it.optString("name"))
            }
        }
    }

    fun useServer(address: String) {
        chosenServer = address.trim().trimEnd('/').takeIf { it != defaultServer() }.orEmpty()
        save()
    }

    fun signedIn(token: String, profile: Profile) {
        // A second person signing in does not sign the first one out: the phone then holds both.
        val before = this.profile
        if (before != null && before.uniqueId != profile.uniqueId && this.token.isNotBlank()) kept.add(0, this.token to before)
        kept.removeAll { it.second.uniqueId == profile.uniqueId }
        this.token = token
        this.profile = profile
        pending = null
        save()
    }

    /** Changes to another person signed in on this phone. False when [uniqueId] is not one of them. */
    fun switchTo(uniqueId: String): Boolean {
        val at = kept.indexOfFirst { it.second.uniqueId == uniqueId }
        if (at < 0) return false
        val (nextToken, next) = kept.removeAt(at)
        profile?.let { kept.add(0, token to it) }
        token = nextToken
        profile = next
        photo.delete()
        save()
        return true
    }

    /**
     * For the edition handed to people who use a place: anyone else signed in on this phone, from a build
     * of the other edition, is signed out of it. Their account is untouched; they sign in on their own app.
     */
    fun keepOnlyUsers() {
        val before = kept.size to profile
        kept.removeAll { !it.second.usesAPlace }
        if (profile?.usesAPlace == false) {
            val next = if (kept.isEmpty()) null else kept.removeAt(0)
            token = next?.first.orEmpty()
            profile = next?.second
            photo.delete()
        }
        if (before != kept.size to profile) save()
    }

    fun waitingFor(pending: PendingSignUp?) {
        this.pending = pending
        save()
    }

    /** Signs the person in use out. Anyone else signed in on this phone stays so, and the last one used takes over. */
    fun signOut() {
        val next = if (kept.isEmpty()) null else kept.removeAt(0)
        token = next?.first.orEmpty()
        profile = next?.second
        photo.delete()
        save()
    }

    /** The server address the app was built with; it can be changed on the first screen. */
    private fun defaultServer(): String = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("deskless.server")
    }.getOrNull().orEmpty().ifBlank { "http://10.0.2.2:8800" }

    private fun save() {
        fun written(it: Profile) = JSONObject().put("unique_id", it.uniqueId).put("role", it.role).put("name", it.name).put("email", it.email)
            .put("phone", it.phone).put("organisation", it.organisation).put("aadhaar", it.aadhaar)
            .put("role_name", it.roleName).put("reports_to", it.reportsTo).put("invite_code", it.inviteCode)
        val json = JSONObject().put("server_chosen", chosenServer).put("token", token)
        profile?.let { json.put("profile", written(it)) }
        json.put("others", JSONArray().apply { kept.forEach { (token, person) -> put(JSONObject().put("token", token).put("profile", written(person))) } })
        pending?.let { json.put("pending", JSONObject().put("email", it.email).put("ticket", it.ticket).put("name", it.name)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }
}
