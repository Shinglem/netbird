package dev.shinglem.netbird.ui.daemon

import dev.shinglem.netbird.ui.api.DaemonApi
import dev.shinglem.netbird.ui.api.DaemonClient
import dev.shinglem.netbird.ui.api.DaemonConfig
import dev.shinglem.netbird.ui.api.Features
import dev.shinglem.netbird.ui.api.LoginResponse
import dev.shinglem.netbird.ui.api.NetworkRoute
import dev.shinglem.netbird.ui.api.SystemEvent
import dev.shinglem.netbird.ui.data.UiState
import dev.shinglem.netbird.ui.profile.Profile
import dev.shinglem.netbird.ui.profile.ProfileLayout
import dev.shinglem.netbird.ui.profile.ProfileStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * 一个配置的生命周期：拉起 daemon 子进程 → 等网关就绪 → 轮询状态 → 停止时先 `Down` 再终止。
 *
 * 关停只作用于本类持有的 [DaemonProcess] 句柄：不调 `schtasks`、不扫进程表、不按进程名杀，
 * 所以物理上碰不到这台机器上别的 NetBird 实例。
 */
class ProfileSupervisor(
    val profile: Profile,
    private val layout: ProfileLayout,
    private val exe: String,
    private val launcher: DaemonLauncher,
    private val storage: ProfileStorage,
    private val osEnv: Map<String, String>,
    private val scope: CoroutineScope,
    private val setupKey: String? = null,
    private val pollIntervalMs: Long = 2_000,
    /**
     * 工厂带上 profile handle：`GetConfig` / `SetConfig` 在 daemon 侧不接受空 handle。
     * 参数是 (baseUrl, profileHandle)。
     */
    private val clientFactory: (String, String) -> DaemonApi = { url, handle -> DaemonClient(url, handle) },
) {

    private val _state = MutableStateFlow<DaemonState>(DaemonState.Stopped)
    val state: StateFlow<DaemonState> = _state.asStateFlow()

    private val _status = MutableStateFlow<UiState?>(null)
    val status: StateFlow<UiState?> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** daemon 的事件缓冲；由每次状态快照里的 `fullStatus.events` 合并去重而来。 */
    private val _events = MutableStateFlow<List<SystemEvent>>(emptyList())
    val events: StateFlow<List<SystemEvent>> = _events.asStateFlow()

    private val mutex = Mutex()
    private var process: DaemonProcess? = null

    // 用 StateFlow 发布而不是普通 var：UI 的页面协程与这里的生命周期协程是两个线程，
    // 普通字段的写入对它们不保证可见。
    private val _client = MutableStateFlow<DaemonApi?>(null)
    private var logJob: Job? = null
    private var pollJob: Job? = null

    val isBusy: Boolean
        get() = _state.value is DaemonState.Starting || _state.value is DaemonState.Stopping

    fun start() {
        if (_state.value is DaemonState.Starting || _state.value is DaemonState.Running ||
            _state.value is DaemonState.Stopping
        ) {
            return
        }
        scope.launch { startDaemon() }
    }

    fun stop() {
        if (_state.value is DaemonState.Stopped || _state.value is DaemonState.Stopping) return
        scope.launch { stopDaemon() }
    }

    /** 退出应用：不等 UI，直接收摊（[terminate] 自带宽限期）。 */
    fun shutdown() {
        pollJob?.cancel()
        logJob?.cancel()
        runCatching { process?.terminate(STOP_GRACE_MS) }
        process = null
        _client.value?.close()
        _client.value = null
        _state.value = DaemonState.Stopped
    }

    private suspend fun startDaemon() = mutex.withLock {
        if (_state.value is DaemonState.Running) return@withLock
        _state.value = DaemonState.Starting(START_PREPARING)
        try {
            storage.mkdirs(layout.stateDir)
            if (!storage.exists(layout.configFile)) {
                storage.writeText(layout.configFile, DaemonPlan.configJson(profile))
            }
            storage.writeText(layout.instanceFile, DaemonPlan.instanceJson(profile))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(START_WRITE_FAILED, e.message)
            return@withLock
        }

        _state.value = DaemonState.Starting(START_LAUNCHING)
        val started = try {
            launcher.launch(DaemonPlan.spec(profile, layout, exe, osEnv, setupKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(START_LAUNCH_FAILED, e.message)
            return@withLock
        }
        process = started
        watchLogs(started)
        appendLog("$START_PID_PREFIX ${started.pid}")

        val baseUrl = "http://127.0.0.1:${profile.gatewayPort}"
        val probe = clientFactory(baseUrl, DaemonPlan.profileHandle(layout.configFile))
        _client.value = probe
        _state.value = DaemonState.Starting(START_WAITING_GATEWAY)

        val outcome = awaitReady(started, probe, READY_TIMEOUT_MS)
        if (outcome is ReadyOutcome.Failed) {
            fail(outcome.title, outcome.detail)
            started.terminate(STOP_GRACE_MS)
            process = null
            probe.close()
            _client.value = null
            return@withLock
        }

        _state.value = DaemonState.Running(baseUrl)
        startPolling(probe)
    }

    private suspend fun stopDaemon() = mutex.withLock {
        val running = process
        if (running == null) {
            resetToStopped()
            return@withLock
        }
        _state.value = DaemonState.Stopping
        pollJob?.cancel()
        pollJob = null

        // 先让 daemon 自己断开（拆路由 / DNS / 网卡），再终止进程：
        // 直接硬杀会跳过清理路径，可能留下坏掉的 wintun 适配器。
        runCatching { _client.value?.down() }
        delay(STOP_DRAIN_MS)

        running.terminate(STOP_GRACE_MS)
        appendLog(STOP_DONE)
        resetToStopped()
    }

    private fun resetToStopped() {
        logJob?.cancel()
        logJob = null
        pollJob?.cancel()
        pollJob = null
        process = null
        _client.value?.close()
        _client.value = null
        _status.value = null
        _events.value = emptyList()
        _state.value = DaemonState.Stopped
    }

    private fun watchLogs(source: DaemonProcess) {
        logJob = scope.launch {
            try {
                source.lines().collect { line -> appendLog(line) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 进程被终止时读端会抛：流关掉即可，不当错误上报。
            }
        }
    }

    private fun startPolling(probe: DaemonApi) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                _status.value = try {
                    val snapshot = probe.status()
                    mergeEvents(snapshot.fullStatus?.events.orEmpty())
                    UiState.Ready(snapshot)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    UiState.Unavailable(e.message ?: GATEWAY_UNREACHABLE)
                }
                delay(pollIntervalMs)
            }
        }
    }

    private suspend fun awaitReady(
        source: DaemonProcess,
        probe: DaemonApi,
        timeoutMs: Long,
    ): ReadyOutcome = withTimeoutOrNull(timeoutMs) {
        while (true) {
            if (!source.isAlive()) {
                return@withTimeoutOrNull ReadyOutcome.Failed(START_EXITED, logTail())
            }
            val ready = runCatching { probe.status() }.isSuccess
            if (ready) return@withTimeoutOrNull ReadyOutcome.Ready
            delay(READY_POLL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        ReadyOutcome.Ready
    } ?: ReadyOutcome.Failed(START_TIMEOUT, logTail())

    private fun appendLog(line: String) {
        _log.update { previous -> (previous + line).takeLast(MAX_LOG_LINES) }
    }

    /** 清空界面缓冲区；不影响 daemon 自己写的日志文件。 */
    fun clearLog() {
        _log.value = emptyList()
    }

    /**
     * 把快照里的事件合并进缓冲。
     *
     * daemon 每次快照都回整段历史，所以只能按事件身份去重后追加，否则同一条事件会被
     * 重复显示成几十条。ID 为空时退化到「时间 + 文案」作身份。
     */
    private fun mergeEvents(incoming: List<SystemEvent>) {
        if (incoming.isEmpty()) return
        _events.update { previous ->
            val known = previous.mapTo(HashSet()) { it.identity() }
            val fresh = incoming.filter { it.identity() !in known }
            (previous + fresh).takeLast(MAX_EVENTS)
        }
    }

    private fun SystemEvent.identity(): String =
        id.ifBlank { "${timestamp.orEmpty()}|$message" }

    // ---- 页面操作 ----
    // 都要求 daemon 已就绪；失败原样抛给 UI 展示，不在这里吞掉或再记一遍日志。

    /** `Up`（实现固定异步）；连接进度看 [status]。 */
    suspend fun connect() {
        requireClient().up()
    }

    /** 只断开连接，不停 daemon 进程。 */
    suspend fun disconnect() {
        requireClient().down()
    }

    /**
     * 重新拉起 daemon。
     *
     * 改过的配置只有重启才会进 daemon 的内存副本（`SetConfig` 只写盘，不更新
     * `s.config`），所以像「关掉 SSH 声明后才能注销」这类修复必须重启一次。
     * 返回时网关已就绪，可以直接继续调用别的 RPC。
     */
    suspend fun restart() {
        stopDaemon()
        startDaemon()
    }

    suspend fun login(setupKey: String = "", managementUrl: String = ""): LoginResponse =
        requireClient().login(setupKey = setupKey, managementUrl = managementUrl)

    suspend fun waitSSOLogin(userCode: String): String = requireClient().waitSSOLogin(userCode)

    suspend fun logout() {
        requireClient().logout()
    }

    suspend fun networks(): List<NetworkRoute> = requireClient().networks()

    suspend fun setNetworkSelected(id: String, selected: Boolean) {
        requireClient().selectNetworks(listOf(id), selected)
    }

    suspend fun config(): DaemonConfig = requireClient().config()

    suspend fun setConfig(fields: JsonObject) {
        requireClient().setConfig(fields)
    }

    suspend fun features(): Features = requireClient().features()

    suspend fun logLevel(): String = requireClient().logLevel()

    suspend fun setLogLevel(level: String) {
        requireClient().setLogLevel(level)
    }

    private fun requireClient(): DaemonApi =
        _client.value ?: throw IllegalStateException(DAEMON_NOT_RUNNING)

    private fun logTail(lines: Int = 6): String? =
        _log.value.takeLast(lines).joinToString("\n").ifBlank { null }

    private fun fail(title: String, detail: String?) {
        _state.value = DaemonState.Failed(title, detail)
    }

    private sealed interface ReadyOutcome {
        data object Ready : ReadyOutcome

        data class Failed(val title: String, val detail: String?) : ReadyOutcome
    }

    companion object {
        const val READY_TIMEOUT_MS = 60_000L
        const val MAX_LOG_LINES = 400
        const val MAX_EVENTS = 200

        private const val DAEMON_NOT_RUNNING = "daemon 未运行，先启动这个配置"

        private const val READY_POLL_MS = 250L
        private const val STOP_GRACE_MS = 5_000L
        private const val STOP_DRAIN_MS = 700L

        private const val START_PREPARING = "写入配置"
        private const val START_LAUNCHING = "启动 daemon"
        private const val START_WAITING_GATEWAY = "等待网关就绪"
        private const val START_PID_PREFIX = "[ui] daemon pid"
        private const val START_WRITE_FAILED = "无法写入配置目录"
        private const val START_LAUNCH_FAILED = "无法启动 daemon"
        private const val START_EXITED = "daemon 进程已退出"
        private const val START_TIMEOUT = "等待网关就绪超时"
        private const val STOP_DONE = "[ui] daemon 已停止"
        private const val GATEWAY_UNREACHABLE = "daemon 网关不可达"
    }
}
