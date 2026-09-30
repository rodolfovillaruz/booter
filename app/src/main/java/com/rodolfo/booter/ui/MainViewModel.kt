package com.rodolfo.booter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rodolfo.booter.aws.AwsCredentials
import com.rodolfo.booter.aws.Ec2Client
import com.rodolfo.booter.aws.Ec2Instance
import com.rodolfo.booter.data.PendingResizeStore
import com.rodolfo.booter.security.CredentialVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.crypto.Cipher

data class ShutdownNotice(
    val instanceName: String,
    val size: String,
    /** null when the shutdown behavior couldn't be checked. */
    val terminatesOnShutdown: Boolean?,
)

data class UiState(
    val hasCredentials: Boolean = false,
    val unlocked: Boolean = false,
    val region: String = CredentialVault.DEFAULT_REGION,
    val maskedKeyId: String? = null,
    val instances: List<Ec2Instance> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val busyIds: Set<String> = emptySet(),
    val pendingResizes: Map<String, String> = emptyMap(),
    val shutdownNotice: ShutdownNotice? = null,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val vault = CredentialVault(app)
    private val pendingStore = PendingResizeStore(app)
    private val pollMutex = Mutex()

    /** Decrypted keys live only in memory, for as long as the process does. */
    private var credentials: AwsCredentials? = null

    private val _state = MutableStateFlow(
        UiState(
            hasCredentials = vault.hasCredentials(),
            region = vault.region,
            pendingResizes = pendingStore.all(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val client: Ec2Client?
        get() = credentials?.let { Ec2Client(it, _state.value.region) }

    // --- Credentials ---------------------------------------------------------------------

    fun cipherForUnlock(): Cipher = vault.decryptionCipher()

    fun cipherForSave(): Cipher = vault.encryptionCipher()

    fun completeUnlock(authenticatedCipher: Cipher) {
        runCatching { vault.decrypt(authenticatedCipher) }
            .onSuccess(::onCredentialsReady)
            .onFailure { showMessage("Couldn't decrypt your keys: ${it.message}") }
    }

    fun saveCredentials(authenticatedCipher: Cipher, creds: AwsCredentials, region: String) {
        vault.encrypt(authenticatedCipher, creds)
        vault.region = region
        _state.update { it.copy(hasCredentials = true, region = region, instances = emptyList(), loaded = false) }
        onCredentialsReady(creds)
        showMessage("Keys encrypted with your fingerprint")
    }

    fun updateRegion(region: String) {
        if (region == _state.value.region) return
        vault.region = region
        _state.update { it.copy(region = region, instances = emptyList(), loaded = false) }
        refresh()
    }

    fun onKeyInvalidated() {
        clearCredentials()
        showMessage("Your fingerprints changed, so the saved keys can't be unlocked anymore. Please enter them again.")
    }

    fun clearCredentials() {
        vault.clear()
        credentials = null
        _state.update {
            it.copy(hasCredentials = false, unlocked = false, maskedKeyId = null, instances = emptyList(), loaded = false)
        }
    }

    private fun onCredentialsReady(creds: AwsCredentials) {
        credentials = creds
        _state.update { it.copy(unlocked = true, maskedKeyId = mask(creds.accessKeyId)) }
        refresh()
    }

    // --- Instances -----------------------------------------------------------------------

    fun refresh() {
        viewModelScope.launch { poll(showSpinner = true) }
    }

    /** Reloads the list, then boots any instance whose pending resize is now possible. */
    suspend fun poll(showSpinner: Boolean = false) {
        val ec2 = client ?: return
        if (!pollMutex.tryLock()) return
        try {
            if (showSpinner) _state.update { it.copy(refreshing = true) }
            val instances = ec2.describeInstances()
            _state.update { it.copy(instances = instances, loaded = true) }
            resumePendingResizes(ec2, instances)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showMessage("Couldn't load instances: ${e.message}")
        } finally {
            _state.update { it.copy(refreshing = false) }
            pollMutex.unlock()
        }
    }

    /** Poll fast while something is changing or a resize is waiting on a shutdown. */
    fun nextPollDelayMs(): Long {
        val s = _state.value
        val changing = s.instances.any { it.state in TRANSITIONAL_STATES }
        return if (changing || s.pendingResizes.isNotEmpty()) 5_000 else 30_000
    }

    fun start(instance: Ec2Instance, size: String) {
        val ec2 = client ?: return
        viewModelScope.launch { startWithSize(ec2, instance, size) }
    }

    /** Remembers the new size; the instance gets booted with it once the user shuts it down. */
    fun requestResize(instance: Ec2Instance, size: String) {
        val ec2 = client ?: return
        pendingStore.put(instance.id, size)
        _state.update { it.copy(pendingResizes = pendingStore.all()) }
        viewModelScope.launch {
            val behavior = try {
                ec2.shutdownBehavior(instance.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update {
                it.copy(
                    shutdownNotice = ShutdownNotice(
                        instanceName = instance.displayName,
                        size = size,
                        terminatesOnShutdown = behavior?.let { b -> b == "terminate" },
                    ),
                )
            }
        }
    }

    fun cancelResize(instanceId: String) {
        pendingStore.remove(instanceId)
        _state.update { it.copy(pendingResizes = pendingStore.all()) }
    }

    fun dismissShutdownNotice() = _state.update { it.copy(shutdownNotice = null) }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private suspend fun resumePendingResizes(ec2: Ec2Client, instances: List<Ec2Instance>) {
        for ((id, size) in pendingStore.all()) {
            val instance = instances.find { it.id == id }
            if (instance == null) {
                pendingStore.remove(id) // terminated or gone
                continue
            }
            if (instance.state != "stopped" || id in _state.value.busyIds) continue

            // One attempt only: on failure the user gets a message instead of a retry loop.
            pendingStore.remove(id)
            startWithSize(ec2, instance, size)
        }
        _state.update { it.copy(pendingResizes = pendingStore.all()) }
    }

    private suspend fun startWithSize(ec2: Ec2Client, instance: Ec2Instance, size: String) {
        setBusy(instance.id, true)
        try {
            if (instance.type != size) ec2.modifyInstanceType(instance.id, size)
            ec2.startInstance(instance.id)
            _state.update { s ->
                s.copy(instances = s.instances.map { if (it.id == instance.id) it.copy(state = "pending", type = size) else it })
            }
            showMessage("Starting ${instance.displayName} as $size")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showMessage("Couldn't start ${instance.displayName}: ${e.message}")
        } finally {
            setBusy(instance.id, false)
        }
    }

    private fun setBusy(id: String, busy: Boolean) =
        _state.update { it.copy(busyIds = if (busy) it.busyIds + id else it.busyIds - id) }

    private fun mask(keyId: String): String =
        if (keyId.length <= 8) "••••" else "${keyId.take(4)}••••${keyId.takeLast(4)}"

    private companion object {
        val TRANSITIONAL_STATES = setOf("pending", "stopping", "shutting-down")
    }
}
