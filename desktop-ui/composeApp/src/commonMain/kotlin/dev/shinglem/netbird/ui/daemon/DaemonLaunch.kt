package dev.shinglem.netbird.ui.daemon

import kotlinx.coroutines.flow.Flow

/** 一次 daemon 启动所需的全部参数（纯数据，便于单测断言）。 */
data class DaemonLaunchSpec(
    val exe: String,
    val args: List<String>,
    val env: Map<String, String>,
    val workDir: String,
    /** 从拉起进程到网关可用允许的最长时间；建网卡比纯 HTTP 服务慢得多。 */
    val timeoutMs: Long = 60_000,
)

/** 桌面端持有的一个 daemon 子进程句柄；关停只作用于它，不扫进程表。 */
interface DaemonProcess {
    val pid: Long

    /** 子进程的控制台输出（stdout+stderr 合并），用于日志面板与失败诊断。 */
    fun lines(): Flow<String>

    fun isAlive(): Boolean

    /** 先请整棵进程树退出，超过宽限期再强杀。 */
    fun terminate(graceMs: Long)
}

fun interface DaemonLauncher {
    fun launch(spec: DaemonLaunchSpec): DaemonProcess
}

/** daemon 进程层状态（与网关层的 [dev.shinglem.netbird.ui.data.UiState] 分开）。 */
sealed interface DaemonState {
    data object Stopped : DaemonState

    data class Starting(val step: String) : DaemonState

    data class Running(val baseUrl: String) : DaemonState

    data object Stopping : DaemonState

    data class Failed(val title: String, val detail: String?) : DaemonState
}
