# 0001 — Profile-driven instance identity

Commit: `feat(windows): drive wintun GUID and NRPT key prefix from the profile config`

Four files, +142/−16 lines. Makes two process-wide Windows settings per-profile.

## The problem

Running two NetBird instances on one Windows host fails for two independent
reasons, both caused by state that upstream fixes per *process* while the second
instance is a second process:

1. **Wintun adapter GUID.** `client/iface/device/device_windows.go` requests a
   hard-coded GUID (`{f2f29e61-...}`). Two processes asking for the same GUID
   contend for one adapter, so the second interface cannot come up.

2. **NRPT key namespace.** Every rule this client writes is named
   `NRPTKeyPrefix + ...`, and cleanup enumerates the policy store by that prefix.
   With one shared prefix, the second instance's shutdown removes the first
   instance's DNS rules, and the first loses name resolution mid-session.

## The changes

### `client/iface/device/windows_guid.go`

Upstream already declared the extension point:

```go
// CustomWindowsGUIDString is a custom GUID string for the interface
var CustomWindowsGUIDString string
```

but no production code ever assigned it — only tests did. So instead of
inventing a new mechanism, this adds a setter:

```go
func SetWintunGUID(guid string) {
	if guid == "" {
		return
	}
	CustomWindowsGUIDString = guid
}
```

An `init()` reads `NB_WINDOWS_GUID` as the environment fallback.

**No build tag on this file, deliberately.** The variable it writes is only read
by `device_windows.go`, but a build-tagged setter would force every caller to
carry a matching tag. Defining it everywhere keeps the call site in
`profilemanager` platform-agnostic.

### `client/internal/dns/host_windows.go`

The prefix and everything derived from it move from `const` to `var`:

```go
const defaultNRPTKeyPrefix = "NetBird-"   // upstream's value, still the default

var (
	NRPTKeyPrefix    = defaultNRPTKeyPrefix
	nrptMatchKeyName = NRPTKeyPrefix + "Match"

	dnsPolicyConfigMatchPath    = DNSPolicyConfigRoot + `\` + nrptMatchKeyName
	gpoDnsPolicyConfigMatchPath = GPODNSPolicyConfigRoot + `\` + nrptMatchKeyName
	dnsPolicyConfigExemptLocalPath    = DNSPolicyConfigRoot + `\` + NRPTKeyPrefix + `ExemptLocal`
	gpoDnsPolicyConfigExemptLocalPath = GPODNSPolicyConfigRoot + `\` + NRPTKeyPrefix + `ExemptLocal`
)
```

plus `SetNRPTKeyPrefix` / `setNRPTKeyPrefix`, which recompute all five derived
values, and an `init()` reading `NB_NRPT_KEY_PREFIX`.

`NRPTKeyPrefix` keeps its exported name because `debug_windows.go` reads it (as a
value at run time, not in a const expression), so changing it to a `var` does not
break compilation.

⚠️ **The `const` to `var` conversion is the risky part of this patch.** Nothing
guarantees those derived paths are only read after `init()` has run. If a future
change reads them during another package's `init()`, the value seen depends on
package initialisation order.

### `client/internal/dns/nrpt_prefix_other.go` (new)

```go
//go:build !windows
func SetNRPTKeyPrefix(string) {}
```

NRPT is Windows-only, so there is nothing to namespace elsewhere. The stub exists
so the profile-config loader can call the setter without a build tag.

### `client/internal/profilemanager/config.go`

`Config` gains two fields:

```go
// WintunGUID ... Windows only, ignored elsewhere. Empty keeps the built-in default...
WintunGUID string `json:",omitempty"`

// NRPTKeyPrefix ... Empty keeps upstream's "NetBird-" namespace...
NRPTKeyPrefix string `json:",omitempty"`
```

and `readConfig` publishes them:

```go
func (c *Config) publishInstanceIdentity() {
	device.SetWintunGUID(c.WintunGUID)
	dns.SetNRPTKeyPrefix(c.NRPTKeyPrefix)
}
```

**The call site is the important decision.** It lives in `readConfig`, *not* in
`apply()` — `apply()` also runs against throwaway probe and baseline configs, and
writing process-wide state from a config that is only being inspected would leak
one profile's identity into the running one.

## Precedence

```
profile config field  >  NB_WINDOWS_GUID / NB_NRPT_KEY_PREFIX  >  built-in default
```

Config wins because configuration replaces the environment (the environment is
read in `init()`, the config after). Empty values are ignored at every level, so
the zero value of a config field is exactly upstream behaviour.

⚠️ **"Config wins" does not mean "config is enough."** See
[`0002-nrpt-prefix-timing.md`](0002-nrpt-prefix-timing.md): paths that run before
`readConfig` never see the config value, and for those the environment variable is
mandatory.

## What this does not solve

Nothing in this patch makes NetBird a portable application. A Windows service
registration, the scheduled task, the wintun kernel driver, the NRPT registry
entries and the wintun PnP device are all still system state. What the patch
achieves is that a *profile* fully describes an instance, so several can coexist.
