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
  "Given a lib manifest, a seq of lib-task keywords to install, and an alias-map
   (lib-kw → installed-kw for explicitly aliased tasks), returns:
     {installed-kw {:install    :explicit|:implicit
                    :lib-task   lib-kw          ; only present when aliased
                    :required-by [installed-kw] ; implicit only
                    :task-def   {...}}}
   Deps are always installed under their lib names (no alias propagation to deps).
   Throws on unknown tasks, missing deps, or dependency cycles."
  [lib-manifest explicit-lib-kws alias-map]
  (let [lib-tasks (:tasks lib-manifest {})]
    ;; Validate all explicitly requested tasks exist in the library
    (doseq [kw explicit-lib-kws]
      (when-not (get lib-tasks kw)
        (throw (ex-info (str "Task not found in library: " (name kw))
                        {:task kw :available (keys lib-tasks)}))))

    (let [result (atom {})]
      ;; Register explicit tasks under their installed (possibly aliased) name
      (doseq [lib-kw explicit-lib-kws]
        (let [installed-kw (get alias-map lib-kw lib-kw)]
          (swap! result assoc installed-kw
                 (cond-> {:install :explicit :task-def (get lib-tasks lib-kw)}
                   (not= lib-kw installed-kw) (assoc :lib-task lib-kw)))))

      ;; DFS to collect implicit deps.
      ;; Deps use their lib name as both lib name and installed name.
      ;; requirer-installed-kw is the installed name of the requiring task.
      (letfn [(visit! [lib-kw requirer-installed-kw path]
                (when (contains? (set path) lib-kw)
                  (throw (ex-info
                          (str "Dependency cycle: "
                               (str/join " → " (map name (conj path lib-kw))))
                          {:cycle (conj path lib-kw)})))
                (let [task-def (get lib-tasks lib-kw)]
                  (when-not task-def
                    (throw (ex-info (str "Dependency not found in library: " (name lib-kw))
                                    {:task lib-kw :required-by requirer-installed-kw})))
                  (if-let [existing (get @result lib-kw)]
                    ;; Already tracked — extend required-by list if implicit
                    (when (= :implicit (:install existing))
                      (swap! result update-in [lib-kw :required-by] conj requirer-installed-kw))
                    ;; New dep — register as implicit, then recurse into its deps
                    (do (swap! result assoc lib-kw
                               {:install     :implicit
                                :required-by [requirer-installed-kw]
                                :task-def    task-def})
                        (doseq [dep-kw (:depends task-def [])]
                          (visit! dep-kw lib-kw (conj path lib-kw)))))))]

        ;; Start DFS from each explicit task, passing its installed name as the requirer
        (doseq [lib-kw explicit-lib-kws]
          (let [installed-kw (get alias-map lib-kw lib-kw)]
            (doseq [dep-kw (:depends (get lib-tasks lib-kw) [])]
              (visit! dep-kw installed-kw [lib-kw])))))

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
        (map (fn [[task-kw {:keys [install required-by lib-task]}]]
               [task-kw (cond-> {:source  source-kw
                                 :lib     lib-name
                                 :install install
                                 :lock    lock-coord}
                          (= :implicit install) (assoc :required-by required-by)
                          lib-task               (assoc :lib-task lib-task))])
             task-set)))

;;; CLI parsing

(defn- parse-args
  "Parse add args: <source> <task> [<task>...] [--as <name>]
   Returns {:source-name :task-names :alias-name}."
  [args]
  (let [[source-name & rest-args] args]
    (when-not source-name
      (throw (ex-info "Usage: bbum add <source> <task> [<task> ...] [--as <name>]" {})))
    (let [as-idx     (first (keep-indexed #(when (= "--as" %2) %1) rest-args))
          task-names (if as-idx (vec (take as-idx rest-args)) (vec rest-args))
          alias-name (when as-idx (nth rest-args (inc as-idx) nil))]
      (when (empty? task-names)
        (throw (ex-info "At least one task name is required." {})))
      (when (and as-idx (nil? alias-name))
        (throw (ex-info "--as requires a name argument." {})))
      (when (and alias-name (> (count task-names) 1))
        (throw (ex-info "--as can only be used when installing a single task." {})))
      {:source-name source-name
       :task-names  task-names
       :alias-name  alias-name})))

;;; Entry point

(defn run
  "bbum add <source> <task> [<task> ...] [--as <name>]"
  [args]
  (let [{:keys [source-name task-names alias-name]} (parse-args args)
        root             (config/project-root)
        source-kw        (keyword source-name)
        explicit-lib-kws (mapv keyword task-names)
        alias-map        (if alias-name
                           {(keyword (first task-names)) (keyword alias-name)}
                           {})
        global           (config/read-global-config)
        manifest         (config/read-project-manifest root)
        bb-edn           (config/read-bb-edn root)
        [coord _]        (source/resolve-source-name source-kw manifest global)]
    (source/with-source-dir coord
      (fn [src-dir]
        (let [lib-manifest (config/read-lib-manifest src-dir)
              task-set     (resolve-task-set lib-manifest explicit-lib-kws alias-map)
              lock-coord   (source/resolve-coord coord)]
          (preflight! task-set bb-edn root)
          (copy-files! task-set src-dir root)
          (let [task-entries (into {} (map (fn [[installed-kw {:keys [task-def]}]]
                                            [installed-kw (:task task-def)])
                                          task-set))
                new-bb-edn  (-> bb-edn
                                (config/bb-edn-splice-tasks task-entries)
                                (config/bb-edn-ensure-path ".bbum/lib"))]
            (config/write-bb-edn root new-bb-edn))
          (let [new-entries  (build-manifest-entries
                              task-set source-kw (:lib lib-manifest) lock-coord)
                new-manifest (update manifest :tasks merge new-entries)]
            (config/write-project-manifest root new-manifest))
          (doseq [[installed-kw {:keys [install lib-task]}] task-set]
            (println (str "Installed " (name install) " task: " (name installed-kw)
                          (when lib-task
                            (str " (from lib task: " (name lib-task) ")"))))))))))
