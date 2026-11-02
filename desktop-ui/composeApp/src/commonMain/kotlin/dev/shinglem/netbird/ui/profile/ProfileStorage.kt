package dev.shinglem.netbird.ui.profile

/**
 * 配置目录的读写能力。
 *
 * common 源集没有 `java.io`，桌面端用 [java.io.File] 实现：这样数据层可以直接单测（注入内存实现），
 * UI 也不需要知道路径细节。
 */
interface ProfileStorage {
    fun exists(path: String): Boolean

    fun readText(path: String): String?

    /** 覆盖写，父目录自动创建；UTF-8 无 BOM（daemon 拒绝带 BOM 的配置）。 */
    fun writeText(path: String, text: String)

    /** 直接子目录名（不含路径）。 */
    fun listDirs(path: String): List<String>

    /** 直接子文件名（不含路径）。 */
    fun listFiles(path: String): List<String>

    fun mkdirs(path: String)

    fun deleteDir(path: String)

    /** 端口当前是否可绑定（用于给新配置挑一个网关端口）。 */
    fun isPortFree(port: Int): Boolean
}
