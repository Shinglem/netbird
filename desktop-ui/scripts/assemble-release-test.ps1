<#
.SYNOPSIS
    组装 release-test/ —— 发版前的本地真实测试目录（后端 + UI 启动器 + 实例配置）。

.DESCRIPTION
    产物布局（与 hermes-compose 一致；整个目录已被根 .gitignore 忽略，不入库）：

      release-test/
        |- profiles\                 后端配置：每个实例一个子目录（config\ + runfiles\）
        |- netbird-multi.exe         后端（自编译的多实例版本）
        |- wintun.dll                后端依赖，必须与 exe 同目录
        |- netbird-multi-ui.exe      UI 启动器（jpackage 只产这一份 exe）
        |- ui\ui.json                界面默认配置（随版本覆盖，不要手改）
        |- ui\ui.custom.json         用户覆盖（只存改过的键，升级不丢）
        |- app\netbird-multi-ui.cfg  启动配置：app.runtime 外挂 rt\、classpath 指向 bin\ 与 lib\
        |- bin\App.jar               本模块的类与资源
        |- lib\*.jar                 全部依赖（含 skiko 的 dll 与 icudtl.dat）
        |- rt\                       jlink 裁剪运行时（scripts 不动它，由 Gradle 产出）
        |- ui\                       UI 侧资源目录（图标 / 主题等，当前为空）

    几点约定：

      * UI 的三层（exe / app\ / bin+lib+rt）由 `:composeApp:syncReleaseTest` 产出，
        **不要手改**；`profiles\`、`ui\`、`Netbird\` 与后端二进制是用户数据/外部输入，
        同步时会被 preserve。
      * 启动器 exe 只认 `app\<名字>.cfg`；cfg 里 `app.runtime=$ROOTDIR\rt`，
        所以 **`rt\` 不是可选项**，删了 exe 直接打不开。
      * UI 的实例根取自启动器所在目录（`AppPathsResolver` 的第 3 优先来源）：
        `netbird-multi-ui.exe` 旁边有 `profiles\` 就用它，所以这个目录是自包含的，
        双击 exe 不需要设任何环境变量。

    本脚本不含任何本机绝对路径：后端位置要么用 -BackendDir 显式给，要么从
    `.local\env.ps1` 的实例清单里推导（该文件不入库）。

.EXAMPLE
    pwsh -NoProfile -File desktop-ui\scripts\assemble-release-test.ps1

.EXAMPLE
    pwsh -NoProfile -File desktop-ui\scripts\assemble-release-test.ps1 -NoBuild
    # 后端与配置已就绪，只想刷新 UI 时不要这样用；-NoBuild 表示连 Gradle 都不跑

.EXAMPLE
    pwsh -NoProfile -File desktop-ui\scripts\assemble-release-test.ps1 `
        -BackendDir <含 netbird-multi.exe 与 wintun.dll 的目录> `
        -ProfilesFrom <既有 profiles 目录>

.NOTES
    跑之前先关掉 release-test 里的 UI 与它拉起的 daemon，否则 exe/app/rt 正被占用。
#>
[CmdletBinding()]
param(
    # 输出目录，默认 <仓库>\release-test（UI 部分固定由 Gradle 输出到这里）
    [string]$Target,
    # 后端目录（含 netbird-multi.exe 与 wintun.dll）；留空则从 .local\env.ps1 推导
    [string]$BackendDir,
    # 可选：把一份既有 profiles\ 复制进来
    [string]$ProfilesFrom,
    # 本机实例清单，默认 <仓库>\.local\env.ps1
    [string]$EnvFile,
    # 跳过 Gradle 的 syncReleaseTest，只刷新后端与配置
    [switch]$NoBuild,
    # 不覆盖后端 netbird-multi.exe / wintun.dll（它们被运行中的 daemon 占用时用这个）
    [switch]$SkipBackend
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$uiRoot = Join-Path $repoRoot 'desktop-ui'
if (-not $Target) { $Target = Join-Path $repoRoot 'release-test' }
if (-not $EnvFile) { $EnvFile = Join-Path $repoRoot '.local\env.ps1' }

function Get-LocalJdk {
    $file = Join-Path $uiRoot 'local.properties'
    if (-not (Test-Path $file)) { return $null }
    $line = Get-Content $file |
        Where-Object { $_ -match '^\s*netbird\.jdk\.dir\s*=' } |
        Select-Object -Last 1
    if (-not $line) { return $null }
    return ($line -split '=', 2)[1].Trim()
}

function Get-BackendDirectory {
    param([string]$Explicit)

    if ($Explicit) { return (Resolve-Path $Explicit).Path }
    if (-not (Test-Path $EnvFile)) {
        throw "找不到 $EnvFile。请用 -BackendDir 指向含 netbird-multi.exe 与 wintun.dll 的目录。"
    }

    . $EnvFile
    $multi = $NetbirdInstances |
        Where-Object { $_.Kind -eq 'self-built-multi' } |
        Select-Object -First 1
    if (-not $multi) {
        throw "$EnvFile 里没有 Kind = 'self-built-multi' 的实例；请用 -BackendDir 显式指定后端目录。"
    }
    return Split-Path -Parent $multi.Binary
}

function Assert-NotBusy {
    param([string]$Dir)

    $busy = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*$Dir*" -and $_.ProcessId -ne $PID }
    if ($busy) {
        throw ("$Dir 正在被占用（pid: $($busy.ProcessId -join ', ')）。" +
               '先关掉那里的 UI（会一并停掉它拉起的 daemon），再重跑。')
    }
}

if (-not $Target) { $Target = Join-Path $repoRoot 'release-test' }
$defaultTarget = Join-Path $repoRoot 'release-test'
if ((Resolve-Path -LiteralPath $Target -ErrorAction SilentlyContinue)?.Path -ne
    (Resolve-Path -LiteralPath $defaultTarget -ErrorAction SilentlyContinue)?.Path) {
    throw "UI 部分固定由 Gradle 输出到 $defaultTarget；-Target 只用于改后端/配置的落点，当前构建不支持。"
}

Assert-NotBusy $Target

$backendRoot = Get-BackendDirectory $BackendDir

if (-not $NoBuild) {
    $jdk = Get-LocalJdk
    if ($jdk) {
        $env:JAVA_HOME = $jdk
        Write-Host "JAVA_HOME = $jdk"
    } else {
        Write-Warning 'local.properties 里没有 netbird.jdk.dir，Gradle 将使用当前 JAVA_HOME。'
    }

    Push-Location $uiRoot
    try {
        Write-Host 'gradlew :composeApp:verifyReleaseTest   （内部会先 syncReleaseTest）'
        # verify 依赖 sync：这一条同时完成 jlink 裁剪 rt\、jpackage 只产启动器、写 app\*.cfg、
        # bin\App.jar + lib\*.jar 的镜像，并校验结构与模块集合。
        & .\gradlew.bat :composeApp:verifyReleaseTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle 失败，exit=$LASTEXITCODE" }
    } finally { Pop-Location }
}

foreach ($required in 'netbird-multi-ui.exe', 'app\netbird-multi-ui.cfg', 'bin\App.jar', 'lib', 'rt\bin\server\jvm.dll') {
    if (-not (Test-Path (Join-Path $Target $required))) {
        throw "release-test 缺少 $required：先跑 :composeApp:verifyReleaseTest（不要加 -NoBuild）。"
    }
}

if ($SkipBackend) {
    Write-Host '跳过后端复制（-SkipBackend）'
} else {
    foreach ($file in 'netbird-multi.exe', 'wintun.dll') {
        $source = Join-Path $backendRoot $file
        if (-not (Test-Path $source)) { throw "后端缺少 $source" }
        try {
            Copy-Item $source $Target -Force
        } catch {
            throw ("覆盖 $file 失败：$($_.Exception.Message)`n" +
                   '该文件正被运行中的 daemon 使用（它会断开当前的连接）。' +
                   '先停掉那个实例，或用 -SkipBackend 保留现有后端。')
        }
    }
}

foreach ($dir in 'profiles', 'ui') {
    New-Item -ItemType Directory -Force (Join-Path $Target $dir) | Out-Null
}

# 界面默认配置：与 data/DesktopConfig.kt 的默认值一致。程序每次启动会重写这个文件，
# 这里给一份初值只是让人打开目录就能看到有哪些可配项；用户改动落在 ui.custom.json。
$uiConfig = Join-Path $Target 'ui\ui.json'
if (-not (Test-Path $uiConfig)) {
    $defaultUi = @'
{
  "theme": "material",
  "themeMode": "system",
  "closeToTray": true
}
'@
    [IO.File]::WriteAllText($uiConfig, $defaultUi + "`n", (New-Object System.Text.UTF8Encoding($false)))
}

if ($ProfilesFrom) {
    $source = (Resolve-Path $ProfilesFrom).Path
    Copy-Item (Join-Path $source '*') (Join-Path $Target 'profiles') -Recurse -Force
}

Write-Host ''
Write-Host "release-test 就绪：$Target" -ForegroundColor Green
Get-ChildItem $Target | Sort-Object Name | ForEach-Object {
    $kind = if ($_.PSIsContainer) { 'dir ' } else { 'file' }
    Write-Host ("  {0} {1}" -f $kind, $_.Name)
}
Write-Host ''
Write-Host "双击 $(Join-Path $Target 'netbird-multi-ui.exe')；实例根会自动取同目录的 profiles\\。"
