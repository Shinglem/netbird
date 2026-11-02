package dev.shinglem.netbird.ui.screen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 登录期间的状态文案判定。这两条写错一次的代价是"每次登录都被自己取消"，
 * 所以锁住：`no peer auth method provided ... use a setup key ...` 里也含 "setup key"。
 */
class ConnectPageMessagesTest {

    private val notProvided =
        "rpc error: code = PermissionDenied desc = no peer auth method provided, " +
            "please use a setup key or interactive SSO login"

    private val invalidKey = "rpc error: code = NotFound desc = couldn't add peer: setup key is invalid"

    @Test
    fun `the unattended-login hint is not an invalid key`() {
        assertFalse(isInvalidSetupKeyError(notProvided))
        assertTrue(isAuthNotProvided(notProvided))
    }

    @Test
    fun `an invalid key is recognised`() {
        assertTrue(isInvalidSetupKeyError(invalidKey))
        assertFalse(isAuthNotProvided(invalidKey))
    }

    @Test
    fun `empty and unrelated messages are neither`() {
        assertFalse(isInvalidSetupKeyError(""))
        assertFalse(isInvalidSetupKeyError("context canceled"))
        assertFalse(isAuthNotProvided(""))
    }
}
