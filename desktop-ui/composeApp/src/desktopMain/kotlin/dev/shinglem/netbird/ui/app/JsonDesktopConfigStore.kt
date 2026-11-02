package dev.shinglem.netbird.ui.app

import dev.shinglem.netbird.ui.data.DesktopConfig
import dev.shinglem.netbird.ui.data.DesktopConfigStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `ui\` 下的两层配置：
 *
 * - `ui.json` —— **默认值**，随版本发布并被覆盖。用户手改这里没有意义，启动时会被重写。
 * - `ui.custom.json` —— **用户覆盖**，只存与默认值不同的键。升级替换整个目录后仍然生效。
 *
 * 读 = 默认值 ⊕ 覆盖层；写 = 只把与默认值不同的键落进覆盖层。
 * 因此新版本新增的字段、改掉的默认值，对没碰过的用户依然生效。
 *
 * 落盘约束：写临时文件再原子替换；坏文件备份成 `*.corrupt` 后按默认值启动；
 * 写失败**抛出**，由调用方提示，而不是"提示已保存却没有文件"。
 */
class JsonDesktopConfigStore(private val dir: File) : DesktopConfigStore {

    private val defaultFile = File(dir, DEFAULT_FILE)
    private val customFile = File(dir, CUSTOM_FILE)

    override fun load(): DesktopConfig {
        val defaults = readObject(defaultFile)
        val custom = readObject(customFile)
        if (custom == null && defaults == null) return DesktopConfig()

        val merged = JsonObject((defaults ?: JsonObject(emptyMap())) + (custom ?: JsonObject(emptyMap())))
        return runCatching { json.decodeFromString<DesktopConfig>(merged.toString()) }.getOrElse { failure ->
            backup(customFile.takeIf { it.isFile } ?: defaultFile, failure)
            DesktopConfig()
        }
    }

    override fun save(config: DesktopConfig) {
        val full = json.encodeToJsonElement(DesktopConfig.serializer(), config).jsonObject
        val defaults = readObject(defaultFile) ?: JsonObject(emptyMap())
        val patch = JsonObject(full.filter { (key, value) -> defaults[key] != value })

        if (patch.isEmpty()) {
            // 全部等于默认值：删掉覆盖层，让以后的默认值变更也能生效。
            customFile.delete()
            return
        }
        writeAtomic(customFile, json.encodeToString(JsonObject.serializer(), patch) + "\n")
    }

    /**
     * 刷新默认值文件，保证它与当前版本一致；用户改动永远不写这里。
     *
     * 每次启动调用。内容没变时不重写，避免无谓的磁盘写入与时间戳变化。
     */
    fun ensureDefaults() {
        val text = json.encodeToString(DesktopConfig.serializer(), DesktopConfig()) + "\n"
        val current = runCatching { defaultFile.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()
        if (current == text) return
        writeAtomic(defaultFile, text)
    }

    private fun readObject(file: File): JsonObject? {
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        // 坏文件备份后当作不存在：不能让一份写坏的覆盖层把界面卡在启动失败上。
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrElse { failure ->
            backup(file, failure)
            null
        }
    }

    private fun writeAtomic(file: File, text: String) {
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        runCatching {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            // 部分网络盘/被占位的文件不支持 ATOMIC_MOVE，退回普通覆盖。
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun backup(file: File, failure: Throwable) {
        val target = File(file.parentFile, "${file.name}.corrupt")
        runCatching { file.copyTo(target, overwrite = true) }
        System.err.println("${file.name} 解析失败，已备份为 ${target.name}，按默认值启动：${failure.message}")
    }

    private companion object {
        const val DEFAULT_FILE = "ui.json"
        const val CUSTOM_FILE = "ui.custom.json"

        /** 人读文件：缩进两格、省略 null。 */
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}