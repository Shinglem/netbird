package dev.shinglem.netbird.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.shinglem.netbird.ui.api.LoginResponse
import dev.shinglem.netbird.ui.api.NsGroupState
import dev.shinglem.netbird.ui.api.RelayState
import dev.shinglem.netbird.ui.api.SshServerState
import dev.shinglem.netbird.ui.api.StatusResponse
import dev.shinglem.netbird.ui.app.openUrl
import dev.shinglem.netbird.ui.daemon.DaemonState
import dev.shinglem.netbird.ui.daemon.ProfileSupervisor
import dev.shinglem.netbird.ui.data.UiState
import dev.shinglem.netbird.ui.profile.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun ConnectPage(profile: Profile, supervisor: ProfileSupervisor) {
    val daemonState by supervisor.state.collectAsState()
    val status by supervisor.status.collectAsState()
    val ready = (status as? UiState.Ready)?.status
    val running = daemonState is DaemonState.Running
    val rawStatus = ready?.status ?: "Idle"
    // 已注册的实例断开后也是 Idle，但 localPeer 里有身份；新起的 daemon 两者都空。
    val registered = !ready?.fullStatus?.localPeerState?.pubKey.isNullOrBlank() ||
        !ready?.fullStatus?.localPeerState?.ip.isNullOrBlank()
    val loginRequired = running && (needsLogin(rawStatus) || (rawStatus == "Idle" && !registered))

    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var sso by remember { mutableStateOf<LoginResponse?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(block: suspend () -> Unit) {
        job = scope.launch {
            busy = true
            actionError = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = e.message ?: Strings.STATUS_UNKNOWN
            } finally {
                busy = false
                sso = null
                job = null
            }
        }
    }

    // 登录期间盯住 daemon 自己报出来的管理端错误：无效的 setup key 会让它指数退避重试，
    // Login RPC 迟迟不返回，但状态快照里的 managementState.error 已经说出原因了。
    //
    // 只认「setup key is invalid」这种确定结论。未注册实例的常态提示是
    // `no peer auth method provided, please use a setup key or interactive SSO login`，
    // 里面也有 "setup key"，宽匹配会把每次登录都当场取消掉。
    LaunchedEffect(busy, ready?.fullStatus?.managementState?.error) {
        if (!busy) return@LaunchedEffect
        val message = ready?.fullStatus?.managementState?.error.orEmpty()
        if (isInvalidSetupKeyError(message)) {
            job?.cancel()
            actionError = message
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionHero(
            ready = ready,
            daemonState = daemonState,
            busy = busy,
            blockedByLogin = loginRequired,
            onConnect = { run { supervisor.connect() } },
            onDisconnect = { run { supervisor.disconnect() } },
            onLogout = { run { supervisor.logout() } },
        )

        if (!running) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    text = Strings.DAEMON_STOPPED_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (loginRequired || busy) {
            LoginCard(
                busy = busy,
                sso = sso,
                onCancel = { job?.cancel() },
                onSso = {
                    run {
                        val response = supervisor.login(setupKey = "")
                        if (!response.needsSSOLogin) {
                            supervisor.connect()
                            return@run
                        }
                        sso = response
                        val target = response.verificationURIComplete.ifBlank { response.verificationURI }
                        openUrl(target)
                        supervisor.waitSSOLogin(response.userCode)
                        supervisor.connect()
                    }
                },
                onSetupKey = { key, managementUrl ->
                    run {
                        supervisor.login(setupKey = key, managementUrl = managementUrl)
                        supervisor.connect()
                    }
                },
            )
        }

        ErrorBanner(actionError)
        if (actionError?.contains("SSH server", ignoreCase = true) == true) {
            Text(Strings.LOGOUT_SSH_BLOCKED_HINT, style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = {
                    run {
                        supervisor.setConfig(buildJsonObject { put("serverSSHAllowed", false) })
                        supervisor.restart()
                        supervisor.logout()
                    }
                },
                enabled = running && !busy,
            ) { Text(Strings.LOGOUT_DISABLE_SSH_AND_RETRY) }
        }
        if (ready != null) {
            // 「no peer auth method provided」只是"还没认证"的常态提示，登录卡已经说清了；
            // 当成红色错误横幅贴出来会让人以为哪里坏了。
            ready.fullStatus?.managementState?.error
                ?.takeIf { it.isNotBlank() && !isAuthNotProvided(it) }
                ?.let { ErrorBanner(it) }
            ready.fullStatus?.signalState?.error?.takeIf { it.isNotBlank() }?.let {
                ErrorBanner(it)
            }
        }

        when (val current = status) {
            is UiState.Ready -> StatusDetails(current.status, profile)
            is UiState.Unavailable -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Strings.STATUS_UNAVAILABLE, style = MaterialTheme.typography.titleMedium)
                    Text(current.reason, style = MaterialTheme.typography.bodySmall)
                }
            }
            null -> if (running) Text(Strings.LOADING, style = MaterialTheme.typography.bodySmall)
        }

        (daemonState as? DaemonState.Failed)?.let { failure ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(failure.title, style = MaterialTheme.typography.titleMedium)
                    failure.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionHero(
    ready: StatusResponse?,
    daemonState: DaemonState,
    busy: Boolean,
    blockedByLogin: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onLogout: () -> Unit,
) {
    val running = daemonState is DaemonState.Running
    val raw = ready?.status ?: "Idle"
    val connected = raw == "Connected"

    SectionCard(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(statusText(raw), style = MaterialTheme.typography.headlineMedium)
            StatusPill(daemonStateText(daemonState), daemonTone(daemonState))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Text(
            text = when {
                !running -> Strings.DAEMON_STOPPED
                connected -> Strings.CONNECTED
                blockedByLogin -> Strings.NEEDS_LOGIN_HINT
                raw == "Connecting" -> Strings.CONNECTING_HINT
                else -> Strings.NOT_CONNECTED_HINT
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        ready?.sessionExpiresAt?.let {
            Text(
                text = Strings.SESSION_EXPIRING + " " + formatTimestamp(it),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect, enabled = running && !busy && !connected && !blockedByLogin) {
                Text(Strings.CONNECT)
            }
            OutlinedButton(onClick = onDisconnect, enabled = running && !busy && raw != "Idle") {
                Text(Strings.DISCONNECT)
            }
            TextButton(onClick = onLogout, enabled = running && !busy) { Text(Strings.LOGOUT) }
        }
    }
}

@Composable
private fun LoginCard(
    busy: Boolean,
    sso: LoginResponse?,
    onCancel: () -> Unit,
    onSso: () -> Unit,
    onSetupKey: (String, String) -> Unit,
) {
    var setupKey by remember { mutableStateOf("") }
    var managementUrl by remember { mutableStateOf("") }

    SectionCard(title = Strings.NEEDS_LOGIN_HINT) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSso, enabled = !busy) { Text(Strings.LOGIN_SSO) }
        }
        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(Strings.LOGIN_BUSY, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel) { Text(Strings.CANCEL) }
            }
            Text(Strings.LOGIN_SLOW_HINT, style = MaterialTheme.typography.bodySmall)
        }
        sso?.let { prompt ->
            Text(Strings.LOGIN_WAITING, style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = Strings.LOGIN_CODE_LABEL + ": " + prompt.userCode,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(Strings.LOGIN_URL_LABEL, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = prompt.verificationURIComplete.ifBlank { prompt.verificationURI },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        OutlinedTextField(
            value = setupKey,
            onValueChange = { setupKey = it },
            label = { Text(Strings.SETUP_KEY_LABEL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = managementUrl,
            onValueChange = { managementUrl = it },
            label = { Text(Strings.MANAGEMENT_OVERRIDE_LABEL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSetupKey(setupKey.trim(), managementUrl.trim()) },
            enabled = !busy && setupKey.isNotBlank(),
        ) { Text(Strings.LOGIN_SETUP_KEY) }
    }
}

@Composable
private fun StatusDetails(status: StatusResponse, profile: Profile) {
    val full = status.fullStatus ?: return
    val local = full.localPeerState

    SectionCard(title = Strings.SECTION_LOCAL_PEER) {
        InfoRow(Strings.LABEL_DAEMON_VERSION, status.daemonVersion)
        InfoRow(Strings.LABEL_LOCAL_IP, local?.ip.orEmpty())
        InfoRow(Strings.LABEL_IPV6, local?.ipv6.orEmpty())
        InfoRow(Strings.LABEL_FQDN, local?.fqdn.orEmpty())
        InfoRow(Strings.LABEL_INTERFACE, profile.iface)
        InfoRow(Strings.LABEL_WG_PORT, local?.wgPort?.takeIf { it > 0 }?.toString() ?: profile.wgPort.toString())
        InfoRow(Strings.LABEL_INTERFACE_KIND, if (local?.kernelInterface == true) Strings.YES else Strings.NO)
        InfoRow(Strings.LABEL_ROSENPASS, if (local?.rosenpassEnabled == true) Strings.ENABLED else Strings.DISABLED)
    }

    SectionCard(title = Strings.SECTION_CONNECTIONS) {
        InfoRow(Strings.LABEL_MANAGEMENT, linkText(full.managementState?.url, full.managementState?.connected))
        InfoRow(Strings.LABEL_SIGNAL, linkText(full.signalState?.url, full.signalState?.connected))
        InfoRow(Strings.LABEL_RELAYS, relayText(full.relays))
        InfoRow(Strings.LABEL_DNS_GROUPS, dnsText(full.dnsServers))
        InfoRow(Strings.LABEL_SSH, sshText(full.sshServerState))
        InfoRow(Strings.LABEL_PEERS, full.peers.size.toString())
    }
}

private fun dnsText(groups: List<NsGroupState>): String {
    if (groups.isEmpty()) return Strings.DASH
    return groups.joinToString("；") { group ->
        val domains = group.domains.ifEmpty { listOf("*") }.joinToString("，")
        val servers = group.servers.joinToString("，").ifBlank { Strings.DASH }
        "$domains → $servers" + if (group.error.isNotBlank()) "（${group.error}）" else ""
    }
}

private fun sshText(state: SshServerState?): String = when {
    state == null -> Strings.DASH
    !state.enabled -> Strings.DISABLED
    state.sessions.isEmpty() -> Strings.ENABLED
    else -> Strings.ENABLED + " · " + state.sessions.size + " 个会话"
}

private fun relayText(relays: List<RelayState>): String {
    if (relays.isEmpty()) return Strings.DASH
    return relays.joinToString("，") { relay ->
        val state = if (relay.available) Strings.CONNECTED else Strings.DISCONNECTED
        listOf(relay.uri, state, relay.transport).filter { it.isNotBlank() }.joinToString(" ")
    }
}

fun linkText(url: String?, connected: Boolean?): String = when {
    url.isNullOrBlank() -> Strings.DASH
    connected == true -> "$url（${Strings.CONNECTED}）"
    else -> "$url（${Strings.DISCONNECTED}）"
}

fun daemonStateText(state: DaemonState): String = when (state) {
    is DaemonState.Stopped -> Strings.DAEMON_STOPPED
    is DaemonState.Starting -> Strings.DAEMON_STARTING + " · " + state.step
    is DaemonState.Running -> Strings.DAEMON_RUNNING
    is DaemonState.Stopping -> Strings.DAEMON_STOPPING
    is DaemonState.Failed -> Strings.DAEMON_FAILED
}

/**
 * 管理端明确拒绝了这个 setup key。
 *
 * 只匹配确定结论（`couldn't add peer: setup key is invalid`）。未注册实例的常态提示
 * `no peer auth method provided, please use a setup key or interactive SSO login`
 * 也含 "setup key"，宽匹配会把每次登录都当场取消。
 */
internal fun isInvalidSetupKeyError(message: String): Boolean =
    message.contains("setup key is invalid", ignoreCase = true)

/** daemon 还没认证过任何身份 —— 这是状态而不是故障。 */
internal fun isAuthNotProvided(message: String): Boolean =
    message.contains("no peer auth method provided", ignoreCase = true)

private fun daemonTone(state: DaemonState): StatusTone = when (state) {
    is DaemonState.Running -> StatusTone.OK
    is DaemonState.Starting, is DaemonState.Stopping -> StatusTone.WARN
    is DaemonState.Failed -> StatusTone.BAD
    is DaemonState.Stopped -> StatusTone.NEUTRAL
}
