package com.techazsure.deskless.link

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What the link is told an entry handed over for sending on is called. The app says what it is itself. */
const val ENTRY_TITLE = "Entry to send on"

/**
 * Something a user of a place told their phone while it had no network, handed to this phone to send on.
 * [entry] is the text the user's phone signed, [signature] its signature: this phone passes both to the
 * server as they are, and cannot change a word of them without the server noticing.
 */
data class Carried(
    val clientId: String,
    /** The DeskLess ID and the name of the person who told it. */
    val sender: String,
    val senderName: String,
    val entry: String,
    val signature: String,
    val title: String,
    val place: String,
    /** The name of the phone that handed it over, and when. */
    val from: String,
    val at: Long,
) {
    /** What the link knows this entry's sealed copies by. */
    val report: String get() = reportOf(clientId)

    companion object {
        fun reportOf(clientId: String) = "entry-$clientId"
    }
}

/**
 * Entries on their way to a place through another phone. A user's phone with no network wraps what was
 * told, signed, and the link takes it sealed to a linked phone; that phone keeps it in [folder] and posts
 * it to the server for the user as soon as it has a network itself. The signature is made with a key
 * the server gives only to the user's own phone, so the phone in between can carry the entry and
 * cannot forge or alter one.
 */
class Carry(private val folder: File, private val cache: File) {
    init {
        folder.mkdirs()
    }

    /** Wraps a signed entry for the link: a mark that says what it is, what is known about it, then the picture if there is one. */
    fun wrap(clientId: String, sender: String, senderName: String, entry: String, signature: String, title: String, place: String, photo: File?): File {
        val out = File(cache, "entry-out-${safe(clientId)}.bin")
        DataOutputStream(out.outputStream().buffered()).use { stream ->
            stream.write(MARK)
            listOf(clientId, sender, senderName, entry, signature, title, place).forEach { stream.text(it) }
            photo?.takeIf { it.isFile }?.inputStream()?.use { it.copyTo(stream) }
        }
        return out
    }

    /**
     * If [pack] is an entry handed over to send on, keeps it on this phone and returns it; otherwise
     * null, and the file is something else, such as a report or a spoken message.
     */
    fun unwrap(pack: File, from: String): Carried? = runCatching {
        DataInputStream(pack.inputStream().buffered()).use { stream ->
            val mark = ByteArray(MARK.size)
            stream.readFully(mark)
            if (!mark.contentEquals(MARK)) return null
            val held = Carried(
                clientId = stream.text(), sender = stream.text(), senderName = stream.text(), entry = stream.text(), signature = stream.text(),
                title = stream.text().ifBlank { "Untitled" }, place = stream.text(), from = from, at = System.currentTimeMillis(),
            )
            require(safe(held.clientId).isNotEmpty() && safe(held.sender).isNotEmpty() && held.entry.isNotBlank() && held.signature.isNotBlank())
            val picture = photoFile(held)
            picture.outputStream().use { stream.copyTo(it) }
            if (picture.length() == 0L) picture.delete()
            DataOutputStream(aboutFile(held).outputStream().buffered()).use { kept ->
                listOf(held.clientId, held.sender, held.senderName, held.entry, held.signature, held.title, held.place, held.from).forEach { kept.text(it) }
                kept.writeLong(held.at)
            }
            held
        }
    }.getOrNull()

    /** The picture that came with an entry; null when none did. */
    fun photoOf(held: Carried): File? = photoFile(held).takeIf { it.isFile }

    /** What this phone holds for others, the newest first. */
    fun load(): List<Carried> = folder.listFiles().orEmpty().filter { it.name.endsWith(KEPT) }
        .mapNotNull { file ->
            runCatching {
                DataInputStream(file.inputStream().buffered()).use { kept ->
                    Carried(kept.text(), kept.text(), kept.text(), kept.text(), kept.text(), kept.text(), kept.text(), kept.text(), kept.readLong())
                }
            }.getOrNull()
        }
        .sortedByDescending { it.at }

    /** The entry has gone on to its place, or the place's server would not have it: it is kept no longer. */
    fun drop(held: Carried) {
        aboutFile(held).delete()
        photoFile(held).delete()
    }

    private fun name(held: Carried) = safe(held.sender) + "-" + safe(held.clientId)
    private fun aboutFile(held: Carried) = File(folder, name(held) + KEPT)
    private fun photoFile(held: Carried) = File(folder, name(held) + ".jpg")

    companion object {
        /** What a sealed file begins with when it is an entry to send on. */
        private val MARK = "DLENTRY1\n".toByteArray(Charsets.US_ASCII)
        private const val KEPT = ".entry"

        /** No one piece of an entry is anywhere near this long; a file that says otherwise is not one. */
        private const val MAX_TEXT_BYTES = 256 * 1024

        /** A name from another phone becomes part of a file name here, so only letters and digits of it are used. */
        private fun safe(text: String) = text.filter { it.isLetterOrDigit() }.take(40)

        private fun DataOutputStream.text(value: String) {
            val bytes = value.toByteArray()
            writeInt(bytes.size)
            write(bytes)
        }

        private fun DataInputStream.text(): String {
            val size = readInt()
            require(size in 0..MAX_TEXT_BYTES)
            return String(ByteArray(size).also { readFully(it) })
        }

        /** The user's signature on an entry: only their phone and the place's server know [key]. */
        fun sign(key: String, entry: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
            return hex(mac.doFinal(entry.toByteArray()))
        }

        /** The fingerprint of a picture, written into the signed entry so that the picture cannot be swapped on the way. */
        fun fingerprint(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
