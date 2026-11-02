package dev.shinglem.netbird.ui.data

import dev.shinglem.netbird.ui.api.netbirdJson
import dev.shinglem.netbird.ui.daemon.DaemonLauncher
import dev.shinglem.netbird.ui.daemon.DaemonPlan
import dev.shinglem.netbird.ui.daemon.ProfileSupervisor
import dev.shinglem.netbird.ui.profile.Profile
import dev.shinglem.netbird.ui.profile.ProfileLayout
import dev.shinglem.netbird.ui.profile.ProfileStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * 配置清单与它们的 daemon 进程。
 *
 * 配置来源是磁盘而不是内存：`<root>\profiles\<name>\instance.json`（与多实例部署同一套布局），
 * 所以既有的 `profiles\*\` 目录可以直接被接管，手工建好的配置也能立刻出现在标签页里。
 */
class ProfilesController(
    private val storage: ProfileStorage,
    private val launcher: DaemonLauncher,
    private val exe: String,
    private val root: String,
    private val osEnv: Map<String, String>,
    private val scope: CoroutineScope,
    /** 期望运行状态：界面每次启停都会写它，下次启动据此恢复。 */
    private val runtime: UiRuntimeStore = InMemoryRuntimeStore(),
) {

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _autostart = MutableStateFlow(runtime.load().running)

    /** 每个配置的「随界面启动」开关；没有记录的按开启处理（新配置就是这种状态）。 */
    val autostart: StateFlow<Map<String, Boolean>> = _autostart.asStateFlow()

    private var supervisors: MutableMap<String, ProfileSupervisor> = mutableMapOf()

    val profilesRoot: String get() = ProfileLayout.join(root, PROFILES_DIR)

    fun supervisor(name: String): ProfileSupervisor? = supervisors[name]

    /** 重新扫描磁盘，并复用未变化的 supervisor（避免把正在跑的 daemon 弄丢）。 */
    fun reload() {
        val discovered = discover()
        _profiles.value = discovered
        val next = mutableMapOf<String, ProfileSupervisor>()
        discovered.forEach { profile ->
            val existing = supervisors[profile.name]
            next[profile.name] = if (existing != null && existing.profile == profile) {
                existing
            } else {
                existing?.shutdown()
                ProfileSupervisor(
                    profile = profile,
                    layout = ProfileLayout(profilesRoot, profile.name),
                    exe = exe,
                    launcher = launcher,
                    storage = storage,
                    osEnv = osEnv,
                    scope = scope,
                )
            }
        }
        supervisors.forEach { (name, sup) -> if (name !in next) sup.shutdown() }
        supervisors = next
        pruneAutostart(discovered)
    }

    fun createProfile(name: String, managementUrl: String): Result<Profile> {
        val trimmed = name.trim()
        if (!Profile.isValidName(trimmed)) return Result.failure(IllegalArgumentException(INVALID_NAME))
        if (_profiles.value.any { it.name == trimmed }) {
            return Result.failure(IllegalArgumentException(NAME_TAKEN))
        }
        val taken = _profiles.value.map { it.gatewayPort }.toSet()
        val profile = Profile.create(
            name = trimmed,
            managementUrl = managementUrl.trim(),
            wgPort = nextWireGuardPort(),
            gatewayPort = allocateGatewayPort(taken),
        )
        return runCatching {
            val layout = ProfileLayout(profilesRoot, profile.name)
            storage.mkdirs(layout.stateDir)
            storage.writeText(layout.instanceFile, DaemonPlan.instanceJson(profile))
            if (!storage.exists(layout.configFile)) {
                storage.writeText(layout.configFile, DaemonPlan.configJson(profile))
            }
            reload()
            profile
        }
    }

    fun removeProfile(name: String): Result<Unit> {
        val supervisor = supervisors[name] ?: return Result.failure(IllegalArgumentException(NOT_FOUND))
        return runCatching {
            supervisor.shutdown()
            storage.deleteDir(ProfileLayout(profilesRoot, name).dir)
            reload()
        }
    }

    fun startAll() {
        _profiles.value.forEach { supervisors[it.name]?.start() }
        recordAutostart(_profiles.value.associate { it.name to true })
    }

    fun stopAll() {
        _profiles.value.forEach { supervisors[it.name]?.stop() }
        recordAutostart(_profiles.value.associate { it.name to false })
    }

    /** 单个配置的启停：同时更新「随界面启动」记录，所见即所得。 */
    fun start(name: String) {
        supervisors[name]?.start()
        recordAutostart(mapOf(name to true))
    }

    fun stop(name: String) {
        supervisors[name]?.stop()
        recordAutostart(mapOf(name to false))
    }

    fun isAutostart(name: String): Boolean = _autostart.value[name] ?: true

    fun setAutostart(name: String, enabled: Boolean) = recordAutostart(mapOf(name to enabled))

    /** 按记录挑出应当在界面启动时拉起的配置；纯计算，便于单测。 */
    fun autostartNames(): List<String> =
        _profiles.value.filter { isAutostart(it.name) }.map { it.name }

    /**
     * 界面启动时按记录拉起 daemon。
     *
     * 与「手动点全部启动」分开：这条只在进程启动时跑一次，失败不去重试 ——
     * daemon 的失败原因（缺少 wintun、非管理员）不会自愈。
     */
    fun startAutostart() {
        autostartNames().forEach { supervisors[it]?.start() }
    }

    /** 退出应用：停掉本进程拉起的每一个 daemon；**不改**记录（记录的是下次要不要起）。 */
    fun shutdown() {
        supervisors.values.forEach { it.shutdown() }
        supervisors = mutableMapOf()
    }

    private fun recordAutostart(patch: Map<String, Boolean>) {
        val next = _autostart.value + patch
        if (next == _autostart.value) return
        _autostart.value = next
        persistRuntime()
    }

    /** 配置被删掉后，记录里不该继续留着它的名字。 */
    private fun pruneAutostart(discovered: List<Profile>) {
        val names = discovered.map { it.name }.toSet()
        val kept = _autostart.value.filterKeys { it in names }
        if (kept.size == _autostart.value.size) return
        _autostart.value = kept
        persistRuntime()
    }

    private fun persistRuntime() {
        runCatching { runtime.save(UiRuntimeState(_autostart.value)) }
    }

    private fun discover(): List<Profile> {
        val names = storage.listDirs(profilesRoot).sorted()
        val usedPorts = mutableSetOf<Int>()
        val usedWgPorts = mutableSetOf<Int>()
        return names.map { name ->
            val layout = ProfileLayout(profilesRoot, name)
            val stored = readInstance(layout)
            val config = readConfig(layout)
            val profile = merge(name, stored, config, usedWgPorts)
            usedWgPorts += profile.wgPort
            // 端口属于部署配置：写了就用（且不与前面的冲突），没写才顺延分配。
            // 不改写既有 instance.json —— 那是部署脚本的元数据（fqdn / domains 等），桌面端不拥有它。
            val preferred = stored?.gatewayPort ?: 0
            val port = if (preferred > 0 && preferred !in usedPorts) preferred else allocateGatewayPort(usedPorts)
            usedPorts += port
            profile.copy(gatewayPort = port)
        }
    }

    private fun merge(
        name: String,
        stored: StoredProfile?,
        config: StoredConfig?,
        usedWgPorts: Set<Int>,
    ): Profile = Profile(
        name = name,
        iface = stored?.iface?.takeIf { it.isNotBlank() }
            ?: config?.WgIface?.takeIf { it.isNotBlank() }
            ?: "wt-$name",
        wgPort = stored?.wgPort?.takeIf { it > 0 }
            ?: config?.WgPort?.takeIf { it > 0 }
            ?: nextWireGuardPort(usedWgPorts),
        guid = stored?.guid?.takeIf { it.isNotBlank() }
            ?: config?.WintunGUID?.takeIf { it.isNotBlank() }
            ?: "",
        nrptPrefix = stored?.nrptPrefix?.takeIf { it.isNotBlank() }
            ?: config?.NRPTKeyPrefix?.takeIf { it.isNotBlank() }
            ?: "NBAlt-$name-",
        gatewayPort = stored?.gatewayPort ?: 0,
        managementUrl = stored?.managementUrl?.takeIf { it.isNotBlank() }
            ?: config?.ManagementURL?.toUrlString()
            ?: "",
        mode = stored?.mode?.takeIf { it.isNotBlank() } ?: "Source",
        logLevel = stored?.logLevel?.takeIf { it.isNotBlank() } ?: "info",
        created = stored?.created.orEmpty(),
    )

    private fun nextWireGuardPort(used: Set<Int> = _profiles.value.map { it.wgPort }.toSet()): Int {
        var port = FIRST_WIREGUARD_PORT
        while (port in used) port++
        return port
    }

    private fun allocateGatewayPort(taken: Set<Int>): Int {
        var port = FIRST_GATEWAY_PORT
        while (port in taken || !storage.isPortFree(port)) port++
        return port
    }

    private fun readInstance(layout: ProfileLayout): StoredProfile? {
        val text = storage.readText(layout.instanceFile) ?: return null
        return runCatching { netbirdJson.decodeFromString<StoredProfile>(text) }.getOrNull()
    }

    private fun readConfig(layout: ProfileLayout): StoredConfig? {
        val text = storage.readText(layout.configFile) ?: return null
        return runCatching {
            netbirdJson.decodeFromString<StoredConfig>(text)
        }.getOrElse {
            // 配置文件里可能有私钥；解析失败只当"没有配置"，绝不在日志里回显内容。
            null
        }
    }

    private companion object {
        const val PROFILES_DIR = "profiles"
        const val FIRST_WIREGUARD_PORT = 51821
        const val FIRST_GATEWAY_PORT = 8471
        const val INVALID_NAME = "配置名只能是小写字母、数字与连字符（1-32 位）"
        const val NAME_TAKEN = "已存在同名配置"
        const val NOT_FOUND = "配置不存在"
    }
}

/** `instance.json` 的宽松读取：老文件没有桌面端新增的字段。 */
@Serializable
private data class StoredProfile(
    val name: String = "",
    val mode: String = "Source",
    val iface: String = "",
    val wgPort: Int = 0,
    val guid: String = "",
    val nrptPrefix: String = "",
    val gatewayPort: Int = 0,
    val managementUrl: String = "",
    val logLevel: String = "info",
    val created: String = "",
)

/** `config/default.json` 的只读投影（`ManagementURL` 是 Go `url.URL` 的对象形态）。 */
@Serializable
private data class StoredConfig(
    val Name: String = "",
    val WgIface: String = "",
    val WgPort: Int = 0,
    val WintunGUID: String = "",
    val NRPTKeyPrefix: String = "",
    val ManagementURL: StoredUrl? = null,
)

@Serializable
private data class StoredUrl(
    val Scheme: String = "",
    val Host: String = "",
    val Path: String = "",
) {
    fun toUrlString(): String {
        if (Scheme.isBlank() || Host.isBlank()) return ""
        val suffix = if (Path.isBlank()) "" else "/${Path.trimStart('/')}"
        return "$Scheme://$Host$suffix"
    }
}
