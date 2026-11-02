package dev.shinglem.netbird.ui.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 这些样本按 protojson 的默认命名规则手写：有下划线的字段转驼峰
 * （`disable_dns` → `disableDns`），没有下划线的字段原样保留（`ID`、`adminURL`、`mDMManagedFields`）。
 * 它们锁住的是「不要按 proto 字段名照抄」这条规则。
 */
class DtosTest {

    @Test
    fun `snake_case keys from the proto are not what the gateway emits`() {
        // 网关用 runtime.NewServeMux 的默认 marshaler（UseProtoNames=false），
        // 所以 proto 里的 disable_dns 到 HTTP 上是 disableDns。这条测试锁住这一点，
        // 免得有人照着 daemon.proto 的字段名加 DTO。
        val config = netbirdJson.decodeFromString<DaemonConfig>(
            """{"disable_dns":true,"disable_ipv6":true}""",
        )
        assertFalse(config.disableDns, "下划线键名不会被识别")
        assertFalse(config.disableIpv6, "下划线键名不会被识别")
    }

    @Test
    fun `config uses camelCase for snake_case fields and keeps acronyms`() {
        val json = """
            {
              "managementUrl": "https://nb.example.com",
              "configFile": "C:/profiles/office/config/default.json",
              "adminURL": "https://nb.example.com",
              "interfaceName": "wt-office",
              "wireguardPort": "51821",
              "mtu": "1280",
              "disableNotifications": true,
              "disableDns": true,
              "disableIpv6": true,
              "lazyConnectionEnabled": true,
              "mDMManagedFields": ["managementURL"],
              "brandNewField": 42
            }
        """.trimIndent()

        val config = netbirdJson.decodeFromString<DaemonConfig>(json)

        val expected = DaemonConfig(
            managementUrl = "https://nb.example.com",
            configFile = "C:/profiles/office/config/default.json",
            adminUrl = "https://nb.example.com",
            interfaceName = "wt-office",
            wireguardPort = 51821,
            mtu = 1280,
            disableNotifications = true,
            disableDns = true,
            disableIpv6 = true,
            lazyConnectionEnabled = true,
            mDMManagedFields = listOf("managementURL"),
        )
        assertEquals(expected, config)
    }

    @Test
    fun `networks keep the ID key and flag exit nodes`() {
        val json = """
            {
              "routes": [
                {"ID": "exit", "range": "0.0.0.0/0", "selected": true, "domains": []},
                {"ID": "lan", "range": "10.10.0.0/24", "selected": false, "domains": []},
                {"ID": "dns", "range": "10.20.0.0/24", "selected": false, "domains": ["internal.example"]}
              ]
            }
        """.trimIndent()

        val routes = netbirdJson.decodeFromString<ListNetworksResponse>(json).routes

        assertEquals(listOf("exit", "lan", "dns"), routes.map { it.id })
        assertTrue(routes[0].isExitNode)
        assertFalse(routes[1].isExitNode)
        assertFalse(routes[2].isExitNode, "带域名的网段不是出口节点")
        assertTrue(routes[2].domains.contains("internal.example"))
    }

    @Test
    fun `events and features decode enum names and camelCase keys`() {
        val events = netbirdJson.decodeFromString<FullStatus>(
            """
            {
              "events": [
                {
                  "id": "e1",
                  "severity": "WARNING",
                  "category": "DNS",
                  "message": "nameserver unreachable",
                  "userMessage": "DNS 不可用",
                  "timestamp": "2026-01-01T00:00:00Z",
                  "metadata": {"network": "lan"}
                }
              ]
            }
            """.trimIndent(),
        )

        val event = events.events.single()
        assertEquals("WARNING", event.severity)
        assertEquals("DNS", event.category)
        assertEquals("DNS 不可用", event.userMessage)
        assertEquals("lan", event.metadata["network"])

        val features = netbirdJson.decodeFromString<Features>(
            """{"disableProfiles":true,"disableNetworks":false}""",
        )
        assertTrue(features.disableProfiles)
        assertTrue(features.disableNetworks.not())
    }

    @Test
    fun `login response keeps the needsSSOLogin acronym`() {
        val response = netbirdJson.decodeFromString<LoginResponse>(
            """{"needsSSOLogin":true,"userCode":"ABCD-1234","verificationURI":"https://nb.example.com/device"}""",
        )

        assertTrue(response.needsSSOLogin)
        assertEquals("ABCD-1234", response.userCode)
        assertEquals("https://nb.example.com/device", response.verificationURI)
    }
}
