package dev.shinglem.netbird.ui.daemon

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 用 [ProcessBuilder] 拉起 daemon 子进程，**前台**运行并收集控制台输出。
 *
 * 两条纪律：
 * 1. `redirectErrorStream(true)`：stdout/stderr 并成一个管道、单线程读；两个管道都要读，
 *    漏一个就会因为缓冲区满把子进程憋死。
 * 2. 环境只给 [DaemonPlan] 算好的白名单，不整份继承 —— 免得父进程的 `NB_*`、代理、
 *    别的实例的状态目录漏进来。
 */
class DesktopDaemonLauncher : DaemonLauncher {

    override fun launch(spec: DaemonLaunchSpec): DaemonProcess {
        val builder = ProcessBuilder(listOf(spec.exe) + spec.args).apply {
            directory(File(spec.workDir))
            redirectErrorStream(true)
            environment().clear()
            environment().putAll(spec.env)
        }
        return DesktopDaemonProcess(builder.start())
    }
}

private class DesktopDaemonProcess(private val process: Process) : DaemonProcess {

    override val pid: Long get() = process.pid()

    override fun lines(): Flow<String> = callbackFlow {
        val pump = thread(isDaemon = true, name = "netbird-daemon-out") {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { trySend(it) }
                }
            } catch (_: Throwable) {
                // 进程被终止时读端会抛：关流即可，不当错误上报。
            } finally {
                close()
            }
        }
        awaitClose { pump.interrupt() }
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun terminate(graceMs: Long) {
        if (!process.isAlive) return
        // daemon 可能拉起工具子进程，只杀根节点会留孤儿。
        process.descendants().forEach { handle -> runCatching { handle.destroy() } }
        process.destroy()
        if (runCatching { process.waitFor(graceMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)) return

        process.descendants().forEach { handle -> runCatching { handle.destroyForcibly() } }
        process.destroyForcibly()
        runCatching { process.waitFor(graceMs, TimeUnit.MILLISECONDS) }
    }
}
