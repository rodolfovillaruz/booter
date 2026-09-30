package com.rodolfo.booter.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.rodolfo.booter.aws.AwsCredentials
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the AWS keys encrypted with an AES-256-GCM key that lives in the Android Keystore
 * and can only be used right after a successful strong-biometric (fingerprint) check.
 * The key is invalidated if fingerprints are added or removed, so a newly enrolled finger
 * can never unlock previously saved credentials.
 *
 * Callers get a [Cipher] from [encryptionCipher] / [decryptionCipher], pass it through
 * BiometricPrompt, and hand the authenticated cipher back to [encrypt] / [decrypt].
 */
class CredentialVault(context: Context) {

    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    var region: String
        get() = prefs.getString(KEY_REGION, DEFAULT_REGION) ?: DEFAULT_REGION
        set(value) = prefs.edit { putString(KEY_REGION, value) }

    fun hasCredentials(): Boolean = prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    fun encryptionCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        return cipher
    }

    /** @throws KeyPermanentlyInvalidatedException if fingerprints changed since the keys were saved. */
    fun decryptionCipher(): Cipher {
        val iv = Base64.decode(
            prefs.getString(KEY_IV, null) ?: error("No saved credentials"),
            Base64.NO_WRAP,
        )
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw KeyPermanentlyInvalidatedException("Encryption key is missing")
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    fun encrypt(authenticatedCipher: Cipher, credentials: AwsCredentials) {
        val json = JSONObject()
            .put("accessKeyId", credentials.accessKeyId)
            .put("secretAccessKey", credentials.secretAccessKey)
            .toString()
        val ciphertext = authenticatedCipher.doFinal(json.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            putString(KEY_IV, Base64.encodeToString(authenticatedCipher.iv, Base64.NO_WRAP))
        }
    }

    fun decrypt(authenticatedCipher: Cipher): AwsCredentials {
        val ciphertext = Base64.decode(
            prefs.getString(KEY_CIPHERTEXT, null) ?: error("No saved credentials"),
            Base64.NO_WRAP,
        )
        val json = JSONObject(String(authenticatedCipher.doFinal(ciphertext), Charsets.UTF_8))
        return AwsCredentials(
            accessKeyId = json.getString("accessKeyId"),
            secretAccessKey = json.getString("secretAccessKey"),
        )
    }

    fun clear() {
        prefs.edit {
            remove(KEY_CIPHERTEXT)
            remove(KEY_IV)
        }
        deleteKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Every use needs a fresh fingerprint; device PIN is not accepted.
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun deleteKey() {
        keyStore().deleteEntry(KEY_ALIAS)
    }

    companion object {
        const val DEFAULT_REGION = "us-east-1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "booter_aws_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val KEY_IV = "iv"
        private const val KEY_REGION = "region"
    }
}
