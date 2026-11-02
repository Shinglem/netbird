package dev.shinglem.netbird.ui.profile

import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * 一个可独立启停的 NetBird 配置：一个 daemon 子进程 + 一套接口 / DNS / 状态目录。
 *
 * 字段与多实例部署的 `instance.json` 对齐（`<root>\profiles\<name>\instance.json`），
 * 所以桌面端可以直接接管既有的 `profiles\*\` 目录，而不需要另建一套元数据。
 */
@Serializable
data class Profile(
    val name: String,
    val iface: String,
    val wgPort: Int,
    /** wintun 适配器 GUID：两个实例共用会让第二个建不起接口。 */
    val guid: String,
    /** NRPT 键前缀：两个实例共用会让一个退出时删掉另一个的 DNS 规则。 */
    val nrptPrefix: String,
    /** 本配置 daemon 的 HTTP/JSON 网关端口（只监听回环）。 */
    val gatewayPort: Int,
    val managementUrl: String = "",
    val mode: String = "Source",
    val logLevel: String = "info",
    val created: String = "",
) {
    /** 每个实例一条独立管道，避免与别的 NetBird 部署抢 `npipe://netbird`。 */
    val daemonAddr: String get() = "npipe://netbird-$name"

    companion object {
        const val NAME_PATTERN = "^[a-z0-9][a-z0-9-]{0,31}$"

        fun isValidName(name: String): Boolean = Regex(NAME_PATTERN).matches(name)

        /**
         * 新建配置的默认身份。`wgPort` 与 `gatewayPort` 由调用方按已有配置顺延，
         * 保证同机多实例不撞端口。
         */
        fun create(
            name: String,
            managementUrl: String = "",
            wgPort: Int = 51821,
            gatewayPort: Int = 8471,
            created: String = "",
        ): Profile = Profile(
            name = name,
            iface = "wt-$name",
            wgPort = wgPort,
            guid = randomGuid(),
            nrptPrefix = "NBAlt-$name-",
            gatewayPort = gatewayPort,
            managementUrl = managementUrl,
            created = created,
        )

        /** UUID v4；common 源集里没有 `java.util.UUID`，按 RFC 4122 就地生成。 */
        private fun randomGuid(): String {
            val bytes = Random.nextBytes(16)
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return buildString {
                append('{')
                append(hex, 0, 8).append('-')
                append(hex, 8, 12).append('-')
                append(hex, 12, 16).append('-')
                append(hex, 16, 20).append('-')
                append(hex, 20, 32)
                append('}')
            }
        }
    }
}
