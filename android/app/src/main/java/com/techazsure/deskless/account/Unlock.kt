package com.techazsure.deskless.account

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * Unlocking the app with the fingerprint. Signing in needs the network once; after that the phone keeps
 * the sign-in, and it is the fingerprint that stands between the app and whoever picks the phone up.
 */
object Unlock {
    /** True when the phone has a fingerprint sensor with a finger enrolled. Without one there is no lock. */
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return false
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(BiometricManager::class.java) else null
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                @Suppress("DEPRECATION")
                manager?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            else -> true
        }
    }

    /** Shows the fingerprint prompt. [onResult] is called once on the main thread with whether it matched. */
    fun ask(context: Context, who: String, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onResult(true)
            return
        }
        var answered = false
        fun answer(matched: Boolean) {
            if (answered) return
            answered = true
            onResult(matched)
        }
        val executor = ContextCompat.getMainExecutor(context)
        runCatching {
            BiometricPrompt.Builder(context)
                .setTitle("Unlock DeskLess")
                .setSubtitle(who)
                .setNegativeButton("Cancel", executor) { _, _ -> answer(false) }
                .build()
                .authenticate(
                    CancellationSignal(), executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = answer(true)
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = answer(false)
                    },
                )
        }.onFailure { answer(false) }
    }
}
