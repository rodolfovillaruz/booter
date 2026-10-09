package com.rodolfo.booter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.sshborg.Ec2Launch
import com.sshborg.data.db.SshKeyEntity

/**
 * The first connection to an instance: who to log in as and with which key. What the user
 * picks is saved as a host linked to the instance, so later taps connect straight away.
 */
@Composable
fun ConnectDialog(
    launch: Ec2Launch,
    onConnect: (username: String, key: SshKeyEntity) -> Unit,
    onManageKeys: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keys by rememberSavedKeys()
    var username by rememberSaveable { mutableStateOf("ec2-user") }
    var keyId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickingKey by rememberSaveable { mutableStateOf(false) }
    val key = keys?.let { list -> list.find { it.id == keyId } ?: list.singleOrNull() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to ${launch.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    launch.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("Username") },
                    supportingText = { Text("ec2-user on Amazon Linux, ubuntu on Ubuntu") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickingKey = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(key?.let { "Key: ${it.label}" } ?: "Pick a key")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { key?.let { onConnect(username, it) } },
                enabled = key != null && username.isNotEmpty(),
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingKey) {
        SavedKeyPickerDialog(
            title = "Key for ${launch.name}",
            note = "Pick the key whose public half is on the instance (its key pair).",
            onPick = {
                keyId = it.id
                pickingKey = false
            },
            onManageKeys = {
                pickingKey = false
                onManageKeys()
            },
            onDismiss = { pickingKey = false },
        )
    }
}
