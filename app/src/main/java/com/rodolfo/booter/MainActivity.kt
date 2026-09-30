package com.rodolfo.booter

import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.rodolfo.booter.aws.AwsCredentials
import com.rodolfo.booter.security.BiometricUnavailableException
import com.rodolfo.booter.security.Biometrics
import com.rodolfo.booter.ui.BooterApp
import com.rodolfo.booter.ui.MainViewModel
import com.rodolfo.booter.ui.theme.BooterTheme
import kotlinx.coroutines.CancellationException
import javax.crypto.Cipher

/** FragmentActivity (not plain ComponentActivity) because BiometricPrompt needs it. */
class MainActivity : FragmentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BooterTheme {
                BooterApp(vm = vm, unlock = ::unlock, saveSettings = ::saveSettings)
            }
        }
    }

    private suspend fun unlock() {
        try {
            val authed = authenticate("Unlock Booter", "Decrypt your AWS keys", vm.cipherForUnlock()) ?: return
            vm.completeUnlock(authed)
        } catch (e: KeyPermanentlyInvalidatedException) {
            vm.onKeyInvalidated()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.showMessage("Couldn't unlock: ${e.message}")
        }
    }

    /** Blank key fields mean "keep the current keys, just update the region". */
    private suspend fun saveSettings(keyId: String, secret: String, region: String): Boolean {
        if (keyId.isBlank() && secret.isBlank() && vm.state.value.unlocked) {
            vm.updateRegion(region)
            return true
        }
        return try {
            val authed = authenticate("Encrypt AWS keys", "Lock them to your fingerprint", vm.cipherForSave())
                ?: return false
            vm.saveCredentials(authed, AwsCredentials(keyId, secret), region)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.showMessage("Couldn't save keys: ${e.message}")
            false
        }
    }

    private suspend fun authenticate(title: String, subtitle: String, cipher: Cipher): Cipher? =
        try {
            Biometrics.authenticate(this, title, subtitle, cipher)
        } catch (e: BiometricUnavailableException) {
            vm.showMessage(e.message ?: "Fingerprint unavailable")
            null
        }
}
