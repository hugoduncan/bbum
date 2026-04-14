---
name: bbum-task-lib
description: Reference for constructing bbum-compatible task libraries. Use when creating, extending, or structuring a library of babashka tasks installable via bbum.
lambda: "λlib. bbum.edn → {tasks files deps} | installable"
---

# bbum Task Library Construction

A bbum task library is a repo (or local path) containing a `bbum.edn` manifest and Clojure source files that implement each task.

---

## Repository Layout

```
bbum.edn                          ← library manifest (required)
src/
  <org>/<lib>/<task_name>.clj     ← one namespace per task (convention)
```

Files under `src/` are installed into `.bbum/lib/` with the `src/` prefix stripped. Use a namespaced path to avoid collisions across libraries.

---

## `bbum.edn` Manifest

```clojure
{:lib <org>/<lib-name>            ; qualified library name (symbol-like string)

 :tasks
 {:<task-kw>
  {:doc     "One-line description shown by `bbum list`"
   :files   ["src/org/lib/task_name.clj"]   ; all files to install
   :depends [:<other-task-kw>]              ; optional — intra-library deps
   :task    {:doc      "Description shown in bb help"
             :requires ([org.lib.task-name :as task-name])
             :task     (task-name/run)}}}}
```

### Field reference

| Field | Required | Notes |
|-------|----------|-------|
| `:lib` | yes | Identifies the library in the project manifest |
| `:tasks` | yes | Map of task keyword → task entry |
| `:doc` (outer) | yes | Shown by `bbum list <source>` |
| `:files` | yes | Paths relative to repo root; all copied on install |
| `:depends` | no | Keywords of other tasks in this library; installed implicitly |
| `:task` | yes | Spliced verbatim into `bb.edn` under the task key |
| `:task :doc` | recommended | Shown by `bb help <task>` |
| `:task :requires` | yes | Babashka `:requires` vector for the task namespace |
| `:task :task` | yes | Expression evaluated when the task runs |

---

## Task Implementation

Each task is a Clojure namespace with a `run` entry point:

```clojure
(ns org.lib.task-name
  (:require [clojure.string :as str]))

(defn run
  "Brief docstring."
  []
  ;; access CLI args via *command-line-args*
  )
```

- Use `*command-line-args*` for CLI argument parsing.
- Call `(System/exit 1)` on failure.
- Keep each task in its own namespace/file.

---

## Dependencies Between Tasks

If task `:b` requires task `:a` to also be installed:

```clojure
:tasks
{:a {:doc "..." :files [...] :task {...}}
 :b {:doc "..." :files [...] :depends [:a] :task {...}}}
```

`bbum add <source> b` will install both `:b` (explicit) and `:a` (implicit).  
`bbum remove b` will warn about the orphaned implicit `:a`; use `--with-deps` to remove both.

---

## Multiple Files Per Task

List every file the task needs:

```clojure
:files ["src/org/lib/task_name.clj"
        "src/org/lib/shared_util.clj"]
```

Shared files across tasks are deduplicated at install time but are tracked per task in the manifest.

---

## Minimal Example

```clojure
;; bbum.edn
{:lib acme/bb-tasks

 :tasks
 {:lint
  {:doc   "Run clj-kondo on src/ and test/"
   :files ["src/acme/bb_tasks/lint.clj"]
   :task  {:doc      "Run clj-kondo linter"
           :requires ([acme.bb-tasks.lint :as lint])
           :task     (lint/run)}}}}
```

```clojure
;; src/acme/bb_tasks/lint.clj
(ns acme.bb-tasks.lint
  (:require [babashka.process :as proc]))

(defn run []
  (let [{:keys [exit]} (proc/shell "clj-kondo" "--lint" "src" "test")]
    (System/exit exit)))
```

---

## Checklist

- [ ] `bbum.edn` at repo root with `:lib` and `:tasks`
- [ ] Every file in `:files` exists at the listed path
- [ ] Each task namespace has a `(defn run [] ...)` 
- [ ] `:task :requires` matches the namespace in the source file
- [ ] `:doc` present at both the task level and inside `:task`
- [ ] Namespace paths are collision-safe (use org/lib prefix)
