# bbum — Implementation Plan

## 1. Project Scaffold

- `bb.edn` with `:paths`, `:deps`, `:tasks`
- `src/bbum/` namespace root
- bbin entry point (`bb.edn` `:bbin/bin` or dedicated script)
- `bbum.edn` — the library's own manifest (dogfooding)
- README stub

## 2. Config Layer

- Read/write `~/.bbum/config.edn` (global sources)
- Read/write `.bbum.edn` (project manifest: sources, tasks, locks)
- Read/write `bb.edn` (splice/remove `:tasks` entries, manage `:paths`)
- Schemas/specs for all file formats
- Locate project root (walk up from cwd looking for `bb.edn`)

## 3. Source Resolution

- Resolve a source name → coords (check per-project then global)
- Local path: confirm path exists, read `bbum.edn` from it
- Git branch: fetch remote head sha → lock
- Git tag: resolve tag to sha → lock
- Git sha: already pinned, use directly
- Fetch `bbum.edn` from a resolved source (local read or git fetch)

## 4. `bbum source` Commands

- `bbum source add [--global] <name> <coords>`
- `bbum source remove [--global] <name>`
- `bbum source list`

## 5. `bbum list <source>`

- Resolve source
- Fetch library `bbum.edn`
- Display available tasks with docs

## 6. `bbum add <source> <task> [<task> ...]`

- Resolve source to lock
- Fetch library `bbum.edn`
- Resolve task deps recursively (detect cycles)
- Pre-flight: check task name conflicts in `bb.edn :tasks`
- Pre-flight: check file conflicts in `.bbum/lib/`
- Copy files into `.bbum/lib/`
- Splice task entries into `bb.edn :tasks`
- Ensure `.bbum/lib` in `bb.edn :paths`
- Record tasks (explicit/implicit, required-by, lock) in `.bbum.edn`

## 7. `bbum remove <task> [--with-deps]`

- Look up task in `.bbum.edn`, error if implicit
- Identify orphaned implicit tasks (required-by becomes empty)
- Warn about orphans, or remove them if `--with-deps`
- Identify files safe to remove (not claimed by remaining tasks)
- Remove files from `.bbum/lib/`
- Remove task entries from `bb.edn :tasks`
- Update `.bbum.edn` (remove tasks, update `:required-by` on remaining implicits)

## 8. `bbum status`

- For each installed task, re-resolve source eagerly
- Compare current sha to locked sha
- Display table: task, source, locked sha, current sha, status
- Annotate implicit tasks with required-by
- Exit non-zero if any task is `outdated`

## 9. `bbum update [<task>]`

- Without arg: update all installed tasks
- With arg: update named task only
- Per task: re-resolve source → compare sha → if changed, re-copy files, update `bb.edn :tasks` entry if changed, update lock in `.bbum.edn`
- Pinned sha sources: skip (log as pinned)
- Local path sources: always re-copy
