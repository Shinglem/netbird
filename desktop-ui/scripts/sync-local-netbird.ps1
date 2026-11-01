<#
.SYNOPSIS
    把本机 NetBird 实例的真实配置同步到 <仓库>\.local\ —— 供 desktop-ui 调试用。

.DESCRIPTION
    本脚本**不含任何本机路径**：仓库是公开的，实例清单放在 `.local/env.ps1`（不入库）。
    缺那个文件时脚本会直接报错并说明要写什么，不会猜路径。

    它只**读**本机 NetBird 的位置，在 `.local\netbird\` 下生成三样东西：

      instances.json            两个实例的结构化清单（UI 可直接当调试配置读）
      <实例>\*.json             脱敏副本（PrivateKey / SSHKey / PreSharedKey → <redacted>）
      raw\<实例>.json           原始副本，供本机对照；ACL 收紧到当前用户 + SYSTEM + Administrators

    幂等：可重复运行，每次覆盖。`.local/` 已被根 .gitignore 忽略，绝不入库。

.PARAMETER EnvFile
    本机实例定义，默认 `<仓库>\.local\env.ps1`。格式见该文件里的注释。

.PARAMETER OutRoot
    输出根，默认 `<仓库>\.local`

.PARAMETER WhatIf
    只报告将写什么，不落盘。

.EXAMPLE
    pwsh -NoProfile -File desktop-ui\scripts\sync-local-netbird.ps1
    pwsh -NoProfile -File desktop-ui\scripts\sync-local-netbird.ps1 -WhatIf
#>
[CmdletBinding()]
param(
    [string]$EnvFile,
    [string]$OutRoot,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $EnvFile) { $EnvFile = Join-Path $repoRoot '.local\env.ps1' }
if (-not $OutRoot) { $OutRoot = Join-Path $repoRoot '.local' }

if (-not (Test-Path $EnvFile)) {
    throw @"
缺少本机实例定义：$EnvFile

这个文件不入库（仓库是公开的）。按下面的形状写一份：
`$NetbirdInstances = @(
    [pscustomobject]@{
        Name = '<instance>'; Kind = 'official-installer' | 'self-built-multi'
        Binary = '<netbird.exe 绝对路径>'; StateDir = '<状态目录>'
        ConfigPath = '<default.json 绝对路径>'
        ProfileDir = '<profiles\<名>，官方安装时留 $null>'
        DaemonAddr = 'npipe://...'
        SuggestedUrl = 'http://127.0.0.1:<port>'
        ExtraFiles = @('service.json', 'active_profile.json')
        Note = '<一句话说明>'
    }
)
本机现成的取值见 `.local\environment.md`。
"@
}

. $EnvFile
if (-not $NetbirdInstances) { throw "$EnvFile 没有定义 `$NetbirdInstances。" }

$sensitiveKeys = @('PrivateKey', 'SSHKey', 'PreSharedKey')
$redacted = '<redacted>'

function Read-JsonFile([string]$Path) {
    if (-not (Test-Path $Path)) { return $null }
    try { return (Get-Content -Raw -Encoding UTF8 $Path | ConvertFrom-Json) }
    catch { Write-Warning "解析失败：$Path — $($_.Exception.Message)"; return $null }
}

function ConvertTo-SanitizedJson($Object) {
    # 只处理顶层键：这些配置的敏感项都在顶层。
    if ($null -eq $Object) { return '{}' }
    $copy = [ordered]@{}
    foreach ($p in $Object.PSObject.Properties) {
        $copy[$p.Name] = if ($sensitiveKeys -contains $p.Name -and -not [string]::IsNullOrEmpty([string]$p.Value)) { $redacted } else { $p.Value }
    }
    return ($copy | ConvertTo-Json -Depth 8)
}

function Get-ManagementHost($Config) {
    if ($null -eq $Config -or $null -eq $Config.ManagementURL) { return $null }
    $m = $Config.ManagementURL
    if ($m -is [string]) { return $m }
    if ($m.Host) { return "$($m.Scheme)://$($m.Host)" }
    return $null
}

function Test-InstanceRunning([string]$DaemonAddr) {
    $procs = Get-CimInstance Win32_Process -Filter "Name='netbird.exe' OR Name='netbird-multi.exe'" -ErrorAction SilentlyContinue
    if (-not $procs) { return $false }
    foreach ($p in $procs) {
        if ($p.CommandLine -and $p.CommandLine.Contains($DaemonAddr)) { return $true }
    }
    return $false
}

$base   = Join-Path $OutRoot 'netbird'
$rawDir = Join-Path $base 'raw'
foreach ($d in @($OutRoot, $base, $rawDir)) {
    if (-not $WhatIf) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
}

$manifest = @()

foreach ($inst in $NetbirdInstances) {
    $cfg = Read-JsonFile $inst.ConfigPath
    if ($null -eq $cfg) {
        Write-Warning "$($inst.Name)：读不到 $($inst.ConfigPath) —— 跳过（该实例可能未安装或未初始化）"
        continue
    }

    $outDir = Join-Path $base $inst.Name
    if (-not $WhatIf) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

    # 1) 脱敏配置
    $sanitizedPath = Join-Path $outDir 'config.sanitized.json'
    $sanitizedText = ConvertTo-SanitizedJson $cfg
    if ($WhatIf) { "WhatIf：将写 $sanitizedPath（$($sanitizedText.Length) 字符）" }
    else { Set-Content -Path $sanitizedPath -Value $sanitizedText -Encoding utf8NoBOM }

    # 2) 附加文件（service.json / instance.json / daemon.ps1 …）
    foreach ($rel in $inst.ExtraFiles) {
        $root = if ($inst.ProfileDir) { $inst.ProfileDir } else { $inst.StateDir }
        $src = Join-Path $root $rel
        if (-not (Test-Path $src)) { Write-Warning "$($inst.Name)：缺少 $src"; continue }
        $dst = Join-Path $outDir (Split-Path $rel -Leaf)
        if ($WhatIf) { "WhatIf：将复制 $src -> $dst" }
        else { Copy-Item -Path $src -Destination $dst -Force }
    }

    # 3) 原始副本（含私钥），单独目录
    $rawPath = Join-Path $rawDir "$($inst.Name).json"
    if ($WhatIf) { "WhatIf：将复制原始配置 -> $rawPath" }
    else { Copy-Item -Path $inst.ConfigPath -Destination $rawPath -Force }

    # 4) 清单条目（不含任何敏感值）
    # 与台账口径对比：现值偏离期望时告警（不阻断，配置漂移本身就是快照要记录的事实）
    $actualManagement = Get-ManagementHost $cfg
    if ($inst.ExpectedManagementUrl -and $actualManagement -ne $inst.ExpectedManagementUrl) {
        Write-Warning "$($inst.Name)：management 与台账口径不一致 —— 现值 $actualManagement，台账 $($inst.ExpectedManagementUrl)"
    }

    $extra = if ($inst.ProfileDir) { Read-JsonFile (Join-Path $inst.ProfileDir 'instance.json') } else { $null }
    $manifest += [ordered]@{
        name             = $inst.Name
        kind             = $inst.Kind
        binary           = $inst.Binary
        configPath       = $inst.ConfigPath
        stateDir         = $inst.StateDir
        daemonAddr       = $inst.DaemonAddr
        suggestedBaseUrl = $inst.SuggestedUrl
        jsonSocket       = $null   # 目前都没开：需要 --enable-json-socket --json-socket tcp://…
        managementUrl    = (Get-ManagementHost $cfg)
        # 台账口径（`.local/env.ps1` 的 Expected* 字段）；不一致就是配置漂移，下面会告警。
        expectedManagementUrl = $inst.ExpectedManagementUrl
        managementUrlMatches  = if ($inst.ExpectedManagementUrl) { (Get-ManagementHost $cfg) -eq $inst.ExpectedManagementUrl } else { $null }
        expectedDomain        = $inst.ExpectedDomain
        expectedRange         = $inst.ExpectedRange
        interfaceName    = $cfg.WgIface
        wgPort           = $cfg.WgPort
        mtu              = $cfg.MTU
        hasPrivateKey    = -not [string]::IsNullOrEmpty([string]$cfg.PrivateKey)
        profileName      = if ($cfg.Name) { $cfg.Name } else { 'default' }
        fqdn             = $extra.fqdn
        domains          = $extra.domains
        running          = (Test-InstanceRunning $inst.DaemonAddr)
        note             = $inst.Note
    }
}

$doc = [ordered]@{
    generatedAt = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
    generatedBy = 'desktop-ui/scripts/sync-local-netbird.ps1'
    warning     = '本机快照，含路径与实例名；raw/ 还含私钥。.local 已被 .gitignore 忽略，绝不入库。'
    instances   = $manifest
}

$manifestPath = Join-Path $base 'instances.json'
if ($WhatIf) { "WhatIf：将写 $manifestPath" }
else { Set-Content -Path $manifestPath -Value ($doc | ConvertTo-Json -Depth 8) -Encoding utf8NoBOM }

# ── 收紧 raw/ 的 ACL（私钥副本）───────────────────────────────────────────
if (-not $WhatIf) {
    $me = "$env:USERDOMAIN\$env:USERNAME"
    # 全路径：Hermes 的 PATH 被裁剪，System32 不一定在 PATH 里（实测 icacls 找不到）
    $icacls = Join-Path $env:SystemRoot 'System32\icacls.exe'
    if (Test-Path $icacls) {
        & $icacls $rawDir /inheritance:r /grant:r "${me}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' | Out-Null
    } else {
        Write-Warning "找不到 icacls.exe，raw/ 的 ACL 未收紧 —— 请手工执行：icacls `"$rawDir`" /inheritance:r /grant:r `"${me}:(OI)(CI)F`""
    }
}

# ── 回读校验：脱敏副本里不得出现任何敏感原值 ──────────────────────────────
if (-not $WhatIf) {
    $fail = 0
    foreach ($inst in $NetbirdInstances) {
        $san = Join-Path $base "$($inst.Name)\config.sanitized.json"
        $raw = Join-Path $rawDir "$($inst.Name).json"
        if (-not (Test-Path $san) -or -not (Test-Path $raw)) { continue }
        $orig = Get-Content -Raw -Encoding UTF8 $raw | ConvertFrom-Json
        $text = Get-Content -Raw -Encoding UTF8 $san
        foreach ($k in $sensitiveKeys) {
            $v = [string]$orig.$k
            if ($v -and $text.Contains($v)) { Write-Warning "校验失败：$san 仍包含 $k 的原值"; $fail++ }
        }
    }
    if ($fail -eq 0) { '校验通过：脱敏副本不含任何敏感原值。' }
    "已写入：$manifestPath"
    "实例目录：$base"
    "原始副本（ACL 已收紧）：$rawDir"
}
