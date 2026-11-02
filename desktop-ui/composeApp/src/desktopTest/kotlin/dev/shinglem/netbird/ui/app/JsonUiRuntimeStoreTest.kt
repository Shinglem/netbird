package dev.shinglem.netbird.ui.app

import dev.shinglem.netbird.ui.data.UiRuntimeState
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonUiRuntimeStoreTest {

    @Test
    fun `state round trips through the json file`() {
        val dir = Files.createTempDirectory("netbird-ui-").toFile()
        try {
            val file = File(dir, "state.json")
            val store = JsonUiRuntimeStore(file)

            store.save(UiRuntimeState(mapOf("alpha" to true, "beta" to false)))

            assertTrue(file.isFile)
            assertEquals(
                UiRuntimeState(mapOf("alpha" to true, "beta" to false)),
                store.load(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing file means nothing is remembered`() {
        val dir = Files.createTempDirectory("netbird-ui-").toFile()
        try {
            assertEquals(UiRuntimeState(), JsonUiRuntimeStore(File(dir, "state.json")).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `broken file is backed up instead of blocking startup`() {
        val dir = Files.createTempDirectory("netbird-ui-").toFile()
        try {
            val file = File(dir, "state.json")
            file.writeText("""{"running": """, Charsets.UTF_8)

            val loaded = JsonUiRuntimeStore(file).load()

            assertEquals(UiRuntimeState(), loaded)
            assertTrue(File(dir, "state.json.corrupt").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }
}
