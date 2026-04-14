(ns bbum.cmd.list
  "Implementation of `bbum list <source>`."
  (:require [bbum.config :as config]
            [bbum.print  :as bprint]
            [bbum.source :as source]
            [clojure.string :as str]))

;;; list

(defn run
  "bbum list <source>"
  [args]
  (let [source-name (first args)]
    (when-not source-name
      (throw (ex-info "Usage: bbum list <source>" {})))
    (let [source-kw (keyword source-name)
          global    (config/read-global-config)
          manifest  (config/read-project-manifest)
          [coord _] (source/resolve-source-name source-kw manifest global)
          lib       (source/read-lib-manifest coord)
          tasks     (:tasks lib {})]
      (println (str "Library: " (:lib lib)))
      (println)
      (if (empty? tasks)
        (println "No tasks available.")
        (let [rows (for [[task-kw task-def] (sort-by key tasks)]
                     (let [deps (:depends task-def)]
                       [(name task-kw)
                        (or (:doc task-def) "")
                        (if (seq deps)
                          (str/join ", " (map name deps))
                          "")]))]
          (bprint/print-table ["task" "doc" "depends"] rows))))))
