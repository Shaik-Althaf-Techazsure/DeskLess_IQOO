package com.techazsure.deskless.account

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
 * report to the office, and to open the person's dashboard. Capturing, the models and building a report
 * need none of them. Every call blocks, so it is made off the main thread.
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

    private fun post(path: String, body: JSONObject): JSONObject =
        call("POST", path, contentType = "application/json") { it.write(body.toString().toByteArray()) }

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
    ): JSONObject {
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
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299 && json != null) return json
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
