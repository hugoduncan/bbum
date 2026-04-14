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

See [SPEC.md](SPEC.md) for full documentation.
