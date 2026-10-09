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
import androidx.compose.runtime.LaunchedEffect
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

/** Launches a new instance; image, key pair, subnet, and security group come from AWS. */
@Composable
fun LaunchDialog(
    options: LaunchOptions,
    onLaunch: (
        name: String,
        image: Ec2Image,
        size: String,
        keyName: String?,
        subnetId: String,
        securityGroupId: String?,
    ) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSavedKey: (publicKey: String, label: String) -> Unit,
    onManageKeys: () -> Unit,
    onImportKeyAs: (name: String) -> Unit,
    onCancelKeyImport: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf(INSTANCE_SIZES.keys.first()) }
    var imageId by rememberSaveable { mutableStateOf<String?>(null) }
    // "" means the user chose no key pair; null means they haven't picked yet.
    var keyChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var subnetId by rememberSaveable { mutableStateOf<String?>(null) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }

    // A saved key, once picked, selects the AWS key pair that holds it.
    LaunchedEffect(options.keyPick) { options.keyPick?.let { keyChoice = it.name } }
    var pickingKey by rememberSaveable { mutableStateOf(false) }

    val image = options.images.find { it.id == imageId } ?: options.images.firstOrNull()
    val keyName = (keyChoice ?: options.keyPairs.firstOrNull()?.name)?.takeIf { it.isNotEmpty() }
    // Null only when the region has no VPCs at all, and then there's nothing to launch into.
    val subnet = options.subnets.find { it.id == subnetId } ?: options.subnets.firstOrNull()
    // Groups belong to a VPC, so only the subnet's VPC's groups can be used. Until the user picks,
    // prefer one SSH can get through.
    val groups = options.securityGroups.filter { it.vpcId == subnet?.vpcId }
    val group = groups.find { it.id == groupId }
        ?: groups.firstOrNull { it.allowsSsh }
        ?: groups.firstOrNull()
    val ready = !options.loading && options.error == null

    AlertDialog(
        onDismissRequest = { if (!options.launching) onDismiss() },
        title = { Text("Launch instance") },
        text = {
            when {
                options.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Loading images, key pairs, and networks…", modifier = Modifier.padding(start = 16.dp))
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
                        choices = options.keyPairs.map { it.name to it.name } +
                            ("" to "No key pair") + (FROM_SAVED_KEYS to "Use one of your SSH keys…"),
                        supporting = when {
                            options.keyBusy -> "Looking for your key in AWS…"
                            keyName != null && keyName == options.keyPick?.name -> options.keyPick?.note
                            keyName == null -> "You won't be able to SSH in"
                            else -> null
                        },
                        onPick = {
                            if (it != FROM_SAVED_KEYS) keyChoice = it else pickingKey = true
                        },
                    )
                    options.keyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    if (subnet == null) {
                        Text(
                            "No subnets in this region. Create a VPC (or a default VPC) in the AWS console first.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Picker(
                            label = "Subnet",
                            selected = subnet.label,
                            choices = options.subnets.map { it.id to it.label },
                            supporting = "${subnet.id} · ${subnet.vpcId}",
                            onPick = { subnetId = it },
                        )
                    }

                    if (group != null) {
                        Picker(
                            label = "Security group",
                            selected = group.name,
                            choices = groups.map {
                                it.id to "${it.name} · ${if (it.allowsSsh) "SSH open" else "no SSH"}"
                            },
                            supporting = if (group.allowsSsh) {
                                "${group.id} · allows SSH"
                            } else {
                                "${group.id} · no inbound SSH rule, so SSH can't connect"
                            },
                            onPick = { groupId = it },
                        )
                    }

                    Text(
                        "Gets a public IP and the image's default disk.",
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
                    onClick = {
                        if (image != null && subnet != null) onLaunch(name, image, size, keyName, subnet.id, group?.id)
                    },
                    enabled = ready && !options.keyBusy && image != null && subnet != null,
                ) { Text("Launch") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !options.launching) { Text("Cancel") }
        },
    )

    options.keyNamePrompt?.let { KeyNameDialog(it, options.keyBusy, onImportKeyAs, onCancelKeyImport) }

    if (pickingKey) {
        SavedKeyPickerDialog(
            title = "Key for the instance",
            note = "Only its public half goes to AWS.",
            onPick = { key ->
                pickingKey = false
                publicKeyLine(key)?.let { onSavedKey(it, key.label) }
            },
            onManageKeys = {
                pickingKey = false
                onManageKeys()
            },
            onDismiss = { pickingKey = false },
        )
    }
}

/** Asks what to call the saved key in AWS, since the name Booter wanted holds another key. */
@Composable
private fun KeyNameDialog(
    prompt: KeyNamePrompt,
    busy: Boolean,
    onImport: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(prompt.suggested) { mutableStateOf(prompt.suggested) }
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Name the key in AWS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${prompt.error} Pick a name for your key \"${prompt.label}\".")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Key pair name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { onImport(name) }, enabled = name.isNotBlank()) { Text("Add to AWS") }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
    )
}

/** The key pair picker's entry that opens the saved keys instead of naming a key pair. */
private const val FROM_SAVED_KEYS = "\u0000saved"

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
