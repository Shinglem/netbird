package dev.shinglem.netbird.ui.profile

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket

/** `java.io.File` 版本的配置目录读写；配置一律 UTF-8 无 BOM（daemon 拒绝带 BOM 的 JSON）。 */
class DesktopProfileStorage : ProfileStorage {

    override fun exists(path: String): Boolean = path.isNotBlank() && File(path).exists()

    override fun readText(path: String): String? = runCatching {
        File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrNull()

    override fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    override fun listDirs(path: String): List<String> =
        File(path).listFiles { file -> file.isDirectory }?.map { it.name }?.sorted().orEmpty()

    override fun listFiles(path: String): List<String> =
        File(path).listFiles { file -> file.isFile }?.map { it.name }?.sorted().orEmpty()

    override fun mkdirs(path: String) {
        if (path.isNotBlank()) File(path).mkdirs()
    }

    override fun deleteDir(path: String) {
        if (path.isNotBlank()) File(path).deleteRecursively()
    }

    /**
     * 只判断本机回环端口能不能绑：用来给新配置挑网关端口。
     * 这不是"扫端口发现实例" —— 那件事由 `profiles\*\instance.json` 负责。
     */
    override fun isPortFree(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrDefault(false)
}
