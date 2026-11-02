package dev.shinglem.netbird.ui.data

/**
 * 运行环境事实：由桌面端注入，UI 据此提示"为什么 daemon 起不来"。
 *
 * @property elevated 当前进程是否已是管理员；daemon 要建 wintun 网卡，非管理员必然失败。
 * @property binaryPath 实际使用的 netbird 可执行文件。
 * @property binaryFound 该文件是否存在。
 * @property requiresElevation 打包产物：启动时会请求管理员权限，被拒绝时提示要与开发态区分开。
 * @property appVersion jpackage 的版本号；开发态为空。
 * @property configFile 界面配置 `ui.json` 的绝对路径，设置页直接显示给用户。
 */
data class AppEnvironment(
    val elevated: Boolean,
    val binaryPath: String,
    val binaryFound: Boolean,
    val profilesRoot: String,
    val requiresElevation: Boolean = false,
    val appVersion: String = "",
    val configFile: String = "",
)
