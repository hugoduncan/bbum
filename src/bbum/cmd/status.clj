(ns bbum.cmd.status
  "Implementation of `bbum status`."
  (:require [bbum.config  :as config]
            [bbum.print   :as bprint]
            [bbum.source  :as source]
            [clojure.string :as str]))

;;; SHA display helpers

(defn- abbrev [sha]
  (if (and sha (>= (count sha) 7))
    (subs sha 0 7)
    (or sha "?")))

(defn- sha-display [coord]
  (case (config/coord-type coord)
    :local     "(local)"
    :git/sha   (abbrev (:git/sha coord))
    ;; Locks are always :local or :git/sha — other types shouldn't appear
    (str coord)))

;;; Per-task status resolution

(defn- resolve-status
  "Compute the status of a single installed task.
   Returns map: {:locked-sha :current-sha :status}.
   Re-resolves the source coord to detect drift."
  [task-rec source-coord]
  (let [lock         (:lock task-rec)
        lock-type    (config/coord-type lock)]
    (cond
      ;; Local path — always current
      (= :local lock-type)
      {:locked-sha  "(local)"
       :current-sha "(local)"
       :status      :local}

      ;; Pinned sha source — can never float
      (= :git/sha (config/coord-type source-coord))
      {:locked-sha  (abbrev (:git/sha lock))
       :current-sha (abbrev (:git/sha lock))
       :status      :pinned}

      ;; Floating source — re-resolve and compare
      :else
      (let [current-lock (source/resolve-coord source-coord)
            locked-sha   (:git/sha lock)
            current-sha  (:git/sha current-lock)
            status       (if (= locked-sha current-sha) :ok :outdated)]
        {:locked-sha  (abbrev locked-sha)
         :current-sha (abbrev current-sha)
         :status      status}))))

;;; Implicit task annotation

(defn- implicit-note [task-rec]
  (when (= :implicit (:install task-rec))
    (str "(implicit, required-by: "
         (str/join ", " (map name (:required-by task-rec)))
         ")")))

;;; Entry point

(defn run
  "bbum status — show installed tasks vs their current source resolution."
  [_args]
  (let [root      (config/project-root)
        manifest  (config/read-project-manifest root)
        global    (config/read-global-config)
        all-tasks (:tasks manifest {})
        sources   (merge (:sources manifest {})
                         (:sources global {}))]

    (if (empty? all-tasks)
      (println "No tasks installed.")
      (let [rows     (for [[task-kw task-rec] (sort-by key all-tasks)]
                       (let [source-kw    (:source task-rec)
                             source-coord (get sources source-kw)
                             {:keys [locked-sha current-sha status]}
                             (if source-coord
                               (resolve-status task-rec source-coord)
                               {:locked-sha  (sha-display (:lock task-rec))
                                :current-sha "?"
                                :status      :unknown})
                             note         (implicit-note task-rec)]
                         [(name task-kw)
                          (name source-kw)
                          locked-sha
                          current-sha
                          (name status)
                          (or note "")]))
            outdated? (some #(= "outdated" (nth % 4)) rows)]

        (bprint/print-table
         ["task" "source" "locked" "current" "status" "notes"]
         rows)

        (when outdated?
          (System/exit 1))))))
