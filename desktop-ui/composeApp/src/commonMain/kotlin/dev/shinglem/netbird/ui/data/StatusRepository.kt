package dev.shinglem.netbird.ui.data

import dev.shinglem.netbird.ui.api.DaemonClient
import dev.shinglem.netbird.ui.api.StatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI 侧的状态：`DaemonUnavailable` 是 UI 合成的，daemon 不会返回它。 */
sealed interface UiState {
    data object Loading : UiState
    data class Ready(val status: StatusResponse) : UiState
    data class Unavailable(val reason: String) : UiState
}

/**
 * 只负责"拿 daemon 的状态"。
 *
 * 首版用 `Status` 轮询（2 秒），二期换成 `SubscribeStatus` 流：
 * 网关的流是逐行 JSON，首帧即快照，届时这里只换实现、不改 UI。
 */
class StatusRepository(private val client: DaemonClient) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun start(intervalMillis: Long = 2_000) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(intervalMillis)
            }
        }
    }

    suspend fun refresh() {
        _state.value = try {
            UiState.Ready(client.status())
        } catch (e: Exception) {
            UiState.Unavailable(e.message ?: "daemon 不可达")
        }
    }

    fun stop() {
        pollJob?.cancel()
        client.close()
    }
}
