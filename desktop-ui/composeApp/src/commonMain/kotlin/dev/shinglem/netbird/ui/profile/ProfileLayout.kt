package dev.shinglem.netbird.ui.profile

/**
 * 一个配置在磁盘上的固定布局：
 *
 * ```
 * <root>\profiles\<name>\
 *     instance.json          桌面端读写的身份（与既有部署同格式）
 *     config\default.json    daemon 的 `--config`
 *     runfiles\              NB_STATE_DIR + daemon.log
 * ```
 *
 * 用 `/` 拼路径：Windows 的 Win32 与 Go 的 filepath 都接受正斜杠，而 common 源集不知道分隔符。
 */
data class ProfileLayout(
    val profilesRoot: String,
    val name: String,
) {
    val dir: String get() = join(profilesRoot, name)
    val instanceFile: String get() = join(dir, "instance.json")
    val configFile: String get() = join(dir, "config", "default.json")
    val stateDir: String get() = join(dir, "runfiles")
    val logFile: String get() = join(stateDir, "daemon.log")

    companion object {
        fun join(vararg parts: String): String = parts
            .filter { it.isNotBlank() }
            .joinToString("/") { it.replace('\\', '/').trimEnd('/') }
    }
}
