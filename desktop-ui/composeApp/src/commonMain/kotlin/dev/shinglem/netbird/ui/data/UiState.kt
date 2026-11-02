package dev.shinglem.netbird.ui.data

import dev.shinglem.netbird.ui.api.StatusResponse

/**
 * 网关层的状态。
 *
 * `Unavailable` 是 UI 合成的：daemon 自己永远不会返回它。daemon 进程没起来（[dev.shinglem.netbird.ui.daemon.DaemonState.Stopped]）
 * 与"进程在但网关连不上"是两种事实，UI 要分开显示。
 */
sealed interface UiState {
    data class Ready(val status: StatusResponse) : UiState

    data class Unavailable(val reason: String) : UiState
}
