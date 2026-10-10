package com.techazsure.deskless.link

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealing a report for one phone. A sealed report can be carried by any number of other phones on its way
 * and none of them can open it: only the phone it is addressed to holds the key that does. The phone that
 * opens it can also tell that it was sealed by the phone it claims to come from, and that nothing was
 * changed on the way, including who it is from and who it is for.
 *
 * Each phone has a key pair (elliptic curve P-256). A report is sealed with AES-256-GCM under a key agreed
 * from the sender's key, a one-off key made for this report, and the receiver's public key.
 */
object Seal {
    private val MAGIC = "DLSEAL1\n".toByteArray(Charsets.US_ASCII)
    private const val MAX_HEADER = 4096
    private val random = SecureRandom()

    /** A phone's key pair, as text that can be stored. The private half never leaves the phone. */
    class Keys(val private: String, val public: String)

    /**
     * What travels in the clear on the outside of a sealed report: enough for a phone carrying it to know
     * where it is going, and nothing about what is inside.
     */
    data class Label(
        val id: String,
        val from: String,
        val fromName: String,
        val to: String,
        val made: Long,
        /** The one-off public key and the nonce the receiver needs to open it. */
        val oneOff: String,
        val nonce: String,
    )

    fun newKeys(): Keys {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        return Keys(encode(pair.private.encoded), encode(pair.public.encoded))
    }

    /** A new name for a report on its way. */
    fun newId(): String = ByteArray(9).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * Seals [plain] into [out] for the phone that holds the private half of [toPublic]. [mine] are the
     * sending phone's keys.
     */
    fun seal(plain: File, out: File, id: String, from: String, fromName: String, to: String, made: Long, mine: Keys, toPublic: String) {
        val receiver = publicKey(toPublic)
        val oneOff = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        val nonce = ByteArray(12).also(random::nextBytes)
        val label = Label(id, from, fromName, to, made, encode(oneOff.public.encoded), encode(nonce))
        val outside = write(label)
        val key = key(agree(oneOff.private, receiver), agree(privateKey(mine.private), receiver), outside)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            // The label is bound to the contents: change either and the report no longer opens.
            updateAAD(outside)
        }
        DataOutputStream(out.outputStream().buffered()).use { file ->
            file.write(MAGIC)
            file.writeInt(outside.size)
            file.write(outside)
            plain.inputStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    cipher.update(buffer, 0, read)?.let(file::write)
                }
            }
            file.write(cipher.doFinal())
        }
    }

    /** Reads the outside of a sealed report. Null when the file is not one. */
    fun label(sealed: File): Label? = runCatching {
        DataInputStream(sealed.inputStream().buffered()).use { file -> read(outsideOf(file)) }
    }.getOrNull()

    /**
     * Opens [sealed] into [out] with this phone's keys. [senderPublic] is the public key of the phone the
     * label says it is from. False, with nothing written, when it was not sealed for this phone, not by
     * that sender, or was changed on the way.
     */
    fun open(sealed: File, out: File, mine: Keys, senderPublic: String): Boolean {
        val opened = runCatching {
            DataInputStream(sealed.inputStream().buffered()).use { file ->
                val outside = outsideOf(file)
                val label = read(outside) ?: return false
                val me = privateKey(mine.private)
                val key = key(agree(me, publicKey(label.oneOff)), agree(me, publicKey(senderPublic)), outside)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, decode(label.nonce)))
                    updateAAD(outside)
                }
                out.outputStream().use { plain ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = file.read(buffer)
                        if (read < 0) break
                        cipher.update(buffer, 0, read)?.let(plain::write)
                    }
                    // This is where a wrong key or a changed file is caught.
                    plain.write(cipher.doFinal())
                }
            }
        }.isSuccess
        if (!opened) out.delete()
        return opened
    }

    /** Signs a short text with a phone's private key; used for the receipt a report's receiver sends back. */
    fun sign(mine: Keys, text: String): String = encode(
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey(mine.private))
            update(text.toByteArray())
            sign()
        }
    )

    fun signedBy(public: String, text: String, signature: String): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(public))
            update(text.toByteArray())
            verify(decode(signature))
        }
    }.getOrDefault(false)

    private fun outsideOf(file: DataInputStream): ByteArray {
        val magic = ByteArray(MAGIC.size).also(file::readFully)
        require(magic.contentEquals(MAGIC)) { "Not a sealed report" }
        val length = file.readInt()
        require(length in 1..MAX_HEADER) { "Not a sealed report" }
        return ByteArray(length).also(file::readFully)
    }

    private fun write(label: Label): ByteArray = listOf(
        "id" to label.id, "from" to label.from, "name" to label.fromName, "to" to label.to,
        "made" to label.made.toString(), "oneoff" to label.oneOff, "nonce" to label.nonce,
    ).joinToString("\n") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }.toByteArray()

    private fun read(outside: ByteArray): Label? {
        val fields = String(outside).split('\n').mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) null else line.substring(0, at) to URLDecoder.decode(line.substring(at + 1), "UTF-8")
        }.toMap()
        return Label(
            id = fields["id"]?.takeIf { it.matches(Regex("[0-9a-f]{8,40}")) } ?: return null,
            from = fields["from"] ?: return null,
            fromName = fields["name"].orEmpty(),
            to = fields["to"] ?: return null,
            made = fields["made"]?.toLongOrNull() ?: 0L,
            oneOff = fields["oneoff"] ?: return null,
            nonce = fields["nonce"] ?: return null,
        )
    }

    private fun agree(mine: PrivateKey, theirs: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(mine)
            doPhase(theirs, true)
            generateSecret()
        }

    /**
     * The sealing key, drawn (HKDF with SHA-256) from two agreed secrets: one that only the receiver can
     * work out again, and one that only the true sender could have made.
     */
    private fun key(forReceiver: ByteArray, fromSender: ByteArray, outside: ByteArray): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
        val drawn = hmac("DeskLess sealed report v1".toByteArray(), forReceiver + fromSender)
        return hmac(drawn, outside + 1.toByte())
    }

    private fun publicKey(text: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(text)))
    private fun privateKey(text: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decode(text)))
    // Keys and nonces are stored as hex: plain, and the same on every Android version.
    private fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun decode(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
