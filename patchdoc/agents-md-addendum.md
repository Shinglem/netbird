## 本 fork 的改动

<!--
  本节的正文由 patchdoc/apply-agents-md.ps1 追加到 AGENTS.md。
  之所以单独成文件，是因为 AGENTS.md 是受保护的 agent 指令文件，
  工具会拒绝直接编辑它。

  以下内容只描述本 fork。上方的上游指引完全适用；本节只是在其之上
  补上本地补丁集。

  注意：本文件里的相对链接是按「追加到仓库根 AGENTS.md 之后」的样子写的，
  即从仓库根解析，而不是从 patchdoc/ 解析。就地对本文件跑链接检查会误报失效。
-->

本仓库是 `netbirdio/netbird` 的 fork，携带一套上游没有的、opt-in 的小补丁集。
**在改动 `client/iface/device/`、`client/internal/dns/` 或
`client/internal/profilemanager/` 下任何内容之前，以及在构建或部署客户端二进制之前，
请先读本节。**

### 这套补丁做了什么

它把两处 Windows 设置从进程级改成 per-profile，从而让多个 NetBird 实例能在同一台
Windows 主机上同时运行，各有自己的 wintun 适配器和 NRPT 键命名空间：

- `Config.WintunGUID` —— 本 profile 的接口创建时使用的适配器 GUID
- `Config.NRPTKeyPrefix` —— 它的 NRPT 规则键所用的命名空间

两者都是 `omitempty`，空值与上游行为逐字节一致，所以已有配置不受影响，
同一份二进制仍可作为普通客户端使用。该改动的提交信息说环境变量是
「honoured as a fallback」；基于下面的原因，这个说法是错的。

### 由此产生的规则

1. **`NB_NRPT_KEY_PREFIX` 必须导出，不能只配置。** 配置值由 `readConfig` 发布，
   而它跑在不干净关闭的恢复路径**之后** —— 那条路径按前缀过滤并删除 NRPT 规则。
   只设配置字段时，它看到的是内置的 `NetBird-`，于是删掉**另一个**实例的规则。
   凡是设置了 `NB_STATE_DIR` 的启动脚本，都必须同时设置它。

2. **进程级设置一律从 `readConfig` 发布，绝不要从 `apply()`。** `apply()` 也会对
   一次性的探针配置和基线配置执行；从一份只被检查的配置里写进程级状态，
   会把某个 profile 的身份泄漏进正在运行的那个。新增此类设置时，先查清有哪些路径
   跑在 `readConfig` **之前**，再决定用哪个通道触达它们。

3. **保持非 Windows 平台可构建。** `SetWintunGUID` 和 `SetNRPTKeyPrefix` 在所有平台
   都有定义（后者是 `//go:build !windows` 的空实现），这样调用方不需要 build tag。
   验证时要用 `GOOS=linux` 和 `GOOS=darwin` 构建，不能只验 Windows。

4. **不要把 NRPT 前缀改回 `const`。** `NRPTKeyPrefix` 和由它派生的四条路径，
   必须在每次前缀变化时一起重算；把它们拆到一个 const 和一个 var 里，
   正是它们彼此漂移的原因。

5. **这些改动不会让客户端变得便携。** 服务注册、计划任务、wintun 内核驱动、
   NRPT 注册表项和 wintun PnP 设备依然是系统状态。不要把结果描述成
   「绿色安装」或「便携安装」。

### 未经讨论不要上游化

该改动触及客户端系统集成（DNS、接口），而
[CONTRIBUTING.md](CONTRIBUTING.md#high-risk-areas) 将其列为 high-risk area；
它还改变了配置文件的格式。按上方的 STOP 规则，必须先有 agreed ticket。
这套补丁是按「可上游化」写的 —— 它复用既有的 `CustomWindowsGUIDString` 扩展点，
而不是新增机制，且 opt-in、零默认行为变化 —— 但讨论在先。

### 细节在哪

| 路径 | 内容 |
|---|---|
| [`patchdoc/`](patchdoc/README.md) | 补丁改了哪些文件、怎么验收 |
| [`patchdoc/0002-nrpt-prefix-timing.md`](patchdoc/0002-nrpt-prefix-timing.md) | 启动时序缺陷，以及为何环境变量是必需的 |
| [`.ai/skill/`](.ai/skill/README.md) | 按任务加载的技能 —— 从 `netbird-multi-instance` 开始 |

### 构建与验收客户端二进制

```bash
cd client && CGO_ENABLED=0 go build .        # 与上游 goreleaser 配置一致

go fmt ./...
go vet ./client/iface/device/... ./client/internal/dns/... ./client/internal/profilemanager/...

# 空实现必须保证各平台都能构建
GOOS=linux  GOARCH=amd64 CGO_ENABLED=0 go build ./client/...
GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build ./client/...
```

部署重新构建的二进制之前，先确认实例身份相关的符号确实在产物里 ——
一个悄悄丢了它们的构建照样能跑，照样会用默认 GUID 和默认前缀：

```bash
grep -c WintunGUID <binary> && grep -c NRPTKeyPrefix <binary> && grep -c SetNRPTPrefix <binary>
```
