package dev.shinglem.netbird.ui.app

import dev.shinglem.netbird.ui.data.DesktopConfig
import dev.shinglem.netbird.ui.theme.ThemeMode
import dev.shinglem.netbird.ui.theme.ThemeStyle
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class JsonDesktopConfigStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("netbird-ui-").toFile()

    private fun objectAt(file: File) =
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject

    @Test
    fun `defaults file is materialised with every editable key`() {
        val dir = tempDir()
        try {
            val uiDir = File(dir, "ui")
            JsonDesktopConfigStore(uiDir).ensureDefaults()

            val defaults = objectAt(File(uiDir, "ui.json"))
            assertEquals("\"material\"", defaults.getValue("theme").toString())
            assertEquals("\"system\"", defaults.getValue("themeMode").toString())
            assertFalse(File(uiDir, "ui.custom.json").exists(), "没有人改过，不该有覆盖层")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `saving writes only the keys that differ from the defaults`() {
        val dir = tempDir()
        try {
            val uiDir = File(dir, "ui")
            val store = JsonDesktopConfigStore(uiDir)
            store.ensureDefaults()

            store.save(DesktopConfig(theme = ThemeStyle.OCEAN.id))

            val patch = objectAt(File(uiDir, "ui.custom.json"))
            assertEquals(setOf("theme"), patch.keys, "默认值相同的键不该落进覆盖层")
            assertEquals("\"ocean\"", patch.getValue("theme").toString())
            assertEquals(ThemeStyle.OCEAN.id, store.load().theme)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `updating the defaults keeps the user override`() {
        val dir = tempDir()
        try {
            val uiDir = File(dir, "ui")
            val store = JsonDesktopConfigStore(uiDir)
            store.ensureDefaults()
            store.save(DesktopConfig(theme = ThemeStyle.SUNSET.id, autostart = true))

            // 新版本换了默认值：用户没碰过的字段随新默认，改过的仍然保留。
            File(uiDir, "ui.json").writeText(
                """{"theme":"forest","themeMode":"dark"}""",
                Charsets.UTF_8,
            )

            val loaded = store.load()
            assertEquals(ThemeStyle.SUNSET.id, loaded.theme, "用户改过的主题不该被新默认值盖掉")
            assertEquals(ThemeMode.DARK.id, loaded.themeMode, "没改过的字段跟随新默认值")
            assertEquals(true, loaded.autostart)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `going back to the defaults removes the override file`() {
        val dir = tempDir()
        try {
            val uiDir = File(dir, "ui")
            val store = JsonDesktopConfigStore(uiDir)
            store.ensureDefaults()
            store.save(DesktopConfig(theme = ThemeStyle.ROSE.id))
            assertTrue(File(uiDir, "ui.custom.json").isFile)

            store.save(DesktopConfig())

            assertFalse(
                File(uiDir, "ui.custom.json").exists(),
                "全部回到默认值后覆盖层应当消失，让以后的新默认值生效",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing files mean defaults`() {
        val dir = tempDir()
        try {
            val loaded = JsonDesktopConfigStore(File(dir, "ui")).load()

            assertEquals(DesktopConfig(), loaded)
            assertNull(loaded.autostart, "没写过自启时不替用户做决定")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `broken override is backed up instead of blocking startup`() {
        val dir = tempDir()
        try {
            val uiDir = File(dir, "ui").apply { mkdirs() }
            val store = JsonDesktopConfigStore(uiDir)
            store.ensureDefaults()
            File(uiDir, "ui.custom.json").writeText("""{"theme": """, Charsets.UTF_8)

            val loaded = store.load()

            assertEquals(DesktopConfig(), loaded, "坏掉的覆盖层按默认值处理")
            assertTrue(File(uiDir, "ui.custom.json.corrupt").isFile, "原文件要留一份以便排查")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `no temp file is left behind`() {
        val dir = tempDir()
        try {
            val uiDir = File(dir, "ui")
            val store = JsonDesktopConfigStore(uiDir)
            store.ensureDefaults()
            store.save(DesktopConfig(theme = ThemeStyle.FOREST.id))
            store.save(DesktopConfig(theme = ThemeStyle.SLATE.id))

            assertTrue(
                (File(uiDir, "ui.json").parentFile?.listFiles().orEmpty()).none { it.name.endsWith(".tmp") },
                "原子替换后不该留 .tmp",
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}