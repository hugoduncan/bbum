---
name: bbum
description: Reference for using bbum (Babashka Universal Module Manager) to manage babashka task libraries. Use when installing, removing, updating, or inspecting bbum tasks, or managing bbum sources.
lambda: "λtasks. bbum → {source add remove status update} | task-libs"
---

# bbum — Babashka Universal Module Manager

Installs babashka tasks from versioned task libraries into a project's `bb.edn`.

## Key Concepts

| Term | Meaning |
|------|---------|
| **source** | Named pointer to a task library (git repo or local path) |
| **task** | A named babashka task provided by a library |
| **explicit** | Task directly requested by the user |
| **implicit** | Task auto-installed as a dependency |
| **coord** | Address of a source: `key=value` pairs |
| **lock** | Resolved SHA snapshot recorded at install time |

**Config files:**
- `~/.bbum.edn` — global sources
- `.bbum.edn` — project manifest (installed tasks + project sources)
- `bb.edn` — babashka tasks (bbum splices tasks in here)
- `bbum.edn` — library manifest (lives in a task library repo)

---

## Commands

### Source Management

```bash
# Register a source (project-scoped)
bbum source add <name> git/url=<url> branch=<branch>
bbum source add <name> git/url=<url> sha=<sha>
bbum source add <name> git/url=<url> tag=<tag>
bbum source add <name> local/path=<path>

# Register globally (available in all projects)
bbum source add --global <name> git/url=<url> branch=<branch>

# Remove a source
bbum source remove <name>
bbum source remove --global <name>

# List all effective sources (global + project)
bbum source list
```

> Cannot remove a project source while tasks are installed from it — remove those tasks first.

---

### Browse a Source

```bash
bbum list <source>
```

Prints all available tasks and their docs from the library.

---

### Install Tasks

```bash
bbum add <source> <task> [<task> ...]

# Install under a different name (avoids bb.edn conflicts)
bbum add <source> <task> --as <name>
```

- Checks for task-name conflicts in `bb.edn` before writing anything.
- On conflict: remove the existing task first, or use `--as <name>`.
- Implicit deps are installed automatically.
- Files land in `.bbum/lib/`, path added to `bb.edn` classpath.

---

### Remove Tasks

```bash
bbum remove <task>

# Also remove implicit deps that become orphaned
bbum remove --with-deps <task>
```

- Only explicit tasks can be directly removed.
- Without `--with-deps`, warns if orphaned implicits would remain.

---

### Status

```bash
bbum status
```

Shows each installed task, its source, locked SHA, current SHA, and status:

| Status | Meaning |
|--------|---------|
| `ok` | Locked SHA matches current source HEAD |
| `outdated` | Source has moved ahead of locked SHA |
| `pinned` | Source is a fixed SHA — never floats |
| `local` | Source is a local path |
| `unknown` | Source no longer registered |

Exits non-zero if any task is `outdated`.

---

### Update Tasks

```bash
# Update all installed tasks
bbum update

# Update a single task
bbum update <task>
```

Re-fetches from source, overwrites files in `.bbum/lib/`, updates lock in `.bbum.edn`. Pinned (`git/sha`) sources are never updated.

---

## Common Workflows

**Add a new task library and install a task:**
```bash
bbum source add mylib git/url=https://github.com/org/bb-tasks branch=main
bbum list mylib
bbum add mylib some-task
```

**Install a task under an alternative name:**
```bash
bbum add mylib lint --as check
```

**Pin a source to a specific SHA:**
```bash
bbum source add mylib git/url=https://github.com/org/bb-tasks sha=abc1234
```

**Check for outdated tasks and update:**
```bash
bbum status     # exits 1 if outdated
bbum update
```

**Remove a task and its orphaned deps:**
```bash
bbum remove --with-deps some-task
```
