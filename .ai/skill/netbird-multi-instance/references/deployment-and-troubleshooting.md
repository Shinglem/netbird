# 部署与排障

## 部署第二个实例

```powershell
$root = 'C:\app\multinetbird'
$name = 'ordia'

# 1. 目录布局
New-Item -ItemType Directory -Force -Path `
    "$root\profiles\$name\config", "$root\profiles\$name\runfiles" | Out-Null

# 2. 二进制与 wintun.dll 放一起（dll 必须与 exe 同目录）
Copy-Item <built>\netbird.exe "$root\netbird-multi.exe"
Copy-Item <official-install>\wintun.dll "$root\wintun.dll"

# 3. 配置：克隆一个已有 profile，再改身份字段。
#    改文本，绝不要经 ConvertFrom-Json 往返 —— ManagementURL 是 url.URL，
#    其结构形式无法往返，且 PrivateKey 必须保持逐字节一致。
#      WgIface        -> wt-<name>
#      WgPort         -> 其他实例未占用的端口
#      WintunGUID     -> 一个新的 GUID，本机唯一
#      NRPTKeyPrefix  -> NBAlt-<name>-
#      DisableDNS     -> false      （让该实例自己配 DNS）
#    用 UTF8Encoding($false) 写：无 BOM。
```

然后创建启动脚本和计划任务（SYSTEM、`RunLevel Highest`、无触发器，即只按需启动 ——
笔记本不需要一条自己不在用的隧道）。启动脚本设置技能正文里那两个环境变量。

## 症状 → 病因

| 症状 | 最可能的原因 |
|---|---|
| `Error loading wintun.dll` | dll 与 exe 不在同一目录 |
| `create wintun interface: GUID already exists` / 第二个适配器始终不出现 | 两个实例的 `WintunGUID` 相同 |
| 第二个实例连上后，第一个实例不再解析域名 | 没导出 `NB_NRPT_KEY_PREFIX` —— 见 `../../patchdoc/0002-nrpt-prefix-timing.md` |
| `create tun device: ... cannot find the file specified` | 强杀把 wintun PnP 设备留在了坏状态；删掉该实例的设备后重启 |
| `invalid character '\ufeff' looking for beginning of value` | 配置 JSON 被写成了带 BOM |
| 实例每次启停，主实例 DNS 掉约 15 秒 | 接口变动引发的上游重建，属预期；先轮询再下结论 |
| 实例运行期间主实例每一两分钟重建一次 | 实例目录树在 `%PROGRAMDATA%\Netbird` 下；挪出去 |
| 实例连上了，但系统解析不了 mesh 名 | NRPT 前缀不匹配，或从别的模式残留的 `DisableDNS: true` |
| `Interface type: Userspace`，但适配器在、路由也通 | 仅显示层面：管理服务器下发的标志，不代表本机能力 |

## 日志取证

有两份日志值得看，它们回答不同的问题。

**实例自己的日志**（`runfiles\daemon.log`）说明该实例做了什么：

```bash
grep -E "cleaning up state dns_state|added [0-9]+ NRPT|Routing (cleanup|setup) complete" daemon.log
```

`cleaning up state dns_state` 意味着不干净关闭的恢复路径跑了 —— 那正是按前缀删除的路径。
如果另一个实例在同一时刻丢了规则，它就是元凶。

**主实例的日志**（`%PROGRAMDATA%\Netbird\client.log`）说明主实例有没有被打扰。
先记录行数基线，操作，再比对：

```bash
BEFORE=$(wc -l < /c/ProgramData/Netbird/client.log)
# ... 启停一个实例 ...
sed -n "$((BEFORE+1)),\$p" /c/ProgramData/Netbird/client.log |
    grep -E "cleanupConnection|service is down|Routing (cleanup|setup) complete|active profile config existed"
```

出现任何一条就说明主实例重建了。标志行是
`client/server/server.go: active profile config existed: true`，位于
`cleanupConnection()` 内 —— 只有 `Server.Down()` 或 profile 登出能到达这里。
如果你没发起过这两者，就去查是什么让守护进程走上了那条路径。

**时间戳就是全部论据。** 结论若形如「主实例把 DNS 弄坏了」，只有在主实例日志于那一刻
有事件时才成立；「第二个实例把 DNS 弄坏了」同理，只有第二个实例的日志有事件时才成立。
动手前先比对两份日志。

## 区分两种失效模式

从外面看两者一模一样 —— 名字不解析了，`netbird status` 仍显示 `Connected` ——
但应对方式不同：

| | 重建进行中 | 前缀删掉了规则 |
|---|---|---|
| 持续时间 | 13~60 秒内自愈 | 永久，直到重启 |
| 主实例日志 | 有清理/重建事件 | 静默 |
| 第二个实例日志 | 无相关内容 | `cleaning up state dns_state` |
| 修法 | 等 | 导出 `NB_NRPT_KEY_PREFIX`，然后重启该实例 |

把一次缺失的键当作故障之前，先轮询满一分钟。实例刚启动就查一次，
会把健康的部署报成坏的。

## 移除一个实例

```powershell
# 停掉它（要杀掉计划任务拉起的守护进程，而不只是任务自己的 pwsh）
Stop-ScheduledTask -TaskName 'NetBird-Instance-<name>'
Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'netbird*' -and $_.CommandLine -like '*npipe://netbird-<name>*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# 它自己的 DNS 规则随之而去；若曾被强杀，按前缀删掉
Get-DnsClientNrptRule | Where-Object Name -like 'NBAlt-<name>-*' |
    ForEach-Object { Remove-DnsClientNrptRule -Name $_.Name -Force }

# 然后是任务；实例目录可选保留
Unregister-ScheduledTask -TaskName 'NetBird-Instance-<name>' -Confirm:$false
```

匹配进程要**同时**看 `Name` 和 `CommandLine`。只按命令行过滤，会连正在执行清理的那个
shell 一起匹配到，于是它把自己也杀掉。

除非下次启动失败，否则别动 wintun PnP 设备。它可以复用，而且这个设备类是与无关软件共享的
（见 SKILL 的坑一节）。
