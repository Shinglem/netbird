package dev.shinglem.netbird.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.shinglem.netbird.ui.app.AppPathsResolver
import dev.shinglem.netbird.ui.app.DesktopTray
import dev.shinglem.netbird.ui.app.Elevation
import dev.shinglem.netbird.ui.app.JsonDesktopConfigStore
import dev.shinglem.netbird.ui.app.JsonUiRuntimeStore
import dev.shinglem.netbird.ui.app.SingleInstanceLock
import dev.shinglem.netbird.ui.app.SingleInstanceSignal
import dev.shinglem.netbird.ui.app.TrayMenuItem
import dev.shinglem.netbird.ui.app.WindowsAutostart
import dev.shinglem.netbird.ui.app.appIconImage
import dev.shinglem.netbird.ui.app.isSilentStart
import dev.shinglem.netbird.ui.daemon.DesktopDaemonLauncher
import dev.shinglem.netbird.ui.data.AppEnvironment
import dev.shinglem.netbird.ui.data.AutostartState
import dev.shinglem.netbird.ui.data.ProfilesController
import dev.shinglem.netbird.ui.data.UiPreferences
import dev.shinglem.netbird.ui.data.reconcileAutostart
import dev.shinglem.netbird.ui.profile.DesktopProfileStorage
import dev.shinglem.netbird.ui.screen.App
import dev.shinglem.netbird.ui.screen.Strings
import java.io.File
import javax.swing.JOptionPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 入口：桌面端是 daemon 的唯一管理者。
 *
 * 每个配置一个 daemon 子进程（见 `ProfileSupervisor`）。关窗口只收进托盘，daemon 继续运行 ——
 * 用户关掉界面不应该断网；真正的退出走托盘菜单，那时才停掉本次拉起的全部 daemon。
 *
 * 带 `--silent`（登录自启的计划任务就是这么起的）时窗口不进入组合，只在托盘常驻。
 */
/**
 * 已有实例在跑：非静默触发应当把它的窗口叫出来，而不是默默退出。
 *
 * 叫不到时必须给反馈（对方是旧版本、跨权限被拒）—— javaw 没有控制台，静默退出
 * 对用户来说就是「点了没反应」。
 */
private fun requestExistingInstance(home: File) {
    if (SingleInstanceSignal.notify(home)) return
    runCatching {
        JOptionPane.showMessageDialog(
            null,
            "已有 NetBird UI 在运行（可能在系统托盘里）。\n如果看不到窗口，请从托盘图标打开。",
            "NetBird UI",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }
}

fun main(args: Array<String>) {
    val silent = isSilentStart(args.asList())
    val paths = AppPathsResolver.resolve()
    val lockFile = File(paths.home, "instance.lock")

    // 第一步只能是**只读探测**：下面未提权时会 runas 重启自己，旧进程若仍持着锁，
    // 提权后的新进程会把自己判成"第二个实例"而去唤醒那个正要退出的旧进程，形成活锁。
    if (SingleInstanceLock.isRunning(lockFile)) {
        // 静默触发（登录自启）撞上已经在跑的那一份时直接退出：用户正在用，别把窗口抢到前台。
        if (!silent) requestExistingInstance(paths.home)
        return
    }

    if (!Elevation.isElevated()) {
        // 参数要带给提权后的新进程，否则登录自启会变成弹窗启动。
        val outcome = Elevation.relaunchElevated(args.toList())
        when (outcome) {
            Elevation.Relaunch.STARTED -> return
            Elevation.Relaunch.CANCELLED -> {
                System.err.println("用户拒绝了管理员权限请求；界面将以普通权限运行，daemon 无法启动")
            }
            Elevation.Relaunch.FAILED -> {
                System.err.println(
                    "无法请求管理员权限（launcher=${Elevation.launcherExe()}）；" +
                        "请右键启动器选「以管理员身份运行」",
                )
            }
            Elevation.Relaunch.NOT_PACKAGED -> Unit
        }
        System.err.println("提权结果：$outcome，当前 elevated=${Elevation.isElevated()}")
    }

    val lock = SingleInstanceLock.acquire(lockFile)
    if (lock == null) {
        requestExistingInstance(paths.home)
        return
    }

    // 第二个实例发来的「显示窗口」请求。唤醒线程在后台跑，切前台交给 Compose 侧。
    val activateSignal = MutableStateFlow(0)
    val activationWatcher = SingleInstanceSignal.watch(paths.home) { activateSignal.value += 1 }

    val storage = DesktopProfileStorage()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = ProfilesController(
        storage = storage,
        launcher = DesktopDaemonLauncher(),
        exe = paths.binary.absolutePath,
        root = paths.root.absolutePath,
        osEnv = System.getenv(),
        scope = scope,
        runtime = JsonUiRuntimeStore(paths.runtimeStateFile),
    )
    storage.mkdirs(controller.profilesRoot)
    controller.reload()

    // daemon 跟随界面生命周期：启动时按上次记录拉起，退出时全部停掉。
    controller.startAutostart()

    val environment = AppEnvironment(
        elevated = Elevation.isElevated(),
        binaryPath = paths.binary.absolutePath,
        binaryFound = paths.binary.isFile,
        profilesRoot = controller.profilesRoot,
        requiresElevation = Elevation.launcherExe() != null,
        appVersion = System.getProperty("jpackage.app-version").orEmpty(),
        configFile = paths.customConfigFile.absolutePath,
    )

    val configStore = JsonDesktopConfigStore(paths.configDir)
    val preferences = UiPreferences(configStore)

    // 自启项要跑 schtasks，不放在主线程；配置里没写过时这里会补上系统现状。
    scope.launch(Dispatchers.IO) {
        // 默认值文件随版本刷新；用户改动只落在 ui.custom.json。
        runCatching { configStore.ensureDefaults() }
        val state = reconcileAutostart(preferences, WindowsAutostart)
        if (state is AutostartState.Failed) {
            System.err.println("同步登录自启失败：${state.message}")
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread { controller.shutdown() })

    // 退出路径兜底：正常退出走 quit()，这里覆盖被强杀之外的其他终止方式。
    Runtime.getRuntime().addShutdownHook(Thread { activationWatcher.close() })

    application {
        var windowVisible by remember { mutableStateOf(!silent) }
        val closeToTray by preferences.closeToTray.collectAsState()
        var trayForQuit: DesktopTray? = null

        fun quit() {
            controller.shutdown()
            scope.cancel()
            activationWatcher.close()
            lock.close()
            trayForQuit?.dispose()
            exitApplication()
        }

        val windowIcon = remember { BitmapPainter(appIconImage(64).toComposeImageBitmap()) }

        val tray = remember {
            DesktopTray(
                icon = appIconImage(64),
                tooltip = Strings.TRAY_TOOLTIP,
                onActivate = { windowVisible = true },
                menu = {
                    listOf(
                        TrayMenuItem.Action(Strings.TRAY_SHOW) { windowVisible = true },
                        TrayMenuItem.Separator,
                        TrayMenuItem.Action(Strings.TRAY_START_ALL) { controller.startAll() },
                        TrayMenuItem.Action(Strings.TRAY_STOP_ALL) { controller.stopAll() },
                        TrayMenuItem.Separator,
                        TrayMenuItem.Action(Strings.TRAY_QUIT) { quit() },
                    )
                },
            )
        }
        trayForQuit = tray

        LaunchedEffect(Unit) {
            val installed = tray.install()
            // 静默启动时托盘是唯一入口：装不上就把窗口显示出来，否则界面等于彻底消失。
            if (!installed && silent) windowVisible = true
        }
        DisposableEffect(Unit) { onDispose { tray.dispose() } }

        // 第二个实例请求显示窗口：从托盘里拉出来。
        val activation by activateSignal.collectAsState()
        LaunchedEffect(activation) {
            if (activation > 0) windowVisible = true
        }

        if (windowVisible) {
            Window(
                onCloseRequest = {
                    // 关闭 = 收进托盘；daemon 继续跑。真正退出走托盘菜单（那里会停掉所有 daemon）。
                    if (closeToTray) windowVisible = false else quit()
                },
                title = "NetBird UI",
                icon = windowIcon,
                state = rememberWindowState(width = 960.dp, height = 760.dp),
            ) {
                // 从托盘或第二个实例唤醒时尽量把窗口提到前面。
                LaunchedEffect(activation) {
                    if (activation > 0) {
                        window.toFront()
                        window.requestFocus()
                    }
                }
                App(controller, environment, preferences, WindowsAutostart)
            }
        }
    }
}