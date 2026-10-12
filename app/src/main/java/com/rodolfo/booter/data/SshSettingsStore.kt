package com.rodolfo.booter.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who to log in as, and with which saved SSH key: defaults for every instance, and overrides
 * per instance ID. Key IDs are those of the SSH keys screen (SshKeyEntity.id).
 */
data class SshSettings(
    val defaultUsername: String? = null,
    val defaultKeyId: Long? = null,
    val usernames: Map<String, String> = emptyMap(),
    val keyIds: Map<String, Long> = emptyMap(),
) {
    /** What an instance without its own username logs in as. */
    val fallbackUsername: String get() = defaultUsername ?: DEFAULT_USERNAME

    fun username(instanceId: String): String = usernames[instanceId] ?: fallbackUsername

    /** The key an instance uses, which may no longer exist if it was deleted. */
    fun keyId(instanceId: String): Long? = keyIds[instanceId] ?: defaultKeyId

    companion object {
        const val DEFAULT_USERNAME = "ec2-user"
    }
}

/** Keeps [SshSettings] across restarts; a null value clears a setting back to its default. */
class SshSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<SshSettings> = _settings.asStateFlow()

    fun setDefaultUsername(username: String?) =
        write { if (username == null) remove(DEFAULT_USER) else putString(DEFAULT_USER, username) }

    fun setDefaultKey(keyId: Long?) =
        write { if (keyId == null) remove(DEFAULT_KEY) else putLong(DEFAULT_KEY, keyId) }

    fun setUsername(instanceId: String, username: String?) =
        write { if (username == null) remove(USER + instanceId) else putString(USER + instanceId, username) }

    fun setKey(instanceId: String, keyId: Long?) =
        write { if (keyId == null) remove(KEY + instanceId) else putLong(KEY + instanceId, keyId) }

    private fun write(change: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = change)
        _settings.value = read()
    }

    private fun read(): SshSettings {
        val all = prefs.all
        return SshSettings(
            defaultUsername = all[DEFAULT_USER] as? String,
            defaultKeyId = all[DEFAULT_KEY] as? Long,
            usernames = all.mapNotNull { (k, v) -> if (k.startsWith(USER) && v is String) k.removePrefix(USER) to v else null }.toMap(),
            keyIds = all.mapNotNull { (k, v) -> if (k.startsWith(KEY) && v is Long) k.removePrefix(KEY) to v else null }.toMap(),
        )
    }

    private companion object {
        const val DEFAULT_USER = "default_username"
        const val DEFAULT_KEY = "default_key_id"
        const val USER = "user."
        const val KEY = "key."
    }
}
