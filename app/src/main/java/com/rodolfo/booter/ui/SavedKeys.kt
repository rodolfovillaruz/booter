package com.rodolfo.booter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sshborg.SshBorgApp
import com.sshborg.data.db.SshKeyEntity

/** The SSH keys saved on this device, null until they've loaded. */
@Composable
fun rememberSavedKeys(): State<List<SshKeyEntity>?> {
    val app = LocalContext.current.applicationContext as SshBorgApp
    return app.db.sshKeyDao().getAll().collectAsState(initial = null)
}

/** "type base64", the public half of [key] without its comment, or null when it can't be read. */
fun publicKeyLine(key: SshKeyEntity): String? {
    val parts = key.publicKey.trim().split(Regex("\\s+"))
    return if (parts.size < 2) null else "${parts[0]} ${parts[1]}"
}

/** Picks one of the saved keys; [onManageKeys] opens the Keys screen to add one. */
@Composable
fun SavedKeyPickerDialog(
    title: String,
    note: String?,
    onPick: (SshKeyEntity) -> Unit,
    onManageKeys: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keys by rememberSavedKeys()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val list = keys
            when {
                list == null -> {}
                list.isEmpty() -> Text("No SSH keys yet. Generate or import one under SSH keys.")
                else -> Column {
                    note?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn {
                        items(list, key = { it.id }) { key ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(key) }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(key.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    key.keyType,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onManageKeys) { Text("SSH keys") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
