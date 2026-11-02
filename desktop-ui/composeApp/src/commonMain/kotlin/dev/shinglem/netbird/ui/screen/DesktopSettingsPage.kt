package dev.shinglem.netbird.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.shinglem.netbird.ui.data.AppEnvironment
import dev.shinglem.netbird.ui.data.Autostart
import dev.shinglem.netbird.ui.data.AutostartState
import dev.shinglem.netbird.ui.data.UiPreferences
import dev.shinglem.netbird.ui.theme.ThemeMode
import dev.shinglem.netbird.ui.theme.ThemeStyle
import dev.shinglem.netbird.ui.theme.preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面端自己的设置：主题、自启、托盘行为。
 *
 * 与「设置」页（`SettingsPage`）分清：那一页改的是 daemon 的配置（接口、路由、DNS），
 * 这一页只改界面，写本地偏好，不碰 daemon。
 */
@Composable
fun DesktopSettingsPage(
    preferences: UiPreferences,
    environment: AppEnvironment,
    autostartController: Autostart,
) {
    val theme by preferences.theme.collectAsState()
    val mode by preferences.mode.collectAsState()
    val closeToTray by preferences.closeToTray.collectAsState()
    val saveError by preferences.saveError.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val scope = rememberCoroutineScope()
    var autostart by remember { mutableStateOf<AutostartState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        autostart = withContext(Dispatchers.Default) { autostartController.state() }
    }

    fun applyAutostart(enabled: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val next = withContext(Dispatchers.Default) {
                    if (enabled) autostartController.enable() else autostartController.disable()
                }
                autostart = next
                if (next is AutostartState.Failed) {
                    error = next.message
                } else {
                    preferences.setAutostart(enabled)
                }
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Strings.DESKTOP_TITLE, style = MaterialTheme.typography.titleMedium)
        ErrorBanner(error ?: saveError)

        SectionCard(title = Strings.DESKTOP_SECTION_APPEARANCE) {
            Text(Strings.DESKTOP_THEME, style = MaterialTheme.typography.labelLarge)
            ThemePicker(selected = theme, dark = dark, onSelect = preferences::setTheme)
            Text(Strings.DESKTOP_THEME_HINT, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(Strings.DESKTOP_MODE, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { entry ->
                    FilterChip(
                        selected = mode == entry,
                        onClick = { preferences.setMode(entry) },
                        label = { Text(entry.label) },
                    )
                }
            }
        }

        SectionCard(title = Strings.DESKTOP_SECTION_STARTUP) {
            SwitchRow(
                label = Strings.DESKTOP_AUTOSTART,
                checked = autostart is AutostartState.Enabled,
                enabled = !busy && autostart != null && autostart !is AutostartState.Unsupported,
            ) { applyAutostart(it) }

            val state = autostart
            val description = when (state) {
                null -> Strings.DESKTOP_AUTOSTART_LOADING
                AutostartState.Unsupported -> Strings.DESKTOP_AUTOSTART_DEV
                AutostartState.Disabled -> Strings.DESKTOP_AUTOSTART_OFF
                is AutostartState.Enabled -> Strings.DESKTOP_AUTOSTART_ON
                is AutostartState.Failed -> Strings.DESKTOP_AUTOSTART_FAILED
            }
            Text(description, style = MaterialTheme.typography.bodySmall)
            if (state is AutostartState.Enabled) {
                Text(
                    text = state.target,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!environment.elevated && state !is AutostartState.Unsupported) {
                Text(
                    text = Strings.DESKTOP_AUTOSTART_NEEDS_ADMIN,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(
                label = Strings.DESKTOP_CLOSE_TO_TRAY,
                checked = closeToTray,
                enabled = true,
            ) { preferences.setCloseToTray(it) }
            Text(Strings.DESKTOP_CLOSE_TO_TRAY_HINT, style = MaterialTheme.typography.bodySmall)
            Text(Strings.DESKTOP_TRAY_HINT, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(title = Strings.DESKTOP_SECTION_ABOUT) {
            InfoRow(
                label = Strings.DESKTOP_CONFIG_FILE,
                value = environment.configFile,
                mono = true,
            )
            InfoRow(
                label = Strings.DESKTOP_APP_VERSION,
                value = environment.appVersion.ifBlank { Strings.DESKTOP_APP_VERSION_DEV },
            )
            InfoRow(
                label = Strings.DESKTOP_PROFILES_ROOT,
                value = environment.profilesRoot,
                mono = true,
            )
            InfoRow(label = Strings.DESKTOP_BINARY, value = environment.binaryPath, mono = true)
            InfoRow(
                label = Strings.DESKTOP_ELEVATED,
                value = if (environment.elevated) {
                    Strings.DESKTOP_ELEVATED_YES
                } else {
                    Strings.DESKTOP_ELEVATED_NO
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePicker(
    selected: ThemeStyle,
    dark: Boolean,
    onSelect: (ThemeStyle) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeStyle.entries.forEach { style ->
            val preview = style.preview(dark)
            FilterChip(
                selected = style == selected,
                onClick = { onSelect(style) },
                label = { Text(style.label) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(preview.surface, CircleShape),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .align(Alignment.Center)
                                .clip(CircleShape)
                                .background(preview.primary, CircleShape),
                        )
                    }
                },
            )
        }
    }
}
