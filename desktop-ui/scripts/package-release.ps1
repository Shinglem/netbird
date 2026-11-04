<#
.SYNOPSIS
    把 release-test/ 打成可发布的 zip（本地与 CI 共用）。

.DESCRIPTION
    产出：

      <OutDir>\netbird-multi-ui-<tag>-windows-x64.zip
      <OutDir>\SHA256SUMS.txt

    包内顶层目录固定是 `netbird-multi-ui\`，包含启动器、app\、bin\、lib\、rt\、后端
    netbird-multi.exe、wintun.dll、空的 profiles\ 与 README.txt。**不含**用户数据：
    profiles\ 下的内容、ui\ui.custom.json、ui\state.json、instance.lock、*.log 一律排除。

    版本口径（见 desktop-ui/AGENTS.md 的「发布命名」）：tag 形如 v<本仓库版本>-<上游版本>，
    其中本仓库版本必须等于 composeApp/build.gradle.kts 的 appVersion。

.PARAMETER Tag
    发布 tag，例如 v0.1.2-0.82.0。

.PARAMETER Source
    成品目录，默认 <repo>\release-test。

.PARAMETER OutDir
    zip 落点，默认 <repo>\.local\release（不入库）。

.EXAMPLE
    pwsh -NoProfile -File desktop-ui\scripts\package-release.ps1 -Tag v0.1.2-0.82.0
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Tag,
    [string]$Source,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Source) { $Source = Join-Path $repoRoot 'release-test' }
if (-not $OutDir) { $OutDir = Join-Path $repoRoot '.local\release' }

$match = [regex]::Match($Tag, '^v(\d+\.\d+\.\d+)-(\d+\.\d+\.\d+)$')
if (-not $match.Success) {
    throw "Tag 必须形如 v0.1.2-0.82.0（本仓库版本-上游版本），实际 '$Tag'"
}
$desktop = $match.Groups[1].Value
$upstream = $match.Groups[2].Value

$gradleScript = Join-Path $repoRoot 'desktop-ui\composeApp\build.gradle.kts'
$declared = (Select-String -Path $gradleScript -Pattern '^val appVersion = "([^"]+)"' |
    Select-Object -First 1).Matches[0].Groups[1].Value
if ($declared -ne $desktop) {
    throw "tag 里的版本 $desktop 与 build.gradle.kts 的 appVersion $declared 不一致（先改版本再发）"
}

if (-not (Test-Path $Source)) { throw "成品目录不存在：$Source（先跑 :composeApp:verifyReleaseTest）" }
foreach ($item in 'netbird-multi-ui.exe', 'app', 'bin', 'lib', 'rt', 'netbird-multi.exe', 'wintun.dll') {
    if (-not (Test-Path (Join-Path $Source $item))) {
        throw "成品目录缺少 $item：$Source（先跑 :composeApp:verifyReleaseTest）"
    }
}

New-Item -ItemType Directory -Force $OutDir | Out-Null
$stageRoot = Join-Path $OutDir "_stage_$Tag"
$packageDir = Join-Path $stageRoot 'netbird-multi-ui'
Remove-Item $stageRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $packageDir | Out-Null

foreach ($item in 'netbird-multi-ui.exe', 'app', 'bin', 'lib', 'rt', 'netbird-multi.exe', 'wintun.dll') {
    Copy-Item (Join-Path $Source $item) $packageDir -Recurse -Force
}

# profiles\ 只留空目录：里面的实例配置含注册私钥。
New-Item -ItemType Directory -Force (Join-Path $packageDir 'profiles') | Out-Null
# ui\ 只带默认配置；用户在 ui.custom.json / state.json 里的改动不随包。
New-Item -ItemType Directory -Force (Join-Path $packageDir 'ui') | Out-Null
$defaultUi = Join-Path $Source 'ui\ui.json'
if (Test-Path $defaultUi) { Copy-Item $defaultUi (Join-Path $packageDir 'ui') -Force }

$readme = @"
NetBird 多实例桌面端 $desktop（上游基线 $upstream）
==================================================

解压后双击 netbird-multi-ui.exe。首次启动会请求一次管理员权限（要建虚拟网卡）。

首次启动会在本目录下生成：
  ui\ui.json          界面默认配置，随版本更新，不要手改
  ui\ui.custom.json   你的改动（主题、关闭到托盘、登录自启）
  ui\state.json       每个配置的「随界面启动」记录
  profiles\           实例配置与运行状态（含注册私钥，不要外传）

daemon 跟随界面：界面启动时按 ui\state.json 拉起配置，退出界面（托盘菜单
「退出（停止所有配置）」）会停掉全部 daemon。关闭窗口只是收进托盘。

登录自启是静默的：计划任务带 --silent，登录时只出现托盘图标，不弹主窗口。
界面已经在托盘里时，再次双击 netbird-multi-ui.exe 会把窗口叫出来。

新增配置：顶部「新建配置」，填名称与管理服务器地址。

本目录可整体搬走（便携）：路径全部相对程序目录解析。

运行时是 Temurin 27 的 jlink 裁剪版（rt\，9 个模块），不含 javac 等开发工具；
界面字体随包内置（Inter / JetBrains Mono，SIL OFL 1.1），中文用系统字体回退。
"@
[IO.File]::WriteAllText((Join-Path $packageDir 'README.txt'), $readme, (New-Object Text.UTF8Encoding($false)))

$zip = Join-Path $OutDir "netbird-multi-ui-$Tag-windows-x64.zip"
Remove-Item $zip -Force -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem
# includeBaseDirectory=false：stageRoot 下的 netbird-multi-ui\ 就是包内顶层目录。
[IO.Compression.ZipFile]::CreateFromDirectory(
    $stageRoot, $zip, [IO.Compression.CompressionLevel]::Optimal, $false)
# CreateFromDirectory 会跳过空目录，而 profiles\ 必须出现在包里。
$archive = [IO.Compression.ZipFile]::Open($zip, 'Update')
$archive.CreateEntry('netbird-multi-ui/profiles/') | Out-Null
$archive.Dispose()

Remove-Item $stageRoot -Recurse -Force

$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
$sums = Join-Path $OutDir 'SHA256SUMS.txt'
"$hash  $(Split-Path -Leaf $zip)" | Set-Content $sums -Encoding ascii

$sizeMb = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host "[release] 发布包：$zip（$sizeMb MB）"
Write-Host "[release] sha256：$hash"
Write-Host "[release] 校验和：$sums"
