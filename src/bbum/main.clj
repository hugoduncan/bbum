(ns bbum.main
  (:require [bbum.cmd.add    :as cmd-add]
            [bbum.cmd.list   :as cmd-list]
            [bbum.cmd.source :as cmd-source]))

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

(defn- run [args]
  (let [[cmd & rest-args] args]
    (case cmd
      "source" (cmd-source/run rest-args)
      "list"   (cmd-list/run rest-args)
      "add"    (cmd-add/run rest-args)
      "remove" (not-implemented "remove")
      "status" (not-implemented "status")
      "update" (not-implemented "update")
      (do (usage)
          (System/exit (if cmd 1 0))))))

(defn -main [& args]
  (try
    (run args)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (str "Error: " (ex-message e))))
      (System/exit 1))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "Unexpected error: " (.getMessage e))))
      (System/exit 1))))
