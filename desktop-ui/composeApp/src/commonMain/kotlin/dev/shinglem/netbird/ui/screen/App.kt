package dev.shinglem.netbird.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.netbird.ui.data.StatusRepository
import dev.shinglem.netbird.ui.data.UiState
import kotlinx.coroutines.launch

@Composable
fun App(repository: StatusRepository) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = Strings.APP_TITLE, style = MaterialTheme.typography.headlineMedium)

                when (val s = state) {
                    is UiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text(Strings.LOADING)
                    }

                    is UiState.Unavailable -> Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Strings.STATUS_UNAVAILABLE, style = MaterialTheme.typography.titleMedium)
                            Text(s.reason, style = MaterialTheme.typography.bodySmall)
                            Text(Strings.HINT_NO_GATEWAY, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { scope.launch { repository.refresh() } }) {
                                Text(Strings.REFRESH)
                            }
                        }
                    }

                    is UiState.Ready -> StatusCard(s)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: UiState.Ready) {
    val status = state.status
    val full = status.fullStatus

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(statusText(status.status), style = MaterialTheme.typography.titleLarge)

            InfoRow(Strings.LABEL_DAEMON_VERSION, status.daemonVersion.ifBlank { Strings.DASH })
            InfoRow(Strings.LABEL_LOCAL_IP, full?.localPeerState?.ip?.ifBlank { Strings.DASH } ?: Strings.DASH)
            InfoRow(Strings.LABEL_FQDN, full?.localPeerState?.fqdn?.ifBlank { Strings.DASH } ?: Strings.DASH)
            InfoRow(Strings.LABEL_MANAGEMENT, linkText(full?.managementState?.url, full?.managementState?.connected))
            InfoRow(Strings.LABEL_SIGNAL, linkText(full?.signalState?.url, full?.signalState?.connected))
            InfoRow(Strings.LABEL_PEERS, (full?.peers?.size ?: 0).toString())
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun linkText(url: String?, connected: Boolean?): String = when {
    url.isNullOrBlank() -> Strings.DASH
    connected == true -> "$url（${Strings.CONNECTED}）"
    connected == false -> "$url（${Strings.DISCONNECTED}）"
    else -> url
}

private fun statusText(raw: String): String = when (raw) {
    "Idle" -> Strings.STATUS_IDLE
    "Connecting" -> Strings.STATUS_CONNECTING
    "Connected" -> Strings.STATUS_CONNECTED
    "NeedsLogin" -> Strings.STATUS_NEEDS_LOGIN
    "LoginFailed" -> Strings.STATUS_LOGIN_FAILED
    "SessionExpired" -> Strings.STATUS_SESSION_EXPIRED
    else -> if (raw.isBlank()) Strings.STATUS_UNKNOWN else raw
}
