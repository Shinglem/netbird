package dev.shinglem.netbird.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.netbird.ui.api.PeerState
import dev.shinglem.netbird.ui.daemon.DaemonState
import dev.shinglem.netbird.ui.daemon.ProfileSupervisor
import dev.shinglem.netbird.ui.data.UiState
import dev.shinglem.netbird.ui.theme.appMonoFontFamily

@Composable
fun PeersPage(supervisor: ProfileSupervisor) {
    val daemonState by supervisor.state.collectAsState()
    val running = daemonState is DaemonState.Running
    val status by supervisor.status.collectAsState()
    val ready = (status as? UiState.Ready)?.status
    val peers = ready?.fullStatus?.peers.orEmpty()
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }

    val filtered = peers
        .filter { it.matches(query) }
        .sortedWith(compareBy({ it.fqdn.ifBlank { it.ip } }, { it.ip }))

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(Strings.PEERS_SEARCH) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text("${filtered.size} / ${peers.size}", style = MaterialTheme.typography.bodySmall)
        }

        if (peers.isEmpty()) {
            Text(
                text = when {
                    !running -> Strings.DAEMON_STOPPED
                    ready == null -> Strings.LOADING
                    else -> Strings.PEERS_EMPTY
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.key() }) { peer ->
                PeerRow(
                    peer = peer,
                    expanded = expanded == peer.key(),
                    onToggle = { expanded = if (expanded == peer.key()) null else peer.key() },
                )
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerState, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = peer.fqdn.ifBlank { peer.ip },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = peer.ip + if (peer.ipv6.isNotBlank()) " · ${peer.ipv6}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = appMonoFontFamily(),
                    )
                }
                StatusPill(
                    text = peerStatusText(peer.connStatus),
                    tone = if (peer.connStatus == "Connected") StatusTone.OK else StatusTone.NEUTRAL,
                )
                StatusPill(
                    text = if (peer.relayed) Strings.PEER_RELAYED else Strings.PEER_DIRECT,
                    tone = if (peer.relayed) StatusTone.WARN else StatusTone.OK,
                )
                Text(formatLatency(peer.latency), style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = "${Strings.PEER_RX} ${formatBytes(peer.bytesRx)}" +
                        " · ${Strings.PEER_TX} ${formatBytes(peer.bytesTx)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "${Strings.PEER_LAST_HANDSHAKE} ${formatTimestamp(peer.lastWireguardHandshake)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (expanded) {
                InfoRow(Strings.PEER_PUBKEY, peer.pubKey, mono = true)
                InfoRow(Strings.PEER_ICE_LOCAL, peer.localIceCandidateType + " " + peer.localIceCandidateEndpoint)
                InfoRow(Strings.PEER_ICE_REMOTE, peer.remoteIceCandidateType + " " + peer.remoteIceCandidateEndpoint)
                InfoRow(Strings.PEER_RELAY_ADDRESS, peer.relayAddress, mono = true)
                InfoRow(Strings.PEER_NETWORKS, peer.networks.joinToString("，"))
                InfoRow(Strings.PEER_STATUS, formatTimestamp(peer.connStatusUpdate))
                Text(Strings.COLLAPSE, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun PeerState.key(): String = pubKey.ifBlank { ip }

private fun PeerState.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return fqdn.contains(needle, ignoreCase = true) ||
        ip.contains(needle) ||
        ipv6.contains(needle) ||
        pubKey.contains(needle)
}

private fun peerStatusText(raw: String): String = when (raw) {
    "Connected" -> Strings.STATUS_CONNECTED
    "Connecting" -> Strings.STATUS_CONNECTING
    "Disconnected" -> Strings.STATUS_IDLE
    "" -> Strings.STATUS_UNKNOWN
    else -> raw
}
