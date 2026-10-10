# 0002 — The NRPT prefix must be exported, not just configured

**Severity: high.** Deploying with only the config field makes a second instance
delete the *first* instance's DNS rules. Read this before running two instances.

## Symptom

Start a second instance, and the first one — which was healthy and stays
`Connected` — loses name resolution. Its NRPT rules are gone:

```powershell
Get-DnsClientNrptRule | Where-Object Name -like 'NetBird-*'
# (empty)
```

The first instance's *own* log shows nothing at all: no cleanup, no routing
teardown, no DNS event. It did not delete its own rules. The second instance did.

## Root cause: a startup ordering problem

`NRPTKeyPrefix` from the profile config is published by
`profilemanager.readConfig`. That is too late for at least one code path that runs
earlier.

When a daemon did not shut down cleanly — a force-kill, a crash, a power loss —
`state.json` still holds a `dns_state` entry. On the next start the state manager
performs recovery **before the profile config is read**:

```
daemon start
  |
  +-- statemanager loads state.json
  |     dns_state present (left by the unclean shutdown)
  |     -> dns.ShutdownState.Cleanup()
  |        -> registryConfigurator.restoreUncleanShutdownDNS()
  |           -> removeDNSMatchPolicies()
  |
  |     *** this deletes every NRPT key whose name starts with NRPTKeyPrefix ***
  |     *** NRPTKeyPrefix is still the default "NetBird-" here                  ***
  |     *** so it deletes the OTHER instance's keys                             ***
  |
  +-- readConfig
        -> publishInstanceIdentity()
           -> dns.SetNRPTKeyPrefix("NBAlt-ordia-")   <-- far too late
```

The deletion itself is correct and correctly scoped — `listNRPTRuleKeys` filters
by prefix:

```go
if strings.HasPrefix(strings.ToLower(name), strings.ToLower(NRPTKeyPrefix)) {
	ruleKeys = append(ruleKeys, name)
}
```

The defect is only that *the prefix has not been set yet at that moment*.

## The fix: export the variable

`host_windows.go` reads `NB_NRPT_KEY_PREFIX` in `init()`, which runs before
anything that could call the recovery path. So both the config field (for the
runtime paths) and the environment variable (for the early paths) must be set:

```powershell
$env:NB_STATE_DIR       = '<instance>\runfiles'
$env:NB_NRPT_KEY_PREFIX = 'NBAlt-<name>-'      # required, not a fallback
& '<root>\netbird-multi.exe' service run `
    --config '<instance>\config\default.json' `
    --daemon-addr 'npipe://netbird-<name>' `
    --log-file '<instance>\runfiles\daemon.log'
```

This makes the commit message's original claim — that the environment variables
are "still honoured as a fallback" — misleading. They are not a fallback. For
paths that precede `readConfig` they are the only channel that works.

## Reproduction

```powershell
# 1. both instances healthy, both keys present
Get-DnsClientNrptRule | Where-Object Name -like 'NetBird-*'    # first
Get-DnsClientNrptRule | Where-Object Name -like 'NBAlt-*'      # second

# 2. force-kill the second instance, so it cannot clean up and leaves dns_state
Stop-ScheduledTask -TaskName 'NetBird-Instance-<name>'
Get-CimInstance Win32_Process | Where-Object Name -eq 'netbird-multi.exe' |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# 3. start it again
Start-ScheduledTask -TaskName 'NetBird-Instance-<name>'
```

Then check whether the first instance's keys survived.

With only the config field, the second instance's log shows the recovery running
and the first instance's keys are gone:

```
INFO client/internal/statemanager/manager.go:420: cleaning up state dns_state
```

With the environment variable exported, the same line appears — the path still
runs — but the keys survive, because the deletion is now scoped to
`NBAlt-<name>-`.

## Why the config field alone cannot fix it

The obvious alternative — making the recovery path read the prefix later — does
not work. The recovery path has to run before `readConfig`, because `readConfig`
decides which profile is active and the recovery has to clean up whatever the
*previous* run left behind. Deferring it would mean recovering state after the
profile it belongs to has already been selected and partially applied.

The generalisable rule: **when process-wide settings are published from
`readConfig`, enumerate every path that runs before it.** Package `init()`,
state-manager cleanup and unclean-shutdown recovery all precede it, and for those
only a variable present at process start is early enough.

## Regression test

This belongs in any deployment checklist, because a graceful shutdown hides the
bug entirely — `down` removes the rules itself, so no stale `dns_state` is left:

```powershell
# both keys present
# force-kill the second instance
# restart it
# assert: the log shows "cleaning up state dns_state"
# assert: Get-DnsClientNrptRule still lists BOTH namespaces
# assert: both mesh names still resolve
```
