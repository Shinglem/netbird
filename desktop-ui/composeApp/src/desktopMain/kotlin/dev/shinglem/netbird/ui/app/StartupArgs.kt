package dev.shinglem.netbird.ui.app

/** 登录自启时用的参数：不显示主窗口，只驻留托盘。 */
const val SILENT_START_ARGUMENT = "--silent"

/** [SILENT_START_ARGUMENT] 的别名，便于手动验证「登录时是什么样」。 */
const val TRAY_START_ARGUMENT = "--tray"

/**
 * 是否以静默方式启动。
 *
 * 只认这两个标志，其余参数忽略 —— jpackage 启动器会塞自己的 `jpackage.app-path` 之类。
 * 标志自身大小写不敏感。
 */
fun isSilentStart(args: Iterable<String>): Boolean = args.any(::isSilentFlag)

/** 计划任务里存的是一整条命令行，按空白拆开再判断。 */
fun isSilentStart(commandLine: String): Boolean = isSilentStart(commandLine.split(' '))

private fun isSilentFlag(arg: String): Boolean =
    arg.equals(SILENT_START_ARGUMENT, ignoreCase = true) || arg.equals(TRAY_START_ARGUMENT, ignoreCase = true)
