package dev.shinglem.netbird.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * daemon 的 `StatusResponse`（`client/proto/daemon.proto:293`）。
 *
 * 字段名是 grpc-gateway 的 JSON 投影，与 proto 字段名不完全一致：
 * 一律以 `curl` 实测输出为准，并保持 `ignoreUnknownKeys`，daemon 升级加字段时不炸。
 */
@Serializable
data class StatusResponse(
    /** Idle | Connecting | Connected | NeedsLogin | LoginFailed | SessionExpired */
    val status: String = "Idle",
    val daemonVersion: String = "",
    val fullStatus: FullStatus? = null,
)

@Serializable
data class FullStatus(
    val managementState: PeerLink? = null,
    val signalState: PeerLink? = null,
    val localPeerState: LocalPeer? = null,
    val peers: List<PeerState> = emptyList(),
)

@Serializable
data class PeerLink(
    val url: String = "",
    val connected: Boolean = false,
    val error: String = "",
)

@Serializable
data class LocalPeer(
    // proto 里这个字段叫 `IP`（全大写），protojson 保留原名 —— 不是 `ip`。
    @SerialName("IP") val ip: String = "",
    val pubKey: String = "",
    val fqdn: String = "",
    val networks: List<String> = emptyList(),
)

@Serializable
data class PeerState(
    @SerialName("IP") val ip: String = "",
    val fqdn: String = "",
    val connStatus: String = "",
    val relayed: Boolean = false,
    val bytesRx: Long = 0,
    val bytesTx: Long = 0,
    // protobuf Duration 在 JSON 里是字符串（如 "0.012s"），不是数字。
    val latency: String? = null,
)
