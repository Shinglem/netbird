---
name: compose-multiplatform-desktop
description: "用 Compose Multiplatform 写桌面客户端：选型与桌面端坑。"
version: 1.0.0
license: MIT
platforms: [windows, linux, macos]
metadata:
  tags: [Compose Multiplatform, Kotlin, 桌面客户端, 选型, 迁移, IME, 终端, Markdown 渲染]
  related_skills: [open-source-project-evaluation, codebase-inspection]
---

# Compose Multiplatform 桌面客户端

> **中文名**：Compose 桌面客户端｜目录与 `name`：`compose-multiplatform-desktop`

做 CMP 桌面客户端的**流程与决策**：怎么挑组件、哪些坑一定会在 desktop 上踩到、从 Electron/Web 客户端迁移时先量什么。
具体候选清单与逐项对比见 `references/component-selection.md`。

## When to Use

- 用 Kotlin / Compose Multiplatform 新写桌面客户端
- 把 Electron / Web 客户端迁到 Compose（后端不动、只换前端）
- 给 CMP 项目挑组件：Markdown 渲染、代码高亮、终端、编辑器、图片、自适应布局
- CMP 应用出现「能跑但手感怪」：输入法、滚动、窗口 resize、Swing 互操作

## 铁律

1. **官方优先**：M3 / foundation 能拼出来的（列表、开关、对话框、分段按钮、卡片、下拉、搜索）不引第三方库。
2. **引库前先查能不能装到**：`search.maven.org` 按 group 查、`jitpack.io/api/builds` 查 ref 状态、GitHub license API 查许可证。CMP 生态里「README 写了坐标、注册表查不到」是**常态**，不是意外。
3. **重资产用开源，协议层自研**：渲染 / 终端 / 编辑器 / 图片一律用成熟库；只有生态里确实没有的那层（典型：带 server→client 请求与事件重放的 JSON-RPC over WebSocket）才自己写。
4. **聊天输入框永远用 `BasicTextField`**：自绘编辑器内核要自己接管 IME 组合态，中文输入是硬需求时这是不可接受的风险。
5. **选型阶段就查平台缺陷**：搜 `<组件名> <目标平台> issue`。desktop 的 resize / IME / 滚动条是重灾区，写完才发现等于返工。

## 选型流程

1. 按功能域列清单（布局 / 列表 / 渲染 / 输入 / 编辑器 / 终端 / 图片 / 网络 / 持久化 / i18n / 打包）。
2. 每个域先问「官方有没有」，再问「成熟第三方有没有」。
3. 候选过注册表与许可证检查（见铁律 2）。
4. 候选再过平台缺陷检查（见铁律 5）。
5. 出表：**需求 / 首选 / 备选 / 已知坑**，每行带验证方式。
6. 交用户逐条拍板；用户常会主动砍功能来换少写代码，别替他保留「完整性」。

## 桌面端硬坑（每条都见过，别赌它不存在）

| 现象 | 原因 | 做法 |
|---|---|---|
| 跨消息选中复制不了 | LazyColumn 回收滚出视口的 item，选择链断裂 | 每条消息一个 `SelectionContainer` + 「复制整条 / 引用」按钮；写进验收口径 |
| 消息列表卡死或测量异常 | LazyColumn 内嵌 LazyColumn，高度无界 | 外层 LazyColumn + 单条消息内部用普通 `Column` |
| 滚动条闪烁 | `stickyHeader` 与 `VerticalScrollbar` 同用 | 不吸顶，用普通分组标题 |
| 两栏布局拖窗口后不重排 | M3 adaptive 的 ListDetailPaneScaffold 在 desktop 不随尺寸变化自适应 | 把 directive 显式绑到 `windowSizeClass` 的 State + `key(sizeClass)` 强制重组；不行就自写 `Row`（约 150 行） |
| 中文输入丢字 / 候选框错位 | 自绘文本组件没接管 IME | 输入只用 `BasicTextField`；中文需显式指定字体族并验断行与标点禁则 |
| 终端区焦点 / DPI / 重绘异常 | SwingPanel 互操作 | 优先选 Compose 原生的终端组件；走 Swing 就要预留 3–5 天 |
| 新增文件后编译器只报 `Unresolved reference: 我的新类`，从不报该文件内部的语法错误 | KDoc 里写了路径通配，例如 `` `/api/profiles/*` `` —— **Kotlin 块注释可嵌套**，`/*` 会开启一层嵌套注释，没有对应的 `*/` 就把整个文件吞掉；编译器在解析阶段就失败，所以看不到真正的错误 | 注释里不要写 `/*`（写成「`/api/profiles` 系列」）；排查时统计全文 `/*` 与 `*/` 的配对深度 |
| 流式回答越写越卡 | 每 token 全量重排整条消息 | 切「稳定块 + 活跃尾块」，只重组尾块；有库内置就直接用 |
| 拖放 API import 错包，编译报 `Unresolved reference 'dragAndDropTarget'` | `Modifier.dragAndDropTarget` 在 **`androidx.compose.foundation.draganddrop`**（`ui` 包下只有返回 `Modifier.Node` 的 `DragAndDropTargetModifierNode` 工厂，不能直接挂进修饰符链） | `import androidx.compose.foundation.draganddrop.dragAndDropTarget`；读拖入数据用 `androidx.compose.ui.draganddrop.dragData()`（desktop，`@ExperimentalComposeUiApi`）→ `DragData.FilesList.readFiles()`（**返回 URI**，要转本地路径）/ `DragData.Text.readText()` |
| 以为 desktop 没有拖放能力 | `receiveContent` 改名 `Modifier.contentReceiver`，且 skiko 的 `PlatformTransferableContent` 是空实现 | desktop 只认 `dragAndDropTarget`；文件拖入必须自己判断 `javaFileListFlavor` 那一路（`dragData()` 已封装） |
| Swing 终端里切标签要再点一下才能打字 | `SwingPanel` 托管的 Swing 组件不参与 Compose 的焦点系统，`Card`/`Box` 的可见性切换不会自动给 Swing 组件焦点 | 切/新建标签时显式 `widget.requestFocusInWindow()`（必须在 EDT 上做）；反过来，Swing 组件获得焦点后 Compose 的 `onPreviewKeyEvent` 收不到按键 —— 全局快捷键要在设计上说明"终端聚焦时失效" |
| 读 Swing 组件的尺寸/列数总是差一拍 | 尺寸回调发生在 Compose 布局阶段，Swing 侧的重排是异步的 | `onSizeChanged` 里只记 size，`LaunchedEffect(size)` 里 `delay(150)` 再读真实值 |
| 想给第三方 Swing 组件加复制/粘贴菜单做不动 | JediTerm 的 `copySelection` / `handlePaste` / `pasteFromClipboard` 全是 **private**，没有公开的"取选中文本"API | 要么只用它的 public 面（`selectAll()` / `getSelection()` / `clearBuffer()` / `TerminalStarter.sendString`），要么放弃并记为缺口；别用反射硬撬。**本项目的终局选择**：换成 Compose 原生的终端（源码裁剪 BossTerm，ADR-054），Swing 边界直接消失 |
| 弹出 `AlertDialog` 被终端内容遮住半截 | `SwingPanel` 是 heavyweight 组件，**永远画在 Compose 之上**；换成独立窗口的 `Dialog` 也一样被遮（实测两者都不行） | 先把 Swing 组件移出合成树（收起面板 / 切走页面）再弹框，关闭后恢复；或者在 Swing 侧用 `JOptionPane`。**根因修法**：把该区域改成 Compose 原生渲染（本项目 ADR-054 之后，两处绕法都已删除） |
| 想用 `-Dcompose.layers.type=WINDOW` 把 Popup/Dialog 变独立窗口绕开遮挡 | **实测在 JBR 25 + CMP 1.12.2 上 app 直接 `EXCEPTION_ACCESS_VIOLATION` 崩溃**（官方也列了 Linux 渲染 #4437、显示闪烁 #4475、父窗 resize 裁剪 #4484 三个已知问题）；另一个 flag `-Dcompose.swing.render.on.graphics=true`（`ComposeFeatureFlags.useSwingGraphicsInComposePanel`）官方注明**对 `ComposeWindow`/`ComposeDialog` 无效**，只作用于嵌在 Swing 里的 `ComposePanel` | 别指望这两个 flag。要真正在互操作组件之上弹东西，只有 desktop 专属 `DialogWindow`（独立 `JDialog`）或 Swing 的 `JOptionPane`；官方立场见 compose-multiplatform#1521（"限制来自架构，看起来不可行"）与 Swing 集成教程的前提条件「应用里没有 popup / tooltip / context menu」 |
| 设置页这类"一个大 `when` 分支"的详情面板参数爆炸 | 十几页的回调全平铺在同一个 `@Composable` 签名里，改动一次要碰三处 | 按页把回调打包成 `data class XxxPageCallbacks(val onFoo: ... = {})`，用默认值保持向后兼容 |

## 从 Electron / Web 客户端迁移：先量后算

1. **钉死统计口径**：LOC 要说明是否含测试 / 语言包 / 生成物，用逐文件求和或 pygount，**不要** `find … | xargs wc -l | tail -1`（xargs 会分批，`tail -1` 只取最后一批的 total，误差可达一个量级且不报错）。
2. **契约优先**：后端有单一 schema 源就写生成器输出 Kotlin `@Serializable` data class + 方法常量，不要手抄几百个类型。
3. **逐功能域做映射**：原版用什么 JS 库 → Compose 侧对应什么 → 风险等级，最后得出「必须 WebView 的清单」。实测这类清单通常收缩到很小的面（图表渲染、远程桌面协议这类），终端 / 编辑器 / 数学公式往往都有原生替代。
4. **人力估算只报量级**，不报承诺；并注明「这是移植下限」还是「含测试重建与打包」。

## 参考

- `references/component-selection.md` —— 逐功能域的候选表（渲染 / 高亮 / 编辑器 / 终端 / SVG / 自适应布局 / 网络 / 持久化），含已知坑与验证方式。
