# bbum — Working Memory

Updated: 2026-04-13

## Project

**bbum** — Babashka Universal Module Manager  
A package manager for babashka tasks. Installs tasks from libraries into projects, tracking sources and versions.

Repo: `/Users/duncan/projects/hugoduncan/bb-task-lib/master`  
Git: 10 commits — spec, plan, and **full implementation**.

## Phase

✅ **Implementation complete** — all 9 plan steps done and committed.

## Source Layout

```
src/bbum/
  main.clj          — CLI entry point, command dispatch, error handler
  config.clj        — all EDN file I/O (global config, project manifest, bb.edn, lib manifest)
  source.clj        — coord resolution, git ls-remote, with-source-dir, lib manifest fetch
  print.clj         — column-aligned table printing
  cmd/
    source.clj      — source add/remove/list
    list.clj        — list tasks in a source
    add.clj         — install tasks (dep resolution, preflight, file copy, manifest write)
    remove.clj      — remove tasks (orphan detection, --with-deps, file cleanup)
    status.clj      — task status table (ok/outdated/pinned/local, exit 1 on outdated)
    update.clj      — re-resolve coords, overwrite files, update locks
```

## Key Design Decisions

- **Config**: `config.clj` owns all file I/O (read-edn/write-edn, project-root walk)
- **Source resolution**: `resolve-coord` for locking, `with-source-dir` for file access
- **Git**: shallow clone for branch/tag, fetch-by-sha for pinned (with full-clone fallback)
- **Add pre-flight**: all conflict checks (task names + files) before any write
- **Remove**: `:required-by` tracks which explicit tasks own each implicit; orphan detection is recursive
- **Update**: groups tasks by source → one clone per unique source; local sources always re-copy
- **bb.edn round-trip**: read with `clojure.edn/read-string`, write with `pprint` (loses comments — known limitation)

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

### Commands — All Implemented
`source add/remove/list` | `list <source>` | `add` | `remove [--with-deps]` | `status` | `update`

## --as flag (task name override on install)

`bbum add <source> <task> --as <name>` — installs the task under a custom name.

- Only valid when installing a single task (error otherwise)
- Alias applies only to the explicit task; deps keep their lib names
- `.bbum.edn` stores `:lib-task <original-kw>` on aliased records
- `config/lib-task-kw` helper: `(get task-rec :lib-task installed-kw)` — used by update
- `update` looks up `(lib-task-kw installed-kw task-rec)` in new lib manifest; preserves alias in bb.edn
- `status` shows `(lib: <original>)` in notes column
- `:required-by` in implicit tasks stores the installed name (alias) of the requiring task

## Bug Fixes Applied

- **bb.edn task keys must be symbols** — `bb-edn-splice-tasks` and `bb-edn-remove-tasks` now coerce keyword→symbol at the write boundary; `update-source-tasks!` also fixed. Symptom: `bb tasks` silently showed "No tasks found."
- **remove with --as alias didn't delete files** — `task-files` in `remove.clj` used installed name as lib manifest key; fixed to use `config/lib-task-kw`.

## Known Limitations / Future Work

- `bb.edn` writes lose comments/formatting (pprint round-trip)
- `bbum remove` leaves empty parent directories in `.bbum/lib/` after deleting the last file
- No `bbum update` "up to date" message for local sources (always re-copies per spec)
- Git sha-pinned source fetching falls back to full clone if server rejects fetch-by-sha
