package dev.shinglem.netbird.ui.data

import dev.shinglem.netbird.ui.daemon.DaemonLauncher
import dev.shinglem.netbird.ui.profile.ProfileStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class ProfilesControllerTest {

    private val root = "C:/netbird"
    private val profilesRoot = "$root/profiles"

    @Test
    fun `takes over a config directory that has no instance json yet`() {
        val storage = FakeStorage()
        storage.put("$profilesRoot/office/config/default.json", """
            {
              "Name": "office",
              "WgIface": "wt-office",
              "WgPort": 51821,
              "WintunGUID": "{9b2f0c1a-7d3e-4c58-9a11-3e6b7c2d5f40}",
              "NRPTKeyPrefix": "NBAlt-office-",
              "ManagementURL": {"Scheme": "https", "Host": "nb.example.com:443", "Path": ""}
            }
        """.trimIndent())

        val controller = controller(storage)
        controller.reload()

        val profile = controller.profiles.value.single()
        assertEquals("office", profile.name)
        assertEquals("wt-office", profile.iface)
        assertEquals(51821, profile.wgPort)
        assertEquals("{9b2f0c1a-7d3e-4c58-9a11-3e6b7c2d5f40}", profile.guid)
        assertEquals("NBAlt-office-", profile.nrptPrefix)
        assertEquals("https://nb.example.com:443", profile.managementUrl)
        assertEquals(8471, profile.gatewayPort, "接管时要在 8471 起分配一个网关端口")
        assertTrue(
            "$profilesRoot/office/instance.json" !in storage.files,
            "既有 instance.json 属于部署脚本（含 fqdn/domains），桌面端不改写它",
        )
    }

    @Test
    fun `gives every profile its own gateway port`() {
        val storage = FakeStorage()
        storage.put("$profilesRoot/alpha/instance.json", """{"name":"alpha","iface":"wt-alpha","wgPort":51821}""")
        storage.put("$profilesRoot/beta/instance.json", """{"name":"beta","iface":"wt-beta","wgPort":51822}""")

        val controller = controller(storage)
        controller.reload()

        val ports = controller.profiles.value.map { it.gatewayPort }
        assertEquals(listOf(8471, 8472), ports, "两个配置不能共用网关端口")
    }

    @Test
    fun `rejects duplicate and malformed profile names`() {
        val storage = FakeStorage()
        storage.put("$profilesRoot/taken/instance.json", """{"name":"taken","iface":"wt-taken","wgPort":51821}""")
        val controller = controller(storage)
        controller.reload()

        assertTrue(controller.createProfile("taken", "").isFailure, "同名配置必须被拒")
        assertTrue(controller.createProfile("Bad Name", "").isFailure, "大写与空格会让接口名/管道名非法")
        assertTrue(controller.createProfile("fresh", "https://mgmt.example.com:443").isSuccess)

        val created = controller.profiles.value.single { it.name == "fresh" }
        assertTrue("$profilesRoot/fresh/config/default.json" in storage.files, "新配置要落一份可启动的 config")
        assertEquals(8472, created.gatewayPort, "新配置的网关端口在已有配置之后顺延")
    }

    @Test
    fun `removing a profile deletes its directory`() {
        val storage = FakeStorage()
        storage.put("$profilesRoot/gone/config/default.json", """{"WgIface":"wt-gone"}""")
        val controller = controller(storage)
        controller.reload()

        assertTrue(controller.removeProfile("gone").isSuccess)

        assertTrue(controller.profiles.value.isEmpty(), "删除后不应再出现在标签页里")
        assertTrue(
            storage.files.keys.none { it.startsWith("$profilesRoot/gone/") },
            "配置目录（含注册状态与私钥）要一起删掉",
        )
    }

    private fun controller(
        storage: ProfileStorage,
        runtime: UiRuntimeStore = InMemoryRuntimeStore(),
    ) = ProfilesController(
        storage = storage,
        launcher = DaemonLauncher { error("测试里不启动进程") },
        exe = "$root/netbird-multi.exe",
        root = root,
        osEnv = emptyMap(),
        scope = CoroutineScope(SupervisorJob()),
        runtime = runtime,
    )

    @Test
    fun `every profile starts with the ui by default`() {
        val storage = FakeStorage()
        storage.put("$profilesRoot/alpha/instance.json", """{"name":"alpha","iface":"wt-alpha","wgPort":51821}""")
        storage.put("$profilesRoot/beta/instance.json", """{"name":"beta","iface":"wt-beta","wgPort":51822}""")
        val runtime = InMemoryRuntimeStore()
        val controller = controller(storage, runtime)

        controller.reload()

        assertEquals(listOf("alpha", "beta"), controller.autostartNames(), "没有记录时默认跟随界面启动")
    }

    @Test
    fun `turning autostart off keeps the profile out of the next launch`() {
        val storage = FakeStorage()
        storage.put("$profilesRoot/alpha/instance.json", """{"name":"alpha","iface":"wt-alpha","wgPort":51821}""")
        storage.put("$profilesRoot/beta/instance.json", """{"name":"beta","iface":"wt-beta","wgPort":51822}""")
        val runtime = InMemoryRuntimeStore()
        val controller = controller(storage, runtime)
        controller.reload()

        controller.setAutostart("alpha", false)

        assertEquals(listOf("beta"), controller.autostartNames())
        assertEquals(mapOf("alpha" to false), runtime.load().running, "开关要落盘，下次启动才能生效")

        // 重新构造控制器（模拟重启界面）后记录仍然有效。
        val restarted = controller(storage, runtime)
        restarted.reload()
        assertEquals(listOf("beta"), restarted.autostartNames())
    }

    @Test
    fun `manual stop is remembered so the profile stays down on next launch`() {
        val storage = FakeStorage()
        storage.put("$profilesRoot/alpha/instance.json", """{"name":"alpha","iface":"wt-alpha","wgPort":51821}""")
        val runtime = InMemoryRuntimeStore()
        val controller = controller(storage, runtime)
        controller.reload()

        controller.stop("alpha")

        assertEquals(false, controller.isAutostart("alpha"))
        assertEquals(mapOf("alpha" to false), runtime.load().running)
    }

    @Test
    fun `deleting a profile drops its record`() {
        val storage = FakeStorage()
        storage.put("$profilesRoot/gone/instance.json", """{"name":"gone","iface":"wt-gone","wgPort":51821}""")
        val runtime = InMemoryRuntimeStore(UiRuntimeState(mapOf("gone" to false, "ghost" to true)))
        val controller = controller(storage, runtime)

        controller.reload()
        assertEquals(mapOf("gone" to false), runtime.load().running, "还在的配置保留记录，孤儿名字被清掉")

        assertTrue(controller.removeProfile("gone").isSuccess)

        assertEquals(emptyMap(), runtime.load().running, "配置没了，记录里也不该留着它的名字")
    }
}

private class FakeStorage : ProfileStorage {
    val files = mutableMapOf<String, String>()
    private val dirs = mutableSetOf<String>()

    override fun exists(path: String): Boolean = files.containsKey(path) || dirs.contains(path)

    override fun readText(path: String): String? = files[path]

    override fun writeText(path: String, text: String) {
        files[path] = text
        parentDirsOf(path).forEach { dirs += it }
    }

    override fun listDirs(path: String): List<String> = children(path) { it in dirs }

    override fun listFiles(path: String): List<String> = children(path) { it in files.keys }

    override fun mkdirs(path: String) {
        dirs += path
    }

    override fun deleteDir(path: String) {
        files.keys.filter { it.startsWith("$path/") }.forEach { files.remove(it) }
        dirs.removeAll { it == path || it.startsWith("$path/") }
    }

    override fun isPortFree(port: Int): Boolean = true

    fun put(path: String, text: String) {
        files[path] = text
        parentDirsOf(path).forEach { dirs += it }
    }

    private fun children(path: String, predicate: (String) -> Boolean): List<String> =
        (dirs + files.keys).filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
            .filter(predicate)
            .map { it.removePrefix("$path/") }
            .sorted()

    private fun parentDirsOf(path: String): List<String> {
        val parts = path.split('/')
        return (1 until parts.size).map { parts.take(it).joinToString("/") }
    }
}
