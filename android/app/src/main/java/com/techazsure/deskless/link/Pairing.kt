package com.techazsure.deskless.link

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A phone running DeskLess: its code, the name people know it by, and the secret the two phones share.
 * For this phone itself the secret is the one its own QR code carries. [public] is the phone's public
 * key, which reports are sealed for; it is learnt the first time the two phones connect, and is blank
 * until then.
 */
data class Peer(val id: String, val name: String, val key: String, val public: String = "")

/**
 * How two phones come to trust each other. One shows a QR code holding its code and a secret; the other
 * scans it. From then on each proves to the other that it knows the secret, so reports pass between them
 * with nobody tapping Accept, and a phone that never saw the QR code is turned away.
 */
object Pairing {
    private const val SCHEME = "deskless://pair"

    // No 0/O or 1/I, so a code read aloud or copied by hand cannot be mistaken.
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    /** A new code for this phone, such as "DL-7K3F9Q2M". */
    fun newId(): String = "DL-" + (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** A new 128-bit secret, as hex. */
    fun newKey(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** What the QR code says. */
    fun uri(peer: Peer): String =
        "$SCHEME?id=${encode(peer.id)}&name=${encode(peer.name)}&key=${encode(peer.key)}"

    /** Reads a scanned QR code. Null when it is not a DeskLess pairing code. */
    fun parse(text: String): Peer? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("$SCHEME?")) return null
        val fields = trimmed.substringAfter('?').split('&').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else part.substring(0, at) to runCatching { URLDecoder.decode(part.substring(at + 1), "UTF-8") }.getOrDefault("")
        }.toMap()
        val id = fields["id"].orEmpty()
        val key = fields["key"].orEmpty()
        if (!id.startsWith("DL-") || key.length < 16) return null
        return Peer(id, fields["name"].orEmpty().ifBlank { id }, key)
    }

    /**
     * What a phone sends to show it knows the shared secret: a keyed hash that names both phones, so a
     * proof made for one pair cannot be replayed to another. It also covers the sender's public key, so
     * nobody in between can swap in a key of their own.
     */
    fun proof(key: String, from: String, to: String, public: String = ""): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        return mac.doFinal("deskless-link:$from>$to:$public".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** What this phone calls itself to phones nearby: its code, then its name. */
    fun endpointName(peer: Peer): String = "${peer.id}|${peer.name.take(40)}"

    /** The code and name in a nearby phone's announcement, or null if it is not in DeskLess's form. */
    fun fromEndpointName(text: String): Pair<String, String>? {
        val id = text.substringBefore('|')
        if (!id.startsWith("DL-")) return null
        return id to text.substringAfter('|', id)
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
}
