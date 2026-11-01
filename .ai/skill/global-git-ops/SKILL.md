---
name: global-git-ops
description: "在本仓库（fork：Shinglem/netbird，上游 netbirdio/netbird）与 GitHub 同步时使用：代理探活、凭据自查、origin/upstream 两条远端与提交纪律。"
---

# git 操作与同步（本仓库 ↔ GitHub）

> **中文名**：git 同步 ｜ 目录与 `name`：`global-git-ops`
> 来源：从同类项目的同名技能改写（那份写死了另一个仓库），流程通用、事实按本仓库重写。

本仓库是 `netbirdio/netbird` 的**公开 fork**（`Shinglem/netbird`），
默认分支 **`main`**。两条远端分工固定：

| 远端 | 仓库 | 用途 |
|---|---|---|
| `origin` | `https://github.com/Shinglem/netbird.git` | 自己的补丁集与文档，**推这里** |
| `upstream` | `https://github.com/netbirdio/netbird.git` | 只读，用来 `rebase` 跟上新版本 |

**不推送上游**（见根 `AGENTS.md` 的「本 fork 的改动」节）。技能只留流程与命令，
**不写账号 / token 值，也不登记任何个人凭据的位置**。

## When to Use

- 用户说「拉取」「同步一下」「push 上去」「跟上上游」
- `git fetch` / `pull` / `push` **卡住、超时、504 或 TLS 报错**（多半是没走代理）
- 提交（`commit` / `push`）前做**明文凭据扫描**自查
- `git` 报 `detected dubious ownership`（本机跨盘仓库常见，见 §5）

**不适用**：Gradle 拉依赖的代理（同一台代理，配置在 `desktop-ui/gradle.properties`）。

## §1 首要规则：GitHub 访问按需走代理

本机 Windows，**直连 `github.com:443` 不通**，必须显式走本机代理。代理地址与端口见
`.local/environment.md`（下文写作 `<proxy-host>:<proxy-port>`）；代理客户端开关会变，用前先探活。

- ⚠️ **用前探活**：端口 + HTTPS 一次实请求双确认。
- ⚠️ **不要把代理写进全局配置**（`git config --global http.proxy …`）：代理关掉后**所有** git 操作都会失败。
  需要时按「一次性」传参。

```powershell
Test-NetConnection <proxy-host> -Port <proxy-port> -InformationLevel Quiet   # True = 端口在听

# 200/401 都算通道通（私有仓库未带凭据即 401）；000 / 超时 = 代理没开
curl.exe -sS -m 8 -o NUL -w "%{http_code}`n" -x http://<proxy-host>:<proxy-port> `
  https://github.com/Shinglem/netbird.git/info/refs?service=git-upload-pack

git config --get http.proxy     # 返回空 = 默认直连，正常
```

一次性带代理（推荐）：

```powershell
git -c http.proxy=http://<proxy-host>:<proxy-port> fetch origin --prune
git -c http.proxy=http://<proxy-host>:<proxy-port> pull --rebase origin main
git -c http.proxy=http://<proxy-host>:<proxy-port> push origin main
```

> `Invoke-WebRequest` 不读环境变量代理且吞吐极差；验通道一律用 `curl.exe` + `-x` 显式指定。

## §2 凭据 —— 只引用位置，绝不打印值

- 远端是 HTTPS，推送要 GitHub 凭据；**凭据值永远不进对话 / 脚本 / 文档 / 仓库 / 日志**。
- 本机已配凭据助手（`git config --get credential.helper` 非空），命令里不出现 token。
- `403` / `Authentication failed` = 未带凭据，或该凭据无 push 权限。

```powershell
git config --get credential.helper
git -c http.proxy=http://<proxy-host>:<proxy-port> push --dry-run origin main   # 先 dry-run 验权限
```

## §3 同步流程

**自己的仓库（origin）**：`pull --rebase` 保持线性；rebase 冲突就 `git rebase --abort`，
恢复现场并报告，不自行解冲突、不 force push。

1. `git status -sb`、`git log --oneline -3`（工作区脏先提交或 `git stash`）。
2. 探活 → 带代理 `pull --rebase origin main`。
3. rebase 冲突 → `git rebase --abort` → 报告冲突文件 → **停下等指示**。
4. 收尾核对：`git status -sb` + `git log --oneline -3`。

**跟上上游（upstream）**：这是一次**独立操作**，不与功能提交混做。

```powershell
git -c http.proxy=http://<proxy-host>:<proxy-port> fetch upstream --prune
git rebase upstream/main          # 预期冲突点见 patchdoc/README.md 的「跟上游同步时的注意点」
```

冲突落点（已知）：`client/internal/profilemanager/config.go` 与 `client/internal/dns/host_windows.go`。
`AGENTS.md` 末尾的「本 fork 的改动」节与 `patchdoc/` 也在 rebase 范围，冲突时**保留本 fork 的节**。

## §4 提交纪律

- **入库前扫描明文凭据**：按内容特征匹配（`KEY=` / `--key` / `*_TOKEN` / `PASSWORD` / 长随机串），
  出现真值先改占位符或改从环境变量读取，再 `git add`。
- **提交信息沿用上游 Conventional Commits**：`type(scope): summary`，英文标题（与仓库历史一致，
  便于 rebase 后仍可读）；**正文与补丁文档用中文**。类型取 `feat` / `fix` / `docs` / `refactor` /
  `chore` / `test`。
- **一次提交一件事**；不要混合「重构 + 功能」。
- **不提交**：`build/`、`.gradle/`、`*.log`、`local.properties`、任何 token
  （`desktop-ui/.gitignore` 与根 `.gitignore` 已覆盖）。
- **新写文件一律 LF**；例外见根 `.gitattributes`。
- **禁止**：force push（含 `--force-with-lease` 推 `main`）、绕过钩子、`git checkout --` 丢弃他人改动、
  制造空提交。
- 同步技能后，提交信息里写清楚同步了哪个技能的哪个版本。

## §5 坑位

| 现象 | 多半是 | 处理 |
|---|---|---|
| `fatal: detected dubious ownership in repository at '<repo>'` | 仓库所在盘的属主 SID 与当前账户不一致（本机实测） | 一次性用 `git -c safe.directory=<repo> <命令>`；或 `git config --global --add safe.directory <repo>` |
| `fetch` / `pull` 长时间无输出、最后超时或 `Empty reply from server` | 直连 GitHub，没走代理 | 按 §1 探活后加 `-c http.proxy=…` |
| 刚才还能拉，突然全挂 | 代理客户端被关了 | 重跑 §1 探活 |
| **所有** git 操作都连不上代理 | 曾把代理写死进全局配置 | `git config --global --unset http.proxy`（以及 `https.proxy`） |
| `Authentication failed` / `403` | 未配凭据助手或凭据无权限 | 按 §2 确认 `credential.helper` + `push --dry-run` |
| `pull --rebase` 报冲突 | 本地与远端真分叉 | `git rebase --abort` → 报告 → 等指示 |
| 提交里出现整文件重写、diff 巨大 | 行尾 CRLF/LF 漂移 | 按 `.gitattributes` 重写该文件后再提交 |
| `.github/skills` 在别的机器上「技能消失」 | 符号链接被检出成普通文件（`core.symlinks=false`） | 见 [`../../../.github/copilot-instructions.md`](../../../.github/copilot-instructions.md) 的自检与修复 |

## §6 与其他文档的关系

- **规则与纪律**：根 [`AGENTS.md`](../../../AGENTS.md)（上游通用规则 + 「本 fork 的改动」节）；
  UI 层追加规则见 [`desktop-ui/AGENTS.md`](../../../desktop-ui/AGENTS.md)。
- **技能清单与来源**：[`.ai/skill/README.md`](../README.md)。
- 本技能只讲**本机 ↔ 远端**同步，不含 Gradle 依赖下载与本地构建。
