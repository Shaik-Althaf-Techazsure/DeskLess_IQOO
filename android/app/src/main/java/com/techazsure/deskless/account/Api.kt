package com.techazsure.deskless.account

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The server said no, or could not be reached. The message is one sentence that can be shown as it is.
 * [status] is the answer code, or 0 when there was no answer at all. [refused] is true when the DeskLess
 * server itself gave the reason, and not something standing in front of it or a wrong address.
 */
class ApiError(message: String, val status: Int = 0, val refused: Boolean = false) : Exception(message)

/**
 * The calls the app makes to the DeskLess server: while signing up and signing in, to send a finished
 * report to the office, to open the person's dashboard, and to fetch and act on what waits for them
 * there. Capturing, the models and building a report need none of them. Every call blocks, so it is made
 * off the main thread.
 */
class Api(private val server: () -> String) {

    /** Sends the sign-up form with the photo. The answer carries the ticket the phone keeps. */
    fun signUp(fields: Map<String, String>, photo: File): JSONObject =
        multipart("/api/signup", null, fields, listOf(Part("photo", "photo.jpg", "image/jpeg", photo)))

    /** Whose code this is: the company the newcomer would join and the person they would answer to. */
    fun invite(code: String, role: String): JSONObject = call("GET", "/api/signup/invite?code=${encode(code)}&role=${encode(role)}")

    fun verify(email: String, code: String): JSONObject = post("/api/signup/verify", JSONObject().put("email", email).put("code", code))

    fun resend(email: String): JSONObject = post("/api/signup/resend", JSONObject().put("email", email))

    /** How a sign-up is doing: waiting for the code, waiting for approval, approved or rejected. */
    fun status(email: String, ticket: String): JSONObject =
        call("GET", "/api/signup/status?email=${encode(email)}&ticket=${encode(ticket)}")

    fun login(uniqueId: String, password: String): JSONObject =
        post("/api/login", JSONObject().put("unique_id", uniqueId).put("password", password))

    fun forgot(uniqueId: String): JSONObject = post("/api/password/forgot", JSONObject().put("unique_id", uniqueId))

    /** Checks the emailed code. A right one is used up and answered with the pass to choose a new password with. */
    fun verifyReset(uniqueId: String, code: String): JSONObject =
        post("/api/password/verify", JSONObject().put("unique_id", uniqueId).put("code", code))

    /** Sets the new password with the pass a checked code gave. */
    fun reset(ticket: String, password: String): JSONObject =
        post("/api/password/reset", JSONObject().put("ticket", ticket).put("password", password))

    /** The signed-in person's details as the server has them now. Asking also tells the server the phone is in use. */
    fun me(token: String): JSONObject = call("GET", "/api/me", token)

    /** Fetches the signed-in person's photo into [into]. A photo that cannot be fetched is not an error worth stopping for. */
    fun photo(token: String, into: File): Boolean = runCatching {
        val connection = open("GET", "/api/me/photo", token)
        try {
            if (connection.responseCode != 200) return false
            connection.inputStream.use { input -> into.outputStream().use { input.copyTo(it) } }
            true
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    /**
     * Sends a walk to the office: what it holds ([about]) and the pictures the office does not have yet,
     * each named as [about] names it. A built report brings its [pdf]; a walk still going on has none.
     * Sending the same walk again replaces what was sent before.
     */
    fun sendReport(token: String, about: JSONObject, pdf: File?, pictures: List<File>): JSONObject =
        multipart(
            "/api/reports",
            token,
            mapOf("meta" to about.toString()),
            listOfNotNull(pdf?.let { Part("pdf", it.name, "application/pdf", it) }) + pictures.map { Part("photos", it.name, "image/jpeg", it) },
        )

    /** A code that works once and for two minutes, with which a browser opens the person's dashboard already signed in. */
    fun webLink(token: String): String =
        call("POST", "/api/web-link", token, "application/json") { it.write("{}".toByteArray()) }.getString("code")

    // ---------------------------------------------------------------- what waits for this person at the office

    /**
     * What a dashboard's first screen shows for this person, as the server works it out for their role:
     * the issues that need them first, each with the step the server's assistant proposes, and the
     * messages that wait on them.
     */
    fun desk(token: String): JSONObject = try {
        // A server that has it gives the desk by itself, with a mark to wait on; an older one gives it
        // as part of the dashboard's first screen.
        call("GET", "/api/desk", token)
    } catch (e: ApiError) {
        if (e.status != 404) throw e
        call("GET", "/api/dashboard", token)
    }

    /**
     * Waits on the server until this person's desk is no longer the one [mark] stands for, or for about
     * [seconds]. The answer carries the mark as it now is, and "changed". This is how something newly
     * given or sent reaches the phone within a second or two, with nothing asked again and again.
     */
    fun deskWait(token: String, mark: String, seconds: Int = 25): JSONObject =
        call("GET", "/api/desk/wait?mark=${encode(mark)}&seconds=$seconds", token)

    /** One issue with its history, the messages about it, and whom this person may pass it to. */
    fun issue(token: String, id: String): JSONObject = call("GET", "/api/issues/${encode(id)}", token)

    /** The technicians this person may give work to; only a manager or managing director may ask. */
    fun technicians(token: String): JSONArray = JSONArray(text("GET", "/api/technicians", token))

    /** Fetches an issue's first picture, small, into [into]. False when it has none or it cannot be fetched. */
    fun issuePhoto(token: String, id: String, into: File): Boolean = runCatching {
        val connection = open("GET", "/api/issues/${encode(id)}/photo", token)
        try {
            if (connection.responseCode != 200) return false
            connection.inputStream.use { input -> into.outputStream().use { input.copyTo(it) } }
            into.length() > 0
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    /** Gives the issue to a technician. [dueOn] is YYYY-MM-DD, or blank to keep the day it has. */
    fun assign(token: String, id: String, to: String, dueOn: String, note: String): JSONObject =
        post("/api/issues/${encode(id)}/assign", JSONObject().put("to", to).put("due_on", dueOn).put("note", note), token)

    /** Passes the issue to someone with a word; [to] is a person's id, or "admin". */
    fun send(token: String, id: String, to: String, text: String): JSONObject =
        post("/api/issues/${encode(id)}/send", JSONObject().put("to", to).put("text", text), token)

    /** The technician says it is repaired, and shows it with a picture. */
    fun fixed(token: String, id: String, photo: File, note: String): JSONObject =
        multipart("/api/issues/${encode(id)}/fixed", token, mapOf("note" to note), listOf(Part("photo", "repair.jpg", "image/jpeg", photo)))

    /** The repair is accepted and the issue closed; or, with [back], it is sent back as not good enough. */
    fun settle(token: String, id: String, back: Boolean, note: String): JSONObject =
        post("/api/issues/${encode(id)}/${if (back) "reopen" else "close"}", JSONObject().put("note", note), token)

    // ---------------------------------------------------------------- someone who uses a place

    /** The short sign-up for someone who uses a place: no Aadhaar number, no photo, nobody to approve them. */
    fun signUpUser(name: String, email: String, phone: String, password: String): JSONObject =
        post("/api/signup/user", JSONObject().put("name", name).put("email", email).put("phone", phone).put("password", password))

    /** The places that take what their users send in, and the kinds they are. */
    fun places(token: String): JSONObject = call("GET", "/api/places", token)

    /** Brings what a user noticed, or wants to know, to the place. [photo] may be left out. */
    fun sendIn(token: String, fields: Map<String, String>, photo: File?): JSONObject =
        multipart("/api/requests", token, fields, listOfNotNull(photo?.let { Part("photo", "photo.jpg", "image/jpeg", it) }))

    /** Everything this person has sent in, with where each stands and what was said about it. */
    fun sentIn(token: String): JSONObject = call("GET", "/api/requests", token)

    /** Adds a word to something sent in; it goes to whoever at the place wrote last. */
    fun sayMore(token: String, id: String, text: String): JSONObject =
        post("/api/requests/${encode(id)}/say", JSONObject().put("text", text), token)

    /** What the place wrote about it has been read out to the person. */
    fun heard(token: String, id: String): JSONObject = post("/api/requests/${encode(id)}/heard", JSONObject(), token)

    private fun post(path: String, body: JSONObject, token: String? = null): JSONObject =
        call("POST", path, token, contentType = "application/json") { it.write(body.toString().toByteArray()) }

    private class Part(val field: String, val name: String, val type: String, val file: File)

    /** A form with files. Its length is worked out first, so the files are streamed and never held in memory. */
    private fun multipart(path: String, token: String?, fields: Map<String, String>, files: List<Part>): JSONObject {
        val boundary = "----deskless" + System.nanoTime()
        val texts = fields.map { (name, value) ->
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()
        }
        val heads = files.map {
            "--$boundary\r\nContent-Disposition: form-data; name=\"${it.field}\"; filename=\"${it.name}\"\r\nContent-Type: ${it.type}\r\n\r\n".toByteArray()
        }
        val line = "\r\n".toByteArray()
        val end = "--$boundary--\r\n".toByteArray()
        val length = texts.sumOf { it.size.toLong() } + files.indices.sumOf { heads[it].size + files[it].file.length() + line.size } + end.size
        return call("POST", path, token, "multipart/form-data; boundary=$boundary", length) { out ->
            texts.forEach(out::write)
            files.forEachIndexed { at, part ->
                out.write(heads[at])
                part.file.inputStream().use { it.copyTo(out) }
                out.write(line)
            }
            out.write(end)
        }
    }

    private fun open(method: String, path: String, token: String? = null): HttpURLConnection {
        val connection = URL(server().trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Accept", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        return connection
    }

    private fun call(
        method: String,
        path: String,
        token: String? = null,
        contentType: String? = null,
        length: Long = -1,
        write: ((OutputStream) -> Unit)? = null,
    ): JSONObject = JSONObject(text(method, path, token, contentType, length, write))

    /** The answer as the server wrote it; every answer is JSON, an object or, for a few lists, an array. */
    private fun text(
        method: String,
        path: String,
        token: String? = null,
        contentType: String? = null,
        length: Long = -1,
        write: ((OutputStream) -> Unit)? = null,
    ): String {
        val connection = try {
            open(method, path, token)
        } catch (e: Exception) {
            throw ApiError("The server address is not right: ${server()}")
        }
        try {
            if (write != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                if (length >= 0) connection.setFixedLengthStreamingMode(length)
                connection.outputStream.use(write)
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (code in 200..299 && (json != null || runCatching { JSONArray(body) }.isSuccess)) return body
            // The server explains each refusal in a sentence; anything else is not one of its own answers.
            val why = json?.opt("detail") as? String
            throw ApiError(why ?: "The server answered in a way the app did not expect ($code).", code, refused = why != null)
        } catch (e: IOException) {
            throw ApiError("Cannot reach the DeskLess server at ${server()}. Check the connection and the server address.")
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
}

/** Checks done on the phone before a form is sent, so a slip is caught at once and without the network. */
object Checks {
    private val D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )

    /**
     * True for twelve digits that can be an Aadhaar number: not starting with 0 or 1, and ending in the
     * right check digit (Verhoeff's scheme, which catches any one wrong digit and any two swapped).
     */
    fun aadhaar(digits: String): Boolean {
        if (digits.length != 12 || digits.any { !it.isDigit() } || digits[0] in "01") return false
        var check = 0
        digits.reversed().forEachIndexed { i, digit -> check = D[check][P[i % 8][digit - '0']] }
        return check == 0
    }

    /** An Indian mobile number: ten digits starting with 6 to 9, with or without +91 in front. */
    fun mobile(typed: String): Boolean {
        val digits = typed.filter { it.isDigit() }.let { if (it.length == 12 && it.startsWith("91")) it.drop(2) else it.trimStart('0') }
        return digits.length == 10 && digits[0] in "6789"
    }

    fun email(typed: String): Boolean = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]{2,}$""").matches(typed.trim())

    /** The letters and digits a code to sign up with is made of: none that are mistaken for one another. */
    private const val INVITE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val INVITE_LENGTH = 8

    /** A code as it is sent, however it was typed: capitals, with spaces and dashes dropped. */
    fun cleanInvite(typed: String): String = typed.uppercase().filter { it.isLetterOrDigit() }.take(INVITE_LENGTH)

    /** True when what was typed can be a code from a manager or managing director. */
    fun invite(typed: String): Boolean = cleanInvite(typed).let { it.length == INVITE_LENGTH && it.all(INVITE_LETTERS::contains) }

    const val MIN_PASSWORD = 8
}
