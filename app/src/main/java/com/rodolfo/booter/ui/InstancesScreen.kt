@file:OptIn(ExperimentalMaterial3Api::class)

package com.rodolfo.booter.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rodolfo.booter.aws.Ec2Instance
import com.rodolfo.booter.aws.INSTANCE_SIZES

@Composable
fun InstancesScreen(
    state: UiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: (Ec2Instance, String) -> Unit,
    onResize: (Ec2Instance, String) -> Unit,
    onCancelResize: (String) -> Unit,
    onDismissShutdownNotice: () -> Unit,
) {
    var startTarget by remember { mutableStateOf<Ec2Instance?>(null) }
    var resizeTarget by remember { mutableStateOf<Ec2Instance?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun copyPublicIp(instance: Ec2Instance) {
        val ip = instance.publicIp
        val message = if (ip == null) {
            "${instance.displayName} has no public IP"
        } else {
            clipboard.setText(AnnotatedString(ip))
            "IP address $ip copied to clipboard"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Booter")
                        Text(
                            state.region,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Tap to copy public IP · swipe left to start · swipe right on a running instance to resize",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.loaded && state.instances.isEmpty()) {
                    item {
                        Text(
                            "No instances in ${state.region}.",
                            modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                items(state.instances, key = { it.id }) { instance ->
                    InstanceRow(
                        instance = instance,
                        pendingSize = state.pendingResizes[instance.id],
                        busy = instance.id in state.busyIds,
                        onStartRequest = { startTarget = instance },
                        onResizeRequest = { resizeTarget = instance },
                        onCancelResize = { onCancelResize(instance.id) },
                        onClick = { copyPublicIp(instance) },
                    )
                }
            }
        }
    }

    startTarget?.let { instance ->
        SizePickerDialog(
            title = "Start ${instance.displayName}",
            message = "Pick the size to boot with.",
            sizes = INSTANCE_SIZES.keys.toList(),
            current = instance.type,
            onPick = { size ->
                startTarget = null
                onStart(instance, size)
            },
            onDismiss = { startTarget = null },
        )
    }

    resizeTarget?.let { instance ->
        SizePickerDialog(
            title = "Resize ${instance.displayName}",
            message = "Currently ${instance.type}. Switch to:",
            sizes = INSTANCE_SIZES.keys.filter { it != instance.type },
            current = instance.type,
            onPick = { size ->
                resizeTarget = null
                onResize(instance, size)
            },
            onDismiss = { resizeTarget = null },
        )
    }

    state.shutdownNotice?.let { ShutdownNoticeDialog(it, onDismissShutdownNotice) }
}

@Composable
private fun InstanceRow(
    instance: Ec2Instance,
    pendingSize: String?,
    busy: Boolean,
    onStartRequest: () -> Unit,
    onResizeRequest: () -> Unit,
    onCancelResize: () -> Unit,
    onClick: () -> Unit,
) {
    val canStart = instance.state == "stopped" && !busy
    val canResize = instance.state == "running" && !busy && pendingSize == null

    // The swipe state is remembered once, so read the latest values through these.
    val latestCanStart by rememberUpdatedState(canStart)
    val latestCanResize by rememberUpdatedState(canResize)
    val latestOnStart by rememberUpdatedState(onStartRequest)
    val latestOnResize by rememberUpdatedState(onResizeRequest)

    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.EndToStart -> if (latestCanStart) latestOnStart()
                SwipeToDismissBoxValue.StartToEnd -> if (latestCanResize) latestOnResize()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // always snap back; the dialog takes over from here
        },
    )

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = canResize,
        enableDismissFromEndToStart = canStart,
        backgroundContent = { SwipeBackground(swipeState.dismissDirection) },
    ) {
        InstanceCard(instance, pendingSize, busy, canStart, onStartRequest, onCancelResize, onClick)
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val colors = MaterialTheme.colorScheme
    val (color, alignment) = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> colors.primaryContainer to Alignment.CenterEnd
        SwipeToDismissBoxValue.StartToEnd -> colors.tertiaryContainer to Alignment.CenterStart
        SwipeToDismissBoxValue.Settled -> return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CardDefaults.shape)
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                Text("Start", color = colors.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = colors.onPrimaryContainer)
            } else {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = colors.onTertiaryContainer)
                Spacer(Modifier.width(8.dp))
                Text("Resize", color = colors.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun InstanceCard(
    instance: Ec2Instance,
    pendingSize: String?,
    busy: Boolean,
    canStart: Boolean,
    onStartRequest: () -> Unit,
    onCancelResize: () -> Unit,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateDot(instance.state)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    instance.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${instance.type} · ${instance.state}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    listOfNotNull(instance.id, instance.publicIp).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pendingSize != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "→ $pendingSize after shutdown",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        TextButton(onClick = onCancelResize) { Text("Cancel") }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                FilledTonalButton(onClick = onStartRequest, enabled = canStart) { Text("Start") }
            }
        }
    }
}

@Composable
private fun StateDot(state: String) {
    val color = when (state) {
        "running" -> Color(0xFF2E7D32)
        "stopped" -> MaterialTheme.colorScheme.outline
        else -> Color(0xFFF9A825)
    }
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun SizePickerDialog(
    title: String,
    message: String,
    sizes: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                sizes.forEach { size ->
                    OutlinedCard(onClick = { onPick(size) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(size, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    INSTANCE_SIZES[size].orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (size == current) {
                                Text(
                                    "current",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ShutdownNoticeDialog(notice: ShutdownNotice, onDismiss: () -> Unit) {
    val howTo = when (notice.terminatesOnShutdown) {
        false -> "You can shut it down now — run `sudo shutdown -h now` on the instance, " +
            "or stop it from the AWS console."
        true -> "Heads up: this instance is set to TERMINATE when its OS shuts down. " +
            "Don't run `shutdown` on it — stop it from the AWS console instead."
        null -> "You can shut it down now from the AWS console. (Booter couldn't confirm the " +
            "instance's shutdown behavior, so avoid `sudo shutdown` unless you know it's set to stop.)"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ready to switch to ${notice.size}") },
        text = {
            Text(
                "$howTo\n\nKeep Booter open: as soon as ${notice.instanceName} reports stopped, " +
                    "Booter will switch it to ${notice.size} and start it back up.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
