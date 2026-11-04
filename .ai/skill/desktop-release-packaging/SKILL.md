---
name: desktop-release-packaging
description: "把 Compose Desktop（Windows）打成可分发成品：jpackage 只产启动器、jlink 裁剪运行时外挂 rt、图标生成与缓存、发版校验。"
version: 1.0.0
license: MIT
platforms: [windows]
metadata:
  tags: [jpackage, jlink, 运行时裁剪, 图标, 发版, Windows, Compose Desktop, release-test]
  related_skills: [compose-multiplatform-desktop, dev-desktop-gui-e2e-testing]
---

# Compose Desktop 发版打包（Windows）

> **中文名**：桌面端发版打包｜目录与 `name`：`desktop-release-packaging`

把 Compose Desktop 应用打成**自包含、体积可控、可重复**的 Windows 成品。本仓库的成品目录是
`<repo>\release-test\`（整目录 `.gitignore` 忽略）。

## When to Use

- 要出 Windows 成品（发给别人跑、或本机真机验证）
- 成品体积太大 / runtime 里带着 javac、jmods、全部 locale
- 改了图标、加了依赖、换了 JDK 之后要重建成品
- jpackage 的 exe 打不开、启动后 `NoClassDefFoundError`、skiko 崩溃、图标是咖啡杯

## 三条铁律

1. **出成品只有一个入口**：`./gradlew :composeApp:syncReleaseTest`（本仓库）。
   **不要**用 Compose 的 `createDistributable` 出成品 —— 它每次 jlink 一份完整 runtime
   （85.6 MB），且布局与成品不一致。
2. **构建产物不手改**：`app\`、`bin\`、`lib\`、`rt\` 全部由任务产出。手工替换 `rt\`、
   手改 cfg 会让「下次重建」与「当前这份」分叉，之后没人能判断成品里到底是什么。
3. **依赖 / 图标 / JDK 变了就重跑对应任务**：加 jar → 复核 `runtimeModules`；
   换图标 → 重跑 `scripts/make-icons.ps1`；换 JDK → 重跑 jlink（`rt\` 是它产出的）。

## 成品结构（启动器 + 外挂运行时）

```text
release-test/
  netbird-multi-ui.exe        0.48 MB   jpackage 产的原生启动器
  app\netbird-multi-ui.cfg              app.runtime=$ROOTDIR\rt
  bin\App.jar                0.6 MB     本模块的类与资源
  lib\*.jar                  92.6 MB    全部依赖 + skiko 的 dll/.sha256/icudtl.dat
  rt\                        58.6 MB    jlink 裁剪运行时
  netbird-multi.exe / wintun.dll        后端（外部输入）
  profiles\ / ui\ / Netbird\            用户数据
```

三条来自 jpackage 的硬事实：

- 启动器**只认** `<exe 同级>\app\<exe 文件名>.cfg`，路径关系改不了。
- cfg 里的 `app.runtime`、`app.classpath` 支持 `$ROOTDIR`（exe 所在目录）与 `lib\*` 通配符。
- `--runtime-image` 指向**空目录**时，jpackage 不 jlink、不拷 runtime，产物只剩启动器。
  这是「启动器与运行时解耦」的关键，**不是** hack：`--runtime-image` 本来就是官方参数。

## 任务链（本仓库实现，可照搬）

| 任务 | 关键参数 / 行为 |
|---|---|
| `linkRuntime` | `jlink --add-modules <清单> --strip-debug --no-header-files --no-man-pages --compress=zip-9`；有 `jmods/` 时加 `--module-path <jdk>\jmods`，没有就走 JDK 24+ 的 **JEP 493**（Temurin 27 属于这种） |
| `jpackageLauncher` | `jpackage --type app-image --runtime-image <空目录> --icon …`，input 只放一份 App.jar |
| `launcherBundle` | 拷 exe + **自写** cfg（`app.runtime=$ROOTDIR\rt`、`bin\App.jar`、`lib\*`） |
| `syncReleaseTest` | `Sync` 到 `<repo>\release-test`，`preserve` 用户数据与后端二进制 |
| `checkReleaseTest` | **只校验现状**（不同步）：结构、cfg 指向、`rt\` 模块集合、skiko 资产、jar 数量 |
| `verifyReleaseTest` | `syncReleaseTest` + `checkReleaseTest` 的校验 —— 出成品的唯一入口 |

校验必须能**挡住手工改坏**，否则等于没有。负向自检：把 `app\*.cfg` 的 `app.runtime` 改成
`$ROOTDIR\runtime`，`checkReleaseTest` 必须失败并指出缺哪一行；`verifyReleaseTest` 必须重建修复。
（本仓库实测：前者报 `cfg 缺少：app.runtime=$ROOTDIR\rt`，后者覆盖后校验通过。）

`Sync` 的 preserve 必须显式列出 `profiles/`、`ui/`、后端 exe 与 dll —— 否则同步一次就把用户数据删了。

## 裁剪：模块怎么定

先跑 `jdeps --print-module-deps --ignore-missing-deps --multi-release 21 lib\*.jar` 拿静态闭包，
再**手工补静态分析看不见的那几类**：

| 类别 | 例子 | 漏了会怎样 |
|---|---|---|
| 反射 / 服务加载 | `jdk.unsupported`（`sun.misc.Unsafe`） | skiko / 协程在运行期才炸 |
| 字符集 | `jdk.charsets`（GBK） | `Charset.forName("GBK")` 抛异常，或静默退回 UTF-8 乱码 |
| 平台服务 | `jdk.crypto.ec`（TLS ECDHE） | HTTPS 握手失败 |
| 配置解析 | `java.xml`（JAXP） | logback 读 XML 配置时 `NoClassDefFoundError` |
| 本地偏好 | `java.prefs` | 主题/窗口偏好写不进去 |

**可以省的两类**（本仓库实测）：`jdk.localedata`（界面文案硬编码中文、日志只用数字格式，
省 ~8 MB；一旦用月份名/星期名这类 locale 数据就必须加回来）、`java.naming`（logback 的 JNDI
分支不触发，`LoggerContext` 初始化与日志输出正常）。

实测：jpackage 默认 runtime **85.6 MB** → 9 个模块 jlink **58.6 MB**（-31.5%）。

## 坑（都只在打包态暴露）

| 现象 | 根因 | 做法 |
|---|---|---|
| skiko 启动即崩（`EXCEPTION_ILLEGAL_INSTRUCTION`），日志只有 `SkLoadICU: datafile ... is missing` | `icudtl.dat` 没跟 dll 一起放到 `-Dskiko.library.path` 指向的目录 | 从 skiko runtime jar 解压时带上 `*.dat` |
| `Sync` 报 `Access denied`，覆盖 exe 失败 | jpackage 产出的 exe 带 **ReadOnly** 属性 | 覆盖前 `setWritable(true)`；递归删除也要先清只读 |
| 启动器找不到 JVM / 直接退出 | cfg 里 `app.runtime` 指错，或 `rt\` 被删/改名 | `rt\` 是硬依赖，校验 `rt\bin\server\jvm.dll` |
| 启动后 `NoClassDefFoundError` | `runtimeModules` 少了反射/服务加载用的模块 | 按上表补；用裁剪 runtime 做冒烟 |
| `jlink` 报找不到模块 / 产物是空 | 拿的是 JRE（没有 `bin\jlink.exe`），或 JDK 既无 `jmods/` 又没开 JEP 493 | 换成官方 JDK（Temurin 27 无 `jmods/`，靠 JEP 493 从运行时镜像裁剪）；构建里保留这条回退，拿 JRE 时直接失败而不是静默跳过 |
| 单独用 `java -cp` 起成品就崩 skiko | 少了 `-Dskiko.library.path` / `-Dcompose.application.configure.swing.globals=true` | 从 `app\*.cfg` 抄参数，不要自己拼 |
| 换了图标，exe 还是旧图标 | ① 没重新 jpackage；② Windows 图标缓存 | 确认 exe 时间/大小变了，再 `ie4uinit.exe -show`；仍旧则重启 explorer |

## 图标

- **源**是 SVG（形状来自官方 mark，或自绘），颜色改动只改 SVG。
- 用脚本一次生成 **16/24/32/48/64/128/256 PNG + 多尺寸 ICO**；ICO 里直接塞 PNG 数据即可，
  Windows 10+ 支持。
- 本机没有 ImageMagick/Inkscape/rsvg 时，用 **JDK 的 Java2D** 自己解析 SVG path
  （只需 `M/L/H/V/C/Z`），不要为一张图标引第三方依赖。
- **同时设置两处**：`jpackage --icon`（exe 图标）与 Compose `Window(icon = …)`（标题栏）；
  托盘图标从同一份 PNG 加载，避免三处不一致。
- 验证 exe 图标：`[System.Drawing.Icon]::ExtractAssociatedIcon($exe)` —— 它读文件，不信缓存。

## 验证工作流（不要每次弹 UAC）

1. **不需要 UAC 的等价启动**：用成品的裁剪 runtime 直接跑，参数从 `app\*.cfg` 抄：
   ```powershell
   cd release-test
   .\rt\bin\java.exe -Djpackage.app-version=1.0.0 --enable-native-access=ALL-UNNAMED `
     -Dcompose.application.configure.swing.globals=true `
     -Dcompose.application.resources.dir=$PWD\bin -Dskiko.library.path=$PWD\lib `
     -cp "bin\App.jar;lib\*" <主类>
   ```
   这样验证的是 **rt + jar 镜像 + skiko 路径**，唯独没验证启动器 exe。
2. **模块探测**：写一个只跑 JDK API 的小类（GBK / prefs / Unsafe / AWT / JAXP / TLS），
   用 `rt\bin\java.exe` 跑；再加载一次 Ktor/logback 的关键类。
3. **启动器本体**：打包态会自己请求管理员权限（`ShellExecuteExW`），必须**经用户同意**再弹 UAC。
   启动后用 `MainWindowTitle` 找到实际 UI 进程截图；关闭它同样需要 UAC。
4. **截图与注入**：见技能 `dev-desktop-gui-e2e-testing`。提权窗口（UIPI）普通权限注入无效，
   别去点它。

## CI 自动发布（本仓库）

`.github/workflows/desktop-ui-release.yml` 把上面这条链搬到 windows-latest：

1. `actions/setup-java` 装 Temurin 27（**不要**换成 JBR：`rt\` 与开发态必须是同一份运行时）；
2. 下载 wintun 0.14.1（带 sha256 校验）并 `go build` 出 `netbird-multi.exe`；
3. `gradlew :composeApp:verifyReleaseTest` —— jlink（有 `jmods/` 用，没有走 JEP 493）与
   jpackage 只产启动器都在这里发生；
4. `desktop-ui/scripts/package-release.ps1` 打 zip（顶层 `netbird-multi-ui\`，不含用户数据）；
5. `gh release create/upload`（tag 已存在则覆盖资产）。

本地与 CI 共用同一个打包脚本，**不要**在 workflow 里另写一套复制/压缩逻辑 —— 两套必然漂移。

```powershell
# 本地等价动作（需先有 release-test）：
pwsh -NoProfile -File desktop-ui\scripts\package-release.ps1 -Tag v0.1.2-0.82.0
```

## 反例

| 做法 | 为什么错 |
|---|---|
| 用 `createDistributable` 出成品 | 每次 jlink 完整 runtime，布局与成品不同，等于两套结构 |
| 手工把 jlink 产物拷进 app-image 的 `runtime\` | 依赖「jpackage 不校验内容」，与 cfg 的 `app.runtime` 语义脱节；升级 JDK 后容易漏 |
| 手改 `app\*.cfg` 调试 | 下次同步被覆盖；参数漂移无人可查 |
| 只测 Gradle/JDK 里的 `java` | 模块缺失只在裁剪 runtime 暴露 |
| 加依赖不改 `runtimeModules` | 开发态永远正常，打包态才 `NoClassDefFoundError` |
| 换图标只改 SVG 不重跑脚本/jpackage | PNG/ICO 与 exe 都是生成物 |
| 用窗口标题 `NetBird*` 找窗口注入 | 会打到上游 Wails 客户端或旧实例上（见 e2e 技能 §3.1） |

## 相关

- [`compose-multiplatform-desktop`](../compose-multiplatform-desktop/SKILL.md) —— 桌面端 UI 本身的坑
- [`dev-desktop-gui-e2e-testing`](../dev-desktop-gui-e2e-testing/SKILL.md) —— 真机驱动与截图
- 本仓库的**规则**（强制流程、验收表）在 `desktop-ui/AGENTS.md` §5/§7
