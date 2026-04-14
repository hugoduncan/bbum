(ns bbum.cmd.source
  "Implementation of the `bbum source` subcommands."
  (:require [bbum.config :as config]
            [bbum.print  :as bprint]
            [clojure.string :as str]))

;;; Coord parsing from CLI key=value args

(defn- parse-coord-arg
  "Parse a single 'key=value' string into a [keyword value] pair.
   Keys like 'git/url' become namespaced keywords like :git/url."
  [s]
  (let [idx (.indexOf s "=")]
    (when (neg? idx)
      (throw (ex-info (str "Invalid coord argument (expected key=value): " s)
                      {:arg s})))
    [(keyword (subs s 0 idx)) (subs s (inc idx))]))

(defn- parse-coord-args
  "Parse a seq of 'key=value' strings into a coord map."
  [args]
  (when (empty? args)
    (throw (ex-info "No coord arguments provided" {})))
  (into {} (map parse-coord-arg args)))

;;; Coord display

(defn- display-coord [coord]
  (case (config/coord-type coord)
    :local      (str "local/path=" (:local/path coord))
    :git/sha    (str "git/url=" (:git/url coord) "  sha=" (:git/sha coord))
    :git/branch (str "git/url=" (:git/url coord) "  branch=" (:git/branch coord))
    :git/tag    (str "git/url=" (:git/url coord) "  tag=" (:git/tag coord))))

;;; source add

(defn- cmd-add
  "bbum source add [--global] <name> <coord-kv ...>"
  [args]
  (let [global?    (= "--global" (first args))
        rest-args  (if global? (rest args) args)
        name-str   (first rest-args)
        coord-args (rest rest-args)]
    (when-not name-str
      (throw (ex-info "Usage: bbum source add [--global] <name> <key=value ...>" {})))
    (let [name-kw (keyword name-str)
          coord   (parse-coord-args coord-args)]
      (if global?
        (do (config/write-global-config
             (assoc-in (config/read-global-config) [:sources name-kw] coord))
            (println (str "Added global source: " name-str)))
        (do (config/write-project-manifest
             (assoc-in (config/read-project-manifest) [:sources name-kw] coord))
            (println (str "Added project source: " name-str)))))))

;;; source remove

(defn- cmd-remove
  "bbum source remove [--global] <name>"
  [args]
  (let [global?   (= "--global" (first args))
        rest-args (if global? (rest args) args)
        name-str  (first rest-args)]
    (when-not name-str
      (throw (ex-info "Usage: bbum source remove [--global] <name>" {})))
    (let [name-kw (keyword name-str)]
      (if global?
        (do (config/write-global-config
             (update (config/read-global-config) :sources dissoc name-kw))
            (println (str "Removed global source: " name-str)))
        (let [manifest     (config/read-project-manifest)
              tasks-from   (filter #(= name-kw (:source (val %))) (:tasks manifest))]
          (when (seq tasks-from)
            (throw (ex-info
                    (str "Cannot remove source '" name-str
                         "' — tasks are installed from it: "
                         (str/join ", " (map (comp str key) tasks-from)))
                    {:source name-kw :tasks (mapv key tasks-from)})))
          (config/write-project-manifest
           (update manifest :sources dissoc name-kw))
          (println (str "Removed project source: " name-str)))))))

;;; source list

(defn- cmd-list
  "bbum source list"
  [_args]
  (let [global   (config/read-global-config)
        manifest (config/read-project-manifest)
        sources  (config/effective-sources global manifest)]
    (if (empty? sources)
      (println "No sources configured.")
      (let [rows (for [[name-kw {:keys [coord origin shadowed?]}]
                       (sort-by (comp str key) sources)]
                   [(name name-kw)
                    (str (name origin)
                         (when shadowed? " (shadowed)"))
                    (display-coord coord)])]
        (bprint/print-table ["name" "origin" "coords"] rows)))))

;;; Dispatch

(defn run
  "Dispatch `bbum source <subcmd> [args...]`."
  [args]
  (let [[subcmd & rest-args] args]
    (case subcmd
      "add"    (cmd-add rest-args)
      "remove" (cmd-remove rest-args)
      "list"   (cmd-list rest-args)
      (throw (ex-info (str "Unknown source subcommand: " subcmd
                           "\nUsage: bbum source add|remove|list [args...]")
                      {:subcmd subcmd})))))
