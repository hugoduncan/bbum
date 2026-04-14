❌ babashka bb.edn task keys must be symbols, not keywords.

`bb tasks` silently shows "No tasks found" when task keys are keywords
(e.g. `:ref-report`). babashka requires unquoted symbols (`ref-report`).

`clojure.edn/read-string` parses both fine — the bug is invisible until
you try to run tasks.

Fix: coerce at the bb.edn write boundary.
  splice: `(into {} (map (fn [[k v]] [(symbol (name k)) v]) task-map))`
  remove: `(apply dissoc tasks (map (comp symbol name) task-keys))`

Also affects incremental accumulators that build bb-tasks with keyword
keys — convert there too with `(symbol (name kw))`.
