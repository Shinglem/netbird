package dev.shinglem.netbird.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import dev.shinglem.netbird.ui.api.DaemonConfig
import dev.shinglem.netbird.ui.api.Features
import dev.shinglem.netbird.ui.daemon.DaemonState
import dev.shinglem.netbird.ui.daemon.ProfileSupervisor
import dev.shinglem.netbird.ui.data.ConfigDraft
import dev.shinglem.netbird.ui.data.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val LOG_LEVELS = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")

@Composable
fun SettingsPage(supervisor: ProfileSupervisor) {
    val daemonState by supervisor.state.collectAsState()
    val status by supervisor.status.collectAsState()
    val running = daemonState is DaemonState.Running

    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<DaemonConfig?>(null) }
    var features by remember { mutableStateOf<Features?>(null) }
    var logLevel by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<ConfigDraft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        val loaded = supervisor.config()
        config = loaded
        draft = ConfigDraft.of(loaded)
        features = runCatching { supervisor.features() }.getOrNull()
        logLevel = runCatching { supervisor.logLevel() }.getOrDefault("")
    }

    LaunchedEffect(running, (status as? UiState.Ready)?.status?.status) {
        if (!running) {
            config = null
            draft = null
            return@LaunchedEffect
        }
        busy = true
        error = null
        try {
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            busy = false
        }
    }

    val lockedByFeature = features?.disableUpdateSettings == true

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(Strings.NAV_SETTINGS, style = MaterialTheme.typography.titleMedium)
        }

        ErrorBanner(error)
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (lockedByFeature) {
            ErrorBanner(Strings.SETTINGS_LOCKED)
        }

        val loadedConfig = config
        val currentDraft = draft
        if (!running) {
            Text(Strings.SETTINGS_NEED_RUNNING, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (loadedConfig == null || currentDraft == null) {
            Text(if (busy) Strings.SETTINGS_LOADING else Strings.DASH, style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        val managed = loadedConfig.mDMManagedFields
        if (managed.isNotEmpty()) {
            Text("${Strings.SETTINGS_MDM_HINT} ${managed.joinToString("，")}", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(title = Strings.SETTINGS_SECTION_TUNNEL) {
            OutlinedTextField(
                value = currentDraft.interfaceName,
                onValueChange = { draft = currentDraft.copy(interfaceName = it) },
                label = { Text(Strings.LABEL_INTERFACE_NAME) },
                singleLine = true,
                enabled = !lockedByFeature && !loadedConfig.managed("interfaceName"),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = currentDraft.wireguardPort,
                onValueChange = { draft = currentDraft.copy(wireguardPort = it) },
                label = { Text(Strings.LABEL_WG_PORT_SETTING) },
                singleLine = true,
                enabled = !lockedByFeature && !loadedConfig.managed("wireguardPort"),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = currentDraft.mtu,
                onValueChange = { draft = currentDraft.copy(mtu = it) },
                label = { Text(Strings.LABEL_MTU) },
                singleLine = true,
                enabled = !lockedByFeature && !loadedConfig.managed("mtu"),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(Strings.SETTINGS_RESTART_HINT, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(title = Strings.SETTINGS_SECTION_ROUTES) {
            SwitchRow(
                label = Strings.SWITCH_DISABLE_DNS,
                checked = currentDraft.disableDns,
                enabled = !lockedByFeature && !loadedConfig.managed("disableDns"),
            ) { draft = currentDraft.copy(disableDns = it) }
            SwitchRow(
                label = Strings.SWITCH_DISABLE_CLIENT_ROUTES,
                checked = currentDraft.disableClientRoutes,
                enabled = !lockedByFeature && !loadedConfig.managed("disableClientRoutes"),
            ) { draft = currentDraft.copy(disableClientRoutes = it) }
            SwitchRow(
                label = Strings.SWITCH_DISABLE_SERVER_ROUTES,
                checked = currentDraft.disableServerRoutes,
                enabled = !lockedByFeature && !loadedConfig.managed("disableServerRoutes"),
            ) { draft = currentDraft.copy(disableServerRoutes = it) }
            SwitchRow(
                label = Strings.SWITCH_BLOCK_LAN,
                checked = currentDraft.blockLanAccess,
                enabled = !lockedByFeature && !loadedConfig.managed("blockLanAccess"),
            ) { draft = currentDraft.copy(blockLanAccess = it) }
        }

        SectionCard(title = Strings.SETTINGS_SECTION_BEHAVIOR) {
            SwitchRow(
                label = Strings.SWITCH_AUTO_CONNECT,
                checked = currentDraft.disableAutoConnect,
                enabled = !lockedByFeature && !loadedConfig.managed("disableAutoConnect"),
                invert = true,
            ) { draft = currentDraft.copy(disableAutoConnect = it) }
            SwitchRow(
                label = Strings.SWITCH_LAZY_CONNECTION,
                checked = currentDraft.lazyConnectionEnabled,
                enabled = !lockedByFeature && !loadedConfig.managed("lazyConnection"),
            ) { draft = currentDraft.copy(lazyConnectionEnabled = it) }
            SwitchRow(
                label = Strings.SWITCH_BLOCK_INBOUND,
                checked = currentDraft.blockInbound,
                enabled = !lockedByFeature && !loadedConfig.managed("blockInbound"),
            ) { draft = currentDraft.copy(blockInbound = it) }
            SwitchRow(
                label = Strings.SWITCH_NETWORK_MONITOR,
                checked = currentDraft.networkMonitor,
                enabled = !lockedByFeature && !loadedConfig.managed("networkMonitor"),
            ) { draft = currentDraft.copy(networkMonitor = it) }
            SwitchRow(
                label = Strings.SWITCH_DISABLE_NOTIFICATIONS,
                checked = currentDraft.disableNotifications,
                enabled = !lockedByFeature && !loadedConfig.managed("disableNotifications"),
            ) { draft = currentDraft.copy(disableNotifications = it) }
            SwitchRow(
                label = Strings.SWITCH_DISABLE_IPV6,
                checked = currentDraft.disableIpv6,
                enabled = !lockedByFeature && !loadedConfig.managed("disableIpv6"),
            ) { draft = currentDraft.copy(disableIpv6 = it) }
            SwitchRow(
                label = Strings.SWITCH_ALLOW_SSH_SERVER,
                checked = currentDraft.serverSshAllowed,
                enabled = !lockedByFeature && !loadedConfig.managed("allowServerSSH"),
            ) { draft = currentDraft.copy(serverSshAllowed = it) }
            Text(Strings.SETTINGS_SSH_SERVER_HINT, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(title = Strings.SETTINGS_SECTION_DEBUG) {
            Text(Strings.LABEL_LOG_LEVEL, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LOG_LEVELS.forEach { level ->
                    FilterChip(
                        selected = logLevel == level,
                        onClick = {
                            scope.launch {
                                error = null
                                notice = null
                                try {
                                    supervisor.setLogLevel(level)
                                    logLevel = level
                                    notice = Strings.SETTINGS_SAVED
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message
                                }
                            }
                        },
                        label = { Text(level) },
                    )
                }
            }
            Text(Strings.SSH_UNAVAILABLE_TITLE, style = MaterialTheme.typography.labelLarge)
            Text(Strings.SSH_UNAVAILABLE_BODY, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(title = Strings.SETTINGS_SECTION_INFO) {
            InfoRow(Strings.LABEL_MANAGEMENT_URL, loadedConfig.managementUrl, mono = true)
            InfoRow(Strings.LABEL_CONFIG_FILE, loadedConfig.configFile, mono = true)
            InfoRow(Strings.LABEL_LOG_FILE, loadedConfig.logFile, mono = true)
            InfoRow(Strings.LABEL_INTERFACE_NAME, loadedConfig.interfaceName, mono = true)
            InfoRow(Strings.LABEL_MTU, loadedConfig.mtu.toString())
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        notice = null
                        try {
                            val fields = currentDraft.diff(loadedConfig)
                            if (fields.isEmpty()) {
                                notice = Strings.SETTINGS_UNCHANGED
                            } else {
                                supervisor.setConfig(fields)
                                reload()
                                notice = Strings.SETTINGS_SAVED
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && !lockedByFeature,
            ) { Text(Strings.SETTINGS_SAVE) }
        }
    }
}

private fun DaemonConfig.managed(key: String): Boolean =
    mDMManagedFields.any { it.equals(key, ignoreCase = true) }

