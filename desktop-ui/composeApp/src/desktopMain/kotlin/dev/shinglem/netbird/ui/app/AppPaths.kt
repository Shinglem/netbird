package dev.shinglem.netbird.ui.app

import dev.shinglem.netbird.ui.api.netbirdJson
import java.io.File
import kotlinx.serialization.Serializable

/** 桌面端用到的三处路径：自己的数据目录、要拉起的二进制、profiles 的父目录。 */
data class AppPaths(
    val home: File,
    val binary: File,
    val root: File,
) {
    /** 界面配置目录。默认值在 `ui.json`（随版本更新），用户改动只写 `ui.custom.json`。 */
    val configDir: File get() = File(home, UI_CONFIG_DIR)

    val configFile: File get() = File(configDir, UI_CONFIG_DEFAULT_FILE)

    val customConfigFile: File get() = File(configDir, UI_CONFIG_CUSTOM_FILE)

    /** 运行态：每个配置的期望运行状态，由界面自己读写。 */
    val runtimeStateFile: File get() = File(configDir, UI_STATE_FILE)
}

private const val UI_CONFIG_DIR = "ui"
private const val UI_CONFIG_DEFAULT_FILE = "ui.json"
private const val UI_CONFIG_CUSTOM_FILE = "ui.custom.json"
private const val UI_STATE_FILE = "state.json"

/**
 * 路径解析顺序（先命令/配置，后同目录，最后回落到 <home>）：
 *
 * | 项 | 1 | 2 | 3 | 4 |
 * |---|---|---|---|---|
 * | home | `NETBIRD_UI_HOME` | 启动器所在目录（打包态） | `%APPDATA%\netbird-ui`（开发态） | |
 * | binary | `NETBIRD_UI_BINARY` | `config.json.binary` | `<root>\netbird-multi.exe` | `<home>\bin\netbird-multi.exe` |
 * | root | `NETBIRD_UI_ROOT` | `config.json.root` | 启动器同目录（其下有 `profiles\`） | `<home>` |
 *
 * **便携版**：`home` 取启动器所在目录，`ui\ui.json` 与 `config.json` 都是相对它的路径，
 * 整个目录搬走即可继续用。开发态进程路径是 JDK 的 `java.exe`，那里不能当作家，
 * 回落到 `%APPDATA%\netbird-ui`。
 *
 * `config.json` 里的 `binary` / `root` 可以写相对路径，一律相对 `home` 解析 ——
 * 写死绝对路径的配置一换机器就失效。
 *
 * 「启动器同目录」是 release-test 的自包含约定：`netbird-multi-ui.exe`、`profiles\` 与
 * `netbird-multi.exe` 摆在一起，双击 exe 就该用这一套，而不是去猜别处的部署根。
 * 不允许把某个人的部署根写进代码：要接管既有部署，用 `NETBIRD_UI_ROOT` 或 `config.json`。
 */
object AppPathsResolver {

    fun resolve(
        env: Map<String, String> = System.getenv(),
        launcherPath: String? = System.getProperty("jpackage.app-path") ?: currentProcessPath(),
    ): AppPaths {
        val home = resolveHome(env, launcherPath)
        home.mkdirs()

        val config = readConfig(File(home, DEPLOY_CONFIG_FILE))
        val root = listOfNotNull(
            env["NETBIRD_UI_ROOT"],
            config?.root,
            launcherDir(launcherPath)?.absolutePath,
        ).firstOrNull { it.isNotBlank() }?.let { resolvePath(it, home) } ?: home

        val binary = listOfNotNull(
            env["NETBIRD_UI_BINARY"],
            config?.binary,
            File(root, BINARY_NAME).absolutePath,
            File(home, "bin${File.separator}$BINARY_NAME").absolutePath,
        ).map { resolvePath(it, home) }.firstOrNull { it.isFile } ?: File(root, BINARY_NAME)

        return AppPaths(home = home, binary = binary, root = root)
    }

    private fun resolveHome(env: Map<String, String>, launcherPath: String?): File {
        env["NETBIRD_UI_HOME"]?.takeIf { it.isNotBlank() }
            ?.let { return canonical(File(it).absoluteFile) }
        portableHome(launcherPath)?.let { return it }
        val appData = env["APPDATA"]?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home").orEmpty()
        return canonical(File(appData, HOME_DIR_NAME))
    }

    /** 打包态：启动器所在目录就是「家」。开发态进程是 `java.exe`，它的 bin 目录不是。 */
    private fun portableHome(launcherPath: String?): File? {
        val path = launcherPath?.takeIf { it.isNotBlank() } ?: return null
        val exe = runCatching { File(path).absoluteFile }.getOrNull() ?: return null
        if (exe.name.equals("java.exe", ignoreCase = true) || exe.name.equals("java", ignoreCase = true)) {
            return null
        }
        return exe.parentFile?.let(::canonical)
    }

    /** 绝对路径原样返回；相对路径以 [home] 为唯一基准。 */
    private fun resolvePath(configured: String, home: File): File {
        val file = File(configured)
        return if (file.isAbsolute) file else File(home, configured)
    }

    private fun canonical(file: File): File =
        runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

    private fun readConfig(file: File): AppConfig? {
        val text = runCatching { file.takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { netbirdJson.decodeFromString<AppConfig>(text) }.getOrNull()
    }

    /**
     * 启动器所在目录，且必须有 `profiles\` —— 只看目录不够：`./gradlew run` 时进程路径是
     * JDK 的 `bin\java.exe`，那里没有 profiles，必须让它落到后面的候选。
     */
    private fun launcherDir(launcherPath: String?): File? {
        val path = launcherPath?.takeIf { it.isNotBlank() } ?: return null
        val dir = runCatching { File(path).absoluteFile.parentFile }.getOrNull() ?: return null
        return dir.takeIf { File(it, PROFILES_DIR).isDirectory }
    }

    private fun currentProcessPath(): String? =
        runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull()

    @Serializable
    private data class AppConfig(val binary: String = "", val root: String = "")

    private const val HOME_DIR_NAME = "netbird-ui"
    private const val DEPLOY_CONFIG_FILE = "config.json"
    private const val PROFILES_DIR = "profiles"
    private const val BINARY_NAME = "netbird-multi.exe"
}
