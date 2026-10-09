package com.rodolfo.booter.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rodolfo.booter.aws.Ec2Instance
import com.sshborg.Ec2Launch
import com.sshborg.SshBorgApp
import com.sshborg.data.db.HostEntity
import com.sshborg.service.SessionManager
import com.sshborg.service.SshForegroundService
import com.sshborg.ui.extrabar.ExtraBarEditorScreen
import com.sshborg.ui.extrabar.ExtraBarEditorViewModel
import com.sshborg.ui.extrabar.ExtraBarsScreen
import com.sshborg.ui.keys.KeysScreen
import com.sshborg.ui.terminal.TerminalScreen
import kotlinx.coroutines.launch

private object Routes {
    const val INSTANCES = "instances"
    const val TERMINAL = "terminal/{sessionId}"
    const val KEYS = "keys"
    const val KEY_BARS = "keybars"
    const val KEY_BAR_EDITOR = "keybars/{barId}"

    fun terminal(sessionId: String) = "terminal/$sessionId"
    fun keyBarEditor(barId: String) = "keybars/$barId"
}

/** What the instance list can open: an instance's terminal, the SSH keys, the key bars. */
class SshActions(
    val openInstance: (Ec2Instance) -> Unit,
    val openKeys: () -> Unit,
    val openKeyBars: () -> Unit,
)

/**
 * The unlocked app: the instance list ([instances]) and the SSH screens it leads to. Tapping
 * an instance opens its terminal: the open session if there is one, else a new one to the host
 * saved for that instance, else [ConnectDialog] first to save that host.
 */
@Composable
fun SshNavigation(instances: @Composable (SshActions) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SshBorgApp
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var connectTarget by remember { mutableStateOf<Ec2Launch?>(null) }

    fun openTerminal(host: HostEntity) {
        val open = app.sessionManager.sessions.value.lastOrNull {
            it.hostId == host.id && it.type == SessionManager.SessionType.Shell &&
                (it.status == SessionManager.Status.Connected || it.status == SessionManager.Status.Connecting)
        }
        val id = open?.id ?: app.sessionManager.create(host.id, host.label, SessionManager.SessionType.Shell).also {
            SshForegroundService.start(context)
        }
        nav.navigate(Routes.terminal(id))
    }

    fun openInstance(instance: Ec2Instance) {
        val ip = instance.publicIp
        when {
            instance.state != "running" -> toast(context, "${instance.displayName} isn't running")
            ip == null -> toast(context, "${instance.displayName} has no public IP")
            else -> scope.launch {
                when (val target = Ec2Launch(instance.id, instance.displayName, ip).resolve(app.db.hostDao())) {
                    is Ec2Launch.Target.Saved -> openTerminal(target.host)
                    is Ec2Launch.Target.New -> connectTarget = target.launch
                }
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.INSTANCES) {
        composable(Routes.INSTANCES) {
            instances(
                SshActions(
                    openInstance = ::openInstance,
                    openKeys = { nav.navigate(Routes.KEYS) },
                    openKeyBars = { nav.navigate(Routes.KEY_BARS) },
                ),
            )
        }

        composable(Routes.TERMINAL, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            val sessions by app.sessionManager.sessions.collectAsState()
            TerminalScreen(
                sessionId = sessionId,
                sessions = sessions,
                onBack = { nav.popBackStack() },
                onSwitchSession = { newId -> switchSession(nav, app, sessionId, newId) },
            )
        }

        composable(Routes.KEYS) {
            KeysScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.KEY_BARS) {
            ExtraBarsScreen(
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate(Routes.keyBarEditor(id)) },
            )
        }

        composable(Routes.KEY_BAR_EDITOR, arguments = listOf(navArgument("barId") { type = NavType.StringType })) { entry ->
            ExtraBarEditorScreen(
                barId = entry.arguments?.getString("barId") ?: ExtraBarEditorViewModel.NEW_ID,
                onBack = { nav.popBackStack() },
            )
        }
    }

    // Only over the list: it waits there while the user adds a key under SSH keys.
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    connectTarget?.takeIf { route == Routes.INSTANCES }?.let { launch ->
        ConnectDialog(
            launch = launch,
            onConnect = { username, key ->
                connectTarget = null
                scope.launch {
                    val host = HostEntity(
                        label = launch.name,
                        hostname = launch.host,
                        username = username,
                        keyId = key.id,
                        ec2InstanceId = launch.instanceId,
                    )
                    openTerminal(host.copy(id = app.db.hostDao().upsert(host)))
                }
            },
            onManageKeys = { nav.navigate(Routes.KEYS) },
            onDismiss = { connectTarget = null },
        )
    }
}

/** Moves to another session's tab, replacing the current one, as SSHBorg does. */
private fun switchSession(nav: NavHostController, app: SshBorgApp, fromId: String, toId: String) {
    // Keeps the soft keyboard up across the switch: the outgoing screen's onDispose
    // checks this flag and skips hiding the IME (see TerminalScreen).
    app.sessionManager.switchingTab = true
    nav.navigate(Routes.terminal(toId)) {
        popUpTo(Routes.terminal(fromId)) { inclusive = true }
    }
}

private fun toast(context: Context, message: String) =
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
