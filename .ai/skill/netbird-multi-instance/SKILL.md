---
name: netbird-multi-instance
description: 在单台 Windows 主机上同时运行或排查多个 NetBird 实例（各自接入独立的覆盖网络）。
version: 1.0.0
metadata:
  tags: [netbird, windows, multi-instance, wintun, nrpt, daemon]
  related: [patchdoc/0001-profile-driven-instance-identity.md, patchdoc/0002-nrpt-prefix-timing.md]
---

# 在单台 Windows 主机上运行多个 NetBird 实例

## 何时使用

- 需要从同一台 Windows 机器**同时**访问两个及以上的覆盖网络，且每个都要有可用的系统级域名解析。
- 已有的多实例部署在某实例重启后不再解析域名。
- 你在决定该用 profile 切换，还是让实例并存。

**不适用**的场景：只连一个网络、偶尔换账号。`netbird profile` 的切换功能已经能做这件事，
不需要本技能的任何内容。

## 决定一切的那个约束

上游 NetBird 在构造上就是单实例守护进程。有两处状态是进程级、且启动时即固定，
第二个实例一定会来争：

| 单例 | 位置 | 两个实例共用时的症状 |
|---|---|---|
| wintun 适配器 GUID | `client/iface/device/device_windows.go`，硬编码 `{f2f29e61-...}` | 第二个接口建不起来 |
| NRPT 键前缀 | `client/internal/dns/host_windows.go`，`NRPTKeyPrefix = "NetBird-"` | 一个实例退出会删掉另一个的 DNS 规则 |

其余状态都能用普通参数隔离：`--config`、`--daemon-addr`、`--log-file` 和 `NB_STATE_DIR`。
两处单例各自的机制、以及 `NB_STATE_DIR` 的细节（这一个变量还决定 profile 目录，不只是
`state.json`）见 [`references/windows-singletons.md`](references/windows-singletons.md)。

本 fork 把这两处单例改成了 per-profile；见
[`../../../patchdoc/0001-profile-driven-instance-identity.md`](../../../patchdoc/0001-profile-driven-instance-identity.md)。

## 可用的目录布局

```
<root>\
  |- netbird-multi.exe          一份二进制服务所有实例
  |- wintun.dll                 必须与 exe 同目录
  |- profiles\
       |- <name>\
            |- daemon.ps1        计划任务指向它
            |- instance.json     脚本元数据
            |- config\default.json      --config
            |- runfiles\{state.json, active_profile.json, daemon.log}
```

两条硬要求：

1. **`wintun.dll` 必须与 exe 同目录。** 放在别处会报 `Error loading wintun.dll`。
2. **实例目录树绝不能位于 `%PROGRAMDATA%\Netbird` 下。** 主实例的 MDM 策略重载 ticker
   监控该树；实例在树内写状态会被当成策略变更，主实例随即拆掉并重建自己的引擎 ——
   于是 DNS 与路由消失 13~60+ 秒，每一两分钟重复一次。

## 启动一个实例

```powershell
$env:NB_STATE_DIR       = '<root>\profiles\<name>\runfiles'
$env:NB_NRPT_KEY_PREFIX = 'NBAlt-<name>-'      # 必需 —— 见下
& '<root>\netbird-multi.exe' service run `
    --config '<root>\profiles\<name>\config\default.json' `
    --daemon-addr 'npipe://netbird-<name>' `
    --log-file '<root>\profiles\<name>\runfiles\daemon.log'
```

`config\default.json` 必须包含：

```json
{ "WgIface": "wt-<name>", "WintunGUID": "<唯一 guid>", "NRPTKeyPrefix": "NBAlt-<name>-", "DisableDNS": false }
```

另外，profile 里还要：每个实例一个不同的 `WgPort`，以及一个**与其他实例 DNS 域名不同**的
管理服务器 —— 两个网络用同一个域名会在客户端解析器里互相打架。

## ⚠️ 必须导出 `NB_NRPT_KEY_PREFIX`，不能只写在配置里

只写进 `default.json` **会删掉另一个实例的 NRPT 规则**。配置值由 `readConfig` 发布；
而不干净关闭的恢复路径跑在它之前，那时前缀还是内置的 `NetBird-`。

完整机制、复现步骤，以及为什么只靠配置字段无法修复：
[`../../../patchdoc/0002-nrpt-prefix-timing.md`](../../../patchdoc/0002-nrpt-prefix-timing.md)。
部署前请先读 —— 这个故障是静默的，且看起来像是**另一个**实例坏了。

## 验收

```powershell
# 两个适配器都在
Get-NetAdapter -IncludeHidden | Where-Object InterfaceDescription -match WireGuard

# 每个实例一套 NRPT 命名空间，各自指向隧道内的解析器（<mesh>/16 -> x.y.255.254）
Get-DnsClientNrptRule | Where-Object { $_.Name -like 'NetBird-*' -or $_.Name -like 'NBAlt-*' }

# 两个网络都能解析 —— 而且是系统层解析，不是 `netbird status`
Resolve-DnsName <主 mesh 名>
Resolve-DnsName <第二个 mesh 名>
```

`netbird status` 不足以作为证据：它报告的是守护进程的视角，会在系统完全解析不了的情况下
照样显示 `Connected`。

也不要看 `Nameservers: n/n` 那个计数器来判断 DNS —— 它数的是 netbird 托管的 nameserver，
即使在 Custom Zones 正常工作时也读作 `0/0`。唯一的证明是真实发起一次查询。

## 坑

- **实例启停后 NRPT 键短暂缺失，可能是一次重建正在进行，而不是故障。** 启停实例会搅动
  它的适配器，主实例会在 13~18 秒后重新注册自己的规则（实测）。先轮询满一分钟再下结论；
  查一次就重启服务，会把一小段能自愈的空窗变成更长的中断。
- **`netbird status` 显示 `Interface type: Userspace` 不等于内核接口丢了。** 它反映的是
  管理服务器下发的 `usesKernelInterface` 值。该去看适配器、路由和一次能用的查询。
- **绝不要因为「没有对应适配器」就删 wintun PnP 设备。** 未激活的设备本来就没有适配器，
  而这个设备类会列出所有使用 wintun 的程序 —— 在某台机器上它包含了 Tailscale 的适配器，
  其 GUID `{37217669-...}` 原样出现在 `tailscaled.exe` 里。判断归属要看 `FriendlyName`
  加在候选二进制里搜 GUID。删除用 `pnputil /remove-device "SWD\WINTUN\{...}"`；
  `Remove-PnpDevice` 并非每个 Windows 版本都有。
- **强杀会让适配器处于坏状态。** 下次启动报 `create tun device: Error creating interface:
  The system cannot find the file specified`。符合设计的修法：删掉该实例自己的 PnP 设备
  （先确认那个 GUID 是它的），然后重新启动。
- **配置 JSON 不能有 BOM。** 守护进程会报
  `invalid character '\ufeff' looking for beginning of value`。PowerShell 的
  `Set-Content -Encoding UTF8` 和 Python 的 `utf-8-sig` 都会加上；用 `UTF8Encoding($false)`
  写字节。
- **用 `--daemon-addr npipe://...`，不要用回环 TCP。** 命名管道携带 Windows 需要的调用方
  身份；回环 TCP 不携带，特权调用会被拒。

更深入的排障手法，包括如何判断是哪个实例删了规则：
[`references/deployment-and-troubleshooting.md`](references/deployment-and-troubleshooting.md)。
