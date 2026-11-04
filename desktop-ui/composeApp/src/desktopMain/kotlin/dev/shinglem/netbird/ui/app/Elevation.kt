package dev.shinglem.netbird.ui.app

import java.io.File

/**
 * 管理员权限的获取。
 *
 * 两条路径与 hermes 一致：
 * - **双击启动**：不是管理员就 `runas` 重启自己，用户看到一次 UAC（Windows 唯一受支持的提权方式）。
 * - **登录自启**：走计划任务（`install-autostart-task.ps1`，`HighestAvailable` + `InteractiveToken`），
 *   由 Task Scheduler 直接创建进程，不经过 `consent.exe`，因此不弹 UAC。
 *
 * 刻意不给 exe 加 `requireAdministrator` manifest：那会让每次登录也走加载器提权，
 * 计划任务「不弹 UAC」的前提就没了。
 */
object Elevation {

    enum class Relaunch {
        /** 已拉起管理员进程，当前进程应当退出。 */
        STARTED,

        /** 用户点了「否」—— 不是故障，调用方可以普通权限继续。 */
        CANCELLED,

        /** `ShellExecuteExW` 失败。 */
        FAILED,

        /** 开发态（`./gradlew run`）没有 jpackage 启动器，无从重启。 */
        NOT_PACKAGED,
    }

    /**
     * 当前进程是否已是管理员。
     *
     * 判据是"能不能在 System32 里建文件"：不引入 JNI/FFM，也不会因为某台机器没有
     * `\\.\PHYSICALDRIVE0` 而误判。
     */
    fun isElevated(): Boolean = runCatching {
        val system32 = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32")
        val probe = File(system32, "netbird-ui-elevation-${ProcessHandle.current().pid()}.tmp")
        probe.createNewFile()
        probe.delete()
        true
    }.getOrDefault(false)

    /** jpackage 启动器的绝对路径；开发态返回 null。 */
    fun launcherExe(): File? = runCatching {
        val fromProperty = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }
        val fromProcess = ProcessHandle.current().info().command().orElse(null)
            ?.takeIf { File(it).name.equals(LAUNCHER_NAME, ignoreCase = true) }
        val path = fromProperty ?: fromProcess ?: return null
        File(path).takeIf { it.isFile }
    }.getOrNull()

    /**
     * 以管理员身份重新拉起启动器；返回 [Relaunch.STARTED] 时调用方必须立刻退出。
     *
     * [arguments] 必须原样带给新进程 —— 登录自启靠 `--silent` 决定要不要显示窗口，
     * 丢了参数就变成弹窗启动。
     */
    fun relaunchElevated(arguments: List<String> = emptyList()): Relaunch {
        val exe = launcherExe() ?: return Relaunch.NOT_PACKAGED
        val workingDir = exe.absoluteFile.parentFile?.absolutePath ?: return Relaunch.FAILED
        return when (WindowsRunAs.run(exe.absolutePath, workingDir, arguments)) {
            0 -> Relaunch.STARTED
            WindowsRunAs.ERROR_CANCELLED -> Relaunch.CANCELLED
            else -> Relaunch.FAILED
        }
    }

    private const val LAUNCHER_NAME = "netbird-multi-ui.exe"
}
