# 项目规则 —— 见 `AGENTS.md`

> ⚠️ **本文件不含规则内容，只作指针。** 规则分布在两处：
>
> 1. **仓库根 [`AGENTS.md`](../AGENTS.md)** —— 全仓规则，**中文**（上游规则的中文改写）
>    ＋「本 fork 的改动」节（多实例补丁集、部署形态、验收、公开仓库不留本机信息）。
> 2. **[`desktop-ui/AGENTS.md`](../desktop-ui/AGENTS.md)** —— **桌面 UI（Kotlin / Compose Multiplatform）的规则权威源**：
>    定位与非目标、技术栈、与 daemon 的契约、硬约束、验收、编码规范。
>
> 在 `desktop-ui/` 下工作时，**两份都要读**；冲突时以 `desktop-ui/AGENTS.md` 为准（仅限该目录）。

**请先完整阅读 [`AGENTS.md`](../AGENTS.md) 并严格遵守。**

## 一眼要记住的几条

- **语言** —— 对话与思考过程用中文；代码标识符、命令、路径、配置键名、报错原文保持英文原样。
- **Go 树只读** —— `client/**/*.go`、`client/proto/**` 不改；确需改走 `patchdoc/` 并先给用户方案。
- **UI 只走 HTTP/JSON 网关** —— `POST http://127.0.0.1:<port>/daemon.DaemonService/<Method>`；
  不要试图连命名管道（Kotlin/JVM 没有可用传输）。
- **`Up` 必须 `async: true`** —— 否则 RPC 阻塞到引擎就绪，界面看起来像卡死。
- **流式按行读** —— `SubscribeStatus` / `SubscribeEvents` 是持续输出，首帧即快照。
- **多实例** —— 实例来自 `<root>\profiles\*\instance.json`，端口 8471+n，不扫端口、不假设单实例。
- **不做** —— 移动端、多语言、WebView、像素复刻、改 daemon 行为。

细则见 [`desktop-ui/AGENTS.md`](../desktop-ui/AGENTS.md)。

## 技能接入（Copilot）

- 技能真身：`.ai/skill/<技能名>/SKILL.md`（目录名 = frontmatter `name` = 技能 ID，**必须一级**）。
- 清单与来源：[`.ai/skill/README.md`](../.ai/skill/README.md)。
- Copilot 读的是 [`.github/skills`](./skills) —— 一个指向 `../.ai/skill` 的**符号链接**，
  整目录共享，新增技能**无需**改链接。
- 自检（`LinkType` 必须是 `SymbolicLink`，否则技能静默失效）：

  ```powershell
  Get-Item .github\skills | Select-Object Name,LinkType,Target
  ```

- 修复（Windows 需开发者模式或管理员）：

  ```powershell
  Remove-Item .github\skills -Recurse -Force
  New-Item -ItemType SymbolicLink -Path .github\skills -Target '../.ai/skill'
  ```

## 先读什么（任务路由）

| 任务 | 先读 |
|---|---|
| 给 UI 接 / 改 daemon 通道 | 技能 `netbird-desktop-ui-contract` + 其 `references/daemon-api.md` |
| 写 Compose 界面、踩渲染/IME/窗口的坑 | 技能 `compose-multiplatform-desktop` |
| 驱动真实窗口做端到端验证 | 技能 `dev-desktop-gui-e2e-testing` |
| 多实例部署 / 排查两个实例抢 DNS | 技能 `netbird-multi-instance` |
| 拉取 / 推送 / 跟上上游 | 技能 `global-git-ops` |
| 改 Go 侧或 proto | **先停** —— `patchdoc/README.md`，写方案给用户 |
