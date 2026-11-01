// ============================================================================
//  netbird-ui · Gradle 设置
//  ---------------------------------------------------------------------------
//  国内网络环境约定（与同版本线的另一个 CMP 项目一致）：
//   · 仓库顺序：国内镜像在前，官方源兜底
//   · 腾讯云 maven-public 放第一：nexus 代理 Central，实测构件最完整
//     （Gradle 一旦在某仓库命中 metadata 就不会回退到下一个仓库，
//       所以"最完整"的镜像必须排第一，否则会出现"pom 有、jar 404"）
//   · 代理不写死：需要时用 -Dhttps.proxyHost=<proxy-host> -Dhttps.proxyPort=<proxy-port>
//     （本机取值见 .local/environment.md）
// ============================================================================

rootProject.name = "netbird-ui"

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // 禁止各模块自带 repositories，统一在这里管
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android\\..*")
                includeGroupByRegex("org\\.jetbrains\\.androidx\\..*")
            }
        }
        mavenCentral()
        google()
    }
}

include(":composeApp")
