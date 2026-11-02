package dev.shinglem.netbird.ui.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPathsResolverTest {

    @Test
    fun `launcher directory is used when it carries a profiles folder`() {
        val dir = Files.createTempDirectory("netbird-ui-").toFile()
        try {
            File(dir, "profiles").mkdirs()

            val paths = AppPathsResolver.resolve(
                env = mapOf("APPDATA" to dir.absolutePath),
                launcherPath = File(dir, "netbird-multi-ui.exe").absolutePath,
            )

            assertEquals(dir.absolutePath, paths.root.absolutePath, "release-test 应自包含")
            assertEquals(
                File(dir, "netbird-multi.exe").absolutePath,
                paths.binary.absolutePath,
                "后端 exe 就在实例根旁边",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `explicit root wins over the launcher directory`() {
        val dir = Files.createTempDirectory("netbird-ui-").toFile()
        val override = Files.createTempDirectory("netbird-ui-override-").toFile()
        try {
            File(dir, "profiles").mkdirs()

            val paths = AppPathsResolver.resolve(
                env = mapOf(
                    "APPDATA" to dir.absolutePath,
                    "NETBIRD_UI_ROOT" to override.absolutePath,
                ),
                launcherPath = File(dir, "netbird-multi-ui.exe").absolutePath,
            )

            assertEquals(override.absolutePath, paths.root.absolutePath)
        } finally {
            dir.deleteRecursively()
            override.deleteRecursively()
        }
    }

    @Test
    fun `a development process path is not treated as home`() {
        val dir = Files.createTempDirectory("netbird-ui-").toFile()
        try {
            // 没有 profiles\ 且进程是 JDK 的 java.exe：./gradlew run 不能把 JDK 的 bin 当成家。
            val jdkBin = File(dir, "jdk/bin").apply { mkdirs() }
            val paths = AppPathsResolver.resolve(
                env = mapOf("APPDATA" to dir.absolutePath),
                launcherPath = File(jdkBin, "java.exe").absolutePath,
            )

            assertEquals(File(dir, "netbird-ui").absolutePath, paths.home.absolutePath)
            assertEquals(File(dir, "netbird-ui").absolutePath, paths.root.absolutePath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `packaged launcher makes the whole directory portable`() {
        val dir = Files.createTempDirectory("netbird-ui-").toFile()
        try {
            val paths = AppPathsResolver.resolve(
                env = mapOf("APPDATA" to dir.absolutePath),
                launcherPath = File(dir, "netbird-multi-ui.exe").absolutePath,
            )

            assertEquals(dir.canonicalPath, paths.home.canonicalPath, "启动器所在目录就是家")
            assertEquals(
                File(dir, "ui/ui.json").canonicalPath,
                paths.configFile.canonicalPath,
                "默认值随版本发布，用户覆盖另存一份",
            )
            assertEquals(File(dir, "ui/ui.custom.json").canonicalPath, paths.customConfigFile.canonicalPath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `relative paths in config json resolve against home`() {
        val dir = Files.createTempDirectory("netbird-ui-").toFile()
        try {
            File(dir, "deploy").mkdirs()
            File(dir, "deploy/netbird-multi.exe").writeText("", Charsets.UTF_8)
            File(dir, "config.json").writeText(
                """{"root":"deploy","binary":"deploy/netbird-multi.exe"}""",
                Charsets.UTF_8,
            )

            val paths = AppPathsResolver.resolve(
                env = mapOf("NETBIRD_UI_HOME" to dir.absolutePath, "APPDATA" to dir.absolutePath),
                launcherPath = File(dir, "netbird-multi-ui.exe").absolutePath,
            )

            assertEquals(File(dir, "deploy").canonicalPath, paths.root.canonicalPath)
            assertEquals(File(dir, "deploy/netbird-multi.exe").canonicalPath, paths.binary.canonicalPath)
        } finally {
            dir.deleteRecursively()
        }
    }
}
