<#
.SYNOPSIS
    安装/移除 NetBird UI 的登录自启计划任务（以最高权限运行，登录时不弹 UAC）。

.DESCRIPTION
    daemon 要创建 wintun 网卡、改路由与 DNS，必须由管理员进程拉起。两条路径与 hermes 一致：

      * **双击启动器**：UI 自己用 ShellExecuteExW("runas") 重启自己，弹一次 UAC；
      * **登录自启**：本脚本装的计划任务，由 Task Scheduler 直接创建进程，不经过 consent.exe。

    不用 manifest 的 requireAdministrator 或注册表 Run 键：前者会让每次登录也走加载器提权
    （计划任务「不弹 UAC」的前提就没了），后者根本不提权。

    写任务需要管理员权限（实测非管理员连 /RL LIMITED 都是 Access denied），所以未提权时
    本脚本只报告状态，不尝试写系统。

    与 hermes 的差异：这里的任务只拉起**界面**，每个配置的 daemon 仍由界面自己作为子进程
    管辖（ProfileSupervisor），所以「关闭窗口 = 最小化到托盘、daemon 继续跑」成立。

    任务默认带 --silent（-Arguments 的默认值）：登录时只在托盘出现图标，不弹主窗口。
    漏掉这个参数就退回「开机弹窗」，界面里的 reconcileAutostart 会在下次启动时把旧任务重写。

.EXAMPLE
    # 开发态：登录后以管理员跑 gradlew :composeApp:run
    pwsh -NoProfile -File desktop-ui\scripts\install-autostart-task.ps1 `
        -Command "<repo>\desktop-ui\gradlew.bat" -Arguments ":composeApp:run" `
        -WorkingDirectory "<repo>\desktop-ui"

.EXAMPLE
    # 打包产物（release-test 布局）
    pwsh -NoProfile -File desktop-ui\scripts\install-autostart-task.ps1 `
        -Command "<repo>\release-test\netbird-multi-ui.exe"

.EXAMPLE
    pwsh -NoProfile -File desktop-ui\scripts\install-autostart-task.ps1 -Status
    pwsh -NoProfile -File desktop-ui\scripts\install-autostart-task.ps1 -Remove
#>
[CmdletBinding()]
param(
    [string]$TaskName = 'NetBirdUI',
    [string]$Command,
    # 默认只驻留托盘，不弹主窗口。开发态要覆盖它（例如 ':composeApp:run'）。
    [string]$Arguments = '--silent',
    [string]$WorkingDirectory,
    [switch]$Remove,
    [switch]$Status
)

$ErrorActionPreference = 'Stop'

function Test-Elevated {
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-CurrentUser {
    $domain = $env:USERDOMAIN
    if ([string]::IsNullOrWhiteSpace($domain)) { $domain = $env:COMPUTERNAME }
    return "$domain\$env:USERNAME"
}

function Get-TaskCommand {
    param([string]$Name)
    $xml = & schtasks.exe /Query /TN $Name /XML 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    $text = $xml -join "`n"
    $match = [regex]::Match($text, '<Command>(.*?)</Command>', 'Singleline')
    if (-not $match.Success) { return $null }
    return [System.Net.WebUtility]::HtmlDecode($match.Groups[1].Value.Trim())
}

# 字段取值理由：
#   RunLevel=HighestAvailable（不是 RequireHighest，否则非管理员用户直接失败）
#   LogonType=InteractiveToken（S4U 会落到没有 UI 的会话，托盘图标加不上）
#   ExecutionTimeLimit=PT0S（默认 PT72H 会到点强杀界面，连带 daemon）
#   Delay=PT10S（登录瞬间托盘区可能还没就绪）
function New-TaskXml {
    param([string]$Name, [string]$Exe, [string]$ExeArguments, [string]$WorkDir)

    $user = Get-CurrentUser
    $escape = {
        param($value)
        $value.Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;').Replace('"', '&quot;')
    }
    $exeEscaped = & $escape $Exe
    $argsEscaped = & $escape $ExeArguments
    $dirEscaped = & $escape $WorkDir

    return @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>NetBird UI · 登录时以最高权限启动（无 UAC）；每个配置的 daemon 由界面管辖</Description>
    <URI>\$Name</URI>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <UserId>$user</UserId>
      <Delay>PT10S</Delay>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>$user</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>HighestAvailable</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>$exeEscaped</Command>
      <Arguments>$argsEscaped</Arguments>
      <WorkingDirectory>$dirEscaped</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"@
}

$elevated = Test-Elevated
$current = Get-TaskCommand -Name $TaskName

if ($Status -or ($Remove -and -not $elevated)) {
    if ($null -eq $current) {
        Write-Host "任务 $TaskName：未安装"
    } else {
        Write-Host "任务 $TaskName：已安装 → $current"
    }
    Write-Host "当前进程是管理员：$elevated"
    exit 0
}

if (-not $elevated) {
    Write-Warning "写计划任务需要管理员权限。请用管理员 PowerShell 重新运行本脚本（当前不是管理员）。"
    exit 1
}

if ($Remove) {
    & schtasks.exe /Delete /TN $TaskName /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "删除任务失败（exit $LASTEXITCODE）" }
    Write-Host "已删除任务 $TaskName"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Command)) {
    throw "必须给出 -Command（打包后是 NetBird UI.exe；开发态是 gradlew.bat）。当前任务：$current"
}

if (-not (Test-Path -LiteralPath $Command)) {
    throw "找不到可执行文件：$Command"
}

$workDir = if ([string]::IsNullOrWhiteSpace($WorkingDirectory)) {
    Split-Path -Parent (Resolve-Path -LiteralPath $Command).Path
} else {
    (Resolve-Path -LiteralPath $WorkingDirectory).Path
}

$xml = New-TaskXml -Name $TaskName -Exe (Resolve-Path -LiteralPath $Command).Path -ExeArguments $Arguments -WorkDir $workDir
$temp = Join-Path $env:TEMP "netbird-ui-task.xml"
# schtasks /Create /XML 对编码敏感：必须 UTF-16LE **带 BOM**，UTF-8 会报"任务 XML 包含错误格式"。
[IO.File]::WriteAllBytes($temp, ([byte[]](0xFF, 0xFE) + [Text.Encoding]::Unicode.GetBytes($xml)))

try {
    & schtasks.exe /Create /TN $TaskName /XML $temp /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "创建任务失败（exit $LASTEXITCODE）" }
} finally {
    Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue
}

$installed = Get-TaskCommand -Name $TaskName
Write-Host "已安装任务 $TaskName → $installed"
Write-Host "下次登录会以最高权限启动，不弹 UAC。立即试运行：schtasks /Run /TN $TaskName"
