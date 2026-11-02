---
name: dev-desktop-gui-e2e-testing
description: "用脚本驱动 Windows 桌面 GUI（键鼠注入 + 窗口截图）验证 Compose Desktop 应用时使用：中文输入法吞按键、Enter 不发送、空格丢失、Shift+Enter 不换行、抢不到前台、点击落到别的窗口等问题与替代手法。"
---

# 桌面 GUI 端到端测试：输入法与注入的坑

> **中文名**：桌面 GUI 端到端测试 ｜ 目录与 `name`：`dev-desktop-gui-e2e-testing`

**适用**：需要"启动应用 → 点界面 → 输入文字 → 截图断言"的自动验证（本项目优先 Compose Desktop 桌面端）。
**不适用**：纯逻辑单测、Compose UI 测试（`runComposeUiTest`，不需要真实窗口）。

本技能只写**可复用的判据与手法**；本仓库的 UI 验收口径见
[`desktop-ui/AGENTS.md`](../../../desktop-ui/AGENTS.md) §7。

---

## §1 头号坑：中文输入法会吞掉一切合成按键

**症状**（实测于微软拼音激活状态）：

| 你发的键 | 实际结果 |
|---|---|
| 字母 `o` `k` | 输入框里出现 `ok`，但应用的 `onPreviewKeyEvent` **一个 KeyEvent 都没收到** |
| 空格 | 丢失（`line one` → `lineone`） |
| `Enter` | 不发送、不换行，什么都不发生 |
| `Shift+Enter` | 不换行 |
| `/sk` | 不进入文本框，只在屏幕底部弹输入法候选框 |

**判据（30 秒定位）**：在应用的按键处理里临时打一行 DEBUG 日志
（例如 `Composer.kt` 的 `handleComposerKey`），然后跑脚本。
**日志里连字母 KeyEvent 都没有** → 按键被 IME 在 AWT/TSF 层消费，**不是应用 bug**；
日志里有 KeyEvent 但行为不对 → 才是应用逻辑问题。定位完记得把日志删掉。

**为什么文字还是"进"了输入框**：IME 直接把提交结果写进文本通道，绕过 KeyEvent。
所以"能看到字"完全不能证明按键到达了应用。

> 反过来说，这条通道**本身**是要验收的功能：候选框能不能弹、commit 后文本对不对，
> 验证方法见 §2.7（完整中文输入链路）。两者不要混为一谈。

---

## §2 可靠手法（按优先级）

### 2.1 用剪贴板粘贴代替逐字输入 ★首选

要验证的是"有这段文本之后应用怎么反应"，不是"键盘能不能打字"：

```powershell
Set-Clipboard -Value "要验证的文本（可多行、可中文）"
# 点中输入框后再按 Ctrl+V
keybd_event(0x11, 0, 0, 0); Key(0x56); keybd_event(0x11, 0, 2, 0)
```

- 走文本通道，不受键盘布局 / IME 状态影响；
- 一次注入整段，**能验证多行与自动高度**；
- 中文无需 `SendKeys`（`SendKeys` 对非 ASCII 会直接抛异常）。

注意：应用若拦截 `Ctrl+V`（本项目为了图片粘贴 / 大段折叠 / 路径识别），
必须保证**普通文本回落到默认粘贴**，否则粘贴会"消失"——
这正是端到端要覆盖的回归点。

### 2.2 点击代替按键

要触发"发送/确认"，优先**点按钮**；只有专门验证 Enter 键行为时才用键盘，
并明确写下"这一条依赖键盘、可能被 IME 干扰"。

### 2.3 实在要按键：先切英文，但仍不可靠

给输入框焦点后发一次孤立 `VK_LSHIFT`（`0xA0`）按下+抬起，多数中文 IME 会切到英文直通。
**不可靠**：`Ctrl+A`、切换焦点、点击别的控件都可能把状态切回去。
所以它只能是"补充手段"，不能作为验证结论的唯一依据。

### 2.4 方向键 / Delete / Home 必须带 `KEYEVENTF_EXTENDEDKEY` ★

`keybd_event` 对**扩展键**（↑↓←→、Insert/Delete/Home/End/PgUp/PgDn、右 Ctrl/Alt、小键盘除号与回车）
必须带标志位 `0x0001`，否则被当作小键盘的等价键（↑ 变成 NumPad8），应用一个事件都收不到：

```powershell
# 按下: EXTENDEDKEY(0x1)；抬起: EXTENDEDKEY|KEYUP(0x3)
keybd_event(0x26, 0, 1, 0); keybd_event(0x26, 0, 3, 0)   # ↑
```

**实测教训**：本仓库"输入框按 ↑ 取发送历史"连续多轮判为"不生效"，实际是脚本没带扩展键标志；
应用侧逻辑一直是对的（Compose UI 测试一直是绿的）。**先怀疑注入，再怀疑应用** ——
判据是同一次会话里普通键（Enter/Backspace/Ctrl+V）都生效、只有方向键类无效。

### 2.5 点击坐标偏移：先说清"点击落在哪"★本轮新增

**症状**：`+` 按钮点坐标有效，点输入框毫无反应（连 `onFocusChanged` 都不触发），
看起来像"输入框不可点击"的应用 bug。

**根因**：`GetWindowRect` 返回的矩形可能**大于窗口实际可见区域**（DWM 边框/阴影未计入内容区）。
`CopyFromScreen` 按这个矩形截图，于是截图底部**混入了窗口之外的内容**，
而 `SetCursorPos` 用的是真实屏幕坐标 —— 两者差几十像素。

**判据（一眼识别）**：截图最底部出现**不属于该窗口**的内容（终端文字、桌面背景、别的窗口边缘）。
一旦看到，就不要相信截图坐标，必须校准：

```powershell
# 1. 找一个已知可点的控件（如新建按钮），用截图坐标点击，确认成功
# 2. 输入框用「截图坐标 - 偏移」逐个试，直到出现日志/光标变化
foreach ($y in @(820, 840, 855, 865, 875)) {
    Click-At $h 860 $y
    Key 0x41
    if ((Get-Content $log | Select-String 'DIAG').Count -gt 0) { break }   # 命中
}
```

本仓库实测：1440x900 窗口上偏移约 **57px**（截图 y=877 的输入框，实际要点 y=820）。

### 2.6 切输入法布局用 Win+Space，别指望单次 Shift ★本轮新增

孤立 `VK_LSHIFT(0xA0)` 或 `VK_SHIFT(0x10)` 在微软拼音下**经常无效**（本轮两种都试过，layout 始终 `0x8040804`）。
可靠的切法是模拟系统快捷键：

```powershell
keybd_event(0x5B,0,0,0);  keybd_event(0x20,0,0,0)   # Win down, Space down
keybd_event(0x20,0,2,0);  keybd_event(0x5B,0,2,0)   # Space up, Win up
# 用 GetKeyboardLayout(GetWindowThreadProcessId($h)) 校验：0x8040804(中文) → 0x4090409(英文)
```

**不要省略校验**：`GetKeyboardLayout` 是唯一能证明"切换真的生效"的证据，
否则后面所有的"打不出字"都无法归因。

### 2.7 验证中文输入：拼音 → 候选框 → 数字键选词 ★本轮新增

想验证"中文输入法能不能用"，光打字母不够（IME 可能只是在直通模式）。**完整链路**：

1. 确认 layout 是中文（`0x8040804`）且输入框已有焦点；
2. 逐键发 `n i h a o`（`0x4E 0x49 0x48 0x41 0x4F`），每键间隔 130–150ms；
3. 截图：应看到**候选框**（`1 你好 2 你号 3 拟好 …`）+ 输入框里的组合串（`ani'hao`）；
4. **用数字键 `1`（`0x31`）选词**，不要用空格；
5. 截图：输入框应变成 `你好`。

**为什么不用空格选词**：`keybd_event` 注入的空格会被**应用与 IME 同时接收** ——
应用把它当普通空格插入，IME 又同时关闭候选框，结果是 `anihao`（拼音残留）而不是"你好"。
数字键没有这个二义性。**真实键盘不受影响**，这只是注入侧的现象。

**这条路能证明什么**：候选框位置、组合态渲染、IME commit → `onValueChange` 全通，
即 BasicTextField 的 IME 通道是好的。**不能证明**的是真实键盘的空格/Enter 时序 —— 那只能人工验。


---

## §3 抢前台：Windows 不允许后台进程抢焦点

`SetForegroundWindow` 从后台进程调用会返回 `False`，鼠标/键盘会打进用户当时的窗口（**危险**）。

```powershell
ShowWindowAsync($h, 6)   # SW_MINIMIZE
Start-Sleep -Milliseconds 400
ShowWindowAsync($h, 9)   # SW_RESTORE —— 还原时通常能拿到前台
```

**每一个**鼠标和键盘动作前都校验（不只是点击前——`Ctrl+A` + `Delete` 打到别的窗口会删掉用户的文件内容），
不匹配就立即中止，绝不盲目注入：

```powershell
if ([W]::GetForegroundWindow() -ne $h) { Write-Host "foreground lost"; exit 2 }
```

### 3.1 别只按窗口标题找进程 ★真实事故

上游 Wails 桌面端（`netbird-ui.exe`，标题 `NetBird`）与本项目 Compose 桌面端
（标题 `NetBird UI`，进程 `java(w).exe`）**很容易按标题误命中**：按
`Get-Process | Where-Object MainWindowTitle -like 'NetBird*'` 会把用户正在用的真实客户端也算进来，
于是按键打进了那个窗口（它有真实连接状态，点 Up/Down 会真的断网）。

**可靠做法**：按可执行文件 / 进程名区分，并在启动应用时自己记住 pid。

```powershell
# JVM 进程的窗口句柄就挂在 java(w).exe 上；上游 Wails 端是 netbird-ui.exe
$proc = Get-Process java,javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $proc) { Write-Host 'app window not found (已退出？)'; exit 1 }
```

**前置断言**：脚本开始先确认窗口确实存在且**是刚启动的那个**（日志里有 startup 行 +
`MainWindowHandle -ne 0`）。应用可能已经退出，此时所有注入都会打到别处。

### 3.2 前台句柄是 0 就立刻停手 ★

`GetForegroundWindow()` 返回 **0** 表示当前会话没有可见桌面（锁屏、RDP 断开、快速用户切换）。
此时 `ShowWindowAsync` / `SetForegroundWindow` 全部无效，而且**键鼠会打进另一个会话的登录界面**。

```powershell
if ([W]::GetForegroundWindow() -eq 0) { Write-Host 'session not interactive (locked/disconnected)'; exit 3 }
```

对照判据（本仓库实测）：窗口站仍是 `WinSta0\Default`、屏幕尺寸读得到、应用 `Responding=True`，
但前台句柄恒为 0、`LogonUI` 出现在**另一个 SessionId** —— 这不是 bug，是会话不可见。
**不要试图绕过**（例如 `PrintWindow` 截图 + `PostMessage` 注入），结果不可信。

### 3.3 原生拖放（OLE DnD）无法脚本化

文件拖入走的是 OLE 拖放，需要**真实的拖放源**（资源管理器发起）。
`SendInput` 只能移动光标，不会生成 `IDropTarget` 调用；同进程 `DoDragDrop` 也不跨进程生效。
所以拖放的验证只能：① 逐项测它下游的行为（本项目 UI 目前**没有**拖放功能，不要为它造用例）；
② 真加了拖放时，让人工拖一次确认交互，别指望脚本。

实现侧注意：CMP 的 `Modifier.dragAndDropTarget` 在 `androidx.compose.foundation.draganddrop`
（不在 `ui` 包），详见技能 [`compose-multiplatform-desktop`](../compose-multiplatform-desktop/SKILL.md) 的硬坑表。

---

## §4 结果判定：截图，不要相信"操作成功"

```powershell
$rect = New-Object W+RECT
[void][W]::GetWindowRect($h, [ref]$rect)
$bmp = New-Object System.Drawing.Bitmap ($rect.Right - $rect.Left), ($rect.Bottom - $rect.Top)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size $w, $h))
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
```

- 脚本"成功结束"只证明按键发出去了，**不证明界面做了对的事**；
- 关键节点各截一张（输入后 / 动作后 / 完成态），失败时能直接看出卡在哪；
- 窗口 rect 与截图坐标系同为物理像素，DPI 缩放下也一致；
- 只截目标窗口区域，避免把用户桌面其他内容带进证据。

---

## §5 实例隔离与善后（必做）

端到端会**真的改变网络状态**（`Up` / `Down` / 切换 profile / 改配置），必须先隔离：

1. **专用 daemon 实例**：用技能 `netbird-multi-instance` 的目录布局起一个测试实例
   （独立 `--config`、`--daemon-addr`、`NB_STATE_DIR`），并给它单独的
   `--json-socket tcp://127.0.0.1:8479` —— 端口不要与生产实例撞；
2. 所有验证都在这个实例上做，`NETBIRD_UI_PORT=8479` 启动 UI；
3. 结束时停掉该实例、删掉它的状态目录，确认**生产实例**的 NRPT 规则与接口都还在
   （`Get-DnsClientNrptRule`、`Get-NetAdapter`）；
4. 误操作（点到了生产实例的 UI）立刻收拾：先把网络操作停手（`netbird down`），
   再看 `Status` 确认回到 Idle，并在报告里写明影响面。

**绝不在用户正在连生产网络的实例上点 Up / Down / 切换 profile** —— 那是真的断网，
不是"测试数据"。同理不要在生产实例上改 `SetConfig`（会真的重连、真的改 MTU/DNS 开关）。

---

## §6 检查清单

跑 GUI 端到端前逐条确认：

- [ ] 是否必须注入按键？能换剪贴板就换
- [ ] 若必须按键：先切英文（用 Win+Space 并校验 layout），并在报告里标注"此结论依赖 IME 状态"
- [ ] 截图底部有没有混入窗口之外的内容？有就先校准点击坐标（§2.5）
- [ ] 窗口确实存在（`MainWindowHandle -ne 0`）、且按**进程名**而非窗口标题定位
- [ ] `GetForegroundWindow() != 0`（是 0 就别动，会话不可见）
- [ ] 每次鼠标/键盘前校验前台窗口
- [ ] 关键节点截图（至少：动作前、动作后）
- [ ] 实例隔离：专用 daemon 实例 + 独立 `--json-socket` 端口 + 结束后清理，生产实例不受影响
- [ ] 失败时能区分"注入没到"与"应用逻辑错"（临时 DEBUG 日志）
- [ ] **目标窗口是不是提权进程？** 是就必须用管理员脚本注入（见 §2.8）

---

### §2.8 提权窗口无法从普通权限进程注入 ★UIPI

**症状**：`SetCursorPos`/`mouse_event`/`SendInput`/`PostMessage` 全部"成功"返回，
但光标始终停在原处（`GetCursorPos` 读到的是用户自己的位置），界面毫无反应。截图与
`GetWindowRect` 都正常，于是很容易误判成"点错了坐标"或"应用不响应"。

**判据**：`SetCursorPos(x,y)` 之后立刻 `GetCursorPos` 读回的不是 `(x,y)` —— 不是被用户抢走，
而是被 **UIPI（User Interface Privilege Isolation）** 拒绝：低完整性进程不能向高完整性窗口
投递输入。桌面 UI 一旦自己 `ShellExecuteExW("runas")` 提权，所有普通权限的注入脚本就全废，
`PostMessage` 发到 Frame 也不会被 AWT 处理（该窗口没有子句柄，别指望发给 canvas）。

**做法**：注入脚本本身用管理员权限启动，一次 UAC 换整段流程：

```powershell
Start-Process pwsh -ArgumentList @('-NoProfile','-File',$script,'-Key',$key) -Verb RunAs -PassThru
```

- 脚本内部先断言 `GetForegroundWindow() -eq $hwnd`，再点击；每次点击前重试直到
  `GetCursorPos` 读回目标坐标；
- **一次 UAC 做完整条链**（启动实例 → 等网关 → 填表 → 提交 → 截图），别一步一次；
- 杀掉提权 UI 同样需要管理员：普通权限 `Stop-Process` 会 `Access denied`。
- 反过来也要注意：**提权进程与普通进程的 `$env:TEMP` 未必一致**，脚本输出路径用绝对路径。

---

## §7 反例（都踩过）

| 做法 | 为什么错 |
|---|---|
| `SendKeys.SendWait("{ENTER}")` | pwsh 默认 MTA 线程，`SendKeys` 本身不可靠；且 IME 会吃掉 Enter |
| `SendKeys` 打中文 | 非 ASCII 字符直接抛异常 |
| "输入成功了 → 说明 Enter 也能用" | 输入走文本通道，Enter 走 KeyEvent，两条路完全不同 |
| "文字没变 → 一定是应用没实现" | 先查注入：方向键缺 `EXTENDEDKEY`、IME 吞键、焦点丢了，三种都表现为"没反应" |
| 连续做完"输入 + 发送 + 断言"再看结果 | 失败时无法定位是注入、是应用、还是断言写错；要拆开并截图 |
| 直接点坐标不校验前台 | 会点到用户正在用的窗口上（本仓库实测误点过其他应用） |
| 用窗口标题 `NetBird*` 找窗口 | 上游 Wails 客户端标题是 `NetBird`，会把按键打进真实客户端；应按进程名 `java/javaw` + `MainWindowHandle -ne 0` |
| 只在点击前校验前台 | 键盘动作同样危险：`Ctrl+A`+`Delete` 打到编辑器/资源管理器会丢用户内容 |
| "点输入框没反应 → 输入框坏了" | 先看截图底部有没有混入窗口外的内容：有就是坐标偏移（§2.5），本仓库实测差 57px |
| 孤立的 `Shift` 切中英 | 本轮两种 VK 都无效，`Win+Space` 一次成功（§2.6）；切完必须用 `GetKeyboardLayout` 校验 |
| 空格选词验证中文输入 | 注入的空格会被应用和 IME 同时接收，得到 `anihao` 而不是"你好"；用数字键选词（§2.7） |

---

## §8 相关

- UI 验收口径：[`desktop-ui/AGENTS.md`](../../../desktop-ui/AGENTS.md) §7
- daemon 通道与状态机：[`netbird-desktop-ui-contract`](../netbird-desktop-ui-contract/SKILL.md)
- 本机构建与镜像类坑：[`desktop-ui/AGENTS.md`](../../../desktop-ui/AGENTS.md) §5

---

## §9 顺带发现的产品 bug（GUI 端到端才暴露的两类模式）

用剪贴板 + 键盘实跑才发现的问题，单测与 Compose UI 测试都不容易覆盖：

| 现象 | 根因 | 判据 |
|---|---|---|
| 接受补全后得到 `/skinill`（目标 `/skin`） | 替换区间用的是**请求发出时**的光标，异步返回后文本已变长，尾部残留 | 任何"异步结果 + 文本区间"的组合都要在**应用时刻**重算区间 |
| 按 Enter 什么都不发生 | 补全浮层还在但已无有效项，`handleComposerKey` 无条件 `return true` 吞掉按键 | 按键处理里"接管"必须和"确实处理了"绑定：返回布尔值，未处理就放行 |
| 接受补全后浮层不消失、Enter 一直停在"接受" | 接受后立刻重新请求补全，同一条目又匹配自己 | 接受结果不要触发同一套自动补全，等用户下一次输入 |

**通用结论**：GUI 端到端不只验证"能不能用"，它专门暴露**状态机在时间维度上的错位**
（请求时的快照 vs 应用时的现状、按键接管 vs 实际处理）。这类问题单测要写"返回前文本又变了"的用例才能覆盖。
