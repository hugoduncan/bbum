(ns bbum.main)

;;; Command dispatch

(defn- usage []
  (println "bbum — Babashka Universal Module Manager")
  (println)
  (println "Usage: bbum <command> [options]")
  (println)
  (println "Commands:")
  (println "  source add [--global] <name> <coords>  Register a source")
  (println "  source remove [--global] <name>        Remove a source")
  (println "  source list                             List all sources")
  (println "  list <source>                           List tasks in a source")
  (println "  add <source> <task> [<task> ...]        Install tasks")
  (println "  remove [--with-deps] <task>             Remove a task")
  (println "  status                                  Show task status")
  (println "  update [<task>]                         Update tasks"))

(defn- not-implemented [cmd]
  (binding [*out* *err*]
    (println (str "bbum: '" cmd "' not yet implemented")))
  (System/exit 1))

(defn- run-source [args]
  (not-implemented (str "source " (first args))))

(defn- run-list [_args]
  (not-implemented "list"))

(defn- run-add [_args]
  (not-implemented "add"))

(defn- run-remove [_args]
  (not-implemented "remove"))

(defn- run-status [_args]
  (not-implemented "status"))

(defn- run-update [_args]
  (not-implemented "update"))

(defn -main [& args]
  (let [[cmd & rest-args] args]
    (case cmd
      "source" (run-source rest-args)
      "list"   (run-list rest-args)
      "add"    (run-add rest-args)
      "remove" (run-remove rest-args)
      "status" (run-status rest-args)
      "update" (run-update rest-args)
      (do (usage)
          (System/exit (if cmd 1 0))))))
