# NetBird agent 指引

> **语言约定（强制，优先于本文件其余一切表述）**
>
> - **思考过程与回复一律使用中文。** 包括分析、计划、总结、"下一步"之类的旁白。
> - 代码标识符、命令、路径、配置键名、日志与报错原文、上游 URL 保持英文原样，不要翻译。
> - 注释与提交信息沿用所在文件的既有语言：上游 Go 代码里的注释保持英文；
>   本 fork 自己的文档（本文件、`patchdoc/`、`.ai/skill/`、`desktop-ui/`）一律中文。

**NetBird** 是开源的连接平台：一张带控制平面的、基于 WireGuard® 的覆盖网络。
**agent**（`client/`）以特权守护进程的形式跑在用户机器上，管理 WireGuard 接口、路由、
防火墙与 DNS。**Management**（`management/`）是控制平面兼 REST/gRPC API，**Signal**
（`signal/`）撮合 peer 握手，**Relay**（`relay/`）在无法直连时承载流量，**Proxy**
（`proxy/`）是 Agent Network 背后的身份感知代理。

本文件适用于整个仓库，是此处 agent 指引的唯一事实源。`CLAUDE.md` 只是指向它的一行指针 ——
指引写在本文件里，不要在两处重复。

## 目录

- [停止并先问用户](#停止并先问用户)
- [快速参考](#快速参考)
- [仓库结构](#仓库结构)
- [去哪里找](#去哪里找)
- [安全](#安全)
- [agent 约定](#agent-约定)
- [全仓原则](#全仓原则)
- [类型安全](#类型安全)
- [并发与生命周期](#并发与生命周期)
- [错误处理](#错误处理)
- [注释](#注释)
- [测试](#测试)
- [坑](#坑)
- [提交与发布](#提交与发布)
- [推送之后](#推送之后)
- [讨论与支持](#讨论与支持)
- [本 fork 的改动](#本-fork-的改动)
- [桌面 UI](#桌面-ui)

## 停止并先问用户

- **除了一行式的小修，开 PR 之前先确认有没有 ticket。** 直接问用户：*「这个改动有对应的
  讨论或 issue 吗？」* NetBird 是 discussion 优先的项目 —— 社区报告先发到
  [Discussions](https://github.com/netbirdio/netbird/discussions)，DevRel 验证后才转成 issue。
  没有关联 issue 的行为变更 PR 可能一落地就被关。没有 ticket 时，**改为起草讨论帖**而不是 PR，
  等用户拍板。只有错别字、失效链接、文档订正，以及「已经有 issue 的一行式修改」可以跳过这一步。
  *（本 fork 是公开仓库、不向上游提 PR，这条对本地改动不适用；保留它是为了让上游规则保持完整，
  见 [本 fork 的改动](#本-fork-的改动)。）*
- **在下列高风险区做设计**（见
  [CONTRIBUTING.md](CONTRIBUTING.md#high-risk-areas)）：公共 API 与 OpenAPI schema、gRPC proto、
  升级后现有部署能感知到的行为变更、peer 连通性（ICE、NAT 穿透、relay 选择、WireGuard® 或
  Rosenpass 密钥处理）、客户端系统集成（路由、防火墙、DNS、接口）、认证与授权、CLI 或服务参数、
  配置文件格式、daemon IPC、store schema 与迁移、新增功能。设计要先在 ticket 里谈定。
- **写 store 迁移或改持久化模型。** 迁移在线上是单向的，而且 GORM 与 pgx 两条路径可能都要改。
- **手改生成代码。** `*.pb.go`、`*.gen.go` 和 mock 都是产物。改源头（`.proto`、`openapi.yml`）
  并重跑对应的 `generate.sh`。
- **新增、删除或升级依赖**，以及绝不要 vendor 一个 fork。
- **削弱安全控制** —— 认证、授权、证书校验、降权、peer 身份校验 —— 哪怕那是让测试变绿最快的路。
- **向 `main` 强推**、对已在 review 的分支强推、amend 已推送的提交，或用 `--no-verify` 绕过钩子。
  *（本 fork 的 `main` 只有你在用，本条按 [本 fork 的改动](#本-fork-的改动) 里写的本地纪律执行。）*

## 快速参考

```bash
# 构建
go build ./...
cd client && CGO_ENABLED=0 go build .        # agent
cd management && go build .                  # management 服务
cd signal && go build .                      # signal 服务

# 推送前必做
go fmt ./...
make lint            # golangci-lint，只查相对 origin/main 的改动（也是 pre-push 钩子）
make lint-all        # 全仓 lint，与 CI 一致
make test-unit       # 宿主安全的单测集，-tags devcert，不需要 sudo
make test-privileged # privileged 测试集，在带 NET_ADMIN 的 Docker 容器里跑
make setup-hooks     # 装上 .githooks：pre-push 跑 make lint，commit-msg 拒绝工具署名 trailer

# 窄范围运行
go test ./client/internal/dns/...
go test -race -run TestPeerConn ./client/internal/peer/...
PRIV_RUN=TestNftablesManager PRIV_PKGS=./client/firewall/nftables/... make test-privileged

# 代码生成（绝不手改产物）
./shared/management/http/api/generate.sh   # 从 openapi.yml 生成 REST 类型
./shared/management/proto/generate.sh
./shared/signal/proto/generate.sh
./client/proto/generate.sh
./flow/proto/generate.sh

# 本地运行（只在自己的实验环境，绝不在依赖的机器上）
sudo ./client/netbird up --log-level debug --log-file console
sudo ./client/netbird down                   # 拆解：恢复路由、防火墙、DNS
./signal/signal run --log-level debug --log-file console
./management/management management --log-level debug --log-file console --config ./management.json
```

本 fork 的客户端构建必须带 `GOPROXY=https://goproxy.cn,direct`：默认的 `proxy.golang.org`
在本网络下极慢（实测 20 秒只下到几十个模块元数据文件），换镜像后完整构建一次通过。

`netbird up` 需要 root，并且会重写宿主的路由表、防火墙规则、DNS 配置与 WireGuard® 接口。
只在可重装的测试环境（VM、容器、一次性机器）里跑，绝不在你自己依赖其网络的工作站或服务器上跑。
收工前、重编二进制前、以及**每一条失败路径**上都要 `sudo netbird down`，让宿主的网络状态被恢复
而不是半应用地留着。为什么每条退出路径都要清理，见 [坑](#坑)。

### 各平台构建

| 平台 | 命令 |
|---|---|
| Windows（本机） | `cd client; $env:CGO_ENABLED='0'; $env:GOPROXY='https://goproxy.cn,direct'; go build .` |
| 交叉编译检查 | `GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build ./client/...`、`GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build ./client/...` |
| 桌面 UI | `cd desktop-ui; ./gradlew :composeApp:compileKotlinDesktop`（KMP 下 `compileKotlin` 是歧义任务） |

## 仓库结构

```text
netbird/
├── client/              NetBird agent
│   ├── cmd/             agent CLI
│   ├── internal/        agent 业务逻辑（engine、peer、dns、routemanager……）
│   ├── server/          后台运行的 daemon
│   ├── proto/           daemon gRPC proto
│   ├── iface/           WireGuard® 接口管理
│   ├── firewall/        nftables、iptables、pf、WFP、userspace 后端
│   ├── ssh/             内置 SSH 服务端与客户端
│   ├── ui/              上游桌面 UI（Wails v3 + React，**不是**本 fork 的基线）
│   ├── android/, ios/   移动端绑定
│   ├── wasm/            WebAssembly 构建
│   └── mdm/, system/    MDM 策略、主机信息
├── management/          控制平面
│   └── server/          account、peer、groups、networks、posture、permissions、
│                        settings、store、http（REST）、idp、integrations、migration
├── signal/              握手撮合（peer/、server/）
├── relay/               relay 服务（protocol/、server/、healthcheck/）
├── proxy/               身份感知代理（llm/、acme/、accesslog/、middleware/、tcp/、udp/）
├── agent-network/       Agent Network 概述
├── shared/              agent 与服务共同引用
│   ├── management/      proto/、client/、http/api（OpenAPI + 生成的类型）
│   ├── signal/          proto/、client/
│   └── relay/, auth/, sshauth/, metrics/
├── e2e/                 端到端测试集与 harness
├── encryption/, dns/, route/, stun/, sharedsock/, util/, flow/
├── infrastructure_files/  docker compose 与快速上手模板
└── release_files/       打进发行包的文件
```

本 fork 额外增加：

```text
desktop-ui/              桌面 UI（Kotlin / Compose Multiplatform，Windows 优先）
patchdoc/                本 fork 补丁集的变更记录与复现步骤
.ai/skill/               按任务加载的 agent 技能（.github/skills 是指向它的符号链接）
```

## 去哪里找

| 任务 | 位置 |
|---|---|
| REST API / OpenAPI | `shared/management/http/api/` + `management/server/http/` |
| Management gRPC 协议 | `shared/management/proto/` |
| Signal 协议 | `shared/signal/proto/` |
| daemon IPC 协议 | `client/proto/` |
| peer 连接与 NAT | `client/internal/peer/` |
| network map 处理 | `client/internal/engine.go`、`shared/management/networkmap/` |
| 路由 | `client/internal/routemanager/`、`route/` |
| 防火墙后端 | `client/firewall/` |
| DNS | `client/internal/dns/`、`dns/` |
| WireGuard® 接口 | `client/iface/` |
| 持久化与迁移 | `management/server/store/`、`management/server/migration/` |
| IdP 集成 | `management/server/idp/` |
| 权限模型 | `management/server/permissions/` |
| LLM 路由 / Agent Network | `proxy/internal/llm/`、`agent-network/` |
| 端到端测试 | `e2e/` |
| **本 fork** 的实例身份补丁 | `client/iface/device/`、`client/internal/dns/host_windows.go`、`client/internal/profilemanager/config.go` |
| **本 fork** 的桌面 UI | `desktop-ui/`（规则在 `desktop-ui/AGENTS.md`） |
| **本 fork** 的技能与踩坑 | `.ai/skill/`（清单见 `.ai/skill/README.md`） |

## 安全

### 绝不 fail open

当安全校验 —— 访问控制、IP 限制、认证判定 —— 遇到错误（值解析不了、查询不可用、
状态不认识）时，它必须**拒绝**。绝不因为校验本身失败就跳过校验或放行请求，
安全相关的 `switch` 里 `default` 与未知分支也必须拒绝，而不是掉到下面继续走。

### daemon RPC 的输入不可信

agent 以 root 身份运行（Windows 上是 LocalSystem），所以一条 daemon RPC 跨越了特权边界：
把每个字段都当成不可信输入，而不是「UI 或 CLI 已经校验过了」。

新增或修改 RPC 时，先问：handler 以 root 身份拿调用方输入做什么。如果答案涉及文件路径、
URL 或主机名、或者特权状态变更，那么**闸门必须放在 handler 里** —— 放在「通常调用它的那个
客户端」里的检查不算检查。

- **调用方给路径、daemon 去打开。** 绝不以 root 直接 `os.Open`。先约束路径，再用
  `ipcauth.OpenOwnedFile` **以调用方身份**打开：它以 `O_NOFOLLOW` 打开、要求是普通文件、
  并拒绝调用方并不拥有的文件 —— 于是指向 root-only 文件的符号链接或硬链接会被挡下。
- **调用方给 URL 或主机、daemon 去抓取。** 限制 scheme，非特权调用方只允许已知主机。
  优先「按名字做词法白名单 + TLS 校验」，而不是「先解析域名再拒绝私有 IP」：
  后者有 DNS rebinding 竞态（校验时是公网 IP，连接时是攻击者 IP），名字白名单则没有 IP 可竞态。
  该用 `https://` 的地方绝不接受 `http://`。
- **特权状态变更**（SSH root 登录、management URL、注销）要基于
  `ipcauth.CallerIdentity(ctx)` 给出的调用方身份来闸门。

调用方身份来自内核 —— `SO_PEERCRED`、`LOCAL_PEERCRED`、或命名管道的客户端令牌 ——
**绝不来自 RPC 字段**。当 `ipcauth.CallerIdentity` 报告无法确定身份时，**拒绝**；
不要退化成把调用方当成传输对端。

### 本 fork 的额外提醒

多实例部署下，每条启动脚本都要导出 `NB_STATE_DIR` 与 `NB_NRPT_KEY_PREFIX`；
命名管道名用 `npipe://netbird-<名>`。**不要为了「方便」把 daemon 改成监听回环 TCP**：
回环 TCP 不携带调用方身份，上面那套闸门会全部失效。
细节与证据见 [本 fork 的改动](#本-fork-的改动)。

## agent 约定

### 三种组网模式

包到底从哪走，取决于 agent 跑在哪种模式下。三者不可互换，所以动手前先确定改动适用于哪种模式、
另外两种模式下应该怎样。

- **内核态（kernel mode，仅 Linux）**：内核里的 WireGuard®。点对点流量与转发流量都由内核处理，
  ACL 就是 iptables 或 nftables 规则。客户端只编程内核设施，自己看不到流量。
- **用户态（userspace mode，wireguard-go + TUN）**：wireguard-go 跑在进程内。点对点流量离开 TUN
  之后由内核处理；转发流量（exit node 与 network route）走用户态的 forwarder —— 它终结连接，
  再用操作系统套接字重新建立。用于没有内核 WireGuard® 的平台，或用户显式选择时。
- **netstack 模式**：wireguard-go 在进程内，没有 TUN，也没有内核网络。forwarder 全靠拼接
  用户态套接字完成路由，内置 SSH 与 DNS 服务这类 listener 绑在 gVisor netstack 上。
  用于无法创建 TUN 设备的场景，例如嵌入式客户端（`client/embed/`）与 WASM 构建。

### 覆盖接口不等于「WireGuard」

除非代码确实与 WireGuard® 这个实现耦合（调用 wireguard-go、某个握手字段、某个内核
WireGuard® netlink 属性），否则不要在标识符或注释里写 "WireGuard"。指接口、主机、peer、
或泛指的流量时，说「NetBird 接口」「覆盖接口」或「覆盖网络」。防火墙、路由、DNS 代码大多与
传输方式无关，在那里写 WireGuard® 是不准确的，而且会随传输方式演进而腐烂。

### IPv6 是软特性

IPv6 覆盖是 opt-in 的双栈，且能力在运行期会变。把它当软特性而非前提：

- 本地 v6 路径用接口访问器判断（`wgIface.Address().HasIPv6()`），不要读裸状态字段；
  宿主没有 v6 时**跳过** v6 路径，而不是返回错误。
- peer 的 v6 地址为空或解析不了，就当作「该 peer 没有 v6」跳过，保持 v4 路径可用。
- **绝不让缺失的 v6 破坏 v4。** fail-closed 是给安全校验的；能力不匹配时跳过 v6 的活儿继续走。

### 环境变量

变量名放进常量，布尔值用 `strconv.ParseBool` 解析，不要内联比较字符串 ——
这样异常取值会被记日志，而不是默默当成 false：

```go
const EnvDisableFeature = "NB_DISABLE_FEATURE"

func isDisabledByEnv() bool {
    val := os.Getenv(EnvDisableFeature)
    if val == "" {
        return false
    }
    disabled, err := strconv.ParseBool(val)
    if err != nil {
        log.Warnf("failed to parse %s: %v", EnvDisableFeature, err)
        return false
    }
    return disabled
}
```

本 fork 自己的两个变量同理，已经写成常量并集中在各自文件里：
`client/iface/device/windows_guid.go` 的 `envWintunGUID`、`client/internal/dns/host_windows.go`
的 `envNRPTKeyPrefix`。

### 按协议规范校验

当改动取决于协议到底要求什么时，去 [IETF datatracker](https://datatracker.ietf.org/) 读规范原文，
不要读二手总结；并且确认拿到的是**当前**那份 RFC —— 一个协议被广泛引用的常常是已被取代的版本。
引用时要给到**章节号**而不只是文档号，这样 reviewer 能直接跳到那一条。

## 全仓原则

1. **每个改过的 Go 文件都要 `go fmt`。** 格式不是可选项。
2. **linter 警告零残留。** 修掉 `golangci-lint` 在你碰到的代码上报告的问题，并删掉重构后
   变成孤儿的 import、helper 与参数。例外：共享代码里的未使用参数可能被仓库外的构建消费 ——
   别删，先问。
3. **导出函数必须有注释**，完整句子、以句号结尾、以标识符名开头。
4. **优先私有函数与常量。** 只导出包外调用方真正需要的东西。
5. **早返回与守卫子句。** 先处理错误与边界情况，不要写层层嵌套的 `if`/`else`。
6. **拆开复杂函数。** 触发复杂度警告就拆成有名字的 helper，而不是静音警告。
7. **避免 LLM 腔：** em dash、预设式铺垫、把 diff 用散文复述一遍、结尾再多一段总结。
   这是默认偏好而非绝对禁令，适用于代码、注释、提交信息与 PR 描述。
8. **并发：每次改动共享状态之后都做两遍竞态分析**，包括读既有的 map 与 slice。
   用 mutex（或更合适的 atomic、channel）保护，临界区保持短，并在被改的包上跑 `go test -race`。
   要检查的失败模式见 [并发与生命周期](#并发与生命周期)。
9. **跨平台构建必须继续可用。** agent 面向 Linux、macOS、Windows、FreeBSD、Android、iOS。
   新增平台相关文件时，要给它补齐其他平台的对应实现或带 build tag 的兜底。
10. **绝不手改生成文件。** 改源头再重新生成。
11. **绝不记录机密** —— 私钥、setup key、token、PAT —— peer IP 与主机名不要出现在 debug 以上的日志里。

## 类型安全

**领域概念不要用裸基本类型。** 一个 `string` 参数是 account ID，旁边另一个 `string` 参数是
peer ID，这是两个等着发生的 bug，因为编译器抓不到这种互换。类型声明一次，全程使用，
只在数据进出的边界上转换 —— protobuf、gRPC、HTTP、外部库。

```go
type ServiceID string
type AccountID string

// 内部：全程强类型
func (r *Router) RemoveRoute(host SNIHost, svcID ServiceID) { ... }

// proto 边界：进出各转换一次
svcID := ServiceID(mapping.GetId())
req.ServiceId = string(svcID)
```

- **IP 地址用 `netip.Addr`**，不要 `string`，也不要 `net.IP`。在边界解析，把类型化的值往里传。
- **解析之后、从 `net.IP` 转换之后、从 `RemoteAddr()` 取出之后，一律 `Unmap()`。**
  它把 v4-mapped v6 地址（`::ffff:10.1.2.3`）归一成纯 v4，这样 IPv4 规则才能匹配上。
  存下来或用于比较的映射地址会静默地匹配不上那些规则。
- **端口内部用 `uint16`**；只在库强制要求的地方用 `int`，并且立刻转换。
- **枚举用「字符串类型 + 常量」**，让合法取值可被发现，让拼写错误编译不过。
- **map key 同理**，而且必须是真类型（`type ServiceID string`）而不是别名
  （`type serviceID = string`）—— 别名会静默接受裸字符串。

## 并发与生命周期

除了上面原则里的 mutex 卫生，还要检查这些失败模式。

- **绝不在 goroutine 里读结构体字段**，当另一个 goroutine 可能把它置 nil 或换掉时。
  把值当参数传进去，或者启动前捕获到局部变量。这在 `Stop()` 会置 nil 字段而不等 goroutine
  结束时最要紧。

  ```go
  go func(ifaceName string) {   // 好：参数传入，下面不可能被置 nil
      m.Start(ctx, ifaceName)
  }(iface.Name())
  ```

- **绝不在持有「发送方也需要」的锁时等 channel。** 需要的东西在锁下拷出来、放锁、再等。

  ```go
  func (m *Manager) Stop() {
      m.mu.Lock()
      cancel, done := m.cancel, m.done
      m.mu.Unlock()
      if cancel != nil {
          cancel()
          <-done
      }
  }
  ```

- **`Stop`/`Close` 必须幂等** —— 用已停止标志或 nil cancel 守卫 —— 并且要释放它守护的状态。
  清空 map 与缓存；被取消的 goroutine 若抓着一个仍存活的 map，那份内存就一直被钉住。
  注意 nil map 只在写时 panic，读与遍历表现得像空 map，所以「close 之后再使用必须被拒」的地方
  要显式检查停止标志。
- **所有可能失败的步骤都成功之后，再发布耦合状态。** 若干字段构成一条不变式时，
  先把它们构建到局部变量里，最后一次性赋给 receiver。边算边赋会在后面的步骤失败时留下
  半初始化的对象，于是就绪判定报告「就绪」而某个耦合字段还是 nil。
  如果之前的步骤已经产生外部副作用 —— 建了链、开了句柄、插了规则 —— 返回错误前先回滚。
- **构造函数的错误路径上清理自己占有的一切。** 构造函数一旦启动了什么，后面每条错误路径都要撤销：
  取消 goroutine 并等它退出、停 ticker、关 watcher。对象永远不会被返回，它的 `Close` 也就永远不会执行。
- **一次失败的 `Start` 必须撤销它启动的一切。** 组件按序拉起多个子系统（连接管理器、watcher、
  路由、DNS、flow、持久化状态）时，中途失败要把已经跑起来的都拆掉，而不是只关报错的那个句柄。
  「已经启动」的守卫要放在这条拆解路径**之前**，这样第二次 `Start` 被拒时不会把正在跑的那个拆掉。

## 错误处理

错误只在 `if` 内部用到时，用单赋值形式：

```go
// 好
if err := someCall(); err != nil {
    return fmt.Errorf("context: %w", err)
}

// 差 - 不必要的拆分
err := someCall()
if err != nil {
    return fmt.Errorf("context: %w", err)
}
```

错误块之后还要用值，才用多赋值：

```go
result, err := someCall()
if err != nil {
    return fmt.Errorf("context: %w", err)
}
```

加简短而有意义的上下文，并且**不要**以 "failed to"、"error" 这类废话开头：

```go
// 好
return fmt.Errorf("parse remote address: %w", err)
return fmt.Errorf("listen on %s: %w", addr, err)

// 差
return fmt.Errorf("failed to parse remote address: %w", err)
return fmt.Errorf("error listening on %s: %w", addr, err)

// 日志里写 "failed" 没问题
log.Debugf("failed to parse remote address: %v", err)
```

函数只是提取或转调、包装也不增加信息时，不要包：

```go
func parseAddr(addr string) (string, int, error) {
    host, portStr, err := net.SplitHostPort(addr)
    if err != nil {
        return "", 0, err
    }
    // ...
}
```

选择不处理的错误要记日志：

- `log.Debugf()`：不影响程序流程、但有助于排查的错误。
- `log.Tracef()`：非常啰嗦、否则会刷屏的错误。
- **绝不忽略**写、网络发送、关键清理的错误。
- 只读操作的 Close 错误可以忽略；写操作的按 debug 记。

**不要既记日志又返回同一个错误。** 它会被两处各报一次，而第二个读者无法判断到底发生了一次还是两次。
返回它，让调用方决定。例外是已经写出响应的 API handler。内部 helper 返回错误，而不是记日志并吞掉。

**绝不把带类型的 nil 当 error 返回。** 一个 nil 的 `*MyError` 存进 `error` 接口之后不是 nil，
于是 `err != nil` 为真，调用方在成功时走了失败路径。只在真的被赋值的地方返回错误：

```go
if _, err := conn.Write(buf); err != nil {   // 好
    return err
}
return nil
```

**一次操作应当在个别失败后继续时，用 `multierror` 累积** —— 拆解、清理，或允许部分成功的装配。
`client/errors.FormatErrorOrNil` 对空累积器返回 nil，所以调用方在完全成功时拿到的仍是普通 nil：

```go
func (m *Manager) Cleanup() error {
    var merr *multierror.Error
    for _, r := range m.resources {
        if err := r.Close(); err != nil {
            merr = multierror.Append(merr, fmt.Errorf("close %s: %w", r.Name, err))
        }
    }
    return nberrors.FormatErrorOrNil(merr)
}
```

| 场景 | 做法 | 原因 |
|---|---|---|
| 清理 / 拆解 | 累积 | 尽可能多清掉一些 |
| 装配需要回滚 | 首个错误即中止 | 半成品状态无效；撤掉已生效的 |
| 装配允许部分成功 | 累积 | 降级运行仍然有用 |

## 注释

注释写 **why**，绝不写 **what**。默认不写，只在某个隐性约束或 workaround 会让后来的读者意外时才加。
绝不在注释里提到当前任务、PR 或你自己的改动。

```go
// 差 - 解释显而易见之事的尾随注释
defer localConn.Close() // 关闭连接
if err != nil {         // 检查是否出错

// 好
defer localConn.Close()

// 好 - 解释非显而易见的约束
// 按 RFC 1624 做增量校验和更新，为了性能。
checksum = updateChecksum(checksum, oldPort, newPort)
```

### 长度预算

下面两条都**不是** linter 强制的，是周边代码大体遵守的约定而非硬上限：

- **每行约 90 字符。** 该换行就换行，不要大幅超出。
- **每条注释约 250 字符**，约三行。导出标识符的文档注释在 API 确实需要解释时可以超出；
  函数体里的行内注释很少应该超出。

预算是异味探测器，不是拿来钻的空子。不要为了塞进去而把必要的解释压成神秘缩写 ——
如果一块代码需要超过 250 字符的散文，那是代码做得太多了。修代码：

- **抽出有名字的函数。** 好名字替代注释：名字说 *what*，函数体展示 *how*，你省下的那条注释
  本来也只是在讲 *what*。Clean Code 称此为「用代码解释自己」。
- **抽出有名字的常量或谓词。** `if isExpiredSetupKey(key)` 不需要注释；
  `if key.ExpiresAt.Before(now) && !key.Revoked && key.UsageLimit > 0` 需要。
- **把剩下的注释留给 why** —— RFC、内核怪癖、顺序约束。这部分通常一两行。

### 长 switch 与 if/else 链

带多行解释的 `switch` 是最常突破预算的地方，而那条注释本身就是症状。按优先级：

1. **把每个 case 体抽成有名字的函数。** case 变成一行，名字承载含义，switch 读起来像目录。
2. **用查表替代 switch** —— 分支形制统一时用 `map[Kind]handlerFunc`。加一个分支就不再意味着
   改一个越来越大的函数。
3. **分支随类型变化、且同样的 switch 形状开始出现在第二处时，用多态替代条件。**
   Clean Code 的经验法则：一个 switch 语句若只出现**一次**、藏在一个返回接口的工厂里、
   没有别处对同一类型做 dispatch，那可以容忍。对同一个枚举的第二个 switch 就是引入接口的信号。

case 各一行且自明时，不要纯粹为了满足预算去重排 —— 一个扁平的、无聊的、对枚举做 dispatch 的
`switch` 很好，完全不需要注释。

测试里的解释性注释是受欢迎的 —— 它们记录被搭建的场景，250 字符预算不适用于测试。

## 测试

- **单元测试**与代码同目录，`_test.go`。`make test-unit` 跑宿主安全的那一套，
  带 `-tags devcert`、不需要 sudo。
- **privileged 测试**带 `privileged` build tag，会改动宿主网络。它们通过 `make test-privileged`
  在带 `NET_ADMIN` 的 Docker 容器里跑。**绝不在宿主上直接跑来绕过那套 harness。**
- **端到端测试集**在 `e2e/`，共用一套 harness。
- **测真实行为，不测 API 是否存在。** 断言消费方能观察到的最终状态 —— 到达的字节、
  翻译之后的那个包、写入之后的那一行 —— 而不只是某个方法存在或返回了错误。
- **自己拥有的代码避免用 mock。** 用真实的 store、manager、controller，并断言调用方实际收到什么。
- **`require` 用于装配与前置条件，`assert` 用于被测条件。** 后续一行会 panic 或失去意义的
  地方一律用 `require`。
- **断言消息：** `NoError`/`Error` 可选；比较、布尔、集合断言一律给上下文。
- **先复现再修。** 先写测试，看着它因为**你预期的原因**失败 —— 因为不相干原因失败的测试什么都证明不了 ——
  然后修，再确认通过。顺手把周边那几个薄薄的用例补上。
- **用 `t.Setenv`** 而不是 `os.Setenv`，让原值在清理时恢复。要测未设置的情形，
  先调 `t.Setenv` 登记恢复动作，再 `os.Unsetenv`。
- **有并行子测试时优先 `t.Cleanup` 而不是 `defer`**：父函数返回时执行 `defer`，而并行的子测试
  还挂起着。顺序执行时 `defer` 安全，但 `t.Cleanup` 两种情形都对。
- **测试里的解释性注释受欢迎。** 描述被搭建的场景。

```go
server, err := StartTestServer()
require.NoError(t, err, "Test server setup must succeed")
defer server.Close()

result, err := client.DoOperation()
assert.NoError(t, err)
assert.Equal(t, expectedResult, result, "Result should match expected")
```

本 fork 的补丁期望有对应的测试：`client/internal/profilemanager`（字段被发布）、
`client/internal/dns`（前缀与派生路径一起重算）。前缀从 `const` 改成 `var` 之后，
**重算的完整性**是最值得断言的一点。

## 坑

- **agent 以 root 运行。** 任何触碰路由、防火墙、DNS、接口的改动都可能让用户的机器断网。
  优先做可逆的改动，并确保每条退出路径都执行清理。
- **Management 有两个账户加载器**（GORM 与 pgx）。给 account 加关联时往往两边都要改，
  否则生产环境里会静默返回空。
- **不带 `-tags devcert` 的 `go test ./...` 会跳过**需要开发证书的测试。用 `make test-unit`。
- **`make lint` 只查相对 `origin/main` 的 diff。** CI 跑的是 `make lint-all`；大改动推送前也跑一次。
- **proto 被已发布的客户端消费。** 旧 agent 必须能对上新 Management，所以字段只增，
  绝不重新编号或删除。
- **Windows 需要 wintun 驱动**，且 daemon 提供的是命名管道（`npipe://netbird`）而不是回环 TCP。
  回环 TCP 不携带调用方身份，所以特权操作经它一律被拒。

本 fork 的多实例部署另有几条坑（完整清单与排障手法见
[本 fork 的改动](#本-fork-的改动) 与 `.ai/skill/netbird-multi-instance/`）：

- **`wintun.dll` 必须与 exe 同目录**，否则报 `Error loading wintun.dll`。
- **实例目录绝不能放在 `%PROGRAMDATA%\Netbird` 树内**：主实例的 MDM 策略重载 ticker 每 60 秒
  监视该树，实例在里面写状态会被当成策略变更，主实例随即拆掉并重建自己的引擎。
- **配置 JSON 不能带 BOM**（`invalid character '\ufeff'`）。PowerShell 的 `Set-Content -Encoding UTF8`
  会加，用 `UTF8Encoding($false)` 写字节。
- **强杀会让该实例的 wintun PnP 设备处于坏状态**，下次启动报
  `create tun device: ... cannot find the file specified`。
- **删 wintun PnP 设备前先确认归属。** 这个设备类是与无关软件共享的（实测某台机器上列着
  Tailscale 的适配器），判归属看 `FriendlyName` 并在候选二进制里搜 GUID。

## 提交与发布

- **PR 标题必须以方括号 tag 开头。** 提标题之前，**读
  [`.github/workflows/pr-title-check.yml`](.github/workflows/pr-title-check.yml)，
  从里面的 `allowedTags` 数组取允许的 tag。** 那是唯一事实源，它会随组件增减而变，
  且该检查在每次标题编辑时都跑 —— 不在数组里的 tag 就是红。不要依赖从别处（包括本文件）
  记下来的列表。

  ```text
  [client] Authorize daemon IPC callers by their local identity
  [management,client] Add MDM policy support
  ```

  多个 tag 逗号分隔，放在同一对方括号里。tag 要对上你实际改的组件，而不是你读得最多的那个。

- **用仓库的 PR 模板。** 填
  [`.github/pull_request_template.md`](.github/pull_request_template.md)，不要用自己的摘要替掉它：
  描述改动、链接 issue、诚实地勾选清单（包括「本地跑过」「单一目的」）、并完成文档那一节。
  没验证过的框不要勾，不适用的行也不要删 —— CI 里的文档 gate 会读那一节，缺了就红。

- **PR 描述要短。** 模板正文之外不超过 1000 词，通常远少于此 —— 几段就够。reviewer 会读 diff，
  描述存在的意义是讲 diff 自己说不清的事。这远低于 agent 的默认产出量，所以贴出去之前先砍。

- **正文：why 先于 what。** 先讲问题与选这条路的原因，再讲改动的形状。不要文件清单、
  不要逐函数走读、不要用散文复述 diff、不要结尾总结节、不要自我表扬式的收尾句。

- **PR 描述里不要 `Co-Authored-By` 或工具署名 trailer**，提交里同理。贡献归属作者本人：
  无论什么工具产出了这份 diff，开 PR 的人就是它的作者 —— 他读过每一行、能解释为什么成立、
  能不看模型就回答 review 问题，并为合并后的后果负责。不要加任何把这份归属摊给某个工具的
  trailer、footer 或描述行。

- **提交标题同样遵循 `[scope] Subject` 约定。** 标题要短，正文用来说 why 先于 what。
  不要文件清单。

- **review 修补单独提交。** PR 合并时会 squash，所以 review 期间没有理由改写历史；
  多个小提交让复审更易读。

- **不要强推正在 review 的分支。** 强推会让已有 review 评论脱离它们所在的行，毁掉 reviewer
  依赖的「上次 review 之后的改动」diff，也丢掉「哪个提交弄坏了什么」的 CI 历史。
  追加提交 —— fixup 与 revert 也一样。只有在别无选择时才强推：rebase 解决真实冲突，
  或移除误提交的机密、大文件。必须强推时，先问用户，再在 PR 里留一条评论告知 reviewer
  他们的锚点移动了。**绝不强推 `main`，也绝不强推不属于你的分支。**

- **一个 PR 一个目的。** 重构与修复分开，修复与功能分开。

- **PR 要小。** 体积是 PR 等待时间的最强预测因子。目标 **400 行以内、20 个文件以内**；
  超过大约 **1000 行或 50 个文件**，社区 PR 很可能被退回拆分，或者在拆开之前一直没人看。
  团队外的大 PR，若体积从没在 ticket 里谈过，可能直接被挡 —— 对着一个特权网络守护进程
  review 一大坨改动，本身就是安全风险，不只是时间成本。

  体积按手写代码估算：生成产物、`go.sum`、vendored 文件、测试 fixture 不计入，
  但也不要拿它们的存在来论证一个 3000 行的 PR 很小。

  改动确实小不了时 —— 协议迁移、跨组件重命名 —— 在 ticket 里**先把拆法谈定**再写代码，
  落成一串各自能构建、能测试、能独立说通的 PR。把拆法提给用户，而不是开一个大 PR 指望运气。

  这类序列优先用 GitHub 的 stacked pull request，而不是手工维护 base 分支：每个 PR 开在它下面
  那个分支上而不是 `main`，于是每个 PR 的 diff 只显示自己那层。合并一层会自动把上面的 PR
  retarget，base 分支上的分支保护与必需检查对每个 PR 依旧生效。

- **面向用户的改动需要配一份 docs PR**，在
  [netbirdio/docs](https://github.com/netbirdio/docs)，并从 PR 描述里链接过去。

## 推送之后

开 PR 不是任务的终点。看着跑、读 bot 说了什么、把 PR 推到绿再报告完成。

```bash
gh pr checks <pr>            --watch    # 全部检查，实时
gh run view <run-id> --log-failed       # 只看失败的步骤
gh pr view <pr> --comments              # bot 与人的 review 评论
```

**检查还在 pending 或红着时，绝不报告改动已完成**，也绝不把红的 PR 说成通过。
若你在 CI 出结果前就结束了这一轮，说清楚哪些检查还在跑。

### 检查有哪些

- **Go 测试** —— `golang-test-{linux,darwin,windows,freebsd}.yml`，按组件分片。你没碰的组件失败
  往往是真实的相互作用，不是噪声；先读日志再判定 flake。
- **golangci-lint** —— `golangci-lint.yml` 跑全仓，而 `make lint` 只查你的 diff。
  大改动上本地 lint 干净不等于 CI 会绿。
- **PR Title Check** —— `pr-title-check.yml`，见上。
- **Codecov** —— 由 Linux 测试流程上传，按组件打 flag（`unit,client`、`unit,management`、
  `unit,relay`、`unit,proxy`、`unit,signal`、`integration,management`）。新代码的覆盖率不应倒退。
  为你引入的路径补测试；不要调阈值或排除文件来清报告。
- **CodeRabbit** —— 配置在 [`.coderabbit.yaml`](.coderabbit.yaml)：`chill` 档，非 draft PR 自动 review，
  TypeScript/JavaScript/SVG 路径被过滤。chat 自动回复开着，评论里 `@coderabbitai` 能叫到它。
- **SonarCloud** —— 项目 `netbirdio_netbird`，质量门针对新代码（bug、漏洞、代码异味、重复、覆盖率）。
- **Snyk** —— 依赖与代码扫描。

Sonar 与 Snyk 在本仓库以 GitHub App check 的形式报告，而不是 workflow，所以细节在 PR check 上，
不在 Actions 日志里。

### 怎么处理 bot 的发现

- **每条评论都读、都处理。** 要么修掉，要么回复说明为什么不适用。不要为了清数字批量 resolve，
  也不要因为某个检查只是建议性质就默默忽略。
- **bot 在这里经常是错的。** NetBird 有大量特权、平台相关、并发密集的代码，静态分析读得很糟。
  一条自信的 CodeRabbit 或 Sonar 评论也可能完全站不住。改之前先对着代码核实 ——
  绝不为了让 bot 闭嘴去改正确的代码。
- **安全类发现默认反过来：** 对 Snyk 或 Sonar 的漏洞，或 CodeRabbit 关于认证、授权、证书校验、
  密钥处理的评论，在你证伪之前都当它是真的。把它交给用户，而不是自己判掉。
- **新出现的脆弱依赖是停手信号。** 升级或替换依赖需要用户决定，见上。
- **绝不为了让检查通过而改 workflow、阈值、lint 排除或 bot 配置。** 如果某个检查确实错了，
  说出来并让用户决定。
- **不要靠盲目重跑掩盖 flake。** 先定位失败。若它是已知 flake，点名；若你分不出来，
  就按「未解决」上报，而不是重跑到绿。

## 讨论与支持

- Discussions：<https://github.com/netbirdio/netbird/discussions>
- Slack：<https://docs.netbird.io/slack-url>
- 文档：<https://docs.netbird.io>
- 安全：<https://github.com/netbirdio/netbird/security/policy> —— 绝不要发在公开渠道
- 贡献流程：[CONTRIBUTING.md](CONTRIBUTING.md)

## 本 fork 的改动

本仓库是 `netbirdio/netbird` 的**公开 fork**（`Shinglem/netbird`），在上游之上携带一套 opt-in 的小补丁集，
目的是让**多个 NetBird 实例能在同一台 Windows 主机上同时运行**：各有自己的 wintun 适配器、
路由表项、DNS 命名空间和状态目录。

**在改动 `client/iface/device/`、`client/internal/dns/`、`client/internal/profilemanager/`
下任何内容之前，以及在构建或部署客户端二进制之前，请先读完本节。**

### 上游为什么做不到

上游支持在 profile 之间**切换**，不支持**并存**：有两处状态是**进程级、且启动时即固定**，
第二个实例（也就是第二个进程）一定会来抢。其余状态都能用普通参数隔离
（`--config`、`--daemon-addr`、`--log-file`、`NB_STATE_DIR`）。

| 单例 | 位置 | 两个实例共用时的症状 |
|---|---|---|
| wintun 适配器 GUID（硬编码 `{f2f29e61-…}`） | `client/iface/device/device_windows.go` 读 `CustomWindowsGUIDString`，而上游生产代码从不给它赋值 | 两个进程要同一个 GUID，于是第二个接口建不起来 |
| NRPT 键命名空间（`NRPTKeyPrefix = "NetBird-"`） | `client/internal/dns/host_windows.go`，清理时按前缀枚举整机策略存储 | 一个实例退出会删掉另一个实例的 DNS 规则 |

### 改了什么

#### 1. `client/iface/device/windows_guid.go`（新增）

上游已经声明了扩展点，只是没人用，所以这里不另造机制，只加 setter：

```go
func SetWintunGUID(guid string) {
	if guid == "" {
		return
	}
	CustomWindowsGUIDString = guid
}
```

另有 `init()` 读 `NB_WINDOWS_GUID` 作为环境变量兜底。

**这个文件刻意不带 build tag。** 它写的那个变量只被 `device_windows.go` 读，但一个带 build tag
的 setter 会迫使每个调用方都带上对应的 tag。在所有平台都定义它（非 Windows 上同样有定义，
代价是一个没人读的包级变量），才能让 `profilemanager` 里的调用点保持平台无关。

#### 2. `client/internal/dns/host_windows.go`

前缀及由它派生的一切，从 `const` 变成 `var`：

```go
const defaultNRPTKeyPrefix = "NetBird-"   // 上游原值，仍是默认值

var (
	NRPTKeyPrefix    = defaultNRPTKeyPrefix
	nrptMatchKeyName = NRPTKeyPrefix + "Match"

	dnsPolicyConfigMatchPath          = DNSPolicyConfigRoot + `\` + nrptMatchKeyName
	gpoDnsPolicyConfigMatchPath       = GPODNSPolicyConfigRoot + `\` + nrptMatchKeyName
	dnsPolicyConfigExemptLocalPath    = DNSPolicyConfigRoot + `\` + NRPTKeyPrefix + `ExemptLocal`
	gpoDnsPolicyConfigExemptLocalPath = GPODNSPolicyConfigRoot + `\` + NRPTKeyPrefix + `ExemptLocal`
)
```

加上 `SetNRPTKeyPrefix` / `setNRPTKeyPrefix`（后者重算**全部五个**派生值），以及读
`NB_NRPT_KEY_PREFIX` 的 `init()`。`NRPTKeyPrefix` 保留导出名，因为 `debug_windows.go` 会读它。

⚠️ **`const` 改 `var` 是本补丁最危险的部分。** 没有任何东西保证那些派生路径只在 `init()` 跑完之后
才被读。若将来有改动让别的包的 `init()` 期间读它们，读到的值就取决于包的初始化顺序。

#### 3. `client/internal/dns/nrpt_prefix_other.go`（新增）

```go
//go:build !windows

package dns

// SetNRPTKeyPrefix is a no-op off Windows. NRPT is a Windows-only mechanism:
// elsewhere the resolver configuration is not keyed by a prefix this package
// picks, so there is nothing to namespace.
//
// It is defined here so that the profile-config loader can call it on every
// platform without a build tag of its own.
func SetNRPTKeyPrefix(string) {}
```

#### 4. `client/internal/profilemanager/config.go`

`Config` 增加两个字段（都是 `omitempty`，零值等价于上游行为）：

```go
	// WintunGUID ...
	WintunGUID string `json:",omitempty"`

	// NRPTKeyPrefix ...
	NRPTKeyPrefix string `json:",omitempty"`
```

并由 `readConfig` 发布它们：

```go
func (config *Config) publishInstanceIdentity() {
	device.SetWintunGUID(config.WintunGUID)
	dns.SetNRPTKeyPrefix(config.NRPTKeyPrefix)
}
```

**调用点的位置才是关键决策。** 它在 `readConfig`、**不在** `apply()` —— `apply()` 也会对一次性的
探针配置和基线配置执行，而从一份只被「看一眼」的配置里写进程级状态，会把某个 profile 的身份
泄漏进正在运行的那个。

### 优先级

```text
profile 配置字段  >  NB_WINDOWS_GUID / NB_NRPT_KEY_PREFIX  >  内置默认值
```

配置优先，因为配置会覆盖环境变量（环境变量在 `init()` 里读，配置在其后）。每一层的空值都被忽略，
所以配置字段的零值恰好等于上游行为。

⚠️ **「配置优先」不等于「有配置就够了」。** 见下面那节时序缺陷：跑在 `readConfig` 之前的路径
永远看不到配置值，对那些路径而言**环境变量是必需的**。

### 硬规则

1. **`NB_NRPT_KEY_PREFIX` 必须导出，不能只配置。** 配置值由 `readConfig` 发布，而它跑在不干净关闭的
   恢复路径**之后**。凡是设置了 `NB_STATE_DIR` 的启动脚本，都必须同时设置它。
2. **进程级设置一律从 `readConfig` 发布，绝不要从 `apply()`。** 新增此类设置时，先枚举所有跑在
   `readConfig` **之前**的路径（包的 `init()`、状态管理器的清理、不干净关闭的恢复），再决定用
   哪个通道触达它们。
3. **保持非 Windows 平台可构建。** 两个 setter 在所有平台都有定义，调用方不需要 build tag。
   验证时要用 `GOOS=linux` 与 `GOOS=darwin` 构建，不能只验 Windows。
4. **不要把 NRPT 前缀改回 `const`。** `NRPTKeyPrefix` 与由它派生的四条路径必须在每次前缀变化时
   一起重算；把它们拆到一个 const 加一个 var 里，正是它们彼此漂移的原因。
5. **这些改动不会让客户端变得便携。** 服务注册、计划任务、wintun 内核驱动、NRPT 注册表项与
   wintun PnP 设备依然是系统状态。不要把结果描述成「绿色安装」或「便携安装」。
6. **保持 opt-in、保持零默认行为变化。** 两个字段都 `omitempty`，同一份二进制仍可作为普通客户端使用。

### 启动时序缺陷：为什么环境变量是必需的

**严重度：高。** 只设配置字段就部署，会让第二个实例删掉**第一个**实例的 DNS 规则。

当守护进程没能干净退出 —— 强杀、崩溃、断电 —— `state.json` 里仍留着 `dns_state` 条目。
下次启动时，状态管理器会在**读取 profile 配置之前**执行恢复：

```text
daemon 启动
  |
  +-- statemanager 载入 state.json
  |     dns_state 存在 -> dns.ShutdownState.Cleanup()
  |        -> registryConfigurator.restoreUncleanShutdownDNS()
  |           -> removeDNSMatchPolicies()
  |              *** 按 NRPTKeyPrefix 删键；此刻前缀还是默认的 "NetBird-" ***
  |              *** 于是它删掉了「另一个」实例的键                        ***
  |
  +-- readConfig
        -> publishInstanceIdentity()
           -> dns.SetNRPTKeyPrefix("NBAlt-<名>-")   <-- 太晚了
```

删除动作本身正确，作用域也对 —— `listNRPTRuleKeys` 是按前缀过滤的。缺陷只在于那一刻前缀还没被设过。

**症状：** 启动第二个实例后，第一个实例一直健康、`Connected` 也没掉，却失去了域名解析，
它自己的日志里什么都没有（不是它删的）。

**为什么不能靠「让恢复路径晚点读前缀」来修：** 恢复路径**必须**跑在 `readConfig` 之前，
因为 `readConfig` 决定哪个 profile 是激活的，而恢复要清理的是**上一次**运行留下的东西。

**回归测试（必须做，因为正常退出会完全掩盖这个 bug）：**

```powershell
# 1. 两套键都在
Get-DnsClientNrptRule | Where-Object Name -like 'NetBird-*'
Get-DnsClientNrptRule | Where-Object Name -like 'NBAlt-*'
# 2. 强杀第二个实例，让它来不及清理，从而留下 dns_state
#    再启动它
# 3. 断言：它的 daemon.log 里出现 "cleaning up state dns_state"
#    断言：Get-DnsClientNrptRule 仍列出【两套】命名空间
#    断言：两个 mesh 名都能 Resolve-DnsName 成功
```

### 部署形态

```text
<root>\                             # 本机取值见 .local/environment.md
  |- netbird-multi.exe              # 一份自编译二进制服务所有实例
  |- wintun.dll                     # 必须与 exe 同目录
  |- profiles\<name>\
       |- daemon.ps1                # 计划任务指向它
       |- instance.json             # 脚本元数据：mode、iface、wgPort、guid、nrptPrefix、domains
       |- config\default.json       # --config；一份配置 = 一个实例的完整身份
       |- runfiles\{state.json, active_profile.json, daemon.log}
```

启动一个实例：

```powershell
$env:NB_STATE_DIR       = '<root>\profiles\<name>\runfiles'
$env:NB_NRPT_KEY_PREFIX = 'NBAlt-<name>-'      # 必需 —— 见上面的时序缺陷
& '<root>\netbird-multi.exe' service run `
    --config '<root>\profiles\<name>\config\default.json' `
    --daemon-addr 'npipe://netbird-<name>' `
    --log-file '<root>\profiles\<name>\runfiles\daemon.log'
```

`config\default.json` 至少要包含各实例互不相同的 `WgIface` / `WgPort` / `WintunGUID` /
`NRPTKeyPrefix`，且 `DisableDNS: false`（让实例自己管 DNS）。管理服务器域名也要与其他实例不同 ——
两个网络用同一个域名会在客户端解析器里互相打架。**改配置要改文本，绝不要经 `ConvertFrom-Json` 往返**：
`ManagementURL` 是 `url.URL`，其结构形式无法往返，`PrivateKey` 必须逐字节一致。

编排脚本不在本仓库：`netbird-multi.ps1`（`up` / `down` / `status` / `list` / `update-alt`）与
构建脚本 `build-netbird.ps1` 在技能库 `mesh-vpn-management` 下，默认
`-RepoDir <repo>`、`-DeployDir <deploy-root>`（本机取值见 `.local/environment.md`）。**重起一个 Source 模式的实例必须显式
写 `-Mode Source`**：`up` 不读 `instance.json` 里已存的 mode，默认 Patch 会按 Patch 起重并覆盖
`instance.json`（`nrptPrefix` 与 `domains` 随之丢失）。

### 验收

```powershell
# 两个适配器都在
Get-NetAdapter -IncludeHidden | Where-Object InterfaceDescription -match WireGuard

# 每个实例一套 NRPT 命名空间
Get-DnsClientNrptRule | Where-Object { $_.Name -like 'NetBird-*' -or $_.Name -like 'NBAlt-*' }

# 两个网络都能解析 —— 而且是系统层解析，不是 `netbird status`
Resolve-DnsName <主 mesh 名>
Resolve-DnsName <第二个 mesh 名>
```

`netbird status` 不足以作为证据：它报告守护进程的视角，会在系统完全解析不了时照样显示
`Connected`。也不要看 `Nameservers: n/n` 计数器判断 DNS —— 它数的是 netbird 托管的 nameserver，
即使在 Custom Zones 正常工作时也读作 `0/0`。唯一的证明是真实发起一次查询。

部署重新构建的二进制之前，先确认实例身份相关的符号确实在产物里 —— 一个悄悄丢了它们的构建
照样能跑，照样用默认 GUID 与默认前缀：

```bash
grep -c WintunGUID <binary> && grep -c NRPTKeyPrefix <binary> && grep -c SetNRPTPrefix <binary>
```

### 公开仓库，不走上游流程

本仓库是**公开**的（`Shinglem/netbird`），但只供自用，**不向上游提 PR**。上游的 STOP 规则
（开票、high-risk area 先谈设计、走 CI 与 review bot 流程）**都不适用**：改这里的东西不需要先开票，
文档用中文即可。`[scope] Subject` 的提交前缀照用；**但不加工具署名 trailer** ——
`.githooks/commit-msg` 会拒绝（`make setup-hooks` 装上钩子之后生效）。

**正因为公开，本机事实一律不进仓库。** 绝对路径、账户名、实例名、wintun GUID、mesh 域名、
代理端口，统一放进被忽略的 `.local/`（唯一事实表 `.local/environment.md`，占位符对照
`.local/README.md`），文档里只写 `<repo>`、`<deploy-root>`、`<instance>`、`<guid>`、`<proxy-port>`
这类占位符。提交前自查：

```bash
git grep -nIE '[A-Za-z]:\\\\|/home/[a-z]|/Users/[A-Za-z]|/mnt/[a-z]|/s/[a-z]+/' -- ':!.local'
```

命中就改成占位符或搬进 `.local/`；历史里已经混进去的，重写后再推。

唯一值得从上游保留的习惯是：这套补丁触及客户端系统集成（DNS、接口）与配置文件格式，属于上游
眼中的高风险区 —— 所以改动时**优先复用既有扩展点、保持 opt-in、保持零默认行为变化**，
这些习惯本身能让改动更容易验证，与是否上游无关。

`patchdoc/` 是这套补丁的**变更记录与复现步骤**（含被本文件摘要掉的源码级细节与实测日志）。
本文件是**规则**，`patchdoc/` 是**证据**；两边冲突时以本文件为准，并把 `patchdoc/` 改过来。

| 路径 | 内容 |
|---|---|
| [`patchdoc/0001-profile-driven-instance-identity.md`](patchdoc/0001-profile-driven-instance-identity.md) | 三处代码改动的逐处说明与位置选择理由 |
| [`patchdoc/0002-nrpt-prefix-timing.md`](patchdoc/0002-nrpt-prefix-timing.md) | 启动时序缺陷的完整机制与复现步骤 |
| [`.ai/skill/netbird-multi-instance/`](.ai/skill/netbird-multi-instance/SKILL.md) | 部署与排障手法（含 `references/` 下的现场证据） |

### 与上游同步

```bash
git remote add upstream https://github.com/netbirdio/netbird.git
git fetch upstream
git rebase upstream/main
```

改动集中在 4 个文件、约 140 行，冲突可能性低；若真冲突，会落在这两处：

- `client/internal/profilemanager/config.go` —— `Config` 的字段区与 import 区
- `client/internal/dns/host_windows.go` —— 前缀及其派生值由 `const` 改 `var` 的那段

本 fork 不推送上游，rebase 只是为了跟上新版本。**本文件是上游 `AGENTS.md` 的中文改写版**，
rebase 时这个文件必然冲突，解决方式是保留本文件（我们的版本），再从上游差异里挑出真正的规则变更
补进来。

## 桌面 UI

本仓库新增了一个**桌面前端**：`desktop-ui/`（Kotlin / Compose Multiplatform，Windows 优先）。
它只是已有 daemon 的客户端 —— 网络栈、DNS、路由、账号全在 daemon 里。

**在改动 `desktop-ui/` 下任何内容之前，以及在给 UI 接 daemon 之前，请先读
[`desktop-ui/AGENTS.md`](desktop-ui/AGENTS.md)** —— 那份是该层的**规则权威源**
（定位、非目标、技术栈、契约、硬约束、验收、编码规范）。本节只放几条不可违反的摘要，
细则不在这里重复。

### 不可违反的五条

1. **UI 只走 daemon 自带的 HTTP/JSON 网关**：
   `POST http://127.0.0.1:<port>/daemon.DaemonService/<Method>`（grpc-gateway，
   `--enable-json-socket --json-socket tcp://127.0.0.1:<port>`）。
   Windows 上 daemon 默认只监听命名管道，而 Kotlin/JVM 没有可用的 gRPC-over-named-pipe 传输 ——
   **不要试图自己实现一条**。

2. **`Up` 必须传 `async: true`**。默认 `false` 会阻塞到引擎就绪（CLI 的语义），UI 会表现为点击无反应。

3. **流式 RPC 按行解析**：`SubscribeStatus` / `SubscribeEvents` 是持续输出、不会结束，
   且 `SubscribeStatus` 首帧即当前快照。

4. **Go 树只读**：`client/**/*.go`、`client/proto/**` 不改。UI 的需求优先在 UI 侧解决；
   确需改 daemon 或 proto 的，先在 `patchdoc/` 写方案，由用户拍板，不在同一次提交里混做。

5. **出 Windows 成品只走 `:composeApp:verifyReleaseTest`**（内部先 `syncReleaseTest`：jlink 裁剪
   `rt\` + jpackage 只产启动器 + 自写 cfg + jar 镜像，然后校验结构/模块集合/skiko 资产）。
   `createDistributable` 只是开发态 app-image，**不是成品**；也不要手改 `release-test\` 里的构建产物。

（第 1、2 条的完整理由与证据行号见 `.ai/skill/netbird-desktop-ui-contract/`；
第 5 条的结构与打包态坑见 `.ai/skill/desktop-release-packaging/`。）

### 规则与技能在哪

| 路径 | 内容 |
|---|---|
| [`desktop-ui/AGENTS.md`](desktop-ui/AGENTS.md) | **UI 层规则权威源**（含多实例、托盘、单实例、编码规范、验收） |
| [`.ai/skill/README.md`](.ai/skill/README.md) | 技能清单唯一处（含 UI 用的 6 个技能、来源与同步方式） |
| [`.ai/skill/netbird-desktop-ui-contract/SKILL.md`](.ai/skill/netbird-desktop-ui-contract/SKILL.md) | 接 daemon 的工作流与坑；`references/daemon-api.md` 是完整 RPC 与字段表 |
| [`.ai/skill/desktop-release-packaging/SKILL.md`](.ai/skill/desktop-release-packaging/SKILL.md) | **出成品前必读**：启动器结构、jlink 裁剪、图标、打包态坑 |
| [`.ai/skill/netbird-multi-instance/SKILL.md`](.ai/skill/netbird-multi-instance/SKILL.md) | 多实例部署形态，UI 的实例发现基于它 |
| [`.github/copilot-instructions.md`](.github/copilot-instructions.md) | VS Code / Copilot 的指针文件（不含规则内容） |

### 构建与验收（UI 层）

```bash
cd desktop-ui
./gradlew :composeApp:compileKotlinDesktop   # 快速编译检查（KMP 下 compileKotlin 是歧义任务）
./gradlew :composeApp:run                    # 运行（需要先有带 json-socket 的 daemon）
./gradlew :composeApp:verifyReleaseTest      # 出成品：jlink 裁剪 + 启动器 + 结构校验
```

验收的前提是 daemon 网关可达：

```bash
curl -s -X POST http://127.0.0.1:8471/daemon.DaemonService/Status \
  -H 'Content-Type: application/json' -d '{}'
```

端口约定 **8471 起、每实例 +1**。完整验收表见 `desktop-ui/AGENTS.md` §7。

### 与 Go 侧工作的边界

- UI 是纯客户端：**不改变同一份 daemon 二进制的行为**，也不进 `client/` 的构建产物。
- 若某个需求确实要 daemon 支持（新 RPC、新字段），按 `patchdoc/` 的既有做法处理：
  写一份编号文档说明改了什么/为什么/怎么验收，再由用户决定是否落地。
