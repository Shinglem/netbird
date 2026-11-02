package dev.shinglem.netbird.ui.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 网关的请求形状。这两处一旦写错，界面表现是「点了没反应」或「什么都读不到」，
 * 而且不会有任何错误提示 —— 所以单独锁住。
 */
class DaemonRequestsTest {

    @Test
    fun `status asks for the full peer status`() {
        val body = statusRequestBody(getFullPeerStatus = true)

        assertTrue(body["getFullPeerStatus"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `up is always asynchronous`() {
        assertTrue(UP_REQUEST_BODY["async"]!!.jsonPrimitive.boolean, "同步 Up 会阻塞到引擎就绪")
    }

    @Test
    fun `login omits empty overrides`() {
        val body = loginRequestBody(setupKey = "", managementUrl = "", hostname = "")
        assertTrue(body.isEmpty(), "空串意味着沿用配置，不要当成要清空")

        val filled = loginRequestBody(setupKey = "KEY-1", managementUrl = "https://nb.example.com", hostname = "")
        assertEquals("KEY-1", filled["setupKey"]!!.jsonPrimitive.content)
        assertEquals("https://nb.example.com", filled["managementUrl"]!!.jsonPrimitive.content)
        assertFalse(filled.containsKey("hostname"))
    }

    @Test
    fun `network selection is incremental`() {
        val body = selectNetworksRequestBody(listOf("exit", "lan"), all = false)

        assertEquals(listOf("exit", "lan"), body["networkIDs"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(body["append"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("all"))
    }
}
