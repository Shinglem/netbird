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
| 自己绕过 UAC | 只走 `ShellExecuteExW("runas")`（双击时弹一次 UAC）与计划任务（登录时不弹），见 §5 |

---

## 2. 技术栈（版本以 `desktop-ui/gradle/libs.versions.toml` 为准）

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 / 运行时 | Kotlin 2.4.x + JDK 25（Temurin；本机路径见 `.local/environment.md`） | 编译目标 21 |
| UI | Compose Multiplatform 1.12.x + Material 3 | 单窗口 + 托盘，不做自适应三栏 |
| 网络 | Ktor client（CIO）+ `content-negotiation` + `kotlinx-serialization-json` | 只要 HTTP/JSON，**不需要 WebSocket / gRPC** |
| 序列化 | kotlinx-serialization-json | DTO 手写（proto 生成 Kotlin 的成本不划算，见 §3.6） |
| 并发 | kotlinx-coroutines（`StateFlow` 做主状态） | |
| 本地偏好 | 结构化 JSON（`ui\` 两层） | 界面配置落 `<home>\ui\ui.json`（默认值，随版本覆盖）与 `ui\ui.custom.json`（用户覆盖，升级不丢）；**不写注册表**，便携版整目录搬走仍有效 |
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

### 3.8 `GetConfig` / `SetConfig` 必须带 profile handle

空 handle 会返回 500 `resolve profile: profile handle is empty` —— **`--disable-profiles` 也不会
回落到当前 profile**。每个 daemon 只服务一个配置，handle 就是配置文件名去掉 `.json`
（`config/default.json` → `default`），由 `DaemonClient` 构造时带上（`DaemonPlan.profileHandle`）。

`Logout` 相反：空请求体走「当前 profile」分支，带上 `profileName` 反而会因为缺 `username` 报
`InvalidArgument`。

### 3.9 注销在 SSH 声明开启时会被拒

`profilemanager` 给缺省的 `ServerSSHAllowed` 补 `true`（`sshServerEnabled` 的 nil-means-on），
此时 `Logout` 被当作特权操作，而回环网关验证不了调用方身份 ⇒ `PermissionDenied`
（消息里带 `Reinstalling the service on a socket that carries the caller's identity`）。

- 本 fork 的 `DaemonPlan.configJson` **显式写 `ServerSSHAllowed: false`**（这些实例不提供 SSH 服务）；
- 既有实例要**重启一次**才生效：`SetConfig` 只写盘，daemon 内存里的 `s.config` 不会更新；
- UI 在收到这条拒绝时给出「关闭「允许 SSH 服务」，重启并重试注销」按钮。

### 3.10 `Login` / `Logout` 是长操作

它们要连 management 做探测、注册或注销，正常网络也可能超过 5 秒。`DaemonClient` 给这两个 RPC
单独的 120 秒超时。用默认的 5 秒会把正常登录判成 `Request timeout has expired`，Ktor 一断开，
daemon 侧的上下文就被取消，状态里随后冒出 `wait for signal stream: context canceled` ——
那两条错误是超时的后果，不是独立故障。

### 3.11 登录期间的状态文案不能宽匹配

未注册实例的 `managementState.error` 常态就是
`no peer auth method provided, please use a setup key or interactive SSO login`，
**里面含 "setup key"**。用它判断"key 无效"会把每次登录都当场取消，daemon 日志只留一行
`failed creating connection to Management Service: context canceled`（点击后几十毫秒）。

只认确定结论：`couldn't add peer: setup key is invalid`（`isInvalidSetupKeyError`）。
同理，「no peer auth method provided」不该作为红色错误横幅常驻 —— 登录卡已经说明了它。

---

## 4. 硬约束（每条都对应一次实测或明确决策）

1. **每个 daemon 实例一条通道，端口不写死。** 本仓库的部署形态是多实例并存（见技能
   `netbird-multi-instance`）：`<root>\profiles\<name>\instance.json` 描述每个实例。UI 的实例列表
   来自这里，`baseUrl` 也来自这里（或由 UI 配置文件覆盖）。**不得假设"只有一个 daemon"。**
2. **不要在代码里扫端口**。端口属于部署配置；扫端口会在别人的机器上连错实例，也掩盖配置错误。
3. **UI 单实例**：重复启动必须把已有窗口提到前台，不要开两个窗口连同一个 daemon。
   实现是两半：`SingleInstanceLock`（`<home>\instance.lock` 文件锁，判在位）+
   `SingleInstanceSignal`（`Local\NetBirdUI-<home hash>-activate` 自动重置事件，唤醒并显示窗口）。
   登录自启的 `--silent` 触发是唯一例外：直接退出，不抢前台。
4. **关闭窗口 = 最小化到托盘**，退出走显式菜单项。UI 退出**不影响**连接 —— daemon 是系统服务，
   这条要写进 UI 文案，避免用户以为"关了 UI 就断网"。
5. **不得在 `@Composable` 里发请求**：`Repository`（持有 `HttpClient`）+ `StateFlow`；
   副作用走 `LaunchedEffect` / ViewModel 的协程。
6. **不碰 Go 产物路径**：Gradle 输出只能落在 `desktop-ui/build`、`desktop-ui/.gradle`；
   不要把 `.exe` / MSI 写进 `client/`。`desktop-ui/` 下**不能出现 `.go` 文件**（否则 `go build ./...` 会扫进来）。
7. **`local.properties` 不进 git**（JDK 路径是本机的，可能含用户名）；仓库里只放 `gradle.properties` 的公共配置。
8. **不做像素复刻**：视觉稿只是参考；用 M3 组件与默认主题，间距/圆角允许不同。判定顺序：
   功能对等 > 原生观感 > 视觉接近。

### 4.1 界面配置（`ui\ui.json` + `ui\ui.custom.json`）

界面自己的配置只覆盖一件事：外观与登录自启。两层的分工是**升级不丢用户设置的前提**：

```text
<home>\ui\
  |- ui.json          默认值，随版本发布并被覆盖；用户不要手改（启动时会被重写）
  |- ui.custom.json   用户覆盖，只存与默认值不同的键；升级替换目录后仍然生效
  |- state.json       运行态：每个配置的「随界面启动」，由界面每次启停时重写
```

`ui.json`（程序写入）：

```json
{
  "theme": "material",
  "themeMode": "system",
  "closeToTray": true
}
```

`ui.custom.json`（只有用户改过的键）：

```json
{
  "theme": "ocean",
  "themeMode": "dark",
  "autostart": true,
  "closeToTray": false
}
```

- 读 = 默认值 ⊕ 覆盖层；写 = 只把与默认值不同的键落进覆盖层。全部改回默认值时覆盖层文件会被删除，
  这样以后版本改掉的默认值对该用户依然生效。
- **`<home>` 是启动器所在目录**（打包态），`ui\` 与 `config.json` 都相对它解析，
  所以整个目录搬走（便携版、换机器）配置仍然有效。开发态进程是 JDK 的 `java.exe`，
  回落到 `%APPDATA%\netbird-ui`；`NETBIRD_UI_HOME` 可显式覆盖。
- **`autostart` 是期望值，不是系统状态的镜像**：启动时与计划任务 `NetBirdUI` 对齐。
  两层里都没有这个键（首次运行）时沿用系统现状再写回 —— 用脚本手工装过自启的旧部署
  不会被默认值悄悄删掉；开发态（`Unsupported`）则完全不碰。
- 主题与明暗改完立即落盘；写失败只在设置页顶部提示，界面照常生效。
- **部署路径不在这里**：`config.json` 的 `root` / `binary` 才是，且可以写相对路径
  （相对 `<home>`），写死绝对路径的配置一换机器就失效。
- 不写注册表。旧版本的偏好若还留在 `java.util.prefs` 里，不会再被读取。

### 4.2 daemon 生命周期（界面托管）

界面是 daemon 的唯一管理者，模型与 hermes-compose 一致：

- **启动界面时**：`ProfilesController.startAutostart()` 按 `ui\state.json` 的记录拉起配置，
  没有记录的新配置按「随界面启动」处理。失败不自动重试 —— 缺 wintun、非管理员这类原因不会自愈。
- **开关在实例自己的面板里**：选中某个配置后，内容区顶部的实例栏显示它的名称、daemon 状态与
  「随界面启动」开关。它属于这个配置，不放在全局设置页。
- **退出界面**（托盘「退出」）：`controller.shutdown()` 逐个 `terminate` 本进程拉起的 daemon。
  不扫进程表、不按名字杀，所以碰不到官方安装器或别的部署。
- **关闭窗口 ≠ 退出**：默认只收进托盘（`closeToTray`，可在「桌面」页关掉），daemon 继续跑。
- **登录自启是静默的**：计划任务 `NetBirdUI` 带 `--silent`（`--tray` 是别名），
  此时主窗口不进入组合，只有托盘图标；托盘装不上时会自动把窗口显示出来，
  否则界面等于彻底消失。旧版本装的任务不带这个参数，`reconcileAutostart` 启动时重建成新形式。
- **记录随操作走**：界面里点启动/停止/全部启动/全部停止都会改写 `ui\state.json`，
  所以「我停掉了它」就等于「下次界面启动不要自动起它」。删除配置时它的记录一并清掉。
- **不要写进 `instance.json`**：那个文件属于部署脚本（含 fqdn / domains），桌面端只读不改写。

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

**本机配置快照**（写 DTO / 调通道时对着真实配置，而不是照文档猜）：

```powershell
pwsh -NoProfile -File desktop-ui\scripts\sync-local-netbird.ps1
```

脚本读 `.local\env.ps1` 的实例清单，生成到 `.local\netbird\`：`instances.json`（各实例的
daemon 地址、建议网关端口、management URL、iface/端口、当前是否在跑）、每个实例的**脱敏**配置、
以及 `raw\`（含私钥，ACL 已收紧）。`.local/` 已被 `.gitignore` 忽略 ——
**这些取值不得抄回仓库的任何文档**，仓库文档只写占位符（见 `.local/README.md` 的占位符表）。

**管理员权限（两条路径，与 hermes 一致）**：daemon 要建 wintun 网卡、改路由与 DNS，必须由管理员
进程拉起。

| 场景 | 做法 | UAC |
|---|---|---|
| 双击启动器 | 不是管理员就 `ShellExecuteExW("runas")` 重启自己（`Elevation.relaunchElevated()`） | **弹一次** |
| 登录自启 | 计划任务 `HighestAvailable` + `InteractiveToken`，Task Scheduler 直接建进程 | 不弹 |

刻意**不给 exe 加 `requireAdministrator` manifest**：那会让每次登录也走加载器提权，计划任务
「不弹 UAC」的前提就没了。用户点「否」不是故障 —— 界面以普通权限继续，banner 说明 daemon 起不来。

**提权必须发生在占锁之前**：未提权进程如果先拿到单实例锁，`runas` 出来的新进程会看到锁被占用、
去唤醒那个正要退出的旧进程，形成活锁。`Main` 的顺序固定为「只读探测 → runas → 占锁」。

登录自启安装：

```powershell
# 需要管理员 PowerShell；开发态把动作指向 gradlew
pwsh -NoProfile -File desktop-ui\scripts\install-autostart-task.ps1 `
    -Command "<repo>\desktop-ui\gradlew.bat" -Arguments ":composeApp:run" `
    -WorkingDirectory "<repo>\desktop-ui"

# 打包产物（release-test 布局）
pwsh -NoProfile -File desktop-ui\scripts\install-autostart-task.ps1 `
    -Command "<repo>\release-test\netbird-multi-ui.exe"

pwsh -NoProfile -File desktop-ui\scripts\install-autostart-task.ps1 -Status
pwsh -NoProfile -File desktop-ui\scripts\install-autostart-task.ps1 -Remove
```

打包产物可以在界面的「桌面」页直接开关这条任务（`WindowsAutostart`，任务名同为 `NetBirdUI`）。
开发态没有 jpackage 启动器、也拿不到稳定的启动命令，界面里只显示「请用脚本安装」。

任务声明 `RunLevel=HighestAvailable` + `LogonType=InteractiveToken`，`ExecutionTimeLimit=PT0S`
（否则 72 小时到点会连带杀掉界面管辖的 daemon）。**任务只拉起界面**：每个配置的 daemon 仍是
界面的子进程（`ProfileSupervisor`），所以关窗口只收进托盘、daemon 继续跑。

**`<Arguments>` 必须是 `--silent`**：界面和脚本都默认写它，登录时只驻留托盘、不弹主窗口。
手写任务时漏掉它，每次开机都会跳出一个主窗口。开发态示例用 `-Arguments ":composeApp:run"` 覆盖
默认值（gradle 自己也有同名的静默日志开关，别把两者搞混）。

---

### release 结构：启动器 + 外挂运行时（对齐 hermes-compose）

`release-test/` **不是**一个 jpackage app-image，而是拆成三层：

```text
netbird-multi-ui.exe            原生启动器（jpackage 只产它，约 0.5 MB）
app\netbird-multi-ui.cfg        手写的启动配置：app.runtime=$ROOTDIR\rt
bin\App.jar                     本模块的类与资源
lib\*.jar                       全部依赖 + skiko 的 dll 与 icudtl.dat
rt\                             jlink 裁剪运行时
```

任务链（`composeApp/build.gradle.kts`）：

| 任务 | 作用 |
|---|---|
| `linkRuntime` | 用 `netbird.jdk.dir` 的 `jmods/` 跑 jlink：`--strip-debug --no-header-files --no-man-pages --compress=zip-9` |
| `jpackageLauncher` | jpackage 只生成启动器：`--runtime-image` 指向**空目录** |
| `launcherBundle` | 拷贝 exe + **自己写** cfg（runtime 外挂 `rt\`、classpath 指向 `bin\`/`lib\*`） |
| `syncReleaseTest` | `Sync` 到 `<repo>\release-test`，并 preserve `profiles/`、`ui/`、`Netbird/`、后端二进制 |

**为什么不用 jpackage 的 `--runtime-image` 直接打进 app-image**：那样每出一次包都要复制 60 MB，
而且 runtime 与启动器绑死；空 runtime + 自写 cfg 让 `rt\` 可以单独替换（换 JDK 只换一个目录）。
`createDistributable`（Compose 自带）只用于开发时快速看 app-image，**不参与 release**。

**实测体积**（Temurin JBR SDK 25.0.4.1）：jpackage 默认 runtime **85.6 MB** → jlink 裁剪后
**58.6 MB**（-31.5%，205 个文件）。

模块清单与理由（少一个就是运行期 `NoClassDefFoundError`，而且往往只在打包态暴露）：

| 模块 | 谁用 |
|---|---|
| `java.base` | 基础；jpackage 启动器也依赖 |
| `java.desktop` | AWT/Swing：窗口、托盘、字体渲染 |
| `java.datatransfer` | 剪贴板（BasicTextField 的复制粘贴） |
| `java.logging` | `java.util.logging`：若干库类初始化时引用 |
| `java.prefs` | `java.desktop` 的传递依赖（jlink 一定会带上）；本项目不直接用，列出来是为了与 `rt\release` 的 MODULES 保持一致 |
| `java.xml` | logback 配置解析（JAXP） |
| `jdk.crypto.ec` | TLS ECDHE 握手 |
| `jdk.charsets` | GBK：解码 `schtasks` 输出（Windows 控制台代码页） |
| `jdk.unsupported` | `sun.misc.Unsafe`（skiko / 协程兜底） |

**刻意不含**、并且已经实测过没有问题的：

- `jdk.localedata` —— 界面文案是硬编码中文，日志时间格式只用数字。将来若用**依赖 locale
  名称**的格式化（月份名、星期名），要加回来（约 +8 MB）。
- `java.naming` —— logback 的 JNDI 分支不触发：`LoggerContext` 初始化与日志输出实测正常。

**加/换依赖后的复验流程**（静态分析看不出反射与服务加载）：

```powershell
# 1. 静态闭包（在 release-test\lib 上跑；注意 Windows 通配符）
jdeps --print-module-deps --ignore-missing-deps --multi-release 21 lib\*.jar

# 2. 差值补进 runtimeModules，然后重建成品
./gradlew :composeApp:syncReleaseTest

# 3. 用裁剪后的 java.exe 做一次冒烟（不要只跑 Gradle 的 JDK）
.\rt\bin\java.exe -cp "bin\App.jar;lib\*" <Probe>
```

**三个只在打包态才会踩到的坑**（都已修，别改回去）：

- `icudtl.dat` 必须和 skiko 的 dll 一起解压到 `lib\`（`syncReleaseTest` 里 `include("*.dat")`）。
  少了它 skiko 的 `ParagraphBuilder` 会直接 abort，日志只有一句
  `SkLoadICU: datafile ... is missing`。
- `-Dskiko.library.path=$ROOTDIR\lib` 必须写进 cfg，且 dll/sha256/dat 就在那个目录。
- jpackage 产出的 exe 带 **ReadOnly** 属性：覆盖前要 `setWritable(true)`，否则 `Sync` 报
  `Access denied`（`forceDeleteTree()` 与 `doFirst` 里都做了）。

`jmods` 缺失时（用 `jbr-*` 纯运行时或普通 JRE 打包）`linkRuntime` 会跳过，`syncReleaseTest`
直接失败并提示换 `jbrsdk-*` —— 不会产出没有 `rt\` 的坏包。

---

### 发版流程（强制，不许绕）

出 Windows 成品只走这一条链：

```powershell
./gradlew :composeApp:build                        # 1. 编译 + 测试
./gradlew :composeApp:verifyReleaseTest            # 2. 同步 + 校验（内部先 syncReleaseTest）
pwsh -NoProfile -File desktop-ui\scripts\assemble-release-test.ps1 -SkipBackend   # 3. 补后端与配置
# 4. 冒烟：见技能 desktop-release-packaging §验证工作流
```

**禁止**：

- 用 `createDistributable` 出成品（它是开发态 app-image，runtime 未裁剪、布局不同）。
- 手工往 `release-test\` 里拷 `app\`/`bin\`/`lib\`/`rt\`，或手改 `app\*.cfg`。
- 手改 `scripts/make-icons.ps1` 产出的 PNG/ICO。
- 跳过 `verifyReleaseTest` 直接发成品。

**改了这些就必须重新出成品**（对应任务会自动重跑，但别绕过）：

| 改动 | 后果 |
|---|---|
| 加/换依赖 | 先按技能里的 `jdeps` 流程复核 `runtimeModules`，再 `verifyReleaseTest` |
| 换 JDK / 改 `netbird.jdk.dir` | `rt\` 由 jlink 产出，必须重跑；`jmods/` 缺失会直接失败 |
| 改图标 SVG | 先 `scripts/make-icons.ps1`，再 `verifyReleaseTest`（否则 exe 还是旧图标） |
| 改主类 / 包名 / JVM 参数 | 都在 `build.gradle.kts` 的常量与 cfg 里；重跑，不要手改 cfg |

`verifyReleaseTest` 会拦住：缺 `rt\`、cfg 被改坏、`rt\` 模块集合与 `runtimeModules` 不一致、
skiko 的 dll/`icudtl.dat` 缺失、`lib\` 依赖镜像不完整。它失败时**修根因**，不要改校验。

只想确认"现有目录有没有被手改过"时用 `:composeApp:checkReleaseTest`（不同步，只校验）；
它比 `verifyReleaseTest` 少一次重建，适合发出去之前最后一眼。

操作细节与打包态坑见技能 [`desktop-release-packaging`](../../.ai/skill/desktop-release-packaging/SKILL.md)。

---

### release-test/ —— 发版前的真实测试目录

`<repo>\release-test\` 是**真机验证的唯一目录**（整目录已被根 `.gitignore` 忽略）：

```text
release-test/
  |- profiles/                 后端配置：每个实例一个子目录（config\ + runfiles\）
  |- netbird-multi.exe         后端（自编译的多实例版本）
  |- wintun.dll                后端依赖，必须与 exe 同目录
  |- netbird-multi-ui.exe      UI 启动器（jpackage 只产它）
  |- ui\                       界面配置：ui.json（默认值）+ ui.custom.json（用户覆盖）
  |- app\netbird-multi-ui.cfg  启动配置：app.runtime=$ROOTDIR\rt
  |- bin\App.jar               本模块的类与资源
  |- lib\*.jar                 全部依赖 + skiko 的 dll/icudtl.dat
  |- rt\                       jlink 裁剪运行时（别手改，由 Gradle 产出）
  |- ui/                       UI 侧资源（图标 / 主题等，当前为空）
```

装配（可重复执行；`app/`、`bin/`、`lib/`、`rt/` 由 Gradle 每次镜像，
`profiles/`、`ui/`、`Netbird/`、后端二进制会被 preserve）：

```powershell
# 脚本内部会先跑 :composeApp:verifyReleaseTest（= syncReleaseTest + 校验）
pwsh -NoProfile -File desktop-ui\scripts\assemble-release-test.ps1
# 只刷新后端与配置、不动 Gradle：-NoBuild
# 后端 exe 正被运行中的 daemon 占用：-SkipBackend
# 后端不在 .local\env.ps1 的实例清单里：-BackendDir <dir>
# 要带一份既有实例：-ProfilesFrom <profiles 目录>
```

四条不可违反：

1. **自包含**：`netbird-multi-ui.exe` 同目录有 `profiles\` 时，UI 的实例根就取这里
   （`AppPathsResolver` 的第 3 来源），不需要 `NETBIRD_UI_ROOT`，也不会去抢别的部署根。
2. **`rt\` 不是可选**：cfg 里 `app.runtime=$ROOTDIR\rt`，而启动器只认 `app\<名字>.cfg`；
   删了 `rt\` 或改了目录名，exe 直接打不开。
3. **`app\`、`bin\`、`lib\`、`rt\` 都是构建产物**，不要手改；改名就改 `build.gradle.kts` 的
   `appImageName`（它同时是 exe 名与 cfg 名）再重跑装配脚本。
4. **跑之前先关掉这里的 UI**：脚本会拒绝写入正被占用的目录（否则 exe/`app/` 复制到一半）。

---

## 6. 编码规范

- Kotlin 官方风格；4 空格缩进；文件名 = 顶层声明名。
- 包名 `dev.shinglem.netbird.ui`，分层：
  `api/`（`DaemonClient`、`@Serializable` DTO、请求体构造）· `data/`（`ProfilesController` 实例清单、
  `ConfigDraft` 设置草稿、`UiState`、`UiPreferences` 与 `DesktopConfig` 界面配置、
  `UiRuntimeState` 运行态）·
  `daemon/`（`ProfileSupervisor` 进程与通道、`DaemonPlan` 启动规格）·
  `profile/`（实例身份与磁盘布局）· `screen/`（`App` 骨架 + 各功能页 + `Common.kt` 共享组件）·
  `app/`（入口、托盘、单实例锁、路径解析、`ui.json` 读写）。
- 主界面 = 顶部实例下拉 + 左侧 `NavigationRail` 五页（连接 / Peers / 网络 / 设置 / 日志）；
  这些都是**当前实例**的内容。全局的「桌面」入口在顶部工具栏「新建配置」右边，
  点它切到全局设置页，点左侧任一 tab 回到实例页。tab 全局共用，切实例不换页。
- 实例内容区顶部有一条实例栏（`ProfileBar`）：名称 + daemon 状态 + 「随界面启动」开关。
  启动策略属于配置本身，**不要**把它挪回全局设置页。
- 实例选择用 M3 `ExposedDropdownMenuBox`，不要退回「按钮 + 弹出菜单」：按钮看不出能切换实例。
- M3 配色在 `ui/theme/Theme.kt`：`ThemeStyle`（默认 / 海洋 / 森林 / 落日 / 蔷薇 / 石板 / 高对比）
  × `ThemeMode`（跟随系统 / 浅色 / 深色）。每套配色的明暗是**两份独立色板** —— 深色主题的
  primary 必须是浅色调；`ThemeTest` 会断言主要前景/背景的对比度。
- 状态用 `sealed interface` 表达（`UiState.Ready / Unavailable(msg)`；daemon 进程生命周期另有
  `DaemonState`），不用 boolean 堆砌。
- 所有字符串中文，集中放在 `ui/screen/Strings.kt`（`object Strings`），**不要散落在深层组件里**。
- 托盘用 `app/DesktopTray.kt`（AWT `TrayIcon` + Swing `JPopupMenu`），**不要用**
  `androidx.compose.ui.window.Tray`：后者的菜单是原生 Win32 菜单，中文按 ANSI 转码后全是方框，
  `MenuItem.setFont` 也改不动（CMP-1936）。Swing 菜单走 Java2D 与 LAF 字体，中文正常。
- **图标**：源文件 `desktop-ui/icons/netbird-blue.svg`（官方 NetBird mark 的蓝色版），
  由 `scripts/make-icons.ps1` 调 `scripts/MakeIcons.java`（JDK 自带 Java2D，无第三方依赖）
  生成 `composeApp/src/desktopMain/resources/icons/netbird-*.png` 与 `composeApp/icons/netbird-blue.ico`。
  改颜色/尺寸就改 SVG 后重跑脚本；**不要手改 PNG/ICO**。窗口图标与托盘图标都从这份资源加载
  （`app/AppIcon.kt`），资源缺失会直接报错而不是退回占位图。
- 时间：daemon 给的是 protobuf `Timestamp`（网关输出 RFC3339 字符串），DTO 保留原字符串，
  统一由 `screen/Common.kt` 的 `formatTimestamp` 格式化 —— 组件里不要各自 parse。刻意不转
  `kotlinx.datetime.Instant`：本项目解析到的 compat 变体已把该 API 标为弃用。
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
| 关闭窗口 | 点 × 后窗口消失、进程仍在、托盘可恢复，daemon 仍在跑；把「关闭窗口时收进托盘」关掉后再点 ×，进程退出且 daemon 全部停止 |
| 自动启动 | 选中某个配置，在实例栏关掉「随界面启动」→ 退出界面 → 重新启动：它不自动起，另一个照常起；`ui\state.json` 里有对应记录 |
| 桌面设置 | 顶部「新建配置」右边是「桌面」入口（不在左侧 rail）；切换配色/明暗后重启界面，选择仍在；`ui\ui.custom.json` 里只出现改过的键 |
| 配置升级不丢 | 改过主题后替换 `ui\ui.json`（模拟新版默认值），重启界面：用户改过的键保留，没改过的键跟随新默认值；全部改回默认时 `ui.custom.json` 自动消失 |
| 配置便携 | 把整个发布目录复制到另一路径启动，主题与自启设置仍在（`ui\` 跟着走）；`config.json` 里的 `root` / `binary` 写相对路径也能解析 |
| 自启动 | 打包态在「桌面」页开关后 `schtasks /Query /TN NetBirdUI /XML` 能读出任务且 `<Arguments>--silent</Arguments>`；开发态显示提示且开关禁用 |
| 静默自启 | `netbird-multi-ui.exe --silent`：`Get-Process netbird-multi-ui` 的 `MainWindowHandle` 全为 0（没有窗口）、托盘有图标、网关可达；UI 已在跑时 `schtasks /Run /TN NetBirdUI` 不产生新进程也不抢前台 |
| 单实例唤醒 | 已有实例在托盘时再启动一次启动器：窗口出现在前台（`MainWindowHandle` 从 0 变成非 0），进程数不变、不弹第二个窗口 |
| 托盘 | 关闭窗口后进程仍在、托盘可恢复；右键菜单**中文正常**且不被任务栏裁切；退出菜单项真的退出 |
| 图标 | 窗口标题栏与托盘都显示蓝色 NetBird mark；`createDistributable` 产出的 exe 图标一致（`ExtractAssociatedIcon` 可读） |
| 裁剪 runtime | `rt\release` 的 `MODULES` 等于 `runtimeModules`，体积 ≈ 58 MB；用 `rt\bin\java.exe -cp "bin\App.jar;lib\*"` 跑探测：GBK/Unsafe/AWT/xml/TLS 全部可用；应用窗口、托盘、主题切换正常 |
| release 结构 | `app\netbird-multi-ui.cfg` 的 `app.runtime` 指向 `$ROOTDIR\rt`；`lib\` 里有 `skiko-windows-x64.dll`、`.sha256`、`icudtl.dat`；双击 exe 能起（会弹一次 UAC） |

GUI 级别的自动化（真实窗口驱动、截图断言）见技能 `dev-desktop-gui-e2e-testing`。

---

## 8. 技能与文档索引

| 路径 | 内容 |
|---|---|
| `.ai/skill/README.md` | **技能清单唯一处**（本目录用哪些技能、来源与同步方式） |
| `.ai/skill/netbird-desktop-ui-contract/` | **先读这个**：daemon 契约、实例编排、验收命令（含 `references/daemon-api.md` 的完整 RPC 与字段表） |
| `.ai/skill/netbird-multi-instance/` | 多实例部署形态（wintun GUID / NRPT 前缀 / 环境变量），UI 的实例发现基于它 |
| `.ai/skill/compose-multiplatform-desktop/` | 写 CMP 桌面代码前的坑（IME、滚动、窗口 resize、拖放） |
| `.ai/skill/desktop-release-packaging/` | **出成品前必读**：启动器结构、jlink 裁剪、图标、打包态坑与验证流程 |
| `.ai/skill/dev-desktop-gui-e2e-testing/` | 脚本驱动真实窗口做端到端验证 |
| `.ai/skill/global-git-ops/` | 本机 git 纪律：代理、提交前自查、上游同步 |
| `patchdoc/` | 本 fork 的**变更记录与证据**（规则在根 `AGENTS.md` 的「本 fork 的改动」节）；UI 改动若触及 Go 侧，必须在这里追加一份 |
| `desktop-ui/scripts/sync-local-netbird.ps1` | 把本机实例配置同步成 `.local\netbird\` 快照（脱敏副本 + `raw\` 原始副本，ACL 收紧） |
| `.local/` | **本机专属事实（不入库）**：环境事实、实例清单、调试快照；仓库文档里的占位符取值见其 `README.md` |

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
| 出 Windows 成品、改裁剪模块、换图标、打包态报错 | **技能 `desktop-release-packaging`** + 本文件 §5「发版流程」 |
| 脚本驱动窗口验收 | 技能 `dev-desktop-gui-e2e-testing` |
| 加依赖、升版本、Gradle 报错 | `desktop-ui/gradle/libs.versions.toml` + 本文件 §2 的依赖门槛（Kotlin 工具链类技能**未引入**，见 `.ai/skill/README.md`） |
| 提交 / 推送 / 跟上上游 | 技能 `global-git-ops` + 根 `AGENTS.md` 的 fork 小节 |
| 要改 daemon 或 proto | **先停**：读 `patchdoc/README.md`，写方案给用户 |
