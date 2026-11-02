package dev.shinglem.netbird.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import dev.shinglem.netbird.ui.api.NetworkRoute
import dev.shinglem.netbird.ui.daemon.ProfileSupervisor
import dev.shinglem.netbird.ui.data.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NetworksPage(supervisor: ProfileSupervisor) {
    val status by supervisor.status.collectAsState()
    val ready = (status as? UiState.Ready)?.status
    val connected = ready?.status == "Connected"
    val revision = ready?.fullStatus?.networksRevision ?: 0

    var routes by remember { mutableStateOf<List<NetworkRoute>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(connected, revision, reloadToken) {
        if (!connected) {
            routes = null
            return@LaunchedEffect
        }
        busy = true
        error = null
        try {
            routes = supervisor.networks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            busy = false
        }
    }

    fun toggle(route: NetworkRoute, selected: Boolean) {
        scope.launch {
            busy = true
            error = null
            try {
                supervisor.setNetworkSelected(route.id, selected)
                routes = supervisor.networks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(Strings.NAV_NETWORKS, style = MaterialTheme.typography.titleMedium)
            if (busy) CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
            TextButton(onClick = { reloadToken++ }, enabled = connected && !busy) {
                Text(Strings.NETWORKS_RELOAD)
            }
        }

        Text(Strings.NETWORKS_HINT, style = MaterialTheme.typography.bodySmall)
        ErrorBanner(error)

        val current = routes
        when {
            !connected -> Text(Strings.NETWORKS_NEED_CONNECTION, style = MaterialTheme.typography.bodyMedium)
            current == null -> Text(Strings.LOADING, style = MaterialTheme.typography.bodySmall)
            current.isEmpty() -> Text(Strings.NETWORKS_EMPTY, style = MaterialTheme.typography.bodyMedium)
            else -> {
                val exitNodes = current.filter { it.isExitNode }
                val others = current.filterNot { it.isExitNode }
                if (exitNodes.isNotEmpty()) {
                    SectionCard(title = Strings.NETWORKS_EXIT_NODE) {
                        exitNodes.forEach { route ->
                            NetworkRow(route, enabled = !busy) { toggle(route, it) }
                        }
                    }
                }
                if (others.isNotEmpty()) {
                    SectionCard(title = Strings.NETWORKS_OTHER) {
                        others.forEach { route ->
                            NetworkRow(route, enabled = !busy) { toggle(route, it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkRow(route: NetworkRoute, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = route.selected,
            onCheckedChange = { onToggle(it) },
            enabled = enabled,
        )
        Column(Modifier.weight(1f)) {
            Text(route.range, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            if (route.domains.isNotEmpty()) {
                Text(
                    text = Strings.NETWORKS_DOMAINS + ": " + route.domains.joinToString("，"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (route.id.isNotBlank()) {
            Text(route.id, style = MaterialTheme.typography.labelSmall)
        }
    }
}
