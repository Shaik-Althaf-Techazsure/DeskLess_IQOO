package com.techazsure.deskless.report

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Signing a report off with the phone's fingerprint sensor. The phone keeps a signing key in its secure
 * hardware that works only straight after a fingerprint match. The finished PDF is signed with it and the
 * signature travels beside the PDF, so anyone holding both can tell whether the report was changed after
 * the inspector signed it. Nothing leaves the phone.
 */
object SignOff {
    private const val ALIAS = "deskless-signoff"
    private const val STORE = "AndroidKeyStore"
    private const val ALGORITHM = "SHA256withECDSA"

    enum class Verdict { VALID, CHANGED }

    /** Who signed, when, with which key, and whether the PDF still matches the signature. */
    data class Seal(val by: String, val at: String, val keyId: String, val verdict: Verdict)

    /** What a signed report prints about its signature: who, when, with which key, and the file that holds it. */
    data class Mark(val by: String, val at: Date, val keyId: String, val file: String)

    /** The signature file that belongs to [pdf]. */
    fun fileFor(pdf: File) = File(pdf.parentFile, pdf.nameWithoutExtension + ".sig.json")

    /** True when this phone can sign: Android 9 or later with a fingerprint enrolled. */
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(BiometricManager::class.java) ?: return false
            return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            return context.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        }
        return true
    }

    /** The short name of this phone's signing key, e.g. "3F9A-22C1", or null before the first signature. */
    fun keyId(): String? = runCatching { publicKey()?.let(::idOf) }.getOrNull()

    /**
     * Shows the fingerprint prompt. [onResult] is called once on the main thread: with a signature that is
     * ready to use if the finger matched, or with null if the user chose not to sign or it could not be done.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun ask(context: Context, who: String, what: String, onResult: (Signature?) -> Unit) {
        val signature = try {
            Signature.getInstance(ALGORITHM).apply { initSign(privateKey()) }
        } catch (e: Exception) {
            // A key is thrown away by the phone when fingerprints are added or removed; make a new one once.
            runCatching {
                KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS)
                Signature.getInstance(ALGORITHM).apply { initSign(privateKey()) }
            }.getOrNull()
        }
        if (signature == null) {
            onResult(null)
            return
        }
        var answered = false
        fun answer(result: Signature?) {
            if (answered) return
            answered = true
            onResult(result)
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt.Builder(context)
            .setTitle("Sign this report")
            .setSubtitle(if (who.isBlank()) what else "$who · $what")
            .setDescription("Your fingerprint confirms that you made this report. It is checked by the phone and never stored in the report.")
            .setNegativeButton("Build unsigned", executor) { _, _ -> answer(null) }
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG) }
            .build()
        runCatching {
            prompt.authenticate(
                BiometricPrompt.CryptoObject(signature),
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                        answer(result.cryptoObject?.signature)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = answer(null)
                },
            )
        }.onFailure { answer(null) }
    }

    /**
     * Signs the finished [pdf] with a signature the fingerprint has just unlocked, and writes the signature
     * file beside it. Returns that file.
     */
    fun seal(pdf: File, signature: Signature, by: String, at: Date): File {
        val bytes = pdf.readBytes()
        signature.update(bytes)
        val signed = signature.sign()
        val key = publicKey() ?: error("The signing key is missing.")
        val json = JSONObject()
            .put("file", pdf.name)
            .put("signed_by", by)
            .put("signed_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(at))
            .put("confirmed_with", "Fingerprint, checked on the phone that built the report")
            .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .put("algorithm", "ECDSA P-256 with SHA-256")
            .put("signature", Base64.encodeToString(signed, Base64.NO_WRAP))
            .put("public_key", Base64.encodeToString(key.encoded, Base64.NO_WRAP))
            .put("key_id", idOf(key))
            .put("how_to_check", "Verify the signature over the PDF's bytes with the public key. Any change to the PDF makes it fail.")
        return fileFor(pdf).apply { writeText(json.toString(2)) }
    }

    /** Checks [pdf] against its signature file. Null when the report was never signed. */
    fun check(pdf: File): Seal? {
        val file = fileFor(pdf).takeIf { it.isFile } ?: return null
        return runCatching {
            val json = JSONObject(file.readText())
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(json.getString("public_key"), Base64.NO_WRAP)))
            val matches = Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(pdf.readBytes())
                verify(Base64.decode(json.getString("signature"), Base64.NO_WRAP))
            }
            Seal(
                by = json.optString("signed_by"),
                at = json.optString("signed_at"),
                keyId = idOf(key),
                verdict = if (matches) Verdict.VALID else Verdict.CHANGED,
            )
        }.getOrElse { Seal("", "", "", Verdict.CHANGED) }
    }

    private fun publicKey(): PublicKey? =
        KeyStore.getInstance(STORE).apply { load(null) }.getCertificate(ALIAS)?.publicKey

    /** The key that signs. It is made on first use, lives in the phone's secure hardware and never leaves it. */
    private fun privateKey(): PrivateKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? PrivateKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // Every signature needs its own fingerprint match.
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).apply { initialize(spec) }.generateKeyPair().private
    }

    /** A short name for a key that a person can read out: the start of its SHA-256. */
    private fun idOf(key: PublicKey): String =
        hex(MessageDigest.getInstance("SHA-256").digest(key.encoded)).take(8).uppercase(Locale.US).chunked(4).joinToString("-")

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
