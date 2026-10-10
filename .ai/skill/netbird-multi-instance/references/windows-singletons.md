# The Windows integration points

Source-level detail for the three places where two NetBird instances would
otherwise collide, plus the one that only *looks* like a collision. Line numbers
are from the tree this fork is based on and will drift.

## 1. Wintun adapter GUID

`client/iface/device/device_windows.go`:

```go
const defaultWindowsGUIDSTring = "{f2f29e61-d91f-4d76-8151-119b20c4bdeb}"

func getGUID() (windows.GUID, error) {
	guidString := defaultWindowsGUIDSTring
	if CustomWindowsGUIDString != "" {
		guidString = CustomWindowsGUIDString
	}
	return windows.GUIDFromString(guidString)
}
```

`CustomWindowsGUIDString` is declared in `windows_guid.go` as

```go
// CustomWindowsGUIDString is a custom GUID string for the interface
var CustomWindowsGUIDString string
```

Upstream never assigns it outside tests, so every process claims the same adapter.
This fork adds `SetWintunGUID`, called from the profile config.

The file has no build tag, which matters: the variable is only *read* by
`device_windows.go`, but a build-tagged setter would push a matching build tag
onto every caller. Defining the setter everywhere keeps `profilemanager`
platform-agnostic, at the cost of a no-op function on Linux and macOS.

## 2. NRPT key namespace

`client/internal/dns/host_windows.go`. Every rule this client writes is named from
one prefix, and cleanup enumerates the machine-wide policy store by that prefix:

```go
func listNRPTRuleKeys(root string) ([]string, error) {
	...
	for _, name := range names {
		// registry key names are case insensitive
		if strings.HasPrefix(strings.ToLower(name), strings.ToLower(NRPTKeyPrefix)) {
			ruleKeys = append(ruleKeys, name)
		}
	}
	return ruleKeys, nil
}
```

`removeDNSMatchPolicies` then deletes everything that function returned. The
filter is correct and precise — which is exactly why the prefix must be right
*before* the first cleanup runs. Two callers reach it:

- `applyDNSConfig` — clearing the previous apply before installing fresh rules
- `restoreUncleanShutdownDNS` — the recovery path, which runs **before the profile
  config is read** (see `../../patchdoc/0002-nrpt-prefix-timing.md`)

Five values derive from the prefix and must all be recomputed together when it
changes: `NRPTKeyPrefix`, `nrptMatchKeyName`, and the match and ExemptLocal paths
in both the local and GPO policy stores.

## 3. `NB_STATE_DIR` — one variable, three jobs

`client/internal/profilemanager/service.go`:

```go
if stateDir := os.Getenv("NB_STATE_DIR"); stateDir != "" {
	DefaultConfigPathDir = stateDir
	...
}
ActiveProfileStatePath = filepath.Join(DefaultConfigPathDir, "active_profile.json")
```

So `NB_STATE_DIR` decides the **profile store**, `active_profile.json` **and**
`state.json`. That is convenient — one variable isolates all persistence — but it
means a second instance pointing at the same value shares profile storage, not
just state. Give each instance its own directory.

`NB_STATE_DIR` cannot be a config field: it determines *which config is read*, so
it must be known before the config exists. The same is true of any other setting
consumed before `readConfig`.

## 4. What looks like a collision but is not: the daemon address

Two daemons on `127.0.0.1:<port>` would conflict, but the Windows daemon does not
use TCP. It serves a named pipe:

```
\\.\pipe\ProtectedPrefix\Administrators\netbird-<name>
```

The pipe name is derived from `--daemon-addr npipe://netbird-<name>`, so distinct
names isolate cleanly with no port allocation. Do not "fix" a pipe setup by
moving to loopback TCP — the pipe carries the caller token that privileged
operations are gated on, and loopback TCP carries no caller identity.

## 5. Interaction worth knowing: an instance adapter appearing or disappearing

The primary instance's route manager reacts to interface churn by tearing down and
re-initialising routing and DNS. That is upstream behaviour, and it is why the
primary's NRPT rules momentarily vanish when a second instance starts or stops.
It recovers on its own; measured windows were 13 s, 16 s and 18 s.

The one case where it does *not* recover cleanly from the application's point of
view is when the cleanup at the start of that path is scoped by the wrong prefix —
which is the timing bug in `../../patchdoc/0002-nrpt-prefix-timing.md`, not a
routing problem.

Do not try to prevent the churn by keeping adapters alive. The rebuild is
harmless once the prefix is right.
