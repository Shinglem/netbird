package dev.shinglem.netbird.ui.app

import dev.shinglem.netbird.ui.data.Autostart
import dev.shinglem.netbird.ui.data.AutostartState
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 登录自启的 Windows 实现：一条计划任务（`HighestAvailable` + `InteractiveToken`）。
 *
 * 与 `scripts/install-autostart-task.ps1` 用同一个任务名，两边谁先装的都认；
 * 差别只在界面里只能装“当前启动器”，开发态因此判为 [AutostartState.Unsupported]。
 *
 * 不能用注册表 Run 键：那条路径不提升权限，daemon 起不来。
 */
object WindowsAutostart : Autostart {
    private const val TASK_NAME = "NetBirdUI"

    override fun state(): AutostartState {
        if (!isWindows()) return AutostartState.Unsupported
        val launcher = Elevation.launcherExe() ?: return AutostartState.Unsupported
        val xml = queryXml() ?: return AutostartState.Disabled
        val command = extractTag(xml, "Command") ?: launcher.absolutePath
        val arguments = extractTag(xml, "Arguments").orEmpty()
        return AutostartState.Enabled(command, silent = isSilentStart(arguments))
    }

    override fun enable(): AutostartState {
        if (!isWindows()) return AutostartState.Unsupported
        val launcher = Elevation.launcherExe() ?: return AutostartState.Unsupported
        val workDir = launcher.absoluteFile.parentFile
            ?: return AutostartState.Failed("启动器所在目录不可用：${launcher.absolutePath}")

        val xml = autostartTaskXml(
            taskName = TASK_NAME,
            command = launcher.absolutePath,
            // 登录时只驻留托盘；否则每次开机都弹一个主窗口。
            arguments = SILENT_START_ARGUMENT,
            workingDirectory = workDir.absolutePath,
            user = currentUser(),
        )

        return runCatching {
            val temp = Files.createTempFile("netbird-ui-task", ".xml").toFile()
            try {
                // schtasks /Create /XML 对编码敏感：必须是带 BOM 的 UTF-16LE。
                Files.write(temp.toPath(), utf16LeWithBom(xml))
                val result = exec("schtasks", "/Create", "/TN", TASK_NAME, "/XML", temp.absolutePath, "/F")
                if (result.exitCode != 0) error(result.message("安装自启动任务失败"))
            } finally {
                temp.delete()
            }
            AutostartState.Enabled(launcher.absolutePath)
        }.getOrElse { AutostartState.Failed(it.message ?: it.toString()) }
    }

    override fun disable(): AutostartState {
        if (!isWindows()) return AutostartState.Unsupported
        val result = exec("schtasks", "/Delete", "/TN", TASK_NAME, "/F")
        if (result.exitCode == 0) return AutostartState.Disabled
        // 任务本来就不存在时 schtasks 也返回非零，这不是失败。
        if (!taskExists()) return AutostartState.Disabled
        return AutostartState.Failed(result.message("删除自启动任务失败"))
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")

    private fun taskExists(): Boolean = exec("schtasks", "/Query", "/TN", TASK_NAME).exitCode == 0

    private fun queryXml(): String? {
        val result = exec("schtasks", "/Query", "/TN", TASK_NAME, "/XML")
        return if (result.exitCode == 0) result.output else null
    }

    private fun extractTag(xml: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun currentUser(): String {
        val domain = System.getenv("USERDOMAIN").orEmpty().ifBlank { System.getenv("COMPUTERNAME").orEmpty() }
        val user = System.getenv("USERNAME").orEmpty()
        return if (domain.isBlank()) user else "$domain\\$user"
    }
}

/**
 * 任务定义。字段取值理由与安装脚本一致：
 * - `HighestAvailable` 而不是 `RequireHighest`：非管理员用户装不上后者；
 * - `InteractiveToken`：S4U 会落到没有 UI 的会话，托盘图标加不上；
 * - `ExecutionTimeLimit=PT0S`：默认 72 小时到点会强杀界面，连带它管辖的 daemon。
 */
internal fun autostartTaskXml(
    taskName: String,
    command: String,
    arguments: String,
    workingDirectory: String,
    user: String,
): String = """
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>NetBird UI · 登录时以最高权限启动（无 UAC）；每个配置的 daemon 由界面管辖</Description>
    <URI>\$taskName</URI>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <UserId>${xmlEscape(user)}</UserId>
      <Delay>PT10S</Delay>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>${xmlEscape(user)}</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>${xmlEscape(command)}</Command>
      <Arguments>${xmlEscape(arguments)}</Arguments>
      <WorkingDirectory>${xmlEscape(workingDirectory)}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
""".trimIndent()

internal fun utf16LeWithBom(text: String): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private const val EXEC_TIMEOUT_SECONDS = 30L

private class ExecResult(val exitCode: Int, val output: String) {
    fun message(prefix: String): String =
        if (output.isBlank()) "$prefix（schtasks exit $exitCode）" else "$prefix：$output"
}

private fun exec(vararg command: String): ExecResult {
    val process = runCatching { ProcessBuilder(*command).redirectErrorStream(true).start() }
        .getOrElse { return ExecResult(-1, it.message ?: it.toString()) }
    val bytes = runCatching { process.inputStream.use { it.readBytes() } }.getOrDefault(ByteArray(0))
    if (!process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return ExecResult(-1, "命令超时：${command.joinToString(" ")}")
    }
    return ExecResult(process.exitValue(), decodeConsole(bytes).trim())
}

/**
 * schtasks 的 stdout 走控制台代码页（中文系统上是 GBK），而不是 Java 的默认字符集。
 *
 * 这里用 GBK 解码；即使系统不是中文，XML 标签与路径里的 ASCII 部分仍然正确 ——
 * 调用方只从输出里取 `<Command>` 标签。
 */
private fun decodeConsole(bytes: ByteArray): String =
    runCatching { String(bytes, Charset.forName("GBK")) }.getOrElse { String(bytes) }
