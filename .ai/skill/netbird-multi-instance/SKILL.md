---
name: netbird-multi-instance
description: Run or debug several NetBird instances on one Windows host at the same time (separate overlay networks).
version: 1.0.0
metadata:
  tags: [netbird, windows, multi-instance, wintun, nrpt, daemon]
  related: [patchdoc/0001-profile-driven-instance-identity.md, patchdoc/0002-nrpt-prefix-timing.md]
---

# Running multiple NetBird instances on one Windows host

## When to use

- Two or more overlay networks must be reachable from one Windows machine **at
  the same time**, each with working system-level name resolution.
- An existing multi-instance setup stopped resolving names after an instance was
  restarted.
- You are deciding whether to switch profiles or run instances side by side.

**Not** the scenario: connecting to one network and occasionally switching
accounts. `netbird profile` switching already does that and needs none of this.

## The constraint that shapes everything

Upstream NetBird is a single-instance daemon by construction. Two pieces of state
are process-wide and fixed at startup, and both are contended by a second
instance:

| Singleton | Where | Symptom when two instances share it |
|---|---|---|
| wintun adapter GUID | `client/iface/device/device_windows.go`, hard-coded `{f2f29e61-...}` | Second interface cannot be created |
| NRPT key prefix | `client/internal/dns/host_windows.go`, `NRPTKeyPrefix = "NetBird-"` | One instance's shutdown deletes the other's DNS rules |

Everything else isolates with ordinary flags: `--config`, `--daemon-addr`,
`--log-file` and `NB_STATE_DIR`. See
[`references/windows-singletons.md`](references/windows-singletons.md) for the
mechanism of each and for the `NB_STATE_DIR` detail (one variable also decides
the profile directory, not just `state.json`).

This fork makes the two singletons per-profile; see
[`../../../patchdoc/0001-profile-driven-instance-identity.md`](../../../patchdoc/0001-profile-driven-instance-identity.md).

## Layout that works

```
<root>\
  |- netbird-multi.exe          one binary serves every instance
  |- wintun.dll                 MUST sit next to the exe
  |- profiles\
       |- <name>\
            |- daemon.ps1        the scheduled task points here
            |- instance.json     script metadata
            |- config\default.json      --config
            |- runfiles\{state.json, active_profile.json, daemon.log}
```

Two hard requirements:

1. **`wintun.dll` next to the exe.** A copy elsewhere fails with
   `Error loading wintun.dll`.
2. **The instance tree must not sit under `%PROGRAMDATA%\Netbird`.** The primary
   instance's MDM policy-reload ticker watches that tree; an instance writing its
   state inside it looks like a policy change, and the primary reacts by tearing
   down and rebuilding its engine — DNS and routing gone for 13–60+ s, repeating
   every minute or two.

## Starting an instance

```powershell
$env:NB_STATE_DIR       = '<root>\profiles\<name>\runfiles'
$env:NB_NRPT_KEY_PREFIX = 'NBAlt-<name>-'      # REQUIRED — see below
& '<root>\netbird-multi.exe' service run `
    --config '<root>\profiles\<name>\config\default.json' `
    --daemon-addr 'npipe://netbird-<name>' `
    --log-file '<root>\profiles\<name>\runfiles\daemon.log'
```

`config\default.json` must carry:

```json
{ "WgIface": "wt-<name>", "WintunGUID": "<unique guid>", "NRPTKeyPrefix": "NBAlt-<name>-", "DisableDNS": false }
```

Plus, in the profile: a distinct `WgPort` per instance, and a management server
whose **DNS domain differs** from the other instances' — two networks with the
same domain name will fight inside the client resolver.

## ⚠️ Export `NB_NRPT_KEY_PREFIX`, do not rely on the config field

Setting it only in `default.json` **will delete the other instance's NRPT rules**.
The config value is published from `readConfig`; the unclean-shutdown recovery
path runs before that, when the prefix is still the built-in `NetBird-`.

The full mechanism, the reproduction and why the config field alone cannot fix it:
[`../../../patchdoc/0002-nrpt-prefix-timing.md`](../../../patchdoc/0002-nrpt-prefix-timing.md).
Read it before deploying — the failure is silent and looks like the *other*
instance breaking.

## Verify

```powershell
# both adapters up
Get-NetAdapter -IncludeHidden | Where-Object InterfaceDescription -match WireGuard

# one NRPT namespace per instance, each pointing at its own in-tunnel resolver (<mesh>/16 -> x.y.255.254)
Get-DnsClientNrptRule | Where-Object { $_.Name -like 'NetBird-*' -or $_.Name -like 'NBAlt-*' }

# both networks resolve, from the OS — not from `netbird status`
Resolve-DnsName <primary-mesh-name>
Resolve-DnsName <second-mesh-name>
```

`netbird status` is not sufficient evidence: it reports the daemon's view, and
will happily show `Connected` while the OS cannot resolve anything.

Do not judge DNS from the `Nameservers: n/n` counter either — it counts
netbird-managed nameservers and reads `0/0` even when Custom Zones are working.
The only proof is an actual lookup.

## Pitfalls

- **A missing NRPT key right after an instance start/stop may be a rebuild in
  progress, not a fault.** Bringing an instance up or down churns its adapter, and
  the primary re-registers its rules 13–18 s later (measured). Poll for up to a
  minute before concluding anything is broken; restarting the service on a single
  check turns a short self-healing gap into a longer outage.
- **`netbird status` showing `Interface type: Userspace` does not mean the kernel
  interface is missing.** It reflects a `usesKernelInterface` value reported by the
  management server. Check for the adapter, the routes and a working lookup
  instead.
- **Never delete a wintun PnP device because "no adapter matches it".** An
  inactive device has no adapter by design, and the class lists every program that
  uses wintun — on one machine that included Tailscale's adapter, whose GUID
  `{37217669-...}` appears verbatim inside `tailscaled.exe`. Identify ownership
  from `FriendlyName` plus a GUID search in the candidate binaries. Removal is
  `pnputil /remove-device "SWD\WINTUN\{...}"`; `Remove-PnpDevice` does not exist
  on every Windows build.
- **A force-kill leaves an adapter in a bad state.** The next start fails with
  `create tun device: Error creating interface: The system cannot find the file
  specified`. Allowed-by-design fix: remove that instance's PnP device (after
  confirming the GUID is its own) and start again.
- **The config JSON must have no BOM.** The daemon fails with
  `invalid character '\ufeff' looking for beginning of value`. PowerShell's
  `Set-Content -Encoding UTF8` and Python's `utf-8-sig` both add one; write bytes
  with `UTF8Encoding($false)`.
- **`--daemon-addr npipe://...`, never loopback TCP.** The named pipe carries the
  caller identity Windows needs; loopback TCP does not, and privileged calls are
  refused over it.

Deeper debugging recipes, including how to tell which instance deleted a rule:
[`references/deployment-and-troubleshooting.md`](references/deployment-and-troubleshooting.md).
