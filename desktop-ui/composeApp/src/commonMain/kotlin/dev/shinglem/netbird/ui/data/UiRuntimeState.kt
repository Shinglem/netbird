package dev.shinglem.netbird.ui.data

import kotlinx.serialization.Serializable

/**
 * 运行态：哪些配置在界面退出后应当被重新拉起。
 *
 * 与 [DesktopConfig]（用户配置）分开存放，因为它由程序自己读写、随每次启停变化；
 * 手改它也有效，但不需要用户理解。
 *
 * `running` 里没有的配置按「随界面启动」处理（新配置就是这种状态）。
 */
@Serializable
data class UiRuntimeState(
    val running: Map<String, Boolean> = emptyMap(),
)

/** [UiRuntimeState] 的读写；坏文件按空状态处理，不阻塞界面启动。 */
interface UiRuntimeStore {
    fun load(): UiRuntimeState

    fun save(state: UiRuntimeState)
}

/** 不落盘的实现：测试与探针用，行为与真实存储一致。 */
class InMemoryRuntimeStore(initial: UiRuntimeState = UiRuntimeState()) : UiRuntimeStore {
    private var current = initial

    override fun load(): UiRuntimeState = current

    override fun save(state: UiRuntimeState) {
        current = state
    }
}
