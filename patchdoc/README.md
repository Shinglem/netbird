# Fork-local patches

This fork (`Shinglem/netbird`) carries a small, opt-in patch set on top of
upstream `netbirdio/netbird`. It exists so that **more than one NetBird instance
can run on a single Windows host at the same time**, each with its own wintun
adapter, routing table entries, DNS namespace and state directory.

Upstream supports *switching* between profiles, not running them side by side: a
single process owns one wintun adapter GUID and one NRPT key namespace, and both
are fixed when the daemon starts. This fork makes those two process-wide settings
per-profile instead.

## Documents

| Document | Content |
|---|---|
| [`0001-profile-driven-instance-identity.md`](0001-profile-driven-instance-identity.md) | The three code changes: what was fixed, where, and why each location was chosen |
| [`0002-nrpt-prefix-timing.md`](0002-nrpt-prefix-timing.md) | **Read this before deploying.** A startup-order defect that makes the environment variable mandatory rather than optional |
| [`agents-md-addendum.md`](agents-md-addendum.md) | The fork-local section for `AGENTS.md` (written in Chinese), kept out of line because that file is protected |
| [`apply-agents-md.ps1`](apply-agents-md.ps1) | Idempotent, verified script that appends the above to `AGENTS.md` |

## Applying the AGENTS.md addendum

`AGENTS.md` is a protected agent-instruction file — tooling refuses to write it,
by design, so that an agent cannot edit the rules it runs under. The section
therefore lives in `agents-md-addendum.md` and is applied by a script you run
yourself:

```powershell
pwsh -NoProfile -File patchdoc\apply-agents-md.ps1 -WhatIf   # preview
pwsh -NoProfile -File patchdoc\apply-agents-md.ps1           # apply
```

It backs up `AGENTS.md` to `AGENTS.md.bak`, adds both the section and its table-of-
contents entry, then reads the file back to confirm. Re-running it is a no-op.

The appended section's prose is Chinese while `AGENTS.md` itself is English. That
is deliberate: the file is the agent-facing rulebook for this repository and its
maintainer reads Chinese, while the surrounding upstream guidance stays untouched
so rebases onto upstream stay clean.

> **This document's own prose is English, unlike `.ai/skill/`.** `patchdoc/`
> describes code and is written to be quotable in an upstream issue or PR
> discussion; `.ai/skill/` is operating knowledge for whoever deploys this build.

## What changes for a user

Nothing, unless a config opts in. Both new fields are `omitempty` and an empty
value leaves upstream behaviour byte-for-byte identical, so an existing
`default.json` is unaffected and the same binary still works as an ordinary
client.

To use it, a profile gains two fields:

```json
{
  "WgIface": "wt-ordia",
  "WintunGUID": "{792ff245-31dd-4291-ac65-604a6413488d}",
  "NRPTKeyPrefix": "NBAlt-ordia-"
}
```

With those set, **one config file is a complete instance description**, and:

```powershell
netbird-multi.exe service run --config <instance>\config\default.json `
    --daemon-addr npipe://netbird-<name> --log-file <instance>\runfiles\daemon.log
```

starts that instance.

## Mandatory: export `NB_NRPT_KEY_PREFIX` as well

Setting `NRPTKeyPrefix` in the config is **not sufficient**. The value is
published from `readConfig`, and at least one code path removes NRPT rules
*before* the config is read — at which point the prefix is still the built-in
`NetBird-`, and that cleanup deletes another instance's rules.

```powershell
$env:NB_STATE_DIR       = '<instance>\runfiles'
$env:NB_NRPT_KEY_PREFIX = 'NBAlt-<name>-'      # required, not a fallback
```

Full explanation and the reproduction: [`0002-nrpt-prefix-timing.md`](0002-nrpt-prefix-timing.md).

## Verification

```bash
# Build (the client module, matching upstream's goreleaser config)
cd client && CGO_ENABLED=0 go build .
```

```bash
go fmt ./...
go vet ./client/internal/profilemanager/... ./client/internal/dns/... ./client/iface/device/...
# cross-compile, to confirm the non-Windows stubs keep other platforms building
GOOS=linux   GOARCH=amd64 CGO_ENABLED=0 go build ./client/...
GOOS=darwin  GOARCH=arm64 CGO_ENABLED=0 go build ./client/...
```

On Windows, after starting two instances with different prefixes, the acceptance
checks are:

```powershell
# two adapters, two NRPT namespaces, both meshes resolving
Get-NetAdapter -IncludeHidden | Where-Object InterfaceDescription -match WireGuard
Get-DnsClientNrptRule | Where-Object { $_.Name -like 'NetBird-*' -or $_.Name -like 'NBAlt-*' }
Resolve-DnsName <primary-mesh-name>; Resolve-DnsName <second-mesh-name>
```

And the regression that matters most — force-kill the second instance, restart
it, and confirm the first instance's rules survived:

```powershell
# after the restart, the log shows the recovery path ran...
#   INFO statemanager: cleaning up state dns_state
# ...and this must still list both keys:
Get-DnsClientNrptRule | Where-Object Name -like 'NetBird-*'
```

## Rebase notes

`git remote add upstream https://github.com/netbirdio/netbird.git`,
`git fetch upstream`, then `git rebase upstream/main`. The change is four files
and ~140 lines, so conflicts are unlikely and would land in:

- `client/internal/profilemanager/config.go` — the `Config` field block and the
  import block
- `client/internal/dns/host_windows.go` — the `const` to `var` conversion of the
  prefix and its derived paths
