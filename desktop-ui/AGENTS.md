# AGENTS.md — desktop-ui（NetBird 桌面端 UI）

> 本目录是 NetBird 客户端的**桌面前端**（Kotlin / Compose Multiplatform，Windows 优先）。
> 仓库根 `AGENTS.md` 的通用规则（语言、Go 侧约定、提交纪律）**继续适用**；
> 本文件只写**本目录追加的规则**，两处冲突时以本文件为准（仅限 `desktop-ui/` 之内）。
>
> 一句话：**UI 只是 daemon 的一个客户端。** 网络栈、DNS、路由、账号全在 daemon 里，UI 不碰。

---

## 0. 首要规则

- **语言**：对话与思考用中文；代码标识符、命令、路径、配置键名、报错原文保持英文原样。
- **读不到被引用的文件 → 立即停止并报告缺失的文件名**，不要凭记忆或同类经验补全。
- **Go 树只读**：`client/**/*.go`、`client/proto/**`、`management/`、`shared/` 一律不改。
  UI 的需求优先在 UI 侧解决；确需改 daemon 的，走 `patchdoc/` 写方案、用户拍板，不在同一次提交里混做。

---

## 1. 项目定位

给**已经在跑的** NetBird daemon（Windows 服务 `Netbird`）做一个桌面外壳：看状态、连/断、
选实例、选网络（Networks）、改少量设置、看事件与 peer 列表。桌面端比一个带聊天/编辑器/终端的
CMP 项目 **简单得多** ——
没有聊天、没有编辑器、没有终端、没有 Markdown。

上游的 Wails 前端（`client/ui/frontend/`，React + Tailwind）**不是基线**，只作为两个用途：

1. **能力清单参考**：`client/ui/frontend/WAILS-API.md` 记录了上游 UI 用到哪些 daemon 能力、
   状态怎么流转 —— 读它比自己从 proto 推快（但**字段名不可照抄**，见 §3.6）。
2. **视觉参考**：配色与信息层级可以借鉴，**不做像素复刻**，用 Material 3 原生组件。

### 非目标（明确不做，不要"顺手实现"）

| 不做 | 原因 |
|---|---|
| 改 daemon 行为 / 改 proto / 加 RPC | 契约只读；改了会与上游分叉，且每次 rebase 都要复验 |
| 移动端（Android / iOS）源集 | 桌面端优先；多端登录由多个 daemon 实例解决，不需要移动 UI |
| 多语言 | 只做简体中文 |
| WebView / 内嵌 React | 上游 Wails 前端的价值是"能参考"，不是"要保留"；引入 WebView 等于重开一个 Electron |
| 托盘之外的常驻后台服务 | daemon 已经是 Windows 服务；UI 退出不影响连接，不要再造一个服务 |
| 自实现 WireGuard / DNS / 路由逻辑 | 全部来自 daemon 的 Status |
| 权限提升 / UAC 自动化 | 特权操作用户自己用管理员 CLI 做（见 §3.3） |

---

## 2. 技术栈（版本以 `desktop-ui/gradle/libs.versions.toml` 为准）

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 / 运行时 | Kotlin 2.4.x + JDK 25（Temurin；本机路径见 `.local/environment.md`） | 编译目标 21 |
| UI | Compose Multiplatform 1.12.x + Material 3 | 单窗口 + 托盘，不做自适应三栏 |
| 网络 | Ktor client（CIO）+ `content-negotiation` + `kotlinx-serialization-json` | 只要 HTTP/JSON，**不需要 WebSocket / gRPC** |
| 序列化 | kotlinx-serialization-json | DTO 手写（proto 生成 Kotlin 的成本不划算，见 §3.6） |
| 并发 | kotlinx-coroutines（`StateFlow` 做主状态） | |
| 本地偏好 | multiplatform-settings | 窗口位置、上次选中实例、托盘行为 |
| ViewModel | `lifecycle-viewmodel-compose` | 可选；简单页面用普通类 + `StateFlow` 也可以 |
| 日志 | kotlin-logging + logback（desktopMain） | 落到 `<用户目录>\AppData\Roaming\netbird-ui\logs\` |
| 打包 | Compose desktop `packageDistributionForCurrentOS`（MSI）| 二期再做 |

**不引入**：Markdown 渲染、代码高亮、终端、Coil、adaptive-layout、WebView、i18n 框架、
React 生态的缓存库（react-query 一类）。理由：这个 UI 没有这些功能域，多一个依赖多一份许可证与
升级负担。

**新增依赖的门槛**（与另一个 CMP 项目一致）：先查 `search.maven.org` 能否解析、查许可证、
查目标平台已知 issue。CMP 生态"README 写了坐标、仓库里没有"是常态。

---

## 3. 与 daemon 的契约（本文件最重要的一节）

> 决定 UI 架构的只有一个事实：**daemon 是 Windows 上的系统服务，通过命名管道对外提供 gRPC，
> 而它自带一个 HTTP/JSON 网关。UI 走网关。**

### 3.1 唯一通道：grpc-gateway 的 HTTP/JSON

daemon 支持把 `DaemonService` 暴露成 REST 风格 JSON（grpc-gateway）：

```
POST http://127.0.0.1:<port>/daemon.DaemonService/<Method>
Content-Type: application/json
Body: {} 或该方法的请求字段
```

- 路径由 `generate_unbound_methods=true` 生成 ⇒ **每个 RPC 都有默认路径**：
  `client/proto/generate.sh`；路由存在性由 `client/proto/daemon_gateway_test.go` 断言。
- 开启方式（daemon 启动参数，或 `netbird service install` 持久化）：
  `--enable-json-socket --json-socket tcp://127.0.0.1:<port>`
  （`client/cmd/service.go:86-87`）
- 默认值是 `unix:///var/run/netbird-http.sock` —— **在 Windows 上没有意义**，必须显式给 `tcp://`。

### 3.2 不要试图连命名管道

- Windows 上 daemon 默认只监听 `npipe://netbird`（`client/internal/daemonaddr/resolve_pipe_windows.go`），
  且**在管道不应答时不会自动回落到 TCP**（有意为之，见该文件注释）。
- Kotlin/JVM 没有生产可用的 gRPC-over-named-pipe 传输。自己实现一条 = 自己维护一个 transport，
  远超本项目收益。**结论：UI 一律走 §3.1 的 TCP JSON 网关。**

### 3.3 TCP 通道不携带调用者身份 ⇒ 特权 RPC 会被拒

`daemonaddr.CarriesIdentity` 只认 `unix://` 与 `npipe://`（`client/internal/daemonaddr/identity.go`）；
TCP 上 daemon 无法确认调用者是谁，于是**特权操作返回 `PermissionDenied`**：

| 会被拒的操作 | 位置 |
|---|---|
| 改 SSH 相关选项的 `Up`（`--enable-ssh-root` / `--allow-server-ssh` / `--allow-remote-jobs` / 本地指标 / 改 management URL） | `client/server/ssh_gate.go:87-143` |
| debug bundle 上传与打开调用者文件 | `client/server/debug_gate.go` |
| peer 抓包（`StartCapture` / `StartBundleCapture`） | `client/server/capture.go:81` |

**UI 规则**：这些能力**不做入口**，或做成"提示 + 一条可直接复制的管理员 CLI 命令"
（daemon 的 `PermissionDenied` 消息里已经带了 `netbird …` 命令原文，直接展示即可，不要自己拼命令）。

### 3.4 流式 RPC：按行解析，不要等结束

`SubscribeStatus` / `SubscribeEvents` / `StartCapture` / `ExposeService` 是 server-streaming，
网关把它们写成**一块持续输出的 JSON 行流**（不是数组、不会结束）：

- 用 Ktor 的 `HttpStatement` + `bodyAsChannel()` 逐行读，一行一帧解析；
- **`SubscribeStatus` 的第一帧就是当前快照**（`client/proto/daemon.proto:28-31` 注释），
  UI 连上即可渲染，**不需要**先单独调一次 `Status`；
- 流断掉（daemon 重启 / 实例切换）要自动重连并退避，同时把 UI 置为合成的 `DaemonUnavailable`。

### 3.5 状态机以 daemon 为准

`StatusResponse.status` 的取值（`WAILS-API.md` 的枚举说明 + `client/internal/state.go`）：

`Idle` / `Connecting` / `Connected` / `NeedsLogin` / `LoginFailed` / `SessionExpired`

加上 **UI 合成**的 `DaemonUnavailable`（网关连不上时由 UI 产生，daemon 不会返回它）。
不要自造状态名写进 DTO 以外的层；`Error` 是上游托盘自用的合成值，UI 不引入。

### 3.6 字段名以**实测 JSON** 为准

protojson 会把字段名改成 `json_name`（下划线转驼峰、个别全大写字段保留原样，如 `IP`、
`mDMManagedFields`、`disable_notifications` 这类混合命名）。所以：

- 首版 DTO **照抄一次 `curl` 的真实输出**，不要照 `WAILS-API.md` 的 TS 类型抄（那是 Wails 层的投影）；
- unknown 字段必须在解析器里忽略（daemon 升级会加字段）：`Json { ignoreUnknownKeys = true }`；
- 新增字段前先跑：

```bash
curl -s -X POST http://127.0.0.1:8471/daemon.DaemonService/Status \
  -H 'Content-Type: application/json' -d '{}'
```

---

### 3.7 `Up` 必须传 `async: true`

`UpRequest.async` 默认 `false` ⇒ RPC **阻塞到引擎就绪**（CLI 需要这个语义），UI 会表现为"点击后没反应"。
UI 一律 `{"async": true}`，进度看 `SubscribeStatus`（`client/proto/daemon.proto:276-281`）。

---

## 4. 硬约束（每条都对应一次实测或明确决策）

1. **每个 daemon 实例一条通道，端口不写死。** 本仓库的部署形态是多实例并存（见技能
   `netbird-multi-instance`）：`<root>\profiles\<name>\instance.json` 描述每个实例。UI 的实例列表
   来自这里，`baseUrl` 也来自这里（或由 UI 配置文件覆盖）。**不得假设"只有一个 daemon"。**
2. **不要在代码里扫端口**。端口属于部署配置；扫端口会在别人的机器上连错实例，也掩盖配置错误。
3. **UI 单实例**：重复启动必须把已有窗口提到前台（命名 mutex 或本地锁文件），不要开两个窗口连同一个 daemon。
4. **关闭窗口 = 最小化到托盘**，退出走显式菜单项。UI 退出**不影响**连接 —— daemon 是系统服务，
   这条要写进 UI 文案，避免用户以为"关了 UI 就断网"。
5. **不得在 `@Composable` 里发请求**：`Repository`（持有 `HttpClient`）+ `StateFlow`；
   副作用走 `LaunchedEffect` / ViewModel 的协程。
6. **不碰 Go 产物路径**：Gradle 输出只能落在 `desktop-ui/build`、`desktop-ui/.gradle`；
   不要把 `.exe` / MSI 写进 `client/`。`desktop-ui/` 下**不能出现 `.go` 文件**（否则 `go build ./...` 会扫进来）。
7. **`local.properties` 不进 git**（JDK 路径是本机的，可能含用户名）；仓库里只放 `gradle.properties` 的公共配置。
8. **不做像素复刻**：视觉稿只是参考；用 M3 组件与默认主题，间距/圆角允许不同。判定顺序：
   功能对等 > 原生观感 > 视觉接近。

---

## 5. 构建与运行

```bash
cd desktop-ui

# 本机 JDK 25：在 local.properties 里写绝对路径，文件不进 git（已忽略）
#   netbird.jdk.dir=<jdk-home>                     # 本机取值见 .local/environment.md
# 编译目标 21（jvmTarget），运行靠 JDK 25 即可。

./gradlew :composeApp:run            # 运行 UI
./gradlew :composeApp:compileKotlinDesktop  # 只编译（快速检查；compileKotlin 在 KMP 下是歧义任务）
./gradlew build                      # 编译 + 测试
```

**本机网络注意**（Windows / MSYS bash，本机取值见 `.local/environment.md`）：

- Gradle 发行版走腾讯镜像（见 `gradle/wrapper/gradle-wrapper.properties`），依赖走腾讯云 / 阿里云
  maven（见 `settings.gradle.kts`）。**默认不开代理**；需要时 `-Dhttps.proxyHost=<proxy-host> -Dhttps.proxyPort=<proxy-port>`
  （本机取值见 `.local/environment.md`）。
- GitHub 直连不通，clone / submodule 要显式带代理。
- 跑 PowerShell 用 `pwsh`（7.x），不要用 `powershell`（5.1）。

**开发时要先有 daemon 网关**（否则 UI 只能是 `DaemonUnavailable`）：

```powershell
# 对已装服务持久化（需要管理员；改完重启服务）
netbird service install --enable-json-socket --json-socket tcp://127.0.0.1:8471
# 或临时前台起一个实例（不要动生产实例）
```

> 端口约定：**8471 起，每实例 +1**（写进实例编排脚本；见技能 `netbird-desktop-ui-contract`）。

---

## 6. 编码规范

- Kotlin 官方风格；4 空格缩进；文件名 = 顶层声明名。
- 包名 `dev.shinglem.netbird.ui`，分层：
  `api/`（`DaemonClient`、`@Serializable` DTO、错误映射）· `data/`（`Repository`，管实例与连接状态）
  · `feature/`（`status` / `peers` / `networks` / `settings`）· `screen/`（页面与 `Theme.kt`）·
  `desktopMain/`（入口、托盘、单实例锁、日志）。
- 状态用 `sealed interface` 表达（`UiState.Loading / Ready / Unavailable(msg)`），不用 boolean 堆砌。
- 所有字符串中文，集中放在 `ui/Strings.kt`（`object Strings`），**不要散落在深层组件里**。
- 时间：daemon 给的是 protobuf `Timestamp` → 网关输出 RFC3339 字符串，**统一在 DTO 层转 `Instant`**，
  组件里不要自己 parse 字符串。
- 新增文件一律 LF。

---

## 7. 验收（每条都能跑）

| 层 | 怎么验 |
|---|---|
| 网关可达 | `curl -s -X POST http://127.0.0.1:8471/daemon.DaemonService/Status -H 'Content-Type: application/json' -d '{}'` 返回含 `status` / `daemonVersion` 的 JSON |
| 流式 | 同上但打 `/SubscribeStatus`：`curl -N` 能持续吐行，第一帧即快照 |
| 编译 | `./gradlew :composeApp:compileKotlinDesktop` 无警告即通过 |
| 状态渲染 | daemon 停止 → UI 显示 `DaemonUnavailable`；启动 → 自动恢复到真实状态 |
| 连接控制 | UI 的 `Up` / `Down` 后 `Status.status` 变化与 UI 一致（用 curl 交叉验证，不信 UI 自报） |
| 多实例 | 至少两个实例各配一个端口，UI 能切换且不串状态 |
| 托盘 | 关闭窗口后进程仍在、托盘可恢复；退出菜单项真的退出 |

GUI 级别的自动化（真实窗口驱动、截图断言）见技能 `dev-desktop-gui-e2e-testing`。

---

## 8. 技能与文档索引

| 路径 | 内容 |
|---|---|
| `.ai/skill/README.md` | **技能清单唯一处**（本目录用哪些技能、来源与同步方式） |
| `.ai/skill/netbird-desktop-ui-contract/` | **先读这个**：daemon 契约、实例编排、验收命令（含 `references/daemon-api.md` 的完整 RPC 与字段表） |
| `.ai/skill/netbird-multi-instance/` | 多实例部署形态（wintun GUID / NRPT 前缀 / 环境变量），UI 的实例发现基于它 |
| `.ai/skill/compose-multiplatform-desktop/` | 写 CMP 桌面代码前的坑（IME、滚动、窗口 resize、拖放） |
| `.ai/skill/dev-desktop-gui-e2e-testing/` | 脚本驱动真实窗口做端到端验证 |
| `.ai/skill/global-git-ops/` | 本机 git 纪律：代理、提交前自查、上游同步 |
| `patchdoc/` | 本 fork 的**变更记录与证据**（规则在根 `AGENTS.md` 的「本 fork 的改动」节）；UI 改动若触及 Go 侧，必须在这里追加一份 |

---

## 9. 多 agent 接入

- 规则权威源：**本文件 + 仓库根 `AGENTS.md` 的「桌面 UI」小节**（后者只是入口与硬约束摘要，
  细则在这里 —— 两份都不要复制第三份）。
- 技能单一事实源：`.ai/skill/<技能名>/SKILL.md`，**目录名 = frontmatter `name`**，必须是 `.ai/skill/` 的一级子目录
  （VS Code 的技能发现只扫一层）。
- VS Code / Copilot：`.github/copilot-instructions.md` 是指针文件，`.github/skills` 是指向 `../.ai/skill`
  的符号链接（整目录共享，新增技能无需改链接）。符号链接的自检与修复见该指针文件。
- Claude Code：根 `CLAUDE.md` 用 `@AGENTS.md` 导入根规则；在本目录工作时加载本文件。
- Codex / opencode / 其他：原生读 `AGENTS.md`（就近优先）。

### 任务路由

| 任务 | 先读 |
|---|---|
| 接/改 daemon 通道、加一个 RPC 调用 | 技能 `netbird-desktop-ui-contract` + `references/daemon-api.md` |
| 起一个可调试的 daemon 实例（带 json-socket） | 技能 `netbird-desktop-ui-contract` §启动参数矩阵 |
| 写 Compose 界面、选组件、踩到渲染/IME/滚动的坑 | 技能 `compose-multiplatform-desktop` |
| 脚本驱动窗口验收 | 技能 `dev-desktop-gui-e2e-testing` |
| 加依赖、升版本、Gradle 报错 | `desktop-ui/gradle/libs.versions.toml` + 本文件 §2 的依赖门槛（Kotlin 工具链类技能**未引入**，见 `.ai/skill/README.md`） |
| 提交 / 推送 / 跟上上游 | 技能 `global-git-ops` + 根 `AGENTS.md` 的 fork 小节 |
| 要改 daemon 或 proto | **先停**：读 `patchdoc/README.md`，写方案给用户 |
