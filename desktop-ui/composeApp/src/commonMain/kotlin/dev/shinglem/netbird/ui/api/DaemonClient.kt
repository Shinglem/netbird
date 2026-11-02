package dev.shinglem.netbird.ui.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 平台侧提供引擎（desktop 用 CIO）；common 只关心"有一个配好的 client"。 */
expect fun createHttpClient(): HttpClient

/**
 * daemon JSON 的统一解析配置。
 *
 * `ignoreUnknownKeys`：daemon 升级会加字段，不为了一个字段让整个状态解析失败。
 * `isLenient`：proto3 JSON 把 int64 / uint64 写成**带引号的字符串**（`"bytesRx":"12345"`），
 * 严格模式会直接解析失败。
 */
internal val netbirdJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 两端共用的 client 配置。 */
internal fun HttpClientConfig<*>.netbirdDefaults() {
    install(ContentNegotiation) {
        json(netbirdJson)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 3_000
    }
    // 让上层按状态码自己判断（网关把 gRPC 码映射成 HTTP 码）
    expectSuccess = false
}

/** UI 需要的 daemon 能力；抽成接口是为了让状态机可以在没有真实进程的情况下单测。 */
interface DaemonApi {
    /**
     * `Status`。默认要完整状态：不带 `getFullPeerStatus` 时 daemon 不填 `fullStatus`，
     * peer / relay / DNS / 事件全都拿不到。
     */
    suspend fun status(getFullPeerStatus: Boolean = true): StatusResponse

    /** `Up`；实现内部固定带 `async: true`，否则会阻塞到引擎就绪。 */
    suspend fun up()

    suspend fun down()

    /** `Login`；`setupKey` 为空时走 SSO，返回的 [LoginResponse.needsSSOLogin] 为 true。 */
    suspend fun login(
        setupKey: String = "",
        managementUrl: String = "",
        hostname: String = "",
    ): LoginResponse

    /** `WaitSSOLogin`：阻塞到用户在浏览器完成授权，返回邮箱。调用方要给足超时。 */
    suspend fun waitSSOLogin(userCode: String): String

    suspend fun logout()

    suspend fun networks(): List<NetworkRoute>

    /** `SelectNetworks` / `DeselectNetworks`；`append = true` 表示增量修改。 */
    suspend fun selectNetworks(ids: List<String>, selected: Boolean, all: Boolean = false)

    suspend fun config(): DaemonConfig

    /** `SetConfig` 的部分更新：只提交 [fields] 里的键。 */
    suspend fun setConfig(fields: JsonObject)

    suspend fun features(): Features

    suspend fun logLevel(): String

    suspend fun setLogLevel(level: String)

    fun close()
}

/**
 * daemon 的 HTTP/JSON 网关客户端。
 *
 * 地址形如 `http://127.0.0.1:8471`，对应 daemon 启动参数
 * `--enable-json-socket --json-socket tcp://127.0.0.1:<port>`。
 * **不要改成命名管道**：Kotlin/JVM 没有可用的 gRPC-over-named-pipe 传输。
 *
 * [profileName] 是 daemon 侧的 profile handle。`GetConfig` / `SetConfig` 不吃空 handle
 * （`resolve profile: profile handle is empty`），而每个 daemon 只服务一个配置，
 * handle 就是配置文件名去掉 `.json`（默认 `default`）。
 */
class DaemonClient(
    val baseUrl: String,
    val profileName: String = DEFAULT_PROFILE,
) : DaemonApi {

    private val http: HttpClient = createHttpClient()

    /** `POST /daemon.DaemonService/Status`。 */
    override suspend fun status(getFullPeerStatus: Boolean): StatusResponse =
        post("Status", statusRequestBody(getFullPeerStatus)).body()

    /**
     * `Up`：`async` 固定为 true。默认的 false 会让 RPC 阻塞到引擎就绪（CLI 语义），
     * UI 上表现为点击后长时间无反应。
     */
    override suspend fun up() {
        post("Up", UP_REQUEST_BODY)
    }

    /**
     * `POST /daemon.DaemonService/Down`：让 daemon 自己断开（拆路由、DNS、网卡）。
     *
     * 停止配置前先调它，比直接终止进程干净：硬杀会跳过清理路径，留下坏掉的 wintun 适配器。
     */
    override suspend fun down() {
        post("Down", EMPTY)
    }

    override suspend fun login(
        setupKey: String,
        managementUrl: String,
        hostname: String,
    ): LoginResponse = post(
        method = "Login",
        body = loginRequestBody(setupKey, managementUrl, hostname),
        timeoutMs = LONG_OPERATION_TIMEOUT_MS,
    ).body()

    override suspend fun waitSSOLogin(userCode: String): String {
        val response = http.post("$baseUrl/daemon.DaemonService/WaitSSOLogin") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("userCode", userCode) })
            // 等的是人在浏览器里点完授权，默认的 5 秒超时会把它当成故障。
            timeout {
                requestTimeoutMillis = SSO_WAIT_TIMEOUT_MS
                socketTimeoutMillis = SSO_WAIT_TIMEOUT_MS
            }
        }
        if (!response.status.isSuccess()) throw DaemonException(response.errorText())
        return (response.body<JsonObject>()["email"] as? JsonPrimitive)?.content.orEmpty()
    }

    override suspend fun logout() {
        post("Logout", EMPTY, timeoutMs = LONG_OPERATION_TIMEOUT_MS)
    }

    override suspend fun networks(): List<NetworkRoute> =
        post("ListNetworks", EMPTY).body<ListNetworksResponse>().routes

    override suspend fun selectNetworks(ids: List<String>, selected: Boolean, all: Boolean) {
        val method = if (selected) "SelectNetworks" else "DeselectNetworks"
        post(method, selectNetworksRequestBody(ids, all))
    }

    override suspend fun config(): DaemonConfig =
        post("GetConfig", profileRequest(EMPTY)).body()

    override suspend fun setConfig(fields: JsonObject) {
        post("SetConfig", profileRequest(fields))
    }

    override suspend fun features(): Features = post("GetFeatures", EMPTY).body()

    override suspend fun logLevel(): String =
        post("GetLogLevel", EMPTY).body<LogLevelResponse>().level

    override suspend fun setLogLevel(level: String) {
        post("SetLogLevel", buildJsonObject { put("level", level) })
    }

    override fun close() {
        http.close()
    }

    /**
     * 统一的 POST + 状态码检查；网关的失败体只在这里读一次。
     *
     * [timeoutMs] 只给长操作（`Login` / `Logout`）覆盖：这两个要连 management 做
     * 探测、注册或注销，默认的 5 秒会在正常网络上就超时，而超时会让 daemon 侧的
     * 上下文被取消，留下 `context canceled` 的半截状态。
     */
    private suspend fun post(method: String, body: JsonObject, timeoutMs: Long? = null): HttpResponse {
        val response = http.post("$baseUrl/daemon.DaemonService/$method") {
            contentType(ContentType.Application.Json)
            setBody(body)
            if (timeoutMs != null) {
                timeout {
                    requestTimeoutMillis = timeoutMs
                    socketTimeoutMillis = timeoutMs
                }
            }
        }
        if (!response.status.isSuccess()) throw DaemonException(response.errorText())
        return response
    }

    /** 需要 profile handle 的 RPC 的请求体：把 [extra] 的键平铺进来。 */
    private fun profileRequest(extra: JsonObject): JsonObject = buildJsonObject {
        put("profileName", profileName)
        extra.forEach { (key, value) -> put(key, value) }
    }

    /**
     * 网关的失败响应体是 grpc 状态投影 `{"code":13,"message":"…"}`。
     * 必须显式抛错：`expectSuccess = false` 下若忽略状态码，错误体只会被解析成一个字段全空的
     * `StatusResponse`，UI 会把它当成 `Idle`（"未连接"）显示，把故障说成正常状态。
     */
    private suspend fun HttpResponse.errorText(): String {
        val raw = bodyAsText()
        val gatewayError = runCatching { netbirdJson.decodeFromString<GatewayError>(raw) }.getOrNull()
        return gatewayError?.message?.takeIf { it.isNotBlank() }
            ?: "HTTP ${status.value}: ${raw.take(200)}"
    }

    private companion object {
        val EMPTY = JsonObject(emptyMap())

        /** 等用户在浏览器完成 SSO 的时间；比默认的 5 秒宽松得多。 */
        const val SSO_WAIT_TIMEOUT_MS = 300_000L

        /** `Login` / `Logout` 要连 management，正常网络也可能超过默认的 5 秒。 */
        const val LONG_OPERATION_TIMEOUT_MS = 120_000L
    }
}
/** daemon 返回的错误；`message` 是网关原文，可直接展示。 */
class DaemonException(message: String) : Exception(message)

/** 单配置 daemon 的默认 profile handle：`config/default.json` 的文件名。 */
const val DEFAULT_PROFILE = "default"

/**
 * `Status` 的请求体。`getFullPeerStatus` 决定 daemon 填不填 `fullStatus` ——
 * 不带它时 peer / relay / DNS / 事件全是空的，UI 会把"没数据"显示成"没有 peer"。
 */
internal fun statusRequestBody(getFullPeerStatus: Boolean): JsonObject = buildJsonObject {
    if (getFullPeerStatus) put("getFullPeerStatus", true)
}

/** `Up` 的请求体：`async` 必须是 true，理由见 [DaemonApi.up]。 */
internal val UP_REQUEST_BODY: JsonObject = buildJsonObject { put("async", true) }

/** `Login` 的请求体；空串表示沿用配置里的值，不要发出去。 */
internal fun loginRequestBody(
    setupKey: String,
    managementUrl: String,
    hostname: String,
): JsonObject = buildJsonObject {
    if (setupKey.isNotBlank()) put("setupKey", setupKey)
    if (managementUrl.isNotBlank()) put("managementUrl", managementUrl)
    if (hostname.isNotBlank()) put("hostname", hostname)
}

/** `SelectNetworks` / `DeselectNetworks` 的请求体；`append` 固定为增量选择。 */
internal fun selectNetworksRequestBody(ids: List<String>, all: Boolean): JsonObject =
    buildJsonObject {
        put("networkIDs", JsonArray(ids.map { JsonPrimitive(it) }))
        put("append", true)
        if (all) put("all", true)
    }

@Serializable
private data class GatewayError(val code: Int = 0, val message: String = "")
