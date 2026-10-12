@file:OptIn(ExperimentalMaterial3Api::class)

package com.rodolfo.booter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rodolfo.booter.aws.Ec2Instance
import com.rodolfo.booter.data.SshSettings
import com.rodolfo.booter.data.SshSettingsStore
import com.sshborg.data.db.SshKeyEntity

/**
 * Who to log in to an instance as, and with which key. Unless it sets its own, an instance uses
 * the defaults from [SshDefaultsScreen]. Each change is saved as it's made.
 */
@Composable
fun InstanceSshScreen(
    instanceId: String,
    instance: Ec2Instance?,
    store: SshSettingsStore,
    onBack: () -> Unit,
    onConnect: (Ec2Instance) -> Unit,
    onOpenDefaults: () -> Unit,
    onManageKeys: () -> Unit,
) {
    val settings by store.settings.collectAsState()
    val keys by rememberSavedKeys()
    val keyList = keys.orEmpty()
    fun label(id: Long?): String? = id?.let { keyList.find { k -> k.id == it }?.label }

    val defaultKeyLabel = label(settings.defaultKeyId)
    val ownKeyId = settings.keyIds[instanceId]
    val effectiveKey = label(settings.keyId(instanceId))
    val canConnect = instance?.state == "running" && instance.publicIp != null && effectiveKey != null
    // Ticked stays ticked while the field is blank, even though a blank name saves as "no override".
    var customUsername by rememberSaveable { mutableStateOf(instanceId in settings.usernames) }

    SettingsScaffold(
        title = instance?.displayName ?: instanceId,
        subtitle = listOfNotNull(instanceId, instance?.state, instance?.publicIp).joinToString(" · "),
        onBack = onBack,
        onManageKeys = onManageKeys,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = customUsername,
                    role = Role.Checkbox,
                    onValueChange = { checked ->
                        customUsername = checked
                        store.setUsername(instanceId, if (checked) settings.fallbackUsername else null)
                    },
                ),
        ) {
            Checkbox(checked = customUsername, onCheckedChange = null)
            Text("Custom username", modifier = Modifier.padding(start = 12.dp))
        }
        if (customUsername) {
            UsernameField(
                value = settings.usernames[instanceId].orEmpty(),
                placeholder = settings.fallbackUsername,
                onChange = { store.setUsername(instanceId, it) },
            )
        } else {
            Note("Logs in as ${settings.fallbackUsername}, the default.")
        }

        KeyChooser(
            title = "Key for ${instance?.displayName ?: instanceId}",
            current = when {
                ownKeyId != null -> label(ownKeyId) ?: "A deleted key"
                defaultKeyLabel != null -> "$defaultKeyLabel (default)"
                else -> null
            },
            extraOption = "Use the default key" + (defaultKeyLabel?.let { " ($it)" } ?: ""),
            onPick = { store.setKey(instanceId, it?.id) },
            onManageKeys = onManageKeys,
        )

        Button(
            onClick = { instance?.let(onConnect) },
            enabled = canConnect,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }
        if (!canConnect) {
            Note(
                when {
                    instance?.state != "running" -> "Start the instance to connect."
                    instance.publicIp == null -> "The instance has no public IP."
                    else -> "Pick a key to connect."
                },
            )
        }

        HorizontalDivider()
        TextButton(onClick = onOpenDefaults) { Text("SSH defaults for all instances") }
    }
}

/** The username and key every instance uses unless it sets its own. */
@Composable
fun SshDefaultsScreen(
    store: SshSettingsStore,
    onBack: () -> Unit,
    onManageKeys: () -> Unit,
) {
    val settings by store.settings.collectAsState()
    val keys by rememberSavedKeys()
    val defaultKeyLabel = settings.defaultKeyId?.let { id -> keys.orEmpty().find { it.id == id }?.label }

    SettingsScaffold(title = "SSH defaults", subtitle = "For every instance", onBack = onBack, onManageKeys = onManageKeys) {
        Note("Used by every instance that doesn't set its own, under its SSH settings (swipe it right).")
        UsernameField(
            value = settings.defaultUsername.orEmpty(),
            placeholder = SshSettings.DEFAULT_USERNAME,
            onChange = store::setDefaultUsername,
        )
        KeyChooser(
            title = "Default key",
            current = defaultKeyLabel ?: settings.defaultKeyId?.let { "A deleted key" },
            extraOption = "No default key",
            onPick = { store.setDefaultKey(it?.id) },
            onManageKeys = onManageKeys,
        )
    }
}

@Composable
private fun SettingsScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onManageKeys: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = onManageKeys) { Text("SSH keys") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Blank means "not set", which saves as null. */
@Composable
private fun UsernameField(value: String, placeholder: String, onChange: (String?) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.trim()
            onChange(text.ifEmpty { null })
        },
        label = { Text("Username") },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A button naming the chosen key; [onPick] gets null for [extraOption]. */
@Composable
private fun KeyChooser(
    title: String,
    current: String?,
    extraOption: String,
    onPick: (SshKeyEntity?) -> Unit,
    onManageKeys: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
        Text(current?.let { "Key: $it" } ?: "Pick a key")
    }
    if (picking) {
        SavedKeyPickerDialog(
            title = title,
            note = null,
            onPick = {
                picking = false
                onPick(it)
            },
            onManageKeys = {
                picking = false
                onManageKeys()
            },
            onDismiss = { picking = false },
            extraOption = extraOption,
            onPickExtra = {
                picking = false
                onPick(null)
            },
        )
    }
}
