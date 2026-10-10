<#
.SYNOPSIS
    把本 fork 的改动说明追加到 AGENTS.md（幂等）。

.DESCRIPTION
    AGENTS.md 是受保护的 agent 指令文件：自动化工具会拒绝编辑它，
    所以正文放在 patchdoc/agents-md-addendum.md，由本脚本负责落地。
    请在你自己的会话里运行一次。

    脚本可重复运行：若该节已存在，它会如实报告并且不做任何改动。
    写入前会备份 AGENTS.md，写入后回读文件以证明写入生效。

.PARAMETER RepoRoot
    仓库根目录，默认为脚本的上级目录。

.PARAMETER WhatIf
    只显示将要发生的改动，不写文件。

.EXAMPLE
    pwsh -NoProfile -File patchdoc\apply-agents-md.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$agents   = Join-Path $RepoRoot 'AGENTS.md'
$addendum = Join-Path $RepoRoot 'patchdoc\agents-md-addendum.md'

# 正文里的这个标题同时充当幂等标记。
$marker    = '## 本 fork 的改动'
$oldMarker = '## Fork-local changes (this repository)'   # 早期英文版用过
$tocEntry  = '- [本 fork 的改动](#本-fork-的改动)'
$tocAfter  = '- [Discussion and support](#discussion-and-support)'

foreach ($p in @($agents, $addendum)) {
    if (-not (Test-Path $p)) { throw "未找到: $p" }
}

$text = [IO.File]::ReadAllText($agents)

if ($text.Contains($marker)) {
    "AGENTS.md 已包含「本 fork 的改动」一节 —— 无需操作。"
    return
}

if ($text.Contains($oldMarker)) {
    throw ("检测到旧的英文版小节（'" + $oldMarker + "'）已存在于 AGENTS.md。" +
           "若直接追加会产生重复内容。请先手工删除该节及其目录条目，再重新运行本脚本。")
}

$section = [IO.File]::ReadAllText($addendum).TrimEnd()

# 优先把目录条目插在其兄弟项旁边；找不到锚点则退化为不动目录。
if ($text.Contains($tocAfter) -and -not $text.Contains($tocEntry)) {
    $text = $text.Replace($tocAfter, $tocAfter + "`n" + $tocEntry)
    "目录：已在 'Discussion and support' 之后插入新条目"
} else {
    Write-Warning "无法定位目录锚点（未找到 '$tocAfter'）—— 目录列表保持不变"
}

$text = $text.TrimEnd() + "`n`n" + $section + "`n"

if ($WhatIf) {
    "WhatIf：将备份为 AGENTS.md.bak，并写入 $($text.Length) 个字符。"
    return
}

$backup = "$agents.bak"
Copy-Item $agents $backup -Force
"备份：$backup"

[IO.File]::WriteAllText($agents, $text, [Text.UTF8Encoding]::new($false))
"已写入：$agents"

# 回读，而不是相信写入调用本身：这是该节确实落到磁盘上的唯一证明，
# 也能顺带发现编码意外。
$check = [IO.File]::ReadAllText($agents)
if ($check.Contains($marker) -and $check.Contains($tocEntry)) {
    "校验通过：小节与目录条目均已在磁盘上出现。"
    ""
    "--- AGENTS.md 末尾 ---"
    ($check -split "`n" | Select-Object -Last 6) -join "`n"
} elseif ($check.Contains($marker)) {
    "校验：小节已存在；目录条目缺失 —— 请手工补上。"
} else {
    throw "校验失败：文件中没有该小节"
}
