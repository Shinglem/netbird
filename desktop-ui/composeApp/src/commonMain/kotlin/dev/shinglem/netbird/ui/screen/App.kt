package dev.shinglem.netbird.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.shinglem.netbird.ui.daemon.DaemonState
import dev.shinglem.netbird.ui.daemon.ProfileSupervisor
import dev.shinglem.netbird.ui.data.AppEnvironment
import dev.shinglem.netbird.ui.data.Autostart
import dev.shinglem.netbird.ui.data.ProfilesController
import dev.shinglem.netbird.ui.data.UiPreferences
import dev.shinglem.netbird.ui.profile.Profile
import dev.shinglem.netbird.ui.theme.NetBirdTheme
import dev.shinglem.netbird.ui.theme.appMonoFontFamily

/** 主窗口的功能页，都是「当前实例」的内容；全局设置走顶部的「桌面」入口。 */
enum class AppTab(val label: String) {
    CONNECT(Strings.NAV_CONNECT),
    PEERS(Strings.NAV_PEERS),
    NETWORKS(Strings.NAV_NETWORKS),
    SETTINGS(Strings.NAV_SETTINGS),
    LOGS(Strings.NAV_LOGS),
}

private fun AppTab.icon(): ImageVector = when (this) {
    AppTab.CONNECT -> Icons.Outlined.Link
    AppTab.PEERS -> Icons.Outlined.Devices
    AppTab.NETWORKS -> Icons.Outlined.Public
    AppTab.SETTINGS -> Icons.Outlined.Settings
    AppTab.LOGS -> Icons.Outlined.Description
}

@Composable
fun App(
    controller: ProfilesController,
    environment: AppEnvironment,
    preferences: UiPreferences,
    autostart: Autostart,
) {
    val profiles by controller.profiles.collectAsState()
    val autostartFlags by controller.autostart.collectAsState()
    val theme by preferences.theme.collectAsState()
    val mode by preferences.mode.collectAsState()
    var selectedName by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(AppTab.CONNECT) }
    var showDesktopSettings by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }

    val selected = profiles.firstOrNull { it.name == selectedName } ?: profiles.firstOrNull()
    val supervisor = selected?.let { controller.supervisor(it.name) }

    NetBirdTheme(style = theme, mode = mode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    profiles = profiles,
                    selected = selected,
                    supervisor = supervisor,
                    supervisorOf = controller::supervisor,
                    onSelect = { selectedName = it },
                    onDesktop = { showDesktopSettings = true },
                    onStartProfile = controller::start,
                    onStopProfile = controller::stop,
                    onStartAll = { controller.startAll() },
                    onStopAll = { controller.stopAll() },
                    onCreate = { showCreate = true },
                    onDelete = { deleteTarget = it },
                )
                EnvironmentBanner(environment)

                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        AppTab.entries.forEach { entry ->
                            NavigationRailItem(
                                selected = !showDesktopSettings && tab == entry,
                                onClick = {
                                    showDesktopSettings = false
                                    tab = entry
                                },
                                icon = { Icon(entry.icon(), contentDescription = entry.label) },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 「桌面」是全局设置：不依赖实例，没有配置时也要能改主题与自启。
                        when {
                            showDesktopSettings -> DesktopSettingsPage(
                                preferences = preferences,
                                environment = environment,
                                autostartController = autostart,
                            )
                            profiles.isEmpty() -> EmptyState(controller.profilesRoot)
                            supervisor == null -> Text(
                                text = Strings.NO_INSTANCE,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(20.dp),
                            )

                            else -> Column(modifier = Modifier.fillMaxSize()) {
                                ProfileBar(
                                    profile = selected,
                                    supervisor = supervisor,
                                    autostart = autostartFlags[selected.name] ?: true,
                                    onToggleAutostart = { controller.setAutostart(selected.name, it) },
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    when (tab) {
                                        AppTab.CONNECT -> ConnectPage(selected, supervisor)
                                        AppTab.PEERS -> PeersPage(supervisor)
                                        AppTab.NETWORKS -> NetworksPage(supervisor)
                                        AppTab.SETTINGS -> SettingsPage(supervisor)
                                        AppTab.LOGS -> LogsPage(supervisor)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateProfileDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, managementUrl ->
                val result = controller.createProfile(name, managementUrl)
                if (result.isSuccess) {
                    selectedName = result.getOrNull()?.name
                    showCreate = false
                    null
                } else {
                    result.exceptionOrNull()?.message ?: Strings.CREATE_FAILED
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(Strings.CONFIRM_DELETE_TITLE) },
            text = { Text(Strings.CONFIRM_DELETE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    controller.removeProfile(target.name)
                    deleteTarget = null
                }) { Text(Strings.DELETE_PROFILE) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(Strings.CANCEL) }
            },
        )
    }
}

@Composable
private fun TopBar(
    profiles: List<Profile>,
    selected: Profile?,
    supervisor: ProfileSupervisor?,
    supervisorOf: (String) -> ProfileSupervisor?,
    onSelect: (String) -> Unit,
    onDesktop: () -> Unit,
    onStartProfile: (String) -> Unit,
    onStopProfile: (String) -> Unit,
    onStartAll: () -> Unit,
    onStopAll: () -> Unit,
    onCreate: () -> Unit,
    onDelete: (Profile) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = Strings.APP_TITLE, style = MaterialTheme.typography.headlineSmall)

        InstancePicker(
            profiles = profiles,
            selected = selected,
            supervisorOf = supervisorOf,
            onSelect = onSelect,
            onDelete = onDelete,
        )

        if (supervisor != null) {
            val name = selected?.name.orEmpty()
            StartStopButton(
                supervisor = supervisor,
                onStart = { onStartProfile(name) },
                onStop = { onStopProfile(name) },
            )
        }

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onStartAll, enabled = profiles.isNotEmpty()) { Text(Strings.START_ALL) }
        TextButton(onClick = onStopAll, enabled = profiles.isNotEmpty()) { Text(Strings.STOP_ALL) }
        Button(onClick = onCreate) { Text(Strings.NEW_PROFILE) }
        OutlinedButton(onClick = onDesktop) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(Strings.DESKTOP_TITLE)
        }
    }
}

/**
 * 实例选择器。
 *
 * 用 [ExposedDropdownMenuBox] 而不是普通按钮加弹出菜单：按钮只在点击后才露出候选，
 * 用户看不出这里能切换实例。下拉框带 label、箭头与当前值，形状本身就在说明用途。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstancePicker(
    profiles: List<Profile>,
    selected: Profile?,
    supervisorOf: (String) -> ProfileSupervisor?,
    onSelect: (String) -> Unit,
    onDelete: (Profile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = selected?.let { supervisorOf(it.name) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (profiles.isNotEmpty()) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.name ?: Strings.NO_INSTANCE,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = profiles.isNotEmpty(),
            label = { Text(Strings.INSTANCE) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .width(220.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                ProfileMenuItem(
                    profile = profile,
                    supervisor = supervisorOf(profile.name),
                    selected = profile.name == selected?.name,
                    onClick = {
                        onSelect(profile.name)
                        expanded = false
                    },
                )
            }
            if (selected != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(Strings.DELETE_PROFILE) },
                    onClick = {
                        onDelete(selected)
                        expanded = false
                    },
                )
            }
        }
    }
    // 状态点跟着当前实例走，不用打开菜单就能看出它是否在跑。
    if (active != null) DaemonStateDot(active)
}

@Composable
private fun ProfileMenuItem(
    profile: Profile,
    supervisor: ProfileSupervisor?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val state = if (supervisor != null) supervisor.state.collectAsState().value else null
    DropdownMenuItem(
        text = {
            Column {
                Text(profile.name)
                Text(
                    text = if (state == null) Strings.DASH else daemonStateText(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dotColor(state), CircleShape),
            )
        },
        trailingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun DaemonStateDot(supervisor: ProfileSupervisor) {
    val state = supervisor.state.collectAsState().value
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(dotColor(state), CircleShape),
    )
}

/**
 * 实例自己的面板头部：名称、daemon 状态与「随界面启动」。
 *
 * 开关放在这里而不是全局设置页：它描述的是这个配置的启动行为，与它的 daemon 状态
 * 处在同一处，切换实例时一眼就能看到当前配置下次会不会被自动拉起。
 */
@Composable
private fun ProfileBar(
    profile: Profile,
    supervisor: ProfileSupervisor,
    autostart: Boolean,
    onToggleAutostart: (Boolean) -> Unit,
) {
    val state by supervisor.state.collectAsState()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DaemonStateDot(supervisor)
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = daemonStateText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Strings.PROFILE_AUTOSTART, style = MaterialTheme.typography.bodySmall)
            Switch(checked = autostart, onCheckedChange = onToggleAutostart)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun dotColor(state: DaemonState?): Color = when (state) {
    is DaemonState.Running -> MaterialTheme.colorScheme.primary
    is DaemonState.Starting, is DaemonState.Stopping -> MaterialTheme.colorScheme.tertiary
    is DaemonState.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun StartStopButton(
    supervisor: ProfileSupervisor,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val state by supervisor.state.collectAsState()
    when (state) {
        is DaemonState.Running -> OutlinedButton(onClick = onStop) { Text(Strings.STOP) }
        is DaemonState.Starting, is DaemonState.Stopping -> {
            OutlinedButton(onClick = { }, enabled = false) { Text(daemonStateText(state)) }
        }
        else -> Button(onClick = onStart) { Text(Strings.START) }
    }
}

@Composable
private fun EnvironmentBanner(environment: AppEnvironment) {
    if (!environment.binaryFound) {
        Banner(
            title = Strings.BINARY_MISSING_TITLE,
            body = Strings.BINARY_MISSING_BODY + environment.binaryPath,
        )
        return
    }
    if (!environment.elevated) {
        Banner(
            title = Strings.NOT_ELEVATED_TITLE,
            body = if (environment.requiresElevation) {
                Strings.NOT_ELEVATED_BODY_PACKAGED
            } else {
                Strings.NOT_ELEVATED_BODY_DEV
            },
        )
    }
}

@Composable
private fun Banner(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState(profilesRoot: String) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(Strings.NO_PROFILE, style = MaterialTheme.typography.titleMedium)
        Text(Strings.NO_PROFILE_HINT, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = profilesRoot,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = appMonoFontFamily(),
        )
    }
}

@Composable
private fun CreateProfileDialog(onDismiss: () -> Unit, onCreate: (String, String) -> String?) {
    var name by remember { mutableStateOf("") }
    var managementUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.NEW_PROFILE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(Strings.FIELD_NAME) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = managementUrl,
                    onValueChange = { managementUrl = it },
                    label = { Text(Strings.FIELD_MANAGEMENT) },
                    singleLine = true,
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onCreate(name, managementUrl) }) { Text(Strings.CREATE) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.CANCEL) }
        },
    )
}
