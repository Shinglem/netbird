# Windows 上的集成点

源码级细节：两实例本会互相碰撞的三处位置，外加一处只是**看起来**像碰撞的。
行号来自本 fork 所基于的代码树，会随时间漂移。

## 1. wintun 适配器 GUID

`client/iface/device/device_windows.go`：

```go
const defaultWindowsGUIDSTring = "{f2f29e61-d91f-4d76-8151-119b20c4bdeb}"

func getGUID() (windows.GUID, error) {
	guidString := defaultWindowsGUIDSTring
	if CustomWindowsGUIDString != "" {
		guidString = CustomWindowsGUIDString
	}
	return windows.GUIDFromString(guidString)
}
```

`CustomWindowsGUIDString` 声明在 `windows_guid.go`：

```go
// CustomWindowsGUIDString is a custom GUID string for the interface
var CustomWindowsGUIDString string
```

上游除测试外从不给它赋值，于是每个进程都去抢同一个适配器。本 fork 增加了
`SetWintunGUID`，由 profile 配置调用。

该文件不带 build tag，这点很重要：这个变量只被 `device_windows.go` **读**，但一个带
build tag 的 setter 会把对应的 tag 强加给所有调用方。在所有平台都定义 setter，
代价是 Linux 和 macOS 上一个空函数，换来的是 `profilemanager` 无需关心平台。

## 2. NRPT 键命名空间

`client/internal/dns/host_windows.go`。这个客户端写下的每条规则都由同一个前缀命名，
而清理逻辑按该前缀枚举全机的策略存储：

```go
func listNRPTRuleKeys(root string) ([]string, error) {
	...
	for _, name := range names {
		// registry key names are case insensitive
		if strings.HasPrefix(strings.ToLower(name), strings.ToLower(NRPTKeyPrefix)) {
			ruleKeys = append(ruleKeys, name)
		}
	}
	return ruleKeys, nil
}
```

`removeDNSMatchPolicies` 随后删除该函数返回的全部内容。这个过滤是正确且精确的 ——
正因如此，前缀必须在**第一次清理之前**就是对的。有两个调用方会走到它：

- `applyDNSConfig` —— 装新规则前清掉上一次的
- `restoreUncleanShutdownDNS` —— 恢复路径，跑在**读取 profile 配置之前**
  （见 `../../patchdoc/0002-nrpt-prefix-timing.md`）

有五个值由该前缀派生，前缀变化时必须一起重算：`NRPTKeyPrefix`、`nrptMatchKeyName`，
以及本地与 GPO 两个策略存储中的 match 路径与 ExemptLocal 路径。

## 3. `NB_STATE_DIR` —— 一个变量，三份职责

`client/internal/profilemanager/service.go`：

```go
if stateDir := os.Getenv("NB_STATE_DIR"); stateDir != "" {
	DefaultConfigPathDir = stateDir
	...
}
ActiveProfileStatePath = filepath.Join(DefaultConfigPathDir, "active_profile.json")
```

所以 `NB_STATE_DIR` 同时决定 **profile 存储**、`active_profile.json` **和** `state.json`。
这很方便 —— 一个变量隔离全部持久化 —— 但也意味着第二个实例若指向同一个值，
共享的就不只是状态，还有 profile 存储。给每个实例各自的目录。

`NB_STATE_DIR` 不可能是配置字段：它决定**读哪个配置**，所以必须在配置存在之前就被知晓。
任何在 `readConfig` 之前被消费的设置，同理。

## 4. 看似碰撞、其实不是：守护进程地址

两个守护进程若都在 `127.0.0.1:<port>` 上确实会冲突，但 Windows 守护进程不使用 TCP。
它提供的是一个命名管道：

```
\\.\pipe\ProtectedPrefix\Administrators\netbird-<name>
```

管道名由 `--daemon-addr npipe://netbird-<name>` 派生，所以不同的名字天然隔离，无需分配端口。
不要为了「修好」管道方案而改用回环 TCP —— 管道携带特权操作所依据的调用方令牌，
而回环 TCP 不携带任何调用方身份。

## 5. 值得知道的一处相互作用：实例适配器的出现与消失

主实例的路由管理器对接口变动有反应：拆掉并重新初始化路由与 DNS。这是上游行为，
也是为什么第二个实例启停时主实例的 NRPT 规则会短暂消失。它会自行恢复；实测窗口为
13 秒、16 秒、18 秒。

唯一一个从应用视角看**不能**干净恢复的情况，是那条路径开头的清理用错了前缀作用域 ——
那是 `../../patchdoc/0002-nrpt-prefix-timing.md` 里的时序缺陷，不是路由问题。

不要试图靠「让适配器常驻」来避免这种搅动。前缀对了之后，重建是无害的。
