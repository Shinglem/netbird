package dev.shinglem.netbird.ui.data

/**
 * 登录自启的当前状态。
 *
 * [Unsupported] 是开发态（`./gradlew run`）：没有 jpackage 启动器可写进计划任务，
 * 只能用 `scripts/install-autostart-task.ps1` 显式装。
 */
sealed interface AutostartState {
    data object Unsupported : AutostartState

    data object Disabled : AutostartState

    /**
     * @property target 计划任务指向的启动器。
     * @property silent 任务是否带静默标志。不带的话登录时会弹出主窗口，属于旧版本留下的任务。
     */
    data class Enabled(val target: String, val silent: Boolean = true) : AutostartState

    data class Failed(val message: String) : AutostartState
}

/**
 * 登录自启。
 *
 * 平台实现由入口注入（`WindowsAutostart`）。做成接口而不是 `expect object`：
 * expect/actual 的类型在 Kotlin 里仍是 Beta，编译会打警告；这里只有一份桌面实现，
 * 注入更简单，也和 `ProfileStorage` 一处风格一致。
 */
interface Autostart {
    /** 读当前状态；不修改系统。 */
    fun state(): AutostartState

    /** 安装或覆盖自启项。可能需要管理员权限。 */
    fun enable(): AutostartState

    /** 删除自启项；不存在时视作 [AutostartState.Disabled]。 */
    fun disable(): AutostartState
}

/**
 * 让系统里的自启项与 `ui.json` 一致。
 *
 * 配置里还没写过（`null`）时以系统现状为准并写回 —— 用脚本手工装过自启的旧部署
 * 不会因为默认值被悄悄删掉。读状态失败（[AutostartState.Failed]）时不动配置。
 */
fun reconcileAutostart(preferences: UiPreferences, controller: Autostart): AutostartState {
    var current = controller.state()
    if (current is AutostartState.Unsupported) return current

    // 旧版本建的任务不带静默参数，登录时会弹出主窗口；重建成当前形式。
    // 用户明确关掉自启时不做这一步 —— 反正接下来就删掉了。
    if (current is AutostartState.Enabled && !current.silent && preferences.autostart.value != false) {
        val upgraded = controller.enable()
        if (upgraded is AutostartState.Failed) return upgraded
        current = upgraded
    }

    val desired = preferences.autostart.value
    if (desired == null) {
        when (current) {
            is AutostartState.Enabled -> preferences.setAutostart(true)
            AutostartState.Disabled -> preferences.setAutostart(false)
            else -> Unit
        }
        return current
    }

    if (desired == (current is AutostartState.Enabled)) return current
    return if (desired) controller.enable() else controller.disable()
}
