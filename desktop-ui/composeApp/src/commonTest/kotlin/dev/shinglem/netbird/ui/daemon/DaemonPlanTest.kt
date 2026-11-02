package dev.shinglem.netbird.ui.daemon

import dev.shinglem.netbird.ui.api.netbirdJson
import dev.shinglem.netbird.ui.profile.Profile
import dev.shinglem.netbird.ui.profile.ProfileLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DaemonPlanTest {

    private val profile = Profile(
        name = "office",
        iface = "wt-office",
        wgPort = 51821,
        guid = "{9b2f0c1a-7d3e-4c58-9a11-3e6b7c2d5f40}",
        nrptPrefix = "NBAlt-office-",
        gatewayPort = 8472,
        managementUrl = "https://nb.example.com:443",
    )

    private val layout = ProfileLayout("C:/netbird/profiles", "office")
    private val exe = "C:/netbird/netbird-multi.exe"

    @Test
    fun `launches one daemon per profile on its own pipe and gateway port`() {
        val spec = DaemonPlan.spec(profile, layout, exe, mapOf("PATH" to "C:/bin"))

        assertEquals(exe, spec.exe)
        assertEquals("C:/netbird", spec.workDir, "wintun.dll 必须与 exe 同目录，工作目录跟随 exe")
        assertEquals(
            listOf("service", "run"),
            spec.args.take(2),
            "daemon 子进程必须走 service run，而不是注册成服务",
        )
        assertTrue("npipe://netbird-office" in spec.args, "每个实例一条独立管道")
        assertTrue("tcp://127.0.0.1:8472" in spec.args, "网关端口来自配置，不写死 8471")
        assertTrue("--enable-json-socket" in spec.args)
        assertTrue("C:/netbird/profiles/office/config/default.json" in spec.args)
        assertTrue("C:/netbird/profiles/office/runfiles/daemon.log" in spec.args)
    }

    @Test
    fun `does not inherit parent NB variables`() {
        val env = DaemonPlan.spec(
            profile,
            layout,
            exe,
            mapOf(
                "PATH" to "C:/bin",
                "NB_STATE_DIR" to "C:/someone-else/runfiles",
                "NB_NRPT_KEY_PREFIX" to "NetBird-",
                "HTTP_PROXY" to "http://127.0.0.1:1080",
            ),
        ).env

        assertEquals("C:/netbird/profiles/office/runfiles", env["NB_STATE_DIR"])
        assertEquals("NBAlt-office-", env["NB_NRPT_KEY_PREFIX"], "前缀必须来自本配置，否则会删别的实例的 NRPT 规则")
        assertEquals("C:/bin", env["PATH"])
        assertFalse("HTTP_PROXY" in env, "白名单以外的一律不继承")
    }

    @Test
    fun `setup key is passed through the environment not the command line`() {
        val spec = DaemonPlan.spec(profile, layout, exe, emptyMap(), setupKey = "ABC-123")

        assertEquals("ABC-123", spec.env["NB_SETUP_KEY"])
        assertFalse(spec.args.any { it.contains("ABC-123") }, "密钥不进命令行，避免出现在进程列表里")
    }

    @Test
    fun `config json keeps ManagementURL in the shape the daemon parses`() {
        val config = netbirdJson.parseToJsonElement(DaemonPlan.configJson(profile)).jsonObject

        // Go 侧是 *url.URL：JSON 是对象，写成字符串 daemon 会立刻解析失败。
        val management = config["ManagementURL"]!!.jsonObject
        assertEquals("https", management["Scheme"]!!.jsonPrimitive.content)
        assertEquals("nb.example.com:443", management["Host"]!!.jsonPrimitive.content)
        assertEquals("wt-office", config["WgIface"]!!.jsonPrimitive.content)
        assertEquals("{9b2f0c1a-7d3e-4c58-9a11-3e6b7c2d5f40}", config["WintunGUID"]!!.jsonPrimitive.content)
        assertEquals("NBAlt-office-", config["NRPTKeyPrefix"]!!.jsonPrimitive.content)
        // 缺省时 profilemanager 会补 true，daemon 就把注销当成特权操作；这里必须显式关掉。
        assertFalse(config["ServerSSHAllowed"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `instance json round-trips through the tolerant reader`() {
        val parsed = netbirdJson.decodeFromString<Profile>(DaemonPlan.instanceJson(profile))

        assertEquals(profile, parsed, "instance.json 是接管既有部署的依据，必须能读回")
    }

    @Test
    fun `parent path follows the executable location`() {
        assertEquals("C:/netbird", DaemonPlan.parentOf("C:\\netbird\\netbird-multi.exe"))
        assertEquals("netbird-multi.exe", DaemonPlan.parentOf("netbird-multi.exe"))
    }

    @Test
    fun `profile handle is the config file name without extension`() {
        assertEquals("default", DaemonPlan.profileHandle("C:\\p\\mine\\config\\default.json"))
        assertEquals("custom", DaemonPlan.profileHandle("C:/p/mine/config/custom.json"))
        assertEquals("default", DaemonPlan.profileHandle(""))
    }
}
