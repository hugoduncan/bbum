# bbum — Working Memory

Updated: 2026-04-13

## Project

**bbum** — Babashka Universal Module Manager  
A package manager for babashka tasks. Installs tasks from libraries into projects, tracking sources and versions.

Repo: `/Users/duncan/projects/hugoduncan/bb-task-lib/master`  
Git: 2 commits — spec + plan only. **No implementation code yet.**

## Phase

🎯 **Pre-implementation** — spec and plan are complete, nothing built yet.

Next logical step: scaffold the project (bb.edn, src/bbum/, bbin entry point).

## Key Files

| File | Purpose |
|------|---------|
| `SPEC.md` | Full spec: concepts, file formats, commands, conflict rules |
| `PLAN.md` | 9-step implementation plan |

## Architecture (from spec)

### Core Concepts
- **Library** — exposes tasks via `bbum.edn` manifest
- **Source** — named reference to a library (global `~/.bbum/config.edn` or per-project `.bbum.edn`)
- **Task** — named, installable bb task with files + bb.edn entry
- **Project** — consumer; maintains `.bbum.edn` with sources + installed tasks + locks

### Source Coord Types
```edn
{:local/path "../my-task-lib"}                               ; never locked
{:git/url "..." :git/branch "main"}                          ; resolved → sha at install
{:git/url "..." :git/tag "v1.2.0"}                           ; resolved → sha at install
{:git/url "..." :git/sha "abc123"}                           ; pinned, never floats
```

### File Layout
- `.bbum.edn` — project manifest (sources, tasks, locks)
- `~/.bbum/config.edn` — global sources
- `.bbum/lib/` — installed task files (committed to VCS)
- `bbum.edn` — library manifest (in a library project)

### Commands
`source add/remove/list` | `list <source>` | `add` | `remove [--with-deps]` | `status` | `update`

## Implementation Plan (PLAN.md)
1. Project scaffold
2. Config layer (read/write all file formats, schemas)
3. Source resolution (local / git branch / tag / sha)
4. `bbum source` commands
5. `bbum list`
6. `bbum add`
7. `bbum remove`
8. `bbum status`
9. `bbum update`

## Notes
- All-or-nothing install (pre-flight before any writes)
- Explicit vs implicit tasks tracked in `.bbum.edn`
- bbin installable: `bbin install io.github.hugoduncan/bbum`
- `.bbum/lib/` is committed — clone-and-go without bbum
