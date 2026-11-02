package dev.shinglem.netbird.ui.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 真正的 `runas` 必须人工点 UAC（安全桌面，脚本注入无效），没法在单测里跑。
 * 但 `SHELLEXECUTEINFOW` 的字段偏移、UTF-16 字符串与返回值处理恰恰是最容易写错的地方，
 * 所以用 `open` 走同一条代码路径：能证明结构写对了、调用返回成功。
 */
class WindowsRunAsTest {

    @Test
    fun `shell execute succeeds with a plain open verb`() {
        val exe = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\whoami.exe")
        if (!exe.isFile) return

        val code = WindowsRunAs.run(exe.absolutePath, exe.parentFile.absolutePath, verb = "open")

        assertEquals(0, code, "ShellExecuteExW 应返回成功；非 0 是 GetLastError")
    }
}
