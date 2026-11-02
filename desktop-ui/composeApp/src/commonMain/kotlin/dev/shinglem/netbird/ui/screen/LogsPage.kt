package dev.shinglem.netbird.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.shinglem.netbird.ui.api.SystemEvent
import dev.shinglem.netbird.ui.daemon.ProfileSupervisor

@Composable
fun LogsPage(supervisor: ProfileSupervisor) {
    val events by supervisor.events.collectAsState()
    val log by supervisor.log.collectAsState()
    var autoScroll by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(Strings.NAV_LOGS, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
                Text(Strings.LOGS_AUTOSCROLL, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { supervisor.clearLog() }) {
                    Text(Strings.LOGS_CLEAR)
                }
            }
        }
        Text(Strings.LOGS_CLEAR_HINT, style = MaterialTheme.typography.bodySmall)

        SectionCard(title = Strings.LOGS_EVENTS) {
            EventList(events, Modifier.fillMaxWidth().height(220.dp))
        }

        SectionCard(title = Strings.LOGS_DAEMON, modifier = Modifier.weight(1f)) {
            DaemonLog(log, autoScroll, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun EventList(events: List<SystemEvent>, modifier: Modifier) {
    if (events.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(Strings.LOGS_EVENTS_EMPTY, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // daemon 按发生顺序给历史，最新的放最上面。
        items(events.asReversed(), key = { it.id.ifBlank { it.timestamp.orEmpty() + it.message } }) { event ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(event.severity, severityTone(event.severity))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = event.userMessage.ifBlank { event.message },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "${event.category} · ${formatTimestamp(event.timestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DaemonLog(lines: List<String>, autoScroll: Boolean, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size, autoScroll) {
        if (autoScroll && lines.isNotEmpty()) scroll.scrollTo(scroll.maxValue)
    }
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(scroll)
            .padding(8.dp),
    ) {
        Text(
            text = lines.ifEmpty { listOf(Strings.LOG_EMPTY) }.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun severityTone(severity: String): StatusTone = when (severity) {
    "ERROR", "CRITICAL" -> StatusTone.BAD
    "WARNING" -> StatusTone.WARN
    else -> StatusTone.NEUTRAL
}
