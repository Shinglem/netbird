package dev.shinglem.netbird.ui.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 平台侧提供引擎（desktop 用 CIO）；common 只关心"有一个配好的 client"。 */
expect fun createHttpClient(): HttpClient

/** 两端共用的 client 配置。 */
internal fun HttpClientConfig<*>.netbirdDefaults() {
    install(ContentNegotiation) {
        json(
            Json {
                // daemon 升级会加字段：忽略未知键，不为了一个字段让整个状态解析失败。
                ignoreUnknownKeys = true
                isLenient = true
            },
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 3_000
    }
    // 让上层按状态码自己判断（网关把 gRPC 码映射成 HTTP 码）
    expectSuccess = false
}

/**
 * daemon 的 HTTP/JSON 网关客户端。
 *
 * 地址形如 `http://127.0.0.1:8471`，对应 daemon 启动参数
 * `--enable-json-socket --json-socket tcp://127.0.0.1:8471`。
 * **不要改成命名管道**：Kotlin/JVM 没有可用的 gRPC-over-named-pipe 传输。
 */
class DaemonClient(val baseUrl: String) {

    private val http: HttpClient = createHttpClient()

    /** `POST /daemon.DaemonService/Status`，body 为空对象。 */
    suspend fun status(): StatusResponse =
        http.post("$baseUrl/daemon.DaemonService/Status") {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(emptyMap()))
        }.body()

    fun close() {
        http.close()
    }
}
