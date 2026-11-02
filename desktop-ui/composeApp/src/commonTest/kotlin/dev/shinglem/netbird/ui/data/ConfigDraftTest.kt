package dev.shinglem.netbird.ui.data

import dev.shinglem.netbird.ui.api.DaemonConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class ConfigDraftTest {

    private val baseline = DaemonConfig(
        interfaceName = "wt-office",
        wireguardPort = 51821,
        mtu = 1280,
    )

    @Test
    fun `an untouched draft produces an empty patch`() {
        assertTrue(ConfigDraft.of(baseline).diff(baseline).isEmpty())
    }

    @Test
    fun `only changed fields are sent and they use the gateway json names`() {
        val patch = ConfigDraft.of(baseline)
            .copy(disableDns = true, mtu = "1400", lazyConnectionEnabled = true)
            .diff(baseline)

        assertEquals(setOf("disableDns", "mtu", "lazyConnectionEnabled"), patch.keys)
        assertEquals(true, (patch["disableDns"] as JsonPrimitive).content.toBoolean())
        assertEquals("1400", (patch["mtu"] as JsonPrimitive).content)
    }

    @Test
    fun `invalid numbers never reach the daemon`() {
        val patch = ConfigDraft.of(baseline)
            .copy(mtu = "abc", wireguardPort = "")
            .diff(baseline)

        assertTrue(patch.isEmpty(), "解析不了的数字要当没改，而不是提交 0")
    }

    @Test
    fun `closing the ssh server declaration is a normal config change`() {
        val withSsh = baseline.copy(serverSSHAllowed = true)
        val patch = ConfigDraft.of(withSsh).copy(serverSshAllowed = false).diff(withSsh)

        assertEquals(setOf("serverSSHAllowed"), patch.keys)
        assertEquals("false", (patch["serverSSHAllowed"] as JsonPrimitive).content)
    }
}
