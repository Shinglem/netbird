# 本 fork 的补丁

本 fork（`Shinglem/netbird`）在上游 `netbirdio/netbird` 之上携带一套 opt-in 的小补丁集。
它存在的目的，是让**多个 NetBird 实例能在同一台 Windows 主机上同时运行**，
各有自己的 wintun 适配器、路由表项、DNS 命名空间和状态目录。

上游支持在 profile 之间**切换**，但不支持并存：单个进程独占一个 wintun 适配器 GUID
和一个 NRPT 键命名空间，两者都在守护进程启动时固定。本 fork 把这两处进程级设置
改成了 per-profile。

> **本仓库是公开 fork，不向上游提 PR。** 文档用中文行文，**用占位符**描述部署
> （`<root>`、`<instance>`、`<guid>` 等）；绝对路径、实例名、GUID、域名、端口等本机事实
> 只写在被忽略的 `.local/environment.md` 里，不入库。

## 文档

| 文档 | 内容 |
|---|---|
| [`0001-profile-driven-instance-identity.md`](0001-profile-driven-instance-identity.md) | 三处代码改动：改了什么、改在哪、每处位置为何这样选 |
| [`0002-nrpt-prefix-timing.md`](0002-nrpt-prefix-timing.md) | 启动时序缺陷的完整机制与复现步骤 |
| [`../AGENTS.md`](../AGENTS.md) | **规则在这里**：「本 fork 的改动」一节已把这套补丁的硬规则、部署形态、验收全部并入 |

**分工：`AGENTS.md` 是规则，`patchdoc/` 是变更记录与证据。** 两边冲突时以 `AGENTS.md` 为准，
并把这里改过来。

早先为绕开「`AGENTS.md` 受保护、工具拒绝写入」而单独存放的两份追加节（`agents-md-addendum.md`、
`agents-md-desktop-ui-addendum.md`）及其落地脚本 `apply-agents-md.ps1`、
`apply-desktop-ui-agents-md.ps1` **已删除**：那个前提实测不成立，正文也已并入 `AGENTS.md`。
需要旧版本时从 git 历史取。

## 对使用者而言有什么变化

没有 —— 除非某份配置主动启用。两个新字段都是 `omitempty`，空值下行为与上游逐字节一致，
所以已有的 `default.json` 不受影响，同一份二进制仍可作为普通客户端使用。

要用它，在 profile 里加上两个字段：

```json
{
  "WgIface": "wt-<instance>",
  "WintunGUID": "<guid>",
  "NRPTKeyPrefix": "NBAlt-<instance>-"
}
```

（本机实际取值见 `.local/environment.md`。）

设置之后，**一份配置文件就是一个完整的实例描述**，于是：

```powershell
netbird-multi.exe service run --config <实例>\config\default.json `
    --daemon-addr npipe://netbird-<名> --log-file <实例>\runfiles\daemon.log
```

就能启动该实例。

## 必需项：还要导出 `NB_NRPT_KEY_PREFIX`

只在配置里设 `NRPTKeyPrefix` **不够**。该值由 `readConfig` 发布，而至少有一条代码路径
在读取配置**之前**就会删除 NRPT 规则 —— 那一刻前缀还是内置的 `NetBird-`，
而那次清理会删掉另一个实例的规则。

```powershell
$env:NB_STATE_DIR       = '<实例>\runfiles'
$env:NB_NRPT_KEY_PREFIX = 'NBAlt-<名>-'      # 必需，不是兜底
```

完整解释与复现步骤：[`0002-nrpt-prefix-timing.md`](0002-nrpt-prefix-timing.md)。

## 验收

```bash
# 构建客户端模块（与上游 goreleaser 配置一致）
cd client && CGO_ENABLED=0 go build .
```

```bash
go fmt ./...
go vet ./client/internal/profilemanager/... ./client/internal/dns/... ./client/iface/device/...
# 交叉编译，确认非 Windows 的桩代码让其他平台仍能构建
GOOS=linux   GOARCH=amd64 CGO_ENABLED=0 go build ./client/...
GOOS=darwin  GOARCH=arm64 CGO_ENABLED=0 go build ./client/...
```

在 Windows 上，用不同前缀启动两个实例后，验收检查是：

```powershell
# 两个适配器、两套 NRPT 命名空间、两张 mesh 都能解析
Get-NetAdapter -IncludeHidden | Where-Object InterfaceDescription -match WireGuard
Get-DnsClientNrptRule | Where-Object { $_.Name -like 'NetBird-*' -or $_.Name -like 'NBAlt-*' }
Resolve-DnsName <主 mesh 名>; Resolve-DnsName <第二个 mesh 名>
```

以及最重要的那条回归 —— 强杀第二个实例、重启它，确认第一个实例的规则存活：

```powershell
# 重启后日志会显示恢复路径跑过……
#   INFO statemanager: cleaning up state dns_state
# ……而这里必须仍然列出两套键：
Get-DnsClientNrptRule | Where-Object Name -like 'NetBird-*'
```

## 跟上游同步时的注意点

`git remote add upstream https://github.com/netbirdio/netbird.git`、
`git fetch upstream`，然后 `git rebase upstream/main`。改动是 4 个文件、约 140 行，
冲突可能性低；若真发生冲突，会落在这两处：

- `client/internal/profilemanager/config.go` —— `Config` 的字段区与 import 区
- `client/internal/dns/host_windows.go` —— 前缀及其派生路径由 `const` 改 `var` 的那段

（本 fork 不推送上游；rebase 只是为了跟上新版本。）
