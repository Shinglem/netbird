package dev.shinglem.netbird.ui.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/**
 * 单实例：对 `<home>\instance.lock` 持有排他锁。
 *
 * 用文件锁而不是 PID 文件：锁随进程回收，强杀不会留下"文件还在、进程已死"的僵尸状态，
 * 也就不需要判断 PID 复用。
 */
class SingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        /** 已有实例在跑时返回 null。 */
        fun acquire(lockFile: File): SingleInstanceLock? = runCatching {
            lockFile.parentFile?.mkdirs()
            val channel = FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                return null
            }
            channel.truncate(0)
            channel.write(ByteBuffer.wrap(ProcessHandle.current().pid().toString().toByteArray()))
            channel.force(true)
            SingleInstanceLock(channel, lock)
        }.getOrNull()

        fun isRunning(lockFile: File): Boolean = runCatching {
            RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.tryLock()?.let { probe ->
                    probe.release()
                    false
                } ?: true
            }
        }.getOrDefault(false)
    }
}
