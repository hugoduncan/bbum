# bbum — Babashka Universal Module Manager

A package manager for babashka tasks. Installs tasks from libraries into projects,
tracking sources and versions so tasks can be updated or removed.

## Install

```sh
bbin install io.github.hugoduncan/bbum
```

## Usage

```sh
bbum source add [--global] <name> <coords>   # register a source
bbum source remove [--global] <name>         # remove a source
bbum source list                             # list all sources
bbum list <source>                           # list tasks in a source
bbum add <source> <task> [<task> ...]        # install tasks
bbum remove [--with-deps] <task>             # remove a task
bbum status                                  # show task status
bbum update [<task>]                         # update tasks
```

## Coords

A coord identifies where a task library lives. Coords are specified as
`key=value` pairs on the command line.

| Type | Example | Behaviour |
|------|---------|-----------|
| Local path | `local/path=../my-task-lib` | Read directly from the filesystem. Never locked. |
| Git branch | `git/url=https://github.com/org/lib git/branch=main` | Resolved to a sha at install time and locked. Updated on `bbum update`. |
| Git tag | `git/url=https://github.com/org/lib git/tag=v1.2.0` | Resolved to a sha at install time and locked. Updated if the tag moves. |
| Git sha | `git/url=https://github.com/org/lib git/sha=abc123def` | Pinned. Never updated automatically. |

Examples:

```sh
bbum source add my-lint   local/path=../my-task-lib
bbum source add org-tasks git/url=https://github.com/org/bb-tasks git/branch=main
bbum source add stable    git/url=https://github.com/org/bb-tasks git/tag=v1.2.0
bbum source add pinned    git/url=https://github.com/org/bb-tasks git/sha=abc123def456
```

See [SPEC.md](SPEC.md) for full documentation.
