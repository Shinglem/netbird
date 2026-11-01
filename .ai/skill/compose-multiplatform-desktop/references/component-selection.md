# Compose Multiplatform 桌面端 · 组件候选清单

> 逐功能域的候选表。每条都标注**可获取渠道**（Maven Central / JitPack / 无发布物）、**许可证**、
> **已知坑**与**验证方式**。碰新需求先查本表，不要凭记忆选库。

---

## 1. 自适应布局

| 候选 | 坐标 | 状态 | 坑 |
|---|---|---|---|
| **M3 canonical list-detail** | `org.jetbrains.compose.material3:material3-adaptive-navigation-suite` | ✅ 推荐 | **CMP-8735**：Desktop/iOS 不随窗口 resize 自适应；用 `ThreePaneScaffoldDirective` 绑 `windowSizeClass` + `key(sizeClass)` 规避 |
| WindowSizeClass | `org.jetbrains.compose.material3:material3-window-size-class` | ✅ 必备 | 与上一项配套 |
| 自写 Row 布局 | — | 备选（~150 行） | 能拖宽，但要自己做响应式退化 |

**验证**：拖动窗口从 1400 → 700 → 1400，观察 pane 是否重排（不重排是已知缺陷，非本项目 bug）。

---

## 2. Markdown 渲染

> **2026-10-31 起（ADR-052）**：渲染实现已抽成独立公共库 `Shinglem/markdown-one`，以 git submodule
> 挂在 `modules/markdown-one`（Apache-2.0），本仓库只剩门面。下表是**当年选 mikepenz 的理由与坑**，
> 仍然成立（它就是 markdown-one 的历史底板），继续用于判断"某个能力该不该自己做"。

| 候选 | 坐标 | 状态 | 坑 |
|---|---|---|---|
| **multiplatform-markdown-renderer** | `com.mikepenz:multiplatform-markdown-renderer`（`-m3` / `-code` / `-coil3`） | 历史底板，Apache 2.0（现已内嵌进 markdown-one，本仓不再直接依赖） | `annotator` 只能改已有节点，不能造块级元素；表格宽表无横向滚动策略 |
| JetBrains/markdown | `org.jetbrains:markdown` | ✅ 传递依赖 | 纯解析器，无渲染；但 `MarkdownFlavourDescriptor` 是自定义节点入口 |
| compose-markdown（Jeziellago） | Maven Central | 弃 | 长期不维护、无流式 |
| 自研 | — | 弃 | 52 人天起 |

**验证**：给 1,000 token 流式输出，观察是否重排/抖动；未闭合 ``` 与 `**` 是否闪烁。

---

## 3. 代码高亮

| 候选 | 坐标 | 状态 | 坑 |
|---|---|---|---|
| **Highlights** | `dev.snipme:highlights` | ✅ 首选，Apache 2.0 | **仅 17 种语言**：缺 JSON/YAML/SQL/HTML/XML/TOML/Dockerfile/Markdown/Lua，需补正则 tokenizer |
| kodemirror `lang-*` | `com.github.seanghay:...`（JitPack） | 备选 | 绑定 CodeMirror 6 运行时，重 |
| SyntaxMP | GitHub | 弃（4 stars） | 39 语言但不成熟 |
| tree-sitter-kotlin / JNI | — | 弃 | 打包与跨平台成本高 |

**验证**：同一段 Java/Kotlin/Python 代码在三处显示是否一致；缺语言时是否退化为纯文本（不报错）。

---

## 4. Markdown / 代码编辑器（输入与编辑）

| 候选 | 形态 | 状态 |
|---|---|---|
| **kodemirror** | Compose 原生，CodeMirror 6 Kotlin 移植 | 编辑场景可选（JitPack，锁 tag v0.3.7） |
| SweetEditorCompose | Compose 原生 | 可选 |
| compose-rich-editor | WYSIWYG | 弃：语义与「发送 Markdown 源码」不符 |
| **BasicTextField + InlineTextContent** | 原生 | ✅ **输入框首选**（IME 可靠） |

**关键判据**：**中文输入法组合态**。任何自绘编辑器都要自己接管 IME，这是 Compose Desktop 上最容易出事的地方。

**验证**：中文输入法打「你好世界」，中途按 Enter（应上屏不发送）、按 Esc（应取消组合）。

---

## 5. 终端

| 候选 | UI | 渠道 | 许可证 | 状态 |
|---|---|---|---|---|
| **BossTerm** | Compose 原生 | **JitPack**（`com.github.kshivang.BossTerm:*`，锁 `v1.2.155`）；Maven Central **无** `com.risaboss` | LGPL-3.0 | ✅ 首选 |
| KetraTerm | Swing | `io.github.ketraterm:ketraterm-bom`（Maven Central） | Apache 2.0 | 备选（需 SwingPanel） |
| JediTerm | Swing | **无 Maven 发布物**（numFound=0），JitPack 需锁 commit | LGPLv3/Apache 双许可 | 兜底 |
| 自研 VT100 | — | — | — | 不做（vttest 过不去） |

**PTY**：`org.jetbrains.pty4j:pty4j`（Windows 走 ConPTY）。

**验证**：vim 进出、top 刷新、中文目录 `ls`、256 色、拖窗口 resize 后光标是否偏移。

---

## 6. SVG / 图片

| 候选 | 坐标 | 状态 |
|---|---|---|
| **Coil 3 + coil-svg** | `io.coil-kt.coil3:coil-compose` / `coil-svg` / `coil-network-ktor` | ✅ 首选（运行时解码，不编译期转换） |
| Skia `SVGDOM` | 随 Compose | 底层可用但 API 原始 |
| svg-to-compose（编译期代码生成） | Gradle 插件 | 弃：构建期负担、不支持运行时 URL |

**验证**：加载一个含渐变与文字的外链 SVG，检查渲染完整性与失败降级。

---

## 7. 网络 / 序列化 / 持久化

| 需求 | 坐标 | 备注 |
|---|---|---|
| HTTP + WS | `io.ktor:ktor-client-core` `ktor-client-cio` `ktor-client-websockets` `ktor-client-content-negotiation` | CIO 引擎在 desktop 最省事 |
| JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 契约生成配合 |
| 时间 | `org.jetbrains.kotlinx:kotlinx-datetime` | 相对时间显示 |
| 偏好 | `com.russhwolf:multiplatform-settings` | 桌面落 `java.util.prefs` 或自定义文件 |
| 数据库 | `app.cash.sqldelight` | **本期不用**；等需要全文检索 transcript 再评估 |
| 日志 | `co.touchlab:kermit` | 可选 |

**验证**：拔网线 30s 后恢复，事件不丢（重连重放）；半开 TCP 能被心跳判死。

---

## 8. 交互增强

| 需求 | 坐标 | 备注 |
|---|---|---|
| 拖拽排序 | `sh.calvin.reorderable:reorderable` | M3 列表排序 |
| 图标 | `org.jetbrains.compose.material:material-icons-extended` | 体积大，按需 |
| 快捷键 | `Window.onPreviewKeyEvent` / `KeyShortcut` | 原生 |
| 右键菜单 | `ContextMenuArea`（desktop） | 原生 |

---

## 9. 明确不引入

| 类别 | 理由 |
|---|---|
| DI 框架（Koin / Hilt） | 单窗口应用，手动构造足够 |
| 导航库（Decompose / Voyager / Navigation 3） | 一个顶层 `AppMode` 状态即可 |
| 富文本编辑器（WYSIWYG） | 语义不符 |
| WebView 方案（KCEF 等） | 本期无富嵌入需求 |
| 自研终端 / 自研编辑器 | 成本与风险都不划算 |

---

## 10. 选库流程（每次都要走）

1. `search.maven.org/solrsearch/select?q=g:"<group>"` —— **不要只信 README 的坐标**
2. JitPack 项：`jitpack.io/api/builds/<owner>/<repo>` 看 tag 构建状态
3. `api.github.com/repos/<owner>/<repo>/license` 查许可证
4. 搜 `<库名> <平台> issue`（如 "compose desktop resize"）
5. 写进项目的 `desktop-ui/AGENTS.md` §2 + `desktop-ui/gradle/libs.versions.toml`
