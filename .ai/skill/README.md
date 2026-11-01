# AI 技能

给在本仓库工作、或部署本仓库构建产物的 AI agent 用的、可按需加载的复用知识。

这些是 **`SKILL.md` 意义上的技能**：触发条件、工作流，以及那些真正花掉调试时间的坑 ——
不是参考资料。设计成任务匹配时加载、不匹配时跳过。

**本文件是技能清单的唯一处**；新增 / 删除技能都要在这里登记。

| 技能 | 何时加载 | 来源 |
|---|---|---|
| [`netbird-desktop-ui-contract/`](netbird-desktop-ui-contract/SKILL.md) | 给 `desktop-ui/` 接 daemon：通道选型、RPC 分级、多实例编排、故障对照 | **本项目自建** |
| [`netbird-multi-instance/`](netbird-multi-instance/SKILL.md) | 在同一台主机上运行或排查多个 NetBird 实例 | **本项目自建** |
| [`compose-multiplatform-desktop/`](compose-multiplatform-desktop/SKILL.md) | 写任何 Compose Multiplatform 桌面代码前 —— 布局、渲染、IME、窗口、拖放的坑 | 自外部技能库同步 |
| [`dev-desktop-gui-e2e-testing/`](dev-desktop-gui-e2e-testing/SKILL.md) | 用脚本驱动真实窗口做端到端验证前 —— 输入法吞按键、抢前台、截图断言 | 自同类项目同步 |
| [`global-git-ops/`](global-git-ops/SKILL.md) | 拉取 / 推送 / 跟上上游、代理探活、提交自查 | 自同类项目改写（仓库事实按本仓库重写） |

这些技能遵循两条约定，都是刻意为之：

- **`SKILL.md` 只装工作流与坑。** 篇幅长、面窄、或只是偶尔相关的内容放进 `references/`
  按需加载，让常见路径保持简短。
- **每个非显然的结论都要点名证据** —— 一个源文件、一行日志、一个实测数字。只断言
  「这样做」的技能，在代码变动之后无从校验。

## 为什么只有这五个

`compose-multiplatform-desktop` 的坑表里，终端 / Swing 互操作 / 富文本渲染几节本项目用不到
（UI 没有终端、不做 Markdown），但**保留原样**：那份技能从技能库同步，改它会让下次同步对不上；
不匹配的场景 description 不会命中，不占上下文。

Kotlin 官方的 `kotlin-tooling-*` 系列**没有引入**：适用面是 Kotlin Toolchain（Amper）工程与迁移场景，
而 `desktop-ui/` 是普通 Gradle 工程 —— 引进来只会误导。Gradle / JDK 的实际配置写在
[`../../desktop-ui/AGENTS.md`](../../desktop-ui/AGENTS.md) §5。

## 同步方式

技能来自另一个 CMP 项目的技能库（路径见 `.local/environment.md`）：

```bash
cp -r <sibling-project>/.ai/skill/dev-desktop-gui-e2e-testing .ai/skill/
cp -r <sibling-project>/.ai/skill/compose-multiplatform-desktop .ai/skill/
```

> ⚠️ 技能库（`<skill-library>`）里的 `compose-multiplatform-desktop` **缺 `references/`**
> （SKILL.md 结尾引用了它）。只从技能库同步会得到一份带死链的副本。

同步后**必须检查并修正写死旧仓库的引用**：上一次同步 `dev-desktop-gui-e2e-testing` 时有 4 处指向
旧项目 `.design/` 的失效链接、`global-git-ops` 写死了另一个仓库的远端地址 —— 死链比没有技能更糟
（agent 会停在「文件读不到」上）。检查：

```bash
grep -rnE '\.design/|[A-Za-z]:\\\\|/[A-Za-z]/[A-Za-z]+/' .ai/skill/
```

`global-git-ops` 是**改写版**：流程与坑通用，但远端、提交风格、`safe.directory` 坑都按本仓库重写，
不要从上游整份覆盖。

## 与规则文档的分工

| 位置 | 性质 |
|---|---|
| [`../../AGENTS.md`](../../AGENTS.md)（仓库根） | 上游通用规则 + 「本 fork 的改动」节（受保护文件，用 `patchdoc/` 的脚本落地） |
| [`../../desktop-ui/AGENTS.md`](../../desktop-ui/AGENTS.md) | **UI 层规则权威源**：定位、技术栈、daemon 契约、硬约束、验收 |
| `.ai/skill/` | 可复用的方法论与踩坑（怎么做），本文件登记 |
| [`../../patchdoc/`](../../patchdoc/README.md) | 本 fork 改了 Go 侧的什么、为什么、怎么验收 |

本仓库其他相关材料：

- [`../../patchdoc/`](../../patchdoc/README.md) —— 本 fork 改了什么、为什么
- [`../../AGENTS.md`](../../AGENTS.md) —— 仓库级 agent 指引
- [`../../desktop-ui/AGENTS.md`](../../desktop-ui/AGENTS.md) —— 桌面 UI 规则
