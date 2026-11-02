package dev.shinglem.netbird.ui.daemon

import dev.shinglem.netbird.ui.profile.Profile
import dev.shinglem.netbird.ui.profile.ProfileLayout
import io.ktor.http.Url
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把配置翻译成一条 daemon 启动规格（`06-本地后端` 那套做法的 NetBird 版）。
 *
 * 纯计算，不碰文件系统：命令行、环境变量、配置内容都能直接单测。
 */
object DaemonPlan {

    /**
     * 只有这些键从父进程透传。`NB_*` 一律不透传 —— 本机可能已经有一个按环境变量配置好的
     * 实例，继承过来会让本配置悄悄用上别人的状态目录或 DNS 前缀。
     */
    private val PASS_THROUGH = listOf(
        "PATH", "PATHEXT", "COMSPEC", "SYSTEMROOT", "WINDIR", "SYSTEMDRIVE",
        "TEMP", "TMP", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "USERNAME", "USERDOMAIN",
        "COMPUTERNAME", "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PROGRAMFILES",
        "PROGRAMFILES(X86)", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS",
    )

    /**
     * `service run` 的完整命令行。
     *
     * - `--config` / `--daemon-addr`：每个配置独立的配置文件与管道名，避免抢
     *   `npipe://netbird`（那是官方安装器的实例）。
     * - `--log-file console` + 文件：控制台给 UI 面板，文件留给事后排查。
     * - `--enable-json-socket --json-socket tcp://127.0.0.1:<port>`：UI 唯一可用的通道
     *   （Kotlin/JVM 没有 gRPC-over-named-pipe 传输）。
     * - `--disable-profiles`：该 daemon 只服务这一个配置，别让页面上再出现 profile 切换。
     */
    fun spec(
        profile: Profile,
        layout: ProfileLayout,
        exe: String,
        osEnv: Map<String, String>,
        setupKey: String? = null,
    ): DaemonLaunchSpec {
        val args = buildList {
            add("service")
            add("run")
            add("--config"); add(layout.configFile)
            add("--daemon-addr"); add(profile.daemonAddr)
            add("--log-file"); add("console")
            add("--log-file"); add(layout.logFile)
            add("--log-level"); add(profile.logLevel)
            add("--enable-json-socket")
            add("--json-socket"); add("tcp://127.0.0.1:${profile.gatewayPort}")
            add("--disable-profiles")
        }

        val env = buildMap {
            PASS_THROUGH.forEach { key -> osEnv[key]?.let { put(key, it) } }
            // 这两个必须走环境变量：daemon 读配置之前就要用（见 patchdoc 0002）。
            put("NB_STATE_DIR", layout.stateDir)
            put("NB_NRPT_KEY_PREFIX", profile.nrptPrefix)
            // setup key 只用于首次注册；走环境变量而不是命令行，免得出现在进程列表里。
            setupKey?.takeIf { it.isNotBlank() }?.let { put("NB_SETUP_KEY", it) }
        }

        return DaemonLaunchSpec(
            exe = exe,
            args = args,
            env = env,
            workDir = parentOf(exe),
        )
    }

    /**
     * 首次启动时写入 `config/default.json`。只在文件不存在时写：已有配置里有私钥，
     * 覆盖会把这个配置的注册状态抹掉。
     *
     * `ManagementURL` 在 Go 侧是 `*url.URL`，JSON 是**对象**而不是字符串 —— 写成字符串
     * daemon 会解析失败。
     */
    fun configJson(profile: Profile): String {
        val config = buildJsonObject {
            put("Name", profile.name)
            if (profile.managementUrl.isNotBlank()) put("ManagementURL", urlObject(profile.managementUrl))
            put("WgIface", profile.iface)
            put("WgPort", profile.wgPort)
            put("WintunGUID", profile.guid)
            put("NRPTKeyPrefix", profile.nrptPrefix)
            put("DisableDNS", false)
            put("DisableNotifications", true)
            // profilemanager 会给缺省值补 true（见 sshServerEnabled 的 nil-means-on 注释）。
            // 这个实例不提供 SSH 服务，显式写 false：否则 daemon 把注销当成特权操作，
            // 而回环网关验证不了调用方身份，`Logout` 会被 PermissionDenied 拒绝。
            put("ServerSSHAllowed", false)
            put("NetworkMonitor", true)
            put("MTU", 1280)
        }
        return PRETTY.encodeToString(JsonObject.serializer(), config)
    }

    /** 与既有部署的 `instance.json` 同格式，便于脚本与手工排查对照。 */
    fun instanceJson(profile: Profile): String =
        PRETTY.encodeToString(profile) + "\n"

    /**
     * daemon 侧的 profile handle：配置文件名的去扩展名部分（`config/default.json` → `default`）。
     *
     * `GetConfig` / `SetConfig` 不接受空 handle（`resolve profile: profile handle is empty`），
     * 而每个实例只有一份配置，所以 handle 由文件名唯一确定。
     */
    fun profileHandle(configFile: String): String {
        val name = configFile.substringAfterLast('/').substringAfterLast('\\')
        return name.removeSuffix(".json").ifBlank { "default" }
    }

    /** `https://host:port/path` → Go `url.URL` 的 JSON 投影。 */
    private fun urlObject(raw: String): JsonObject {
        val url = Url(raw)
        val host = if (url.port > 0) "${url.host}:${url.port}" else url.host
        return buildJsonObject {
            put("Scheme", url.protocol.name)
            put("Host", host)
            put("Path", url.encodedPath.trimEnd('/').takeIf { it != "/" } ?: "")
        }
    }

    fun parentOf(path: String): String {
        val normalized = path.replace('\\', '/')
        val index = normalized.lastIndexOf('/')
        return if (index <= 0) normalized else normalized.substring(0, index)
    }

    private val PRETTY = kotlinx.serialization.json.Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }
}
