(ns bbum.cmd.add
  "Implementation of `bbum add <source> <task> [<task> ...]`."
  (:require [babashka.fs     :as fs]
            [bbum.config     :as config]
            [bbum.source     :as source]
            [clojure.string  :as str]))

;;; Task dependency resolution

(defn- task-exists?
  "True if the task is declared in the library manifest."
  [lib-manifest task-kw]
  (contains? (:tasks lib-manifest) task-kw))

(defn- resolve-task-set
  "Given a lib manifest and a seq of explicitly requested task keywords,
   returns a map of all tasks to install:
     {task-kw {:install :explicit|:implicit :required-by [kw...] :task-def {...}}}
   Throws on unknown tasks, missing deps, or dependency cycles."
  [lib-manifest explicit-kws]
  (let [lib-tasks (:tasks lib-manifest {})]
    ;; Validate all explicitly requested tasks exist
    (doseq [kw explicit-kws]
      (when-not (get lib-tasks kw)
        (throw (ex-info (str "Task not found in library: " (name kw))
                        {:task kw :available (keys lib-tasks)}))))

    (let [result (atom {})]
      ;; Register explicit tasks first
      (doseq [kw explicit-kws]
        (swap! result assoc kw {:install :explicit :task-def (get lib-tasks kw)}))

      ;; DFS to collect implicit deps
      (letfn [(visit! [task-kw requirer-kw path]
                (when (contains? (set path) task-kw)
                  (throw (ex-info
                          (str "Dependency cycle: "
                               (str/join " → " (map name (conj path task-kw))))
                          {:cycle (conj path task-kw)})))
                (let [task-def (get lib-tasks task-kw)]
                  (when-not task-def
                    (throw (ex-info (str "Dependency not found in library: " (name task-kw))
                                    {:task task-kw :required-by requirer-kw})))
                  (if-let [existing (get @result task-kw)]
                    ;; Already tracked — extend required-by if implicit
                    (when (= :implicit (:install existing))
                      (swap! result update-in [task-kw :required-by] conj requirer-kw))
                    ;; New — register as implicit then recurse
                    (do (swap! result assoc task-kw
                               {:install     :implicit
                                :required-by [requirer-kw]
                                :task-def    task-def})
                        (doseq [dep-kw (:depends task-def [])]
                          (visit! dep-kw task-kw (conj path task-kw)))))))]

        ;; Traverse deps of all explicit tasks
        (doseq [kw explicit-kws]
          (doseq [dep-kw (:depends (get lib-tasks kw) [])]
            (visit! dep-kw kw [kw]))))

      @result)))

;;; File destination

(defn- dest-relative-path
  "Strip a leading 'src/' component from file-path, as per install convention."
  [file-path]
  (if (str/starts-with? file-path "src/")
    (subs file-path 4)
    file-path))

(defn- install-path
  "Absolute destination path for a task file inside a project."
  [root file-path]
  (str root "/.bbum/lib/" (dest-relative-path file-path)))

;;; Pre-flight checks (all checks before any writes)

(defn- all-files
  "Collect the deduplicated set of all :files across all tasks in task-set."
  [task-set]
  (->> task-set vals (mapcat (comp :files :task-def)) distinct))

(defn- preflight!
  "Run all pre-flight checks. Throws on the first conflict found.
   No filesystem modifications are made here."
  [task-set bb-edn root]
  ;; Task name conflicts
  (let [existing-tasks (set (keys (:tasks bb-edn {})))]
    (doseq [[task-kw _] task-set]
      (when (existing-tasks task-kw)
        (throw (ex-info (str "Task already exists in bb.edn: " (name task-kw)
                             " — remove it first or choose a different source.")
                        {:task task-kw})))))

  ;; File conflicts
  (doseq [file-path (all-files task-set)]
    (let [dest (install-path root file-path)]
      (when (fs/exists? dest)
        (throw (ex-info (str "File already exists: " dest
                             " — cannot install without overwriting.")
                        {:file file-path :dest dest}))))))

;;; Apply install (only called after preflight passes)

(defn- copy-files!
  "Copy all task files from src-dir into .bbum/lib/ under root."
  [task-set src-dir root]
  (doseq [file-path (all-files task-set)]
    (let [src  (str src-dir "/" file-path)
          dest (install-path root file-path)]
      (when-not (fs/exists? src)
        (throw (ex-info (str "Source file not found in library: " file-path)
                        {:src src})))
      (fs/create-dirs (fs/parent dest))
      (fs/copy src dest))))

(defn- build-manifest-entries
  "Build the .bbum.edn :tasks entries for all tasks in task-set."
  [task-set source-kw lib-name lock-coord]
  (into {}
        (map (fn [[task-kw {:keys [install required-by]}]]
               [task-kw (cond-> {:source  source-kw
                                 :lib     lib-name
                                 :install install
                                 :lock    lock-coord}
                          (= :implicit install) (assoc :required-by required-by))])
             task-set)))

;;; Entry point

(defn run
  "bbum add <source> <task> [<task> ...]"
  [args]
  (let [[source-name & task-names] args]
    (when-not source-name
      (throw (ex-info "Usage: bbum add <source> <task> [<task> ...]" {})))
    (when (empty? task-names)
      (throw (ex-info "At least one task name is required." {})))

    (let [root        (config/project-root)
          source-kw   (keyword source-name)
          task-kws    (mapv keyword task-names)
          global      (config/read-global-config)
          manifest    (config/read-project-manifest root)
          bb-edn      (config/read-bb-edn root)
          [coord _]   (source/resolve-source-name source-kw manifest global)]

      (source/with-source-dir coord
        (fn [src-dir]
          (let [lib-manifest (config/read-lib-manifest src-dir)
                task-set     (resolve-task-set lib-manifest task-kws)
                lock-coord   (source/resolve-coord coord)]

            ;; All checks before any writes
            (preflight! task-set bb-edn root)

            ;; Copy files
            (copy-files! task-set src-dir root)

            ;; Update bb.edn — splice tasks and ensure .bbum/lib path
            (let [task-entries (into {} (map (fn [[kw {:keys [task-def]}]]
                                               [kw (:task task-def)])
                                             task-set))
                  new-bb-edn   (-> bb-edn
                                   (config/bb-edn-splice-tasks task-entries)
                                   (config/bb-edn-ensure-path ".bbum/lib"))]
              (config/write-bb-edn root new-bb-edn))

            ;; Update .bbum.edn — record all installed tasks with locks
            (let [new-entries  (build-manifest-entries
                                task-set source-kw (:lib lib-manifest) lock-coord)
                  new-manifest (update manifest :tasks merge new-entries)]
              (config/write-project-manifest root new-manifest))

            ;; Report
            (doseq [[task-kw {:keys [install]}] task-set]
              (println (str "Installed " (name install) " task: " (name task-kw))))))))))
