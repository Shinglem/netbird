package dev.shinglem.netbird.ui.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusResponseTest {

    // 按 grpc-gateway 实测形状手写：大小写保留的 URL/IP、int64 写成字符串、还有 DTO 里没有的字段。
    private val connectedJson = """
        {
          "status": "Connected",
          "daemonVersion": "0.82.0",
          "sessionExpiresAt": "2026-12-01T00:00:00Z",
          "fullStatus": {
            "managementState": {"URL": "https://nb.example.com:443", "connected": true, "error": ""},
            "signalState": {"URL": "https://nb.example.com:443", "connected": false, "error": "lookup failed"},
            "localPeerState": {
              "IP": "10.99.0.5/16",
              "pubKey": "localpubkey",
              "kernelInterface": false,
              "fqdn": "surface.server.example",
              "networks": ["10.99.0.0/16"],
              "ipv6": "",
              "wgPort": 51821
            },
            "peers": [
              {
                "IP": "10.99.0.7/16",
                "fqdn": "peer1.server.example",
                "connStatus": "Connected",
                "relayed": false,
                "bytesRx": "12345",
                "bytesTx": "6789",
                "latency": "0.012s",
                "networks": ["10.99.0.0/16"]
              }
            ],
            "relays": [],
            "dnsServers": [],
            "networksRevision": "3"
          }
        }
    """.trimIndent()

    @Test
    fun `parses connected status with gateway field names`() {
        val status = netbirdJson.decodeFromString<StatusResponse>(connectedJson)
        val full = status.fullStatus

        assertEquals("Connected", status.status)
        assertEquals("0.82.0", status.daemonVersion)
        assertEquals("https://nb.example.com:443", full?.managementState?.url, "URL 是大小写敏感的键")
        assertEquals(true, full?.managementState?.connected, "management 应为已连接")
        assertEquals("lookup failed", full?.signalState?.error)
        assertEquals("10.99.0.5/16", full?.localPeerState?.ip, "IP 是大小写敏感的键")
        assertEquals("surface.server.example", full?.localPeerState?.fqdn)
        assertEquals(1, full?.peers?.size, "peers 应解析出一条")
        assertEquals(12345L, full?.peers?.first()?.bytesRx, "int64 在 proto3 JSON 里是带引号的字符串")
        assertEquals(6789L, full?.peers?.first()?.bytesTx)
        assertEquals("0.012s", full?.peers?.first()?.latency)
    }

    @Test
    fun `tolerates idle status without full status`() {
        val status = netbirdJson.decodeFromString<StatusResponse>(
            """{"status":"Idle","fullStatus":null,"daemonVersion":"0.82.0"}""",
        )

        assertEquals("Idle", status.status)
        assertNull(status.fullStatus)
    }

    @Test
    fun `ignores fields the daemon added after this build`() {
        val status = netbirdJson.decodeFromString<StatusResponse>(
            """{"status":"NeedsLogin","brandNewField":{"nested":true},"daemonVersion":"0.90.0"}""",
        )

        assertEquals("NeedsLogin", status.status)
        assertEquals("0.90.0", status.daemonVersion)
    }
}
