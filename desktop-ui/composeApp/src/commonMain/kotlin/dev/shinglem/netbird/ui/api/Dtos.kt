package dev.shinglem.netbird.ui.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * daemon 的 `StatusResponse`（`client/proto/daemon.proto:293`）。
 *
 * 字段名是 grpc-gateway 的 JSON 投影，与 proto 字段名不完全一致：
 * 一律以 `curl` 实测输出为准，并保持 `ignoreUnknownKeys`，daemon 升级加字段时不炸。
 *
 * `fullStatus` 只有请求里带 `getFullPeerStatus: true` 时才返回 —— 不带的话 daemon
 * 不会填它，UI 就会把"没有数据"显示成"没有 peer"。
 */
@Serializable
data class StatusResponse(
    /** Idle | Connecting | Connected | NeedsLogin | LoginFailed | SessionExpired */
    val status: String = "Idle",
    val daemonVersion: String = "",
    val fullStatus: FullStatus? = null,
    /** protobuf Timestamp（RFC3339 字符串）；SSO 会话到期时刻，仅 SSO 注册时有值。 */
    val sessionExpiresAt: String? = null,
)

@Serializable
data class FullStatus(
    val managementState: PeerLink? = null,
    val signalState: PeerLink? = null,
    val localPeerState: LocalPeer? = null,
    val peers: List<PeerState> = emptyList(),
    val relays: List<RelayState> = emptyList(),
    // proto 字段是 `dns_servers`，protojson 转驼峰。
    val dnsServers: List<NsGroupState> = emptyList(),
    /** 事件历史；daemon 每次快照都带回当前缓冲，UI 按 id 去重后追加。 */
    val events: List<SystemEvent> = emptyList(),
    val lazyConnectionEnabled: Boolean = false,
    val sshServerState: SshServerState? = null,
    val networksRevision: Long = 0,
)

@Serializable
data class PeerLink(
    // proto 里是 `URL`，protojson 只把下划线转驼峰、不改大小写 —— 不是 `url`。
    @SerialName("URL") val url: String = "",
    val connected: Boolean = false,
    val error: String = "",
)

@Serializable
data class LocalPeer(
    // proto 里这个字段叫 `IP`（全大写），protojson 保留原名 —— 不是 `ip`。
    @SerialName("IP") val ip: String = "",
    val pubKey: String = "",
    val fqdn: String = "",
    val kernelInterface: Boolean = false,
    val rosenpassEnabled: Boolean = false,
    val rosenpassPermissive: Boolean = false,
    val networks: List<String> = emptyList(),
    val ipv6: String = "",
    val wgPort: Long = 0,
)

@Serializable
data class PeerState(
    @SerialName("IP") val ip: String = "",
    val fqdn: String = "",
    val pubKey: String = "",
    val connStatus: String = "",
    /** protobuf Timestamp（RFC3339 字符串）。 */
    val connStatusUpdate: String? = null,
    val relayed: Boolean = false,
    val localIceCandidateType: String = "",
    val remoteIceCandidateType: String = "",
    val localIceCandidateEndpoint: String = "",
    val remoteIceCandidateEndpoint: String = "",
    /** protobuf Timestamp（RFC3339 字符串）。 */
    val lastWireguardHandshake: String? = null,
    val bytesRx: Long = 0,
    val bytesTx: Long = 0,
    val rosenpassEnabled: Boolean = false,
    val networks: List<String> = emptyList(),
    val relayAddress: String = "",
    val ipv6: String = "",
    // protobuf Duration 在 JSON 里是字符串（如 "0.012s"），不是数字。
    val latency: String? = null,
)

@Serializable
data class RelayState(
    // proto 字段是 `URI`，同样保留原样。
    @SerialName("URI") val uri: String = "",
    val available: Boolean = false,
    val error: String = "",
    val transport: String = "",
)

@Serializable
data class NsGroupState(
    val servers: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val enabled: Boolean = false,
    val error: String = "",
)

@Serializable
data class SshServerState(
    val enabled: Boolean = false,
    val sessions: List<SshSessionInfo> = emptyList(),
)

@Serializable
data class SshSessionInfo(
    val username: String = "",
    val remoteAddress: String = "",
    val command: String = "",
    val jwtUsername: String = "",
    val portForwards: List<String> = emptyList(),
)

/** `SystemEvent`（`daemon.proto:683`）：severity/category 是枚举名，如 `WARNING` / `DNS`。 */
@Serializable
data class SystemEvent(
    val id: String = "",
    val severity: String = "INFO",
    val category: String = "SYSTEM",
    /** 技术日志行，给排查用。 */
    val message: String = "",
    /** 给用户看的文案，UI 优先显示这个。 */
    val userMessage: String = "",
    val timestamp: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * `ListNetworks` 的一条网络（`daemon.proto` 的 `Network`）。
 *
 * proto 字段名是 `ID`，没有下划线，protojson 原样保留。
 */
@Serializable
data class NetworkRoute(
    @SerialName("ID") val id: String = "",
    val range: String = "",
    val selected: Boolean = false,
    val domains: List<String> = emptyList(),
) {
    /** 出口节点：默认路由，且不是域名网络。 */
    val isExitNode: Boolean get() = domains.isEmpty() && (range == "0.0.0.0/0" || range == "::/0")
}

@Serializable
data class ListNetworksResponse(val routes: List<NetworkRoute> = emptyList())

/**
 * `GetConfig` 的响应。键名以 protojson 的默认规则为准：有下划线的字段转驼峰
 * （`disable_dns` → `disableDns`），没有下划线的原样保留（`mDMManagedFields`、`adminURL`）。
 * 不确定的字段不要往这里加 —— 先 `curl` 实测。
 */
@Serializable
data class DaemonConfig(
    val managementUrl: String = "",
    val configFile: String = "",
    val logFile: String = "",
    val preSharedKey: String = "",
    @SerialName("adminURL") val adminUrl: String = "",
    val interfaceName: String = "",
    val wireguardPort: Long = 0,
    val mtu: Long = 0,
    val disableAutoConnect: Boolean = false,
    val serverSSHAllowed: Boolean = false,
    val rosenpassEnabled: Boolean = false,
    val rosenpassPermissive: Boolean = false,
    val disableNotifications: Boolean = false,
    val lazyConnectionEnabled: Boolean = false,
    val blockInbound: Boolean = false,
    val networkMonitor: Boolean = false,
    val disableDns: Boolean = false,
    val disableClientRoutes: Boolean = false,
    val disableServerRoutes: Boolean = false,
    val blockLanAccess: Boolean = false,
    val enableSSHRoot: Boolean = false,
    val enableSSHSFTP: Boolean = false,
    val enableSSHLocalPortForwarding: Boolean = false,
    val enableSSHRemotePortForwarding: Boolean = false,
    val disableSSHAuth: Boolean = false,
    val sshJWTCacheTTL: Int = 0,
    val disableIpv6: Boolean = false,
    val remoteJobsAllowed: Boolean = false,
    /** 被 MDM 强制、UI 必须置灰的键名（与 `mdm.Key*` 常量同名，如 `managementURL`）。 */
    val mDMManagedFields: List<String> = emptyList(),
)

/** `GetFeatures`：运维/MDM 关掉的功能，UI 据此隐藏入口。 */
@Serializable
data class Features(
    val disableProfiles: Boolean = false,
    val disableUpdateSettings: Boolean = false,
    val disableNetworks: Boolean = false,
    val disableAdvancedView: Boolean? = null,
)

/** `Login` 的响应；`needsSSOLogin` 时用 `userCode` 调 `WaitSSOLogin`。 */
@Serializable
data class LoginResponse(
    val needsSSOLogin: Boolean = false,
    val userCode: String = "",
    val verificationURI: String = "",
    val verificationURIComplete: String = "",
)

@Serializable
data class LogLevelResponse(val level: String = "INFO")
