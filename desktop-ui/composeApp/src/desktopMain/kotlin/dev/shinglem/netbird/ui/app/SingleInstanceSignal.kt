package dev.shinglem.netbird.ui.app

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 「显示窗口」的跨进程通道：`Local\NetBirdUI-<home hash>-activate` 自动重置事件。
 *
 * 单实例判定由 [SingleInstanceLock] 的文件锁负责（句柄随进程回收，强杀不留僵尸锁）；
 * 这里只补第二条启动时缺的那一半 —— 让已有实例把窗口叫出来。没有它，双击 exe 只会
 * 静默退出，而界面的 stderr 在 javaw 下根本看不见。
 *
 * 用内核对象而不用窗口消息：Compose 窗口没有可挂的 WndProc。用 `Local\` 而不是 `Global\`：
 * 后者要 `SeCreateGlobalPrivilege`（只有管理员有），提权与非提权实例会看不到彼此。
 *
 * 非 Windows 或 FFM 不可用时退化为 no-op：单实例仍然有效，只是不能唤醒。
 */
object SingleInstanceSignal {

    /** 请已有实例显示窗口；`false` = 送不到（对方没建事件，或权限被拒）。 */
    fun notify(home: File): Boolean {
        val api = Api.instance ?: return false
        val handle = api.openEvent(eventName(home)) ?: return false
        return try {
            api.setEvent(handle)
        } finally {
            api.closeHandle(handle)
        }
    }

    /**
     * 起一个等待线程，每收到一次请求回调一次。
     *
     * [onActivate] 跑在后台线程上，调用方负责切回自己的线程。等待用 1 秒超时轮询而不是
     * `INFINITE`，这样 [AutoCloseable.close] 能让线程及时退出。
     */
    fun watch(home: File, onActivate: () -> Unit): AutoCloseable {
        val api = Api.instance ?: return NoopSignal
        val handle = api.createEvent(eventName(home)) ?: return NoopSignal

        val running = AtomicBoolean(true)
        val worker = thread(isDaemon = true, name = "netbird-activate-watcher") {
            while (running.get()) {
                val result = api.waitForSingleObject(handle, WAIT_SLICE_MS)
                if (!running.get()) break
                if (result == WAIT_OBJECT_0) runCatching(onActivate)
            }
        }

        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            running.set(false)
            worker.interrupt()
            api.closeHandle(handle)
        }
    }

    private object NoopSignal : AutoCloseable {
        override fun close() = Unit
    }

    private const val WAIT_OBJECT_0 = 0
    private const val WAIT_SLICE_MS = 1_000L
    private const val EVENT_MODIFY_STATE = 0x0002

    /**
     * 按 `<home>` 哈希：两份便携包各自的实例互不干扰。
     *
     * 必须用 `canonicalPath`，否则大小写、短路径（`PROGRA~1`）、符号链接会算成不同的名字。
     */
    private fun eventName(home: File): String {
        val canonical = runCatching { home.canonicalPath }.getOrDefault(home.absolutePath)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        val hash = digest.take(4).joinToString("") { "%02x".format(it) }
        return "Local\\NetBirdUI-$hash-activate"
    }

    /** `kernel32` 的命名事件绑定（FFM，与 `WindowsRunAs` 同一套做法）。 */
    private class Api(
        private val createEventHandle: MethodHandle,
        private val openEventHandle: MethodHandle,
        private val setEventHandle: MethodHandle,
        private val waitHandle: MethodHandle,
        private val closeHandleHandle: MethodHandle,
    ) {
        companion object {
            val instance: Api? by lazy {
                runCatching {
                    val linker = Linker.nativeLinker()
                    // `defaultLookup()` 找不到 kernel32 的导出，必须按库名查。
                    val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())

                    fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle? =
                        kernel32.find(name).orElse(null)?.let { linker.downcallHandle(it, descriptor) }

                    val createEvent = downcall(
                        "CreateEventW",
                        FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ) ?: return@runCatching null
                    val openEvent = downcall(
                        "OpenEventW",
                        FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ) ?: return@runCatching null
                    val setEvent = downcall("SetEvent", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                        ?: return@runCatching null
                    val wait = downcall(
                        "WaitForSingleObject",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                    ) ?: return@runCatching null
                    val close = downcall("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                        ?: return@runCatching null

                    Api(createEvent, openEvent, setEvent, wait, close)
                }.getOrNull()
            }
        }

        /** 名字必须是 UTF-16：`allocateFrom` 默认写 UTF-8，宽字符 API 会读到乱码。 */
        private fun wideName(name: String): MemorySegment =
            Arena.ofAuto().allocateFrom(name, Charsets.UTF_16LE)

        /** 自动重置事件（`bManualReset = 0`）：唤醒一个等待者后自动复位。 */
        fun createEvent(name: String): MemorySegment? {
            val handle = createEventHandle.invokeWithArguments(
                MemorySegment.NULL,
                0,
                0,
                wideName(name),
            ) as MemorySegment
            return handle.takeIf { it.address() != 0L }
        }

        fun openEvent(name: String): MemorySegment? {
            val handle = openEventHandle.invokeWithArguments(
                EVENT_MODIFY_STATE,
                0,
                wideName(name),
            ) as MemorySegment
            return handle.takeIf { it.address() != 0L }
        }

        fun setEvent(handle: MemorySegment): Boolean =
            (setEventHandle.invokeWithArguments(handle) as Int) != 0

        fun waitForSingleObject(handle: MemorySegment, timeoutMs: Long): Int =
            waitHandle.invokeWithArguments(handle, timeoutMs.toInt()) as Int

        fun closeHandle(handle: MemorySegment) {
            if (handle.address() == 0L) return
            runCatching { closeHandleHandle.invokeWithArguments(handle) }
        }
    }
}
