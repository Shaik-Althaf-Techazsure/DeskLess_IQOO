package com.techazsure.deskless.account

import android.content.Context
import android.content.pm.PackageManager
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
    companion object {
        /** The roles a person can sign up as, in the order the form offers them, with what each one does. */
        val ROLES = listOf(
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

    init {
        runCatching {
            val json = JSONObject(file.readText())
            chosenServer = json.optString("server_chosen")
            token = json.optString("token")
            profile = json.optJSONObject("profile")?.let(Profile::from)?.takeIf { token.isNotBlank() }
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
        this.token = token
        this.profile = profile
        pending = null
        save()
    }

    fun waitingFor(pending: PendingSignUp?) {
        this.pending = pending
        save()
    }

    fun signOut() {
        token = ""
        profile = null
        photo.delete()
        save()
    }

    /** The server address the app was built with; it can be changed on the first screen. */
    private fun defaultServer(): String = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("deskless.server")
    }.getOrNull().orEmpty().ifBlank { "http://10.0.2.2:8800" }

    private fun save() {
        val json = JSONObject().put("server_chosen", chosenServer).put("token", token)
        profile?.let {
            json.put(
                "profile",
                JSONObject().put("unique_id", it.uniqueId).put("role", it.role).put("name", it.name).put("email", it.email)
                    .put("phone", it.phone).put("organisation", it.organisation).put("aadhaar", it.aadhaar)
                    .put("role_name", it.roleName).put("reports_to", it.reportsTo).put("invite_code", it.inviteCode),
            )
        }
        pending?.let { json.put("pending", JSONObject().put("email", it.email).put("ticket", it.ticket).put("name", it.name)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }
}
