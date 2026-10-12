package com.rodolfo.booter.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rodolfo.booter.aws.Ec2Instance
import com.rodolfo.booter.data.SshSettingsStore
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
    const val INSTANCE = "instance/{instanceId}"
    const val SSH_DEFAULTS = "ssh-defaults"

    fun terminal(sessionId: String) = "terminal/$sessionId"
    fun instance(instanceId: String) = "instance/$instanceId"
    fun keyBarEditor(barId: String) = "keybars/$barId"
}

/** What the instance list can open: an instance's terminal or SSH settings, the SSH keys and defaults, the key bars. */
class SshActions(
    val openInstance: (Ec2Instance) -> Unit,
    val configureInstance: (Ec2Instance) -> Unit,
    val openKeys: () -> Unit,
    val openSshDefaults: () -> Unit,
    val openKeyBars: () -> Unit,
)

/**
 * The unlocked app: the instance list ([instances], showing [instanceList]) and the SSH screens
 * it leads to. Tapping an instance opens its terminal, the open session if there is one, with the
 * username and key from [SshSettingsStore]; until both are set it opens [InstanceSshScreen].
 */
@Composable
fun SshNavigation(instanceList: List<Ec2Instance>, instances: @Composable (SshActions) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SshBorgApp
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val store = remember { SshSettingsStore(context) }

    fun openTerminal(host: HostEntity) {
        val open = app.sessionManager.sessions.value.lastOrNull {
            it.hostId == host.id && it.type == SessionManager.SessionType.Shell &&
                (it.status == SessionManager.Status.Connected || it.status == SessionManager.Status.Connecting)
        }
        val id = open?.id ?: app.sessionManager.create(host.id, host.label, SessionManager.SessionType.Shell).also {
            SshForegroundService.start(context)
        }
        // Straight over the list, so leaving the terminal never lands back on the settings.
        nav.navigate(Routes.terminal(id)) { popUpTo(Routes.INSTANCES) }
    }

    fun configureInstance(instance: Ec2Instance) =
        nav.navigate(Routes.instance(instance.id)) { launchSingleTop = true }

    fun openInstance(instance: Ec2Instance) {
        val ip = instance.publicIp
        when {
            instance.state != "running" -> toast(context, "${instance.displayName} isn't running")
            ip == null -> toast(context, "${instance.displayName} has no public IP")
            else -> scope.launch {
                val settings = store.settings.value
                val username = settings.username(instance.id)
                val key = settings.keyId(instance.id)?.let { app.db.sshKeyDao().getById(it) }
                if (key == null) {
                    toast(context, "Pick a key for ${instance.displayName} first")
                    configureInstance(instance)
                    return@launch
                }
                val dao = app.db.hostDao()
                val host = when (val target = Ec2Launch(instance.id, instance.displayName, ip).resolve(dao)) {
                    // The saved host keeps its pinned host key; only who logs in, and how, follows the settings.
                    is Ec2Launch.Target.Saved -> target.host.let { saved ->
                        if (saved.username == username && saved.keyId == key.id) saved
                        else saved.copy(username = username, keyId = key.id).also { dao.upsert(it) }
                    }
                    is Ec2Launch.Target.New -> HostEntity(
                        label = instance.displayName,
                        hostname = ip,
                        username = username,
                        keyId = key.id,
                        ec2InstanceId = instance.id,
                    ).let { it.copy(id = dao.upsert(it)) }
                }
                openTerminal(host)
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.INSTANCES) {
        composable(Routes.INSTANCES) {
            instances(
                SshActions(
                    openInstance = ::openInstance,
                    configureInstance = ::configureInstance,
                    openKeys = { nav.navigate(Routes.KEYS) },
                    openSshDefaults = { nav.navigate(Routes.SSH_DEFAULTS) },
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

        composable(Routes.INSTANCE, arguments = listOf(navArgument("instanceId") { type = NavType.StringType })) { entry ->
            val instanceId = entry.arguments?.getString("instanceId") ?: return@composable
            InstanceSshScreen(
                instanceId = instanceId,
                instance = instanceList.find { it.id == instanceId },
                store = store,
                onBack = { nav.popBackStack() },
                onConnect = ::openInstance,
                onOpenDefaults = { nav.navigate(Routes.SSH_DEFAULTS) },
                onManageKeys = { nav.navigate(Routes.KEYS) },
            )
        }

        composable(Routes.SSH_DEFAULTS) {
            SshDefaultsScreen(
                store = store,
                onBack = { nav.popBackStack() },
                onManageKeys = { nav.navigate(Routes.KEYS) },
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
