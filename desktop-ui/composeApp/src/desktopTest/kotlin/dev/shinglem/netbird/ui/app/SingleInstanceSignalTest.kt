package dev.shinglem.netbird.ui.app

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun onWindows(): Boolean =
    System.getProperty("os.name").lowercase().contains("windows")

class SingleInstanceSignalTest {

    @Test
    fun `notify wakes the watcher`() {
        if (!onWindows()) return

        val home = Files.createTempDirectory("netbird-signal").toFile()
        val received = CountDownLatch(1)
        val watcher = SingleInstanceSignal.watch(home) { received.countDown() }
        try {
            assertTrue(SingleInstanceSignal.notify(home), "第一个实例应该已经建好事件")
            assertTrue(received.await(5, TimeUnit.SECONDS), "第二个实例的请求要唤醒等待线程")
        } finally {
            watcher.close()
        }
    }

    @Test
    fun `notify without a watcher is reported as undeliverable`() {
        if (!onWindows()) return

        val home = Files.createTempDirectory("netbird-signal").toFile()

        assertFalse(SingleInstanceSignal.notify(home), "没人建事件时调用方要能给出提示")
    }

    @Test
    fun `different home directories do not share the event`() {
        if (!onWindows()) return

        val first = Files.createTempDirectory("netbird-signal-a").toFile()
        val second = Files.createTempDirectory("netbird-signal-b").toFile()
        val received = CountDownLatch(1)
        val watcher = SingleInstanceSignal.watch(first) { received.countDown() }
        try {
            assertFalse(SingleInstanceSignal.notify(second), "两份便携包各自的实例互不影响")
            assertTrue(SingleInstanceSignal.notify(first))
            assertTrue(received.await(5, TimeUnit.SECONDS))
        } finally {
            watcher.close()
        }
    }
}
