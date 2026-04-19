# bbum — A task package manager for babashka

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
| Git branch | `git/url=https://github.com/org/lib git/branch=master` | Resolved to a sha at install time and locked. Updated on `bbum update`. |
| Git tag | `git/url=https://github.com/org/lib git/tag=v1.2.0` | Resolved to a sha at install time and locked. Updated if the tag moves. |
| Git sha | `git/url=https://github.com/org/lib git/sha=abc123def` | Pinned. Never updated automatically. |

Examples:

```sh
bbum source add my-lint   local/path=../my-task-lib
bbum source add org-tasks git/url=https://github.com/org/bb-tasks git/branch=master
bbum source add stable    git/url=https://github.com/org/bb-tasks git/tag=v1.2.0
bbum source add pinned    git/url=https://github.com/org/bb-tasks git/sha=abc123def456
```

See [SPEC.md](SPEC.md) for full documentation.

## AI Skills

Agent skills for bbum and bbum task library authoring are available via the
[skills CLI](https://github.com/vercel-labs/skills):

```sh
# install all bbum skills
npx skills add hugoduncan/bbum

# install a specific skill
npx skills add hugoduncan/bbum --skill bbum
npx skills add hugoduncan/bbum --skill bbum-task-lib
```

| Skill | Description |
|-------|-------------|
| `bbum` | Using bbum — sources, install, remove, update, status |
| `bbum-task-lib` | Authoring bbum-compatible task libraries |

---

## Creating a task library

A task library is any directory (or git repo) that contains a `bbum.edn` manifest at
its root. bbum reads that manifest to know what tasks exist, what files they need, and
what to splice into the consumer project's `bb.edn`.

### Minimal example

```
my-task-lib/
  bbum.edn
  src/
    my_org/
      my_task_lib/
        greet.clj
```

**`src/my_org/my_task_lib/greet.clj`**

```clojure
(ns my-org.my-task-lib.greet)

(defn run []
  (println "Hello from my-task-lib!"))
```

**`bbum.edn`**

```edn
{:lib my-org/my-task-lib

 :tasks
 {:greet
  {:doc   "Print a greeting"
   :files ["src/my_org/my_task_lib/greet.clj"]
   :task  {:doc      "Print a greeting"
           :requires ([my-org.my-task-lib.greet :as greet])
           :task     (greet/run)}}}}
```

Install it:

```sh
bbum source add my-lib local/path=../my-task-lib
bbum add my-lib greet
```

### bbum.edn reference

```edn
{:lib my-org/my-task-lib        ; qualified library name (required)

 :tasks
 {:task-name                    ; key used in bb.edn :tasks
  {:doc     "Short description" ; shown by bbum list
   :files   ["src/my_org/my_task_lib/impl.clj"]   ; paths relative to library root
   :depends [:other-task]       ; other tasks in this lib to auto-install (optional)
   :task    { ... }}}}          ; verbatim bb.edn task map — see below
```

**`:task`** is copied verbatim into the consumer's `bb.edn :tasks` under the task
key. Any valid babashka task map works:

```edn
:task {:doc      "Run linting"
       :requires ([my-org.my-task-lib.lint :as lint])
       :task     (lint/run)}
```

### File paths and namespaces

Files are declared relative to the library root and installed into the consumer
project under `.bbum/lib/`. A leading `src/` component is stripped:

```
Declared:   src/my_org/my_task_lib/lint.clj
Installed:  .bbum/lib/my_org/my_task_lib/lint.clj
```

**Always use fully-qualified namespaces** in your implementation files — e.g.
`my-org.my-task-lib.lint`, not just `lint`. This prevents classpath collisions when
multiple libraries are installed in the same project.

### Task dependencies with `:depends`

Use `:depends` when one task requires another from the same library. bbum installs
dependent tasks automatically (as implicit installs) and removes them when no longer
needed.

```edn
{:lib my-org/my-task-lib

 :tasks
 {:lint
  {:doc     "Run linting"
   :files   ["src/my_org/my_task_lib/lint.clj"]
   :depends [:lint:config]
   :task    {:doc      "Run linting"
             :requires ([my-org.my-task-lib.lint :as lint])
             :task     (lint/run)}}

  :lint:config
  {:doc   "Validate lint config"
   :files ["src/my_org/my_task_lib/lint.clj"]
   :task  {:doc      "Validate lint config"
           :requires ([my-org.my-task-lib.lint :as lint])
           :task     (lint/check-config)}}}}
```

`bbum add my-lib lint` installs both `:lint` (explicit) and `:lint:config`
(implicit). Running `bbum remove lint` cleans up `:lint:config` too (or warns if
something else depends on it).

### Shared files across tasks

Multiple tasks may list the same file in `:files` — bbum deduplicates and only copies
it once. A shared file is only removed when the last task that declared it is removed.

### Publishing

Any git host works. Tag releases for stable coords; use a branch for rolling updates:

```sh
# consumer installs from a tag
bbum source add my-lib git/url=https://github.com/org/my-task-lib git/tag=v1.0.0

# consumer tracks master
bbum source add my-lib git/url=https://github.com/org/my-task-lib git/branch=master
```

No special publishing step is required — the `bbum.edn` at the repo root is all bbum
needs.
