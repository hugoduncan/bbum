(ns bbum.cmd.remove
  "Implementation of `bbum remove [--with-deps] <task>`."
  (:require [babashka.fs    :as fs]
            [bbum.config    :as config]
            [bbum.source    :as source]
            [clojure.string :as str]))

;;; Orphan detection

(defn- orphaned-implicits
  "Return the set of implicit task keys that become fully orphaned when
   removing-set is removed from all-tasks."
  [removing-set all-tasks]
  (into #{}
        (keep (fn [[kw {:keys [install required-by]}]]
                (when (and (= :implicit install)
                           (not (removing-set kw))
                           (seq required-by)
                           (every? removing-set required-by))
                  kw))
              all-tasks)))

(defn- collect-removals
  "Expand removing-set to include all transitively orphaned implicit tasks."
  [initial-set all-tasks]
  (loop [removing initial-set]
    (let [newly-orphaned (orphaned-implicits removing all-tasks)]
      (if (empty? newly-orphaned)
        removing
        (recur (into removing newly-orphaned))))))

;;; Library manifests (fetched once per unique lock coord)

(defn- fetch-lib-manifests
  "Fetch library manifests for all unique lock coords in task-records.
   Returns map of lock-coord → lib-manifest."
  [task-records]
  (let [locks (distinct (map :lock (vals task-records)))]
    (into {} (map (fn [lock] [lock (source/read-lib-manifest lock)]) locks))))

(defn- task-files
  "Return the :files list for task-kw from the appropriate lib manifest."
  [lib-manifests task-record task-kw]
  (get-in lib-manifests [(:lock task-record) :tasks task-kw :files] []))

;;; File safety

(defn- safe-to-remove-files
  "Files declared by removing-set that are not also used by any kept task.
   Returns a seq of relative file paths."
  [removing-set all-tasks lib-manifests]
  (let [keeping-set   (into #{} (remove removing-set (keys all-tasks)))
        keeping-files (into #{} (mapcat #(task-files lib-manifests (get all-tasks %) %)
                                        keeping-set))
        removing-files (mapcat #(task-files lib-manifests (get all-tasks %) %)
                               removing-set)]
    (remove keeping-files removing-files)))

;;; Manifest update helpers

(defn- update-required-by
  "Remove removed-kws from all :required-by lists in remaining-tasks."
  [remaining-tasks removed-kws]
  (let [removed-set (set removed-kws)]
    (into {}
          (map (fn [[kw rec]]
                 [kw (update rec :required-by #(vec (remove removed-set %)))])
               remaining-tasks))))

;;; Entry point

(defn run
  "bbum remove [--with-deps] <task>"
  [args]
  (let [with-deps? (= "--with-deps" (first args))
        rest-args  (if with-deps? (rest args) args)
        task-name  (first rest-args)]
    (when-not task-name
      (throw (ex-info "Usage: bbum remove [--with-deps] <task>" {})))

    (let [root      (config/project-root)
          task-kw   (keyword task-name)
          manifest  (config/read-project-manifest root)
          bb-edn    (config/read-bb-edn root)
          all-tasks (:tasks manifest {})]

      ;; Task must exist and be explicit
      (let [task-rec (get all-tasks task-kw)]
        (when-not task-rec
          (throw (ex-info (str "Task not installed: " task-name) {:task task-kw})))
        (when (= :implicit (:install task-rec))
          (throw (ex-info
                  (str "Cannot directly remove implicit task '" task-name
                       "'. Remove the explicit task(s) that require it: "
                       (str/join ", " (map name (:required-by task-rec))))
                  {:task task-kw :required-by (:required-by task-rec)}))))

      ;; Determine full removal set
      (let [initial-orphans (orphaned-implicits #{task-kw} all-tasks)]
        (if (and (seq initial-orphans) (not with-deps?))
          ;; Warn and stop — let user decide
          (do (println (str "Warning: removing '" task-name
                            "' would orphan implicit tasks:"))
              (doseq [kw initial-orphans]
                (println (str "  " (name kw))))
              (println "Run with --with-deps to remove them, or remove each manually."))

          ;; Proceed with removal
          (let [removing-set  (collect-removals #{task-kw} all-tasks)
                lib-manifests (fetch-lib-manifests (select-keys all-tasks removing-set))
                files-to-rm   (safe-to-remove-files removing-set all-tasks lib-manifests)]

            ;; Remove files
            (doseq [file-path files-to-rm]
              (let [dest (str root "/.bbum/lib/"
                              (if (str/starts-with? file-path "src/")
                                (subs file-path 4)
                                file-path))]
                (when (fs/exists? dest)
                  (fs/delete dest))))

            ;; Update bb.edn — remove task entries
            (config/write-bb-edn root
              (config/bb-edn-remove-tasks bb-edn removing-set))

            ;; Update .bbum.edn — remove tasks and clean up required-by
            (let [remaining (apply dissoc all-tasks removing-set)
                  updated   (update-required-by remaining removing-set)
                  new-manifest (assoc manifest :tasks updated)]
              (config/write-project-manifest root new-manifest))

            ;; Report
            (doseq [kw removing-set]
              (let [install (:install (get all-tasks kw))]
                (println (str "Removed " (name install) " task: " (name kw)))))))))))
