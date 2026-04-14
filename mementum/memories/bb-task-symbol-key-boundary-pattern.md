🔁 Pattern: babashka bb.edn uses symbol keys — every boundary that reads
or writes :tasks must normalise keyword↔symbol.

Affected sites found so far in bbum:
  bb-edn-splice-tasks   → coerce to symbol on write
  bb-edn-remove-tasks   → coerce to symbol for dissoc
  update-source-tasks!  → coerce to symbol in accumulator
  preflight!            → compare by (name ...) not by equality

Detection: `bb tasks` silently shows "No tasks found" OR conflict guard
silently no-ops. Both are invisible without tests.

Write a test that checks the actual key type after any bb.edn write:
  (is (every? symbol? (keys (:tasks (config/read-bb-edn root)))))
