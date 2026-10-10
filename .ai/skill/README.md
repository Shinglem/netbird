# Agent skills

Reusable, task-scoped knowledge for AI agents working in this repository or
deploying what it builds.

These are **skills in the `SKILL.md` sense**: a trigger, the workflow, and the
pitfalls that cost real debugging time — not reference documentation. They are
written to be loaded when the task matches, and skipped otherwise.

| Skill | Load when |
|---|---|
| [`netbird-multi-instance/`](netbird-multi-instance/SKILL.md) | Running, or debugging, more than one NetBird instance on a single host |

Two conventions these skills follow, both deliberate:

- **`SKILL.md` carries the workflow and the traps.** Anything long, narrow or
  only occasionally relevant lives in `references/` and is loaded on demand, so
  the common path stays short.
- **Every non-obvious claim names its evidence** — a source file, a log line, a
  measured number. A skill that just asserts "do X" cannot be checked when the
  code moves out from under it.

Related material elsewhere in this repository:

- [`../../patchdoc/`](../../patchdoc/README.md) — what this fork changes and why
- [`../../AGENTS.md`](../../AGENTS.md) — repository-wide agent guidance
