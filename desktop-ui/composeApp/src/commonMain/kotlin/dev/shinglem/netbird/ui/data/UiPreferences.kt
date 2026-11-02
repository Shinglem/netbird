package dev.shinglem.netbird.ui.data

import dev.shinglem.netbird.ui.theme.ThemeMode
import dev.shinglem.netbird.ui.theme.ThemeStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面偏好：主题、明暗、登录自启的期望值。
 *
 * 每次改动都整份交给 [DesktopConfigStore]，由它只把与默认值不同的键写进 `ui.custom.json`；
 * 用户也可以直接编辑那个文件再重启界面。写盘失败时内存值照常生效（界面不该因为存不了盘就卡住），
 * 原因通过 [saveError] 给出去。
 */
class UiPreferences(private val store: DesktopConfigStore) {

    private val initial = store.load()

    private val _theme = MutableStateFlow(ThemeStyle.fromId(initial.theme))
    val theme: StateFlow<ThemeStyle> = _theme.asStateFlow()

    private val _mode = MutableStateFlow(ThemeMode.fromId(initial.themeMode))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _autostart = MutableStateFlow(initial.autostart)
    val autostart: StateFlow<Boolean?> = _autostart.asStateFlow()

    private val _closeToTray = MutableStateFlow(initial.closeToTray)
    val closeToTray: StateFlow<Boolean> = _closeToTray.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    fun setTheme(style: ThemeStyle) {
        if (_theme.value == style) return
        _theme.value = style
        persist()
    }

    fun setMode(mode: ThemeMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        persist()
    }

    fun setAutostart(enabled: Boolean) {
        if (_autostart.value == enabled) return
        _autostart.value = enabled
        persist()
    }

    fun setCloseToTray(enabled: Boolean) {
        if (_closeToTray.value == enabled) return
        _closeToTray.value = enabled
        persist()
    }

    private fun persist() {
        val config = DesktopConfig(
            theme = _theme.value.id,
            themeMode = _mode.value.id,
            autostart = _autostart.value,
            closeToTray = _closeToTray.value,
        )
        runCatching { store.save(config) }.onSuccess {
            _saveError.value = null
        }.onFailure { failure ->
            _saveError.value = "写入 ui.custom.json 失败：${failure.message ?: failure}"
        }
    }
}
