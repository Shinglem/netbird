// 根构建脚本：只声明插件版本，不写依赖（依赖在 composeApp/build.gradle.kts）。
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose) apply false
}
