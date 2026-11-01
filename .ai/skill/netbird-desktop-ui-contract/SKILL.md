---
name: netbird-desktop-ui-contract
description: 给 NetBird 的桌面 UI（Kotlin/Compose）接 daemon：通道选型、RPC 分级、多实例编排与验收。
version: 1.0.0
metadata:
  tags: [netbird, daemon, grpc-gateway, json, windows, desktop-ui, compose-multiplatform, multi-instance]
  related: [netbird-multi-instance, compose-multiplatform-desktop]
---

# NetBird 桌面 UI 与 daemon 的契约

## 何时使用

- 要在 `desktop-ui/`（Kotlin / Compose Multiplatform）里调 NetBird daemon：连/断、读状态、切换实例、
  选网络、改设置。
- UI 起不来、只显示 `DaemonUnavailable`，或者某个操作返回 `PermissionDenied` 而不知道原因。
- 要给同一台主机上的多个 daemon 实例分别配 UI 通道。
- 决定"UI 该自己实现点什么，还是都交给 daemon"。

**不适用**：部署多实例本身（wintun GUID / NRPT 前缀 / `NB_STATE_DIR`）→ 技能 `netbird-multi-instance`。
写 Compose 界面代码 → 技能 `compose-multiplatform-desktop`。

## 决定架构的三个事实

### 1. daemon 在 Windows 上只监听命名管道，UI 走它自带的 HTTP/JSON 网关

- 默认 `npipe://netbird`，且**管道不应答时不会自动回落 TCP**（`client/internal/daemonaddr/resolve_pipe_windows.go`
  的注释明确说：自动回落等于把身份交给"任何能在 127.0.0.1 上监听的本地进程"）。
- Kotlin/JVM 没有可用的 gRPC-over-named-pipe 传输 ⇒ **不要试图手写 pipe transport**。
- daemon 已经把 `DaemonService` 用 grpc-gateway 暴露成 HTTP/JSON：

```
POST http://127.0.0.1:<port>/daemon.DaemonService/<Method>
Content-Type: application/json
{} 或该方法的请求字段
```

  路径是 `generate_unbound_methods=true` 生成的默认路径 —— 见 `client/proto/generate.sh`；
  路由存在性由 `client/proto/daemon_gateway_test.go` 对**每个** RPC 断言。

- Kotlin 侧因此只需要 **Ktor client + kotlinx-serialization**，不需要 gRPC、不需要 protobuf 插件。

### 2. TCP 通道没有调用者身份 ⇒ 一部分 RPC 必然被拒

`daemonaddr.CarriesIdentity` 只认 `unix://` 与 `npipe://`（`client/internal/daemonaddr/identity.go`）。

| 被拒的能力 | 证据 |
|---|---|
| `Up` 里改 SSH / 远程作业 / 本地指标 / management URL | `client/server/ssh_gate.go:87-143` |
| debug bundle 上传、打开调用者文件 | `client/server/debug_gate.go` |
| `StartCapture` / `StartBundleCapture` | `client/server/capture.go:81` |

**做法**：UI 不做这些入口。用户真需要时，把 daemon 返回的 `PermissionDenied` 消息**原样展示** ——
它的 metadata 里已经带了可直接复制的 `netbird …` 命令（`client/cmd/daemon_error.go:35-61` 的展示路径即参考）。

### 3. 流式 RPC 是"一行一帧"，且首帧就是快照

- `SubscribeStatus` / `SubscribeEvents` / `StartCapture` / `ExposeService` 是 server-streaming，
  网关写成持续输出的 JSON 行流（不是数组、不会结束）。
- `SubscribeStatus` 首帧即当前 `StatusResponse`（`client/proto/daemon.proto:28-31`）⇒ **不用先单独调 `Status`**。
- 用 Ktor `preparePost(...).execute { it.bodyAsChannel() }` 逐行读；断流要退避重连，重连期间置 `DaemonUnavailable`。

## 启动参数矩阵

UI 需要 daemon 带 `--enable-json-socket --json-socket tcp://…`（`client/cmd/service.go:86-87`）。

| 场景 | 命令 |
|---|---|
| 已装服务持久化（管理员） | `netbird service install --enable-json-socket --json-socket tcp://127.0.0.1:8471`，随后重启服务 |
| 临时前台实例（不动生产实例） | `netbird service run --config <实例>\config\default.json --daemon-addr npipe://netbird-<名> --enable-json-socket --json-socket tcp://127.0.0.1:8472 --log-file <实例>\runfiles\daemon.log`，并先导出 `NB_STATE_DIR` 与 `NB_NRPT_KEY_PREFIX`（见 `netbird-multi-instance`） |
| 端口约定 | **8471 起，每实例 +1**；端口属于部署配置，写进 `instance.json`，UI 只读不猜 |

`--json-socket` 只接受 `unix|tcp`（`service.go:87` 的 flag 说明）—— Windows 上**只能 `tcp://`**。

## RPC 分级（首版只做第一列）

| 首版必需 | 二期 | 不做（理由见上/deprecated/危险） |
|---|---|---|
| `Status`、`SubscribeStatus` | `SetConfig`（设置页） | `ForwardingRules`（deprecated） |
| `Up`、`Down` | `SubscribeEvents`、`GetEvents`（事件面板/通知） | `TracePacket`、`StartCapture`、`StartBundleCapture`、`StartCPUProfile` |
| `Login`、`WaitSSOLogin`（SSO 流程） | `Logout` | `CleanState`、`DeleteState`、`ListStates`（破坏性） |
| `ListProfiles`、`GetActiveProfile`、`SwitchProfile` | `TriggerUpdate`（更新交给 daemon，UI 只提示） | `DebugBundle`（需要身份）、`ExposeService`、`GetInstallerResult` |
| `GetConfig`、`GetFeatures` | `GetPeerSSHHostKey` | `WailsUIReady`（Wails 专用，UI 不调） |
| `ListNetworks`、`SelectNetworks`、`DeselectNetworks` | `RequestJWTAuth` / `WaitJWTToken`（JWT 登录路径） | `DismissSessionWarning`、`SetSyncResponsePersistence` |

完整签名、请求字段与响应要点见 [`references/daemon-api.md`](references/daemon-api.md)。

## 实例发现（多实例是本 fork 的核心场景）

UI 不得假设"只有一个 daemon"，也不得扫端口。实例列表来自部署目录：

```
<root>\
  profiles\<name>\
    instance.json     ← 脚本元数据（实例名、daemon-addr、状态目录）
    config\default.json
    runfiles\{state.json, daemon.log}
```

UI 的发现顺序（从可靠到兜底）：

1. UI 自己的配置文件（`AppData\Roaming\netbird-ui\instances.json`，用户显式配置的 `name` + `baseUrl`）；
2. `<root>\profiles\*\instance.json`（首次运行扫描，生成默认列表，端口按 8471+n 约定）；
3. 兜底：`tcp://127.0.0.1:8471`（单实例部署）。

每个实例一条 `HttpClient` 复用同一 `baseUrl`；切实例要**先关旧流再开新流**，
否则会同时收到两个实例的 `SubscribeStatus`（症状：状态在两个值之间跳）。

## 常见故障对照

| 症状 | 原因 | 处理 |
|---|---|---|
| 启动即 `DaemonUnavailable` | daemon 没开 json-socket；或没起 | `curl` 打一次 `Status` 确认；检查服务参数 |
| `Status` 通但 `SubscribeStatus` 不吐数据 | 用了一次性读取（`bodyAsText()`），把无限流当响应体等结束 | 改 `bodyAsChannel()` 逐行读 |
| 某操作 `PermissionDenied` | TCP 无身份（上文事实 2） | 不做该入口；展示 daemon 给的命令 |
| 状态卡在 `Connecting` 不再更新 | 流断了没重连；或 UI 在 `SwitchProfile` 后没重新订阅 | 断流重连 + 切换后重建订阅 |
| 两个实例状态互相串 | 复用了同一条连接 / 共用端口 | 一实例一 `HttpClient` 一 `baseUrl` |
| `Unresolved reference` 指向刚加的 DTO 字段 | 字段名猜错（protojson 的 `json_name` 与 proto 字段名不同） | 以 `curl` 实测 JSON 为准，`ignoreUnknownKeys=true` |

## 验收

```bash
# 1) 网关在
curl -s -X POST http://127.0.0.1:8471/daemon.DaemonService/Status \
  -H 'Content-Type: application/json' -d '{}'

# 2) 流式首帧即快照（-N 关缓冲，Ctrl-C 退出）
curl -N -X POST http://127.0.0.1:8471/daemon.DaemonService/SubscribeStatus \
  -H 'Content-Type: application/json' -d '{}'

# 3) 实例列表（每个实例应返回不同 email/name）
curl -s -X POST http://127.0.0.1:8471/daemon.DaemonService/ListProfiles \
  -H 'Content-Type: application/json' -d '{}'
```

UI 侧验收：daemon 停止 → 显示 `DaemonUnavailable` 且**自动**恢复；`Up`/`Down` 后用 `curl` 交叉确认
`status` 真的变了（不信 UI 自报）；两实例切换不串状态。

## 参考

- [`references/daemon-api.md`](references/daemon-api.md) —— 全部 RPC 签名、请求字段、`StatusResponse`/`SystemEvent`
  结构、状态与事件枚举、端点与实测命令。
- `client/ui/frontend/WAILS-API.md`（上游）—— 上游 Wails 前端用到的能力清单与状态流转说明，
  **字段名不可照抄**（那是 Wails 层投影，不是网关输出）。
