package dev.shinglem.netbird.ui.data

import dev.shinglem.netbird.ui.theme.ThemeMode
import dev.shinglem.netbird.ui.theme.ThemeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DesktopConfigTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `config round trips through json`() {
        val original = DesktopConfig(
            theme = ThemeStyle.OCEAN.id,
            themeMode = ThemeMode.DARK.id,
            autostart = true,
        )

        val decoded = json.decodeFromString<DesktopConfig>(json.encodeToString(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `unknown keys do not fail the parse`() {
        // 降级回旧版本时文件里可能多出新版本的键，不该因此丢掉整份配置。
        val decoded = json.decodeFromString<DesktopConfig>(
            """{"theme":"forest","somethingNew":42}""",
        )

        assertEquals(ThemeStyle.FOREST.id, decoded.theme)
        assertEquals(DesktopConfig().themeMode, decoded.themeMode)
        assertNull(decoded.autostart, "没写过就留空，交给 reconcile 用系统现状补")
    }

    @Test
    fun `missing autostart is filled from the system state`() {
        val store = FakeStore(DesktopConfig(autostart = null))
        val preferences = UiPreferences(store)
        val controller = FakeAutostart(AutostartState.Enabled("""C:\app\netbird-multi-ui.exe"""))

        reconcileAutostart(preferences, controller)

        assertEquals(true, preferences.autostart.value)
        assertEquals(true, store.config.autostart, "系统现状要写回文件")
        assertTrue(controller.calls.isEmpty(), "第一次只读，不改系统")
    }

    @Test
    fun `configured autostart wins over the system state`() {
        val store = FakeStore(DesktopConfig(autostart = false))
        val preferences = UiPreferences(store)
        val controller = FakeAutostart(AutostartState.Enabled("""C:\app\netbird-multi-ui.exe"""))

        reconcileAutostart(preferences, controller)

        assertEquals(listOf("disable"), controller.calls)
        assertEquals(false, preferences.autostart.value)
    }

    @Test
    fun `development mode leaves everything alone`() {
        val store = FakeStore(DesktopConfig())
        val preferences = UiPreferences(store)
        val controller = FakeAutostart(AutostartState.Unsupported)

        reconcileAutostart(preferences, controller)

        assertNull(preferences.autostart.value, "开发态没有可对齐的系统项，别凭空写 false")
        assertTrue(controller.calls.isEmpty())
    }

    @Test
    fun `appearance changes are persisted immediately`() {
        val store = FakeStore()
        val preferences = UiPreferences(store)

        preferences.setTheme(ThemeStyle.ROSE)
        preferences.setMode(ThemeMode.DARK)

        assertEquals(ThemeStyle.ROSE.id, store.config.theme)
        assertEquals(ThemeMode.DARK.id, store.config.themeMode)
    }

    @Test
    fun `close to tray is on by default and persisted when turned off`() {
        val store = FakeStore()
        val preferences = UiPreferences(store)

        assertEquals(true, preferences.closeToTray.value, "默认关闭窗口只收进托盘")
        preferences.setCloseToTray(false)

        assertEquals(false, store.config.closeToTray)
        assertEquals(false, UiPreferences(store).closeToTray.value, "重新加载后仍然生效")
    }

    @Test
    fun `a failing store keeps the in-memory value and surfaces the error`() {
        val preferences = UiPreferences(
            object : DesktopConfigStore {
                override fun load() = DesktopConfig()

                override fun save(config: DesktopConfig) = error("磁盘只读")
            },
        )

        preferences.setTheme(ThemeStyle.SLATE)

        assertEquals(ThemeStyle.SLATE, preferences.theme.value, "界面照常生效")
        assertTrue(
            preferences.saveError.value?.contains("磁盘只读") == true,
            "写盘失败要能提示出来：${preferences.saveError.value}",
        )
    }

    private class FakeStore(var config: DesktopConfig = DesktopConfig()) : DesktopConfigStore {
        override fun load(): DesktopConfig = config

        override fun save(config: DesktopConfig) {
            this.config = config
        }
    }

    private class FakeAutostart(private var current: AutostartState) : Autostart {
        val calls = mutableListOf<String>()

        override fun state(): AutostartState = current

        override fun enable(): AutostartState {
            calls += "enable"
            current = AutostartState.Enabled("""C:\app\netbird-multi-ui.exe""")
            return current
        }

        override fun disable(): AutostartState {
            calls += "disable"
            current = AutostartState.Disabled
            return current
        }
    }
}
