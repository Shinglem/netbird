# 0001 —— 由 profile 驱动的实例身份

提交：`feat(windows): drive wintun GUID and NRPT key prefix from the profile config`

四个文件，+142/−16 行。把两处 Windows 的进程级设置改成 per-profile。

## 问题

在同一台 Windows 主机上跑两个 NetBird 实例会失败，原因有两个且互相独立 ——
都是上游把状态定在**进程**级，而第二个实例是第二个进程：

1. **wintun 适配器 GUID。** `client/iface/device/device_windows.go` 请求一个硬编码的
   GUID（`{f2f29e61-...}`）。两个进程要同一个 GUID 就会争同一个适配器，
   于是第二个接口建不起来。

2. **NRPT 键命名空间。** 这个客户端写下的每条规则都以 `NRPTKeyPrefix + ...` 命名，
   清理时也按该前缀枚举策略存储。共用一个前缀时，第二个实例退出会删掉第一个实例的
   DNS 规则，第一个就在会话中途失去域名解析。

## 改动

### `client/iface/device/windows_guid.go`

上游其实已经声明了扩展点：

```go
// CustomWindowsGUIDString is a custom GUID string for the interface
var CustomWindowsGUIDString string
```

但**生产代码里从没给它赋过值** —— 只有测试用。所以这里没有另造机制，只是加了个 setter：

```go
func SetWintunGUID(guid string) {
	if guid == "" {
		return
	}
	CustomWindowsGUIDString = guid
}
```

另有 `init()` 读 `NB_WINDOWS_GUID` 作为环境变量兜底。

**这个文件刻意不带 build tag。** 它写的那个变量只被 `device_windows.go` 读，
但一个带 build tag 的 setter 会迫使每个调用方都带上对应的 tag。
在所有平台都定义它，才能让 `profilemanager` 里的调用点保持平台无关。

### `client/internal/dns/host_windows.go`

前缀及由它派生的一切，从 `const` 变成 `var`：

```go
const defaultNRPTKeyPrefix = "NetBird-"   // 上游的原值，仍是默认值

var (
	NRPTKeyPrefix    = defaultNRPTKeyPrefix
	nrptMatchKeyName = NRPTKeyPrefix + "Match"

	dnsPolicyConfigMatchPath    = DNSPolicyConfigRoot + `\` + nrptMatchKeyName
	gpoDnsPolicyConfigMatchPath = GPODNSPolicyConfigRoot + `\` + nrptMatchKeyName
	dnsPolicyConfigExemptLocalPath    = DNSPolicyConfigRoot + `\` + NRPTKeyPrefix + `ExemptLocal`
	gpoDnsPolicyConfigExemptLocalPath = GPODNSPolicyConfigRoot + `\` + NRPTKeyPrefix + `ExemptLocal`
)
```

再加上 `SetNRPTKeyPrefix` / `setNRPTKeyPrefix`（它会重算全部五个派生值），
以及读 `NB_NRPT_KEY_PREFIX` 的 `init()`。

`NRPTKeyPrefix` 保留了导出名，因为 `debug_windows.go` 会读它（是运行时取值，
不在 const 表达式里），所以改成 `var` 不会破坏编译。

⚠️ **`const` 改 `var` 是本补丁最危险的部分。** 没有任何东西保证那些派生路径只在
`init()` 跑完之后才被读。若将来有改动让别的包的 `init()` 期间读它们，
读到的值就取决于包的初始化顺序。

### `client/internal/dns/nrpt_prefix_other.go`（新增）

```go
//go:build !windows
func SetNRPTKeyPrefix(string) {}
```

NRPT 只在 Windows 上有，其他平台没有东西需要命名空间隔离。这个桩存在的唯一目的，
是让 profile 配置加载器能调用 setter 而不必带 build tag。

### `client/internal/profilemanager/config.go`

`Config` 增加两个字段：

```go
// WintunGUID ... 仅 Windows，其他平台忽略。留空则保持内置默认值……
WintunGUID string `json:",omitempty"`

// NRPTKeyPrefix ... 留空则保持上游的 "NetBird-" 命名空间……
NRPTKeyPrefix string `json:",omitempty"`
```

并由 `readConfig` 发布它们：

```go
func (c *Config) publishInstanceIdentity() {
	device.SetWintunGUID(c.WintunGUID)
	dns.SetNRPTKeyPrefix(c.NRPTKeyPrefix)
}
```

**调用点的位置才是关键决策。** 它放在 `readConfig`、**不是** `apply()` ——
`apply()` 也会对一次性的探针配置和基线配置执行，而从一个只被「看一眼」的配置里
写进程级状态，会把某个 profile 的身份泄漏进正在运行的那个。

## 优先级

```
profile 配置字段  >  NB_WINDOWS_GUID / NB_NRPT_KEY_PREFIX  >  内置默认值
```

配置优先，因为配置会覆盖环境变量（环境变量在 `init()` 里读，配置在其后）。
每一层的空值都被忽略，所以配置字段的零值恰好等于上游行为。

⚠️ **「配置优先」不等于「有配置就够了」。** 见
[`0002-nrpt-prefix-timing.md`](0002-nrpt-prefix-timing.md)：跑在 `readConfig` 之前的路径
永远看不到配置值，对那些路径而言环境变量是必需的。

## 这套补丁没有解决什么

本补丁的任何部分都不会让 NetBird 变成便携程序。Windows 服务注册、计划任务、wintun
内核驱动、NRPT 注册表项和 wintun PnP 设备，全都仍是系统状态。补丁做到的是：
一个 *profile* 足以完整描述一个实例，因此多个实例可以并存。
