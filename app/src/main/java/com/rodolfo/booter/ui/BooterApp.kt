package com.rodolfo.booter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
fun BooterApp(
    vm: MainViewModel,
    unlock: suspend () -> Unit,
    saveSettings: suspend (keyId: String, secret: String, region: String) -> Boolean,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // While unlocked and on screen, keep the list fresh and watch for pending resizes.
    if (state.unlocked) {
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.poll()
                    delay(vm.nextPollDelayMs())
                }
            }
        }
    }

    BackHandler(enabled = showSettings && state.hasCredentials) { showSettings = false }

    when {
        !state.hasCredentials || (showSettings && state.unlocked) -> SettingsScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onBack = if (state.hasCredentials) ({ showSettings = false }) else null,
            onSave = saveSettings,
            onSaved = { showSettings = false },
            onClear = vm::clearCredentials,
        )

        !state.unlocked -> LockedScreen(
            snackbarHostState = snackbarHostState,
            unlock = unlock,
        )

        else -> InstancesScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onRefresh = vm::refresh,
            onOpenSettings = { showSettings = true },
            onStart = vm::start,
            onResize = vm::requestResize,
            onCancelResize = vm::cancelResize,
            onDismissShutdownNotice = vm::dismissShutdownNotice,
            onOpenLaunch = vm::openLaunch,
            onLaunch = vm::launch,
            onRetryLaunchOptions = vm::retryLaunchOptions,
            onDismissLaunch = vm::dismissLaunch,
        )
    }
}
