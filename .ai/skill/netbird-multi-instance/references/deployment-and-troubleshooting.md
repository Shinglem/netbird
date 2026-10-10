# Deployment and troubleshooting

## Deploying a second instance

```powershell
$root = 'C:\app\multinetbird'
$name = 'ordia'

# 1. layout
New-Item -ItemType Directory -Force -Path `
    "$root\profiles\$name\config", "$root\profiles\$name\runfiles" | Out-Null

# 2. binary + wintun.dll together (the dll must be next to the exe)
Copy-Item <built>\netbird.exe "$root\netbird-multi.exe"
Copy-Item <official-install>\wintun.dll "$root\wintun.dll"

# 3. config: clone an existing profile, then change identity fields.
#    Edit text, never round-trip through ConvertFrom-Json -- ManagementURL is a
#    url.URL whose structured form does not survive, and PrivateKey must stay
#    byte-identical.
#      WgIface        -> wt-<name>
#      WgPort         -> a port unused by the other instances
#      WintunGUID     -> a fresh GUID, unique on this machine
#      NRPTKeyPrefix  -> NBAlt-<name>-
#      DisableDNS     -> false      (the instance programs its own DNS)
#    Write with UTF8Encoding($false): no BOM.
```

Then create the launcher and a scheduled task (SYSTEM, `RunLevel Highest`, no
trigger, so it starts on demand only — a laptop does not want a tunnel it is not
using). The launcher sets the two environment variables from the skill body.

## Symptom to cause

| Symptom | Most likely cause |
|---|---|
| `Error loading wintun.dll` | The dll is not in the same directory as the exe |
| `create wintun interface: GUID already exists` / second adapter never appears | Two instances have the same `WintunGUID` |
| Second instance connects, first instance stops resolving names | `NB_NRPT_KEY_PREFIX` not exported — see `../../patchdoc/0002-nrpt-prefix-timing.md` |
| `create tun device: ... cannot find the file specified` | Wintun PnP device left bad by a force-kill; remove that instance's device and restart |
| `invalid character '\ufeff' looking for beginning of value` | The config JSON was written with a BOM |
| Primary's DNS drops for ~15 s whenever an instance starts/stops | Expected upstream rebuild on interface churn; poll before concluding it is broken |
| Primary rebuilds every 1–2 minutes while an instance runs | The instance tree is under `%PROGRAMDATA%\Netbird`; move it out |
| Instance connects but the OS cannot resolve mesh names | NRPT prefix mismatch, or `DisableDNS: true` left over from a different mode |
| `Interface type: Userspace` but the adapter exists and routes work | Cosmetic: a management-reported flag, not a local capability |

## Log forensics

Two logs matter, and they answer different questions.

**The instance's own log** (`runfiles\daemon.log`) says what the instance did:

```bash
grep -E "cleaning up state dns_state|added [0-9]+ NRPT|Routing (cleanup|setup) complete" daemon.log
```

`cleaning up state dns_state` means the unclean-shutdown recovery ran — which is
the path that deletes by prefix. If another instance lost its rules at the same
timestamp, this is the culprit.

**The primary's log** (`%PROGRAMDATA%\Netbird\client.log`) says whether the
primary was disturbed. Baseline the line count, act, then diff:

```bash
BEFORE=$(wc -l < /c/ProgramData/Netbird/client.log)
# ... start or stop an instance ...
sed -n "$((BEFORE+1)),\$p" /c/ProgramData/Netbird/client.log |
    grep -E "cleanupConnection|service is down|Routing (cleanup|setup) complete|active profile config existed"
```

Anything there means the primary rebuilt. The marker line is
`client/server/server.go: active profile config existed: true`, which sits inside
`cleanupConnection()` — reachable only from `Server.Down()` or a profile logout.
If you did not issue either, look for what made the daemon take that path.

**Timestamps are the whole argument.** A conclusion of the form "the primary
broke the DNS" is only valid if the primary's log has an event at that moment;
"the second instance broke the DNS" is only valid if the second instance's log
does. Compare the two before acting.

## Distinguishing the two failure modes

Both look identical from outside — names stop resolving, `netbird status` still
says `Connected` — but they need different responses:

| | Rebuild in progress | Prefix deleted the rules |
|---|---|---|
| Duration | Recovers in 13–60 s | Permanent until restarted |
| Primary's log | Has cleanup/setup events | Silent |
| Second instance's log | Nothing relevant | `cleaning up state dns_state` |
| Fix | Wait | Export `NB_NRPT_KEY_PREFIX`, then restart the instance |

Poll for up to a minute before treating a missing key as a fault. A single check
immediately after an instance starts will report a healthy setup as broken.

## Removing an instance

```powershell
# stop it (kills the daemon the task spawned, not just the task's pwsh)
Stop-ScheduledTask -TaskName 'NetBird-Instance-<name>'
Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'netbird*' -and $_.CommandLine -like '*npipe://netbird-<name>*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# its own DNS rules go with it; if it was force-killed, remove them by prefix
Get-DnsClientNrptRule | Where-Object Name -like 'NBAlt-<name>-*' |
    ForEach-Object { Remove-DnsClientNrptRule -Name $_.Name -Force }

# then the task, and optionally the instance directory
Unregister-ScheduledTask -TaskName 'NetBird-Instance-<name>' -Confirm:$false
```

Match the process on **both** `Name` and `CommandLine`. Filtering on the command
line alone also matches the shell running the cleanup, which then kills itself.

Leave the wintun PnP device alone unless the next start fails. It is reusable, and
the device class is shared with unrelated software (see the SKILL's pitfalls).
