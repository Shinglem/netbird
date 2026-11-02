package dev.shinglem.netbird.ui.data

import dev.shinglem.netbird.ui.api.DaemonConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设置页的可编辑副本。
 *
 * 文本字段保留原文，保存时再解析：非法输入不会把草稿弄崩，也不会因为一个空串就把
 * daemon 的端口改成 0。只有真正变化的字段才会进入 [diff] —— `SetConfig` 是部分更新，
 * 多发字段等于多给自己一次触发 MDM 拒绝的机会。
 */
internal data class ConfigDraft(
    val interfaceName: String,
    val wireguardPort: String,
    val mtu: String,
    val disableDns: Boolean,
    val disableClientRoutes: Boolean,
    val disableServerRoutes: Boolean,
    val blockLanAccess: Boolean,
    val disableAutoConnect: Boolean,
    val lazyConnectionEnabled: Boolean,
    val blockInbound: Boolean,
    val networkMonitor: Boolean,
    val disableNotifications: Boolean,
    val disableIpv6: Boolean,
    val serverSshAllowed: Boolean,
) {
    fun diff(original: DaemonConfig): JsonObject = buildJsonObject {
        if (interfaceName.trim() != original.interfaceName) put("interfaceName", interfaceName.trim())
        wireguardPort.trim().toLongOrNull()?.let {
            if (it != original.wireguardPort) put("wireguardPort", it)
        }
        mtu.trim().toLongOrNull()?.let {
            if (it != original.mtu) put("mtu", it)
        }
        if (disableDns != original.disableDns) put("disableDns", disableDns)
        if (disableClientRoutes != original.disableClientRoutes) {
            put("disableClientRoutes", disableClientRoutes)
        }
        if (disableServerRoutes != original.disableServerRoutes) {
            put("disableServerRoutes", disableServerRoutes)
        }
        if (blockLanAccess != original.blockLanAccess) put("blockLanAccess", blockLanAccess)
        if (disableAutoConnect != original.disableAutoConnect) {
            put("disableAutoConnect", disableAutoConnect)
        }
        if (lazyConnectionEnabled != original.lazyConnectionEnabled) {
            put("lazyConnectionEnabled", lazyConnectionEnabled)
        }
        if (blockInbound != original.blockInbound) put("blockInbound", blockInbound)
        if (networkMonitor != original.networkMonitor) put("networkMonitor", networkMonitor)
        if (disableNotifications != original.disableNotifications) {
            put("disableNotifications", disableNotifications)
        }
        if (disableIpv6 != original.disableIpv6) put("disableIpv6", disableIpv6)
        if (serverSshAllowed != original.serverSSHAllowed) {
            put("serverSSHAllowed", serverSshAllowed)
        }
    }

    companion object {
        fun of(config: DaemonConfig): ConfigDraft = ConfigDraft(
            interfaceName = config.interfaceName,
            wireguardPort = config.wireguardPort.takeIf { it > 0 }?.toString().orEmpty(),
            mtu = config.mtu.takeIf { it > 0 }?.toString().orEmpty(),
            disableDns = config.disableDns,
            disableClientRoutes = config.disableClientRoutes,
            disableServerRoutes = config.disableServerRoutes,
            blockLanAccess = config.blockLanAccess,
            disableAutoConnect = config.disableAutoConnect,
            lazyConnectionEnabled = config.lazyConnectionEnabled,
            blockInbound = config.blockInbound,
            networkMonitor = config.networkMonitor,
            disableNotifications = config.disableNotifications,
            disableIpv6 = config.disableIpv6,
            serverSshAllowed = config.serverSSHAllowed,
        )
    }
}
