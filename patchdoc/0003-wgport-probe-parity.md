# 0003 —— WgPort 探测必须与真实绑定一致

**严重度：中高。** 端口探测判定「空闲」而实际绑定失败时，引擎起不来、无限重试同一端口，
界面一直卡在 `Connecting`，日志却只重复同一行 `bind: Only one usage of each socket address`。
多实例部署下这几乎是必然会碰到的：Windows 的动态端口范围（默认 49152–65535）里，
任何系统服务都可能先一步占住配置的 `WgPort`。

## 症状

实例启动后状态一直是 `Connecting`，覆盖网络不通：

```
ERRO client/internal/engine.go:676: failed to pull up wgInterface [wt-<实例>]:
     listen udp6 :<WgPort>: bind: Only one usage of each socket address
     (protocol/network address/port) is normally permitted.
ERRO client/internal/connect.go:500: error while starting Netbird Connection Engine: up wg interface: ...
```

同时**没有**出现 `using <N> as wireguard port: <WgPort> is in use` —— 说明
`freePort` 认为端口可用，压根没有走换端口的兜底。

查占用：

```powershell
Get-NetUDPEndpoint -LocalPort <WgPort> | Select-Object LocalAddress,LocalPort,OwningProcess
# LocalAddress = ::  <- 只有 IPv6 通配地址被占
```

实测一次事故的占用者是 `Internet Connection Sharing (SharedAccess)` ——
Hyper-V 的 `vEthernet (Default Switch)` 会把它拉起来，而它会占用动态端口。

## 根因：两种「绑定」语义不一致

`client/internal/connect.go` 的 `freePort` 原来只用一个**双栈** socket 探测：

```go
conn, err := net.ListenUDP("udp", &net.UDPAddr{Port: initPort})
```

而真正的绑定是 wireguard-go 的 `WinRingBind.Open`
（`github.com/netbirdio/wireguard-go/conn/bind_windows.go`）：它用 `winrio.Socket` 建
**AF_INET 与 AF_INET6 两个独立 socket**，绑同一个端口号。二者对「端口可用」的判定不同。

实测（Windows，一个只占 IPv6 通配地址、带 `SO_REUSEADDR` 的 socket 存在时）：

```text
held v6-reuse port: 59876
udp  :59876 -> ok      <- freePort 的探测，误判为可用
udp4 :59876 -> ok
udp6 :59876 -> FAIL    <- wireguard 的 IPv6 socket，失败
```

也就是说：只要占用方只持有 IPv6 侧（系统服务常见做法），双栈探测就探不出来。

## 改了什么

### 1. `client/internal/connect.go`

`freePort` 只负责「试请求端口，不行就让系统分配」，探测细节交给平台文件：

```go
func freePort(initPort int) (int, error) {
	if port, err := probeUDPPort(initPort); err == nil {
		return port, nil
	}

	// if the port is already in use, ask the system for a free port
	port, err := probeUDPPort(0)
	if err != nil {
		return 0, fmt.Errorf("unable to get a free port: %w", err)
	}
	return port, nil
}
```

### 2. `client/internal/connect_port_windows.go`（新增）

按引擎真正会开的三种 socket 依次探测：`udp`（双栈，用来发现被宽松占用的端口）、
`udp4`、`udp6`。任一失败即判定该端口不可用。

```go
var probeNetworks = []string{"udp", "udp4", "udp6"}

func probeUDPPort(port int) (int, error) {
	for _, network := range probeNetworks {
		conn, err := net.ListenUDP(network, &net.UDPAddr{Port: port})
		if err != nil {
			return 0, fmt.Errorf("bind %s port %d: %w", network, port, err)
		}

		port = conn.LocalAddr().(*net.UDPAddr).Port
		closeConnWithLog(conn)
	}
	return port, nil
}
```

`port == 0` 时第一轮拿到的端口会继续用于后两轮，所以三轮探的是同一个号。

### 3. `client/internal/connect_port_other.go`（新增）

非 Windows 保持上游的单次双栈探测，行为零变化：

```go
//go:build !windows

func probeUDPPort(port int) (int, error) {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{Port: port})
	...
}
```

### 4. `client/internal/connect_windows_test.go`（新增）

用 `syscall` 复刻占用方的做法（IPv6 socket + `SO_REUSEADDR`），断言 `freePort`
不会把这种端口还回去。**这条测试在修复前必失败**：

```text
freePort returned 63911, which cannot be bound on IPv6; the interface would never start
```

## 为什么不靠「把 WgPort 设成 0」

`WgPort: 0` 是上游支持的语义（随机端口，见
`client/internal/profilemanager/config_test.go` 的 `TestWireguardPortZeroExplicit`）：
`freePort(0)` 返回系统分配的端口，之后握手上报、`status`、NAT 映射都用这个实际端口。
它能明显降低撞车概率，但**不能根治**：系统分配只保证探测那一种 socket 可用，
实测仍可能选到「双栈可绑、IPv6 不可绑」的端口。所以两者都要：

- 代码修好探测（本补丁）；
- 部署上把 `WgPort` 设成 `0`，或设到动态端口范围（49152 起）之外的固定值。

代价说明：`WgPort: 0` 意味着每次重连端口可能变化，依赖固定端口放行的防火墙规则
和上游 `PortForwardManager` 的端口映射会失去意义（映射本身会按实际端口重建）。

## 验收

```bash
go test ./client/internal -run 'Test_freePort|TestFreePortSkipsPortHeldOnIPv6' -v
go test ./client/internal -count=1
go vet ./client/internal

# 非 Windows 仍要能构建（平台桩代码）
GOOS=linux  GOARCH=amd64 CGO_ENABLED=0 go build ./client/internal
GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build ./client/internal
```

真机（Windows）：

```powershell
# 1. 端口被系统服务占着时，日志里应出现换端口的提示
#    INFO using <N> as wireguard port: <WgPort> is in use
# 2. 适配器出现、gateway Status 不再是 Connecting
Get-NetAdapter -IncludeHidden | Where-Object InterfaceDescription -match WireGuard
curl.exe -s -X POST http://127.0.0.1:<gateway-port>/daemon.DaemonService/Status `
  -H 'Content-Type: application/json' -d '{}'
```

`netbird status` 显示 `Connected` 不算通过：必须真实解析一次 mesh 域名。

## 注意

- 探测与实际绑定之间仍有 TOCTOU 窗口：探测通过后端口被别人抢走，引擎照样失败。
  本补丁把「必然失败」变成「极小概率失败」，没有消除竞态。
- `probeNetworks` 要求 `udp6` 也能绑。宿主机完全没有 IPv6 时这会判定端口不可用，
  与引擎的真实行为一致 —— wireguard 的 IPv6 socket 同样绑不上。
