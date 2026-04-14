# bbum — Babashka Universal Module Manager

A package manager for babashka tasks. Installs tasks from libraries into projects,
tracking sources and versions so tasks can be updated or removed.

---

## Concepts

### Library

A babashka project that exposes installable tasks via a `bbum.edn` manifest. A library
declares what tasks it offers, what files each task requires, and the exact `bb.edn`
task entry to splice in on install.

### Source

A named reference to a library. Sources can be registered globally (available to all
projects) or per-project (scoped to one project). Per-project sources take precedence
over global sources with the same name.

**Global sources** live in `~/.bbum/config.edn`. Personal convenience aliases for
libraries used across many projects. Not committed to version control.

**Per-project sources** live in `.bbum.edn`. Used for project-specific sources (e.g.
local paths during development) or to override a global alias for this project.

Regardless of how a source is named or where it is registered, `.bbum.edn` always
records the full resolved coordinates in `:lock` — never just an alias. The project
is always self-contained and reproducible without the global config.

Source coordinate types:

```edn
{:local/path "../my-task-lib"}                               ; local filesystem, no lock
{:git/url "https://github.com/org/lib" :git/branch "main"}  ; floating branch
{:git/url "https://github.com/org/lib" :git/tag "v1.2.0"}   ; floating tag
{:git/url "https://github.com/org/lib" :git/sha "abc123"}   ; pinned, never floats
```

Local path sources are always re-resolved on update (no sha to pin).
Git sources with branch or tag are resolved to a sha at install time and locked.
Git sources with sha are already pinned and never change on update.

### Task

A named, installable bb task declared by a library. A task specifies:
- its name (the key that appears in `bb.edn :tasks`)
- the files it requires (copied into the project)
- the exact `bb.edn` task map to splice in
- any other tasks from the same library it depends on

Task names are the library author's choice. There is no forced namespacing of task
names. The library author is responsible for choosing names that are unlikely to
collide. Clojure implementation namespaces inside the files **must** be fully
namespaced (e.g. `my-org.my-lib.lint`) to prevent classpath collisions.

### Project

A babashka project that uses bbum to manage installed tasks. Maintains a `.bbum.edn`
file recording configured sources and installed tasks with their resolved locks.

---

## File Formats

### Library manifest — `bbum.edn`

Lives at the root of a task library.

```edn
{:lib my-org/my-task-lib          ; qualified library name

 :tasks
 {:lint                           ; task name as it appears in bb.edn :tasks
  {:doc     "Run linting"
   :files   ["src/my_org/my_task_lib/lint.clj"   ; paths relative to library root
             "src/my_org/my_task_lib/common.clj"]
   :depends [:lint:check]         ; other tasks in this library to auto-install
   :task    {:doc      "Run linting"              ; verbatim bb.edn task map
             :requires ([my-org.my-task-lib.lint :as lint])
             :task     (lint/run)}}

  :lint:check
  {:doc   "Validate lint configuration"
   :files ["src/my_org/my_task_lib/lint.clj"
           "src/my_org/my_task_lib/common.clj"]
   :task  {:doc      "Validate lint configuration"
           :requires ([my-org.my-task-lib.lint :as lint])
           :task     (lint/check)}}}}
```

`:depends` is resolved recursively. Cycles are an error.

### Global config — `~/.bbum/config.edn`

Personal source aliases available to all projects. Managed by `bbum source add --global`.

```edn
{:sources
 {:org-tasks {:git/url "https://github.com/org/bb-tasks" :git/branch "main"}
  :stable    {:git/url "https://github.com/org/bb-tasks" :git/tag    "v1.2.0"}
  :my-lint   {:local/path "/home/user/projects/my-task-lib"}}}
```

### Project manifest — `.bbum.edn`

Lives at the project root. Managed by bbum; do not edit the `:tasks` map by hand.

```edn
{:sources
 {:my-lint   {:local/path "../my-task-lib"}
  :org-tasks {:git/url    "https://github.com/org/bb-tasks"
              :git/branch "main"}
  :stable    {:git/url    "https://github.com/org/bb-tasks"
              :git/tag    "v1.2.0"}}

 :tasks
 {:lint       {:source   :my-lint
               :lib      my-org/my-task-lib
               :install  :explicit
               :lock     {:local/path "../my-task-lib"}}
  :lint:check {:source      :my-lint
               :lib         my-org/my-task-lib
               :install     :implicit
               :required-by [:lint]
               :lock        {:local/path "../my-task-lib"}}
  :deploy     {:source  :org-tasks
               :lib     my-org/bb-tasks
               :install :explicit
               :lock    {:git/url "https://github.com/org/bb-tasks"
                         :git/sha "abc123def456"}}}}
```

The `:lock` value is the resolved coordinate at the time of install or last update.
For local path sources it mirrors the source coord. For git sources it always records
a `:git/sha`, regardless of whether the source used branch or tag.

### Explicit vs implicit installs

Tasks installed directly by the user (`bbum add <source> <task>`) are **explicit**.
Tasks pulled in automatically via `:depends` resolution are **implicit**.

Both are recorded in `.bbum.edn` with an `:install` key:

```edn
:tasks
{:lint       {:source :my-lint :lib my-org/my-task-lib :install :explicit :lock {...}}
 :lint:check {:source :my-lint :lib my-org/my-task-lib :install :implicit
              :required-by [:lint]          ; which explicit task(s) pulled this in
              :lock   {...}}}
```

`:required-by` is a list — an implicit task may be depended on by multiple tasks.
When a task is added explicitly and it matches an already-installed implicit task,
that task is upgraded to `:explicit` (the user has now claimed it directly).

### File destination

Files are copied into the project under `.bbum/lib/`, preserving their path as
declared in `:files`. This directory is the complete bbum-managed file space —
clearly separated from hand-written project files.

```
Library file:   src/my_org/my_task_lib/lint.clj
Installed to:   .bbum/lib/my_org/my_task_lib/lint.clj
Namespace:      my-org.my-task-lib.lint
```

`.bbum/lib/` should be committed to version control — it is the resolved, installed
state of the project's task dependencies.

bbum adds `.bbum/lib` to `bb.edn :paths` once on first install if not already
present. This is the only modification bbum makes to `bb.edn :paths`.

---

## Commands

### `bbum source add [--global] <name> <coords>`

Register a source. Without `--global`, adds to the project's `.bbum.edn`. With
`--global`, adds to `~/.bbum/config.edn`. Does not resolve or fetch anything.

```
bbum source add my-lint local/path=../my-task-lib
bbum source add org-tasks git/url=https://github.com/org/bb-tasks git/branch=main
bbum source add --global stable git/url=https://github.com/org/bb-tasks git/tag=v1.2.0
```

### `bbum source remove [--global] <name>`

Remove a source. Without `--global`, removes from `.bbum.edn`. With `--global`,
removes from `~/.bbum/config.edn`. Errors if any tasks are currently installed from
that source (checked against the project's `.bbum.edn` only — global sources may
still be in use in other projects).

### `bbum source list`

List all sources visible to the current project: per-project sources first, then
global sources, with a note indicating origin and which global sources are shadowed
by a per-project source of the same name.

```
name        origin            coords
──────────  ────────────────  ─────────────────────────────────────────────────
my-lint     project           local/path=../my-task-lib
org-tasks   project           git/url=https://github.com/org/bb-tasks  branch=main
org-tasks   global (shadowed) git/url=https://github.com/org/bb-tasks  tag=v1.2.0
stable      global            git/url=https://github.com/org/bb-tasks  tag=v1.1.0
```

### `bbum list <source>`

Fetch the library manifest from `<source>` and display available tasks with docs.
Does not install anything. Resolves the source to get the current manifest.

### `bbum add <source> <task>`

Install a task from a source into the current project.

Procedure:
1. Resolve source to sha (if floating git) or confirm path exists (if local).
2. Fetch library `bbum.edn` from resolved source.
3. Resolve task dependencies recursively — build full set of tasks and files to install.
4. Check all task names: if any already exist in `bb.edn :tasks`, error and stop.
5. Check all files: if any destination file already exists in the project, error and stop.
6. Nothing has been modified yet. Only now proceed.
7. Copy all files into `.bbum/lib/`.
8. Splice all task entries into `bb.edn :tasks`.
9. Ensure required paths are in `bb.edn :paths`.
10. Record all installed tasks and the resolved lock in `.bbum.edn`.

Steps 4–5 are pre-flight checks. bbum does not partially install — it is all or nothing.

### `bbum remove <task>`

Remove an explicitly installed task from the project.

Procedure:
1. Look up task in `.bbum.edn`. Error if not found or if task is `:implicit` (implicit
   tasks are managed by bbum, not removed directly — remove the explicit task that
   requires them).
2. Identify implicit tasks whose `:required-by` list becomes empty after this removal.
   These are now orphaned. Warn the user listing the orphaned tasks by name.
   Do not remove them automatically — the user must run `bbum remove <task>` on each,
   or `bbum remove --with-deps <task>` to remove them in one step.
3. Identify files owned by this task (and any deps being removed). A file is only
   removed if no remaining installed task declares it in its `:files`.
4. Remove files from `.bbum/lib/`.
5. Remove task entry from `bb.edn :tasks`.
6. Remove task record from `.bbum.edn`. Update `:required-by` on any remaining
   implicit tasks that listed this task.

`bbum remove --with-deps <task>` removes the task and all orphaned implicit tasks
in one step, warning the user what will be removed before proceeding.

### `bbum status`

Show the current state of all installed tasks relative to their sources.

For each installed task, re-resolves the source coordinate and compares to the lock:

```
task        source      locked-sha   current-sha  status
──────────  ──────────  ───────────  ───────────  ──────
lint        my-lint     (local)      (local)      ok
lint:check  my-lint     (local)      (local)      ok  (implicit, required-by: lint)
deploy      org-tasks   abc123       def456       outdated
fmt         stable      v1.2.0→aaa   v1.2.0→aaa   ok
```

Statuses:
- `ok` — locked sha matches current resolution
- `outdated` — source has moved (branch advanced, tag re-pointed)
- `pinned` — source is a bare sha, cannot float
- `local` — local path source, always considered current

Implicit tasks are annotated with `(implicit, required-by: <task>)`.

Exits with a non-zero code if any task is `outdated`, suitable for use in CI.

### `bbum update [task]`

Update installed tasks to the latest resolution of their floating source coordinates.

Without `[task]`: updates all installed tasks.
With `[task]`: updates only that task (and re-copies its files).

Procedure per task:
1. Re-resolve the source coordinate (re-fetch branch head sha, re-check tag sha, re-stat local path).
2. If resolved sha matches the locked sha (or local path is unchanged), skip — no-op.
3. If sha has changed: fetch new library `bbum.edn`, re-copy all files unconditionally (overwrite), update lock in `.bbum.edn`.
4. If the task's entry in `bb.edn :tasks` has changed in the new manifest, update it.

Tasks installed with a pinned `:git/sha` source are never updated (sha cannot float).

---

## Conflict Rules

**Task name conflict:** A task name already present in `bb.edn :tasks` when `bbum add`
is run — whether installed by bbum or manually written — is an error. bbum will not
overwrite it.

**File conflict:** A destination file already present in the project when `bbum add`
is run — whether installed by bbum or manually created — is an error. bbum will not
overwrite it during install (only during update).

Both checks run before any modification. Either failure aborts the entire install.

---

## bbin Integration

bbum is itself a bb script installable via bbin:

```
bbin install io.github.hugoduncan/bbum          ; from github
bbin install --local /path/to/bbum              ; local dev
```

Once installed, `bbum` is available as a command in any project.

---

## Decisions

- `.bbum/lib/` is committed to version control. The project is self-contained —
  clone and go, no bbum required to run already-installed tasks.
- `bbum add` accepts multiple tasks: `bbum add <source> <task> [<task> ...]`
  All tasks are resolved and pre-flight checked together before any modification.
- `bbum status` always fetches eagerly from the source. Accurate over fast.
