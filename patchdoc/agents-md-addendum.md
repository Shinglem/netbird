## Fork-local changes (this repository)

<!--
  This section is appended to AGENTS.md by patchdoc/apply-agents-md.ps1.
  It is kept as a separate file because AGENTS.md is a protected
  agent-instruction file that tooling refuses to edit directly.

  Everything below describes THIS FORK only. Upstream guidance above still
  applies in full; this section adds the local patch set on top of it.

  NOTE: relative links in this file are written as they must appear once the
  section is appended to the repository-root AGENTS.md, i.e. they resolve from
  the repository root, not from patchdoc/. A link checker run on this file
  in place will report them as broken.
-->

This repository is a fork of `netbirdio/netbird` carrying one small, opt-in
patch set that upstream does not have. **Read this before changing anything under
`client/iface/device/`, `client/internal/dns/`, or
`client/internal/profilemanager/`, and before building or deploying a client
binary.**

### What the patch set does

It makes two Windows settings per-profile instead of process-wide, so that
several NetBird instances can run on one Windows host at the same time, each with
its own wintun adapter and its own NRPT key namespace:

- `Config.WintunGUID` — the adapter GUID this profile's interface is created with
- `Config.NRPTKeyPrefix` — the namespace its NRPT rule keys use

Both are `omitempty`, and an empty value reproduces upstream behaviour exactly, so
an existing config is unaffected and the same binary still works as an ordinary
client. The commit message for the change says the environment variables are
"honoured as a fallback"; for the reasons below that wording is wrong.

### Rules that follow from it

1. **`NB_NRPT_KEY_PREFIX` must be exported, not only configured.** The config
   value is published by `readConfig`, which runs *after* the unclean-shutdown
   recovery path — and that path deletes NRPT rules filtered by the prefix. With
   only the config field set, it sees the built-in `NetBird-` and deletes the
   *other* instance's rules. Any launcher that sets `NB_STATE_DIR` must set this
   too.

2. **Publish process-wide settings from `readConfig`, never from `apply()`.**
   `apply()` also runs against throwaway probe and baseline configs; writing
   process-wide state from a config that is only being inspected leaks one
   profile's identity into the running one. When adding another such setting,
   check what runs *before* `readConfig` and decide what channel reaches it.

3. **Keep the non-Windows side building.** `SetWintunGUID` and
   `SetNRPTKeyPrefix` are defined on every platform (the latter as a `//go:build
   !windows` no-op) so callers need no build tags. Verify with
   `GOOS=linux` and `GOOS=darwin` builds, not just a Windows one.

4. **Do not convert the NRPT prefix back to a `const`.** `NRPTKeyPrefix` and the
   four paths derived from it have to be recomputed together whenever the prefix
   changes; splitting them across a const and a var is how they drift apart.

5. **Nothing here makes the client portable.** The service registration, the
   scheduled task, the wintun kernel driver, the NRPT registry entries and the
   wintun PnP device remain system state. Do not describe the result as a
   green or portable install.

### Do not upstream this without a discussion

The change touches client system integration (DNS, interface), which
[CONTRIBUTING.md](CONTRIBUTING.md#high-risk-areas) lists as a high-risk area, and
it changes a config file format. Per the STOP rules above, that needs an agreed
ticket first. The patch was written to be upstreamable — it reuses the existing
`CustomWindowsGUIDString` extension point rather than adding a new mechanism, and
is opt-in with zero default-behaviour change — but the discussion comes first.

### Where the detail lives

| Path | Content |
|---|---|
| [`patchdoc/`](patchdoc/README.md) | What the patch changes, file by file, and how to verify it |
| [`patchdoc/0002-nrpt-prefix-timing.md`](patchdoc/0002-nrpt-prefix-timing.md) | The startup-order defect and why the environment variable is mandatory |
| [`.ai/skill/`](.ai/skill/README.md) | Task-scoped skills — start with `netbird-multi-instance` |

### Building and verifying a client binary

```bash
cd client && CGO_ENABLED=0 go build .        # matches upstream's goreleaser config

go fmt ./...
go vet ./client/iface/device/... ./client/internal/dns/... ./client/internal/profilemanager/...

# the stubs must keep every platform building
GOOS=linux  GOARCH=amd64 CGO_ENABLED=0 go build ./client/...
GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build ./client/...
```

Before deploying a rebuilt binary, confirm the instance-identity symbols are
actually in it — a build that silently lost them still runs, and still claims the
default GUID and prefix:

```bash
grep -c WintunGUID <binary> && grep -c NRPTKeyPrefix <binary> && grep -c SetNRPTPrefix <binary>
```
