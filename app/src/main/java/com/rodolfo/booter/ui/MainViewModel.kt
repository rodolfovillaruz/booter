package com.rodolfo.booter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rodolfo.booter.aws.AwsCredentials
import com.rodolfo.booter.aws.Ec2Client
import com.rodolfo.booter.aws.Ec2Image
import com.rodolfo.booter.aws.Ec2Instance
import com.rodolfo.booter.aws.Ec2SecurityGroup
import com.rodolfo.booter.data.PendingResizeStore
import com.rodolfo.booter.security.CredentialVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import javax.crypto.Cipher

data class ShutdownNotice(
    val instanceName: String,
    val size: String,
    /** null when the shutdown behavior couldn't be checked. */
    val terminatesOnShutdown: Boolean?,
)

/** The launch dialog's live options; the dialog is open while this is non-null in [UiState]. */
data class LaunchOptions(
    val loading: Boolean = true,
    val images: List<Ec2Image> = emptyList(),
    val keyPairs: List<String> = emptyList(),
    val securityGroups: List<Ec2SecurityGroup> = emptyList(),
    val error: String? = null,
    val launching: Boolean = false,
    /** One per dialog, so a repeated Launch tap can't start a second instance. */
    val clientToken: String = UUID.randomUUID().toString(),
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
    val launchOptions: LaunchOptions? = null,
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

    // --- Launch --------------------------------------------------------------------------

    fun openLaunch() {
        val ec2 = client ?: return
        _state.update { it.copy(launchOptions = LaunchOptions()) }
        viewModelScope.launch { loadLaunchOptions(ec2) }
    }

    fun retryLaunchOptions() {
        val ec2 = client ?: return
        _state.update { it.copy(launchOptions = it.launchOptions?.copy(loading = true, error = null)) }
        viewModelScope.launch { loadLaunchOptions(ec2) }
    }

    fun dismissLaunch() = _state.update { it.copy(launchOptions = null) }

    fun launch(name: String, image: Ec2Image, size: String, keyName: String?, securityGroupId: String?) {
        val ec2 = client ?: return
        val options = _state.value.launchOptions ?: return
        if (options.launching) return
        _state.update { it.copy(launchOptions = options.copy(launching = true)) }
        viewModelScope.launch {
            try {
                val instance = ec2.runInstance(name.trim(), image.id, size, keyName, securityGroupId, options.clientToken)
                _state.update { s ->
                    val others = s.instances.filter { it.id != instance.id }
                    s.copy(
                        launchOptions = null,
                        instances = (others + instance).sortedBy { it.displayName.lowercase() },
                    )
                }
                showMessage("Launching ${instance.displayName} as $size")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(launchOptions = it.launchOptions?.copy(launching = false)) }
                showMessage("Couldn't launch: ${e.message}")
            }
        }
    }

    private suspend fun loadLaunchOptions(ec2: Ec2Client) {
        try {
            val loaded = coroutineScope {
                val images = async { ec2.describeLaunchImages() }
                val keyPairs = async { ec2.describeKeyPairs() }
                val securityGroups = async { ec2.describeDefaultVpcSecurityGroups() }
                LaunchOptions(
                    loading = false,
                    images = images.await(),
                    keyPairs = keyPairs.await(),
                    securityGroups = securityGroups.await(),
                )
            }
            _state.update { s ->
                s.copy(launchOptions = s.launchOptions?.let { loaded.copy(clientToken = it.clientToken) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(launchOptions = it.launchOptions?.copy(loading = false, error = e.message ?: "Unknown error"))
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
