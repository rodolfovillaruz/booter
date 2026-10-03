@file:OptIn(ExperimentalMaterial3Api::class)

package com.rodolfo.booter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rodolfo.booter.aws.Ec2Image
import com.rodolfo.booter.aws.INSTANCE_SIZES

/** Launches a new instance with the usual defaults; only image and key pair come from AWS. */
@Composable
fun LaunchDialog(
    options: LaunchOptions,
    onLaunch: (name: String, image: Ec2Image, size: String, keyName: String?, securityGroupId: String?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf(INSTANCE_SIZES.keys.first()) }
    var imageId by rememberSaveable { mutableStateOf<String?>(null) }
    // "" means the user chose no key pair; null means they haven't picked yet.
    var keyChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }

    val image = options.images.find { it.id == imageId } ?: options.images.firstOrNull()
    val keyName = (keyChoice ?: options.keyPairs.firstOrNull())?.takeIf { it.isNotEmpty() }
    // Null only when the region has no default VPC; AWS then picks (and likely rejects) the launch.
    // Until the user picks, prefer a group SSHBorg can get through.
    val group = options.securityGroups.find { it.id == groupId }
        ?: options.securityGroups.firstOrNull { it.allowsSsh }
        ?: options.securityGroups.firstOrNull()
    val ready = !options.loading && options.error == null

    AlertDialog(
        onDismissRequest = { if (!options.launching) onDismiss() },
        title = { Text("Launch instance") },
        text = {
            when {
                options.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Loading images, key pairs, and security groups…", modifier = Modifier.padding(start = 16.dp))
                }

                options.error != null -> Text("Couldn't load launch options: ${options.error}")

                else -> Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (image == null) {
                        Text("No images found in this region.", color = MaterialTheme.colorScheme.error)
                    } else {
                        Picker(
                            label = "Image",
                            selected = image.label,
                            choices = options.images.map { it.id to it.label },
                            supporting = image.id,
                            onPick = { imageId = it },
                        )
                    }

                    Column {
                        Text("Size", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            INSTANCE_SIZES.keys.forEach { s ->
                                FilterChip(selected = s == size, onClick = { size = s }, label = { Text(s) })
                            }
                        }
                        Text(
                            INSTANCE_SIZES[size].orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Picker(
                        label = "Key pair",
                        selected = keyName ?: "No key pair",
                        choices = options.keyPairs.map { it to it } + ("" to "No key pair"),
                        supporting = if (keyName == null) "You won't be able to SSH in" else null,
                        onPick = { keyChoice = it },
                    )

                    if (group != null) {
                        Picker(
                            label = "Security group",
                            selected = group.name,
                            choices = options.securityGroups.map {
                                it.id to "${it.name} · ${if (it.allowsSsh) "SSH open" else "no SSH"}"
                            },
                            supporting = if (group.allowsSsh) {
                                "${group.id} · allows SSH"
                            } else {
                                "${group.id} · no inbound SSH rule, so SSHBorg can't connect"
                            },
                            onPick = { groupId = it },
                        )
                    }

                    Text(
                        "Uses the default VPC and subnet, and the image's default disk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when {
                options.error != null -> TextButton(onClick = onRetry) { Text("Retry") }
                options.launching -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> TextButton(
                    onClick = { image?.let { onLaunch(name, it, size, keyName, group?.id) } },
                    enabled = ready && image != null,
                ) { Text("Launch") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !options.launching) { Text("Cancel") }
        },
    )
}

/** A read-only dropdown; [choices] are (value, label) pairs. */
@Composable
private fun Picker(
    label: String,
    selected: String,
    choices: List<Pair<String, String>>,
    supporting: String?,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            supportingText = supporting?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onPick(value)
                        expanded = false
                    },
                )
            }
        }
    }
}
