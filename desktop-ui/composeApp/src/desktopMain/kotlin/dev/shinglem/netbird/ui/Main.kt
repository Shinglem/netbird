package dev.shinglem.netbird.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.shinglem.netbird.ui.api.DaemonClient
import dev.shinglem.netbird.ui.data.StatusRepository
import dev.shinglem.netbird.ui.screen.App

/**
 * 入口。端口来自环境变量 `NETBIRD_UI_PORT`，默认 8471（端口约定：8471 起、每实例 +1）。
 * 多实例切换（每实例一条 client）在数据层加，不在入口里写死。
 */
fun main() {
    val port = System.getenv("NETBIRD_UI_PORT")?.takeIf { it.isNotBlank() } ?: "8471"
    val repository = StatusRepository(DaemonClient("http://127.0.0.1:$port"))
    repository.start()

    application {
        Window(
            onCloseRequest = {
                repository.stop()
                exitApplication()
            },
            title = "NetBird UI",
            state = rememberWindowState(width = 720.dp, height = 600.dp),
        ) {
            App(repository)
        }
    }
}
