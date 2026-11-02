package dev.shinglem.netbird.ui.app

import dev.shinglem.netbird.ui.data.UiRuntimeState
import dev.shinglem.netbird.ui.data.UiRuntimeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/**
 * `ui\state.json`：记录每个配置的期望运行状态。
 *
 * 与 `ui.json` / `ui.custom.json` 不同，这个文件是**运行态**，程序每次启停都会重写；
 * 升级覆盖 `ui.json` 不影响它。写失败只影响"下次是否自动启动"，不阻塞当前操作。
 */
class JsonUiRuntimeStore(private val file: File) : UiRuntimeStore {

    override fun load(): UiRuntimeState {
        if (!file.isFile) return UiRuntimeState()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return UiRuntimeState()
        return runCatching { json.decodeFromString<UiRuntimeState>(text) }.getOrElse { failure ->
            val backup = File(file.parentFile, "${file.name}.corrupt")
            runCatching { file.copyTo(backup, overwrite = true) }
            System.err.println("${file.name} 解析失败，已备份为 ${backup.name}：${failure.message}")
            UiRuntimeState()
        }
    }

    override fun save(state: UiRuntimeState) {
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(json.encodeToString(state) + "\n", Charsets.UTF_8)
        runCatching {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
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
