# 0002 —— NRPT 前缀必须导出，不能只配置

**严重度：高。** 只设配置字段就部署，会让第二个实例删掉**第一个**实例的 DNS 规则。
跑两个实例之前请先读这篇。

## 症状

启动第二个实例后，第一个实例 —— 它一直健康、`Connected` 也没掉 —— 失去了域名解析。
它的 NRPT 规则不见了：

```powershell
Get-DnsClientNrptRule | Where-Object Name -like 'NetBird-*'
# (空)
```

而第一个实例**自己的日志里什么都没有**：没有清理、没有路由拆解、没有 DNS 事件。
它不是自己删的。是第二个实例删的。

## 根因：一个启动顺序问题

来自 profile 配置的 `NRPTKeyPrefix` 由 `profilemanager.readConfig` 发布。对至少一条
跑得更早的代码路径来说，这太晚了。

当守护进程没能干净退出 —— 强杀、崩溃、断电 —— `state.json` 里仍留着 `dns_state` 条目。
下次启动时，状态管理器会在**读取 profile 配置之前**执行恢复：

```
daemon 启动
  |
  +-- statemanager 载入 state.json
  |     dns_state 存在（上次不干净退出留下的）
  |     -> dns.ShutdownState.Cleanup()
  |        -> registryConfigurator.restoreUncleanShutdownDNS()
  |           -> removeDNSMatchPolicies()
  |
  |     *** 这里会删掉所有以 NRPTKeyPrefix 开头的 NRPT 键 ***
  |     *** 而此刻 NRPTKeyPrefix 还是默认的 "NetBird-"      ***
  |     *** 于是它删掉了「另一个」实例的键                  ***
  |
  +-- readConfig
        -> publishInstanceIdentity()
           -> dns.SetNRPTKeyPrefix("NBAlt-ordia-")   <-- 太晚了
```

删除动作本身是正确的，作用域也对 —— `listNRPTRuleKeys` 是按前缀过滤的：

```go
if strings.HasPrefix(strings.ToLower(name), strings.ToLower(NRPTKeyPrefix)) {
	ruleKeys = append(ruleKeys, name)
}
```

缺陷只在于：**那一刻前缀还没被设过**。

## 修法：导出该变量

`host_windows.go` 在 `init()` 里读 `NB_NRPT_KEY_PREFIX`，而 `init()` 跑在任何可能调用
恢复路径的代码之前。所以配置字段（供运行期路径用）和环境变量（供早期路径用）**两个都要设**：

```powershell
$env:NB_STATE_DIR       = '<实例>\runfiles'
$env:NB_NRPT_KEY_PREFIX = 'NBAlt-<名>-'      # 必需，不是兜底
& '<root>\netbird-multi.exe' service run `
    --config '<实例>\config\default.json' `
    --daemon-addr 'npipe://netbird-<名>' `
    --log-file '<实例>\runfiles\daemon.log'
```

这就使得提交信息里最初的说法 —— 环境变量只是「still honoured as a fallback」——
具有误导性。它们不是兜底。对跑在 `readConfig` 之前的路径而言，它们是唯一可用的通道。

## 复现

```powershell
# 1. 两个实例都健康，两套键都在
Get-DnsClientNrptRule | Where-Object Name -like 'NetBird-*'    # 第一个
Get-DnsClientNrptRule | Where-Object Name -like 'NBAlt-*'      # 第二个

# 2. 强杀第二个实例，让它来不及清理，从而留下 dns_state
Stop-ScheduledTask -TaskName 'NetBird-Instance-<名>'
Get-CimInstance Win32_Process | Where-Object Name -eq 'netbird-multi.exe' |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# 3. 再启动它
Start-ScheduledTask -TaskName 'NetBird-Instance-<名>'
```

然后看第一个实例的键是否还在。

只设配置字段时，第二个实例的日志会显示恢复路径跑过，而第一个实例的键没了：

```
INFO client/internal/statemanager/manager.go:420: cleaning up state dns_state
```

导出了环境变量之后，同一行日志照样出现 —— 该路径依然会跑 —— 但键守住了，
因为删除已经被限制在 `NBAlt-<名>-` 作用域内。

## 为什么光有配置字段修不了

显而易见的那条替代方案 —— 让恢复路径晚一点再读前缀 —— 行不通。
恢复路径**必须**在 `readConfig` 之前跑，因为 `readConfig` 决定哪个 profile 是激活的，
而恢复要清理的是**上一次**运行留下的东西。把它往后挪，等于在 profile 已被选定并部分应用
之后才去恢复那个 profile 的状态。

可以推广的规则：**当进程级设置由 `readConfig` 发布时，先枚举所有跑在它之前的路径。**
包的 `init()`、状态管理器清理、不干净关闭的恢复，全都早于它；对这些路径而言，
只有进程启动时就存在的变量才够早。

## 回归测试

这条应当写进任何部署检查清单，因为**正常退出会完全掩盖这个 bug** ——
`down` 自己就把规则删干净了，不会留下任何 `dns_state`：

```powershell
# 确认两套键都在
# 强杀第二个实例
# 重启它
# 断言：日志中出现 "cleaning up state dns_state"
# 断言：Get-DnsClientNrptRule 仍然列出【两套】命名空间
# 断言：两个 mesh 名都能解析
```
