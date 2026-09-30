package com.rodolfo.booter.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BiometricUnavailableException(message: String) : Exception(message)

object Biometrics {

    private val CANCEL_CODES = setOf(
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
    )

    /**
     * Shows the fingerprint prompt bound to [cipher].
     * Returns the now-usable cipher, or null if the user backed out.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cipher: Cipher,
    ): Cipher? {
        when (BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                throw BiometricUnavailableException("No fingerprint enrolled. Add one in system settings first.")
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                throw BiometricUnavailableException("This device has no fingerprint sensor.")
            else ->
                throw BiometricUnavailableException("Fingerprint sensor is unavailable right now.")
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!cont.isActive) return
                        if (errorCode in CANCEL_CODES) {
                            cont.resume(null)
                        } else {
                            cont.resumeWithException(BiometricUnavailableException(errString.toString()))
                        }
                    }
                },
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }
    }
}
