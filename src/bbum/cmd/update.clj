(ns bbum.cmd.update
  "Implementation of `bbum update [<task>]`."
  (:require [babashka.fs    :as fs]
            [bbum.config    :as config]
            [bbum.source    :as source]
            [clojure.string :as str]))

;;; File copy helpers (shared with add, but update overwrites)

(defn- dest-relative-path [file-path]
  (if (str/starts-with? file-path "src/")
    (subs file-path 4)
    file-path))

(defn- install-path [root file-path]
  (str root "/.bbum/lib/" (dest-relative-path file-path)))

(defn- copy-task-files!
  "Copy (overwriting) all files for task-kw from src-dir into root."
  [root src-dir task-def]
  (doseq [file-path (:files task-def [])]
    (let [src  (str src-dir "/" file-path)
          dest (install-path root file-path)]
      (when (fs/exists? src)
        (fs/create-dirs (fs/parent dest))
        (fs/copy src dest {:replace-existing true})))))

;;; Needs-update? logic

(defn- needs-update?
  "True if this task should be re-fetched given its source coord type and new lock."
  [task-rec source-coord new-lock]
  (let [src-type (config/coord-type source-coord)]
    (case src-type
      :local   true                                          ; local always re-copies
      :git/sha false                                         ; pinned — never floats
      ;; Floating — update when sha has changed
      (not= (:git/sha (:lock task-rec)) (:git/sha new-lock)))))

;;; Process all tasks for a single source in one fetch

(defn- update-source-tasks!
  "Fetch source once, then update all tasks-to-update from it.
   Returns updated {:manifest-tasks :bb-task-entries}.
   :bb-task-entries is a map of installed-sym → task-def for z-splice-tasks.
   Uses config/lib-task-kw so aliased tasks are looked up by their library name."
  [root source-coord new-lock task-kws all-tasks]
  (source/with-source-dir source-coord
    (fn [src-dir]
      (let [lib-manifest (config/read-lib-manifest src-dir)]
        (reduce (fn [{:keys [manifest-tasks bb-task-entries]} installed-kw]
                  (let [task-rec (get manifest-tasks installed-kw)
                        lib-kw   (config/lib-task-kw installed-kw task-rec)
                        task-def (get-in lib-manifest [:tasks lib-kw])]
                    (when-not task-def
                      (throw (ex-info (str "Task no longer in library manifest: " (name lib-kw))
                                      {:installed installed-kw :lib-task lib-kw})))
                    ;; Re-copy files (overwrite)
                    (copy-task-files! root src-dir task-def)
                    {:manifest-tasks  (assoc-in manifest-tasks [installed-kw :lock] new-lock)
                     :bb-task-entries (assoc bb-task-entries installed-kw (:task task-def))}))
                {:manifest-tasks all-tasks :bb-task-entries {}}
                task-kws)))))

;;; Entry point

(defn run
  "bbum update [<task>] — update all or one installed task."
  [args]
  (let [task-filter (some-> (first args) keyword)
        root        (config/project-root)
        manifest    (config/read-project-manifest root)
        global      (config/read-global-config)
        all-tasks   (:tasks manifest {})
        all-sources (merge (:sources global {}) (:sources manifest {}))]

    (when (and task-filter (not (get all-tasks task-filter)))
      (throw (ex-info (str "Task not installed: " (name task-filter))
                      {:task task-filter})))

    ;; Determine candidate tasks (all, or just the requested one)
    (let [candidates (if task-filter
                       (select-keys all-tasks [task-filter])
                       all-tasks)]

      ;; Resolve new locks for all unique floating sources upfront
      (let [source-kws     (distinct (map :source (vals candidates)))
            new-locks      (into {}
                                 (keep (fn [src-kw]
                                         (let [coord (get all-sources src-kw)]
                                           (when (and coord
                                                      (not= :git/sha (config/coord-type coord)))
                                             [src-kw (source/resolve-coord coord)])))
                                       source-kws))

            ;; Group updatable tasks by source key
            by-source      (->> candidates
                                 (filter (fn [[task-kw task-rec]]
                                           (let [src-kw (keyword (:source task-rec))
                                                 coord  (get all-sources src-kw)
                                                 new-lock (get new-locks src-kw coord)]
                                             (and coord (needs-update? task-rec coord new-lock)))))
                                 (group-by (comp :source val)))]

        (if (empty? by-source)
          (println "All tasks are up to date.")

          ;; Process each source group with one fetch
          (let [final-state
                (reduce (fn [{:keys [manifest-tasks bb-task-entries]} [src-kw task-pairs]]
                          (let [task-kws  (mapv key task-pairs)
                                src-coord (get all-sources src-kw)
                                new-lock  (get new-locks src-kw src-coord)
                                result    (update-source-tasks!
                                           root src-coord new-lock task-kws manifest-tasks)]
                            {:manifest-tasks  (:manifest-tasks result)
                             :bb-task-entries (merge bb-task-entries (:bb-task-entries result))}))
                        {:manifest-tasks all-tasks :bb-task-entries {}}
                        by-source)]

            ;; Persist updated bb.edn via zipper — preserves task order and formatting
            (config/update-bb-edn! root
              (fn [z] (config/z-splice-tasks z (:bb-task-entries final-state))))
            (config/write-project-manifest root
              (assoc manifest :tasks (:manifest-tasks final-state)))

            ;; Report
            (doseq [[src-kw task-pairs] by-source
                    [task-kw _]        task-pairs]
              (println (str "Updated task: " (name task-kw))))))))))
