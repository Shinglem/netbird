package dev.shinglem.netbird.ui.daemon

import dev.shinglem.netbird.ui.api.DaemonApi
import dev.shinglem.netbird.ui.api.DaemonConfig
import dev.shinglem.netbird.ui.api.Features
import dev.shinglem.netbird.ui.api.FullStatus
import dev.shinglem.netbird.ui.api.LoginResponse
import dev.shinglem.netbird.ui.api.NetworkRoute
import dev.shinglem.netbird.ui.api.StatusResponse
import dev.shinglem.netbird.ui.api.SystemEvent
import dev.shinglem.netbird.ui.data.UiState
import dev.shinglem.netbird.ui.profile.Profile
import dev.shinglem.netbird.ui.profile.ProfileLayout
import dev.shinglem.netbird.ui.profile.ProfileStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

class ProfileSupervisorTest {

    private val profile = Profile(
        name = "office",
        iface = "wt-office",
        wgPort = 51821,
        guid = "{9b2f0c1a-7d3e-4c58-9a11-3e6b7c2d5f40}",
        nrptPrefix = "NBAlt-office-",
        gatewayPort = 8472,
    )
    private val layout = ProfileLayout("C:/netbird/profiles", "office")

    @Test
    fun `starting a profile launches one daemon and becomes ready when the gateway answers`() = runBlocking {
        val process = FakeProcess()
        val api = FakeApi()
        val storage = FakeStorage()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val supervisor = supervisor(process, api, storage, scope)

        supervisor.start()
        awaitState(supervisor) { it is DaemonState.Running }

        val state = supervisor.state.value as DaemonState.Running
        assertEquals("http://127.0.0.1:8472", state.baseUrl, "网关地址来自配置端口")
        assertTrue("C:/netbird/profiles/office/config/default.json" in storage.files, "首次启动要落一份 config")
        awaitStatus(supervisor) { it is UiState.Ready }

        scope.cancel()
    }

    @Test
    fun `a daemon that exits immediately is reported as failed with its log tail`() = runBlocking {
        val process = FakeProcess(alive = false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val supervisor = supervisor(process, FakeApi(), FakeStorage(), scope)

        supervisor.start()
        awaitState(supervisor) { it is DaemonState.Failed }
        val state = supervisor.state.value as DaemonState.Failed

        assertEquals("daemon 进程已退出", state.title)

        scope.cancel()
    }

    @Test
    fun `stopping disconnects the daemon before terminating the process`() = runBlocking {
        val process = FakeProcess()
        val api = FakeApi()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val supervisor = supervisor(process, api, FakeStorage(), scope)

        supervisor.start()
        awaitState(supervisor) { it is DaemonState.Running }

        supervisor.stop()
        awaitState(supervisor) { it is DaemonState.Stopped }

        assertTrue(api.downCalls > 0, "停止前要调 Down，让 daemon 自己拆路由/DNS/网卡")
        assertTrue(process.terminated, "最后才终止进程")
        assertTrue(api.closed, "网关 client 要关掉，别留连接")

        scope.cancel()
    }

    @Test
    fun `events accumulate across snapshots without duplicates`() = runBlocking {
        val api = FakeApi()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val supervisor = supervisor(FakeProcess(), api, FakeStorage(), scope)

        supervisor.start()
        awaitState(supervisor) { it is DaemonState.Running }

        api.snapshot = snapshotOf(event("a"))
        awaitEvents(supervisor) { it.size == 1 }

        api.snapshot = snapshotOf(event("a"), event("b"))
        awaitEvents(supervisor) { it.size == 2 }

        // daemon 的事件历史是有界缓冲，旧条目可能被它自己挤掉；
        // UI 侧不能因此删掉已经展示过的事件，也不能把同一事件重复追加。
        api.snapshot = snapshotOf(event("b"))
        delay(300)
        assertEquals(listOf("a", "b"), supervisor.events.value.map { it.id })

        scope.cancel()
    }

    private fun event(id: String) = SystemEvent(id = id, message = "message-$id", userMessage = "user-$id")

    private fun snapshotOf(vararg events: SystemEvent) = StatusResponse(
        status = "Idle",
        daemonVersion = "0.80.0",
        fullStatus = FullStatus(events = events.toList()),
    )

    private fun supervisor(
        process: FakeProcess,
        api: FakeApi,
        storage: FakeStorage,
        scope: CoroutineScope,
    ) = ProfileSupervisor(
        profile = profile,
        layout = layout,
        exe = "C:/netbird/netbird-multi.exe",
        launcher = DaemonLauncher { process },
        storage = storage,
        osEnv = emptyMap(),
        scope = scope,
        pollIntervalMs = 50,
        clientFactory = { _, _ -> api },
    )

    private suspend fun awaitState(supervisor: ProfileSupervisor, predicate: (DaemonState) -> Boolean) {
        withTimeout(5_000) {
            while (!predicate(supervisor.state.value)) delay(10)
        }
    }

    private suspend fun awaitStatus(supervisor: ProfileSupervisor, predicate: (UiState?) -> Boolean) {
        withTimeout(5_000) {
            while (!predicate(supervisor.status.value)) delay(10)
        }
    }

    private suspend fun awaitEvents(
        supervisor: ProfileSupervisor,
        predicate: (List<SystemEvent>) -> Boolean,
    ) {
        withTimeout(5_000) {
            while (!predicate(supervisor.events.value)) delay(10)
        }
    }
}

private class FakeProcess(override val pid: Long = 4242, var alive: Boolean = true) : DaemonProcess {
    var terminated = false

    override fun lines(): Flow<String> = emptyFlow()

    override fun isAlive(): Boolean = alive

    override fun terminate(graceMs: Long) {
        terminated = true
        alive = false
    }
}

private class FakeApi(
    var snapshot: StatusResponse = StatusResponse(status = "Idle", daemonVersion = "0.80.0"),
) : DaemonApi {
    var downCalls = 0
    var closed = false

    override suspend fun status(getFullPeerStatus: Boolean): StatusResponse = snapshot

    override suspend fun up() = Unit

    override suspend fun down() {
        downCalls++
    }

    override suspend fun login(
        setupKey: String,
        managementUrl: String,
        hostname: String,
    ): LoginResponse = LoginResponse()

    override suspend fun waitSSOLogin(userCode: String): String = ""

    override suspend fun logout() = Unit

    override suspend fun networks(): List<NetworkRoute> = emptyList()

    override suspend fun selectNetworks(ids: List<String>, selected: Boolean, all: Boolean) = Unit

    override suspend fun config(): DaemonConfig = DaemonConfig()

    override suspend fun setConfig(fields: JsonObject) = Unit

    override suspend fun features(): Features = Features()

    override suspend fun logLevel(): String = "INFO"

    override suspend fun setLogLevel(level: String) = Unit

    override fun close() {
        closed = true
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

    override fun listDirs(path: String): List<String> = emptyList()

    override fun listFiles(path: String): List<String> = emptyList()

    override fun mkdirs(path: String) {
        dirs += path
    }

    override fun deleteDir(path: String) {
        files.keys.filter { it.startsWith("$path/") }.forEach { files.remove(it) }
    }

    override fun isPortFree(port: Int): Boolean = true

    private fun parentDirsOf(path: String): List<String> {
        val parts = path.split('/')
        return (1 until parts.size).map { parts.take(it).joinToString("/") }
    }
}
