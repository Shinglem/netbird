<#
.SYNOPSIS
    Append the fork-local section to AGENTS.md, idempotently.

.DESCRIPTION
    AGENTS.md is a protected agent-instruction file: automated tooling refuses to
    edit it, which is why the text lives in patchdoc/agents-md-addendum.md and
    this script applies it instead. Run it once from your own session.

    The script is safe to re-run: if the section is already present it reports so
    and changes nothing. It backs AGENTS.md up before writing and reads the file
    back afterwards to prove the write landed.

.PARAMETER RepoRoot
    Repository root. Defaults to the script's parent directory.

.PARAMETER WhatIf
    Show what would change without writing.

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

# The heading in the addendum doubles as the idempotency marker.
$marker   = '## Fork-local changes (this repository)'
$tocEntry = '- [Fork-local changes (this repository)](#fork-local-changes-this-repository)'
$tocAfter = '- [Discussion and support](#discussion-and-support)'

foreach ($p in @($agents, $addendum)) {
    if (-not (Test-Path $p)) { throw "not found: $p" }
}

$text = [IO.File]::ReadAllText($agents)

if ($text.Contains($marker)) {
    "AGENTS.md already contains the fork-local section -- nothing to do."
    return
}

$section = [IO.File]::ReadAllText($addendum).TrimEnd()

# Prefer inserting the contents entry next to its sibling; fall back to appending.
if ($text.Contains($tocAfter) -and -not $text.Contains($tocEntry)) {
    $text = $text.Replace($tocAfter, $tocAfter + "`n" + $tocEntry)
    "contents: added the new entry after 'Discussion and support'"
} else {
    Write-Warning "could not place the contents entry (anchor '$tocAfter' not found) -- leaving the contents list alone"
}

$text = $text.TrimEnd() + "`n`n" + $section + "`n"

if ($WhatIf) {
    "WhatIf: would back up to AGENTS.md.bak and write $($text.Length) characters."
    return
}

$backup = "$agents.bak"
Copy-Item $agents $backup -Force
"backup: $backup"

[IO.File]::WriteAllText($agents, $text, [Text.UTF8Encoding]::new($false))
"written: $agents"

# Read back rather than trusting the write call: this is the only proof the file
# on disk carries the section, and it catches an encoding surprise.
$check = [IO.File]::ReadAllText($agents)
if ($check.Contains($marker) -and $check.Contains($tocEntry)) {
    "verified: section and contents entry are both present on disk."
    ""
    "--- tail of AGENTS.md ---"
    ($check -split "`n" | Select-Object -Last 6) -join "`n"
} elseif ($check.Contains($marker)) {
    "verified: section present; the contents entry is missing -- add it by hand."
} else {
    throw "verification failed: the section is not in the file on disk"
}
