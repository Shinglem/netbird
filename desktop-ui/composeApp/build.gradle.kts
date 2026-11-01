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
            implementation(libs.multiplatform.settings)
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

compose.desktop {
    application {
        mainClass = "dev.shinglem.netbird.ui.MainKt"
        jdkHome?.let { javaHome = it }

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "NetBird UI"
            // MSI 版本号段必须是数字且首段 ≥ 1；正式发版时再改。
            packageVersion = "1.0.0"
            description = "NetBird desktop client (Compose Multiplatform)"
            vendor = "shinglem"
            windows {
                menu = true
                shortcut = true
                dirChooser = true
            }
        }
    }
}
