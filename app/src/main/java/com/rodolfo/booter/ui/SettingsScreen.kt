package com.rodolfo.booter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rodolfo.booter.aws.isOpenSshPublicKey
import kotlinx.coroutines.launch

private val REGION_REGEX = Regex("^[a-z]{2}(-[a-z]+)+-\\d+$")

/** What AWS allows in a tag value. */
private val NAMESPACE_REGEX = Regex("^[\\p{L}\\p{N} _.:/=+\\-@]{0,256}$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    snackbarHostState: SnackbarHostState,
    onBack: (() -> Unit)?,
    onSave: suspend (keyId: String, secret: String, settings: AppSettings) -> Boolean,
    onSaved: () -> Unit,
    onClear: () -> Unit,
) {
    // Keys use plain remember so they never end up in the saved-instance-state bundle.
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf(state.region) }
    var namespace by rememberSaveable { mutableStateOf(state.namespace) }
    var extraKeysText by rememberSaveable { mutableStateOf(state.extraKeys.joinToString("\n")) }
    var saving by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val mustEnterKeys = !state.unlocked
    val enteringKeys = mustEnterKeys || keyId.isNotBlank() || secret.isNotBlank()
    val regionValid = REGION_REGEX.matches(region.trim())
    val namespaceValid = NAMESPACE_REGEX.matches(namespace.trim())
    val extraKeyLines = extraKeysText.lines().map { it.trim() }
    val badKeyLine = extraKeyLines.indexOfFirst { it.isNotEmpty() && !isOpenSshPublicKey(it) }
    val canSave = !saving && regionValid && namespaceValid && badKeyLine < 0 && (!enteringKeys || (keyId.isNotBlank() && secret.isNotBlank()))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AWS settings") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Your keys are encrypted on this phone with a key only your fingerprint can unlock. " +
                    "They're only used to sign requests to AWS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.maskedKeyId?.let {
                Text("Saved access key: $it", style = MaterialTheme.typography.titleSmall)
            }

            val hint = if (mustEnterKeys) "" else " (leave blank to keep)"
            OutlinedTextField(
                value = keyId,
                onValueChange = { keyId = it.trim() },
                label = { Text("Access key ID$hint") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it.trim() },
                label = { Text("Secret access key$hint") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = region,
                onValueChange = { region = it.trim().lowercase() },
                label = { Text("Region") },
                singleLine = true,
                isError = !regionValid,
                supportingText = { if (!regionValid) Text("e.g. us-east-1, ap-southeast-1") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = namespace,
                onValueChange = { namespace = it },
                label = { Text("Namespace (optional)") },
                singleLine = true,
                isError = !namespaceValid,
                supportingText = {
                    Text(
                        if (namespaceValid) {
                            "Only instances tagged with it are listed, and new ones get it. Leave blank to see all."
                        } else {
                            "Letters, numbers, spaces and _ . : / = + - @ only, up to 256"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = extraKeysText,
                onValueChange = { extraKeysText = it },
                label = { Text("Extra SSH public keys (optional)") },
                minLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                isError = badKeyLine >= 0,
                supportingText = {
                    Text(
                        if (badKeyLine >= 0) {
                            "Line ${badKeyLine + 1} isn't an OpenSSH public key"
                        } else {
                            "One per line. Added to new instances on first boot, alongside the key pair."
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        try {
                            if (onSave(
                                    keyId, secret,
                                    AppSettings(region.trim(), namespace.trim(), extraKeyLines.filter { it.isNotEmpty() }),
                                )) {
                                keyId = ""
                                secret = ""
                                onSaved()
                            }
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (enteringKeys) "Encrypt with fingerprint & save" else "Save")
            }

            if (state.hasCredentials) {
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove saved keys", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Remove saved keys?") },
            text = { Text("You'll need to enter your AWS keys again to use Booter.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}
