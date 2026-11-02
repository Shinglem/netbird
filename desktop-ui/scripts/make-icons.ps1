<#
.SYNOPSIS
    把 desktop-ui/icons/netbird-blue.svg 光栅化成 PNG（窗口/托盘用）与 ICO（jpackage 用）。

.DESCRIPTION
    本机没有 ImageMagick / Inkscape / rsvg，也不为一张图标引第三方依赖：
    光栅化在 `MakeIcons.java` 里用 JDK 自带的 Java2D 完成（只支持官方 mark 用到的
    M/L/H/V/C/Z 命令）。改颜色或形状只改 SVG，然后重跑本脚本。

    JDK 来源：`desktop-ui/local.properties` 的 `netbird.jdk.dir` → `JAVA_HOME` → PATH 上的 `java`。

.EXAMPLE
    pwsh -NoProfile -File desktop-ui\scripts\make-icons.ps1
    pwsh -NoProfile -File desktop-ui\scripts\make-icons.ps1 -Source icons\other.svg
#>
[CmdletBinding()]
param(
    [string]$Source,
    [string]$PngDir,
    [string]$IcoPath
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $scriptDir

if (-not $Source) { $Source = Join-Path $root 'icons\netbird-blue.svg' }
if (-not $PngDir) { $PngDir = Join-Path $root 'composeApp\src\desktopMain\resources\icons' }
if (-not $IcoPath) { $IcoPath = Join-Path $root 'composeApp\icons\netbird-blue.ico' }

function Find-Java {
    $localProperties = Join-Path $root 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $line = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^\s*netbird\.jdk\.dir\s*=' } |
            Select-Object -First 1
        if ($line) {
            $jdkHome = ($line -split '=', 2)[1].Trim().Replace('/', '\')
            $candidate = Join-Path $jdkHome 'bin\java.exe'
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw '找不到 java：在 desktop-ui/local.properties 里写 netbird.jdk.dir，或设置 JAVA_HOME'
}

$java = Find-Java
$javaSource = Join-Path $scriptDir 'MakeIcons.java'

$arguments = @(
    '-Djava.awt.headless=true'
    $javaSource
    (Resolve-Path -LiteralPath $Source).Path
    [IO.Path]::GetFullPath($PngDir)
    [IO.Path]::GetFullPath($IcoPath)
)
& $java @arguments

if ($LASTEXITCODE -ne 0) { throw "图标生成失败（exit $LASTEXITCODE）" }
