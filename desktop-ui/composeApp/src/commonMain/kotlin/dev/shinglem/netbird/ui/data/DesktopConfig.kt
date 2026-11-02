package dev.shinglem.netbird.ui.data

import dev.shinglem.netbird.ui.theme.ThemeMode
import dev.shinglem.netbird.ui.theme.ThemeStyle
import kotlinx.serialization.Serializable

/**
 * 界面自己的配置，落在程序目录下的 `ui.json`。
 *
 * 只放界面的事：网络参数一律在 daemon 的配置里，这里另存一份只会两边打架。
 * 路径全部相对程序目录，整个目录搬走（便携版、换机器）配置仍然有效。
 *
 * @property autostart 登录自启的期望值。`null` 表示文件里还没写过 —— 首次启动沿用
 *   系统计划任务的现状再写回，这样用脚本装过自启的旧部署不会被默认值悄悄改掉。
 */
@Serializable
data class DesktopConfig(
    val theme: String = ThemeStyle.MATERIAL.id,
    val themeMode: String = ThemeMode.SYSTEM.id,
    val autostart: Boolean? = null,
    /** 关闭窗口时收进托盘而不是退出。daemon 跟随界面生命周期，退出才会停掉它们（见 `ui\state.json`）。 */
    val closeToTray: Boolean = true,
)

/** [DesktopConfig] 的读写。实现写失败必须抛出，调用方据此提示而不是假装保存成功。 */
interface DesktopConfigStore {
    fun load(): DesktopConfig

    fun save(config: DesktopConfig)
}
