package dev.shinglem.netbird.ui.app

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * `ShellExecuteExW("runas")` —— 等价于资源管理器里的「以管理员身份运行」。
 *
 * 这是 Windows 唯一受支持的提权路径，不自己绕 UAC。只支持「exe + 工作目录」：
 * 调用方是 jpackage 启动器，参数都在 `app\` 的启动配置里，没有命令行要还原。
 *
 * 返回 `GetLastError()`：0 = 已拉起，[ERROR_CANCELLED] = 用户点了「否」，其余为失败。
 */
internal object WindowsRunAs {

    const val ERROR_CANCELLED = 1223

    private const val SEE_MASK_NOCLOSEPROCESS = 0x00000040
    private const val SEE_MASK_NOASYNC = 0x00000100
    private const val SEE_MASK_FLAG_NO_UI = 0x00000400
    private const val SW_SHOWNORMAL = 1

    /** `SHELLEXECUTEINFOW` 在 x64 上是 112 字节，最后一个字段是 `hProcess`。 */
    private const val INFO_SIZE = 112L
    private const val OFFSET_H_PROCESS = 104L

    fun run(exe: String, workingDir: String, verb: String = "runas"): Int {
        val api = Api.instance ?: return -1
        return runCatching { api.shellExecuteEx(verb, exe, workingDir) }.getOrDefault(-1)
    }

    private class Api(
        private val shellExecuteEx: java.lang.invoke.MethodHandle,
        private val closeHandle: java.lang.invoke.MethodHandle,
        private val captureLayout: java.lang.foreign.MemoryLayout,
        private val errorOffset: Long,
    ) {
        companion object {
            val instance: Api? by lazy {
                runCatching {
                    val linker = Linker.nativeLinker()
                    // `SymbolLookup.defaultLookup()` 找不到 shell32 的导出，必须按库名查。
                    val shell32 = SymbolLookup.libraryLookup("shell32", Arena.global())
                    val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
                    val capture = Linker.Option.captureCallState("GetLastError")
                    val layout = Linker.Option.captureStateLayout()
                    val shellExecute = shell32.find("ShellExecuteExW").orElse(null)
                        ?: return@runCatching null
                    val close = kernel32.find("CloseHandle").orElse(null) ?: return@runCatching null
                    Api(
                        shellExecuteEx = linker.downcallHandle(
                            shellExecute,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                            capture,
                        ),
                        closeHandle = linker.downcallHandle(
                            close,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        ),
                        captureLayout = layout,
                        errorOffset = layout.byteOffset(
                            java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"),
                        ),
                    )
                }.getOrNull()
            }
        }

        /**
         * 结构按 x64 偏移直接写（见 `SHELLEXECUTEINFOW`）：
         * ```
         *  0 cbSize   4 fMask   8 hwnd   16 lpVerb   24 lpFile
         * 32 lpParameters   40 lpDirectory   48 nShow   104 hProcess（ShellExecuteEx 写回）
         * ```
         */
        fun shellExecuteEx(verb: String, file: String, workingDir: String): Int {
            val arena = Arena.ofShared()
            try {
                val state = arena.allocate(captureLayout)
                val info = arena.allocate(INFO_SIZE)
                info.set(ValueLayout.JAVA_INT, 0, INFO_SIZE.toInt())
                info.set(
                    ValueLayout.JAVA_INT,
                    4,
                    SEE_MASK_NOCLOSEPROCESS or SEE_MASK_NOASYNC or SEE_MASK_FLAG_NO_UI,
                )
                info.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL)
                info.set(ValueLayout.ADDRESS, 16, arena.allocateFrom(verb, Charsets.UTF_16LE))
                info.set(ValueLayout.ADDRESS, 24, arena.allocateFrom(file, Charsets.UTF_16LE))
                info.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL)
                info.set(ValueLayout.ADDRESS, 40, arena.allocateFrom(workingDir, Charsets.UTF_16LE))
                info.set(ValueLayout.JAVA_INT, 48, SW_SHOWNORMAL)
                info.set(ValueLayout.ADDRESS, OFFSET_H_PROCESS, MemorySegment.NULL)

                val ok = (shellExecuteEx.invokeWithArguments(state, info) as Int) != 0
                if (!ok) return state.get(ValueLayout.JAVA_INT, errorOffset)

                val process = info.get(ValueLayout.ADDRESS, OFFSET_H_PROCESS)
                if (process.address() != 0L) {
                    runCatching { closeHandle.invokeWithArguments(process) }
                }
                return 0
            } finally {
                arena.close()
            }
        }
    }
}
