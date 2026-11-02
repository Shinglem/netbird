# daemon API 参考（grpc-gateway 的 HTTP/JSON 投影）

> 本文件是 `netbird-desktop-ui-contract` 的参考资料。所有结论都标了出处；
> **字段名以 `curl` 实测为准**，proto 字段名与网关输出的 `json_name` 会不一致（见末节例证）。

## 调用约定

```
POST http://127.0.0.1:<port>/daemon.DaemonService/<Method>
Content-Type: application/json
Body: {}
```

- 路径来自 `generate_unbound_methods=true`（`client/proto/generate.sh`），
  每个 RPC 都有默认路径；`client/proto/daemon_gateway_test.go` 对全部 RPC 断言路由存在。
- 错误：gRPC 状态码映射为 HTTP 状态码，body 为 `{"code":…, "message":…, "details":[…]}`。
  `PermissionDenied` → 403；`FailedPrecondition`（MDM 字段被改）→ 400 且 `details` 带
  `MDMManagedFieldsViolation`（`client/proto/daemon.proto:883-890`）。
- 流式方法（`stream`）返回 chunked 的**逐行 JSON**，永不结束。

## RPC 全表（46 个）

`client/proto/daemon.proto` 的 `service DaemonService`。

### 连接与状态（首版必需）

| RPC | 请求 | 响应 | UI 用法 |
|---|---|---|---|
| `Status` | `{getFullPeerStatus?, shouldRunProbes?, waitForReady?}` | `StatusResponse` | 首次快照 / 兜底查询 |
| `SubscribeStatus` | 同上 | `stream StatusResponse` | **主通道**：首帧即快照，之后状态变化推送 |
| `Up` | `{profileName?, username?, async}` | `{}` | **必须传 `async: true`**（见下） |
| `Down` | `{}` | `{}` | 断开 |

**`Up.async` 是 UI 的关键开关**（`daemon.proto:276-281`）：默认 `false` 时 RPC **阻塞到引擎就绪**（CLI 行为），
UI 会一直卡在"无响应"；`true` 时立即返回，进度靠 `SubscribeStatus` 推。

### 认证（首版必需）

| RPC | 请求 | 响应 |
|---|---|---|
| `Login` | `{setupKey, preSharedKey(deprecated), managementURL, hostname, profileName?, username?, hint?, …可选连接参数}` | `{needsSSOLogin, userCode, verificationURI, verificationURIComplete}` |
| `WaitSSOLogin` | `{userCode, hostname}` | `{email}`（阻塞到用户在浏览器完成） |
| `Logout` | `{profileName?, username?}` | `{}` |
| `RequestJWTAuth` | `{hint?, hasGraphicalSession}` | `{verificationURI, verificationURIComplete, userCode, deviceCode, expiresIn, cachedToken, maxTokenAge}` |
| `WaitJWTToken` | `{deviceCode, userCode}` | `{token, tokenType, expiresIn}` |
| `RequestExtendAuthSession` | `{hint?, hasGraphicalSession}` | 含验证 URI（会话续期） |
| `WaitExtendAuthSession` | — | — |
| `DismissSessionWarning` | — | — |

`hasGraphicalSession` 由**调用方**声明（daemon 拿不到会话环境），决定 PKCE 还是 device code。
桌面 UI 一律传 `true`。

### 配置与特性

| RPC | 说明 |
|---|---|
| `GetConfig` | `{profileName, username}` → 大量字段（管理 URL、接口名、MTU、各类 disable_* 开关），
`mDMManagedFields` 列出**被 MDM 强制**的键名 ⇒ UI 必须把这些输入置灰 |
| `SetConfig` | 部分更新（字段皆 `optional`）；改到 MDM 字段 → `FailedPrecondition` + `MDMManagedFieldsViolation` |
| `GetFeatures` | `{disable_profiles, disable_update_settings, disable_networks, disable_advanced_view?}` ⇒ UI 据此隐藏入口 |
| `GetLogLevel` / `SetLogLevel` | 日志级别（大写枚举名） |

### 实例（profile）

| RPC | 请求 | 响应 |
|---|---|---|
| `ListProfiles` | `{username}` | `{profiles: [{name, is_active, id}]}` |
| `GetActiveProfile` | `{}` | `{profileName, username, id}` |
| `SwitchProfile` | `{profileName?, username?}`（handle：ID / 唯一前缀 / 唯一显示名） | `{id}` |
| `AddProfile` | `{username, profileName}` | `{id}` |
| `RenameProfile` | `{username, handle, newProfileName}` | `{oldProfileName}` |
| `RemoveProfile` | `{username, profileName}` | `{id}` |

### 网络（Networks = routed networks / exit nodes）

| RPC | 请求 | 响应 |
|---|---|---|
| `ListNetworks` | `{}` | `{routes: [{id, range, selected, domains, resolvedIPs}]}` |
| `SelectNetworks` / `DeselectNetworks` | `{routes: [{id, …}]}` | `{}` |

**不要轮询 `ListNetworks`**：`StatusResponse.fullStatus.networksRevision` 在网络集合或选中态变化时才变，
UI 指纹比对它再拉一次（`daemon.proto:482-486`）。

### 事件

| RPC | 说明 |
|---|---|
| `SubscribeEvents` | `stream SystemEvent` |
| `GetEvents` | `{events: [...]}`（当前缓冲） |
| `RegisterUILog` | UI 把自身日志交给 daemon（可选） |

### 诊断 / 特权 / 不做

`DebugBundle`、`StartCapture`(stream)、`StartBundleCapture`、`StopBundleCapture`、`TracePacket`、
`StartCPUProfile`、`StopCPUProfile`、`ExposeService`(stream)、`GetInstallerResult`、
`CleanState`、`DeleteState`、`ListStates`、`SetSyncResponsePersistence`、`TriggerUpdate`、
`GetPeerSSHHostKey`、`ForwardingRules`(deprecated)、`WailsUIReady`。

其中带身份校验的（TCP 网关必被拒）：`DebugBundle`、`StartCapture`/`StartBundleCapture`、
`Up` 里的 SSH/远程作业/本地指标/managementURL 分支。

## 关键结构

### `StatusResponse`

```text
status: string            // Idle | Connecting | Connected | NeedsLogin | LoginFailed | SessionExpired
fullStatus: FullStatus?   // 见下
daemonVersion: string
sessionExpiresAt: Timestamp?  // SSO 会话到期时刻；UI 自己算"将要过期"
```

### `FullStatus`（`daemon.proto:466-487`）

```text
managementState  { url, connected, error? }
signalState      { url, connected, error? }
localPeerState   { IP, pubKey, kernelInterface, fqdn, rosenpassEnabled, rosenpassPermissive, networks[] }
peers[]          { IP, pubKey, connStatus, connStatusUpdate, relayed, localIceCandidateType,
                   remoteIceCandidateType, fqdn, localIceCandidateEndpoint, remoteIceCandidateEndpoint,
                   lastWireguardHandshake, bytesRx, bytesTx, rosenpassEnabled, networks[],
                   latency(Duration), relayAddress, sshHostKey(bytes), ipv6 }
relays[]         { uri, available, error? }
dns_servers[]    { NSGroupState }
events[]         { SystemEvent }
lazyConnectionEnabled: bool
sshServerState   { enabled, sessions[] }
networksRevision: uint64   // 变了才重拉 ListNetworks
```

### `SystemEvent`（`daemon.proto:683-707`）

```text
id, severity(INFO|WARNING|ERROR|CRITICAL), category(NETWORK|DNS|AUTHENTICATION|CONNECTIVITY|SYSTEM),
message(技术日志行), userMessage(给用户看), timestamp, metadata(map<string,string>)
```

UI 渲染用 `userMessage`；`metadata` 里的已知键：`new_version_available`、`enforced`、`id`、
`network`、`version`、`progress_window`（上游 `WAILS-API.md` 记录）。

### `Profile`

`{name, is_active, id}` —— **上游 `WAILS-API.md` 写的是 `{name, isActive, email}`，与 proto 不一致**
（那份文档描述的是 Wails 的 Go 结构体投影）。这正是"字段名照抄会踩坑"的实证。

### `GetConfigResponse`

**键名规则**：daemon 的网关用 `runtime.NewServeMux` 的默认 marshaler（`UseProtoNames=false`），
所以 proto 里带下划线的字段到 HTTP 上会转驼峰：`disable_dns` → `disableDns`、
`disable_ipv6` → `disableIpv6`、`dns_servers` → `dnsServers`。没有下划线的字段**原样保留**：
`adminURL`、`mDMManagedFields`、`ID`、`IP`、`URL`、`bytesRx`。

```text
managementUrl, configFile, logFile, preSharedKey, adminURL, interfaceName,
wireguardPort, mtu, disableAutoConnect, serverSSHAllowed, rosenpassEnabled,
rosenpassPermissive, disableNotifications, lazyConnectionEnabled, blockInbound,
networkMonitor, disableDns, disableClientRoutes, disableServerRoutes,
blockLanAccess, disableIpv6, remoteJobsAllowed, mDMManagedFields[]
```

`mDMManagedFields` 列出被 MDM 强制的键名（与 `client/mdm` 的 `Key*` 常量同名，如
`managementURL`、`disableClientRoutes`、`wireguardPort`），UI 必须把这些输入置灰。

## 状态与网关的对应

| UI 状态 | 来源 |
|---|---|
| `Idle` / `Connecting` / `Connected` / `NeedsLogin` / `LoginFailed` / `SessionExpired` | `StatusResponse.status` |
| `DaemonUnavailable` | **UI 合成**：连接失败 / 流断开且重连失败 |

## 实测命令

```bash
P=8471   # 每实例 +1

# 快照
curl -s -X POST http://127.0.0.1:$P/daemon.DaemonService/Status \
  -H 'Content-Type: application/json' -d '{}' | python -m json.tool

# 流式（首帧即快照；Ctrl-C 退出）
curl -N -X POST http://127.0.0.1:$P/daemon.DaemonService/SubscribeStatus \
  -H 'Content-Type: application/json' -d '{}'

# 实例与特性
curl -s -X POST http://127.0.0.1:$P/daemon.DaemonService/ListProfiles -H 'Content-Type: application/json' -d '{}'
curl -s -X POST http://127.0.0.1:$P/daemon.DaemonService/GetFeatures  -H 'Content-Type: application/json' -d '{}'
```

`curl` 不通时按顺序排查：服务是否带 `--enable-json-socket`（`netbird service status` /
服务命令行）→ 端口是否被占 → 是否被本机防火墙拦（回环一般不会）→ daemon 日志
（`<实例>\runfiles\daemon.log`，搜 `json gateway`）。

## 证据索引

| 结论 | 出处 |
|---|---|
| 每个 RPC 都有默认 HTTP 路径 | `client/proto/generate.sh`（`generate_unbound_methods=true`）、`client/proto/daemon_gateway_test.go` |
| json-socket 的开关与地址格式 | `client/cmd/service.go:86-87` |
| Windows 只监听 pipe 且不自动回落 TCP | `client/internal/daemonaddr/resolve_pipe_windows.go` |
| TCP 不携带身份 | `client/internal/daemonaddr/identity.go` |
| 特权分支（SSH/远程作业/指标/URL） | `client/server/ssh_gate.go:87-143` |
| 特权分支（debug bundle / capture） | `client/server/debug_gate.go`、`client/server/capture.go:81` |
| `SubscribeStatus` 首帧即快照 | `client/proto/daemon.proto:28-31` |
| `Up.async` 语义 | `client/proto/daemon.proto:276-281` |
| `networksRevision` 用来免轮询 | `client/proto/daemon.proto:482-486` |
| MDM 字段不可改 | `client/proto/daemon.proto:376-381`、`:883-890` |
