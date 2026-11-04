package dev.shinglem.netbird.ui.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupArgsTest {

    @Test
    fun `silent and tray flags are recognised in any case`() {
        assertTrue(isSilentStart(listOf("--silent")))
        assertTrue(isSilentStart(listOf("--tray")))
        assertTrue(isSilentStart(listOf("--SILENT")))
        assertTrue(isSilentStart(listOf("--verbose", "--silent")))
    }

    @Test
    fun `unrelated arguments do not make it silent`() {
        assertFalse(isSilentStart(emptyList()))
        assertFalse(isSilentStart(listOf("--verbose", """jpackage.app-path=C:\tools\netbird-multi-ui.exe""")))
        assertFalse(isSilentStart(listOf("--silent-profile")), "前缀相同但不是同一个标志")
    }

    @Test
    fun `the command line stored in the scheduled task is parsed too`() {
        assertTrue(isSilentStart("--silent"))
        assertTrue(isSilentStart(" --TRAY "))
        assertFalse(isSilentStart(""))
        assertFalse(isSilentStart("--verbose"))
    }
}
