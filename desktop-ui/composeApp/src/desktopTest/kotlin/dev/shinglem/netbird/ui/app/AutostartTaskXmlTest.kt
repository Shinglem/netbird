package dev.shinglem.netbird.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutostartTaskXmlTest {

    private val xml = autostartTaskXml(
        taskName = "NetBirdUI",
        command = """C:\app & tools\netbird-multi-ui.exe""",
        arguments = "",
        workingDirectory = """C:\app & tools""",
        user = "WORKGROUP\\alice",
    )

    @Test
    fun keepsTheNoUacFieldsFromTheInstallScript() {
        assertTrue(xml.contains("<RunLevel>HighestAvailable</RunLevel>"), "登录自启必须提权但不要求 RequireHighest")
        assertTrue(xml.contains("<LogonType>InteractiveToken</LogonType>"), "S4U 会落到没有 UI 的会话")
        assertTrue(xml.contains("<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>"), "默认 72 小时会连 daemon 一起杀掉")
        assertTrue(xml.contains("<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>"))
        assertTrue(xml.contains("<URI>\\NetBirdUI</URI>"))
    }

    @Test
    fun escapesPathsAndUserForXml() {
        assertTrue(xml.contains("<Command>C:\\app &amp; tools\\netbird-multi-ui.exe</Command>"))
        assertTrue(xml.contains("<WorkingDirectory>C:\\app &amp; tools</WorkingDirectory>"))
        assertTrue(xml.contains("<UserId>WORKGROUP\\alice</UserId>"))
        assertTrue(!xml.contains(" & tools"), "未转义的 & 会让 schtasks 拒绝整份 XML")
    }

    @Test
    fun encodesUtf16LeWithBom() {
        val bytes = utf16LeWithBom(xml)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xFE, bytes[1].toInt() and 0xFF)
        assertEquals(xml.toByteArray(Charsets.UTF_16LE).size + 2, bytes.size)
    }
}
