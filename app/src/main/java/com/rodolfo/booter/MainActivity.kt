package com.rodolfo.booter

import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.rodolfo.booter.aws.AwsCredentials
import com.rodolfo.booter.aws.Ec2Instance
import com.rodolfo.booter.security.BiometricUnavailableException
import com.rodolfo.booter.security.Biometrics
import com.rodolfo.booter.ui.AppSettings
import com.rodolfo.booter.ui.BooterApp
import com.rodolfo.booter.ui.MainViewModel
import com.rodolfo.booter.ui.theme.BooterTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.crypto.Cipher

/** FragmentActivity (not plain ComponentActivity) because BiometricPrompt needs it. */
class MainActivity : FragmentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BooterTheme {
                BooterApp(vm = vm, unlock = ::unlock, saveSettings = ::saveSettings, terminate = ::terminate)
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

    /** Blank key fields mean "keep the current keys, just update the other settings". */
    private suspend fun saveSettings(keyId: String, secret: String, settings: AppSettings): Boolean {
        if (keyId.isBlank() && secret.isBlank() && vm.state.value.unlocked) {
            vm.updateSettings(settings)
            return true
        }
        return try {
            val authed = authenticate("Encrypt AWS keys", "Lock them to your fingerprint", vm.cipherForSave())
                ?: return false
            vm.saveCredentials(authed, AwsCredentials(keyId, secret), settings)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.showMessage("Couldn't save keys: ${e.message}")
            false
        }
    }

    /** Terminating can't be undone, so it takes a fingerprint. */
    private fun terminate(instance: Ec2Instance) {
        lifecycleScope.launch {
            val confirmed = try {
                Biometrics.confirm(
                    this@MainActivity,
                    "Terminate ${instance.displayName}?",
                    instance.id,
                    "This deletes the instance for good, along with its root disk and any other disk " +
                        "set to delete on termination. It can't be undone.",
                )
            } catch (e: BiometricUnavailableException) {
                vm.showMessage(e.message ?: "Fingerprint unavailable")
                false
            }
            if (confirmed) vm.terminate(instance)
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
