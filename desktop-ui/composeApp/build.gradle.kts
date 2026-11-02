@file:Suppress("DEPRECATION")

// ============================================================================
//  composeApp · 应用模块（commonMain + desktopMain）
//  ---------------------------------------------------------------------------
//  「先搭框架」：只声明插件、目标、source set 与依赖，业务代码在 src/ 下。
//
//  为什么 @file:Suppress("DEPRECATION")：
//   Gradle 9.8 + CMP 1.12 起，`compose.material3` / `compose.desktop.currentOs` 等便捷 DSL
//   被标记 deprecated，而 Kotlin DSL 脚本把 deprecation 当**编译错误**。这两个必须继续用：
//   material3 在 CMP 1.12.2 没有同版本的显式坐标；desktop.currentOs 需要由插件按当前 OS
//   选 skiko 变体。
// ============================================================================

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

// JDK 来源：`-Pnetbird.jdk.dir=…` → `local.properties` 的 `netbird.jdk.dir`。
// 不做 toolchain 自动探测（本机的 JBR 不会被 Gradle 识别）。没配就退回 Gradle 自己的 JDK。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val jdkHome: String? =
    (providers.gradleProperty("netbird.jdk.dir").orNull ?: localProperties.getProperty("netbird.jdk.dir"))
        ?.takeIf { it.isNotBlank() }

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlin.logging)

            // 只要 HTTP/JSON：daemon 的 grpc-gateway 是 REST 风格端点，不需要 WebSocket / gRPC。
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.logback.classic)
            // ViewModel 的 viewModelScope 依赖 Dispatchers.Main.immediate，desktop 默认没有
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

// 测试也会调 FFM（WindowsRunAs 用 `open` 走同一条 ShellExecuteExW 路径），
// 显式声明原生访问，避免 JDK 24+ 的 restricted-method 警告。
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ============================================================================
//  运行时裁剪
//  ---------------------------------------------------------------------------
//  jpackage 自己 jlink 的 runtime 只含 Compose 插件算出的模块，缺两样本项目真正用到的：
//   · jdk.charsets —— Autostart 用 GBK 解码 schtasks 的输出（Windows 控制台代码页）；
//   · jdk.unsupported —— sun.misc.Unsafe（skiko / 协程的兜底路径）。
//  这里显式给出集合，并用自定义 jlink（zip-9 + 去调试信息）替换 app-image 的 runtime。
//  刻意不含 jdk.localedata：界面文案是硬编码中文、日志时间格式只用数字，不需要 zh 的
//  locale 数据（省约 8 MB）。
// ============================================================================
val appImageName = "netbird-multi-ui"

val runtimeModules = listOf(
    "java.base",         // 一切的基础，jpackage 启动器也依赖它
    "java.desktop",      // AWT/Swing：窗口、托盘、字体渲染
    "java.datatransfer", // 剪贴板（BasicTextField 的复制粘贴）
    "java.logging",      // java.util.logging：若干库在类初始化时引用
    "java.prefs",        // java.desktop 的传递依赖；列出来才能与 rt/release 的 MODULES 一致
    "java.xml",          // logback 的配置解析（JAXP）
    "jdk.crypto.ec",     // TLS 的 ECDHE 握手：连 management 与本地网关
    "jdk.charsets",      // GBK：解码 schtasks 输出
    "jdk.unsupported",   // sun.misc.Unsafe（skiko / 协程兜底）
)

/** jlink 的输入必须含 `jmods/`：`jbr-*` 是纯运行时、`jbrsdk-*` 才有。 */
val jmodsHome: File? = (jdkHome?.let(::File) ?: File(System.getProperty("java.home")))
    .let { File(it, "jmods") }
    .takeIf { it.isDirectory }

val jlinkRuntimeDir = layout.buildDirectory.dir("jlink-runtime")

val linkRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "用 jmods jlink 出一份裁剪运行时（含 jdk.charsets / jdk.unsupported）"

    val jmods = jmodsHome
    val jlinkExe = jmods?.parentFile?.let { File(it, "bin/jlink.exe") }
    onlyIf { jmods != null && jlinkExe != null && jlinkExe.isFile }

    inputs.property("modules", runtimeModules)
    inputs.property("jmods", jmods?.absolutePath.orEmpty())
    outputs.dir(jlinkRuntimeDir)
    // jmods 的路径不变不代表内容不变（换 JDK 时路径可能相同），每次都重建，代价几秒。
    outputs.upToDateWhen { false }

    doFirst {
        val out = jlinkRuntimeDir.get().asFile
        out.deleteRecursively()
        commandLine(
            listOf(
                jlinkExe!!.absolutePath,
                "--module-path", jmods!!.absolutePath,
                "--add-modules", runtimeModules.joinToString(","),
                "--output", out.absolutePath,
                // 去掉 class 调试信息、C 头文件、man 页；zip-9 压 jimage。
                "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-9",
            ),
        )
    }
}

// ============================================================================
//  release 结构（对齐 hermes-compose）
//  ---------------------------------------------------------------------------
//  `release-test/` 不再是一个完整 jpackage app-image，而是拆成三层：
//
//      netbird-multi-ui.exe            原生启动器（jpackage 只产它）
//      app\netbird-multi-ui.cfg        我们自己写的启动配置（app.runtime 外挂 rt\）
//      bin\App.jar                     本模块的类与资源
//      lib\*.jar                       全部依赖（skiko 的 dll 也解压到这里）
//      rt\                             jlink 裁剪运行时（linkRuntime 产出）
//
//  不用 jpackage 的 `--runtime-image` 直接打进 app-image，是因为那样每出一次包都要复制
//  60 MB，且 runtime 与启动器绑死；空 runtime + 自写 cfg 让两者解耦，rt\ 可以单独替换。
//  jpackage 在这里只当"启动器生成器"，它生成的 cfg 直接丢弃。
// ============================================================================
val appVersion = "0.1.0"
val appMainClass = "dev.shinglem.netbird.ui.MainKt"
val repoRootDir: File = rootProject.projectDir.parentFile

val jpackageWorkDir = layout.buildDirectory.dir("jpackage")
val launcherImageDir = jpackageWorkDir.map { it.dir("image/$appImageName") }
val launcherBundleDir = layout.buildDirectory.dir("launcher")

/**
 * 递归删除，顺带清掉只读属性。
 *
 * jpackage 产出的 exe 是 ReadOnly 的：直接 deleteRecursively 会静默失败，下一次 jpackage
 * 就会报"目标目录已存在"。
 */
fun File.forceDeleteTree() {
    if (!exists()) return
    walkTopDown().filter { it.isFile }.forEach { it.setWritable(true) }
    deleteRecursively()
}

/** jpackage 的输入：只放一份 App.jar；classpath 由我们自己写进 cfg。 */
val prepareLauncherInput by tasks.registering {
    group = "distribution"
    description = "准备 jpackage 输入（App.jar + 空 runtime 目录）"

    val desktopJar = tasks.named<Jar>("desktopJar").flatMap { it.archiveFile }
    inputs.file(desktopJar)

    doLast {
        val root = jpackageWorkDir.get().asFile
        val input = File(root, "input").apply { forceDeleteTree(); mkdirs() }
        desktopJar.get().asFile.copyTo(File(input, "App.jar"), overwrite = true)
        // 空目录：jpackage 不 jlink、不拷 runtime，产物只剩启动器。
        File(root, "empty-runtime").apply { forceDeleteTree(); mkdirs() }
    }
}

/** 调 jpackage 生成启动器（app-image 里只要 `netbird-multi-ui.exe`）。 */
val jpackageLauncher by tasks.registering(Exec::class) {
    group = "distribution"
    description = "生成原生启动器（runtime 为空，只取 exe）"

    dependsOn(prepareLauncherInput)

    val icon = project.file("icons/netbird-blue.ico")
    inputs.file(icon).withPropertyName("launcherIcon")
    outputs.dir(launcherImageDir)

    doFirst {
        val javaHomeDir = jdkHome?.let(::File) ?: File(System.getProperty("java.home"))
        val jpackageExe = File(javaHomeDir, "bin/jpackage.exe")
        if (!jpackageExe.isFile) {
            throw GradleException("找不到 jpackage：${jpackageExe.absolutePath}（需要 JDK 的 bin\\jpackage.exe）")
        }
        if (!icon.isFile) {
            throw GradleException("缺少启动器图标：${icon.absolutePath}（先跑 scripts/make-icons.ps1）")
        }

        val root = jpackageWorkDir.get().asFile
        File(root, "image").forceDeleteTree()
        commandLine(
            listOf(
                jpackageExe.absolutePath,
                "--type", "app-image",
                "--name", appImageName,
                "--dest", File(root, "image").absolutePath,
                "--input", File(root, "input").absolutePath,
                "--main-jar", "App.jar",
                "--main-class", appMainClass,
                "--runtime-image", File(root, "empty-runtime").absolutePath,
                "--app-version", appVersion,
                "--vendor", "shinglem",
                "--description", "NetBird desktop client (Compose Multiplatform)",
                "--icon", icon.absolutePath,
            ),
        )
    }
}

/**
 * 整理启动器：`netbird-multi-ui.exe` + `app/netbird-multi-ui.cfg`。
 *
 * cfg 是手写的，不沿用 jpackage 的那份：runtime 指向 `$ROOTDIR\rt`，classpath 指向
 * `bin\App.jar` 与 `lib\*`，JVM 参数只留真正需要的几条。
 */
val launcherBundle by tasks.registering {
    group = "distribution"
    description = "整理启动器：exe + 外挂 rt\\ 的 cfg"

    dependsOn(jpackageLauncher)
    outputs.dir(launcherBundleDir)

    doLast {
        val out = launcherBundleDir.get().asFile.apply { forceDeleteTree(); mkdirs() }
        val sourceExe = File(launcherImageDir.get().asFile, "$appImageName.exe")
        if (!sourceExe.isFile) throw GradleException("jpackage 没有产出启动器：${sourceExe.absolutePath}")

        sourceExe.copyTo(File(out, "$appImageName.exe"), overwrite = true)

        val cfg = buildString {
            appendLine("[Application]")
            appendLine("app.runtime=\$ROOTDIR\\rt")
            appendLine("app.classpath=\$ROOTDIR\\bin\\App.jar")
            appendLine("app.classpath=\$ROOTDIR\\lib\\*")
            appendLine("app.mainclass=$appMainClass")
            appendLine()
            appendLine("[JavaOptions]")
            appendLine("java-options=-Djpackage.app-version=$appVersion")
            // Skiko 加载 native 库；JDK 24+ 不声明会被 JEP 472 拦截。
            appendLine("java-options=--enable-native-access=ALL-UNNAMED")
            appendLine("java-options=-Dcompose.application.configure.swing.globals=true")
            appendLine("java-options=-Dcompose.application.resources.dir=\$ROOTDIR\\bin")
            // skiko 的 dll 与 sha256 从 jar 里解压到 lib\（见 syncReleaseTest）。
            appendLine("java-options=-Dskiko.library.path=\$ROOTDIR\\lib")
        }
        val appDir = File(out, "app").apply { mkdirs() }
        File(appDir, "$appImageName.cfg").writeText(cfg, Charsets.UTF_8)
        logger.lifecycle("[release] 启动器：$appImageName.exe + app\\$appImageName.cfg")
    }
}

/** skiko 的 native 库在 runtime jar 里，解压到 `lib\` 供 `-Dskiko.library.path` 使用。 */
val skikoRuntimeJar = configurations.named("desktopRuntimeClasspath").map { classpath ->
    classpath.first { it.name.startsWith("skiko-awt-runtime-") && it.extension == "jar" }
}

/**
 * 组装 `<repo>\release-test`。
 *
 * `profiles/`、`ui/`、`Netbird/` 与后端二进制是用户数据/外部输入，必须 preserve ——
 * Sync 默认会删掉目标里源集合没有的东西。
 */
val syncReleaseTest by tasks.registering(Sync::class) {
    group = "distribution"
    description = "组装 release-test：启动器 + bin/App.jar + lib/*.jar + rt/（jlink 裁剪）"

    dependsOn(launcherBundle, linkRuntime, tasks.named("desktopJar"))

    into(repoRootDir.resolve("release-test"))

    from(launcherBundleDir)
    from(tasks.named("desktopJar")) {
        into("bin")
        rename { "App.jar" }
    }
    from(
        configurations.named("desktopRuntimeClasspath").map { classpath ->
            classpath.filter { it.extension == "jar" }.distinct()
        },
    ) {
        into("lib")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    from(skikoRuntimeJar.map { zipTree(it) }) {
        // icudtl.dat 是 skiko 的 ICU 数据：缺了它 skiko 的 ParagraphBuilder 会直接 abort
        // （日志只有一句 `SkLoadICU: datafile ... is missing`，看不出是它）。
        include("*.dll", "*.sha256", "*.dat")
        into("lib")
    }
    from(jlinkRuntimeDir) {
        into("rt")
    }

    preserve {
        include("profiles/**")
        include("ui/**")
        include("Netbird/**")
        include("netbird-multi.exe")
        include("wintun.dll")
        include("*.log")
    }

    doFirst {
        val runtimeHome = jlinkRuntimeDir.get().asFile
        if (!File(runtimeHome, "bin/server/jvm.dll").isFile) {
            throw GradleException(
                "没有可用的裁剪运行时：${runtimeHome.absolutePath}\n" +
                    "jlink 需要含 jmods 的 JDK（jbrsdk-*，不是 jbr-*）。在 desktop-ui/local.properties " +
                    "里写 netbird.jdk.dir=<jbrsdk 目录> 后重跑。",
            )
        }

        // jpackage 产出的 exe 带 ReadOnly 属性；Sync 覆盖它时会 Access denied。
        // 只清受管区域，不碰 profiles/、ui/、Netbird/ 这些用户数据。
        val target = repoRootDir.resolve("release-test")
        listOf("$appImageName.exe").forEach { name -> File(target, name).takeIf { it.isFile }?.setWritable(true) }
        listOf("app", "bin", "lib", "rt").forEach { dir ->
            val root = File(target, dir)
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile }.forEach { it.setWritable(true) }
            }
        }
    }

    doLast {
        val target = repoRootDir.resolve("release-test")
        val runtimeSize = target.resolve("rt").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        logger.lifecycle(
            "[release] release-test 就绪：${target.absolutePath}（rt/ = " +
                "${"%.1f".format(runtimeSize / 1024.0 / 1024.0)} MB）",
        )
    }
}

/**
 * 校验 `release-test/` 的结构：cfg 指向、裁剪模块集合、skiko 资产、依赖镜像。
 *
 * 由 [checkReleaseTest]（只看现状）与 [verifyReleaseTest]（先同步再校验）共用。
 */
fun verifyReleaseLayout() {
    val target = repoRootDir.resolve("release-test")
    // 名称与平台相关：与 appImageName、make-icons.ps1 的输出保持一致。
    val required = listOf(
        "$appImageName.exe",
        "app/$appImageName.cfg",
        "bin/App.jar",
        "rt/bin/server/jvm.dll",
        "lib/icudtl.dat",
        "lib/skiko-windows-x64.dll",
        "lib/skiko-windows-x64.dll.sha256",
        "netbird-multi.exe",
        "wintun.dll",
    )
    val missing = required.filterNot { target.resolve(it).exists() }
    if (missing.isNotEmpty()) {
        throw GradleException("release-test 缺少：${missing.joinToString()}（先跑 syncReleaseTest）")
    }

    val cfg = target.resolve("app/$appImageName.cfg").readText()
    listOf(
        "app.runtime=\$ROOTDIR\\rt",
        "app.classpath=\$ROOTDIR\\bin\\App.jar",
        "app.classpath=\$ROOTDIR\\lib\\*",
        "java-options=-Dskiko.library.path=\$ROOTDIR\\lib",
    ).forEach { expected ->
        if (!cfg.contains(expected)) {
            throw GradleException("cfg 缺少：$expected（不要手改 app\\*.cfg，重跑 syncReleaseTest）")
        }
    }

    // jlink 写 MODULES 的顺序与 --add-modules 不一定相同，按集合比。
    val releaseFile = target.resolve("rt/release")
    val actualModules = releaseFile.takeIf { it.isFile }
        ?.readText()
        ?.let { Regex("MODULES=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        ?.split(' ')
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()
    if (actualModules != runtimeModules.toSet()) {
        throw GradleException(
            "rt/ 的模块集合与 runtimeModules 不一致（被手工替换过？）。\n" +
                "  实际：${actualModules.sorted()}\n" +
                "  期望：${runtimeModules.sorted()}\n" +
                "重跑 syncReleaseTest。",
        )
    }

    val jars = target.resolve("lib").listFiles()?.count { it.extension == "jar" } ?: 0
    if (jars < 10) throw GradleException("lib/ 只有 $jars 个 jar，依赖镜像不完整")

    val runtimeSize = target.resolve("rt").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    logger.lifecycle(
        "[release] 校验通过：$jars 个 jar，rt/ = ${"%.1f".format(runtimeSize / 1024.0 / 1024.0)} MB",
    )
}

/** 只看现状，不同步。发布前若确信没动过目录，用它做一次体检。 */
val checkReleaseTest by tasks.registering {
    group = "distribution"
    description = "校验现有 release-test（不同步）"

    doLast { verifyReleaseLayout() }
}

/**
 * 同步后再校验 —— 出成品的唯一入口。
 *
 * 先 sync 再 check，顺序由依赖保证；`checkReleaseTest` 可以单独跑，用来确认
 * 目录有没有被手工改坏。
 */
val verifyReleaseTest by tasks.registering {
    group = "distribution"
    description = "同步并校验 release-test（结构 + cfg + 模块集合 + skiko 资产）"

    // 顺序由 dependsOn + 本任务的 doLast 保证；不要改成同时依赖两个任务 ——
    // Gradle 不保证平级依赖的先后，check 可能先于 sync 跑，读到旧目录。
    dependsOn(syncReleaseTest)

    doLast { verifyReleaseLayout() }
}

compose.desktop {
    application {
        mainClass = "dev.shinglem.netbird.ui.MainKt"
        jdkHome?.let { javaHome = it }
        // shell32 的 ShellExecuteExW 走 FFM（提权要 runas）。JDK 24+ 把未声明原生访问
        // 视为受限调用，不加这个参数每次启动都会打警告。
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            // 这个名字同时是 jpackage app-image 的启动器名（netbird-multi-ui.exe）与
            // app/ 内的启动配置名；release-test 的布局依赖它，改之前先看
            // desktop-ui/scripts/assemble-release-test.ps1。
            packageName = appImageName
            // 与 [runtimeModules] 同源：jmods 缺失时 jpackage 的默认 runtime 也要模块齐全。
            modules(*runtimeModules.toTypedArray())
            // MSI 版本号段必须是数字且首段 ≥ 1；与启动器 cfg 的 app-version 同源。
            packageVersion = appVersion
            description = "NetBird desktop client (Compose Multiplatform)"
            vendor = "shinglem"
            windows {
                // 蓝色版官方 mark；PNG/ICO 都由 desktop-ui/scripts/make-icons.ps1 从
                // desktop-ui/icons/netbird-blue.svg 生成，改色后重跑脚本。
                iconFile.set(project.file("icons/netbird-blue.ico"))
                menu = true
                shortcut = true
                dirChooser = true
            }
        }
    }
}

